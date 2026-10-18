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

   No ANTLR object leaves this namespace: a packet is plain data, in the
   target-neutral shape `yang.antlr.packet` owns (the IR). The host-free half
   of the export lives in `yang.antlr.export`; this file holds the JVM walk."
  (:require
    [yang.antlr.export :as export]
    [yang.antlr.packet :as packet])
  (:import
    (org.antlr.v4.runtime
      ANTLRErrorListener
      BaseErrorListener
      CharStreams
      CommonTokenStream
      Lexer
      NoViableAltException
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
      TerminalNode)))


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
                   (let [^Token t offending]
                     (export/token-span offsets (.getStartIndex t)
                                        (.getStopIndex t)))
                   (if (instance? Lexer recognizer)
                     (let [^Lexer lx recognizer]
                       [(export/offset-at offsets (._tokenStartCharIndex lx))
                        (export/offset-at offsets
                                          (.index (.getInputStream lx)))])
                     nil))]
        (vswap! errors conj (export/syntax-error msg line column span))))))


(defn- ops
  [^Parser parser]
  (let [rule-names (.getRuleNames parser)
        vocab (.getVocabulary parser)]
    {:token? (fn [t] (instance? TerminalNode t)),
     :token (fn [t]
              (let [tok (.getSymbol ^TerminalNode t)
                    type (.getType tok)]
                {:type-name (if (= type Token/EOF)
                              "EOF"
                              (or (.getSymbolicName vocab type)
                                  (.getLiteralName vocab type)
                                  (str type))),
                 :start (.getStartIndex tok),
                 :stop (.getStopIndex tok),
                 :text (.getText tok)})),
     :rule-name (fn [ctx]
                  (aget rule-names (.getRuleIndex ^ParserRuleContext ctx))),
     :children (fn [^ParserRuleContext ctx]
                 (mapv #(.getChild ctx (int %))
                       (range (.getChildCount ctx)))),
     :start (fn [^ParserRuleContext ctx]
              (some-> (.getStart ctx)
                      (.getStartIndex)))}))


(defn parse-unit
  "Parse one sealed unit's `source` with `worker`; return its CST packet."
  [worker unit ^String source]
  (let [{:keys [profile]} worker
        cps (packet/code-points source)
        offsets (export/byte-offsets cps)
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
        tree ((:start profile) parser)]
    (export/result profile unit @errors
                   #(export/export-nodes (ops parser) cps offsets tree))))


(defn parse-transform
  "The parser stage's transform over source events (`packet/transform`)."
  [worker]
  (packet/transform parse-unit worker))


(defn utf8-bytes
  "The UTF-8 byte count of a string: span ends are checked against it."
  [^String s]
  (packet/utf8-bytes s))


(defn first-failure
  "Where the grammar first refuses `source` with `worker`: nil when it
   parses, `:end` when the first error failed on an end-of-input token, else
   `:early` (`export/failure-kind` decides).

   The failing token is the offending token of a mismatch; for a
   no-viable-alternative error the JVM runtime lazy-fills its token
   buffer, so the buffer's last token is the furthest the parser had read
   when the error was reported (the JS and Dart runtimes fill to EOF and
   use the listener's offending token instead — their hosts' cst
   namespaces carry that rationale). A lexer error has no token."
  [worker source]
  (let [{:keys [profile lexer-atn lexer-dfa lexer-cache parser-atn
                parser-dfa parser-cache]}
        worker
        failure (volatile! nil)
        lexer ^Lexer ((:make-lexer profile) (CharStreams/fromString source))
        tokens (CommonTokenStream. lexer)
        listener (proxy [BaseErrorListener] []
                   (syntaxError
                     [_recognizer offending _line _column _msg e]
                     (when-not @failure
                       (vreset! failure
                                (cond
                                  (not (instance? Token offending)) :lexical
                                  (instance? NoViableAltException e)
                                  (dec (count (.getTokens tokens)))
                                  :else (.getTokenIndex ^Token offending))))))
        _ (.setInterpreter
            lexer
            (LexerATNSimulator. lexer
                                ^ATN lexer-atn
                                ^"[Lorg.antlr.v4.runtime.dfa.DFA;" lexer-dfa
                                ^PredictionContextCache lexer-cache))
        _ (.removeErrorListeners lexer)
        _ (.addErrorListener lexer ^ANTLRErrorListener listener)
        parser ^Parser ((:make-parser profile) tokens)
        _ (.setInterpreter
            parser
            (ParserATNSimulator. parser
                                 ^ATN parser-atn
                                 ^"[Lorg.antlr.v4.runtime.dfa.DFA;" parser-dfa
                                 ^PredictionContextCache parser-cache))
        _ (.removeErrorListeners parser)
        _ (.addErrorListener parser ^ANTLRErrorListener listener)]
    ((:start profile) parser)
    (export/failure-kind
      @failure
      (keep (fn [^Token t]
              (when (not= Token/EOF (.getType t))
                {:index (.getTokenIndex t),
                 :start (.getStartIndex t),
                 :stop (.getStopIndex t)}))
            (.getTokens tokens)))))
