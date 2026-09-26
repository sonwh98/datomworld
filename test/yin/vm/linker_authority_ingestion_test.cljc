(ns yin.vm.linker-authority-ingestion-test
  "Slice A3 (docs/design/yin.vm.linker.md section 8.2): datom
   ingestion. The section 8.2 assertion datoms -- `[ev
   :yin.module/envelope env]` beside `[ev :yin.module/proof proof]` --
   are committed to an in-memory dao.space, read at a snapshot through
   the query API (the `current` view, as-of bounded at the committed
   cursor position, its facts drained through `match`), and assembled by
   `yin.vm.linker.authority/events-from-datoms` into exactly the event
   sequence `name-environment` folds. Ingestion is pure over the datoms
   it is handed: the source and the snapshot are the composition's, and
   the tests below are the whole composition."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.space.transact :as transact]
            [dao.space.transactor :as transactor]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm.linker.authority :as authority]))


;; =============================================================================
;; Fixtures: an in-memory dao.space, the A1/A2 signature stand-in, and
;; the two reads a composition performs at a snapshot
;; =============================================================================

(defn- addr
  "A distinct manifest address for a distinct payload."
  [payload]
  (jing/segment-key payload))


(defn- fake-sign
  "The 'signature' over an envelope: the blake3 digest of its canonical
   bytes, as a plain hex string (the A1/A2 stand-in, unchanged)."
  [env]
  (jing/content-hash env))


(defn- fake-verify
  [_key bs sig]
  (= sig (jing/digest-bytes :blake3 bs)))


(defn- sig-decl
  [principal]
  {:proof :yin.module/signature
   :key [:key principal]
   :verify fake-verify
   :seq-floor 0})


(defn- attested-decl
  [log-id]
  {:proof :yin.module/attested :dao.stream/identity log-id})


(defn- assertion-env
  [principal name-sym manifest n]
  {:yin.module/op :assert
   :yin.module/name name-sym
   :yin.module/manifest manifest
   :yin.module/asserted-by principal
   :yin.module/seq n})


(defn- retraction-env
  [principal asserted n]
  {:yin.module/op :retract
   :yin.module/of (authority/assertion-id asserted)
   :yin.module/asserted-by principal
   :yin.module/seq n})


(defn- open-space
  "An in-memory dao.space: a transactor value over a complete-retention
   memory-log local stream and a ringbuffer intake pool."
  []
  (let [mem (memory-log/create! {:dao.stream/type :dao.stream/memory-log})
        pool (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                                  :dao.stream.ringbuffer/capacity 64})]
    (transactor/create!
      {:local-stream (:dao.stream/handle mem)
       :intake-pool [(:dao.stream/handle pool)]})))


(defn- commit!
  "Resolve tx-data against the retained history and commit it as one
   atomic transaction. Answers the receipt, whose :dao.space/t is the
   committed cursor position -- the snapshot the reads below bound to."
  [log tx-data]
  (let [prepared (transact/prepare-tx
                   {:base-datoms (index/snapshot-datoms (:local-stream log))
                    :tx-data tx-data})]
    (transactor/transact! log (mapv #(assoc % 3 nil) (:datoms prepared)))))


(defn- signed-event
  "The section 8.2 datoms for one signed event: envelope and proof on
   one event entity, `ev` a tempid."
  [ev env]
  [[:db/add ev :yin.module/envelope env]
   [:db/add ev :yin.module/proof {:yin.module/signature (fake-sign env)}]])


(defn- events-at
  "The authority events visible at cursor position `t`: the retained
   history under the `current` view, as-of bounded, its current facts
   read through `match` and handed to ingestion. `carrier` is the
   :dao.stream/identity of the stream this read is from, nil when the
   read names none."
  ([log t] (events-at log t nil))
  ([log t carrier]
   (let [view (query/current
                (query/relation
                  (index/snapshot-datoms (:local-stream log)))
                t)]
     (authority/events-from-datoms
       (query/match view ['_ '_ '_])
       carrier))))


(defn- entry
  [snapshot name-sym]
  (get (:names snapshot) name-sym))


(defn- discards
  [snapshot kind]
  (filter (fn [d] (= kind (:kind d))) (:diagnostics snapshot)))


;; =============================================================================
;; End to end from transacted datoms
;; =============================================================================

(deftest transacted-assertion-datoms-resolve-the-name
  (let [log (open-space)
        a (addr [:manifest 'my.lib 1])
        receipt (commit! log (signed-event
                               "tid_ev" (assertion-env 'alice 'my.lib a 1)))
        t (:dao.space/t receipt)
        snap (authority/name-environment
               {:snapshot t :principals {'alice (sig-decl 'alice)}}
               (events-at log t))]
    ;; the first committed transaction is cursor position 0
    (is (= 0 t))
    (is (= {'my.lib {:status :ok
                     :address a
                     :yin.link/provenance
                     {:yin.module/asserted-by ['alice]
                      :yin.link/proof-kind [:yin.module/signature]
                      :yin.link/snapshot 0}}}
           (:names snap)))
    (is (empty? (:diagnostics snap)))
    (is (= {'alice 1} (:honored-seq snap)))))


(deftest datom-transacted-after-the-snapshot-is-invisible-until-rebuilt
  (let [log (open-space)
        old (addr [:m 'my.lib 1])
        new (addr [:m 'your.lib 2])
        t1 (:dao.space/t
             (commit! log (signed-event
                            "tid_1" (assertion-env 'alice 'my.lib old 1))))
        ;; transacted after the first snapshot's cursor position; the
        ;; reads below happen after both commits, so the bound alone
        ;; decides what each snapshot sees
        t2 (:dao.space/t
             (commit! log (signed-event
                            "tid_2" (assertion-env 'alice 'your.lib new 2))))
        snap1 (authority/name-environment
                {:snapshot t1 :principals {'alice (sig-decl 'alice)}}
                (events-at log t1))
        ;; the composition advances: a fresh read at the new cursor
        snap2 (authority/name-environment
                {:snapshot t2 :principals {'alice (sig-decl 'alice)}}
                (events-at log t2))]
    (is (= :ok (:status (entry snap1 'my.lib))))
    (is (= old (:address (entry snap1 'my.lib))))
    ;; invisible at the first snapshot: no name, no event, no diagnostic
    (is (nil? (entry snap1 'your.lib)))
    (is (empty? (:diagnostics snap1)))
    ;; the first snapshot is a value: the second build changed nothing of it
    (is (= {'my.lib {:status :ok
                     :address old
                     :yin.link/provenance
                     {:yin.module/asserted-by ['alice]
                      :yin.link/proof-kind [:yin.module/signature]
                      :yin.link/snapshot t1}}}
           (:names snap1)))
    ;; at the rebuilt snapshot the later datom exists
    (is (= :ok (:status (entry snap2 'your.lib))))
    (is (= new (:address (entry snap2 'your.lib))))
    (is (= t2 (-> (entry snap2 'your.lib)
                  :yin.link/provenance :yin.link/snapshot)))
    (is (= {'alice 2} (:honored-seq snap2)))))


(deftest transacted-retraction-removes-exactly-its-assertion
  (let [log (open-space)
        doomed-env (assertion-env 'alice 'my.lib (addr [:m 'my.lib 1]) 1)
        kept (addr [:m 'my.lib 3])
        other (addr [:m 'other.lib 2])
        tx-data (concat
                  (signed-event "tid_1" doomed-env)
                  (signed-event "tid_2"
                                (assertion-env 'alice 'my.lib kept 2))
                  (signed-event "tid_3"
                                (assertion-env 'alice 'other.lib other 3))
                  (signed-event "tid_4" (retraction-env 'alice doomed-env 4)))
        t (:dao.space/t (commit! log tx-data))
        snap (authority/name-environment
               {:snapshot t :principals {'alice (sig-decl 'alice)}}
               (events-at log t))]
    ;; the retracted id is gone; the same-named sibling survives it
    (is (= :ok (:status (entry snap 'my.lib))))
    (is (= kept (:address (entry snap 'my.lib))))
    (is (= other (:address (entry snap 'other.lib))))
    (is (empty? (discards snap :dangling-retraction)))
    (is (= {'alice 4} (:honored-seq snap)))))


;; =============================================================================
;; Malformed datoms fail closed through the policy
;; =============================================================================

(deftest malformed-authority-datoms-fail-closed
  (let [log (open-space)
        env (assertion-env 'alice 'my.lib (addr [:m 1]) 1)
        t (:dao.space/t
            (commit! log
                     [;; an envelope datom with no proof datom
                      [:db/add "tid_1" :yin.module/envelope env]
                      ;; a proof datom with no envelope datom
                      [:db/add "tid_2" :yin.module/proof
                       {:yin.module/signature (fake-sign env)}]
                      ;; an envelope datom whose value is no envelope
                      [:db/add "tid_3" :yin.module/envelope
                       "not an envelope"]]))
        events (events-at log t)
        snap (authority/name-environment
               {:snapshot t :principals {'alice (sig-decl 'alice)}}
               events)]
    ;; one event per envelope datom; the orphan proof names no event
    (is (= 2 (count events)))
    (is (= :absent (:reason (entry snap 'my.lib))))
    ;; only the no-proof and the malformed-envelope kinds appear
    (is (= #{:no-proof :malformed-envelope}
           (set (map :kind (:diagnostics snap)))))
    (is (= 1 (count (discards snap :no-proof))))
    (is (= 1 (count (discards snap :malformed-envelope))))
    (is (= :not-an-envelope
           (:defect (first (discards snap :malformed-envelope)))))))


;; =============================================================================
;; The carrier of the read gates attested proofs
;; =============================================================================

(deftest attested-datoms-prove-only-through-the-reads-carrier
  (let [log (open-space)
        a (addr [:m 1])
        env (assertion-env 'reporter 'log.lib a 1)
        t (:dao.space/t
            (commit! log
                     [[:db/add "tid_ev" :yin.module/envelope env]
                      [:db/add "tid_ev" :yin.module/proof
                       {:yin.module/attested :dao.stream/log-1}]]))
        decls {'reporter (attested-decl :dao.stream/log-1)}]
    (testing "read from the declared stream, the envelope is proven"
      (let [snap (authority/name-environment
                   {:snapshot t :principals decls}
                   (events-at log t :dao.stream/log-1))]
        (is (= :ok (:status (entry snap 'log.lib))))
        (is (= a (:address (entry snap 'log.lib))))
        (is (empty? (:diagnostics snap)))))
    (testing "the same datoms read from nowhere carry no proof"
      (let [snap (authority/name-environment
                   {:snapshot t :principals decls}
                   (events-at log t))]
        (is (= :absent (:reason (entry snap 'log.lib))))
        (is (= 1 (count (discards snap :bad-proof))))))))


;; =============================================================================
;; One proof per event entity, whatever the datom order
;; =============================================================================

(deftest two-distinct-proofs-on-one-entity-fail-closed
  (let [log (open-space)
        env (assertion-env 'alice 'my.lib (addr [:m 1]) 1)
        t (:dao.space/t
            (commit! log
                     [[:db/add "tid_ev" :yin.module/envelope env]
                      [:db/add "tid_ev" :yin.module/proof
                       {:yin.module/signature (fake-sign env)}]
                      [:db/add "tid_ev" :yin.module/proof
                       {:yin.module/signature "forged"}]]))
        events (events-at log t)
        snap (authority/name-environment
               {:snapshot t :principals {'alice (sig-decl 'alice)}}
               events)]
    (is (= 1 (count events)))
    (is (not (contains? (first events) :yin.module/proof)))
    (is (= :absent (:reason (entry snap 'my.lib))))
    (is (= 1 (count (discards snap :no-proof))))))


(deftest ingestion-is-invariant-under-datom-permutation
  (let [env (assertion-env 'alice 'my.lib (addr [:m 1]) 1)
        good {:yin.module/signature (fake-sign env)}
        bad {:yin.module/signature "forged"}
        datoms [[1 :yin.module/envelope env]
                [1 :yin.module/proof good]
                [1 :yin.module/proof bad]
                [2 :yin.module/envelope env]
                [2 :yin.module/proof good]]
        result (fn [ds] (frequencies (authority/events-from-datoms ds)))
        expected (result datoms)]
    (doseq [ds [(reverse datoms)
                (concat (drop 2 datoms) (take 2 datoms))
                [(nth datoms 2) (nth datoms 4) (nth datoms 0)
                 (nth datoms 3) (nth datoms 1)]]]
      (is (= expected (result ds))))))


(deftest orphan-proof-joins-a-later-envelope-deterministically
  (let [env (assertion-env 'alice 'my.lib (addr [:m 1]) 1)
        proof {:yin.module/signature (fake-sign env)}
        orphan [[7 :yin.module/proof proof]]
        joined [[7 :yin.module/proof proof]
                [7 :yin.module/envelope env]]]
    (is (empty? (authority/events-from-datoms orphan)))
    (is (= [{:yin.module/envelope env :yin.module/proof proof}]
           (authority/events-from-datoms joined)
           (authority/events-from-datoms (reverse joined))))))
