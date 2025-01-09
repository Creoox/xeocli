(ns xeo.processor.api
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [taoensso.timbre :as l]
   [xeo.storage.api :refer [upload-file!]]))


(def client (http/client (assoc-in http/default-client-opts [:ssl-context :insecure] true)))

(defn- start! [downloadUrl type {:keys [converter-url token] :as opts}]
  (let
   [
    _ (l/debug "Processing file:" opts)
    service-url (str converter-url "process")
    _ (l/debug "Processing file:" service-url)
    response (http/post service-url {:client client
                                     :headers {"Authorization" (str "Bearer " token)
                                               :content-type "application/json"}
                                     :body (json/encode {:type type
                                                         :downloadUrl downloadUrl})})
    _ (l/debug "Processing file" response)
    body (json/parse-string (:body response) true)]
    (:id body)))

(defn- status! [id {:keys [converter-url token]}]
  (let
   [response (http/get (str converter-url "process/" id)
                       {:client client
                        :headers {"Authorization" (str "Bearer " token)}})
    body (json/parse-string (:body response) true)]
    body))

(defn- start<!! [file-url process-type opts]
  (let [id (start! file-url process-type opts)]
    (loop []
      (let [res (status! id opts)]
        (if (not= "process_completed" (:status res))
          (do
            (Thread/sleep 1000)
            (recur))
          res)))))

;;  process-type (or conversion-type (cond
;;                                    (= ext "ifc") "ifc-xkt"
;;                                    (= ext "glb") "glb-xkt"
;;                                    :else (error! "Unsupported file type"
;;                                                  {:file file-path
;;                                                   :category :fault})))


(defn convert! [file-url conversion-type opts]
  (start<!! file-url conversion-type opts))

(defn validate! [file-url validation-type opts]
  (start<!! file-url validation-type opts))


(comment
  (let [opts {:storage-url "https://storage.xeovision.io/"
              :converter-url "https://converter.xeovision.io/"
              :token  (System/getenv "XEO_TOKEN")}
        file-path "20160125OTC-Conference Center - IFC4.ifc"
        file-url (upload-file! file-path opts)]
    (convert! file-url "ifc-xkt"
              opts))
  ;;
  (let [opts {:storage-url "https://storage.xeovision.io/"
            :converter-url "https://converter.xeovision.io/"
            :token  (System/getenv "XEO_TOKEN")}]
  (convert! "https://github.com/buildingSMART/Sample-Test-Files/raw/refs/heads/main/PCERT-Sample-Scene/IFC%204.0.2.1%20(IFC%204)/Building-Architecture.ifc" "ifc-xkt"
            opts))

  *e
  )
