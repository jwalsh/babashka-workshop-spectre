# The fixed nREPL port. .envrc exports this checkout's own (1667 in the main
# checkout, 1700-1999 in a worktree); 1667 here is only what is left when
# direnv has not loaded it. In a worktree run gmake through `direnv exec .`
# unless your shell did: `gmake status` says when the two disagree.
NREPL_PORT ?= 1667
# tmux session for this checkout: spectre in the main one, spectre-<dir> in a worktree
SESSION    ?= spectre$(if $(wildcard .git/HEAD),,-$(notdir $(CURDIR)))
FILE       ?= src/spectre/core.clj
# Whichever Emacs is first on PATH; EMACS=/path/to/emacs for another build.
# Set with =, not ?=: make predefines EMACS in some environments (inside an
# Emacs shell it is "t").
EMACS      := $(if $(filter-out t,$(EMACS)),$(EMACS),emacs)
# Universal Ctags. macOS's /usr/bin/ctags is a different program with no Clojure parser.
CTAGS      ?= $(firstword $(wildcard /opt/homebrew/opt/universal-ctags/bin/ctags /usr/local/opt/universal-ctags/bin/ctags) ctags)
SHELL := bash

.DEFAULT_GOAL := help

.PHONY: help FORCE demo session session-shot session-stop status tags deps deps-emacs todos agenda namespaces elisp docs test e1 e2 e2-clipboard e3 e4 e5 e5-optional e5-tui e6 seed nrepl nrepl-jvm nrepl-stop

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
# one. Options are in .ctags.d/. Written to .tags, not tags: on a
# case-insensitive filesystem tags is the same file as TAGS, and that name is
# the Emacs table below. Ignored locally. Phony, so it always regenerates.
tags: ## Symbol index with Universal Ctags in .tags: every defn, defn-, def, deftest and ns with its line
	@$(CTAGS) -R -f .tags src test dev && echo "$$(grep -vc '^!' .tags) tags in ./.tags"

# NOT phony: TAGS is a real file target that depends on the Clojure sources.
# It is rebuilt only when one of them is newer than it; otherwise gmake TAGS
# says it is up to date. Emacs's own etags has no Clojure parser, but its
# Lisp one tags every (def... form. Ignored locally. Emacs looks for a file
# named TAGS by itself, or M-x visit-tags-table.
CLOJURE_SOURCES := $(shell find src test dev -name '*.clj' -o -name '*.bb')

TAGS: $(CLOJURE_SOURCES) ## Emacs tags table for the Clojure sources (rebuilt when they change)
	@etags --language=lisp -o $@ $(CLOJURE_SOURCES) && echo "$$(grep -c $$'\x7f' $@) tags in ./$@"

agenda: ## List the TODO headings in the Org files with their deadlines (ALL=1 for done ones too)
	@bb -cp dev -m workshop.agenda $(if $(ALL),--all)

# One Emacs in a detached tmux session, jacked in with FILE loaded. You attach
# to it; an agent reads it with session-shot and evaluates in the same REPL
# through .nrepl-port (brepl -e '(...)'). direnv exec gives it this
# checkout's environment whatever the tmux server was started with.
session: ## Emacs in tmux, jacked in, FILE loaded (default core.clj); attach with tmux attach -t $(SESSION)
	@if tmux has-session -t $(SESSION) 2>/dev/null; then echo "session $(SESSION) already running"; else \
	  tmux new-session -d -s $(SESSION) -x 140 -y 44 -c "$(CURDIR)" \
	    "$$(command -v direnv >/dev/null && echo 'direnv exec .') $(EMACS) -nw -l babashka-workshop-spectre.el $(FILE) -f spectre-session" && \
	  echo "started $(SESSION): tmux attach -t $(SESSION)"; fi

# The same thing in a graphical frame, for sitting in front of: your init
# file, this checkout's environment, detached from the shell that ran it.
# No tmux, so session-shot has nothing to print; brepl and `gmake status`
# still reach its REPL through .nrepl-port. Absolute paths: by the time a
# long init file has run, a bare `-l name.el' is no longer found here.
demo: ## Graphical Emacs, jacked in, FILE loaded (EMACS=/path/to/emacs for another build)
	@if [ -f .nrepl-port ]; then echo "a REPL is already recorded in .nrepl-port; see gmake status"; else \
	  nohup $$(command -v direnv >/dev/null && echo 'direnv exec .') $(EMACS) -l "$(CURDIR)/babashka-workshop-spectre.el" "$(CURDIR)/$(FILE)" -f spectre-session >/dev/null 2>&1 & \
	  echo "started $(EMACS) on $(FILE); gmake status once it has jacked in"; fi

session-shot: ## Print what the session's screen shows right now
	@tmux capture-pane -t $(SESSION) -p

session-stop: ## Stop the session and its REPL
	@tmux kill-session -t $(SESSION) 2>/dev/null && echo "stopped $(SESSION)" || echo "no session $(SESSION)"

status: ## Is the REPL live and this checkout's, is the tmux session up (also the SessionStart hook)
	@bb dev/session-status.bb

# Native-comp trampolines are off: package-lint advises `message', and a
# broken libgccjit should not fail a lint run.
EMACS_BATCH := $(EMACS) --batch --eval '(setq native-comp-enable-subr-trampolines nil)' --eval '(package-initialize)' -L .

elisp: ## Byte-compile, checkdoc and ERT for the Emacs wiring
	@$(EMACS_BATCH) --eval '(setq byte-compile-error-on-warn t)' -f batch-byte-compile babashka-workshop-spectre.el
	@rm -f babashka-workshop-spectre.elc
	@$(EMACS_BATCH) --eval '(checkdoc-file "babashka-workshop-spectre.el")' 2>&1 | grep -B1 -E '^babashka-workshop-spectre.el:' && exit 1 || echo "checkdoc: clean"
	@$(EMACS_BATCH) -l babashka-workshop-spectre-test.el -f ert-run-tests-batch-and-exit

# What a document says and the code does, where that can be compared without
# reading prose. src is on the classpath because the walkthrough's results are
# checked by evaluating them.
docs: ## Check the documents against the code: targets, commands, links, the walkthrough's results
	@bb -cp dev:src -m workshop.docs

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

e5-tui: ## E5 run the TUI with an nREPL on this checkout's NREPL_PORT (needs a real terminal)
	bb tui2 --nrepl --port $(NREPL_PORT)

# E6 has no tests; this checks the launcher from outside the repo.
e6: ## E6 check a pw launcher is on PATH and runs from another directory
	@command -v pw || { echo "pw is not on PATH yet; see E6 in exercises.org"; exit 1; }
	cd / && pw --help

seed: ## Add example sites to db.edn (SPECTRE_DB, else ~/.config/spectre/db.edn); writes nothing before E3
	bb db:seed

nrepl: ## babashka nREPL on this checkout's NREPL_PORT (cider-connect-clj, or M-x spectre-connect)
	@if lsof -i :$(NREPL_PORT) >/dev/null 2>&1; then echo "nREPL already up on $(NREPL_PORT)"; else bb dev --port $(NREPL_PORT); fi

nrepl-jvm: ## Same code on a JVM Clojure with cider-nrepl, same port: apropos, jump to library source, debugger
	@if lsof -i :$(NREPL_PORT) >/dev/null 2>&1; then echo "nREPL already up on $(NREPL_PORT)"; else bb dev --jvm --port $(NREPL_PORT); fi

nrepl-stop: ## Stop the nREPL on this checkout's NREPL_PORT
	@pid=$$(lsof -ti :$(NREPL_PORT) 2>/dev/null); \
	if [ -n "$$pid" ]; then kill $$pid && echo "Stopped nREPL on $(NREPL_PORT)"; else echo "nREPL not running"; fi
