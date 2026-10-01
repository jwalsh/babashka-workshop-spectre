# Findings

What bit, and what was done about it. Numbered so they can be cited; newest
last. Each was hit, not reasoned to.

## REPL and editor

1. **CIDER against babashka's nREPL does not turn on `cider-mode` in source
   buffers.** The connection handler asks for `cider/init-debugger`, which
   only cider-nrepl provides, errors, and stops. Symptom: `C-c C-k is
   undefined` after a successful jack-in. The wiring enables the mode itself;
   the error still prints once per connect. `bb dev --jvm` for the debugger.
2. **A fixed nREPL port attaches a worktree to the wrong REPL.** "Something
   is listening" is not "it is mine". Jack-in takes an OS-assigned port;
   `.envrc` derives a distinct `NREPL_PORT` per worktree for the fixed-port
   commands.
3. **Resolve the checkout from the current buffer**, not from where the elisp
   was loaded, or one Emacs runs every test and jack-in in the first checkout.
4. **`.dir-locals.el` may hold only `safe-local-variable`s**, or Emacs
   prompts on every file. An ERT test enforces it.
5. **A `.nrepl-port` file is a claim.** `gmake status` connects and asks the
   REPL for its working directory, which catches a stale file and a REPL from
   another checkout. After a stop, babashka takes a second or two to remove it.
6. **A session started with `-f spectre-session` leaves point in the REPL
   window.** Keys meant for the source buffer need `C-x o` first.
7. **An evaluation from a source buffer leaves nothing in the REPL buffer.**
   Only `=> value` in `*Messages*`, without the form. `nrepl-log-messages` is
   now on, which keeps form, namespace, value and time, in memory.
8. **Native compilation can be broken** (`libgccjit`), and then CIDER's
   transient menus never open: `C-c C-d` (docs) and `C-c C-v` (eval) both
   need a trampoline for `recursive-edit`. The same `transient-setup` call
   failed with trampolines on and opened with them off, so the wiring file
   now sets `native-comp-enable-subr-trampolines` to nil, as the batch runs
   already did. Documentation itself was never the problem: babashka's nREPL
   answers `info`, `lookup` and `eldoc`.
9. **A bare `-l name.el` is not found by a graphical Emacs once a long init
   has run.** `gmake demo` passes absolute paths.
10. **`bb tui2 --nrepl` is a second REPL**, and it writes no `.nrepl-port`.
    A form sent to the jacked-in REPL changes nothing on the TUI's screen;
    `brepl -p <port>` reaches the right one. A redefinition shows on the next
    keypress, not before.

## Environment

11. **Do not override `XDG_CONFIG_HOME` per checkout.** The workshop reads it
    for the default `db.edn`; git, gh and clojure read it too. `SPECTRE_DB`
    isolates the database.
12. **direnv's dotenv rejects an unquoted value with a space**, and `.env`
    cannot compute: derived values go in `.envrc`.
13. **`source .envrc` without `./` searches `PATH` first** in bash.
14. **A worktree with no `.envrc` inherits the parent checkout's direnv
    values**, and so does a tmux server started from it. In `conj-26` the TUI
    came up prefilled with the template identity, and writes would have gone
    to the parent's `db.edn` path. Pass `SPECTRE_DB` explicitly there.
15. **`db/default-path` is a `def`**: `SPECTRE_DB` is read once at namespace
    load. Changing it in a live REPL does nothing without a reload.

## Checks that pass and are wrong

16. **GitNexus does not parse Clojure**: its index of this repo is files
    only. It also rewrites `AGENTS.md` and adds `.claude/skills/`. Reverted;
    the symbol index is Universal Ctags.
17. **lsp-mode refused every file: the home directory was on its blocklist.**
    The blocklist is checked before the workspace folders and an entry blocks
    everything under it. It comes from answering `d` to "import project
    root?" at `~`. `gmake deps-emacs` said `ok`: it checked client, server
    registration and binary. It now reads the session file too, and
    `M-x spectre-lsp` removes the entry.
18. **keycast was installed and never shown.** An init that does not activate
    package.el leaves a package that exists only under `package-user-dir` off
    the `load-path`. The batch check calls `package-initialize`, so it saw
    it. The wiring now activates keycast alone when `require` fails.
19. **Generalizing 17 and 18:** a batch check answers "is it installed". What
    a session has switched on is user state, read at start from the user's
    files, and only the running editor knows it: `emacsclient -e`.
20. **cljfmt in the edit hook costs nothing here.** The sources already
    conform, and a one-line edit to copies of three files changed only that
    line. Objection withdrawn after measuring.

## Observing a TUI through tmux

21. **Batched arrow keys are lossy in `tui2`**: two Downs moved one row. One
    key per `send-keys`, then wait for the redraw.
22. **Selection is inverse video only**, invisible to `capture-pane -p`. Use
    `-e`, or read the `n/m sites` line.
23. **A key that correctly does nothing looks like a dropped key.** At a
    floor or an edge the only signal is a timeout.
24. **The session dies with the command.** To keep the exit status:
    `sh -c 'bb tui; echo EXIT=$?; exec cat'`.

## Build and tools

25. **Org Babel and failing commands:** wrap as `{ ...; } 2>&1 | sed` so
    stderr lands in the result and a non-zero exit still returns output.
26. **A file-backed make target cannot warn that the file exists** without a
    `FORCE` prerequisite. `gmake .env` uses one and never overwrites.
27. **In a ctags regex bracket, `\n` is a backslash and an `n`**:
    `[^ \t\n()]` truncated `openssl` to `ope`.
28. **`tags` and `TAGS` are one file on a case-insensitive filesystem.** The
    ctags index is `.tags`; the Emacs table is `TAGS`, built with `etags
    --language=lisp`.
29. **macOS `grep` has no `-P`**, and `/usr/bin/make`, `ctags` and `git` are
    shims that fail without the Command Line Tools.
30. **A buffer with no file is linted as `stdin.clj`**, so clj-kondo reports a
    namespace mismatch unless the `ns` is `stdin`. Real files are unaffected.
