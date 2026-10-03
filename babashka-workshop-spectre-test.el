;;; babashka-workshop-spectre-test.el --- Tests for the workshop wiring  -*- lexical-binding: t; -*-

;;; Commentary:
;; $ gmake elisp
;; Nothing here starts a REPL: only the parts that can be checked offline.

;;; Code:

(require 'ert)
(require 'babashka-workshop-spectre)

(defmacro spectre-test--with-clojure (source &rest body)
  "Run BODY in a `clojure-mode' buffer holding SOURCE."
  (declare (indent 1))
  `(with-temp-buffer
     (insert ,source)
     (delay-mode-hooks (clojure-mode))
     ,@body))

(ert-deftest spectre-test-root-is-the-repo ()
  (should (file-exists-p (expand-file-name "bb.edn" spectre-root)))
  (should (file-exists-p (expand-file-name "exercises.org" spectre-root))))

(ert-deftest spectre-test-root-follows-the-buffer ()
  "A buffer in another checkout, a worktree say, resolves to that checkout."
  (let* ((worktree (file-name-as-directory (make-temp-file "spectre-worktree" t)))
         (source-dir (expand-file-name "src/spectre/" worktree)))
    (unwind-protect
        (progn
          (make-directory source-dir t)
          (write-region "{}" nil (expand-file-name "bb.edn" worktree))
          (let ((default-directory source-dir))
            (should (file-equal-p worktree (spectre--root))))
          (let ((default-directory temporary-file-directory))
            (should (file-equal-p spectre-root (spectre--root)))))
      (delete-directory worktree t))))

(ert-deftest spectre-test-ns-from-source-buffer ()
  (skip-unless (require 'clojure-mode nil t))
  (spectre-test--with-clojure "(ns spectre.db\n  (:require [babashka.fs :as fs]))\n"
    (should (equal "spectre.db-test" (spectre--test-ns)))))

(ert-deftest spectre-test-ns-from-test-buffer ()
  (skip-unless (require 'clojure-mode nil t))
  (spectre-test--with-clojure "(ns spectre.db-test\n  (:require [clojure.test :refer [deftest]]))\n"
    (should (equal "spectre.db-test" (spectre--test-ns)))))

(ert-deftest spectre-test-ns-without-a-namespace ()
  (with-temp-buffer
    (should-not (spectre--test-ns))))

(ert-deftest spectre-test-nothing-listening ()
  "A port nothing is bound to reads as not listening, without signalling."
  (let ((spectre-nrepl-port 1))
    (should-not (spectre--listening-p))))

(ert-deftest spectre-test-map-binds-commands ()
  (let ((bound 0))
    (map-keymap (lambda (_key command)
                  (setq bound (1+ bound))
                  (should (commandp command)))
                spectre-map)
    (should (= 12 bound))))

(ert-deftest spectre-test-blocking-finds-ancestors ()
  "The checkout and the directories above it block; siblings and remotes do not."
  (let ((root "/home/someone/src/spectre/"))
    (should (equal '("/home/someone/" "/home/someone/src/spectre")
                   (spectre--blocking '("/home/someone/"
                                        "/home/someone/src/other"
                                        "/ssh:host:/home/someone/"
                                        "/home/someone/src/spectre")
                                      root)))
    (should-not (spectre--blocking nil root))))

(ert-deftest spectre-test-kondo-reports-a-finding ()
  "The Flymake backend turns a clj-kondo warning into a diagnostic on its line."
  (skip-unless (executable-find "clj-kondo"))
  (with-temp-buffer
    ;; a buffer with no file is linted as stdin.clj, so the ns has to agree
    (insert "(ns stdin)\n\n(defn add [left right]\n  (let [unused 1]\n    (+ left right)))\n")
    (let ((reported 'pending)
          (waited 0))
      (spectre-flymake-kondo (lambda (diagnostics &rest _) (setq reported diagnostics)))
      (while (and (eq reported 'pending) (< waited 100))
        (accept-process-output nil 0.1)
        (setq waited (1+ waited)))
      (should (= 1 (length reported)))
      (should (eq :warning (flymake-diagnostic-type (car reported))))
      (should (string-match-p "unused" (flymake-diagnostic-text (car reported))))
      (should (= 4 (line-number-at-pos (flymake-diagnostic-beg (car reported))))))))

(ert-deftest spectre-test-info-without-a-name-is-none ()
  "What babashka answers for an alias, a namespace or a typo counts as nothing."
  (skip-unless (require 'nrepl-dict nil t))
  (should-not (spectre--named-info nil))
  (should-not (spectre--named-info '(dict "id" "21" "status" ("done"))))
  (should (spectre--named-info '(dict "ns" "babashka.fs" "name" "which")))
  (should (spectre--named-info '(dict "class" "java.lang.String"))))

(ert-deftest spectre-test-namespace-page ()
  "A namespace page writes names as the buffer that asked types them."
  (with-temp-buffer
    (spectre--insert-namespace "babashka.fs"
                               '(("exists?" "[path]") ("which" "[program] [program opts]"))
                               "fs" "spectre.clipboard")
    (goto-char (point-min))
    (should (looking-at-p "babashka.fs\n  fs/ in spectre.clipboard\n  2 public names"))
    (should (search-forward "fs/which    [program] [program opts]" nil t))
    (let ((looked-up nil))
      (cl-letf (((symbol-function 'cider-doc-lookup)
                 (lambda (symbol) (setq looked-up symbol))))
        (push-button (- (point) (length "fs/which    [program] [program opts]"))))
      (should (equal "babashka.fs/which" looked-up))))
  (with-temp-buffer
    (spectre--insert-namespace "babashka.fs" '(("which" "[program]")))
    (should (string-match-p "^which  \\[program\\]$" (buffer-string)))
    (should-not (string-match-p "fs/" (buffer-string)))))

(ert-deftest spectre-test-namespace-form ()
  "The Clojure that describes a namespace runs in babashka and reads in Emacs."
  (skip-unless (executable-find "bb"))
  (let ((values (mapcar (lambda (asked)
                          (let ((code (format spectre--namespace-form asked "user")))
                            (car (read-from-string
                                  (car (process-lines "bb" "-e" (concat "(require '[babashka.fs :as fs]) (prn " code ")")))))))
                        '("fs" "babashka.fs" "sf"))))
    (should (equal "babashka.fs" (aref (nth 0 values) 0)))
    (should (equal (nth 0 values) (nth 1 values)))
    (should (seq-find (lambda (entry) (equal ["which" "[program] [program opts]"] entry))
                      (aref (nth 0 values) 1)))
    (should-not (nth 2 values))))

(ert-deftest spectre-test-no-unsafe-dir-locals ()
  "Opening a file must not prompt: every dir-local is a safe one."
  (let ((dir-locals (with-temp-buffer
                      (insert-file-contents (expand-file-name ".dir-locals.el" spectre-root))
                      (read (current-buffer)))))
    (require 'cider nil t)
    (dolist (mode-entry dir-locals)
      (dolist (binding (cdr mode-entry))
        (should (safe-local-variable-p (car binding) (cdr binding)))))))

(provide 'babashka-workshop-spectre-test)
;;; babashka-workshop-spectre-test.el ends here
