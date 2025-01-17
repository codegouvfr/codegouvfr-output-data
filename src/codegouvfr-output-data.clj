#!/usr/bin/env bb

;; Copyright (c) DINUM, Bastien Guerry
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSE.txt

(deps/add-deps
 '{:deps {clj-rss/clj-rss          {:mvn/version "0.4.0"}
          org.babashka/cli         {:mvn/version "0.8.60"}
          org.babashka/http-client {:mvn/version "0.3.11"}}})

(require '[clj-rss.core :as rss]
         '[clojure.tools.logging :as log]
         '[babashka.cli :as cli]
         '[clojure.walk :as walk]
         '[babashka.http-client :as http])

;;; Define CLI options

(def cli-options
  {:help {:alias :h
          :desc  "Display this help message"}
   :test {:alias :t
          :desc  "Test with a limited number of hosts (2 by default)"}})

(defn- show-help
  []
  (println
   (cli/format-opts
    (merge {:spec cli-options} {:order (vec (keys cli-options))}))))

;;; Initialize variables

(defonce short_desc_size 120)
(defonce cli-opts (atom nil))
(defonce annuaire (atom {}))
(defonce annuaire_tops (atom {}))
(defonce hosts (atom ()))
(defonce forges (atom ()))
(defonce owners (atom {}))
(defonce repositories (atom {}))
(defonce awesome-data (atom nil))
(defonce awesome (atom {}))
(defonce ospos (atom {}))
(defonce awesome-releases (atom {}))

(defonce urls
  {:hosts                      "https://ecosystem.code.gouv.fr/api/v1/hosts"
   :sill                       "https://code.gouv.fr/sill/api/sill.json"
   :formations                 "https://code.gouv.fr/data/formations-logiciels-libres.yml"
   :top_organizations          "https://code.gouv.fr/data/top_organizations.yml"
   :comptes-organismes-publics "https://code.gouv.fr/data/comptes-organismes-publics.yml"
   :fr-public-sector-ospo      "https://code.gouv.fr/data/fr-public-sector-ospo.yml"
   :awesome-codegouvfr         "https://code.gouv.fr/data/awesome-codegouvfr.yml"
   :cnll-providers             "https://annuaire.cnll.fr/api/prestataires-sill.json"
   :cdl-providers              "https://comptoir-du-libre.org/public/export/comptoir-du-libre_export_v1.json"})

;;; Helper functions

(defn- shorten-string [^String s]
  (if (> (count s) short_desc_size)
    (str (subs s 0 short_desc_size) "…")
    s))

(defn- replace-vals [m v r]
  (walk/postwalk #(if (= % v) r %) m))

(defn- to-inst
  [^String s]
  (.toInstant (clojure.instant/read-instant-date s)))

(defn- maps-to-csv [m]
  (let [columns (keys (first m))
        header  (map name columns)
        rows    (mapv #(mapv % columns) m)]
    (cons header rows)))

(defn- get-repo-properties [repo_html_url]
  (->> @repositories
       (filter #(= (str/lower-case (:html_url (val %)))
                   (str/lower-case repo_html_url)))
       first
       second))

(defn- get-publiccode-url [^String awesome-repo]
  (let [{:keys [html_url full_name default_branch platform]}
        (get-repo-properties awesome-repo)]
    (when-let [prefix-url
               (condp = platform
                 "github.com" (format "https://raw.githubusercontent.com/%s/%s/"
                                      full_name default_branch)
                 "git.sr.ht"  (format "%s/blob/%s/" html_url default_branch)
                 "gitlab.com" (format "%s/-/raw/%s/" html_url default_branch)
                 nil)]
      (str prefix-url "publiccode.yml"))))

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
   :os :ospo_url
   :p  :forge
   :ps :organization
   :r  :repositories_count
   :s  :followers})

(defn- owners-as-map [owners-init & [full?]]
  (as-> (filter
         (fn [[_ v]] (and (not-empty (:html_url v))
                          (when-let [repos_cnt (:repositories_count v)]
                            (> repos_cnt 0))))
         owners-init) owners
    (for [[_ {:keys [description repositories_count html_url icon_url name
                     pso_top_id_name login followers created_at floss_policy
                     ospo_url website forge email location pso]}] owners]
      (conj
       (let [short_desc (when (not-empty description) (shorten-string description))]
         {:au icon_url
          :d  (if full? description short_desc)
          :f  floss_policy
          :h  website
          :id html_url
          :l  login
          :m  pso_top_id_name
          :n  name
          :os ospo_url
          :ps pso
          :r  repositories_count
          :s  (or followers 0)})
       (when full? {:a location :e email :c created_at :p forge})))
    (replace-vals owners nil "")))

(defn- owners-to-csv []
  (->> (owners-as-map @owners :full)
       (map #(set/rename-keys % owners-keys-mapping))
       maps-to-csv))

(defn- compute-repo-score
  [{:keys [metadata template description fork forks_count archived subscribers_count]}]
  (let [files  (:files metadata)
        high   1000
        medium 100
        low    10]
    (+
     ;; Does the repo have a known license?
     (let [license (:license files)]
       (if (and (not-empty license) (not (= "other" license))) high 0))
     ;; Is the repo a template?
     (if template high 0)
     ;; Does the repo have a publiccode.yml file?
     (if (not-empty (:publiccode files)) high 0)
     ;; Does the repo have a README?
     (if (not-empty (:readme files)) medium 0)
     ;; Does the repo have a CONTRIBUTING.md file?
     (if (not-empty (:contributing files)) medium 0)
     ;; Does the repo have a CHANGELOG.md file?
     (if (not-empty (:changelog files)) low 0)
     ;; Does the repo have a description?
     (if (not-empty description) 0 (- medium))
     ;; Is the repo archived?
     (if (or archived false) (- high) 0)
     ;; Is the repo a fork?
     (if fork (- high) 0)
     ;; Does the repo have many forks?
     (if-let [f forks_count]
       (condp < f 100 high 10 medium 1 low 0) 0)
     ;; Does the repo have many subscribers?
     (if-let [f subscribers_count]
       (condp < f 100 high 10 medium 1 low 0) 0))))

(def repositories-keys-mapping
  {:a  :awesome-score
   :a? :archived
   :u  :updated_at
   :d  :description
   :id :id
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

(defn- repositories-as-map [repos-init & [full?]]
  (as-> (filter #(not-empty (:owner_url (val %))) repos-init) repos
    (for [[_ {:keys [metadata owner_url description full_name
                     updated_at fork template language html_url
                     license forks_count archived platform]
              :as   repo_data}] repos]
      (let [short_desc (when (not-empty description) (shorten-string description))
            repo_name  (or (last (re-matches #".+/([^/]+)/?" full_name)) full_name)
            files      (:files metadata)]
        (conj
         {:a  (compute-repo-score repo_data)
          :a? (or archived false)
          :c? (false? (empty? (:contributing files)))
          :d  (if full? description short_desc)
          :f  (if (int? forks_count) forks_count 0)
          :fn full_name
          :f? (or fork false)
          :l  language
          :li license
          :n  repo_name
          :o  (when-let [[_ host owner]
                         (re-matches
                          (re-pattern (str (:hosts urls) "/([^/]+)/owners/([^/]+)"))
                          owner_url)]
                (let [host (if (= host "GitHub") "github.com" host)]
                  (str "https://" host "/" owner)))
          :p  platform
          :p? (false? (empty? (:publiccode files)))
          :t? (or template false)
          :u  updated_at}
         (when full? {:id html_url}))))
    (if full? repos (filter #(>= (:a %) 0) repos))
    (replace-vals repos nil "")))

(defn- repositories-to-csv []
  (->> (repositories-as-map @repositories :full)
       (map #(set/rename-keys % repositories-keys-mapping))
       maps-to-csv))

;;; Fetching functions

(defn- get-url [url]
  (try (http/get url {:connection-timeout 30000})
       (catch Exception _
         (log/error "Failed to fetch " url))))

(defn- get-urls [urls]
  (when-let
      [data (doall (map #(http/get % {:async              true
                                      :throw              false
                                      :connection-timeout 30000})
                        urls))]
    (doall (map (comp :body deref) data))))

(defn- get-urls-json [urls & [msg]]
  (when msg (log/info msg))
  (when-let [data (get-urls urls)]
    (flatten
     (map #(try (json/parse-string % true)
                (catch Exception _
                  (log/error "Failed to fetch json data")))
          data))))

(defn- get-urls-yaml [urls & [msg]]
  (when msg (log/info msg))
  (when-let [data (get-urls urls)]
    (flatten (map #(yaml/parse-string % :keywords false) data))))

(defn- fetch-yaml [url]
  (log/info "Fetching yaml data from" url)
  (when-let [res (get-url url)]
    (when (= (:status res) 200)
      (yaml/parse-string (:body res) :keywords false))))

(defn- fetch-json [url]
  (log/info "Fetching json data from" url)
  (when-let [res (get-url url)]
    (when (= (:status res) 200)
      (json/parse-string (:body res) true))))

(defn- fetch-annuaire-zip []
  (log/info "Fetching annuaire as a zip file from data.gouv.fr...")
  (let [annuaire-zip-url "https://www.data.gouv.fr/fr/datasets/r/d0158eb2-6772-49c2-afb1-732e573ba1e5"
        stream           (-> (http/get annuaire-zip-url
                                       {:as :bytes :connection-timeout 30000})
                             :body
                             (io/input-stream)
                             (java.util.zip.ZipInputStream.))]
    (.getNextEntry stream)
    (log/info "Output annuaire.json")
    (io/copy stream (io/file "annuaire.json"))))

;;; Set annuaire, hosts, owners, repositories and public forges

(defn- get-name-from-annuaire-id [^String id]
  (:nom (get @annuaire id)))

(defn- add-service-sup! []
  (log/info "Adding service_sup...")
  (doseq [[s_id s_data] (filter #(< 0 (count (:hierarchie (val %)))) @annuaire)]
    (doseq [b (filter #(= (:type_hierarchie %) "Service Fils") (:hierarchie s_data))]
      (swap! annuaire update-in [(:service b)]
             conj
             {:service_sup {:id s_id :nom (get-name-from-annuaire-id s_id)}}))))

(defn- get-ancestor [service_sup_id]
  (let [seen (atom #{})]
    (loop [s_id service_sup_id]
      (let [sup (:id (:service_sup (get @annuaire s_id)))]
        (if (or (nil? sup)
                (contains? @seen s_id)
                (some #{s_id} @annuaire_tops))
          s_id
          (do (swap! seen conj s_id)
              (recur sup)))))))

(defn- add-service-top! []
  (doseq [[s_id s_data] (filter #(seq (:id (:service_sup (val %)))) @annuaire)]
    (let [ancestor (get-ancestor (:id (:service_sup s_data)))]
      (swap! annuaire update-in [s_id]
             conj
             {:service_top {:id ancestor :nom (get-name-from-annuaire-id ancestor)}}))))

(defn- set-annuaire! []
  ;; First download annuaire.json
  (fetch-annuaire-zip)
  (->> (json/parse-string (slurp "annuaire.json") true)
       :service
       (map (fn [a] [(:id a) a]))
       (into {})
       (reset! annuaire))
  ;; Then set annuaire tops
  (when-let [res (fetch-yaml (:top_organizations urls))]
    (reset! annuaire_tops (into #{} (keys res))))
  ;; Update annuaire with services sup and top
  (add-service-sup!)
  (add-service-top!))

(defn- set-hosts! []
  (when-let [res (fetch-json (:hosts urls))]
    (reset! hosts
            (if-let [test-opt (:test @cli-opts)]
              (take (if (int? test-opt) test-opt 2)
                    (shuffle res))
              res))))

(defn- update-owners! []
  (doseq [[f forge-data] @forges]
    (let [f (if (= f "github.com") "github" f)]
      (if-let [groups (get forge-data "owners")]
        (doseq [[group group-data] groups]
          (let [owner_url
                (str/lower-case
                 (format (str (:hosts urls) "/%s/owners/%s") f group))
                {:strs [pso_id floss_policy ospo_url]} group-data]
            (swap! owners update-in [owner_url]
                   #(assoc %
                           :pso (get-name-from-annuaire-id pso_id)
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
    (let [top_id      (or (some #{pso_id} @annuaire_tops)
                          (:id (:service_top (get @annuaire pso_id))))
          top_id_name (:nom (get @annuaire top_id))]
      (swap! owners update-in [k]
             conj {:pso_top_id      top_id
                   :pso_top_id_name top_id_name}))))

(defn- set-owners! []
  ;; Set owners by fetching data
  ;; FIXME: Handle case when there are more than 1000 owners per host
  (let [data (get-urls-json (map #(str (:owners_url %) "?per_page=1000")
                                 @hosts) "Fetching owners data...")]
    (doseq [o (filter #(= (:kind %) "organization") data)]
      (swap! owners assoc
             (str/lower-case (:owner_url o))
             (dissoc o :owner_url)))))

(defn- hosts-to-query-urls [data]
  (->> data
       (sequence
        (comp
         (map #(select-keys % [:repositories_url :repositories_count :url]))
         (map #(for [n (range (Math/ceil (/ (:repositories_count %) 1000)))]
                 (str (:repositories_url %)
                      "?page=" (+ n 1)
                      "&per_page=" (if (:test @cli-opts) "10" "1000"))))))
       flatten))

(defn- set-repos! []
  (let [data (get-urls-json (hosts-to-query-urls @hosts) "Fetching repositories data...")]
    (doseq [r data]
      (swap! repositories assoc
             (str/lower-case (:repository_url r))
             (-> r
                 (assoc :platform
                        (last (re-matches #"^https://([^/]+).*$" (or (:html_url r) ""))))
                 (dissoc :repository_url))))))

(defn- set-public-sector-forges! []
  (when-let [res (fetch-yaml (:comptes-organismes-publics urls))]
    (reset! forges res)))

(defn- set-ospos! []
  (when-let [res (fetch-yaml (:fr-public-sector-ospo urls))]
    (reset! ospos res)))

(defn- set-awesome! []
  (let [awes (fetch-yaml (:awesome-codegouvfr urls))]
    (reset! awesome-data (get-urls-yaml (remove nil? (map get-publiccode-url (keys awes)))))))

(defn- update-awesome! []
  (doseq [p @awesome-data]
    (let [repo_url (str/lower-case (get p "url"))]
      (swap! awesome assoc repo_url
             (conj p {"releases_url" (:releases_url (get-repo-properties repo_url))})))))

(defn- set-awesome-releases! []
  (let [data (->> @awesome
                  (map #(str (get (val %) "releases_url") "?per_page=3"))
                  (filter #(re-matches #"^https://.*" %))
                  get-urls-json
                  (map #(select-keys % [:url :name :html_url :tag_name :body :published_at]))
                  (map #(assoc % :repo_name
                               (last (re-matches #"https://[^/]+/([^/]+/[^/]+).*" (:html_url %)))))
                  (map #(update-in % [:body] (fn [s] (shorten-string s)))))]
    (doseq [[k v] @awesome]
      (let [rels (filter #(str/includes? (str/lower-case (:html_url %)) k) data)]
        (swap! awesome assoc k (conj v {"releases" rels}))))))

;;; Output functions

(defn- output-annuaire-sup []
  (log/info "Output annuaire_sup.json...")
  (spit "annuaire_sup.json"
        (json/generate-string
         (for [[k v] @annuaire]
           (conj (dissoc (into {} v) :hierarchie) {:id k})))))

(defn- output-awesome-json []
  (log/info "Output awesome.json...")
  (->> @awesome
       vals
       flatten
       json/generate-string
       (spit "awesome.json")))

(defn- output-ospos-json []
  (log/info "Output fr-ospos.json...")
  (spit "fr-ospos.json"
        (json/generate-string (for [[k v] @ospos] (conj v {:url k})))))

(defonce awesome-codegouvfr-md-format-string
  "# Awesome code.gouv.fr

A list of [awesome code.gouv.fr projects](https://code.gouv.fr/fr/awesome) funded by French public organizations.

%s

# License

DINUM, Bastien Guerry.

This list is published under Licence Ouverte 2.0 and CC BY.")

(defn- output-awesome-md []
  (log/info "Output awesome-codegouvfr.md...")
  (->> (for [[_ {:strs [name url description ]}]
             (sort-by #(get (second %) "name") @awesome)]
         (let [desc (or (not-empty (get-in description ["en" "shortDescription"]))
                        (not-empty (get-in description ["fr" "shortDescription"]))
                        "N/A")]
           (format "- [%s](%s) - %s" name url (str/trim desc))))
       (str/join "\n")
       (format awesome-codegouvfr-md-format-string)
       (spit "awesome-codegouvfr.md")))

(defn- output-owners-json [& [full?]]
  (log/info "Output codegouvfr-organizations.json...")
  (as-> (owners-as-map @owners full?) owners
    (mapv identity owners)
    (if-not full? owners (map #(set/rename-keys % owners-keys-mapping) owners))
    (json/generate-string owners)
    (spit (if full? "codegouvfr-organizations.json" "owners.json") owners)))

(defn- output-owners-csv []
  (log/info "Output codegouvfr-organizations.csv...")
  (with-open [file (io/writer "codegouvfr-organizations.csv")]
    (csv/write-csv file (owners-to-csv))))

(defn- output-repositories-csv []
  (log/info "Output codegouvfr-repositories.csv...")
  (with-open [file (io/writer "codegouvfr-repositories.csv")]
    (csv/write-csv file (repositories-to-csv))))

(defn- output-latest-sill-xml []
  (log/info "Output latest-sill.xml...")
  (->> (fetch-json (:sill urls))
       (sort-by #(java.util.Date. (:referencedSinceTime %)))
       reverse
       (take 10)
       (map #(let [link (str "https://code.gouv.fr/sill/detail?name=" (:name %))]
               {:title       (str "Nouveau logiciel au SILL : " (:name %))
                :link        link
                :guid        link
                :description (:description %)
                :pubDate     (.toInstant (java.util.Date. (:referencedSinceTime %)))}))
       (rss/channel-xml
        {:title       "code.gouv.fr - Nouveaux logiciels libres au SILL - New SILL entries"
         :link        "https://code.gouv.fr/data/latest-sill.xml"
         :description "code.gouv.fr - Nouveaux logiciels libres au SILL - New SILL entries"})
       (spit "latest-sill.xml")))

(defn- output-latest-releases-xml []
  (log/info "Output latest-releases.xml...")
  (->> @awesome
       vals
       (map #(get % "releases"))
       flatten
       (sort-by #(clojure.instant/read-instant-date (:published_at %)))
       reverse
       (take 10)
       (map (fn [{:keys [name repo_name html_url body published_at]}]
              {:title       (format "Nouvelle version de %s : %s" repo_name name)
               :link        html_url
               :guid        html_url
               :description body
               :pubDate     (to-inst published_at)}))
       (rss/channel-xml
        {:title       "code.gouv.fr/sources - Nouvelles versions Awesome"
         :link        "https://code.gouv.fr/data/latest-releases.xml"
         :description "code.gouv.fr/sources - Nouvelles versions Awesome"})
       (spit "latest-releases.xml")))

(defn- output-repositories-json [& [full]]
  (let [file-name (if full "codegouvfr-repositories.json" "repos_preprod.json")]
    (log/info (format "Output %s..." file-name))
    (as-> (repositories-as-map @repositories full) repos
      (if-not full repos (map #(set/rename-keys % repositories-keys-mapping) repos))
      (json/generate-string repos)
      (spit file-name repos))))

(defn- output-latest-repositories-xml []
  (log/info "Output latest-repositories.xml...")
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
                 :pubDate     (to-inst (:created_at r-data))})))
       (rss/channel-xml
        {:title       "code.gouv.fr/sources - Nouveaux dépôts de code source"
         :link        "https://code.gouv.fr/data/latest-repositories.xml"
         :description "code.gouv.fr/sources - Nouveaux dépôts de code source"})
       (spit "latest-repositories.xml")))

(defn- output-forges-csv []
  (log/info "Output codegouvfr-forges.csv...")
  (shell/sh "rm" "-f" "codegouvfr-forges.csv")
  (doseq [{:keys [name kind]} @hosts]
    (let [n (if (= "GitHub" name) "github.com" name)]
      (spit "codegouvfr-forges.csv" (str n "," kind "\n") :append true))))

(defn- get-top-owners-by [n k]
  (->> @owners
       (filter #(when-let [s (get (val %) k)] (> s 1)))
       (map #(let [v (val %)] [[(:name v) (:html_url v)] (get v k)]))
       (into {})
       (sort-by val)
       reverse
       (take n)))

(defn- get-top-x [n k & [exclude-re]]
  (->> @repositories
       vals
       (filter #(if-let [xre exclude-re]
                  (when-let [v (not-empty (get % k))]
                    (nil? (re-matches xre v)))
                  (get % k)))
       (group-by k)
       (map (fn [[k v]] {k (count v)}))
       (into {})
       (sort-by val)
       reverse
       (take n)))

(defn- get-top-owners-repos-k [min_repos min_k k]
  (let [owners (filter #(let [v (val %)]
                          (and (int? (get v k))
                               (> (get v k) min_k)
                               (int? (:repositories_count v))
                               (> (:repositories_count v) min_repos)))
                       @owners)]
    (for [[_ v] owners]
      {:owner              (:name v)
       k                   (get v k)
       :repositories_count (:repositories_count v)})))

(defn- get-top-owners-repos-stars [min_repos min_stars]
  (get-top-owners-repos-k min_repos min_stars :total_stars))

(defn- get-top-owners-repos-followers [min_repos min_followers]
  (get-top-owners-repos-k min_repos min_followers :followers))

(defn- get-top-repos-by-score-range []
  (let [score-range (fn [score]
                      (let [lower (* (quot score 100) 100)
                            upper (+ lower 100)]
                        [lower upper]))]
    (->> (repositories-as-map @repositories)
         (map :a)
         (group-by score-range)
         (map (fn [[range repos]] [range (count repos)]))
         (into (sorted-map))
         (map (fn [[[min max] v]] [(str min "-" max ) v])))))

(defn- output-stats-json []
  (let [stats     {:repos_cnt                (str (count @repositories))
                   :orgas_cnt                (str (count @owners))
                   :top_orgs_by_stars        (get-top-owners-by 10 :total_stars)
                   :top_orgs_by_repos        (get-top-owners-by 10 :repositories_count)
                   :top_orgs_repos_stars     (get-top-owners-repos-stars 1 200)
                   :top_orgs_repos_followers (get-top-owners-repos-followers 1 20)
                   :top_repos_by_score_range (get-top-repos-by-score-range)
                   :top_licenses             (get-top-x 10 :license #"(?i)other")
                   :top_languages            (get-top-x 10 :language)}
        stats-str (json/generate-string stats)]
    (spit (-> "yyyy-MM-dd"
              java.text.SimpleDateFormat.
              (.format (java.util.Date.))
              (str "-stats.json")) stats-str)
    (spit "stats_preprod.json" stats-str)))

(defn- output-formations-json []
  (when-let [res (fetch-yaml (:formations urls))]
    (->> res
         json/generate-string
         (spit "formations-logiciels-libres.json"))))

(defn- output-sill-providers []
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
         (map (fn [[id l]] [id (flatten (map second l))]))
         (filter #(not-empty (second %)))
         (map (fn [[id l]]
                {:sill_id      id
                 :prestataires (map #(apply merge (val %))
                                    (group-by #(str/lower-case (:nom %)) l))}))
         (sort-by :sill_id)
         json/generate-string
         (spit  "sill-prestataires.json"))))

(defn- output-sill-latest-xml []
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
                   :pubDate     (to-inst (str (java.time.Instant/ofEpochMilli (:referencedSinceTime item))))})))
         (rss/channel-xml
          {:title       "code.gouv.fr - Nouveaux logiciels libres au SILL - New SILL entries"
           :link        "https://code.gouv.fr/data/latest-sill.xml"
           :description "code.gouv.fr - Nouveaux logiciels libres au SILL - New SILL entries"})
         (spit "latest-sill.xml"))))

(defn- set-data! []
  (let [hosts-future    (future (set-hosts!))
        forges-future   (future (set-public-sector-forges!))
        annuaire-future (future (set-annuaire!))]
    @hosts-future
    @forges-future
    @annuaire-future
    (set-owners!)
    (update-owners!)
    (set-repos!)
    (when-not (:test @cli-opts)
      (set-ospos!)
      (set-awesome!)
      (update-awesome!)
      (set-awesome-releases!))))

(defn- output-data! []
  (output-owners-json)
  (output-owners-json :full)
  (output-owners-csv)
  (output-annuaire-sup)
  (output-latest-sill-xml)
  (output-repositories-json)
  (output-repositories-json :full)
  (output-repositories-csv)
  (output-latest-repositories-xml)
  (output-latest-releases-xml)
  (output-forges-csv)
  (output-stats-json)
  (when-not (:test @cli-opts)
    (output-ospos-json)
    (output-awesome-json)
    (output-awesome-md))
  (output-formations-json)
  (output-sill-providers)
  (output-sill-latest-xml))

(defn- display-data! []
  (log/info "Hosts:" (count @hosts))
  (log/info "Owners:" (count @owners))
  (log/info "Owners (limited):" (count (owners-as-map @owners)))
  (log/info "Repositories:" (count @repositories))
  (log/info "Repositories (limited):" (count (repositories-as-map @repositories)))
  (when-not (:test @cli-opts)
    (log/info "Awesome codegouvfr:" (count @awesome))))

;; Main execution
(defn- -main [args]
  (let [opts (cli/parse-opts args {:spec cli-options})]
    (reset! cli-opts opts)
    (if (or (:help opts) (:h opts))
      (println (show-help))
      (do (set-data!)
          (output-data!)
          (display-data!)))))

(-main *command-line-args*)


