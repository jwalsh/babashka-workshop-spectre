(ns workshop.files
  "The files git tracks, each with what it says it is:
   `bb -cp dev -m workshop.files`.

   Dev tooling, like workshop.todos. Nothing here describes a file. A file
   describes itself: a namespace by the first sentence of its docstring, an
   Emacs Lisp file by its header line, an Org document by its title, a
   Markdown one by its first heading, a script or a configuration file by
   the comment it opens with. A file that says nothing has an empty cell,
   and that is the gap showing.

   Whose a file is comes from git. One that is also on the upstream branch is
   the workshop's, and `changed` when it differs from the one there; the rest
   are local. Without that branch nothing is said.

   --as says how the list is written: text, the default; edn, the data the
   others are drawn from; org, a table; mermaid, the directories as a tree.
   workshop.blocks puts any of them into an Org document."
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [workshop.org :as org]
   [workshop.todos :as todos]))

;;;; what the files say

(defn- git
  "The lines git prints for arguments, or nil when it fails: no such branch,
   or not a repository."
  [& arguments]
  (let [{:keys [exit out]} (apply p/sh "git" arguments)]
    (when (zero? exit)
      (remove str/blank? (str/split-lines out)))))

(def ^:private kinds
  "The kinds of file, in the order they are listed."
  ["source" "test" "tooling" "document" "notes" "configuration"])

(defn- kind [file]
  (cond
    (str/starts-with? file "src/") "source"
    (str/starts-with? file "test/") "test"
    (str/starts-with? file "dev/") "tooling"
    (str/starts-with? file ".meta/") "notes"
    (re-find #"^[^/.][^/]*\.el$|^Makefile$" file) "tooling"
    (re-find #"^[^/.][^/]*\.(org|md)$|^LICENSE$" file) "document"
    :else "configuration"))

(defn- first-sentence
  "The first sentence of text, on one line: up to a full stop, or to the
   colon before the command that a docstring here goes on to give."
  [text]
  (some->> text
           (re-find #"(?s)^(.*?)(?:\.(?:\s|$)|:\s+`|\n\s*\n|$)")
           second
           (#(str/replace % #"\s+" " "))
           str/trim
           not-empty))

(defn- opening-comment
  "The comment a file opens with, its comment characters taken off, or nil.
   A #! line is not part of it."
  [lines]
  (->> (if (some-> (first lines) (str/starts-with? "#!")) (rest lines) lines)
       (take-while #(re-find #"^\s*(;+|#)(\s|$)" %))
       (map #(str/replace % #"^\s*(;+|#)\s?" ""))
       (str/join "\n")
       not-empty))

(defn- namespace-form [source]
  (first (filter #(and (seq? %) (= 'ns (first %))) (todos/top-level-forms source))))

(defn- described
  "What a file says of itself and, for Clojure, the namespace it defines:
   {:what sentence :ns symbol}, either of which may be missing."
  [file source]
  (let [lines (str/split-lines source)
        extension (fs/extension file)]
    (cond
      (#{"clj" "cljc" "bb"} extension)
      (let [[_ named & more] (namespace-form source)]
        {:ns (when (symbol? named) named)
         :what (first-sentence (or (first (filter string? more)) (opening-comment lines)))})

      ;; The header line of a library, else the first line of the comment:
      ;; such a comment is a line to a thought, not sentences.
      (= "el" extension)
      {:what (or (second (re-find #"^;;; \S+ --- (.*?)(?:\s+-\*-.*)?$" (str (first lines))))
                 (some-> (opening-comment lines) str/split-lines first))}

      (= "org" extension)
      {:what (some #(second (re-find #"(?i)^#\+TITLE:\s*(.+)$" %)) lines)}

      (= "md" extension)
      {:what (some #(second (re-find #"^# (.+)$" %)) lines)}

      ;; A Makefile opens with a comment on its first variable, not on itself.
      (= "Makefile" (fs/file-name file))
      {}

      :else
      {:what (first-sentence (opening-comment lines))})))

(defn- collapsed
  "entries with the files under each of directories gathered into one entry
   that counts them: a directory of imported configuration is one fact."
  [entries directories]
  (let [home (fn [{:keys [file]}] (first (filter #(str/starts-with? file (str % "/")) directories)))
        {gathered true single false} (group-by (comp some? home) entries)]
    (concat single
            (for [[directory files] (group-by home gathered)]
              {:file (str directory "/")
               :kind (:kind (first files))
               :files (count files)
               :lines (reduce + (keep :lines files))
               :whose (let [owners (distinct (map :whose files))]
                        (if (= 1 (count owners)) (first owners) "mixed"))}))))

(defn model
  "The files git tracks, as maps: every way of writing the list is drawn
   from this. :whose is left out when :upstream is not a branch here.
   The directories in :collapse are one entry each."
  [{:keys [upstream collapse] :or {upstream "upstream/main" collapse [".clj-kondo"]}}]
  (let [theirs (some-> (git "ls-tree" "-r" "--name-only" upstream) set)
        changed (when theirs (set (git "diff" "--name-only" upstream "--")))
        entries (for [file (git "ls-files")
                      :let [source (try (slurp file) (catch Exception _ nil))
                            {:keys [what] named :ns} (when source (described file source))
                            exercise (when (#{"source" "test"} (kind file)) (todos/exercise-of file))]]
                  (cond-> {:file file :kind (kind file)}
                    source (assoc :lines (count (str/split-lines source)))
                    named (assoc :ns named)
                    exercise (assoc :exercise exercise)
                    what (assoc :what what)
                    theirs (assoc :whose (cond
                                           (not (theirs file)) "local"
                                           (changed file) "workshop, changed"
                                           :else "workshop"))))]
    (vec (sort-by (juxt #(.indexOf ^java.util.List kinds (:kind %)) :file)
                  (collapsed entries collapse)))))

;;;; ways of writing it

(defn- said
  "What goes in the last column: what the file says it is, or how many files
   a collapsed directory holds."
  [{:keys [what files]}]
  (cond
    files (str files " files")
    what what
    :else ""))

(defn- columns
  "The header and the rows of the list. The whose column is there only when
   the files have one."
  [entries link]
  (let [whose? (some :whose entries)]
    (cons (cond-> ["file" "kind" "lines" "exercise"] whose? (conj "whose") true (conj "what it says it is"))
          (for [{:keys [file kind lines exercise whose] :as entry} entries]
            (cond-> [(link file) kind (str lines) (or exercise "")]
              whose? (conj (or whose ""))
              true (conj (said entry)))))))

(defn- as-text [entries]
  (let [[header & rows :as all] (columns entries identity)
        widths (apply map (fn [& cells] (apply max 1 (map count cells))) all)
        line (fn [cells] (str/trimr (str/join "  " (map #(format (str "%-" %1 "s") %2) widths cells))))]
    (str/join "\n" (cons (line header) (map line rows)))))

(defn- as-edn [entries]
  (str/trimr (with-out-str (pprint/pprint entries))))

(defn- as-org [entries]
  (org/table (columns entries #(if (str/ends-with? % "/") (str "~" % "~") (str "[[file:" % "][" % "]]")))))

(defn- as-mermaid
  "The directories as a tree, left to right: a rounded node for a directory,
   a square one for a file."
  [entries]
  (let [parts #(str/split % #"/")
        paths (distinct
               (for [{:keys [file]} entries
                     depth (range 1 (inc (count (parts file))))]
                 (vec (take depth (parts file)))))
        id (zipmap (cons [] paths) (map #(str "n" %) (range)))
        gathered (into {} (for [{:keys [file files]} entries :when files] [(parts file) files]))
        directory? (into (set (map pop paths)) (keys gathered))]
    (str/join
     "\n"
     (concat
      ["flowchart LR"
       (str "  " (id []) "([\".\"])")]
      (for [path paths]
        (str "  " (id (pop path)) " --> " (id path)
             (if (directory? path)
               (str "([\"" (peek path) "/"
                    (when-let [files (gathered path)] (str "<br/>" files " files"))
                    "\"])")
               (str "[\"" (peek path) "\"]"))))))))

(def formats #{:text :edn :org :mermaid})

(defn view
  "The files written as :as, a string: what `--as` prints, and what
   workshop.blocks puts in a document. The other options are model's."
  [{:keys [as] :or {as :text} :as options}]
  (let [entries (model options)]
    (case as
      :text (as-text entries)
      :edn (as-edn entries)
      :org (as-org entries)
      :mermaid (as-mermaid entries))))

;;;; entry point

(def spec
  {:as {:desc "How to write it: text, edn, org or mermaid"
        :coerce :keyword
        :default :text}
   :upstream {:desc "The branch whose files are the workshop's"
              :default "upstream/main"}
   :collapse {:desc "Directories to list as one entry"
              :coerce [:string]
              :default [".clj-kondo"]}})

(defn files
  "Print the files git tracks, with what each says it is."
  {:org.babashka/cli {:spec spec :restrict true}}
  [{:keys [as] :as options}]
  (when-not (formats as)
    (println (str "No way to write it as " (name as) ": " (str/join ", " (sort (map name formats)))))
    (System/exit 1))
  (println (view options)))

(defn -main [& args]
  (files (cli/parse-opts args {:spec spec :restrict true})))
