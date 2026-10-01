# keycast was installed and never shown

2026-09-30, same session as [lsp-blocklist](lsp-blocklist.md), same shape.

`gmake deps-emacs` said `ok keycast ... header line`. Neither the tmux
session nor the native Emacs showed a keycast header line. Asked through
`emacsclient`, the running Emacs had:

    (featurep 'keycast)        => nil
    (locate-library "keycast") => nil
    package--initialized       => nil
    (length package-alist)     => 0

The owner's init does not activate package.el, so a package that exists only
as a directory under `~/.emacs.d/elpa` is not on the `load-path`. The other
packages the wiring uses (CIDER, clojure-mode, paredit) get there some other
way. keycast was installed on 2026-09-30 through package.el and nothing else.
`EMACS_BATCH` calls `(package-initialize)`, so batch saw it.

## What changed

`spectre--keycast` in `babashka-workshop-spectre.el`: when `require` fails,
load the package descriptors and `package-activate` keycast alone (with its
dependencies, compat and cond-let), then require again. It does not call
`package-initialize`: activating all 163 packages in an init that chose not
to is not this file's business.

## Verified

In the running native Emacs, through `emacsclient`, after reloading the file:
`keycast-header-line-mode` is `t`. The header line itself was not seen: there
is no screen capture of a GUI frame from here.

## Generalizes

Second time in one session that the batch check passed and the live editor
did not. `emacsclient -e` against the session the owner is actually in is
the surface that answers; the batch check answers "is it installed".
