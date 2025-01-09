(ns user
  (:require
   [babashka.cli :as cli]
   [borkdude.gh-release-artifact :as ghr]))

(comment
  (ghr/release-artifact {:org "mmilian"
                         :repo "xv"
                         :tag "v1.0.0"
                         :file "xv-windows-amd64.zip"
                         :sha256 true
                         :overwrite true}))


(def cli-options {:org {:default "mmilian"}
                  :repo {:default "xv"}
                  :tag {:default "v1.0.0"}
                  :file {}})


(let [{:keys [org repo tag file]} (cli/parse-opts *command-line-args* {:spec cli-options})]
  (ghr/release-artifact {:org org
                         :repo repo
                         :tag tag
                         :file file
                         :sha256 true
                         :overwrite true}))
