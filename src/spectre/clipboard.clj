(ns spectre.clipboard
  "Copy to the system clipboard by shelling out."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]))

(def ^:private tools
  "Clipboard commands, in the order they are tried. A command is a vector
   of strings: the program, looked up on PATH, then its arguments.

   Each is a copy command: it reads its standard input to the end, exits 0,
   prints nothing, and leaves that text as the clipboard's plain text, so
   that the platform's own interface reads back the same bytes: NSPasteboard
   on macOS, the CLIPBOARD selection on X11, the Wayland selection, the
   Windows clipboard. copy! sees only the exit code; gmake copy-commands
   reads the board."
  [["pbcopy"]
   ["wl-copy"]
   ["xclip" "-selection" "clipboard"]
   ["xsel" "-ib"]
   ["clip"]])

(defn tool
  "First available clipboard command, or nil: the first entry of tools whose
   program fs/which finds on PATH, such as [\"pbcopy\"] on a Mac."
  []
  ;; some stops at the first; filter then first would ask about all five,
  ;; since a vector's seq comes in chunks
  (some (fn [[program :as command]]
          (when (fs/which program) command))
        tools))

(defn copy!
  "Copy s, a string, to the clipboard with cmd, by default the first
   available tool. cmd is a command as in tools, or nil. Returns the command
   used, or nil when there is none or it fails, so that a caller can print
   the value instead. The value goes over stdin, never argv."
  ([s] (copy! s (tool)))
  ([s cmd]
   (when cmd
     ;; shell and not sh: wl-copy, xclip and xsel leave a process behind to
     ;; serve the clipboard, holding their output open, and sh reads that
     ;; output to its end. :continue makes a failing tool an exit code
     (when (zero? (:exit (apply p/shell {:in s :continue true} cmd)))
       cmd))))

(comment
  ;; load this file first (C-c C-k): fs/ and p/ only resolve, for evaluation
  ;; and for C-c C-d d alike, once the namespace exists in the REPL

  ;; the docs for the calls tool and copy! make; they print in the REPL buffer
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

  ;; fs/which takes one program name, and a command is a vector, so it is a
  ;; command's first element that is looked up (clj-kondo flags the vector)
  (map first tools)
  (fs/which "pbcopy")
  (fs/which ["pbcopy"])

  ;; -> puts the value first in each form, ->> last; filter and some take
  ;; the collection last
  (macroexpand '(-> tools (filter odd?) first))
  (macroexpand '(->> tools (filter odd?) first))

  ;; a command handed over whole is run as one program named "[cat]";
  ;; apply spreads it into the arguments
  (p/sh {:in "a b c"} ["cat"])
  (:out (apply p/sh {:in "a b c"} ["tr" "a-c" "A-C"]))

  ;; tool and copy!, against a file standing in for the clipboard, the way
  ;; copy-test does it
  (tool)
  (def scratch (str (fs/create-temp-file {:suffix ".clip"})))
  (copy! "SECRET" ["tee" scratch])
  (slurp scratch)
  (copy! "SECRET" nil)
  ;; a tool that fails gives nil, as no tool does, and the CLI prints instead
  (copy! "SECRET" ["false"])
  (fs/delete-if-exists scratch)

  ;; this overwrites your real clipboard
  (copy! "foo")
  (:out (p/sh "pbpaste")))
