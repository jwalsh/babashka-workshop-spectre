# What "working" looks like: the conj-26 branch, driven from tmux

2026-09-30. `conj-26` is upstream's solutions branch (E3 to E5 solved),
checked out at `worktrees/conj-26` with none of our tooling. A sub-agent ran
the tests, the CLI and both TUIs there, with the database in a scratch
directory and `--print` on every `pw` so the clipboard was never touched.
Everything below was run unless marked *read*.

## Result

`bb test --excludes :clipboard --excludes :optional`: 25 tests, 95
assertions, 0 failures. CLI and both TUIs behave as their source says.

## CLI contract (`bb pw`)

- Defaults `{:counter 1 :template :long :variant :password}`. Only those
  three keys are stored per site; the name is not.
- New site: one `Saving <site> to db.edn: {...}` line. Stored site, no
  flags: no message, db unchanged. A flag that disagrees with a stored key:
  one `db.edn has :counter 1 for <site>, using and saving 2` line per key,
  then save.
- A stored entry with only some keys is filled out silently.
- No counter floor: `-c 0` and `-c -5` are accepted and saved. The TUIs do
  floor at 1, and a stored `-5` jumps to `1` on the first Left or Right.
- Warnings and the identicon go to stderr, the password to stdout.
- Bad template, non-numeric counter, unknown option, extra positional,
  missing site: `Error: ...`, rc=1, db untouched.
- Copies only without `--print` and on a tty (*read*, `cli.clj:74`).

## TUI contract (`bb tui`, `bb tui2`)

Neither derives a password or touches the clipboard: they browse and edit
settings. Filter is a case-insensitive substring ranked by match position;
backspace widens. List and field movement clamp, no wraparound. Template and
variant wrap in both directions; the counter floors at 1. Enter saves, Esc
discards, Esc on the search screen quits. `tui2` adds an identity screen on
Tab, a `> ` selection marker, cursor movement inside the query, PageUp and
PageDown, and `--nrepl`.

## Friction: the part this repo cares about

- **The parent checkout's environment leaks into a worktree with no
  `.envrc`.** The shell arrived with main's direnv values (`SPECTRE_DB`,
  `SPECTRE_NAME`, `SPECTRE_MASTER`, `NREPL_PORT`), and the tmux server
  inherited them. The first `tui2` run came up prefilled with the workshop's
  example identity. Without an explicit `SPECTRE_DB` on every command, writes
  would have gone to main's `db.edn` path.
- **Batched arrow keys are lossy in `tui2`.** `send-keys Down Down` moved one
  row. One key per `send-keys`, then wait for the redraw, was reliable. `tui`
  took batches without loss.
- **Selection is invisible in a plain capture.** `tui` marks it with inverse
  video only, and so does `tui2`'s edit screen (bold cyan):
  `capture-pane -p` shows nothing move. `capture-pane -p -e` and a search for
  `ESC[7m` does, as does the `n/m sites` line.
- **A key that correctly does nothing looks like a dropped key.** At a floor
  or an edge the only signal is a timeout.
- **The session dies with the command**, taking the exit status with it:
  wrap as `sh -c 'bb tui; echo EXIT=$?; exec cat'`.
- **`db/default-path` is a `def`** (*read*, `db.clj:8`): `SPECTRE_DB` is read
  once at namespace load, so changing it in a live REPL does nothing without
  a reload.
- tmux key names that worked: `BSpace`, `Escape`, `Enter`, `Tab`, `C-u`,
  `C-c`, `NPage`, `PPage`, `End`.

## Not verified

The clipboard path and its test (deliberately). Interactive prompts with the
masked master password. `bb dev` and `bb tui2 --nrepl` (they bind 1667).
Shell completion, scrolling past a screenful, resize, the scrypt FFI path.

## Generalizes

`gmake session-shot` is `capture-pane -p`: it would miss the selection in
either TUI for the same reason. A screen surface for a TUI needs `-e`.
