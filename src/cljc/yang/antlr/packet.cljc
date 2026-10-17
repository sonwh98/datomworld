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

   Invariants every target's exporter must meet (`conform` checks 2-5):

     1. ids are preorder indexes; :root is 0; child edges form a tree
        (`validate!`).
     2. :rule is the grammar rule name from the parser's rule-name table,
        never a target class name; :type is the symbolic token name, else the
        literal name, else the decimal type, \"EOF\" for EOF.
     3. :span is half-open UTF-8 byte offsets into the sealed source. Each
        target converts its own index unit at the boundary (the JVM indexes
        code points; the JS and Dart streams are opened in code-point mode).
     4. A token is :synthetic with a zero-width span when its text is not the
        source at its span or it overlaps the previous real token.
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


(defn validate!
  "Return the packet when its nodes form one tree rooted at 0 with preorder
   ids equal to their index and every child referenced exactly once, after
   its parent; otherwise throw a qualified malformed-cst error."
  [packet]
  (let [nodes (:yang.cst/nodes packet)
        n (count nodes)]
    (when-not (and (vector? nodes) (pos? n) (= 0 (:yang.cst/root packet)))
      (malformed! "CST packet has no root" {}))
    (loop [i 0
           seen #{}]
      (if (< i n)
        (let [node (nth nodes i)
              kids (:children node)]
          (when-not (= i (:id node))
            (malformed! "CST node id is not its index" {:index i}))
          (when-not (contains? #{:rule :token} (:kind node))
            (malformed! "CST node has an unknown kind" {:id i}))
          (doseq [k kids]
            (when-not (and (integer? k) (< i k n) (not (contains? seen k)))
              (malformed! "CST child edge is not a tree edge"
                          {:parent i, :child k})))
          (recur (inc i) (into seen kids)))
        (when-not (= (dec n) (count seen))
          (malformed! "CST has nodes unreachable from the root"
                      {:reachable (count seen), :nodes n}))))
    packet))


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
;; Conformance (invariants 3-5), the per-target oracle
;; =============================================================================

(defn conform
  "Check an ok packet against its `source` text; return a vector of
   violations (empty when it conforms). Spans stay inside [0, utf8 length],
   are ordered, synthetic tokens are zero-width, real tokens do not overlap
   the previous real token, and a rule's span covers its real children."
  [packet source]
  (let [total (utf8-bytes source)
        nodes (:yang.cst/nodes packet)
        bad (volatile! [])
        note! (fn [id why] (vswap! bad conj {:id id, :violation why}))
        last-end (volatile! 0)]
    (doseq [n nodes
            :let [id (:id n)
                  [a b] (:span n)]]
      (when-not (and (integer? a) (integer? b) (<= 0 a b total))
        (note! id :span-out-of-range))
      (when (= :token (:kind n))
        (if (:synthetic n)
          (when-not (= a b) (note! id :synthetic-not-zero-width))
          (do (when (< a @last-end) (note! id :token-overlaps-previous))
              (when (and (integer? b) (< a b)) (vreset! last-end b))))))
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
    @bad))
