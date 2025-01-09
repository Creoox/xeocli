(ns user
  (:require
   [babashka.cli :as cli]
   [borkdude.gh-release-artifact :as ghr]
   [clojure.java.io :as io]
   [clojure.string :as str]))


(def cli-options {:org {:default "mmilian"}
                  :repo {:default "xv"}
                  :tag {:default "v1.0.0"}
                  :file {}})

(defn remove-second
  [coll]
  (concat (take 1 coll)
          (drop 2 coll)))

(defn remove-version [file]
  (let [parts (str/split file #"-")
        file-name (str/join "-" (remove-second parts))]
    file-name))

(comment
  ;; create a few samples of remove-version
  (remove-version "xeo-v1.0.0-linux-amd64.tar.gz"))

;; copy the file to to the ./tmp/(remove-version file)
(defn copy-file-tmp [file-name new-file-name]
  (io/copy (io/file file-name) (io/file new-file-name)))

(comment
  (let [file-name "xeo-1.0.1-linux-amd64.tar.gz"]
    (copy-file-tmp file-name (str "/tmp/" (remove-version file-name))))
;;
  )


(defn -main [& _args]
  (let [{:keys [org repo tag file]} (cli/parse-opts *command-line-args* {:spec cli-options})
        file-name (remove-version file)
        file-path (str "/tmp/" file-name)]
    (copy-file-tmp file file-path)
    (ghr/release-artifact {:org org
                           :repo repo
                           :tag tag
                           :file file-path
                           :sha256 true
                           :overwrite true})))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))


(comment
  (ghr/release-artifact {:org "mmilian"
                         :repo "xv"
                         :tag "v1.0.1"
                         :file "xeo-v1.0.0-linux-amd64.tar.gz"
                         :sha256 true
                         :overwrite true}))
