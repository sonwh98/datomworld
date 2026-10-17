(ns yang.python.antlr.repl-probe
  "The Python ANTLR frontend's completeness probe (docs/design/yang.antlr.md
   section 8.5.6, slice F3; portable: the lexical scan is pure and the one
   parser question goes through `yang.antlr.cst/first-failure`). `probe` answers
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
    [yang.antlr.cst :as cst]
    [yang.python.antlr.parser :as parser]))


;; =============================================================================
;; Lexical scan
;; =============================================================================

(def ^:private opener-of
  {\) \(, \] \[, \} \{})


(defn- char-at
  [text i]
  #?(:cljd (subs text i (inc i))
     :clj (.charAt ^String text (int i))
     :cljs (.charAt text i)))


(defn- starts-at?
  [text prefix i]
  (= prefix (subs text i (min (count text) (+ i (count prefix))))))


(def ^:private blanks #{" " "\t" "\f" "\r"})


(defn- string-end
  "The index just past the string literal whose body starts at `i`, quoted
   by `q`, tripled when `triple?`: `:open` when more lines can still close
   it (a triple string, or a short string whose line ends in a backslash),
   `:broken` when a short string's line ends without one. A backslash
   escapes the next character, a newline included, in raw strings too."
  [text i q triple?]
  (let [n (count text)
        closer (if triple? (str q q q) (str q))]
    (loop [i i]
      (if (>= i n)
        (if triple? :open :broken)
        (let [c (char-at text i)]
          (cond
            (= \\ c) (if (< (inc i) n) (recur (+ i 2)) :open)
            (starts-at? text closer i) (+ i (count closer))
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
  [text]
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
          (let [c (char-at text i)]
            (cond
              (= \# c)
              (let [eol (str/index-of text "\n" i)]
                (recur (or eol n) stack line-begin line? block?))

              (= \newline c)
              (if (empty? stack)
                (recur (inc i) stack (inc i) true block?)
                (recur (inc i) stack line-begin line? block?))

              (or (= \" c) (= \' c))
              (let [triple? (starts-at? text (str c c c) i)
                    end (string-end text (+ i (if triple? 3 1)) c triple?)
                    [stack block?] (code stack)]
                (case end
                  :open (assoc state :string :open :string-at i)
                  :broken (assoc state :error :string)
                  (recur end stack line-begin false block?)))

              (= \\ c)
              (cond
                (= (inc i) n) (assoc state :continued true)
                (= \newline (char-at text (inc i)))
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

              (contains? blanks (str c))
              (recur (inc i) stack line-begin line? block?)

              :else
              (let [[stack block?] (code stack)]
                (recur (inc i) stack line-begin false block?)))))))))


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
      string (if (= :early (cst/first-failure (parser/make-worker) (str (subs text 0 string-at)
                                                                        "''\n")))
               :complete
               :incomplete)
      (and (not open?) (last-line-blank? text)) :complete
      :else (case (cst/first-failure (parser/make-worker) (str text "\n"))
              nil (if (or open? block?) :incomplete :complete)
              :end :incomplete
              :early :complete))))
