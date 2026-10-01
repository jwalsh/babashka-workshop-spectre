(ns workshop.docs
  "Check the documents against the code: `bb -cp dev:src -m workshop.docs`.

   Dev tooling, like workshop.todos: not part of Spectre, off the project's
   :paths, only what babashka bundles. It checks what can be checked without
   reading prose:

   - every Makefile target is in README.org, and every `gmake x` a document
     names is a target;
   - every `M-x spectre-...` a document names is a command, and every command
     is in the wiring file's commentary and in `spectre-map`;
   - every Org and Markdown link, and every src/ test/ dev/ .meta/ path in
     code markup, points at something, including the text an Org search link
     looks for;
   - every `form ;; => value` in walkthrough.org still evaluates to that value.

   Each problem is a `file:line: message` line, so it is a link in an Emacs
   compilation buffer. Exits 1 when there is one."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]))

(def ^:private documents
  "The documents whose claims are checked, relative to the repo root."
  (concat ["README.org" "AGENTS.md" "exercises.org" "walkthrough.org"]
          (map str (sort (fs/glob "." ".meta/*.md" {:hidden true})))
          (map str (sort (fs/glob "." "dev/claude-plugins/plugins/*/README.md")))))

(def ^:private target-mentioning-files
  "Files that name gmake targets without being documents: comments and help.
   Not this file, whose own comments talk about targets in general."
  (concat ["babashka-workshop-spectre.el" ".env.template" ".envrc"]
          (remove #(str/ends-with? % "workshop/docs.clj")
                  (map str (sort (fs/glob "dev" "**.{clj,bb,el}"))))))

(def ^:private wiring-file "babashka-workshop-spectre.el")

(defn- numbered-lines
  "The lines of file as [line-number text] pairs."
  [file]
  (map-indexed (fn [index text] [(inc index) text]) (str/split-lines (slurp file))))

(defn- problem [file line message]
  {:file file :line line :message message})

;;;; gmake targets

(defn- makefile-targets
  "The targets `gmake help` lists: the ones with a ## description."
  []
  (set (keep #(second (re-find #"^([A-Za-z0-9_.-]+):.*?## " %))
             (str/split-lines (slurp "Makefile")))))

(defn- named-targets
  "The [line target] pairs for each `gmake target` in file. Only code counts:
   after a backquote, tilde, quote or bracket, or at the start of a line, so
   that 'run gmake through direnv' in a sentence is not a target."
  [file]
  (for [[line text] (numbered-lines file)
        [_ target] (re-seq #"(?:^\s*(?:[#;]+\s*)?|[`~'(])gmake\s+([A-Za-z0-9_.-]+)" text)]
    [line target]))

(defn- target-problems []
  (let [targets (makefile-targets)
        in-readme (set (map second (named-targets "README.org")))]
    (concat
     (for [target (sort targets)
           :when (not (in-readme target))]
       (problem "README.org" 1 (str "gmake " target " is a target and is not listed here")))
     (for [file (concat documents target-mentioning-files)
           [line target] (named-targets file)
           :when (not (targets target))]
       (problem file line (str "gmake " target " is not a target"))))))

;;;; Emacs commands

(defn- wiring-commands
  "The interactive commands the wiring file defines."
  []
  (let [source (slurp wiring-file)]
    (set (for [[_ name body] (re-seq #"(?s)\n\(defun (spectre-[a-z-]+)(.*?)(?=\n\(|\z)" source)
               :when (re-find #"\n\s+\(interactive" body)]
           name))))

(defn- command-problems []
  (let [commands (wiring-commands)
        source (slurp wiring-file)
        commentary (first (str/split source #"\n;;; Code:"))
        keymap (second (re-find #"(?s)\(defvar spectre-map(.*?)\n\(fset" source))]
    (concat
     (for [file documents
           [line text] (numbered-lines file)
           [_ command] (re-seq #"M-x (spectre-[a-z-]+)" text)
           :when (not (commands command))]
       (problem file line (str "M-x " command " is not a command in " wiring-file)))
     (for [command (sort commands)
           :when (not (re-find (re-pattern (str "\\b" command "\\b")) commentary))]
       (problem wiring-file 1 (str command " is missing from the commentary")))
     (for [command (sort commands)
           :when (not (str/includes? (str keymap) (str "#'" command ")")))]
       (problem wiring-file 1 (str command " is not bound in spectre-map"))))))

;;;; links and paths

(defn- squeeze [text]
  (str/replace text #"\s+" " "))

(defn- link-problems-in
  "Problems with the links on one line of file."
  [file line text]
  (let [directory (or (fs/parent file) ".")
        resolve-target #(fs/path directory %)]
    (concat
     ;; Org: [[file:path]] or [[file:path::search text]]
     (for [[_ path search] (re-seq #"\[\[file:([^\]:]+)(?:::([^\]]+))?\]" text)
           :let [target (resolve-target path)]
           message [(cond
                      (not (fs/exists? target))
                      (str "link to " path ", which does not exist")

                      (and search (re-find #"^\(.*\)$" search))
                      (str "search link " (pr-str search) " is wrapped in parentheses: Org reads that as a code reference")

                      (and search (not (str/includes? (squeeze (slurp (str target))) (squeeze search))))
                      (str "search link: " (pr-str search) " is not in " path))]
           :when message]
       (problem file line message))
     ;; Markdown: [text](relative/path)
     (for [[_ path] (re-seq #"\]\(([^)#\s]+)\)" text)
           :when (not (re-find #"^[a-z]+:" path))
           :when (not (fs/exists? (resolve-target path)))]
       (problem file line (str "link to " path ", which does not exist"))))))

(defn- path-problems-in
  "Problems with repo paths written as code on one line: `src/...` and the
   like. Only the directories that are in the repo for everyone."
  [file line text]
  (for [[_ path] (re-seq #"[`~]((?:src|test|dev|\.meta)/[A-Za-z0-9_./-]*)" text)
        :let [path (str/replace path #"[.:]+$" "")]
        :when (not (fs/exists? path))]
    (problem file line (str path " does not exist"))))

(defn- link-problems []
  (for [file documents
        [line text] (numbered-lines file)
        found (concat (link-problems-in file line text) (path-problems-in file line text))]
    found))

;;;; walkthrough results

(defn- clojure-claims
  "The `form ;; => value` claims in the clojure blocks of org-file, as
   {:line n :form text :expected text}. A form may run over several lines,
   and the forms before it in the block run first, so a def is in effect."
  [org-file]
  (loop [remaining (numbered-lines org-file) in-block? false pending [] claims []]
    (if-let [[line text] (first remaining)]
      (cond
        (re-find #"(?i)^#\+begin_src clojure" text) (recur (rest remaining) true [] claims)
        (re-find #"(?i)^#\+end_src" text) (recur (rest remaining) false [] claims)
        (not in-block?) (recur (rest remaining) false [] claims)
        :else
        (let [[_ code expected] (re-find #"^(.*?)\s*;; => (.*)$" text)]
          (cond
            expected
            (recur (rest remaining) true []
                   (conj claims {:line line
                                 :form (str/join "\n" (cond-> pending (not (str/blank? code)) (conj code)))
                                 :expected expected}))

            (or (str/blank? text) (str/starts-with? (str/trim text) ";;"))
            (recur (rest remaining) true pending claims)

            :else
            (recur (rest remaining) true (conj pending text) claims))))
      claims)))

(defn- machine-specific?
  "An absolute path is this machine's, not a fact about the code."
  [expected]
  (and (string? expected) (str/starts-with? expected "/")))

(defn- walkthrough-problems
  "[problems checked-count]: each claim of walkthrough.org evaluated inside
   spectre.core, where the private helpers are callable."
  []
  (let [file "walkthrough.org"]
    (if-let [failure (try (require 'spectre.core 'spectre.identicon) nil
                          (catch Exception caught caught))]
      [[(problem file 1 (str "cannot load spectre.core, run with -cp dev:src: " (ex-message failure)))] 0]
      (let [results (for [{:keys [line form expected]} (clojure-claims file)
                          :let [wanted (read-string expected)]
                          :when (not (machine-specific? wanted))]
                      (try
                        (let [actual (binding [*ns* (the-ns 'spectre.core)] (load-string form))]
                          (when-not (= wanted actual)
                            (problem file line (str "says " (pr-str wanted) ", evaluates to " (pr-str actual)))))
                        (catch Exception caught
                          (problem file line (str "says " (pr-str wanted) ", throws " (ex-message caught))))))]
        [(remove nil? results) (count results)]))))

;;;; report

(defn -main [& _args]
  (let [[walkthrough-found walkthrough-count] (walkthrough-problems)
        problems (concat (target-problems) (command-problems) (link-problems) walkthrough-found)]
    (doseq [{:keys [file line message]} problems]
      (println (str file ":" line ": " message)))
    (println (str (when (seq problems) "\n")
                  (count (makefile-targets)) " targets, "
                  (count (wiring-commands)) " commands, "
                  (count documents) " documents, "
                  walkthrough-count " walkthrough results: "
                  (if (seq problems) (str (count problems) " problems") "all hold")))
    (when (seq problems) (System/exit 1))))
