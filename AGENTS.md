# Working in this repo

A personal checkout of the babashka Spectre workshop. The workshop code is in
`src/` and `test/`; everything else at the root and under `dev/` is local
tooling for working through it.

## Rules

- **The workshop belongs to its authors.** Never propose changes back to the
  originating repo: no PRs, no issues, no "worth upstreaming". Edits here are
  local.
- **Babashka, not Python.** For any scripting in a shell call (editing files
  programmatically, parsing output, one-off checks) use `bb -e` or a `bb`
  script. This is a babashka workshop: `babashka.fs`, `babashka.process`,
  `clojure.string` and `clojure.edn` cover it, and using them is practice.
  Prefer the dedicated edit tools over scripted edits in the first place.
- **`gmake`, not `make`.** `gmake help` lists the targets.
- **Leave `bb.edn` alone.** Dev tooling lives in `dev/`, off the project's
  `:paths`, and uses only what babashka bundles. Run it with `bb -cp dev -m
  workshop.<name>` or through its `gmake` target. E6 is the one exception:
  the exercise itself adds a `:bbin/bin` entry.
- **The exercise stubs are the user's to fill in.** Do not complete a TODO in
  `src/` or `test/` unless asked to. `gmake todos` lists them. Exploration
  forms in a file's `(comment ...)` block are a different thing, and welcome
  when asked for: doc lookups and library calls on throwaway inputs, values
  left for the user to evaluate, never the body of a stub.
- **No allocation state in the repo, ignored or not.** Ports, pids, locks and
  logs that the tooling records belong under `${XDG_STATE_HOME:-~/.local/state}`,
  keyed by checkout path. Today nothing is recorded: the fixed port is
  computed from the directory name each time, and the tmux session is found
  by name. The one port file in the tree, `.nrepl-port`, is babashka's own:
  it writes it on start, removes it on exit, and `brepl` and CIDER look for
  it there. Do not add siblings to it.
- **`resources/` is a local shelf of other people's documents, and is never
  committed.** `gmake resources` fills it with the manuals for the versions
  installed here. It is ignored; `gmake guard-resources` fails when any of it
  is tracked, staged or in a local branch's history. Do not `git add -f`
  from it, and do not copy a document out of it into a tracked file: quote a
  line and say where it is. Name a shelf path in a document as code, never
  as a link, which `gmake docs` refuses: a clone has no shelf. It is
  documents and not allocation state, so the rule above does not send it to
  `XDG_STATE_HOME`; what was fetched and when is recorded beside it, in
  `resources/.index.edn`.
- Conventional commits, co-author as a `--trailer`. Run `gmake elisp` and
  `clj-kondo --lint dev` before committing changes to the tooling, and
  `gmake docs` before committing changes to a document or to anything a
  document names. Run `gmake guard-resources` before a push.

## Evaluate, do not guess

A claim about what the code does should come from running it.

- `gmake session` starts Emacs in a detached tmux session, jacked in to a
  babashka nREPL with `src/spectre/core.clj` loaded. `gmake session-shot`
  prints its screen; `gmake session-stop` ends it. `gmake demo` is the same
  in a graphical frame, which is what the user usually sits in front of:
  there is no pane to print, so ask the editor itself (below).
- `brepl -e '(form)'` evaluates in that same REPL through `.nrepl-port`.
  State is shared with whoever is attached to the session.
- Without a session, `bb -e "(require 'spectre.core) ..."` is enough for a
  one-off.
- `clojure-lsp diagnostics`, `clojure-lsp references --from ns/var` for
  static questions from the shell. With the local plugin installed
  (`dev/claude-plugins/`, see its README) the LSP tool answers the same
  for `.clj`: symbols, references, callers, definitions. It also pushes
  clj-kondo and clojure-lsp diagnostics into your context unasked; the
  unfilled stubs account for nearly all of them.
- Documentation for a library call: the LSP tool's hover gives the docstring
  of `fs/which` from the library's jar, and the Javadoc for interop, with no
  REPL. From the shell, `bb -e "(clojure.repl/doc babashka.fs/which)"`. In
  the user's REPL an alias such as `fs/` resolves only once the namespace
  that declares it has been loaded; a full name resolves at any time.
- The manuals themselves are on the shelf, `resources/`, when `gmake
  resources` has been run in this clone: `resources/INDEX.org` lists what is
  there and for which version. It is the place for what a docstring does not
  say: the options a library documents in its README, what a CIDER command
  is bound to, what the Spectre algorithm specifies. `rg -n
  'name="babashka.fs/which"' resources/babashka/fs/API.md` finds a function;
  read from that line. Name the directory when searching: it is ignored, so
  a search from the repo root, the Grep tool's included, passes over it.
  `README.org` has the table of what to read for which exercise.
- `emacsclient -e '(form)'` asks the running Emacs, when its init file
  starts the server: which modes are on in a buffer, what a key is bound to,
  the tail of `*Messages*`, the `*nrepl-messages ...*` log of what was
  evaluated. Read with it. Do not open a menu or a prompt in the user's
  editor from it: it stays open on their screen after your call returns.
- In a worktree, run through `direnv exec .`. Your shell keeps the
  environment of the checkout Claude Code was started in, wherever you `cd`:
  `SPECTRE_DB` and `NREPL_PORT` are the main checkout's. `gmake status`
  reports the port; nothing reports the database.
- `gmake tags` writes `./.tags` (Universal Ctags): one line per `defn`,
  `defn-`, `def`, `deftest` and `ns`, with file and line. `awk -F'\t'
  '$1=="derive"' .tags` finds a definition. `gmake TAGS` is the Emacs table:
  a real file target, rebuilt only when a source file is newer than it.
  GitNexus does not parse Clojure (its index of this repo holds files only),
  so do not use it here.

A SessionStart hook (`.claude/settings.json`) runs `dev/session-status.bb`
and puts the result in your context: whether `.nrepl-port` points at a live
REPL, whether that REPL is running in *this* checkout, the tmux session name
and whether it is up. `gmake status` runs the same check. Read it before
evaluating anything: a stale or wrong-checkout port means your results are
about some other code.

A second hook, `clj-paren-repair-claude-hook` (from clojure-mcp-light, on
PATH from `~/.local/bin`), runs before and after every Write and Edit and
balances the delimiters of a Clojure file. What lands on disk can therefore
differ from what you wrote: if a form mattered, read it back. It runs with
`--cljfmt`, which reformats the file after each edit: the workshop's sources
conform as shipped, so an edit changes what it changed, and tidies any
whitespace a hand edit left in that file (`cljfmt check src test dev` shows
what it would touch). It runs without `--log-level`, which would write a log
file into the checkout.

Before editing a file under `src/` or `test/`, ask the running Emacs whether
it is open there with unsaved edits (`buffer-modified-p` through
`emacsclient -e`). If it is, your write to disk becomes a conflict prompt on
the user's next save: leave the file alone and say so.

## Surfaces

Files on disk are one surface among several here. Each of the others answers
a question the files cannot, and each has a way to go wrong.

| surface | answers | reach it with | goes wrong when |
|---|---|---|---|
| files | what is written | read, grep | the stub compiles and returns `nil` |
| symbol index | where a name is defined | `.tags`, `TAGS` | `.tags` not regenerated; `TAGS` only rebuilds on a newer source |
| namespace graph | what loads what, in order | `gmake namespaces` | needs `clj-kondo` |
| static analysis | references, callers, diagnostics | the LSP tool; `clojure-lsp references`, `diagnostics` | macros and dynamic calls it cannot see |
| library docs | what a call takes and returns | LSP hover; `clojure.repl/doc` | an alias in a namespace the REPL has not loaded; a hover in the first minute of an LSP server, which can come back empty |
| reference shelf | what the manual says, for the version installed | `resources/INDEX.org`, then `rg` with the directory named | no shelf in this clone until `gmake resources`; a search from the root skips it; a tool upgraded since the fetch (`gmake resources LIST=1` says `stale`) |
| live REPL | what the code does | `brepl -e`, `.nrepl-port` | stale port, or a REPL from another checkout; a second REPL (`bb tui2 --nrepl`) that writes no port file |
| editor screen | what the user is looking at | `gmake session-shot` | no tmux session (`gmake demo` has none); a selection drawn in inverse video does not show |
| running editor | modes, key bindings, `*Messages*`, the nREPL message log | `emacsclient -e` | no server in that Emacs; a menu or prompt you open stays open |
| session status | whether the REPL and the screen can be trusted | SessionStart hook, `gmake status` | read once at start, then things change: rerun it |
| exercise state | what is left and when it is due | `gmake todos`, `gmake agenda` | TODO markers removed without the test passing |
| the documents | whether they still match the code | `gmake docs` | it checks targets, commands, links and the walkthrough's results, not prose |

Prefer the surface that answers by execution over the one that answers by
reading, and say which one a claim came from.

## Finding your way

| want | run |
|---|---|
| what is installed | `gmake deps`, `gmake deps-emacs` |
| namespaces in load order | `gmake namespaces` (`NS=spectre.cli` for one) |
| TODO stubs with their forms | `gmake todos` (`E=e3`, `SRC=1`) |
| exercise deadlines | `gmake agenda` |
| one exercise's tests | `gmake e1` … `gmake e5` |
| all required tests | `gmake test` |
| the manual for an installed tool or library | `resources/INDEX.org`; `gmake resources` fetches, `LIST=1` only reports |
| what an operator in tmux is doing | `gmake claude-status`: it reads and changes nothing. `gmake claude` is the owner's to run, and refuses from inside a session |
| every target | `gmake help`; `README.org` has the same list with more words |
| do the documents still match | `gmake docs` |

`exercises.org` is the exercise text, and under *Working it here* for each
exercise: what it loads, who calls its stubs, where the docs for the calls it
names are, and what its tests report before anything is written.
`walkthrough.org` is the reading order for `core.clj` and what loads what.
`.meta/conj-26-review.md` is what the finished CLI and TUI do.
`.meta/README.md` is where things stand: what is open and what was never
verified. Read it when starting cold.

Findings go in `.meta/findings.md` as you hit them, one numbered entry each:
what bit and what was done. Keep `.meta/README.md` current instead of adding
a file per session. This repo has no git notes, so do not start keeping them
there; one place is enough.

## Worktrees

Exercises are worked in branches under `worktrees/` (ignored via
`.git/info/exclude`, which is this clone's and not in the repo); `main` stays
the base. Each checkout derives its own `SPECTRE_DB`, `NREPL_PORT` and bbin
directory in `.envrc`, and needs its own `gmake .env` and `direnv allow`. The
tmux session name is per checkout. Tooling and documents change on `main`;
an exercise branch is fast-forwarded to it and holds only that exercise.
