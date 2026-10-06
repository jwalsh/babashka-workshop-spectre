# Start here

For someone, or some agent, arriving cold. `AGENTS.md` has the rules and the
commands; `README.org` lists every target. This directory holds only what
those do not say.

| file | read it for |
|---|---|
| this one | where things stand, what is open, what was never checked |
| [findings.md](findings.md) | what bit, one entry each, with the fix |
| [conj-26-review.md](conj-26-review.md) | what the finished CLI and TUI do: the target to check an implementation against |

## What this repo is for

A fork of the babashka Spectre workshop, worked REPL first: read a namespace,
load it, explore from the `(comment ...)` block, and only then write tests and
fill in stubs. The password tool is the vehicle. The subject is the method,
and what an agent can read besides files: a shared live REPL, the editor's
own state, logs, hooks, a language server.

## Where things stand (2026-10-06)

- `main` carries the tooling and is the base. `src/` and `test/` differ from
  upstream's `main` in five files: E1's tests in `core_test.clj`, E2's
  `tool` and `copy!` with docstrings and probes in `clipboard.clj`,
  docstrings and probes in `core.clj`, exploration forms in the `comment`
  block of `db.clj`, and REPL runners at the end of `clipboard_test.clj`.
  Nothing is pushed to `origin`. It is a public fork, where a push publishes
  at once: decide between the history as it is and a scrubbed one before
  the first push.
- E1 and E2 are done and merged. `gmake e1` runs 3 tests, 13 assertions;
  `gmake e2` 2 tests, 3 assertions. E2 was finished for the owner, on
  request, in `worktrees/e2`; the `tool` they had begun in the main checkout
  is kept on the branch `e2-attempt`. Its heading in `exercises.org` still
  says TODO: that buffer held unsaved results of the E2 block when E2 was
  merged, so the file was left to the owner. E3 to E5 are stubs, and `gmake
  test` stands at 22 tests, 98 assertions, 25 failing and 2 errors, all of
  them theirs. Deadlines: `gmake agenda`.
- clj-kondo now knows E2's signatures and `fs/which`'s, and `C-c C-d d`
  answers an alias or a namespace with a page of its names (finding 55).
  The wiring change reaches a running Emacs only when it loads the file
  again.
- `exercises.org` carries, per exercise, what it loads, who calls its stubs
  and how to read the docs for the calls it names. E2 and E3 are done in
  detail; E4 to E6 are thinner.
- Worktrees, under `worktrees/` (ignored through `.git/info/exclude`):
  `e1` and `e2`, merged into `main` and level with it; `conj-26`, upstream's
  solutions branch, with none of our tooling. Do not read solution bodies
  out of `conj-26` into an exercise branch.
- Hooks in `.claude/settings.json`: session status on start; delimiter
  repair and cljfmt around every Write and Edit. The clojure-lsp plugin
  under `dev/claude-plugins/` is installed at user scope on this machine.
- `gmake resources` fills `resources/` with the manuals for the versions
  installed here: the Spectre algorithm paper, the babashka book and the
  APIs of the libraries inside `bb`, clojure.org's reference, ClojureDocs'
  examples, the CIDER and nREPL manuals, Emacs's Info files, and the tools'
  docs. 24 entries in `dev/resources.edn`, about 17 MB, ignored by git, one
  shelf for the clone. `resources/INDEX.org` lists what is there; a clone
  has none until the target is run. `README.org` says what to read for
  which exercise, and `gmake guard-resources` is the check before a push.
- A scaffold for the parts of a document that the files can say for
  themselves. `gmake files` and `gmake namespaces` write the file list and
  the load graph as text, EDN, an Org table or Mermaid (`AS=`). `gmake
  blocks` rebuilds every Org dynamic block named `workshop` from them and
  `CHECK=1` reports the ones that are behind. No document has such a block
  yet: it was tried on scratch documents only.
- `gmake claude` puts Claude Code in a tmux session of its own for the
  checkout and attaches; `gmake claude-status` reads it. It starts Claude
  with `--permission-mode auto`, the owner's choice on 2026-10-02.

## Open

- The owner's Emacs has not loaded the wiring again since finding 55's fix,
  so `C-c C-d d` on an alias there still stops at `stringp, nil`.
- Two babashka REPLs run in the main checkout, 64536 and 55869, from a
  second jack-in (finding 56). `.nrepl-port` names the newer. In a batch
  Emacs, `cider-quit` left the port file behind; a plain `kill` of the
  older server deletes it.
  `gmake status` could list every nREPL server whose working directory is
  the checkout, and does not.
- An operator started unattended has no brief here: nothing says what it is
  to do, or to leave alone, when nobody is there. And `-c` continues the
  most recent conversation in the directory, so `gmake claude` run while
  another session is live in the same checkout opens that session's
  conversation a second time. The target only refuses when it is run from
  inside one.
- The generated blocks, in the order they would be done:
  1. `walkthrough.org` opens with a block pasted from `gmake namespaces` and
     annotated by hand. As `#+BEGIN: workshop :view namespaces :as text
     :from "spectre.cli spectre.tui2"` it would be the same eight
     namespaces. It would lose "stub" and "given, E1 adds tests", which no
     file says, and `charm.*` would be spelled out.
  2. `gmake docs` does not look at blocks. A stale one should fail it:
     `workshop.blocks/problems` returns what `CHECK=1` prints.
  3. Emacs cannot rebuild one. `C-c C-x C-u` on a block looks for
     `org-dblock-write:workshop`; the wiring would define it over
     `bb -cp dev -m workshop.blocks --emit`, which also spares an open
     buffer from going stale under a rewrite on disk.
  4. `PROGRESS=1` puts the TODOs left into the graph. In a document that
     makes the block change as the exercises are worked, on branches where
     documents are not meant to change. Undecided; off unless asked for.
  5. A third view, who calls an exercise's stubs, from clj-kondo's var
     usages: the maps under E2 to E4 in `exercises.org` are that, made by
     hand, with notes beside them that no file holds.
  6. Images. `gmake draw` pipes a view through `mmdc` to wherever `OUT`
     says, handing it the puppeteer config it needs to find a browser
     (finding 50). A block is drawn from inside Emacs when its first line
     gives `:file` and `:puppeteer-config-file`; `ob-mermaid` then puts its
     `#+RESULTS:` link inside the block, where `CHECK=1` calls the block
     behind and the next `gmake blocks` takes the link out. So whether
     images are kept, and where, is undecided, and until it is a block
     cannot be both drawn in place and checked. GitHub shows Mermaid in an
     Org file as source.

- `gmake deps-emacs` runs in batch and reports `ok` for things a real
  session does not have. See findings 17 to 19. The check that answers is
  `emacsclient -e` against the running Emacs; no target wraps it yet.
- `gmake session-shot` is `tmux capture-pane -p`, which drops the inverse
  video the TUIs mark a selection with. It needs `-e`.
- `spectre-map` is bound to no key. `M-x spectre-test-ns` works; a prefix
  such as `C-c s` is the owner's to choose.
- `.tags`, `TAGS` and `worktrees/` are ignored through `.git/info/exclude`,
  which a fresh clone does not have: there `gmake tags` leaves untracked
  files. `.gitignore` already carries `.env`, `.bin/` and `/resources`.
- The shelf has no paredit entry: the package ships no manual, only the
  commentary in `paredit.el`.
- `gmake guard-resources` is run by hand. Nothing runs it before a commit or
  a push.
- The Remember plugin keeps its history in `.remember/` in the checkout,
  ignoring itself. The no-state-in-the-repo rule in `AGENTS.md` predates it.
- clj-kondo on PATH is 2024.11.14; current is 2026.08.04. The newer one
  inside clojure-lsp reports a namespace-name mismatch in
  `dev/session-status.bb` that `clj-kondo --lint dev` does not.
- `clj-kondo --lint src` reports two type mismatches on purpose, both
  probes in a `comment` block: the bare `:login` passed to `derive` in
  `core.clj` (finding 52), and a command vector passed to `fs/which` in
  `clipboard.clj`.
- Java on the shell's PATH is 21; the FFI scrypt path needs 22+.
- nREPL messages are logged in memory only (`*nrepl-messages ...*`). Nothing
  durable records what was evaluated.

## Never verified

- Keys in the user's Emacs. `C-c C-d d`, `C-c C-t n` and `M-.` were checked
  by calling the functions under them through `emacsclient`, and the nREPL
  ops under those in a scratch REPL. Nobody pressed them.
- `M-x spectre-lsp`, and the language server starting inside Emacs at all.
- `M-x spectre-nrepl-jvm` and `M-x spectre-connect` from Emacs. The targets
  they wrap were run: `gmake nrepl`, `gmake nrepl-jvm`, and a redefinition
  inside a running `bb tui2 --nrepl` over `brepl -p 1667`.
- Two checkouts jacked in side by side in one Emacs.
- Whether the mode line counts Flymake's findings. The findings themselves
  were seen in the owner's Emacs on 2026-10-01: clj-kondo's type mismatch on
  a bare keyword passed to `derive` showed in the buffer.
- `gmake demo` on anything but the macOS build it was written on.
- `gmake e2-clipboard`, deliberately.
- E6: that a bbin launcher installed into `.bin` is off `PATH` outside the
  checkout, and that `gmake e6` passes anyway. Reasoned from direnv, not run.
- `/plugin marketplace add` typed inside a Claude Code session.
- Links in the `*spectre-todos*` buffer jumping.
- `gmake resources` anywhere but this machine. The Info manuals are found by
  asking Emacs, and the manual pages by `man`, so neither path is assumed;
  no other system has run it. Faults were planted for a missing tag, file,
  host, manual and page, not for a machine that is offline.
- `gmake resources EMACS=...` naming a second build of Emacs. There is one
  build here: the override was run with that build's own binary, and with a
  path that does not exist, which leaves the Info entry `kept`.
- Reading a shelf copy of an Info manual inside Emacs (`C-u C-h i`). The
  files were checked as text.
- `gmake blocks` on a document of this repo: none has a block. Rewriting,
  checking, a hand edit, a moved require and four kinds of unreadable block
  were run on scratch documents.
- `gmake blocks` refusing a file with unsaved edits in a real Emacs. The form
  it asks with was run read-only against the running one and answered
  rightly for a modified, an open and an unopened file; the refusal itself
  was run against a stand-in for `emacsclient`.
- `gmake claude` with the real `claude`, and its attach. It was run against
  a tmux server of its own with a stand-in for `claude` and with attach and
  switch-client recorded, not done: 21 checks. The `STATUS.org` branch of
  `gmake claude-status` was not run, there being no such file.
- The namespace page of `C-c C-d d` in the Emacs someone is sitting in. It
  was run in a batch Emacs with no init file and the owner's CIDER,
  2.0.1-snapshot from the straight build, against a scratch REPL: the page
  for `fs`, `p`, `babashka.fs` and `clojure.string`, a button followed and
  `l` back, the messages for a typo and for an unloaded buffer.
- A generated Mermaid block drawn in the Emacs someone is sitting in. One
  was drawn by `ob-mermaid` in a batch Emacs with no init file, and the
  views by `gmake draw`.
