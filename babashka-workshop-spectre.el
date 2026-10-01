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
;;   M-x spectre-nrepl      ; `bb dev' on the fixed port, then connect CIDER
;;   M-x spectre-nrepl-jvm  ; `bb dev --jvm', the same code with cider-nrepl
;;   M-x spectre-connect    ; attach to whatever already listens on :1667,
;;                          ; e.g. `bb tui2 --nrepl' running in a terminal
;;   M-x spectre-todos      ; the TODOs left, as links
;;   M-x spectre-screenshare ; keycast in the header line, for recordings
;;   M-x spectre-test-ns    ; `bb test --nses' for the buffer's namespace
;;
;; `spectre-map' holds all of it; bind it under a prefix, e.g.
;;   (global-set-key (kbd "C-c s") spectre-map)

;;; Code:

(require 'compile)
(require 'subr-x)

(defvar cider-repl-display-help-banner)
(defvar org-babel-clojure-backend)
(declare-function cider-connect-clj "cider")
(declare-function cider-mode "cider-mode")
(declare-function keycast-header-line-mode "keycast")
(declare-function package-installed-p "package")
(declare-function cider-jack-in-clj "cider")
(declare-function clojure-find-ns "clojure-mode")

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

(defcustom spectre-nrepl-port 1667
  "Port for `bb dev' and `bb tui2 --nrepl'.  Both default to 1667."
  :type 'integer)

;;;; Editing affordances

;; Not in .dir-locals.el: these are not `safe-local-variable's, and there
;; they would prompt on every file.
(setq cider-repl-display-help-banner nil)
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

(defun spectre-screenshare ()
  "Show keystrokes in the header line, at the top of every window.
Offers to install keycast when it is missing."
  (interactive)
  (unless (require 'keycast nil t)
    (when (y-or-n-p "Keycast is not installed.  Install it from the archives? ")
      (package-refresh-contents)
      (package-install 'keycast)
      (require 'keycast)))
  (if (fboundp 'keycast-header-line-mode)
      (keycast-header-line-mode 1)
    (message "No keycast, so no keystrokes in the header line")))

;; On by default when keycast is there; never installs anything on load.
(when (require 'keycast nil t)
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
    (define-key m (kbd "n") #'spectre-nrepl)
    (define-key m (kbd "j") #'spectre-nrepl-jvm)
    (define-key m (kbd "c") #'spectre-connect)
    (define-key m (kbd "q") #'spectre-nrepl-stop)
    (define-key m (kbd "t") #'spectre-test-ns)
    (define-key m (kbd "T") #'spectre-test)
    (define-key m (kbd "o") #'spectre-todos)
    (define-key m (kbd "e") #'spectre-exercises)
    (define-key m (kbd "k") #'spectre-screenshare)
    m)
  "Commands for the workshop, intended to be bound under a prefix.")
(fset 'spectre-map spectre-map)

(provide 'babashka-workshop-spectre)
;;; babashka-workshop-spectre.el ends here
