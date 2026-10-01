#!/usr/bin/env bb
;; What this checkout's REPL and editor session look like right now:
;; `bb dev/session-status.bb`, `gmake status`, and the SessionStart hook in
;; .claude/settings.json, whose stdout becomes context for the agent.
;;
;; It reports and never starts, stops or kills anything. The two rules it
;; checks: a .nrepl-port file is a claim, not a fact, so ask the port; a REPL
;; that answers may still belong to another checkout, so ask it where it is
;; running.

(ns session-status
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [bencode.core :as bencode]
   [clojure.string :as str])
  (:import
   (java.io PushbackInputStream)
   (java.net InetSocketAddress Socket)))

(def root
  (str (fs/canonicalize (or (System/getenv "CLAUDE_PROJECT_DIR") "."))))

(def worktree?
  "In a worktree .git is a file pointing at the main checkout's git dir."
  (fs/regular-file? (fs/path root ".git")))

(defn- sh [& command]
  (try
    (let [{:keys [exit out]} (apply p/sh {:dir root :err :out} command)]
      (when (zero? exit) (str/trim out)))
    (catch Exception _ nil)))

;;;; What the checkout should have

(def session-name
  "The tmux session `gmake session` uses here: same rule as the Makefile."
  (if worktree? (str "spectre-" (fs/file-name root)) "spectre"))

(def derived-port
  "The fixed nREPL port .envrc derives here: same rule as .envrc."
  (if worktree?
    (let [sum (-> (p/sh {:in (str (fs/file-name root) "\n")} "cksum") :out (str/split #" ") first parse-long)]
      (+ 1700 (mod sum 300)))
    1667))

;;;; Asking a port

(defn- text [value]
  (if (bytes? value) (String. ^bytes value "UTF-8") value))

(defn- nrepl-eval
  "Evaluate code on the nREPL at port. Returns the value as a string, or nil
   when nothing answers within a second."
  [port code]
  (try
    (with-open [socket (Socket.)]
      (.connect socket (InetSocketAddress. "127.0.0.1" (int port)) 500)
      (.setSoTimeout socket 1000)
      (let [in (PushbackInputStream. (.getInputStream socket))
            out (.getOutputStream socket)]
        (bencode/write-bencode out {"op" "eval" "code" code "id" "status"})
        (.flush out)
        (loop [value nil]
          (let [message (bencode/read-bencode in)
                value (or (some-> (get message "value") text) value)]
            (if (some #(= "done" (text %)) (get message "status"))
              value
              (recur value))))))
    (catch Exception _ nil)))

(defn- repl-at
  "What answers on port: {:dir its working directory, :runtime bb or JVM}."
  [port]
  (when-let [answer (nrepl-eval port "[(System/getProperty \"user.dir\") (or (System/getProperty \"babashka.version\") \"jvm\")]")]
    (let [[dir runtime] (read-string answer)]
      {:dir (str (fs/canonicalize dir))
       :runtime (if (= "jvm" runtime) "JVM Clojure" (str "babashka " runtime))})))

;;;; The report

(defn- port-file-line []
  (let [file (fs/path root ".nrepl-port")]
    (if-not (fs/exists? file)
      ["nREPL" "no .nrepl-port: no jacked-in REPL here. Start one with `gmake session`."]
      (let [port (parse-long (str/trim (slurp (str file))))
            repl (when port (repl-at port))]
        (cond
          (nil? repl)
          ["nREPL" (str "STALE: .nrepl-port says " port " but nothing answers. "
                        "Do not trust it; `gmake session` starts a fresh one.")]

          (not= root (:dir repl))
          ["nREPL" (str "WRONG CHECKOUT: port " port " answers from " (:dir repl)
                        ", not here. Evaluating there tests that checkout's code.")]

          :else
          ["nREPL" (str "live on " port ", " (:runtime repl) ", running in this checkout. "
                        "`brepl -e '(form)'` evaluates in it.")])))))

(defn- fixed-port-line []
  (let [from-env (System/getenv "NREPL_PORT")
        repl (repl-at derived-port)]
    ["fixed port"
     (str derived-port " for `gmake nrepl` / `gmake e5-tui`"
          (cond
            (and from-env (not= from-env (str derived-port)))
            (str ". NREPL_PORT in this environment is " from-env ", which overrides it")
            (nil? from-env) ". NREPL_PORT is not set here (direnv not loaded in this shell)"
            :else "")
          (cond
            (nil? repl) "; nothing listening"
            (= root (:dir repl)) "; in use by this checkout"
            :else (str "; IN USE BY " (:dir repl))))]))

(defn- tmux-line []
  (let [windows (sh "tmux" "list-windows" "-t" session-name "-F" "#{window_index}:#{window_name} #{pane_current_path}")]
    ["tmux"
     (if windows
       (str "session `" session-name "` is running (" (str/join ", " (str/split-lines windows))
            "). `gmake session-shot` prints its screen.")
       (str "no session `" session-name "`. `gmake session` starts Emacs in it, jacked in."))]))

(defn- environment-line []
  ["environment"
   (str (if (fs/exists? (fs/path root ".env")) ".env present" "no .env (`gmake .env`)")
        (let [status (sh "direnv" "status")]
          (cond
            (nil? status) ""
            (re-find #"Found RC allowed (0|true)" status) ", direnv allowed"
            :else ", direnv NOT allowed for this checkout (`direnv allow`)"))
        (when-let [db (System/getenv "SPECTRE_DB")] (str ", SPECTRE_DB=" db)))])

(defn -main [& _args]
  (let [branch (or (sh "git" "rev-parse" "--abbrev-ref" "HEAD") "?")
        lines [["checkout" (str root " (" (if worktree? "worktree" "main checkout") ", branch " branch ")")]
               (port-file-line)
               (fixed-port-line)
               (tmux-line)
               (environment-line)]]
    (println "Clojure project (babashka). Session status:")
    (doseq [[label line] lines]
      (println (format "  %-12s %s" label line)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
