(ns yin.vm.pipeline-test
  "D5 of docs/design/yin.vm.debruijn-projection.md: the post-emission
   pipeline that persists the named batch first and the projected envelope
   second, independently — named failure skips projected persistence,
   projection failure never fails the named side, dedupe is DaoJing's
   write idempotence over the canonical envelope, and only complete
   root-framed batches are projected."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.file :as jing-file]
            [yin.vm :as vm]
            [yin.vm.debruijn :as debruijn]
            [yin.vm.pipeline :as pipeline]))


;; =============================================================================
;; Fixtures: a named writer and a DaoJing store, both owned by the test
;; =============================================================================

(defn- lit
  [v]
  {:type :literal, :value v})


(defn- v
  [s]
  {:type :variable, :name s})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [op & args]
  {:type :application, :operator op, :operands (vec args)})


(def ^:private worked-example
  (lam '[count] (app (v '+) (v 'count) (lit 1))))


(defn- recording-writer
  "A named writer answering :ok with a transactor-shaped receipt and
   recording every batch it accepted — and, given a shared event log,
   logging [:named-write] there so its order against the store shows."
  ([] (recording-writer nil))
  ([log]
   (let [batches (atom [])]
     {:batches batches,
      :writer (fn [datoms]
                (swap! batches conj (vec datoms))
                (when log (swap! log conj [:named-write]))
                {:dao.stream/outcome :dao.stream/ok,
                 :dao.space/t 7,
                 :dao.space/datoms (vec datoms)})})))


(defn- answering-writer
  "A named writer answering one fixed non-ok outcome, recording batches."
  [outcome]
  (let [batches (atom [])]
    {:batches batches,
     :writer (fn [datoms]
               (swap! batches conj (vec datoms))
               {:dao.stream/outcome outcome})}))


(defn- mem-store
  "The jing-test in-memory content store: :inserted on the first write of
   an address, :present afterwards, never overwriting. The backing atom is
   exposed as :store so the tests observe dedupe directly; given a shared
   event log, every put logs [:put address result] and every get
   [:get address]."
  ([] (mem-store nil))
  ([log]
   (let [store (atom {})
         note! (fn [event] (when log (swap! log conj event)))]
     (assoc {:store store}
            :put-content-fn (fn [address payload]
                              (let [result (if (contains? @store address)
                                             :present
                                             (do (swap! store assoc address payload)
                                                 :inserted))]
                                (note! [:put address result])
                                result))
            :get-content-fn (fn [address not-found]
                              (note! [:get address])
                              (if (contains? @store address)
                                (get @store address)
                                not-found))))))


(defn- refusing-store
  "A content store whose backend answers with an invalid result, so
   materialize! throws."
  []
  {:put-content-fn (fn [_address _payload] :not-a-result),
   :get-content-fn (fn [_address not-found] not-found)})


(defn- persist!
  ([ast]
   (persist! ast (recording-writer) (mem-store)))
  ([ast writer store]
   (let [result (pipeline/persist-compiled! {:ast ast,
                                             :named-writer (:writer writer),
                                             :projected-store store,
                                             :provenance {:batch :test}})]
     (assoc result
            :batches @(:batches writer)
            :store-contents (when-let [backing (:store store)]
                              @backing)))))


;; =============================================================================
;; Separation and the happy path
;; =============================================================================

(deftest named-and-projected-persist-separately
  (let [writer (recording-writer)
        store (mem-store)
        result (persist! worked-example writer store)
        expected-datoms (second (vm/ast->datoms-with-root worked-example))
        address (get-in result [:projected :address])]
    (is (= :ok (:outcome (:named result))))
    (is (= [expected-datoms] (:batches result))
        "the named writer received the COMPLETE emission, root marker last")
    (is (= 7 (get-in result [:named :receipt :dao.space/t]))
        "the writer's own receipt rides under :receipt")
    (is (= :ok (:outcome (:projected result))))
    (is (= (:fingerprint (debruijn/project-datoms expected-datoms))
           (get-in result [:projected :fingerprint])))
    (is (jing/segment-address? address))
    (is (= {:yin.debruijn/fingerprint (get-in result [:projected :fingerprint]),
            :yin.debruijn/datoms (debruijn/projected->datoms
                                   (debruijn/project-datoms expected-datoms))}
           (jing/get store address ::absent))
        "the store holds exactly the canonical envelope under its address")
    (is (= {:batch :test} (:provenance result))
        "provenance is echoed opaque, never interpreted")
    (is (not-any? #(= "yin.debruijn" (namespace (nth % 1))) (first (:batches result)))
        "no projected record entered the named batch — named datoms stay :yin/*")
    (is (every? #(= "yin.debruijn" (namespace (nth % 1)))
                (:yin.debruijn/datoms (jing/get store address ::absent)))
        "the envelope's datoms are all :yin.debruijn/* — a separate segment")))


;; =============================================================================
;; Failure isolation
;; =============================================================================

(deftest projection-failure-never-fails-the-named-side
  (let [writer (recording-writer)
        store (mem-store)
        result (persist! (lam '[x] (app (v 'f) (lit (fn [q] q)))) writer store)]
    (is (= :ok (:outcome (:named result))))
    (is (= 1 (count (:batches result)))
        "the defective batch still persisted on the named side")
    (is (= :diagnostic (:outcome (:projected result))))
    (is (= :unsupported-value (:rule (get-in result [:projected :diagnostic]))))
    (is (empty? @(:store store))
        "no envelope was written for an unprojectable batch")))


(deftest projected-write-failure-is-reported-not-thrown
  (let [writer (recording-writer)
        result (persist! worked-example writer (refusing-store))]
    (is (= :ok (:outcome (:named result))))
    (is (= :diagnostic (:outcome (:projected result))))
    (is (= :projected-write-failed (get-in result [:projected :diagnostic :rule])))
    (is (= [:address :result]
           (sort (keys (get-in result [:projected :diagnostic :cause])))))
    (is (= :not-a-result (get-in result [:projected :diagnostic :cause :result]))
        "the store's answer rides under :cause — never the envelope payload")))


(deftest named-failure-skips-projected-persistence
  (let [writer (answering-writer :dao.stream/full)
        store (mem-store)
        result (persist! worked-example writer store)]
    (is (= :dao.stream/full (:outcome (:named result))))
    (is (= {:dao.stream/outcome :dao.stream/full} (get-in result [:named :receipt])))
    (is (= :not-attempted (:outcome (:projected result))))
    (is (= :dao.stream/full (:named-outcome (:projected result))))
    (is (empty? @(:store store))
        "projected persistence is never attempted after a named failure")))


;; =============================================================================
;; Dedupe: fingerprint identity, write-idempotent storage
;; =============================================================================

(deftest equal-projections-dedupe-to-one-envelope
  (let [log (atom [])
        writer (recording-writer log)
        store (mem-store log)
        first-result (persist! worked-example writer store)
        second-result (persist! (lam '[n] (app (v '+) (v 'n) (lit 1))) writer store)
        ;; snapshot before this test's own reads touch the store
        events @log
        address (get-in first-result [:projected :address])]
    (is (= [[:named-write]
            [:put address :inserted]
            [:named-write]
            [:put address :present]
            [:get address]]
           events)
        "the exact sequence: each named write precedes its store put, the
         second put finds :present, and the only get is materialize!'s
         read-back after :present — no pre-write lookup, no overwrite")
    (is (not-any? #(= :get (first %))
                  (take-while #(not= :put (first %)) events))
        "no get precedes the first put")
    (is (= (get-in first-result [:projected :fingerprint])
           (get-in second-result [:projected :fingerprint]))
        "alpha-equivalent programs share the Merkle fingerprint")
    (is (= (get-in first-result [:projected :address])
           (get-in second-result [:projected :address]))
        "equal envelopes content-address to one segment")
    (is (= 1 (count @(:store store)))
        "the second write found :present: nothing overwritten, still one entry")
    (is (= 2 (count (:batches second-result)))
        "named artifacts are never deduplicated by fingerprint")))


(deftest different-programs-keep-different-identities
  (let [one (persist! worked-example)
        two (persist! (lam '[x] (v 'y)))]
    (is (not= (get-in one [:projected :fingerprint])
              (get-in two [:projected :fingerprint])))
    (is (not= (get-in one [:projected :address])
              (get-in two [:projected :address])))))


;; =============================================================================
;; Ordering: complete batches only, per-batch state
;; =============================================================================

(deftest partial-and-rootless-batches-reject-before-persistence
  (let [writer (recording-writer)
        store (mem-store)
        complete (second (vm/ast->datoms-with-root worked-example))
        rootless (vec (butlast complete))
        call (fn [datoms]
               (pipeline/persist-compiled! {:datoms datoms,
                                            :named-writer (:writer writer),
                                            :projected-store store,
                                            :provenance nil}))]
    (testing "a mid-emission prefix"
      (let [result (call rootless)]
        (is (= :rejected (:outcome (:named result))))
        (is (= :not-attempted (:outcome (:projected result))))
        (is (= :partial-frame (:rule (:named result)) (:rule (:projected result)))
            "the rule is the framing gate's own")))
    (testing "an entirely rootless batch"
      (let [result (call (filter #(not= :yin/root (nth % 1)) complete))]
        (is (= :rejected (:outcome (:named result))))
        (is (= :not-attempted (:outcome (:projected result)))))
      (is (empty? @(:batches writer)) "the writer was never called")
      (is (empty? @(:store store))
          "and nothing was ever projected"))))


(deftest only-exactly-one-complete-frame-persists
  (let [complete (second (vm/ast->datoms-with-root worked-example))
        other (second (vm/ast->datoms-with-root (lam '[x] (v 'y))))
        retracting (mapv (fn [[e a val t :as d]]
                           (if (= :yin/type a) [e a val t 0] d))
                         complete)]
    (doseq [[label datoms rule]
            [["a complete graph trailed by an unfinished one"
              (into complete (butlast other))
              :partial-frame]
             ["two concatenated complete graphs"
              (into complete other)
              :multiple-frames]
             ["a retract datom" retracting :retract]
             ["a malformed datom" (conj complete [-16 :yin/type]) :malformed-datom]
             ["an empty batch" [] :missing-root]]]
      (testing label
        (let [log (atom [])
              writer (recording-writer log)
              store (mem-store log)
              result (pipeline/persist-compiled! {:datoms datoms,
                                                  :named-writer (:writer writer),
                                                  :projected-store store,
                                                  :provenance nil})]
          (is (= {:outcome :rejected, :rule rule} (:named result)))
          (is (= {:outcome :not-attempted, :rule rule} (:projected result)))
          (is (empty? @log) "neither the writer nor the store was touched"))))))


(deftest writer-answers-without-a-keyword-outcome-are-classified
  (doseq [answer [nil 5 {} {:dao.stream/outcome "ok"}]]
    (let [store (mem-store)
          result (pipeline/persist-compiled! {:ast worked-example,
                                              :named-writer (fn [_] answer),
                                              :projected-store store,
                                              :provenance nil})]
      (is (= {:outcome :invalid-writer-answer, :receipt answer} (:named result))
          (str "answer " (pr-str answer)))
      (is (= :not-attempted (:outcome (:projected result))))
      (is (empty? @(:store store))))))


(deftest a-request-takes-exactly-one-source
  (let [writer (recording-writer)
        rule-of (fn [request]
                  (try (pipeline/persist-compiled! (merge {:named-writer (:writer writer),
                                                           :projected-store (mem-store)}
                                                          request))
                       nil
                       (catch #?(:cljd Object :clj Exception :cljs :default) e
                         (:rule (ex-data e)))))]
    (is (= :bad-request
           (rule-of {:ast worked-example,
                     :datoms (second (vm/ast->datoms-with-root worked-example))}))
        "both :ast and :datoms")
    (is (= :bad-request (rule-of {})) "neither")
    (is (empty? @(:batches writer)))))


(deftest projection-defects-are-internal-errors-not-diagnostics
  ;; no well-framed input reaches an internal defect, so one is injected by
  ;; redefining the projection; :cljd first — ClojureDart does not redefine
  ;; vars, and its host pass also reads :clj
  #?(:cljd nil
     :default (let [result (with-redefs [debruijn/project-datoms
                                         (fn [_]
                                           (throw (ex-info "projection defect"
                                                           {:rule :missing-record})))]
                             (persist! worked-example))]
                (is (= :ok (:outcome (:named result))))
                (is (= :internal-error (:outcome (:projected result))))
                (is (= :internal-error (get-in result [:projected :diagnostic :rule])))
                (is (= {:rule :missing-record}
                       (get-in result [:projected :diagnostic :data])))))
  (let [result (persist! (lam '[x] (app (v 'f) (lit (fn [q] q)))))]
    (is (= :diagnostic (:outcome (:projected result)))
        "invalid input stays a :diagnostic, distinct from :internal-error")))


(deftest envelope-build-defects-are-internal-errors-not-write-failures
  ;; the envelope is built before any store call, so its writer's own
  ;; defect (:missing-record) classifies as internal, never as the store's
  ;; :projected-write-failed; :cljd first, as above
  #?(:cljd nil
     :default (let [store (mem-store)
                    result (with-redefs [debruijn/projected->datoms
                                         (fn [_]
                                           (throw (ex-info "envelope defect"
                                                           {:rule :missing-record})))]
                             (persist! worked-example (recording-writer) store))]
                (is (= :ok (:outcome (:named result))))
                (is (= :internal-error (:outcome (:projected result))))
                (is (= :internal-error (get-in result [:projected :diagnostic :rule])))
                (is (not= :projected-write-failed
                          (get-in result [:projected :diagnostic :rule])))
                (is (empty? @(:store store)) "the store was never called"))))


(deftest a-framing-gate-defect-rejects-with-its-rule-only
  ;; a plain host error thrown by the gate itself: rejected before either
  ;; side persists, carrying only :rule :internal-error; :cljd first
  #?(:cljd nil
     :default (let [writer (recording-writer)
                    store (mem-store)
                    result (with-redefs [debruijn/frame-datoms
                                         (fn [_]
                                           (throw #?(:clj (RuntimeException. "gate defect")
                                                     :cljs (js/Error. "gate defect"))))]
                             (persist! worked-example writer store))]
                (is (= {:outcome :rejected, :rule :internal-error} (:named result)))
                (is (= {:outcome :not-attempted, :rule :internal-error} (:projected result)))
                (is (empty? @(:batches writer)))
                (is (empty? @(:store store))))))


(deftest adjacent-batches-reusing-tempids-project-independently
  (let [writer (recording-writer)
        store (mem-store)
        first-result (persist! worked-example writer store)
        second-result (persist! worked-example writer store)]
    (is (every? #(= :ok (:outcome %)) (map :projected [first-result second-result])))
    (is (= (get-in first-result [:projected :address])
           (get-in second-result [:projected :address]))
        "per-batch framing and index state: both -16-based emissions
         project to the same one graph")
    (is (= 2 (count (:batches second-result)))
        "each call persisted its own named batch")))


;; =============================================================================
;; D6 gaps: durable round trip and stored-tuple equality
;; =============================================================================

(defn- temp-path
  []
  (str "target/test-pipeline-projected-" (random-uuid) ".log"))


(defn- cleanup-file
  "Delete the file at path; :cljd first, since the ClojureDart host pass
   also reads :clj."
  [path]
  #?(:cljd (try (let [f (dart-io/File. path)]
                  (when (.existsSync f) (.deleteSync f)))
                (catch Object _ nil))
     :clj (let [f (java.io.File. path)] (when (.exists f) (.delete f)))
     :cljs (try (.unlinkSync (js/require "fs") path) (catch :default _))))


(deftest a-nil-literal-round-trips-through-durable-storage
  (let [path (temp-path)
        ast (lam '[x] (app (v 'f) (lit nil) (v 'x)))]
    (try
      (let [store (jing-file/create-content-file path)
            result (persist! ast (recording-writer) store)
            address (get-in result [:projected :address])
            _ (jing/close! store)
            ;; reopen: the envelope now comes back through the backend's
            ;; own text codec, replayed from disk
            reopened (jing-file/create-content-file path)
            stored (:yin.debruijn/datoms (jing/get reopened address ::absent))]
        (jing/close! reopened)
        (is (= :ok (:outcome (:projected result))))
        (is (some (fn [d] (and (= :yin.debruijn/value (nth d 1)) (nil? (nth d 2))))
                  stored)
            "the nil-valued datom survived storage as a present fact")
        (is (= (select-keys (debruijn/project-datoms
                              (second (vm/ast->datoms-with-root ast)))
                            [:fingerprint :records])
               (debruijn/datoms->projected stored))
            "the reader verifies every stored record: no :hash-mismatch"))
      (finally (cleanup-file path)))))


(def ^:private durable-programs
  "Between them: every node type the emitter produces (a macro lambda
   outside operator position included) and every scalar class the value
   table accepts that the file store's EDN codec carries on every host —
   nil, both booleans, a decomposed-NFC string, int, integral and
   non-integral doubles, keyword, symbol, vector, set, and map.
   Negative zero and lists are NOT here: dao.jing.file's text codec does
   not carry them on every host. On CLJS pr-str spells -0.0 as 0, which
   the store's print-based address does not notice, so the projected
   reader diagnoses :hash-mismatch; on Dart a list literal fails the
   store's own codec round-trip check, so the write is refused. Both are
   pinned by `values-the-file-codec-cannot-carry-are-refused-not-corrupted`;
   lists stay covered in memory by `list-literals-persist-in-memory`."
  [(app (lam '[x y]
             {:type :if,
              :test (app (v '<) (v 'x) (lit 1)),
              :consequent {:type :stream/put,
                           :target {:type :stream/make, :buffer 4},
                           :val {:type :stream/next,
                                 :source {:type :stream/cursor,
                                          :source {:type :stream/close,
                                                   :source {:type :vm/store-get,
                                                            :key 'k}}}}},
              :alternate (app (v 'list)
                              {:type :vm/store-put, :key 'k, :val 5}
                              {:type :vm/gensym, :prefix "g"}
                              {:type :vm/current-continuation}
                              {:type :vm/park}
                              {:type :vm/resume, :parked-id :p1, :val (v 'y)}
                              {:type :dao.stream.apply/call,
                               :op :op/echo,
                               :operands [(lit 1)]}
                              {:type :lambda, :macro? true, :params '[m], :body (v 'm)})})
        (lit 3))
   (app (v 'list)
        (lit nil)
        (lit true)
        (lit false)
        (lit "é")
        (lit 7)
        (lit 2.0)
        (lit 1.5)
        (lit :ns/kw)
        (lit 'ns/sym)
        (lit [1 "a" :k])
        (lit #{1 "b" :c})
        (lit {:a [1 2], "k" #{nil}}))])


(deftest every-node-type-and-scalar-class-round-trips-through-durable-storage
  (let [path (temp-path)]
    (try
      (let [store (jing-file/create-content-file path)
            addresses (mapv (fn [ast]
                              (get-in (persist! ast (recording-writer) store)
                                      [:projected :address]))
                            durable-programs)
            _ (jing/close! store)
            reopened (jing-file/create-content-file path)
            stored (mapv (fn [address]
                           (:yin.debruijn/datoms (jing/get reopened address ::absent)))
                         addresses)]
        (jing/close! reopened)
        (is (every? jing/segment-address? addresses))
        (doseq [[ast datoms] (map vector durable-programs stored)]
          (is (= (select-keys (debruijn/project-datoms
                                (second (vm/ast->datoms-with-root ast)))
                              [:fingerprint :records])
                 (debruijn/datoms->projected datoms))
              "the reopened envelope reads back to the original projection")))
      (finally (cleanup-file path)))))


(def ^:private host
  ;; :cljd first: the ClojureDart host pass also reads :clj
  #?(:cljd :cljd :clj :clj :cljs :cljs))


(deftest values-the-file-codec-cannot-carry-are-refused-not-corrupted
  ;; one law on every host: a durable round trip is exact, or it is refused
  ;; loudly — at write, nothing stored (Dart's list case), or at read as
  ;; :hash-mismatch (CLJS's -0.0 case) — never a silent change of value.
  ;; No case requires a refusal on any host, so a codec fix cannot break
  ;; it; :exact-on names only the hosts already shown to carry the value.
  (doseq [{:keys [label ast exact-on]}
          [{:label "negative zero",
            :ast (app (v 'list) (lit -0.0) (lit 0.0)),
            :exact-on #{:clj :cljd}}
           {:label "a list of integers",
            :ast (app (v 'list) (lit (list 1 2))),
            :exact-on #{:clj}}
           {:label "a list holding a double",
            :ast (app (v 'list) (lit (list 1 2.5))),
            :exact-on #{:clj}}]]
    (testing label
      (let [path (temp-path)]
        (try
          (let [store (jing-file/create-content-file path)
                projected (:projected (persist! ast (recording-writer) store))
                _ (jing/close! store)
                original (select-keys (debruijn/project-datoms
                                        (second (vm/ast->datoms-with-root ast)))
                                      [:fingerprint :records])]
            (if (= :ok (:outcome projected))
              (let [reopened (jing-file/create-content-file path)
                    stored (:yin.debruijn/datoms
                             (jing/get reopened (:address projected) ::absent))
                    _ (jing/close! reopened)
                    read-back (try (debruijn/datoms->projected stored)
                                   (catch #?(:cljd Object :clj Exception :cljs :default) e
                                     (ex-data e)))]
                (is (or (= original read-back) (= :hash-mismatch (:rule read-back)))
                    "stored: exact, or refused at read as :hash-mismatch"))
              (do (is (= :diagnostic (:outcome projected)))
                  (is (= :projected-write-failed (get-in projected [:diagnostic :rule]))
                      "refused at write by the store's own codec check")
                  (is (empty? (jing-file/records path))
                      "nothing was half-written for the refused program")))
            (when (contains? exact-on host)
              (is (= :ok (:outcome projected)))
              (when (= :ok (:outcome projected))
                (is (= original
                       (let [reopened (jing-file/create-content-file path)
                             stored (:yin.debruijn/datoms
                                      (jing/get reopened (:address projected) ::absent))]
                         (jing/close! reopened)
                         (debruijn/datoms->projected stored)))
                    "hosts already shown to carry the value carry it exactly"))))
          (finally (cleanup-file path)))))))


(deftest list-literals-persist-in-memory
  ;; the durable file codec does not carry lists on every host; the
  ;; pipeline itself does, and keeps a list distinct from a vector
  (let [store (mem-store)
        as-list (persist! (app (v 'list) (lit (list 1 2.5))) (recording-writer) store)
        as-vector (persist! (app (v 'list) (lit [1 2.5])) (recording-writer) store)]
    (is (= :ok (:outcome (:projected as-list)) (:outcome (:projected as-vector))))
    (is (= {:yin.debruijn/fingerprint (get-in as-list [:projected :fingerprint]),
            :yin.debruijn/datoms (debruijn/projected->datoms
                                   (debruijn/project-datoms
                                     (second (vm/ast->datoms-with-root
                                               (app (v 'list) (lit (list 1 2.5)))))))}
           (jing/get store (get-in as-list [:projected :address]) ::absent)))
    (is (not= (get-in as-list [:projected :fingerprint])
              (get-in as-vector [:projected :fingerprint])))))


(deftest renamed-programs-store-equal-projected-datoms
  (let [one-store (mem-store)
        two-store (mem-store)
        one (persist! worked-example (recording-writer) one-store)
        two (persist! (lam '[n] (app (v '+) (v 'n) (lit 1))) (recording-writer) two-store)
        stored (fn [store result]
                 (:yin.debruijn/datoms
                   (jing/get store (get-in result [:projected :address]) ::absent)))]
    (is (seq (stored one-store one)))
    (is (= (stored one-store one) (stored two-store two))
        "two separate stores hold the identical projected tuples for an
         alpha-equivalent pair")))
