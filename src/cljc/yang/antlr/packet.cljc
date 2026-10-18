(ns yang.antlr.packet
  "The target-neutral parse-tree IR and its reader (docs/design/yang.antlr.md
   §5.5). ANTLR has no neutral tree: each target materializes its own
   parse-tree objects. This namespace owns the neutral form every host's
   exporter produces (`yang.antlr.cst`, one file per host) and every
   language's CST-to-UAST converter consumes: the CST packet, version 1.

   Packet (version 1):

     {:yang.cst/version 1
      :yang.cst/unit u
      :yang.cst/grammar id
      :yang.cst/outcome :yang.cst/ok | :yang.cst/syntax-error | :yang.cst/source-error
      :yang.cst/root 0                       ; ok only
      :yang.cst/nodes [record ...]           ; ok only, preorder, id = index
      :yang.cst/errors [error ...]           ; non-ok only
      :yang.cst/complete true}

   Rule record  {:id n :kind :rule :rule \"name\" :children [ids] :span [s e]}
   Token record {:id n :kind :token :type \"NAME\" :text t :span [s e]}
                 plus :synthetic true when the token's text is not the source
                 at its span (indentation helpers, EOF); its span is then
                 zero-width.
   Error record {:kind :syntax-error :message m :line l :column c :span [s e]}

   Spans are half-open UTF-8 byte offsets into the sealed source. A unit with
   any syntax error yields an error packet and no nodes: never a partial
   program.

   Invariants every target's exporter must meet (`validate!` checks 1, 6 and
   7 structurally, and 2-5 against the source when it is given; `conform` is
   the source-aware check as a vector of violations):

     1. ids are preorder indexes; :root is 0; child edges form a tree
        (`validate!`).
     2. :rule is the grammar rule name from the parser's rule-name table,
        never a target class name; :type is the symbolic token name, else the
        literal name, else the decimal type, \"EOF\" for EOF.
     3. :span is half-open UTF-8 byte offsets into the sealed source. Each
        target converts its own index unit at the boundary (the JVM indexes
        code points; the JS and Dart streams are opened in code-point mode).
     4. A token is :synthetic with a zero-width span and the canonical helper
        text of its type exactly when `synthetic-policy` says so: DEDENT and
        EOF always, NEWLINE only at the end of the source, no other type ever.
        A real token's text is the source at its span.
     5. A rule's span covers its real children only; an empty rule is
        zero-width.
     6. Any syntax error yields an error packet with :yang.cst/errors and no
        nodes.
     7. :yang.cst/grammar names the grammar revision and tool; the target does
        not appear in the packet. Target equivalence is the contract.

   Portable and language-agnostic: child edges are data, so the tree is
   validated before anything walks it.")


(def packet-version 1)


;; =============================================================================
;; Source text
;; =============================================================================

(defn code-points
  "The code points of `s` as a vector of integers."
  [s]
  #?(:cljd (vec (.-runes ^String s))
     :clj (mapv long (.toArray (.codePoints ^String s)))
     :cljs (mapv #(.codePointAt % 0) (js/Array.from s))))


(defn utf8-length
  "The UTF-8 byte count of one code point."
  [cp]
  (cond (< cp 0x80) 1
        (< cp 0x800) 2
        (< cp 0x10000) 3
        :else 4))


(defn utf8-bytes
  "The UTF-8 byte count of a string: span ends are checked against it."
  [s]
  (reduce + 0 (map utf8-length (code-points s))))


(defn byte-offsets
  "Byte offset of each code point index of `cps`, plus the total at index n."
  [cps]
  (loop [out [0]
         acc 0
         cps (seq cps)]
    (if cps
      (let [acc (+ acc (utf8-length (first cps)))]
        (recur (conj out acc) acc (next cps)))
      out)))


(def synthetic-policy
  "Which tokens an indentation helper may fabricate, keyed on the token's
   type: `:when` is `:always` (every token of the type is synthetic) or
   `:at-end` (synthetic only at the end of the source, real elsewhere), and
   `:text` is the canonical text a synthetic token carries. A type absent
   from the map is never synthetic."
  {"DEDENT" {:when :always, :text ""},
   "EOF" {:when :always, :text "<EOF>"},
   "NEWLINE" {:when :at-end, :text "\n"}})


;; =============================================================================
;; Packet construction and the parser stage transform
;; =============================================================================

(defn base
  [grammar-id unit]
  {:yang.cst/version packet-version,
   :yang.cst/unit unit,
   :yang.cst/grammar grammar-id,
   :yang.cst/complete true})


(defn source-error
  [grammar-id unit message data]
  (assoc (base grammar-id unit)
         :yang.cst/outcome :yang.cst/source-error
         :yang.cst/errors [(merge {:kind :source-error, :message message}
                                  data)]))


(defn transform
  "The parser stage's transform over source events: chunks accumulate per
   unit by ordinal; a seal with every chunk present assembles the snapshot,
   parses it with `parse-unit` (fn [worker unit source] packet), and emits
   one packet on port `:cst`. A conflicting duplicate chunk, a seal with
   chunks missing, or an unknown event emits a source-error packet instead.
   State is `{unit {ordinal text}}`."
  [parse-unit worker]
  (let [grammar-id (get-in worker [:profile :grammar-id])]
    (fn [units event]
      (let [unit (:yang.source/unit event)]
        (case (:yang.source/event event)
          :yang.source/chunk
          (let [ordinal (:yang.source/ordinal event)
                text (:yang.source/text event)
                prior (get-in units [unit ordinal])]
            (if (and (some? prior) (not= prior text))
              [(dissoc units unit)
               [[:cst (source-error grammar-id unit "Conflicting duplicate chunk"
                                    {:ordinal ordinal})]]]
              [(assoc-in units [unit ordinal] text) []]))

          :yang.source/seal
          (let [chunks (get units unit {})
                n (:yang.source/chunk-count event)]
            (if (= (set (range n)) (set (keys chunks)))
              [(dissoc units unit)
               [[:cst (parse-unit worker unit
                                  (apply str (map chunks (range n))))]]]
              [(dissoc units unit)
               [[:cst (source-error grammar-id unit "Seal with missing chunks"
                                    {:chunk-count n,
                                     :present (vec (sort (keys chunks)))})]]]))

          [units
           [[:cst (source-error grammar-id unit "Unknown source event"
                                {:event (:yang.source/event event)})]]])))))


(defn- malformed!
  [message data]
  (throw (ex-info message
                  (merge {:yang.python.antlr/diagnostic
                          :yang.python.antlr/malformed-cst}
                         data))))


;; =============================================================================
;; Validation: the packet's contract as a vector of violations
;; =============================================================================

(defn- span?
  [s]
  (and (vector? s)
       (= 2 (count s))
       (integer? (first s))
       (integer? (second s))
       (<= 0 (first s) (second s))))


(defn- blank-string?
  [s]
  (or (not (string? s)) (= "" s)))


(defn- metadata-violations
  "The packet's own fields: version, unit, grammar name, completion, outcome,
   and the outcome-specific contract (an ok packet has a root and nodes and
   no errors; every other outcome has errors and neither nodes nor a root)."
  [packet]
  (let [outcome (:yang.cst/outcome packet)
        errors (:yang.cst/errors packet)
        note (fn [why] {:violation why})]
    (cond-> []
      (not= packet-version (:yang.cst/version packet))
      (conj (note :bad-version))
      (not (contains? packet :yang.cst/unit))
      (conj (note :no-unit))
      (blank-string? (:yang.cst/grammar packet))
      (conj (note :bad-grammar-name))
      (not (true? (:yang.cst/complete packet)))
      (conj (note :not-complete))
      (not (contains? #{:yang.cst/ok :yang.cst/syntax-error :yang.cst/source-error}
                      outcome))
      (conj (note :unknown-outcome))
      (and (= :yang.cst/ok outcome) (contains? packet :yang.cst/errors))
      (conj (note :ok-packet-has-errors))
      (and (= :yang.cst/ok outcome) (not= 0 (:yang.cst/root packet)))
      (conj (note :no-root))
      (and (not= :yang.cst/ok outcome)
           (or (contains? packet :yang.cst/nodes)
               (contains? packet :yang.cst/root)))
      (conj (note :error-packet-has-nodes))
      (and (contains? #{:yang.cst/syntax-error :yang.cst/source-error} outcome)
           (not (and (vector? errors) (seq errors))))
      (conj (note :no-errors)))))


(defn- error-violations
  "Each error record of a non-ok packet: its kind matches the outcome, it has
   a message, a syntax error carries ANTLR's line and column as captured, and
   a span (when present) is a byte span."
  [packet]
  (let [kind ({:yang.cst/syntax-error :syntax-error,
               :yang.cst/source-error :source-error}
              (:yang.cst/outcome packet))
        bad (volatile! [])]
    (doseq [[i e] (map-indexed vector (:yang.cst/errors packet))
            :let [note! (fn [why] (vswap! bad conj {:error i, :violation why}))]]
      (when (not= kind (:kind e)) (note! :error-kind))
      (when-not (string? (:message e)) (note! :error-message))
      (when (and (= :syntax-error kind)
                 (not (and (integer? (:line e)) (<= 1 (:line e)))))
        (note! :error-line))
      (when (and (= :syntax-error kind)
                 (not (and (integer? (:column e)) (<= 0 (:column e)))))
        (note! :error-column))
      (when (and (some? (:span e)) (not (span? (:span e))))
        (note! :error-span)))
    @bad))


(defn- node-field-violations
  [nodes rule-names]
  (let [bad (volatile! [])
        note! (fn [id why] (vswap! bad conj {:id id, :violation why}))]
    (doseq [i (range (count nodes))
            :let [n (nth nodes i)]]
      (cond
        (not= i (:id n)) (note! i :id-not-index)
        (= :rule (:kind n))
        (do (when (blank-string? (:rule n)) (note! i :bad-rule-name))
            (when (and rule-names
                       (string? (:rule n))
                       (not (contains? rule-names (:rule n))))
              (note! i :unknown-rule-name))
            (when-not (vector? (:children n)) (note! i :bad-children))
            (when-not (span? (:span n)) (note! i :bad-span)))
        (= :token (:kind n))
        (do (when (blank-string? (:type n)) (note! i :bad-token-type))
            (when-not (string? (:text n)) (note! i :bad-token-text))
            (when (seq (:children n)) (note! i :token-has-children))
            (when-not (span? (:span n)) (note! i :bad-span)))
        :else (note! i :unknown-kind)))
    @bad))


(defn- edge-violations
  "One preorder walk from the root: a rule's children are exactly the roots of
   consecutive subtrees, so every node is reached once, in document order,
   and a node outside the walk is unreachable."
  [nodes]
  (let [total (count nodes)
        bad (volatile! [])
        note! (fn [id why] (vswap! bad conj {:id id, :violation why}))
        walk (fn walk
               [id]
               (let [n (nth nodes id)]
                 (if (= :rule (:kind n))
                   (loop [cursor (inc id)
                          kids (seq (:children n))]
                     (if-not kids
                       cursor
                       (let [k (first kids)]
                         (cond
                           (not (and (integer? k) (< id k total)))
                           (do (note! id :child-edge-not-forward) nil)
                           (< k cursor) (do (note! id :duplicate-child-edge) nil)
                           (> k cursor) (do (note! id :children-not-preorder) nil)
                           :else (when-let [next-id (walk k)]
                                   (recur next-id (next kids)))))))
                   (inc id))))
        end (walk 0)]
    (when (and (empty? @bad) (not= end total))
      (note! 0 :unreachable-nodes))
    @bad))


(defn- structure-violations
  [packet rule-names]
  (let [meta (metadata-violations packet)]
    (cond
      (seq meta) meta
      (= :yang.cst/ok (:yang.cst/outcome packet))
      (let [nodes (:yang.cst/nodes packet)]
        (if-not (and (vector? nodes) (pos? (count nodes)))
          [{:violation :no-nodes}]
          (let [fields (node-field-violations nodes rule-names)]
            (if (seq fields) fields (edge-violations nodes)))))
      :else (error-violations packet))))


(defn- source-index
  [source]
  (let [cps (code-points source)
        offsets (byte-offsets cps)]
    {:cps cps, :total (peek offsets), :at (zipmap offsets (range))}))


(defn- span-violation
  "Why `span` is not a byte span of the source, or nil: it must lie in
   [0, total] and begin and end on a UTF-8 character boundary."
  [{:keys [total at]} [a b]]
  (cond
    (not (<= 0 a b total)) :span-out-of-range
    (not (and (contains? at a) (contains? at b))) :span-not-on-utf8-boundary))


(defn- token-violation
  "Why a token record disagrees with the source, or nil. A real token's text
   is the source at its span and its type may not be one the policy always
   synthesizes; a synthetic token is permitted by the policy for its type, is
   zero-width, sits at the end of the source when the policy says so, and
   carries the canonical text."
  [{:keys [cps total at]} n]
  (let [[a b] (:span n)
        policy (get synthetic-policy (:type n))]
    (if (:synthetic n)
      (cond
        (nil? policy) :synthetic-not-permitted
        (not= a b) :synthetic-not-zero-width
        (and (= :at-end (:when policy)) (not= a total)) :synthetic-not-at-end
        (not= (:text policy) (:text n)) :synthetic-text-not-canonical)
      (cond
        (= :always (:when policy)) :real-token-of-synthetic-type
        (not= (subvec cps (at a) (at b)) (code-points (:text n)))
        :token-text-not-source))))


(defn- source-violations
  "Invariants 3-5 against `source`: spans are byte spans on character
   boundaries, tokens are in document order, token text and synthetic tokens
   follow the policy, and a rule's span covers its real children."
  [packet source]
  (let [idx (source-index source)]
    (if-not (= :yang.cst/ok (:yang.cst/outcome packet))
      (into []
            (keep-indexed
              (fn [i e]
                (when-let [why (and (:span e) (span-violation idx (:span e)))]
                  {:error i, :violation why})))
            (:yang.cst/errors packet))
      (let [nodes (:yang.cst/nodes packet)
            bad (volatile! [])
            note! (fn [id why] (vswap! bad conj {:id id, :violation why}))
            last-end (volatile! 0)]
        (doseq [n nodes
                :let [id (:id n)
                      [a b] (:span n)
                      span-why (span-violation idx [a b])]]
          (if span-why
            (note! id span-why)
            (when (= :token (:kind n))
              (when-let [why (token-violation idx n)] (note! id why))
              (when (< a @last-end) (note! id :token-overlaps-previous))
              (when (and (not (:synthetic n)) (< a b)) (vreset! last-end b)))))
        (doseq [n nodes
                :when (= :rule (:kind n))
                :let [real (keep (fn [k]
                                   (let [c (nth nodes k)
                                         [a b] (:span c)]
                                     (when (and (not (:synthetic c)) (< a b))
                                       [a b])))
                                 (:children n))
                      [a b] (:span n)]]
          (when (and (seq real)
                     (not= [a b] [(apply min (map first real))
                                  (apply max (map second real))]))
            (note! (:id n) :rule-span-not-children))
          (when (and (empty? real) (not= a b))
            (note! (:id n) :empty-rule-not-zero-width)))
        @bad))))


(defn violations
  "Every way `packet` breaks the contract, as a vector of
   {:violation kw ...} (empty when it conforms). The structure is checked
   first; the source-dependent checks run only on a well-formed packet and
   only when `:source` is given. `opts`: `:source` the sealed source text,
   `:rule-names` the grammar's rule-name set."
  [packet {:keys [source rule-names]}]
  (let [structural (structure-violations packet rule-names)]
    (if (or (seq structural) (nil? source))
      structural
      (source-violations packet source))))


(defn validate!
  "Return the packet when it keeps the contract (`violations`); otherwise
   throw a qualified malformed-cst error carrying the first violation and
   all of them. Without `opts` the check is structural; with `:source` it is
   source-aware."
  ([packet] (validate! packet nil))
  ([packet opts]
   (let [bad (violations packet opts)]
     (when (seq bad)
       (malformed! (str "CST packet violates its contract: "
                        (name (:violation (first bad))))
                   (assoc (select-keys (first bad) [:id :error])
                          :violations bad)))
     packet)))


(defn node
  [packet id]
  (nth (:yang.cst/nodes packet) id))


(defn root
  [packet]
  (node packet (:yang.cst/root packet)))


(defn children
  [packet n]
  (mapv #(node packet %) (:children n)))


(defn rule?
  ([n] (= :rule (:kind n)))
  ([n rule-name] (and (= :rule (:kind n)) (= rule-name (:rule n)))))


(defn token?
  ([n] (= :token (:kind n)))
  ([n text] (and (= :token (:kind n)) (= text (:text n)))))


(defn child-rules
  "The rule children of `n` named `rule-name`, in order."
  [packet n rule-name]
  (filterv #(rule? % rule-name) (children packet n)))


(defn has-token?
  [packet n text]
  (boolean (some #(token? % text) (children packet n))))


;; =============================================================================
;; Conformance, the per-target oracle
;; =============================================================================

(defn conform
  "Check a packet against its `source` text; return a vector of violations
   (empty when it conforms): the structure, then spans on UTF-8 boundaries,
   token text equal to the source, synthetic tokens only where the policy
   allows, ordered terminals and rule extents over real children."
  ([packet source] (conform packet source nil))
  ([packet source opts] (violations packet (assoc opts :source source))))
