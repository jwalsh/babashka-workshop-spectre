(ns workshop.agenda
  "List the TODO headings in the Org files, with their deadlines:
   `bb -cp dev -m workshop.agenda`.

   Dev tooling, like workshop.todos: not part of Spectre, off the project's
   :paths, only what babashka bundles. It reads the Org planning syntax and
   nothing more: a heading with a TODO keyword, and the DEADLINE, SCHEDULED
   or CLOSED line under it. Each row starts `file:line:`, so it is a link in
   an Emacs compilation buffer."
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [clojure.string :as str])
  (:import
   (java.time LocalDate)
   (java.time.temporal ChronoUnit)))

(def ^:private default-keywords
  {:open #{"TODO"} :done #{"DONE"}})

(defn- file-keywords
  "The keywords a file declares with #+TODO: or #+SEQ_TODO:, split at the
   bar into open and done, else Org's defaults. `TODO(t)` counts as `TODO`."
  [lines]
  (if-let [declaration (some #(second (re-find #"(?i)^#\+(?:SEQ_|TYP_)?TODO:\s*(.+)$" %)) lines)]
    (let [words (map #(str/replace % #"\(.*\)$" "") (str/split (str/trim declaration) #"\s+"))
          [open [_bar & done]] (split-with #(not= "|" %) words)]
      (if (seq done)
        {:open (set open) :done (set done)}
        {:open (set (butlast open)) :done #{(last open)}}))
    default-keywords))

(defn- planning
  "The dates on a planning line: {:deadline date, :scheduled date, ...}."
  [line]
  (into {}
        (for [[_ kind date] (re-seq #"(DEADLINE|SCHEDULED|CLOSED):\s*[<\[](\d{4}-\d{2}-\d{2})" line)]
          [(keyword (str/lower-case kind)) (LocalDate/parse date)])))

(defn- file-entries [file]
  (let [lines (str/split-lines (slurp (str file)))
        {:keys [open done]} (file-keywords lines)]
    (for [[index line] (map-indexed vector lines)
          :let [[_ stars keyword title] (re-find #"^(\*+)\s+(\S+)\s+(.*)$" line)]
          :when (and stars (or (open keyword) (done keyword)))]
      (merge {:file (str file)
              :line (inc index)
              :level (count stars)
              :keyword keyword
              :done? (boolean (done keyword))
              :title (str/trim (str/replace title #"\s+:[\w@:]+:\s*$" ""))}
             ;; the planning line, when there is one, is the very next line
             (planning (nth lines (inc index) ""))))))

(defn entries
  "Every TODO heading in the given Org files, as maps."
  [files]
  (mapcat file-entries files))

(defn- due
  "How far off date is from today, in words."
  [date today]
  (let [days (.between ChronoUnit/DAYS today date)]
    (cond
      (zero? days) "today"
      (= 1 days) "tomorrow"
      (pos? days) (str "in " days " days")
      (= -1 days) "1 day overdue"
      :else (str (- days) " days overdue"))))

(defn- row [{:keys [file line keyword title deadline scheduled done?]} today]
  (str file ":" line ": "
       (format "%-5s %-34s" keyword title)
       (cond
         done? ""
         deadline (str " due " deadline "  " (due deadline today))
         scheduled (str " scheduled " scheduled "  " (due scheduled today))
         :else " no deadline")))

(def spec
  {:all {:desc "Include the headings already marked done" :coerce :boolean :alias :a}
   :today {:desc "Pretend today is this date, e.g. 2026-10-02"}})

(defn agenda
  "Print the TODO headings, soonest deadline first, undated ones last."
  {:org.babashka/cli {:spec spec :restrict true}}
  [{:keys [all today files]}]
  (let [today (if today (LocalDate/parse today) (LocalDate/now))
        files (or (seq files) (sort (fs/glob "." "*.org")))
        found (cond->> (entries files) (not all) (remove :done?))
        overdue (filter #(some-> (:deadline %) (.isBefore today)) (remove :done? found))]
    (doseq [entry (sort-by (juxt #(str (or (:deadline %) (:scheduled %) "9999")) :file :line) found)]
      (println (row entry today)))
    (println (str "\n" (count found) " headings"
                  (when (seq overdue) (str ", " (count overdue) " overdue"))))))

(defn -main [& args]
  (let [{:keys [opts args]} (cli/parse-args args {:spec spec :restrict true})]
    (agenda (assoc opts :files args))))
