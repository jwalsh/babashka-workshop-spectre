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

## Worktrees

Exercises are worked in branches under `worktrees/` (ignored via
`.git/info/exclude`); `main` stays the base. Each checkout derives its own
`SPECTRE_DB`, `NREPL_PORT` and bbin directory in `.envrc`, and needs its own
`gmake .env` and `direnv allow`. The tmux session name is per checkout.
