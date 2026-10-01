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

Written 2026-09-30 and **not yet installed or run**. Unknown until it is:

- whether the declaration is accepted as written (`args`, the two language
  ids);
- how long the first start takes while clojure-lsp analyses the classpath;
- whether Java interop symbols (`javax.crypto.Mac` in `core.clj`) resolve;
- whether it and an Emacs lsp-mode session contend over `.lsp/.cache`.

Without it, the same questions are answered from the shell:
`clojure-lsp references --from spectre.core/master-key` and
`clojure-lsp diagnostics`.
