(ns workshop.namespaces
  "The namespaces in load order, with what each requires:
   `bb -cp dev -m workshop.namespaces`.

   Dev tooling, like workshop.todos. The requires come from clj-kondo's
   analysis, so this needs clj-kondo on the PATH; nothing is loaded or run.
   A namespace is listed after everything it requires, which is the order
   the REPL loads them in: `--from spectre.cli` shows what loading that one
   namespace pulls in, and in what order.

   A require inside a top-level (comment ...) is an exploration. Loading the
   file does not run it, so it is kept apart from the requires that load.

   --as says how the graph is written: text, the default; edn, the data the
   others are drawn from; org, a table; mermaid, a flowchart. workshop.blocks
   puts any of them into an Org document."
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [workshop.org :as org]
   [workshop.todos :as todos]))

;;;; what the files say

(defn- analysis
  "clj-kondo's analysis of dirs. It exits non-zero on lint findings, which
   are not this tool's business, so the exit code is ignored."
  [dirs]
  (-> (apply p/sh "clj-kondo" "--lint" (concat dirs ["--config" "{:output {:analysis true :format :edn}}"]))
      :out
      edn/read-string
      :analysis))

(defn- comment-rows
  "The [row end-row] of each top-level (comment ...) form in file."
  [file]
  (for [form (todos/top-level-forms (slurp file))
        :when (and (seq? form) (= 'comment (first form)))
        :let [{:keys [row end-row]} (meta form)]]
    [row end-row]))

(defn graph
  "{namespace {:file f :requires #{...} :explores #{...}}} for the namespaces
   defined in dirs. :requires is what loading the file loads; :explores is
   what only a (comment ...) block in it requires."
  [dirs]
  (let [{:keys [namespace-definitions namespace-usages]} (analysis dirs)
        commented (into {} (for [file (distinct (map :filename namespace-definitions))]
                             [file (comment-rows file)]))
        in-comment? (fn [{:keys [filename row]}]
                      (some (fn [[start end]] (<= start row end)) (commented filename)))
        by-namespace (fn [usages]
                       (reduce (fn [m {:keys [from to]}] (update m from (fnil conj (sorted-set)) to))
                               {}
                               usages))
        requires (by-namespace (remove in-comment? namespace-usages))
        explores (by-namespace (filter in-comment? namespace-usages))]
    (into (sorted-map)
          (for [{:keys [name filename]} namespace-definitions
                :let [loaded (get requires name (sorted-set))]]
            [name {:file filename
                   :requires loaded
                   :explores (into (sorted-set) (remove loaded) (get explores name))}]))))

(defn load-order
  "The namespaces of graph reachable from roots, each after the ones it
   requires. Depth first, requires in alphabetical order."
  [graph roots]
  (letfn [(visit [ordered namespace]
            (if (or (some #{namespace} ordered) (not (contains? graph namespace)))
              ordered
              (conj (reduce visit ordered (:requires (graph namespace))) namespace)))]
    (reduce visit [] roots)))

(defn model
  "The namespaces of :dirs reachable from :from, all of them when it is
   empty, in load order: a vector of maps, which every way of writing the
   graph is drawn from. :loads are namespaces of ours, :libraries the rest.
   With :progress, an exercise's namespace carries the TODOs it has left,
   which makes the result change as the exercises are worked."
  [{:keys [dirs from progress] :or {dirs ["src"]}}]
  (let [graph (graph dirs)
        left (when progress (frequencies (keep :exercise (todos/todos {}))))]
    (vec
     (for [[position namespace] (map-indexed vector (load-order graph (or (seq from) (keys graph))))
           :let [{:keys [file requires explores]} (graph namespace)
                 {ours true theirs false} (group-by #(contains? graph %) requires)
                 exercise (todos/exercise-of file)]]
       (cond-> {:order (inc position)
                :ns namespace
                :file file
                :loads (vec ours)
                :libraries (vec theirs)}
         exercise (assoc :exercise exercise)
         (seq explores) (assoc :explores (vec explores))
         (and progress exercise) (assoc :todos (get left exercise 0)))))))

;;;; ways of writing it

(defn- tag
  "What to say of a namespace beside its name: its exercise, and with
   :progress what is left of it. nil for one that is given."
  [{:keys [exercise todos]}]
  (when exercise
    (str exercise
         (when todos
           (str ", " (case (long todos)
                       0 "no TODOs left"
                       1 "1 TODO left"
                       (str todos " TODOs left")))))))

(defn- as-text [entries]
  (let [name-width (apply max 1 (map (comp count str :ns) entries))
        file-width (apply max 1 (map (comp count :file) entries))]
    (str/join
     "\n"
     (for [{:keys [order file loads libraries] namespace :ns :as entry} entries
           line [(str/trimr (format (str "%2d  %-" name-width "s  %-" file-width "s  %s")
                                    order (str namespace) file (or (tag entry) "")))
                 (when (seq loads) (str "      loads first: " (str/join " " loads)))
                 (when (seq libraries) (str "      libraries:   " (str/join " " libraries)))]
           :when line]
       line))))

(defn- as-edn [entries]
  (str/trimr (with-out-str (pprint/pprint entries))))

(defn- as-org [entries]
  (org/table
   (cons ["#" "namespace" "file" "exercise" "loads first" "libraries"]
         (for [{:keys [order file loads libraries] namespace :ns :as entry} entries]
           [(str order)
            (str "~" namespace "~")
            (str "[[file:" file "][" (fs/file-name file) "]]")
            (or (tag entry) "")
            (str/join " " loads)
            (str/join " " libraries)]))))

(defn- node
  "A name as a Mermaid node id: letters, digits and underscores only."
  [namespace]
  (str/replace (str namespace) #"[^A-Za-z0-9]" "_"))

(defn- as-mermaid
  "A flowchart, an arrow from each namespace to what it loads first, the
   exercises' namespaces drawn heavier. With :libraries the libraries are
   drawn too, rounded, on dotted arrows. :direction is Mermaid's: LR, the
   default, keeps the arrows apart better than TD does."
  [entries {with-libraries :libraries :keys [direction] :or {direction "LR"}}]
  (let [theirs (when with-libraries (sort (distinct (mapcat :libraries entries))))
        exercises (map (comp node :ns) (filter :exercise entries))]
    (str/join
     "\n"
     (concat
      [(str "flowchart " direction)]
      (for [{namespace :ns :as entry} entries]
        (str "  " (node namespace) "[\"" namespace (when-let [said (tag entry)] (str "<br/>" said)) "\"]"))
      (for [library theirs]
        (str "  " (node library) "([\"" library "\"])"))
      (for [{:keys [loads] namespace :ns} entries
            loaded loads]
        (str "  " (node namespace) " --> " (node loaded)))
      (when with-libraries
        (for [{:keys [libraries] namespace :ns} entries
              library libraries]
          (str "  " (node namespace) " -.-> " (node library))))
      (when (seq exercises)
        ["  classDef exercise stroke-width:3px"
         (str "  class " (str/join "," exercises) " exercise")])
      (when (seq theirs)
        ["  classDef library stroke-dasharray:4 3"
         (str "  class " (str/join "," (map node theirs)) " library")])))))

(def formats #{:text :edn :org :mermaid})

(defn view
  "The namespaces written as :as, a string: what `--as` prints, and what
   workshop.blocks puts in a document. The other options are model's."
  [{:keys [as] :or {as :text} :as options}]
  (let [entries (model options)]
    (case as
      :text (as-text entries)
      :edn (as-edn entries)
      :org (as-org entries)
      :mermaid (as-mermaid entries options))))

;;;; entry point

(def spec
  {:from {:desc "Only these namespaces and what they load, e.g. spectre.cli"
          :coerce [:symbol]
          :alias :f}
   :dirs {:desc "Directories to analyse"
          :coerce [:string]
          :default ["src"]}
   :as {:desc "How to write it: text, edn, org or mermaid"
        :coerce :keyword
        :default :text}
   :libraries {:desc "In the mermaid view, draw the libraries too"
               :coerce :boolean}
   :direction {:desc "In the mermaid view, which way it runs: LR, TD, RL or BT"
               :default "LR"}
   :progress {:desc "Count the TODOs left in each exercise"
              :coerce :boolean}})

(defn namespaces
  "Print the namespaces in load order with their requires."
  {:org.babashka/cli {:spec spec :restrict true}}
  [{:keys [from dirs as] :as options}]
  (when-not (formats as)
    (println (str "No way to write it as " (name as) ": " (str/join ", " (sort (map name formats)))))
    (System/exit 1))
  (when (and from (empty? (model options)))
    (println (str "No namespace " (str/join ", " from) " under " (str/join ", " dirs)))
    (System/exit 1))
  (println (view options)))

(defn -main [& args]
  (namespaces (cli/parse-opts args {:spec spec :restrict true})))
