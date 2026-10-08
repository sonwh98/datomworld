(ns yin.vm.ucf.authority.inherited-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.admission :as admission]
            [yin.vm.ucf.authority.completion :as completion]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.checkpoint-fixtures :as fx]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.ledger :as ledger]))


(def ^:private arb "arb-c9")

(def ^:private duration {:s 30})

(def ^:private ok ledger/ok-result)


;; R completes into S1, and S1 into S2; Y halts; X is never seen; S1b is
;; another successor body naming R, never R's recorded one.
(def ^:private r "0c000000-0000-4000-8000-000000000001")

(def ^:private s1 "0c000000-0000-4000-8000-000000000002")

(def ^:private s2 "0c000000-0000-4000-8000-000000000003")

(def ^:private x "0c000000-0000-4000-8000-000000000004")

(def ^:private y "0c000000-0000-4000-8000-000000000005")

(def ^:private s1b "0c000000-0000-4000-8000-000000000006")


(defn- fresh-frames
  []
  (atom [(cbor/encode {:dao.stream.journal/header
                       {:version 1 :identity arb}})]))


(defn- backend
  ([frames] (backend frames nil))
  ([frames cut] (journal/memory-backend frames cut)))


(defn- auth
  ([frames] (auth frames nil))
  ([frames cut]
   (::authority/authority (authority/open! (backend frames cut)))))


(defn- op
  [o n]
  {:yin.k/occurrence o :yin.k/seq n})


(defn- origin
  [o l]
  {:yin.k/occurrence o :dao.lease/lease l :yin.k/emitter "holder-a"})


(def ^:private arbitration
  {:dao.stream/identity arb
   :dao.stream/descriptor {:dao.stream/type :dao.stream/journal}})


(defn- park
  "A first park of occurrence `o`: no origin, no carried id."
  [o]
  (assoc (get fx/fixtures "first-park")
         :yin.k/occurrence o
         :yin.k/arbitration arbitration))


(defn- frame-of
  "The first frame of `body` whose pending reason is `reason`."
  [body reason]
  (some (fn [f] (when (= reason (get-in f [:yin.k/pending :yin.k/reason])) f))
        (:yin.k/frames body)))


(defn- write-pending
  "The pending path of the first write frame of the body at `in`, `[]`
   for the root itself."
  ([body] (write-pending body []))
  ([body in]
   (some (fn [[i f]]
           (when (= :put (get-in f [:yin.k/pending :yin.k/reason]))
             (into (vec in) [:yin.k/frames i :yin.k/pending])))
         (map-indexed vector (:yin.k/frames body)))))


(defn- carrying
  "A blocked root of occurrence `o` with origin `org` and counter 3,
   whose frames carry the ids `ids`: the successor base's own write
   frame once per id, a clone under each.  The regenerated successor
   base (D9) lifts one write, so the clones carry what its hand-built
   three-variant body used to."
  [o org ids]
  (let [base (assoc (get fx/fixtures "successor")
                    :yin.k/occurrence o
                    :yin.k/origin org
                    :yin.k/arbitration arbitration)
        write (frame-of base :put)]
    (assoc base
           :yin.k/frames
           (mapv #(assoc-in write [:yin.k/pending :yin.k/op-id] %) ids))))


(def ^:private foo [:yin.k/installs 'host.mod :yin.k/child])


(defn- with-installs
  "A blocked root of occurrence `o` with origin `org` and counter 6,
   carrying R's id 0 itself, id 4 in its install child and id 5 in the
   child's own child.  The regenerated installs base (D9) lifts a root
   waiting on one install whose child holds one write, so the root
   takes the child's write frame as its own id-0 clone, the child's
   write carries id 4, and a clone of the child under 'bar carries
   id 5 as the grandchild."
  [o org]
  (let [base (assoc (get fx/fixtures "installs")
                    :yin.k/occurrence o
                    :yin.k/origin org
                    :yin.k/arbitration arbitration)
        child (get-in base foo)
        entry (-> (get-in base [:yin.k/installs 'host.mod])
                  (dissoc :yin.k/child))
        install (frame-of base :install)
        write (frame-of child :put)
        under (fn [id] (assoc-in write [:yin.k/pending :yin.k/op-id] id))]
    (-> base
        (update :yin.k/frames conj (under (op r 0)))
        (update-in foo
                   (fn [c]
                     (-> c
                         (assoc-in (conj (write-pending c) :yin.k/op-id)
                                   (op r 4))
                         (update :yin.k/frames conj
                                 (assoc-in install
                                           [:yin.k/pending]
                                           {:yin.k/reason :install
                                            :yin.k/name 'bar}))
                         (assoc :yin.k/installs
                                {'bar (assoc entry
                                             :yin.k/child
                                             (assoc child
                                                    :yin.k/frames
                                                    [(under (op r 5))]))})))))))


(def ^:private succ-1
  "R's recorded successor: S1 carrying R's id 0, Y's id 0 and X's id 0."
  (carrying s1 (origin r "lease-1") [(op r 0) (op y 0) (op x 0)]))


(def ^:private succ-2
  "S1's successor: S2 carrying R's id 0, S1's id 0 and R's id 2."
  (carrying s2 (origin s1 "lease-2") [(op r 0) (op s1 0) (op r 2)]))


(defn- enc
  [b]
  (let [bs (cbor/encode b)]
    {:address (fx/segment-address bs) :bytes bs}))


(defn- offer!
  [a store b]
  (let [{:keys [address bytes]} (enc b)]
    (:yin.k/status (grant/offer! a store address bytes "carrier"))))


(defn- grant!
  [a o l h]
  (:dao.stream/outcome
    (stream/append! (grant/writer a)
                    (lease/grant l (custody/subject o) h duration
                                 {:dao.lease/proposal (str "p-" l)}))))


(defn- report!
  [a author o l b]
  (let [{:keys [address bytes]} (enc b)]
    (:yin.k/status
      (completion/report! a author (completion/resumed o l address) bytes))))


(defn- lapse!
  [a l cause]
  (:dao.stream/outcome
    (stream/append! (grant/writer a) (lease/lapsed l cause))))


(defn- ring
  []
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key 64})))


(defn- diagnostics
  [h]
  (loop [c (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)) vs []]
    (let [n (stream/next h c)]
      (if (= :dao.stream/ok (:dao.stream/outcome n))
        (recur (:dao.stream/cursor n) (conj vs (:dao.stream/value n)))
        vs))))


(defn- records
  [frames]
  (mapv #(:dao.stream.journal/value (cbor/decode %)) (rest @frames)))


(defn- record-facts
  [rec]
  (let [ds (get-in rec [:dao.space/transaction :datoms])]
    (vec (for [e (distinct (map first ds))]
           (into {} (keep (fn [[e' a v]] (when (= e e') [a v]))) ds)))))


(defn- facts
  [frames k]
  (filter #(= k (or (:yin.k/custody %) (:dao.lease/status %)))
          (mapcat record-facts (records frames))))


(defn- world
  "Y completed by a halted result under lease-y; R completed into `succ`
   (an occurrence S1 body) under lease-1; S1 offered and granted as
   lease-2 to holder-b at epoch 0; one enrolled target.  Every step's
   answer is kept under :steps."
  ([] (world succ-1))
  ([succ]
   (let [frames (fresh-frames)
         a (auth frames)
         store (mem/create-content-mem)
         halted (assoc (get fx/fixtures "halted-root")
                       :yin.k/origin (origin y "lease-y"))
         steps [(offer! a store (park y))
                (grant! a y "lease-y" "holder-y")
                (report! a "holder-y" y "lease-y" halted)
                (lapse! a "lease-y" :release)
                (offer! a store (park r))
                (grant! a r "lease-1" "holder-a")
                (report! a "holder-a" r "lease-1" succ)
                (lapse! a "lease-1" :release)
                (offer! a store succ)
                (grant! a s1 "lease-2" "holder-b")]]
     {:frames frames
      :a a
      :store store
      :i (:yin.k/target (authority/enroll! a))
      :diag (ring)
      :steps steps})))


(defn- extended
  "World `w` with S1 completed into S2 under lease-2, and S2 offered and
   granted as lease-3 to holder-c at epoch 0."
  [{:keys [a store] :as w}]
  (update w :steps into
          [(report! a "holder-b" s1 "lease-2" succ-2)
           (lapse! a "lease-2" :release)
           (offer! a store succ-2)
           (grant! a s2 "lease-3" "holder-c")]))


(def ^:private world-steps
  [:committed :dao.stream/ok :committed :dao.stream/ok
   :committed :dao.stream/ok :committed :dao.stream/ok
   :committed :dao.stream/ok])


(defn- env
  ([id v] (env "lease-2" 0 id v))
  ([l e id v]
   {:yin.k/envelope :yin.k/fenced-v1
    :yin.k/incarnation l
    :yin.k/epoch e
    :yin.k/op-id id
    :yin.k/value v}))


(defn- admit!
  ([w e] (admit! w "holder-b" e))
  ([w author e] (admit! w (:store w) author e))
  ([{:keys [a i diag]} store author e]
   (admission/admit! a store i author e diag)))


(defn- outcome
  ([k id] (outcome k "lease-2" id))
  ([k l id]
   {:yin.k/admission k :yin.k/op-id id :yin.k/incarnation l}))


(defn- committed
  ([id] (committed "lease-2" id))
  ([l id] (assoc (outcome :committed l id) :yin.k/effect-result ok)))


(defn- replayed
  [l id]
  (assoc (outcome :replayed l id) :yin.k/effect-result ok))


(defn- suspended
  ([id] (suspended "lease-2" id))
  ([l id]
   (assoc (outcome :suspended l id)
          :yin.k/arbitration {:dao.stream/identity arb})))


(defn- foreign?
  "True when r is the :foreign-op-id diagnostic, one appended, and no
   admission outcome."
  [r]
  (and (= :foreign-op-id (get-in r [::admission/diagnostic :yin.k/defect]))
       (= :dao.stream/ok (get-in r [::admission/appended :dao.stream/outcome]))
       (not (contains? r :yin.k/admission))))


(defn- unchanged?
  "True when `thunk` answers and leaves the frames, the projection and
   the diagnostics of world `w` as they were; answers the answer."
  [w thunk]
  (let [before [@(:frames w) (authority/projection (:a w))
                (diagnostics (:diag w))]
        result (thunk)]
    (when (= before [@(:frames w) (authority/projection (:a w))
                     (diagnostics (:diag w))])
      result)))


;; =============================================================================
;; The world
;; =============================================================================

(deftest the-world-is-a-chain
  (let [w (world)
        p (authority/projection (:a w))]
    (is (= world-steps (:steps w)))
    (is (= {:dao.lease/lease "lease-1" :yin.k/successor s1}
           (get-in p [:occurrences r :yin.k/closed])))
    (is (contains? (get-in p [:occurrences y :yin.k/closed]) :yin.k/result)
        "Y ends in a terminal edge")
    (is (= [:committed :dao.stream/ok :committed :dao.stream/ok]
           (subvec (:steps (extended w)) 10)))))


;; =============================================================================
;; The scope check (UCF 7.7.8 step 4): one fixture per case
;; =============================================================================

(deftest a-current-id-needs-no-checkpoint
  (let [w (world)]
    (is (= (committed (op s1 0))
           (admit! w (mem/create-content-mem) "holder-b" (env (op s1 0) :v)))
        "an empty store: no inherited-membership lookup")))


(deftest an-inherited-member-of-an-ancestor-is-admitted
  (let [{:keys [frames i] :as w} (world)
        n (count @frames)]
    (is (= (committed (op r 0)) (admit! w (env (op r 0) :v))))
    (is (= (inc n) (count @frames)) "one transaction")
    (is (= [{:yin.k/custody :yin.k/admitted
             :yin.k/target i
             :yin.k/op-id (op r 0)
             :yin.k/intent (ledger/intent i :v)
             :yin.k/value :v
             :yin.k/result ok}
            {:yin.k/custody :yin.k/fenced
             :yin.k/op-id (op r 0)
             :yin.k/incarnation "lease-2"
             :yin.k/epoch 0}]
           (record-facts (last (records frames)))))
    (is (= (replayed "lease-2" (op r 0)) (admit! w (env (op r 0) :v))))))


(deftest an-inherited-id-the-checkpoint-never-carried-is-refused
  (let [w (world)]
    (is (foreign? (admit! w (env (op r 1) :v))))
    (is (empty? (facts (:frames w) :yin.k/admitted)))))


(deftest a-member-of-a-non-ancestor-is-refused
  (testing "an occurrence the ledger never saw"
    (let [w (world)]
      (is (foreign? (admit! w (env (op x 0) :v))))
      (is (empty? (facts (:frames w) :yin.k/admitted)))))
  (testing "an occurrence that ended in a terminal edge"
    (let [w (world)]
      (is (foreign? (admit! w (env (op y 0) :v))))
      (is (empty? (facts (:frames w) :yin.k/admitted))))))


(deftest ancestry-runs-through-a-chain-of-two
  (let [w (extended (world))]
    (is (= (committed "lease-3" (op r 0))
           (admit! w "holder-c" (env "lease-3" 0 (op r 0) :v)))
        "R is S2's grandparent")
    (is (= (committed "lease-3" (op s1 0))
           (admit! w "holder-c" (env "lease-3" 0 (op s1 0) :v)))
        "S1 its parent")
    (is (foreign? (admit! w "holder-c" (env "lease-3" 0 (op r 1) :v)))
        "membership is S2's checkpoint's, not S1's")
    (is (foreign? (admit! w "holder-c" (env "lease-3" 0 (op y 0) :v))))))


(deftest an-unreadable-checkpoint-suspends-and-changes-nothing
  (let [{:keys [address]} (enc succ-1)]
    (doseq [[what store]
            [["its body missing" (mem/create-content-mem)]
             ["bytes that do not verify"
              (mem/create-content-mem {address (cbor/encode (park x))})]
             ["a closed store"
              (doto (mem/create-content-mem) ((fn [s] ((:close-fn s)))))]
             ["no store" nil]]]
      (testing what
        (let [w (world)]
          (is (= (suspended (op r 0))
                 (unchanged? w #(admit! w store "holder-b"
                                        (env (op r 0) :v))))
              "tenure, quarantine, dedup and diagnostics unchanged")
          (is (= (committed (op s1 0))
                 (admit! w store "holder-b" (env (op s1 0) :v)))
              "a current id still commits")
          (is (= (committed (op r 0)) (admit! w (env (op r 0) :v)))
              "the readable checkpoint admits the same id"))))))


(deftest stale-wins-over-an-unreadable-checkpoint
  (let [w (world)
        empty-store (mem/create-content-mem)]
    (lapse! (:a w) "lease-2" :silence)
    (is (= (assoc (outcome :stale (op r 0)) :yin.k/observed-epoch 1)
           (admit! w empty-store "holder-b" (env (op r 0) :v))))
    (grant! (:a w) s1 "lease-4" "holder-d")
    (is (= (assoc (outcome :stale (op r 0))
                  :yin.k/observed-epoch 1
                  :yin.k/observed-lease "lease-4")
           (admit! w empty-store "holder-b" (env (op r 0) :v))))
    (is (= (suspended "lease-4" (op r 0))
           (admit! w empty-store "holder-d" (env "lease-4" 1 (op r 0) :v)))
        "the current holder is suspended")))


;; =============================================================================
;; 7.11.1 clause 4: authoritative membership and ancestry
;; =============================================================================

(deftest clause-4-membership-and-ancestry
  (testing "an ancestor id the granted checkpoint never carried is refused"
    (is (foreign? (admit! (world) (env (op r 2) :v)))))
  (testing "a carried one is admitted"
    (is (= (committed (op r 0)) (admit! (world) (env (op r 0) :v)))))
  (testing "a child's carried id counts, the child's child's too"
    (let [w (world (with-installs s1 (origin r "lease-1")))]
      (is (= world-steps (:steps w)))
      (is (= (committed (op r 4)) (admit! w (env (op r 4) :v))))
      (is (= (committed (op r 5)) (admit! w (env (op r 5) :v))))
      (is (= (committed (op r 0)) (admit! w (env (op r 0) :v))))
      (is (foreign? (admit! w (env (op r 1) :v))))))
  (testing "an orphan successor gives no ancestry"
    (let [{:keys [a store]} (world)
          orphan (carrying s1b (origin r "lease-1") [(op r 0) (op r 1)
                                                     (op r 2)])]
      (is (= :refused (offer! a store orphan)) "never offered")
      (is (= :dao.stream/invalid-value (grant! a s1b "lease-9" "holder-z"))
          "never granted")
      (is (false? (admission/ancestor? (authority/projection a) r s1b)))))
  (testing "a reported successor whose silence lapse was a plain reclaim"
    (let [frames (fresh-frames)
          a (auth frames)
          store (mem/create-content-mem)]
      (offer! a store (park r))
      (grant! a r "lease-1" "holder-a")
      (report! a "holder-a" r "lease-1" succ-1)
      (lapse! a "lease-1" :silence)
      (is (= :refused (offer! a store succ-1)))
      (is (false? (admission/ancestor? (authority/projection a) r s1))))))


(deftest ancestry-is-derived-from-the-completion-edges
  (let [p (authority/projection (:a (extended (world))))]
    (is (true? (admission/ancestor? p s1 s2)))
    (is (true? (admission/ancestor? p r s2)))
    (is (true? (admission/ancestor? p r s1)))
    (is (false? (admission/ancestor? p s2 s2)) "not its own ancestor")
    (is (false? (admission/ancestor? p s2 r)) "edges run one way")
    (is (false? (admission/ancestor? p y s1)) "no ancestry through a result")
    (is (false? (admission/ancestor? p x s1)))
    (is (false? (admission/ancestor? p r x)) "an unknown descendant")
    (testing "a terminal edge is no edge, whatever it names"
      (is (false? (admission/ancestor?
                    (assoc-in p [:occurrences y :yin.k/closed :yin.k/result]
                              s1)
                    y s1))))))


(deftest membership-is-the-baseline-carried-ops
  (let [p (authority/projection (:a (world (with-installs
                                             s1 (origin r "lease-1")))))
        b (get-in p [:occurrences s1 :yin.k/baseline])]
    (is (every? #(admission/member? b (op r %)) [0 4 5]))
    (is (not-any? #(admission/member? b (op r %)) [1 2 3 6]))
    (is (not (admission/member? b (op s1 0))))))


;; =============================================================================
;; 7.11.1 clause 8: the closed-occurrence and closed-ancestor fixtures
;; =============================================================================

(deftest clause-8-closed-occurrence-and-closed-ancestor
  (let [w (world)]
    (testing "a closed occurrence: :stale names it closed"
      (is (= {:yin.k/admission :stale
              :yin.k/op-id (op r 0)
              :yin.k/incarnation "lease-1"
              :yin.k/observed-epoch 1
              :yin.k/closed true}
             (admit! w "holder-a" (env "lease-1" 0 (op r 0) :v))))
      (is (= {:yin.k/admission :stale
              :yin.k/op-id (op x 0)
              :yin.k/incarnation "lease-y"
              :yin.k/observed-epoch 1
              :yin.k/closed true}
             (admit! w "holder-y" (env "lease-y" 0 (op x 0) :v)))
          "a halted occurrence is closed too"))
    (testing "an open occurrence's :stale carries no :yin.k/closed"
      (is (not (contains? (admit! w (env "lease-2" 5 (op s1 0) :v))
                          :yin.k/closed))))
    (testing "an inherited id from a closed ancestor"
      (is (= (committed (op r 0)) (admit! w (env (op r 0) :v)))))))


;; =============================================================================
;; Crash cuts and reopen
;; =============================================================================

(deftest a-cut-inherited-admission-commits-zero-or-one-times
  (doseq [[cut persisted retry] [[:before-frame 0 :committed]
                                 [:after-frame-before-visible 1 :replayed]
                                 [:torn-frame 0 :committed]]]
    (testing (name cut)
      (let [{:keys [frames a] :as w} (world)
            _ (authority/close! a)
            w1 (assoc w :a (auth frames cut))]
        (is (= (suspended (op r 0)) (admit! w1 (env (op r 0) :v))))
        (is (= (suspended (op r 0)) (admit! w1 (env (op r 0) :v)))
            "the poisoned authority admits nothing")
        (let [w2 (assoc w :a (auth frames))]
          (is (= persisted (count (facts frames :yin.k/admitted))))
          (is (= retry (:yin.k/admission (admit! w2 (env (op r 0) :v))))
              "the retry finds the recorded result or commits it")
          (is (= 1 (count (facts frames :yin.k/admitted))))
          (is (= 1 (count (facts frames :yin.k/fenced)))))))))


(deftest an-inherited-id-replays-after-reopen-and-regrant
  (let [{:keys [frames a] :as w} (world)]
    (is (= (committed (op r 0)) (admit! w (env (op r 0) :v))))
    (authority/close! a)
    (let [reopened (grant/reopen! (backend frames) nil)
          w2 (assoc w :a (::authority/authority reopened))]
      (is (= ["lease-2"] (:yin.k/reclaimed-leases reopened)))
      (is (= :stale (:yin.k/admission (admit! w2 (env (op r 0) :v))))
          "the old holder is stale after the reopen's reclaim")
      (is (= :dao.stream/ok (grant! (:a w2) s1 "lease-4" "holder-d")))
      (let [n (count @frames)]
        (is (= (replayed "lease-4" (op r 0))
               (admit! w2 "holder-d" (env "lease-4" 1 (op r 0) :v)))
            "the regranted holder replays the retained id")
        (is (= n (count @frames)) "and writes nothing")))))


;; =============================================================================
;; One dedup namespace, and quarantine
;; =============================================================================

(deftest a-successor-meets-the-one-namespace
  (let [{:keys [frames a] :as w} (world)]
    (is (= (committed (op r 0)) (admit! w (env (op r 0) :v))))
    (extended w)
    (testing "the successor re-presenting the retained id replays it"
      (let [n (count @frames)]
        (is (= (replayed "lease-3" (op r 0))
               (admit! w "holder-c" (env "lease-3" 0 (op r 0) :v))))
        (is (= n (count @frames)))))
    (testing "another intent conflicts and quarantines the current occurrence"
      (is (= {:yin.k/admission :intent-conflict
              :yin.k/op-id (op r 0)
              :yin.k/incarnation "lease-3"
              :yin.k/recorded-intent (ledger/intent (:i w) :v)
              :yin.k/observed-intent (ledger/intent (:i w) :w)}
             (admit! w "holder-c" (env "lease-3" 0 (op r 0) :w))))
      (is (= [{:yin.k/custody :yin.k/quarantined
               :yin.k/occurrence s2
               :yin.k/op-id (op r 0)}]
             (vec (facts frames :yin.k/quarantined))))
      (is (= 1 (count (facts frames :yin.k/admitted)))))
    (testing "the quarantine answers before scope"
      (is (= (suspended "lease-3" (op r 2))
             (admit! w "holder-c" (env "lease-3" 0 (op r 2) :z))))
      (is (= (suspended "lease-3" (op x 0))
             (admit! w "holder-c" (env "lease-3" 0 (op x 0) :z))))
      (is (= (suspended "lease-3" (op r 2))
             (admit! w (mem/create-content-mem) "holder-c"
                     (env "lease-3" 0 (op r 2) :z))))
      (is (= 1 (count (facts frames :yin.k/quarantined)))))
    (is (true? (get-in (authority/projection a)
                       [:occurrences s2 :yin.k/quarantined])))
    (is (nil? (get-in (authority/projection a)
                      [:occurrences s1 :yin.k/quarantined])))))


(deftest an-inherited-id-to-a-second-target-conflicts
  (let [{:keys [frames a store diag] :as w} (world)
        j (:yin.k/target (authority/enroll! a))]
    (is (= (committed (op r 0)) (admit! w (env (op r 0) :v))))
    (is (= :intent-conflict
           (:yin.k/admission
             (admission/admit! a store j "holder-b" (env (op r 0) :v) diag))))
    (is (= 1 (count (facts frames :yin.k/quarantined))))))


;; =============================================================================
;; Snapshot variants (UCF 7.7.8): the baseline includes the children
;; =============================================================================

(deftest a-variant-changing-a-child-intent-is-refused
  (let [succ (with-installs s1 (origin r "lease-1"))
        {:keys [frames a store]} (world succ)
        before @frames
        grand (conj foo :yin.k/installs 'bar :yin.k/child)]
    (doseq [[what variant]
            [["a child's payload"
              (assoc-in succ (conj (write-pending (get-in succ foo) foo)
                                   :yin.k/value)
                        :z)]
             ["a grandchild's payload"
              (assoc-in succ (conj (write-pending (get-in succ grand) grand)
                                   :yin.k/value)
                        :z)]
             ["the root counter" (assoc succ :yin.k/next-op-seq 7)]]]
      (testing what
        (let [{:keys [address bytes]} (enc variant)]
          (is (= {:yin.k/status :refused :yin.k/reason :variant-conflict
                  :yin.k/occurrence s1}
                 (grant/offer! a store address bytes "carrier")))
          (is (nil? ((:get-bytes-fn store) address nil))))))
    (is (= before @frames))))


(deftest any-accepted-variant-is-the-checkpoint
  (let [{:keys [a] :as w} (world)
        variant (assoc succ-1 :yin.k/id-counter 9)
        store (mem/create-content-mem)
        {:keys [address bytes]} (enc variant)]
    (is (= :committed (offer! a store variant)) "an equal-baseline variant")
    (is (= #{address (:address (enc succ-1))}
           (get-in (authority/projection a)
                   [:occurrences s1 :yin.k/variants])))
    (is (cbor/bytes= bytes ((:get-bytes-fn store) address nil)))
    (is (= (committed (op r 0)) (admit! w store "holder-b" (env (op r 0) :v)))
        "the store holds only the second variant")))
