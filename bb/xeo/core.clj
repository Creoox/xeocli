#!/usr/bin/env bb

  (ns xeo.core
    (:require
     [babashka.cli :as cli]
     [babashka.fs :as fs]
     [babashka.http-client :as http]
     [cheshire.core :as json]
     [clojure.java.browse :refer [browse-url]]
     [clojure.java.io :as io]
     [clojure.string :as str]
     [taoensso.timbre :as l :refer [set-level!]]
     [utils :refer [current-date-time file-base-name rand-str read-secret
                    valid-url?]]
     [xeo.processor.api :as converter]
     [xeo.storage.api :refer [upload-file!]]
     [xv-script :refer [error!]]))

(def version "v0.0.1")
(def supported-conversion-types ["ifc-xkt" "glb-xkt"])
(def supported-validation-types ["ifc-ids-validate" "ifc-model-check"])

  
;; follow https://specifications.freedesktop.org/basedir-spec/latest/  
(def XDG_CONFIG_HOME (or (System/getenv "XDG_CONFIG_HOME") (System/getProperty "user.home")))
(def XDG_STATE_HOME (or (System/getenv "XDG_STATE_HOME") (System/getProperty "user.home")))

(def XEO_STATE_PATH (str XDG_STATE_HOME "/.xeo"))
(def XEO_TOKEN_PATH (str XDG_STATE_HOME "/.xeo/token"))

(def STORAGE_URL (or (System/getenv "XEO_STORAGE_URL") "https://storage.xeovision.io/"))
(def CONVERTER_URL (or (System/getenv "XEO_CONVERTER_URL") "https://converter.xeovision.io/"))


(defn validate-token [{:keys [url token] :or {url "https://converter.xeovision.io/health"}}]
  (let [response (http/get url {:headers {"Authorization" (str "Bearer " token)
                                          "Content-Type" "application/json"}
                                :throw false})]
    (l/debug "Health check response" response)
    (case (:status response)
      200 {:ok "Service is healthy."}
      401 {:error "Unauthorized access. Please check your XEO_TOKEN." :category :forbidden}
      403 {:error "Forbidden access. Please check your XEO_TOKEN." :category :forbidden}
      {:error (str "Unknown error with: " url)  :category :fault})))

(comment
  (validate-token {:url "https://converter.xeovision.io/health" :token (System/getenv "XEO_TOKEN")})
  *e
;;
  )

(defn read-token-from-local-cache []
  (if (.exists XEO_TOKEN_PATH)
    (slurp XEO_TOKEN_PATH)
    nil))

(defn save-token-to-local-cache [token]
  (let [_ (fs/create-dirs XEO_STATE_PATH)
        token-file (io/file XEO_TOKEN_PATH)]
    (spit token-file token)))

(defn print-no-token []
  (binding [*out* *err*]
    (println (str/trim "
       Error! Token not provided. 
                      Please set the XEO_TOKEN environment variable.

      If you do not have it yet, you can get it at https://docs.xeo.vision

       You can set the token by running the following command in your terminal:
       Windows Powershell: 
         $env:XEO_TOKEN = '<your_token_here>'
       Windows Command Prompt:
         set XEO_TOKEN= '<your_token_here>'                 

       Linux/Mac: 
         export XEO_TOKEN='<your_token_here>'

                          "))))

(defn retrive-and-validate-token []
  (let [token (or (System/getenv "XEO_TOKEN") (read-token-from-local-cache) (read-secret "Enter your XEO_TOKEN:"))]
    (if (nil? token)
      (do
        (print-no-token)
        (error! "Token not provided" {:category :incorect}))
      (when-let [err (:error (validate-token {:url "https://storage.xeovision.io/health" :token token}))]
        (error! (:error err) {:category err})))
    (save-token-to-local-cache token)
    token))

(comment
  (retrive-and-validate-token)
  *e
  ;;
  )

(defn download-output [data dir-name filter-output]
  (let [process-outputs (:processOutputs data)
        dir (-> (str/replace dir-name "." "_") (str "_processed_xeo/" filter-output))
        outputs (filter #(= (:fileType %) filter-output) process-outputs)
        dest-dir (io/file (System/getProperty "user.dir") dir)] ;; 
    ;; Create destination directory if it doesn't exist
    (.mkdirs dest-dir)
    (l/debug "Logs" data)
    ;; Download each log file
    (doseq [output outputs]
      (let [url (:url output)
            file-name (:fileName output)
            dest-file (io/file dest-dir file-name)]
        (l/debug "Downloading" file-name "to" (.getPath dest-file))
        (let [response (http/get url {:insecure true :as :bytes})]
          (if (= 200 (:status response))
            (do
              (io/copy (:body response) dest-file)
              (l/debug "Downloaded" file-name))
            (l/debug "Failed to download" file-name)))))))

(def opts-spec
  {:type {:desc "<ifc-xkt | glb-xkt>, Type of conversion"
          :alias :t
          :require true}
   :file {:desc " Path or url to file"
          :alias :f
          :require true}
   :wait {:desc "Wait until conversion is finished, if false returns immediately with processId"
          :coerce boolean
          :alias :w
          :default true}
   :log {:desc "Dump logs to: `<current-dir>/<file-name>_processed/logs/*.log` "
         :coerce boolean
         :alias :l
         :default false}
   :artifact {:desc "Download all process artifacts (logs, db, glb, ...)"
              :coerce boolean
              :alias :a
              :default false}
   :output-dir {:desc "Output directory"
                :default "`.`"
                :alias :o}})

;; Rewrite the subcommands to be more user-friendly
(defn help [{:keys [opts]}]
  (if (:version opts)
    (println version)
    (println (str "
Manage your 3D models with xeoVision services - https://docs.xeo.vision

Usage: xeo <subcommand> <file-path or url> <options>
Most subcommands support the options:
"
                  (cli/format-opts {:spec opts-spec})
                  "
                      
                      
Subcommands:

  convert    Louch the conversion pipeline.
  validate   Louch the validation pipeline*.
   
  help       Print this help message.

Examples:
  xeo convert wall.ifc  # local file conversion, opens the viewer in the default browser
  xeo convert wall.ifc  --log --artifact --json  # drops logs and artifacts, prints the response as JSON
  xeo convert https://raw.githubusercontent.com/xeokit/xeokit-sdk/master/assets/models/ifc/Duplex.ifc --type ifc-xkt --airtifact # conversion from url
  xeo validate wall.ifc 
  xeo validate wall.ifc --type ifc-ids-validate
  xeo validate https://raw.githubusercontent.com/xeokit/xeokit-sdk/master/assets/models/ifc/Duplex.ifc --type ifc-model-check --log --artifact
                      
                      "))))

(defn validate-conversion-type [convertsion-type]
  (when-not (some #{convertsion-type} supported-conversion-types)
    (error! "Unsupported conversion type. When url provided, the type must be one of: <ifc-xkt | glb-xkt>"
            {:type convertsion-type
             :category :incorrect})))

(comment
  (validate-conversion-type "glb-xkt")
  (validate-conversion-type "foo")
  (validate-conversion-type nil)
  (validate-conversion-type nil)
  *e
  ;;
  )

(def convert-help "

Examples:
  xeo convert --type ifc-xkt wall.ifc  # local file conversion, opens the viewer in the default browser
  xeo convert --type ifc-xkt --log ifc-xkt --artifact --json wall.ifc  # drops logs and artifacts, prints the response as JSON 
  ")

(defn convert-exec [command-line-args]
  (let [opts (:opts command-line-args)
        _ (when (:debug opts) (set-level! :debug))
        _ (l/debug command-line-args)
        token (retrive-and-validate-token)
        conversion-type (:type opts)
        _ (validate-conversion-type conversion-type)
        resource-path (:file opts)
        file-url (if (valid-url? resource-path)
                   resource-path
                   (upload-file! resource-path {:storage-url STORAGE_URL :token token}))
        output-dir-name (or (:output-dir opts) (file-base-name resource-path) (str "./xeo_" (current-date-time)))
        _ (l/debug "File URL" (file-base-name resource-path))
        response (converter/convert! file-url conversion-type {:storage-url STORAGE_URL
                                                               :converter-url CONVERTER_URL
                                                               :token token})]
    (when
     (:log opts) (download-output response output-dir-name "log"))
    (when
     (:artifact opts)
      (l/debug "Downloading artifacts to " output-dir-name)
      (download-output response output-dir-name "json")
      (download-output response output-dir-name "xkt")
      (download-output response output-dir-name "db"))
    (if
     (:json opts)
      (println (json/generate-string response {:pretty true}))
      (do
        (println "Open the viewer at:" (:viewerUrl response))
        (future
          (try
            (browse-url  (:viewerUrl response))
            (catch Exception _
              (println "Can not open your default browser. Please open the viewer manually."))))))))


(defn cli-err-hanlder [{:keys [spec type cause msg option] :as data}]
  (l/debug :data data)
  (if (= :org.babashka/cli type)
    (case cause
      :require
      (println
       (str (format "\nMissing required argument or option:\n%s"
                    (cli/format-opts {:spec (select-keys spec [option])}))
            "\n"
            "

Avaliable options\n"

            (cli/format-opts {:spec spec})


            convert-help))
      (println msg))
    (throw (ex-info msg data)))
  (System/exit 1))

(def convert-cli
  {:fn convert-exec,
   :args->opts [:file]
   :spec {:type {:desc "<ifc-xkt | glb-xkt>, type of conversion"
                 :require true}
          :file {:desc " which is a path or url to file"
                 :require true}
          :wait {:desc "Wait for the conversion to finish"
                 :coerce boolean
                 :alias :w
                 :default true}
          :log {:desc "Drop logs to: `<current-dir>/<file-name>_processed/logs/*.log` "
                :coerce boolean
                :alias :l
                :default false}
          :artifact {:desc "Download all process artifacts (logs, db, glb, ...)"
                     :coerce boolean
                     :alias :d
                     :default false}
          :output-dir {:desc "Output directory"
                       :default "`.`"
                       :alias :o}}
   :error-fn cli-err-hanlder})

(comment
  (cli/format-opts convert-cli)
 ;;
  )


(defn validate-validation-type [type]
  (when (and (not (nil? type)) (not (some #{type} supported-validation-types)))
    (error! "Unsupported validation type"
            {:type type
             :category :fault})))


(defn fetch-validation-respose [res]
  (->> (:processOutputs res)
       (filter #(= (:fileName %) "output.json"))
       (first)
       :url
       (slurp)))

(defn validate-exec [command-line-args]
  (let [opts (:opts command-line-args)
        token (retrive-and-validate-token)
        validation-type (:type opts)
        _ (validate-validation-type validation-type)
        resource-path (:file opts)
        file-url (if (valid-url? resource-path)
                   resource-path
                   (upload-file! resource-path {:storage-url STORAGE_URL :token token}))
        output-dir-name (or (:output-dir opts) (file-base-name resource-path) (str "./xeo_" (current-date-time)))
        res (converter/validate! file-url  validation-type  {:converter-url CONVERTER_URL
                                                             :storage-url STORAGE_URL
                                                             :token token})]
    (when
     (:log opts) (download-output res output-dir-name "log"))
    (when
     (:artifact opts)
      (download-output res output-dir-name "json"))
    (when
     (:json opts)
      (println (json/generate-string res {:pretty true})))
    (when
     (:json-output opts)
      (println (fetch-validation-respose res)))
    (when (not (or (:json opts) (:json-output opts)))
      (println "Validation completed successfully."))))

(def validate-cli
  {:fn validate-exec,
   :args->opts [:file :url :wait]
   :spec {:type {:desc "Processing type"
                 :require true}
          :file {:desc "Path to file to convert"
                 :require true}
          :url {:desc "URL to the file to convert"}
          :wait {:desc "Wait for the conversion to finish"
                 :coerce boolean
                 :alias :w
                 :default true}
          :log {:desc "Download the conversion process logs"
                :coerce boolean
                :alias :l
                :default false}
          :artifact {:desc "Download the conversion artifacts"
                     :coerce boolean
                     :alias :a
                     :default false}}
   :error-fn cli-err-hanlder})

;; TODO: Add nicely explained error messages for the cli
(defn -main [& _args]
  (let [home-dir (System/getProperty "user.home")
        tmp-dir (str home-dir "/.xeo/" (rand-str 8) "/")
        _ (fs/create-dirs tmp-dir)]
    (-> (Runtime/getRuntime) (.addShutdownHook (Thread. #(fs/delete-tree tmp-dir))))
    #_{:clj-kondo/ignore [:deprecated-var]}
    (l/set-level! :warn)
    (cli/dispatch
     [(merge {:cmds ["convert"]}  convert-cli)
      (merge {:cmds ["validate"]}  validate-cli)
      {:cmds ["token"] :fn (fn [& _] (when (retrive-and-validate-token) (println "Token is valid.")))}
      {:cmds ["version"] :fn (fn [& _] (println version))}
      {:cmds ["help"] :fn help}
      {:cmds [] :fn help}]
     *command-line-args*)))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
