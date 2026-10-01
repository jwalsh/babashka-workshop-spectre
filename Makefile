NREPL_PORT ?= 1667
# tmux session for this checkout: spectre in the main one, spectre-<dir> in a worktree
SESSION    ?= spectre$(if $(wildcard .git/HEAD),,-$(notdir $(CURDIR)))
FILE       ?= src/spectre/core.clj
# Universal Ctags. macOS's /usr/bin/ctags is a different program with no Clojure parser.
CTAGS      ?= $(firstword $(wildcard /opt/homebrew/opt/universal-ctags/bin/ctags /usr/local/opt/universal-ctags/bin/ctags) ctags)
SHELL := bash

.DEFAULT_GOAL := help

.PHONY: help FORCE session session-shot session-stop status tags deps deps-emacs todos agenda namespaces elisp test e1 e2 e2-clipboard e3 e4 e5 e5-optional e5-tui e6 seed nrepl nrepl-jvm nrepl-stop

help: ## Show available targets
	@grep -E '^[a-zA-Z0-9_.-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "%-14s %s\n", $$1, $$2}'

# FORCE makes the recipe run even when .env exists, so it can say so. An
# existing .env is never overwritten.
.env: FORCE ## Create .env from .env.template; warns and leaves an existing one alone
	@if [ -e .env ]; then \
	  echo "warning: .env already exists, leaving it alone" >&2; \
	  if ! diff -q <(grep -oE '^#?[A-Z_]+=' .env.template | tr -d '#' | sort -u) <(grep -oE '^#?[A-Z_]+=' .env | tr -d '#' | sort -u) >/dev/null; then \
	    echo "warning: its variables differ from .env.template; compare with: diff .env.template .env" >&2; \
	  fi; \
	else \
	  cp .env.template .env && echo "created .env from .env.template"; \
	fi

FORCE:

deps: ## Show the workshop dependencies and what is installed
	@bb dev/deps.bb

# Batch Emacs with the installed packages but not your init file. The noise
# CIDER and the REPL write to stderr is dropped; the table is on stdout.
deps-emacs: ## Exercise the Emacs packages: clojure-mode, paredit, CIDER jack-in, lsp, org
	@$(EMACS_BATCH) -l babashka-workshop-spectre.el -l dev/emacs-deps.el -f spectre-deps-report 2>/dev/null

# gmake todos E=e3 narrows to one exercise; SRC=1 skips test/.
todos: ## List the TODOs left in the exercises (E=e3 for one, SRC=1 to skip tests)
	@bb -cp dev -m workshop.todos $(if $(E),--exercise $(E)) $(if $(SRC),--src-only)

namespaces: ## Namespaces in load order with their requires (NS=spectre.cli for one and what it loads)
	@bb -cp dev -m workshop.namespaces $(if $(NS),--from $(NS))

# The symbol index, in place of a code graph: nothing here parses Clojure for
# one. Options are in .ctags.d/. One file, ignored locally: no Emacs-format
# TAGS beside it, because on a case-insensitive filesystem that is the same
# file as tags, and CIDER and the language server do the jumping in Emacs.
tags: ## Symbol index with Universal Ctags: every defn, defn-, def, deftest and ns with its line
	@$(CTAGS) -R -f tags src test dev && echo "$$(grep -vc '^!' tags) tags in ./tags"

agenda: ## List the TODO headings in the Org files with their deadlines (ALL=1 for done ones too)
	@bb -cp dev -m workshop.agenda $(if $(ALL),--all)

# One Emacs in a detached tmux session, jacked in with FILE loaded. You attach
# to it; an agent reads it with session-shot and evaluates in the same REPL
# through .nrepl-port (brepl -e '(...)'). direnv exec gives it this
# checkout's environment whatever the tmux server was started with.
session: ## Emacs in tmux, jacked in, FILE loaded (default core.clj); attach with tmux attach -t $(SESSION)
	@if tmux has-session -t $(SESSION) 2>/dev/null; then echo "session $(SESSION) already running"; else \
	  tmux new-session -d -s $(SESSION) -x 140 -y 44 -c "$(CURDIR)" \
	    "$$(command -v direnv >/dev/null && echo 'direnv exec .') emacs -nw -l babashka-workshop-spectre.el $(FILE) -f spectre-session" && \
	  echo "started $(SESSION): tmux attach -t $(SESSION)"; fi

session-shot: ## Print what the session's screen shows right now
	@tmux capture-pane -t $(SESSION) -p

session-stop: ## Stop the session and its REPL
	@tmux kill-session -t $(SESSION) 2>/dev/null && echo "stopped $(SESSION)" || echo "no session $(SESSION)"

status: ## Is the REPL live and this checkout's, is the tmux session up (also the SessionStart hook)
	@bb dev/session-status.bb

# Native-comp trampolines are off: package-lint advises `message', and a
# broken libgccjit should not fail a lint run.
EMACS_BATCH := emacs --batch --eval '(setq native-comp-enable-subr-trampolines nil)' --eval '(package-initialize)' -L .

elisp: ## Byte-compile, checkdoc and ERT for the Emacs wiring
	@$(EMACS_BATCH) --eval '(setq byte-compile-error-on-warn t)' -f batch-byte-compile babashka-workshop-spectre.el
	@rm -f babashka-workshop-spectre.elc
	@$(EMACS_BATCH) --eval '(checkdoc-file "babashka-workshop-spectre.el")' 2>&1 | grep -B1 -E '^babashka-workshop-spectre.el:' && exit 1 || echo "checkdoc: clean"
	@$(EMACS_BATCH) -l babashka-workshop-spectre-test.el -f ert-run-tests-batch-and-exit

test: ## All required exercises (no :optional, no real clipboard)
	bb test --excludes :optional --excludes :clipboard

e1: ## E1 spectre.core sanity tests
	bb test --nses spectre.core-test

e2: ## E2 clipboard, fake clipboard command only
	bb test --nses spectre.clipboard-test --excludes :clipboard

e2-clipboard: ## E2 including the test that writes your real clipboard
	SPECTRE_CLIPBOARD_TEST=1 bb test --nses spectre.clipboard-test

e3: ## E3 db.edn load/save/merge
	bb test --nses spectre.db-test

e4: ## E4 the pw CLI
	bb test --nses spectre.cli-test

e5: ## E5 charm.clj TUI, required TODOs
	bb test --nses spectre.tui2-test --excludes :optional

e5-optional: ## E5 including the optional figure-test
	bb test --nses spectre.tui2-test

e5-tui: ## E5 run the TUI with an nREPL on NREPL_PORT (1667) (needs a real terminal)
	bb tui2 --nrepl --port $(NREPL_PORT)

# E6 has no tests; this checks the launcher from outside the repo.
e6: ## E6 check a pw launcher is on PATH and runs from another directory
	@command -v pw || { echo "pw is not on PATH yet; see E6 in exercises.org"; exit 1; }
	cd / && pw --help

seed: ## Add example sites to db.edn (SPECTRE_DB or ~/.config/spectre-db.edn)
	bb db:seed

nrepl: ## babashka nREPL on NREPL_PORT (1667) (cider-connect-clj, or M-x spectre-connect)
	@if lsof -i :$(NREPL_PORT) >/dev/null 2>&1; then echo "nREPL already up on $(NREPL_PORT)"; else bb dev --port $(NREPL_PORT); fi

nrepl-jvm: ## Same code on a JVM Clojure with cider-nrepl, on NREPL_PORT (1667)
	@if lsof -i :$(NREPL_PORT) >/dev/null 2>&1; then echo "nREPL already up on $(NREPL_PORT)"; else bb dev --jvm --port $(NREPL_PORT); fi

nrepl-stop: ## Stop the nREPL on NREPL_PORT (1667)
	@pid=$$(lsof -ti :$(NREPL_PORT) 2>/dev/null); \
	if [ -n "$$pid" ]; then kill $$pid && echo "Stopped nREPL on $(NREPL_PORT)"; else echo "nREPL not running"; fi
