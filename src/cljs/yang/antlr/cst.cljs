(ns yang.antlr.cst
  "The Node host's parser interpreter (docs/design/yang.antlr.md §4.3, §5.5):
   the same public names as the JVM's `yang.antlr.cst` over the antlr4 npm
   runtime. A grammar profile supplies the host constructors:

     {:grammar-id  string naming grammar revision and tool
      :make-lexer  (fn [CharStream] Lexer)
      :make-parser (fn [TokenStream] Parser)
      :start       (fn [Parser] ParserRuleContext)}

   No ANTLR object leaves this namespace: a packet is plain data, in the
   target-neutral shape `yang.antlr.packet` owns (the IR). The host-free half
   of the export lives in `yang.antlr.export`; this file holds the Node
   walk. `CharStreams.fromString` opens a code-point stream, the unit the JVM
   indexes."
  (:require
    ["antlr4" :default antlr4]
    [yang.antlr.export :as export]
    [yang.antlr.packet :as packet]))


;; Depending on how the host binds the package, the `atn`/`dfa` namespaces
;; sit at top level or only under `default`; the generated parsers use the
;; same fallback.
(def ^:private antlr4-default (or (.-default antlr4) antlr4))


(defn- char-stream
  [source]
  (.fromString (.-CharStreams antlr4) source))


;; =============================================================================
;; Worker: exclusively owned recognizer caches (§4.3)
;; =============================================================================

(defn- fresh-dfa
  [atn]
  (.map (.-decisionToState atn)
        (fn [state i] (new (.. antlr4-default -dfa -DFA) state i))))


(defn make-worker
  "A host worker for `profile` owning its own DFA and prediction-context
   caches, so no parse shares mutable recognizer state with another worker.
   The worker is host state owned by one parser stage; it is not data and
   never crosses a stream."
  [profile]
  (let [lexer ((:make-lexer profile) (char-stream ""))
        parser ((:make-parser profile) (new (.-CommonTokenStream antlr4) lexer))
        lexer-atn (.. lexer -_interp -atn)
        parser-atn (.. parser -_interp -atn)]
    {:profile profile,
     :lexer-atn lexer-atn,
     :lexer-dfa (fresh-dfa lexer-atn),
     :lexer-cache (new (.. antlr4-default -atn -PredictionContextCache)),
     :parser-atn parser-atn,
     :parser-dfa (fresh-dfa parser-atn),
     :parser-cache (new (.. antlr4-default -atn -PredictionContextCache))}))


(defn- own-lexer!
  [lexer {:keys [lexer-atn lexer-dfa lexer-cache]}]
  (set! (.-_interp lexer)
        (new (.. antlr4-default -atn -LexerATNSimulator)
             lexer lexer-atn lexer-dfa lexer-cache)))


(defn- own-parser!
  [parser {:keys [parser-atn parser-dfa parser-cache]}]
  (set! (.-_interp parser)
        (new (.. antlr4-default -atn -ParserATNSimulator)
             parser parser-atn parser-dfa parser-cache)))


;; =============================================================================
;; Export
;; =============================================================================

(defn- token?
  [t]
  (and (some? t) (number? (.-start t)) (number? (.-stop t))))


(defn- listener
  "An error listener whose `syntaxError` is `f`."
  [f]
  (let [l (new (.. antlr4-default -error -ErrorListener))]
    (set! (.-syntaxError l) f)
    l))


(defn- error-listener
  "Collects syntax errors as data into `errors` (a volatile)."
  [errors offsets]
  (listener
    (fn [recognizer offending line column msg _e]
      (let [span (cond
                   (token? offending)
                   (export/token-span offsets (.-start offending)
                                      (.-stop offending))

                   (instance? (.-Lexer antlr4) recognizer)
                   [(export/offset-at offsets (._tokenStartCharIndex recognizer))
                    (export/offset-at offsets (.. recognizer -_input -index))]

                   :else nil)]
        (vswap! errors conj (export/syntax-error msg line column span))))))


(defn- ops
  [parser]
  (let [rule-names (.-ruleNames parser)
        symbolic (.-symbolicNames parser)
        literal (.-literalNames parser)]
    {:token? (fn [t] (instance? (.. antlr4-default -tree -TerminalNode) t)),
     :token (fn [t]
              (let [tok (.-symbol t)
                    type (.-type tok)]
                {:type-name (if (= type -1)
                              "EOF"
                              (or (aget symbolic type)
                                  (aget literal type)
                                  (str type))),
                 :start (.-start tok),
                 :stop (.-stop tok),
                 :text (.-text tok)})),
     :rule-name (fn [ctx] (aget rule-names (.-ruleIndex ctx))),
     :children (fn [ctx] (vec (.-children ctx))),
     :start (fn [ctx] (some-> (.-start ctx) (.-start)))}))


(defn parse-unit
  "Parse one sealed unit's `source` with `worker`; return its CST packet."
  [worker unit source]
  (let [{:keys [profile]} worker
        cps (packet/code-points source)
        offsets (export/byte-offsets cps)
        errors (volatile! [])
        l (error-listener errors offsets)
        lexer ((:make-lexer profile) (char-stream source))
        _ (own-lexer! lexer worker)
        _ (.removeErrorListeners lexer)
        _ (.addErrorListener lexer l)
        parser ((:make-parser profile) (new (.-CommonTokenStream antlr4) lexer))
        _ (own-parser! parser worker)
        _ (.removeErrorListeners parser)
        _ (.addErrorListener parser l)
        tree ((:start profile) parser)]
    (export/result profile unit @errors
                   #(export/export-nodes (ops parser) cps offsets tree))))


(defn parse-transform
  "The parser stage's transform over source events (`packet/transform`)."
  [worker]
  (packet/transform parse-unit worker))


(defn utf8-bytes
  "The UTF-8 byte count of a string: span ends are checked against it."
  [s]
  (packet/utf8-bytes s))


(defn first-failure
  "Where the grammar first refuses `source` with `worker`: nil when it
   parses, `:end` when the first error failed on an end-of-input token, else
   `:early` (`export/failure-kind` decides). See the JVM namespace."
  [worker source]
  (let [{:keys [profile]} worker
        failure (volatile! nil)
        lexer ((:make-lexer profile) (char-stream source))
        tokens (new (.-CommonTokenStream antlr4) lexer)
        l (listener
            (fn [_recognizer offending _line _column _msg e]
              (when-not @failure
                (vreset! failure
                         (cond
                           (not (token? offending)) :lexical
                           (instance? (.. antlr4-default -error -NoViableAltException) e)
                           (dec (.-length (.-tokens tokens)))
                           :else (.-tokenIndex offending))))))
        _ (own-lexer! lexer worker)
        _ (.removeErrorListeners lexer)
        _ (.addErrorListener lexer l)
        parser ((:make-parser profile) tokens)
        _ (own-parser! parser worker)
        _ (.removeErrorListeners parser)
        _ (.addErrorListener parser l)]
    ((:start profile) parser)
    (export/failure-kind
      @failure
      (keep (fn [t]
              (when (not= -1 (.-type t))
                {:index (.-tokenIndex t), :start (.-start t), :stop (.-stop t)}))
            (.-tokens tokens)))))
