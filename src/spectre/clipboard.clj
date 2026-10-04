(ns spectre.clipboard
  "Copy to the system clipboard by shelling out."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]))

(def ^:private tools
  "Clipboard commands, in the order they are tried. A command is a vector
   of strings: the program, looked up on PATH, then its arguments."
  [["pbcopy"]
   ["wl-copy"]
   ["xclip" "-selection" "clipboard"]
   ["xsel" "-ib"]
   ["clip"]])

(defn tool
  "First available clipboard command, or nil: the first entry of tools whose
   program fs/which finds on PATH, such as [\"pbcopy\"] on a Mac."
  []) ;; TODO

(defn copy!
  "Copy s, a string, to the clipboard with cmd, by default the first
   available tool. cmd is a command as in tools, or nil. Returns the command
   used, or nil when there is none. The value goes over stdin, never argv."
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

  ;; fs/which takes one program name, and a command is a vector, so it is a
  ;; command's first element that is looked up (clj-kondo flags the vector)
  (map first tools)
  (fs/which "pbcopy")
  (fs/which ["pbcopy"])

  ;; -> puts the value first in each form, ->> last; filter and some take
  ;; the collection last
  (macroexpand '(-> tools (filter odd?) first))
  (macroexpand '(->> tools (filter odd?) first))
  (select-keys (p/sh {:in "a b c"} "cat") [:exit :out :err])
  (:out (p/shell {:in "a b c" :out :string} "cat"))
  ;; with no :out, the output goes to the nREPL server's stdout, not here
  (p/shell {:in "a b c"} "cat")

  ;; once copy! is written this overwrites your real clipboard
  (copy! "foo"))
