(ns yang.antlr.cst
  "The host parser interpreter's generic half (docs/design/yang.antlr.md §4.3,
   §5.1, §5.5): assemble sealed source units, parse each with an ANTLR
   grammar profile, and export one portable CST packet per unit.

   Nothing here knows a language. A grammar profile supplies the host
   constructors:

     {:grammar-id  string naming grammar revision and tool
      :make-lexer  (fn [CharStream] Lexer)
      :make-parser (fn [TokenStream] Parser)
      :start       (fn [Parser] ParserRuleContext)}

   No ANTLR object leaves this namespace: a packet is plain data.

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
   program."
  (:import
    (java.nio.charset
      StandardCharsets)
    (org.antlr.v4.runtime
      BaseErrorListener
      CharStreams
      CommonTokenStream
      Lexer
      Parser
      ParserRuleContext
      Recognizer
      Token)
    (org.antlr.v4.runtime.atn
      ATN
      LexerATNSimulator
      ParserATNSimulator
      PredictionContextCache)
    (org.antlr.v4.runtime.dfa
      DFA)
    (org.antlr.v4.runtime.tree
      ParseTree
      TerminalNode)))


(def packet-version 1)


;; =============================================================================
;; Source coordinates
;; =============================================================================

(defn- utf8-length
  [cp]
  (cond (< cp 0x80) 1
        (< cp 0x800) 2
        (< cp 0x10000) 3
        :else 4))


(defn- byte-offsets
  "Byte offset of each code point index of `s`, plus the total at index n.
   ANTLR's CodePointCharStream indexes code points."
  ^longs [^String s]
  (let [cps (.toArray (.codePoints s))
        n (alength cps)
        out (long-array (inc n))]
    (loop [i 0
           acc 0]
      (aset out i acc)
      (when (< i n)
        (recur (inc i) (+ acc (utf8-length (aget cps i))))))
    out))


(defn- offset-at
  [^longs offsets i]
  (aget offsets (max 0 (min i (dec (alength offsets))))))


;; =============================================================================
;; Worker: exclusively owned recognizer caches (§4.3)
;; =============================================================================

(defn- fresh-dfa
  ^"[Lorg.antlr.v4.runtime.dfa.DFA;" [^ATN atn]
  (into-array DFA
              (map (fn [i] (DFA. (.getDecisionState atn i) i))
                   (range (.getNumberOfDecisions atn)))))


(defn make-worker
  "A host worker for `profile` owning its own DFA and prediction-context
   caches, so no parse shares mutable recognizer state with another worker.
   The worker is host state owned by one parser stage; it is not data and
   never crosses a stream."
  [profile]
  (let [lexer ^Lexer ((:make-lexer profile) (CharStreams/fromString ""))
        parser ^Parser ((:make-parser profile)
                        (CommonTokenStream. lexer))
        lexer-atn (.getATN lexer)
        parser-atn (.getATN parser)]
    {:profile profile,
     :lexer-atn lexer-atn,
     :lexer-dfa (fresh-dfa lexer-atn),
     :lexer-cache (PredictionContextCache.),
     :parser-atn parser-atn,
     :parser-dfa (fresh-dfa parser-atn),
     :parser-cache (PredictionContextCache.)}))


;; =============================================================================
;; Export
;; =============================================================================

(defn- error-listener
  "Collects syntax errors as data into `errors` (a volatile)."
  [errors offsets]
  (proxy [BaseErrorListener] []
    (syntaxError
      [^Recognizer recognizer offending line column msg _e]
      (let [span (if (instance? Token offending)
                   (let [^Token t offending
                         s (offset-at offsets (.getStartIndex t))
                         e (if (< (.getStopIndex t) (.getStartIndex t))
                             s
                             (offset-at offsets (inc (.getStopIndex t))))]
                     [s e])
                   (if (instance? Lexer recognizer)
                     (let [^Lexer lx recognizer]
                       [(offset-at offsets (._tokenStartCharIndex lx))
                        (offset-at offsets (.index (.getInputStream lx)))])
                     nil))]
        (vswap! errors conj
                {:kind :syntax-error,
                 :message (str msg),
                 :line line,
                 :column column,
                 :span span})))))


(defn- token-record
  "`last-stop` is the stop index of the previous real token: a token that
   overlaps it was produced by a helper, not read from the source (the
   Python helper's DEDENT reports the newline it follows as its text)."
  [^Parser parser ^String source ^longs offsets last-stop id ^Token t]
  (let [type (.getType t)
        vocab (.getVocabulary parser)
        type-name (if (= type Token/EOF)
                    "EOF"
                    (or (.getSymbolicName vocab type)
                        (.getLiteralName vocab type)
                        (str type)))
        start (.getStartIndex t)
        stop (.getStopIndex t)
        text (.getText t)
        n (dec (alength offsets))
        real-text (when (and (<= 0 start) (<= start stop) (< stop n))
                    (let [s (.offsetByCodePoints source 0 start)
                          e (.offsetByCodePoints source 0 (inc stop))]
                      (subs source s e)))
        synthetic? (or (not= real-text text) (<= start last-stop))
        span (if synthetic?
               (let [p (offset-at offsets (inc stop))] [p p])
               [(offset-at offsets start) (offset-at offsets (inc stop))])]
    (cond-> {:id id, :kind :token, :type type-name, :text text, :span span}
      synthetic? (assoc :synthetic true))))


(defn- export-nodes
  "Preorder records for the parse tree; a record's id is its index."
  [^Parser parser source offsets ^ParseTree root]
  (let [rule-names (.getRuleNames parser)
        out (volatile! [])
        last-stop (volatile! -1)]
    (letfn [(walk
              [^ParseTree t]
              (let [id (count @out)]
                (if (instance? TerminalNode t)
                  (let [tok (.getSymbol ^TerminalNode t)
                        record (token-record parser source offsets @last-stop
                                             id tok)]
                    (when-not (:synthetic record)
                      (vreset! last-stop (.getStopIndex tok)))
                    (vswap! out conj record))
                  (let [^ParserRuleContext ctx t]
                    (vswap! out conj nil)
                    (let [kids (mapv #(walk (.getChild ctx (int %)))
                                     (range (.getChildCount ctx)))
                          records @out
                          ;; a rule covers its real text: synthetic tokens
                          ;; and empty rules do not stretch it
                          child-spans (keep (fn [k]
                                              (let [r (nth records k)
                                                    [a b] (:span r)]
                                                (when (and (not (:synthetic r))
                                                           (< a b))
                                                  [a b])))
                                            kids)
                          start-tok (.getStart ctx)
                          s (if start-tok
                              (offset-at offsets (.getStartIndex start-tok))
                              0)
                          span (if (seq child-spans)
                                 [(apply min (map first child-spans))
                                  (apply max (map second child-spans))]
                                 [s s])]
                      (vswap! out assoc id
                              {:id id,
                               :kind :rule,
                               :rule (aget rule-names (.getRuleIndex ctx)),
                               :children kids,
                               :span span}))))
                id))]
      (walk root)
      @out)))


(defn parse-unit
  "Parse one sealed unit's `source` with `worker`; return its CST packet."
  [worker unit ^String source]
  (let [{:keys [profile]} worker
        offsets (byte-offsets source)
        errors (volatile! [])
        listener (error-listener errors offsets)
        lexer ^Lexer ((:make-lexer profile) (CharStreams/fromString source))
        _ (.setInterpreter lexer
                           (LexerATNSimulator. lexer
                                               ^ATN (:lexer-atn worker)
                                               ^"[Lorg.antlr.v4.runtime.dfa.DFA;"
                                               (:lexer-dfa worker)
                                               ^PredictionContextCache
                                               (:lexer-cache worker)))
        _ (.removeErrorListeners lexer)
        _ (.addErrorListener lexer listener)
        parser ^Parser ((:make-parser profile) (CommonTokenStream. lexer))
        _ (.setInterpreter parser
                           (ParserATNSimulator. parser
                                                ^ATN (:parser-atn worker)
                                                ^"[Lorg.antlr.v4.runtime.dfa.DFA;"
                                                (:parser-dfa worker)
                                                ^PredictionContextCache
                                                (:parser-cache worker)))
        _ (.removeErrorListeners parser)
        _ (.addErrorListener parser listener)
        tree ((:start profile) parser)
        base {:yang.cst/version packet-version,
              :yang.cst/unit unit,
              :yang.cst/grammar (:grammar-id profile),
              :yang.cst/complete true}]
    (if (seq @errors)
      (assoc base
             :yang.cst/outcome :yang.cst/syntax-error
             :yang.cst/errors @errors)
      (assoc base
             :yang.cst/outcome :yang.cst/ok
             :yang.cst/root 0
             :yang.cst/nodes (export-nodes parser source offsets tree)))))


;; =============================================================================
;; Source units (§5.1) and the parser stage transform
;; =============================================================================

(defn- source-error
  [grammar-id unit message data]
  {:yang.cst/version packet-version,
   :yang.cst/unit unit,
   :yang.cst/grammar grammar-id,
   :yang.cst/outcome :yang.cst/source-error,
   :yang.cst/errors [(merge {:kind :source-error, :message message} data)],
   :yang.cst/complete true})


(defn parse-transform
  "The parser stage's transform over source events: chunks accumulate per
   unit by ordinal; a seal with every chunk present assembles the snapshot,
   parses it, and emits one packet on port `:cst`. A conflicting duplicate
   chunk, a seal with chunks missing, or an unknown event emits a
   source-error packet instead. State is `{unit {ordinal text}}`."
  [worker]
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


(defn utf8-bytes
  "The UTF-8 byte count of a string: span ends are checked against it."
  [^String s]
  (alength (.getBytes s StandardCharsets/UTF_8)))
