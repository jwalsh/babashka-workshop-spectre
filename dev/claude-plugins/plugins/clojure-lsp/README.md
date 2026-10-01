# clojure-lsp for Claude Code

Puts `clojure-lsp` behind Claude Code's LSP tool: go to definition, find
references, hover, document and workspace symbols, call hierarchy, for
`.clj`, `.cljc`, `.cljs`, `.bb` and `.edn`.

The official marketplace has no Clojure entry. This is the same shape as its
other language servers: one `lspServers` declaration in
`dev/claude-plugins/.claude-plugin/marketplace.json`, and nothing else. The
plugin ships no code; it runs the `clojure-lsp` already on PATH with `listen`.

## Install

From a shell in this checkout, then start a new Claude Code session:

    claude plugin marketplace add ./dev/claude-plugins
    claude plugin install clojure-lsp@spectre-workshop

That is the route that was run. It registers the marketplace in your user
settings by this checkout's absolute path, and enables the plugin for every
project. Needs `clojure-lsp` on PATH (`gmake deps` shows it).

What did not work on the way:

- `/plugin install` in a session that was already running did not find a
  marketplace added from a shell a minute earlier.
- `/reload-plugins` did not start the server. Restarting the session did.

`/plugin marketplace add` typed inside a session was never tried.

## What it answers

Run on 2026-09-30 against this repo. Lines and characters are 1-based.

- **Symbols**: `core.clj` gives the namespace and its 12 definitions, the
  private ones marked.
- **References and callers**: 10 references to `master-key` across two files.
  Incoming calls to `spectre.db/load-db` name `seed!`, `tui2/init`,
  `tui/loop!` and the tests. Outgoing calls from `spectre.cli/generate` are
  the whole flow of the `pw` command. The maps under *Working it here* in
  `exercises.org` were drawn from these.
- **Documentation for a library call**: hover on `fs/which` returns its
  arguments and docstring, read from `babashka/fs` in `~/.m2`; go to
  definition lands on the line inside that jar. No REPL has to be running
  and no namespace has to be loaded, which the REPL route needs.
- **Java interop**: hover on `Mac/getInstance` returns the Javadoc, from the
  JDK sources clojure-lsp keeps under `~/.cache/clojure-lsp/jdk`.
- **Diagnostics** arrive unasked after an edit, from clj-kondo and from
  clojure-lsp. The copy of clj-kondo inside clojure-lsp is newer than the
  2024 one on PATH here, and reports things `clj-kondo --lint` does not.

## Not known

- The first hover on `Mac/getInstance`, within a minute of the server
  starting, came back empty. The same position answered a few minutes later.
  Probably the server was still analysing; that was not established.
- Whether it and an Emacs lsp-mode session contend over `.lsp/.cache`.

Without the plugin, the shell answers the reference and diagnostic questions:
`clojure-lsp references --from spectre.core/master-key` and
`clojure-lsp diagnostics`.
