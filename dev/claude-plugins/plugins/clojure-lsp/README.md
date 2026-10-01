# clojure-lsp for Claude Code

Puts `clojure-lsp` behind Claude Code's LSP tool: go to definition, find
references, hover, document and workspace symbols, call hierarchy, for
`.clj`, `.cljc`, `.cljs`, `.bb` and `.edn`.

The official marketplace has no Clojure entry. This is the same shape as its
other language servers: one `lspServers` declaration in
`dev/claude-plugins/.claude-plugin/marketplace.json`, and nothing else. The
plugin ships no code; it runs the `clojure-lsp` already on PATH with `listen`.

## Install

From a Claude Code session in this checkout:

    /plugin marketplace add ./dev/claude-plugins
    /plugin install clojure-lsp@spectre-workshop
    /reload-plugins

Needs `clojure-lsp` on PATH (`gmake deps` shows it).

## Status

Installed and run on 2026-09-30, on `src/spectre/core.clj`:

- document symbols: the namespace and its 12 definitions, private ones
  marked;
- references to `master-key`: 10 across `core.clj` and `cli.clj`;
- diagnostics arrive unasked after the first call, from both clj-kondo and
  clojure-lsp.

What it took to get there:

- A session only sees a marketplace added from inside it. One added with
  `claude plugin marketplace add` from a shell is not found by `/plugin
  install` in a session already running; `claude plugin install` from the
  same shell works.
- `/reload-plugins` did not start the server. Restarting the session did.

Not working, or not known:

- hover on a Java interop call (`Mac/getInstance`) comes back empty;
- whether it and an Emacs lsp-mode session contend over `.lsp/.cache`.

Without it, the same questions are answered from the shell:
`clojure-lsp references --from spectre.core/master-key` and
`clojure-lsp diagnostics`.
