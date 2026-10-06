(ns workshop.copy-commands
  "What a copy command is, checked against the platform's own clipboard:
   `bb -cp dev:src -m workshop.copy-commands`, or `gmake copy-commands`.

   A copy command reads its standard input to the end, exits 0, prints
   nothing, and leaves that text as the clipboard's plain text, so that the
   platform's own interface reads back the same bytes. The baseline is that
   interface, never another command of the same family:

     macOS    NSPasteboard, the general board: type public.utf8-plain-text,
              its changeCount up with each write. pbcopy.
     X11      the CLIPBOARD selection: its owner answers a request for the
              UTF8_STRING target. The owner is a process, which is why
              xclip and xsel leave one behind. Not checked here.
     Wayland  the selection: a wl_data_source offering
              text/plain;charset=utf-8, served by the process wl-copy leaves
              behind. Not checked here.
     Windows  the clipboard, format CF_UNICODETEXT. clip. Not checked here.

   On macOS the board is read through JXA, osascript's bridge to AppKit.
   By default pbcopy is aimed at the find pasteboard: the same program and
   the same interface, on a board no clipboard history watches. BOARD=general
   checks the entry as `tools` has it, on the clipboard itself, where a
   clipboard history, if one runs, keeps the test values. Either way every
   item and type the board held is saved before the first write and put
   back after the last.

   Tooling for working through the workshop, not part of Spectre: it lives
   in dev/, and needs src/ on the classpath only for spectre.clipboard."
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [spectre.clipboard :as clipboard])
  (:import
   (java.util Base64)))

;;;; NSPasteboard through JXA

(def ^:private board-js
  "JXA that finds the board named by the first argument, general or find."
  "ObjC.import('AppKit');
   function board(name) {
     return name === 'find' ? $.NSPasteboard.pasteboardWithName($.NSFindPboard)
                            : $.NSPasteboard.generalPasteboard;
   }")

(def ^:private read-js
  (str board-js "
   function run(argv) {
     const b = board(argv[0]);
     const data = b.dataForType($.NSPasteboardTypeString);
     return JSON.stringify({changeCount: Number(b.changeCount),
                            types: ObjC.deepUnwrap(b.types) || [],
                            utf8: data.isNil() ? null : data.base64EncodedStringWithOptions(0).js});
   }"))

(def ^:private save-js
  (str board-js "
   function run(argv) {
     const items = ObjC.unwrap(board(argv[0]).pasteboardItems) || [];
     return JSON.stringify(items.map(item => {
       const entry = {};
       (ObjC.deepUnwrap(item.types) || []).forEach(type => {
         const data = item.dataForType(type);
         if (!data.isNil()) entry[type] = data.base64EncodedStringWithOptions(0).js;
       });
       return entry;
     }));
   }"))

(def ^:private restore-js
  (str board-js "
   function run(argv) {
     const b = board(argv[0]);
     const saved = JSON.parse($.NSString.stringWithContentsOfFileEncodingError(
                                argv[1], $.NSUTF8StringEncoding, null).js);
     b.clearContents;
     const items = saved.map(entry => {
       const item = $.NSPasteboardItem.alloc.init;
       Object.keys(entry).forEach(type => item.setDataForType(
         $.NSData.alloc.initWithBase64EncodedStringOptions(entry[type], 0), type));
       return item;
     });
     if (items.length > 0) b.writeObjects($(items));
     return Number(b.changeCount);
   }"))

(defn- jxa [script & arguments]
  (let [{:keys [exit out err]} (apply p/sh "osascript" "-l" "JavaScript" "-e" script arguments)]
    (when-not (zero? exit)
      (throw (ex-info (str "osascript: " (str/trim err)) {:exit exit})))
    (str/trim out)))

(defn read-board
  "The board as NSPasteboard has it: :changeCount, :types, and :utf8, the
   bytes of its plain text, or nil when it has none."
  [board]
  (let [state (json/parse-string (jxa read-js board) true)]
    (update state :utf8 #(when % (.decode (Base64/getDecoder) ^String %)))))

(defn save-board
  "Every item and type on the board, into a file, for `restore-board`."
  [board file]
  (spit (str file) (jxa save-js board))
  file)

(defn restore-board [board file]
  (jxa restore-js board (str file)))

;;;; The check

(def samples
  "Text a copy command has to carry exactly, and why each is here."
  [["a Spectre password" "b0+aejObRu&7LB&Y#j%h"]
   ["a trailing newline" "line\n"]
   ["tabs and CR LF" "a\tb\r\nc"]
   ["accents and an emoji" "é ü 🔑"]
   ["200 KB, past a pipe's buffer" (apply str (repeat 200000 "x"))]
   ["the empty string" ""]])

(defn- without-locale
  "This environment with LANG and every LC_ variable taken out."
  []
  (into {} (remove (fn [[k _]] (re-find #"^(LANG|LC_)" k))) (System/getenv)))

(defn- copy-in-a-child
  "copy! of value with cmd, in a babashka of its own so that what the command
   prints can be seen: {:returned what copy! returned, :printed}."
  [value cmd env]
  (let [code (str "(require 'spectre.clipboard) "
                  "(binding [*out* *err*] (prn (spectre.clipboard/copy! (slurp (System/getenv \"COPY_VALUE_FILE\")) "
                  (pr-str cmd) ")))")
        value-file (fs/create-temp-file {:suffix ".value"})]
    (try
      (spit (str value-file) value)
      (let [{:keys [out err]} (p/sh {:env (assoc env "COPY_VALUE_FILE" (str value-file))}
                                    "bb" "-cp" (str (fs/absolutize "src")) "-e" code)]
        {:returned (str/trim err) :printed out})
      (finally (fs/delete-if-exists value-file)))))

(defn- outcome
  "What copying value with cmd did to the board, against the contract."
  [board cmd value env]
  (let [before (read-board board)
        {:keys [returned printed]} (copy-in-a-child value cmd env)
        after (read-board board)
        expected (.getBytes ^String value "UTF-8")
        received (:utf8 after)
        changed? (> (:changeCount after) (:changeCount before))
        excerpt #(pr-str (subs % 0 (min 30 (count %))))
        failures (cond-> []
                   (not= (pr-str cmd) returned)
                   (conj (str "copy! returned " returned))
                   (not changed?)
                   (conj "the board did not change")
                   (and changed? (seq value) (not (some #{"public.utf8-plain-text"} (:types after))))
                   (conj (str "no plain text on the board: " (str/join " " (:types after))))
                   (and changed? (not (java.util.Arrays/equals expected (or received (byte-array 0)))))
                   (conj (str "read back " (excerpt (String. ^bytes (or received (byte-array 0)) "UTF-8"))))
                   (seq printed)
                   (conj (str "printed " (excerpt printed))))]
    {:failures failures
     :summary (format "changeCount %d -> %d, %d bytes back%s"
                      (:changeCount before) (:changeCount after)
                      (count (or received [])) (if (seq printed) ", printed" ""))}))

(defn- check-command
  "Each sample through cmd, the first only for a command here for contrast.
   Returns the number of samples that failed."
  [board cmd {:keys [expect-failure?]}]
  (println (str "\n" (str/join " " cmd)
                (when expect-failure? "   (not a copy command: here for contrast)")))
  (count
   (for [[label value] (if expect-failure? (take 1 samples) samples)
         :let [{:keys [failures summary]} (outcome board cmd value (into {} (System/getenv)))
               passed? (empty? failures)
               _ (println (format "  %-4s %-30s %s" (if passed? "ok" "FAIL") label
                                  (if passed? summary (str/join "; " failures))))]
         :when (not passed?)]
     label)))

(defn- locale-check
  "The same accented sample with no locale in the environment."
  [board cmd]
  (let [{:keys [failures summary]} (outcome board cmd "é ü 🔑" (without-locale))]
    (println (format "  %-4s %-30s %s" (if (empty? failures) "ok" "FAIL")
                     "accents, with no LANG or LC_*" (if (empty? failures) summary (str/join "; " failures))))))

(def spec
  {:board {:desc "general (the clipboard itself) or find (the default)"
           :default "find"}})

(defn -main [& args]
  (let [{:keys [board]} (cli/parse-opts args {:spec spec :restrict true})]
    (when-not (= "Mac OS X" (System/getProperty "os.name"))
      (println (str "No baseline reader for " (System/getProperty "os.name") " in this check yet;"
                    " see the namespace docstring for what a copy command is there."))
      (System/exit 2))
    (when-not (#{"find" "general"} board)
      (println "BOARD is general or find")
      (System/exit 2))
    (let [found (clipboard/tool)
          cmd (if (= "general" board) found (conj found "-pboard" "find"))
          saved (fs/create-temp-file {:suffix ".board.json"})]
      (when-not (= ["pbcopy"] found)
        (println (str "The first tool found is " (pr-str found) ", and this check knows pbcopy only."))
        (System/exit 2))
      (println (str "macOS: NSPasteboard, the " board " board"
                    (when (= "find" board) " (BOARD=general for the clipboard itself)")))
      (save-board board saved)
      (try
        (let [failed (check-command board cmd {})]
          (locale-check board cmd)
          (check-command board ["cat"] {:expect-failure? true})
          (check-command board ["true"] {:expect-failure? true})
          (println (str "\n" (str/join " " cmd) ": "
                        (if (zero? failed)
                          "a copy command, in a UTF-8 locale"
                          (str failed " of " (count samples) " samples failed"))))
          (when (pos? failed) (System/exit 1)))
        (finally
          (restore-board board saved)
          (println (str "(the " board " board holds again what it held before)"))
          (fs/delete-if-exists saved))))))
