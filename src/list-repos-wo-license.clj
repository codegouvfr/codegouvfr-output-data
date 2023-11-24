#!/usr/bin/env bb

;; Copyright (c) DINUM, Bastien Guerry
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSES/LICENSE.EPL-2.0.txt

;; Description
;;;;;;;;;;;;;;
;;
;; From repositories indexed https://code.gouv.fr/public/,
;; find some where the licence is missing.
;;
;; Usage
;;;;;;;;
;;
;; Get repos from organization_name with no license:
;; ~$ list-repos-wo-license.clj -o organization_name
;;
;; The same, but force refreshing the local database:
;; ~$ list-repos-wo-license.clj -r -o organization_name

(require '[babashka.cli :as cli])

(def opts (cli/parse-opts *command-line-args*))

(if-let [orga-name (:o opts)]
  (let [repos-file (str (System/getenv "HOME") "/.repos.json")
        repos-json (try (slurp repos-file) (catch Exception e nil))]
    (if (and (not-empty repos-json) (not (true? (:r opts))))
      (println "Read ~/.repos.json: done")
      (do (println "Store repos data in ~/.repos.json")
          (let [repos (slurp "https://code.gouv.fr/data/repositories/json/all.json")]
            (spit repos-file repos)
            (def repos-json repos))))
    (let [repos (->> (json/parse-string repos-json true)
                     (map #(select-keys % [:organization_name :repository_url :license]))
                     (filter #(= orga-name (:organization_name %)))
                     (filter #(empty? (:license %))))]
      (if (not-empty repos)
        (do (println (format "Repositories with no license in %s:" orga-name))
            (doall (map #(println (:repository_url %)) repos)))
        (println (format "No repositories for %s." orga-name)))))
  (println "Please provide an organization name with \"-o organization_name\""))
