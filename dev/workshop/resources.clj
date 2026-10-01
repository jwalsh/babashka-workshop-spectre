(ns workshop.resources
  "Fill the reference shelf: `bb -cp dev -m workshop.resources`, or
   `gmake resources`.

   Dev tooling, like workshop.todos: not part of Spectre, off the project's
   :paths, only what babashka bundles. It reads dev/resources.edn and puts
   each entry in resources/, at the version of the tool installed here: the
   API of the babashka.fs inside this babashka, the manual of the CIDER this
   Emacs loads, the Info files of this Emacs.

   resources/ is ignored by git and is not for committing: the documents are
   other people's. There is one shelf per clone, in the main checkout; in a
   worktree, resources is made a link to it, so the path is the same in every
   checkout.

   An entry is fetched when it is missing, when the installed version has
   moved since it was fetched, or when the entry itself was edited. One whose
   version cannot be found here (the tool is not installed, the network is
   down) is skipped, or kept if it is already on the shelf. --list shows
   where each stands and fetches nothing; --refresh fetches again whatever is
   there; --only ID takes one entry. What is on the shelf is written to
   resources/INDEX.org."
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.lang ProcessHandle)
   (java.time LocalDate)
   (java.util.regex Pattern)
   (java.util.zip GZIPInputStream)))

;;;; running commands

(def ^:private patience-ms
  "How long a command, or the wait for a server's answer, may take."
  60000)

(defn- command-output
  "What a command printed, trimmed: its stdout, or its stderr when stdout is
   empty, since some tools print their version there. nil when it fails,
   cannot be found, or is still running after a minute. git is told not to
   ask for a password: a misspelt repository should fail, not wait."
  [& command]
  (try
    (let [running (apply p/process {:out :string :err :string
                                    :extra-env {"GIT_TERMINAL_PROMPT" "0"}}
                         command)
          _ (.close (:in running))
          finished (deref running patience-ms nil)]
      (if (nil? finished)
        (do (p/destroy-tree running) nil)
        (when (zero? (:exit finished))
          (or (not-empty (str/trim (:out finished)))
              (not-empty (str/trim (:err finished)))))))
    (catch Exception _ nil)))

;;;; where things are

(def ^:private manifest-file "dev/resources.edn")

(def ^:private main-checkout
  "The checkout the clone started as: a worktree's git directory points back
   at it. The current directory when this is not a git repository."
  (delay
    (or (some-> (command-output "git" "rev-parse" "--path-format=absolute" "--git-common-dir")
                fs/parent
                str)
        (str (fs/cwd)))))

(defn- shelf
  "The shelf directory: resources/ in the main checkout."
  []
  (fs/path @main-checkout "resources"))

(defn- inside?
  "Whether path is other, or somewhere under it."
  [path other]
  (fs/starts-with? (fs/path path) (fs/path other)))

(defn- link-shelf-into-worktree!
  "In a worktree, make resources a relative link to the main checkout's
   shelf, unless something is already there."
  []
  (let [here (fs/cwd)
        link (fs/path here "resources")]
    (when (and (not (fs/same-file? here @main-checkout))
               (not (fs/exists? link {:nofollow-links true})))
      (fs/create-sym-link link (fs/relativize here (shelf)))
      (println (str "linked   resources -> " (fs/relativize here (shelf)))))))

;;;; which version is installed

(defn- emacs-program
  "The Emacs to ask: $EMACS when the Makefile passed one, else the first on
   PATH. Inside an Emacs shell EMACS is \"t\", which is not a program."
  []
  (let [named (System/getenv "EMACS")]
    (if (or (str/blank? named) (= "t" named)) "emacs" named)))

(defn- program [tool]
  (if (= "emacs" tool) (emacs-program) tool))

(defn- emacs-says
  "The value of form in Emacs, read as EDN. From the running Emacs when it
   has a server: its load path is the one in use, and an init file can put
   another copy of a library ahead of the one package.el installed. Else from
   a batch Emacs with package.el's packages. The form must only read: it is
   evaluated in an editor someone is using. No server is not a reason to
   start one, hence the alternate editor that does nothing."
  [form]
  (let [read-answer #(try (some-> % edn/read-string) (catch Exception _ nil))]
    (if-let [running (command-output "emacsclient" "--alternate-editor=false" "--eval" form)]
      {:value (read-answer running) :asked "the running Emacs"}
      {:value (read-answer (command-output (emacs-program) "--batch"
                                           "--eval" "(setq native-comp-enable-subr-trampolines nil)"
                                           "--eval" "(package-initialize)"
                                           "--eval" (str "(prin1 " form ")")))
       :asked "a batch Emacs, no running one having answered"})))

(def ^:private library-checkout
  "Where an Emacs library was installed from, as the Emacs in use finds it:
   {:checkout directory :asked which-emacs}. The directory is the top of the
   library's git checkout when it is one, else the directory the library is
   in, and nil when Emacs does not find the library."
  (memoize
   (fn [library]
     (let [{file :value :keys [asked]}
           (emacs-says (str "(let ((file (locate-library \"" library ".el\")))"
                            " (and file (file-truename file)))"))
           directory (some-> file fs/parent)
           top (some->> directory str
                        (#(command-output "git" "-C" % "rev-parse" "--show-toplevel"))
                        fs/path)]
       {:asked asked
        ;; The library's own checkout holds it at the top or one directory
        ;; down. A git repository further up is someone's ~/.emacs.d.
        :checkout (cond
                    (nil? directory) nil
                    (and top (or (= top directory) (= top (fs/parent directory)))) (str top)
                    :else (str directory))}))))

(def ^:private bundled
  "The libraries compiled into the babashka running this, as {lib version}."
  (delay
    (let [this-babashka (.. (ProcessHandle/current) info command (orElse "bb"))]
      (into {}
            (for [[lib coordinate] (:deps (edn/read-string (command-output this-babashka "print-deps")))
                  :when (:mvn/version coordinate)]
              [lib (:mvn/version coordinate)])))))

(defn- installed-version
  "The version a manifest entry's :version resolves to here, or nil: the
   tool is not installed, or the network did not answer."
  [[how & arguments]]
  (case how
    :literal (first arguments)
    :bb (System/getProperty "babashka.version")
    :bb-dep (get @bundled (first arguments))
    :command-version (let [[tool flag pattern] arguments
                           found (some->> (command-output (program tool) flag)
                                          (re-find (re-pattern pattern)))]
                       (if (vector? found) (first found) found))
    :git-describe (let [clone (str (fs/expand-home (first arguments)))]
                    (when (fs/directory? clone)
                      (command-output "git" "-C" clone "describe" "--tags" "--always")))
    :emacs-library (when-let [checkout (:checkout (library-checkout (first arguments)))]
                     ;; No version without a checkout: package.el installs the
                     ;; Lisp and leaves the manual out, so there is nothing to
                     ;; fetch a version of.
                     (when (fs/exists? (fs/path checkout ".git"))
                       (command-output "git" "-C" checkout "describe" "--tags" "--always")))
    :remote-head (let [[repo branch] arguments]
                   (some-> (command-output "git" "ls-remote" (str "https://github.com/" repo)
                                           (str "refs/heads/" branch))
                           (str/split #"\s+")
                           first
                           not-empty
                           (subs 0 7)))
    :etag (try
            (when-let [etag (some-> (http/head (first arguments) {:throw false :timeout patience-ms})
                                    (get-in [:headers "etag"])
                                    (str/replace #"^W/|\"" "")
                                    not-empty)]
              (subs etag 0 (min 12 (count etag))))
            (catch Exception _ nil))))

(defn- why-unknown
  "Why installed-version found nothing, for the line that reports it."
  [[how & arguments]]
  (case how
    :emacs-library (let [{:keys [checkout asked]} (library-checkout (first arguments))]
                     (str (if checkout
                            (str "not loaded from a checkout of its repository, but from " checkout)
                            "not a library Emacs finds")
                          ": says " asked))
    (:remote-head :etag) "the network did not answer, or has no such thing"
    "not installed here"))

(defn- git-ref
  "The tag or branch to fetch: the entry's :ref with the version put in."
  [{:keys [ref]} version]
  (if (str/includes? ref "%s") (format ref version) ref))

;;;; getting things

(defn- download!
  "Save url to file. Throws when the server does not answer 200."
  [url file]
  (fs/create-dirs (fs/parent file))
  (let [{:keys [status body]} (try
                                (http/get url {:as :stream :throw false :timeout patience-ms})
                                (catch java.io.IOException _
                                  (throw (ex-info (str "no answer from " url) {}))))]
    (with-open [in body]
      (when-not (= 200 status)
        (throw (ex-info (str "HTTP " status " for " url) {})))
      (io/copy in (fs/file file)))))

(defn- files-under
  "The regular files at path: itself, or everything under it."
  [path]
  (filter fs/regular-file? (file-seq (fs/file path))))

(defn- wanted-file?
  "Whether file passes the entry's :extensions, when it has any."
  [{:keys [extensions]} file]
  (or (empty? extensions)
      (contains? (set extensions) (fs/extension file))))

(defn- copy-in!
  "Copy each of the entry's :include paths found under root into target,
   keeping the wanted files and their places under root. With no :include,
   all of root."
  [{:keys [include] :as entry} root target]
  (doseq [relative (or include [""])
          :let [source (fs/normalize (fs/path root relative))]
          :when (fs/exists? source)
          file (file-seq (fs/file source))
          :when (and (fs/regular-file? file) (wanted-file? entry file))
          :let [destination (fs/path target (fs/relativize root file))]]
    (fs/create-dirs (fs/parent destination))
    (fs/copy file destination {:replace-existing true})))

(defmulti ^:private fetch!
  "Put one entry at target, a path under the shelf. Returns where it came from."
  (fn [entry _version _target] (:kind entry)))

(defmethod fetch! :url [{:keys [url]} _version target]
  (download! url target)
  url)

(defmethod fetch! :github-files [{:keys [repo paths] :as entry} version target]
  (let [ref (git-ref entry version)]
    (doseq [path paths]
      (download! (str "https://raw.githubusercontent.com/" repo "/" ref "/" path)
                 (fs/path target (fs/file-name path))))
    (str "github.com/" repo " at " ref)))

(defmethod fetch! :github-tree [{:keys [repo root] :as entry} version target]
  (let [ref (git-ref entry version)]
    (fs/with-temp-dir [scratch {}]
      (let [tarball (fs/path scratch "repository.tar.gz")
            unpacked (fs/path scratch "unpacked")]
        (download! (str "https://codeload.github.com/" repo "/tar.gz/" ref) tarball)
        (fs/create-dirs unpacked)
        (p/shell {:out :string :err :string} "tar" "-xzf" (str tarball) "-C" (str unpacked))
        ;; the tarball holds one directory, named after the repository and the ref
        (let [top (first (filter fs/directory? (fs/list-dir unpacked)))
              from (fs/path top (or root ""))]
          (when-not (fs/directory? from)
            (throw (ex-info (str "no " root " in " repo " at " ref) {})))
          (copy-in! entry from target))))
    (str "github.com/" repo " at " ref)))

(defmethod fetch! :local-tree [{:keys [from] :as entry} _version target]
  (let [root (fs/path (fs/expand-home from))]
    (when-not (fs/directory? root)
      (throw (ex-info (str "not on this machine: " from) {})))
    (copy-in! entry root target)
    from))

(defmethod fetch! :emacs-library [{:keys [library root] :as entry} _version target]
  (let [{:keys [checkout asked]} (library-checkout library)
        _ (when-not checkout
            (throw (ex-info (str "no library " library ", says " asked) {})))
        from (fs/path checkout (or root ""))]
    (when (fs/directory? from)
      (copy-in! entry from target))
    ;; package.el installs the Lisp and leaves the rest of the repository out
    (when (empty? (files-under target))
      (throw (ex-info (str "nothing of it beside " library " in " checkout ", says " asked) {})))
    (str from)))

(defn- info-locations
  "Where this Emacs finds each manual, as {manual path}, the path as Info
   names it: without the suffix of the file. A batch Emacs, so no init file;
   it only says where its own manuals are."
  [manuals]
  (let [form (str "(progn (require 'info) (info-initialize)"
                  " (dolist (manual '" (pr-str (apply list manuals)) ")"
                  " (princ (format \"%s\\t%s\\n\" manual"
                  " (or (ignore-errors (Info-find-file manual t)) \"\")))))")
        answer (command-output (emacs-program) "--batch"
                               "--eval" "(setq native-comp-enable-subr-trampolines nil)"
                               "--eval" form)]
    (into {}
          (for [line (some-> answer str/split-lines)
                :let [[manual path] (str/split line #"\t")]
                :when (not (str/blank? path))]
            [manual path]))))

(defmethod fetch! :emacs-info [{:keys [manuals]} _version target]
  (let [locations (info-locations manuals)]
    (fs/create-dirs target)
    (doseq [manual manuals
            :let [location (or (get locations manual)
                               (throw (ex-info (str "this Emacs has no Info manual " manual) {})))
                  ;; manual.info.gz here; manual, manual.info and numbered
                  ;; parts (manual.info-1.gz) elsewhere
                  its-files (re-pattern (str "^" (Pattern/quote manual) "(\\.info)?(-[0-9]+)?(\\.gz)?$"))
                  found (filter #(re-find its-files (fs/file-name %))
                                (fs/list-dir (fs/parent location)))]]
      (when (empty? found)
        (throw (ex-info (str "no file for " manual " beside " location) {})))
      (doseq [file found
              :let [[_ _ part compressed] (re-find its-files (fs/file-name file))
                    destination (fs/file (fs/path target (str manual ".info" part)))]]
        (if compressed
          (with-open [in (GZIPInputStream. (io/input-stream (fs/file file)))]
            (io/copy in destination))
          (fs/copy file destination {:replace-existing true}))))
    (str (fs/parent (val (first locations))))))

(defmethod fetch! :man [{:keys [pages]} _version target]
  (fs/create-dirs (fs/parent target))
  (spit (fs/file target)
        (str/join "\n"
                  (for [page pages
                        :let [{:keys [exit out]} (p/sh {:extra-env {"MANWIDTH" "100"}} "man" page)]]
                    (if (and (zero? exit) (not (str/blank? out)))
                      ;; man prints bold and underline by overstriking: a
                      ;; character, a backspace, a character
                      (str/replace out #".\x08" "")
                      (throw (ex-info (str "no manual page " page) {}))))))
  (str "man " (str/join ", man " pages)))

;;;; the manifest

(defn- manifest-problems
  "What is wrong with the manifest, as strings. Each entry is deleted and
   refetched whole, so a :to that leaves the shelf, or sits inside another
   entry's, would delete something that is not its own."
  [entries]
  (concat
   (for [[id same-id] (group-by :id entries)
         :when (< 1 (count same-id))]
     (str id " is the id of " (count same-id) " entries"))
   (for [{:keys [id kind]} entries
         :when (not (contains? (methods fetch!) kind))]
     (str id ": no way to fetch a " (pr-str kind)))
   (for [{:keys [id to]} entries
         :when (or (str/blank? to)
                   (fs/absolute? to)
                   (some #{".."} (map str (fs/components to))))]
     (str id ": :to " (pr-str to) " must be a relative path that stays under resources/"))
   (for [{:keys [id to]} entries
         other entries
         :when (and (not= id (:id other))
                    (not (str/blank? to))
                    (not (str/blank? (:to other)))
                    (inside? to (:to other)))]
     (str id ": :to " to " is inside " (:id other) "'s " (:to other)))))

(defn- read-manifest
  "The entries of dev/resources.edn. Exits 1 when one could delete what is
   not its own."
  []
  (let [entries (edn/read-string (slurp manifest-file))
        problems (manifest-problems entries)]
    (when (seq problems)
      (doseq [problem problems]
        (println (str manifest-file ":1: " problem)))
      (System/exit 1))
    entries))

;;;; what is on the shelf

(defn- size-of [files]
  (let [bytes (reduce + 0 (map fs/size files))]
    (cond
      (< bytes 1024) (str bytes " B")
      (< bytes (* 1024 1024)) (format "%.0f KB" (/ bytes 1024.0))
      :else (format "%.1f MB" (/ bytes 1024.0 1024.0)))))

(defn- state-file [] (fs/path (shelf) ".index.edn"))

(defn- definition
  "What of an entry decides what is fetched: all of it but its description."
  [entry]
  (dissoc entry :what))

(defn- read-state
  "What earlier runs fetched, as {id {:definition :version :source :fetched}}."
  []
  (if (fs/exists? (state-file))
    (edn/read-string (slurp (fs/file (state-file))))
    {}))

(defn- standing
  "Where an entry stands. :missing: not on the shelf. :stale: the installed
   version has moved since it was fetched, or the entry was edited. :current.
   When the version cannot be found here, :kept if it is on the shelf
   anyway, else :unknown."
  [state {:keys [id to] :as entry} version]
  (let [record (get state id)
        on-shelf? (and (fs/exists? (fs/path (shelf) to))
                       (= to (get-in record [:definition :to])))]
    (cond
      (and (nil? version) on-shelf?) :kept
      (nil? version) :unknown
      (not on-shelf?) :missing
      (not= version (:version record)) :stale
      (not= (definition entry) (:definition record)) :stale
      :else :current)))

(defn- delete-from-shelf!
  "Delete path, a place under the shelf, and the directories it leaves
   empty. The one place anything is deleted, so it is where the path is
   held to the shelf: a manifest is checked when read, a state file is not."
  [path]
  (let [target (fs/normalize (fs/path (shelf) path))]
    (when-not (and (fs/starts-with? target (shelf)) (not= target (shelf)))
      (throw (ex-info (str "will not delete " (pr-str path) ": not a place on the shelf") {})))
    (if (fs/directory? target) (fs/delete-tree target) (fs/delete-if-exists target))
    (loop [directory (fs/parent target)]
      (when (and (not= directory (shelf))
                 (fs/directory? directory)
                 (empty? (fs/list-dir directory)))
        (fs/delete directory)
        (recur (fs/parent directory))))))

(defn- retire!
  "Take off the shelf what an earlier run put where no entry puts anything
   now: an entry gone from the manifest, or moved. A place a current entry
   uses, or one that holds a current entry's, is left alone. Returns the
   state without those records."
  [entries state]
  (let [current (into {} (map (juxt :id :to)) entries)]
    (reduce-kv
     (fn [kept id record]
       (let [to (get-in record [:definition :to])]
         (cond
           (= to (get current id))
           (assoc kept id record)

           (or (str/blank? to) (some #(inside? % to) (vals current)))
           kept

           :else
           (do (delete-from-shelf! to)
               (println (format "retired  %-20s %s" id to))
               kept))))
     {}
     state)))

(defn- org-table
  "Rows of strings as an aligned Org table, the first row its header."
  [[header & rows]]
  (let [widths (apply map (fn [& cells] (apply max (map count cells))) header rows)
        line (fn [cells]
               (str "| " (str/join " | " (map #(format (str "%-" %1 "s") %2) widths cells)) " |"))
        rule (str "|-" (str/join "-+-" (map #(str/join (repeat % "-")) widths)) "-|")]
    (str/join "\n" (concat [(line header) rule] (map line rows)))))

(defn- strays
  "Top-level things on the shelf that no entry put there: yours."
  [entries]
  (let [ours (into #{"INDEX.org"}
                   (map #(str (first (fs/components (:to %)))))
                   entries)]
    (->> (fs/list-dir (shelf))
         (map fs/file-name)
         (remove #(or (ours %) (str/starts-with? % ".")))
         sort)))

(defn- write-index!
  "resources/INDEX.org: what is on the shelf, for a person or an agent."
  [entries state]
  (let [on-shelf (for [{:keys [id what to]} entries
                       :let [{:keys [version source fetched]} (get state id)
                             files (files-under (fs/path (shelf) to))]
                       :when (and version (seq files))]
                   [(str "[[file:" to "]]") what version (str (count files)) (size-of files)
                    source fetched])
        absent (for [{:keys [id what]} entries
                     :when (not (get state id))]
                 (str "- " id ": " what))
        others (strays entries)]
    (spit (fs/file (fs/path (shelf) "INDEX.org"))
          (str "#+TITLE: The reference shelf\n\n"
               "Written by ~gmake resources~ from ~dev/resources.edn~; an edit here is lost on\n"
               "the next run. Each document is for the version installed on this machine when\n"
               "it was fetched. How to search it is in the README, under The reference shelf.\n\n"
               "Local copies of other people's documents, for reading. ~resources/~ is ignored\n"
               "by git, and ~gmake guard-resources~ fails when any of it is tracked.\n\n"
               (org-table (cons ["where" "what" "version" "files" "size" "from" "fetched"] on-shelf))
               "\n"
               (when (seq absent)
                 (str "\nIn the manifest and not on the shelf:\n\n" (str/join "\n" absent) "\n"))
               (when (seq others)
                 (str "\nAlso here, and not from the manifest:\n\n"
                      (str/join "\n" (map #(str "- [[file:" % "]]") others)) "\n"))))))

;;;; entry point

(def spec
  {:list {:desc "Show where each entry stands and fetch nothing" :coerce :boolean}
   :refresh {:desc "Fetch again whatever is there, current or not" :coerce :boolean}
   :only {:desc "Only the entry with this id"}})

(defn- fetch-entry!
  "Fetch one entry and return its record for the state. It is fetched to one
   side and put in place once it is whole, so when this throws, what was on
   the shelf before is still there."
  [{:keys [id to] :as entry} version]
  (let [incoming (fs/path (shelf) ".incoming")
        staged (fs/path incoming to)
        target (fs/path (shelf) to)]
    (fs/delete-tree incoming)
    (try
      (let [source (fetch! entry version staged)
            files (files-under staged)
            _ (when (empty? files)
                (throw (ex-info "fetched, and nothing in it matched" {})))
            fetched (format "%d files, %s" (count files) (size-of files))]
        (delete-from-shelf! to)
        (fs/create-dirs (fs/parent target))
        (fs/move staged target)
        (println (format "fetched  %-20s %-24s %s" id version fetched))
        {:definition (definition entry)
         :version version
         :source source
         :fetched (str (LocalDate/now))})
      (finally
        (fs/delete-tree incoming)))))

(defn resources
  "Fetch what is missing or stale, and say what happened to each entry."
  {:org.babashka/cli {:spec spec :restrict true}}
  [{:keys [list refresh only]}]
  (let [all-entries (read-manifest)
        entries (cond->> all-entries only (filter #(= only (:id %))))]
    (when (empty? entries)
      (println (str (if only (str "No entry " only) "No entries") " in " manifest-file))
      (System/exit 1))
    (if list
      (let [state (read-state)]
        (doseq [{:keys [id to] :as entry} entries
                :let [version (installed-version (:version entry))]]
          (println (format "%-8s %-20s %-24s %s"
                           (name (standing state entry version)) id
                           (or version (get-in state [id :version]) "?") to))))
      (let [_ (fs/create-dirs (shelf))
            state (cond->> (read-state) (not only) (retire! all-entries))
            outcome (reduce
                     (fn [progress {:keys [id] :as entry}]
                       (let [version (installed-version (:version entry))
                             stands (standing (:state progress) entry version)]
                         (cond
                           (= :unknown stands)
                           (do (println (format "skipped  %-20s %s" id (why-unknown (:version entry))))
                               progress)

                           (= :kept stands)
                           (do (println (format "kept     %-20s %-24s %s"
                                                id (get-in progress [:state id :version])
                                                (why-unknown (:version entry))))
                               progress)

                           (and (= :current stands) (not refresh))
                           (do (println (format "current  %-20s %s" id version))
                               progress)

                           :else
                           (try
                             (assoc-in progress [:state id] (fetch-entry! entry version))
                             (catch Exception failure
                               (println (format "FAILED   %-20s %s" id
                                                (or (ex-message failure) (str (class failure)))))
                               (update progress :failed conj id))))))
                     {:state state :failed []}
                     entries)]
        (spit (fs/file (state-file)) (pr-str (:state outcome)))
        (write-index! all-entries (:state outcome))
        (link-shelf-into-worktree!)
        (let [files (remove #(str/starts-with? (fs/file-name %) ".") (files-under (shelf)))]
          (println (str "\n" (shelf) ": " (count files) " files, " (size-of files)
                        ". INDEX.org lists them.")))
        (when (seq (:failed outcome))
          (println (str "Not fetched: " (str/join ", " (:failed outcome))))
          (System/exit 1))))))

(defn -main [& args]
  (resources (cli/parse-opts args {:spec spec :restrict true})))
