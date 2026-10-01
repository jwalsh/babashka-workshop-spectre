;;; Directory Local Variables
;;; For more information see (info "(emacs) Directory Variables")

;; Only variables Emacs already considers safe, so opening a file never
;; prompts. The rest of the setup is in babashka-workshop-spectre.el.
((nil
  (cider-preferred-build-tool . babashka))
 (clojure-mode
  (indent-tabs-mode . nil)
  (fill-column . 80)))
