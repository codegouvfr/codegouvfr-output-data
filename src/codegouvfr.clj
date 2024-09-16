#!/usr/bin/env bb

;; Copyright (c) DINUM, Bastien Guerry
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSE.txt

(deps/add-deps '{:deps {clj-rss/clj-rss {:mvn/version "0.4.0"}}})
(deps/add-deps '{:deps {org.babashka/cli {:mvn/version "0.8.60"}}})
;; (deps/add-deps '{:deps {io.github.lispyclouds/bblgum
;;                         {:git/sha "b1b939ae5ae522a55499a8260b450e8898f77781"}}})

(require '[clj-rss.core :as rss]
         '[clojure.tools.logging :as log]
         '[babashka.cli :as cli]
         ;; '[bblgum.core :as b]
         )

;;; Define CLI options

(def cli-options
  {:help        {:alias :h
                 :desc  "Display this help message"}
   :test        {:alias :t
                 :desc  "Run for testing purpose"}
   :only-owners {:alias :o
                 :desc  "Only fetch/export owners data"}})

(defn show-help
  []
  (println
   (cli/format-opts
    (merge {:spec cli-options} {:order (vec (keys cli-options))}))))

;;; Initialize variables

(def annuaire (atom {}))
(def annuaire_tops (atom {}))
(def hosts (atom ()))
(def forges (atom ()))
(def owners (atom {}))
(def repositories (atom {}))
(def awesome (atom ()))
(def awesome-releases (atom ()))

(def urls {:hosts                      "https://data.code.gouv.fr/api/v1/hosts"
           :sill                       "https://code.gouv.fr/sill/api/sill.json"
           :formations                 "https://code.gouv.fr/data/formations-logiciels-libres.yml"
           :top_organizations          "https://code.gouv.fr/data/top_organizations.yml"
           :comptes-organismes-publics "https://code.gouv.fr/data/comptes-organismes-publics.yml"
           :awesome-codegouvfr         "https://code.gouv.fr/data/awesome-codegouvfr.yml"
           :cnll-providers             "https://annuaire.cnll.fr/api/prestataires-sill.json"
           :cdl-providers              "https://comptoir-du-libre.org/public/export/comptoir-du-libre_export_v1.json"})

;;; Helper functions

(defn toInst
  [^String s]
  (.toInstant (clojure.instant/read-instant-date s)))

(defn map-to-csv [m]
  (let [columns (keys (first m))
        header  (map name columns)
        rows    (mapv #(mapv % columns) m)]
    (cons header rows)))

(defn get-repo-properties [repo_url]
  (->> @repositories
       (filter #(= (str/lower-case (:html_url (val %)))
                   (str/lower-case repo_url)))
       first
       second))

(defn prefix-raw-file [^String awesome-repo]
  (when (not-empty awesome-repo)
    (let [{:keys [html_url full_name default_branch platform]}
          (get-repo-properties awesome-repo)]
      (condp = platform
        "github.com" (format "https://raw.githubusercontent.com/%s/%s/" full_name default_branch)
        "gitlab.com" (format "%s/-/raw/%s/" html_url default_branch)
        "git.sr.ht"  (format "%s/blob/%s/" html_url default_branch)
        (format "%s/-/raw/%s/" html_url default_branch)))))

(defn filter-owners [owners]
  (->> owners
       (filter (fn [[_ v]]
                 (and (not-empty (:html_url v))
                      (> (:repositories_count v) 0))))))

(defn filter-repositories [repositories]
  (->> repositories
       (filter #(let [{:keys [metadata archived owner_url]} (val %)]
                  (and (not-empty owner_url)
                       (not-empty (:readme (:files metadata)))
                       (not archived))))))

(def owners-keys-mapping
  {:a  :location
   :au :icon_url
   :c  :created_at
   :d  :description
   :e  :email
   :f  :floss_policy
   :h  :website
   :id :id
   :l  :login
   :m  :ministry
   :n  :name
   :o  :html_url
   :os :ospo_url
   :p  :forge
   :ps :organization
   :r  :repositories_count
   :s  :followers})

(defn owners-to-output [owners]
  (for [[owner_id {:keys [description repositories_count html_url icon_url name
                          pso_top_id_name login followers created_at floss_policy
                          ospo_url website forge email location pso]}] owners]
    {:a  location
     :au icon_url
     :c  created_at
     :d  (if (not-empty description) (subs description 0 (min (count description) 200)) "")
     :e  email
     :f  floss_policy
     :h  website
     :id owner_id
     :l  login
     :m  pso_top_id_name
     :n  name
     :o  html_url
     :os ospo_url
     :p  forge
     :ps pso
     :r  repositories_count
     :s  followers}))

(defn owners-to-csv []
  (as-> @owners o
    (filter-owners o)
    (owners-to-output o)
    (map #(set/rename-keys % owners-keys-mapping) o)
    (map-to-csv o)))

(defn compute-repository-awesome-score
  [{:keys [metadata template description fork forks_count
           subscribers_count stargazers_count]}]
  (let [files  (:files metadata)
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
     (if template (* medium 2) 0)
     ;; Does the repo have a CONTRIBUTING.md file?
     (if (:contributing files) medium 0)
     ;; Does the repo have a description?
     (if (not-empty description) 0 (- medium))
     ;; Is the repo a fork?
     (if fork (- high) 0)
     ;; Does the repo have many forks?
     (if-let [f forks_count]
       (condp < f
         1000 high
         100  medium
         10   low
         0)
       0)
     ;; Does the repo have many subscribers?
     (if-let [f subscribers_count]
       (condp < f
         100 high
         10  medium
         1   low
         0)
       0)
     ;; Does the repo have many star gazers?
     (if-let [s stargazers_count]
       (condp < s
         1000 high
         100  medium
         10   low
         0)
       0))))

(def repositories-keys-mapping
  {:a  :awesome-score
   :u  :updated_at
   :d  :short_desc
   :f? :fork
   :t? :template
   :c? :contributing
   :p? :publiccode
   :l  :language
   :li :license
   :fn :full_name
   :n  :repo_name
   :f  :forks_count
   :p  :platform
   :o  :owner})

(defn repositories-to-output [repositories]
  (for [[_ {:keys [metadata owner_url description full_name
                   updated_at fork template language
                   license forks_count platform]
            :as   repo_data}] repositories]
    (let [short_desc (if (not-empty description)
                       (subs description 0 (min (count description) 200)) "")
          repo_name  (or (last (re-matches #".+/([^/]+)/?" full_name)) full_name)
          files      (:files metadata)]
      {:a  (compute-repository-awesome-score repo_data)
       :u  updated_at
       :d  short_desc
       :f? fork
       :t? template
       :c? (false? (empty? (:contributing files)))
       :p? (false? (empty? (:publiccode files)))
       :l  language
       :li license
       :fn full_name
       :n  repo_name
       :f  forks_count
       :p  platform
       :o  (when-let [[_ host owner]
                      (re-matches
                       (re-pattern (str (:hosts urls) "/([^/]+)/owners/([^/]+)"))
                       owner_url)]
             (let [host (if (= host "GitHub") "github.com" host)]
               (str "https://" host "/" owner)))})))

(defn repositories-to-csv []
  (as-> @repositories r
    (filter-repositories r)
    (repositories-to-output r)
    (map #(set/rename-keys % repositories-keys-mapping) r)
    (map-to-csv r)))

;;; Fetching functions

(defn fetch-json [url]
  (let [res (try (curl/get url)
                 (catch Exception _
                   (log/error "Failed to fetch JSON from" url)))]
    (when (= (:status res) 200)
      (json/parse-string (:body res) true))))

(defn fetch-yaml [url]
  (let [res (try (curl/get url)
                 (catch Exception _
                   (log/error "Failed to fetch YAML from" url)))]
    (when (= (:status res) 200)
      (yaml/parse-string (:body res) :keywords false))))

(defn fetch-annuaire-zip []
  (log/info "Fetching annuaire as a zip file from data.gouv.fr...")
  (let [annuaire-zip-url "https://www.data.gouv.fr/fr/datasets/r/d0158eb2-6772-49c2-afb1-732e573ba1e5"
        stream           (-> (curl/get annuaire-zip-url {:as :bytes})
                             :body
                             (io/input-stream)
                             (java.util.zip.ZipInputStream.))]
    (.getNextEntry stream)
    (log/info "Output annuaire.json")
    (io/copy stream (io/file "annuaire.json"))))

;;; Set annuaire, hosts, owners, repositories and public forges

(defn get-name-from-annuaire-id [^String id]
  (:nom (get @annuaire id)))

(defn add-service-sup! []
  (log/info "Adding service_sup...")
  (doseq [[s_id s_data] (filter #(< 0 (count (:hierarchie (val %)))) @annuaire)]
    (doseq [b (filter #(= (:type_hierarchie %) "Service Fils") (:hierarchie s_data))]
      (swap! annuaire update-in [(:service b)]
             conj
             {:service_sup_id  s_id
              :service_sup_nom (get-name-from-annuaire-id s_id)}))))

(defn get-ancestor [service_sup_id]
  (let [seen (atom #{})]
    (loop [s_id service_sup_id]
      (let [sup (:service_sup_id (get @annuaire s_id))]
        (if (or (nil? sup)
                (contains? @seen s_id)
                (some #{s_id} @annuaire_tops))
          s_id
          (do (swap! seen conj s_id)
              (recur sup)))))))

(defn add-service-top! []
  (doseq [[s_id s_data] (filter #(seq (:service_sup_id (val %))) @annuaire)]
    (let [ancestor (get-ancestor (:service_sup_id s_data))]
      (swap! annuaire
             update-in
             [s_id]
             conj
             {:service_top_id   ancestor
              :service_top_name (get-name-from-annuaire-id ancestor)}))))

(defn set-annuaire! []
  ;; First download annuaire.json
  (fetch-annuaire-zip)
  ;; Then set the @annuaire atom with a subset of annuaire.json
  (->> (json/parse-string (slurp "annuaire.json") true)
       :service
       (map (fn [a] [(:id a) (select-keys a [:hierarchie :nom :sigle])]))
       (into {})
       (reset! annuaire))
  ;; Then set annuaire tops
  (when-let [res (fetch-yaml (:top_organizations urls))]
    (reset! annuaire_tops (into #{} (keys res))))
  ;; Update annuaire with services sup and top
  (add-service-sup!)
  (add-service-top!))

(defn set-hosts! [& [opts]]
  (log/info "Fetching hosts from" (:hosts urls))
  (let [res (or (fetch-json (:hosts urls)) ())]
    (reset! hosts
            (if (or (:test opts) (:only-owners opts))
              (take 2 (shuffle res))
              res))))

(defn update-owners! []
  (doseq [[f forge-data] @forges]
    (let [f (if (= f "github.com") "github" f)]
      (if-let [groups (get forge-data "owners")]
        (doseq [[group group-data] groups]
          (let [owner_url
                (str/lower-case
                 (format (str (:hosts urls) "/%s/owners/%s") f group))
                {:strs [pso pso_id floss_policy ospo_url]} group-data]
            (swap! owners update-in [owner_url]
                   #(assoc %
                           :pso pso
                           :pso_id pso_id
                           :floss_policy floss_policy
                           :ospo_url ospo_url
                           :forge (get forge-data "forge")))))
        (doseq [[k _] (filter #(str/includes? (key %) (str/lower-case f)) @owners)]
          (let [{:strs [pso pso_id floss_policy ospo_url forge]} forge-data]
            (swap! owners update-in [k]
                   #(assoc %
                           :pso pso
                           :pso_id pso_id
                           :floss_policy floss_policy
                           :ospo_url ospo_url
                           :forge forge)))))))
  ;; Add top_id and top_id_name to owners
  (doseq [[k {:keys [pso_id]}] (filter #(:pso_id (val %)) @owners)]
    (let [top_id      (if (some #{pso_id} @annuaire_tops)
                        pso_id
                        (:service_top_id (get @annuaire pso_id)))
          top_id_name (:nom (get @annuaire top_id))]
      (swap! owners update-in [k]
             conj {:pso_top_id      top_id
                   :pso_top_id_name top_id_name}))))

(defn set-owners! []
  ;; Set owners by fetching data
  (doseq [{:keys [owners_url]} @hosts]
    (let [url (str owners_url "?per_page=1000")]
      (when-let [data (fetch-json url)]
        (log/info "Fetching owners data from" url)
        (doseq [e (filter #(= (:kind %) "organization") data)]
          (swap! owners assoc
                 (str/lower-case (:owner_url e))
                 (dissoc e :owner_url))))))
  ;; Update owners by using forge data
  (update-owners!))

(defn set-repos! []
  (doseq [{:keys [repositories_url repositories_count url]} @hosts]
    (dotimes [n (int (clojure.math/floor (+ 1 (/ (- repositories_count 1) 1000))))]
      (let [repos-url (str repositories_url (format "?page=%s&per_page=1000" (+ n 1)))
            platform  (last (re-matches #"^https://([^/]+)/?$" (or url "")))]
        (when-let [data (try (fetch-json repos-url)
                             (catch Exception e
                               (log/error "Error fetching repos from" (.getMessage e))))]
          (log/info "Fetching repos data from" repos-url)
          (doseq [e data]
            (swap! repositories assoc
                   (str/lower-case (:repository_url e))
                   (-> e
                       (assoc :platform platform)
                       (dissoc :repository_url)))))))))

(defn set-public-sector-forges! []
  (log/info "Fetching public sector forges from comptes-organismes-publics.yml")
  (when-let [res (fetch-yaml (:comptes-organismes-publics urls))]
    (reset! forges res)))

(defn set-awesome! []
  (log/info "Fetching public sector forges from comptes-organismes-pubics.yml")
  (when-let [res (fetch-yaml (:awesome-codegouvfr urls))]
    (reset! awesome res)))

(defn update-awesome! []
  (as-> @awesome a
    (for [[k v] a]
      (when-let [pfx (not-empty (prefix-raw-file (str/lower-case k)))]
        (if-let [res (fetch-yaml (str pfx "publiccode.yml"))]
          [k (conj v res)]
          [k v])))
    (reset! awesome a)))

(defn set-awesome-releases! []
  (->> @awesome
       (into {})
       (map #(take 3
                   (let [r        (key %)
                         m        (re-matches #".+/([^/]+)/([^/]+)/?" r)
                         r_name   (last m)
                         r_o_name (second m)]
                     (map
                      (fn [r] (assoc r :repo_name (str r_name " (" r_o_name ")")))
                      (when-let [rel_url (:releases_url (get-repo-properties r))]
                        (fetch-json rel_url))))))
       flatten
       (filter seq)
       (reset! awesome-releases)))

;;; Output functions

(defn output-annuaire-sup []
  (log/info "Output annuaire_sup.json...")
  (spit "annuaire_sup.json"
        (json/generate-string
         (for [[k v] @annuaire]
           (conj (dissoc (into {} v) :hierarchie) {:id k})))))

(defn output-awesome-json []
  (->> @awesome
       (map (fn [[k v]] (assoc v :url k)))
       flatten
       json/generate-string
       (spit "awesome.json")))

(defn output-releases-json []
  (spit "releases.json" (json/generate-string @awesome-releases)))

(defn output-owners-json [& [extended]]
  (as-> @owners o
    (filter-owners o)
    (owners-to-output o)
    (mapv identity o)
    (if-not extended o (map #(set/rename-keys % owners-keys-mapping) o))
    (json/generate-string o)
    (spit (if extended "owners_full.json" "owners.json") o)))

(defn output-owners-csv []
  (with-open [file (io/writer "owners.csv")]
    (csv/write-csv file (owners-to-csv))))

(defn output-repositories-csv []
  (with-open [file (io/writer "repositories.csv")]
    (csv/write-csv file (repositories-to-csv))))

(defn output-latest-sill-xml []
  (->> (fetch-json (:sill urls))
       (sort-by #(java.util.Date. (:referencedSinceTime %)))
       reverse
       (take 10)
       (map #(let [link (str "https://code.gouv.fr/sill/detail?name=" (:name %))]
               {:title       (str "Nouveau logiciel au SILL : " (:name %))
                :link        link
                :guid        link
                :description (:function %)
                :pubDate     (.toInstant (java.util.Date. (:referencedSinceTime %)))}))
       (rss/channel-xml
        {:title       "code.gouv.fr - Nouveaux logiciels libres au SILL - New SILL entries"
         :link        "https://code.gouv.fr/data/latest-sill.xml"
         :description "code.gouv.fr - Nouveaux logiciels libres au SILL - New SILL entries"})
       (spit "latest-sill.xml")))

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

(defn output-latest-releases-xml []
  (->> @awesome-releases
       (sort-by #(clojure.instant/read-instant-date (:published_at %)))
       reverse
       (take 10)
       (map (fn [{:keys [name repo_name html_url uuid body published_at]}]
              {:title       (format "Nouvelle version de %s : %s" repo_name name)
               :link        html_url
               :guid        uuid
               :description body
               :pubDate     (toInst published_at)}))
       (rss/channel-xml
        {:title       "code.gouv.fr/sources - Nouvelles versions Awesome"
         :link        "https://code.gouv.fr/data/latest-releases.xml"
         :description "code.gouv.fr/sources - Nouvelles versions Awesome"})
       (spit "latest-releases.xml")))

(defn output-repositories-json [& [extended]]
  (as-> @repositories r
    (filter-repositories r)
    (repositories-to-output r)
    (if-not extended r (map #(set/rename-keys % repositories-keys-mapping) r))
    (json/generate-string r)
    (spit (if extended "repositories_full.json" "repositories.json") r)))

(defn output-latest-repositories-xml []
  (->> @repositories
       (filter #(:created_at (val %)))
       (sort-by #(clojure.instant/read-instant-date (:created_at (val %))))
       reverse
       (take 10)
       (map (fn [[r r-data]]
              (let [name (let [fn (:full_name r-data)]
                           (or (last (re-matches #".+/([^/]+)/?" fn)) fn))]
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

(defn get-top-owners-by [k]
  (->> @owners
       (filter #(when-let [s (get (val %) k)] (> s 1)))
       (map #(let [v (val %)]
               (hash-map (str (:name v) " (" (:forge v) ")")
                         (get v k))))
       (into {})
       (sort-by val)
       reverse
       (take 10)))

(defn get-top-x [k]
  (let [m (filter k (vals @repositories))]
    (->> m
         (group-by k)
         (map (fn [[k v]] {k (* 100 (/ (* (count v) 1.0) (count m)))}))
         (into {})
         (sort-by val)
         reverse
         (take 10))))

(defn output-stats-json []
  (let [repositories_cnt (filter int? (map #(:repositories_count (val %)) @owners))]
    (->> {:repos_cnt         (str (count @repositories))
          :orgas_cnt         (str (count @owners))
          :avg_repos_cnt     (format "%.2f" (/ (reduce + repositories_cnt) (* 1.0 (count repositories_cnt))))
          :top_orgs_by_stars (get-top-owners-by :total_stars)
          :top_orgs_by_repos (get-top-owners-by :repositories_count)
          :top_licenses      (get-top-x :license)
          :top_languages     (get-top-x :language)}
         json/generate-string
         (spit "stats.json"))))

(defn output-formations-json []
  (when-let [res (fetch-yaml (:formations urls))]
    (->> res
         json/generate-string
         (spit "formations-logiciels-libres.json"))))

(defn output-sill-providers []
  (let [cdl  (->> (fetch-json (:cdl-providers urls))
                  :softwares
                  (filter #(not-empty (:sill (:external_resources %))))
                  (map (juxt #(:id (:sill (:external_resources %)))
                             #(keep (juxt :name :url
                                          (fn [a] (:website (:external_resources a))))
                                    (:providers %))))
                  (map (fn [[a b]]
                         [a (into [] (map (fn [[x y z]]
                                            {:nom x :cdl_url y :website z}) b))])))
        cnll (->> (fetch-json (:cnll-providers urls))
                  (map (juxt :sill_id
                             #(map (fn [p] (set/rename-keys p {:url :cnll_url}))
                                   (:prestataires %)))))]
    (->> (group-by first (concat cdl cnll))
         (map (fn [[id l]] [id (flatten (merge (map second l)))]))
         (filter #(not-empty (second %)))
         (map (fn [[id l]]
                {:sill_id      id
                 :prestataires (map #(apply merge (val %))
                                    (group-by #(str/lower-case (:nom %)) l))}))
         (sort-by :sill_id)
         json/generate-string
         (spit  "sill-prestataires.json"))))

(defn output-sill-latest-xml []
  (when-let [sill (fetch-json (:sill urls))]
    (->> sill
         (filter #(:referencedSinceTime %))
         (sort-by #(java.util.Date. (:referencedSinceTime %)))
         reverse
         (take 10)
         (map (fn [item]
                (let [link (str "https://code.gouv.fr/sill/detail?name=" (:name item))]
                  {:title       (str "Nouveau logiciel au SILL : " (:name item))
                   :link        link
                   :guid        link
                   :description (:function item)
                   :pubDate     (toInst (str (java.time.Instant/ofEpochMilli (:referencedSinceTime item))))})))
         (rss/channel-xml
          {:title       "code.gouv.fr - Nouveaux logiciels libres au SILL - New SILL entries"
           :link        "https://code.gouv.fr/data/latest-sill.xml"
           :description "code.gouv.fr - Nouveaux logiciels libres au SILL - New SILL entries"})
         (spit "latest-sill.xml"))))

;; Testing
;; (defn b-display-owners []
;;   (b/gum :table :in (clojure.java.io/input-stream "owners.csv" :height 10)))

(defn set-data! [{:keys [only-owners] :as opts}]
  (set-public-sector-forges!)
  (set-hosts! opts)
  (set-owners!)
  (when-not only-owners
    (set-annuaire!)
    (set-repos!)
    (set-awesome!)
    (set-awesome-releases!)
    (update-awesome!)))

(defn output-data! [only-owners]
  (output-owners-json)
  (output-owners-json :extended)
  (output-owners-csv)
  (when-not only-owners
    (output-annuaire-sup)
    (output-latest-sill-xml)
    (output-latest-owners-xml)
    (output-repositories-json)
    (output-repositories-json :extended)
    (output-repositories-csv)
    (output-latest-repositories-xml)
    (output-latest-releases-xml)
    (output-forges-csv)
    (output-stats-json)
    (output-awesome-json)
    (output-releases-json)
    (output-formations-json)
    (output-sill-providers)
    (output-sill-latest-xml)))

(defn display-data! [only-owners]
  (log/info "Hosts:" (count @hosts))
  (log/info "Owners:" (count @owners))
  (when-not only-owners
    (log/info "Repositories:" (count @repositories))
    (log/info "Awesome codegouvfr:" (count @awesome))
    (log/info "Awesome releases:" (count @awesome-releases))))

;; Main execution
(defn -main [args]
  (let [{:keys [only-owners] :as opts}
        (cli/parse-opts args {:spec cli-options})]
    (if (or (:help opts) (:h opts))
      (println (show-help))
      (do (set-data! opts)
          (output-data! only-owners)
          (display-data! only-owners)))))

(-main *command-line-args*)
