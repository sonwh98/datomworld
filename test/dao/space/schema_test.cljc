(ns dao.space.schema-test
  "Contract tests for dao.space.schema: the schema interpreter over the
   tuple space (docs/design/dao.space.schema.md).

   Covers schema representation (axioms, bootstrap, type-pred),
   extraction (extract-schema, resolve-props), the schema/current view,
   the validating write wrapper, schema-row validation, and the
   publisher (publish!, parity tests)."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.jing.file :as jing-file]
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.space.schema :as schema]
            [dao.space.transactor :as tx]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.memory-log :as memory-log]
            [dao.stream.v2.ringbuffer :as ringbuffer]
            #?@(:cljd [["dart:io" :as dart-io]])))


;; ---------------------------------------------------------------------------
;; Shared helpers
;; ---------------------------------------------------------------------------

(def ^:private eid
  "Conventional genesis entity ids for the five :db/* property idents."
  {:db/ident 16, :db/valueType 17, :db/cardinality 18,
   :db/unique 19, :db/index 20})


(defn- boot-rows
  "Build d5 rows from (schema/bootstrap) triples at a given t."
  ([t] (boot-rows t 1))
  ([t m]
   (mapv (fn [[_ e a v]] [e a v t m]) (schema/bootstrap))))


;; ---------------------------------------------------------------------------
;; T1: axioms shape
;; ---------------------------------------------------------------------------

(deftest axioms-shape
  (testing "all five property idents present"
    (is (= #{:db/ident :db/valueType :db/cardinality :db/unique :db/index}
           (set (keys schema/axioms)))))
  (testing "all five are card-one"
    (is (every? #(= :db.cardinality/one
                    (get-in schema/axioms [% :db/cardinality]))
                (keys schema/axioms))))
  (testing "only :db/ident has :db/unique"
    (is (true? (get-in schema/axioms [:db/ident :db/unique])))
    (is (every? #(nil? (get-in schema/axioms [% :db/unique]))
                [:db/valueType :db/cardinality :db/unique :db/index])))
  (testing ":db/unique and :db/index are :db.type/boolean"
    (is (= :db.type/boolean (get-in schema/axioms [:db/unique :db/valueType])))
    (is (= :db.type/boolean (get-in schema/axioms [:db/index :db/valueType]))))
  (testing "all valueType keywords are in type-pred"
    (is (every? #(contains? schema/type-pred
                            (get-in schema/axioms [% :db/valueType]))
                (keys schema/axioms)))))


;; ---------------------------------------------------------------------------
;; T2: bootstrap expansion
;; ---------------------------------------------------------------------------

(deftest bootstrap-expands-to-axioms
  (let [triples (schema/bootstrap)]
    (testing "every triple is [:db/add e a v] (4 elements)"
      (is (every? #(and (vector? %) (= 4 (count %)) (= :db/add (first %)))
                  triples)))
    (testing "ident rows present for each entity"
      (let [ident-rows (filter #(= :db/ident (nth % 2)) triples)]
        (is (= 5 (count ident-rows)))
        (is (= #{[:db/add 16 :db/ident :db/ident]
                 [:db/add 17 :db/ident :db/valueType]
                 [:db/add 18 :db/ident :db/cardinality]
                 [:db/add 19 :db/ident :db/unique]
                 [:db/add 20 :db/ident :db/index]}
               (set ident-rows)))))
    (testing "[e a v] set = ident rows + axioms expanded"
      (let [ident-set (set (mapcat (fn [[ident _]]
                                     [((fn [] [(get eid ident) :db/ident ident]))])
                                   schema/axioms))
            axiom-set (set (mapcat (fn [[ident props]]
                                     (map (fn [[a v]] [(get eid ident) a v]) props))
                                   schema/axioms))]
        (is (= (into ident-set axiom-set)
               (set (map subvec triples (repeat 1)))))))
    (testing "entities exactly 16..20"
      (is (= (set (range 16 21))
             (set (map second triples)))))))


;; ---------------------------------------------------------------------------
;; T3: extraction reads user attributes
;; ---------------------------------------------------------------------------

(deftest extraction-reads-user-attributes
  (let [d5-rows (into (boot-rows 0)
                      [[21 :db/ident :person/name 1 1]
                       [21 :db/valueType :db.type/string 1 1]
                       [21 :db/cardinality :db.cardinality/one 1 1]])]
    (is (= {:person/name {:db/valueType :db.type/string
                          :db/cardinality :db.cardinality/one}}
           (dissoc (schema/extract-schema d5-rows)
                   :db/ident :db/valueType :db/cardinality :db/unique :db/index)))))


;; ---------------------------------------------------------------------------
;; T4: extraction supersedes redeclaration
;; ---------------------------------------------------------------------------

(deftest extraction-supersedes-redeclaration
  (let [d5-rows [[21 :db/ident :person/friends 0 1]
                 [21 :db/cardinality :db.cardinality/one 1 1]
                 [21 :db/cardinality :db.cardinality/many 2 1]]]
    (is (= :db.cardinality/many
           (get-in (schema/extract-schema d5-rows)
                   [:person/friends :db/cardinality])))))


;; ---------------------------------------------------------------------------
;; T5: retracted property absent
;; ---------------------------------------------------------------------------

(deftest retracted-property-absent
  (let [d5-rows [[21 :db/ident :person/name 0 1]
                 [21 :db/valueType :db.type/string 1 1]
                 [21 :db/valueType :db.type/string 2 0]]]
    (is (not (contains? (get (schema/extract-schema d5-rows)
                             :person/name)
                        :db/valueType)))))


;; ---------------------------------------------------------------------------
;; T6: reassert after retract
;; ---------------------------------------------------------------------------

(deftest reassert-after-retract
  (let [d5-rows [[21 :db/ident :person/name 0 1]
                 [21 :db/valueType :db.type/string 1 1]
                 [21 :db/valueType :db.type/string 2 0]
                 [21 :db/valueType :db.type/keyword 3 1]]]
    (is (= :db.type/keyword
           (get-in (schema/extract-schema d5-rows)
                   [:person/name :db/valueType])))))


;; ---------------------------------------------------------------------------
;; T7: same-t supersession resolves new value
;; ---------------------------------------------------------------------------

(deftest same-t-supersession-resolves-new-value
  (let [d5-rows [[21 :db/ident :person/name 0 1]
                 [21 :db/valueType :db.type/long 5 0]
                 [21 :db/valueType :db.type/string 5 1]]]
    (is (= :db.type/string
           (get-in (schema/extract-schema d5-rows)
                   [:person/name :db/valueType])))))


;; ---------------------------------------------------------------------------
;; T8: full tie collapses to least v
;; ---------------------------------------------------------------------------

(deftest full-tie-collapses-to-least-v
  ;; Both values are keywords (type-rank 4). Same-rank comparison uses
  ;; `compare` on names: "keyword" < "string" alphabetically, so
  ;; :db.type/keyword is the least v under index/compare-vals.
  (let [d5-rows [[21 :db/ident :person/attr 0 1]
                 [21 :db/valueType :db.type/keyword 5 1]
                 [21 :db/valueType :db.type/string 5 1]]]
    (is (= :db.type/keyword
           (get-in (schema/extract-schema d5-rows)
                   [:person/attr :db/valueType])))))


;; ---------------------------------------------------------------------------
;; T9: unknown :db.type throws
;; ---------------------------------------------------------------------------

(deftest unknown-db-type-throws
  (let [d5-rows [[21 :db/ident :test/attr 0 1]
                 [21 :db/valueType :db.type/instant 0 1]
                 [21 :db/cardinality :db.cardinality/one 0 1]]]
    (is (thrown-with-msg?
          #?(:cljs js/Error
             :cljd Object
             :default Exception)
          #"unknown :db.type"
          (schema/extract-schema d5-rows)))))


;; ---------------------------------------------------------------------------
;; T10: unknown :db/* name ignored
;; ---------------------------------------------------------------------------

(deftest unknown-db-star-name-ignored
  (let [base [[21 :db/ident :test/attr 0 1]
              [21 :db/valueType :db.type/string 0 1]
              [21 :db/cardinality :db.cardinality/one 0 1]]
        with-unknown (conj (vec base) [30 :db/foo :bar 5 1])]
    (is (= (schema/extract-schema base)
           (schema/extract-schema with-unknown)))))


;; ---------------------------------------------------------------------------
;; T11: meta-properties resolve from axioms
;; ---------------------------------------------------------------------------

(deftest meta-properties-resolve-from-axioms
  (let [d5-rows [[16 :db/cardinality :db.cardinality/many 0 1]
                 [21 :db/ident :test/attr 0 1]
                 [21 :db/valueType :db.type/string 0 1]
                 [21 :db/cardinality :db.cardinality/one 0 1]]]
    (testing "resolve-props ignores stream rows for :db/* idents"
      (is (= (get schema/axioms :db/ident)
             (schema/resolve-props (schema/extract-schema d5-rows) :db/ident))))
    (testing "extract-schema still reads user attributes"
      (is (= {:db/valueType :db.type/string
              :db/cardinality :db.cardinality/one}
             (get (schema/extract-schema d5-rows) :test/attr))))))


;; ---------------------------------------------------------------------------
;; T12: conflicting property history throws
;; ---------------------------------------------------------------------------

(deftest conflicting-property-history-throws
  (let [d5-rows [[16 :db/ident :db/ident 0 1]
                 [16 :db/ident :db/ident 0 0]]]
    (is (thrown-with-msg?
          #?(:cljs js/Error
             :cljd Object
             :default Exception)
          #"conflicting"
          (schema/extract-schema d5-rows)))))


;; ---------------------------------------------------------------------------
;; T13: no-ident property rows ignored
;; ---------------------------------------------------------------------------

(deftest no-ident-property-rows-ignored
  (let [d5-rows [[99 :db/valueType :db.type/string 0 1]
                 [99 :db/cardinality :db.cardinality/one 0 1]]]
    (is (= {} (schema/extract-schema d5-rows)))))


;; ---------------------------------------------------------------------------
;; T14: ident tie-break resolves to least v
;; ---------------------------------------------------------------------------

(deftest ident-tie-break-resolves-to-least-v
  ;; Two live :db/ident rows for one entity, same (t, m), values :person/a
  ;; and :person/b. Under index/compare-vals, "a" < "b" alphabetically,
  ;; so the entity is named :person/a.
  (let [d5-rows [[21 :db/ident :person/a 5 1]
                 [21 :db/ident :person/b 5 1]
                 [21 :db/valueType :db.type/string 5 1]
                 [21 :db/cardinality :db.cardinality/one 5 1]]]
    (is (= :person/a
           (first (keys (schema/extract-schema d5-rows)))))))


;; ---------------------------------------------------------------------------
;; T15: a reified metadata reference does not erase a surviving fact
;; ---------------------------------------------------------------------------

(deftest reified-metadata-reference-survives-schema-extraction
  (let [metadata-e datom/first-user-id
        d5-rows [[21 :db/ident :person/name 0 metadata-e]
                 [21 :db/valueType :db.type/string 0 metadata-e]
                 [21 :db/cardinality :db.cardinality/one 0 metadata-e]]]
    (is (= {:db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}
           (get (schema/extract-schema d5-rows) :person/name)))
    (is (= #{[21 :db/ident :person/name]
             [21 :db/valueType :db.type/string]
             [21 :db/cardinality :db.cardinality/one]}
           (query/collect
             (query/q '[:find ?e ?a ?v :where [?e ?a ?v]]
                      (schema/current (query/relation d5-rows))))))))


;; ===========================================================================
;; The read view: schema/current tests
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; Read view helpers
;; ---------------------------------------------------------------------------

(defn- qq
  "Collect a q result: (qq form & inputs)."
  [form & inputs]
  (query/collect (apply query/q form inputs)))


(def ^:private schema-rows
  "Minimal bootstrap + :person/name (string, card-one) + :person/friends (ref, card-many)."
  (into (boot-rows 0)
        [[21 :db/ident :person/name 0 1]
         [21 :db/valueType :db.type/string 0 1]
         [21 :db/cardinality :db.cardinality/one 0 1]
         [22 :db/ident :person/friends 0 1]
         [22 :db/valueType :db.type/ref 0 1]
         [22 :db/cardinality :db.cardinality/many 0 1]]))


;; ---------------------------------------------------------------------------
;; W1: view collapses card-one
;; ---------------------------------------------------------------------------

(deftest view-collapses-card-one
  (let [data (into schema-rows
                   [[7 :db/ident :person 0 1] ; dummy, not needed
                    [7 :person/name "old" 0 1]
                    [7 :person/name "new" 1 1]])
        rel  (query/relation data)
        ;; schema/current collapses card-one
        schema-cur (schema/current rel)
        ;; raw query/current does NOT collapse
        raw-cur    (query/current rel)]
    (testing "schema/current yields only the latest card-one value"
      (is (= #{["new"]}
             (qq '[:find ?v :where [7 :person/name ?v]] schema-cur))))
    (testing "raw query/current yields both (the lens differs)"
      (is (= #{["old"] ["new"]}
             (qq '[:find ?v :where [7 :person/name ?v]] raw-cur))))))


;; ---------------------------------------------------------------------------
;; W2: out-of-order retraction resolves v2
;; ---------------------------------------------------------------------------

(deftest out-of-order-retraction-resolves-v2
  ;; [e a v1 1 assert] [e a v2 2 assert] [e a v1 3 retract]
  ;; Step 1 removes v1 (retraction wins), leaving v2 live.
  (let [data (into schema-rows
                   [[7 :person/name "v1" 1 1]
                    [7 :person/name "v2" 2 1]
                    [7 :person/name "v1" 3 0]])
        rel  (query/relation data)]
    (is (= #{["v2"]}
           (qq '[:find ?v :where [7 :person/name ?v]]
               (schema/current rel))))))


;; ---------------------------------------------------------------------------
;; W3: same-t assert+retract update resolves v-new
;; ---------------------------------------------------------------------------

(deftest same-t-assert-retract-update-resolves-v-new
  (let [data (into schema-rows
                   [[7 :person/name "old" 5 0]
                    [7 :person/name "new" 5 1]])
        rel  (query/relation data)]
    (is (= #{["new"]}
           (qq '[:find ?v :where [7 :person/name ?v]]
               (schema/current rel))))))


;; ---------------------------------------------------------------------------
;; W4: reader tie never throws
;; ---------------------------------------------------------------------------

(deftest reader-tie-never-throws
  ;; Two live values, equal (t, m). Least v under compare-vals wins.
  ;; "Alice" < "Bob" alphabetically.
  (let [data (into schema-rows
                   [[7 :person/name "Bob" 5 1]
                    [7 :person/name "Alice" 5 1]])
        rel  (query/relation data)]
    (is (= #{["Alice"]}
           (qq '[:find ?v :where [7 :person/name ?v]]
               (schema/current rel))))))


;; ---------------------------------------------------------------------------
;; W5: unschematized attributes pass through
;; ---------------------------------------------------------------------------

(deftest unschematized-attributes-pass-through
  ;; :person/age is not declared in schema → card-many semantics (all live values)
  (let [data (into schema-rows
                   [[7 :person/age 30 0 1]
                    [7 :person/age 31 1 1]])
        rel  (query/relation data)]
    (is (= #{[30] [31]}
           (qq '[:find ?v :where [7 :person/age ?v]]
               (schema/current rel))))))


;; ---------------------------------------------------------------------------
;; W6: undeclared cardinality is card-many
;; ---------------------------------------------------------------------------

(deftest undeclared-cardinality-is-card-many
  ;; Attr declared with ident + valueType only, no :db/cardinality.
  ;; Should NOT collapse (card-many by default for undeclared).
  (let [data (into schema-rows
                   [[23 :db/ident :person/phone 0 1]
                    [23 :db/valueType :db.type/string 0 1]
                    [7 :person/phone "111" 0 1]
                    [7 :person/phone "222" 1 1]])
        rel  (query/relation data)]
    (is (= #{["111"] ["222"]}
           (qq '[:find ?v :where [7 :person/phone ?v]]
               (schema/current rel))))))


;; ---------------------------------------------------------------------------
;; W7: schema rows themselves collapse card-one by axiom
;; ---------------------------------------------------------------------------

(deftest schema-rows-themselves-collapse
  (testing "with bootstrap rows: :db/valueType re-declared collapses via q"
    (let [data (into (boot-rows 0)
                     [[21 :db/ident :person/name 0 1]
                      [21 :db/valueType :db.type/string 1 1]
                      [21 :db/cardinality :db.cardinality/one 0 1]
                      [21 :db/valueType :db.type/keyword 2 1]])
          rel  (query/relation data)]
      (is (= #{[:db.type/keyword]}
             (qq '[:find ?v :where [21 :db/valueType ?v]]
                 (schema/current rel))))))
  (testing "pure-axiom case: no bootstrap/ident rows, :db/* collapses by axiom"
    (let [data [[21 :db/valueType :db.type/string 1 1]
                [21 :db/valueType :db.type/keyword 2 1]]
          rel  (query/relation data)]
      (is (= #{[:db.type/keyword]}
             (qq '[:find ?v :where [21 :db/valueType ?v]]
                 (schema/current rel)))))))


;; ---------------------------------------------------------------------------
;; W8: as-of bounds data and schema
;; ---------------------------------------------------------------------------

(deftest as-of-bounds-data-and-schema
  (let [data (into (boot-rows 0)
                   [[21 :db/ident :person/name 0 1]
                    [21 :db/valueType :db.type/string 0 1]
                    [21 :db/cardinality :db.cardinality/one 0 1]
                    [7 :person/name "Alice" 3 1]])
        rel  (query/relation data)]
    (testing "as-of 1: no data visible (data at t=3)"
      (is (= #{}
             (qq '[:find ?v :where [7 :person/name ?v]]
                 (schema/current rel {:as-of 1})))))
    (testing "as-of 3: data visible"
      (is (= #{["Alice"]}
             (qq '[:find ?v :where [7 :person/name ?v]]
                 (schema/current rel {:as-of 3})))))
    (testing "as-of 1 still sees schema (bootstrap at t=0)"
      ;; The schema IS present at t=0, so as-of 1 includes it.
      ;; This means card-one collapse is active and "Alice" at t=3
      ;; is NOT visible at as-of 1.
      (is (= #{}
             (qq '[:find ?v :where [7 :person/name ?v]]
                 (schema/current rel {:as-of 1})))))))


;; ---------------------------------------------------------------------------
;; W9: schema-as-of re-reads schema
;; ---------------------------------------------------------------------------

(deftest schema-as-of-rereads-schema
  ;; Schema: card-one at t 1, changed to card-many at t 5.
  ;; Data: two values at t 6.
  ;; With :schema-as-of 1 → collapse (card-one active)
  ;; With :schema-as-of 5 → no collapse (card-many active)
  (let [data (into []
                   (concat
                     [[16 :db/valueType :db.type/keyword 0 1]
                      [16 :db/cardinality :db.cardinality/one 0 1]
                      [17 :db/valueType :db.type/keyword 0 1]
                      [17 :db/cardinality :db.cardinality/one 0 1]
                      [18 :db/valueType :db.type/keyword 0 1]
                      [18 :db/cardinality :db.cardinality/one 0 1]
                      [19 :db/valueType :db.type/boolean 0 1]
                      [19 :db/cardinality :db.cardinality/one 0 1]
                      [20 :db/valueType :db.type/boolean 0 1]
                      [20 :db/cardinality :db.cardinality/one 0 1]]
                     [[21 :db/ident :person/tag 0 1]
                      [21 :db/valueType :db.type/string 0 1]
                      [21 :db/cardinality :db.cardinality/one 1 1]
                      [21 :db/cardinality :db.cardinality/many 5 1]
                      [7 :person/tag "A" 6 1]
                      [7 :person/tag "B" 6 1]]))
        rel  (query/relation data)]
    (testing "schema-as-of 1: card-one active → collapse"
      (is (= 1
             (count (qq '[:find ?v :where [7 :person/tag ?v]]
                        (schema/current rel {:as-of 6 :schema-as-of 1}))))))
    (testing "schema-as-of 5: card-many active → no collapse"
      (is (= 2
             (count (qq '[:find ?v :where [7 :person/tag ?v]]
                        (schema/current rel {:as-of 6 :schema-as-of 5}))))))))


;; ---------------------------------------------------------------------------
;; W11: schema/current's result is a fact-relation value
;; ---------------------------------------------------------------------------

(deftest schema-current-returns-a-fact-relation-value
  ;; The v1 bound-inheritance property this site used to pin is gone with
  ;; descriptors: relation values carry no :dao.stream/bound to inherit,
  ;; so the old assertion compared two nils. What is real at this site:
  ;; schema/current's result is a query fact-relation value q accepts
  ;; directly, with the current-fact marker set.
  (let [rel (query/relation schema-rows)
        v   (schema/current rel)]
    (is (query/value? v))
    (is (true? (:fact? v)))))


;; ---------------------------------------------------------------------------
;; W12: nested view descriptor rejected
;; ---------------------------------------------------------------------------

(deftest nested-view-descriptor-rejected
  (let [rel (query/relation schema-rows)]
    (testing "nested query/current is rejected"
      (is (thrown-with-msg?
            #?(:cljs js/Error
               :cljd Object
               :default Exception)
            #"nested view"
            (schema/current (query/current rel)))))
    (testing "nested query/history is rejected"
      (is (thrown-with-msg?
            #?(:cljs js/Error
               :cljd Object
               :default Exception)
            #"nested view"
            (schema/current (query/history rel)))))
    (testing "a schema/current result is rejected as a source"
      ;; The view's own output is a :fact? relation, not a d5 source
      ;; value (V6, restated on the value).
      (is (thrown-with-msg?
            #?(:cljs js/Error
               :cljd Object
               :default Exception)
            #"nested view"
            (schema/current (schema/current rel)))))))


;; ---------------------------------------------------------------------------
;; W14: unknown db type throws at realization
;; ---------------------------------------------------------------------------

(deftest unknown-db-type-throws-at-realization
  ;; Schema rows carrying :db.type/instant. The error surfaces when the
  ;; view is built: schema/current's interpretation runs extract-schema
  ;; over the source's history view.
  (let [data [[21 :db/ident :test/attr 0 1]
              [21 :db/valueType :db.type/instant 0 1]
              [21 :db/cardinality :db.cardinality/one 0 1]]
        rel  (query/relation data)]
    (is (thrown-with-msg?
          #?(:cljs js/Error
             :cljd Object
             :default Exception)
          #"unknown :db.type"
          (qq '[:find ?v :where [21 :db/valueType ?v]]
              (schema/current rel))))))


;; ---------------------------------------------------------------------------
;; V13a: snapshot read failures are rejected (D4). A snapshot whose status
;; is :gap or :defect is an observed read failure — a hole opened while
;; the snapshot was reading, or a transport answering outside its
;; contract — and the read is known-incomplete, so schema/current refuses
;; to interpret it. Scripted readers, not a ring buffer: a fresh
;; :oldest cursor never trails a quiescent ringbuffer's first retained
;; position, so overflowing a buffer before snapshotting cannot produce
;; the values-then-hole gap this test needs. Eviction concurrent with a
;; snapshot's read can (query_test's snapshot-of-a-gap-is-data carries
;; the same note); scripting it keeps the test deterministic.
;; ---------------------------------------------------------------------------

(deftest snapshot-read-failures-are-rejected
  (testing "a :gap snapshot is rejected, naming the status"
    (let [h (reify
              stream/IDaoStreamReader

              (cursor
                [_ _]
                {:dao.stream/outcome :dao.stream/ok
                 :dao.stream/cursor {::pos 0}})

              (next
                [_ c]
                (case (::pos c)
                  0 {:dao.stream/outcome :dao.stream/ok
                     :dao.stream/value [21 :db/ident :person/name 0 1]
                     :dao.stream/cursor {::pos 1}}
                  1 {:dao.stream/outcome :dao.stream/gap
                     :dao.stream/cursor {::pos 2}})))
          snap (query/snapshot h)]
      (is (= :gap (:status snap)) "precondition: the snapshot observed a gap")
      (is (thrown-with-msg?
            #?(:cljs js/Error :cljd Object :default Exception)
            #"rejects a snapshot that reported gap"
            (schema/current snap)))))
  (testing "a :defect snapshot is rejected, naming the status"
    (let [h (reify
              stream/IDaoStreamReader

              (cursor
                [_ _]
                {:dao.stream/outcome :dao.stream/ok
                 :dao.stream/cursor {::pos 0}})

              (next
                [_ _]
                {:dao.stream/outcome :dao.stream/cursor-mismatch}))
          snap (query/snapshot h)]
      (is (= :defect (:status snap))
          "precondition: the transport answered outside its contract")
      (is (thrown-with-msg?
            #?(:cljs js/Error :cljd Object :default Exception)
            #"rejects a snapshot that reported defect"
            (schema/current snap))))))


;; ---------------------------------------------------------------------------
;; V13b: complete snapshots are accepted (D4). :ended and :blocked are
;; complete-at-call-time reads; both interpret identically to the same
;; rows handed over as a relation value.
;; ---------------------------------------------------------------------------

(deftest complete-snapshots-are-accepted
  (let [rows (into schema-rows
                   [[7 :person/name "old" 1 1]
                    [7 :person/name "new" 2 1]])
        expected (schema/current (query/relation rows))]
    (testing "an open memory-log answers :blocked; accepted"
      (let [h (:dao.stream/handle
                (memory-log/create! {:dao.stream/type :dao.stream/memory-log}))]
        (doseq [r rows] (stream/append! h r))
        (let [snap (query/snapshot h)]
          (is (= :blocked (:status snap)))
          (is (= expected (schema/current snap))
              "card-one collapse fires over the snapshot's relation"))))
    (testing "a closed memory-log answers :ended; accepted, same answer"
      (let [h (:dao.stream/handle
                (memory-log/create! {:dao.stream/type :dao.stream/memory-log}))]
        (doseq [r rows] (stream/append! h r))
        (stream/close! h)
        (let [snap (query/snapshot h)]
          (is (= :ended (:status snap)))
          (is (= expected (schema/current snap))
              "the closed log's snapshot interprets identically"))))))


;; ---------------------------------------------------------------------------
;; V15: an evicted prefix is not detectable through a snapshot — a
;; DOCUMENTED LIMIT, not a guarantee (D4). A v2 ring buffer of capacity 4
;; evicted the schema vocabulary before the snapshot was taken;
;; query/snapshot mints a fresh :oldest, which on a ring buffer is the
;; earliest RETAINED position, so the surviving suffix reads as :blocked
;; and schema/current cannot distinguish it from complete history.
;; Completeness is the caller's declaration — a transport declaring
;; complete retention (dao.stream.v2.memory-log), or a kept origin cursor
;; minted before the first append and read through with no observed gap —
;; never schema's check. This test pins the limit so that a future change
;; which makes it detectable has to change a test and therefore the
;; design. The (is (= :blocked …)) assertion is a cross-layer pin on
;; query/snapshot's own status: a query-side refactor that changes it
;; also trips this test — read the failing assertion first.
;; ---------------------------------------------------------------------------

(deftest evicted-prefix-is-not-detectable-through-snapshot
  (let [h (:dao.stream/handle
            (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                                 :dao.stream.ringbuffer/capacity 4}))]
    (doseq [r schema-rows] (stream/append! h r))
    (stream/append! h [7 :person/name "old" 1 1])
    (stream/append! h [7 :person/name "new" 2 1])
    (let [snap (query/snapshot h)]
      (is (= :blocked (:status snap))
          "the ring buffer answers over its surviving suffix")
      (is (= #{["old"] ["new"]}
             (qq '[:find ?v :where [7 :person/name ?v]]
                 (schema/current snap)))
          "the vocabulary was evicted: card-one collapse did not fire, and
           the evicted prefix is indistinguishable from complete history"))))


;; ===========================================================================
;; The write wrapper tests
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; Wrapper helpers
;; ---------------------------------------------------------------------------

(defn- fresh-streams
  "Create a fresh memory-log local-stream + v2 intake-pool pair."
  []
  (let [local  (:dao.stream/handle
                 (memory-log/create!
                   {:dao.stream/type :dao.stream/memory-log}))
        intake (:dao.stream/handle
                 (ringbuffer/create!
                   {:dao.stream/type :dao.stream/ringbuffer
                    :dao.stream.ringbuffer/capacity 4096}))]
    {:local local :intake [intake]}))


(defn- datoms-of
  "Extract all [e a v t m] datoms from a drained local-stream, flattening
   transaction records."
  [local-stream]
  (index/snapshot-datoms local-stream))


(defn- wrapper-state
  "The wrapper's state map — the white-box seam behind the T19
   state-identity assertion. The wrapper is a plain map, so this is a
   keyword read with no reader conditional (D8)."
  [w]
  @(:state w))


(defn- bootstrap-tx
  "Bootstrap schema rows as tx-data for transact!."
  []
  (into (schema/bootstrap)
        [[:db/add 21 :db/ident :person/name]
         [:db/add 21 :db/valueType :db.type/string]
         [:db/add 21 :db/cardinality :db.cardinality/one]
         [:db/add 22 :db/ident :person/friends]
         [:db/add 22 :db/valueType :db.type/ref]
         [:db/add 22 :db/cardinality :db.cardinality/many]
         [:db/add 23 :db/ident :person/email]
         [:db/add 23 :db/valueType :db.type/string]
         [:db/add 23 :db/cardinality :db.cardinality/one]
         [:db/add 23 :db/unique true]]))


;; ---------------------------------------------------------------------------
;; W15: wrapper opens and transacts
;; ---------------------------------------------------------------------------

(deftest wrapper-opens-and-transacts
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    ;; Bootstrap
    (let [r1 (schema/transact! w (bootstrap-tx))]
      (is (= :dao.stream/ok (:dao.stream/outcome r1)))
      (is (integer? (:dao.space/t r1))))
    ;; Data
    (let [r2 (schema/transact! w [[7 :person/name "Alice"]])]
      (is (= :dao.stream/ok (:dao.stream/outcome r2))))
    ;; Datoms landed in local-stream
    (let [all (datoms-of local)
          names (filterv #(= :person/name (index/datom-a %)) all)]
      (is (seq names))
      (is (= "Alice" (index/datom-v (last names)))))
    (schema/close! w)))


;; ---------------------------------------------------------------------------
;; W16: map-form supersession
;; ---------------------------------------------------------------------------

(deftest map-form-supersession
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/transact! w (bootstrap-tx))
    ;; Assert initial value
    (schema/transact! w [[7 :person/name "old"]])
    ;; Supersede via map form
    (schema/transact! w [{:db/id 7 :person/name "new"}])
    ;; History shows both retract-old + assert-new at same t
    (let [all (datoms-of local)
          name-datoms (filterv #(= :person/name (index/datom-a %)) all)
          ;; Group by t: the supersession record
          by-t (group-by index/datom-t name-datoms)
          super-t (apply max (keys by-t))
          super-datoms (get by-t super-t)]
      (is (= 2 (count super-datoms))
          "supersession emits retract-old + assert-new at same t")
      (is (some #(and (= "old" (index/datom-v %))
                      (datom/retracted? %))
                super-datoms))
      (is (some #(and (= "new" (index/datom-v %))
                      (datom/asserted? %))
                super-datoms)))
    ;; Raw current sees only "new"
    (let [rel (query/relation (datoms-of local))]
      (is (= #{["new"]}
             (qq '[:find ?v :where [7 :person/name ?v]]
                 (query/current rel)))))
    (schema/close! w)))


;; ---------------------------------------------------------------------------
;; W17: add collision strict rejected / lax appended
;; ---------------------------------------------------------------------------

(deftest add-collision-strict-rejected-lax-appended
  (testing "strict: [:db/add] on card-one with different live value throws"
    (let [{:keys [local intake]} (fresh-streams)
          w (schema/transactor local intake {:strict true})]
      (schema/transact! w (bootstrap-tx))
      (schema/transact! w [[7 :person/name "old"]])
      (is (thrown-with-msg?
            #?(:cljs js/Error :cljd Object :default Exception)
            #"card-one collision"
            (schema/transact! w [[:db/add 7 :person/name "new"]])))
      (schema/close! w)))
  (testing "lax: same collision appends"
    (let [{:keys [local intake]} (fresh-streams)
          w (schema/transactor local intake)]
      (schema/transact! w (bootstrap-tx))
      (schema/transact! w [[7 :person/name "old"]])
      (let [r (schema/transact! w [[:db/add 7 :person/name "new"]])]
        (is (= :dao.stream/ok (:dao.stream/outcome r))))
      ;; Raw current sees both (no supersession emission from bare :db/add)
      (let [rel (query/relation (datoms-of local))]
        (is (= 2
               (count (qq '[:find ?v :where [7 :person/name ?v]]
                          (query/current rel))))))
      (schema/close! w))))


;; ---------------------------------------------------------------------------
;; W18: valueType strict rejected / lax appended + audited
;; ---------------------------------------------------------------------------

(deftest valueType-strict-rejected-lax-appended
  (testing "strict: valueType mismatch throws"
    (let [{:keys [local intake]} (fresh-streams)
          w (schema/transactor local intake {:strict true})]
      (schema/transact! w (bootstrap-tx))
      (is (thrown-with-msg?
            #?(:cljs js/Error :cljd Object :default Exception)
            #"valueType mismatch"
            (schema/transact! w [[7 :person/name 42]])))
      (schema/close! w)))
  (testing "lax: mismatch appends, audit finds it"
    (let [{:keys [local intake]} (fresh-streams)
          w (schema/transactor local intake)]
      (schema/transact! w (bootstrap-tx))
      (let [r (schema/transact! w [[7 :person/name 42]])]
        (is (= :dao.stream/ok (:dao.stream/outcome r))))
      ;; Audit: find valueType violations using type predicate
      (let [rel (query/relation (datoms-of local))
            ;; Get all person/name values
            all-names
            (query/collect
              (query/q '[:find ?e ?v
                         :where [?e :person/name ?v]]
                       (query/current rel)))
            ;; Filter for non-string violations
            violations (set (filter #(not (string? (second %))) all-names))]
        (is (= #{[7 42]} violations)))
      (schema/close! w))))


;; ---------------------------------------------------------------------------
;; W19: ref existence
;; ---------------------------------------------------------------------------

(deftest ref-existence
  (testing "strict: dangling ref throws"
    (let [{:keys [local intake]} (fresh-streams)
          w (schema/transactor local intake {:strict true})]
      (schema/transact! w (bootstrap-tx))
      (is (thrown-with-msg?
            #?(:cljs js/Error :cljd Object :default Exception)
            #"dangling ref"
            (schema/transact! w [[8 :person/friends 999]])))
      (schema/close! w)))
  (testing "strict: ref to entity created earlier in same record passes"
    (let [{:keys [local intake]} (fresh-streams)
          w (schema/transactor local intake {:strict true})]
      (schema/transact! w (bootstrap-tx))
      (let [r (schema/transact! w [{:db/id 8 :person/name "Target"}
                                   {:db/id 9 :person/friends 8}])]
        (is (= :dao.stream/ok (:dao.stream/outcome r))))
      (schema/close! w)))
  (testing "strict: an empty entity map does not create a ref target"
    (let [{:keys [local intake]} (fresh-streams)
          w (schema/transactor local intake {:strict true})]
      (schema/transact! w (bootstrap-tx))
      (is (thrown-with-msg?
            #?(:cljs js/Error :cljd Object :default Exception)
            #"dangling ref"
            (schema/transact! w [{:db/id 8}
                                 {:db/id 9 :person/friends 8}])))
      (schema/close! w)))
  (testing "lax: dangling ref appends"
    (let [{:keys [local intake]} (fresh-streams)
          w (schema/transactor local intake)]
      (schema/transact! w (bootstrap-tx))
      (let [r (schema/transact! w [[8 :person/friends 999]])]
        (is (= :dao.stream/ok (:dao.stream/outcome r))))
      (schema/close! w))))


;; ---------------------------------------------------------------------------
;; W20: lookup ref in :db/id
;; ---------------------------------------------------------------------------

(deftest lookup-ref-in-db-id
  (testing "lookup ref resolves to existing entity"
    (let [{:keys [local intake]} (fresh-streams)
          w (schema/transactor local intake)]
      (schema/transact! w (bootstrap-tx))
      ;; Create entity 7 with email
      (schema/transact! w [{:db/id 7 :person/email "a@b.com"}])
      ;; Lookup ref should resolve to entity 7
      (schema/transact! w [{:db/id [:person/email "a@b.com"]
                            :person/name "Alice"}])
      (let [all (datoms-of local)
            names (filterv #(and (= :person/name (index/datom-a %))
                                 (= "Alice" (index/datom-v %)))
                           all)]
        (is (= 1 (count names)))
        (is (= 7 (index/datom-e (first names)))))
      (schema/close! w)))
  (testing "unmatched lookup ref throws in both modes"
    (doseq [strict? [true false]]
      (let [{:keys [local intake]} (fresh-streams)
            w (schema/transactor local intake {:strict strict?})]
        (schema/transact! w (bootstrap-tx))
        (is (thrown-with-msg?
              #?(:cljs js/Error :cljd Object :default Exception)
              #"unmatched lookup ref"
              (schema/transact! w [{:db/id [:person/email "nope"]
                                    :person/name "X"}])))
        (schema/close! w)))))


;; ---------------------------------------------------------------------------
;; W21: lookup ref in ref value
;; ---------------------------------------------------------------------------

(deftest lookup-ref-in-ref-value
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/transact! w (bootstrap-tx))
    ;; Create entity 7 with email
    (schema/transact! w [{:db/id 7 :person/email "a@b.com"}])
    ;; Create entity 8 with friend = lookup ref to 7 (scalar on card-many ref)
    (schema/transact! w [{:db/id 8 :person/friends [:person/email "a@b.com"]}])
    (let [all (datoms-of local)
          friends8 (filterv #(and (= :person/friends (index/datom-a %))
                                  (= 8 (index/datom-e %)))
                            all)]
      (is (= 1 (count friends8))
          "lookup ref is one datom, not element-wise expansion")
      (is (= 7 (index/datom-v (first friends8)))
          "lookup ref resolved to entity id"))
    ;; Also: bare entity-id vector [5 6] on ref attr → two datoms
    (schema/transact! w [{:db/id 9 :person/friends [8 7]}])
    (let [all (datoms-of local)
          friends9 (filterv #(and (= :person/friends (index/datom-a %))
                                  (= 9 (index/datom-e %)))
                            all)]
      (is (= 2 (count friends9))
          "bare entity-id vector expands element-wise")
      (is (= #{7 8} (set (map index/datom-v friends9)))))
    (schema/close! w)))


;; ---------------------------------------------------------------------------
;; W22: no-dedup
;; ---------------------------------------------------------------------------

(deftest no-dedup
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/transact! w (bootstrap-tx))
    ;; Retract an absent fact — appends as told
    (let [r1 (schema/transact! w [[:db/retract 7 :person/name "ghost"]])]
      (is (= :dao.stream/ok (:dao.stream/outcome r1))))
    ;; Re-assert the live value — appends (no dedup)
    (schema/transact! w [[7 :person/name "Alice"]])
    (let [r2 (schema/transact! w [[:db/add 7 :person/name "Alice"]])]
      (is (= :dao.stream/ok (:dao.stream/outcome r2))))
    (schema/close! w)))


;; ---------------------------------------------------------------------------
;; W23: card-many expansion
;; ---------------------------------------------------------------------------

(deftest card-many-expansion
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/transact! w (bootstrap-tx))
    (schema/transact! w [{:db/id 7 :person/friends [8 9]}])
    (let [all (datoms-of local)
          friends (filterv #(= :person/friends (index/datom-a %)) all)]
      (is (= 2 (count friends)))
      (is (= #{8 9} (set (map index/datom-v friends)))))
    (schema/close! w)))


;; ---------------------------------------------------------------------------
;; W24: retract attribute-wide
;; ---------------------------------------------------------------------------

(deftest retract-attribute-wide
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/transact! w (bootstrap-tx))
    ;; Add two friends (card-many)
    (schema/transact! w [{:db/id 7 :person/friends [8 9]}])
    ;; Retract attribute-wide
    (let [r (schema/transact! w [[:db/retract 7 :person/friends]])]
      (is (= :dao.stream/ok (:dao.stream/outcome r))))
    ;; Both retracted
    (let [all (datoms-of local)
          friends (filterv #(= :person/friends (index/datom-a %)) all)
          live (filterv datom/asserted? (query/current-state-seq friends))]
      (is (= 0 (count live))))
    (schema/close! w)))


;; ---------------------------------------------------------------------------
;; W25: schema evolves through wrapper
;; ---------------------------------------------------------------------------

(deftest schema-evolves-through-wrapper
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake {:strict true})]
    ;; Open with empty schema — no validation yet
    (schema/transact! w (bootstrap-tx))
    ;; Now transact :person/name schema
    (schema/transact! w [[21 :db/ident :person/name]
                         [21 :db/valueType :db.type/string]
                         [21 :db/cardinality :db.cardinality/one]])
    ;; Now the wrapper has :person/name declared string, card-one.
    ;; A bad valueType should be rejected.
    (is (thrown-with-msg?
          #?(:cljs js/Error :cljd Object :default Exception)
          #"valueType mismatch"
          (schema/transact! w [[7 :person/name 42]])))
    ;; But a good value passes
    (let [r (schema/transact! w [[7 :person/name "Alice"]])]
      (is (= :dao.stream/ok (:dao.stream/outcome r))))
    (schema/close! w)))


;; ---------------------------------------------------------------------------
;; W26: a closed wrapper answers closed as data (L5′, D1)
;; ---------------------------------------------------------------------------

(deftest closed-wrapper-answers-closed
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/close! w)
    (is (= {:dao.stream/outcome :dao.stream/closed}
           (schema/transact! w [[7 :person/name "X"]])))
    ;; local stream NOT closed (caller owns it): an open memory-log blocks at
    ;; its tail; a closed one would answer :dao.stream/end
    (is (= :dao.stream/blocked
           (:dao.stream/outcome
             (stream/next local
                          (:dao.stream/cursor
                            (stream/cursor local :dao.stream/newest))))))))


;; ---------------------------------------------------------------------------
;; close! is idempotent and closes the inner value (L1, L2). The inner-value
;; assertion is the direct pin that tx/close! was called: an assertion on
;; the wrapper alone would pass even if the inner value were never closed.
;; ---------------------------------------------------------------------------

(deftest close-is-idempotent-and-closes-the-inner-value
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (is (= {:dao.stream/outcome :dao.stream/ok} (schema/close! w)))
    (is (= {:dao.stream/outcome :dao.stream/ok} (schema/close! w))
        "close! is idempotent")
    (is (= {:dao.stream/outcome :dao.stream/closed}
           (tx/transact! (:inner w) [[7 :x 1]]))
        "the inner transactor value is closed — tx/close! was called")
    (is (= {:dao.stream/outcome :dao.stream/closed}
           (schema/transact! w [[7 :x 1]]))
        "the wrapper answers closed after its inner value does")
    ;; local stream NOT closed (caller owns it): an open memory-log blocks at
    ;; its tail; a closed one would answer :dao.stream/end
    (is (= :dao.stream/blocked
           (:dao.stream/outcome
             (stream/next local
                          (:dao.stream/cursor
                            (stream/cursor local :dao.stream/newest))))))))


;; ---------------------------------------------------------------------------
;; The closed-precedence rule (L10, D1): the wrapper adopts the
;; transactor's precedence rather than inventing a schema-specific order.
;; Empty tx-data throws above the lock, regardless of state; every other
;; argument answers closed before it is examined. The same two calls on
;; the inner value give the same two answers.
;; ---------------------------------------------------------------------------

(deftest closed-precedence-matches-the-transactor
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/close! w)
    (is (thrown-with-msg?
          #?(:cljs js/Error :cljd Object :default Exception)
          #"at least one"
          (schema/transact! w [])))
    (is (= {:dao.stream/outcome :dao.stream/closed}
           (schema/transact! w [[:db/add 30 :db/foo :bar]]))
        "closed answers before the unknown :db/* defect is examined")
    (is (thrown-with-msg?
          #?(:cljs js/Error :cljd Object :default Exception)
          #"at least one"
          (tx/transact! (:inner w) [])))
    (is (= {:dao.stream/outcome :dao.stream/closed}
           (tx/transact! (:inner w) [[:db/add 30 :db/foo :bar]])))
    (is (= :dao.stream/blocked
           (:dao.stream/outcome
             (stream/next local
                          (:dao.stream/cursor
                            (stream/cursor local :dao.stream/newest)))))
        "the wrapper's precedence is data: the local stream is untouched")))


;; ---------------------------------------------------------------------------
;; W43: a refused inner append leaves the wrapper's state untouched (T19/T20).
;; The real transport excludes full, so a memory-log-backed writer double
;; answering full once is the only way to exercise the non-ok path.
;; ---------------------------------------------------------------------------

(deftest failed-inner-append-leaves-wrapper-state-unchanged
  (let [inner (:dao.stream/handle
                (memory-log/create! {:dao.stream/type :dao.stream/memory-log}))
        calls (atom 0)
        local (reify
                stream/IDaoStreamWriter
                (append!
                  [_ val]
                  ;; fail exactly the second append: the bootstrap passes,
                  ;; the first data transaction is refused, the retry passes
                  (if (= 2 (swap! calls inc))
                    {:dao.stream/outcome :dao.stream/full}
                    (stream/append! inner val)))


                stream/IDaoStreamReader

                (cursor [_ anchor] (stream/cursor inner anchor))

                (next [_ cursor] (stream/next inner cursor)))
        intake (:dao.stream/handle
                 (ringbuffer/create!
                   {:dao.stream/type :dao.stream/ringbuffer
                    :dao.stream.ringbuffer/capacity 4096}))
        w (schema/transactor local [intake])]
    (is (= :dao.stream/ok (:dao.stream/outcome (schema/transact! w (bootstrap-tx))))
        "precondition: the bootstrap commits at t 0 through the double")
    (let [st (wrapper-state w)]
      (is (= {:dao.stream/outcome :dao.stream/full}
             (schema/transact! w [[7 :person/name "Alice"]]))
          "the conforming non-ok outcome is returned as data, not thrown")
      (is (= st (wrapper-state w))
          "schema, uniqueness, and current-value state are identical to
           before the refused append")
      (is (= {:dao.stream/outcome :dao.stream/ok
              :dao.space/t 1
              :dao.space/datoms [[7 :person/name "Alice" 1 1]]}
             (schema/transact! w [[7 :person/name "Alice"]]))
          "the retry commits at the same t the refused attempt planned, and
           the receipt is the inner transactor's, unchanged")
      (is (not= st (wrapper-state w))
          "state advances only on the successful commit"))))


;; ---------------------------------------------------------------------------
;; W44: publication after close is permitted (D7). The v1 guard — a closed
;; wrapper refusing publish! under the wrapper lock — is deleted: close
;; rejects further writes, and publication is a read of the caller's
;; still-open local stream plus an enqueue into the caller-owned pool.
;; ---------------------------------------------------------------------------

(deftest publish-after-close-reads-the-callers-stream
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/transact! w (bootstrap-tx))
    (schema/transact! w [[7 :person/name "Alice"]])
    (schema/close! w)
    (let [{:keys [manifest]} (schema/publish! w)]
      (is (= (count (datoms-of local))
             (:count manifest))
          "a publication after close reads the full history of the
           caller's still-open local stream"))))


;; ===========================================================================
;; Schema-row validation tests (the both-modes class)
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; W27: bootstrap reads back
;; ---------------------------------------------------------------------------

(deftest bootstrap-reads-back
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/transact! w (schema/bootstrap))
    ;; q over raw current sees [16 :db/ident :db/ident] etc.
    (let [all (datoms-of local)
          ident-rows (filterv #(= :db/ident (index/datom-a %)) all)]
      (is (= #{:db/ident :db/valueType :db/cardinality :db/unique :db/index}
             (set (map index/datom-v ident-rows)))
          "each of the five entities has a :db/ident row"))
    ;; extract-schema over drained rows returns all five entries
    (let [rows (datoms-of local)
          schema (schema/extract-schema rows)]
      (is (= (set (keys schema/axioms)) (set (keys schema))))
      (is (= (get-in schema/axioms [:db/ident :db/valueType])
             (get-in schema [:db/ident :db/valueType]))))
    (schema/close! w)))


;; ---------------------------------------------------------------------------
;; W28: bootstrap verbatim retransact allowed
;; ---------------------------------------------------------------------------

(deftest bootstrap-verbatim-retransact-allowed
  (doseq [strict? [true false]]
    (testing (str "strict=" strict? ": second bootstrap appends idempotently")
      (let [{:keys [local intake]} (fresh-streams)
            w (schema/transactor local intake {:strict strict?})]
        (schema/transact! w (schema/bootstrap))
        (let [r (schema/transact! w (schema/bootstrap))]
          (is (= :dao.stream/ok (:dao.stream/outcome r))))
        (schema/close! w)))))


;; ---------------------------------------------------------------------------
;; W29: unknown :db/* name rejected both modes
;; ---------------------------------------------------------------------------

(deftest unknown-db-star-name-rejected-both-modes
  (doseq [strict? [true false]]
    (testing (str "strict=" strict? ": [30 :db/foo :bar] rejected")
      (let [{:keys [local intake]} (fresh-streams)
            w (schema/transactor local intake {:strict strict?})]
        (is (thrown-with-msg?
              #?(:cljs js/Error :cljd Object :default Exception)
              #"unknown :db/\*"
              (schema/transact! w [[:db/add 30 :db/foo :bar]])))
        (schema/close! w)))))


;; ---------------------------------------------------------------------------
;; W30: illegal schema values rejected both modes
;; ---------------------------------------------------------------------------

(deftest illegal-schema-values-rejected-both-modes
  (doseq [strict? [true false]]
    (testing (str "strict=" strict? ": illegal :db/valueType")
      (let [{:keys [local intake]} (fresh-streams)
            w (schema/transactor local intake {:strict strict?})]
        (is (thrown-with-msg?
              #?(:cljs js/Error :cljd Object :default Exception)
              #"illegal :db/valueType"
              (schema/transact! w [[:db/add 21 :db/ident :test/x]
                                   [:db/add 21 :db/valueType :db.type/bogus]])))
        (schema/close! w)))
    (testing (str "strict=" strict? ": illegal :db/cardinality")
      (let [{:keys [local intake]} (fresh-streams)
            w (schema/transactor local intake {:strict strict?})]
        (is (thrown-with-msg?
              #?(:cljs js/Error :cljd Object :default Exception)
              #"illegal :db/cardinality"
              (schema/transact! w [[:db/add 21 :db/ident :test/x]
                                   [:db/add 21 :db/cardinality :db.cardinality/seven]])))
        (schema/close! w)))
    (testing (str "strict=" strict? ": illegal :db/unique (not boolean)")
      (let [{:keys [local intake]} (fresh-streams)
            w (schema/transactor local intake {:strict strict?})]
        (is (thrown-with-msg?
              #?(:cljs js/Error :cljd Object :default Exception)
              #"illegal :db/unique"
              (schema/transact! w [[:db/add 21 :db/ident :test/x]
                                   [:db/add 21 :db/unique "yes"]])))
        (schema/close! w)))
    (testing (str "strict=" strict? ": non-namespaced :db/ident")
      (let [{:keys [local intake]} (fresh-streams)
            w (schema/transactor local intake {:strict strict?})]
        (is (thrown-with-msg?
              #?(:cljs js/Error :cljd Object :default Exception)
              #"illegal :db/ident"
              (schema/transact! w [[:db/add 21 :db/ident :name]])))
        (schema/close! w)))))


;; ---------------------------------------------------------------------------
;; W31: duplicate ident both modes
;; ---------------------------------------------------------------------------

(deftest duplicate-ident-rejected-both-modes
  (doseq [strict? [true false]]
    (testing (str "strict=" strict? ": duplicate ident in batch")
      (let [{:keys [local intake]} (fresh-streams)
            w (schema/transactor local intake {:strict strict?})]
        (is (thrown-with-msg?
              #?(:cljs js/Error :cljd Object :default Exception)
              #"duplicate :db/ident"
              (schema/transact! w [[:db/add 21 :db/ident :test/a]
                                   [:db/add 22 :db/ident :test/a]])))
        (schema/close! w)))
    (testing (str "strict=" strict? ": duplicate ident vs live vocabulary")
      (let [{:keys [local intake]} (fresh-streams)
            w (schema/transactor local intake {:strict strict?})]
        (schema/transact! w (bootstrap-tx))
        (is (thrown-with-msg?
              #?(:cljs js/Error :cljd Object :default Exception)
              #"duplicate :db/ident"
              (schema/transact! w [[:db/add 99 :db/ident :person/name]])))
        (schema/close! w)))))


;; ---------------------------------------------------------------------------
;; W32: axiom protection
;; ---------------------------------------------------------------------------

(deftest axiom-protection
  (doseq [strict? [true false]]
    (testing (str "strict=" strict? ": re-declare axiom property differently")
      (let [{:keys [local intake]} (fresh-streams)
            w (schema/transactor local intake {:strict strict?})]
        (schema/transact! w (schema/bootstrap))
        (is (thrown-with-msg?
              #?(:cljs js/Error :cljd Object :default Exception)
              #"axiom protection"
              (schema/transact! w [[:db/add 16 :db/cardinality :db.cardinality/many]])))
        (schema/close! w)))
    (testing (str "strict=" strict? ": new property on axiom entity")
      (let [{:keys [local intake]} (fresh-streams)
            w (schema/transactor local intake {:strict strict?})]
        (schema/transact! w (schema/bootstrap))
        (is (thrown-with-msg?
              #?(:cljs js/Error :cljd Object :default Exception)
              #"axiom protection"
              (schema/transact! w [[:db/add 16 :db/doc "description"]])))
        (schema/close! w)))
    (testing (str "strict=" strict? ": retract axiom property")
      (let [{:keys [local intake]} (fresh-streams)
            w (schema/transactor local intake {:strict strict?})]
        (schema/transact! w (schema/bootstrap))
        (is (thrown-with-msg?
              #?(:cljs js/Error :cljd Object :default Exception)
              #"axiom protection"
              (schema/transact! w [[:db/retract 16 :db/unique true]])))
        (schema/close! w)))
    (testing (str "strict=" strict? ": axiom identity cannot be renamed")
      (let [{:keys [local intake]} (fresh-streams)
            w (schema/transactor local intake {:strict strict?})]
        (schema/transact! w (schema/bootstrap))
        (let [before (datoms-of local)]
          (is (thrown-with-msg?
                #?(:cljs js/Error :cljd Object :default Exception)
                #"axiom protection"
                (schema/transact! w [[:db/add 16 :db/ident
                                      :evil/renamed]])))
          (is (= before (datoms-of local))
              "renaming an axiom appends nothing"))
        (is (thrown-with-msg?
              #?(:cljs js/Error :cljd Object :default Exception)
              #"axiom protection"
              (schema/transact! w [{:db/id 16
                                    :db/ident :evil/map-renamed}])))
        (schema/close! w)))
    (testing (str "strict=" strict?
                  ": same-record axiom declaration cannot mutate its axiom")
      (let [{:keys [local intake]} (fresh-streams)
            w (schema/transactor local intake {:strict strict?})
            before (datoms-of local)]
        (is (thrown-with-msg?
              #?(:cljs js/Error :cljd Object :default Exception)
              #"axiom protection"
              (schema/transact!
                w
                [[:db/add 99 :db/ident :db/ident]
                 [:db/add 99 :db/cardinality :db.cardinality/many]])))
        (is (= before (datoms-of local))
            "a same-record axiom mutation appends nothing")
        (is (= 0 (:dao.space/t (schema/transact! w [[7 :person/value :ok]])))
            "an axiom rejection consumes no transaction time")
        (schema/close! w)))))


;; ---------------------------------------------------------------------------
;; W33: user schema evolution allowed
;; ---------------------------------------------------------------------------

(deftest user-schema-evolution-allowed
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/transact! w (bootstrap-tx))
    ;; Re-declare :person/name as card-many at new t
    (let [r (schema/transact! w [[:db/add 21 :db/cardinality :db.cardinality/many]])]
      (is (= :dao.stream/ok (:dao.stream/outcome r))))
    (schema/close! w)
    ;; A new wrapper over the stream reads card-many
    (let [w2 (schema/transactor local intake)]
      ;; Two values for same entity + attr should not collapse
      (schema/transact! w2 [[7 :person/name "Alice"]])
      (schema/transact! w2 [[7 :person/name "Bob"]])
      (let [all (datoms-of local)
            names (filterv #(and (= :person/name (index/datom-a %))
                                 (= 7 (index/datom-e %)))
                           all)
            live (filterv datom/asserted?
                          (query/current-state-seq names))]
        (is (= 2 (count live))
            "card-many: both values survive"))
      (schema/close! w2))))


;; ---------------------------------------------------------------------------
;; W34: unique requires card-one both modes
;; ---------------------------------------------------------------------------

(deftest unique-requires-card-one-both-modes
  (doseq [strict? [true false]]
    (testing (str "strict=" strict? ": unique on undeclared cardinality")
      (let [{:keys [local intake]} (fresh-streams)
            w (schema/transactor local intake {:strict strict?})
            before (datoms-of local)]
        (is (thrown-with-msg?
              #?(:cljs js/Error :cljd Object :default Exception)
              #":db/unique requires :db.cardinality/one"
              (schema/transact! w [[:db/add 21 :db/ident :test/x]
                                   [:db/add 21 :db/unique true]])))
        (is (= before (datoms-of local))
            "rejection leaves the append-only history unchanged")
        (is (= 0 (:dao.space/t (schema/transact! w [[30 :test/value :ok]])))
            "a rejected plan consumes no transaction time")
        (schema/close! w)))
    (testing (str "strict=" strict? ": unique on card-many")
      (let [{:keys [local intake]} (fresh-streams)
            w (schema/transactor local intake {:strict strict?})
            before (datoms-of local)]
        (is (thrown-with-msg?
              #?(:cljs js/Error :cljd Object :default Exception)
              #":db/unique requires :db.cardinality/one"
              (schema/transact! w [[:db/add 21 :db/ident :test/x]
                                   [:db/add 21 :db/cardinality :db.cardinality/many]
                                   [:db/add 21 :db/unique true]])))
        (is (= before (datoms-of local))
            "rejection leaves the append-only history unchanged")
        (schema/close! w)))))


;; ---------------------------------------------------------------------------
;; W35: lookup ref after same-record unique declaration (F2)
;; ---------------------------------------------------------------------------

(deftest lookup-ref-after-same-record-unique-declaration
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/transact! w (bootstrap-tx))
    ;; Declare unique+card-one+value in one record
    (schema/transact! w [{:db/id 30 :db/ident :test/code
                          :db/valueType :db.type/string
                          :db/cardinality :db.cardinality/one
                          :db/unique true}])
    ;; Assert a value for entity 7
    (schema/transact! w [{:db/id 7 :test/code "ABC"}])
    ;; Lookup ref should resolve in the NEXT record
    (schema/transact! w [{:db/id 8 :person/friends [:test/code "ABC"]}])
    (let [all (datoms-of local)
          friends (filterv #(and (= :person/friends (index/datom-a %))
                                 (= 8 (index/datom-e %)))
                           all)]
      (is (= 1 (count friends)))
      (is (= 7 (index/datom-v (first friends)))
          "lookup ref resolved via same-record unique index"))
    (schema/close! w)))


;; ---------------------------------------------------------------------------
;; W36: re-extract gated (F3)
;; ---------------------------------------------------------------------------

(deftest re-extract-gated
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/transact! w (bootstrap-tx))
    (let [schema-before (:schema (wrapper-state w))]
      ;; Data-only transaction
      (schema/transact! w [[7 :person/name "Alice"]])
      (let [schema-after (:schema (wrapper-state w))]
        (is (identical? schema-before schema-after)
            "schema map unchanged after data-only transact")))
    ;; Schema-row transaction
    (schema/transact! w [[:db/add 30 :db/ident :test/x]
                         [:db/add 30 :db/valueType :db.type/long]
                         [:db/add 30 :db/cardinality :db.cardinality/one]])
    (is (contains? (:schema (wrapper-state w)) :test/x)
        "schema updated after schema-row transact")
    (schema/close! w)))


;; ---------------------------------------------------------------------------
;; W37: audit via :fns (§6 mechanism)
;; ---------------------------------------------------------------------------

(deftest audit-via-fns
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/transact! w (bootstrap-tx))
    ;; Lax: append a string-typed violation
    (schema/transact! w [[7 :person/name 42]])
    ;; Audit: :fns query over the RAW view — valid-only direction
    (let [rel (query/relation (datoms-of local))
          valid
          (query/collect
            (query/q '[:find ?e ?v
                       :where [?e :person/name ?v]
                       [(valid-string? ?v)]]
                     (query/current rel)
                     {:fns {'valid-string? string?}}))]
      ;; The violation (42) is NOT returned (string? returns false).
      ;; The valid value IS returned only if present.
      (is (= #{} (set valid))
          "no valid string values found (only the violation exists)"))
    ;; Audit: preserve provenance by collapsing history to raw-current d5,
    ;; then query that relation directly rather than projecting through the
    ;; current d3 view.
    (let [raw-current-d5 (query/current-state-seq (datoms-of local))
          rel (query/relation raw-current-d5)
          violations
          (query/collect
            (query/q '[:find ?e ?v ?t ?m
                       :where [?e :person/name ?v ?t ?m]
                       [(not-string? ?v)]]
                     rel
                     {:fns {'not-string? (complement string?)}}))]
      (is (= #{[7 42 1 1]} (set violations))
          "audit finds the violation with transaction and metadata provenance"))
    (schema/close! w)))


;; ===========================================================================
;; Publisher tests
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; Publisher helpers
;; ---------------------------------------------------------------------------

(defn- temp-content-path
  [prefix]
  (str "target/test-schema-" prefix "-" (random-uuid) ".log"))


(defn- cleanup-file
  [path]
  #?(:clj (let [f (java.io.File. path)] (when (.exists f) (.delete f)))
     :cljs (try (.unlinkSync (js/require "fs") path) (catch :default _))
     :cljd (try (let [f (dart-io/File path)]
                  (when (.existsSync f) (.deleteSync f)))
                (catch Object _ nil))))


(defn- materialize-to-file-store
  "Drain v2 intake streams through a dao.jing observer into a file-backed
   content store; returns the store handle. Draining runs until blocked or
   end; a gap or defect is fatal to this composition (E11)."
  [intakes path]
  (let [h (jing-file/create-content-file path)]
    (loop [st (jing/observer-state
                (mapv (fn [s]
                        {:stream s
                         :cursor (:dao.stream/cursor
                                   (stream/cursor s :dao.stream/oldest))})
                      intakes))]
      (let [r (jing/observe-step! h st)]
        (case (:signal r)
          :dao.stream/ok (recur (:state r))
          :dao.stream/blocked h
          :dao.stream/end h
          (throw (ex-info "test observer hit a gap or defect"
                          {:result r})))))))


;; ---------------------------------------------------------------------------
;; W38: publish-then-schema-view-parity (§9 publisher parity)
;; ---------------------------------------------------------------------------

(deftest publish-then-schema-view-parity
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/transact! w (bootstrap-tx))
    (schema/transact! w [[7 :person/name "Alice"]
                         [7 :person/email "a@b.com"]
                         [8 :person/name "Bob"]])
    ;; (1) Query over the live relation via schema/current
    (let [rel (query/relation (datoms-of local))
          q-form '[:find ?e ?n :where [?e :person/name ?n]]
          live-result (qq q-form (schema/current rel))]
      ;; (2) Publish, materialize, query over published via schema/current
      (let [{:keys [manifest-address]} (schema/publish! w)
            path (temp-content-path "parity")]
        (try
          (materialize-to-file-store intake path)
          (let [opened (query/open-published!
                         (index/published-index
                           {:dao.jing/type :dao.jing/file :path path}
                           manifest-address))]
            (try
              (is (= live-result (qq q-form (schema/current opened)))
                  "q answers identically before and after publish")
              (finally
                (query/close-published! opened))))
          (finally
            (cleanup-file path))))
      (schema/close! w))))


;; ---------------------------------------------------------------------------
;; W39: published-descriptor-as-raw-db-input (§9 publisher parity)
;; ---------------------------------------------------------------------------

(deftest published-descriptor-as-raw-db-input
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/transact! w (bootstrap-tx))
    (schema/transact! w [[7 :person/name "Alice"]
                         [8 :person/name "Bob"]])
    ;; Drain local to get raw relation for comparison
    (let [all (datoms-of local)
          rel (query/relation all)
          raw-q '[:find ?e ?n :where [?e :person/name ?n]]
          raw-result (qq raw-q (query/current rel))]
      ;; Publish and query over published via schema/current — the opened
      ;; published index is a query value carrying covered indexes;
      ;; schema/current interprets it as a source.
      (let [{:keys [manifest-address]} (schema/publish! w)
            path (temp-content-path "raw-input")]
        (try
          (materialize-to-file-store intake path)
          (let [opened (query/open-published!
                         (index/published-index
                           {:dao.jing/type :dao.jing/file :path path}
                           manifest-address))]
            (try
              (is (= raw-result (qq raw-q (schema/current opened)))
                  "published source answers same raw query as drained relation")
              (finally
                (query/close-published! opened))))
          (finally
            (cleanup-file path))))
      (schema/close! w))))


;; ---------------------------------------------------------------------------
;; W41: published accepted as current source (§7)
;; ---------------------------------------------------------------------------

(deftest published-accepted-as-current-source
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/transact! w (bootstrap-tx))
    (schema/transact! w [[7 :person/name "Alice"]])
    (let [{:keys [manifest-address]} (schema/publish! w)
          path (temp-content-path "current-source")]
      (try
        (materialize-to-file-store intake path)
        (let [opened (query/open-published!
                       (index/published-index
                         {:dao.jing/type :dao.jing/file :path path}
                         manifest-address))]
          (try
            ;; schema/current must NOT reject the opened published index
            (is (= #{["Alice"]}
                   (qq '[:find ?n :where [7 :person/name ?n]]
                       (schema/current opened)))
                "the opened published index is accepted as :source by
                 schema/current")
            (finally
              (query/close-published! opened))))
        (finally
          (cleanup-file path))))
    (schema/close! w)))


;; ---------------------------------------------------------------------------
;; V14: the schema view is self-contained and never closes the store
;; (D6's seam). schema/current over an opened published index forces the
;; rows before returning and closes nothing — the ownership counter
;; proves it, where a re-query after close-published! could not
;; (close-published! is idempotent, so an early close would hide). The
;; counter wraps the store's :close-fn, to which jing/close! delegates;
;; plain data, no with-redefs, so it runs on every host.
;; ---------------------------------------------------------------------------

(deftest schema-view-is-self-contained-and-never-closes-the-store
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/transact! w (bootstrap-tx))
    (schema/transact! w [[7 :person/name "Alice"]])
    (let [form '[:find ?n :where [7 :person/name ?n]]
          expected (qq form (schema/current
                              (query/relation (datoms-of local))))
          {:keys [manifest-address]} (schema/publish! w)
          path (temp-content-path "self-contained")]
      (try
        (materialize-to-file-store intake path)
        (let [opened (query/open-published!
                       (index/published-index
                         {:dao.jing/type :dao.jing/file :path path}
                         manifest-address))
              closes  (atom 0)
              base    (:close-fn (:store opened))
              opened' (assoc-in opened [:store :close-fn]
                                (fn [] (swap! closes inc) (when base (base))))
              view    (schema/current opened')]
          (is (zero? @closes) "schema/current closed nothing")
          (query/close-published! opened')
          (is (= 1 @closes) "the owner's close reached the store exactly once")
          (is (= expected (qq form view)) "the view is self-contained after close")
          (is (= expected (qq form view)) "and stays so"))
        (finally
          (cleanup-file path))))
    (schema/close! w)))


;; ---------------------------------------------------------------------------
;; W42: schema-in-source (§9: install-by-transact then read; the view AND
;; the wrapper find schema rows in the same source; raw q sees them as
;; ordinary tuples)
;; ---------------------------------------------------------------------------

(deftest schema-in-source
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    ;; install: schema tuples transacted through the ordinary write path
    (schema/transact! w (bootstrap-tx))
    (schema/transact! w [[7 :person/name "Alice"]])
    (let [rows (datoms-of local)
          rel  (query/relation rows)]
      (testing "the view finds the schema in the same source"
        (is (= #{["Alice"]}
               (qq '[:find ?n :where [7 :person/name ?n]]
                   (schema/current rel)))))
      (testing "a NEW wrapper finds the schema in the same source"
        ;; strict validation only works if the wrapper read the schema
        (let [w2 (schema/transactor local intake {:strict true})]
          (is (thrown-with-msg?
                #?(:cljs js/Error :cljd Object :default Exception)
                #"valueType mismatch"
                (schema/transact! w2 [[8 :person/name 42]])))
          (schema/close! w2)))
      (testing "q over a raw view sees schema rows as ordinary tuples"
        (is (= #{[:db.type/string]}
               (qq '[:find ?v :where [21 :db/valueType ?v]]
                   (query/current rel))))))
    (schema/close! w)))


;; ---------------------------------------------------------------------------
;; W43: unique duplicate rejection (§3.1: against the live index AND the
;; batch itself; strict-only, lax appends for the §6 audit)
;; ---------------------------------------------------------------------------

(deftest unique-duplicate-rejected-strict-lax-appends
  (testing "batch self-conflict: two entities, one record, same unique value"
    (let [{:keys [local intake]} (fresh-streams)
          w (schema/transactor local intake {:strict true})]
      (schema/transact! w (bootstrap-tx))
      (is (thrown-with-msg?
            #?(:cljs js/Error :cljd Object :default Exception)
            #"unique duplicate"
            (schema/transact! w [{:db/id 7 :person/email "dup@x.com"}
                                 {:db/id 8 :person/email "dup@x.com"}])))
      (schema/close! w)))
  (testing "duplicate against the live index: different entity, later record"
    (let [{:keys [local intake]} (fresh-streams)
          w (schema/transactor local intake {:strict true})]
      (schema/transact! w (bootstrap-tx))
      (schema/transact! w [{:db/id 7 :person/email "a@b.com"}])
      (is (thrown-with-msg?
            #?(:cljs js/Error :cljd Object :default Exception)
            #"unique duplicate"
            (schema/transact! w [{:db/id 8 :person/email "a@b.com"}])))
      (schema/close! w)))
  (testing "lax appends the duplicate; the raw audit view sees both"
    (let [{:keys [local intake]} (fresh-streams)
          w (schema/transactor local intake)]
      (schema/transact! w (bootstrap-tx))
      (schema/transact! w [{:db/id 7 :person/email "a@b.com"}])
      (schema/transact! w [{:db/id 8 :person/email "a@b.com"}])
      (is (= #{[7] [8]}
             (qq '[:find ?e :where [?e :person/email "a@b.com"]]
                 (query/current (query/relation (datoms-of local))))))
      (schema/close! w))))


;; ---------------------------------------------------------------------------
;; W44: lax ambiguity is retained as data, never collapsed in wrapper state
;; ---------------------------------------------------------------------------

(deftest lax-unique-ambiguity-rejects-lookup
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/transact! w (bootstrap-tx))
    (schema/transact! w [{:db/id 7 :person/email "dup@x.com"}])
    (schema/transact! w [{:db/id 8 :person/email "dup@x.com"}])
    (let [before (datoms-of local)]
      (is (thrown-with-msg?
            #?(:cljs js/Error :cljd Object :default Exception)
            #"ambiguous lookup ref"
            (schema/transact! w [{:db/id [:person/email "dup@x.com"]
                                  :person/name "Nobody"}])))
      (is (= before (datoms-of local))
          "an ambiguous address has no value to append"))
    (schema/close! w)
    (testing "a strict wrapper reopening lax history retains the ambiguity"
      (let [strict-wrapper (schema/transactor local intake {:strict true})
            before (datoms-of local)]
        (is (thrown-with-msg?
              #?(:cljs js/Error :cljd Object :default Exception)
              #"ambiguous lookup ref"
              (schema/transact!
                strict-wrapper
                [{:db/id [:person/email "dup@x.com"]
                  :person/name "Nobody"}])))
        (is (= before (datoms-of local)))
        (schema/close! strict-wrapper)))))


;; ---------------------------------------------------------------------------
;; W45: retractions update the live unique projection
;; ---------------------------------------------------------------------------

(deftest retract-frees-unique-value
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake {:strict true})]
    (schema/transact! w (bootstrap-tx))
    (schema/transact! w [{:db/id 7 :person/email "reuse@x.com"}])
    (schema/transact! w [[:db/retract 7 :person/email "reuse@x.com"]])
    (is (= :dao.stream/ok (:dao.stream/outcome
                            (schema/transact! w
                                              [{:db/id 8
                                                :person/email "reuse@x.com"}]))))
    (is (= #{[8]}
           (qq '[:find ?e :where [?e :person/email "reuse@x.com"]]
               (query/current (query/relation (datoms-of local))))))
    (schema/close! w)))


;; ---------------------------------------------------------------------------
;; W46: map-form desired state repairs every lax card-one predecessor
;; ---------------------------------------------------------------------------

(deftest map-form-repairs-all-card-one-values
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake)]
    (schema/transact! w (bootstrap-tx))
    (schema/transact! w [[:db/add 7 :person/name "old-a"]])
    (schema/transact! w [[:db/add 7 :person/name "old-b"]])
    (let [result (schema/transact! w [{:db/id 7 :person/name "new"}])
          repaired (:dao.space/datoms result)]
      (is (= 3 (count repaired))
          "repair retracts both predecessors and asserts desired state")
      (is (= #{"old-a" "old-b"}
             (into #{}
                   (comp (filter datom/retracted?)
                         (map index/datom-v))
                   repaired))))
    (is (= #{["new"]}
           (qq '[:find ?v :where [7 :person/name ?v]]
               (query/current (query/relation (datoms-of local))))))
    (schema/close! w)))


;; ---------------------------------------------------------------------------
;; W47: one wrapper is one serialized writer boundary
;; ---------------------------------------------------------------------------

(deftest concurrent-strict-unique-writes-serialize
  #?(:clj
     (let [{:keys [local intake]} (fresh-streams)
           w (schema/transactor local intake {:strict true})
           start (promise)]
       (schema/transact! w (bootstrap-tx))
       (let [writes (doall
                      (for [e (range 100 116)]
                        (future
                          @start
                          (try
                            (schema/transact!
                              w [{:db/id e :person/email "race@x.com"}])
                            :committed
                            (catch Exception _ :rejected)))))]
         (deliver start true)
         (is (= 1 (count (filter #{:committed} (map deref writes)))))
         (is (= 1
                (count
                  (qq '[:find ?e
                        :where [?e :person/email "race@x.com"]]
                      (query/current
                        (query/relation (datoms-of local))))))))
       (schema/close! w))
     :default
     (is true)))


;; ---------------------------------------------------------------------------
;; W48: only emitted assertions establish entity existence
;; ---------------------------------------------------------------------------

(deftest non-emitting-map-is-not-a-ref-target
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake {:strict true})]
    (schema/transact! w (bootstrap-tx))
    (let [before (datoms-of local)]
      (is (thrown-with-msg?
            #?(:cljs js/Error :cljd Object :default Exception)
            #"dangling ref"
            (schema/transact! w [{:db/id 8 :person/friends []}
                                 {:db/id 9 :person/friends 8}])))
      (is (= before (datoms-of local))
          "a zero-emission map cannot create a transient phantom entity"))
    (schema/close! w)))


;; ---------------------------------------------------------------------------
;; W49: one transaction cannot carry opposite operations for one EAV
;; ---------------------------------------------------------------------------

(deftest opposite-operations-on-one-eav-reject-atomically
  (doseq [strict? [true false]
          tx-data [[[:db/add 7 :person/name "x"]
                    [:db/retract 7 :person/name "x"]]
                   [[:db/retract 7 :person/name "x"]
                    [:db/add 7 :person/name "x"]]]]
    (let [{:keys [local intake]} (fresh-streams)
          w (schema/transactor local intake {:strict strict?})]
      (schema/transact! w (bootstrap-tx))
      (let [before (datoms-of local)]
        (is (thrown-with-msg?
              #?(:cljs js/Error :cljd Object :default Exception)
              #"opposite operations"
              (schema/transact! w tx-data)))
        (is (= before (datoms-of local))
            "a record rejected by current-state semantics appends nothing")
        (is (= 1 (:dao.space/t (schema/transact! w [[8 :person/name "valid"]])))
            "a rejected plan consumes no transaction time"))
      (schema/close! w))))


;; ---------------------------------------------------------------------------
;; W50: schema retractions become the next transaction's epoch
;; ---------------------------------------------------------------------------

(deftest schema-retractions-update-live-and-reopened-wrappers
  (let [{:keys [local intake]} (fresh-streams)
        w (schema/transactor local intake {:strict true})]
    (schema/transact! w (bootstrap-tx))
    (schema/transact! w [[:db/retract 23 :db/unique true]
                         [:db/retract 23 :db/cardinality
                          :db.cardinality/one]])
    (schema/transact! w [[7 :person/email "shared@x.com"]
                         [8 :person/email "shared@x.com"]
                         [7 :person/email "second@x.com"]])
    (is (= #{[7 "shared@x.com"]
             [7 "second@x.com"]
             [8 "shared@x.com"]}
           (qq '[:find ?e ?v :where [?e :person/email ?v]]
               (schema/current (query/relation (datoms-of local))))))
    (schema/close! w)
    (let [reopened (schema/transactor local intake {:strict true})]
      (is (= :dao.stream/ok (:dao.stream/outcome
                              (schema/transact! reopened
                                                [[9 :person/email "shared@x.com"]]))))
      (is (thrown-with-msg?
            #?(:cljs js/Error :cljd Object :default Exception)
            #"unmatched lookup ref"
            (schema/transact!
              reopened
              [{:db/id [:person/email "shared@x.com"]
                :person/name "Nobody"}])))
      (schema/close! reopened))))


;; ---------------------------------------------------------------------------
;; W51: reified metadata refs seed schema and values on wrapper open
;; ---------------------------------------------------------------------------

(deftest reified-metadata-reference-seeds-wrapper-state
  (let [{:keys [local intake]} (fresh-streams)
        metadata-e 99
        rows (conj (mapv #(assoc % 4 metadata-e) schema-rows)
                   [7 :person/name "old" 0 metadata-e])]
    (doseq [row rows]
      (stream/append! local row))
    (let [w (schema/transactor local intake {:strict true})
          result (schema/transact! w [{:db/id 7 :person/name "new"}])]
      (is (= #{[7 :person/name "old" (:db/retract datom/reserved)]
               [7 :person/name "new" (:db/assert datom/reserved)]}
             (set (map (fn [d]
                         [(index/datom-e d)
                          (index/datom-a d)
                          (index/datom-v d)
                          (index/datom-m d)])
                       (:dao.space/datoms result))))
          "reopened state repairs the metadata-backed live value")
      (schema/close! w))))


;; ---------------------------------------------------------------------------
;; W52: the guarantee boundary: :db/unique is per-stream, never per-world
;; ---------------------------------------------------------------------------

(deftest unique-is-per-stream-not-per-world
  (let [{a-local :local, a-intake :intake} (fresh-streams)
        {b-local :local, b-intake :intake} (fresh-streams)
        wa (schema/transactor a-local a-intake {:strict true})
        wb (schema/transactor b-local b-intake {:strict true})]
    (schema/transact! wa (bootstrap-tx))
    (schema/transact! wb (bootstrap-tx))
    (testing "the same unique value commits on two independent streams"
      (is (= :dao.stream/ok (:dao.stream/outcome (schema/transact!
                                                   wa [{:db/id 7 :person/email "dup@x.com"}]))))
      (is (= :dao.stream/ok (:dao.stream/outcome (schema/transact!
                                                   wb [{:db/id 8 :person/email "dup@x.com"}])))))
    (testing "each stream still enforces its own uniqueness"
      (is (thrown-with-msg?
            #?(:cljs js/Error :cljd Object :default Exception)
            #"unique duplicate"
            (schema/transact! wb [{:db/id 9 :person/email "dup@x.com"}]))))
    (schema/close! wa)
    (schema/close! wb)))
