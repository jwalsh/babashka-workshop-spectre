# lsp-mode would not start: the home directory was blocklisted

2026-09-30, first session in the `e1` worktree.

Opening `src/spectre/core.clj` gave, in `*Messages*`:

    LSP :: File .../src/spectre/core.clj is in blocklisted directory ~/
    LSP :: core.clj not in project or it is blocklisted.

`~/.emacs.d/.lsp-session-v1` had the home directory in
`folders-blocklist`, and many workspace folders, none of them this repo.
lsp-mode checks the blocklist before the workspace folders
(`lsp--calculate-root`), and an entry blocks everything under it, so
registering the checkout would not have been enough. The entry comes from
answering `d` to the "import project root?" prompt when the suggested root
was `~`.

`gmake deps-emacs` reported the lsp row as `ok` throughout. It checked the
client, the registration and the binary, in batch without the init file, and
the experience report already listed "language server started from inside
Emacs" as never verified. The session file is user state, not package state,
but it is read from the same place in batch, so the check can see it.

## What changed

- `spectre--blocking`: the blocklist entries that are the checkout or above it.
- `M-x spectre-lsp` (`l` in `spectre-map`): removes those, adds the checkout
  as a workspace folder, starts lsp. It edits the shared session file and
  says which entries it took off.
- `gmake deps-emacs`: the lsp row is `-` with the blocking entry named when
  there is one.

## Not verified

`spectre-lsp` was not run: it rewrites the owner's lsp session, so it is
theirs to invoke. Only `spectre--blocking` (ERT) and the deps row (against
the real session file) were exercised.

## Generalizes

A check that runs without the user's init still has to read the user state
the tool reads at start. "Installed and runs" was true and beside the point.
