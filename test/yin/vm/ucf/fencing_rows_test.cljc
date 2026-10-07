(ns yin.vm.ucf.fencing-rows-test
  "D16-prep: the stage-D rows of linker-dht 14.2.4 that need no
   composition, through a real lift and lower and the landed fenced
   writer (D11) and recorded reader (D12) -- not through
   `yin.vm.ucf.compose`.

   One checkpoint serves every tenure: a real gated machine parked on a
   read of a string-backed source whose continuation writes the value it
   read to the authority's enrolled target.  It is lifted once to
   version-1 bytes, offered to the real authority, and every tenure --
   first grant and each regrant -- lowers those bytes into a fresh
   machine over the toy channel, so the cursor and the write the holder
   performs are the lowered ones, reached through reflections.

   Row 2's fencing half: an id assigned before an unknown append survives
   entering exporting -- the writer and reader append nothing there --
   and rides the successor's bytes unchanged however often the carrier
   is retried.  Row 4's fencing half: a crash before the commit, after
   it, and before the result is delivered; the regrant replays its
   durable input and retries the same id, committing once or replaying
   the stored result.  Row 5: the kept-cursor value is evicted after the
   crash; the regrant recovers with durable inputs and reproduces the old
   intent, fails closed without them, and a divergent re-read at the same
   id is an intent conflict that commits nothing."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [dao.stream.memory-log :as memory-log]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.front :as front]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.handoff :as handoff]
            [yin.vm.ucf.holder.export :as export]
            [yin.vm.ucf.holder.reader :as reader]
            [yin.vm.ucf.holder.writer :as writer]
            [yin.vm.ucf.ledger :as ledger]
            [yin.vm.ucf.v2-support :as s]))


(def ^:private arb "arb-d16")
(def ^:private occ "0c000000-0000-4000-8000-000000000016")
(def ^:private successor "0c000000-0000-4000-8000-000000000017")
(def ^:private duration {:s 30})


;; =============================================================================
;; The world: a real authority, its front, one enrolled target
;; =============================================================================

(defn- log
  []
  (:dao.stream/handle
    (memory-log/create! {:dao.stream/type :dao.stream/memory-log})))


(defn- identity-of
  [h]
  (:dao.stream/identity (stream/descriptor h)))


(defn- read-all
  [h]
  (loop [c (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)) vs []]
    (let [r (stream/next h c)]
      (if (and (= :dao.stream/ok (:dao.stream/outcome r)) (< (count vs) 1000))
        (recur (:dao.stream/cursor r) (conj vs (:dao.stream/value r)))
        vs))))


(defn- reader-over
  "The composition's reader over `h`, attributing it to `author`: the
   next [author record] until the tail."
  [h author]
  (let [c (atom (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)))]
    (fn []
      (let [r (stream/next h @c)]
        (when (= :dao.stream/ok (:dao.stream/outcome r))
          (reset! c (:dao.stream/cursor r))
          [author (:dao.stream/value r)])))))


(defn- world
  "An authority over fresh frames with one enrolled target, and the
   holder's front over rings, attributing its inbound to holder-a."
  []
  (let [frames (atom [(cbor/encode {:dao.stream.journal/header
                                    {:version 1 :identity arb}})])
        a (::authority/authority (authority/open! (journal/memory-backend
                                                    frames nil)
                                                  nil))
        store (mem/create-content-mem)
        in (s/ring 16)
        out (s/ring 16)
        i (:yin.k/target (authority/enroll! a))]
    {:a a
     :store store
     :in in
     :out out
     :target i
     :reader (authority/target-reader a i)
     :replies (reader-over out arb)
     :front (atom (front/front {:authority a
                                :inbound in
                                :reply out
                                :diagnostics (s/ring 16)
                                :resolver (fn [id _]
                                            (when (= id (identity-of in))
                                              "holder-a"))
                                :store store
                                :lease-media {"holder-a" (log)}}))}))


(defn- appender
  [w]
  (fn [request] (stream/append! (:in w) request)))


(defn- answer!
  [w]
  (swap! (:front w) front/step 8)
  w)


(defn- grant!
  [w lease-id]
  (stream/append! (grant/writer (:a w))
                  (lease/grant lease-id (custody/subject occ) "holder-a" duration
                               {:dao.lease/proposal (str "p-" lease-id)})))


(defn- reclaim!
  [w lease-id]
  (stream/append! (grant/writer (:a w)) (lease/lapsed lease-id :silence)))


;; =============================================================================
;; The checkpoint: lifted once, offered, lowered by every tenure
;; =============================================================================

(defn- same-id-server
  "The exporter's `serve!`, entering each handle under its own identity,
   so a lowered write targets the enrolled identity itself."
  [peer chan]
  (fn [h]
    (let [id (identity-of h)]
      (swap! (:table peer) assoc id {:handle h, :surface #{:reader :writer}})
      {:dao.stream/identity id, :dao.stream/channel chan})))


(defn- reading-writer
  "A real gated machine parked on one read of `source`, whose
   continuation writes the value it read to `target`."
  [source target]
  (let [m0 (s/load-ast :semantic (s/new-machine :semantic)
                       (s/let1 'v (s/next-of (s/v 'c))
                               {:type :stream/put, :target (s/v 'w), :val (s/v 'v)}))
        [sref m1] (engine/attach-resource m0 source)
        [cref m2] (engine/handle-cursor m1 {:stream sref} :k1)
        [wref m3] (engine/attach-resource m2 target)]
    (vm/run (assoc m3 :store {'c cref, 'w wref} :yin.k/gate :running))))


(def ^:private arbitration
  {:dao.stream/identity arb
   :dao.stream/descriptor {:dao.stream/type :dao.stream/journal}})


(defn- checkpoint!
  "A source holding A, the checkpoint's bytes over a fresh toy, offered
   to the world's authority.  Answers {:source :export :t :peer}."
  [w]
  (let [source (s/ring 8)
        _ (stream/append! source "A")
        t (s/toy)
        peer (s/served-peer t)
        export (handoff/export-task
                 (reading-writer source (:reader w))
                 (same-id-server peer (:channel t))
                 {:header {:yin.k/occurrence occ
                           :yin.k/arbitration arbitration
                           :yin.k/next-op-seq 0
                           :yin.k/enrolled #{(:target w)}}})]
    (assert (= :ok (:status export)) (pr-str export))
    (grant/offer! (:a w) (:store w) (:address export) (:bytes export) "carrier")
    {:source source :export export :t t :peer peer}))


(defn- prefix
  [lease-id records]
  {:yin.k/occurrence occ
   :dao.lease/lease lease-id
   :yin.k/frontier (count records)
   :yin.k/inputs records})


(defn- lower
  "The checkpoint lowered for tenure `lease-id` at `epoch` with input
   prefix `p`.  Answers [outcome attaches]."
  [w cp lease-id epoch p]
  (let [attaches (atom 0)
        base (s/attacher (:t cp))
        attach! (fn [d] (swap! attaches inc) (base d))
        address (:address (:export cp))
        r (handoff/resume-task
            (s/new-machine :semantic {:attach-stream attach!})
            (:bytes (:export cp)) attach!
            {:address address
             :exclusive true
             :protection {(:target w) :enrolled
                          (identity-of (:source cp)) :at-least-once}
             :grant {:checkpoint address
                     :dao.lease/lease lease-id
                     :dao.lease/holder "holder-a"
                     :tenure {:now 10 :bound 20 :live true}
                     :evidence {:yin.k/status :yin.k/ready
                                :yin.k/binding {:yin.k/occurrence occ
                                                :dao.lease/lease lease-id
                                                :dao.lease/holder "holder-a"
                                                :yin.k/epoch epoch
                                                :yin.k/transaction
                                                {:yin.k/arbitration arb}}
                                :yin.k/enrolled #{(:target w)}
                                :yin.k/prefix p}}})]
    [r @attaches]))


(defn- observer
  "The composition's handle observer over reflections: the mirror is
   stepped until the source answers, so one observation is the source's
   own outcome."
  [peer]
  (fn [h op arg]
    (loop [n 6]
      (s/serve-mirror! peer)
      (let [r (case op
                :next (stream/next h arg)
                :cursor (stream/cursor h arg))]
        (if (and (= :dao.stream/blocked (:dao.stream/outcome r)) (pos? n))
          (recur (dec n))
          r)))))


(defn- read-live
  "One recorded live read of the lowered tenure `m`: observe, record,
   settle.  Answers [machine recorded-input]."
  [w cp m]
  (let [stepped (reader/step m (appender w) (observer (:peer cp)))
        _ (answer! w)
        drained (reader/drain (:machine stepped) (:replies w))]
    (is (= 1 (count (:applied drained))) (pr-str (dissoc drained :machine)))
    [(vm/run (:machine drained))
     (get-in (first (:appended stepped)) [:request :yin.k/input])]))


(defn- record-of
  "The prefix record of the input request `input`."
  [input]
  {:yin.k/input-seq (:yin.k/input-seq input)
   :yin.k/source (:yin.k/source input)
   :yin.k/observed (:yin.k/observed input)})


(defn- write-entry
  [m]
  (first (filter #(= :put (:reason %)) (:wait-set m))))


;; =============================================================================
;; The appenders that cut
;; =============================================================================

(defn- dropping
  "Cut before the request: nothing lands; the writer learns unknown."
  [_w]
  (fn [_r] {:dao.stream/outcome :dao.stream/transport-error}))


(defn- cutting
  "Cut after the commit: the request lands and the front answers it, but
   the writer learns only unknown."
  [w]
  (fn [request]
    (let [r (stream/append! (:in w) request)]
      (answer! w)
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        {:dao.stream/outcome :dao.stream/transport-error}
        r))))


(defn- undelivered
  "The commit and its reply both happen; the holder crashes before it
   reads the result."
  [w]
  (fn [request]
    (let [r (stream/append! (:in w) request)]
      (answer! w)
      r)))


;; =============================================================================
;; Row 4's fencing half: crashes around the commit, through the lower
;; =============================================================================

(deftest a-regranted-lower-retries-the-same-id-and-commits-once
  (doseq [[cut appender-of second-admission]
          [[:before-commit dropping :committed]
           [:after-commit cutting :replayed]
           [:before-result undelivered :replayed]]]
    (testing (name cut)
      (let [w (world)
            cp (checkpoint! w)
            _ (grant! w "lease-1")
            [r1 n1] (lower w cp "lease-1" 0 (prefix "lease-1" []))
            _ (is (= :ok (:status r1)) (pr-str r1))
            _ (is (pos? n1))
            [run1 input] (read-live w cp (:vm r1))
            _ (is (= "A" (get-in input [:yin.k/observed :dao.stream/value]))
                  "tenure 1 read A through the reflection and recorded it")
            e1 (writer/emit run1 (appender-of w))
            id1 (:op-id (write-entry (:machine e1)))
            ;; the crash: tenure 1's machine is gone; the lease lapses
            _ (reclaim! w "lease-1")
            _ (grant! w "lease-2")
            [r2 _] (lower w cp "lease-2" 1 (prefix "lease-2" [(record-of input)]))
            _ (is (= :ok (:status r2)) (pr-str r2))
            replayed (reader/replay (:vm r2))
            run2 (vm/run (:machine replayed))
            e2 (writer/emit run2 (appender w))
            id2 (get-in (first (:appended e2))
                        [:request :yin.k/fenced-envelope :yin.k/op-id])
            _ (answer! w)
            d2 (writer/drain (:machine e2) (:replies w))]
        (is (= {:yin.k/occurrence occ :yin.k/seq 0} id1)
            "tenure 1 assigned the first id of the checkpoint's counter")
        (is (= id1 id2) "the regrant's retry carries the same id")
        (is (= [0] (mapv :input-seq (:applied replayed)))
            "the regrant replayed the durable input, observing nothing live")
        (is (= 1 (count (:appended e2))))
        (is (= 1 (get-in (first (:appended e2))
                         [:request :yin.k/fenced-envelope :yin.k/epoch]))
            "the retry is fenced under the new binding's epoch")
        (is (= [second-admission] (mapv :admission (:discharged d2)))
            (pr-str (dissoc d2 :machine)))
        (is (= [ledger/ok-result] (mapv :effect-result (:discharged d2)))
            "the stored result is delivered")
        (is (= ["A"] (read-all (:reader w)))
            "one commit in total: never a duplicate side effect")
        (is (empty? (:wait-set (:machine d2))))))))


;; =============================================================================
;; Row 5: an evicted kept-cursor value, recovered with inputs and without
;; =============================================================================

(deftest an-evicted-kept-cursor-value-recovers-with-inputs-and-fails-closed-without
  (let [w (world)
        cp (checkpoint! w)
        p0 (:dao.stream/cursor (stream/cursor (:source cp) :dao.stream/oldest))
        _ (grant! w "lease-1")
        [r1 _] (lower w cp "lease-1" 0 (prefix "lease-1" []))
        [run1 input] (read-live w cp (:vm r1))
        e1 (writer/emit run1 (appender w))
        _ (answer! w)
        d1 (writer/drain (:machine e1) (:replies w))
        _ (is (= [:committed] (mapv :admission (:discharged d1))))
        ;; the crash, then the eviction of everything tenure 1 read
        _ (dotimes [_ 9] (stream/append! (:source cp) :junk))
        _ (is (= :dao.stream/gap
                 (:dao.stream/outcome (stream/next (:source cp) p0)))
              "the kept position was evicted at the source")
        _ (reclaim! w "lease-1")
        _ (grant! w "lease-2")]
    (testing "with durable inputs, the lowered regrant reproduces the old intent"
      (let [[r2 _] (lower w cp "lease-2" 1 (prefix "lease-2" [(record-of input)]))
            replayed (reader/replay (:vm r2))
            run2 (vm/run (:machine replayed))
            e2 (writer/emit run2 (appender w))
            _ (answer! w)
            d2 (writer/drain (:machine e2) (:replies w))]
        (is (= :ok (:status r2)) (pr-str r2))
        (is (nil? (:unmatched replayed)))
        (is (= "A" (:datom (write-entry run2)))
            "replay delivered the recorded value, not the live gap")
        (is (= [:replayed] (mapv :admission (:discharged d2)))
            "the same id with the same intent replays the stored result")
        (is (= ["A"] (read-all (:reader w))))))
    (testing "without durable inputs, the lower fails closed"
      (let [[r3 n3] (lower w cp "lease-2" 1 {:yin.k/status :suspended
                                             :yin.k/reason :unavailable})]
        (is (= :yin.k/unsatisfied (:yin.k/status r3)) (pr-str r3))
        (is (= :unavailable (:yin.k/reason r3)))
        (is (not (contains? r3 :vm)) "no machine is restored")
        (is (zero? n3) "nothing is attached")))
    (testing "a divergent re-read at the same id is intent-conflict, no commit"
      ;; a holder recovering without its records re-reads live and sees
      ;; the gap; the test authors that re-read on the lowered machine,
      ;; and the real authority judges the write it produces
      (let [[r4 _] (lower w cp "lease-2" 1 (prefix "lease-2" [(record-of input)]))
            m4 (:vm r4)
            entry (first (:wait-set m4))
            cell (get-in entry [:cursor-ref :id])
            handle (get (:resources m4) (:stream-id (get (:resources m4) cell)))
            gap ((observer (:peer cp)) handle :next
                                       (get-in m4 [:resources cell :cursor]))
            _ (is (= :dao.stream/gap (:dao.stream/outcome gap))
                  "the reflection answers the source's gap")
            run4 (vm/run (engine/apply-next m4 entry gap))
            e4 (writer/emit run4 (appender w))
            _ (answer! w)
            d4 (writer/drain (:machine e4) (:replies w))]
        (is (= {:cause :intent-conflict
                :op-id {:yin.k/occurrence occ :yin.k/seq 0}}
               (:run-end d4)))
        (is (true? (get-in (authority/projection (:a w))
                           [:occurrences occ :yin.k/quarantined]))
            "the authoritative conflict quarantined the occurrence")
        (is (= ["A"] (read-all (:reader w)))
            "the divergent write committed nothing")))))


;; =============================================================================
;; Clause 4: an id crosses park, bytes and lower on all three variants
;; =============================================================================

(defn- recording
  "An inbound appender whose acceptance is unknown, keeping each request."
  [sent]
  (fn [request]
    (swap! sent conj request)
    {:dao.stream/outcome :dao.stream/transport-error}))


(def ^:private retained-writes
  "[reason parked-machine-builder enrolled-identity]: a real machine
   whose one retained write targets the enrolled stream."
  [[:put #(first (s/parked-writer :semantic)) "w"]
   [:ffi-request #(first (s/parked-retained-caller :semantic)) "call-in"]
   [:link-request #(s/parked-link-request :semantic) "link-request"]])


(defn- markers
  "Every stream identity a body names."
  [body]
  (into #{}
        (comp (filter #(and (map? %) (= :yin.k/stream (:yin.k/tag %))))
              (map :dao.stream/identity))
        (tree-seq coll? seq body)))


(defn- custody-of
  [enrolled]
  {:yin.k/occurrence occ
   :dao.lease/lease "lease-1"
   :dao.lease/holder "holder-a"
   :yin.k/epoch 0
   :yin.k/arbitration arbitration
   :yin.k/next-op-seq 0
   :protection {enrolled :enrolled}
   :input {:next 0 :prefix (prefix "lease-1" [])}
   :tenure {:bound 100}})


(deftest a-carried-id-crosses-park-bytes-and-lower-and-retries-fenced
  (doseq [[reason parked enrolled] retained-writes]
    (testing (name reason)
      (let [sent (atom [])
            m (assoc (parked) :yin.k/custody (custody-of enrolled)
                     :yin.k/gate :running)
            ;; park: the writer assigns the id; its append's fate is unknown
            emitted (writer/emit m (recording sent))
            held (:machine emitted)
            id (:op-id (first (:wait-set held)))
            entered (export/enter held)
            t (s/toy)
            peer (s/served-peer t)
            header {:yin.k/occurrence successor
                    :yin.k/arbitration arbitration
                    :yin.k/next-op-seq 1
                    :yin.k/enrolled #{enrolled}
                    :yin.k/origin {:yin.k/occurrence occ
                                   :dao.lease/lease "lease-1"
                                   :yin.k/emitter "holder-a"}}
            prepared (export/prepare (:machine entered) (:record entered)
                                     (same-id-server peer (:channel t)) header)
            ;; bytes
            export (export/encode (:machine entered) (:record prepared))
            body (cbor/decode (:bytes export))
            pending (get-in body [:yin.k/frames 0 :yin.k/pending])
            classes (assoc (zipmap (markers body) (repeat :at-least-once))
                           enrolled :enrolled)
            ;; lower: the successor's first grant
            r (handoff/resume-task
                (s/new-machine :semantic {:attach-stream (s/attacher t)})
                (:bytes export) (s/attacher t)
                {:address (:address export)
                 :exclusive true
                 :protection classes
                 :grant {:checkpoint (:address export)
                         :dao.lease/lease "lease-2"
                         :dao.lease/holder "holder-b"
                         :tenure {:now 10 :bound 20 :live true}
                         :evidence {:yin.k/status :yin.k/ready
                                    :yin.k/binding {:yin.k/occurrence successor
                                                    :dao.lease/lease "lease-2"
                                                    :dao.lease/holder "holder-b"
                                                    :yin.k/epoch 0
                                                    :yin.k/transaction
                                                    {:yin.k/arbitration arb}}
                                    :yin.k/enrolled #{enrolled}
                                    :yin.k/prefix {:yin.k/occurrence successor
                                                   :dao.lease/lease "lease-2"
                                                   :yin.k/frontier 0
                                                   :yin.k/inputs []}}}})
            lowered (:vm r)
            retry (writer/emit lowered (recording sent))
            [first-send resend] @sent]
        (is (= {:yin.k/occurrence occ :yin.k/seq 0} id)
            "the writer assigned the id at the park")
        (is (= 1 (count (:appended emitted))))
        (is (= :ok (:status export)) (pr-str export))
        (is (= reason (:yin.k/reason pending)))
        (is (= id (:yin.k/op-id pending)) "the id rides the bytes unchanged")
        (is (= :ok (:status r)) (pr-str r))
        (is (= id (:op-id (first (:wait-set lowered))))
            "the lower restores the id on the retained write")
        (is (= 1 (get-in lowered [:yin.k/custody :yin.k/next-op-seq]))
            "the counter is restored exactly")
        (is (= 1 (count (:appended retry))))
        (is (= 1 (get-in (:machine retry) [:yin.k/custody :yin.k/next-op-seq]))
            "a carried id takes no new sequence")
        (testing "the retry is fenced: the same id and value under the new tenure"
          (is (= :yin.k/admit (:yin.k/request resend)))
          (is (= enrolled (:yin.k/target resend)))
          (is (= id (get-in resend [:yin.k/fenced-envelope :yin.k/op-id])))
          (is (= "lease-2" (get-in resend [:yin.k/fenced-envelope
                                           :yin.k/incarnation])))
          (is (= 0 (get-in resend [:yin.k/fenced-envelope :yin.k/epoch])))
          (is (= (get-in first-send [:yin.k/fenced-envelope :yin.k/value])
                 (get-in resend [:yin.k/fenced-envelope :yin.k/value]))
              "the write itself is the one the park retained"))))))


;; =============================================================================
;; Row 2's fencing half: a retained id across exporting and the carrier
;; =============================================================================

(deftest an-id-retained-before-export-rides-the-successor-unchanged
  (let [w (world)
        cp (checkpoint! w)
        _ (grant! w "lease-1")
        [r1 _] (lower w cp "lease-1" 0 (prefix "lease-1" []))
        [run1 _] (read-live w cp (:vm r1))
        ;; the first append's acceptance is unknown: the id is retained
        e1 (writer/emit run1 (dropping w))
        held (:machine e1)
        id (:op-id (write-entry held))
        entered (export/enter held)
        exporting (:machine entered)
        sent (atom 0)
        counting (fn [r] (swap! sent inc) ((appender w) r))
        after-emit (writer/emit exporting counting)
        after-read (reader/step exporting counting (observer (:peer cp)))
        t2 (s/toy)
        header {:yin.k/occurrence successor
                :yin.k/arbitration arbitration
                :yin.k/next-op-seq (get-in held [:yin.k/custody :yin.k/next-op-seq])
                :yin.k/enrolled #{(:target w)}
                :yin.k/origin {:yin.k/occurrence occ
                               :dao.lease/lease "lease-1"
                               :yin.k/emitter "holder-a"}}
        prepared (export/prepare exporting (:record entered)
                                 (same-id-server (s/served-peer t2) (:channel t2))
                                 header)
        ;; a failed carrier append retries from the retained record
        first-try (export/encode exporting (:record prepared))
        retry (export/encode exporting (:record prepared))
        pending (get-in first-try [:body :yin.k/frames 0 :yin.k/pending])]
    (is (= {:yin.k/occurrence occ :yin.k/seq 0} id))
    (is (= 1 (:yin.k/next-op-seq header)) "the counter moved once, past the id")
    (is (= :ok (:status entered)) (pr-str entered))
    (testing "exporting: neither the writer nor the reader appends"
      (is (empty? (:appended after-emit)))
      (is (empty? (:appended after-read)))
      (is (zero? @sent))
      (is (empty? (read-all (:reader w))) "no source effect reached the target"))
    (testing "the successor carries the id across every carrier retry"
      (is (= :ok (:status prepared)) (pr-str prepared))
      (is (= :ok (:status first-try)) (pr-str first-try))
      (is (= (:address first-try) (:address retry))
          "one record, one occurrence, equal bytes: the address is their digest")
      (is (= successor (get-in first-try [:body :yin.k/occurrence])))
      (is (= :put (:yin.k/reason pending)))
      (is (= id (:yin.k/op-id pending)) "the retained id, unchanged")
      (is (= 1 (get-in first-try [:body :yin.k/next-op-seq]))))))
