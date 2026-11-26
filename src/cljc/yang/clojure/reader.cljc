(ns yang.clojure.reader
  "Frontend-owned, token-aware Clojure source reader (architect ruling
   1795693415000, RULING 2).

   Consumes original Clojure source text directly and materializes numeric
   tokens through `yang.core.numeric-token/materialize :clojure` AT TOKEN
   TIME, before any collection (`{...}`, `#{...}`) or downstream lowering can
   erase kind or coalesce distinct numeric kinds (such as `1` vs `1.0`).

   Explicit lexical coverage:
   - Whitespace (space, tab, CR, LF, FF, comma) and comments (`;`, `#!`)
   - Strings (`\"...\"`) with standard escapes (`\\\"`, `\\\\`, `\\n`, `\\r`,
     `\\t`, `\\b`, `\\f`, `\\uXXXX`, octal `\\NNN`)
   - Character literals (`\\a`, `\\newline`, `\\space`, `\\tab`, `\\backspace`,
     `\\formfeed`, `\\return`, `\\uXXXX`, `\\oNNN`)
   - Numeric tokens (exact integers, hex/octal/Nr radix, N big-int, binary64
     floats including integral floats and floats beyond 2^53, decimal M,
     ratios) via `yang.core.numeric-token`
   - Symbolic values (`##Inf`, `##-Inf`, `##NaN`)
   - Keywords (`:k`, `:ns/k`), symbols, booleans (`true`, `false`), `nil`
   - Collections: lists `(...)`, vectors `[...]`, maps `{...}` (even count
     and duplicate-key checks), sets `#{...}` (duplicate-element check),
     namespaced maps `#:ns{...}`
   - Reader macros: quote `'`, syntax-quote `` ` ``, deref `@`, var `#'`,
     metadata `^:k` / `^Sym` / `^\"s\"` / `^{...}` / `#^...`, unquote `~`,
     unquote-splicing `~@`
   - Dispatch forms: `#{...}`, `#_` (with syntax validation of discarded
     forms), `#'`, `##`, `#\"...\"`, `#(...)` (capture-avoiding, parse-scoped,
     deterministic), `#:ns{...}`, `#^...`
   - Explicit refusals: reader conditionals (`#?`, `#?@`), eval (`#=`),
     unreadable (`#<`), unknown tagged literals (`#tag`), invalid numeric
     tokens, malformed maps/sets/strings/chars, unmatched or unclosed delimiters.

   Zero ambient mutable state: no `*data-readers*`, no `*read-eval*`, no
   evaluation during reading, no global reader monkeypatching."
  (:require [clojure.string :as str]
            [dao.host.integer :as host-int]
            [dao.jing.cbor :as cbor]
            [yang.core.numeric-token :as numeric-token]))


(defn- reader-error!
  ([msg] (reader-error! msg {}))
  ([msg data]
   (throw (ex-info msg (merge {:type :reader-exception
                               :ex-kind :reader-error}
                              data)))))


(defn- char-at
  [^String s ^long i]
  #?(:cljs (.charAt s i)
     :default (.charAt s i)))


(defn- char-code
  [ch]
  #?(:cljs (.charCodeAt ch 0)
     :default (int ch)))


(defn- code->char
  [n]
  #?(:cljs (js/String.fromCharCode n)
     :default (char n)))


(defn- whitespace?
  [ch]
  (let [c (char-code ch)]
    (or (= c 32)   ;; space
        (= c 9)    ;; \t
        (= c 10)   ;; \n
        (= c 13)   ;; \r
        (= c 12)   ;; \f
        (= c 44)))) ;; comma


(defn- digit?
  [ch]
  (let [c (char-code ch)]
    (and (<= 48 c) (<= c 57))))


(defn- hex-digit?
  [ch]
  (let [c (char-code ch)]
    (or (and (<= 48 c) (<= c 57))
        (and (<= 65 c) (<= c 70))
        (and (<= 97 c) (<= c 102)))))


(defn- octal-digit?
  [ch]
  (let [c (char-code ch)]
    (and (<= 48 c) (<= c 55))))


(defn- hex-value
  [s]
  #?(:cljs (js/parseInt s 16)
     :default (Integer/parseInt s 16)))


(defn- octal-value
  [s]
  #?(:cljs (js/parseInt s 8)
     :default (Integer/parseInt s 8)))


(defn- macro-terminating?
  "Characters that terminate a symbol, keyword, or numeric token in Clojure.
   Matches `clojure.tools.reader`: apostrophe (`'`) is valid inside symbols
   and keywords and does not terminate a token."
  [ch]
  (or (whitespace? ch)
      (let [c (char-code ch)]
        (or (= c 40)   ;; (
            (= c 41)   ;; )
            (= c 91)   ;; [
            (= c 93)   ;; ]
            (= c 123)  ;; {
            (= c 125)  ;; }
            (= c 34)   ;; "
            (= c 59)   ;; ;
            (= c 96)   ;; `
            (= c 126)  ;; ~
            (= c 64)   ;; @
            (= c 94)   ;; ^
            (= c 92))))) ;; \


(defn- skip-line-comment
  [s n i]
  (loop [j i]
    (if (>= j n)
      j
      (let [c (char-code (char-at s j))]
        (if (or (= c 10) (= c 13))
          (inc j)
          (recur (inc j)))))))


(defn- skip-ws-and-comments
  [s n i]
  (loop [j i]
    (if (>= j n)
      j
      (let [ch (char-at s j)
            c (char-code ch)]
        (cond
          (whitespace? ch) (recur (inc j))
          (= c 59) (recur (skip-line-comment s n (inc j))) ;; ;
          (and (= c 35) (< (inc j) n) (= 33 (char-code (char-at s (inc j))))) ;; #!
          (recur (skip-line-comment s n (+ j 2)))
          :else j)))))


(defn- read-token
  "Read a non-empty token starting at `i` up to the next terminating char.
   Returns `[token next-i]`."
  [s n i]
  (loop [j i]
    (if (or (>= j n) (macro-terminating? (char-at s j)))
      [(subs s i j) j]
      (recur (inc j)))))


(defn- read-string-lit
  [s n i]
  ;; s[i] is \"
  (let [sb #?(:cljs (js/Array.) :default (StringBuilder.))]
    (loop [j (inc i)]
      (when (>= j n)
        (reader-error! "EOF while reading string" {:index i}))
      (let [ch (char-at s j)
            c (char-code ch)]
        (cond
          (= c 34) ;; closing \"
          [#?(:cljs (.join sb "") :default (.toString sb)) (inc j)]

          (= c 92) ;; backslash escape
          (do
            (when (>= (inc j) n)
              (reader-error! "EOF while reading string escape" {:index j}))
            (let [esc (char-at s (inc j))
                  ec (char-code esc)]
              (cond
                (= ec 116) (do #?(:cljs (.push sb "\t") :default (.append sb \tab)) (recur (+ j 2)))
                (= ec 114) (do #?(:cljs (.push sb "\r") :default (.append sb \return)) (recur (+ j 2)))
                (= ec 110) (do #?(:cljs (.push sb "\n") :default (.append sb \newline)) (recur (+ j 2)))
                (= ec 92)  (do #?(:cljs (.push sb "\\") :default (.append sb \\)) (recur (+ j 2)))
                (= ec 34)  (do #?(:cljs (.push sb "\"") :default (.append sb \")) (recur (+ j 2)))
                (= ec 98)  (do #?(:cljs (.push sb "\b") :default (.append sb \backspace)) (recur (+ j 2)))
                (= ec 102) (do #?(:cljs (.push sb "\f") :default (.append sb \formfeed)) (recur (+ j 2)))
                (= ec 117) ;; \uXXXX
                (do
                  (when (> (+ j 6) n)
                    (reader-error! "Invalid unicode escape in string" {:index j}))
                  (let [hex (subs s (+ j 2) (+ j 6))]
                    (when-not (every? hex-digit? hex)
                      (reader-error! (str "Invalid unicode escape: \\u" hex) {:index j}))
                    (let [ch-out (code->char (hex-value hex))]
                      #?(:cljs (.push sb ch-out) :default (.append sb ch-out))
                      (recur (+ j 6)))))
                (octal-digit? esc)
                (let [end (loop [k (+ j 2) cnt 1]
                            (if (and (< k n) (< cnt 3) (octal-digit? (char-at s k)))
                              (recur (inc k) (inc cnt))
                              k))
                      oct (subs s (inc j) end)
                      val (octal-value oct)]
                  (when (> val 255)
                    (reader-error! (str "Octal escape out of range: \\" oct) {:index j}))
                  (let [ch-out (code->char val)]
                    #?(:cljs (.push sb ch-out) :default (.append sb ch-out))
                    (recur end)))
                :else
                (reader-error! (str "Unsupported escape character: \\" esc) {:index j}))))

          :else
          (do #?(:cljs (.push sb ch) :default (.append sb ch))
              (recur (inc j))))))))


(defn- read-regex-lit
  [s n i]
  ;; s[i] is \" (immediately after #)
  (let [sb #?(:cljs (js/Array.) :default (StringBuilder.))]
    (loop [j (inc i)]
      (when (>= j n)
        (reader-error! "EOF while reading regex" {:index i}))
      (let [ch (char-at s j)
            c (char-code ch)]
        (cond
          (= c 34)
          (let [pat #?(:cljs (.join sb "") :default (.toString sb))]
            [(re-pattern pat) (inc j)])

          (= c 92)
          (do
            (when (>= (inc j) n)
              (reader-error! "EOF while reading regex escape" {:index j}))
            (let [nxt (char-at s (inc j))]
              #?(:cljs (do (.push sb "\\") (.push sb nxt))
                 :default (do (.append sb \\) (.append sb nxt)))
              (recur (+ j 2))))

          :else
          (do #?(:cljs (.push sb ch) :default (.append sb ch))
              (recur (inc j))))))))


(def ^:private named-chars
  {"newline" \newline
   "space" \space
   "tab" \tab
   "backspace" \backspace
   "formfeed" \formfeed
   "return" \return})


(defn- read-char-lit
  [s n i]
  ;; s[i] is \\
  (when (>= (inc i) n)
    (reader-error! "EOF while reading character" {:index i}))
  (let [first-ch (char-at s (inc i))]
    (if (macro-terminating? first-ch)
      [first-ch (+ i 2)]
      (let [[tok next-i] (read-token s n (inc i))]
        [(cond
           (= 1 (count tok)) (char-at tok 0)
           (contains? named-chars tok) (get named-chars tok)
           (str/starts-with? tok "u")
           (let [hex (subs tok 1)]
             (when-not (and (= 4 (count hex)) (every? hex-digit? hex))
               (reader-error! (str "Invalid unicode character literal: \\" tok) {:index i}))
             (let [v (hex-value hex)]
               (when (and (<= 0xD800 v) (<= v 0xDFFF))
                 (reader-error! (str "Invalid character constant: \\" tok) {:index i}))
               (code->char v)))
           (str/starts-with? tok "o")
           (let [oct (subs tok 1)]
             (when-not (and (<= 1 (count oct) 3) (every? octal-digit? oct))
               (reader-error! (str "Invalid octal character literal: \\" tok) {:index i}))
             (let [v (octal-value oct)]
               (when (> v 255)
                 (reader-error! (str "Octal escape sequence must be in range [0, 377]: \\" tok)
                                {:index i}))
               (code->char v)))
           :else
           (reader-error! (str "Unsupported character: \\" tok) {:index i}))
         next-i]))))


(defn- parse-symbol-token
  [tok i]
  (cond
    (= tok "nil") nil
    (= tok "true") true
    (= tok "false") false
    (= tok "/") '/
    :else
    (let [slash (str/index-of tok "/")]
      (if (nil? slash)
        (symbol tok)
        (let [ns-part (subs tok 0 slash)
              name-part (subs tok (inc slash))]
          (when (or (empty? ns-part)
                    (empty? name-part)
                    (and (not= name-part "/")
                         (str/includes? name-part "/")))
            (reader-error! (str "Invalid token: " tok) {:token tok :index i}))
          (symbol ns-part name-part))))))


(defn- parse-keyword-token
  [tok i]
  ;; tok starts with ":"
  (when (or (= tok ":") (str/starts-with? tok "::"))
    (reader-error! (str "Invalid keyword token: " tok) {:token tok :index i}))
  (let [body (subs tok 1)
        slash (str/index-of body "/")]
    (if (nil? slash)
      (do
        (when (str/starts-with? body ":")
          (reader-error! (str "Invalid keyword token: " tok) {:token tok :index i}))
        (keyword body))
      (let [ns-part (subs body 0 slash)
            name-part (subs body (inc slash))]
        (when (or (empty? ns-part)
                  (empty? name-part)
                  (str/starts-with? name-part ":")
                  (and (not= name-part "/")
                       (str/includes? name-part "/")))
          (reader-error! (str "Invalid keyword token: " tok) {:token tok :index i}))
        (keyword ns-part name-part)))))


(declare read-form*)


(defn- read-delimited
  "Read zero or more forms until `close-code`, returning `[items next-i]`."
  [s n i close-code ctx]
  (loop [j i
         items []]
    (let [j (skip-ws-and-comments s n j)]
      (when (>= j n)
        (reader-error! "EOF while reading collection" {:index i}))
      (let [c (char-code (char-at s j))]
        (if (= c close-code)
          [items (inc j)]
          (let [[status val next-j] (read-form* s n j ctx)]
            (if (= :discard status)
              (recur next-j items)
              (recur next-j (conj items val)))))))))


(defn- build-checked-map
  [items i]
  (when (odd? (count items))
    (reader-error!
      (str "The map literal starting with " (pr-str (first items))
           " contains " (count items) " form(s). Map literals must contain an even number of forms.")
      {:index i}))
  (reduce (fn [m [k v]]
            (when (contains? m k)
              (reader-error! (str "Map literal contains duplicate key: " (pr-str k))
                             {:key k :index i}))
            (assoc m k v))
          {}
          (partition 2 items)))


(defn- build-checked-set
  [items i]
  (reduce (fn [acc x]
            (when (contains? acc x)
              (reader-error! (str "Set literal contains duplicate key: " (pr-str x))
                             {:key x :index i}))
            (conj acc x))
          #{}
          items))


(defn- apply-map-namespace
  [ns-str k]
  (cond
    (keyword? k)
    (let [kns (namespace k)]
      (cond
        (nil? kns) (keyword ns-str (name k))
        (= "_" kns) (keyword (name k))
        :else k))
    (symbol? k)
    (let [kns (namespace k)]
      (cond
        (nil? kns) (symbol ns-str (name k))
        (= "_" kns) (symbol (name k))
        :else k))
    :else k))


(defn- read-namespaced-map
  [s n i ctx]
  ;; s[i] is ":" right after "#"
  (let [[tok next-i] (read-token s n (inc i))
        _ (when (or (empty? tok) (str/starts-with? tok ":") (str/includes? tok "/"))
            (reader-error! (str "Invalid namespaced map prefix: #:" tok) {:index i}))
        open-i (skip-ws-and-comments s n next-i)]
    (when (or (>= open-i n) (not= 123 (char-code (char-at s open-i))))
      (reader-error! (str "Namespaced map #:" tok " must specify a map") {:index open-i}))
    (let [[items end-i] (read-delimited s n (inc open-i) 125 ctx)]
      (when (odd? (count items))
        (reader-error! "Namespaced map literal must contain an even number of forms." {:index open-i}))
      (let [namespaced (mapcat (fn [[k v]] [(apply-map-namespace tok k) v])
                               (partition 2 items))]
        [(build-checked-map namespaced open-i) end-i]))))


(defn- read-next-required
  [s n i ctx what]
  (loop [j i]
    (let [j (skip-ws-and-comments s n j)]
      (when (>= j n)
        (reader-error! (str "EOF while reading " what) {:index i}))
      (let [[status val next-j] (read-form* s n j ctx)]
        (if (= :discard status)
          (recur next-j)
          [val next-j])))))


(defn- read-meta
  [s n i ctx]
  ;; s[i] is ^
  (let [[raw-meta next-i] (read-next-required s n (inc i) ctx "metadata")
        meta-map (cond
                   (map? raw-meta) raw-meta
                   (keyword? raw-meta) {raw-meta true}
                   (or (symbol? raw-meta) (string? raw-meta)) {:tag raw-meta}
                   :else (reader-error! "Metadata must be Symbol, Keyword, String or Map"
                                        {:meta raw-meta :index i}))
        [target end-i] (read-next-required s n next-i ctx "metadata target")]
    (when-not #?(:cljs (and (some? target) (satisfies? IWithMeta target))
                 :default (instance? clojure.lang.IObj target))
      (reader-error! "Metadata can only be applied to IObjs" {:target target :index i}))
    [(with-meta target (merge (meta target) meta-map)) end-i]))


#?(:cljs
   (deftype FnArgRef [id arg-key meta-map]
     IEquiv
     (-equiv [_ other]
       (if (instance? FnArgRef other)
         (let [^FnArgRef o other]
           (and (identical? id (.-id o))
                (= arg-key (.-arg-key o))))
         false))
     IHash
     (-hash [_]
       (hash arg-key))
     IMeta
     (-meta [_] meta-map)
     IWithMeta
     (-with-meta [_ m] (FnArgRef. id arg-key m)))
   :default
   (deftype FnArgRef [id arg-key meta-map]
     Object
     (equals [_ other]
       (and (instance? FnArgRef other)
            (identical? id (.-id ^FnArgRef other))
            (= arg-key (.-arg-key ^FnArgRef other))))
     (hashCode [_]
       (int (hash arg-key)))
     clojure.lang.IObj
     (meta [_] meta-map)
     (withMeta [_ m] (FnArgRef. id arg-key m))))


(defn- supports-meta?
  [v]
  #?(:cljs (and (some? v) (satisfies? IWithMeta v))
     :default (instance? clojure.lang.IObj v)))


(defn- register-fn-arg!
  [fn-id args-atom tok]
  (let [arg-idx (cond
                  (= tok "%") 1
                  (= tok "%&") :rest
                  (re-matches #"^%[1-9][0-9]*$" tok)
                  #?(:cljs (js/parseInt (subs tok 1) 10)
                     :default (Integer/parseInt (subs tok 1)))
                  :else nil)]
    (when arg-idx
      (swap! args-atom conj arg-idx)
      (FnArgRef. fn-id arg-idx nil))))


#?(:cljs
   (deftype SyntaxGensymRef [scope-id base-name meta-map]
     IEquiv
     (-equiv [_ other]
       (if (instance? SyntaxGensymRef other)
         (let [^SyntaxGensymRef o other]
           (and (= scope-id (.-scope-id o))
                (= base-name (.-base-name o))))
         false))
     IHash
     (-hash [_]
       (hash [scope-id base-name]))
     IMeta
     (-meta [_] meta-map)
     IWithMeta
     (-with-meta [_ m] (SyntaxGensymRef. scope-id base-name m)))
   :default
   (deftype SyntaxGensymRef [scope-id base-name meta-map]
     Object
     (equals [_ other]
       (and (instance? SyntaxGensymRef other)
            (= scope-id (.-scope-id ^SyntaxGensymRef other))
            (= base-name (.-base-name ^SyntaxGensymRef other))))
     (hashCode [_]
       (int (hash [scope-id base-name])))
     clojure.lang.IObj
     (meta [_] meta-map)
     (withMeta [_ m] (SyntaxGensymRef. scope-id base-name m))))


(defn- collect-used-syms
  [form acc]
  (let [acc (if (and (supports-meta? form) (seq (meta form)))
              (collect-used-syms (meta form) acc)
              acc)]
    (cond
      (or (instance? FnArgRef form)
          (instance? SyntaxGensymRef form))
      acc
      (symbol? form)
      (cond-> (conj acc form)
        (some? (namespace form)) (conj (symbol (name form))))
      (map? form)
      (reduce (fn [a [k v]]
                (collect-used-syms v (collect-used-syms k a)))
              acc
              form)
      (coll? form)
      (reduce (fn [a x] (collect-used-syms x a)) acc form)
      :else acc)))


(defn- fresh-param-sym
  [prefix used]
  (loop [k 1]
    (let [cand (symbol (str prefix "__" k "#"))]
      (if (contains? used cand)
        (recur (inc k))
        cand))))


(defn- resolve-fn-args
  [form fn-id mapping]
  (let [m (when (supports-meta? form) (meta form))
        resolved
        (cond
          (and (instance? FnArgRef form) (identical? fn-id (.-id ^FnArgRef form)))
          (get mapping (.-arg-key ^FnArgRef form))

          (vector? form)
          (mapv #(resolve-fn-args % fn-id mapping) form)

          (map? form)
          (into (empty form)
                (map (fn [[k v]]
                       [(resolve-fn-args k fn-id mapping)
                        (resolve-fn-args v fn-id mapping)]))
                form)

          (set? form)
          (into #{} (map #(resolve-fn-args % fn-id mapping)) form)

          (seq? form)
          (apply list (map #(resolve-fn-args % fn-id mapping) form))

          :else form)]
    (if (and (seq m) (supports-meta? resolved))
      (with-meta resolved (resolve-fn-args m fn-id mapping))
      resolved)))


(defn- read-anon-fn
  [s n i ctx]
  ;; s[i] is "(" right after "#"
  (when (:in-fn? ctx)
    (reader-error! "Nested #()s are not allowed" {:index i}))
  (let [fn-id #?(:cljs (js-obj) :default (Object.))
        args-atom (atom #{})
        [items end-i] (read-delimited s n (inc i) 41
                                      (assoc ctx :in-fn? true :fn-id fn-id :fn-args args-atom))
        arg-set @args-atom
        pos-nums (filter number? arg-set)
        max-pos (if (seq pos-nums) (apply max pos-nums) 0)
        used0 (collect-used-syms items #{})
        {:keys [params mapping used]}
        (reduce (fn [{:keys [params mapping used]} k]
                  (let [sym (fresh-param-sym (str "p" k) used)]
                    {:params (conj params sym)
                     :mapping (assoc mapping k sym)
                     :used (conj used sym)}))
                {:params [] :mapping {} :used used0}
                (range 1 (inc max-pos)))
        [params mapping]
        (if (contains? arg-set :rest)
          (let [rest-sym (fresh-param-sym "rest" used)]
            [(conj params '& rest-sym) (assoc mapping :rest rest-sym)])
          [params mapping])
        resolved-items (mapv #(resolve-fn-args % fn-id mapping) items)]
    [(list 'fn params (apply list resolved-items)) end-i]))


(defn- numeric-carrier?
  [x]
  (or (cbor/float64? x)
      (cbor/decimal? x)
      (cbor/ratio? x)
      (host-int/big-carrier? x)))


(defn- self-evaluating-scalar?
  [x]
  (or (keyword? x)
      (number? x)
      (numeric-carrier? x)
      (string? x)
      (nil? x)
      (boolean? x)
      #?(:cljs (instance? js/RegExp x)
         :default (or (char? x) (instance? java.util.regex.Pattern x)))))


(defn- unquote-form?
  [x]
  (and (seq? x) (= 'clojure.core/unquote (first x))))


(defn- unquote-splicing-form?
  [x]
  (and (seq? x) (= 'clojure.core/unquote-splicing (first x))))


(def ^:private special-symbols
  "Clojure compiler special symbols left unqualified by syntax-quote."
  '#{& . case* catch def deftype* do finally fn* if
     let* letfn* loop* monitor-enter monitor-exit new
     quote recur reify* set! throw try var clojure.core/import*})


(def clojure-core-symbols
  "Portable immutable set of standard Clojure 1.12 `clojure.core` public
   symbols used by syntax-quote symbol resolution across hosts (no ambient
   host `*ns*` lookup or global reader mutation)."
  '#{* *' *1 *2 *3 *agent* *allow-unresolved-vars* *assert* *clojure-version*
     *command-line-args* *compile-files* *compile-path* *compiler-options*
     *data-readers* *default-data-reader-fn* *e *err* *file* *flush-on-newline*
     *fn-loader* *in* *math-context* *ns* *out* *print-dup* *print-length*
     *print-level* *print-meta* *print-namespace-maps* *print-readably*
     *read-eval* *reader-resolver* *repl* *source-path* *suppress-read*
     *unchecked-math* *use-context-classloader* *verbose-defrecords*
     *warn-on-reflection* + +' - -' -> ->> ->ArrayChunk ->Eduction ->Vec
     ->VecNode ->VecSeq -cache-protocol-fn -reset-methods .. / < <= = == > >=
     EMPTY-NODE Inst NaN? PrintWriter-on StackTraceElement->vec Throwable->map
     abs accessor aclone add-classpath add-tap add-watch agent agent-error
     agent-errors aget alength alias all-ns alter alter-meta! alter-var-root
     amap ancestors and any? apply areduce array-map as-> aset aset-boolean
     aset-byte aset-char aset-double aset-float aset-int aset-long aset-short
     assert assoc assoc! assoc-in associative? atom await await-for await1
     bases bean bigdec bigint biginteger binding bit-and bit-and-not bit-clear
     bit-flip bit-not bit-or bit-set bit-shift-left bit-shift-right bit-test
     bit-xor boolean boolean-array boolean? booleans bound-fn bound-fn* bound?
     bounded-count butlast byte byte-array bytes bytes? case cast cat char
     char-array char-escape-string char-name-string char? chars chunk
     chunk-append chunk-buffer chunk-cons chunk-first chunk-next chunk-rest
     chunked-seq? class class? clear-agent-errors clojure-version coll? comment
     commute comp comparator compare compare-and-set! compile complement
     completing concat cond cond-> cond->> condp conj conj! cons constantly
     construct-proxy contains? count counted? create-ns create-struct cycle dec
     dec' decimal? declare dedupe default-data-readers definline definterface
     defmacro defmethod defmulti defn defn- defonce defprotocol defrecord
     defstruct deftype delay delay? deliver denominator deref derive
     descendants destructure disj disj! dissoc dissoc! distinct distinct? doall
     dorun doseq dosync dotimes doto double double-array double? doubles drop
     drop-last drop-while eduction empty empty? ensure ensure-reduced
     enumeration-seq error-handler error-mode eval even? every-pred every?
     ex-cause ex-data ex-info ex-message extend extend-protocol extend-type
     extenders extends? false? ffirst file-seq filter filterv find find-keyword
     find-ns find-protocol-impl find-protocol-method find-var first flatten
     float float-array float? floats flush fn fn? fnext fnil for force format
     frequencies future future-call future-cancel future-cancelled?
     future-done? future? gen-class gen-interface gensym get get-in get-method
     get-proxy-class get-thread-bindings get-validator group-by halt-when hash
     hash-combine hash-map hash-ordered-coll hash-set hash-unordered-coll
     ident? identical? identity if-let if-not if-some ifn? import in-ns inc
     inc' indexed? infinite? init-proxy inst-ms inst-ms* inst? instance? int
     int-array int? integer? interleave intern interpose into into-array ints
     io! isa? iterate iteration iterator-seq juxt keep keep-indexed key keys
     keyword keyword? last lazy-cat lazy-seq let letfn line-seq list list*
     list? load load-file load-reader load-string loaded-libs locking long
     long-array longs loop macroexpand macroexpand-1 make-array make-hierarchy
     map map-entry? map-indexed map? mapcat mapv max max-key memfn memoize
     merge merge-with meta method-sig methods min min-key mix-collection-hash
     mod munge name namespace namespace-munge nat-int? neg-int? neg? newline
     next nfirst nil? nnext not not-any? not-empty not-every? not= ns
     ns-aliases ns-imports ns-interns ns-map ns-name ns-publics ns-refers
     ns-resolve ns-unalias ns-unmap nth nthnext nthrest num number? numerator
     object-array odd? or parents parse-boolean parse-double parse-long
     parse-uuid partial partition partition-all partition-by partitionv
     partitionv-all pcalls peek persistent! pmap pop pop! pop-thread-bindings
     pos-int? pos? pr pr-str prefer-method prefers primitives-classnames print
     print-ctor print-dup print-method print-simple print-str printf println
     println-str prn prn-str promise proxy proxy-call-with-super proxy-mappings
     proxy-name proxy-super push-thread-bindings pvalues qualified-ident?
     qualified-keyword? qualified-symbol? quot rand rand-int rand-nth
     random-sample random-uuid range ratio? rational? rationalize re-find
     re-groups re-matcher re-matches re-pattern re-seq read read+string
     read-line read-string reader-conditional reader-conditional? realized?
     record? reduce reduce-kv reduced reduced? reductions ref ref-history-count
     ref-max-history ref-min-history ref-set refer refer-clojure reify
     release-pending-sends rem remove remove-all-methods remove-method
     remove-ns remove-tap remove-watch repeat repeatedly replace replicate
     require requiring-resolve reset! reset-meta! reset-vals! resolve rest
     restart-agent resultset-seq reverse reversible? rseq rsubseq run!
     satisfies? second select-keys send send-off send-via seq
     seq-to-map-for-destructuring seq? seqable? seque sequence sequential? set
     set-agent-send-executor! set-agent-send-off-executor! set-error-handler!
     set-error-mode! set-validator! set? short short-array shorts shuffle
     shutdown-agents simple-ident? simple-keyword? simple-symbol? slurp some
     some-> some->> some-fn some? sort sort-by sorted-map sorted-map-by
     sorted-set sorted-set-by sorted? special-symbol? spit split-at split-with
     splitv-at str stream-into! stream-reduce! stream-seq! stream-transduce!
     string? struct struct-map subs subseq subvec supers swap! swap-vals!
     symbol symbol? sync tagged-literal tagged-literal? take take-last take-nth
     take-while tap> test the-ns thread-bound? time to-array to-array-2d
     trampoline transduce transient tree-seq true? type unchecked-add
     unchecked-add-int unchecked-byte unchecked-char unchecked-dec
     unchecked-dec-int unchecked-divide-int unchecked-double unchecked-float
     unchecked-inc unchecked-inc-int unchecked-int unchecked-long
     unchecked-multiply unchecked-multiply-int unchecked-negate
     unchecked-negate-int unchecked-remainder-int unchecked-short
     unchecked-subtract unchecked-subtract-int underive unquote
     unquote-splicing unreduced unsigned-bit-shift-right update update-in
     update-keys update-proxy update-vals uri? use uuid? val vals var-get
     var-set var? vary-meta vec vector vector-of vector? volatile! volatile?
     vreset! vswap! when when-first when-let when-not when-some while
     with-bindings with-bindings* with-in-str with-loading-context
     with-local-vars with-meta with-open with-out-str with-precision
     with-redefs with-redefs-fn xml-seq zero? zipmap})


(def default-syntax-quote-context
  "Default portable syntax-quote resolution context: `user` namespace,
   standard `clojure.core` refers, no aliases, no exclusions."
  {:ns "user"
   :aliases {}
   :refers {}
   :exclude #{}
   :core-syms clojure-core-symbols
   :refer-clojure? true})


(defn- context-name-str
  [x]
  (cond
    (string? x) x
    (symbol? x) (if (namespace x)
                  (str (namespace x) "/" (name x))
                  (name x))
    (keyword? x) (if (namespace x)
                   (str (namespace x) "/" (name x))
                   (name x))
    :else (reader-error! (str "Expected symbol or string in reader context, got: " (pr-str x))
                         {:value x})))


(defn- build-syntax-quote-context
  [opts]
  (if (and (nil? (:ns opts))
           (nil? (:aliases opts))
           (nil? (:refers opts))
           (nil? (:exclude opts))
           (nil? (:core-syms opts))
           (not (contains? opts :refer-clojure?)))
    default-syntax-quote-context
    (let [ns-val (if (some? (:ns opts)) (context-name-str (:ns opts)) "user")
          aliases (reduce-kv (fn [m k v]
                               (assoc m (context-name-str k) (context-name-str v)))
                             {}
                             (or (:aliases opts) {}))
          refers (reduce-kv (fn [m k v]
                              (let [ksym (if (symbol? k) k (symbol (context-name-str k)))]
                                (assoc m ksym
                                       (if (and (symbol? v) (some? (namespace v)))
                                         v
                                         (symbol (context-name-str v) (name ksym))))))
                            {}
                            (or (:refers opts) {}))
          exclude (into #{}
                        (map (fn [x] (if (symbol? x) x (symbol (context-name-str x)))))
                        (or (:exclude opts) #{}))
          core-syms (or (:core-syms opts) clojure-core-symbols)
          refer-clojure? (if (contains? opts :refer-clojure?)
                           (boolean (:refer-clojure? opts))
                           true)]
      {:ns ns-val
       :aliases aliases
       :refers refers
       :exclude exclude
       :core-syms core-syms
       :refer-clojure? refer-clojure?})))


(defn- resolve-syntax-symbol
  "Resolve symbol `sym` for syntax-quote using explicit portable context `sq-ctx`."
  [sym {:keys [ns aliases refers exclude core-syms refer-clojure?] :as sq-ctx}]
  (let [sym-ns (namespace sym)
        sym-name (name sym)]
    (cond
      (contains? special-symbols sym)
      sym

      (some? sym-ns)
      (if-let [target-ns (get aliases sym-ns)]
        (symbol target-ns sym-name)
        sym)

      (str/starts-with? sym-name ".")
      sym

      (str/includes? sym-name ".")
      (if (str/ends-with? sym-name ".")
        (let [base (subs sym-name 0 (dec (count sym-name)))]
          (if (empty? base)
            sym
            (let [resolved (resolve-syntax-symbol (symbol base) sq-ctx)]
              (symbol (str (name resolved) ".")))))
        sym)

      (contains? refers sym)
      (get refers sym)

      (and refer-clojure?
           (contains? core-syms sym)
           (not (contains? exclude sym)))
      (symbol "clojure.core" sym-name)

      :else
      (symbol ns sym-name))))


(declare syntax-quote*)


(defn- expand-syntax-list
  [coll scope-id ctx i]
  (mapv (fn [item]
          (cond
            (unquote-form? item)
            (list 'clojure.core/list (second item))

            (unquote-splicing-form? item)
            (second item)

            :else
            (list 'clojure.core/list (syntax-quote* item scope-id ctx i))))
        coll))


(defn- syntax-quote-coll
  [ctor coll scope-id ctx i]
  (let [res (list 'clojure.core/sequence
                  (list 'clojure.core/seq
                        (apply list 'clojure.core/concat
                               (expand-syntax-list coll scope-id ctx i))))]
    (if ctor
      (list 'clojure.core/apply ctor res)
      res)))


(defn- add-syntax-meta
  [form ret scope-id ctx i]
  (let [user-meta (when (supports-meta? form)
                    (dissoc (meta form) :line :column :end-line :end-column :file :source))]
    (if (seq user-meta)
      (list 'clojure.core/with-meta ret (syntax-quote* user-meta scope-id ctx i))
      ret)))


(defn- syntax-quote*
  [form scope-id ctx i]
  (add-syntax-meta
    form
    (cond
      (unquote-form? form)
      (second form)

      (unquote-splicing-form? form)
      (reader-error! "unquote-splice not in list" {:index i})

      (instance? SyntaxGensymRef form)
      (list 'quote form)

      (symbol? form)
      (list 'quote
            (if (and (nil? (namespace form))
                     (str/ends-with? (name form) "#"))
              (let [s (name form)
                    base (subs s 0 (dec (count s)))]
                (when-let [flag (:has-gensym? ctx)]
                  (reset! flag true))
                (SyntaxGensymRef. scope-id base nil))
              (resolve-syntax-symbol form (:sq-ctx ctx))))

      (self-evaluating-scalar? form)
      form

      (map? form)
      (syntax-quote-coll (if (>= (count form) 16)
                           'clojure.core/hash-map
                           'clojure.core/array-map)
                         (apply concat form)
                         scope-id ctx i)

      (vector? form)
      (list 'clojure.core/vec (syntax-quote-coll nil form scope-id ctx i))

      (set? form)
      (syntax-quote-coll 'clojure.core/hash-set form scope-id ctx i)

      (seq? form)
      (if (seq form)
        (syntax-quote-coll nil form scope-id ctx i)
        '(clojure.core/list))

      :else
      (list 'quote form))
    scope-id ctx i))


(defn- read-syntax-quote
  [s n i ctx]
  ;; s[i] is `
  (let [[form end-i] (read-next-required s n (inc i) ctx "syntax-quote")
        scope-id (swap! (:scope-seq ctx) inc)]
    [(syntax-quote* form scope-id ctx i) end-i]))


(defn- fresh-auto-gensym
  [base next-k used]
  (loop [k next-k]
    (let [cand (symbol (str base "__" k "__auto__"))]
      (if (contains? used cand)
        (recur (inc k))
        [cand (inc k)]))))


(defn- allocate-syntax-gensym!
  [state ^SyntaxGensymRef ref]
  (let [scope-id (.-scope-id ref)
        base (.-base-name ref)
        k [scope-id base]
        st @state]
    (if-let [existing (get (:mapping st) k)]
      existing
      (let [[sym next-k] (fresh-auto-gensym base (:next st) (:used st))]
        (reset! state {:next next-k
                       :mapping (assoc (:mapping st) k sym)
                       :used (conj (:used st) sym)})
        sym))))


(defn- resolve-syntax-gensyms
  [form state]
  (let [m (when (supports-meta? form) (meta form))
        resolved
        (cond
          (instance? SyntaxGensymRef form)
          (allocate-syntax-gensym! state form)

          (vector? form)
          (mapv #(resolve-syntax-gensyms % state) form)

          (map? form)
          (into (empty form)
                (mapv (fn [[k v]]
                        [(resolve-syntax-gensyms k state)
                         (resolve-syntax-gensyms v state)])
                      form))

          (set? form)
          (into #{} (mapv #(resolve-syntax-gensyms % state) form))

          (seq? form)
          (apply list (mapv #(resolve-syntax-gensyms % state) form))

          :else form)]
    (if (and (seq m) (supports-meta? resolved))
      (with-meta resolved (resolve-syntax-gensyms m state))
      resolved)))


(defn- resolve-all-syntax-gensyms
  [forms]
  (let [used0 (collect-used-syms forms #{})
        state (atom {:next 1 :mapping {} :used used0})]
    (mapv #(resolve-syntax-gensyms % state) forms)))


(defn- read-dispatch
  [s n i ctx]
  ;; s[i] is #
  (when (>= (inc i) n)
    (reader-error! "EOF while reading dispatch macro" {:index i}))
  (let [ch (char-at s (inc i))
        c (char-code ch)]
    (cond
      ;; #{...} set
      (= c 123)
      (let [[items end-i] (read-delimited s n (+ i 2) 125 ctx)]
        [:value (build-checked-set items i) end-i])

      ;; #_ discard (still validates syntax of the discarded form)
      (= c 95)
      (let [[_ end-i] (read-next-required s n (+ i 2) ctx "discarded form")]
        [:discard nil end-i])

      ;; #' var quote
      (= c 39)
      (let [[form end-i] (read-next-required s n (+ i 2) ctx "var")]
        [:value (list 'var form) end-i])

      ;; #^ deprecated metadata dispatch macro
      (= c 94)
      (let [[val end-i] (read-meta s n (inc i) ctx)]
        [:value val end-i])

      ;; #"..." regex
      (= c 34)
      (let [[val end-i] (read-regex-lit s n (inc i))]
        [:value val end-i])

      ;; #(...) anonymous fn
      (= c 40)
      (let [[val end-i] (read-anon-fn s n (inc i) ctx)]
        [:value val end-i])

      ;; ## symbolic values (##Inf, ##-Inf, ##NaN)
      (= c 35)
      (let [[sym-tok end-i] (read-token s n (+ i 2))]
        [:value (numeric-token/materialize :clojure (str "##" sym-tok)) end-i])

      ;; #:ns{...} namespaced map
      (= c 58)
      (let [[val end-i] (read-namespaced-map s n (inc i) ctx)]
        [:value val end-i])

      ;; #< unreadable form — explicit refusal
      (= c 60)
      (reader-error! "Unreadable form" {:index i})

      ;; #? / #?@ reader conditionals — explicit refusal
      (= c 63)
      (reader-error! "Reader conditionals are not supported" {:index i})

      ;; #= eval — explicit refusal
      (= c 61)
      (reader-error! "Eval reader (#=) is not supported" {:index i})

      :else
      (let [[tag _] (read-token s n (inc i))]
        (reader-error! (str "No reader function for tag " tag)
                       {:tag tag :index i})))))


(defn- compute-line-starts
  [^String s ^long n]
  (loop [i 0
         starts [0]]
    (if (>= i n)
      starts
      (if (= 10 (char-code (char-at s i)))
        (recur (inc i) (conj starts (inc i)))
        (recur (inc i) starts)))))


(defn- offset->pos
  [line-starts ^long idx]
  (loop [lo 0
         hi (dec (count line-starts))]
    (if (= lo hi)
      [(inc lo) (inc (- idx (nth line-starts lo)))]
      (let [mid (quot (+ lo hi 1) 2)]
        (if (<= (nth line-starts mid) idx)
          (recur mid hi)
          (recur lo (dec mid)))))))


(defn- attach-source-pos
  [val start-i end-i ctx]
  (if (and (:line-starts ctx)
           (supports-meta? val))
    (let [line-starts (:line-starts ctx)
          [sl sc] (offset->pos line-starts start-i)
          [el ec] (offset->pos line-starts end-i)
          pos-meta (cond-> {:line sl
                            :column sc
                            :end-line el
                            :end-column ec}
                     (:file ctx) (assoc :file (:file ctx)))]
      (with-meta val (merge pos-meta (meta val))))
    val))


(defn- read-form-1
  [s n i ctx]
  (let [ch (char-at s i)
        c (char-code ch)]
    (cond
      ;; Unmatched closing delimiters
      (or (= c 41) (= c 93) (= c 125))
      (reader-error! (str "Unmatched delimiter: " ch) {:delimiter (str ch) :index i})

      ;; List (...)
      (= c 40)
      (let [[items end-i] (read-delimited s n (inc i) 41 ctx)]
        [:value (apply list items) end-i])

      ;; Vector [...]
      (= c 91)
      (let [[items end-i] (read-delimited s n (inc i) 93 ctx)]
        [:value items end-i])

      ;; Map {...}
      (= c 123)
      (let [[items end-i] (read-delimited s n (inc i) 125 ctx)]
        [:value (build-checked-map items i) end-i])

      ;; String "..."
      (= c 34)
      (let [[val end-i] (read-string-lit s n i)]
        [:value val end-i])

      ;; Character literal \...
      (= c 92)
      (let [[val end-i] (read-char-lit s n i)]
        [:value val end-i])

      ;; Quote '
      (= c 39)
      (let [[form end-i] (read-next-required s n (inc i) ctx "quote")]
        [:value (list 'quote form) end-i])

      ;; Deref @
      (= c 64)
      (let [[form end-i] (read-next-required s n (inc i) ctx "deref")]
        [:value (list 'clojure.core/deref form) end-i])

      ;; Metadata ^
      (= c 94)
      (let [[val end-i] (read-meta s n i ctx)]
        [:value val end-i])

      ;; Unquote ~ / unquote-splicing ~@
      (= c 126)
      (if (and (< (inc i) n) (= 64 (char-code (char-at s (inc i)))))
        (let [[form end-i] (read-next-required s n (+ i 2) ctx "unquote-splicing")]
          [:value (list 'clojure.core/unquote-splicing form) end-i])
        (let [[form end-i] (read-next-required s n (inc i) ctx "unquote")]
          [:value (list 'clojure.core/unquote form) end-i]))

      ;; Syntax-quote `
      (= c 96)
      (let [[val end-i] (read-syntax-quote s n i ctx)]
        [:value val end-i])

      ;; Dispatch #...
      (= c 35)
      (read-dispatch s n i ctx)

      ;; Number: digit, or +/- followed immediately by digit
      (or (digit? ch)
          (and (or (= c 43) (= c 45))
               (< (inc i) n)
               (digit? (char-at s (inc i)))))
      (let [[tok end-i] (read-token s n i)]
        [:value (numeric-token/materialize :clojure tok) end-i])

      ;; Keyword :...
      (= c 58)
      (let [[tok end-i] (read-token s n i)]
        [:value (parse-keyword-token tok i) end-i])

      ;; Symbol / boolean / nil
      :else
      (let [[tok end-i] (read-token s n i)]
        (if-let [fn-arg (when (:in-fn? ctx)
                          (register-fn-arg! (:fn-id ctx) (:fn-args ctx) tok))]
          [:value fn-arg end-i]
          [:value (parse-symbol-token tok i) end-i])))))


(defn- read-form*
  [s n i ctx]
  (let [[status val end-i] (read-form-1 s n i ctx)]
    (if (= :discard status)
      [:discard nil end-i]
      [:value (attach-source-pos val i end-i ctx) end-i])))


(defn read-forms
  "Read every Clojure form from original `source` text, preserving numeric
   token kind through `yang.core.numeric-token/materialize :clojure` at
   token time. Returns a vector of forms.

   Optional `opts` map supports:
   - `:file` / `:source-positions?` — attach `:line`/`:column`/`:end-line`/
     `:end-column` (and `:file`) metadata on read IObj forms.
   - `:ns` — explicit current namespace (symbol or string, default `\"user\"`)
     for syntax-quote symbol resolution.
   - `:aliases` — explicit map of namespace alias -> target namespace
     (symbols or strings) for syntax-quote qualified symbol resolution.
   - `:refers` — explicit map of unqualified symbol -> target namespace or
     qualified symbol for syntax-quote resolution.
   - `:exclude` — set of `clojure.core` symbols excluded from default refers.
   - `:refer-clojure?` — whether default `clojure.core` refers apply
     (default `true`).
   - `:core-syms` — override set of core symbols (default `clojure-core-symbols`)."
  ([source] (read-forms source nil))
  ([source opts]
   (when-not (string? source)
     (reader-error! "Clojure source reader expects a string" {:source source}))
   (let [n (count source)
         track-pos? (boolean (or (:file opts) (:source-positions? opts)))
         has-gensym? (atom false)
         ctx (cond-> {:sq-ctx (build-syntax-quote-context opts)
                      :scope-seq (atom 0)
                      :has-gensym? has-gensym?}
               track-pos? (assoc :line-starts (compute-line-starts source n))
               (:file opts) (assoc :file (:file opts)))
         raw-forms
         (loop [i 0
                forms []]
           (let [i (skip-ws-and-comments source n i)]
             (if (>= i n)
               forms
               (let [[status val next-i] (read-form* source n i ctx)]
                 (if (= :discard status)
                   (recur next-i forms)
                   (recur next-i (conj forms val)))))))]
     (if @has-gensym?
       (resolve-all-syntax-gensyms raw-forms)
       raw-forms))))
