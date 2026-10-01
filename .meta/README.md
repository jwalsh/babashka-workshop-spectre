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

## Where things stand (2026-10-01)

- `main` carries the tooling and is the base. `src/` and `test/` match
  upstream's `main` except for exploration forms in the `comment` blocks of
  `core.clj`, `clipboard.clj` and `db.clj`. Nothing is pushed; `origin` is a
  public fork, so decide private or rewritten history before the first push.
- No exercise is implemented. 30 TODO markers (`gmake todos`). E1's one
  existing test passes; E2 to E5 fail as designed, and `exercises.org` has
  the count for each target. Deadlines: `gmake agenda`.
- `exercises.org` carries, per exercise, what it loads, who calls its stubs
  and how to read the docs for the calls it names. E2 and E3 are done in
  detail; E4 to E6 are thinner.
- Worktrees, under `worktrees/` (ignored through `.git/info/exclude`):
  `e1`, level with `main`, for E1's test additions; `conj-26`, upstream's
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

## Open

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
- `exercises.org` does not mention the shelf. Its *Documentation for a call*
  table is where the pointer belongs, and the file was open in Emacs when
  the shelf was added, so it was left alone. `README.org` has the table of
  what to read for which exercise meanwhile.
- The shelf has no paredit entry: the package ships no manual, only the
  commentary in `paredit.el`.
- `gmake guard-resources` is run by hand. Nothing runs it before a commit or
  a push.
- The Remember plugin keeps its history in `.remember/` in the checkout,
  ignoring itself. The no-state-in-the-repo rule in `AGENTS.md` predates it.
- clj-kondo on PATH is 2024.11.14; current is 2026.08.04. The newer one
  inside clojure-lsp reports a namespace-name mismatch in
  `dev/session-status.bb` that `clj-kondo --lint dev` does not.
- `cljfmt check src test dev` fails on one whitespace-only line in
  `core.clj`'s `comment` block. Left: the file was open in Emacs with an
  unsaved edit. The edit hook removes it the next time the file is edited.
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
- Flymake underlines in a file that has warnings (only a clean file was
  checked live), and whether the mode line counts them.
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
