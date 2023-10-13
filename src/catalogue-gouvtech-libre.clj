#!/usr/bin/env bb

;; Copyright (c) DINUM, Bastien Guerry
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSE.txt

(require '[babashka.cli :as cli])
(def opts (cli/parse-opts *command-line-args*))

(println "Fetching catalogue-gouvtech data from data.gouv.fr...")

(def catalogue-gouvtech-url "https://www.data.gouv.fr/fr/datasets/r/184e607b-a45d-4ef2-8105-85967959b44b")

(defn get-contents [s]
  (let [res (try (curl/get s)
                 (catch Exception e
                   (println (.getMessage e))))]
    (when (= (:status res) 200) (:body res))))

(defn- rows->maps [csv]
  (let [headers (map #(keyword (str/replace % " " "_"))
                     (first csv))
        rows    (rest csv)]
    (map #(zipmap headers %) rows)))

(defn csv-url-to-map [url]
  (try
    (rows->maps (csv/read-csv (get-contents url)))
    (catch Exception e
      (println (.getMessage e)))))

(def catalogue-gouvtech-libre
  (filter #(= (:Distribution %) "Logiciel libre")
          (csv-url-to-map catalogue-gouvtech-url)))

(spit "catalogue-gouvtech-libre.json"
      (json/generate-string catalogue-gouvtech-libre {:pretty true}))

(println "Wrote catalogue-gouvtech-libre.json in this directory.")

(when (:md opts)
  (spit "catalogue-gouvtech-libre.md" "")
  (doall (map #(spit "catalogue-gouvtech-libre.md"
                     (str "# " (:Nom_solution %) "\n\n"
                          (:Description_courte %) "\n\n"
                          "- **Licence**: " (:Licence_opensource %) "\n"
                          "- **Lien GouvTech**: " (:Collection_ID %) "\n"
                          "- **Lien SILL**: " (:Lien_SILL %) "\n"
                          "- **Recherche SILL: https://code.gouv.fr/sill/list?search=" (:Nom_solution %)
                          "- **Lien Wikidata**: N/A" "\n\n")
                     :append true)
              catalogue-gouvtech-libre))
  (println "Wrote catalogue-gouvtech-libre.md in this directory."))
