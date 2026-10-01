(ns spectre.clipboard
  "Copy to the system clipboard by shelling out."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]))

(def ^:private tools
  [["pbcopy"]
   ["wl-copy"]
   ["xclip" "-selection" "clipboard"]
   ["xsel" "-ib"]
   ["clip"]])

(defn tool
  "First available clipboard command, or nil."
  []) ;; TODO

(defn copy!
  "Copy s to the clipboard with cmd, by default the first available tool.
   Returns the command used, or nil when there is none. The value goes over
   stdin, never argv."
  ([s] (copy! s (tool)))
  ([s cmd])) ;; TODO

(comment
  ;; Load this file first (C-c C-k): fs/ and p/ only resolve, for evaluation
  ;; and for C-c C-d d alike, once the namespace exists in the REPL.

  ;; the docs for the calls the TODOs name; they print in the REPL buffer
  (require '[clojure.repl :refer [dir doc source]])
  (doc fs/which)
  (doc p/shell)
  (doc p/sh)
  (dir babashka.process)
  (source fs/which)

  ;; what they give back
  tools
  (fs/which "cat")
  (str (fs/which "cat"))
  (fs/which "no-such-clipboard-tool")
  (select-keys (p/sh {:in "a b c"} "cat") [:exit :out :err])
  (:out (p/shell {:in "a b c" :out :string} "cat"))
  ;; with no :out, the output goes to the nREPL server's stdout, not here
  (p/shell {:in "a b c"} "cat")

  ;; once copy! is written this overwrites your real clipboard
  (copy! "foo"))
