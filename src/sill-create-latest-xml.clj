#!/usr/bin/env bb

(deps/add-deps '{:deps {clj-rss/clj-rss {:mvn/version "0.4.0"}}})

(require '[clj-rss.core :as rss])

(if-let [sill (try (:body (curl/get "https://code.gouv.fr/sill/api/sill.json"))
                   (catch Exception e (println (.getMessage e))))]
  (do (->> sill
           json/parse-string
           (sort-by #(java.util.Date. (get % "referencedSinceTime")))
           reverse
           (take 10)
           (map (fn [item]
                  (let [link (str "https://code.gouv.fr/sill/detail?name=" (get item "name"))]
                    {:title       (str "Nouveau logiciel au SILL : " (get item "name"))
                     :link        link
                     :guid        link
                     :description (get item "function")
                     :pubDate     (.toInstant (java.util.Date. (get item "referencedSinceTime")))})))
           (rss/channel-xml
            {:title       "code.gouv.fr - Nouveaux logiciels libres au SILL - New SILL entries"
             :link        "https://code.gouv.fr/data/latest-sill.xml"
             :description "code.gouv.fr - Nouveaux logiciels libres au SILL - New SILL entries"})
           (spit "latest-sill.xml"))
      (println "Exported latest-sill.xml"))
  (println "Count not get SILL data"))
