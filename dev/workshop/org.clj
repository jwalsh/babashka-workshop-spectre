(ns workshop.org
  "Write Org from outside Emacs: a table, a block, and the parts of a
   document that a script owns.

   Dev tooling, like workshop.todos. A part a script owns is an Org dynamic
   block named workshop:

     #+BEGIN: workshop :view namespaces :as mermaid
     ...
     #+END:

   Org already has the idea. A dynamic block is text that a function writes
   and a person does not, and its first line says what writes it, so the
   document says of itself which parts were built from the files. Everything
   between the two lines belongs to the builder.

   To show such a block in a document without it being one, put it in an
   example block, where Org escapes the line with a comma: `,#+BEGIN:`."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]))

;;;; things to write

(defn table
  "Rows of strings as an aligned Org table, the first row its header."
  [[header & rows]]
  (let [widths (apply map (fn [& cells] (apply max 1 (map count cells))) header rows)
        line (fn [cells]
               (str "| " (str/join " | " (map #(format (str "%-" %1 "s") %2) widths cells)) " |"))
        rule (str "|-" (str/join "-+-" (map #(str/join (repeat % "-")) widths)) "-|")]
    (str/join "\n" (concat [(line header) rule] (map line rows)))))

(defn- escaped
  "text as Org wants it inside a block: a line that would start a headline
   or a keyword gets a comma in front, as editing the block with `C-c '`
   gives it."
  [text]
  (str/replace text #"(?m)^(,*)(\*|#\+)" ",$1$2"))

(defn src-block
  "text as a source block in language. header is the rest of the first line,
   such as `:eval no`."
  ([language text] (src-block language nil text))
  ([language header text]
   (str "#+begin_src " language (when header (str " " header)) "\n" (escaped text) "\n#+end_src")))

(defn example-block [text]
  (str "#+begin_example\n" (escaped text) "\n#+end_example"))

;;;; the parts a script owns

(def ^:private block-start #"(?i)^[ \t]*#\+BEGIN:[ \t]+workshop(?:[ \t]+(.*))?$")

(def ^:private block-end #"(?i)^[ \t]*#\+END:?[ \t]*$")

(defn- parameters
  "The parameters on a block's first line, `:view namespaces :as mermaid`,
   as a map. They are an Emacs Lisp property list, which reads as EDN: a bare
   word is a symbol, and comes out as a string. Throws when they do not
   pair up."
  [text]
  (let [items (edn/read-string (str "[" text "]"))]
    (when (or (odd? (count items)) (not-every? keyword? (take-nth 2 items)))
      (throw (ex-info "parameters are not :key value pairs" {})))
    (update-vals (apply hash-map items) #(if (symbol? %) (str %) %))))

(defn blocks
  "The workshop blocks of an Org text, in order. Each is {:line, of its first
   line; :header, that line; :parameters; :body, the text between its two
   lines; :end, the index of its last line}, or {:line :problem} for one that
   cannot be read. A block with no end stops the scan: what follows it cannot
   be told from its body."
  [text]
  (let [lines (vec (str/split text #"\n" -1))]
    (loop [index 0
           found []]
      (if (>= index (count lines))
        found
        (if-let [[header parameter-text] (re-find block-start (lines index))]
          (if-let [end (first (filter #(re-find block-end (lines %))
                                      (range (inc index) (count lines))))]
            (recur (inc end)
                   (conj found
                         (try
                           {:line (inc index)
                            :header header
                            :parameters (parameters parameter-text)
                            :body (str/join "\n" (subvec lines (inc index) end))
                            :end end}
                           (catch Exception caught
                             {:line (inc index) :end end :problem (ex-message caught)}))))
            (conj found {:line (inc index) :problem "has no #+END: line"}))
          (recur (inc index) found))))))

(defn rewrite
  "text with the body of each workshop block replaced by (body-of block),
   a string of whole lines. A block with a problem, and one for which
   body-of returns nil, is left as it is. Everything outside the blocks comes
   back byte for byte."
  [text body-of]
  (let [lines (vec (str/split text #"\n" -1))
        ;; from the last block up, so the indexes of the earlier ones hold
        rewritten (reduce (fn [lines {:keys [line end problem] :as block}]
                            (if-let [body (when-not problem (body-of block))]
                              (into (conj (subvec lines 0 line) body) (subvec lines end))
                              lines))
                          lines
                          (reverse (blocks text)))]
    (str/join "\n" rewritten)))
