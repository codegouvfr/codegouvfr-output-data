#!/usr/bin/env bb

;; Copyright (c) DINUM, Bastien Guerry
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSE.txt

;; TODO:
;; - spit tags.json for awesome software
;; - define and add an awesome-like score?

(deps/add-deps '{:deps {clj-rss/clj-rss {:mvn/version "0.4.0"}}})
(require '[clj-rss.core :as rss])

;; Initialize atoms
(def hosts (atom ()))
(def forges (atom ()))
(def owners (atom {}))
(def repositories (atom {}))

(def urls {:hosts
           "https://data.code.gouv.fr/api/v1/hosts"
           :annuaire_sup
           "https://code.gouv.fr/data/annuaire_sup.json"
           :annuaire_tops
           "https://code.gouv.fr/data/annuaire_tops.json"
           :comptes-organismes-publics
           "https://code.gouv.fr/data/comptes-organismes-publics.yml"})

;; Get hosts
(let [url (:hosts urls) res (curl/get url)]
  (println "Fetching hosts at" url)
  (when (= (:status res) 200)
    (->> (json/parse-string (:body res) true)
         (into ())
         ;; Test:
         ;; (take 1)
         (reset! hosts))))

;; Get annuaire
(def annuaire
  (let [url (:annuaire_sup urls) res (curl/get url)]
    (println "Fetching annuaire at" url)
    (when (= (:status res) 200)
      (->> (json/parse-string (:body res) true)
           (map (fn [{:keys [id service_top nom]}]
                  (hash-map id {:top service_top :nom nom})))
           (into {})))))

(def annuaire_tops
  (let [url (:annuaire_tops urls) res (curl/get url)]
    (println "Fetching annuaire tops at" url)
    (when (= (:status res) 200)
      (->> (json/parse-string (:body res))
           (into {})))))

;; Get owners
(doseq [{:keys [owners_url]} @hosts]
  (let [url (str owners_url "?per_page=1000")
        res (curl/get url)]
    (when (= 200 (:status res))
      (println "Fetching owners data from" url)
      (doseq [e (json/parse-string (:body res) true)]
        ;; Only get organization owners. FIXME: data.code.gouv.fr
        ;; should only contain those: remove this check?
        (when (= (:kind e) "organization")
          (swap! owners assoc
                 ;; Use lower-case owner URL for keys
                 (str/lower-case (:owner_url e))
                 (dissoc e :owner_url)))))))

;; Get repos
(doseq [{:keys [repositories_url repositories_count kind]} @hosts]
  (dotimes [n (int (clojure.math/floor (+ 1 (/ (- repositories_count 1) 1000))))]
    (let [url (str repositories_url (format "?page=%s&per_page=1000" (+ n 1)))
          res (try (curl/get url) (catch Exception e (println (.getMessage e))))]
      (when (= 200 (:status res))
        (println "Fetching repos data from" url)
        (doseq [e (json/parse-string (:body res) true)]
          (swap! repositories assoc
                 ;; Use lower-case repository URL for keys
                 (str/lower-case (:repository_url e))
                 (-> e
                     (assoc :platform kind)
                     (dissoc :repository_url))))))))

;; Get comptes-organismes-pubics
(let [url (:comptes-organismes-publics urls)
      res (curl/get url)]
  (when (= 200 (:status res))
    (println "Fetching public sector forges from comptes-organismes-pubics.yml")
    (->> (yaml/parse-string (:body res) :keywords false)
         (into {})
         (reset! forges))))

;; For all owners, add pso/pso_id/floss_policy
(doseq [[f forge-data] @forges]
  (let [f (if (= f "github.com") "github" f)]
    (if-let [groups (get forge-data "groups")]
      (doseq [[group group-data] groups]
        (let [owner_url
              (str/lower-case
               (format (str (:hosts urls "/%s/owners/%s")) f group))
              {:strs [pso pso_id floss_policy]} group-data]
          (swap! owners update-in [owner_url]
                 #(assoc % :pso pso :pso_id pso_id :floss_policy floss_policy
                         :forge (get forge-data "forge")))))
      (doseq [[k _] (filter #(str/includes? (key %) (str/lower-case f)) @owners)]
        (let [{:strs [pso pso_id floss_policy forge]} forge-data]
          (swap! owners update-in [k]
                 #(assoc % :pso pso :pso_id pso_id :floss_policy floss_policy
                         :forge forge)))))))

;; For all owners, add pso_top_id and pso_top_id_name
(doseq [[k v] (filter #(:pso_id (val %)) @owners)]
  (let [pso_id      (:pso_id v)
        top_id      (or (some (into #{} (keys annuaire_tops)) #{pso_id})
                        (:top (get annuaire pso_id))
                        pso_id)
        top_id_name (:nom (get annuaire top_id))]
    (swap! owners update-in [k]
           conj {:pso_top_id top_id :pso_top_id_name top_id_name})))

;; Spit owners.json
(->> ;; Keep those with orga URL and repositories count > 0
 (filter (fn [[_ v]]
           (and (not-empty (:html_url v))
                (> (:repositories_count v) 0)))
         @owners)
 (map (fn [[k v]]
        (let [d  (:description v)
              dd (if (not-empty d) (subs d 0 (min (count d) 200)) "")]
          {:id  k
           :r   (:repositories_count v)
           :o   (:html_url v)
           :au  (:icon_url v)
           :n   (:name v)
           :m   (:pso_top_id_name v)
           :l   (:login v)
           :c   (:created_at v)
           :d   dd
           :f   (:floss_policy v)
           :h   (:website v)
           :p   (:forge v)
           :e   (:email v)
           :a   (:location v)
           ;; TODO: New data pso
           ;; Use it for the search in the UI
           :pso (:pso v)})))
 json/generate-string
 (spit "owners.json"))

(defn toInst [^String s]
  (.toInstant (clojure.instant/read-instant-date s)))

(->> @owners
     (filter #(:created_at (val %)))
     (sort-by #(clojure.instant/read-instant-date (:created_at (val %))))
     reverse
     (take 10)
     (map (fn [[o o-data]]
            {:title       (str "Nouveau compte dans code.gouv.fr : " (:name o-data))
             :link        (:html_url o-data)
             :guid        o
             :description (:description o-data)
             :pubDate     (toInst (:created_at o-data))}))
     (rss/channel-xml
      {:title       "code.gouv.fr/sources - Nouveaux comptes d'organisation"
       :link        "https://code.gouv.fr/data/latest-repositories.xml"
       :description "code.gouv.fr/sources - Nouveaux comptes d'organisation"})
     (spit "latest-owners.xml"))

(defn compute-awesome-score [v]
  (let [files  (:files (:metadata v))
        high   1000
        medium 100
        low    10]
    ;; We assume a readme and not archived
    (+
     ;; Does the repo have a license?
     (if (:license files) high 0)
     ;; Does the repo have a publiccode.yml file?
     (if (:publiccode files) high 0)
     ;; Is the repo a template?
     (if (:template v) (* medium 2) 0)
     ;; Does the repo have a CONTRIBUTING.md file?
     (if (:contributing files) medium 0)
     ;; Does the repo have a description?
     (if (not-empty (:description v)) 0 (- medium))
     ;; Is the repo a fork?
     (if (:fork v) (- high) 0)
     ;; Does the repo have many forks?
     (if-let [f (:forks_count v)]
       (condp < f
         1000 high
         100  medium
         10   low
         0)
       0)
     ;; Does the repo have many subscribers?
     (if-let [f (:subscribers_count v)]
       (condp < f
         100 high
         10  medium
         1   low
         0)
       0))))

;; Spit repositories.json
(->>
 @repositories
 (filter #(let [v (val %)]
            (and (not-empty (:owner_url v))
                 (not-empty (:readme (:files (:metadata v))))
                 (not (:archived v)))))
 (map (fn [[_ v]]
        (let [d       (:description v)
              dd      (if (not-empty d) (subs d 0 (min (count d) 200)) "")
              fn      (:full_name v)
              n       (or (last (re-matches #".+/([^/]+)/?" fn)) fn)
              files   (:files (:metadata v))
              awesome (compute-awesome-score v)]
          {:a  awesome
           :u  (:updated_at v)
           :d  dd
           :f? (:fork v)
           :t? (:template v)
           :c? (false? (empty? (:contributing files)))
           :p? (false? (empty? (:publiccode files)))
           :l  (:language v)
           :li (:license v)
           :fn fn
           :n  n
           :f  (:forks_count v)
           ;; :s  (:subscribers_count v)
           :p  (:platform v)
           :o  (when-let [[_ host owner]
                          (re-matches (re-pattern (str (:hosts urls) "/([^/]+)/owners/([^/]+)"))
                                      (:owner_url v))]
                 (let [host (if (= host "GitHub") "github.com" host)]
                   (str "https://" host "/" owner)))})))
 json/generate-string
 (spit "repositories.json"))

(->> @repositories
     (filter #(:created_at (val %)))
     (sort-by #(clojure.instant/read-instant-date (:created_at (val %))))
     reverse
     (take 10)
     (map (fn [[r r-data]]
            (let [name (let [fn (:full_name r-data)] (or (last (re-matches #".+/([^/]+)/?" fn)) fn))]
              {:title       (str "Nouveau dépôt de code source dans code.gouv.fr : " name)
               :link        (:html_url r-data)
               :guid        r
               :description (:description r-data)
               :pubDate     (toInst (:created_at r-data))})))
     (rss/channel-xml
      {:title       "code.gouv.fr/sources - Nouveaux dépôts de code source"
       :link        "https://code.gouv.fr/data/latest-repositories.xml"
       :description "code.gouv.fr/sources - Nouveaux dépôts de code source"})
     (spit "latest-repositories.xml"))

;; Output forges.csv
(shell/sh "rm" "-f" "forges.csv")
(doseq [{:keys [name kind]} @hosts]
  (let [n (if (= "GitHub" name) "github.com" name)]
    (spit "forges.csv" (str n "," kind "\n") :append true)))

;; Test: display overview
(println "Hosts: " (count @hosts))
(println "Owners: " (count @owners))
(println "Repositories: " (count @repositories))
(println "Forges: " (count @forges))

;; ;; ;; Test: display examples
;; ;; (doseq [a (take 10 (shuffle (into [] @owners)))] (println a "\n"))
