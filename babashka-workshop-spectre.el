;;; babashka-workshop-spectre.el --- Wiring for the Spectre workshop  -*- lexical-binding: t; -*-

;; Version: 0.1.0
;; Package-Requires: ((emacs "27.1"))
;; Keywords: tools, languages

;;; Commentary:
;; REPL wiring for the Spectre workshop.  Same family as tech-crawler/tc.el
;; and build-a-clojure.el: a babashka nREPL on :1667, CIDER attached to it,
;; paredit in the Clojure buffers.
;;
;;   M-x load-file RET babashka-workshop-spectre.el
;;   Launch from a shell with `-nw -l babashka-workshop-spectre.el', then:
;;   M-x spectre-jack-in    ; CIDER starts its own bb nREPL on a free port;
;;                          ; the one to use when several worktrees are open
;;   M-x spectre-session    ; jack in and load the current buffer
;;   M-x spectre-nrepl      ; `bb dev' on the fixed port, then connect CIDER
;;   M-x spectre-nrepl-jvm  ; `bb dev --jvm', the same code with cider-nrepl
;;   M-x spectre-connect    ; attach to whatever already listens on :1667,
;;                          ; e.g. `bb tui2 --nrepl' running in a terminal
;;   M-x spectre-todos      ; the TODOs left, as links
;;   M-x spectre-screenshare ; keycast in the header line, for recordings
;;   M-x spectre-test-ns    ; `bb test --nses' for the buffer's namespace
;;   M-x spectre-lsp        ; lsp-mode here, unblocking the checkout first
;;
;; `spectre-map' holds all of it; bind it under a prefix, e.g.
;;   (global-set-key (kbd "C-c s") spectre-map)

;;; Code:

(require 'compile)
(require 'flymake)
(require 'seq)
(require 'subr-x)

(defvar cider-repl-display-help-banner)
(defvar org-babel-clojure-backend)
(declare-function cider-connect-clj "cider")
(declare-function cider-mode "cider-mode")
(declare-function keycast-header-line-mode "keycast")
(declare-function package-installed-p "package")
(declare-function package-activate "package")
(declare-function package-load-all-descriptors "package")
(declare-function cider-jack-in-clj "cider")
(declare-function cider-connected-p "cider-connection")
(declare-function cider-load-buffer "cider-eval")
(declare-function clojure-find-ns "clojure-mode")
(declare-function lsp "lsp-mode")
(declare-function lsp-session "lsp-mode")
(declare-function lsp-session-folders-blocklist "lsp-mode")
(declare-function lsp-workspace-blocklist-remove "lsp-mode")
(declare-function lsp-workspace-folders-add "lsp-mode")

(defgroup spectre nil
  "Working babashka-workshop-spectre from Emacs."
  :group 'tools)

(defconst spectre-root
  (file-name-directory (or load-file-name buffer-file-name default-directory))
  "Repo root (the directory this file was loaded from).")

(defun spectre--root ()
  "The Spectre checkout the current buffer is in, else `spectre-root'.
A worktree is its own checkout: with this file loaded once from the main
one, a buffer under worktrees/e3/ still jacks in and runs its tests there,
against that branch's code."
  (let ((checkout (locate-dominating-file default-directory "bb.edn")))
    (if (and checkout (file-directory-p (expand-file-name "src/spectre" checkout)))
        (expand-file-name checkout)
      spectre-root)))

(defcustom spectre-nrepl-port
  (let ((from-environment (string-to-number (or (getenv "NREPL_PORT") ""))))
    (if (> from-environment 0) from-environment 1667))
  "Port for `bb dev' and `bb tui2 --nrepl'.
NREPL_PORT when Emacs was started with it set, as .envrc does per checkout,
else the 1667 both commands default to."
  :type 'integer)

;;;; Editing affordances

;; Not in .dir-locals.el: these are not `safe-local-variable's, and there
;; they would prompt on every file.
(setq cider-repl-display-help-banner nil)

;; CIDER's doc and eval menus (C-c C-d, C-c C-v) are transients, and opening
;; one makes Emacs native-compile a trampoline for `recursive-edit'.  Where
;; libgccjit cannot run its compiler driver that fails, and the menu never
;; appears.  Trampolines are only an optimisation: go without them.
(defvar native-comp-enable-subr-trampolines)
(setq native-comp-enable-subr-trampolines nil)

;; Keep every request and response in *nrepl-messages ...*.  An evaluation
;; from a source buffer leaves nothing in the REPL buffer, so without this
;; the only record of what was evaluated is the `=> value' lines in
;; *Messages*, which have the value and not the form.
(defvar nrepl-log-messages)
(setq nrepl-log-messages t)

;; In a (comment ...) block, evaluate the form at point, not the whole
;; block: that is where the explorations in walkthrough.org are typed.
(defvar clojure-toplevel-inside-comment-form)
(setq clojure-toplevel-inside-comment-form t)
(with-eval-after-load 'org
  (when (require 'ob-clojure nil t)
    (setq org-babel-clojure-backend 'cider)))

(dolist (hook '(clojure-mode-hook cider-repl-mode-hook))
  (when (fboundp 'enable-paredit-mode) (add-hook hook #'enable-paredit-mode))
  (when (fboundp 'rainbow-delimiters-mode) (add-hook hook #'rainbow-delimiters-mode)))

;;;; Screenshare

;; Same arrangement as lean4-workshop's profile, which is what the
;; emacs-recording-tools captures were taken from: keystrokes in the header
;; line, at the top of every window, so the mode line stays free for
;; CIDER's status.  The menu bar stays on, for the CIDER, REPL and Sesman
;; menus.
(menu-bar-mode 1)

(defun spectre--keycast ()
  "Load keycast, non-nil when that worked.
An init file that never activates package.el leaves an installed keycast
off the `load-path': activate it, and what it depends on, from
`package-user-dir' and try again.  Nothing else is activated."
  (or (require 'keycast nil t)
      (and (require 'package nil t)
           (progn (package-load-all-descriptors)
                  (ignore-errors (package-activate 'keycast))
                  (require 'keycast nil t)))))

(defun spectre-screenshare ()
  "Show keystrokes in the header line, at the top of every window.
Offers to install keycast when it is missing."
  (interactive)
  (unless (spectre--keycast)
    (when (y-or-n-p "Keycast is not installed.  Install it from the archives? ")
      (package-refresh-contents)
      (package-install 'keycast)
      (require 'keycast)))
  (if (fboundp 'keycast-header-line-mode)
      (keycast-header-line-mode 1)
    (message "No keycast, so no keystrokes in the header line")))

;; On by default when keycast is there; never installs anything on load.
(when (spectre--keycast)
  (spectre-screenshare))

;;;; nREPL

(defun spectre--listening-p ()
  "Non-nil when something accepts connections on `spectre-nrepl-port'."
  (condition-case nil
      (let ((probe (make-network-process :name "spectre-probe" :host "127.0.0.1"
                                         :service spectre-nrepl-port)))
        (delete-process probe)
        t)
    (error nil)))

(defun spectre--in-root (command buffer-name)
  "Run COMMAND from the repo root in a compilation buffer named BUFFER-NAME."
  (let ((default-directory (spectre--root))
        (compilation-buffer-name-function (lambda (_mode) buffer-name)))
    (compile command)))

(defun spectre--cider-in-clojure-buffers ()
  "Turn on `cider-mode' in every Clojure buffer, open or yet to be opened.
CIDER does this itself once connected, but against babashka's nREPL the
connection handler stops early: it asks for cider/init-debugger, which only
cider-nrepl provides.  Without this, \\[cider-load-buffer] and the rest of
the `cider-mode' keys stay unbound in source buffers."
  (add-hook 'clojure-mode-hook #'cider-mode)
  (dolist (buffer (buffer-list))
    (with-current-buffer buffer
      (when (derived-mode-p 'clojure-mode)
        (cider-mode 1)))))

(defun spectre-connect ()
  "Connect CIDER to the nREPL already listening on `spectre-nrepl-port'."
  (interactive)
  (if (require 'cider nil t)
      (progn
        (spectre--cider-in-clojure-buffers)
        (cider-connect-clj (list :host "127.0.0.1" :port spectre-nrepl-port
                               :project-dir (spectre--root))))
    (message "nREPL on port %d; CIDER is not installed" spectre-nrepl-port)))

(defun spectre-jack-in ()
  "Have CIDER start a babashka nREPL for this checkout and connect to it.
The port is picked by the OS, so a worktree next to the main checkout gets
its own REPL instead of attaching to the other one's :1667."
  (interactive)
  (if (require 'cider nil t)
      (let ((default-directory (spectre--root)))
        (spectre--cider-in-clojure-buffers)
        (cider-jack-in-clj (list :project-dir (spectre--root)
                                 :jack-in-cmd "bb nrepl-server localhost:0")))
    (message "CIDER is not installed")))

(defun spectre-session ()
  "Jack in from the current buffer and load it once the REPL is up.
The whole of \"open core.clj, jack in, \\[cider-load-buffer]\" as one
command, so `gmake session' can start a session nobody has to type into."
  (interactive)
  (let ((buffer (current-buffer))
        (attempts 0)
        timer)
    (spectre-jack-in)
    (setq timer
          (run-with-timer
           1 1
           (lambda ()
             (setq attempts (1+ attempts))
             (cond ((and (fboundp 'cider-connected-p) (cider-connected-p))
                    (cancel-timer timer)
                    (when (buffer-live-p buffer)
                      (with-current-buffer buffer (cider-load-buffer))))
                   ((> attempts 60)
                    (cancel-timer timer)
                    (message "No REPL after a minute; not loading %s" buffer))))))))

(defun spectre--serve (command)
  "Start the nREPL with COMMAND unless one is up, then connect CIDER."
  (unless (spectre--listening-p)
    (spectre--in-root command "*spectre-nrepl*")
    (message "waiting for nREPL on port %d..." spectre-nrepl-port)
    (let ((attempts 0))
      (while (and (not (spectre--listening-p)) (< attempts 120))
        (sleep-for 0.5)
        (setq attempts (1+ attempts)))))
  (if (spectre--listening-p)
      (spectre-connect)
    (message "nREPL did not start; see the *spectre-nrepl* buffer")))

(defun spectre-nrepl ()
  "Start `bb dev' and connect CIDER to it.
Babashka serves the REPL itself, so `babashka.*' namespaces are there and a
change is live the moment you evaluate it."
  (interactive)
  (spectre--serve (format "bb dev --port %d" spectre-nrepl-port)))

(defun spectre-nrepl-jvm ()
  "Start `bb dev --jvm' and connect CIDER to it.
The same code on a JVM Clojure with cider-nrepl: debugger, inspector and the
rest of the middleware work, `babashka.nrepl.server' does not exist."
  (interactive)
  (spectre--serve (format "bb dev --jvm --port %d" spectre-nrepl-port)))

(defun spectre-nrepl-stop ()
  "Stop the nREPL started by `spectre-nrepl' or `spectre-nrepl-jvm'."
  (interactive)
  (let ((buffer (get-buffer "*spectre-nrepl*")))
    (if (and buffer (get-buffer-process buffer))
        (kill-process (get-buffer-process buffer))
      (message "no nREPL started from here"))))

;;;; Language server

(defun spectre--blocking (blocklist root)
  "The entries of BLOCKLIST that are ROOT or a directory above it.
lsp-mode will not start in a file under a blocklisted directory, however
the checkout itself is registered: one stray answer to its \"import
project root?\" prompt at ~ and every project under it goes quiet."
  ;; By name, as lsp-mode compares them: `file-in-directory-p' wants the
  ;; directory to exist.
  (let ((checkout (file-name-as-directory (expand-file-name root))))
    (seq-filter (lambda (entry)
                  (and (not (file-remote-p entry))
                       (string-prefix-p (file-name-as-directory (expand-file-name entry))
                                        checkout)))
                blocklist)))

(defun spectre-lsp ()
  "Start lsp-mode in the current buffer with this checkout as its workspace.
Takes the checkout, and any directory above it, off lsp-mode's blocklist
first, and says which entries it removed.  That edits your lsp session
file, which is shared by every project."
  (interactive)
  (if (require 'lsp-mode nil t)
      (let* ((root (spectre--root))
             (blocking (spectre--blocking
                        (copy-sequence (lsp-session-folders-blocklist (lsp-session)))
                        root)))
        (dolist (entry blocking)
          (lsp-workspace-blocklist-remove entry))
        (lsp-workspace-folders-add (directory-file-name root))
        (when blocking
          (message "Took %s off the lsp blocklist" (string-join blocking ", ")))
        (when buffer-file-name
          (lsp)))
    (message "lsp-mode is not installed")))

;;;; Linting

;; clj-kondo as a Flymake backend, written out here so that it needs no
;; package: Flymake is built in, and the linter is one process reading the
;; buffer on stdin.  It runs from the checkout root, where .clj-kondo is.

(defvar-local spectre--kondo-process nil
  "The clj-kondo process checking this buffer, if one is running.")

(defconst spectre--kondo-line
  "^[^:\n]+:\\([0-9]+\\):\\([0-9]+\\): \\(error\\|warning\\|info\\): \\(.*\\)$"
  "A clj-kondo finding: file, line, column, level, message.")

(defun spectre--kondo-diagnostics (source)
  "Flymake diagnostics for SOURCE from clj-kondo output in the current buffer."
  (let (diagnostics)
    (goto-char (point-min))
    (while (re-search-forward spectre--kondo-line nil t)
      (let* ((line (string-to-number (match-string 1)))
             (column (string-to-number (match-string 2)))
             (level (match-string 3))
             (message (match-string 4))
             (region (flymake-diag-region source line column)))
        (push (flymake-make-diagnostic
               source (car region) (cdr region)
               (pcase level ("error" :error) ("warning" :warning) (_ :note))
               message)
              diagnostics)))
    (nreverse diagnostics)))

(defun spectre-flymake-kondo (report-fn &rest _args)
  "Flymake backend: lint the buffer with clj-kondo and call REPORT-FN."
  (when (process-live-p spectre--kondo-process)
    (kill-process spectre--kondo-process))
  (let ((source (current-buffer))
        (default-directory (spectre--root)))
    (setq spectre--kondo-process
          (make-process
           :name "spectre-kondo" :noquery t :connection-type 'pipe
           :buffer (generate-new-buffer " *spectre-kondo*")
           :command (list "clj-kondo" "--lint" "-" "--filename"
                          (or buffer-file-name "stdin.clj"))
           :sentinel
           (lambda (process _event)
             (when (memq (process-status process) '(exit signal))
               (unwind-protect
                   (when (and (buffer-live-p source)
                              (eq process (buffer-local-value
                                           'spectre--kondo-process source)))
                     (with-current-buffer (process-buffer process)
                       (funcall report-fn (spectre--kondo-diagnostics source))))
                 (kill-buffer (process-buffer process)))))))
    (save-restriction
      (widen)
      (process-send-region spectre--kondo-process (point-min) (point-max))
      (process-send-eof spectre--kondo-process))))

(defun spectre--flymake ()
  "Lint this buffer with clj-kondo when it is a file in a Spectre checkout."
  (when (and buffer-file-name
             (executable-find "clj-kondo")
             (file-in-directory-p buffer-file-name (spectre--root)))
    (add-hook 'flymake-diagnostic-functions #'spectre-flymake-kondo nil t)
    (flymake-mode 1)))

(add-hook 'clojure-mode-hook #'spectre--flymake)

;;;; Tests

(defun spectre--test-ns ()
  "The test namespace for the current buffer, or nil."
  (when-let* ((namespace (and (fboundp 'clojure-find-ns) (clojure-find-ns))))
    (if (string-suffix-p "-test" namespace) namespace (concat namespace "-test"))))

(defun spectre-test ()
  "Run every required exercise's tests."
  (interactive)
  (spectre--in-root "bb test --excludes :optional --excludes :clipboard" "*spectre-test*"))

(defun spectre-test-ns ()
  "Run the tests for the namespace in the current buffer.
In src/spectre-db.clj and in test/spectre-db_test.clj alike, that is
`bb test --nses spectre.db-test'."
  (interactive)
  (if-let* ((namespace (spectre--test-ns)))
      (spectre--in-root (format "bb test --nses %s" namespace) "*spectre-test*")
    (message "no Clojure namespace in this buffer")))

(defun spectre-todos ()
  "List the TODOs left in the exercises; RET on a line jumps to it."
  (interactive)
  (spectre--in-root "bb -cp dev -m workshop.todos" "*spectre-todos*"))

;;;; Files

(defun spectre-exercises ()
  "Open exercises.org."
  (interactive)
  (find-file (expand-file-name "exercises.org" (spectre--root))))

(defvar spectre-map
  (let ((m (make-sparse-keymap)))
    (define-key m (kbd "i") #'spectre-jack-in)
    (define-key m (kbd "s") #'spectre-session)
    (define-key m (kbd "n") #'spectre-nrepl)
    (define-key m (kbd "j") #'spectre-nrepl-jvm)
    (define-key m (kbd "c") #'spectre-connect)
    (define-key m (kbd "q") #'spectre-nrepl-stop)
    (define-key m (kbd "t") #'spectre-test-ns)
    (define-key m (kbd "T") #'spectre-test)
    (define-key m (kbd "o") #'spectre-todos)
    (define-key m (kbd "e") #'spectre-exercises)
    (define-key m (kbd "k") #'spectre-screenshare)
    (define-key m (kbd "l") #'spectre-lsp)
    m)
  "Commands for the workshop, intended to be bound under a prefix.")
(fset 'spectre-map spectre-map)

(provide 'babashka-workshop-spectre)
;;; babashka-workshop-spectre.el ends here
