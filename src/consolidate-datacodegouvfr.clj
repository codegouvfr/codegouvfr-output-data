#!/usr/bin/env bb

;; Copyright (c) DINUM, Bastien Guerry
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSE.txt

;; TODO:
;; - spit tags.json for awesome software
;; - define and add an awesome-like score?

;; Initialize atoms
(def hosts (atom ()))
(def forges (atom ()))
(def owners (atom ()))
(def repositories (atom ()))

(def urls {:hosts
           "https://data.code.gouv.fr/api/v1/hosts"
           :annuaire_sup
           "https://code.gouv.fr/data/annuaire_sup.json"
           :annuaire_tops
           "https://code.gouv.fr/data/annuaire_tops.json"
           :comptes-organismes-publics
           "https://git.sr.ht/~codegouvfr/codegouvfr-sources/blob/main/comptes-organismes-publics_new_specs.yml"})

;; Get hosts
(let [url (:hosts urls) res (curl/get url)]
  (println "Feching hosts at" url)
  (when (= (:status res) 200)
    (->> (json/parse-string (:body res) true)
         (into ())
         ;; Test
         ;; (take 1)
         (reset! hosts))))

;; Get annuaire
(def annuaire
  (let [url (:annuaire_sup urls) res (curl/get url)]
    (println "Feching annuaire at" url)
    (when (= (:status res) 200)
      (->> (json/parse-string (:body res) true)
           (map (fn [{:keys [id service_top nom]}]
                  (hash-map id {:top service_top :nom nom})))
           (into {})))))

(def annuaire_tops
  (let [url (:annuaire_tops urls) res (curl/get url)]
    (println "Feching annuaire tops at" url)
    (when (= (:status res) 200)
      (->> (json/parse-string (:body res))
           (into {})))))

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
(doseq [{:keys [repositories_url repositories_count kind]} @hosts]
  (dotimes [n (int (clojure.math/floor (+ 1 (/ (- repositories_count 1) 1000))))]
    (let [url (str repositories_url (format "?page=%s&per_page=1000" (+ n 1)))
          res (try (curl/get url) (catch Exception e (println (.getMessage e))))]
      (when (= 200 (:status res))
        (println "Fetching repos data from" url)
        (->> (json/parse-string (:body res) true)
             ;; Use lower-case repository URL for keys
             (map #(hash-map
                    (str/lower-case (:repository_url %))
                    (-> %
                        (assoc :plateform kind)
                        (dissoc  :repository_url))))
             (into {})
             (reset! repositories))))))

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
               (format (str (:hosts urls "/%s/owners/%s")) f group))]
          (let [{:strs [pso pso_id floss_policy]} group-data]
            (swap! owners update-in [owner_url]
                   #(assoc % :pso pso :pso_id pso_id :floss_policy floss_policy
                           :forge (get forge-data "forge"))))))
      (doseq [[k v] (filter #(str/includes? (key %) (str/lower-case f)) @owners)]
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
(->>  ;; Keep those with orga URL and repositories count > 0
 (filter (fn [[_ v]]
           (and (not-empty (:html_url v))
                (> (:repositories_count v) 0)))
         @owners)
 (map (fn [[_ v]]
        {:r   (:repositories_count v)
         :o   (:html_url v)
         :au  (:icon_url v)
         :n   (:name v)
         :m   (:pso_top_id_name v)
         :l   (:login v)
         :c   (:created_at v)
         :d   (:description v)
         :f   (:floss_policy v)
         :h   (:website v)
         :p   (:forge v)
         :e   (:email v)
         :a   (:location v)
         ;; TODO: New data pso
         ;; Use it for the search in the UI
         :pso (:pso v)}))
 json/generate-string
 (spit "owners.json"))

;; Spit repositories.json
(->>
 (map (fn [[_ v]]
        {:u  (:updated_at v)
         :d  (:description v)
         :a? (:archived v)
         :f? (:fork v)
         ;; TODO: Don't use esr and lib on the UI
         ;; :e?  (:is_esr v)
         ;; :l?  (:is_lib v)
         ;; TODO: use template on the UI instead of lib
         :t? (:template v)
         ;; TODO: Don't use reuses on the UI
         ;; :re (:reuses v)
         :c? (not (empty? (:contributing (:files (:metadata v)))))
         :p? (not (empty? (:publiccode (:files (:metadata v)))))
         :l  (:language v)
         :li (:license v)
         :n  (let [fn (:full_name v)] (or (last (re-matches #"([^/]+)/(.+)" fn)) fn))
         :f  (:forks_count v)
         :s  (:subscribers_count v)
         :p  (:plateform v)
         :r  (:html_url v)
         :o  (when-let [[_ host owner]
                        (re-matches (re-pattern (str (:hosts urls) "/([^/]+)/owners/([^/]+)"))
                                    (:owner_url v))]
               (let [host (if (= host "GitHub") "github.com" host)]
                 (str "https://" host "/" owner)))})
      @repositories)
 json/generate-string
 (spit "repositories.json"))

;; Output platforms.csv
(shell/sh "rm" "-f" "platforms.csv")
(doseq [{:keys [name kind]} @hosts]
  (let [n (if (= "GitHub" name) "github.com" name)]
    (spit "platforms.csv" (str n "," kind "\n") :append true)))

;; ;; Test: display overview
;; (println "Hosts: " (count @hosts))
;; (println "Owner: " (count @owners))
;; (println "Repos: " (count @repositories))
;; (println "Forges: " (count @forges))

;; ;; Test: display examples
;; (doseq [a (take 10 (shuffle (into [] @owners)))] (println a "\n"))
