#!/usr/bin/env bb

;; Copyright (c) DINUM, Bastien Guerry
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSE.txt

;; TODO:
;; - spit stats.json
;; - spit tags.json for awesome software
;; - spit latest-tags.json for awesome software

(deps/add-deps '{:deps {clj-rss/clj-rss {:mvn/version "0.4.0"}}})
(deps/add-deps '{:deps {org.babashka/cli {:mvn/version "0.8.60"}}})
(require '[clj-rss.core :as rss]
         '[clojure.tools.logging :as log]
         '[babashka.cli :as cli])

;; Define CLI options
(def cli-options
  {:test-msg {:desc    "Testing options"
              :default "This is a default test message"}})

;; Initialize atoms
(def hosts (atom ()))
(def forges (atom ()))
(def owners (atom {}))
(def repositories (atom {}))

(def urls {:hosts                      "https://data.code.gouv.fr/api/v1/hosts"
           :annuaire_sup               "https://code.gouv.fr/data/annuaire_sup.json"
           :annuaire_tops              "https://code.gouv.fr/data/annuaire_tops.json"
           :comptes-organismes-publics "https://code.gouv.fr/data/comptes-organismes-publics.yml"})

;; Helper functions
(defn fetch-json [url]
  (let [res (curl/get url)]
    (if (= (:status res) 200)
      (json/parse-string (:body res) true)
      (log/error "Failed to fetch JSON from" url "Status:" (:status res)))))

(defn fetch-yaml [url]
  (let [res (curl/get url)]
    (if (= (:status res) 200)
      (yaml/parse-string (:body res) :keywords false)
      (log/error "Failed to fetch YAML from" url "Status:" (:status res)))))

(defn toInst [^String s]
  (.toInstant (clojure.instant/read-instant-date s)))

;; Fetching functions
(defn fetch-hosts []
  (log/info "Fetching hosts from" (:hosts urls))
  (reset! hosts (or (fetch-json (:hosts urls)) ())))

(defn fetch-annuaire []
  (log/info "Fetching annuaire at" (:annuaire_sup urls))
  (let [data (fetch-json (:annuaire_sup urls))]
    (when data
      (into {} (map (fn [{:keys [id service_top nom]}]
                      [id {:top service_top :nom nom}])
                    data)))))

(defn fetch-annuaire-tops []
  (log/info "Fetching annuaire tops at" (:annuaire_tops urls))
  (->> (:annuaire_tops urls)
       fetch-json
       (into {})))

(defn fetch-owners []
  (doseq [{:keys [owners_url]} @hosts]
    (let [url  (str owners_url "?per_page=1000")
          data (fetch-json url)]
      (when data
        (log/info "Fetching owners data from" url)
        (doseq [e (filter #(= (:kind %) "organization") data)]
          (swap! owners assoc
                 (str/lower-case (:owner_url e))
                 (dissoc e :owner_url)))))))

(defn fetch-repos []
  (doseq [{:keys [repositories_url repositories_count kind]} @hosts]
    (dotimes [n (int (clojure.math/floor (+ 1 (/ (- repositories_count 1) 1000))))]
      (let [url  (str repositories_url (format "?page=%s&per_page=1000" (+ n 1)))
            data (try (fetch-json url)
                      (catch Exception e
                        (log/error "Error fetching repos froma" (.getMessage e))))]
        (when data
          (log/info "Fetching repos data from" url)
          (doseq [e data]
            (swap! repositories assoc
                   (str/lower-case (:repository_url e))
                   (-> e
                       (assoc :platform kind)
                       (dissoc :repository_url)))))))))

(defn fetch-public-sector-forges []
  (log/info "Fetching public sector forges from comptes-organismes-pubics.yml")
  (reset! forges (or (fetch-yaml (:comptes-organismes-publics urls)) {})))

;; Processing functions
(defn process-owners []
  (doseq [[f forge-data] @forges]
    (let [f (if (= f "github.com") "github" f)]
      (if-let [groups (get forge-data "groups")]
        (doseq [[group group-data] groups]
          (let [owner_url                         (str/lower-case
                           (format (str (:hosts urls "/%s/owners/%s")) f group))
                {:strs [pso pso_id floss_policy]} group-data]
            (swap! owners update-in [owner_url]
                   #(assoc % :pso pso :pso_id pso_id :floss_policy floss_policy
                           :forge (get forge-data "forge")))))
        (doseq [[k _] (filter #(str/includes? (key %) (str/lower-case f)) @owners)]
          (let [{:strs [pso pso_id floss_policy forge]} forge-data]
            (swap! owners update-in [k]
                   #(assoc % :pso pso :pso_id pso_id :floss_policy floss_policy
                           :forge forge))))))))

(defn add-pso-top-id [annuaire annuaire-tops]
  (doseq [[k v] (filter #(:pso_id (val %)) @owners)]
    (let [pso_id      (:pso_id v)
          top_id      (or (some (into #{} (keys annuaire-tops)) #{pso_id})
                     (:top (get annuaire pso_id))
                     pso_id)
          top_id_name (:nom (get annuaire top_id))]
      (swap! owners update-in [k]
             conj {:pso_top_id top_id :pso_top_id_name top_id_name}))))

;; Output functions
(defn output-owners-json []
  (->> (filter (fn [[_ v]]
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
                 :pso (:pso v)})))
       json/generate-string
       (spit "owners.json")))

(defn output-latest-owners-xml []
  (->> @owners
       (filter #(:created_at (val %)))
       (sort-by #(clojure.instant/read-instant-date (:created_at (val %))))
       reverse
       (take 10)
       (map (fn [[o o-data]]
              {:title       (str "Nouveau compte dans code.gouv.fr : " (:name o-data))
               :link        (:html_url o-data)
               :guid        o
               :description (:description o-data)
               :pubDate     (toInst (:created_at o-data))}))
       (rss/channel-xml
        {:title       "code.gouv.fr/sources - Nouveaux comptes d'organisation"
         :link        "https://code.gouv.fr/data/latest-repositories.xml"
         :description "code.gouv.fr/sources - Nouveaux comptes d'organisation"})
       (spit "latest-owners.xml")))

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
       0)
     ;; Does the repo have many star gazers?
     (if-let [s (:stargazers_count v)]
       (condp < s
         1000 high
         100  medium
         10   low
         0)
       0))))

(defn output-repositories-json []
  (->> @repositories
       (filter #(let [v (val %)]
                  (and (not-empty (:owner_url v))
                       (not-empty (:readme (:files (:metadata v))))
                       (not (:archived v)))))
       (map (fn [[_ v]]
              (let [d     (:description v)
                    dd    (if (not-empty d) (subs d 0 (min (count d) 200)) "")
                    fn    (:full_name v)
                    n     (or (last (re-matches #".+/([^/]+)/?" fn)) fn)
                    files (:files (:metadata v))]
                {:a  (compute-awesome-score v)
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
                 :p  (:platform v)
                 :o  (when-let [[_ host owner]
                                (re-matches (re-pattern (str (:hosts urls) "/([^/]+)/owners/([^/]+)"))
                                            (:owner_url v))]
                       (let [host (if (= host "GitHub") "github.com" host)]
                         (str "https://" host "/" owner)))})))
       json/generate-string
       (spit "repositories.json")))

(defn output-latest-repositories-xml []
  (->> @repositories
       (filter #(:created_at (val %)))
       (sort-by #(clojure.instant/read-instant-date (:created_at (val %))))
       reverse
       (take 10)
       (map (fn [[r r-data]]
              (let [name (let [fn (:full_name r-data)] (or (last (re-matches #".+/([^/]+)/?" fn)) fn))]
                {:title       (str "Nouveau dépôt de code source dans code.gouv.fr : " name)
                 :link        (:html_url r-data)
                 :guid        r
                 :description (:description r-data)
                 :pubDate     (toInst (:created_at r-data))})))
       (rss/channel-xml
        {:title       "code.gouv.fr/sources - Nouveaux dépôts de code source"
         :link        "https://code.gouv.fr/data/latest-repositories.xml"
         :description "code.gouv.fr/sources - Nouveaux dépôts de code source"})
       (spit "latest-repositories.xml")))

(defn output-forges-csv []
  (shell/sh "rm" "-f" "forges.csv")
  (doseq [{:keys [name kind]} @hosts]
    (let [n (if (= "GitHub" name) "github.com" name)]
      (spit "forges.csv" (str n "," kind "\n") :append true))))

;; Main execution
(defn -main [args]
  (let [{:keys [test-msg] :as opts}
        (cli/parse-opts args {:spec cli-options})]
    (println test-msg)
    (fetch-hosts)
    (let [annuaire      (fetch-annuaire)
          annuaire-tops (fetch-annuaire-tops)]
      (fetch-owners)
      (fetch-repos)
      (fetch-public-sector-forges)
      (process-owners)
      (add-pso-top-id annuaire annuaire-tops)
      (output-owners-json)
      (output-latest-owners-xml)
      (output-repositories-json)
      (output-latest-repositories-xml)
      (output-forges-csv))
    (log/info "Hosts:" (count @hosts))
    (log/info "Owners:" (count @owners))
    (log/info "Repositories:" (count @repositories))
    (log/info "Forges:" (count @forges))))

(-main *command-line-args*)
