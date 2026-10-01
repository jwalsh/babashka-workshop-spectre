(ns spectre.db
  "Per-site settings in db.edn."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]))

(def default-path
  "The SPECTRE_DB environment variable, or ~/.config/spectre/db.edn."
  (or (System/getenv "SPECTRE_DB")
      (str (fs/path (fs/xdg-config-home "spectre") "db.edn"))))

(defn load-db
  ([] (load-db {}))
  ([{:keys [path] :or {path default-path}}])) ;; TODO

(defn save-db!
  ([db] (save-db! db {}))
  ([db {:keys [path] :or {path default-path}}])) ;; TODO

(defn site-settings
  [db site]) ;; TODO

(defn merge-site!
  "Merge settings into the site entry and save. Returns the updated db."
  ([db site settings] (merge-site! db site settings {}))
  ([db site settings opts])) ;; TODO

(comment
  ;; Load this file first (C-c C-k): fs/, edn/ and pp/ only resolve, for
  ;; evaluation and for C-c C-d d alike, once the namespace exists in the REPL.

  ;; the docs for the calls the TODOs name; they print in the REPL buffer
  (require '[clojure.repl :refer [doc]])
  (doc fs/exists?)
  (doc edn/read-string)
  (doc fs/parent)
  (doc fs/create-dirs)
  (doc pp/pprint)

  ;; where this REPL's database is. Read once, when the namespace loaded:
  ;; changing SPECTRE_DB afterwards does nothing without a reload.
  default-path
  (System/getenv "SPECTRE_DB")
  (fs/exists? default-path)

  ;; what they give back, on throwaway paths
  (edn/read-string "{:sites {\"a.example.com\" {:counter 1}}}")
  (edn/read-string "")
  (str (fs/parent "a/b/db.edn"))
  (fs/with-temp-dir [dir {}]
    (let [parent (fs/path dir "a" "b")]
      [(fs/exists? parent) (str (fs/create-dirs parent)) (fs/exists? parent)]))

  ;; the stubs, before and after
  (load-db)
  (site-settings {:sites {"a.example.com" {:counter 1}}} "a.example.com"))
