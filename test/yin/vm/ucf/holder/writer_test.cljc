(ns yin.vm.ucf.holder.writer-test
  "D11: the fenced writer (UCF 7.7.5, 7.7.8; r3 1.3 and 1.11).  Every
machine is parked by the real engine; every admission is the real
authority's, reached through the real front over the memory
substrate.  The composition the writer owes is supplied as functions:
the inbound appender and the outcome readers attribute their streams
the way the composition's resolver would."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.module :as module]
            [yin.vm.test-utils :as tu]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.admission :as admission]
            [yin.vm.ucf.authority.front :as front]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.checkpoint-fixtures :as fx]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.ledger :as ledger]
            [yin.vm.ucf.holder.writer :as writer]
            [yin.vm.ucf.lift-support :as s]))


(def ^:private arb "arb-d11")
(def ^:private occ "0c000000-0000-4000-8000-000000000001")

(def ^:private duration {:s 30})

(def ^:private ok-result ledger/ok-result)


(defn- throws?
  [thunk]
  (try (thunk) false
       (catch #?(:clj Exception :cljs js/Error :cljd Object) _ true)))


;; =============================================================================
;; The world: a real authority, front, judge and streams
;; =============================================================================
(defn- ring

  [n]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key n})))


(defn- log
  []
  (:dao.stream/handle
    (memory-log/create! {:dao.stream/type :dao.stream/memory-log})))


(defn- stream-id
  [h]
  (:dao.stream/identity (stream/descriptor h)))


(defn- oldest
  [h]
  (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)))


(defn- read-all
  [h]
  (loop [c (oldest h) vs []]
    (let [r (stream/next h c)]
      (if (and (= :dao.stream/ok (:dao.stream/outcome r)) (< (count vs) 1000))
        (recur (:dao.stream/cursor r) (conj vs (:dao.stream/value r)))
        vs))))


(defn- records
  [frames]
  (mapv #(:dao.stream.journal/value (cbor/decode %)) (rest @frames)))


(defn- facts-of
  [record]
  (let [ds (get-in record [:dao.space/transaction :datoms])]
    (vec (for [e (distinct (map first ds))]
           (into {} (keep (fn [[e' a v]] (when (= e e') [a v]))) ds)))))


(defn- facts
  [frames k]
  (filter #(= k (or (:yin.k/custody %) (:dao.lease/status %)))
          (mapcat facts-of (records frames))))


(defn- park
  "The first-park fixture body as occurrence `o` with counter `n`, over
this world's arbitration."
  [o n]
  (assoc (get fx/fixtures "first-park")
         :yin.k/occurrence o
         :yin.k/next-op-seq n
         :yin.k/arbitration {:dao.stream/identity arb
                             :dao.stream/descriptor
                             {:dao.stream/type :dao.stream/journal}}))


(defn- world
  "An authority over fresh frames with one offered occurrence and one
enrolled target, and a holder's front over rings, attributing the
inbound to holder-a.  The occurrence is granted `lease-1` to holder-a
at epoch 0 directly (the judge does not know this grant; tests that
release through the judge use `judge-granted-world`), unless
`:no-direct-grant true`.  With `:full-inbound true` the holder's
inbound ring is a one-slot stream already holding a value, so appends
answer `full`."
  [& {:as opts}]
  (let [frames (atom [(cbor/encode {:dao.stream.journal/header
                                    {:version 1 :identity arb}})])
        a (::authority/authority (authority/open! (journal/memory-backend
                                                    frames nil)
                                                  nil))
        bs (cbor/encode (park occ 0))
        store (mem/create-content-mem)
        in (if (:full-inbound opts)
             (let [full (s/one-slot-stream "in-full")]
               (stream/append! full :junk)
               full)
             (ring 8))
        out (ring 8)
        diag (ring 8)
        medium (log)
        _ (grant/offer! a store (fx/segment-address bs) bs "carrier")
        _ (when-not (:no-direct-grant opts)
            (stream/append! (grant/writer a)
                            (lease/grant "lease-1" (custody/subject occ)
                                         "holder-a" duration
                                         {:dao.lease/proposal "p-1"})))
        i (:yin.k/target (authority/enroll! a))]
    {:frames frames
     :a a
     :store store
     :in in
     :out out
     :diag diag
     :medium medium
     :target i
     :reader (authority/target-reader a i)
     :outcomes (admission/outcome-reader a)
     :front (atom (front/front {:authority a
                                :inbound in
                                :reply out
                                :diagnostics diag
                                :resolver (fn [identity _]
                                            (when (= identity (stream-id in))
                                              "holder-a"))
                                :store store
                                :lease-media {"holder-a" medium}}))}))


(defn- appender
  "The composition-supplied appender of the holder's inbound stream."
  [w]
  (fn [request] (stream/append! (:in w) request)))


(defn- counting
  "[appender calls] over `append!`, counting the requests sent."
  [append!]
  (let [n (atom 0)]
    [(fn [r] (swap! n inc) (append! r)) n]))


(defn- answer!
  "Step the world's front past up to `n` requests."
  [w n]
  (swap! (:front w) front/step n)
  w)


(defn- reader-over
  "A composition-supplied outcome reader over handle `h`: the next
[author record] until the tail, attributing the stream to `author`."
  [h author]
  (let [c (atom (oldest h))]
    (fn []
      (let [r (stream/next h @c)]
        (when (= :dao.stream/ok (:dao.stream/outcome r))
          (reset! c (:dao.stream/cursor r))
          [author (:dao.stream/value r)])))))


(defn- reply-reader
  [w]
  (reader-over (:out w) arb))


(defn- outcome-reader
  [w]
  (reader-over (:outcomes w) arb))


(defn- judge
  "A lease judge over the world's authority, reading its medium as
holder-a's lease facts."
  [w]
  (let [ticks (log)]
    (stream/append! ticks (lease/tick {:s 1}))
    (-> (lease/initial-judge
          (merge (grant/judge-config (:a w) duration)
                 {:resolver (fn [source _] source) :self arb}))
        (lease/wire-tick ticks (oldest ticks) :ticks)
        (lease/wire-facts (:medium w) (oldest (:medium w)) "holder-a"))))


(defn- judge-granted-world
  "A world whose lease the judge itself granted: the holder proposes
through the front, the judge drains the proposal and grants, so the
judge's ledger knows the lease a later release must lapse.  Answers
[world lease judge]."
  []
  (let [w (world :no-direct-grant true)
        _ (stream/append! (:in w)
                          {:yin.k/request :yin.k/proposal
                           :yin.k/request-id "r-propose"
                           :dao.lease/proposal "p-a"
                           :yin.k/occurrence occ})
        _ (answer! w 4)
        j (grant/step! (:a w) (judge w))
        l (-> (facts (:frames w) :dao.lease/accepted)
              first :dao.lease/lease)]
    (assert (some? l) "the judge granted nothing")
    [w l j]))


;; =============================================================================
;; Machines under custody
;; =============================================================================
(defn- parked-put

  "A real machine, gated running, parked on a put of `value` to
`handle`; the one write the task retains."
  [handle value]
  (let [m0 (s/load-ast (s/new-machine)
                       {:type :stream/put, :target (s/v 'w), :val (s/lit value)})
        [ref m1] (engine/attach-resource m0 handle)]
    (vm/run (assoc m1 :store {'w ref} :yin.k/gate :running))))


(defn- custody-for
  ([target] (custody-for target {}))
  ([target over]
   (merge {:yin.k/occurrence occ
           :dao.lease/lease "lease-1"
           :dao.lease/holder "holder-a"
           :yin.k/epoch 0
           :yin.k/arbitration {:dao.stream/identity arb}
           :yin.k/next-op-seq 0
           :protection {target :enrolled}
           :input {:next 0, :prefix {:yin.k/frontier 0, :yin.k/inputs []}}
           :tenure {:bound 100}}
          over)))


(defn- enrolled-writer
  "A gated machine parked on one put to the world's enrolled target,
under `over` the custody fixture."
  [w over]
  (assoc (parked-put (:reader w) "v")
         :yin.k/custody (custody-for (:target w) over)))


(defn- install-entry
  "The real 'host.mod install entry of a parked installer, its child
replaced by `child` -- the D9 'installs' fixture's own construction."
  [child]
  (assoc (get (:installs (s/parked-installer)) 'host.mod) :vm child))


(defn- id-retained?
  "The machine after `r` still waits on its one write, id intact, ready
queue empty."
  [r]
  (and (= :put (:reason (first (:wait-set (:machine r)))))
       (some? (:op-id (first (:wait-set (:machine r)))))
       (= 1 (count (:wait-set (:machine r))))
       (empty? (:ready-queue (:machine r)))))


;; =============================================================================
;; Assign before send; one increment; children draw from the root
;; =============================================================================
(deftest assign-precedes-send-and-increments-once-test

  (let [w (world)
        m (enrolled-writer w {})
        [append! n] (counting (appender w))
        r (writer/emit m append!)
        entry (first (:wait-set (:machine r)))]
    (testing "the id is assigned and the request sent with it"
      (is (= [{:yin.k/occurrence occ, :yin.k/seq 0}]
             (keep :op-id (:wait-set (:machine r))))
          "one write, one id, naming the custody occurrence")
      (is (= 1 (get-in (:machine r) [:yin.k/custody :yin.k/next-op-seq]))
          "one increment")
      (is (= 1 @n) "exactly one request")
      (is (= {:yin.k/request :yin.k/admit
              :yin.k/request-id [:yin.k/admit "lease-1"
                                 {:yin.k/occurrence occ, :yin.k/seq 0}]
              :yin.k/target (:target w)
              :yin.k/fenced-envelope
              {:yin.k/envelope :yin.k/fenced-v1
               :yin.k/incarnation "lease-1"
               :yin.k/epoch 0
               :yin.k/op-id {:yin.k/occurrence occ, :yin.k/seq 0}
               :yin.k/value "v"}}
             (:request (first (:appended r))))
          "the five-key envelope in the front's closed request shape"))
    (testing "a second step resends, never reassigns"
      (let [r2 (writer/emit (:machine r) append!)
            entry2 (first (:wait-set (:machine r2)))]
        (is (= (:op-id entry) (:op-id entry2)) "the id is retained")
        (is (= 1 (get-in (:machine r2) [:yin.k/custody :yin.k/next-op-seq]))
            "no second increment")
        (is (= 2 @n))
        (is (= (:request (first (:appended r)))
               (:request (first (:appended r2))))
            "the same request again: correlation only, never dedup")))))


(deftest an-exhausted-counter-assigns-and-sends-nothing-test
  (let [w (world)
        m (enrolled-writer w {:yin.k/next-op-seq custody/max-exact})
        [append! n] (counting (appender w))
        r (writer/emit m append!)]
    (is (= [nil] (mapv :op-id (:wait-set (:machine r))))
        "nothing is assigned at 2^52-1")
    (is (= custody/max-exact
           (get-in (:machine r) [:yin.k/custody :yin.k/next-op-seq]))
        "the counter does not wrap")
    (is (empty? (:appended r)) "nothing is sent")
    (is (zero? @n))
    (is (= :put (:reason (first (:wait-set (:machine r)))))
        "the write stays an undischarged wait")))


(deftest children-draw-from-the-roots-counter-test
  (let [w (world)
        child (parked-put (:reader w) "child-v")
        m (assoc (parked-put (:reader w) "v")
                 :installs {'host.mod (install-entry child)}
                 :yin.k/custody (custody-for (:target w)))
        r (writer/emit m (appender w))
        child-entry (first (get-in (:machine r)
                                   [:installs 'host.mod :vm :wait-set]))]
    (is (= [{:yin.k/occurrence occ, :yin.k/seq 0}
            {:yin.k/occurrence occ, :yin.k/seq 1}]
           (concat (keep :op-id (:wait-set (:machine r)))
                   (keep :op-id (get-in (:machine r)
                                        [:installs 'host.mod :vm :wait-set]))))
        "the root draws first, the child draws after it, one counter")
    (is (= {:yin.k/occurrence occ, :yin.k/seq 1} (:op-id child-entry))
        "the child's id comes from the root's one counter")
    (is (= 2 (get-in (:machine r) [:yin.k/custody :yin.k/next-op-seq])))
    (is (= ["v" "child-v"]
           (mapv #(get-in % [:request :yin.k/fenced-envelope :yin.k/value])
                 (:appended r)))
        "the root's request first, then the child's, both at the target")))


;; =============================================================================
;; The op-id obligation: a real writer run equals the D9 helper
;; =============================================================================
(deftest a-real-writers-entries-equal-the-d9-fixtures-helper-test

  (let [base (s/parked-writer)
        target "w"
        gated (assoc base :yin.k/gate :running)
        custody (custody-for target {:yin.k/occurrence s/predecessor})
        run (writer/emit (assoc gated :yin.k/custody custody)
                         (fn [_r] {:dao.stream/outcome :dao.stream/transport-error}))
        helper (s/with-op-id gated :put (s/op-id 0))
        header (s/header 1 s/origin #{"s0"})]
    (is (= (first (:wait-set helper))
           (first (:wait-set (:machine run))))
        "the writer sets exactly the id the helper set, nothing else")
    (is (= (s/op-id 0) (:op-id (first (:wait-set helper)))))
    (testing "and both lift to the same canonical bytes"
      (let [a (s/lift helper header)
            b (s/lift (:machine run) header)]
        (is (= :ok (:status a)) (pr-str (dissoc a :record :machine)))
        (is (= :ok (:status b)) (pr-str (dissoc b :record :machine)))
        (is (= (:address a) (:address b))
            "one canonical address: no fixture regeneration is owed")))))


;; =============================================================================
;; Retention across the four cases
;; =============================================================================
(deftest the-id-is-retained-through-a-full-inbound-stream-test

  (let [w (world :full-inbound true)
        m (enrolled-writer w {})
        r (writer/emit m (appender w))]
    (is (= :dao.stream/full
           (:dao.stream/outcome (:append (first (:appended r))))))
    (is (id-retained? r) "the id stays through the full path")
    (is (= 1 (get-in (:machine r) [:yin.k/custody :yin.k/next-op-seq])))
    (is (= [:junk] (read-all (:in w))))
    (testing "the retry after the stream accepts it resends the same request"
      (let [r2 (writer/emit (:machine r) (appender w))]
        (is (= :dao.stream/ok
               (:dao.stream/outcome (:append (first (:appended r2))))))
        (is (= 1 (count (:appended r2))))
        (is (= (get-in (first (:appended r)) [:request :yin.k/request-id])
               (get-in (first (:appended r2)) [:request :yin.k/request-id]))
            "the same id, the same envelope, the same request")))))


(defn- dropping-appender
  "An inbound appender cut before the request: nothing is appended, and
the answer to the writer is the unknown-effect refusal."
  []
  (fn [_r] {:dao.stream/outcome :dao.stream/transport-error}))


(defn- cutting-appender
  "An inbound appender cut after the commit: the request is appended and
the front answers it -- the admission may already have committed --
but the answer to the writer is the unknown-effect refusal."
  [w]
  (fn [request]
    (let [r (stream/append! (:in w) request)]
      (answer! w 4)
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        {:dao.stream/outcome :dao.stream/transport-error}
        r))))


(deftest the-id-is-retained-through-an-unknown-effect-append-test
  (let [w (world)
        m (enrolled-writer w {})
        r (writer/emit m (dropping-appender))]
    (is (= :dao.stream/transport-error
           (:dao.stream/outcome (:append (first (:appended r))))))
    (is (id-retained? r) "an unknown effect retains the id")
    (is (empty? (read-all (:in w))) "the request never landed")
    (testing "the retry goes through the fenced boundary and commits once"
      (let [r2 (writer/emit (:machine r) (appender w))]
        (is (= :dao.stream/ok
               (:dao.stream/outcome (:append (first (:appended r2))))))
        (answer! w 4)
        (let [d (writer/drain (:machine r2) (reply-reader w))]
          (is (= [:committed] (mapv :admission (:discharged d))))
          (is (= ["v"] (read-all (:reader w))) "the effect committed once")
          (is (= 1 (count (facts (:frames w) :yin.k/admitted))))
          (is (empty? (:wait-set (:machine d))) "the entry is discharged"))))))


(deftest the-id-is-retained-while-no-outcome-has-arrived-test
  (let [w (world)
        m (enrolled-writer w {})
        r (writer/emit m (appender w))]
    (is (= :dao.stream/ok
           (:dao.stream/outcome (:append (first (:appended r))))))
    (is (id-retained? r) "an acceptance with no outcome yet retains the id")
    (is (empty? (read-all (:out w))) "the front has not answered")
    (testing "the outcome, once read, discharges"
      (answer! w 4)
      (let [d (writer/drain (:machine r) (reply-reader w))]
        (is (= [:committed] (mapv :admission (:discharged d))))))))


(deftest the-id-is-retained-through-a-suspended-outcome-test
  (let [w (world)
        m (enrolled-writer w {})
        r (writer/emit m (appender w))
        _ (authority/close! (:a w))
        _ (answer! w 4)
        d (writer/drain (:machine r) (reply-reader w))]
    (is (= [:suspended] (:retained d)))
    (is (id-retained? d) "a :suspended outcome retains the id")
    (is (= 1 (count (:wait-set (:machine d)))))))


;; =============================================================================
;; Discharge: the five match fields, and the projected outcome
;; =============================================================================
(deftest a-reply-failing-one-match-field-discharges-nothing-test

  (let [w (world)
        m (enrolled-writer w {})
        r (writer/emit m (appender w))
        _ (answer! w 4)
        reply (first (read-all (:out w)))
        id (get-in reply [:yin.k/request-id 2])
        control (writer/discharge (:machine r) arb reply)]
    (testing "the control reply discharges"
      (is (= [:committed] (mapv :admission [(:discharged control)])))
      (is (empty? (:wait-set (:machine control))))
      (is (= [:ok] (mapv :status (:ready-queue (:machine control))))))
    (doseq [[name author record]
            [["another author" "mallory" reply]
             ["another request kind" arb (assoc reply :yin.k/reply :yin.k/offer)]
             ["another request id" arb
              (assoc reply :yin.k/request-id [:yin.k/admit "lease-2" id])]
             ["another op id" arb
              (assoc-in reply [:yin.k/answer :yin.k/op-id]
                        {:yin.k/occurrence occ, :yin.k/seq 99})]
             ["another incarnation" arb
              (assoc-in reply [:yin.k/answer :yin.k/incarnation] "lease-2")]]]
      (testing name
        (let [x (writer/discharge (:machine r) author record)]
          (is (nil? (:discharged x)))
          (is (nil? (:run-end x)))
          (is (some? (::writer/retain x)))
          (is (= (:wait-set (:machine r)) (:wait-set (:machine x)))
              "the entry is unchanged and still waits")
          (is (empty? (:ready-queue (:machine x)))))))))


(deftest a-projected-outcome-of-another-incarnation-is-ignored-test
  (let [w (world)
        m (enrolled-writer w {})
        r (writer/emit m (appender w))
        _ (answer! w 4)
        projected (first (read-all (:outcomes w)))
        same (writer/discharge (:machine r) arb projected)
        ;; the regranted holder carries the same retained id under a new
        ;; lease, and reads the projection the old incarnation filled
        regranted (assoc (:machine r)
                         :yin.k/custody (custody-for
                                          (:target w)
                                          {:dao.lease/lease "lease-2"
                                           :yin.k/epoch 1}))
        other (writer/discharge regranted arb projected)]
    (is (some? (:discharged same))
        "the control: the projection of this incarnation discharges")
    (is (= [:committed] (mapv :admission [(:discharged same)])))
    (is (nil? (:discharged other)))
    (is (= :incarnation (::writer/retain other)))
    (is (= (:wait-set regranted) (:wait-set (:machine other)))
        "the entry keeps its id for the retry, which replays by reply")))


;; =============================================================================
;; Regrant: a new request id, the same op id
;; =============================================================================
(deftest a-resent-request-after-a-regrant-test

  (let [w (world)
        m (enrolled-writer w {})
        replies (reply-reader w)
        r (writer/emit m (appender w))
        _ (answer! w 4)
        d (writer/drain (:machine r) replies)
        _ (is (= [:committed] (mapv :admission (:discharged d))))
        _ (stream/append! (grant/writer (:a w))
                          (lease/lapsed "lease-1" :silence))
        _ (stream/append! (grant/writer (:a w))
                          (lease/grant "lease-2" (custody/subject occ)
                                       "holder-a" duration
                                       {:dao.lease/proposal "p-2"}))
        ;; the regranted holder lowers the same bytes: the same retained
        ;; id, a new lease and epoch in its custody
        regranted (assoc (:machine r)
                         :yin.k/custody (custody-for
                                          (:target w)
                                          {:dao.lease/lease "lease-2"
                                           :yin.k/epoch 1}))
        r2 (writer/emit regranted (appender w))
        old-id (get-in (first (:appended r)) [:request :yin.k/request-id])
        new-id (get-in (first (:appended r2)) [:request :yin.k/request-id])]
    (is (not= old-id new-id) "the regrant changed the request id")
    (is (= (nth old-id 2) (nth new-id 2)) "the op id did not change")
    (is (= "lease-2" (nth new-id 1)))
    (is (= 1 (get-in (first (:appended r2))
                     [:request :yin.k/fenced-envelope :yin.k/epoch]))
        "the envelope carries the new binding's epoch")
    (testing "the retry replays the result already held"
      (answer! w 4)
      (let [d2 (writer/drain (:machine r2) replies)]
        (is (= [:replayed] (mapv :admission (:discharged d2))))
        (is (= [ok-result] (mapv :effect-result (:discharged d2))))
        (is (= ["v"] (read-all (:reader w)))
            "still one effect: the replay committed nothing")
        (is (empty? (:wait-set (:machine d2))))))))


;; =============================================================================
;; The unknown-effect cut, before and after the commit
;; =============================================================================
(deftest the-cut-before-the-commit-commits-on-the-retry-test

  (let [w (world)
        m (enrolled-writer w {})
        r (writer/emit m (dropping-appender))]
    (is (id-retained? r))
    (let [r2 (writer/emit (:machine r) (appender w))]
      (is (= :dao.stream/ok
             (:dao.stream/outcome (:append (first (:appended r2))))))
      (answer! w 4)
      (let [d (writer/drain (:machine r2) (reply-reader w))]
        (is (= [:committed] (mapv :admission (:discharged d))))
        (is (= ["v"] (read-all (:reader w))) "exactly one effect")))))


(deftest the-cut-after-the-commit-replays-on-the-retry-test
  (let [w (world)
        m (enrolled-writer w {})
        r (writer/emit m (cutting-appender w))]
    (is (= :dao.stream/transport-error
           (:dao.stream/outcome (:append (first (:appended r)))))
        "the writer itself learned only the unknown effect")
    (is (id-retained? r))
    (is (= ["v"] (read-all (:reader w)))
        "the authority had already committed")
    (testing "the retry through the fenced boundary replays"
      (let [r2 (writer/emit (:machine r) (appender w))]
        (answer! w 4)
        (let [d (writer/drain (:machine r2) (reply-reader w))]
          ;; reading in stream order, the writer first learns of the
          ;; commit the cut hid from it, then the retry's own answer
          (is (= [:committed :replayed]
                 (mapv (comp :yin.k/admission :yin.k/answer)
                       (read-all (:out w))))
              "the resend's answer was the replay")
          (is (= [:committed] (mapv :admission (:discharged d))))
          (is (= [ok-result] (mapv :effect-result (:discharged d))))
          (is (= ["v"] (read-all (:reader w)))
              "one effect in total: it commits once or replays, never twice")
          (is (empty? (:wait-set (:machine d)))))))))


;; =============================================================================
;; The protection classes
;; =============================================================================
(deftest an-at-least-once-write-carries-no-id-and-is-appended-bare-test

  (let [w (world)
        bare (s/one-slot-stream "bare")
        m (assoc (parked-put bare "b")
                 :yin.k/custody (custody-for "bare"
                                             {:protection
                                              {"bare" :at-least-once}}))
        [append! n] (counting (appender w))
        r (writer/emit m append!)]
    (is (empty? (:appended r)) "no request is sent for it")
    (is (zero? @n))
    (is (= [nil] (mapv (comp :op-id :entry) (:bare r)))
        "no id is assigned to it")
    (is (= 0 (get-in (:machine r) [:yin.k/custody :yin.k/next-op-seq])))
    (is (= [{:path [], :outcome {:dao.stream/outcome :dao.stream/ok}}]
           (mapv #(select-keys % [:path :outcome]) (:bare r)))
        "the bare append and its outcome")
    (is (empty? (:wait-set (:machine r))) "the applied outcome woke it")
    (is (= [:ok] (mapv :status (:ready-queue (:machine r)))))
    (is (= ["b"] (read-all bare)) "the value itself was appended")
    (testing "a full bare stream keeps the write waiting"
      (let [full (s/one-slot-stream "full")
            _ (stream/append! full :warm)
            m2 (assoc (parked-put full "b")
                      :yin.k/custody (custody-for "full"
                                                  {:protection
                                                   {"full" :at-least-once}}))
            r2 (writer/emit m2 append!)]
        (is (= [{:path [], :outcome {:dao.stream/outcome :dao.stream/full}}]
               (mapv #(select-keys % [:path :outcome]) (:bare r2))))
        (is (= [:put] (mapv :reason (:wait-set (:machine r2))))
            "the write waits, as on its own full path")))))


(deftest a-fail-stop-write-ends-the-run-before-performing-it-test
  (let [w (world)
        child (parked-put (s/one-slot-stream "child") "child-v")
        m (assoc (parked-put (:reader w) "v")
                 :installs {'host.mod (install-entry child)}
                 :yin.k/custody (custody-for (:target w)
                                             {:protection
                                              {(:target w) :fail-stop}}))
        [append! n] (counting (appender w))
        r (writer/emit m append!)]
    (is (= {:cause :fail-stop, :dao.stream/identity (:target w)}
           (:run-end r)))
    (is (empty? (:appended r)) "the write is not performed")
    (is (empty? (:bare r)))
    (is (zero? @n))
    (is (= :ended (vm/gate-mode (:machine r))))
    (is (= :ended (vm/gate-mode (get-in (:machine r)
                                        [:installs 'host.mod :vm])))
        "the root owns the mode and stamps it")
    (is (= [nil] (mapv :op-id (:wait-set (:machine r)))) "no id was taken")
    (is (= 0 (get-in (:machine r) [:yin.k/custody :yin.k/next-op-seq])))
    (is (= :put (:reason (first (:wait-set (:machine r)))))
        "the write stays an undischarged wait")
    (testing "nothing further is emitted for an ended run"
      (let [r2 (writer/emit (:machine r) append!)]
        (is (empty? (:appended r2)))
        (is (empty? (:bare r2)))
        (is (zero? @n))))))


(deftest an-undeclared-target-is-unsatisfied-test
  (let [w (world)
        m (assoc (parked-put (s/one-slot-stream "wild") "v")
                 :yin.k/custody (custody-for :none {:protection {}}))
        [append! n] (counting (appender w))
        r (writer/emit m append!)]
    (is (= {:yin.k/status :yin.k/unsatisfied
            :dao.stream/identity "wild"}
           (:unsatisfied r)))
    (is (empty? (:appended r)))
    (is (empty? (:bare r)))
    (is (zero? @n))
    (is (= [nil] (mapv :op-id (:wait-set (:machine r)))))))


;; =============================================================================
;; After :intent-conflict
;; =============================================================================
(deftest intent-conflict-ends-emission-and-quarantine-survives-release-test

  (let [[w lease-1 j0] (judge-granted-world)
        committed-id {:yin.k/occurrence occ, :yin.k/seq 0}
        replies (reply-reader w)
        ;; the first holder's write commits and is discharged
        w1 (assoc (parked-put (:reader w) "v")
                  :yin.k/custody (custody-for (:target w)
                                              {:dao.lease/lease lease-1}))
        r1 (writer/emit w1 (appender w))
        _ (answer! w 4)
        d1 (writer/drain (:machine r1) replies)
        _ (is (= [:committed] (mapv :admission (:discharged d1))))
        ;; a regranted re-execution of the same operation under the same
        ;; retained id, diverged (the kept-cursor gap of 14.2.4 row 5):
        ;; the entry a real replay parks, its value changed by what it
        ;; re-read; and an install child holding an at-least-once write
        bare (ring 8)
        child (parked-put bare "b")
        divergent (assoc (parked-put (:reader w) "other")
                         :installs {'host.mod (install-entry child)}
                         :yin.k/custody (custody-for
                                          (:target w)
                                          {:dao.lease/lease lease-1
                                           :protection {(:target w) :enrolled
                                                        (stream-id bare)
                                                        :at-least-once}}))
        divergent (assoc-in divergent [:wait-set 0 :op-id] committed-id)
        [append! n] (counting (appender w))
        r2 (writer/emit divergent append!)]
    (is (= 1 @n) "the divergent write was sent, under the retained id")
    (is (= ["b"] (read-all bare))
        "the child's at-least-once write was performed while the run lived")
    (answer! w 4)
    (let [reply (last (read-all (:out w)))
          d2 (writer/drain (:machine r2) replies)]
      (is (= :intent-conflict (get-in reply [:yin.k/answer :yin.k/admission])))
      (is (= {:cause :intent-conflict, :op-id committed-id} (:run-end d2)))
      (is (= :ended (vm/gate-mode (:machine d2))))
      (is (= 1 (count (:wait-set (:machine d2))))
          "the conflicting write itself is never applied")
      (is (= committed-id (:op-id (first (:wait-set (:machine d2))))))
      (is (true? (get-in (authority/projection (:a w))
                         [:occurrences occ :yin.k/quarantined]))
          "the authenticated conflict quarantined the occurrence")
      (testing "no further fenced emission, and no at-least-once write"
        (let [r3 (writer/emit (:machine d2) append!)]
          (is (empty? (:appended r3)))
          (is (empty? (:bare r3)))
          (is (= 1 @n) "the appender was not called again")
          (is (= ["b"] (read-all bare))
              "the run's end stops the at-least-once write too")))
      (testing "the release is carried"
        (let [release {:yin.k/request :yin.k/release
                       :yin.k/request-id "r-rel"
                       :dao.lease/lease lease-1}
              _ (stream/append! (:in w) release)
              _ (answer! w 4)]
          (is (= (lease/release lease-1) (last (read-all (:medium w)))))
          (is (= {:yin.k/status :carried}
                 (:yin.k/answer (last (read-all (:out w))))))
          (let [j (grant/step! (:a w) j0)
                p (authority/projection (:a w))]
            (is (= :release (get-in p [:leases lease-1 :dao.lease/cause]))
                "the release lapsed the lease")
            (is (true? (get-in p [:occurrences occ :yin.k/quarantined]))
                "the D11 release cleared no quarantine")
            (is (nil? (get-in p [:occurrences occ :yin.k/closed]))
                "the occurrence stays open: no completion")
            (testing "and it stays ungranted"
              (stream/append! (:medium w)
                              (lease/proposal "p-3" (custody/subject occ)))
              (grant/step! (:a w) j)
              (let [p2 (authority/projection (:a w))]
                (is (nil? (get-in p2 [:occurrences occ :dao.lease/lease]))
                    "no second grant for the quarantined occurrence")
                (is (= 1 (count (facts (:frames w) :dao.lease/accepted)))
                    "the one grant this world ever made is the first")))))))))


;; =============================================================================
;; The carried D6-gate obligations, in the engine
;; =============================================================================
(defn- counting-handle

  "A handle answering `read-outcome` to every next and `put-outcome` to
every append, counting the calls that reach it."
  [calls read-outcome put-outcome]
  (reify
    stream/IDaoStreamDescriptor
    (descriptor

      [_]
      {:dao.stream/outcome :dao.stream/ok
       :dao.stream/identity "scripted"
       :dao.stream/descriptor {:dao.stream/type :dao.stream.test/channel,
                               :dao.stream/identity "scripted"}})


    stream/IDaoStreamReader

    (cursor

      [_ _]
      (swap! calls inc)
      {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor 0})

    (next
      [_ _]
      (swap! calls inc)
      read-outcome)


    stream/IDaoStreamWriter

    (append!

      [_ _]
      (swap! calls inc)
      put-outcome)))


(def ^:private gate-opts
  {:park-entry-fns
   {:stream/put (fn [_s _e r] {:reason :put, :stream-id (:stream-id r)}),
    :stream/next (fn [_s _e r]
                   {:reason :next,
                    :cursor-ref (:cursor-ref r),
                    :stream-id (:stream-id r)})}})


(defn- effect-of
  [kind m]
  (module/make-effect kind m))


(deftest a-read-apply-refuses-an-unminted-cursor-cell-test
  (let [calls (atom 0)
        read-ok {:dao.stream/outcome :dao.stream/ok,
                 :dao.stream/value :v,
                 :dao.stream/cursor 1}
        [sref s0] (engine/attach-resource
                    (vm/empty-state {:make-stream tu/make-stream,
                                     :capability-secret tu/secret})
                    (counting-handle calls read-ok
                                     {:dao.stream/outcome :dao.stream/ok}))
        gated (assoc s0 :yin.k/gate :running)
        cursor (engine/handle-effect gated
                                     (effect-of :stream/cursor {:stream sref})
                                     gate-opts)
        cref (:value cursor)
        next-r (engine/handle-effect (:state cursor)
                                     (effect-of :stream/next {:cursor cref})
                                     gate-opts)
        poll-r (engine/handle-effect (:state next-r)
                                     (effect-of :stream/poll {:cursor cref})
                                     gate-opts)
        m (:state poll-r)
        [next-entry observe-entry] (:wait-set m)
        cell-id (:id (:cursor-ref next-entry))]
    (is (zero? @calls))
    (is (:yin.k/unminted (get-in m [:resources cell-id]))
        "the gate left the cell unminted")
    (is (map? (:cursor-ref next-entry)))
    (testing "both read applies refuse it"
      (is (throws? #(engine/apply-next m next-entry read-ok)))
      (is (throws? #(engine/apply-observation m observe-entry read-ok))))
    (is (zero? @calls) "no stream call either way")
    (testing "mint first, then the apply advances the cell"
      (let [minted (engine/apply-mint m cell-id 0)
            applied (engine/apply-next minted next-entry read-ok)]
        (is (= 1 (get-in applied [:resources cell-id :cursor])))
        (is (= [:next] (mapv :reason (:ready-queue applied))))
        (is (= [:observe] (mapv :reason (:wait-set applied)))
            "only the next entry left; the observe one still waits")
        (is (= :v (:value (first (:ready-queue applied)))))
        (is (zero? @calls))))))


(deftest apply-ffi-outcome-covers-the-terminal-append-outcomes-test
  (let [call-in (s/one-slot-stream "call-in")
        call-out (tu/new-stream 8)
        base (fn []
               (let [m0 (s/new-machine {:call-in call-in
                                        :call-out call-out
                                        :call-out-cursor
                                        (vm/mint-oldest call-out :test)})
                     loaded (s/load-ast m0
                                        {:type :dao.stream.apply/call,
                                         :op :op/echo,
                                         :operands [(s/lit "hello")]})]
                 (vm/run (assoc loaded :yin.k/gate :running))))
        m (base)
        entry (first (:wait-set m))
        call (ffi/request-call-id entry)]
    (is (some? call) "a retained FFI request is parked")
    (is (zero? (count (read-all call-in)))
        "the gate made no append; the slot is free")
    (testing "ok is the sent transition"
      (let [sent (engine/apply-ffi-sent m call)
            via-outcome (engine/apply-ffi-outcome m call
                                                  {:dao.stream/outcome
                                                   :dao.stream/ok})]
        (is (= sent via-outcome))
        (is (empty? (:wait-set via-outcome)))
        (is (= [:ok] (mapv :status (:ready-queue via-outcome)))
            "woken as the sent request the kernel restores as the reader")))
    (testing "full keeps the request waiting"
      (is (= m (engine/apply-ffi-outcome m call
                                         {:dao.stream/outcome
                                          :dao.stream/full}))))
    (testing "a terminal outcome wakes it under its own status"
      (let [refused (engine/apply-ffi-outcome m call
                                              {:dao.stream/outcome
                                               :dao.stream/closed})]
        (is (empty? (:wait-set refused)))
        (is (= [:dao.stream/closed] (mapv :status (:ready-queue refused))))))
    (testing "refused as apply-ffi-sent is"
      (is (throws? #(engine/apply-ffi-outcome
                      (dissoc m :yin.k/gate) call
                      {:dao.stream/outcome :dao.stream/ok})))
      (is (throws? #(engine/apply-ffi-outcome m :no-such-call
                                              {:dao.stream/outcome
                                               :dao.stream/ok})))
      (let [sent (engine/apply-ffi-outcome m call
                                           {:dao.stream/outcome
                                            :dao.stream/ok})]
        (is (throws? #(engine/apply-ffi-outcome sent call
                                                {:dao.stream/outcome
                                                 :dao.stream/ok}))
            "a second transition finds no entry")))))


;; =============================================================================
;; Link Requests
;; =============================================================================

(deftest link-requests-assigned-and-emitted-test
  (let [w (world)
        m0 (s/parked-linker)
        target-id (stream-id (get (:resources m0)
                                  module/link-request-resource))
        m (assoc m0 :yin.k/custody (custody-for target-id))
        [append! n] (counting (appender w))
        r (writer/emit m append!)]
    (testing "a cursorless link request is retained: no send, no cursor call"
      (is (= 0 @n))
      (let [entry (first (:wait-set (:machine r)))]
        (is (= :link-request (:reason entry)))
        (is (not (contains? entry :cursor)) "the mint belongs to D12")
        (is (empty? (:appended r))))))
  (let [w (world)
        m0 (s/parked-linker)
        target-id (stream-id (get (:resources m0)
                                  module/link-request-resource))
        lid (:link-id (first (:wait-set m0)))
        ;; the driver's recorded :newest observation, applied before
        ;; the send (the link-cursor ruling)
        m (-> (engine/apply-link-cursor m0 lid 123)
              (assoc :yin.k/custody (custody-for target-id)))
        [append! n] (counting (appender w))
        r (writer/emit m append!)]
    (testing "with the recorded cursor installed, emit assigns and sends"
      (is (= 1 @n))
      (let [entry (first (:wait-set (:machine r)))]
        (is (= {:yin.k/occurrence occ, :yin.k/seq 0} (:op-id entry)) "assigned id")
        (is (some? (:cursor entry)) "the recorded cursor rides the entry")))
    (testing "discharge applies link-sent"
      ;; the authority's reply: the link request stream is not an
      ;; enrolled target, so the test authors the admission the front
      ;; would carry for an enrolled one
      (let [rid [:yin.k/admit "lease-1" {:yin.k/occurrence occ :yin.k/seq 0}]]
        (stream/append! (:out w)
                        {:yin.k/reply :yin.k/admit
                         :yin.k/request-id rid
                         :yin.k/answer {:yin.k/admission :committed
                                        :yin.k/op-id (nth rid 2)
                                        :yin.k/incarnation "lease-1"
                                        :yin.k/effect-result ok-result}}))
      (let [d (writer/drain (:machine r) (reply-reader w))]
        (is (= [:committed] (mapv :admission (:discharged d))))
        (is (= :link-response (:reason (first (:wait-set (:machine d)))))
            "transitioned via apply-link-sent")))))


;; =============================================================================
;; Close Ordering
;; =============================================================================

(deftest close-ordering-test
  (let [w (world)
        c (atom [])
        append-diag! #(swap! c conj %)
        m0 (s/new-machine)
        [ref m1] (engine/attach-resource m0 (:reader w))
        m (assoc m1 :store {'w ref} :yin.k/gate :running)
        ;; put1, then a close, then put2: the issue stamps must order
        ;; the writer's walk so the close never overtakes the first
        ;; put and the second put never overtakes the close
        r1 (engine/handle-effect m (module/make-effect :stream/put {:stream ref, :val "v1"}) gate-opts)
        r2 (engine/handle-effect (:state r1) (module/make-effect :stream/close {:stream ref}) gate-opts)
        r3 (engine/handle-effect (:state r2) (module/make-effect :stream/put {:stream ref, :val "v2"}) gate-opts)
        m (assoc (:state r3) :yin.k/custody (custody-for (:target w)))
        [append! n] (counting (appender w))
        r (writer/emit m append! append-diag!)]
    (testing "first put emitted, close blocked behind it"
      (is (= 1 @n))
      (is (= 1 (count (:yin.k/closes (:machine r)))))
      (is (empty? @c) "no diagnostic yet"))
    (testing "after the first put discharges, the close resolves and the later put emits"
      (answer! w 4)
      ;; bounded read: the front carried exactly one admission reply
      (let [[reply] (read-all (:out w))
            out (writer/discharge (:machine r) arb reply)
            r2 (writer/emit (:machine out) append! append-diag!)]
        (is (some? (:discharged out)) "the first put discharged")
        (is (= 1 (count @c)) "the enrolled close is a diagnostic")
        (is (empty? (:yin.k/closes (:machine r2))) "the close resolved")
        (is (= 2 @n) "the second put emitted after the close")))))


(deftest link-cursor-ruling-test
  (let [w (world)
        m0 (s/parked-linker)
        lid (:link-id (first (:wait-set m0)))
        target-id (stream-id (get (:resources m0)
                                  module/link-request-resource))
        m-custody (assoc m0 :yin.k/custody (custody-for target-id)
                         :yin.k/gate :running)
        [append! n] (counting (appender w))
        r1 (writer/emit m-custody append!)]
    (testing "cursorless link request is retained without sending or minting"
      (is (= 0 @n))
      (is (= :link-request (:reason (first (:wait-set (:machine r1))))))
      (is (empty? (:bare r1)))
      (is (empty? (:appended r1))))
    (testing "when cursor is recorded, emit sends"
      (let [m2 (engine/apply-link-cursor (:machine r1) lid 123)
            r2 (writer/emit m2 append!)]
        (is (= 1 @n))
        (let [entry (first (:wait-set (:machine r2)))]
          (is (= :link-request (:reason entry)) "emit retains the sent write")
          (is (= {:yin.k/occurrence occ, :yin.k/seq 0} (:op-id entry))
              "the assigned id rode the send"))))))


(deftest bare-link-path-test
  (let [w (world)
        m0 (s/parked-linker)
        lid (:link-id (first (:wait-set m0)))
        m2 (engine/apply-link-cursor m0 lid 123)
        ;; At-least-once target so it goes through bare link path
        m-custody (assoc m2 :yin.k/custody
                         {:protection {(stream-id (get (:resources m2)
                                                       module/link-request-resource))
                                       :at-least-once}}
                         :yin.k/gate :running)
        h (get (:resources m-custody) yin.vm.module/link-request-resource)]
    (testing "successful append transitions"
      (let [r (writer/emit m-custody (appender w))]
        (is (= :link-response (:reason (first (:wait-set (:machine r))))))))
    (testing "a terminal bare outcome is a failure naming the link, and the write is not retried"
      (stream/close! h)
      (is (throws? #(writer/emit m-custody (appender w)))
          "the closed stream's append answers closed: terminal, thrown")
      (let [entry (first (:wait-set m-custody))]
        (is (= :link-request (:reason entry)))
        (is (not (contains? entry :op-id)) "a bare write takes no id")
        (is (some? (:envelope entry)) "the envelope is retained verbatim")))))


(deftest close-at-least-once-order-test
  (let [w (world)
        m0 (s/new-machine)
        h (log)
        [ref m1] (engine/attach-resource m0 h)
        m (assoc m1 :store {'w ref} :yin.k/gate :running)
        r1 (engine/handle-effect m (module/make-effect :stream/close {:stream ref}) gate-opts)
        r2 (engine/handle-effect (:state r1) (module/make-effect :stream/put {:stream ref, :val "v2"}) gate-opts)
        m-custody (assoc (:state r2) :yin.k/custody {:protection {(:dao.stream/identity (stream/descriptor h)) :at-least-once}})
        r (writer/emit m-custody (appender w))]
    (testing "subsequent write sees :closed from earlier close"
      (is (= 1 (count (:bare r))))
      (is (= :dao.stream/closed (:dao.stream/outcome (:outcome (first (:bare r))))))
      (is (empty? (:wait-set (:machine r))) "the closed outcome woke the write off the wait set")
      (is (= :dao.stream/closed
             (some-> (:ready-queue (:machine r)) first :status))
          "woken with the closed status"))))


(deftest close-undeclared-test
  (let [w (world)
        m0 (s/new-machine)
        h (log)
        [ref m1] (engine/attach-resource m0 h)
        m (assoc m1 :store {'w ref} :yin.k/gate :running)
        r1 (engine/handle-effect m (module/make-effect :stream/close {:stream ref}) gate-opts)
        m-custody (assoc (:state r1) :yin.k/custody {:protection {}})
        r (writer/emit m-custody (appender w))]
    (testing "undeclared close returns unsatisfied, retains close"
      (is (some? (:unsatisfied r)))
      (is (= :yin.k/unsatisfied (:yin.k/status (:unsatisfied r))))
      (is (= 1 (count (:yin.k/closes (:machine r))))))))
