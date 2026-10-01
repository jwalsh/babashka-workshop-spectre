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
  workshop.<name>` or through its `gmake` target.
- **The exercise stubs are the user's to fill in.** Do not complete a TODO in
  `src/` or `test/` unless asked to. `gmake todos` lists them.
- **No allocation state in the repo, ignored or not.** Ports, pids, locks and
  logs that the tooling records belong under `${XDG_STATE_HOME:-~/.local/state}`,
  keyed by checkout path. Today nothing is recorded: the fixed port is
  computed from the directory name each time, and the tmux session is found
  by name. The one port file in the tree, `.nrepl-port`, is babashka's own:
  it writes it on start, removes it on exit, and `brepl` and CIDER look for
  it there. Do not add siblings to it.
- Conventional commits, co-author as a `--trailer`. Run `gmake elisp` and
  `clj-kondo --lint dev` before committing changes to the tooling.

## Evaluate, do not guess

A claim about what the code does should come from running it.

- `gmake session` starts Emacs in a detached tmux session, jacked in to a
  babashka nREPL with `src/spectre/core.clj` loaded. `gmake session-shot`
  prints its screen; `gmake session-stop` ends it.
- `brepl -e '(form)'` evaluates in that same REPL through `.nrepl-port`.
  State is shared with whoever is attached to the session.
- Without a session, `bb -e "(require 'spectre.core) ..."` is enough for a
  one-off.
- `clojure-lsp diagnostics`, `clojure-lsp references --from ns/var` for
  static questions. There is no Clojure LSP plugin or MCP server configured.
- `gmake tags` writes `./.tags` (Universal Ctags): one line per `defn`,
  `defn-`, `def`, `deftest` and `ns`, with file and line. `awk -F'\t'
  '$1=="derive"' .tags` finds a definition. `gmake TAGS` is the Emacs table:
  a real file target, rebuilt only when a source file is newer than it. GitNexus does not parse Clojure
  (its index of this repo holds files only), so do not use it here.

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
already conform (`cljfmt check src test dev` is clean), so an edit changes
only what it changed. It runs without `--log-level`, which would write a log
file into the checkout.

## Surfaces

Files on disk are one surface among several here. Each of the others answers
a question the files cannot, and each has a way to go wrong.

| surface | answers | reach it with | goes wrong when |
|---|---|---|---|
| files | what is written | read, grep | the stub compiles and returns `nil` |
| symbol index | where a name is defined | `.tags`, `TAGS` | `.tags` not regenerated; `TAGS` only rebuilds on a newer source |
| namespace graph | what loads what, in order | `gmake namespaces` | needs `clj-kondo` |
| static analysis | references, diagnostics | `clojure-lsp references`, `diagnostics` | macros and dynamic calls it cannot see |
| live REPL | what the code does | `brepl -e`, `.nrepl-port` | stale port, or a REPL from another checkout |
| editor screen | what the owner is looking at | `gmake session-shot` | no session running |
| session status | whether the two above can be trusted | SessionStart hook, `gmake status` | read once at start, then things change: rerun it |
| exercise state | what is left and when it is due | `gmake todos`, `gmake agenda` | TODO markers removed without the test passing |

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

`exercises.org` is the exercise text; `walkthrough.org` is the reading order
for `core.clj` and what loads what.
`.meta/experience-report.md` is the report from the setup session: findings,
what was never verified, and what is open. Read it when starting cold.

Findings go in `.meta/` as you hit them: experiments, deviations from the
workshop, testing lessons, tooling and DX notes. This repo has no git notes,
so do not start keeping them there; one place is enough.

## Worktrees

Exercises are worked in branches under `worktrees/` (ignored via
`.git/info/exclude`); `main` stays the base. Each checkout derives its own
`SPECTRE_DB`, `NREPL_PORT` and bbin directory in `.envrc`, and needs its own
`gmake .env` and `direnv allow`. The tmux session name is per checkout.
