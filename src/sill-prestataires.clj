#!/usr/bin/env bb

;; Copyright (c) DINUM, Bastien Guerry
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSES/LICENSE.EPL-2.0.txt

(println "Generating sill-prestataires.json...")

(def cnll_baseurl "https://annuaire.cnll.fr/api/")
(def cnll_filename "prestataires-sill.json")
(def cdl_baseurl "https://comptoir-du-libre.org/public/export/")
(def cdl_filename "comptoir-du-libre_export_v1.json")

(def cdl
  (->> (json/parse-string (slurp (str cdl_baseurl cdl_filename)) true)
       :softwares
       (filter #(not-empty (:sill (:external_resources %))))
       (map (juxt #(:id (:sill (:external_resources %)))
                  #(keep (juxt :name :url
                               (fn [a] (:website (:external_resources a))))
                         (:providers %))))
       (map (fn [[a b]]
              [a (into [] (map (fn [[x y z]]
                                 {:nom x :cdl_url y :website y}) b))]))))

(def cnll
  (->> (json/parse-string (slurp (str cnll_baseurl cnll_filename)) true)
       (map (juxt :sill_id
                  #(map (fn [p] (set/rename-keys p {:url :cnll_url}))
                        (:prestataires %))))))

(def all
  (->> (group-by first (concat cdl cnll))
       (map (fn [[id l]] [id (flatten (merge (map second l)))]))
       (filter #(not-empty (second %)))
       (map (fn [[id l]]
              {:sill_id      id
               :prestataires (map #(apply merge (val %))
                                  (group-by #(str/lower-case (:nom %)) l))}))
       (sort-by :sill_id)))

(spit "sill-prestataires.json" (json/generate-string all))

(println "Generating sill-prestataires.json... done")
