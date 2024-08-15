#!/usr/bin/env bb

;; Copyright (c) DINUM, Bastien Guerry
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSE.txt

;; Require datalevin pod
(require '[babashka.pods :as pods])
(pods/load-pod 'huahaiy/datalevin "0.9.10")
(require '[pod.huahaiy.datalevin :as d])

;; ;; Remove possibly preexistent db
;; (shell/sh "rm" "-fr" "/tmp/db")

;; Create db
(def schema {:host_url {:db/valueType :db.type/string :db/unique :db.unique/identity}
             :uuid     {:db/valueType :db.type/string :db/unique :db.unique/identity}})
(def conn (d/get-conn "/tmp/db" schema))

;; Add utility functions
(defn db [] (d/db conn))
(defn replace-vals [m v r]
  (clojure.walk/postwalk #(if (= % v) r %) m))

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
         (map #(replace-vals % nil ""))
         (reset! hosts))))

(println "Storing hosts in db...")
(try (doall (d/transact! conn (into [] @hosts)))
     (catch Exception e (println (.getMessage e))))

;; Get owners
(doseq [{:keys [owners_url]} @hosts]
  (let [url (str owners_url "?per_page=1000")
        res (curl/get url)]
    (when (= 200 (:status res))
      (println "Fetching owners data from" url)
      (->> (json/parse-string (:body res) true)
           (swap! owners concat)))))

(println "Storing owners in db...")
(doall
 (doseq [o (map #(replace-vals % nil "") @owners)]
   (try (d/transact! conn [o])
        (catch Exception e (println (.getMessage e))))))

;; Get repos
(doseq [{:keys [repositories_url repositories_count]} @hosts]
  (dotimes [n (int (clojure.math/floor (+ 1 (/ (- repositories_count 1) 1000))))]
    (let [url (str repositories_url (format "?page=%s&per_page=1000" (+ n 1)))
          res (try (curl/get url) (catch Exception e (println (.getMessage e))))]
      (when (= 200 (:status res))
        (println "Fetching repos data from" url)
        (->> (json/parse-string (:body res) true)
             (swap! repositories concat))))))

(println "Storing repositories in db...")
;; 62,52s user 22,38s system 37% cpu 3:48,46 total
(doall
 (doseq [r (map #(replace-vals % nil "") @repositories)]
   (try (d/transact! conn [r])
        (catch Exception e (println (.getMessage e))))))

;; Print results (test)
;; Owners:
(println "Owner: " (count (d/q '[:find ?e :where [?e :login _]] (d/db conn))))
;; Repos:
(println "Owner: " (count (d/q '[:find ?e :where [?e :tags_url _]] (d/db conn))))
;; Owners and repos:
(println "Total: " (count (d/q '[:find ?e :where [?e :uuid _]] (d/db conn))))

;; TODO:

;; - For each owner, add: pso, pso_id, floss_policy
;; - Spit orgas.json (long and short)
;; - For each repos, add: is_publiccode, is_esr, is_contrib
;; - Spit repos.json (long and short)
;; - Remove the use of the db

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
