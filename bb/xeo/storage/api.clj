(ns xeo.storage.api
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.core.async :as a :refer [<!! >!! chan close! pipeline]]
   [clojure.java.io :as io]
   [error :refer [error!]]
   [taoensso.timbre :as l]))

;;  #_{:clj-kondo/ignore [:deprecated-var]}
;; (l/set-level! :debug)


(def client (http/client (assoc-in http/default-client-opts [:ssl-context :insecure] true)))

(defn init-upload
  "Returns upload handle, which is a map {:keys [id urls multi-part-id]},
   where: 
   `id` is the key of the file in storage, 
   `urls` is a list of urls to upload the parts to and 
   `multi-part-id` is the id of the multi-part upload.
   
    Arguments:
      `file-name` - the name of the file to upload,
      `nr-of-parts` - the number of parts the file is split into,
      `opts` - a map of:
        `:storage-url` - the url of the xeoStorage
         `:token` - the token to authenticate with the xeoStorage
   "
  [file-name nr-of-parts {:keys [storage-url token]}]
  (l/debug "done preparing upload")
  (try
    (let [resp (http/post (str storage-url "file")
                          {:client client
                           :headers {"Authorization" (str "Bearer " token)
                                     :content-type "application/json"}
                           :body (json/encode {:name file-name
                                               :parts nr-of-parts})})
          body (json/parse-string (:body resp))
          id (get body "id")
          multi-part-id (get body "uploadId")
          parts (get body "parts")
          urls (mapv #(get % "uploadUrl") parts)]
      {:id id :urls urls :multi-part-id multi-part-id})
    (catch Exception e
      (error! "Error when creating pre-assign urls"
              {:file-name file-name
               :category :fault} e))))


(comment
  (let [file-name "test.txt"
        nr-of-parts 3
        storage-url "https://storage.xeovision.io/"
        token (System/getenv "XEO_TOKEN")]
    (init-upload file-name nr-of-parts {:storage-url storage-url
                                        :token token}))
  ;; 
  )

(defn complete-multipart-upload 
  "Complete the multipart upload
    Arguments:
      `id` - the key of the file in storage
      `upload-id` - the id of the multi-part upload
      `parts` - a list of parts, where each part is a vector [part-nr etag]
      `opts` - a map of:
        `:storage-url` - the url of the xeoStorage
        `:token` - the token to authenticate with the xeoStorage
   "
  [id upload-id parts {:keys [storage-url token]}]
   (let [body {:uploadId upload-id
              "parts" (mapv (fn [[part-nr etag]]
                              {:partNumber part-nr
                               :etag etag})
                            parts)}
        _ (l/debug "Completing multipart upload with parts:" body)
        response (http/post (str storage-url "file/" id "/complete")  {:client client
                                                                       :headers {"Authorization" (str "Bearer " token)
                                                                                 :content-type "application/json"}
                                                                       :body (json/generate-string body)})]
    (if (= 201 (:status response))
      (l/debug "Multipart upload completed successfully.")
      (l/debug "Failed to complete multipart upload:" (:status response) (:body response)))
    response))



(defn- process-chunk [[chunk-nr url chunk]]
  ;; Function to process each chunk by sending it to the given URL 
  (try
    (let [response (http/put url {:client client
                                  :body chunk
                                  :headers {"Content-Type" "application/octet-stream"}})]
      ;; Return the HTTP status code or any other relevant information
      (->
       (:headers response)
       (get "etag")
       (#(conj [(+ chunk-nr 1)] %))))
    (catch Exception e
      (println "Error uploading chunk:" (.getMessage e))
      (str "For url: " url " " e))))

(defn- upload-file-in-chunks [file {:keys [storage-url token]}]
  (let [chunk-size (* 5 1024 1024) ; 5MB in bytes 
        total-size (.length file)
        num-chunks (int (Math/ceil (/ total-size (double chunk-size))))
        _ (l/debug (format "Uploading %s size %d in %d chunks of %d bytes each"
                           (.getName file) total-size num-chunks chunk-size))
        {:keys [id urls multi-part-id]} (init-upload (.getName file)  num-chunks
                                           {:storage-url storage-url
                                            :token token})
        input-stream (io/input-stream file)
        _ (l/debug "Starting upload... number of chunks:" num-chunks)
        task-chan (chan 4) ; Buffer size of 10 to limit in-memory chunks
        result-chan (chan)
        progress (atom 0)
        etags (atom [])
        ]
    ;; Producer: Read the file and put chunks into the channel
    (future
      (try
        (loop [chunk-nr 0]
          (let [buffer (byte-array chunk-size)
                bytes-read (.read input-stream buffer)]
            (l/debug (format "Read %d bytes from file for chunk %d" bytes-read chunk-nr))
            (if (pos? bytes-read)
              (let [chunk (if (= bytes-read chunk-size)
                            buffer
                            (java.util.Arrays/copyOf buffer bytes-read))]
                ;; Put [url chunk] into the channel
                ;; (println (format "Put chunk %d into channel with url %s" chunk-nr (get urls chunk-nr)))
                (>!! task-chan [chunk-nr (get urls chunk-nr) chunk])
                (recur (inc chunk-nr)))
              (do
                ;; End of file reached
                (.close input-stream)
                (close! task-chan)))))
        (catch Exception e
          (println "Error reading file:" (.getMessage e))
          (.close input-stream)
          (close! task-chan))))
    ;; Consumer: Process chunks with limited concurrency
    (pipeline 4 ; Limit to 4 concurrent threads
              result-chan
              (map (fn [[chunk-nr url chunk]]
                     (l/debug "Processing chunk " url)
                     (process-chunk [chunk-nr url chunk])))
              task-chan)
    ;; Result Handler: Consume results from the result channel
    (loop []
      (when-let [result (<!! result-chan)]
        ;; Handle result if needed
        ;; (println "Chunk upload result:" result)
        (swap! progress inc)
        (l/debug (format "Progress: %.2f%% (%d/%d)" (* 100.0 (/ @progress num-chunks)) @progress num-chunks))
        (swap! etags conj result)
        (recur)))
    ;; All tasks processed
    (l/debug "All chunks have been processed. FileId=" id)
    (when multi-part-id
      (complete-multipart-upload id multi-part-id @etags {:storage-url storage-url :token token}))
    id))



(defn upload-file!
  "Returns the url to the uploaded file
   Options:
    `:storage-url` - the url of the xeoStorage
    `:token` - the token to authenticate with the xeoStorage
    `:file` - the file to upload"
  [file-path {:keys [storage-url token] :as opts}]
  (let [file (io/file file-path)
        response (future (upload-file-in-chunks file opts))] ;; (upload-file-classic file-name upload-url content-type)
    (while (not (realized? response))
      (Thread/sleep 1000))
    (l/debug "File uploaded successfully: " @response)
    (->
     (http/get (str storage-url "file/"  @response)  {
      :client client
      :headers {"Authorization" (str "Bearer " token)}})
     :body
     (json/parse-string true)
     (:downloadUrl))))

(comment
  (let [storage-url "https://storage.xeovision.io/"
        token (System/getenv "XEO_TOKEN")]
    (upload-file! "20160125OTC-Conference Center - IFC4.ifc" 
                  {:storage-url storage-url :token token}))
;;   (http/get (str storage-url "file/"  "c216a8c9-5d23-4582-b8a2-331c1b745633")  {:headers {"Authorization" (str "Bearer " token)}})
  ;;
  *e
  )