(ns workshop.blocks
  "Rebuild the parts of the Org documents that are built from the files:
   `bb -cp dev -m workshop.blocks`, or `gmake blocks`.

   Dev tooling, like workshop.todos. Such a part is an Org dynamic block
   named workshop. Its first line says what it is a view of and how that is
   written:

     #+BEGIN: workshop :view namespaces :as mermaid :from \"spectre.cli\"
     ...
     #+END:

   :view is namespaces or files, for workshop.namespaces and workshop.files.
   :as is one of their formats: text, edn, org, mermaid. The other
   parameters are that view's options, a list given as one string with
   spaces. Everything between the two lines is replaced.

   With no arguments the Org files at the top of the repo are brought up to
   date; name files to do those instead. --check writes nothing and exits 1
   when a block is not what the files give now. --emit prints the body of one
   block from its parameters given as options, for an editor to insert.

   A file that a running Emacs holds with unsaved edits is left alone: a
   write under such a buffer turns its next save into a conflict."
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [workshop.files :as files]
   [workshop.namespaces :as namespaces]
   [workshop.org :as org]))

;;;; what goes in a block

(def ^:private views
  "What a block can be a view of: its name, the function that writes it, and
   the formats that function knows."
  {"namespaces" {:write namespaces/view :formats namespaces/formats}
   "files" {:write files/view :formats files/formats}})

(def ^:private notice
  "The first line of every body: an Org comment, so it is not exported."
  "# Built from the files by `gmake blocks`. An edit between these two lines is overwritten.")

(defn- yes? [value]
  (contains? #{"t" "true" "yes" true} value))

(defn- words [value]
  (str/split (str/trim value) #"\s+"))

(defn- options
  "A block's parameters as the options its view takes."
  [{:keys [as from dirs libraries progress direction upstream collapse]}]
  (cond-> {:as (keyword (or as "text"))}
    from (assoc :from (map symbol (words from)))
    dirs (assoc :dirs (words dirs))
    (yes? libraries) (assoc :libraries true)
    (yes? progress) (assoc :progress true)
    direction (assoc :direction direction)
    upstream (assoc :upstream upstream)
    collapse (assoc :collapse (words collapse))))

(def ^{:arglists '([parameters])} body
  "What belongs between the two lines of a block with these parameters.
   Throws, with the reason, when there is nothing to write. Remembered for
   the run: a check and a rewrite ask for the same views."
  (memoize
   (fn [{:keys [view file] :as parameters}]
     (let [{:keys [write formats]}
           (or (views view)
               (throw (ex-info (str "names no view: :view is one of " (str/join ", " (sort (keys views)))) {})))
           {:keys [as] :as view-options} (options parameters)]
       (when-not (formats as)
         (throw (ex-info (str "asks for " view " as " (name as) ": it is written as "
                              (str/join ", " (sort (map name formats))))
                         {})))
       (let [text (write view-options)]
         (str notice "\n"
              (case as
                :org text
                :text (org/example-block text)
                :edn (org/src-block "clojure" ":eval no" text)
                ;; with :file, ob-mermaid can draw it from inside Emacs
                :mermaid (org/src-block "mermaid" (when file (str ":file " file)) text))))))))

;;;; the blocks of a document

(defn- with-expected
  "block with :expected, what it should hold now, or with the :problem that
   keeps that from being worked out."
  [{:keys [parameters problem] :as block}]
  (if problem
    block
    (try
      (assoc block :expected (body parameters))
      (catch Exception caught
        (assoc block :problem (ex-message caught))))))

(defn- described [{:keys [view as]}]
  (str view " as " (or as "text")))

(defn problems
  "What is wrong with the blocks of files, as {:file :line :message}: one
   that cannot be read, and one whose body is not what the files give now."
  [files]
  (for [file files
        {:keys [line problem body expected parameters]} (map with-expected (org/blocks (slurp file)))
        :let [message (cond
                        problem (str "generated block " problem)
                        (not= body expected) (str "generated block (" (described parameters)
                                                  ") is behind the files: gmake blocks"))]
        :when message]
    {:file file :line line :message message}))

(defn- emacs-has
  "How a running Emacs holds file: :modified, with edits not yet saved;
   :open; or nil, which is also the answer when none is running. The form
   only reads."
  [file]
  (let [form (str "(let ((buffer (find-buffer-visiting " (pr-str (str (fs/absolutize file))) ")))"
                  " (cond ((null buffer) nil) ((buffer-modified-p buffer) 'modified) (t 'open)))")
        answer (try
                 (p/sh "emacsclient" "--alternate-editor=false" "--eval" form)
                 (catch Exception _ nil))]
    (when (and answer (zero? (:exit answer)))
      (#{:modified :open} (keyword (str/trim (:out answer)))))))

(defn- counted [number noun]
  (str number " " noun (when (not= 1 number) "s")))

(defn rewrite!
  "Bring the blocks of each file up to the files, saying what was done.
   Returns the problems that are left: a block that cannot be read, and a
   file left alone because Emacs has unsaved edits to it."
  [files]
  (doall
   (mapcat
    (fn [file]
      (let [text (slurp file)
            found (map with-expected (org/blocks text))
            behind (filter #(and (:expected %) (not= (:body %) (:expected %))) found)
            unreadable (for [{:keys [line problem]} found
                             :when problem]
                         {:file file :line line :message (str "generated block " problem)})
            held (when (seq behind) (emacs-has file))]
        (cond
          (empty? found)
          nil

          (empty? behind)
          (do (println (format "%-16s %s, as the files give them" file (counted (count found) "block")))
              unreadable)

          (= :modified held)
          (cons {:file file
                 :line (:line (first behind))
                 :message "has unsaved edits in the running Emacs: save it there, then gmake blocks"}
                unreadable)

          :else
          (do (spit file (org/rewrite text (comp :expected with-expected)))
              (println (format "%-16s %s, %d rebuilt%s" file (counted (count found) "block") (count behind)
                               (if held ": open in Emacs, so M-x revert-buffer there" "")))
              unreadable))))
    files)))

;;;; entry point

(def spec
  {:check {:desc "Write nothing; exit 1 when a block is behind the files"
           :coerce :boolean}
   :emit {:desc "Print the body of one block, from the options below"
          :coerce :boolean}
   :view {:desc "With --emit: namespaces or files"}
   :as {:desc "With --emit: text, edn, org or mermaid"}
   :from {:desc "With --emit, for namespaces: only these and what they load"}
   :dirs {:desc "With --emit, for namespaces: directories to analyse"}
   :libraries {:desc "With --emit, for namespaces as mermaid: t to draw the libraries"}
   :progress {:desc "With --emit, for namespaces: t to count the TODOs left"}
   :direction {:desc "With --emit, for namespaces as mermaid: LR, TD, RL or BT"}
   :upstream {:desc "With --emit, for files: the branch whose files are the workshop's"}
   :collapse {:desc "With --emit, for files: directories to list as one entry"}
   :file {:desc "With --emit, as mermaid: the image ob-mermaid would draw"}})

(defn- documents
  "The Org files at the top of the repo."
  []
  (sort (map str (fs/glob "." "*.org"))))

(defn blocks
  "Rebuild or check the generated blocks, or print one."
  [{:keys [opts args]}]
  (let [files (or (seq args) (documents))
        {:keys [check emit]} opts]
    (if emit
      (try
        (println (body (update-vals (dissoc opts :check :emit) str)))
        (catch Exception caught
          (println (str "A block that " (ex-message caught)))
          (System/exit 1)))
      (let [found (if check (problems files) (rewrite! files))
            total (count (mapcat (comp org/blocks slurp) files))]
        (doseq [{:keys [file line message]} found]
          (println (str file ":" line ": " message)))
        (when (zero? total)
          (println (str "No generated blocks in " (str/join ", " files))))
        (when (and check (pos? total) (empty? found))
          (println (str (counted total "generated block") ", as the files give them")))
        (when (seq found)
          (System/exit 1))))))

(defn -main [& args]
  (blocks (cli/parse-args args {:spec spec :restrict true})))
