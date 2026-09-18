(ns yin.vm.ledger-test
  "U9 contract tests: derivation records and the ledger
   (`docs/design/yin.vm.code-as-tuples.md` §5.2, §8).

   The criteria of the implementation plan's U9 unit, as tests: the §8.6
   provenance walk returns the tree a segment came from;
   retract-then-reassert keeps the reasserted address; a tampered vector
   reports `:yin.k/derivation-mismatch`, never a silent re-lower."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.space.transactor :as transactor]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.ledger :as ledger]
            [yin.vm.linearize :as linearize]))


;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private worked-example
  "`((fn [x] (if x 1 0)) 10)`: one lambda body, a branch, two literals,
   and a call — a vector with a body out of line and every terminator
   kind."
  {:type :application
   :operator {:type :lambda
              :params '[x]
              :body {:type :if
                     :test {:type :variable :name 'x}
                     :consequent {:type :literal :value 1}
                     :alternate {:type :literal :value 0}}}
   :operands [{:type :literal :value 10}]})


(def ^:private other-example
  "A different tree: `((fn [x] x) 20)`."
  {:type :application
   :operator {:type :lambda :params '[x] :body {:type :variable :name 'x}}
   :operands [{:type :literal :value 20}]})


(defn- open-ledger
  "A `dao.space` transactor value over a complete-retention memory-log
   local stream and a ringbuffer intake pool."
  []
  (transactor/create!
    {:local-stream (:dao.stream/handle
                     (memory-log/create! {:dao.stream/type :dao.stream/memory-log}))
     :intake-pool [(:dao.stream/handle
                     (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                                          :dao.stream.ringbuffer/capacity 64}))]}))


(defn- refs
  "The ledger's retained history as raw d5 rows."
  [log]
  (index/snapshot-datoms (:local-stream log)))


(defn- derive-and-name!
  "Lower `ast`, write its `:derive` event, and assert `sym` over the tree
   through `name-ent` — a tempid for a fresh name entity (its identity
   datom is written with it), the resolved id of an existing one for a
   reassertion. The naming datom's m names the event. One transaction;
   answers the derive material and the receipt."
  [log ast sym name-ent]
  (let [bc (vm/ast->semantic-bytecode ast)
        material (ledger/derive-lowering bc (linearize/lower-rows bc) "tid_ev")
        receipt (ledger/transact!
                  log
                  (into (:event material)
                        (into (ledger/assert-name name-ent
                                                  (:tree-address material)
                                                  "tid_ev")
                              (when (string? name-ent)
                                (ledger/name-entity name-ent sym)))))]
    (assoc material :receipt receipt)))


;; ---------------------------------------------------------------------------
;; The record (§8.2)
;; ---------------------------------------------------------------------------

(deftest derive-record-is-content-addressed
  (let [bc (vm/ast->semantic-bytecode worked-example)
        {:keys [record record-address segment-address tree-address]}
        (ledger/derive-lowering bc (linearize/lower-rows bc) "tid_ev")]
    (is (= {:yin.ledger/op :derive
            :yin.ledger/input tree-address
            :yin.ledger/output segment-address
            :yin.ledger/function :yin.vm/lower
            :yin.ledger/profile ledger/lowering-profile}
           record)
        "§5.2's record shape, verbatim")
    (is (= record-address (jing/segment-key record)))
    (is (jing/segment-address? record-address))
    (testing "one tree and one profile, one record address"
      (let [bc' (vm/ast->semantic-bytecode worked-example)]
        (is (= record-address
               (:record-address (ledger/derive-lowering
                                  bc' (linearize/lower-rows bc') "tid_ev"))))))
    (testing "a different tree lowers to a different record"
      (let [other (vm/ast->semantic-bytecode other-example)]
        (is (not= record-address
                  (:record-address (ledger/derive-lowering
                                     other (linearize/lower-rows other)
                                     "tid_ev"))))))))


;; ---------------------------------------------------------------------------
;; Two-step verification (§5.2.2)
;; ---------------------------------------------------------------------------

(deftest verification-passes-a-real-lowering
  (let [bc (vm/ast->semantic-bytecode worked-example)
        lowered (linearize/lower-rows bc)
        {:keys [record]} (ledger/derive-lowering bc lowered "tid_ev")]
    (is (nil? (ledger/verify-derivation bc (:vector lowered) record)))))


(deftest tampered-vector-reports-derivation-mismatch
  (let [bc (vm/ast->semantic-bytecode worked-example)
        lowered (linearize/lower-rows bc)
        honest (ledger/derive-lowering bc lowered "tid_ev")
        ;; A dishonest lowering: the same tree, a vector the profile does
        ;; not lower it to, and a record claiming that vector's address as
        ;; its output. The record is self-consistent — only re-deriving
        ;; exposes it.
        tampered (assoc-in (:vector lowered) [0 1] '[q])
        forged (ledger/derive-record (:tree-address honest)
                                     (jing/segment-key tampered))]
    (is (= {:kind :yin.k/derivation-mismatch
            :output (jing/segment-key tampered)
            :actual (:segment-address honest)}
           (ledger/verify-derivation bc tampered forged))
        "the tampered vector is reported by name, not re-lowered over")))


(deftest tampered-content-reports-hash-mismatch
  (let [bc (vm/ast->semantic-bytecode worked-example)
        lowered (linearize/lower-rows bc)
        {:keys [record]} (ledger/derive-lowering bc lowered "tid_ev")]
    (testing "a vector that does not hash to the record's output"
      (let [tampered (assoc-in (:vector lowered) [0 1] '[q])]
        (is (= {:kind :yin.k/hash-mismatch
                :value (:yin.ledger/output record)}
               (ledger/verify-derivation bc tampered record)))))
    (testing "a row whose id is not its content address"
      (let [root-row (get (:rows bc) (:root bc))
            ;; the tail? slot of the root application: still a valid :bool,
            ;; so every §7.4 rule holds and only the address check fails
            lying (update bc :rows assoc (:root bc) (assoc root-row 4 true))]
        (is (= {:kind :yin.k/hash-mismatch :value (:root bc)}
               (ledger/verify-derivation lying (:vector lowered) record)))))))


(deftest foreign-profile-reports-profile-mismatch
  (let [bc (vm/ast->semantic-bytecode worked-example)
        lowered (linearize/lower-rows bc)
        {:keys [record]} (ledger/derive-lowering bc lowered "tid_ev")
        v2 {:yin.lower/profile "ast-to-bytecode-v2"
            :yin.code/contract "v2"
            :yin.k/version 0}]
    (is (= {:kind :yin.k/profile-mismatch
            :record v2
            :implemented ledger/lowering-profile}
           (ledger/verify-derivation bc (:vector lowered)
                                     (assoc record :yin.ledger/profile v2)))
        "a consumer that does not implement the record's profile does not
         recompute; both profiles are named")))


;; ---------------------------------------------------------------------------
;; The ledger event and the provenance link (§8.2, §8.6)
;; ---------------------------------------------------------------------------

(deftest provenance-walk-returns-the-tree-a-segment-came-from
  (let [log (open-ledger)
        {:keys [record-address segment-address tree-address receipt]}
        (derive-and-name! log worked-example 'my.ns/f "tid_n")
        tempids (:dao.space/tempids receipt)
        ev (get tempids "tid_ev")
        rows (refs log)]
    (is (= :dao.stream/ok (:dao.stream/outcome receipt)))
    (is (>= ev datom/first-user-id)
        "the event is a local user entity, not a reserved marker")
    (is (= tree-address
           (ledger/segment->tree (query/current (query/relation rows))
                                 segment-address))
        "§8.6's walk, the :derive half")
    (is (= tree-address (ledger/resolve-name rows 'my.ns/f))
        "the name resolves to the tree's address")
    (is (= ev (ledger/naming-event rows (get tempids "tid_n") tree-address))
        "the naming datom's m names its event entity")
    (is (= #{[tree-address segment-address :yin.vm/lower
              ledger/lowering-profile record-address]}
           (query/collect
             (query/q '[:find ?in ?out ?fn ?p ?rec :in $ ?ev
                        :where [?ev :yin.ledger/input ?in]
                        [?ev :yin.ledger/output ?out]
                        [?ev :yin.ledger/function ?fn]
                        [?ev :yin.ledger/profile ?p]
                        [?ev :yin.ledger/record ?rec]]
                      (query/current (query/relation rows)) ev))))
    (is (= record-address
           (jing/segment-key (ledger/derive-record tree-address
                                                   segment-address)))
        "the record rebuilt from the walk's addresses rehashes to the
         address the event names")))


(deftest retract-then-reassert-keeps-the-reasserted-address
  (let [log (open-ledger)
        first-derive (derive-and-name! log worked-example 'my.ns/f "tid_n")
        n (get (:dao.space/tempids (:receipt first-derive)) "tid_n")
        addr (:tree-address first-derive)
        retract (ledger/transact! log (ledger/retract-name n addr))
        reasserted (derive-and-name! log worked-example 'my.ns/f n)
        rows (refs log)
        t-assert (:dao.space/t (:receipt first-derive))
        t-retract (:dao.space/t retract)
        t-reassert (:dao.space/t (:receipt reasserted))]
    (is (= addr (ledger/resolve-name rows 'my.ns/f))
        "the reasserted address survives the retraction between")
    (is (= addr (ledger/resolve-name rows 'my.ns/f t-assert)))
    (is (nil? (ledger/resolve-name rows 'my.ns/f t-retract))
        "as of the retraction the name resolves to nothing")
    (is (= addr (ledger/resolve-name rows 'my.ns/f t-reassert))
        "the fold's latest-event-wins keeps the address a hand-written
         negation over raw rows would drop")
    (is (= (get (:dao.space/tempids (:receipt reasserted)) "tid_ev")
           (ledger/naming-event rows n addr))
        "the reassertion's provenance names the second event")))


(deftest a-name-moves-to-a-new-tree-and-back-as-of
  (let [log (open-ledger)
        first-derive (derive-and-name! log worked-example 'my.ns/f "tid_n")
        n (get (:dao.space/tempids (:receipt first-derive)) "tid_n")
        old (:tree-address first-derive)
        t-old (:dao.space/t (:receipt first-derive))
        _retract (ledger/transact! log (ledger/retract-name n old))
        new-derive (derive-and-name! log other-example 'my.ns/f n)
        rows (refs log)]
    (is (= (:tree-address new-derive) (ledger/resolve-name rows 'my.ns/f)))
    (is (= old (ledger/resolve-name rows 'my.ns/f t-old))
        "as of the first transaction, the first tree")))
