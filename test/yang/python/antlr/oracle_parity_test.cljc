(ns yang.python.antlr.oracle-parity-test
  "J2b(a) (docs/design/yin.repl-jolt-implementation.md §7.8): this host's
   Python parser against the committed JVM oracle for the fixed jolt corpus
   (test/yang/python/antlr/fixtures/jolt_j0c_*.edn), so the Node, Dart and
   jolt parsers are all judged against one oracle. On the JVM it is a
   regeneration check: the oracle is what the JVM produces now.

   Per entry: strict packet equality is recorded (a RECORD line with the
   packet's dao.jing content address, the byte-level identity of its
   canonical encoding); the asserted contract is E1, the equivalence the
   jolt adapter meets (src/dev/jolt_j0c_parity.clj): an ok packet is equal
   in full; an error packet is equal apart from :yang.cst/errors, has at
   least one error, and its first error's :kind, :span, :line and :column
   equal the JVM's, with a non-empty :message (message text, expecting sets
   and later errors are exempt). cst/first-failure and the REPL
   completeness probe must answer as on the JVM; the probe's
   first-failure call sequence is compared where vars can be redefined
   (not ClojureDart). The input-policy corpus also checks that the host read
   each source to the JVM's code points.

   J2b(b), the restricted lowering comparison: for every entry whose packet
   is ok on this host and in the oracle, the shared lowering
   (yang.python.antlr.lower/lower-module-body) of this host's packet is
   recorded by its dao.jing content address, or its refusal. The corpus
   holds no float and no integer beyond 64 bits (W8), so a numeric host
   difference cannot masquerade as a parser one. Cross-host equality is read
   from the RECORD lines of each lane, the JVM's being the reference; here
   the host's lowering of its own packet must equal its lowering of the
   oracle packet."
  (:require
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing]]
    [dao.jing :as jing]
    [yang.antlr.cst :as cst]
    [yang.antlr.packet :as packet]
    [yang.python.antlr.lower :as lower]
    [yang.python.antlr.parser :as parser]
    [yang.python.antlr.repl-probe :as probe]
    #?@(:cljd [["dart:io" :as dart-io]])))


(def ^:private host
  #?(:cljd :dart :jolt :jolt :clj :jvm :cljs :node))


(defn- read-text
  [path]
  #?(:cljd (.readAsStringSync (dart-io/File. path))
     :clj (slurp path)
     :cljs (.readFileSync (js/require "fs") path "utf8")))


(def ^:private oracle*
  (delay (edn/read-string
           (read-text "test/yang/python/antlr/fixtures/jolt_j0c_oracle.edn"))))


(def ^:private input-oracle*
  (delay (edn/read-string
           (read-text "test/yang/python/antlr/fixtures/jolt_j0c_input_oracle.edn"))))


;; The divergences from the JVM oracle that remain (owner 2026-10-24: the JVM
;; is canonical; divergent hosts are patched). Each deftest asserts that the
;; failing set is EXACTLY this one, so a new divergence fails, and so does a
;; fixed one until its pin is removed. The lazy-buffer first-failure rule now
;; holds on Node and Dart (their cst namespaces record the furthest sync
;; target), which removed the five probe texts and :bracket-call-open. What is
;; left lies in the per-target lexer helpers that antlr/python3/manifest.edn
;; fetches and patches, so fixing it is a manifest patch plus the gated
;; regeneration (docs/agents/known-gaps.md, the J2b helper-position row):
;; Node's helper drops \f from its newline/indent handling (:indent-formfeed)
;; and never moves a DEDENT to the last token's line
;; (:malformed-unexpected-dedent); Dart's positioned-token patch stamps a
;; helper token with the token-start line/column instead of the lexer's
;; current position (the four Dart entries).
(def ^:private known-divergences
  {:jvm {:corpus #{}, :probe #{}},
   :jolt {:corpus #{}, :probe #{}},
   :node {:corpus #{:indent-formfeed :malformed-unexpected-dedent}, :probe #{}},
   :dart {:corpus #{:blank-lines :malformed-unexpected-indent :malformed-unexpected-newline
                    :malformed-unexpected-dedent},
          :probe #{}}})


(defn- e1-violations
  "E1 of `got` against the JVM's `jvm` packet; [] when it holds."
  [jvm got]
  (let [bad (volatile! [])
        note! (fn [why detail] (vswap! bad conj [why detail]))]
    (if (= :yang.cst/ok (:yang.cst/outcome jvm))
      (when-not (= jvm got) (note! :ok-packet-not-equal (:yang.cst/outcome got)))
      (let [base (fn [p] (dissoc p :yang.cst/errors))
            [je] (:yang.cst/errors jvm)
            [ge] (:yang.cst/errors got)]
        (when-not (= (base jvm) (base got)) (note! :packet-fields nil))
        (when-not (seq (:yang.cst/errors got)) (note! :no-errors nil))
        (when-not (= (:kind je) (:kind ge)) (note! :first-kind [(:kind je) (:kind ge)]))
        (when-not (= (:span je) (:span ge)) (note! :first-span [(:span je) (:span ge)]))
        (when-not (= [(:line je) (:column je)] [(:line ge) (:column ge)])
          (note! :first-line-column [[(:line je) (:column je)] [(:line ge) (:column ge)]]))
        (when-not (and (string? (:message ge)) (seq (:message ge)))
          (note! :first-message (:message ge)))))
    @bad))


(defn- verdict
  "One entry's full verdict, printed as a RECORD line. Nothing is asserted
   here, so a host whose clojure.test stops a deftest at its first failure
   (ClojureDart) still reports every entry."
  [corpus {:keys [id source packet first-failure] :as entry}]
  (let [got (parser/parse-source (parser/make-worker) [:unit 0] source)
        ff (cst/first-failure (parser/make-worker) source)
        valid (try (packet/validate! got {:source source}) true
                   (catch #?(:cljd Object :clj Exception :cljs :default) _ false))
        e1 (e1-violations packet got)
        cps (when-let [cps (:code-points entry)] (= cps (packet/code-points source)))
        v {:host host, :corpus corpus, :id id,
           :strict (= packet got),
           :e1 e1,
           :first-failure (if (= first-failure ff) :same [first-failure ff]),
           :valid valid,
           :code-points (if (nil? cps) :n/a cps),
           :address (jing/content-hash got),
           :oracle-address (jing/content-hash packet)}]
    (println "RECORD j2b" (pr-str v))
    v))


(defn- failing
  [vs]
  (set (keep (fn [{:keys [id e1 first-failure valid code-points]}]
               (when (or (seq e1) (not= :same first-failure) (not valid)
                         (false? code-points))
                 id))
             vs)))


(deftest the-parser-corpus-meets-the-jvm-oracle
  (let [{:keys [entries]} @oracle*]
    (is (= :jvm (:host @oracle*)))
    (is (= parser/grammar-id (:grammar-id @oracle*)))
    (is (= (get-in known-divergences [host :corpus])
           (failing (mapv #(verdict :parser %) entries))))))


(deftest the-input-policy-corpus-meets-the-jvm-oracle
  (let [{:keys [entries]} @input-oracle*]
    (is (= :jvm (:host @input-oracle*)))
    (is (= #{} (failing (mapv #(verdict :input %) entries))))))


(deftest the-completeness-probe-answers-as-on-the-jvm
  (let [vs (mapv (fn [{:keys [text first-failure-calls], answer :probe}]
                   (let [[got calls]
                         #?(:cljd [(probe/probe text) :not-recorded]
                            :default
                            (let [calls (volatile! [])
                                  first-failure cst/first-failure
                                  got (with-redefs [cst/first-failure
                                                    (fn [worker source]
                                                      (let [r (first-failure worker source)]
                                                        (vswap! calls conj [source r])
                                                        r))]
                                        (probe/probe text))]
                              [got @calls]))
                         v {:host host, :corpus :probe, :text text,
                            :answer (if (= answer got) :same [answer got]),
                            :calls (cond (= :not-recorded calls) :not-recorded
                                         (= first-failure-calls calls) :same
                                         :else [first-failure-calls calls])}]
                     (println "RECORD j2b" (pr-str v))
                     v))
                 (:probe @oracle*))]
    (is (= (get-in known-divergences [host :probe])
           (set (keep (fn [{:keys [text answer calls]}]
                        (when (or (not= :same answer)
                                  (not (#{:same :not-recorded} calls)))
                          text))
                      vs))))))


(defn- lowered
  [pk]
  (try {:address (jing/content-hash (lower/lower-module-body pk))}
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         {:refused (or (ex-data e) (str e))})))


(deftest the-lowering-of-each-ok-packet-is-recorded
  (let [vs (vec (for [[corpus o] [[:parser @oracle*] [:input @input-oracle*]]
                      {:keys [id source packet]} (:entries o)
                      :let [got (parser/parse-source (parser/make-worker) [:unit 0] source)]
                      :when (and (= :yang.cst/ok (:yang.cst/outcome packet))
                                 (= :yang.cst/ok (:yang.cst/outcome got)))]
                  (let [mine (lowered got)
                        theirs (lowered packet)
                        v {:host host, :corpus (keyword (str "lower-" (name corpus))), :id id,
                           :lowered mine,
                           :same-as-oracle-packet (= mine theirs)}]
                    (println "RECORD j2b" (pr-str v))
                    v)))]
    (is (seq vs))
    (is (= [] (vec (keep #(when-not (:same-as-oracle-packet %) (:id %)) vs))))))
