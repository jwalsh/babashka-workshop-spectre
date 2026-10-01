;;; emacs-deps.el --- Check the Emacs side of the workshop setup  -*- lexical-binding: t; -*-

;;; Commentary:
;; The Emacs half of dev/deps.bb: `gmake deps-emacs'.
;;
;; Run in batch with the packages from `package-user-dir' and the repo's
;; babashka-workshop-spectre.el loaded.  The user's init file is NOT loaded,
;; so this says what is installed and whether it works, not what a running
;; session has switched on.
;;
;; Each check does something with the package instead of only finding it:
;; clojure-mode reads a namespace, paredit slurps, CIDER jacks in to a real
;; babashka nREPL and evaluates.  The exception is the language server, which
;; is only checked for a client, a registered server and a binary that runs.
;;
;; Exits 1 when something the wiring needs (clojure-mode, CIDER) fails.

;;; Code:

(require 'cl-lib)
(require 'subr-x)

(defvar cider-repl-pop-to-buffer-on-connect)
(defvar cider-version)
(defvar eglot-server-programs)
(defvar org-confirm-babel-evaluate)
(defvar spectre-root)
(declare-function cider-connected-p "cider-connection")
(declare-function cider-nrepl-sync-request:eval "cider-client")
(declare-function cider-quit "cider-connection")
(declare-function clojure-find-ns "clojure-mode")
(declare-function nrepl-dict-get "nrepl-dict")
(declare-function org-babel-execute-src-block "ob-core")
(declare-function org-version "org-version")
(declare-function paredit-forward-slurp-sexp "paredit")
(declare-function spectre-jack-in "babashka-workshop-spectre")

(defvar spectre-deps--rows nil
  "Rows collected so far, newest first: (STATUS TOOL FOR FOUND).")

(defun spectre-deps--version (library)
  "The installed version of the package LIBRARY, a symbol, or nil."
  (when-let* ((description (cadr (assq library (bound-and-true-p package-alist)))))
    (package-version-join (package-desc-version description))))

(defmacro spectre-deps--check (tool purpose required &rest body)
  "Record a row for TOOL, used for PURPOSE.
BODY returns a string describing what worked, or signals.  REQUIRED is
non-nil when a failure should fail the whole run."
  (declare (indent 3))
  `(push (condition-case failure
             (list "ok" ,tool ,purpose (progn ,@body))
           (error (list (if ,required "FAILED" "-") ,tool ,purpose
                        (error-message-string failure))))
         spectre-deps--rows))

(defun spectre-deps--need (library)
  "Load LIBRARY or signal that it is not installed."
  (unless (require library nil t)
    (error "Not installed")))

;;;; The checks

(defun spectre-deps--clojure-mode ()
  "Read a namespace out of a Clojure buffer."
  (spectre-deps--need 'clojure-mode)
  (with-temp-buffer
    (insert "(ns spectre.db\n  (:require [babashka.fs :as fs]))\n")
    (delay-mode-hooks (clojure-mode))
    (unless (equal "spectre.db" (clojure-find-ns))
      (error "Loaded, but did not find the ns form"))
    (format "%s  reads the ns form" (spectre-deps--version 'clojure-mode))))

(defun spectre-deps--paredit ()
  "Slurp a form forward."
  (spectre-deps--need 'paredit)
  (with-temp-buffer
    (insert "(fs/exists?) path")
    (goto-char 2)
    (paredit-forward-slurp-sexp)
    (unless (equal "(fs/exists? path)" (buffer-string))
      (error "Loaded, but slurp gave %S" (buffer-string)))
    (format "%s  slurps" (spectre-deps--version 'paredit))))

(defun spectre-deps--cider ()
  "Jack in to babashka the way `spectre-jack-in' does, evaluate, load a buffer."
  (spectre-deps--need 'cider)
  (setq cider-repl-pop-to-buffer-on-connect nil)
  (let* ((source (find-file-noselect (expand-file-name "src/spectre/core.clj" spectre-root)))
         ;; babashka writes .nrepl-port; put back whatever a live session of
         ;; yours had there, so brepl and friends still find your REPL
         (port-file (expand-file-name ".nrepl-port" spectre-root))
         (port-before (when (file-exists-p port-file)
                        (with-temp-buffer (insert-file-contents port-file) (buffer-string)))))
    (unwind-protect
        (spectre-deps--cider-in source)
      (if port-before
          (write-region port-before nil port-file nil 'quiet)
        (delete-file port-file)))))

(defun spectre-deps--cider-in (source)
  "Jack in from the buffer SOURCE, evaluate, and quit."
  (progn
    (with-current-buffer source
      (spectre-jack-in)
      (let ((waited 0))
        (while (and (not (cider-connected-p)) (< waited 60))
          (accept-process-output nil 0.5)
          (setq waited (1+ waited))))
      (unless (cider-connected-p)
        (error "Installed, but jack-in to babashka did not connect"))
      (unless (eq 'cider-load-buffer (key-binding (kbd "C-c C-k")))
        (error "Connected, but C-c C-k is not bound in the source buffer"))
      (let* ((form "(do (require 'babashka.fs) (str (System/getProperty \"babashka.version\") \" \" (babashka.fs/exists? \"bb.edn\")))")
             (value (nrepl-dict-get (cider-nrepl-sync-request:eval form) "value")))
        (unless (and value (string-suffix-p "true\"" value))
          (error "Connected, but evaluating babashka.fs gave %S" value))
        (prog1 (format "%s  jacks in, evals babashka.fs on bb %s"
                       cider-version
                       (car (split-string (string-trim value "\"" "\""))))
          (ignore-errors (let ((kill-buffer-query-functions nil)) (cider-quit))))))))

(defun spectre-deps--language-server ()
  "Find a client, a server registered for Clojure, and a binary that runs."
  (let ((binary (or (executable-find "clojure-lsp") (error "No clojure-lsp on PATH")))
        (client (cond ((require 'lsp-clojure nil t) "lsp-mode")
                      ((and (require 'eglot nil t)
                            (cl-some (lambda (entry)
                                       (memq 'clojure-mode (ensure-list (car entry))))
                                     eglot-server-programs))
                       "eglot")
                      (t (error "No lsp-mode or eglot client for Clojure")))))
    (format "%s + %s  (not started here)"
            client
            (car (process-lines binary "--version")))))

(defun spectre-deps--org ()
  "Run a shell block the way exercises.org does."
  (require 'org)
  (require 'ob-shell)
  (with-temp-buffer
    (org-mode)
    (insert "#+begin_src sh :results output\necho block-ran\n#+end_src\n")
    (goto-char (point-min))
    (let ((org-confirm-babel-evaluate nil))
      (unless (equal "block-ran" (string-trim (org-babel-execute-src-block)))
        (error "The sh block did not run")))
    (format "%s  runs sh blocks%s" (org-version)
            (if (require 'ob-clojure nil t) ", has ob-clojure" ""))))

(defun spectre-deps--keycast ()
  "Check keycast can show keys in the header line."
  (spectre-deps--need 'keycast)
  (unless (fboundp 'keycast-header-line-mode)
    (error "Installed, but too old for the header line"))
  (format "%s  header line" (spectre-deps--version 'keycast)))

(defun spectre-deps--rainbow-delimiters ()
  "Check rainbow-delimiters loads."
  (spectre-deps--need 'rainbow-delimiters)
  (spectre-deps--version 'rainbow-delimiters))

;;;; Entry point

(defun spectre-deps-report ()
  "Run every check, print the table, exit 1 when a required one failed."
  (spectre-deps--check "emacs" "everything" t
    (if (version< emacs-version "27.1") (error "%s is older than 27.1" emacs-version) emacs-version))
  (spectre-deps--check "clojure-mode" "editing" t (spectre-deps--clojure-mode))
  (spectre-deps--check "paredit" "editing" nil (spectre-deps--paredit))
  (spectre-deps--check "rainbow-delims" "editing" nil (spectre-deps--rainbow-delimiters))
  (spectre-deps--check "cider" "the REPL" t (spectre-deps--cider))
  (spectre-deps--check "lsp" "navigation" nil (spectre-deps--language-server))
  (spectre-deps--check "org" "exercises.org" nil (spectre-deps--org))
  (spectre-deps--check "keycast" "screenshare" nil (spectre-deps--keycast))
  (let ((rows (reverse spectre-deps--rows)))
    (princ (format "%-8s %-14s %-14s %s\n" "" "PACKAGE" "FOR" "FOUND"))
    (dolist (row rows)
      (princ (apply #'format "%-8s %-14s %-14s %s\n" row)))
    (when-let* ((failed (seq-filter (lambda (row) (equal "FAILED" (car row))) rows)))
      (princ (format "\nRequired and not usable: %s\n"
                     (mapconcat #'cadr failed ", ")))
      (kill-emacs 1))))

(provide 'emacs-deps)
;;; emacs-deps.el ends here
