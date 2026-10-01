# Start here

For someone, or some agent, arriving cold. `AGENTS.md` has the rules and the
commands; `gmake help` lists the targets. This directory holds only what
those two do not say.

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
own state, logs, hooks.

## Where things stand (2026-09-30)

- `main` carries the tooling and is the base. `src/` and `test/` match
  upstream's `main` except for exploration forms in `core.clj`'s comment
  block. Nothing is pushed; `origin` is a public fork.
- No exercise is implemented. 30 TODO markers (`gmake todos`). E1's one
  existing test passes; E2 to E5 fail as designed. Deadlines: `gmake agenda`.
- Worktrees, under `worktrees/` (ignored through `.git/info/exclude`):
  `e1`, level with `main`, for E1's test additions; `conj-26`, upstream's
  solutions branch, with none of our tooling. Do not read solution bodies
  out of `conj-26` into an exercise branch.
- Hooks in `.claude/settings.json`: session status on start; delimiter
  repair and cljfmt around every Write and Edit.

## Open

- `gmake deps-emacs` runs in batch and reports `ok` for things a real
  session does not have. See findings 17 to 19. The check that answers is
  `emacsclient -e` against the running Emacs; no target wraps it yet.
- `gmake session-shot` is `tmux capture-pane -p`, which drops the inverse
  video the TUIs mark a selection with. It needs `-e`.
- The `C-c C-v` eval menu errors in an Emacs with a broken libgccjit
  (finding 8). Setting `native-comp-enable-subr-trampolines` to nil in the
  wiring file is the likely fix; not applied.
- clj-kondo on PATH is 2024.11.14; current is 2026.08.04.
- Java on the shell's PATH is 21; the FFI scrypt path needs 22+.
- `walkthrough.org` covers `core.clj` only.
- nREPL messages are logged in memory only (`*nrepl-messages ...*`). Nothing
  durable records what was evaluated.

## Never verified

- `M-x spectre-lsp`, and the language server starting inside Emacs at all.
- `M-x spectre-connect` against a running `bb tui2 --nrepl`. The redefinition
  itself was verified over `brepl -p 1667`.
- Two checkouts jacked in side by side in one Emacs.
- Flymake underlines in a file that has warnings (only a clean file was
  checked live), and whether the mode line counts them.
- `gmake demo` on anything but the macOS build it was written on.
- `e2-clipboard`, `seed`, `nrepl-jvm` targets.
- Links in the `*spectre-todos*` buffer jumping.
