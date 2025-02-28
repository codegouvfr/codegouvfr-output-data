#!/usr/bin/env bb

;; Copyright (c) DINUM, Bastien Guerry
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: EPL-2.0.txt

;; ~$ faq-server-dsfr -s https://code.gouv.fr/data/faq.json
;;
;; Then check http://localhost:8080
;;
;; Here is an example json with "title", "content" and "path", which
;; last item is used to infer the category:
;;
;; [ {
;;   "title" : "FAQ title",
;;   "content" : "<p>Content as HTML",
;;   "path" : [ "Section", "Subsection (as category)" ]
;; } ]

(ns faq-server-dsfr
  (:require [org.httpkit.server :as server]
            [cheshire.core :as json]
            [clojure.string :as str]
            [babashka.cli :as cli]))

;; Define CLI specs
(def cli-options
  {:port      {:desc    "Port number for server"
               :default 8080
               :alias   :p
               :coerce  :int}
   :source    {:desc    "Path to FAQ JSON file"
               :alias   :s
               :default "faq.json"}
   :title     {:desc    "Website title"
               :alias   :t
               :default "FAQ - mission logiciels libres de la DINUM"}
   :tagline   {:desc    "Website tagline"
               :alias   :l
               :default "Questions fréquentes sur les logiciels libres"}
   :footer    {:desc    "Footer text"
               :alias   :f
               :default "FAQ - mission logiciels libres de la DINUM - code.gouv.fr"}
   :base-path {:desc    "Base path for subdirectory deployment (e.g., /faq)"
               :alias   :b
               :default ""}
   :help      {:desc   "Show help"
               :alias  :h
               :coerce :boolean}})

;; Settings with defaults
(def settings
  {:title     "FAQ - mission logiciels libres de la DINUM"
   :tagline   "Questions fréquentes sur les logiciels libres"
   :footer    "FAQ - mission logiciels libres de la DINUM - code.gouv.fr"
   :source    "faq.json"
   :port      8080
   :base-path ""})

;; Function to append base path to URLs
(defn with-base-path [path]
  (let [base-path  (:base-path settings)
        clean-base (if (str/ends-with? base-path "/")
                     (subs base-path 0 (dec (count base-path)))
                     base-path)]
    (str clean-base path)))

;; Load FAQ data directly
(defn load-faq-data [source]
  (try
    (println "Loading FAQ data from" source)
    (let [content (slurp source)
          data    (json/parse-string content true)]
      (println "Loaded" (count data) "FAQ items")
      data)
    (catch Exception e
      (println "Error loading FAQ data from" source ":" (.getMessage e))
      [])))

;; Fix broken links in content
(defn fix-content-links [content]
  (-> content
      (str/replace #"https://<em>(.*?)</em>" "https://$1")
      (str/replace #"</em>/<em>" "/")
      (str/replace #"</em><em>" "")
      (str/replace #"</em>_" "_")
      (str/replace #"_</em>" "_")))

;; Simple search function
(defn search-faq [query faq-data]
  (if (or (nil? query) (empty? query))
    []
    (let [query-lower (str/lower-case query)]
      (filter #(str/includes? (str/lower-case (:title %)) query-lower) faq-data))))

;; Function to get categories from path
(defn get-categories [faq-data]
  (let [paths      (map :path faq-data)
        categories (distinct (map last paths))]
    (sort categories)))

;; Get FAQ items by category
(defn get-faqs-by-category [category faq-data]
  (filter #(= (last (:path %)) category) faq-data))

;; DSFR HTML Templates
(defn dsfr-page-layout [page-title content]
  (str "<!DOCTYPE html>
<html lang=\"fr\" data-fr-theme>
<head>
  <meta charset=\"utf-8\">
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1, shrink-to-fit=no\">
  <title>" page-title " - " (:title settings) "</title>

  <!-- DSFR -->
  <link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.9.3/dist/dsfr/dsfr.min.css\">
  <link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.9.3/dist/utility/utility.min.css\">
  <link rel=\"apple-touch-icon\" href=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.9.3/dist/favicon/apple-touch-icon.png\">
  <link rel=\"icon\" href=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.9.3/dist/favicon/favicon.svg\" type=\"image/svg+xml\">
  <link rel=\"shortcut icon\" href=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.9.3/dist/favicon/favicon.ico\" type=\"image/x-icon\">
  <meta name=\"theme-color\" content=\"#000091\">
</head>
<body>
  <header role=\"banner\" class=\"fr-header\">
    <div class=\"fr-header__body\">
      <div class=\"fr-container\">
        <div class=\"fr-header__body-row\">
          <div class=\"fr-header__brand fr-enlarge-link\">
            <div class=\"fr-header__brand-top\">
              <div class=\"fr-header__logo\">
                <p class=\"fr-logo\">
                  République<br>Française
                </p>
              </div>
            </div>
            <div class=\"fr-header__service\">
              <a href=\"" (with-base-path "/") "\" title=\"Accueil - " (:title settings) "\">
                <p class=\"fr-header__service-title\">" (:title settings) "</p>
              </a>
              <p class=\"fr-header__service-tagline\">" (:tagline settings) "</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  </header>

  <main role=\"main\" class=\"fr-container fr-py-8w\">
    " content "
  </main>

  <footer class=\"fr-footer\" role=\"contentinfo\">
    <div class=\"fr-container\">
      <div class=\"fr-footer__body\">
        <div class=\"fr-footer__brand fr-enlarge-link\">
          <p class=\"fr-logo\">République<br>Française</p>
        </div>
        <div class=\"fr-footer__content\">
          <p class=\"fr-footer__content-desc\">" (:footer settings) "</p>
          <ul class=\"fr-footer__content-list\">
            <li class=\"fr-footer__content-item\">
              <a class=\"fr-footer__content-link\" href=\"https://info.gouv.fr\" target=\"_blank\" rel=\"noopener\">info.gouv.fr</a>
            </li>
            <li class=\"fr-footer__content-item\">
              <a class=\"fr-footer__content-link\" href=\"https://service-public.fr\" target=\"_blank\" rel=\"noopener\">service-public.fr</a>
            </li>
            <li class=\"fr-footer__content-item\">
              <a class=\"fr-footer__content-link\" href=\"https://legifrance.gouv.fr\" target=\"_blank\" rel=\"noopener\">legifrance.gouv.fr</a>
            </li>
            <li class=\"fr-footer__content-item\">
              <a class=\"fr-footer__content-link\" href=\"https://data.gouv.fr\" target=\"_blank\" rel=\"noopener\">data.gouv.fr</a>
            </li>
          </ul>
        </div>
      </div>
    </div>
  </footer>

  <!-- DSFR Scripts -->
  <script type=\"module\" src=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.9.3/dist/dsfr/dsfr.module.min.js\"></script>
  <script type=\"text/javascript\" nomodule src=\"https://cdn.jsdelivr.net/npm/@gouvfr/dsfr@1.9.3/dist/dsfr/dsfr.nomodule.min.js\"></script>
</body>
</html>"))

(defn home-content [faq-data]
  (str "<div class=\"fr-grid-row fr-grid-row--center\">
          <div class=\"fr-col-12 fr-col-md-8\">
            <form action=\"" (with-base-path "/search") "\" method=\"get\" class=\"fr-search-bar fr-mt-4w fr-mb-8w\" role=\"search\">
              <label class=\"fr-label\" for=\"search-input\">Rechercher dans la FAQ</label>
              <input class=\"fr-input\" placeholder=\"Rechercher dans la FAQ...\" type=\"search\" id=\"search-input\" name=\"q\">
              <button class=\"fr-btn\" title=\"Rechercher\">
                Rechercher
              </button>
            </form>

            <div class=\"fr-grid-row fr-grid-row--gutters\">"
       (str/join "\n"
                 (for [category (get-categories faq-data)]
                   (str "<div class=\"fr-col-12 fr-col-md-6 fr-mb-4w\">
                         <div class=\"fr-card fr-enlarge-link fr-card--shadow\">
                           <div class=\"fr-card__body\">
                             <div class=\"fr-card__content\">
                               <h3 class=\"fr-card__title\">
                                 <a href=\"" (with-base-path "/category") "?name=" (java.net.URLEncoder/encode category "UTF-8") "\" class=\"fr-card__link\">" category "</a>
                               </h3>
                               <p class=\"fr-card__desc\">" (count (get-faqs-by-category category faq-data)) " questions</p>
                             </div>
                           </div>
                         </div>
                       </div>")))
       "</div>
          </div>
        </div>"))

(defn category-content [category-name category-faqs]
  (str "<div class=\"fr-grid-row fr-grid-row--center\">
          <div class=\"fr-col-12 fr-col-md-8\">
            <div class=\"fr-mt-2w\">
              <a href=\"" (with-base-path "/") "\" class=\"fr-link fr-fi-arrow-left-line fr-link--icon-left\">Retour à l'accueil</a>
            </div>
            <h1 class=\"fr-mt-4w\">Catégorie : " category-name "</h1>

            <div class=\"fr-accordions-group fr-mt-4w\">"
       (str/join "\n"
                 (for [item category-faqs]
                   (str "<section class=\"fr-accordion\">
                         <h3 class=\"fr-accordion__title\">
                           <button class=\"fr-accordion__btn\" aria-expanded=\"false\" aria-controls=\"accordion-"
                        (hash (:title item)) "\">" (:title item) "</button>
                         </h3>
                         <div class=\"fr-collapse\" id=\"accordion-" (hash (:title item)) "\">
                           " (fix-content-links (:content item)) "
                         </div>
                       </section>")))
       "</div>
          </div>
        </div>"))

(defn search-content [query results]
  (str "<div class=\"fr-grid-row fr-grid-row--center\">
          <div class=\"fr-col-12 fr-col-md-8\">
            <div class=\"fr-mt-2w\">
              <a href=\"" (with-base-path "/") "\" class=\"fr-link fr-fi-arrow-left-line fr-link--icon-left\">Retour à l'accueil</a>
            </div>
            <h1 class=\"fr-mt-4w\">Résultats de recherche</h1>
            <p class=\"fr-text\">Résultats pour \"" query "\" (" (count results) ") :</p>

            <div class=\"fr-mt-4w\">"
       (if (empty? results)
         "<div class=\"fr-alert fr-alert--info\">
                 <h3 class=\"fr-alert__title\">Aucun résultat</h3>
                 <p>Aucun résultat ne correspond à votre recherche. Essayez avec d'autres termes.</p>
               </div>"
         (str "<div class=\"fr-accordions-group\">"
              (str/join "\n"
                        (for [item results]
                          (str "<section class=\"fr-accordion\">
                             <h3 class=\"fr-accordion__title\">
                               <button class=\"fr-accordion__btn\" aria-expanded=\"false\" aria-controls=\"accordion-"
                               (hash (:title item)) "\">" (:title item) "</button>
                             </h3>
                             <div class=\"fr-collapse\" id=\"accordion-" (hash (:title item)) "\">
                               " (fix-content-links (:content item)) "
                             </div>
                           </section>")))
              "</div>"))
       "</div>
          </div>
        </div>"))

(defn faq-content [item]
  (str "<div class=\"fr-grid-row fr-grid-row--center\">
          <div class=\"fr-col-12 fr-col-md-8\">
            <div class=\"fr-mt-2w\">
              <a href=\"javascript:history.back()\" class=\"fr-link fr-fi-arrow-left-line fr-link--icon-left\">Retour</a>
            </div>
            <article class=\"fr-mt-4w\">
              <h1>" (:title item) "</h1>
              <div class=\"fr-callout fr-mt-4w\">
                <div class=\"fr-callout__text\">
                  " (fix-content-links (:content item)) "
                </div>
              </div>
              <p class=\"fr-text--xs fr-mt-4w\">
                Catégorie: <a href=\"" (with-base-path "/category") "?name=" (java.net.URLEncoder/encode (last (:path item)) "UTF-8") "\">" (last (:path item)) "</a>
              </p>
            </article>
          </div>
        </div>"))

(defn not-found-content []
  (str "<div class=\"fr-grid-row fr-grid-row--center\">
     <div class=\"fr-col-12 fr-col-md-8\">
       <div class=\"fr-mt-2w\">
         <a href=\"" (with-base-path "/") "\" class=\"fr-link fr-fi-arrow-left-line fr-link--icon-left\">Retour à l'accueil</a>
       </div>
       <h1 class=\"fr-mt-4w\">FAQ introuvable</h1>
       <div class=\"fr-alert fr-alert--error fr-mt-4w\">
         <h3 class=\"fr-alert__title\">L'article demandé n'existe pas</h3>
         <p>Vérifiez l'URL ou effectuez une nouvelle recherche.</p>
       </div>
     </div>
   </div>"))

(defn error-content []
  (str "<div class=\"fr-grid-row fr-grid-row--center\">
     <div class=\"fr-col-12 fr-col-md-8\">
       <div class=\"fr-mt-2w\">
         <a href=\"" (with-base-path "/") "\" class=\"fr-link fr-fi-arrow-left-line fr-link--icon-left\">Retour à l'accueil</a>
       </div>
       <h1 class=\"fr-mt-4w\">Page non trouvée</h1>
       <div class=\"fr-alert fr-alert--error fr-mt-4w\">
         <h3 class=\"fr-alert__title\">La page demandée n'existe pas</h3>
         <p>Vérifiez l'URL ou retournez à l'accueil.</p>
       </div>
     </div>
   </div>"))

;; Function to extract path without base path
(defn strip-base-path [uri]
  (let [base-path (:base-path settings)
        base-len  (count base-path)]
    (if (and (not (empty? base-path))
             (str/starts-with? uri base-path))
      (let [path (subs uri base-len)]
        (if (str/starts-with? path "/")
          path
          (str "/" path)))
      uri)))

;; Create app function with faq-data as parameter
(defn create-app [faq-data]
  (fn [{:keys [request-method uri query-string]}]
    (let [path   (strip-base-path uri)
          params (when query-string
                   (into {} (for [pair (str/split query-string #"&")]
                              (let [[k v] (str/split pair #"=")]
                                [(keyword k) (java.net.URLDecoder/decode v "UTF-8")]))))]

      (case [request-method path]
        [:get "/"]
        {:status  200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body    (dsfr-page-layout "Accueil" (home-content faq-data))}

        [:get "/category"]
        (let [category-name (:name params)
              category-faqs (get-faqs-by-category category-name faq-data)]
          {:status  200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body    (dsfr-page-layout (str "Catégorie: " category-name)
                                      (category-content category-name category-faqs))})

        [:get "/search"]
        (let [query   (:q params)
              results (search-faq query faq-data)]
          {:status  200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body    (dsfr-page-layout (str "Résultats pour: " query)
                                      (search-content query results))})

        [:get "/faq"]
        (let [id   (:id params)
              item (first (filter #(= (:title %) id) faq-data))]
          (if item
            {:status  200
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body    (dsfr-page-layout (:title item)
                                        (faq-content item))}
            {:status  404
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body    (dsfr-page-layout "FAQ introuvable"
                                        (not-found-content))}))

        ;; Default route - 404
        {:status  404
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body    (dsfr-page-layout "Page non trouvée"
                                    (error-content))}))))

;; Show help
(defn show-help []
  (println "FAQ Web Server - DSFR")
  (println "Usage: faq-server-dsfr.clj [options]")
  (println (cli/format-opts {:spec cli-options}))
  (System/exit 0))

;; Main function
(defn -main []
  (try
    ;; Parse command line arguments with babashka.cli
    (let [opts            (cli/parse-opts *command-line-args* {:spec cli-options})
          parsed-settings (merge settings opts)]

      ;; Show help if requested
      (when (:help parsed-settings)
        (show-help))

      ;; Update settings
      (alter-var-root #'settings (constantly parsed-settings))

      ;; Load FAQ data
      (let [faq-data (load-faq-data (:source parsed-settings))]
        ;; Start the server
        (println (str "Starting server at http://localhost:" (:port settings)))
        (if (empty? (:base-path settings))
          (println "Running at root path /")
          (println "Running at base path:" (:base-path settings)))
        (println "Site title:" (:title settings))
        (println "Site tagline:" (:tagline settings))
        (server/run-server (create-app faq-data) {:port (:port settings)})
        (println "Server started. Press Ctrl+C to stop.")
        @(promise)))
    (catch Exception e
      (println "ERROR:" (.getMessage e))
      (.printStackTrace e)
      (System/exit 1))))

;; Start the server
(-main)
