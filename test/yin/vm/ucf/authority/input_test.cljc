(ns yin.vm.ucf.authority.input-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.authority.input :as input]
            [yin.vm.ucf.authority.seam :as seam]
            [yin.vm.ucf.checkpoint-fixtures :as fx]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.ledger :as ledger]))


(def ^:private arb "arb-c10")

(def ^:private occ fx/occurrence)

(def ^:private duration {:s 30})


(defn- fresh-frames
  []
  (atom [(cbor/encode {:dao.stream.journal/header
                       {:version 1 :identity arb}})]))


(defn- backend
  ([frames] (backend frames nil))
  ([frames cut] (journal/memory-backend frames cut)))


(defn- auth
  ([frames] (auth frames nil nil))
  ([frames cut] (auth frames cut nil))
  ([frames cut opts]
   (::authority/authority (authority/open! (backend frames cut) opts))))


(defn- body
  [n]
  (assoc (get fx/fixtures n)
         :yin.k/arbitration {:dao.stream/identity arb
                             :dao.stream/descriptor
                             {:dao.stream/type :dao.stream/journal}}))


(defn- offer!
  [a]
  (let [bs (cbor/encode (body "successor"))]
    (grant/offer! a (mem/create-content-mem) (fx/segment-address bs) bs
                  "carrier")))


(defn- records
  [frames]
  (mapv #(:dao.stream.journal/value (cbor/decode %)) (rest @frames)))


(defn- record-facts
  [r]
  (let [ds (get-in r [:dao.space/transaction :datoms])]
    (for [e (distinct (map first ds))]
      (into {} (keep (fn [[e' a v]] (when (= e e') [a v]))) ds))))


(defn- facts
  [frames k]
  (filter #(= k (or (:yin.k/custody %) (:dao.lease/status %)))
          (mapcat record-facts (records frames))))


(defn- grant!
  [a l h pid]
  (stream/append! (grant/writer a)
                  (lease/grant l (custody/subject occ) h duration
                               {:dao.lease/proposal pid})))


(defn- reclaim!
  [a l]
  (stream/append! (grant/writer a) (lease/lapsed l :policy)))


(defn- granted
  "Fresh frames with the successor fixture offered and lease-1 granted to
   holder-a at epoch 0, and the authority."
  ([] (granted nil))
  ([opts]
   (let [frames (fresh-frames)
         a (auth frames nil opts)]
     (offer! a)
     (grant! a "lease-1" "holder-a" "p-1")
     [frames a])))


(def ^:private s-read {:yin.k/kind :yin.k/read :yin.k/name "s"})

(def ^:private s-ffi {:yin.k/kind :yin.k/ffi-result :yin.k/name "call-1"})

(def ^:private s-link {:yin.k/kind :yin.k/link-result :yin.k/name "link-1"})


(defn- read-ok
  [v pos]
  {:dao.stream/outcome :dao.stream/ok
   :dao.stream/value v
   :dao.stream/cursor {:position pos}})


(defn- req
  ([k observed] (req k s-read observed))
  ([k source observed] (req "lease-1" 0 k source observed))
  ([l e k source observed]
   (input/request occ l e k source observed)))


(defn- status
  [r]
  (:yin.k/status r))


(defn- p
  [a]
  (authority/projection a))


;; =============================================================================
;; Acknowledgment
;; =============================================================================

(deftest an-input-is-recorded-by-its-commit
  (let [[frames a] (granted)
        before (count @frames)
        r (input/record-input! a "holder-a" (req 0 (read-ok 7 1)))]
    (is (= :recorded (status r)))
    (is (= {:yin.k/occurrence occ :yin.k/input-seq 0}
           (select-keys r [:yin.k/occurrence :yin.k/input-seq])))
    (is (integer? (:dao.space/t r)) "it names the committing transaction")
    (is (= (inc before) (count @frames)) "one transaction")
    (is (= [{:yin.k/custody :yin.k/input
             :yin.k/occurrence occ
             :dao.lease/lease "lease-1"
             :yin.k/input-seq 0
             :yin.k/source s-read
             :yin.k/observed (read-ok 7 1)}]
           (vec (record-facts (last (records frames)))))
        "the input record is its own fact")))


(deftest equal-content-at-a-known-k-replays-and-other-content-is-refused
  (let [[frames a] (granted)
        _ (input/record-input! a "holder-a" (req 0 (read-ok 7 1)))
        before @frames]
    (is (= {:yin.k/status :replayed :yin.k/occurrence occ
            :yin.k/input-seq 0}
           (input/record-input! a "holder-a" (req 0 (read-ok 7 1)))))
    (let [r (input/record-input! a "holder-a" (req 0 (read-ok 8 1)))]
      (is (= :refused (status r)))
      (is (= :input-conflict (:yin.k/reason r)))
      (is (= {:yin.k/source s-read :yin.k/observed (read-ok 7 1)}
             (:yin.k/recorded-input r)))
      (is (= {:yin.k/source s-read :yin.k/observed (read-ok 8 1)}
             (:yin.k/observed-input r))))
    (is (= :input-conflict
           (:yin.k/reason
             (input/record-input! a "holder-a" (req 0 s-ffi (read-ok 7 1)))))
        "the same outcome from another source is another input")
    (is (= :input-conflict
           (:yin.k/reason
             (input/record-input! a "holder-a"
                                  (req 0 (read-ok (cbor/float64 7) 1)))))
        "content is compared kind-strictly: a float 7 is not 7")
    (is (= before @frames) "nothing is committed")
    (is (= :recorded
           (status (input/record-input! a "holder-a"
                                        (req 1 (read-ok (cbor/float64 0) 2))))))
    (is (= :input-conflict
           (:yin.k/reason
             (input/record-input!
               a "holder-a" (req 1 (read-ok (cbor/float64 (* -1.0 0.0)) 2)))))
        "and by canonical bytes: -0.0 is not 0.0, though host = says so")
    (is (some? (p a)) "and nothing is poisoned")))


(deftest the-sequence-is-dense-from-zero
  (let [[frames a] (granted)]
    (let [before @frames
          r (input/record-input! a "holder-a" (req 1 (read-ok 1 1)))]
      (is (= {:yin.k/status :refused :yin.k/reason :input-gap
              :yin.k/input-seq 1 :yin.k/next-input-seq 0}
             r)
          "a gap is refused")
      (is (= before @frames)))
    (is (= :recorded (status (input/record-input! a "holder-a"
                                                  (req 0 (read-ok 1 1))))))
    (is (= :recorded (status (input/record-input! a "holder-a"
                                                  (req 1 (read-ok 2 2))))))
    (is (= :input-gap (:yin.k/reason (input/record-input!
                                       a "holder-a" (req 3 (read-ok 3 3))))))
    (is (= [0 1] (mapv :yin.k/input-seq (facts frames :yin.k/input))))))


(deftest a-malformed-request-commits-nothing
  (let [[frames a] (granted)
        before @frames]
    (doseq [[why r] [["k -1" (req -1 (read-ok 1 1))]
                     ["k a float" (req (cbor/float64 0) (read-ok 1 1))]
                     ["k 2^52" (req (inc ledger/max-exact) (read-ok 1 1))]
                     ["epoch a float"
                      (req "lease-1" (cbor/float64 0) 0 s-read 1)]
                     ["no source kind" (req 0 {:yin.k/name "s"} 1)]
                     ["unknown source kind"
                      (req 0 {:yin.k/kind :yin.k/write :yin.k/name "s"} 1)]
                     ["no observed" (req 0 nil)]
                     ["a non-portable observed" (req 0 (atom 1))]
                     ["occurrence not a uuid"
                      (assoc (req 0 1) :yin.k/occurrence "O")]
                     ["an extra key" (assoc (req 0 1) :yin.k/extra 1)]]]
      (testing why
        (is (= {:yin.k/status :refused :yin.k/reason :malformed-request}
               (input/record-input! a "holder-a" r)))))
    (is (= {:yin.k/status :refused :yin.k/reason :malformed-request}
           (input/record-input! a "holder-a" [:not :a :map])))
    (is (= before @frames))
    (is (some? (p a)) "a non-portable value does not poison the authority")))


;; =============================================================================
;; Tenure: binding, author, epoch, active lease
;; =============================================================================

(deftest tenure-is-checked-against-the-grant-and-epoch
  (let [[frames a] (granted)
        before @frames]
    (is (= {:yin.k/status :refused :yin.k/reason :unbound-lease}
           (input/record-input! a "holder-a" (req "lease-9" 0 0 s-read 1)))
        "a lease never granted")
    (is (= {:yin.k/status :refused :yin.k/reason :unknown-occurrence}
           (input/record-input!
             a "holder-a"
             (input/request "00000000-0000-4000-8000-000000000000"
                            "lease-1" 0 0 s-read 1))))
    (is (= {:yin.k/status :refused :yin.k/reason :wrong-author}
           (input/record-input! a "holder-b" (req 0 1)))
        "attributed to someone other than the bound holder")
    (is (= {:yin.k/status :stale :yin.k/observed-epoch 0
            :yin.k/observed-lease "lease-1"}
           (input/record-input! a "holder-a" (req "lease-1" 1 0 s-read 1)))
        "a wrong epoch is stale")
    (is (= before @frames))))


(deftest a-reclaimed-lease-is-stale-even-for-a-recorded-k
  (let [[frames a] (granted)
        _ (input/record-input! a "holder-a" (req 0 (read-ok 1 1)))
        _ (reclaim! a "lease-1")
        before @frames]
    (is (= {:yin.k/status :stale :yin.k/observed-epoch 1}
           (input/record-input! a "holder-a" (req 0 (read-ok 1 1))))
        "stale wins over the record; no lease is active")
    (is (= {:yin.k/status :stale :yin.k/observed-epoch 1}
           (input/record-input! a "holder-a" (req "lease-1" 1 1 s-read 2)))
        "the current epoch with a lapsed lease is still stale")
    (grant! a "lease-2" "holder-b" "p-2")
    (is (= {:yin.k/status :stale :yin.k/observed-epoch 1
            :yin.k/observed-lease "lease-2"}
           (input/record-input! a "holder-a" (req 1 (read-ok 2 2)))))
    (is (= 1 (count (facts frames :yin.k/input)))
        "the old holder recorded nothing after its reclaim")
    (is (= (inc (count before)) (count @frames)) "only the grant committed")))


(deftest an-exhausted-occurrence-suspends-recording
  (let [opts {::authority/max-epoch 0}
        [frames a] (granted opts)
        _ (reclaim! a "lease-1")
        before @frames]
    (is (true? (get-in (p a) [:occurrences occ :yin.k/exhausted])))
    (is (= {:yin.k/status :suspended :yin.k/reason :exhausted}
           (input/record-input! a "holder-a" (req 0 1)))
        "before the tenure check")
    (is (= before @frames))))


(deftest a-poisoned-authority-records-nothing-and-serves-no-prefix
  (let [[frames a0] (granted)
        _ (authority/close! a0)
        a (auth frames :before-frame)
        r (input/record-input! a "holder-a" (req 0 1))]
    (is (= :suspended (status r)) "not :recorded: the commit is uncertain")
    (is (nil? (p a)))
    (is (= {:yin.k/status :suspended :yin.k/reason :poisoned}
           (input/record-input! a "holder-a" (req 0 1))))
    (is (nil? (input/frontier (p a) "lease-1")))
    (is (= :suspended (status (input/inputs (p a) "lease-1")))
        "no evidence is not an empty prefix")))


;; =============================================================================
;; Bounds
;; =============================================================================

(deftest k-at-the-bound-refuses-without-commit
  (let [[frames a] (granted)
        opts {::input/max-seq 2}]
    (is (= :recorded (status (input/record-input! a "holder-a"
                                                  (req 0 1) opts))))
    (is (= :recorded (status (input/record-input! a "holder-a"
                                                  (req 1 2) opts))))
    (let [before @frames]
      (is (= {:yin.k/status :suspended :yin.k/reason :bound}
             (input/record-input! a "holder-a" (req 2 3) opts))
          "k at the lowered bound would pass it")
      (is (= before @frames))
      (is (= {:yin.k/status :replayed :yin.k/occurrence occ
              :yin.k/input-seq 1}
             (input/record-input! a "holder-a" (req 1 2) opts))
          "a recorded k still replays at the bound")
      (is (some? (p a)) "nothing is poisoned"))
    (is (= input/max-seq ledger/max-exact) "the default bound is 2^52-1")
    (is (= :input-gap
           (:yin.k/reason
             (input/record-input! a "holder-a" (req ledger/max-exact 3))))
        "at the real bound, 2^52-1 is a gap")))


;; =============================================================================
;; The fold
;; =============================================================================

(defn- fold-input
  "Fold one transaction holding `fact` onto projection `p0`."
  [p0 fact]
  (ledger/fold-record
    p0
    {:dao.space/transaction
     {:t (:next-t p0)
      :datoms (mapv #(conj % (:next-t p0) 1)
                    (ledger/fact-datoms (:next-e p0) fact))}}))


(defn- input-fact
  [l k]
  {:yin.k/custody :yin.k/input
   :yin.k/occurrence occ
   :dao.lease/lease l
   :yin.k/input-seq k
   :yin.k/source s-read
   :yin.k/observed k})


(deftest the-fold-refuses-what-the-ledger-cannot-hold
  (let [[_ a] (granted)
        p0 (p a)
        p1 (fold-input p0 (input-fact "lease-1" 0))]
    (is (nil? (::ledger/defect p1)))
    (is (= [{:yin.k/source s-read :yin.k/observed 0
             :dao.lease/lease "lease-1" :dao.space/t (:next-t p0)}]
           (get-in p1 [:occurrences occ :yin.k/inputs])))
    (doseq [[why fact d]
            [["a gap" (input-fact "lease-1" 2) :non-dense-input]
             ["a repeat" (input-fact "lease-1" 0) :non-dense-input]
             ["another lease" (input-fact "lease-9" 1) :inactive-lease]
             ["k a float" (input-fact "lease-1" (cbor/float64 1))
              :malformed-fact]
             ["k 2^52" (input-fact "lease-1" (inc ledger/max-exact))
              :malformed-fact]
             ["no source" (dissoc (input-fact "lease-1" 1) :yin.k/source)
              :malformed-fact]
             ["no observed" (dissoc (input-fact "lease-1" 1) :yin.k/observed)
              :malformed-fact]
             ["an unknown occurrence"
              (assoc (input-fact "lease-1" 1)
                     :yin.k/occurrence
                     "00000000-0000-4000-8000-000000000000")
              :unknown-occurrence]]]
      (testing why
        (is (= {::ledger/defect d} (fold-input p1 fact)))))))


(deftest the-fold-refuses-an-input-after-the-lease-lapsed
  (let [[_ a] (granted)
        _ (reclaim! a "lease-1")]
    (is (= {::ledger/defect :inactive-lease}
           (fold-input (p a) (input-fact "lease-1" 0))))))


;; =============================================================================
;; Frontier and the replay prefix
;; =============================================================================

(deftest the-frontier-is-the-count-at-the-grant
  (let [[_ a] (granted)]
    (is (= 0 (input/frontier (p a) "lease-1")))
    (is (= {:yin.k/occurrence occ :dao.lease/lease "lease-1"
            :yin.k/frontier 0 :yin.k/inputs []}
           (input/inputs (p a) "lease-1"))
        "no input recorded yet is a definite empty prefix")
    (input/record-input! a "holder-a" (req 0 (read-ok 1 1)))
    (input/record-input! a "holder-a" (req 1 s-ffi {:ok 2}))
    (is (= 0 (input/frontier (p a) "lease-1"))
        "a holder's own records do not move its frontier")
    (is (= [] (:yin.k/inputs (input/inputs (p a) "lease-1"))))
    (reclaim! a "lease-1")
    (grant! a "lease-2" "holder-b" "p-2")
    (is (= 2 (input/frontier (p a) "lease-2")))
    (is (= {:yin.k/occurrence occ :dao.lease/lease "lease-2"
            :yin.k/frontier 2
            :yin.k/inputs [{:yin.k/input-seq 0 :yin.k/source s-read
                            :yin.k/observed (read-ok 1 1)}
                           {:yin.k/input-seq 1 :yin.k/source s-ffi
                            :yin.k/observed {:ok 2}}]}
           (input/inputs (p a) "lease-2")))
    (is (= :recorded (status (input/record-input!
                               a "holder-b"
                               (req "lease-2" 1 2 s-read (read-ok 3 2))))))
    (is (= 2 (input/frontier (p a) "lease-2"))
        "the prefix ends at the frontier, not at the tail")
    (is (= 2 (count (:yin.k/inputs (input/inputs (p a) "lease-2")))))
    (is (= 0 (input/frontier (p a) "lease-1")) "history keeps each frontier")
    (reclaim! a "lease-2")
    (grant! a "lease-3" "holder-c" "p-3")
    (is (= 3 (input/frontier (p a) "lease-3")))
    (is (nil? (input/frontier (p a) "lease-9")))
    (is (= {:yin.k/status :refused :yin.k/reason :unbound-lease}
           (input/inputs (p a) "lease-9")))))


(deftest replay-checks-each-source-and-fails-closed
  (let [prefix {:yin.k/occurrence occ :dao.lease/lease "lease-2"
                :yin.k/frontier 2
                :yin.k/inputs [{:yin.k/input-seq 0 :yin.k/source s-read
                                :yin.k/observed (read-ok 1 1)}
                               {:yin.k/input-seq 1 :yin.k/source s-ffi
                                :yin.k/observed {:ok 2}}]}]
    (is (= {:yin.k/status :replayed :yin.k/input-seq 0
            :yin.k/observed (read-ok 1 1)}
           (input/replay-input prefix 0 s-read)))
    (is (= {:yin.k/status :refused :yin.k/reason :source-mismatch
            :yin.k/input-seq 1 :yin.k/recorded-source s-ffi
            :yin.k/observed-source s-link}
           (input/replay-input prefix 1 s-link)))
    (is (= {:yin.k/status :live :yin.k/input-seq 2}
           (input/replay-input prefix 2 s-read))
        "at the frontier the prefix has ended")
    (is (= {:yin.k/status :live :yin.k/input-seq 0}
           (input/replay-input (assoc prefix :yin.k/frontier 0
                                      :yin.k/inputs [])
                               0 s-read))
        "an empty prefix is live from 0")
    (is (= {:yin.k/status :suspended :yin.k/reason :unavailable}
           (input/replay-input (input/inputs nil "lease-2") 0 s-read))
        "missing evidence fails closed, never live")))


;; =============================================================================
;; A scripted holder: plain functions, no VM
;; =============================================================================

(def ^:private script
  "The task's inputs in the order the driver delivers them: a root read,
   an FFI result for an install child, a second root read, and the
   child's link result.  Children draw from the root's sequence."
  [s-read s-ffi (assoc s-read :yin.k/name "s2") s-link])


(defn- intent-of
  "The effect the task emits: a pure function of what it observed."
  [observed]
  {:sum (reduce + (map #(or (:dao.stream/value %) (:ok %) 0) observed))
   :gaps (count (filter #(= :dao.stream/gap (:dao.stream/outcome %))
                        observed))})


(defn- run-holder
  "Run the scripted task as `holder` under lease `l` at epoch `e`.  With
   `replay?` it takes the inputs prefix and replays it; otherwise it
   observes everything live.  `world` maps each source to what a live
   observation answers.  Each input is delivered only after :recorded or
   :replayed (the rule for D).  Then it emits its one effect through the
   seam under op id seq 0.  Answers {:delivered [...] :effect r} or
   {:failed r :delivered [...]} at the first input it cannot deliver."
  [a target {:keys [holder l e world replay? sources]}]
  (let [prefix (if replay?
                 (input/inputs (p a) l)
                 {:yin.k/occurrence occ :dao.lease/lease l
                  :yin.k/frontier 0 :yin.k/inputs []})]
    (loop [k 0 delivered []]
      (if (= k (count sources))
        {:delivered delivered
         :effect (seam/commit-effect! a target
                                      {:yin.k/occurrence occ :yin.k/seq 0}
                                      (intent-of delivered))}
        (let [s (nth sources k)
              r (input/replay-input prefix k s)
              r (if (= :live (:yin.k/status r))
                  (let [v (get world s)
                        ack (input/record-input!
                              a holder (input/request occ l e k s v))]
                    (if (contains? #{:recorded :replayed} (:yin.k/status ack))
                      {:yin.k/status :replayed :yin.k/observed v}
                      ack))
                  r)]
          (if (= :replayed (:yin.k/status r))
            (recur (inc k) (conj delivered (:yin.k/observed r)))
            {:failed r :delivered delivered}))))))


(def ^:private world-before
  {s-read (read-ok 10 1)
   s-ffi {:ok 20}
   (assoc s-read :yin.k/name "s2") (read-ok 30 1)
   s-link {:ok 40}})


(def ^:private world-evicted
  "After the crash the kept-cursor values are evicted: the reads answer
   gap with their successor cursors."
  (assoc world-before
         s-read {:dao.stream/outcome :dao.stream/gap
                 :dao.stream/cursor {:position 5}}
         (assoc s-read :yin.k/name "s2") {:dao.stream/outcome :dao.stream/gap
                                          :dao.stream/cursor {:position 9}}))


(defn- crashed-and-regranted
  "Holder-a runs the script and commits its effect, then crashes; the
   authority reclaims lease-1 and grants lease-2 to holder-b at epoch 1.
   With `record?` false holder-a never recorded its inputs."
  [record?]
  (let [[frames a] (granted)
        target (:yin.k/target (authority/enroll! a))
        first-run (if record?
                    (run-holder a target {:holder "holder-a" :l "lease-1"
                                          :e 0 :world world-before
                                          :replay? false :sources script})
                    {:effect (seam/commit-effect!
                               a target {:yin.k/occurrence occ :yin.k/seq 0}
                               (intent-of (map world-before script)))})]
    (reclaim! a "lease-1")
    (grant! a "lease-2" "holder-b" "p-2")
    {:frames frames :a a :target target :first first-run}))


(deftest recovery-with-inputs-reproduces-intent-and-result
  (let [{:keys [frames a target first]} (crashed-and-regranted true)
        _ (is (= :committed (status (:effect first))))
        admitted (count (facts frames :yin.k/admitted))
        inputs (count (facts frames :yin.k/input))
        again (run-holder a target {:holder "holder-b" :l "lease-2" :e 1
                                    :world world-evicted :replay? true
                                    :sources script})]
    (is (= (:delivered first) (:delivered again))
        "the regranted holder sees what the first one saw, not the gaps")
    (is (= {:yin.k/status :replayed
            :yin.k/result (:yin.k/result (:effect first))}
           (:effect again))
        "the same op id and intent replays the recorded result")
    (is (= admitted (count (facts frames :yin.k/admitted))) "no second commit")
    (is (= inputs (count (facts frames :yin.k/input)))
        "replay records nothing")))


(deftest recovery-without-replay-fails-closed
  (let [{:keys [frames a target]} (crashed-and-regranted true)
        before @frames
        r (run-holder a target {:holder "holder-b" :l "lease-2" :e 1
                                :world world-evicted :replay? false
                                :sources script})]
    (is (= :input-conflict (get-in r [:failed :yin.k/reason]))
        "live observation of k 0 diverges from its record")
    (is (= [] (:delivered r)) "nothing was delivered to the task")
    (is (nil? (:effect r)) "and no effect was attempted")
    (is (= before @frames))))


(deftest without-recorded-inputs-the-divergent-intent-is-refused
  (let [{:keys [frames a target]} (crashed-and-regranted false)
        _ (is (empty? (facts frames :yin.k/input)))
        r (run-holder a target {:holder "holder-b" :l "lease-2" :e 1
                                :world world-evicted :replay? true
                                :sources script})]
    (is (= 2 (count (filter #(= :dao.stream/gap (:dao.stream/outcome %))
                            (:delivered r))))
        "with nothing to replay the holder observes the gaps live")
    (is (= {:yin.k/status :intent-conflict} (:effect r))
        "the same id with divergent intent commits nothing")
    (is (= 1 (count (facts frames :yin.k/admitted))))))


(deftest inputs-keep-the-delivered-order-across-child-work
  (let [{:keys [frames a]} (crashed-and-regranted true)]
    (is (= (mapv (fn [k s] [k s]) (range) script)
           (mapv (juxt :yin.k/input-seq :yin.k/source)
                 (facts frames :yin.k/input)))
        "root and child inputs share one dense sequence, in delivery order")
    (let [swapped [s-read (nth script 2) s-ffi s-link]
          r (run-holder a nil {:holder "holder-b" :l "lease-2" :e 1
                               :world world-evicted :replay? true
                               :sources swapped})]
      (is (= {:yin.k/status :refused :yin.k/reason :source-mismatch
              :yin.k/input-seq 1 :yin.k/recorded-source s-ffi
              :yin.k/observed-source (nth script 2)}
             (:failed r))
          "a replay that asks in another order fails closed")
      (is (= 1 (count (:delivered r)))))))


(deftest gap-successors-replay-verbatim-after-reopen
  (let [[frames a] (granted)
        gap {:dao.stream/outcome :dao.stream/gap
             :dao.stream/cursor {:position 5 :epoch-hint 1.5}}]
    (input/record-input! a "holder-a" (req 0 gap))
    (authority/close! a)
    (let [a2 (::authority/authority (grant/reopen! (backend frames) nil))]
      (grant! a2 "lease-2" "holder-b" "p-2")
      (is (cbor/content=
            {:yin.k/status :replayed :yin.k/input-seq 0 :yin.k/observed gap}
            (input/replay-input (input/inputs (p a2) "lease-2") 0 s-read))
          "the gap and its successor cursor survive the canonical bytes"))))


;; =============================================================================
;; Crash cuts
;; =============================================================================

(deftest a-cut-record-commits-zero-or-one-times
  (doseq [[cut persisted] [[:before-frame 0]
                           [:after-frame-before-visible 1]
                           [:torn-frame 0]]]
    (testing (name cut)
      (let [[frames a0] (granted)
            _ (authority/close! a0)
            a (auth frames cut)
            r (input/record-input! a "holder-a" (req 0 (read-ok 1 1)))]
        (is (= {:yin.k/status :suspended :yin.k/reason :uncertain-append} r)
            "never :recorded before the durable commit")
        (let [a2 (auth frames)]
          (is (= persisted (count (facts frames :yin.k/input))))
          (is (= (if (zero? persisted) :recorded :replayed)
                 (status (input/record-input! a2 "holder-a"
                                              (req 0 (read-ok 1 1)))))
              "a retry after reopen converges")
          (is (= 1 (count (facts frames :yin.k/input)))))))))


(deftest a-cut-record-sets-the-next-frontier
  (doseq [[cut persisted] [[:before-frame 0]
                           [:after-frame-before-visible 1]
                           [:torn-frame 0]]]
    (testing (name cut)
      (let [[frames a0] (granted)
            _ (authority/close! a0)
            a (auth frames cut)
            _ (input/record-input! a "holder-a" (req 0 (read-ok 1 1)))
            a2 (::authority/authority (grant/reopen! (backend frames) nil))]
        (is (= :stale (status (input/record-input! a2 "holder-a"
                                                   (req 0 (read-ok 1 1)))))
            "reopen reclaimed the old tenure")
        (grant! a2 "lease-2" "holder-b" "p-2")
        (is (= persisted (input/frontier (p a2) "lease-2")))))))
