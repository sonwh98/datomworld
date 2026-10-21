;; J0c rounds 2-3: the jolt yang.antlr.cst adapter against the JVM oracle
;; (docs/design/yin.repl-jolt-implementation.md §7.8), under the Architect's
;; equivalence E1 (collab/1792491122000-architect-j0c-error-parity...).
;;
;;   . build/tools/jolt-env.sh
;;   "$JOLT_HOME"/bin/jolt -Srepro \
;;     -Sdeps '{:aliases {:j0c-probe-stub {:extra-paths ["src/dev/jolt-j0c-probe-stub"]}}}' \
;;     -M:jolt:j0c-probe-stub src/dev/jolt_j0c_parity.clj
;;
;; For every jolt_j0c_corpus.edn entry, the packet from the jolt adapter
;; (src/jolt/yang/antlr/cst.jolt, the ANTLR4 C++ parser marshalled into the
;; shared yang.antlr.export) passes packet/validate! (source-aware) and meets
;; E1 against the JVM oracle:
;;
;;   strict   every field of an ok packet; outcome, version, unit, grammar
;;            and completeness of an error packet; cst/first-failure; every
;;            probe answer and its cst/first-failure call sequence
;;   errors   at least one; the FIRST error's :kind and :span equal the
;;            JVM's; its :line/:column equal the JVM's when the offending
;;            token is a real source token, and equal the pinned jolt
;;            constant below when it is helper-fabricated (INDENT, DEDENT,
;;            EOF, a zero-width NEWLINE); its :message is a non-empty string
;;   exempt   message text and expecting-sets, and errors after the first
;;
;; Strict packet equality is still reported, as RECORD strict-diff lines, so
;; nothing E1 exempts is hidden. For every probe text,
;; yang.python.antlr.repl-probe/probe runs under jolt and must answer as on the
;; JVM, making the same cst/first-failure calls with the same results.
;;
;; repl-probe requires yang.python.antlr.parser, whose :clj branch imports the
;; generated Java recognizers; the command above therefore puts a stand-in
;; yang.python.antlr.parser (src/dev/jolt-j0c-probe-stub, holding only
;; `make-worker`, the probe's one use of it, and the grammar id) on a root
;; before the :jolt roots. The probe's own code is the tracked source.
;;
;; Prints PASS/DIVERGES per entry with a minimal diff, RECORD lines for the
;; observations, and exits nonzero on any divergence or failure.
(ns jolt-j0c-parity
  (:require
    [clojure.edn :as edn]
    [jolt.ffi :as ffi]))


(def ^:private before (set (loaded-libs)))


(require '[yang.antlr.cst :as cst] '[yang.antlr.export :as export]
         '[yang.antlr.packet :as packet])


(def ^:private added (sort (remove before (loaded-libs))))

(def corpus-path "test/yang/python/antlr/fixtures/jolt_j0c_corpus.edn")
(def oracle-path "test/yang/python/antlr/fixtures/jolt_j0c_oracle.edn")

(def diverged (atom []))
(def failed (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failed inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail)))


(check "yang.antlr.cst resolves to the jolt adapter (export-records exists) and loads only export/packet beside it"
       (and (some? (resolve 'yang.antlr.cst/export-records))
            (= '(yang.antlr.cst yang.antlr.export yang.antlr.packet) added))
       added)


;; The grammar id the JVM profile carries, read from its one definition.
(def grammar-id
  (let [src (slurp "src/cljc/yang/python/antlr/parser.cljc")
        [_ s] (re-find #"\(def grammar-id\s+\"[^\"]*\"\s+(\"[^\"]*\")\)" src)]
    (edn/read-string s)))


(def profile {:grammar-id grammar-id})
(def corpus (edn/read-string (slurp corpus-path)))
(def oracle (edn/read-string (slurp oracle-path)))


(check "oracle is the JVM's, for this grammar id, and covers the corpus in order"
       (and (= :jvm (:host oracle))
            (= grammar-id (:grammar-id oracle))
            (= (mapv (juxt :id :source) (:entries corpus))
               (mapv (juxt :id :source) (:entries oracle)))
            (= (:probe corpus) (mapv :text (:probe oracle))))
       {:host (:host oracle), :grammar-id grammar-id})


;; -- minimal diffs -------------------------------------------------------------

(defn- map-diff
  [a b]
  (into {}
        (keep (fn [k]
                (when (not= (get a k) (get b k))
                  [k {:jvm (get a k), :jolt (get b k)}])))
        (distinct (concat (keys a) (keys b)))))


(defn- seq-diff
  "Index-wise differences of two record vectors, at most `limit` of them."
  [xs ys limit]
  (let [n (max (count xs) (count ys))]
    (->> (range n)
         (keep (fn [i]
                 (let [x (get xs i) y (get ys i)]
                   (when (not= x y)
                     [i (if (and (map? x) (map? y)) (map-diff x y) {:jvm x, :jolt y})]))))
         (take limit)
         vec)))


(defn- packet-diff
  [jvm jolt]
  (let [top (dissoc (map-diff (dissoc jvm :yang.cst/nodes :yang.cst/errors)
                              (dissoc jolt :yang.cst/nodes :yang.cst/errors)))]
    (cond-> {}
      (seq top) (assoc :fields top)
      (not= (:yang.cst/nodes jvm) (:yang.cst/nodes jolt))
      (assoc :nodes {:count [(count (:yang.cst/nodes jvm)) (count (:yang.cst/nodes jolt))],
                     :first (seq-diff (vec (:yang.cst/nodes jvm))
                                      (vec (:yang.cst/nodes jolt)) 4)})
      (not= (:yang.cst/errors jvm) (:yang.cst/errors jolt))
      (assoc :errors (seq-diff (vec (:yang.cst/errors jvm))
                               (vec (:yang.cst/errors jolt)) 8)))))


;; -- the corpus ----------------------------------------------------------------

(defn- alternative-failure
  "The JS/Dart rule (the listener's offending token even for no-viable-alt),
   as an observation of the C++ token buffer."
  [worker source]
  (let [{:keys [failure tokens]} (cst/export-records worker source)
        {:keys [kind index]} failure]
    (export/failure-kind
      (case kind 0 nil 1 :lexical 2 index)
      (keep (fn [{:keys [type index start stop]}]
              (when (not= -1 type) {:index index, :start start, :stop stop}))
            tokens))))


(def helper-pins
  "The jolt host's [line column] for each entry whose first error is on a
   helper-fabricated token (macOS arm64, the round-3 :cpp helper patch). They
   equal the JVM's because that patch gives the C++ helper the Java helper's
   coordinates (lexer line/column for INDENT and EOF, last token's line for
   DEDENT); a drift in any of them fails the run."
  {:blank-lines [3 2],                  ; INDENT on line 3 after its spaces
   :missing-final-newline [1 5],        ; EOF at the end of line 1
   :malformed-unexpected-indent [1 2],  ; INDENT on line 1 after its spaces
   :malformed-unexpected-dedent [2 0],  ; DEDENT takes the last token's line
   :incomplete-def-header [2 0],        ; EOF at the start of line 2
   :incomplete-decorator [2 0],         ; EOF at the start of line 2
   :incomplete-continuation [2 0],      ; EOF at the start of line 2
   :bracket-list-open [3 0],            ; EOF at the start of line 3
   :bracket-dict-open [2 0]})           ; EOF at the start of line 2


(defn- helper-token?
  "Whether the first error's offending token was fabricated by the lexer
   helper rather than read from the source."
  [records]
  (boolean
    (when-let [{:keys [type a b]} (first (:errors records))]
      (let [tn (cond (= -1 type) "EOF"
                     (< -1 type (count (:symbolic records))) ((:symbolic records) type))]
        (or (#{"INDENT" "DEDENT" "EOF"} tn)
            (and (= "NEWLINE" tn) (< b a)))))))


(defn- e1-violations
  "E1 for one entry; [] when it holds."
  [id source jvm jolt records]
  (let [bad (volatile! [])
        note! (fn [why detail] (vswap! bad conj [why detail]))]
    (when-let [v (try (packet/validate! jolt {:source source}) nil
                      (catch Throwable e (ex-data e)))]
      (note! :validate! v))
    (if (= :yang.cst/ok (:yang.cst/outcome jvm))
      (when-not (= jvm jolt) (note! :ok-packet-not-equal (packet-diff jvm jolt)))
      (let [base (fn [p] (dissoc p :yang.cst/errors))
            [je] (:yang.cst/errors jvm)
            [ke] (:yang.cst/errors jolt)
            helper? (helper-token? records)
            pin (get helper-pins id)]
        (when-not (= (base jvm) (base jolt))
          (note! :packet-fields (map-diff (base jvm) (base jolt))))
        (when-not (seq (:yang.cst/errors jolt)) (note! :no-errors nil))
        (when-not (= (:kind je) (:kind ke)) (note! :first-kind [(:kind je) (:kind ke)]))
        (when-not (= (:span je) (:span ke)) (note! :first-span [(:span je) (:span ke)]))
        (when-not (and (string? (:message ke)) (seq (:message ke)))
          (note! :first-message (:message ke)))
        (cond
          (and helper? (nil? pin)) (note! :helper-token-unpinned [(:line ke) (:column ke)])
          (and (not helper?) pin) (note! :pin-on-a-real-token pin)
          helper? (when-not (= pin [(:line ke) (:column ke)])
                    (note! :helper-pin-drift {:pin pin, :jolt [(:line ke) (:column ke)]}))
          :else (when-not (= [(:line je) (:column je)] [(:line ke) (:column ke)])
                  (note! :first-line-column {:jvm [(:line je) (:column je)],
                                             :jolt [(:line ke) (:column ke)]})))))
    @bad))


(def results (atom []))


(doseq [{:keys [id source packet first-failure]} (:entries oracle)]
  (let [worker (cst/make-worker profile)
        jolt-packet (try (cst/parse-unit worker [:unit 0] source)
                         (catch Throwable e {:thrown (ex-message e), :data (ex-data e)}))
        jolt-ff (try (cst/first-failure worker source)
                     (catch Throwable e {:thrown (ex-message e)}))
        records (cst/export-records worker source)
        strict (= packet jolt-packet)
        e1 (if (:thrown jolt-packet)
             [[:thrown jolt-packet]]
             (e1-violations id source packet jolt-packet records))
        ff-ok (= first-failure jolt-ff)
        ok (and (empty? e1) ff-ok)
        alt (alternative-failure worker source)]
    (swap! results conj {:id id, :strict strict, :e1 (empty? e1), :first-failure ff-ok})
    (when-not ok (swap! diverged conj id))
    (println (if ok "PASS" "FAIL-E1") id
             (pr-str (cond-> {:outcome (:yang.cst/outcome packet),
                              :nodes (count (:yang.cst/nodes packet)),
                              :first-failure first-failure,
                              :strict strict}
                       (seq e1) (assoc :e1-violations e1)
                       (not ff-ok) (assoc :jolt-first-failure jolt-ff))))
    (when-not (or strict (:thrown jolt-packet))
      (println "RECORD strict-diff" id (pr-str (packet-diff packet jolt-packet))))
    (println "RECORD token-buffer" id
             (pr-str {:first-error (:failure records),
                      :buffered-after (count (:tokens records)),
                      :offending-token-rule alt,
                      :rules-agree (= alt jolt-ff)}))
    (cst/close-worker worker)))


(check "every helper pin names a corpus entry"
       (every? (set (map :id (:entries oracle))) (keys helper-pins))
       (remove (set (map :id (:entries oracle))) (keys helper-pins)))


;; -- E1 refuses what it must (the checker is not vacuous) ------------------------

(let [entry (fn [id] (first (filter #(= id (:id %)) (:entries oracle))))
      w (cst/make-worker profile)
      run (fn [id f]
            (let [{:keys [source packet]} (entry id)
                  jolt (f (cst/parse-unit w [:unit 0] source))]
              (set (map first (e1-violations id source packet jolt
                                             (cst/export-records w source))))))
      first-error (fn [k f] #(update-in % [:yang.cst/errors 0 k] f))]
  (doseq [[label id f expect]
          [["an INDENT at line 0 (the round-2 C++ helper)" :malformed-unexpected-indent
            (first-error :line (constantly 0)) #{:validate! :helper-pin-drift}]
           ["a helper-token column off its pin" :incomplete-decorator
            (first-error :column inc) #{:helper-pin-drift}]
           ["a real-token line off the JVM's" :malformed-def
            (first-error :line inc) #{:first-line-column}]
           ["a first-error span off the JVM's" :malformed-def
            (first-error :span (fn [[a b]] [a (inc b)])) #{:first-span}]
           ["an empty message" :malformed-def
            (first-error :message (constantly "")) #{:first-message}]
           ["no errors" :malformed-def
            #(assoc % :yang.cst/errors []) #{:no-errors}]
           ["an ok packet with one node changed" :arith-add
            #(assoc-in % [:yang.cst/nodes 1 :rule] "expr") #{:ok-packet-not-equal}]]]
    (let [got (run id f)]
      (check (str "[E1 self-test] refuses " label)
             (every? got expect)
             got)))
  (cst/close-worker w))


;; -- a failed native parse never yields a packet --------------------------------

;; The seam exists only in a -DJOLT_PARSER_TEST_SEAMS=ON build.
(def inject-fault
  (when (ffi/find-symbol "jolt_parser_inject_fault")
    (ffi/cfn "jolt_parser_inject_fault" [:int] :int)))


(if-not inject-fault
  (println "SKIP fault injection: this library has no jolt_parser_inject_fault (built without JOLT_PARSER_TEST_SEAMS)")
  (let [w (cst/make-worker profile)]
    (doseq [mode [1 2 3]]
      (inject-fault mode)
      (let [r (try (cst/parse-unit w [:unit 0] "1 + 2\n")
                   (catch Throwable e {:thrown (ex-data e)})
                   (finally (inject-fault 0)))]
        (check (str "[injected mode " mode "] parse-unit throws the wrapper status, no packet")
               (and (contains? r :thrown) (#{-2 -3} (get-in r [:thrown :status])))
               r)))
    (check "after the injected failures the same worker answers the oracle packet again"
           (= (:packet (first (:entries oracle)))
              (cst/parse-unit w [:unit 0] (:source (first (:entries oracle)))))
           (:id (first (:entries oracle))))
    (cst/close-worker w)))


;; -- input policy (round 4) ------------------------------------------------------
;;
;; Sources a JVM string can carry (embedded NUL, CR/CRLF, BOM, edge code
;; points) are held to E1 against jolt_j0c_input_oracle.edn, after checking
;; that jolt read each source to the JVM's code points. What has no JVM
;; equivalent is checked at the C ABI against this policy:
;;
;;   invalid UTF-8      fails closed: status -2 (the runtime's strict decode
;;                      throws; the message is copied out), no JPX1 buffer.
;;                      A jolt string cannot carry one: jolt decodes invalid
;;                      bytes to U+FFFD, as the JVM does, and its reader
;;                      refuses a lone surrogate escape
;;   output over 64 MiB status -4, no buffer, and cst throws, so no packet;
;;                      the same worker parses again afterwards
;;   invalid arguments  status -1: NULL source with a length, NULL or
;;                      unrecognized worker, NULL out cells

(def input-oracle
  (edn/read-string (slurp "test/yang/python/antlr/fixtures/jolt_j0c_input_oracle.edn")))


(def input-failed (atom []))


(check "input oracle is the JVM's, for this grammar id"
       (and (= :jvm (:host input-oracle)) (= grammar-id (:grammar-id input-oracle)))
       (:written-by input-oracle))


(doseq [{:keys [id source packet first-failure code-points]} (:entries input-oracle)]
  (let [w (cst/make-worker profile)
        read-ok (= code-points (packet/code-points source))
        jolt (try (cst/parse-unit w [:unit 0] source)
                  (catch Throwable e {:thrown (ex-message e), :data (ex-data e)}))
        ff (try (cst/first-failure w source) (catch Throwable e {:thrown (ex-message e)}))
        e1 (cond (not read-ok) [[:source-read-differs (packet/code-points source)]]
                 (:thrown jolt) [[:thrown jolt]]
                 :else (e1-violations id source packet jolt (cst/export-records w source)))
        ok (and (empty? e1) (= first-failure ff))]
    (cst/close-worker w)
    (when-not ok (swap! input-failed conj id))
    (println (if ok "PASS" "FAIL-E1") "input" id
             (pr-str (cond-> {:outcome (:yang.cst/outcome packet), :first-failure first-failure,
                              :strict (= packet jolt)}
                       (seq e1) (assoc :e1-violations e1)
                       (not= first-failure ff) (assoc :jolt-first-failure ff))))))


(ffi/defcfn c-export-raw "jolt_parser_export" [:pointer :pointer :size_t :pointer :pointer] :int :blocking)
(ffi/defcfn c-free-raw "jolt_parser_free" [:pointer] :void)


(defn- raw-export
  "jolt_parser_export on raw bytes; the status and what came back."
  [handle bytes & {:keys [null-src null-out]}]
  (let [bs (byte-array (map unchecked-byte bytes))
        n (alength bs)
        in (ffi/alloc (max 1 n))
        out-cell (ffi/alloc :pointer)
        len-cell (ffi/alloc :size_t)]
    (try
      (when (pos? n) (ffi/write-array in bs))
      (let [status (c-export-raw handle (if null-src ffi/null in) n
                                 (if null-out ffi/null out-cell) len-cell)
            p (if null-out ffi/null (ffi/read out-cell :pointer))
            len (if null-out 0 (ffi/read len-cell :size_t))
            got (if (ffi/null? p) (byte-array 0) (ffi/read-array p len))]
        (when-not (ffi/null? p) (c-free-raw p))
        {:status status,
         :buffer (cond (zero? (alength got)) :none
                       (and (>= (alength got) 4) (= "JPX1" (String. got 0 4 "US-ASCII"))) :jpx1
                       :else (String. got "UTF-8"))})
      (finally (ffi/free in) (ffi/free out-cell) (ffi/free len-cell)))))


(let [w (cst/make-worker profile)
      h @(:yang.antlr.cst/native w)
      bytes-of (fn [s] (map int (.getBytes ^String s "UTF-8")))]
  (doseq [[label bs] [["a lone 0xFF" [0xff]]
                      ["a truncated two-byte sequence at the end" (concat (bytes-of "x = ") [0xc3])]
                      ["an overlong '/'" [0xc0 0xaf]]
                      ["an encoded surrogate U+D800" [0xed 0xa0 0x80]]
                      ["a code point above U+10FFFF" [0xf4 0x90 0x80 0x80]]
                      ["0xFF inside a string literal" (concat (bytes-of "s = '") [0xff] (bytes-of "'\n"))]]]
    (let [r (raw-export h bs)]
      (when-not (= -2 (:status r)) (swap! input-failed conj label))
      (check (str "[invalid UTF-8] " label " fails closed: -2, no JPX1 buffer")
             (and (= -2 (:status r)) (not= :jpx1 (:buffer r)))
             r)))
  ;; one string token whose text alone passes the bound: cheap to lex
  (let [big (str "s = '" (apply str (repeat (* 70 1024) (apply str (repeat 1024 "a")))) "'\n")
        r (try (cst/parse-unit w [:unit 0] big) :packet
               (catch Throwable e (ex-data e)))]
    (when-not (= -4 (:status r)) (swap! input-failed conj :oversize))
    (check "[oversize] output past 64 MiB (one 70 MiB string token) answers -4 and no packet"
           (= -4 (:status r)) r)
    (check "[oversize] the same worker parses again afterwards"
           (= (:packet (first (:entries oracle)))
              (cst/parse-unit w [:unit 0] (:source (first (:entries oracle)))))
           :ok))
  (let [fake (ffi/alloc 256)
        args [["NULL source with a length" (raw-export h [1 2 3] :null-src true)]
              ["NULL out cell" (raw-export h (bytes-of "1\n") :null-out true)]
              ["NULL worker" (raw-export ffi/null (bytes-of "1\n"))]
              ["an unrecognized worker (zeroed memory)" (raw-export fake (bytes-of "1\n"))]]]
    (doseq [[label r] args]
      (when-not (= -1 (:status r)) (swap! input-failed conj label))
      (check (str "[invalid arguments] " label " answers -1, no buffer")
             (and (= -1 (:status r)) (= :none (:buffer r)))
             r))
    (ffi/free fake))
  (cst/close-worker w))


;; -- the completeness probe ----------------------------------------------------

(require '[yang.python.antlr.repl-probe :as probe])


(check "repl-probe's parser is the stand-in (no rule-names: the tracked parser.cljc did not load) with the same grammar id"
       (and (nil? (resolve 'yang.python.antlr.parser/rule-names))
            (= grammar-id @(resolve 'yang.python.antlr.parser/grammar-id)))
       @(resolve 'yang.python.antlr.parser/grammar-id))


(doseq [{:keys [text first-failure-calls], answer :probe} (:probe oracle)]
  (let [calls (volatile! [])
        ff cst/first-failure
        jolt-answer (with-redefs [cst/first-failure
                                  (fn [w s] (let [r (ff w s)] (vswap! calls conj [s r]) r))]
                      (probe/probe text))
        ok (and (= answer jolt-answer) (= first-failure-calls @calls))]
    (when-not ok (swap! diverged conj [:probe text]))
    (println (if ok "PASS" "FAIL") "probe" (pr-str text)
             (pr-str (cond-> {:probe answer, :calls first-failure-calls}
                       (not ok) (assoc :jolt-probe jolt-answer, :jolt-calls @calls))))))


(let [rs @results]
  (println "RECORD totals"
           (pr-str {:entries (count rs),
                    :e1 (count (filter :e1 rs)),
                    :strict-packets-equal (count (filter :strict rs)),
                    :first-failure-equal (count (filter :first-failure rs)),
                    :probe-texts (count (:probe oracle)),
                    :input-entries (count (:entries input-oracle)),
                    :input-failed @input-failed,
                    :diverged @diverged})))


(def ok? (and (empty? @diverged) (empty? @input-failed) (zero? @failed)))


(println "SUMMARY" (if ok?
                     "ok"
                     (str (count @diverged) " diverged, " (count @input-failed)
                          " input-policy failed, " @failed " failed")))


(System/exit (if ok? 0 1))
