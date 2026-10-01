#!/usr/bin/env bb
;; Show the workshop's dependencies and what is installed: `bb dev/deps.bb`.
;;
;; Dev tooling, like dev/workshop/todos.clj: not part of Spectre, and it uses
;; only what babashka bundles. Exits 1 when something required is missing or
;; too old; optional tools are reported and never fail the run.

(ns deps
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]))

(defn- output
  "Combined stdout and stderr of a command, or nil when it cannot run."
  [& command]
  (try
    (let [{:keys [exit out]} (apply p/sh {:err :out} command)]
      (when (zero? exit) (str/trim out)))
    (catch Exception _ nil)))

(defn- version-of
  "The first dotted number in text, as a vector of longs."
  [text]
  (some->> text (re-find #"\d+(?:\.\d+)*") (#(str/split % #"\.")) (mapv parse-long)))

(defn- at-least? [found minimum]
  (and found (not (neg? (compare (conj (vec (take 3 (concat found [0 0 0]))))
                                 (conj (vec (take 3 (concat minimum [0 0 0])))))))))

(defn- openssl
  "The OpenSSL spectre.scrypt will pick: same candidates, same order."
  []
  (->> [(System/getenv "SPECTRE_OPENSSL")
        "/opt/homebrew/opt/openssl@3/bin/openssl"
        "/usr/local/opt/openssl@3/bin/openssl"
        "C:/Program Files/OpenSSL-Win64/bin/openssl.exe"
        "C:/Program Files/OpenSSL-Win64-ARM/bin/openssl.exe"
        (some-> (fs/which "openssl") str)]
       (remove nil?)
       (filter #(some-> (output % "version") (str/starts-with? "OpenSSL 3")))
       first))

(defn- on-path
  "Run tool with args when it is on the PATH."
  [tool & args]
  (when (fs/which tool) (apply output tool args)))

(def ^:private dependencies
  "Rows in README order. :minimum makes a version check; :required makes a
   miss an error rather than a note."
  [{:tool "babashka" :for "everything" :minimum [1 13 220] :required true
    :found #(System/getProperty "babashka.version")}
   {:tool "java" :for "bb test, bbin" :minimum [17] :required true
    :found #(some-> (on-path "java" "-version") str/split-lines first)}
   {:tool "java 22+" :for "FFI scrypt" :minimum [22]
    :found #(some-> (on-path "java" "-version") str/split-lines first)}
   {:tool "openssl" :for "scrypt" :minimum [3] :required true
    :found #(when-let [bin (openssl)]
              (str (first (str/split (output bin "version") #" \(")) "  " bin))}
   {:tool "libsodium" :for "FFI scrypt"
    :found #(on-path "pkg-config" "--modversion" "libsodium")}
   {:tool "clojure" :for "bb dev --jvm"
    :found #(on-path "clojure" "--version")}
   {:tool "bbin" :for "E6"
    :found #(on-path "bbin" "--version")}
   {:tool "clojure-lsp" :for "editor"
    :found #(some-> (on-path "clojure-lsp" "--version") str/split-lines first)}
   {:tool "clj-kondo" :for "editor"
    :found #(on-path "clj-kondo" "--version")}
   {:tool "emacs" :for "editor"
    :found #(some-> (on-path "emacs" "--version") str/split-lines first)}])

(defn- status [{:keys [minimum required]} found]
  (cond
    (and found (or (nil? minimum) (at-least? (version-of found) minimum))) :ok
    found (if required :too-old :not-enough)
    required :missing
    :else :absent))

(def ^:private labels
  {:ok "ok" :too-old "TOO OLD" :missing "MISSING" :not-enough "no" :absent "-"})

(defn -main [& _args]
  (let [rows (for [{:keys [found] :as dependency} dependencies
                   :let [found (found)]]
               (assoc dependency :found found :status (status dependency found)))]
    (println (format "%-8s %-12s %-14s %-11s %s" "" "TOOL" "FOR" "NEEDS" "FOUND"))
    (doseq [{:keys [tool for minimum found status]} rows]
      (println (format "%-8s %-12s %-14s %-11s %s"
                       (labels status) tool for
                       (if minimum (str ">= " (str/join "." minimum)) "")
                       (or found "not found"))))
    (when-let [failed (seq (filter (comp #{:too-old :missing} :status) rows))]
      (println (str "\nRequired and not usable: " (str/join ", " (map :tool failed))))
      (System/exit 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
