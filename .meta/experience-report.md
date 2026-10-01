# Experience report: workshop setup, 2026-09-30

For the next agent starting cold in this repo. `AGENTS.md` has the rules;
this is what was built, why, what bit, and what is still open.

## Goal

Work through the babashka Spectre workshop (E1 to E6, deadlines in
`exercises.org`, most due 2026-10-01) from Emacs, REPL first. The owner reads
a namespace, loads it (`C-c C-k` or per form), explores from the `(comment
...)` block, then fills in the TODO stubs. Individual exercises are to be
ground out in branches under `worktrees/`, possibly by subagents, while
`main` stays the base carrying the tooling.

A second goal behind the first: give agents more surface than files on disk.
A live REPL and a visible editor session that the owner and an agent share.

## State at the end of the session

- Branch `main`, 16 commits ahead of `origin/main`, tree clean, **nothing
  pushed**. `origin` is the owner's fork; `upstream` is the workshop.
- No exercise is done. E1's existing test passes; E2 to E5 fail as designed.
  30 TODO markers remain (`gmake todos`).
- No worktrees exist yet. `worktrees/` is ignored through
  `.git/info/exclude`, not `.gitignore`.
- A tmux session named `spectre` may still be running with a babashka nREPL
  (`gmake session-stop`). `.nrepl-port` belongs to it.
- `.env` exists locally (ignored), an older copy of the template, so `gmake
  .env` prints a drift warning. direnv is allowed for the main checkout.
- keycast was installed into `~/.emacs.d/elpa` during this session.
  clojure-lsp was upgraded by the owner to 2026.07.06.
- The owner removed and re-requested the Xcode Command Line Tools; the
  install may not have finished. Homebrew `git` and `gmake` are unaffected;
  `cc` and anything building from source are.

## What was built

| piece | where | entry point |
|---|---|---|
| Exercises as runnable Org | `exercises.org` | `C-c C-c` on a block |
| Reading order for `core.clj` | `walkthrough.org` | read it |
| Per-exercise test targets | `Makefile` | `gmake e1` … `e6`, `test` |
| TODO stubs with enclosing form | `dev/workshop/todos.clj` | `gmake todos` |
| Org TODO headings + deadlines | `dev/workshop/agenda.clj` | `gmake agenda` |
| Namespace load order | `dev/workshop/namespaces.clj` | `gmake namespaces` |
| System dependency check | `dev/deps.bb` | `gmake deps` |
| Emacs package check | `dev/emacs-deps.el` | `gmake deps-emacs` |
| CIDER wiring | `babashka-workshop-spectre.el` | `M-x spectre-jack-in` etc. |
| Elisp checks (compile, checkdoc, ERT) | `babashka-workshop-spectre-test.el` | `gmake elisp` |
| Shared editor + REPL session | `Makefile` | `gmake session`, `session-shot` |
| Per-checkout environment | `.envrc`, `.env.template` | `gmake .env`, `direnv allow` |

Design choices worth keeping:

- `bb.edn` and the workshop's `src/` are untouched apart from the exercises
  themselves. Dev tools sit in `dev/`, off `:paths`, run with `bb -cp dev -m
  workshop.<name>`, and use only bundled babashka libraries (plus the
  `clj-kondo` binary for `namespaces`).
- Every tool prints `file:line:` so its output is clickable in an Emacs
  compilation buffer.
- Elisp commands use a `spectre-` prefix (file is `<repo>.el`, so
  package-lint's prefix-must-match-filename complaint is knowingly ignored).

## Findings: things that bit

1. **CIDER against babashka's nREPL does not enable `cider-mode` in source
   buffers.** The connection handler requests `cider/init-debugger`, which
   only cider-nrepl provides, errors, and stops before turning the mode on.
   Symptom: `C-c C-k is undefined` after a successful jack-in. The wiring
   enables `cider-mode` itself. The error still prints once per connect and
   is harmless. Use `bb dev --jvm` (`M-x spectre-nrepl-jvm`) for the
   debugger and inspector.
2. **A fixed nREPL port silently attaches a worktree to the wrong REPL.**
   `bb dev` and `bb tui2 --nrepl` default to 1667. "Is something listening"
   is not "is it mine". Jack-in uses an OS-assigned port per checkout;
   `.envrc` derives a distinct `NREPL_PORT` for worktrees for the fixed-port
   commands.
3. **Commands must resolve the checkout from the current buffer**, not from
   where the elisp was loaded. One Emacs with buffers in several worktrees
   otherwise runs every test and jack-in in the first checkout.
4. **`.dir-locals.el` may only hold `safe-local-variable`s**, or Emacs
   prompts on every file. An ERT test enforces this. Everything else is set
   in the `.el`.
5. **Do not override `XDG_CONFIG_HOME` per checkout.** The workshop reads it
   only for the default `db.edn`, but git, gh and clojure read it too.
   `SPECTRE_DB` isolates the database instead.
6. **direnv's dotenv rejects an unquoted value with a space.** Quote it.
   `.env` cannot compute values; derived ones go in `.envrc`.
7. **Org Babel and failing commands:** wrap blocks as `{ ...; } 2>&1 | sed`
   so stderr lands in the result, a non-zero exit still returns output, and
   babashka's exception dump after the test summary is cut.
8. **Native compilation is broken on this machine** (`libgccjit`, missing
   `emutls_w`). package-lint dies advising `message` unless
   `native-comp-enable-subr-trampolines` is nil; the Makefile sets it.
9. **`source .envrc` without `./` searches `PATH` first** in bash and loaded
   an unrelated file. Use `source ./.envrc` when probing.
10. **The Homebrew formula is `clojure-lsp-native`**, in the
    `clojure-lsp/brew` tap, not `clojure-lsp`.
11. **A file-backed make target cannot warn when the file exists** without a
    `FORCE` prerequisite; `gmake .env` uses one and never overwrites.

12. **GitNexus does not parse Clojure.** `analyze` indexed 34 files, 6
    folders and 5 Markdown sections: no functions, no call edges. It also
    appends a block of "MUST run impact before editing" rules to `AGENTS.md`
    and creates `CLAUDE.md` and `.claude/skills/`. Those were reverted. The
    symbol index is Universal Ctags instead (`gmake tags`, options in
    `.ctags.d/`); its built-in Clojure parser tags only `defn` and `ns`, so
    `defn-`, `def` and `deftest` are added by regex.
13. **In a ctags regex bracket, `\n` is a backslash and an `n`**, so
    `[^ \t\n()]` truncated `openssl` to `ope`. Leave `\n` out.
14. **`tags` and `TAGS` are one file on macOS's case-insensitive
    filesystem.** Writing the Emacs format second overwrote the first. Only
    `tags` is written.
15. **macOS `grep` has no `-P`**, and `/usr/bin/make`, `/usr/bin/ctags` and
    `/usr/bin/git` are shims that fail without the Command Line Tools. Use
    `gmake`, the Homebrew `git`, and the Universal Ctags path the Makefile
    finds.
16. **A `.nrepl-port` file is a claim.** `dev/session-status.bb` (the
    SessionStart hook, also `gmake status`) connects to the port and asks the
    REPL for its working directory, which catches both a stale file and a
    REPL that belongs to another checkout. After `gmake session-stop` the
    babashka process takes a second or two to exit and remove the file.

Added after the first version of this report: the SessionStart hook in
`.claude/settings.json`, `gmake status`, `gmake tags`, and the rule (in
`AGENTS.md`) that allocation state goes under `XDG_STATE_HOME`, never in the
repo. The hook has been run by hand in all four states (no REPL, stale, live,
wrong checkout); it has **not** been seen firing in a real session start.

## Not verified

- Two checkouts jacked in side by side. The derivation and the root
  resolution were each tested alone, with throwaway worktrees.
- The language server started from inside Emacs. Only client, registration
  and binary were checked.
- Links in the `*spectre-todos*` compilation buffer actually jumping.
- `.dir-locals.el` no longer prompting in a fresh Emacs (it was trimmed
  after the session that showed the prompt had started).
- That Claude Code loads `AGENTS.md` natively (owner's statement, from
  2.1.277; installed is 2.1.286). `/memory` in a new session will show it.
- `e2-clipboard`, `e5-tui`, `seed`, `nrepl-jvm` make targets were never run.

## Open

- Worktree workflow is prepared, not exercised: `git worktree add
  worktrees/e3 -b e3`, then in it `gmake .env`, `direnv allow`, `gmake
  session`. Expect session name `spectre-e3`.
- `AGENTS.md` tells agents not to fill in TODO stubs unless asked. If
  subagents are meant to grind exercises, the task prompt has to say so.
- Java here is 21; the FFI/libsodium scrypt path needs 22+.
- `babashka-workshop-spectre.el` has no URL header (package-lint).
- `walkthrough.org` covers `core.clj` only. The same treatment for
  `clipboard`, `db`, `cli`, `tui2` was suggested there, not written.

## How the owner works (observed)

Terse, sends follow-ups mid-task, expects them folded in. Wants things run,
not described: when asked whether something should be tested, test it.
Prefers Org for documents they read, gmake targets over remembered syntax,
babashka over Python for scripting, small progressive commits. Corrects
naming and placement quickly (dev tooling out of the `spectre` namespace).
