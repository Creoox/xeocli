(ns utils)

(def url-regex
  #"(?i)^(https?)://[^\s/$.?#].[^\s]*$")

(defn valid-url? [s]
  (boolean (re-matches url-regex s)))


(comment
  (valid-url? "wall.ifc"))


;; extract extension from file path using regex
(defn file-extension [file-path]
  (when file-path
    (let [ext-regex #"\.([a-zA-Z0-9]+)$"]
      (when (re-find ext-regex file-path)
        (second (re-find ext-regex file-path))))))

(comment
  ;; write some example of extract-ext
  (file-extension "wall.ifc")
  (file-extension "wall.glb")
  (file-extension "wall")
  (file-extension nil)
  (file-extension "")
  ;;
  )


(defn rand-str [len]
  (apply str (take len (repeatedly #(char (+ (rand 26) 65))))))


;; drop extension from file path
(defn file-base-name [file-path]
  (when file-path
    (let [ext-regex #"(.*)(?=\.[a-zA-Z0-9]+$)"]
      (first (re-find ext-regex file-path)))))

(comment
  (file-base-name "wall.ifc")
  (file-base-name "")
  (file-base-name nil)
  ;;
  )


;; current date and time in format: yyyy-MM-dd-HH-mm-ss
(defn current-date-time []
  (let [now (java.util.Date.)]
    (str (java.text.SimpleDateFormat. "yyyy-MM-dd-HH-mm-ss") (.format now))))

(defn read-secret [msg]
  (println msg)
  (if-let [console (System/console)]
    (let [secret (.readPassword console)]
      (String. secret))
    (do
      (println "Console not available. Falling back to standard input.")
      (read-line))))

(comment
  (read-secret "msg")
  :end)
