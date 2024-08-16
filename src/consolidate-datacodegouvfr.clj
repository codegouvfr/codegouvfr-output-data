#!/usr/bin/env bb

;; Copyright (c) DINUM, Bastien Guerry
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSE.txt

;; Initialize atoms
(def hosts (atom ()))
(def owners (atom ()))
(def repositories (atom ()))

;; Get hosts
(let [url "https://data.code.gouv.fr/api/v1/hosts"
      res (curl/get url)]
  (println "Feching hosts at" url)
  (when (= (:status res) 200)
    (->> (json/parse-string (:body res) true)
         (reset! hosts))))

;; Get owners
(doseq [{:keys [owners_url]} @hosts]
  (let [url (str owners_url "?per_page=1000")
        res (curl/get url)]
    (when (= 200 (:status res))
      (println "Fetching owners data from" url)
      (->> (json/parse-string (:body res) true)
           (swap! owners concat)))))

;; Get repos
(doseq [{:keys [repositories_url repositories_count]} @hosts]
  (dotimes [n (int (clojure.math/floor (+ 1 (/ (- repositories_count 1) 1000))))]
    (let [url (str repositories_url (format "?page=%s&per_page=1000" (+ n 1)))
          res (try (curl/get url) (catch Exception e (println (.getMessage e))))]
      (when (= 200 (:status res))
        (println "Fetching repos data from" url)
        (->> (json/parse-string (:body res) true)
             (swap! repositories concat))))))

;; Print results (test)
(println "Hosts: " (count @hosts))
(println "Owner: " (count @owners))
(println "Repos: " (count @repositories))

;; TODO:

;; - For each owner, add: pso, pso_id, floss_policy
;; - Spit orgas.json (long and short)
;; - For each repos, add: is_publiccode, is_esr, is_contrib
;; - Spit repos.json (long and short)
;; - define an awesome-like score
;; - For each repos, add the Awesome score

;; ;; Get comptes-organismes-pubics
;; (let [url "https://git.sr.ht/~codegouvfr/codegouvfr-sources/blob/main/comptes-organismes-publics_new_specs.yml"
;;       res (curl/get url)]
;;   (when (= 200 (:status res))
;;     (println "Get metadata from comptes-organismes-pubics.yml")
;;     (let [metadata (yaml/parse-string (:body res) :keywords false)]
;;       (doseq [[forge data] metadata]
;;         (if-let [groups (get data "groups")]          
;;           ;; For each owner where html_url matches forge/group, set
;;           ;; pso, pso_id, floss_policy
;;           (println forge groups)
;;           ;; Otherwise, set the hosts pso, pso_id, floss_policy for each owner
;;           (println "No group"))))))

;; ;; Add key-val to a list of hashmaps
;; (def users [{:name "James" :age 26}  {:name "John" :age 43}])
;; (map #(if (= "James" (:name %)) (conj % {:aaa "aaa"}) %) users)
