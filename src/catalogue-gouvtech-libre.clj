#!/usr/bin/env bb

;; Copyright (c) DINUM, Bastien Guerry
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSE.txt

(println "Fetching catalogue-gouvtech data from data.gouv.fr...")

(def catalogue-gouvtech-url "https://www.data.gouv.fr/fr/datasets/r/184e607b-a45d-4ef2-8105-85967959b44b")

(defn get-contents [s]
  (let [res (try (curl/get s)
                 (catch Exception e
                   (println (.getMessage e))))]
    (when (= (:status res) 200) (:body res))))

(defn- rows->maps [csv]
  (let [headers (map keyword (first csv))
        rows    (rest csv)]
    (map #(zipmap headers %) rows)))

(defn csv-url-to-map [url]
  (try
    (rows->maps (csv/read-csv (get-contents url)))
    (catch Exception e
      (println (.getMessage e)))))

(spit "catalogue-gouvtech-libre.json"
      (json/generate-string
       (filter #(= (:Distribution %) "Logiciel libre")
               (csv-url-to-map catalogue-gouvtech-url))
       {:pretty true}))

(println "Wrote catalogue-gouvtech-libre.json in this directory.")
