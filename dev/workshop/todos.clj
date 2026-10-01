(ns workshop.todos
  "List the TODOs left in the exercises: `bb -cp dev -m workshop.todos`.

   Tooling for working through the workshop, not part of Spectre: it lives in
   dev/, off the project's :paths, and uses only what babashka bundles.

   Each line is `file:line: name  text`, which Emacs compilation buffers and
   most editors turn into a link. Under it, the source of the form the TODO
   sits in, so the docstring, the arguments and the stub are all there. Each
   file is headed by the namespaces it requires, which is where to look in
   the REPL: (clojure.repl/dir babashka.fs), (doc fs/which)."
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [clojure.string :as str]
   [edamame.core :as edamame]))

(def ^:private exercises
  "File stem to exercise, in the order of exercises.org."
  {"core" "E1" "clipboard" "E2" "db" "E3" "cli" "E4" "tui2" "E5"})

(defn exercise-of
  "The exercise a source file or its test belongs to, or nil: it is given."
  [file]
  (get exercises (str/replace (fs/strip-ext (fs/file-name file)) #"_test$" "")))

(defn top-level-forms
  "The top-level forms of source, each carrying :row and :end-row metadata.
   nil when the file does not parse, so one broken file hides nothing else."
  [source]
  (try
    (edamame/parse-string-all source {:all true
                                      :auto-resolve name
                                      :readers (fn [_tag] identity)})
    (catch Exception _ nil)))

(defn- enclosing-form
  "The form a TODO on line belongs to: the one containing it, else the next
   one down, since the tests put the TODO in a comment above the deftest."
  [forms line]
  (let [located (filter (comp :row meta) forms)]
    (or (first (filter #(<= (:row (meta %)) line (:end-row (meta %))) located))
        (first (filter #(< line (:row (meta %))) located)))))

(defn- required-namespaces [forms]
  (let [ns-form (first (filter #(and (seq? %) (= 'ns (first %))) forms))
        require-clause (first (filter #(and (seq? %) (= :require (first %))) ns-form))]
    (map #(if (sequential? %) (first %) %) (rest require-clause))))

(defn- todo-text [line-text]
  (let [text (str/trim (second (re-find #";+\s*(TODO.*)$" line-text)))]
    (if (= "TODO" text) "" (str/replace text #"^TODO[:,]?\s*(optional:\s*)?" ""))))

(def ^:private max-form-lines
  "A longer form is shown as its head plus a window around each TODO."
  16)

(defn- excerpt
  "The source lines to show for a form spanning row..end-row: all of it when
   short, else its first two lines and two lines either side of each TODO.
   Returns [line-number text] pairs, with nil marking an elided stretch."
  [lines row end-row todo-lines]
  (let [numbered (map (fn [number] [number (nth lines (dec number))])
                      (range row (inc end-row)))
        near? (fn [number]
                (or (< number (+ row 2))
                    (some #(<= (- % 2) number (+ % 2)) todo-lines)))]
    (if (<= (count numbered) max-form-lines)
      numbered
      (->> numbered
           (partition-by (comp boolean near? first))
           (mapcat #(if (near? (ffirst %)) % [nil]))))))

(defn- comment-block
  "The run of comment lines starting at line: a TODO written above a form."
  [lines line]
  (->> (map (fn [number] [number (nth lines (dec number))])
            (range line (inc (count lines))))
       (take-while #(re-find #"^\s*;" (second %)))))

(defn- file-todos [file]
  (let [source (slurp (str file))
        lines (str/split-lines source)
        forms (top-level-forms source)]
    (for [[index line-text] (map-indexed vector lines)
          :when (re-find #";+\s*TODO" line-text)
          :let [line (inc index)
                form (enclosing-form forms line)
                {:keys [row end-row]} (meta form)]]
      {:file (str file)
       :line line
       :exercise (exercise-of file)
       :optional? (boolean (re-find #"TODO, optional" line-text))
       :var (when (and (seq? form) (symbol? (second form))) (second form))
       ;; nil when the TODO is a comment above the form, not inside it
       :form-rows (when (and row (<= row line end-row)) [row end-row])
       :text (todo-text line-text)
       :lines lines
       :requires (required-namespaces forms)})))

(defn todos
  "Every TODO under the given directories, as maps, in exercise order."
  [{:keys [dirs] :or {dirs ["src" "test"]}}]
  (->> dirs
       (mapcat #(fs/glob % "**.clj"))
       (mapcat file-todos)
       (sort-by (juxt :exercise :file :line))))

(defn- print-source [numbered]
  (doseq [[number text :as entry] numbered]
    (println (if entry (format "  %4d  %s" number text) "        ..."))))

(defn- print-form
  "The TODOs sharing one form: a link line each, then the form once."
  [entries]
  (let [{:keys [lines form-rows line]} (first entries)]
    (println)
    (doseq [{:keys [file line var text optional?]} entries]
      (println (str file ":" line ": "
                    (or var "?")
                    (when optional? "  (optional)")
                    (when (and form-rows (seq text)) (str "  " text)))))
    (print-source (if-let [[row end-row] form-rows]
                    (excerpt lines row end-row (map :line entries))
                    (comment-block lines line)))))

(defn- print-file [entries]
  (println (str "\n" (:exercise (first entries)) "  " (:file (first entries))))
  (println (str "    requires: " (str/join " " (:requires (first entries)))))
  ;; a comment above a form stands alone; TODOs inside one form share it
  (run! print-form (partition-by #(or (:form-rows %) (:line %)) entries)))

(def spec
  {:exercise {:desc "Only this exercise, e.g. e3"
              :alias :e
              :coerce (comp str/upper-case name)}
   :src-only {:desc "Skip the TODOs in test/"
              :coerce :boolean}})

(defn list-todos
  "Print the TODOs left in the exercises."
  {:org.babashka/cli {:spec spec :restrict true}}
  [{:keys [exercise src-only]}]
  (let [found (cond->> (todos {:dirs (if src-only ["src"] ["src" "test"])})
                exercise (filter #(= exercise (:exercise %))))]
    (run! print-file (partition-by :file found))
    (println (str "\n" (count found) " TODOs"
                  (when exercise (str " in " exercise))))))

(defn -main [& args]
  (list-todos (cli/parse-opts args {:spec spec :restrict true})))
