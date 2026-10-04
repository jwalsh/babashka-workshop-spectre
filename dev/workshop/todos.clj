(ns workshop.todos
  "List the TODOs left in the exercises: `bb -cp dev -m workshop.todos`.

   Tooling for working through the workshop, not part of Spectre: it lives in
   dev/, off the project's :paths, and uses only what babashka bundles.

   Each line is `file:line: name  text`, which Emacs compilation buffers and
   most editors turn into a link. Under it, the source of the form the TODO
   sits in, so the docstring, the arguments and the stub are all there. Each
   file is headed by the namespaces it requires, which is where to look in
   the REPL: (clojure.repl/dir babashka.fs), (doc fs/which).

   A file being worked on may stop reading partway, at an unfinished fs/ say.
   Its header then says where and why, and the forms from there on are found
   in the text, so each TODO still has its name and its lines."
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

(def ^:private parse-options
  {:all true
   :auto-resolve name
   :readers (fn [_tag] identity)})

(defn top-level-forms
  "The top-level forms of source, each carrying :row and :end-row metadata.
   nil when the file does not parse, so one broken file hides nothing else."
  [source]
  (try
    (edamame/parse-string-all source parse-options)
    (catch Exception _ nil)))

(defn readable-forms
  "The top-level forms of source read one at a time, up to the first that
   does not read: {:forms [...]}, with :error {:message :row :col} when one
   did not. A file being worked on is often unfinished in one form, and the
   forms above it, the ns form among them, still say what the file is."
  [source]
  (let [reader (edamame/reader source)
        ;; parse-next, unlike parse-string-all, takes its options normalized
        options (edamame/normalize-opts parse-options)]
    (loop [forms []]
      (let [[form failure] (try [(edamame/parse-next reader options) nil]
                                (catch Exception e [nil e]))]
        (cond
          failure {:forms forms
                   :error {:message (ex-message failure)
                           :row (:row (ex-data failure))
                           :col (:col (ex-data failure))}}
          (= :edamame.core/eof form) {:forms forms}
          :else (recur (conj forms form)))))))

(def ^:private defining-line
  "The first line of a definition, the name in group 1."
  #"^\((?:defn-?|defmacro|defmulti|defmethod|defonce|def|deftest)\s+(?:\^\S+\s+)*([^\s\[\]()\"]+)")

(defn- defined-name
  "The name the line at row defines, or nil."
  [lines row]
  (some-> (re-find defining-line (nth lines (dec row))) second symbol))

(defn- text-form
  "The form around line, found in the text, for a file that stops reading at
   or above it: {:rows [row end-row] :name name}. It starts at the last line
   at or above line that opens a top-level form, and ends before the next
   blank line or top-level form. A TODO in a comment above a form gets only
   the name of the form below it."
  [lines line]
  (let [opens? #(str/starts-with? (nth lines (dec %)) "(")
        below (range (inc line) (inc (count lines)))]
    (if (re-find #"^\s*;" (nth lines (dec line)))
      {:name (some->> below (filter opens?) first (defined-name lines))}
      (when-let [row (first (filter opens? (range line 0 -1)))]
        {:rows [row (or (some->> below
                                 (filter #(or (opens? %) (str/blank? (nth lines (dec %)))))
                                 first
                                 dec)
                        (count lines))]
         :name (defined-name lines row)}))))

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
        {:keys [forms error]} (readable-forms source)]
    (for [[index line-text] (map-indexed vector lines)
          :when (re-find #";+\s*TODO" line-text)
          :let [line (inc index)
                form (enclosing-form forms line)
                {:keys [row end-row]} (meta form)
                ;; past the first form that does not read there are no forms,
                ;; only text
                as-text (when (and error (nil? form)) (text-form lines line))]]
      {:file (str file)
       :line line
       :exercise (exercise-of file)
       :optional? (boolean (re-find #"TODO, optional" line-text))
       :var (if (and (seq? form) (symbol? (second form))) (second form) (:name as-text))
       ;; nil when the TODO is a comment above the form, not inside it
       :form-rows (if (and row (<= row line end-row)) [row end-row] (:rows as-text))
       :text (todo-text line-text)
       :lines lines
       :requires (required-namespaces forms)
       :error error})))

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
  (let [{:keys [exercise file requires error]} (first entries)]
    (println (str "\n" exercise "  " file))
    (println (str "    requires: " (if (seq requires)
                                     (str/join " " requires)
                                     (if error "? (its ns form does not read)" "nothing"))))
    (when-let [{:keys [message row col]} error]
      (println (str "    stops reading at " row ":" col ": " message
                    ". The forms from there on are found in the text"))))
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
