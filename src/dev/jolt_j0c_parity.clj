;; J0c round 2, step C: the jolt yang.antlr.cst adapter against the JVM oracle
;; (docs/design/yin.repl-jolt-implementation.md §7.8).
;;
;;   . build/tools/jolt-env.sh
;;   "$JOLT_HOME"/bin/jolt -Srepro \
;;     -Sdeps '{:aliases {:j0c-probe-stub {:extra-paths ["src/dev/jolt-j0c-probe-stub"]}}}' \
;;     -M:jolt:j0c-probe-stub src/dev/jolt_j0c_parity.clj
;;
;; For every jolt_j0c_corpus.edn entry: the packet from the jolt adapter
;; (src/jolt/yang/antlr/cst.jolt, the ANTLR4 C++ parser marshalled into the
;; shared yang.antlr.export) must equal the JVM oracle's packet, and its
;; cst/first-failure the oracle's. For every probe text,
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
(require '[yang.antlr.cst :as cst] '[yang.antlr.export :as export])
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
  [source]
  (let [{:keys [failure tokens]} (cst/export-records source)
        {:keys [kind index]} failure]
    (export/failure-kind
      (case kind 0 nil 1 :lexical 2 index)
      (keep (fn [{:keys [type index start stop]}]
              (when (not= -1 type) {:index index, :start start, :stop stop}))
            tokens))))


(def results (atom []))


(doseq [{:keys [id source packet first-failure]} (:entries oracle)]
  (let [worker (cst/make-worker profile)
        jolt-packet (try (cst/parse-unit worker [:unit 0] source)
                         (catch Throwable e {:thrown (ex-message e), :data (ex-data e)}))
        jolt-ff (try (cst/first-failure worker source)
                     (catch Throwable e {:thrown (ex-message e)}))
        records (cst/export-records source)
        packet-ok (= packet jolt-packet)
        ff-ok (= first-failure jolt-ff)
        alt (alternative-failure source)]
    (swap! results conj {:id id, :packet packet-ok, :first-failure ff-ok})
    (when-not (and packet-ok ff-ok) (swap! diverged conj id))
    (println (if (and packet-ok ff-ok) "PASS" "DIVERGES") id
             (pr-str (cond-> {:outcome (:yang.cst/outcome packet),
                              :nodes (count (:yang.cst/nodes packet)),
                              :first-failure first-failure}
                       (not packet-ok) (assoc :packet-diff (if (:thrown jolt-packet)
                                                             jolt-packet
                                                             (packet-diff packet jolt-packet)))
                       (not ff-ok) (assoc :jolt-first-failure jolt-ff))))
    (println "RECORD token-buffer" id
             (pr-str {:first-error (:failure records),
                      :buffered-after (count (:tokens records)),
                      :offending-token-rule alt,
                      :rules-agree (= alt jolt-ff)}))))


;; -- a failed native parse never yields a packet --------------------------------

(ffi/defcfn c-inject-fault "jolt_parser_inject_fault" [:int] :int)


(doseq [mode [1 2 3]]
  (c-inject-fault mode)
  (let [r (try (cst/parse-unit (cst/make-worker profile) [:unit 0] "1 + 2\n")
               (catch Throwable e {:thrown (ex-data e)})
               (finally (c-inject-fault 0)))]
    (check (str "[injected mode " mode "] parse-unit throws the wrapper status, no packet")
           (and (contains? r :thrown) (#{-2 -3} (get-in r [:thrown :status])))
           r)))


(check "after the injected failures parse-unit answers the oracle packet again"
       (= (:packet (first (:entries oracle)))
          (cst/parse-unit (cst/make-worker profile) [:unit 0]
                          (:source (first (:entries oracle)))))
       (:id (first (:entries oracle))))


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
    (println (if ok "PASS" "DIVERGES") "probe" (pr-str text)
             (pr-str (cond-> {:probe answer, :calls first-failure-calls}
                       (not ok) (assoc :jolt-probe jolt-answer, :jolt-calls @calls))))))


(let [rs @results]
  (println "RECORD totals"
           (pr-str {:entries (count rs),
                    :packets-equal (count (filter :packet rs)),
                    :first-failure-equal (count (filter :first-failure rs)),
                    :probe-texts (count (:probe oracle)),
                    :diverged @diverged})))


(println "SUMMARY" (if (and (empty? @diverged) (zero? @failed))
                     "ok"
                     (str (count @diverged) " diverged, " @failed " failed")))


(System/exit (if (and (empty? @diverged) (zero? @failed)) 0 1))
