(ns spectre.clipboard-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [borkdude.deflet :as d]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [spectre.clipboard :as clipboard]))

(deftest copy-test
  (testing "the value is passed over stdin"
    (d/deflet
      (def f (fs/create-temp-file {:suffix ".clip"}))
      (def bb (or (System/getenv "BABASHKA_BINARY") "bb"))
      (def fake [bb "-e" (str "(spit " (pr-str (str f)) " (slurp *in*))")])
      (try
        (is (some? (clipboard/copy! "SECRET" fake)))
        (is (= "SECRET" (slurp (str f))))
        (finally (fs/delete-if-exists f))))))

(deftest no-tool-test
  (testing "returns nil so callers can fall back to printing"
    (is (nil? (clipboard/copy! "SECRET" nil)))))

;; The real clipboard is not available everywhere: Linux runners need a display
;; (xvfb-run) and Windows needs an interactive session. This overwrites and
;; restores the clipboard, so it only runs when asked for.

(defn- paste-cmd []
  (some (fn [[exe :as cmd]] (when (fs/which exe) cmd))
        [["pbpaste"]
         ["wl-paste" "--no-newline"]
         ["xclip" "-selection" "clipboard" "-o"]
         ["xsel" "-ob"]
         ["powershell" "-NoProfile" "-Command" "Get-Clipboard -Raw"]]))

(defn- pasted
  "Clipboard contents, without the trailing newline paste commands add."
  [cmd]
  (str/replace (:out (apply p/sh cmd)) #"\r?\n\z" ""))

(deftest ^:clipboard real-clipboard-test
  (when (System/getenv "SPECTRE_CLIPBOARD_TEST")
    (d/deflet
      (def copy (clipboard/tool))
      (def paste (paste-cmd))
      (is (some? copy) "a clipboard tool is available")
      (is (some? paste) "a paste command is available to check with")
      (when (and copy paste)
        (d/deflet
          (def previous (pasted paste))
          (try
            (testing "the value reaches the system clipboard unchanged"
              (clipboard/copy! "spectre-test-value")
              (is (= "spectre-test-value" (pasted paste))))
            (finally
              (clipboard/copy! previous))))))))

(comment
  ;; load clipboard.clj, then this file (C-c C-k in each): the tests call
  ;; whatever copy! the REPL holds, so load clipboard.clj again after each
  ;; change to it, or they run the old one
  (clojure.test/run-tests 'spectre.clipboard-test)

  ;; one test at a time, with the same summary
  (clojure.test/run-test copy-test)
  (clojure.test/run-test no-tool-test)

  ;; real-clipboard-test passes here without asserting anything: it only runs
  ;; when SPECTRE_CLIPBOARD_TEST was set before the REPL started. gmake
  ;; e2-clipboard sets it, overwrites your clipboard, and puts it back
  (clojure.test/run-test real-clipboard-test))
