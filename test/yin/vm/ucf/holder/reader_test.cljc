(ns yin.vm.ucf.holder.reader-test
  "D12: the recorded reader and replay (UCF 7.7.5, 7.7.7, 7.7.8; r3 1.4;
   linker-dht 14.2.2 and 14.2.4 row 5).  Every machine is parked by the
real engine; every input record is the real authority's, reached through
the real front over the memory substrate.  The composition the reader
owes is supplied as functions: the input appender and the handle
observer attribute and observe the way the composition's resolver would."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [dao.stream.journal :as journal]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.module :as module]
            [yin.vm.test-utils :as tu]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.front :as front]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.authority.input :as input]
            [yin.vm.ucf.checkpoint-fixtures :as fx]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.holder.reader :as reader]
            [yin.vm.ucf.holder.writer :as writer]
            [yin.vm.ucf.lift-support :as s]))


(def ^:private arb "arb-d12")
(def ^:private occ "0c000000-0000-4000-8000-000000000001")

(def ^:private duration {:s 30})


;; =============================================================================
;; The world: a real authority, front and streams, as the writer's
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
enrolled target, lease-1 granted to holder-a at epoch 0 directly, and a
holder's front over rings, attributing the inbound to holder-a."
  []
  (let [frames (atom [(cbor/encode {:dao.stream.journal/header
                                    {:version 1 :identity arb}})])
        a (::authority/authority (authority/open! (journal/memory-backend
                                                    frames nil)
                                                  nil))
        bs (cbor/encode (park occ 0))
        store (mem/create-content-mem)
        in (ring 8)
        out (ring 8)
        diag (ring 8)
        medium (log)
        _ (grant/offer! a store (fx/segment-address bs) bs "carrier")
        _ (stream/append! (grant/writer a)
                          (lease/grant "lease-1" (custody/subject occ)
                                       "holder-a" duration
                                       {:dao.lease/proposal "p-1"}))
        i (:yin.k/target (authority/enroll! a))]
    {:frames frames
     :a a
     :in in
     :out out
     :diag diag
     :medium medium
     :target i
     :reader (authority/target-reader a i)
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


(defn- answer!
  "Step the world's front past up to `n` requests."
  [w n]
  (swap! (:front w) front/step n)
  w)


(defn- reader-over
  "A composition-supplied reader over handle `h`: the next [author record]
until the tail, attributing the stream to `author`."
  [h author]
  (let [c (atom (oldest h))]
    (fn []
      (let [r (stream/next h @c)]
        (when (= :dao.stream/ok (:dao.stream/outcome r))
          (reset! c (:dao.stream/cursor r))
          [author (:dao.stream/value r)])))))


(defn- ack-reader
  [w]
  (reader-over (:out w) arb))


;; =============================================================================
;; Machines under custody
;; =============================================================================
(def ^:private gate-opts
  "The park builders the engine's own kernels use, for fixtures that park
through `handle-effect` rather than a run."
  {:park-entry-fns
   {:stream/put (fn [_s _e r] {:reason :put, :stream-id (:stream-id r)}),
    :stream/next (fn [_s _e r]
                   {:reason :next,
                    :cursor-ref (:cursor-ref r),
                    :stream-id (:stream-id r)})}})


(defn- counting-observe
  "[observe! calls] over one counting handle observer: the composition's
default dispatch, counting every observation it makes."
  []
  (let [n (atom 0)]
    [(fn [handle op arg]
       (swap! n inc)
       (case op
         :next (stream/next handle arg)
         :cursor (stream/cursor handle arg)))
     n]))


(defn- counting-proxy
  "A handle over `h` counting every protocol call that reaches it."
  [h calls]
  (reify
    stream/IDaoStreamDescriptor
    (descriptor [_] (stream/descriptor h))


    stream/IDaoStreamReader

    (cursor [_ anchor] (swap! calls inc) (stream/cursor h anchor))

    (next [_ cursor] (swap! calls inc) (stream/next h cursor))


    stream/IDaoStreamWriter

    (append! [_ v] (swap! calls inc) (stream/append! h v))


    stream/IDaoStreamClosable

    (close! [_] (swap! calls inc) (stream/close! h))))


(defn- cursor-over
  "A machine over `handle` whose cursor cell is minted (ungated, so a
real one) at the stream's current oldest.  [cursor-ref machine]."
  [handle]
  (let [m0 (s/new-machine)
        [sref m1] (engine/attach-resource m0 handle)
        [cref m2] (engine/handle-cursor m1 {:stream sref} :k1)]
    [cref m2]))


(defn- gated-read-then-write
  "A real gated machine that parks on one :next of `source` and, once the
read wakes, parks on a put of the value it read to `target`.  Built
before any later eviction, so its cell sits at the oldest of build
time.  [cursor-ref machine]."
  [source target]
  (let [m0 (s/load-ast (s/new-machine)
                       (s/let1 'v {:type :stream/next, :source (s/v 'c)}
                               {:type :stream/put, :target (s/v 'w), :val (s/v 'v)}))
        [sref m1] (engine/attach-resource m0 source)
        [cref m2] (engine/handle-cursor m1 {:stream sref} :k1)
        [wref m3] (engine/attach-resource m2 target)]
    [cref (vm/run (assoc m3 :store {'c cref, 'w wref} :yin.k/gate :running))]))


(defn- gated-poll
  "A real gated machine parked on one poll of `handle`, as an :observe
entry, through a cursor cell minted at the stream's oldest at build
time.  [cursor-ref machine]."
  [handle]
  (let [[cref m] (cursor-over handle)
        r (engine/handle-effect
            (assoc m :yin.k/gate :running)
            (module/make-effect :stream/poll {:cursor cref})
            gate-opts)]
    [cref (:state r)]))


(defn- waiter-machine
  "A gated machine whose wait set is one hand-built :next waiter per tag
over one cursor cell of `handle` -- the entries the engine's own park
builder leaves, for selection among waiters sharing a cell.
[machine cursor-ref]."
  [handle & tags]
  (let [[cref m] (cursor-over handle)]
    [(assoc m :blocked? true
            :yin.k/gate :running
            :wait-set (mapv (fn [t] {:reason :next, :cursor-ref cref, :k t, :env {}})
                            tags))
     cref]))


(defn- ffi-reader
  "A real gated machine parked as the FFI response reader of one
`dao.stream.apply/call` over `call-in`/`call-out`.  [machine call-id]."
  [call-in call-out]
  (let [m0 (s/new-machine {:call-in call-in
                           :call-out call-out
                           :call-out-cursor (vm/mint-oldest call-out :test)})
        loaded (s/load-ast m0
                           {:type :dao.stream.apply/call
                            :op :op/echo
                            :operands [(s/lit "hello")]})
        parked (vm/run (assoc loaded :yin.k/gate :running))
        entry (first (:wait-set parked))
        call (ffi/request-call-id entry)]
    [(vm/run (engine/apply-ffi-sent parked call)) call]))


(defn- install-entry
  "The real 'host.mod install entry of a parked installer, its child
replaced by `child`."
  [child]
  (assoc (get (:installs (s/parked-installer)) 'host.mod)
         :vm (assoc child :yin.k/gate :running)))


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


(defn- prefix-custody
  "Custody carrying `records`, the replay prefix, one map per k from 0."
  ([records] (prefix-custody records {}))
  ([records over]
   (merge {:yin.k/occurrence occ
           :dao.lease/lease "lease-1"
           :yin.k/epoch 0
           :yin.k/arbitration {:dao.stream/identity arb}
           :yin.k/next-op-seq 0
           :protection {}
           :input {:next 0
                   :prefix {:yin.k/frontier (count records)
                            :yin.k/inputs (mapv (fn [k r] (assoc r :yin.k/input-seq k))
                                                (range) records)}}
           :tenure {:bound 100}}
          over)))


;; =============================================================================
;; The source shapes, as the records carry them
;; =============================================================================
(defn- read-source
  [op task stream position]
  {:yin.k/kind :yin.k/read
   :yin.k/name {:yin.k/op op
                :yin.k/task task
                :yin.k/stream stream
                :yin.k/position position}})


(defn- cursor-source
  [task stream origin]
  {:yin.k/kind :yin.k/read
   :yin.k/name {:yin.k/op :cursor :yin.k/task task
                :yin.k/stream stream :yin.k/origin origin}})


(defn- ffi-source
  [task call-id]
  {:yin.k/kind :yin.k/ffi-result
   :yin.k/name {:yin.k/op :ffi-result :yin.k/task task :yin.k/call-id call-id}})


(defn- link-source
  [task link-id]
  {:yin.k/kind :yin.k/link-result
   :yin.k/name {:yin.k/op :link-result :yin.k/task task :yin.k/link-id link-id}})


;; =============================================================================
;; Nothing is applied before its acknowledgment
;; =============================================================================
(deftest nothing-is-applied-before-its-acknowledgment-test

  (let [h (ring 8)
        _ (stream/append! h "A")
        [cref parked] (gated-poll h)
        position (get-in parked [:resources (:id cref) :cursor])
        observed (stream/next h position)
        w (world)
        m (assoc parked :yin.k/custody (custody-for (:target w)))
        [observe! calls] (counting-observe)
        r1 (reader/step m (appender w) observe!)]
    (testing "one observation, one request, nothing applied"
      (is (= 1 @calls) "exactly one live observation")
      (is (= 1 (count (:appended r1))))
      (is (= :dao.stream/ok
             (:dao.stream/outcome (:append (first (:appended r1))))))
      (is (empty? (:ready-queue (:machine r1)))
          "the entry is not applied before the acknowledgment")
      (is (= [:observe] (mapv :reason (:wait-set (:machine r1))))
          "it still waits")
      (is (= 0 (get-in (:machine r1) [:yin.k/custody :input :next]))
          "k has not advanced"))
    (testing "the request is the input protocol's, with the source inside :yin.k/name"
      (is (= {:yin.k/request :yin.k/input
              :yin.k/request-id [:yin.k/input "lease-1" 0]
              :yin.k/input {:yin.k/occurrence occ
                            :dao.lease/lease "lease-1"
                            :yin.k/epoch 0
                            :yin.k/input-seq 0
                            :yin.k/source (read-source :poll []
                                                       (stream-id h)
                                                       position)
                            :yin.k/observed observed}}
             (:request (first (:appended r1))))
          "no cell id, host cursor or sealed reference anywhere"))
    (testing "the held observation rides the entry in state 2"
      (is (= {:yin.k/input-seq 0
              :yin.k/source (read-source :poll [] (stream-id h) position)
              :yin.k/observed observed
              :yin.k/state :requested}
             (get-in (:machine r1) [:wait-set 0 :yin.k/held]))))
    (testing "a record that is not the acknowledgment applies nothing"
      (let [mallory (reader/settle (:machine r1) "mallory"
                                   {:yin.k/reply :yin.k/input
                                    :yin.k/request-id [:yin.k/input "lease-1" 0]
                                    :yin.k/answer {:yin.k/status :recorded
                                                   :yin.k/input-seq 0}})]
        (is (nil? (:applied mallory)))
        (is (nil? (:run-end mallory)))
        (is (empty? (:ready-queue (:machine mallory))))))
    (testing "the acknowledged observation applies exactly once"
      (answer! w 2)
      (let [d (reader/drain (:machine r1) (ack-reader w))]
        (is (= [0] (mapv :input-seq (:applied d))))
        (is (empty? (:wait-set (:machine d))) "the poll entry left the wait set")
        (is (= ["A"] (mapv :value (:ready-queue (:machine d))))
            "woken with the poll's own value")
        (is (= 1 (get-in (:machine d) [:yin.k/custody :input :next]))
            "k advanced exactly once")
        (is (nil? (some :yin.k/held (concat (:wait-set (:machine d))
                                            (:ready-queue (:machine d)))))
            "the held state is gone (state 4)")
        (testing "a second acknowledgment of the same k retains"
          (let [again (reader/settle (:machine d) arb
                                     {:yin.k/reply :yin.k/input
                                      :yin.k/request-id [:yin.k/input "lease-1" 0]
                                      :yin.k/answer {:yin.k/status :recorded
                                                     :yin.k/input-seq 0}})]
            (is (nil? (:applied again)))))))
    (is (= 1 @calls) "still exactly one live observation across the whole flow")))


;; =============================================================================
;; The held-observation rule: one observation, one k, any number of retries
;; =============================================================================
(deftest a-held-poll-survives-ten-failed-recording-attempts-test

  (let [h (ring 8)
        _ (stream/append! h "A")
        [cref parked] (gated-poll h)
        position (get-in parked [:resources (:id cref) :cursor])
        observed (stream/next h position)
        m (assoc parked :yin.k/custody (prefix-custody []))
        [observe! calls] (counting-observe)
        dropping (fn [_r] {:dao.stream/outcome :dao.stream/transport-error})
        round (fn [machine] (:machine (reader/step machine dropping observe!)))
        final (reduce (fn [machine _n] (round machine)) m (range 10))]
    (is (= 1 @calls) "ten failed recordings, one observation")
    (is (= 1 (count (:wait-set final))) "the entry still waits")
    (is (empty? (:ready-queue final)))
    (is (= 0 (get-in final [:yin.k/custody :input :next])) "one k, never advanced")
    (is (= {:yin.k/input-seq 0
            :yin.k/source (read-source :poll [] (stream-id h) position)
            :yin.k/observed observed
            :yin.k/state :requested}
           (get-in final [:wait-set 0 :yin.k/held]))
        "the exact observation is retained through every failure")
    (testing "the eleventh attempt that lands still sends the same request"
      (let [w (world)
            r (reader/step final (appender w) observe!)]
        (is (= 1 @calls) "the observation was not repeated")
        (is (= {:yin.k/input-seq 0
                :yin.k/source (read-source :poll [] (stream-id h) position)
                :yin.k/observed observed
                :yin.k/state :requested}
               (get-in (:machine r) [:wait-set 0 :yin.k/held])))))))


(deftest a-full-append-leaves-the-request-unsent-test

  (let [h (ring 8)
        _ (stream/append! h "A")
        [_cref parked] (gated-poll h)
        m (assoc parked :yin.k/custody (prefix-custody []))
        full (s/one-slot-stream "in-full")
        _ (stream/append! full :junk)
        [observe! calls] (counting-observe)
        r (reader/step m (fn [_req] (stream/append! full :x)) observe!)]
    (is (= :dao.stream/full (:dao.stream/outcome (:append (first (:appended r)))))
        "the appender itself answers full")
    (is (= :observed (get-in (:machine r) [:wait-set 0 :yin.k/held :yin.k/state]))
        "state 1: observed, not yet requested")
    (is (= 1 @calls))
    (is (= 0 (get-in (:machine r) [:yin.k/custody :input :next])))))


;; =============================================================================
;; Unknown recording acceptance resends the identical request
;; =============================================================================
(deftest unknown-recording-acceptance-resends-the-identical-request-test

  (let [h (ring 8)
        _ (stream/append! h "A")
        [_cref parked] (gated-poll h)
        w (world)
        m (assoc parked :yin.k/custody (custody-for (:target w)))
        [observe! calls] (counting-observe)
        r1 (reader/step m (appender w) observe!)
        r2 (reader/step (:machine r1) (appender w) observe!)]
    (is (= (:request (first (:appended r1)))
           (:request (first (:appended r2))))
        "the resend is the identical request: source, sequence and value")
    (let [request (:request (first (:appended r1)))]
      (is (= [request request] (read-all (:in w)))
          "two copies landed; the ledger's dedup answers both"))
    (is (= 1 @calls) "the observation was not repeated")
    (testing "the answers acknowledge the one observation once"
      (answer! w 4)
      (let [d (reader/drain (:machine r2) (ack-reader w))]
        (is (= [0] (mapv :input-seq (:applied d))) "applied exactly once")
        (is (= [:no-held] (:retained d)) "the second answer found nothing held")
        (is (empty? (:wait-set (:machine d))))
        (is (= 1 (get-in (:machine d) [:yin.k/custody :input :next])))))))


;; =============================================================================
;; Replay: two waiters on one cell see successive positions
;; =============================================================================
(deftest two-waiters-on-one-cell-replay-successive-positions-test

  (let [h (ring 8)
        _ (stream/append! h "a")
        _ (stream/append! h "b")
        sid (stream-id h)
        c0 (oldest h)
        r1 (stream/next h c0)
        r2 (stream/next h (:dao.stream/cursor r1))
        [m cref] (waiter-machine h :k1 :k2)
        m (assoc m :yin.k/custody
                 (prefix-custody
                   [{:yin.k/source (read-source :next [] sid c0)
                     :yin.k/observed r1}
                    {:yin.k/source (read-source :next [] sid
                                                (:dao.stream/cursor r1))
                     :yin.k/observed r2}]))
        out (reader/replay m)]
    (is (nil? (:unmatched out)) (pr-str (:unmatched out)))
    (is (nil? (:run-end out)))
    (is (= [0 1] (mapv :input-seq (:applied out))) "both records applied")
    (is (= ["a" "b"] (mapv :value (:ready-queue (:machine out))))
        "the first waiter took a, the second b -- not both at the shared cursor")
    (is (empty? (:wait-set (:machine out))))
    (is (= (:dao.stream/cursor r2)
           (get-in (:machine out) [:resources (:id cref) :cursor]))
        "the shared cell advanced twice")
    (is (= 2 (get-in (:machine out) [:yin.k/custody :input :next])))))


(deftest below-the-frontier-the-live-step-observes-nothing-test

  (let [h (ring 8)
        _ (stream/append! h "a")
        [m _] (waiter-machine h :k1)
        m (assoc m :yin.k/custody
                 (prefix-custody
                   [{:yin.k/source (read-source :next [] (stream-id h) (oldest h))
                     :yin.k/observed (stream/next h (oldest h))}]))
        [observe! calls] (counting-observe)
        out (reader/step m (fn [_r] {:dao.stream/outcome :dao.stream/ok})
                         observe!)]
    (is (true? (get out :yin.vm.ucf.holder.reader/replay-pending)))
    (is (zero? @calls))
    (is (empty? (:appended out)))
    (is (= m (:machine out)) "the machine is untouched")))


;; =============================================================================
;; A :poll and a :next at one position do not match each other's records
;; =============================================================================
(deftest a-poll-and-a-next-at-one-position-do-not-match-each-other-test

  (let [h (ring 8)
        _ (stream/append! h "a")
        sid (stream-id h)
        c0 (oldest h)
        r1 (stream/next h c0)
        [m cref] (waiter-machine h :k1)
        next-entry (first (:wait-set m))
        poll-entry {:reason :observe :op :poll :cursor-ref cref :k :kp :env {}}
        ;; the next entry waits first: a record selecting by position alone
        ;; would take it and leave nothing for the second record
        m (assoc m :wait-set [next-entry poll-entry])
        m (assoc m :yin.k/custody
                 (prefix-custody
                   [{:yin.k/source (read-source :poll [] sid c0)
                     :yin.k/observed r1}
                    {:yin.k/source (read-source :next [] sid
                                                (:dao.stream/cursor r1))
                     :yin.k/observed r1}]))
        out (reader/replay m)]
    (is (nil? (:unmatched out)) (pr-str (:unmatched out)))
    (is (= [:poll :next] (mapv :kind (:applied out)))
        "the :poll record selected the observe entry, the :next the reader")
    (is (empty? (:wait-set (:machine out))))
    (is (= ["a" "a"] (mapv :value (:ready-queue (:machine out)))))))


;; =============================================================================
;; Root and child with equal call ids replay to the right task
;; =============================================================================
(deftest root-and-child-with-equal-call-ids-replay-to-the-right-task-test

  (let [call-in (s/one-slot-stream "ci")
        call-out (tu/new-stream 8)
        [root cid] (ffi-reader call-in call-out)
        [child cid2] (ffi-reader call-in call-out)
        _ (is (= cid cid2) "the premise: two machines mint equal call ids")
        _ (stream/append! call-out (apply2/success-response cid "r"))
        _ (stream/append! call-out (apply2/success-response cid "c"))
        c0 (oldest call-out)
        r1 (stream/next call-out c0)
        r2 (stream/next call-out (:dao.stream/cursor r1))
        m (assoc root :installs {'host.mod (install-entry child)})
        m (assoc m :yin.k/custody
                 (prefix-custody
                   [{:yin.k/source (ffi-source [] cid)
                     :yin.k/observed r1}
                    {:yin.k/source (ffi-source ['host.mod] cid2)
                     :yin.k/observed r2}]))
        out (reader/replay m)
        after (:machine out)]
    (is (nil? (:unmatched out)) (pr-str (:unmatched out)))
    (is (= [[] ['host.mod]] (mapv :task (:applied out)))
        "the root's record went to the root, the child's to the child")
    (is (= ["r"] (mapv (comp :dao.stream.apply/ok :value)
                       (:ready-queue after)))
        "the root's reader woke with the root's response")
    (is (= ["c"] (mapv (comp :dao.stream.apply/ok :value)
                       (get-in after [:installs 'host.mod :vm :ready-queue])))
        "the child's reader woke with the child's response")
    (is (empty? (:wait-set after)))
    (is (empty? (get-in after [:installs 'host.mod :vm :wait-set])))))


;; =============================================================================
;; Divergence is not declared while a fenced write awaits its outcome
;; =============================================================================
(deftest divergence-is-not-declared-while-a-fenced-write-awaits-test

  (let [h (ring 8)
        [m _] (waiter-machine h :k1)
        sid (stream-id h)
        write {:reason :put :stream-id ::enrolled
               :datom "v"
               :op-id {:yin.k/occurrence occ :yin.k/seq 0}}
        ;; a record at a position no waiter reads: the divergence trigger
        divergent {:yin.k/source (read-source :next [] sid ::nowhere)
                   :yin.k/observed {:dao.stream/outcome :dao.stream/blocked}}
        with-write (assoc m :wait-set (conj (:wait-set m) write)
                          :yin.k/custody (prefix-custody [divergent]))]
    (testing "the fenced write holds divergence back"
      (let [out (reader/replay with-write)]
        (is (nil? (:run-end out)) "the run does not end")
        (is (= 0 (:unmatched out)) "record 0 matched nothing")
        (is (= :running (vm/gate-mode (:machine out))))
        (is (= with-write (:machine out)) "nothing changed")))
    (testing "an outstanding control request holds it back too"
      (let [bare (assoc m :yin.k/custody (prefix-custody [divergent]))
            out (reader/replay bare {:control-outstanding? true})]
        (is (nil? (:run-end out)))
        (is (= 0 (:unmatched out)))))
    (testing "quiescent with no match, the divergence ends the run"
      (let [bare (assoc m :yin.k/custody (prefix-custody [divergent]))
            out (reader/replay bare)]
        (is (= {:cause :divergence
                :yin.k/input-seq 0
                :yin.k/source (read-source :next [] sid ::nowhere)}
               (:run-end out)))
        (is (= :ended (vm/gate-mode (:machine out))))
        (is (= 0 (get-in (:machine out) [:yin.k/custody :input :next]))
            "the divergent record advanced nothing")))))


;; =============================================================================
;; An input conflict ends the run and quarantines nothing
;; =============================================================================
(deftest an-input-conflict-ends-the-run-with-no-quarantine-test

  (let [h (ring 8)
        [_cref parked] (gated-poll h)
        w (world)
        ;; a previous process's different record at k 0, through the front
        ;; under its own holder-chosen request id (opaque to the front)
        _ (stream/append! (:in w)
                          {:yin.k/request :yin.k/input
                           :yin.k/request-id "r-prev"
                           :yin.k/input (input/request
                                          occ "lease-1" 0 0
                                          (read-source :poll [] (stream-id h) ::other)
                                          {:dao.stream/outcome :dao.stream/ok
                                           :dao.stream/value ::other
                                           :dao.stream/cursor ::c})})
        _ (answer! w 2)
        _ (is (= :recorded
                 (-> (read-all (:out w)) first :yin.k/answer :yin.k/status)))
        m (assoc parked :yin.k/custody (custody-for (:target w)))
        [observe! _calls] (counting-observe)
        r (reader/step m (appender w) observe!)
        _ (answer! w 2)
        reply (last (read-all (:out w)))
        d (reader/drain (:machine r) (ack-reader w))]
    (is (= :input-conflict (get-in reply [:yin.k/answer :yin.k/reason])))
    (is (= [:request-id] (:retained d))
        "the earlier record's own reply correlates nothing")
    (is (= {:cause :input-conflict :yin.k/input-seq 0}
           (select-keys (:run-end d) [:cause :yin.k/input-seq])))
    (is (= :input-conflict
           (:yin.k/reason (get-in reply [:yin.k/answer]))))
    (is (not= :intent-conflict (:cause (:run-end d)))
        "an input conflict is never translated to :intent-conflict")
    (is (= :ended (vm/gate-mode (:machine d))))
    (is (nil? (get-in (authority/projection (:a w))
                      [:occurrences occ :yin.k/quarantined]))
        "the conflict quarantined nothing")
    (is (nil? (get-in (authority/projection (:a w))
                      [:occurrences occ :yin.k/closed]))
        "and completed nothing")
    (is (= 1 (count (:wait-set (:machine d))))
        "the held observation is retained, never applied")
    (is (empty? (:ready-queue (:machine d))))
    (is (= 0 (get-in (:machine d) [:yin.k/custody :input :next])))
    (testing "a late acknowledgment for the ended run reaches state 3 and no apply"
      (let [ack {:yin.k/reply :yin.k/input
                 :yin.k/request-id [:yin.k/input "lease-1" 0]
                 :yin.k/answer {:yin.k/status :recorded :yin.k/input-seq 0}}
            late (reader/settle (:machine d) arb ack)]
        (is (nil? (:applied late)))
        (is (= :not-running (::reader/retain late)))
        (is (= :acknowledged
               (get-in (:machine late) [:wait-set 0 :yin.k/held :yin.k/state]))
            "state 3: acknowledged, awaiting an application that cannot come")))))


;; =============================================================================
;; An evicted value: recovery with records, without, and divergent (14.2.4 row 5)
;; =============================================================================
(deftest an-evicted-value-recovers-with-records-and-without-test

  (let [source (ring 8)
        w (world)
        target (:reader w)
        _ (stream/append! source "A")
        p0 (oldest source)
        ;; every tenure's machine is built here, before the eviction, so
        ;; each one's cursor cell sits at the position tenure 1 read
        [_cref m1] (gated-read-then-write source target)
        [_cref2 m2] (gated-read-then-write source target)
        [_cref3 m3] (gated-read-then-write source target)
        [_cref4 m4] (gated-read-then-write source target)
        ;; one reply reader with one cursor, as the composition keeps:
        ;; re-reading the reply stream from the oldest would re-drain
        ;; tenure 1's answers against later machines
        replies (reader-over (:out w) arb)
        ;; tenure 1: record the read, write what it read, commit
        step1 (reader/step (assoc m1 :yin.k/custody (custody-for (:target w)))
                           (appender w) (first (counting-observe)))
        _ (answer! w 2)
        d1 (reader/drain (:machine step1) replies)
        run1 (vm/run (:machine d1))
        _ (is (= [:put] (mapv :reason (:wait-set run1)))
              "the read woke and the write parked")
        e1 (writer/emit run1 (appender w))
        _ (answer! w 4)
        dw1 (writer/drain (:machine e1) replies)
        _ (is (= [:committed] (mapv :admission (:discharged dw1)))
              "tenure 1 committed its write")
        recorded-input (get-in (first (:appended step1)) [:request :yin.k/input])
        ;; the crash, then the eviction of everything tenure 1 read
        _ (dotimes [_ 9] (stream/append! source :junk))
        _ (is (= :dao.stream/gap
                 (:dao.stream/outcome (stream/next source p0)))
              "the kept position was evicted: a live re-read answers gap")]
    (testing "with durable inputs, replay reproduces the old intent and result"
      (let [m2 (assoc m2 :yin.k/custody
                      (custody-for (:target w)
                                   {:input {:next 0
                                            :prefix {:yin.k/frontier 1
                                                     :yin.k/inputs
                                                     [{:yin.k/input-seq 0
                                                       :yin.k/source (:yin.k/source recorded-input)
                                                       :yin.k/observed (:yin.k/observed recorded-input)}]}}}))
            out (reader/replay m2)
            _ (is (nil? (:unmatched out)) (pr-str (:unmatched out)))
            run2 (vm/run (:machine out))
            _ (is (= [:put] (mapv :reason (:wait-set run2)))
                  "the replayed read woke and the same write parked")
            e2 (writer/emit run2 (appender w))
            _ (answer! w 4)
            dw2 (writer/drain (:machine e2) replies)]
        (is (= [0] (mapv :input-seq (:applied out))))
        (is (= ["A"] (mapv :value (:ready-queue (:machine out))))
            "replay delivered the old value, not the live gap")
        (is (= [:replayed] (mapv :admission (:discharged dw2)))
            "the re-executed write reproduced the old intent at the same id")
        (is (= ["A"] (read-all (:reader w))))))
    (testing "without usable evidence, the reader fails closed"
      (let [m3 (assoc m3 :yin.k/custody
                      (custody-for (:target w)
                                   {:input {:next 0
                                            :prefix {:yin.k/status :suspended
                                                     :yin.k/reason :unavailable}}}))
            [observe! calls] (counting-observe)
            out (reader/step m3 (appender w) observe!)]
        (is (= {:yin.k/status :yin.k/unsatisfied :yin.k/reason :no-prefix}
               (:unsatisfied out)))
        (is (zero? @calls) "nothing was observed live")
        (is (empty? (:appended out)))
        (is (= m3 (:machine out)) "the machine did not move")
        (is (= [:next] (mapv :reason (:wait-set (:machine out))))
            "the read still waits: no write exists to emit")))
    (testing "a divergent re-read at the same id is intent-conflict, no commit"
      ;; the holder that recovers without its records and re-reads live
      ;; sees the gap; the test authors that re-read (the writer's own
      ;; row-5 fixture discipline) and the real authority judges it
      (let [gap (stream/next source p0)
            _ (is (= :dao.stream/gap (:dao.stream/outcome gap)))
            entry (first (:wait-set m4))
            diverged (engine/apply-next m4 entry gap)
            run4 (vm/run diverged)
            _ (is (= [:put] (mapv :reason (:wait-set run4))))
            e4 (writer/emit (assoc run4 :yin.k/custody (custody-for (:target w)))
                            (appender w))
            _ (answer! w 4)
            dw4 (writer/drain (:machine e4) replies)]
        (is (= {:cause :intent-conflict
                :op-id {:yin.k/occurrence occ :yin.k/seq 0}}
               (:run-end dw4))
            "the divergent re-read at the same id is intent-conflict")
        (is (= ["A"] (read-all (:reader w)))
            "the divergent write committed nothing")))))


;; =============================================================================
;; Cursor replay without live minting (residual 1)
;; =============================================================================
(deftest cursor-replay-applies-the-recorded-position-without-minting-test

  (let [rh (ring 8)
        _ (stream/append! rh "A")
        p0 (oldest rh)
        _ (dotimes [_ 8] (stream/append! rh :junk))
        _ (is (not= p0 (oldest rh)) "the live oldest moved past the record")
        sid (stream-id rh)
        calls (atom 0)
        h (counting-proxy rh calls)
        m0 (s/new-machine)
        [sref m1] (engine/attach-resource m0 h)
        gated (assoc m1 :yin.k/gate :running)
        cursor-r (engine/handle-effect
                   gated
                   (module/make-effect :stream/cursor {:stream sref})
                   {})
        cref (:value cursor-r)
        m (:state cursor-r)
        cell-id (:id cref)
        _ (is (map? (get-in m [:resources cell-id :yin.k/unminted]))
              "the gate left the cell unminted")
        m (assoc m :yin.k/custody
                 (prefix-custody
                   [{:yin.k/source (cursor-source [] sid :dao.stream/oldest)
                     :yin.k/observed {:dao.stream/outcome :dao.stream/ok
                                      :dao.stream/cursor p0}}]))
        out (reader/replay m)]
    (is (zero? @calls) "replay minted nothing: zero calls on the handle")
    (is (= (vm/cursor-entry (:id sref) p0)
           (get-in (:machine out) [:resources cell-id]))
        "the cell holds the recorded position, not the live oldest")
    (is (= 1 (get-in (:machine out) [:yin.k/custody :input :next])))
    (is (= [0] (mapv :input-seq (:applied out))))
    (testing "a read through a recorded mint observes from the recorded position"
      ;; a second stream whose recorded position is still live
      (let [rh2 (ring 8)
            _ (stream/append! rh2 "A")
            q0 (oldest rh2)
            m0 (s/new-machine)
            [sref2 m1] (engine/attach-resource m0 rh2)
            cursor-r (engine/handle-effect
                       (assoc m1 :yin.k/gate :running)
                       (module/make-effect :stream/cursor {:stream sref2})
                       {})
            cref2 (:value cursor-r)
            minted (:state cursor-r)
            minted (assoc minted :yin.k/custody
                          (prefix-custody
                            [{:yin.k/source (cursor-source [] (stream-id rh2)
                                                           :dao.stream/oldest)
                              :yin.k/observed {:dao.stream/outcome :dao.stream/ok
                                               :dao.stream/cursor q0}}]))
            replayed (reader/replay minted)
            read-r (engine/handle-effect (:machine replayed)
                                         (module/make-effect :stream/next
                                                             {:cursor cref2})
                                         gate-opts)
            parked (:state read-r)]
        (is (= :next (:reason (first (:wait-set parked)))))
        (let [[observe! ocalls] (counting-observe)
              live (reader/step parked
                                (fn [_r] {:dao.stream/outcome :dao.stream/ok})
                                observe!)]
          (is (= 1 @ocalls) "exactly the one live read")
          (is (= q0 (get-in live [:observation :source :yin.k/name :yin.k/position]))
              "the read observes at the recorded position")
          (is (= "A" (get-in live [:observation :observed :dao.stream/value]))))))))


(deftest a-live-mint-is-a-recorded-cursor-observation-test

  (let [h (ring 8)
        sid (stream-id h)
        m0 (s/new-machine)
        [sref m1] (engine/attach-resource m0 h)
        gated (assoc m1 :yin.k/gate :running)
        cursor-r (engine/handle-effect
                   gated
                   (module/make-effect :stream/cursor {:stream sref})
                   {})
        cref (:value cursor-r)
        m (:state cursor-r)
        w (world)
        m (assoc m :yin.k/custody (custody-for (:target w)))
        [observe! calls] (counting-observe)
        r (reader/step m (appender w) observe!)
        minted (:dao.stream/cursor (stream/cursor h :dao.stream/oldest))]
    (is (= 1 @calls) "one mint")
    (is (= {:yin.k/op :cursor :yin.k/task []
            :yin.k/stream sid :yin.k/origin :dao.stream/oldest}
           (get-in (first (:appended r))
                   [:request :yin.k/input :yin.k/source :yin.k/name]))
        "the source carries the origin and no position")
    (is (= {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor minted}
           (get-in (first (:appended r)) [:request :yin.k/input :yin.k/observed])))
    (is (map? (get-in (:machine r) [:resources (:id cref) :yin.k/unminted]))
        "the cell stays unminted until the acknowledgment")
    (answer! w 2)
    (let [d (reader/drain (:machine r) (ack-reader w))]
      (is (= (vm/cursor-entry (:id sref) minted)
             (get-in (:machine d) [:resources (:id cref)]))
          "the acknowledged mint applied the recorded position")
      (is (= 1 (get-in (:machine d) [:yin.k/custody :input :next]))))))


(deftest a-link-cursor-mint-is-recorded-at-newest-and-replays-test

  (let [response-h (ring 8)
        calls (atom 0)
        response (counting-proxy response-h calls)
        m0 (vm/run (-> (s/new-machine {:link-request (ring 8)
                                       :link-response response})
                       (s/load-ast (s/app (s/v 'require) (s/lit 'host.mod)))
                       (assoc :yin.k/gate :running)))
        response-id (stream-id response-h)
        newest (:dao.stream/cursor (stream/cursor response-h :dao.stream/newest))
        w (world)
        live-m (assoc m0 :yin.k/custody (custody-for (:target w)))
        [observe! ocalls] (counting-observe)
        r (reader/step live-m (appender w) observe!)]
    (testing "live: the mint is a :cursor observation at :dao.stream/newest"
      (is (= 1 @ocalls))
      (is (= {:yin.k/op :cursor :yin.k/task []
              :yin.k/stream response-id :yin.k/origin :dao.stream/newest}
             (get-in (first (:appended r))
                     [:request :yin.k/input :yin.k/source :yin.k/name])))
      (is (= {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor newest}
             (get-in (first (:appended r))
                     [:request :yin.k/input :yin.k/observed])))
      (answer! w 2)
      (let [d (reader/drain (:machine r) (ack-reader w))]
        (is (= newest (:cursor (first (:wait-set (:machine d)))))
            "the acknowledged mint installed the recorded position")
        (is (= :link-request (:reason (first (:wait-set (:machine d))))))))
    (testing "replay: the recorded position applies without a live mint"
      (let [m2 (assoc m0 :yin.k/custody
                      (prefix-custody
                        [{:yin.k/source (cursor-source [] response-id
                                                       :dao.stream/newest)
                          :yin.k/observed {:dao.stream/outcome :dao.stream/ok
                                           :dao.stream/cursor newest}}]))
            before @calls
            out (reader/replay m2)]
        (is (= before @calls) "replay made no call on the link response stream")
        (is (= newest (:cursor (first (:wait-set (:machine out))))))
        (is (= :link-request (:reason (first (:wait-set (:machine out)))))
            "the entry still waits for its request, envelope verbatim")
        (is (= 1 (get-in (:machine out) [:yin.k/custody :input :next])))))))


(deftest a-link-result-read-replays-to-its-own-entry-test

  ;; a recorded read of another id's response advances the kept cursor
  ;; and keeps the entry waiting -- the driver reads past traffic it
  ;; does not own, exactly as the ungated scan does
  (let [response-h (ring 8)
        m0 (vm/run (-> (s/new-machine {:link-request (ring 8)
                                       :link-response response-h})
                       (s/load-ast (s/app (s/v 'require) (s/lit 'host.mod)))
                       (assoc :yin.k/gate :running)))
        entry (first (:wait-set m0))
        lid (:link-id entry)
        sent (engine/apply-link-sent
               (engine/apply-link-cursor m0 lid (oldest response-h))
               lid)
        _ (is (= :link-response (:reason (first (:wait-set sent))))
              "the entry reads its response stream through its kept cursor")
        _ (stream/append! response-h {:yin.link/id ::other, :status :ok})
        observed (stream/next response-h (:cursor (first (:wait-set sent))))
        _ (is (= :dao.stream/ok (:dao.stream/outcome observed)))
        out (reader/replay (assoc sent :yin.k/custody
                                  (prefix-custody
                                    [{:yin.k/source (link-source [] lid)
                                      :yin.k/observed observed}])))]
    (is (nil? (:unmatched out)) (pr-str (:unmatched out)))
    (is (= [:link-read] (mapv :kind (:applied out)))
        "the record's source is the link-result source of its own id")
    (is (= [:link-response] (mapv :reason (:wait-set (:machine out))))
        "another id's response keeps the entry waiting")
    (is (= (:dao.stream/cursor observed)
           (:cursor (first (:wait-set (:machine out)))))
        "the kept cursor advanced past the skipped response")
    (is (= 1 (get-in (:machine out) [:yin.k/custody :input :next])))))


;; =============================================================================
;; A read on a stream with a close pending follows the close
;; =============================================================================
(deftest a-read-defers-to-a-close-pending-on-its-stream-test

  (let [h (ring 8)
        _ (stream/append! h "A")
        [_cref parked] (gated-poll h)
        ;; a close the driver's writer has not resolved yet, queued on
        ;; the same stream the poll reads
        with-close (assoc parked :yin.k/closes [{:stream-id (get-in parked
                                                                    [:resources
                                                                     :k1
                                                                     :stream-id])
                                                 :yin.k/issue 0}])
        [observe! calls] (counting-observe)
        sink (fn [_r] {:dao.stream/outcome :dao.stream/ok})
        out (reader/step (assoc with-close :yin.k/custody (prefix-custody []))
                         sink observe!)]
    (is (zero? @calls) "nothing is observed while the close pends")
    (is (empty? (:appended out)))
    (is (= 1 (count (:wait-set (:machine out)))) "the poll still waits")
    (testing "once the writer resolves the close, the read observes"
      (let [out2 (reader/step (assoc (dissoc with-close :yin.k/closes)
                                     :yin.k/custody (prefix-custody []))
                              sink observe!)]
        (is (= 1 @calls) "exactly the one deferred observation")
        (is (= 1 (count (:appended out2))))))))


;; =============================================================================
;; Scan progress: an empty first stream must not starve a later one
;; =============================================================================
(deftest an-empty-first-stream-must-not-starve-a-later-one-test

  (let [empty-h (ring 8)
        ready-h (ring 8)
        _ (stream/append! ready-h "B")
        p1 (oldest empty-h)
        p2 (oldest ready-h)
        blocked-read (stream/next empty-h p1)
        ready-read (stream/next ready-h p2)
        _ (is (= :dao.stream/blocked (:dao.stream/outcome blocked-read)))
        w (world)]
    (testing "live: the acknowledged blocked read advances the scan to the child"
      (let [[root rcref] (waiter-machine empty-h :k1)
            [child _ccref] (waiter-machine ready-h :k2)
            tree (assoc root :installs {'host.mod (install-entry child)})
            m (assoc tree :yin.k/custody (custody-for (:target w)))
            [observe! calls] (counting-observe)
            r1 (reader/step m (appender w) observe!)
            _ (is (= 1 @calls) "the first candidate is observed first")
            ;; one observation in flight: before its acknowledgment a
            ;; second step resends and observes nothing
            r1b (reader/step (:machine r1) (appender w) observe!)
            _ (is (= 1 @calls) "each observation awaits its acknowledgment")
            _ (answer! w 4)
            d1 (reader/drain (:machine r1b) (ack-reader w))
            _ (is (= [0] (mapv :input-seq (:applied d1)))
                  "the blocked read is recorded and applied")
            _ (is (= (read-source :next [] (stream-id empty-h) p1)
                     (get-in (:machine d1) [:yin.k/custody :input :yin.k/scan]))
                  "the scan resumes after the last applied observation")
            r2 (reader/step (:machine d1) (appender w) observe!)
            _ (is (= 2 @calls) "the second step observed the child, not the empty stream again")
            _ (answer! w 4)
            d2 (reader/drain (:machine r2) (ack-reader w))
            m2 (:machine d2)]
        (is (= [['host.mod]] (mapv :task (:applied d2)))
            "the child's read was the second observation")
        (is (= [:next] (mapv :reason (:wait-set m2)))
            "the blocked root waiter still waits, first in its wait set")
        (is (= [:k1] (mapv :k (:wait-set m2))) "no wait entry was reordered")
        (is (= p1 (get-in m2 [:resources (:id rcref) :cursor]))
            "the blocked read advanced nothing")
        (is (= ["B"] (mapv :value (get-in m2 [:installs 'host.mod :vm :ready-queue])))
            "the child's reader woke with its value")
        (is (empty? (get-in m2 [:installs 'host.mod :vm :wait-set])))
        (is (= 2 (get-in m2 [:yin.k/custody :input :next])))))
    (testing "replay reproduces the delivery without live reads"
      (let [ecalls (atom 0)
            rcalls (atom 0)
            eproxy (counting-proxy empty-h ecalls)
            rproxy (counting-proxy ready-h rcalls)
            [root2 rcref2] (waiter-machine eproxy :k1)
            [child2 _ccref2] (waiter-machine rproxy :k2)
            tree2 (assoc root2 :installs {'host.mod (install-entry child2)})
            _ (reset! ecalls 0)
            _ (reset! rcalls 0)
            out (reader/replay
                  (assoc tree2 :yin.k/custody
                         (prefix-custody
                           [{:yin.k/source (read-source :next [] (stream-id empty-h) p1)
                             :yin.k/observed blocked-read}
                            {:yin.k/source (read-source :next ['host.mod]
                                                        (stream-id ready-h) p2)
                             :yin.k/observed ready-read}])))
            m (:machine out)]
        (is (nil? (:unmatched out)) (pr-str (:unmatched out)))
        (is (= [0 1] (mapv :input-seq (:applied out)))
            "replay still applies record k to the first matching source")
        (is (zero? @ecalls) "no live read on the empty stream")
        (is (zero? @rcalls) "no live read on the ready stream")
        (is (= [:next] (mapv :reason (:wait-set m))) "the root waiter still waits")
        (is (= p1 (get-in m [:resources (:id rcref2) :cursor])))
        (is (= ["B"] (mapv :value (get-in m [:installs 'host.mod :vm :ready-queue])))
            "the child's delivery is reproduced")
        (is (= 2 (get-in m [:yin.k/custody :input :next])))
        (is (= (read-source :next ['host.mod] (stream-id ready-h) p2)
               (get-in m [:yin.k/custody :input :yin.k/scan]))
            "replay leaves the scan where the recorded delivery ended")))))


;; =============================================================================
;; Round 3: the scan advances across DISTINCT source groups
;; =============================================================================
(deftest an-alias-group-does-not-capture-the-scan-test

  ;; two waiters on one cursor share one source; an acknowledged blocked
  ;; read on the group must not hand the next observation to the second
  ;; alias -- the distinct stream behind them progresses
  (let [shared (ring 8)
        ready (ring 8)
        _ (stream/append! ready "B")
        p-shared (oldest shared)
        w (world)
        acks (ack-reader w)
        [root _cref] (waiter-machine shared :k1 :k2)
        [child _ccref] (waiter-machine ready :k3)
        tree (assoc root :installs {'host.mod (install-entry child)})
        live (assoc tree :yin.k/custody (custody-for (:target w)))
        [observe! calls] (counting-observe)
        r1 (reader/step live (appender w) observe!)
        _ (is (= 1 @calls))
        _ (answer! w 2)
        d1 (reader/drain (:machine r1) acks)
        r2 (reader/step (:machine d1) (appender w) observe!)
        _ (is (= 2 @calls) "a second observation happened")
        _ (answer! w 2)
        d2 (reader/drain (:machine r2) acks)
        r3 (reader/step (:machine d2) (appender w) observe!)
        _ (is (= 3 @calls))
        _ (answer! w 2)
        d3 (reader/drain (:machine r3) acks)]
    (is (= [[]] (mapv :task (:applied d1)))
        "the alias group's turn: one acknowledged blocked observation")
    (is (= [['host.mod]] (mapv :task (:applied d2)))
        "the next observation is the DISTINCT stream's, not the second alias")
    (is (= ["B"] (mapv :value (get-in (:machine d2)
                                      [:installs 'host.mod :vm :ready-queue])))
        "the distinct stream progressed after one alias-group acknowledgment")
    (is (= [:k1 :k2] (mapv :k (:wait-set (:machine d2))))
        "both aliases still wait, unreordered")
    (is (= p-shared (get-in (:machine d2)
                            [:resources (:id _cref) :cursor]))
        "the shared cell did not advance: the blocked read was the only one")
    (is (= [[]] (mapv :task (:applied d3)))
        "the rotation returns to the alias group's turn, not to it again mid-round")
    (is (= 3 (get-in (:machine d3) [:yin.k/custody :input :next])))))


(deftest blocked-then-successful-delivery-selects-the-same-continuation-live-and-replay-test

  ;; two waiters on one cursor: the live scan and replay's first
  ;; matching source must wake the same continuation
  (let [h (ring 8)
        p0 (oldest h)
        blocked-read (stream/next h p0)
        _ (is (= :dao.stream/blocked (:dao.stream/outcome blocked-read)))
        w (world)
        acks (ack-reader w)]
    (testing "live: the group's first waiter is the one observed again"
      (let [[m cref] (waiter-machine h :k1 :k2)
            live (assoc m :yin.k/custody (custody-for (:target w)))
            [observe! _calls] (counting-observe)
            r1 (reader/step live (appender w) observe!)
            _ (is (= :dao.stream/blocked
                     (get-in r1 [:observation :observed :dao.stream/outcome])))
            _ (answer! w 2)
            d1 (reader/drain (:machine r1) acks)
            _ (is (= [:k1 :k2] (mapv :k (:wait-set (:machine d1))))
                  "the blocked observation retained both aliases")
            ;; the value arrives after the blocked observation
            _ (stream/append! h "v")
            r2 (reader/step (:machine d1) (appender w) observe!)
            _ (is (= :dao.stream/ok
                     (get-in r2 [:observation :observed :dao.stream/outcome])))
            p1 (get-in r2 [:observation :observed :dao.stream/cursor])
            _ (answer! w 2)
            d2 (reader/drain (:machine r2) acks)
            m2 (:machine d2)]
        (is (= [:k1] (mapv :k (:ready-queue m2)))
            "the FIRST alias woke: the scan selects the first candidate of
the group, exactly the one replay's first match selects")
        (is (= [:k2] (mapv :k (:wait-set m2))))
        (is (= p1 (get-in m2 [:resources (:id cref) :cursor])))))
    (testing "replay: the record's first matching source is the same continuation"
      (let [ok-read (stream/next h p0)
            _ (is (= :dao.stream/ok (:dao.stream/outcome ok-read)))
            [m2 _cref] (waiter-machine h :k1 :k2)
            out (reader/replay
                  (assoc m2 :yin.k/custody
                         (prefix-custody
                           [{:yin.k/source (read-source :next [] (stream-id h) p0)
                             :yin.k/observed blocked-read}
                            {:yin.k/source (read-source :next [] (stream-id h) p0)
                             :yin.k/observed ok-read}])))
            m (:machine out)]
        (is (nil? (:unmatched out)) (pr-str (:unmatched out)))
        (is (= [:k1] (mapv :k (:ready-queue m)))
            "the same continuation as live")
        (is (= [:k2] (mapv :k (:wait-set m))))))))


(deftest equal-source-mint-candidates-form-one-scan-group-test

  ;; two unminted cursor cells on one stream share one :cursor source;
  ;; they are one group, minted first-by-seq across turns
  (let [x (ring 8)
        y (ring 8)
        _ (stream/append! y "B")
        p0 (oldest x)
        w (world)
        acks (ack-reader w)
        build (fn []
                (let [m0 (s/new-machine)
                      [xref m1] (engine/attach-resource m0 x)
                      c1r (engine/handle-effect
                            (assoc m1 :yin.k/gate :running)
                            (module/make-effect :stream/cursor {:stream xref})
                            {})
                      c2r (engine/handle-effect
                            (:state c1r)
                            (module/make-effect :stream/cursor {:stream xref})
                            {})
                      [yref m2] (engine/attach-resource (:state c2r) y)
                      [cref m3] (engine/handle-cursor m2 {:stream yref} :ky)]
                  {:c1 (:value c1r) :c2 (:value c2r) :xref xref
                   :machine (assoc m3 :blocked? true
                                   :wait-set [{:reason :next
                                               :cursor-ref cref
                                               :k :ky
                                               :env {}}])}))]
    (testing "live: first cell by seq, then the distinct group, then the second cell"
      (let [{:keys [c1 c2 xref machine]} (build)
            [observe! _calls] (counting-observe)
            step-ack (fn [m]
                       (let [r (reader/step m (appender w) observe!)]
                         (answer! w 2)
                         (:machine (reader/drain (:machine r) acks))))
            m1 (step-ack (assoc machine :yin.k/custody (custody-for (:target w))))
            m2 (step-ack m1)
            m3 (step-ack m2)]
        (is (= (vm/cursor-entry (:id xref) p0)
               (get-in m1 [:resources (:id c1)]))
            "the FIRST cell's creation was minted first")
        (is (map? (get-in m1 [:resources (:id c2) :yin.k/unminted]))
            "the second cell still waits: the group's turn is one observation")
        (is (= ["B"] (mapv :value (:ready-queue m2)))
            "the distinct group's reader progressed between the two mints")
        (is (= (vm/cursor-entry (:id xref) p0)
               (get-in m3 [:resources (:id c2)]))
            "the group's next turn minted the second cell")
        (is (= 3 (get-in m3 [:yin.k/custody :input :next])))))
    (testing "a failed first mint moves the scan to the distinct group, not the second cell"
      ;; a fresh world: the ok leg above consumed this occurrence's k 0-2
      (let [w2 (world)
            acks2 (ack-reader w2)
            {:keys [c1 c2 machine]} (build)
            scripted (fn [handle op arg]
                       (if (= op :cursor)
                         {:dao.stream/outcome :dao.stream/closed}
                         (stream/next handle arg)))
            step-ack (fn [m]
                       (let [r (reader/step m (appender w2) scripted)]
                         (answer! w2 2)
                         (:machine (reader/drain (:machine r) acks2))))
            m1 (step-ack (assoc machine :yin.k/custody (custody-for (:target w2))))
            m2 (step-ack m1)
            m3 (step-ack m2)]
        (is (= 1 (get-in m1 [:yin.k/custody :input :next]))
            "the failed mint is recorded and acknowledged")
        (is (map? (get-in m1 [:resources (:id c1) :yin.k/unminted]))
            "the first mint failed and installed nothing")
        (is (= ["B"] (mapv :value (:ready-queue m2)))
            "the second observation went to the distinct stream, not to the
second cell of the failed group")
        (is (and (map? (get-in m3 [:resources (:id c1) :yin.k/unminted]))
                 (map? (get-in m3 [:resources (:id c2) :yin.k/unminted])))
            "the group's next turn retried the FIRST cell; both stay unminted")))))


;; =============================================================================
;; Cursor failures: a failed mint is recorded, installs nothing, is retried
;; =============================================================================
(deftest a-failed-mint-is-recorded-installs-nothing-and-is-retried-test

  (doseq [[label outcome] [["closed" {:dao.stream/outcome :dao.stream/closed}]
                           ["refused" {:dao.stream/outcome :dao.stream/refused}]
                           ["invalid anchor" {:dao.stream/outcome :dao.stream/invalid-anchor}]
                           ["transport error" {:dao.stream/outcome :dao.stream/transport-error}]
                           ["a malformed success" {:dao.stream/outcome :dao.stream/ok}]]]
    (testing label
      (let [h (ring 8)
            sid (stream-id h)
            m0 (s/new-machine)
            [sref m1] (engine/attach-resource m0 h)
            cursor-r (engine/handle-effect
                       (assoc m1 :yin.k/gate :running)
                       (module/make-effect :stream/cursor {:stream sref})
                       {})
            cref (:value cursor-r)
            cell-id (:id cref)
            cell-before (get-in (:state cursor-r) [:resources cell-id])
            w (world)
            m (assoc (:state cursor-r) :yin.k/custody (custody-for (:target w)))
            scripted (fn [_handle _op _arg] outcome)
            r1 (reader/step m (appender w) scripted)
            _ (is (= outcome
                     (get-in (first (:appended r1))
                             [:request :yin.k/input :yin.k/observed]))
                  "the failure is durably recorded as an observation")
            _ (answer! w 2)
            d1 (reader/drain (:machine r1) (ack-reader w))
            _ (is (= [0] (mapv :input-seq (:applied d1))) "and acknowledged")
            m1' (:machine d1)
            _ (is (= cell-before (get-in m1' [:resources cell-id]))
                  "the cell keeps its unminted marker: nothing was installed")
            _ (is (= 1 (get-in m1' [:yin.k/custody :input :next])))
            calls (atom 0)
            scripted2 (fn [handle op arg] (swap! calls inc) (scripted handle op arg))
            r2 (reader/step m1' (appender w) scripted2)]
        (is (= 1 @calls) "the next step retries the mint as a new observation")
        (is (= 1 (get-in (first (:appended r2)) [:request :yin.k/input :yin.k/input-seq]))
            "a new k")
        (is (= (cursor-source [] sid :dao.stream/oldest)
               (get-in (first (:appended r2))
                       [:request :yin.k/input :yin.k/source]))
            "the same source: the retry is the same mint, observed afresh")))))


(deftest a-failed-mint-replays-without-installing-test

  (let [h (ring 8)
        sid (stream-id h)
        m0 (s/new-machine)
        [sref m1] (engine/attach-resource m0 h)
        cursor-r (engine/handle-effect
                   (assoc m1 :yin.k/gate :running)
                   (module/make-effect :stream/cursor {:stream sref})
                   {})
        cref (:value cursor-r)
        cell-id (:id cref)
        cell-before (get-in (:state cursor-r) [:resources cell-id])
        out (reader/replay (assoc (:state cursor-r) :yin.k/custody
                                  (prefix-custody
                                    [{:yin.k/source (cursor-source [] sid
                                                                   :dao.stream/oldest)
                                      :yin.k/observed
                                      {:dao.stream/outcome :dao.stream/closed}}])))
        m (:machine out)]
    (is (nil? (:unmatched out)) (pr-str (:unmatched out)))
    (is (= [0] (mapv :input-seq (:applied out))) "the failed mint applied")
    (is (= cell-before (get-in m [:resources cell-id]))
        "replay installs nothing: the cell stays unminted, exactly the
live disposition")
    (is (= 1 (get-in m [:yin.k/custody :input :next])))))


(deftest a-failed-link-mint-installs-no-cursor-and-the-link-stays-unsendable-test

  (let [m0 (vm/run (-> (s/new-machine {:link-request (ring 8)
                                       :link-response (ring 8)})
                       (s/load-ast (s/app (s/v 'require) (s/lit 'host.mod)))
                       (assoc :yin.k/gate :running)))
        entry-before (first (:wait-set m0))
        w (world)
        request-id (stream-id (get (:resources m0) module/link-request-resource))
        m (assoc m0 :yin.k/custody (custody-for request-id))
        scripted (fn [_h _op _a] {:dao.stream/outcome :dao.stream/closed})
        r (reader/step m (appender w) scripted)
        _ (answer! w 2)
        d (reader/drain (:machine r) (ack-reader w))
        m' (:machine d)
        entry (first (:wait-set m'))]
    (is (= [0] (mapv :input-seq (:applied d))) "the failure is recorded and acknowledged")
    (is (= :link-request (:reason entry)))
    (is (not (contains? entry :cursor))
        "no cursor was installed: the entry stays sendable-nothing")
    (is (= (:envelope entry-before) (:envelope entry)) "the envelope is verbatim")
    (is (nil? (some :op-id (:wait-set m'))) "no id was taken")
    (testing "the writer still emits nothing for it"
      (let [before (count (read-all (:in w)))
            e (writer/emit m' (appender w))]
        (is (empty? (:appended e)) "a cursorless link request is not sent")
        (is (= before (count (read-all (:in w))))
            "nothing new reached the inbound stream")))))


;; =============================================================================
;; Missing evidence never means frontier zero
;; =============================================================================
(deftest a-custody-map-without-a-frontier-fails-closed-test

  (let [h (ring 8)
        _ (stream/append! h "A")
        [_cref parked] (gated-poll h)
        base (assoc parked :yin.k/custody (prefix-custody []))
        no-input (assoc parked :yin.k/custody
                        (dissoc (prefix-custody []) :input))
        [observe! calls] (counting-observe)
        sink (fn [_r] {:dao.stream/outcome :dao.stream/ok})]
    (doseq [[label m] [["a missing :input map" no-input]
                       ["an empty prefix map" (assoc-in base
                                                        [:yin.k/custody :input :prefix]
                                                        {})]
                       ["a status-bearing prefix" (assoc-in base
                                                            [:yin.k/custody :input :prefix]
                                                            {:yin.k/status :suspended
                                                             :yin.k/reason :unavailable})]]
            :let [out (reader/step m sink observe!)]]
      (testing label
        (is (= {:yin.k/status :yin.k/unsatisfied :yin.k/reason :no-prefix}
               (:unsatisfied out)))
        (is (empty? (:appended out)))
        (is (= m (:machine out)))))
    (is (zero? @calls) "nothing was ever observed")
    (testing "replay fails closed the same way"
      (let [out (reader/replay no-input)]
        (is (= {:yin.k/status :yin.k/unsatisfied :yin.k/reason :no-prefix}
               (:unsatisfied out)))
        (is (empty? (:applied out)))))))
