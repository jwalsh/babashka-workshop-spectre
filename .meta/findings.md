# Findings

What bit, and what was done about it. Numbered so they can be cited: a number
is never reused and a new entry takes the next one, so within a section the
order is not by number. Each was hit, not reasoned to.

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

## Looking things up

31. **Doc lookup by alias needs the namespace loaded.** `C-c C-d d` on
    `fs/which` in a file that has not been loaded finds nothing: the alias
    resolves through the buffer's namespace, and the REPL has none by that
    name until `C-c C-k`. A full name resolves in a REPL with nothing loaded.
32. **Babashka's nREPL is not cider-nrepl.** It answers `info`, `lookup`,
    `eldoc`, `complete` and the test ops. It does not answer apropos,
    ClojureDocs lookup, macroexpand, `fn-refs`, `undef` or refresh, so
    `C-c C-d a`, `s`, `c` and `C-c RET` stop at "requires the nREPL op". It
    gives no line number for anything, and for `babashka.*` a file that is
    not on disk, so `M-.` has nowhere to go. `clojure.repl`'s `doc`, `dir`,
    `apropos` and `source` all work, `source` included for functions compiled
    into the binary.
33. **The JVM REPL has the rest.** `gmake nrepl-jvm` in a worktree: 183 ops,
    the exercise namespaces load, `fs/which` resolves to `fs.cljc:1706`
    inside the library's jar, and `password` returns what babashka returns.
    It writes `.nrepl-port` and removes it on exit; `gmake nrepl` writes
    none. Started beside a jacked-in session it takes `brepl` and `gmake
    status` away from that session.
34. **`p/shell` prints into the nREPL server, not the REPL.** It inherits the
    server's stdout, so the output shows in the `*nrepl-server ...*` buffer.
    `p/sh`, or `{:out :string}`, returns it as a value.
35. **Explorations in a `comment` block use the requires**, so clj-kondo
    stops reporting them unused, and stops reporting `tools` unused. The
    underlines are not a count of what is left. `gmake todos` and the tests
    are.
46. **Which copy of a library is loaded decides which manual is right, and a
    batch Emacs does not know.** Asked where `cider.el` is, a batch Emacs
    with package.el's packages named one copy, and the running Emacs another:
    a git checkout its init file puts first. Only the checkout has the manual
    beside it; package.el installs the Lisp alone. So `gmake resources` asks
    the running Emacs, with a `locate-library` that only reads, and when none
    answers it keeps what is on the shelf instead of fetching for the other
    copy. Finding 19 again, for documents.
47. **A search that respects ignores does not see the shelf.** `rg` from the
    repo root for a string in `resources/babashka/fs/API.md` listed two
    documents and nothing under `resources/`. With the directory named it
    found the line. The Grep tool is the same program.

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
    to the parent's `db.edn` path. Pass `SPECTRE_DB` explicitly there. An
    agent's shell does the same in any worktree, `.envrc` or not: it keeps the
    environment Claude Code started with. In `worktrees/e1`, `gmake status`
    reported `NREPL_PORT` 1667 against a derived 1822, and `SPECTRE_DB` was
    the main checkout's. `direnv exec .` gives the worktree its own.
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
36. **One empty answer is not "unsupported".** The first LSP hover on
    `Mac/getInstance`, a minute after the server started, came back empty,
    and "Java interop does not resolve" went into a README. The same position
    returned the Javadoc a few minutes later. Ask twice before recording a
    negative.
37. **Reading the editor through `emacsclient` is safe; opening things in it
    is not.** To test whether a transient menu could open, `transient-setup`
    and then `transient-quit-all` were called from `emacsclient`. The quit ran
    outside the command loop, and the eval menu stayed on the user's screen
    for ten minutes. Closing it took a real event: `C-g` pushed onto
    `unread-command-events`. Check a cause some way that draws nothing.
42. **Writing a file the user has open with unsaved edits makes their next
    save a conflict.** A one-line whitespace fix to `core.clj` went to disk
    while its buffer was modified. Undone: the file was put back to `HEAD`
    and the buffer's recorded modification time resynced with
    `set-visited-file-modtime`, so the save does not prompt. Ask
    `buffer-modified-p` first.
38. **A document can agree with itself and still be wrong.** The Makefile's
    help said the default database is `~/.config/spectre-db.edn`; the code
    says `~/.config/spectre/db.edn`. A key table listed `C-c C-d a` because
    the menu has that entry, not because it works on babashka. `gmake docs`
    now checks what can be checked mechanically: targets, commands, links
    and the walkthrough's results.
45. **A manifest that names a path works on the machine that wrote it.** The
    first `dev/resources.edn` gave the directory one Emacs package manager
    keeps its checkouts in, and fetched all 24 entries. In a public repo that
    is a detail of one person's setup, and for anyone else three entries that
    are never found. Nothing in the manifest names a path now: where a thing
    is installed is asked of what installed it (`bb print-deps`, the tool's
    `--version`, Emacs's `locate-library`).

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
39. **An Org search link wrapped in parentheses is read as a code
    reference.** A link to `src/tour.clj` searching for `(fs/which "git")`
    fails with an rx error; without the outer parentheses it lands on the
    line. Found by following every new link in the running Emacs, not by
    reading them. Links by search text also survive an exercise being
    implemented, which a line number in `cli.clj` would not.
41. **A result copied from a terminal can hide what it is.** The walkthrough
    showed the salt as a string with three spaces before the length byte.
    They are NUL bytes; Emacs shows `^@`. `gmake docs` compares values, not
    how they print, and that is how it surfaced. The form now returns the
    bytes.
40. **A Claude Code plugin with an LSP server needs a new session.**
    `/reload-plugins` loaded the plugin and started no server; and a
    marketplace added from a shell was not found by `/plugin install` in a
    session already running. `dev/claude-plugins/plugins/clojure-lsp/README.md`
    has the route that worked.
43. **zsh does not split an unquoted variable into words.** A loop over
    `"owner/repo ref"` pairs with `set -- $pair` left the pair whole in `$1`,
    and curl answered `000` to seven malformed URLs. That read as the host
    being down. One URL typed out showed it was up. A `bb` script, which the
    rules already ask for, has no word splitting to get wrong.
44. **A cell of an Org table cannot hold a `|`.** The first
    `resources/INDEX.org` put a shell pipeline in its "from" column and those
    rows came out with extra columns. Taking the shell out of the manifest
    fixed it and something else with it: a data file no longer runs commands.
    Manual pages are a kind of their own, and the overstriking `col -b`
    removed is one regex.
