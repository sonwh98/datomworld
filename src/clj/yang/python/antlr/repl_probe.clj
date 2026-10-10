(ns yang.python.antlr.repl-probe
  "The Python ANTLR frontend's completeness probe (docs/design/yang.antlr.md
   section 8.5.6, slice F3; JVM only, as the parser is). `probe` answers
   whether the text typed so far is a whole submission:

   - `:complete`: the text parses, or it is wrong in a way more lines cannot
     mend (the shell then reports the syntax error);
   - `:incomplete`: more lines can still finish it.

   Two passes decide. A lexical `scan` follows short and triple-quoted
   strings with their escapes, comments, typed bracket nesting and explicit
   backslash continuation: a string still open waits for more lines unless
   the text, with that string standing as an empty one, already fails on
   or before it; a bracket or continuation still open waits too (a blank
   line among them is legal Python), and a closer that matches no opener
   is an error at once. Then
   the parser runs, and its first error is classified by the token it
   failed on: only a failure on the end-of-input tokens (EOF, and the
   NEWLINE and DEDENT the lexer synthesizes there) is mendable by more
   lines; any earlier token is not, so the error is reported even under an
   open bracket. With brackets, strings and continuations closed, a blank
   line ends the submission, and text that parses waits only while its
   last logical line is indented: as in CPython's REPL, a blank line closes
   a block."
  (:require
    [clojure.string :as str]
    [yang.python.antlr.parser :as parser])
  (:import
    (org.antlr.v4.runtime
      ANTLRErrorListener
      BaseErrorListener
      CharStreams
      CommonTokenStream
      Lexer
      NoViableAltException
      Parser
      Token)
    (org.antlr.v4.runtime.atn
      ATN
      LexerATNSimulator
      ParserATNSimulator
      PredictionContextCache)))


;; =============================================================================
;; Lexical scan
;; =============================================================================

(def ^:private opener-of
  {\) \(, \] \[, \} \{})


(defn- string-end
  "The index just past the string literal whose body starts at `i`, quoted
   by `q`, tripled when `triple?`: `:open` when more lines can still close
   it (a triple string, or a short string whose line ends in a backslash),
   `:broken` when a short string's line ends without one. A backslash
   escapes the next character, a newline included, in raw strings too."
  [^String text i q triple?]
  (let [n (count text)
        closer (if triple? (str q q q) (str q))]
    (loop [i i]
      (if (>= i n)
        (if triple? :open :broken)
        (let [c (.charAt text i)]
          (cond
            (= \\ c) (if (< (inc i) n) (recur (+ i 2)) :open)
            (.startsWith text closer (int i)) (+ i (count closer))
            (and (not triple?) (= \newline c)) :broken
            :else (recur (inc i))))))))


(defn scan
  "The lexical state at the end of `text`:

     {:brackets [opener ...]   ; still open, innermost last
      :block? b}               ; the last logical line is indented

   plus `:string :open` when the text ends inside a string (`:string-at`
   its opening quote), `:continued
   true` when it ends on an explicit backslash continuation, or `:error`
   (`:bracket`, `:string`) for a closer with no matching opener or a short
   string broken by a newline; the scan stops there."
  [^String text]
  (let [n (count text)]
    ;; line-begin: where the current logical line's physical line began;
    ;; line?: no code yet on the current logical line
    (loop [i 0
           stack []
           line-begin 0
           line? true
           block? false]
      (let [state {:brackets stack, :block? block?}
            code (fn [stack]
                   [stack (if line? (> i line-begin) block?)])]
        (if (>= i n)
          state
          (let [c (.charAt text i)]
            (cond
              (= \# c)
              (let [eol (str/index-of text "\n" i)]
                (recur (or eol n) stack line-begin line? block?))

              (= \newline c)
              (if (empty? stack)
                (recur (inc i) stack (inc i) true block?)
                (recur (inc i) stack line-begin line? block?))

              (or (= \" c) (= \' c))
              (let [triple? (.startsWith text (str c c c) (int i))
                    end (string-end text (+ i (if triple? 3 1)) c triple?)
                    [stack block?] (code stack)]
                (case end
                  :open (assoc state :string :open :string-at i)
                  :broken (assoc state :error :string)
                  (recur end stack line-begin false block?)))

              (= \\ c)
              (cond
                (= (inc i) n) (assoc state :continued true)
                (= \newline (.charAt text (inc i)))
                (recur (+ i 2) stack line-begin line? block?)
                :else (let [[stack block?] (code stack)]
                        (recur (inc i) stack line-begin false block?)))

              (contains? #{\( \[ \{} c)
              (let [[stack block?] (code stack)]
                (recur (inc i) (conj stack c) line-begin false block?))

              (contains? opener-of c)
              (if (= (peek stack) (opener-of c))
                (let [[stack block?] (code stack)]
                  (recur (inc i) (pop stack) line-begin false block?))
                (assoc state :error :bracket))

              (contains? #{\space \tab \formfeed \return} c)
              (recur (inc i) stack line-begin line? block?)

              :else
              (let [[stack block?] (code stack)]
                (recur (inc i) stack line-begin false block?)))))))))


;; =============================================================================
;; Parse: where the first error failed
;; =============================================================================

(defn- first-failure
  "Parse `source` and answer nil when it parses, `:end` when its first
   error failed on an end-of-input token, else `:early`.

   The failing token is the offending token of a mismatch; a
   no-viable-alternative error names the token its prediction began at,
   so for it the failing token is the furthest one the parser had read
   when the error was reported. End-of-input tokens are EOF and the
   zero-width ones after the last token with source text (the lexer's
   EOF can carry a span, so it is excluded by type). A lexer error has no
   token and is `:early`."
  [^String source]
  (let [{:keys [profile lexer-atn lexer-dfa lexer-cache parser-atn
                parser-dfa parser-cache]}
        (parser/make-worker)
        far (volatile! -1)
        failure (volatile! nil)
        lexer ^Lexer ((:make-lexer profile) (CharStreams/fromString source))
        tokens (proxy [CommonTokenStream] [lexer]
                 (LT
                   [k]
                   (let [^Token t (proxy-super LT k)]
                     (when t (vswap! far max (.getTokenIndex t)))
                     t)))
        listener (proxy [BaseErrorListener] []
                   (syntaxError
                     [_recognizer offending _line _column _msg e]
                     (when-not @failure
                       (vreset! failure
                                (cond
                                  (not (instance? Token offending)) :lexical
                                  (instance? NoViableAltException e) @far
                                  :else (.getTokenIndex ^Token offending))))))
        _ (.setInterpreter
            lexer
            (LexerATNSimulator.
              lexer
              ^ATN lexer-atn
              ^"[Lorg.antlr.v4.runtime.dfa.DFA;" lexer-dfa
              ^PredictionContextCache lexer-cache))
        _ (.removeErrorListeners lexer)
        _ (.addErrorListener lexer ^ANTLRErrorListener listener)
        parser ^Parser ((:make-parser profile) tokens)
        _ (.setInterpreter
            parser
            (ParserATNSimulator.
              parser
              ^ATN parser-atn
              ^"[Lorg.antlr.v4.runtime.dfa.DFA;" parser-dfa
              ^PredictionContextCache parser-cache))
        _ (.removeErrorListeners parser)
        _ (.addErrorListener parser ^ANTLRErrorListener listener)]
    ((:start profile) parser)
    (let [index @failure
          last-real (->> (.getTokens ^CommonTokenStream tokens)
                         (keep (fn [^Token t]
                                 (when (and (not= Token/EOF (.getType t))
                                            (<= (.getStartIndex t)
                                                (.getStopIndex t)))
                                   (.getTokenIndex t))))
                         (reduce max -1))]
      (cond
        (nil? index) nil
        (and (int? index) (> index last-real)) :end
        :else :early))))


;; =============================================================================
;; Probe
;; =============================================================================

(defn- last-line-blank?
  [text]
  (str/blank? (peek (str/split text #"\n" -1))))


(defn probe
  [text]
  (let [{:keys [error string string-at continued brackets block?]} (scan text)
        open? (boolean (or continued (seq brackets)))]
    (cond
      error :complete
      ;; the open string stands as an empty one: an error on it or before
      ;; it is not mended by more lines
      string (if (= :early (first-failure (str (subs text 0 string-at)
                                               "''\n")))
               :complete
               :incomplete)
      (and (not open?) (last-line-blank? text)) :complete
      :else (case (first-failure (str text "\n"))
              nil (if (or open? block?) :incomplete :complete)
              :end :incomplete
              :early :complete))))
