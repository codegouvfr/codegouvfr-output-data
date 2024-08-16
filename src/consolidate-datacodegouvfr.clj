#!/usr/bin/env bb

;; Copyright (c) DINUM, Bastien Guerry
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSE.txt

;; TODO:
;; - Spit orgas.json (long and short)
;; - For each repos, add: is_publiccode, is_esr, is_contrib?
;; - Spit repos.json (long and short)
;; - define an awesome-like score
;; - For each repos, add the Awesome score

;; Initialize atoms
(def hosts (atom ()))
(def forges (atom ()))
(def owners (atom ()))
(def repositories (atom ()))

;; Get hosts
(let [url "https://data.code.gouv.fr/api/v1/hosts"
      res (curl/get url)]
  (println "Feching hosts at" url)
  (when (= (:status res) 200)
    (->> (json/parse-string (:body res) true)
         (reset! hosts))))

;; Get annuaire
(def annuaire
  (let [url "https://code.gouv.fr/data/annuaire_sup.json"
        res (curl/get url)]
    (println "Feching annuaire at" url)
    (when (= (:status res) 200)
      (->> (json/parse-string (:body res) true)
           (map (fn [{:keys [id service_top nom]}]
                  (hash-map id {:top service_top :nom nom})))
           (into {})))))

(def annuaire_tops
  (let [url "https://code.gouv.fr/data/annuaire_tops.json"
        res (curl/get url)]
    (println "Feching annuaire tops at" url)
    (when (= (:status res) 200)
      (->> (json/parse-string (:body res))
           (into {})))))

;; ;; Test
;; (reset! hosts (take 1 @hosts))

;; Get owners
(doseq [{:keys [owners_url]} @hosts]
  (let [url (str owners_url "?per_page=1000")
        res (curl/get url)]
    (when (= 200 (:status res))
      (println "Fetching owners data from" url)
      (->> (json/parse-string (:body res) true)
           ;; Use lower-case owner URL for keys
           (map #(hash-map (str/lower-case (:owner_url %))
                           (dissoc % :owner_url)))
           (into {})
           (reset! owners)))))

;; Get repos
(doseq [{:keys [repositories_url repositories_count]} @hosts]
  (dotimes [n (int (clojure.math/floor (+ 1 (/ (- repositories_count 1) 1000))))]
    (let [url (str repositories_url (format "?page=%s&per_page=1000" (+ n 1)))
          res (try (curl/get url) (catch Exception e (println (.getMessage e))))]
      (when (= 200 (:status res))
        (println "Fetching repos data from" url)
        (->> (json/parse-string (:body res) true)
             ;; Use lower-case repository URL for keys
             (map #(hash-map (str/lower-case (:repository_url %))
                             (dissoc % :repository_url)))
             (into {})
             (reset! repositories))))))

;; Get comptes-organismes-pubics
(let [url "https://git.sr.ht/~codegouvfr/codegouvfr-sources/blob/main/comptes-organismes-publics_new_specs.yml"
      res (curl/get url)]
  (when (= 200 (:status res))
    (println "Fetching public sector forges from comptes-organismes-pubics.yml")
    (->> (yaml/parse-string (:body res) :keywords false)
         (into {})
         (reset! forges))))

;; For all owners, add pso/pso_id/floss_policy
(doseq [[forge forge-data] @forges]
  (let [forge (if (= forge "github.com") "github" forge)]
    (if-let [groups (not-empty (get forge-data "groups"))]
      (doseq [[group group-data] groups]
        (let [owner_url (str/lower-case
                         (format "https://data.code.gouv.fr/api/v1/hosts/%s/owners/%s"
                                 forge group))]
          (let [{:strs [pso pso_id floss_policy]} group-data]
            (swap! owners update-in [owner_url]
                   #(assoc % :pso pso :pso_id pso_id :floss_policy floss_policy)))))
      (doseq [[k v] (filter #(str/includes? (key %) (str/lower-case forge)) @owners)]
        (let [{:strs [pso pso_id floss_policy]} forge-data]
          (swap! owners update-in [k]
                 #(assoc % :pso pso :pso_id pso_id :floss_policy floss_policy)))))))

(doseq [[k v] (filter #(:pso_id (val %)) @owners)]
  (let [pso_id      (:pso_id v)
        top_id      (or (some (into #{} (keys annuaire_tops)) #{pso_id})
                        (:top (get annuaire pso_id))
                        pso_id)
        top_id_name (:nom (get annuaire top_id))]
    (swap! owners update-in [k]
           conj {:pso_top_id top_id :pso_top_id_name top_id_name})))

;; Print results (test)
(println "Hosts: " (count @hosts))
(println "Owner: " (count @owners))
(println "Repos: " (count @repositories))
(println "Forges: " (count @forges))

;; ;; Test
;; (doseq [a (take 4 (shuffle (into [] @owners)))] (println a "\n"))

;; ;; Add key-val to a list of hashmaps
;; (def users [{:name "James" :age 26}  {:name "John" :age 43}])
;; (map #(if (= "James" (:name %)) (conj % {:aaa "aaa"}) %) users)
