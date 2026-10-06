(ns yin.vm.ucf.holder.driver-test
  "D13: the candidate half of the custody driver (UCF 7.7.2 to 7.7.8,
   7.8, 7.9; r3 1.5, 1.6 and 1.7's D13 row; the D10 lower-inputs
   ruling).  Every checkpoint is a real lift of a real machine the
   engine parked; every offer, grant, binding, input and admission is
   the real authority's, reached through the real front over the memory
   substrate; every lease the driver accepts was granted by the real
   judge or recorded by the real ledger writer.  The composition the
   driver owes -- the front streams, the ledger reader, the lease clock
   -- is supplied as functions over that world."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.journal :as journal]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.module :as module]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.authority.admission :as admission]
            [yin.vm.ucf.authority.completion :as completion]
            [yin.vm.ucf.authority.front :as front]
            [yin.vm.ucf.authority.grant :as grant]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.holder.driver :as driver]
            [yin.vm.ucf.holder.export :as export]
            [yin.vm.ucf.ledger :as ledger]
            [yin.vm.ucf.lift-support :as s]))


(def ^:private arb "arb-d13")

(def ^:private duration {:s 30})

(def ^:private interval {:s 10})


;; =============================================================================
;; The substrate: rings, logs, and integer-position streams
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


(defn- int-stream
  "A stream whose values sit in an atom and whose cursors are plain
  integer positions, so a position lifted from the source's instance is
  a valid cursor on the receiving composition's own instance."
  [identity]
  (let [values (atom [])]
    (reify
      stream/IDaoStreamDescriptor
      (descriptor
        [_]
        {:dao.stream/outcome :dao.stream/ok
         :dao.stream/identity identity
         :dao.stream/descriptor {:dao.stream/type :dao.stream.test/channel
                                 :dao.stream/identity identity}})


      stream/IDaoStreamReader

      (cursor
        [_ _anchor]
        {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor 0})

      (next
        [_ cursor]
        (let [vs @values]
          (cond
            (not (and (integer? cursor) (<= 0 cursor (count vs))))
            {:dao.stream/outcome :dao.stream/invalid-cursor}
            (< cursor (count vs))
            {:dao.stream/outcome :dao.stream/ok
             :dao.stream/value (nth vs cursor)
             :dao.stream/cursor (inc cursor)}
            :else {:dao.stream/outcome :dao.stream/blocked})))


      stream/IDaoStreamWriter

      (append!
        [_ x]
        (swap! values conj x)
        {:dao.stream/outcome :dao.stream/ok}))))


(defn- bounded-stream
  "A stream that answers `full` while `n` or more of its values remain
  unread, freeing capacity as the reader consumes them: the backpressure
  a holder's inbound stream gives its front."
  [identity n]
  (let [st (atom {:values [] :served 0})]
    (reify
      stream/IDaoStreamDescriptor
      (descriptor
        [_]
        {:dao.stream/outcome :dao.stream/ok
         :dao.stream/identity identity
         :dao.stream/descriptor {:dao.stream/type :dao.stream.test/channel
                                 :dao.stream/identity identity}})


      stream/IDaoStreamReader

      (cursor
        [_ _anchor]
        {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor 0})

      (next
        [_ cursor]
        (let [{:keys [values]} @st]
          (cond
            (not (and (integer? cursor) (<= 0 cursor (count values))))
            {:dao.stream/outcome :dao.stream/invalid-cursor}
            (< cursor (count values))
            (do (swap! st update :served max (inc cursor))
                {:dao.stream/outcome :dao.stream/ok
                 :dao.stream/value (nth values cursor)
                 :dao.stream/cursor (inc cursor)})
            :else {:dao.stream/outcome :dao.stream/blocked})))


      stream/IDaoStreamWriter

      (append!
        [_ x]
        (let [{:keys [values served]} @st]
          (if (>= (- (count values) served) n)
            {:dao.stream/outcome :dao.stream/full}
            (do (swap! st update :values conj x)
                {:dao.stream/outcome :dao.stream/ok})))))))


(defn- reader-over
  "A composition-supplied reader over handle `h`: the next [author
  record] until the tail, attributing the stream to `author`."
  [h author]
  (let [c (atom (oldest h))]
    (fn []
      (let [r (stream/next h @c)]
        (when (= :dao.stream/ok (:dao.stream/outcome r))
          (reset! c (:dao.stream/cursor r))
          [author (:dao.stream/value r)])))))


(defn- attach-over
  "A receiving composition's attach dispatch over a fixed table of
  identity to handle."
  [table]
  (fn [descriptor]
    (if-some [h (get table (:dao.stream/identity descriptor))]
      {:dao.stream/outcome :dao.stream/ok :dao.stream/handle h}
      {:dao.stream/outcome :dao.stream/not-found})))


;; =============================================================================
;; The source: a real machine, really lifted, and its second encoding
;; =============================================================================

(def ^:private occurrence "3d000000-0000-4000-8000-0000000000d1")

(def ^:private predecessor "3d000000-0000-4000-8000-0000000000d0")


(defn- arbitration-map
  []
  {:dao.stream/identity arb
   :dao.stream/descriptor {:dao.stream/type :dao.stream/journal}})


(defn- header-of
  ([n] {:yin.k/occurrence occurrence
        :yin.k/arbitration (arbitration-map)
        :yin.k/next-op-seq n
        :yin.k/enrolled #{}})
  ([n enrolled origin?]
   (cond-> (assoc (header-of n) :yin.k/enrolled enrolled)
     origin? (assoc :yin.k/origin {:yin.k/occurrence predecessor
                                   :dao.lease/lease "lease-0"
                                   :yin.k/emitter "peer-0"}))))


(defn- parked-reader-over
  "A real gated machine over `source`, parked on its first read: one
  more delivered value and it halts with :done."
  [source]
  (let [m0 (s/load-ast (s/new-machine)
                       (s/then {:type :stream/next, :source (s/v 'c)}
                               (s/lit :done)))
        [sref m1] (engine/attach-resource m0 source)
        [cref m2] (engine/handle-cursor m1 {:stream sref} :k1)]
    (vm/run (assoc m2 :store {'c cref} :yin.k/gate :running))))


(defn- parked-writer-over
  "A real gated machine over `target`, parked on one put of `value` to it."
  [target value]
  (let [m0 (s/load-ast (s/new-machine)
                       (s/then {:type :stream/put, :target (s/v 'w),
                                :val (s/lit value)}
                               (s/lit :done)))
        [wref m1] (engine/attach-resource m0 target)]
    (vm/run (assoc m1 :store {'w wref} :yin.k/gate :running))))


(defn- serve-as
  "The exporter's `serve!`, answering one fixed identity for every
  handle it is asked about."
  [identity]
  (fn [_h]
    {:dao.stream/identity identity
     :dao.stream/channel {:dao.stream/type :dao.stream.test/channel
                          :dao.stream/identity identity}}))


(defn- lift-under
  "Enter exporting from `machine`, prepare under `header` with `serve!`,
  and encode: the bytes, address and body of one real snapshot
  variant."
  [machine header serve!]
  (let [e (export/enter machine)]
    (when-not (= :ok (:status e))
      (throw (ex-info "The source did not enter exporting" e)))
    (let [p (export/prepare (:machine e) (:record e) serve! header)]
      (when-not (= :ok (:status p))
        (throw (ex-info "The source did not prepare" (dissoc p :record))))
      (let [r (export/encode (:machine e) (:record p))]
        (when-not (= :ok (:status r))
          (throw (ex-info "The source did not encode" (dissoc r :record))))
        (select-keys r [:bytes :address :body])))))


(defn- variant-of
  "A second encoding of the occurrence `lifted` names: the same baseline
  (occurrence, arbitration, counter, carried ids) in different bytes,
  the way the C4 variant fixtures mutate a base."
  [lifted]
  (let [bytes (cbor/encode (assoc (:body lifted) :yin.k/id-counter 9))]
    {:bytes bytes
     :address (keyword "segment"
                       (str "blake3-" (jing/digest-bytes :blake3 bytes)))}))


(defn- reader-source
  "The reader source of every test: the parked machine (kept, for the
  source-fence test), its real lift, and a second encoding of the same
  occurrence."
  []
  (let [machine (parked-reader-over (int-stream "prog-r"))
        lifted (lift-under machine (header-of 0) (serve-as "prog-r"))]
    {:machine machine
     :sources {"v1" lifted
               "v2" (variant-of lifted)}}))


;; =============================================================================
;; The world: an authority, a judge, and one front per holder
;; =============================================================================

(defn- records
  [frames]
  (mapv #(:dao.stream.journal/value (cbor/decode %)) (rest @frames)))


(defn- attributed
  ([frames] (attributed frames arb))
  ([frames author] (mapv (fn [r] [author r]) (records frames))))


(defn- world
  "A real authority over fresh frames, one front per named holder (its
  inbound from `:inbound`, a fresh ring by default), a lease-fact
  medium per holder, a tick log, and a judge wired over every medium.
  Every source in `sources` (a map of variant name to {:bytes
  :address}) is offered before one more target is enrolled.
  `:epoch-bound` lowers the authority's epoch bound for tests."
  [& {:keys [holders sources inbound lease-media epoch-bound]}]
  (let [frames (atom [(cbor/encode {:dao.stream.journal/header
                                    {:version 1 :identity arb}})])
        a (::authority/authority (authority/open!
                                   (journal/memory-backend frames nil)
                                   (when (some? epoch-bound)
                                     {::authority/max-epoch epoch-bound})))
        store (mem/create-content-mem)
        ;; the enrolled target comes first, so a source may serve its
        ;; write target under the target's own identity
        target (:yin.k/target (authority/enroll! a))
        _ (doseq [{:keys [bytes address]} (vals sources)]
            (let [r (grant/offer! a store address bytes "carrier")]
              (when (contains? #{:refused :suspended} (:yin.k/status r))
                (throw (ex-info "The world's offer was refused" r)))))
        ticks (log)
        _ (stream/append! ticks (lease/tick {:s 1}))
        media (merge (into {} (map (fn [h] [h (log)])) holders) lease-media)
        inbounds (into {} (map (fn [h] [h (ring 16)])) holders)
        inbounds (merge inbounds (select-keys inbound holders))
        identities (into {} (map (fn [[h r]] [h (stream-id r)])) inbounds)
        resolver (fn [identity _]
                   (some (fn [[h i]] (when (= identity i) h)) identities))
        replies (into {} (map (fn [h] [h (ring 16)])) holders)
        fronts (into {}
                     (map (fn [h]
                            [h (atom (front/front
                                       {:authority a
                                        :inbound (get inbounds h)
                                        :reply (get replies h)
                                        :diagnostics (log)
                                        :resolver resolver
                                        :store store
                                        :lease-media media}))]))
                     holders)
        judge (atom (reduce (fn [j h]
                              (lease/wire-facts j (get media h)
                                                (oldest (get media h)) h))
                            (-> (lease/initial-judge
                                  (merge (grant/judge-config a duration)
                                         {:resolver (fn [source _] source)
                                          :self arb}))
                                (lease/wire-tick ticks (oldest ticks) :ticks))
                            holders))]
    {:frames frames
     :a a
     :store store
     :target target
     :media media
     :inbounds inbounds
     :replies replies
     :fronts fronts
     :judge judge
     :ticks ticks
     :outcomes (admission/outcome-reader a)}))


(defn- inbound
  [w holder]
  (read-all (get (:inbounds w) holder)))


(defn- requests-of
  [w holder kind]
  (filter #(= kind (:yin.k/request %)) (inbound w holder)))


(defn- carry!
  "Step every front past up to 16 requests."
  [w]
  (doseq [f (vals (:fronts w))]
    (swap! f front/step 16))
  w)


(defn- judge!
  "Advance the tick log one reading and run one judge step."
  [w reading]
  (stream/append! (:ticks w) (lease/tick reading))
  (swap! (:judge w) #(grant/step! (:a w) %))
  w)


(defn- grant-direct!
  "Record a grant of `l` to `holder` answering `pid`, through the
  authority's own ledger writer (the judge's writer path)."
  [w l holder pid]
  (stream/append! (grant/writer (:a w))
                  (lease/grant l (custody/subject occurrence) holder duration
                               {:dao.lease/proposal pid})))


(defn- lapse-direct!
  [w l cause]
  (stream/append! (grant/writer (:a w)) (lease/lapsed l cause)))


;; =============================================================================
;; The driver over the world
;; =============================================================================

(defn- counting
  "[calls answers] over one counting fn."
  [f]
  (let [n (atom 0)]
    [(fn [& args] (swap! n inc) (apply f args)) n]))


(defn- driver-for
  "The candidate's step state for `holder` over variant `v` of
  `sources` (or the raw {:keys bytes address]} of `:raw`).  `:ledger`
  overrides the ledger read, `:protection-as` the protection
  declaration."
  [w holder sources v & {:keys [ledger protection-as raw attach-table]}]
  (let [{:keys [bytes address]} (or raw (get sources v))
        _ (assert (some? bytes) "the variant's bytes")
        prog (int-stream "prog-r")
        clock (atom {:s 1})
        [attach! attaches] (counting
                             (attach-over (merge {"prog-r" prog} attach-table)))
        [observe! observations] (counting
                                  (fn [h op arg]
                                    (case op
                                      :next (stream/next h arg)
                                      :cursor (stream/cursor h arg))))
        diagnostics (log)]
    {:state (driver/initial
              {:me holder
               :bytes bytes
               :address address
               :protection (or protection-as {"prog-r" :at-least-once})
               :renewal-interval interval
               :receiver (s/new-machine)
               :attach! attach!
               :append-request! (fn [request]
                                  (stream/append!
                                    (get (:inbounds w) holder) request))
               :read-reply! (reader-over (get (:replies w) holder) arb)
               :read-outcome! (reader-over (:outcomes w) arb)
               :read-ledger! (fn []
                               (let [rs (attributed (:frames w))]
                                 (if ledger (ledger rs) rs)))
               :clock (fn [] @clock)
               :observe! observe!
               :append-diagnostic! (fn [d] (stream/append! diagnostics d))})
     :clock clock
     :prog prog
     :attaches attaches
     :observations observations
     :diagnostics diagnostics}))


(defn- drive
  "Step `d` once, then carry the fronts; with `:judge reading` also run
  one judge step at that reading."
  [w d & {:keys [judge]}]
  (let [state (driver/step (:state d))]
    (carry! w)
    (when judge (judge! w judge))
    (assoc d :state state)))


(defn- drive-to
  "Drive until `pred` holds of the state or `n` steps are spent; asserts
  the predicate held."
  [w d pred n & {:keys [judge]}]
  (loop [d d k 0]
    (let [d' (drive w d :judge judge)]
      (if (or (pred (:state d')) (>= (inc k) n))
        (do (is (pred (:state d')) "the driver reached the expected phase")
            d')
        (recur d' (inc k))))))


(defn- phase-of
  [phase]
  (fn [state] (= phase (:phase state))))


(defn- to-running
  "Drive `d` to :running through the judge's own grant."
  [w d]
  (drive-to w d (phase-of :running) 8 :judge {:s 2}))


(defn- own-grant-driver
  [w sources variant]
  (let [driver (drive w (drive w (driver-for w "holder-a" sources variant)))]
    (grant-direct! w "lease-1" "holder-a" (get-in driver [:state :proposal :id]))
    driver))


;; =============================================================================
;; Two candidates over two encodings of one occurrence (14.2.4 row 1)
;; =============================================================================

(deftest two-candidates-over-two-encodings-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a" "holder-b"] :sources sources)
        a (driver-for w "holder-a" sources "v1")
        b (driver-for w "holder-b" sources "v2")
        a2 (drive w (drive w a))                 ; validate, propose
        b2 (drive w (drive w b))                 ; validate, propose
        _ (judge! w {:s 2})]                     ; the judge grants holder-a
    (testing "the winner activates"
      (is (zero? @(:attaches a))
          "no stream was attached before the grant was accepted")
      (let [s (drive w a2)
            st (:state s)]
        (is (= :running (:phase st)))
        (is (= :yin.k/ok (:status st)))
        (is (= :running (vm/gate-mode (:machine st))))
        (is (= occurrence (get-in st [:machine :yin.k/custody
                                      :yin.k/occurrence])))
        (is (= "holder-a" (get-in st [:machine :yin.k/custody
                                      :dao.lease/holder])))
        (is (= 0 (get-in st [:machine :yin.k/custody :input :next]))
            "a first grant starts input at zero")))
    (testing "the loser: awaiting-grant, then not-holder"
      (is (= :yin.k/awaiting-grant (:status (:state b2)))
          "while its proposal was unanswered")
      (let [s (drive w b2)
            st (:state s)]
        (is (= :yin.k/not-holder (:status st)))
        (is (= "holder-a" (:dao.lease/holder (:detail st)))
            "the lease state observed names the holder that won")
        (is (some? (:dao.lease/lease (:detail st))))
        (is (nil? (:machine st)))
        (is (= [] (filter #(= :dao.lease/released (:dao.lease/status %))
                          (read-all (get (:media w) "holder-b"))))
            "nothing was released: the loser acquired nothing")
        (is (= 1 (count (requests-of w "holder-b" :yin.k/proposal)))
            "and it proposes no further while the occurrence is held")
        (testing "the refused proposal id is never reused (7.7.8)"
          (let [pid (get-in b2 [:state :proposal :id])
                lease (get-in (authority/projection (:a w))
                              [:occurrences occurrence :dao.lease/lease])]
            (is (= :dao.lease/rejected
                   (get-in (authority/projection (:a w))
                           [:answered ["holder-b" pid]]))
                "the judge's granting pass refused the losing proposal")
            ;; the winner's lease lapses: the occurrence is grantable
            ;; again and holder-b is a candidate once more
            (lapse-direct! w lease :policy)
            (let [st' (:state (drive w s))]
              (is (= :yin.k/awaiting-grant (:status st')))
              (is (not= pid (get-in st' [:proposal :id]))
                  "the fresh step proposes a fresh id, never the
                   answered one")
              (is (= 2 (count (distinct (map :dao.lease/proposal
                                             (requests-of w "holder-b"
                                                          :yin.k/proposal))))))
              (is (= 2 (:proposals st'))))))))))


;; =============================================================================
;; Invalid bindings: not-holder, then a release; the occurrence stays
;; regrantable (residual 2)
;; =============================================================================

(defn- binding-record
  "A hand-built later record holding one binding fact for lease `l`,
  entity ids above any the fold has drawn."
  [t l epoch]
  {:dao.space/transaction
   {:t t
    :datoms (mapv (fn [[a v]] [900 a v t datom/default-op])
                  [[:yin.k/custody :yin.k/bound]
                   [:yin.k/occurrence occurrence]
                   [:dao.lease/lease l]
                   [:dao.lease/holder "holder-a"]
                   [:yin.k/epoch epoch]])}})


(defn- binding-entities
  "The entity ids of the binding facts of lease `l` in `datoms`: the
  entities carrying both the `:yin.k/bound` dispatch and the lease."
  [datoms l]
  (let [by-e (group-by first datoms)]
    (set (for [[e ds] by-e
               :when (and (some #(and (= :yin.k/custody (nth % 1))
                                      (= :yin.k/bound (nth % 2)))
                                ds)
                          (some #(and (= :dao.lease/lease (nth % 1))
                                      (= l (nth % 2)))
                                ds))]
           e))))


(defn- reframe
  "Re-encode `r` as the journal wraps every record, with `f` applied to
  its datoms."
  [r f]
  (cbor/encode
    {:dao.stream.journal/value
     (update-in r [:dao.space/transaction :datoms] f)}))


(defn- split-binding!
  "Rewrite the frames so the binding entity of lease `l` no longer rides
  its grant's transaction, appending a later record that holds exactly
  the binding fact; answers that record."
  [frames l]
  (let [strip (fn [encoded]
                (let [r (:dao.stream.journal/value (cbor/decode encoded))
                      ds (get-in r [:dao.space/transaction :datoms])
                      es (binding-entities ds l)]
                  (if (seq es)
                    (reframe r #(vec (remove (fn [d] (contains? es (first d))) %)))
                    encoded)))
        stripped (mapv strip (rest @frames))
        extra (binding-record (count stripped) l 0)]
    (reset! frames (into (into [(first @frames)] stripped)
                         [(cbor/encode {:dao.stream.journal/value extra})]))
    extra))


(defn- duplicate-binding!
  "Append a second binding record for lease `l` at the tail, wrapped as
  the journal wraps every record."
  [frames l]
  (swap! frames conj (cbor/encode
                       {:dao.stream.journal/value
                        (binding-record (count (records frames)) l 0)})))


(defn- float-epoch!
  "Rewrite the frames so the binding entity of lease `l` carries a
  float epoch."
  [frames l]
  (swap! frames
         (fn [fr]
           (into [(first fr)]
                 (map (fn [encoded]
                        (let [r (:dao.stream.journal/value
                                  (cbor/decode encoded))
                              ds (get-in r [:dao.space/transaction :datoms])
                              es (binding-entities ds l)]
                          (if (seq es)
                            (reframe r (fn [ds']
                                         (mapv (fn [d]
                                                 (if (and (= :yin.k/epoch
                                                             (nth d 1))
                                                          (contains? es
                                                                     (first d)))
                                                   (assoc d 2 (cbor/float64 0))
                                                   d))
                                               ds')))
                            encoded))))
                 (rest fr)))))


(defn- not-holder-and-released?
  "Drive `d` to the end of its release and prove the D13 invalid-binding
  answer: not-holder, then the release, then the failed state."
  [w d reason]
  (let [s (drive-to w d (phase-of :failed) 8)
        st (:state s)
        [release] (requests-of w "holder-a" :yin.k/release)]
    (is (= :yin.k/not-holder (:status st)))
    (is (= reason (:yin.k/reason (:detail st))))
    (is (nil? (:machine st)) "no machine was ever exposed")
    (is (true? (get-in st [:detail :dao.lease/released])))
    (is (some? release) "the acquired lease was released")
    (is (= [:yin.k/release (:dao.lease/lease release)] (:yin.k/request-id release)))
    s))


(deftest invalid-bindings-answer-not-holder-and-release-test
  (testing "a binding duplicated in another record"
    (let [{:keys [sources]} (reader-source)
          w (world :holders ["holder-a"] :sources sources)
          d (own-grant-driver w sources "v1")
          _ (duplicate-binding! (:frames w) "lease-1")]
      (not-holder-and-released? w (drive w (drive w d)) :duplicate-binding)))
  (testing "a binding carried by another author"
    (let [{:keys [sources]} (reader-source)
          w (world :holders ["holder-a"] :sources sources)
          original (own-grant-driver w sources "v1")
          extra (split-binding! (:frames w) "lease-1")
          _ (is (some? extra) "the binding left its grant's transaction")
          d (assoc-in original [:state :seams :read-ledger!]
                      (fn []
                        (let [rs (attributed (:frames w))]
                          (mapv (fn [[a r]]
                                  (if (and (= a arb) (= extra r))
                                    ["mallory" r] [a r]))
                                rs))))]
      (not-holder-and-released? w (drive w (drive w d)) :no-binding)))
  (testing "a binding outside its grant's transaction"
    (let [{:keys [sources]} (reader-source)
          w (world :holders ["holder-a"] :sources sources)
          d (own-grant-driver w sources "v1")
          _ (split-binding! (:frames w) "lease-1")]
      (not-holder-and-released? w (drive w (drive w d))
                                :not-in-grant-transaction)))
  (testing "a binding with a float epoch"
    (let [{:keys [sources]} (reader-source)
          w (world :holders ["holder-a"] :sources sources)
          d (own-grant-driver w sources "v1")
          _ (float-epoch! (:frames w) "lease-1")]
      (not-holder-and-released? w (drive w (drive w d)) :inexact-epoch)))
  (testing "the released occurrence stays regrantable"
    (let [{:keys [sources]} (reader-source)
          w (world :holders ["holder-a" "holder-b"] :sources sources)
          d (drive w (drive w (driver-for w "holder-a" sources "v1")) :judge {:s 2})
          acquired (get-in (authority/projection (:a w))
                           [:occurrences occurrence :dao.lease/lease])
          refused-view (atom @(:frames w))
          _ (duplicate-binding! refused-view acquired)
          d (assoc-in d [:state :seams :read-ledger!]
                      (fn [] (attributed refused-view)))
          _ (not-holder-and-released? w (drive w (drive w d))
                                      :duplicate-binding)
          _ (judge! w {:s 4})
          candidate (drive w (drive w (driver-for w "holder-b" sources "v2"))
                           :judge {:s 5})]
      (is (some? (get-in candidate [:state :proposal :id])))
      (is (= "holder-b"
             (let [projection (authority/projection (:a w))
                   granted (get-in projection [:occurrences occurrence :dao.lease/lease])]
               (get-in projection [:leases granted :dao.lease/holder])))
          "an ordinary failed run's occurrence is granted again"))))


;; =============================================================================
;; The epoch dimension of the tenure recheck: a crafted record
;; =============================================================================

(defn- frame-facts
  "The fact maps of every record in `frames`, entities in first-appearance
  order, as the journal holds them."
  [frames]
  (into []
        (mapcat (fn [encoded]
                  (let [ds (get-in (:dao.stream.journal/value (cbor/decode encoded))
                                   [:dao.space/transaction :datoms])]
                    (mapv (fn [e]
                            (into {} (keep (fn [d]
                                             (when (= e (first d)) [(nth d 1) (nth d 2)]))
                                           ds)))
                          (distinct (map first ds))))))
        (rest @frames)))


(defn- crafted-ledger
  "A journal-shaped frame list over `frames`' own header, enrollment and
  offers whose fold leaves the same grant fact `grant-fact` as the
  occurrence's live lease, bound one epoch higher than the real history
  bound it: one earlier tenure -- a grant to another holder, its lapse
  and its reclaim -- precedes it, so the live lease id is unchanged while
  the occurrence's epoch has moved.  An epoch change with the lease id
  unchanged has no foldable-history path, so the record is crafted
  directly.  `more`, when given, supplies further fact groups appended
  after the grant."
  ([frames grant-fact] (crafted-ledger frames grant-fact nil))
  ([frames grant-fact more]
   (let [facts (frame-facts frames)
         enrolled (some #(when (= :yin.k/enrolled (:yin.k/custody %)) %)
                        facts)
         offers (filterv #(= :yin.k/offered (:yin.k/custody %)) facts)
         l (:dao.lease/lease grant-fact)
         h (:dao.lease/holder grant-fact)
         groups (cond-> [[enrolled]]
                  true (into (mapv vector offers))
                  true (into [[(lease/grant "lease-prior"
                                            (custody/subject occurrence)
                                            "holder-prior" duration)
                               (custody/bound occurrence "lease-prior"
                                              "holder-prior" 0)]
                              [(lease/lapsed "lease-prior" :policy)
                               (custody/reclaimed occurrence "lease-prior" 1)]
                              [grant-fact
                               (custody/bound occurrence l h 1)]])
                  (seq more) (into more))]
     (:frames'
       (reduce
         (fn [{:keys [frames' e t]} group]
           {:frames' (conj frames'
                           (cbor/encode
                             {:dao.stream.journal/value
                              {:dao.space/transaction
                               {:t t
                                :datoms (mapv (fn [[e a v]]
                                                [e a v t datom/default-op])
                                              (ledger/facts->datoms e group))}}}))
            :e (+ e (count group))
            :t (inc t)})
         {:frames' [(first @frames)]
          :e datom/first-user-id
          :t 0}
         groups)))))


(deftest the-epoch-dimension-of-the-tenure-recheck-test
  (testing "the running recheck refuses on the epoch alone"
    (let [{:keys [sources]} (reader-source)
          w (world :holders ["holder-a"] :sources sources)
          d (own-grant-driver w sources "v1")
          running (drive-to w d (phase-of :running) 6)
          st0 (:state running)
          l (:lease st0)
          real-grant (some #(when (and (= :dao.lease/accepted
                                          (:dao.lease/status %))
                                       (= l (:dao.lease/lease %)))
                              %)
                           (frame-facts (:frames w)))
          _ (is (some? real-grant) "the real grant fact")
          crafted (atom (crafted-ledger (:frames w) real-grant))
          st (driver/step (assoc-in st0 [:seams :read-ledger!]
                                    (fn [] (attributed crafted))))]
      (is (= 0 (get-in st0 [:evidence :yin.k/binding :yin.k/epoch]))
          "the evidence the driver accepted binds epoch 0")
      (is (= :releasing (:phase st)))
      (is (= :yin.k/ended (:status st)))
      (is (= :stale (get-in st [:detail :cause])))
      (is (= l (:dao.lease/observed-lease (:detail st)))
          "the lease id is unchanged: the epoch alone moved")
      (is (= :ended (vm/gate-mode (:machine st))))
      (is (= 1 (count (requests-of w "holder-a" :yin.k/release))))
      (is (= [{:yin.k/diagnostic :yin.k/run-ended
               :yin.k/occurrence occurrence
               :dao.lease/lease l
               :yin.k/end {:cause :stale
                           :dao.lease/lease l
                           :dao.lease/observed-lease l}}]
             (read-all (:diagnostics running)))
          "one diagnostic ends the run")))
  (testing "accept refuses the same moved epoch: stale, not the holder"
    (let [{:keys [sources]} (reader-source)
          w (world :holders ["holder-a" "holder-b"] :sources sources)
          d (drive w (drive w (driver-for w "holder-a" sources "v1")))
          pid (get-in d [:state :proposal :id])
          crafted (atom
                    (crafted-ledger
                      (:frames w)
                      (lease/grant "lease-1" (custody/subject occurrence)
                                   "holder-a" duration
                                   {:dao.lease/proposal pid})
                      [[(lease/lapsed "lease-1" :policy)
                        (custody/reclaimed occurrence "lease-1" 2)]
                       [(lease/grant "lease-2" (custody/subject occurrence)
                                     "holder-b" duration)
                        (custody/bound occurrence "lease-2" "holder-b" 2)]]))
          st (driver/step (assoc-in (:state d) [:seams :read-ledger!]
                                    (fn [] (attributed crafted))))]
      (is (= :yin.k/not-holder (:status st)))
      (is (= :proposing (:phase st)) "refused, not failed: a regrant may come")
      (is (= "lease-2" (:dao.lease/lease (:detail st))))
      (is (= "holder-b" (:dao.lease/holder (:detail st))))
      (is (= 2 (:yin.k/epoch (:detail st)))
          "the state observed carries the moved epoch, not the binding's 1")
      (is (nil? (:machine st)))
      (is (zero? @(:attaches d)) "nothing was lowered")
      (is (empty? (requests-of w "holder-a" :yin.k/release))
          "nothing was acquired, nothing released"))))


;; =============================================================================
;; An occurrence that can never be granted again: the stale-grant arm
;; answers not-holder too, never a fresh proposal
;; =============================================================================
;;
;; The never-again marks the driver's own fold can observe are closed
;; and quarantined; exhausted is folded only when the fold's epoch
;; bound saturates (a reclaim at its lease's own epoch, which the
;; fold's own next-epoch rule admits only there), and the holder-side
;; fold runs at the default bound -- such a history defects
;; :epoch-mismatch and answers :unsatisfied before the stale arm, so
;; the exhausted disjunct is exercised nowhere here.

(defn- dead-occurrence-answers-not-holder
  "Step the :proposing driver `d`, whose own stale grant still reads
  from the ledger, over an occurrence marked `mark` (:yin.k/closed or
  :yin.k/quarantined): the step must answer not-holder carrying the
  occurrence state observed and append no fresh proposal -- the judge
  refuses every proposal on such an occurrence, so a proposal minted
  here would be minted and refused again each pass forever, the step
  answering :awaiting-grant instead."
  [w d mark]
  (let [pids (fn []
               (count (distinct (map :dao.lease/proposal
                                     (requests-of w "holder-a"
                                                  :yin.k/proposal)))))
        before (pids)
        st (driver/step (:state d))]
    (is (= :yin.k/not-holder (:status st))
        "an occurrence that can never be granted again is not answered
         with a proposal")
    (is (= :proposing (:phase st)) "refused, not failed")
    (is (= occurrence (:yin.k/occurrence (:detail st))))
    (is (true? (get (:detail st) mark))
        (str "the state observed carries " mark))
    (is (nil? (:dao.lease/lease (:detail st))) "the occurrence is unheld")
    (is (= before (pids)) "no fresh proposal was appended")
    (is (nil? (:machine st)))
    (is (empty? (requests-of w "holder-a" :yin.k/release))
        "nothing was acquired, nothing released")
    (testing "the answer is stable across judge passes"
      (let [st' (:state (drive w (assoc d :state st) :judge {:s 50}))]
        (is (= :yin.k/not-holder (:status st')))
        (is (= before (pids))
            "the judge had no fresh proposal to refuse")))))


(deftest a-closed-occurrence-answers-not-holder-past-the-gap-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a"] :sources sources)
        d (drive w (drive w (driver-for w "holder-a" sources "v1"))
                 :judge {:s 2})
        l (get-in (authority/projection (:a w))
                  [:occurrences occurrence :dao.lease/lease])
        _ (is (some? l) "the judge granted the tenure")
        ;; the tenure reports a real halted result whose origin names
        ;; itself, and its release-lapse transaction carries the
        ;; closure and the terminal edge: the occurrence is closed
        result (lift-under (s/halted-machine)
                           (assoc (header-of 0)
                                  :yin.k/origin {:yin.k/occurrence occurrence
                                                 :dao.lease/lease l
                                                 :yin.k/emitter "holder-a"})
                           (serve-as "halted"))
        _ (is (= :committed
                 (:yin.k/status (completion/report!
                                  (:a w) "holder-a"
                                  (completion/resumed occurrence l
                                                      (:address result))
                                  (:bytes result)))))
        _ (lapse-direct! w l :release)
        _ (is (some? (get-in (authority/projection (:a w))
                             [:occurrences occurrence :yin.k/closed]))
              "the release-lapse closed the occurrence")]
    (dead-occurrence-answers-not-holder w d :yin.k/closed)))


(deftest a-quarantined-occurrence-answers-not-holder-past-the-gap-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a"] :sources sources)
        d (drive w (drive w (driver-for w "holder-a" sources "v1"))
                 :judge {:s 2})
        l (get-in (authority/projection (:a w))
                  [:occurrences occurrence :dao.lease/lease])
        _ (is (some? l) "the judge granted the tenure")
        envelope (fn [v]
                   {:yin.k/envelope :yin.k/fenced-v1
                    :yin.k/incarnation l
                    :yin.k/epoch (get-in (authority/projection (:a w))
                                         [:leases l :yin.k/epoch])
                    :yin.k/op-id {:yin.k/occurrence occurrence :yin.k/seq 0}
                    :yin.k/value v})
        diag (log)
        ;; two conflicting admissions under the live lease: the real
        ;; intent-conflict path quarantines the occurrence
        _ (admission/admit! (:a w) (:store w) (:target w) "holder-a"
                            (envelope :v) diag)
        conflict (admission/admit! (:a w) (:store w) (:target w) "holder-a"
                                   (envelope :w) diag)
        _ (is (= :intent-conflict (:yin.k/admission conflict)))
        _ (lapse-direct! w l :policy)]
    (dead-occurrence-answers-not-holder w d :yin.k/quarantined)))


;; =============================================================================
;; Unavailable ledger history never activates (never an empty prefix)
;; =============================================================================

(defn- corrupt
  "The ledger read as one broken mode of D3's unavailable family."
  [name]
  (fn [rs]
    (case name
      :gap (into [(first rs)] (drop 2) rs)
      :origin (vec (rest rs))
      :transport (conj (vec (butlast rs))
                       [arb {:dao.stream/outcome
                             :dao.stream/transport-error}])
      :fold (assoc rs 1 [arb {}]))))


(defn- check-unavailable
  [name reason]
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a"] :sources sources)
        original (own-grant-driver w sources "v1")
        d (assoc-in original [:state :seams :read-ledger!]
                    (fn [] ((corrupt name) (attributed (:frames w)))))
        st (:state (drive w (drive w d)))]
    (is (= :yin.k/unsatisfied (:status st)) (str name))
    (is (= reason (:yin.k/reason (:detail st))) (str name))
    (is (nil? (:machine st)) (str name))
    (is (= :proposing (:phase st)) (str name " -- not terminal"))
    (is (empty? (requests-of w "holder-a" :yin.k/release))
        (str name " -- custody was not accepted, nothing released"))))


(deftest unavailable-ledger-history-never-activates-test
  (check-unavailable :gap :gap)
  (check-unavailable :origin :start-past-origin)
  (check-unavailable :transport :transport-error)
  (check-unavailable :fold :fold-defect))


;; =============================================================================
;; Post-grant failure releases, and retains release progress
;; =============================================================================

(deftest post-grant-failure-releases-or-retains-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a" "holder-b"] :sources sources
                 :inbound {"holder-a" (bounded-stream "in-a" 2)})
        ;; the declaration contradicts the evidence's enrollment: the
        ;; lower refuses after the grant
        d (driver-for w "holder-a" sources "v1"
                      :protection-as {"prog-r" :enrolled})
        started (drive w (drive w d) :judge {:s 2})
        _ (is (= :proposing (:phase (:state started)))
              "the judge granted the occurrence")
        _ (stream/append! (get (:inbounds w) "holder-a") :junk)
        _ (stream/append! (get (:inbounds w) "holder-a") :junk)
        ;; the inbound is full: the release cannot leave yet
        s1 (drive w started)
        st1 (:state s1)]
    (testing "the release is retained while the inbound is full"
      (is (= :releasing (:phase st1)))
      (is (= :yin.k/unsatisfied (:status st1))
          "the lower's refusal is the failure the release carries")
      (is (false? (get-in st1 [:release :sent])))
      (is (empty? (requests-of w "holder-a" :yin.k/release))
          "the release attempt appended nothing: the stream was full"))
    (testing "the identical request is retried and delivers"
      (let [s2 (drive-to w s1 (phase-of :failed) 6)
            st2 (:state s2)
            [release] (requests-of w "holder-a" :yin.k/release)]
        (is (= :failed (:phase st2)))
        (is (true? (get-in st2 [:release :sent])))
        (is (true? (get-in st2 [:detail :dao.lease/released])))
        (is (= (:yin.k/request-id release)
               (get-in st2 [:release :request :yin.k/request-id]))
            "the retry is the identical request")
        (is (= [:yin.k/release (get-in st2 [:release :lease])]
               (:yin.k/request-id release)))))
    (testing "the occurrence is regrantable after the release"
      (let [_ (judge! w {:s 4})
            held (get-in (authority/projection (:a w))
                         [:occurrences occurrence :dao.lease/lease])
            _ (is (nil? held))
            b (driver-for w "holder-b" sources "v2")
            s (drive-to w b (phase-of :running) 8 :judge {:s 5})]
        (is (= :running (:phase (:state s))))))))


;; =============================================================================
;; Renewal before half the duration; all IO stops at the bound
;; =============================================================================

(deftest renewal-before-half-and-io-stops-at-the-bound-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a" "holder-b"] :sources sources)
        d (driver-for w "holder-a" sources "v1")
        running (to-running w (drive w (drive w d)))
        _ (is (= :running (:phase (:state running))))
        _ (reset! (:clock running) {:s 6})
        early (drive w running)
        _ (is (empty? (requests-of w "holder-a" :yin.k/renewal)))
        ;; the renewal lands before half the duration
        _ (reset! (:clock running) {:s 11})
        sent (drive w early :judge {:s 12})
        s (drive w sent)
        st (:state s)
        l (:lease st)
        [renewal] (requests-of w "holder-a" :yin.k/renewal)
        ;; the bound the renewal moved to: basis 11 + duration 30
        before (count (inbound w "holder-a"))
        observed-before @(:observations running)
        _ (reset! (:clock running) {:s 41})
        s2 (drive w s)
        st2 (:state s2)
        after (inbound w "holder-a")]
    (is (= {:yin.k/request :yin.k/renewal
            :yin.k/request-id [:yin.k/renewal l 0]
            :dao.lease/lease l}
           renewal)
        "one renewal through D2's front request")
    (is (= {:s 11} (:last-renewal-at (:holder st)))
        "authenticated carriage advances to the retained pre-send reading")
    (is (= 1 (count (requests-of w "holder-a" :yin.k/renewal))))
    (is (< (:s (:last-renewal-at (:holder st)))
           (+ 1 (quot (:s duration) 2)))
        "the actual renewal reading precedes half the granted duration")
    (testing "all IO stops at the bound"
      (is (= :releasing (:phase st2)))
      (is (= :yin.k/ended (:status st2)))
      (is (= :lease-bound (:cause (:detail st2))))
      (is (= :ended (vm/gate-mode (:machine st2))))
      (is (= observed-before @(:observations running)))
      (is (= (dissoc (:machine st) :yin.k/gate)
             (dissoc (:machine st2) :yin.k/gate))
          "the bound step does not compute or apply a program result")
      (is (= (inc before) (count after))
          "the bound step appended exactly one value: the release")
      (is (= :yin.k/release (:yin.k/request (last after))))
      (is (= [{:yin.k/diagnostic :yin.k/run-ended
               :yin.k/occurrence occurrence
               :dao.lease/lease l
               :yin.k/end {:cause :lease-bound :dao.lease/lease l}}]
             (read-all (:diagnostics s2)))
          "one diagnostic ends the run")
      (testing "and nothing more is appended after the bound"
        (let [s3 (drive w s2)
              s4 (drive w s3)]
          (is (= :failed (:phase (:state s4))))
          (is (= observed-before @(:observations running)))
          (is (= (:machine st2) (get-in s4 [:state :machine])))
          (is (= after (inbound w "holder-a"))))))
    (testing "the ordinary failed run's occurrence is regrantable"
      (let [_ (judge! w {:s 42})          ; the judge drains the release
            held (get-in (authority/projection (:a w))
                         [:occurrences occurrence :dao.lease/lease])]
        (is (nil? held) "the judge lapsed the released lease")
        (let [b (driver-for w "holder-b" sources "v1")
              s5 (drive-to w b (phase-of :running) 8 :judge {:s 43})]
          (is (= :running (:phase (:state s5)))))))))


;; =============================================================================
;; A renewal carriage accepted past the bound inside one step ends the
;; run once
;; =============================================================================

(deftest delayed-carriage-past-the-bound-ends-the-run-once-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a"] :sources sources)
        running (to-running w (drive w (drive w (driver-for w "holder-a"
                                                            sources "v1"))))
        _ (is (= :running (:phase (:state running))))
        ;; the machine completes its one read and halts: the step answers
        ;; :safepoint, and a safepoint still renews
        _ (stream/append! (:prog running) "B")
        halted (drive-to w running (phase-of :safepoint) 10)
        ;; the renewal is sent at 11 and carried by the front; its
        ;; carriage reply waits unread in the reply stream
        _ (reset! (:clock halted) {:s 11})
        renewed (drive w halted)
        _ (is (= :safepoint (:phase (:state renewed))))
        _ (is (some? (get-in renewed [:state :renewal])))
        l (:lease (:state renewed))
        ;; the critical step: the reply is withheld from the pre-cycle
        ;; drain and released inside the post-cycle one, and the clock --
        ;; a controlled reading -- crosses the bound at exactly that
        ;; moment, the way a clock that advanced during the cycle would
        ;; read; the between-step atom clocks of this world cannot fire
        ;; the arm at all
        reply-reader (get-in renewed [:state :seams :read-reply!])
        past-bound (atom false)
        armed (-> renewed
                  (assoc-in [:state :seams :read-reply!]
                            (let [served (atom false)]
                              (fn []
                                (if @served
                                  (do (reset! past-bound true)
                                      (reply-reader))
                                  (do (reset! served true) nil)))))
                  (assoc-in [:state :seams :clock]
                            (fn [] (if @past-bound {:s 31} {:s 11}))))
        st (driver/step (:state armed))]
    (testing "the drain's run end is the step's one answer"
      (is (= :releasing (:phase st))
          "never flipped back to :safepoint by the step's cond")
      (is (= :yin.k/ended (:status st))
          "never re-labelled :ok: the ended answer survives")
      (is (= :lease-bound (:cause (:detail st))))
      (is (= l (:dao.lease/lease (:detail st))))
      (is (= :ended (vm/gate-mode (:machine st))))
      (is (= 1 (count (requests-of w "holder-a" :yin.k/release)))
          "the release left exactly once")
      (is (= [{:yin.k/diagnostic :yin.k/run-ended
               :yin.k/occurrence occurrence
               :dao.lease/lease l
               :yin.k/end {:cause :lease-bound :dao.lease/lease l}}]
             (read-all (:diagnostics armed)))
          "one diagnostic ends the run (r3 1.6)"))
    (testing "the next step retries the release, never a second run end"
      (let [again (driver/step st)]
        (is (= :releasing (:phase again)))
        (is (= 1 (count (read-all (:diagnostics armed))))
            "the run is never ended a second time")
        (is (= 1 (count (distinct (map :yin.k/request-id
                                       (requests-of w "holder-a"
                                                    :yin.k/release)))))
            "the retry is the identical release request")))))


;; =============================================================================
;; A regrant replays from the checkpoint
;; =============================================================================

(deftest a-regrant-replays-from-the-checkpoint-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a" "holder-b"] :sources sources)
        a (driver-for w "holder-a" sources "v1")
        running (to-running w (drive w (drive w a)))
        _ (is (= :running (:phase (:state running))))
        ;; tenure 1 records one real input: the read of B at k 0
        _ (stream/append! (:prog running) "B")
        _ (loop [d running k 0]
            (if (or (seq (requests-of w "holder-a" :yin.k/input))
                    (>= k 6))
              d
              (recur (drive w d) (inc k))))
        [request] (requests-of w "holder-a" :yin.k/input)
        _ (is (some? request) "tenure 1 recorded its read as an input")
        _ (is (= "B" (get-in request [:yin.k/input :yin.k/observed
                                      :dao.stream/value])))
        ;; the crash: the holder and its state die; the lease is
        ;; reclaimed and the regrant goes to holder-b
        _ (judge! w {:s 40})
        b (driver-for w "holder-b" sources "v1")
        s (drive-to w b (phase-of :safepoint) 10 :judge {:s 41})
        st (:state s)]
    (is (= :safepoint (:phase st)))
    (is (= 1 (get-in st [:machine :yin.k/custody :input :prefix
                         :yin.k/frontier]))
        "the evidence's prefix is the input tenure 1 recorded")
    (is (= :done (:value (:machine st)))
        "the replayed read woke the machine and it ran to its halt")
    (is (zero? @(:observations b))
        "nothing was observed live: the prefix was replayed, not read")))


;; =============================================================================
;; The run drives the writer (a declared at-least-once write)
;; =============================================================================
;;
;; An enrolled retained write cannot reach a D13 activation without the
;; exit half: its body carries a predecessor's operation id, and the
;; authority refuses a successor's offer as an orphan until the
;; predecessor's closure is recorded (D14 owns that).  The writer arm is
;; driven here through the class a first export can carry.

(deftest the-run-drives-the-writer-test
  (let [machine (parked-writer-over (s/one-slot-stream "w") "v")
        lifted (lift-under machine (header-of 0) (serve-as "prog-w"))
        w (world :holders ["holder-a"] :sources {"v1" lifted})
        receiver-stream (int-stream "prog-w")
        d (driver-for w "holder-a" {"v1" lifted} "v1"
                      :protection-as {"prog-r" :at-least-once
                                      "prog-w" :at-least-once}
                      :attach-table {"prog-w" receiver-stream})
        running (to-running w (drive w (drive w d)))
        _ (is (= :running (:phase (:state running))))
        ;; the writer performed the declared at-least-once append bare,
        ;; through the machine's own attached handle, and the machine
        ;; ran on to its halt
        s (drive-to w running (phase-of :safepoint) 8)
        st (:state s)]
    (is (= :safepoint (:phase st)))
    (is (= :done (:value (:machine st))))
    (is (= ["v"] (read-all receiver-stream))
        "the bare append landed on the receiving composition's stream")
    (is (empty? (requests-of w "holder-a" :yin.k/admit))
        "an unenrolled target never goes through the fenced boundary")))


;; =============================================================================
;; The source itself resumes only after its own grant
;; =============================================================================

(deftest the-source-resumes-only-after-its-own-grant-test
  (let [{:keys [sources machine]} (reader-source)
        fenced (export/enter machine)
        _ (is (= :ok (:status fenced)))
        w (world :holders ["holder-a" "holder-b"] :sources sources)
        d (driver-for w "holder-a" sources "v1")
        s1 (drive w (drive w (drive w d)))]
    (testing "no grant: the source stays fenced and nothing runs"
      (is (= :yin.k/awaiting-grant (:status (:state s1))))
      (is (nil? (:machine (:state s1))))
      (is (= :exporting (vm/gate-mode (:machine fenced))))
      (is (= [] (:wait-set (:machine fenced)))))
    (testing "a grant to another: still fenced, not the holder"
      (let [_ (grant-direct! w "lease-1" "holder-b" "p-x")
            s2 (drive w s1)]
        (is (= :yin.k/not-holder (:status (:state s2))))
        (is (= :exporting (vm/gate-mode (:machine fenced))))
        (is (nil? (:machine (:state s2))))))
    (testing "its own grant: the bytes lower and run"
      (let [_ (lapse-direct! w "lease-1" :policy)
            _ (grant-direct! w "lease-2" "holder-a" (get-in s1 [:state :proposal :id]))
            s3 (drive-to w s1 (phase-of :running) 8)
            st (:state s3)]
        (is (= :running (:phase st)))
        (is (= :running (vm/gate-mode (:machine st))))
        (is (not= (:machine fenced) (:machine st))
            "the resumed task is the lowered body, never the source's
             own local machine")
        (is (= :exporting (vm/gate-mode (:machine fenced)))
            "and the source's local machine stays fenced forever")))))


;; =============================================================================
;; Zero side effects before the grant; the small refusals
;; =============================================================================

(deftest validating-is-side-effect-free-test
  (testing "bytes that decode to no body fail with nothing attached"
    (let [{:keys [sources]} (reader-source)
          w (world :holders ["holder-a"] :sources sources)
          junk-bytes (cbor/encode [1 2])
          d (driver-for w "holder-a" nil nil
                        :raw {:bytes junk-bytes
                              :address (keyword
                                         "segment"
                                         (str "blake3-"
                                              (jing/digest-bytes
                                                :blake3 junk-bytes)))})
          st (:state (drive w d))]
      (is (= :failed (:phase st)))
      (is (= :yin.k/undecodable (:status st)))
      (is (zero? @(:attaches d)) "no stream was attached")
      (is (= [] (inbound w "holder-a")) "no request was sent"))))


(deftest a-missing-authority-attachment-is-unsatisfied-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a"] :sources sources)
        d (driver-for w "holder-a" sources "v1"
                      :ledger (fn [_rs] nil))
        st (:state (drive w (drive w d)))]
    (is (= :yin.k/unsatisfied (:status st)))
    (is (= :no-arbitration (:yin.k/reason (:detail st))))
    (is (= arb (:dao.stream/identity (:detail st))))
    (is (= [] (inbound w "holder-a")) "nothing was proposed")))


(deftest an-unadmitted-variant-is-not-the-checkpoint-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a"] :sources
                 ;; only the first encoding is offered
                 {"offered" (get sources "v1")})
        d (own-grant-driver w sources "v2")
        s (not-holder-and-released? w (drive w (drive w d))
                                    :variant-not-admitted)]
    (is (= #{(:address (get sources "v1"))}
           (:yin.k/admitted (:detail (:state s))))
        "the admission came from the ledger's own variants, not from
         the equality of two supplied addresses")))


;; =============================================================================
;; A halted result needs no grant
;; =============================================================================

(deftest a-halted-result-lowers-without-a-grant-test
  (let [lifted (lift-under (s/halted-machine)
                           (header-of 0 #{} true) (serve-as "halted"))
        w (world :holders ["holder-a"] :sources {})
        d (driver-for w "holder-a" nil nil :raw lifted)
        st (:state (drive w d))]
    (is (= :safepoint (:phase st)))
    (is (= :yin.k/ok (:status st)))
    (is (= 42 (:value (:machine st))))
    (is (= :ended (vm/gate-mode (:machine st))))
    (is (not (contains? (:machine st) :yin.k/custody)))
    (is (= [] (inbound w "holder-a")) "no grant was ever needed")))


(deftest foreign-grants-never-activate-a-restarted-driver-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a"] :sources sources)
        _ (grant-direct! w "foreign" "holder-a" "old-process")
        d (driver-for w "holder-a" sources "v1")
        st (:state (drive w (drive w d)))]
    (is (nil? (:machine st)))
    (is (zero? @(:attaches d)))
    (is (= :yin.k/not-holder (:status st)))))


(deftest profile-refusal-precedes-wrong-address-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a"] :sources sources)
        bytes (cbor/encode (assoc (:body (get sources "v1")) :yin.k/version 2))
        d (driver-for w "holder-a" nil nil
                      :raw {:bytes bytes :address (:address (get sources "v1"))})
        st (:state (drive w d))]
    (is (= :yin.k/profile-mismatch (:status st)))
    (is (zero? @(:attaches d)))
    (is (empty? (inbound w "holder-a")))))


(deftest stale-own-grant-mints-only-one-successor-proposal-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a"] :sources sources)
        d (drive w (drive w (driver-for w "holder-a" sources "v1"))
                 :judge {:s 2})
        lease (get-in (authority/projection (:a w))
                      [:occurrences occurrence :dao.lease/lease])
        _ (lapse-direct! w lease :policy)
        stepped (nth (iterate #(drive w %) d) 5)]
    (is (= 2 (:proposals (:state stepped))))
    (is (= 2 (count (distinct (map :dao.lease/proposal
                                   (requests-of w "holder-a" :yin.k/proposal))))))))


(deftest queued-control-requests-need-authenticated-carriage-test
  (let [{:keys [sources]} (reader-source)
        medium (bounded-stream "lease-a" 1)
        _ (stream/append! medium :blocked)
        w (world :holders ["holder-a"] :sources sources
                 :lease-media {"holder-a" medium})
        proposed (drive w (drive w (driver-for w "holder-a" sources "v1")))
        retried (drive w proposed)]
    (is (not (true? (get-in retried [:state :proposal :carried]))))
    (is (= 2 (count (requests-of w "holder-a" :yin.k/proposal))))
    (is (= 1 (count (distinct (map :dao.lease/proposal
                                   (requests-of w "holder-a" :yin.k/proposal))))))
    (judge! w {:s 2})
    (is (= :running (:phase (:state (to-running w retried)))))))


(deftest renewal-does-not-advance-on-queue-acceptance-test
  (doseq [late? [false true]]
    (let [{:keys [sources]} (reader-source)
          w (world :holders ["holder-a"] :sources sources)
          running (to-running w (driver-for w "holder-a" sources "v1"))
          _ (reset! (:clock running) {:s 11})
          queued (assoc running :state (driver/step (:state running)))]
      (is (nil? (get-in queued [:state :holder :last-renewal-at])))
      (is (some? (get-in queued [:state :renewal])))
      (carry! w)
      (reset! (:clock queued) {:s (if late? 31 12)})
      (let [settled (driver/step (:state queued))]
        (if late?
          (do (is (= :releasing (:phase settled)))
              (is (= :lease-bound (:cause (:detail settled)))))
          (is (= {:s 11} (get-in settled [:holder :last-renewal-at]))))))))


(deftest restarted-drivers-mint-distinct-proposals-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a"] :sources sources)
        first-run (drive w (drive w (driver-for w "holder-a" sources "v1")))
        restart (drive w (drive w (driver-for w "holder-a" sources "v1")))]
    (is (not= (get-in first-run [:state :proposal :id])
              (get-in restart [:state :proposal :id])))))


(deftest dense-ledger-with-invalid-content-is-a-fold-defect-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a"] :sources sources)
        proposed (own-grant-driver w sources "v1")
        damaged (assoc-in proposed [:state :seams :read-ledger!]
                          (fn []
                            (let [records (attributed (:frames w))]
                              (assoc-in records [0 1 :dao.space/transaction :datoms]
                                        [[999 :unpublished/attribute 1 0 true]]))))
        st (:state (drive w damaged))]
    (is (= :yin.k/unsatisfied (:status st)))
    (is (= :fold-defect (:yin.k/reason (:detail st))))
    (is (nil? (:machine st)))
    (is (zero? @(:attaches damaged)))
    (is (empty? (requests-of w "holder-a" :yin.k/release)))))


(deftest failed-renewal-carriage-retains-original-reading-test
  (let [{:keys [sources]} (reader-source)
        medium (bounded-stream "lease-a" 1)
        w (world :holders ["holder-a"] :sources sources
                 :lease-media {"holder-a" medium})
        running (to-running w (driver-for w "holder-a" sources "v1"))
        _ (stream/append! medium :blocked)
        _ (reset! (:clock running) {:s 11})
        queued (drive w running)
        _ (reset! (:clock running) {:s 12})
        failed (drive w queued)]
    (is (nil? (get-in failed [:state :holder :last-renewal-at])))
    (is (= {:s 11} (get-in failed [:state :renewal :reading])))
    (is (= 1 (count (distinct (requests-of w "holder-a" :yin.k/renewal)))))
    (judge! w {:s 12})
    (let [settled (drive w (drive w failed))]
      (is (= {:s 11} (get-in settled [:state :holder :last-renewal-at]))))))


(deftest lost-control-replies-retry-unchanged-obligations-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a"] :sources sources)
        original (driver-for w "holder-a" sources "v1")
        reply-reader (get-in original [:state :seams :read-reply!])
        lost (assoc-in original [:state :seams :read-reply!]
                       (fn [] (reply-reader) nil))
        proposed (drive w (drive w lost))
        retried (drive w proposed)
        _ (is (= 2 (count (requests-of w "holder-a" :yin.k/proposal))))
        _ (is (= 1 (count (distinct (requests-of w "holder-a" :yin.k/proposal)))))
        running (to-running w (assoc-in retried [:state :seams :read-reply!] reply-reader))
        _ (reset! (:clock running) {:s 31})
        ending (drive w (assoc-in running [:state :seams :read-reply!]
                                  (fn [] (reply-reader) nil)))
        retrying (drive w ending)]
    (is (= :releasing (get-in retrying [:state :phase])))
    (is (= 2 (count (requests-of w "holder-a" :yin.k/release))))
    (is (= 1 (count (distinct (requests-of w "holder-a" :yin.k/release)))))
    (let [released (drive w (assoc-in retrying [:state :seams :read-reply!] reply-reader))]
      (is (= :failed (get-in released [:state :phase])))
      (is (true? (get-in released [:state :release :carried]))))))


(deftest release-does-not-finish-on-queue-acceptance-test
  (let [{:keys [sources]} (reader-source)
        medium (bounded-stream "lease-a" 1)
        w (world :holders ["holder-a"] :sources sources
                 :lease-media {"holder-a" medium})
        running (to-running w (driver-for w "holder-a" sources "v1"))
        _ (stream/append! medium :blocked)
        _ (reset! (:clock running) {:s 31})
        ending (drive w running)
        suspended (drive w ending)]
    (is (= :releasing (:phase (:state suspended))))
    (is (not (true? (get-in suspended [:state :detail :dao.lease/released]))))
    (is (= 1 (count (distinct (requests-of w "holder-a" :yin.k/release)))))
    (judge! w {:s 32})
    (let [done (drive-to w suspended (phase-of :failed) 6 :judge {:s 33})]
      (is (true? (get-in done [:state :release :carried])))
      (is (nil? (get-in (authority/projection (:a w))
                        [:occurrences occurrence :dao.lease/lease]))))))


(deftest unreadable-ledger-still-ends-at-local-bound-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a"] :sources sources)
        running (to-running w (driver-for w "holder-a" sources "v1"))
        _ (reset! (:clock running) {:s 31})
        st (driver/step (assoc-in (:state running) [:seams :read-ledger!] (constantly nil)))]
    (is (= :releasing (:phase st)))
    (is (= :lease-bound (:cause (:detail st))))
    (is (= :ended (vm/gate-mode (:machine st))))))


(defn- effect-stream
  [identity append close]
  (reify
    stream/IDaoStreamDescriptor
    (descriptor
      [_]
      {:dao.stream/outcome :dao.stream/ok
       :dao.stream/identity identity
       :dao.stream/descriptor {:dao.stream/type :dao.stream.test/channel}})


    stream/IDaoStreamWriter

    (append! [_ value] (append value))


    stream/IDaoStreamClosable

    (close! [_] (close))))


(deftest tenure-is-rechecked-between-bare-effects-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a"] :sources sources)
        running (to-running w (driver-for w "holder-a" sources "v1"))
        emitted (atom [])
        handle (effect-stream "bare"
                              (fn [value]
                                (swap! emitted conj value)
                                (reset! (:clock running) {:s 31})
                                {:dao.stream/outcome :dao.stream/ok})
                              (fn []
                                (swap! emitted conj :closed)
                                {:dao.stream/outcome :dao.stream/ok}))
        entry (-> (first (get-in running [:state :machine :wait-set]))
                  (dissoc :cursor-ref)
                  (assoc :reason :put :stream-id :bare :datom 1 :yin.k/issue 0))
        machine (-> (get-in running [:state :machine])
                    (assoc :wait-set [entry (assoc entry :id :second :datom 2 :yin.k/issue 2)]
                           :yin.k/closes [{:stream-id :bare :yin.k/issue 1}])
                    (assoc-in [:resources :bare] handle)
                    (assoc-in [:yin.k/custody :protection "bare"] :at-least-once)
                    (assoc-in [:installs :child :vm]
                              (assoc (s/halted-machine) :yin.k/gate :running)))
        st (driver/step (assoc (:state running) :machine machine))]
    (is (= [1] @emitted))
    (is (= :releasing (:phase st)))
    (is (= :ended (vm/gate-mode (:machine st))))
    (is (= :ended (vm/gate-mode (get-in st [:machine :installs :child :vm]))))
    (is (some? (:release st)))))


(deftest the-cycles-run-end-folds-into-the-drains-one-diagnostic-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a"] :sources sources)
        running (to-running w (drive w (drive w (driver-for w "holder-a"
                                                            sources "v1"))))
        _ (is (= :running (:phase (:state running))))
        ;; the machine parks on a declared fail-stop put: the cycle's
        ;; writer arm ends the run before performing the write
        performed (atom 0)
        handle (effect-stream "bare" (fn [_]
                                       (swap! performed inc)
                                       {:dao.stream/outcome :dao.stream/ok})
                              (fn [] {:dao.stream/outcome :dao.stream/ok}))
        entry (-> (first (get-in running [:state :machine :wait-set]))
                  (dissoc :cursor-ref)
                  (assoc :reason :put :stream-id :bare :datom 1 :yin.k/issue 0))
        fail-stop (-> (get-in running [:state :machine])
                      (assoc :wait-set [entry])
                      (assoc-in [:resources :bare] handle)
                      (assoc-in [:yin.k/custody :protection "bare"] :fail-stop))
        ;; the renewal is sent at 11 and carried; its reply waits
        ;; unread, and the clock crosses the bound exactly when it is
        ;; released into the post-cycle drain
        _ (reset! (:clock running) {:s 11})
        renewed (drive w running)
        _ (is (some? (get-in renewed [:state :renewal])))
        l (:lease (:state renewed))
        reply-reader (get-in renewed [:state :seams :read-reply!])
        past-bound (atom false)
        armed (-> renewed
                  (assoc-in [:state :machine] fail-stop)
                  (assoc-in [:state :seams :read-reply!]
                            (let [served (atom false)]
                              (fn []
                                (if @served
                                  (do (reset! past-bound true)
                                      (reply-reader))
                                  (do (reset! served true) nil)))))
                  (assoc-in [:state :seams :clock]
                            (fn [] (if @past-bound {:s 31} {:s 11}))))
        st (driver/step (:state armed))]
    (is (= :releasing (:phase st)))
    (is (= :yin.k/ended (:status st)))
    (is (= {:cause :lease-bound :dao.lease/lease l :also-ended :fail-stop}
           (:detail st))
        "the drain's end carries the cycle's own cause folded in")
    (is (nil? (:run-end st)) "the state carries no run end past it")
    (is (zero? @performed) "the fail-stop write was never performed")
    (is (= [{:yin.k/diagnostic :yin.k/run-ended
             :yin.k/occurrence occurrence
             :dao.lease/lease l
             :yin.k/end {:cause :lease-bound
                         :dao.lease/lease l
                         :also-ended :fail-stop}}]
           (read-all (:diagnostics armed)))
        "one diagnostic ends the run, carrying both causes (r3 1.6)")))


(deftest computation-cannot-spend-tenure-and-then-perform-io-test
  (let [{:keys [sources]} (reader-source)
        w (world :holders ["holder-a"] :sources sources)
        running (to-running w (driver-for w "holder-a" sources "v1"))
        emitted (atom 0)
        handle (effect-stream "bare" (fn [_]
                                       (swap! emitted inc)
                                       {:dao.stream/outcome :dao.stream/ok})
                              (fn [] {:dao.stream/outcome :dao.stream/ok}))
        [reference machine] (engine/attach-resource
                              (assoc (get-in running [:state :machine]) :wait-set []) handle)
        machine (-> machine
                    (assoc-in [:primitives 'expire]
                              (fn [] (reset! (:clock running) {:s 31}) nil))
                    (assoc-in [:yin.k/custody :protection "bare"] :at-least-once)
                    (assoc :store {'w reference})
                    (s/load-ast (s/then (s/app (s/v 'expire))
                                        {:type :stream/put :target (s/v 'w) :val (s/lit 1)})))
        st (driver/step (assoc (:state running) :machine machine))]
    (is (zero? @emitted))
    (is (= :releasing (:phase st)))
    (is (= :lease-bound (:cause (:detail st))))))


(deftest enrolled-write-after-activation-routes-both-answer-paths-test
  (doseq [projected? [false true]]
    (let [w (world :holders ["holder-a"] :sources {})
          target (:target w)
          source (int-stream "prog-r")
          sink (int-stream target)
          base (s/load-ast (s/new-machine)
                           (s/then {:type :stream/next :source (s/v 'c)}
                                   (s/then {:type :stream/put :target (s/v 'w) :val (s/lit "effect")}
                                           (s/lit :done))))
          [source-ref base] (engine/attach-resource base source)
          [cursor-ref base] (engine/handle-cursor base {:stream source-ref} :k1)
          [target-ref base] (engine/attach-resource base sink)
          machine (vm/run (assoc base :store {'c cursor-ref 'w target-ref} :yin.k/gate :running))
          served (fn [handle]
                   {:dao.stream/identity (stream-id handle)
                    :dao.stream/channel {:dao.stream/type :dao.stream.test/channel
                                         :dao.stream/identity (stream-id handle)}})
          lifted (lift-under machine (header-of 0 #{target} false) served)
          _ (grant/offer! (:a w) (:store w) (:address lifted) (:bytes lifted) "carrier")
          d (driver-for w "holder-a" {"v1" lifted} "v1"
                        :protection-as {"prog-r" :at-least-once target :enrolled}
                        :attach-table {target sink})
          reply-reader (get-in d [:state :seams :read-reply!])
          d (if projected?
              (assoc-in d [:state :seams :read-reply!]
                        (fn []
                          (loop [] (when-let [pair (reply-reader)]
                                     (if (= :yin.k/admit (:yin.k/reply (second pair)))
                                       (recur) pair)))))
              (assoc-in d [:state :seams :read-outcome!] (constantly nil)))
          running (to-running w d)
          _ (stream/append! (:prog running) "input")
          done (drive-to w running (phase-of :safepoint) 12)
          requests (requests-of w "holder-a" :yin.k/admit)]
      (is (seq requests))
      (is (= :done (get-in done [:state :machine :value])))
      (is (= ["effect"] (read-all (authority/target-reader (:a w) target)))))))


(deftest terminal-bare-link-failure-retains-release-and-is-not-retried-test
  (let [{:keys [sources]} (reader-source)
        medium (bounded-stream "lease-a" 1)
        w (world :holders ["holder-a"] :sources sources
                 :lease-media {"holder-a" medium})
        running (to-running w (driver-for w "holder-a" sources "v1"))
        calls (atom 0)
        handle (effect-stream "link"
                              (fn [_]
                                (swap! calls inc)
                                {:dao.stream/outcome :dao.stream/closed})
                              (fn [] {:dao.stream/outcome :dao.stream/ok}))
        entry (-> (first (get-in running [:state :machine :wait-set]))
                  (dissoc :cursor-ref :stream-id)
                  (assoc :reason :link-request :link-id "link" :cursor 0
                         :name 'missing :envelope {} :request module/link-request-resource
                         :response module/link-response-resource))
        machine (-> (get-in running [:state :machine])
                    (assoc :wait-set [entry])
                    (assoc-in [:resources module/link-request-resource] handle)
                    (assoc-in [:yin.k/custody :protection "link"] :at-least-once))
        _ (stream/append! medium :blocked)
        st (try (driver/step (assoc (:state running) :machine machine))
                (catch #?(:cljd Object :clj Exception :cljs :default) _ nil))]
    (is (= 1 @calls))
    (is (= :releasing (:phase st)))
    (is (= :ended (vm/gate-mode (:machine st))))
    (is (some? (:failure (:detail st))))
    (when st
      (let [continued (drive w (assoc running :state st))]
        (is (= :releasing (:phase (:state continued))))
        (is (= 1 @calls))))))
