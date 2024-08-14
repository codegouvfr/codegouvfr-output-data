#!/usr/bin/env bb

;; Copyright (c) DINUM, Bastien Guerry
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSE.txt

;; Get the json annuaire file
(println "Fetching annuaire as a zip file from data.gouv.fr...")
(def annuaire-zip-url
  "https://www.data.gouv.fr/fr/datasets/r/d0158eb2-6772-49c2-afb1-732e573ba1e5")
(let [stream (-> (curl/get annuaire-zip-url {:as :bytes})
                 :body
                 (io/input-stream)
                 (java.util.zip.ZipInputStream.))]
  (.getNextEntry stream)
  (println "Creating annuaire.json")
  (io/copy stream (io/file "annuaire.json")))

;; Create a variable containing the original data
(def annuaire-data
  (map #(select-keys % [:id :hierarchie :nom :sigle])
       (:service (json/parse-string (slurp "annuaire.json") true))))

;; Require datalevin pod
(require '[babashka.pods :as pods])
(pods/load-pod 'huahaiy/datalevin "0.9.10")
(require '[pod.huahaiy.datalevin :as d])

;; Remove possibly preexistent db
(shell/sh "rm" "-fr" "/tmp/annuaire")

;; Create the new db
(def schema {:id          {:db/valueType :db.type/string :db/unique :db.unique/identity}
             :nom         {:db/valueType :db.type/string}
             :sigle       {:db/valueType :db.type/string}
             :service_sup {:db/valueType :db.type/string}
             :service_top {:db/valueType :db.type/string}
             :hierarchie  {:db/cardinality :db.cardinality/many}})
(def conn (d/get-conn "/tmp/annuaire" schema))
(def db (d/db conn))

;; Feed it with annuaire data
(try (d/transact! conn (into [] annuaire-data))
     (catch Exception e (println (.getMessage e))))

;; Add service_sup
(println "Adding service_sup...")
(doseq [{:keys [id hierarchie]} (filter #(< 0 (count (:hierarchie %))) annuaire-data)]
  (doseq [{:keys [service]} (filter #(= (:type_hierarchie %) "Service Fils") hierarchie)]
    (try
      (d/transact! conn [{:id service :service_sup id}])
      (catch Exception e (println (.getMessage e))))))

;; Update annuaire-data
(defn annuaire-reset-data []
  (->> (d/q '[:find ?e :where [?e :id _]] db)
       (map #(d/touch (d/entity db (first %))))
       (map #(select-keys % [:id :hierarchie :nom :sigle :service_top :service_sup]))))

;; Reset annuaire data
(def annuaire-data (annuaire-reset-data))

(def tops
  #{
    ;; "Ministère de l'Éducation nationale et de la Jeunesse"
    "3ed2f725-e077-4973-a531-498e13fc7861"
    ;; "Ministère des Solidarités, de l'Autonomie et des Personnes handicapées"
    "38a2db24-e96e-4e30-8013-98dd8a7b705d"
    ;; "Ministère de l'Enseignement supérieur et de la Recherche"
    "4ca60afd-3886-434c-80bb-f6bc55581323"
    ;; "Ministère de l'Europe et des Affaires étrangères"
    "ac1a7fdb-50f4-4599-b306-ec06b7250965"
    ;; "Ministère des Armées"
    "d24d1af2-c506-4b10-82e8-ab92b8e959d2"
    ;; "Ministère de l'Économie, des Finances et de la Souveraineté industrielle et numérique"
    "c4195774-f535-4d82-b03f-d5f0c6b08635"
    ;; "Ministère de la Santé et de la Prévention"
    "163a8b8e-8ebc-4fb7-8ed4-44fbb78110db"
    ;; "Ministère des Sports et des Jeux Olympiques et Paralympiques"
    "2fd089d2-669f-4a91-80b2-b345db391349"
    ;; "Ministère de la Transition écologique et de la Cohésion des territoires"
    "05f90b6a-e3d9-4a41-a919-2e2f2d77e517"
    ;; "Ministère de l'Intérieur et des Outre-mer"
    "fa33b07a-3f71-46d4-b682-569c9faf211e"
    ;; "Ministère de la Justice"
    "7bc12104-b232-46a6-a6d7-85bcf2dfccf1"
    ;; "Ministère de la Culture"
    "da1d6275-6710-485d-a249-282cac69dfe7"
    ;; "Ministère du Travail, du Plein emploi et de l'Insertion"
    "f130ad96-4a81-4eb4-a40c-0539edad5fff"
    ;; "Ministère de l'Agriculture et de la Souveraineté alimentaire"
    "45e488a2-c977-487f-b62f-19b4d6504d9c"
    ;; "Ministère de la Transformation et de la Fonction publiques"
    "9f101ac7-9a0e-4d62-8b27-8109876dff94"
    ;; "Autorités indépendantes"
    "82108933-ca09-4b05-b8b6-be04cc434de6"
    ;; "Institutions et juridictions"
    "b34a97a6-5862-4fb4-9700-9e6e8e7f4b22"
    ;; "Commission européenne"
    "b128cdf9-1bf5-4294-9470-2041e8ecd1f6"
    })

(defn get-ancestor [service_sup_id]
  (let [seen (atom #{})]
    (loop [s_id service_sup_id]
      (let [sup (:service_sup
                 (first (filter #(= (:id %) s_id) annuaire-data)))]
        (if (or (nil? sup)
                (contains? @seen s_id)
                (some #{s_id} tops))
          s_id
          (do (swap! seen conj s_id)
              (recur sup)))))))

;; Add service_top
(println "Adding service_top...")
(doseq [{:keys [id service_sup]}
        (filter #(seq (:service_sup %)) annuaire-data)]
  (d/transact! conn [{:id id :service_top
                      (get-ancestor service_sup)}]))

;; Reset annuaire-data
(def annuaire-data (annuaire-reset-data))

;; Output annuaire_sup.json
(println "Creating annuaire_sup.json...")
(spit "annuaire_sup.json"
      (json/generate-string annuaire-data {:pretty true}))
