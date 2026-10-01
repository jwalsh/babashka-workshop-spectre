(ns workshop.namespaces
  "Print the namespaces in load order, with what each requires:
   `bb -cp dev -m workshop.namespaces`.

   Dev tooling, like workshop.todos. The requires come from clj-kondo's
   analysis, so this needs clj-kondo on the PATH; nothing is loaded or run.
   A namespace is listed after everything it requires, which is the order
   the REPL loads them in: `--from spectre.cli` shows what loading that one
   namespace pulls in, and in what order."
  (:require
   [babashka.cli :as cli]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(defn- analysis
  "clj-kondo's analysis of dirs. It exits non-zero on lint findings, which
   are not this tool's business, so the exit code is ignored."
  [dirs]
  (-> (apply p/sh "clj-kondo" "--lint" (concat dirs ["--config" "{:output {:analysis true :format :edn}}"]))
      :out
      edn/read-string
      :analysis))

(defn graph
  "{namespace {:file f :requires #{...}}} for the namespaces defined in dirs."
  [dirs]
  (let [{:keys [namespace-definitions namespace-usages]} (analysis dirs)
        requires (reduce (fn [m {:keys [from to]}] (update m from (fnil conj (sorted-set)) to))
                         {}
                         namespace-usages)]
    (into (sorted-map)
          (for [{:keys [name filename]} namespace-definitions]
            [name {:file filename :requires (get requires name (sorted-set))}]))))

(defn load-order
  "The namespaces of graph reachable from roots, each after the ones it
   requires. Depth first, requires in alphabetical order."
  [graph roots]
  (letfn [(visit [ordered namespace]
            (if (or (some #{namespace} ordered) (not (contains? graph namespace)))
              ordered
              (conj (reduce visit ordered (:requires (graph namespace))) namespace)))]
    (reduce visit [] roots)))

(def spec
  {:from {:desc "Only this namespace and what it loads, e.g. spectre.cli"
          :coerce :symbol
          :alias :f}
   :dirs {:desc "Directories to analyse"
          :coerce [:string]
          :default ["src"]}})

(defn namespaces
  "Print the namespaces in load order with their requires."
  {:org.babashka/cli {:spec spec :restrict true}}
  [{:keys [from dirs]}]
  (let [graph (graph dirs)
        ordered (load-order graph (if from [from] (keys graph)))
        width (apply max 0 (map (comp count str) ordered))]
    (when (and from (empty? ordered))
      (println (str "No namespace " from " under " (str/join ", " dirs)))
      (System/exit 1))
    (doseq [[position namespace] (map-indexed vector ordered)
            :let [{:keys [file requires]} (graph namespace)
                  {ours true theirs false} (group-by #(contains? graph %) requires)]]
      (println (format (str "%2d  %-" width "s  %s") (inc position) (str namespace) file))
      (when (seq ours) (println (str "      loads first: " (str/join " " ours))))
      (when (seq theirs) (println (str "      libraries:   " (str/join " " theirs)))))))

(defn -main [& args]
  (namespaces (cli/parse-opts args {:spec spec :restrict true})))
