(ns yin.vm.ucf.authority-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.seam :as seam]
            [yin.vm.ucf.ledger :as ledger]))


(defn- open
  ([frames] (open frames nil nil))
  ([frames cut] (open frames cut nil))
  ([frames cut opts]
   (authority/open! (journal/memory-backend frames cut) opts)))


(defn- auth
  ([frames] (auth frames nil nil))
  ([frames cut] (auth frames cut nil))
  ([frames cut opts] (::authority/authority (open frames cut opts))))


(defn- op
  [n]
  {:yin.k/occurrence "O" :yin.k/seq n})


(defn- status
  [r]
  (:yin.k/status r))


(defn- outcome
  [r]
  (:dao.stream/outcome r))


(defn- cur
  [h a]
  (:dao.stream/cursor (stream/cursor h a)))


(defn- read-all
  "Every value from cursor c to the tail, and the terminal outcome."
  [h c]
  (loop [c c vs [] n 0]
    (let [r (stream/next h c)]
      (if (and (= :dao.stream/ok (outcome r)) (< n 1000))
        (recur (:dao.stream/cursor r) (conj vs (:dao.stream/value r)) (inc n))
        {:values vs :terminal (outcome r)}))))


(defn- admitted
  "The recorded results of op id `o` in the journal's frames."
  [frames o]
  (for [bs (rest @frames)
        :let [ds (get-in (cbor/decode bs)
                         [:dao.stream.journal/value
                          :dao.space/transaction :datoms])
              es (set (keep (fn [[e a v]]
                              (when (and (= :yin.k/op-id a) (= o v)) e))
                            ds))]
        [e a v] ds
        :when (and (es e) (= :yin.k/result a))]
    v))


(defn- enrolled
  "A fresh journal with one enrolled target: the frames and the target."
  []
  (let [frames (atom [])
        a (auth frames)
        i (:yin.k/target (authority/enroll! a))]
    (authority/close! a)
    [frames i]))


(deftest open-enroll-and-reopen
  (let [frames (atom [])
        r (open frames)
        a (::authority/authority r)
        arb (:dao.stream/identity r)
        e (authority/enroll! a)
        i (:yin.k/target e)]
    (is (= :open (status r)))
    (is (= {:yin.k/status :committed :yin.k/target i :dao.space/t 0} e))
    (is (= (ledger/target-identity arb 0) i)
        "the target identity derives from the arbitration identity and t")
    (is (= :committed (status (seam/commit-effect! a i (op 0) :v))))
    (is (= 3 (count @frames)) "a header and one frame per decision")
    (let [p (authority/projection a)]
      (is (= :closed (status (authority/close! a))))
      (is (= :closed (status (authority/enroll! a))))
      (is (nil? (authority/projection a)))
      (let [r2 (open frames)
            a2 (::authority/authority r2)]
        (is (= arb (:dao.stream/identity r2)))
        (is (= p (authority/projection a2))
            "the reopened projection is the folded ledger")
        (is (= {:yin.k/status :committed
                :yin.k/target (ledger/target-identity arb 2)
                :dao.space/t 2}
               (authority/enroll! a2))
            "t continues from the record count")))))


(deftest a-retry-finds-the-recorded-result
  (let [[frames i] (enrolled)
        a (auth frames)]
    (is (= {:yin.k/status :committed :yin.k/result ledger/ok-result
            :dao.space/t 1}
           (seam/commit-effect! a i (op 0) :v)))
    (is (= {:yin.k/status :replayed :yin.k/result ledger/ok-result}
           (seam/commit-effect! a i (op 0) :v)))
    (is (= {:yin.k/status :intent-conflict}
           (seam/commit-effect! a i (op 0) :w)))
    (is (= {:yin.k/status :refused :yin.k/reason :unknown-target}
           (seam/commit-effect! a "nowhere" (op 1) :v)))
    (is (= {:yin.k/status :replayed :yin.k/result ledger/ok-result}
           (seam/commit-effect! (auth frames) i (op 0) :v))
        "after reopen too")
    (is (= [ledger/ok-result] (admitted frames (op 0))))))


(deftest crash-at-each-cut-commits-zero-or-one
  (doseq [[cut persisted] [[:before-frame 0]
                           [:after-frame-before-visible 1]
                           [:torn-frame 0]]]
    (testing (name cut)
      (let [[frames i] (enrolled)
            a (auth frames cut)
            reader (authority/target-reader a i)
            kept (cur reader :dao.stream/oldest)]
        (is (= {:yin.k/status :suspended :yin.k/reason :uncertain-append}
               (seam/commit-effect! a i (op 0) :v)))
        (testing "poison blocks every transition and every reader"
          (let [before @frames]
            (is (= {:yin.k/status :suspended :yin.k/reason :poisoned}
                   (seam/commit-effect! a i (op 1) :w)))
            (is (= :suspended (status (authority/enroll! a))))
            (is (= :suspended (status (authority/close-target! a i))))
            (is (= before @frames) "a poisoned authority writes nothing"))
          (is (nil? (authority/projection a)))
          (is (= :dao.stream/transport-error
                 (outcome (stream/cursor reader :dao.stream/oldest))))
          (is (= :dao.stream/transport-error
                 (outcome (stream/next reader kept)))))
        (let [a2 (auth frames)]
          (is (= persisted (count (admitted frames (op 0))))
              "reopen shows zero or one commit")
          (is (= (if (= 1 persisted) :replayed :committed)
                 (status (seam/commit-effect! a2 i (op 0) :v)))
              "a retry after reopen finds the recorded result")
          (is (= [ledger/ok-result] (admitted frames (op 0))))
          (is (= [:v] (:values (read-all (authority/target-reader a2 i)
                                         kept)))
              "readers on the reopened authority serve again"))))))


(defn- frame
  [v]
  (cbor/encode v))


(defn- entry
  [p t datoms]
  (frame {:dao.stream.journal/position p
          :dao.stream.journal/value {:dao.space/transaction
                                     {:t t
                                      :datoms (mapv #(conj % t 1) datoms)}}}))


(deftest a-frame-the-fold-rejects-poisons-and-the-reopen-refuses
  (let [[frames i] (enrolled)
        a (auth frames)
        duplicate (fn [_]
                    {::authority/facts
                     [{:yin.k/custody :yin.k/admitted
                       :yin.k/target i
                       :yin.k/op-id (op 0)
                       :yin.k/intent (ledger/intent i :v)
                       :yin.k/value :v
                       :yin.k/result ledger/ok-result}]
                     ::authority/reply {:yin.k/status :committed}})]
    (is (= :committed (status (seam/commit-effect! a i (op 0) :v))))
    (is (= {:yin.k/status :suspended :yin.k/reason :uninstalled}
           (authority/transition! a duplicate))
        "the frame persisted but its fold defected: nothing is installed")
    (is (= {:yin.k/status :suspended :yin.k/reason :poisoned}
           (seam/commit-effect! a i (op 1) :w))
        "later decisions are refused")
    (is (nil? (authority/projection a)))
    (let [r (open frames)]
      (is (= :refused (status r)) "the standing frame refuses the reopen")
      (is (= :duplicate-op-id (:yin.k/defect r))))))


(deftest reopen-refuses-a-gap-and-a-t-mismatch
  (let [arb "arb"
        header (frame {:dao.stream.journal/header {:version 1
                                                   :identity arb}})
        enroll (fn [e t]
                 (ledger/facts->datoms
                   e [{:yin.k/custody :yin.k/enrolled
                       :yin.k/target (ledger/target-identity arb t)
                       :yin.k/effect-kinds #{:yin.k/append}}]))
        refused (fn [fs]
                  (let [frames (atom fs)
                        r (open frames)]
                    (is (= fs @frames) "the refusal writes nothing")
                    (when (= :refused (status r)) (:yin.k/defect r))))]
    (is (= :open (status (open (atom [header (entry 0 0 (enroll 16 0))])))))
    (is (= :t-mismatch (refused [header (entry 0 1 (enroll 16 1))])))
    (is (= :t-mismatch (refused [header (entry 0 0 (enroll 16 0))
                                 (entry 1 2 (enroll 17 2))]))
        "a gap in t")
    (is (= :position-gap (refused [header (entry 0 0 (enroll 16 0))
                                   (entry 2 1 (enroll 17 1))]))
        "a gap in journal positions")
    (is (= :malformed-record
           (refused [header (frame {:dao.stream.journal/position 0
                                    :dao.stream.journal/value :x})])))))


(deftest a-transition-past-a-bound-commits-nothing
  (let [frames (atom [])
        opts {::authority/max-exact 18}
        a (auth frames nil opts)
        i (:yin.k/target (authority/enroll! a))]
    (is (= :committed (status (seam/commit-effect! a i (op 0) :v))))
    (is (= :committed (status (seam/commit-effect! a i (op 1) :v))))
    (let [before @frames]
      (is (= {:yin.k/status :suspended :yin.k/reason :bound}
             (seam/commit-effect! a i (op 2) :v))
          "entity id 19 would pass the bound of 18")
      (is (= {:yin.k/status :suspended :yin.k/reason :bound}
             (authority/enroll! a)))
      (is (= before @frames) "nothing was committed"))
    (is (some? (authority/projection a)) "a bound does not poison")
    (is (= {:yin.k/status :replayed :yin.k/result ledger/ok-result}
           (seam/commit-effect! a i (op 1) :v))
        "a decision that commits nothing still answers")
    (is (= :suspended
           (status (seam/commit-effect! (auth frames nil opts) i (op 2) :v)))
        "the bound holds after reopen")))


(deftest the-target-reader-contract
  (let [[frames i] (enrolled)
        a (auth frames)
        r (authority/target-reader a i)
        origin (cur r :dao.stream/oldest)]
    (testing "identity and surfaces"
      (is (= {:dao.stream/type authority/target-type :dao.stream/identity i}
             (:dao.stream/descriptor (stream/descriptor r))))
      (is (= i (:dao.stream/identity (stream/descriptor r))))
      (is (stream/reader? r))
      (is (not (stream/writer? r)) "admission is the only append")
      (is (not (stream/closable? r)))
      (is (nil? (authority/target-reader a "nowhere"))))
    (testing "blocked at the tail"
      (is (= {:values [] :terminal :dao.stream/blocked} (read-all r origin))))
    (seam/commit-effect! a i (op 0) :x)
    (seam/commit-effect! a i (op 1) :x)
    (let [after-first (:dao.stream/cursor (stream/next r origin))
          other (:yin.k/target (authority/enroll! a))]
      (is (= {:values [:x :x] :terminal :dao.stream/blocked}
             (read-all r origin))
          "equal effects keep separate positions")
      (is (= :dao.stream/cursor-mismatch
             (outcome (stream/next (authority/target-reader a other)
                                   origin))))
      (is (= :dao.stream/invalid-cursor
             (outcome (stream/next r (assoc origin
                                            ::authority/position 3)))))
      (authority/close! a)
      (testing "cursors survive reopen and the canonical codec"
        (let [a2 (auth frames)
              r2 (authority/target-reader a2 i)]
          (is (= origin (cur r2 :dao.stream/oldest)))
          (is (= {:values [:x] :terminal :dao.stream/blocked}
                 (read-all r2 (cbor/decode (cbor/encode after-first)))))
          (testing "end after an authority-authored close"
            (is (= {:yin.k/status :committed :dao.space/t 4}
                   (authority/close-target! a2 i)))
            (is (= {:yin.k/status :replayed} (authority/close-target! a2 i)))
            (is (= {:yin.k/status :committed
                    :yin.k/result ledger/closed-result
                    :dao.space/t 5}
                   (seam/commit-effect! a2 i (op 2) :y))
                "a later admission records the closed result")
            (is (= {:values [:x :x] :terminal :dao.stream/end}
                   (read-all r2 origin)))
            (is (= {:yin.k/status :replayed
                    :yin.k/result ledger/closed-result}
                   (seam/commit-effect! (auth frames) i (op 2) :y)))
            (is (= {:values [:x :x] :terminal :dao.stream/end}
                   (read-all (authority/target-reader (auth frames) i)
                             origin)))))))))


(defn- concurrently
  "Run each thunk, on its own JVM thread where threads share memory,
   and answer their results.  Node and Dart run one isolate
   synchronously, so there the calls are serial by construction."
  [thunks]
  #?(:cljd (mapv #(%) thunks)
     :clj (mapv deref (mapv #(future (%)) thunks))
     :cljs (mapv #(%) thunks)))


(deftest two-decisions-cannot-both-act-on-one-projection
  (let [frames (atom [])
        a (auth frames)
        enrolls (concurrently (repeat 8 #(authority/enroll! a)))
        i (:yin.k/target (first enrolls))
        commits (concurrently
                  (repeat 8 #(seam/commit-effect! a i (op 0) :v)))]
    (is (= (set (range 8)) (set (map :dao.space/t enrolls)))
        "each decision saw the projection the previous one installed")
    (is (= 8 (count (set (map :yin.k/target enrolls)))))
    (is (= {:committed 1 :replayed 7} (frequencies (map status commits))))
    (is (= [ledger/ok-result] (admitted frames (op 0))))
    (is (= (authority/projection a)
           (authority/projection (auth frames))))))


;; The cross-host fixture: one small ledger over a fixed journal
;; identity, pinned by the BLAKE3 digest of each frame's bytes.  The same
;; decisions must produce the same frames on every host.
(def ^:private fixture-digests
  ["e11e48605e86a84d7810c69d134b134f33e39d09cab1d786f0d777612a5b8d27"
   "aed4685e5647b3adc178107f4c8b2dff86100dbf5cea081bfdace52f912da006"
   "a010ecdd49f93412ae107b4e40758755ef8cb99e55ccb047d4f49955b8883451"
   "d9ffc6daf293850ba00362e4828718885fe6f377e9e745d32d661df20e4a7d0b"
   "33568faabd26ac36fe440357766dfff93de2bd9487c604df53ee3b4d82160768"
   "5dd7f1e5abbbc8269081b3e39cf28cb53a89143ad7856c78d052dddfd6a3def9"])


(deftest the-same-decisions-make-the-same-bytes
  (let [frames (atom [(cbor/encode {:dao.stream.journal/header
                                    {:version 1 :identity "c3-fixture"}})])
        a (auth frames)
        i (:yin.k/target (authority/enroll! a))]
    (seam/commit-effect! a i (op 0) "payload")
    (seam/commit-effect! a i (op 1) [1 -2 {:k #{:s}}])
    (authority/close-target! a i)
    (seam/commit-effect! a i (op 2) nil)
    (is (= fixture-digests (mapv #(jing/digest-bytes :blake3 %) @frames)))))
