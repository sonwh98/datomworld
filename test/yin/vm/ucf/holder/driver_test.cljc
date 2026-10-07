(ns yin.vm.ucf.holder.driver-test
  "D13 and D14: the custody driver (UCF 7.7.2 to 7.7.8, 7.8, 7.9; r3
   1.5 to 1.10; the D9 header and D10 lower-inputs rulings; linker-dht
   14.2.4 rows 2 and 7).  Every checkpoint is a real lift of a real
   machine the engine parked; every offer, grant, binding, input and
   admission is the real authority's, reached through the real front
   over the memory substrate; every lease the driver accepts was
   granted by the real judge or recorded by the real ledger writer.
   The composition the driver owes -- the front streams, the ledger
   reader, the lease clock and, since D14, the progress journal, the
   content store and the exporter's server -- is supplied as functions
   and handles over that world."
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


(deftest stalled-public-entries-do-not-progress-test
  (let [state {:phase :stalled :status :yin.k/unsatisfied}]
    (is (= state (driver/abort state)))
    (is (= state (driver/enroll state)))
    (is (= state (driver/hand-off state)))))


(deftest journal-fold-keeps-unmatched-retry-intents-test
  (let [intent {:yin.k/journal :yin.k/intent :yin.k/action :yin.k/offer
                :yin.k/occurrence "source"
                :yin.k/request {:yin.k/request-id "offer"}}
        attempt {:yin.k/journal :yin.k/attempt :yin.k/action :yin.k/offer
                 :yin.k/occurrence "source" :yin.k/append :dao.stream/full}
        folded (driver/fold-journal [intent attempt intent])]
    (is (= [{:append :dao.stream/full} {}] (get-in folded [:attempts "source"])))
    (is (= [intent intent] (:ordered-intents folded)))))


(deftest journal-transport-failure-is-not-end-of-history-test
  (let [handle (reify stream/IDaoStreamReader
                 (cursor [_ _] {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor 0})

                 (next [_ _] {:dao.stream/outcome :dao.stream/transport-error}))
        failure (try (driver/journal-records handle) nil
                     (catch #?(:cljd Object :clj Throwable :cljs :default) failure failure))]
    (is (some? failure))))


(deftest journal-gap-and-malformed-records-refuse-test
  (doseq [value [{:yin.k/journal :unknown} :not-a-record]]
    (let [reads (atom 0)
          handle (reify stream/IDaoStreamReader
                   (cursor
                     [_ _]
                     {:dao.stream/outcome :dao.stream/ok
                      :dao.stream/cursor {:dao.stream.memory-log/identity "journal"
                                          :dao.stream.memory-log/position 0}})

                   (next
                     [_ _]
                     (swap! reads inc)
                     {:dao.stream/outcome :dao.stream/ok
                      :dao.stream/cursor {:dao.stream.memory-log/identity "journal"
                                          :dao.stream.memory-log/position 2}
                      :dao.stream/value value}))
          failure (try (driver/journal-records handle) nil
                       (catch #?(:cljd Object :clj Throwable :cljs :default) failure failure))]
      (is (some? failure))
      (is (= 1 @reads)))))


(deftest a-known-journal-tag-with-missing-fields-is-not-a-valid-frame-test
  (let [reads (atom 0)
        handle (reify stream/IDaoStreamReader
                 (cursor [_ _] {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor 0})

                 (next
                   [_ _]
                   (if (= 1 (swap! reads inc))
                     {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor 1
                      :dao.stream/value {:yin.k/journal :yin.k/intent}}
                     {:dao.stream/outcome :dao.stream/blocked})))
        failure (try (driver/journal-records handle) nil
                     (catch #?(:cljd Object :clj Throwable :cljs :default) failure failure))]
    (is (some? failure))
    (is (= 1 @reads))))


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


(defn- fresh-journal
  "A composition-supplied progress journal over fresh frames; the atom
   is kept, so the same frames reopen."
  []
  (let [frames (atom [])]
    {:frames frames
     :journal (:dao.stream/handle
                (journal/open! (journal/memory-backend frames nil)))}))


(defn- reopened-journal
  "A fresh handle over `frames` -- the reopen after a crash."
  [frames]
  (:dao.stream/handle (journal/open! (journal/memory-backend frames nil))))


(defn- cut-backend
  "A memory journal backend whose one write at `position` (a frame
   count) suffers `cut` -- :before-frame or :after-frame-before-visible
   -- exactly once, the crash windows of an uncertain append."
  [frames position cut]
  (let [inner (journal/memory-backend frames nil)
        armed (atom true)]
    (assoc inner
           :dao.stream.journal/write-frame!
           (fn [bs]
             (if (and @armed (= position (count @frames)))
               (do (reset! armed false)
                   (case cut
                     :before-frame
                     (throw (ex-info "cut before the frame" {}))
                     :after-frame-before-visible
                     (do (swap! frames conj bs)
                         (throw (ex-info "cut after the frame" {})))))
               ((:dao.stream.journal/write-frame! inner) bs))))))


(defn- driver-for
  "The candidate's step state for `holder` over variant `v` of
  `sources` (or the raw {:keys bytes address]} of `:raw`).  `:ledger`
  overrides the ledger read, `:protection-as` the protection
  declaration.  D14's seams -- the journal (fresh frames, kept for a
  reopen), the world's content store, the exporter's server and the
  carrier medium -- ride along."
  [w holder sources v & {:keys [ledger protection-as raw attach-table
                                journal-as]}]
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
        diagnostics (log)
        j (or journal-as (fresh-journal))]
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
               :append-diagnostic! (fn [d] (stream/append! diagnostics d))
               :serve! (serve-as "prog-r")
               :journal (:journal j)
               :store (:store w)
               :medium "carrier"})
     :clock clock
     :prog prog
     :attaches attaches
     :observations observations
     :diagnostics diagnostics
     :journal-frames (:frames j)}))


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
        (do (is (pred (:state d')) (pr-str (select-keys (:state d') [:phase :status :detail])))
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
    (is (= [:yin.k/intent :yin.k/attempt :yin.k/ack]
           (mapv :yin.k/journal
                 (filter #(= :yin.k/renewal (:yin.k/action %))
                         (driver/journal-records (get-in st [:seams :journal]))))))
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


(deftest renewal-journal-ack-cannot-cross-the-existing-bound-test
  (let [{:keys [sources]} (reader-source)
        world' (world :holders ["holder-a"] :sources sources)
        running (to-running world' (driver-for world' "holder-a" sources "v1"))
        _ (reset! (:clock running) {:s 11})
        queued (driver/step (:state running))
        _ (carry! world')
        _ (reset! (:clock running) {:s 12})
        progress (get-in queued [:seams :journal])
        wrapped (reify stream/IDaoStreamWriter
                  (append!
                    [_ record]
                    (let [answer (stream/append! progress record)]
                      (when (and (= :yin.k/ack (:yin.k/journal record))
                                 (= :yin.k/renewal (:yin.k/action record)))
                        (reset! (:clock running) {:s 31}))
                      answer)))
        settled (driver/step (assoc-in queued [:seams :journal] wrapped))]
    (is (= :releasing (:phase settled)))
    (is (= :lease-bound (:cause (:detail settled))))
    (is (nil? (get-in settled [:holder :last-renewal-at])))
    (is (= :ended (vm/gate-mode (:machine settled))))))


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


;; =============================================================================
;; D14: the source half -- mint once, journal before prepare, fence,
;; offer, then candidacy (14.2.4 row 2)
;; =============================================================================

(defn- source-config
  "The source's config over `machine` (kept, so a reopen reuses it) and
   the pieces the driver record carries: the clock, the program stream,
   the diagnostics."
  [w holder machine attach-table]
  (let [prog (int-stream "prog-r")
        clock (atom {:s 1})
        [attach! attaches] (counting
                             (attach-over (merge {"prog-r" prog}
                                                 attach-table)))
        diagnostics (log)]
    {:config {:me holder
              :machine machine
              :arbitration (arbitration-map)
              :protection {"prog-r" :at-least-once}
              :renewal-interval interval
              :receiver (s/new-machine)
              :attach! attach!
              :append-request! (fn [request]
                                 (stream/append!
                                   (get (:inbounds w) holder) request))
              :read-reply! (reader-over (get (:replies w) holder) arb)
              :read-outcome! (reader-over (:outcomes w) arb)
              :read-ledger! (fn [] (attributed (:frames w)))
              :clock (fn [] @clock)
              :observe! (fn [h op arg]
                          (case op
                            :next (stream/next h arg)
                            :cursor (stream/cursor h arg)))
              :append-diagnostic! (fn [d] (stream/append! diagnostics d))
              :serve! (serve-as "prog-r")
              :store (:store w)
              :medium "carrier"}
     :clock clock
     :prog prog
     :attaches attaches
     :diagnostics diagnostics}))


(defn- source-driver
  "The source record over `machine`, with fresh journal frames."
  [w holder machine attach-table]
  (let [rec (source-config w holder machine attach-table)
        j (fresh-journal)]
    (assoc rec
           :state (driver/source (assoc (:config rec)
                                        :journal (:journal j)))
           :journal-frames (:frames j))))


(defn- step-source
  "Step the source once and carry the fronts; with `:judge reading`
  also run one judge step at that reading."
  [w d & {:keys [judge]}]
  (let [state (driver/step (:state d))]
    (carry! w)
    (when judge (judge! w judge))
    (assoc d :state state)))


(defn- journal-of
  "The driver's journal records, decoded."
  [frames]
  (mapv #(:dao.stream.journal/value (cbor/decode %)) (rest @frames)))


(defn- kinds-of
  "The :yin.k/journal dispatch keys, in order."
  [frames]
  (mapv :yin.k/journal
        (remove #(contains? #{:yin.k/serve :yin.k/store} (:yin.k/action %))
                (journal-of frames))))


(defn- source-machine
  "A real machine parked on its first read of a fresh program stream."
  []
  (parked-reader-over (int-stream "prog-r")))


(deftest fenced-source-recovery-does-not-need-original-machine-test
  (let [world' (world :holders ["holder-a"])
        source (source-machine)
        prepared (nth (iterate #(step-source world' %)
                               (source-driver world' "holder-a" source nil)) 3)
        restored (driver/reopen (-> (:config prepared)
                                    (dissoc :machine)
                                    (assoc :journal (reopened-journal (:journal-frames prepared)))))
        encoded (export/encode (get-in restored [:export :m])
                               (get-in restored [:export :record]))]
    (is (= :exporting (:phase restored)))
    (is (= :exporting (vm/gate-mode (get-in restored [:export :m]))))
    (is (empty? (get-in restored [:export :m :wait-set])))
    (is (= 1 (count (get-in restored [:export :record :wait-set]))))
    (is (= (vec (get-in prepared [:state :export :bytes])) (vec (:bytes encoded))))))


(deftest enrollment-reconciles-before-retry-and-survives-reopen-test
  (let [world' (world :holders ["holder-a"])
        configured (source-driver world' "holder-a" (source-machine) nil)
        available (atom true)
        calls (atom 0)
        config (assoc (:config configured)
                      :journal (get-in configured [:state :seams :journal])
                      :read-ledger! (fn [] (when @available (attributed (:frames world'))))
                      :enroll! (fn []
                                 (swap! calls inc)
                                 (authority/enroll! (:a world'))
                                 (reset! available false)
                                 {:yin.k/status :suspended}))
        intended (driver/enroll (driver/source config))
        attempted (driver/enroll intended)
        waiting (driver/enroll attempted)]
    (is (= 1 @calls))
    (is (= :yin.k/unsatisfied (:status waiting)))
    (reset! available true)
    (let [recovered (driver/reopen (assoc config :journal
                                          (reopened-journal (:journal-frames configured))))
          reconciled (driver/enroll recovered)]
      (is (= 1 @calls))
      (is (= (get-in intended [:enroll :derived]) (get-in reconciled [:enroll :target]))))))


(deftest incomplete-preparation-recovery-stays-non-runnable-test
  (let [world' (world :holders ["holder-a"])
        minted (step-source world' (source-driver world' "holder-a" (source-machine) nil))
        restored (driver/reopen (assoc (:config minted)
                                       :journal (reopened-journal (:journal-frames minted))))]
    (is (= :stalled (:phase restored)))
    (is (nil? (:machine restored)))
    (is (= restored (driver/step restored)))))


(deftest an-empty-source-journal-does-not-revive-the-config-machine-test
  (let [world' (world :holders ["holder-a"])
        configured (source-driver world' "holder-a" (source-machine) nil)
        recovered (driver/reopen (assoc (:config configured) :journal
                                        (reopened-journal (:journal-frames configured))))]
    (is (= :stalled (:phase recovered)))
    (is (nil? (:machine recovered)))
    (is (nil? (:export recovered)))
    (is (= recovered (driver/step recovered)))))


(deftest serving-and-storage-have-write-ahead-brackets-test
  (let [world' (world :holders ["holder-a"])
        configured (source-driver world' "holder-a" (source-machine) nil)
        frames (:journal-frames configured)
        observed (atom [])
        original-serve (get-in configured [:state :seams :serve!])
        original-store (get-in configured [:state :seams :content-store])
        wrapped (-> configured
                    (assoc-in [:state :seams :serve!]
                              (fn [handle]
                                (swap! observed conj :serve)
                                (is (= :yin.k/intent (:yin.k/journal (last (journal-of frames)))))
                                (is (= :yin.k/serve (:yin.k/action (last (journal-of frames)))))
                                (original-serve handle)))
                    (assoc-in [:state :seams :content-store :put-bytes-fn]
                              (fn [address bytes]
                                (swap! observed conj :store)
                                (is (= :yin.k/intent (:yin.k/journal (last (journal-of frames)))))
                                (is (= :yin.k/store (:yin.k/action (last (journal-of frames)))))
                                ((:put-bytes-fn original-store) address bytes))))
        fenced (nth (iterate #(step-source world' %) wrapped) 3)
        acknowledgments (filter #(and (= :yin.k/ack (:yin.k/journal %))
                                      (contains? #{:yin.k/serve :yin.k/store} (:yin.k/action %)))
                                (journal-of frames))]
    (is (= [:serve :store :store] @observed))
    (is (= 3 (count acknowledgments)))
    (is (= :yin.k/fenced (:yin.k/journal (last (journal-of frames)))))
    (is (some? (get-in fenced [:state :export :address])))))


(deftest uncertain-successful-retry-cannot-abort-test
  (let [world' (world :holders ["holder-a"])
        frames (atom [])
        progress (:dao.stream/handle (journal/open! (cut-backend frames 12 :before-frame)))
        sends (atom 0)
        config (assoc (:config (source-config world' "holder-a" (source-machine) nil))
                      :journal progress
                      :append-request! (fn [_]
                                         {:dao.stream/outcome
                                          (if (= 1 (swap! sends inc))
                                            :dao.stream/full :dao.stream/ok)}))
        fenced (nth (iterate driver/step (driver/source config)) 3)
        full (driver/step fenced)
        uncertain (driver/step full)
        occurrence (get-in fenced [:export :occurrence])
        attempts (get-in (driver/fold-journal (journal-of frames)) [:attempts occurrence])]
    (is (= 2 @sends))
    (is (= :stalled (:phase uncertain)))
    (is (= uncertain (driver/abort uncertain)))
    (is (= [{:append :dao.stream/full} {}] attempts))
    (let [recovered (driver/reopen (assoc config :journal (reopened-journal frames)))
          refused (driver/abort recovered)]
      (is (= :exporting (:phase recovered)))
      (is (= :exporting (:phase refused)))
      (is (= :yin.k/refused (:status refused))))))


(deftest source-journal-cuts-stay-fenced-at-every-boundary-test
  (doseq [position (range 1 12)
          cut [:before-frame :after-frame-before-visible]]
    (testing (pr-str [position cut])
      (let [world' (world :holders ["holder-a"])
            frames (atom [])
            progress (:dao.stream/handle (journal/open! (cut-backend frames position cut)))
            configured (source-config world' "holder-a" (source-machine) nil)
            config (assoc (:config configured) :journal progress)
            crashed (loop [state (driver/source config) remaining 6]
                      (if (or (= :stalled (:phase state)) (zero? remaining))
                        state
                        (let [next-state (driver/step state)]
                          (carry! world')
                          (recur next-state (dec remaining)))))
            recovered (driver/reopen (-> config
                                         (dissoc :machine)
                                         (assoc :receiver (s/new-machine)
                                                :journal (reopened-journal frames))))]
        (is (= :stalled (:phase crashed)))
        (is (nil? (:machine recovered)))
        (is (<= (count (filter #(= :yin.k/minted (:yin.k/journal %))
                               (journal-of frames))) 1))
        (if (some #(= :yin.k/fenced (:yin.k/journal %)) (journal-of frames))
          (do (is (= :exporting (vm/gate-mode (get-in recovered [:export :m]))))
              (is (empty? (get-in recovered [:export :m :wait-set])))
              (is (= 1 (count (get-in recovered [:export :record :wait-set])))))
          (do (is (= :stalled (:phase recovered)))
              (is (nil? (:export recovered)))
              (is (= recovered (driver/step recovered)))))))))


(deftest mismatched-fence-body-refuses-before-attachment-test
  (let [world' (world :holders ["holder-a"])
        configured (source-driver world' "holder-a" (source-machine) nil)
        fenced (nth (iterate #(step-source world' %) configured) 3)
        cell (get-in fenced [:state :export])
        wrong-bytes (cbor/encode :not-the-body)
        wrong-address (jing/segment-key :not-the-body)
        _ ((:put-bytes-fn (:store world')) wrong-address wrong-bytes)
        _ (stream/append! (get-in fenced [:state :seams :journal])
                          {:yin.k/journal :yin.k/fenced :yin.k/occurrence (:occurrence cell)
                           :yin.k/address wrong-address :yin.k/record (:record-address cell)})
        failure (try (driver/reopen (assoc (:config configured) :journal
                                           (reopened-journal (:journal-frames configured)))) nil
                     (catch #?(:cljd Object :clj Throwable :cljs :default) failure failure))]
    (is (some? failure))
    (is (zero? @(get configured :attaches)))))


(deftest the-source-half-mints-fences-and-offers-before-candidacy-test
  (let [machine (source-machine)
        w (world :holders ["holder-a"])
        d (source-driver w "holder-a" machine nil)
        minted (nth (iterate #(step-source w %) d) 1)
        prepared (step-source w minted)
        fenced (step-source w prepared)
        offered (step-source w fenced)
        ;; the front admitted the offer; the next step reads the reply,
        ;; journals the acknowledgment and becomes a candidate
        candidacy (step-source w offered)
        st (:state candidacy)]
    (testing "the journal brackets in order"
      (is (= [:yin.k/minted :yin.k/fenced :yin.k/intent
              :yin.k/attempt :yin.k/ack]
             (kinds-of (:journal-frames candidacy)))
          "mint before prepare, fence before offer, intent before the
           send, attempt after it, the acknowledgment after evidence")
      (let [[mint fence intent _ ack] (remove #(contains? #{:yin.k/serve :yin.k/store}
                                                          (:yin.k/action %))
                                              (journal-of (:journal-frames candidacy)))]
        (is (= (:yin.k/occurrence mint) (:yin.k/occurrence fence)))
        (is (= (:yin.k/occurrence mint)
               (:yin.k/occurrence intent)
               (:yin.k/occurrence ack)))
        (is (= :yin.k/first (:yin.k/role mint)))
        (is (= 0 (get-in mint [:yin.k/header :yin.k/next-op-seq]))
            "a first export carries next-op-seq 0")
        (is (nil? (get-in mint [:yin.k/header :yin.k/origin]))
            "and no origin")
        (is (= #{(:target w)}
               (get-in mint [:yin.k/header :yin.k/enrolled]))
            "the enrolled set is the ledger fold's")
        (is (some? (:yin.k/bytes-at intent))
            "the offer intent names where the bytes stand")))
    (testing "the fenced record's projection and the body bytes are in
              the store"
      (let [[mint fence] (take 2 (filter #(contains? #{:yin.k/minted :yin.k/fenced}
                                                     (:yin.k/journal %))
                                         (journal-of (:journal-frames candidacy))))
            rec-bytes ((:get-bytes-fn (:store w)) (:yin.k/record fence)
                                                  ::absent)
            body ((:get-bytes-fn (:store w)) (:yin.k/address fence)
                                             ::absent)
            stored (cbor/decode rec-bytes)]
        (is (not= ::absent rec-bytes))
        (is (not= ::absent body))
        (is (= (get-in mint [:yin.k/header :yin.k/occurrence])
               (get-in stored [:yin.k/header :yin.k/occurrence])))
        (is (set? (:yin.k/descriptors stored))
            "the recovery object retains portable descriptors")))
    (testing "the source became a candidate over its own bytes"
      (is (= :proposing (:phase st)))
      (is (= (:yin.k/occurrence (first (journal-of
                                         (:journal-frames candidacy))))
             (:occurrence st)))
      (is (= (:yin.k/address
               (first (filter #(= :yin.k/fenced (:yin.k/journal %))
                              (journal-of (:journal-frames candidacy)))))
             (:address st)))
      (is (contains? (get-in (authority/projection (:a w))
                             [:occurrences (:occurrence st)
                              :yin.k/variants])
                     (:address st))
          "the offer was admitted: the address is an admitted variant"))
    (testing "the candidacy wins its own grant and runs"
      (let [run (drive-to w candidacy (phase-of :running) 8 :judge {:s 2})]
        (is (= :running (:phase (:state run))))
        (is (= (:occurrence st) (get-in run [:state :occurrence])))))))


(deftest a-fork-lift-answers-the-bytes-without-a-journal-test
  (let [machine (source-machine)
        w (world :holders ["holder-a"])
        d (source-driver w "holder-a" machine nil)
        ;; the composition's fork decision: no arbitration
        d (update-in d [:state :export] assoc :role nil :arbitration nil)
        lifted (drive-to w d (fn [st] (= :lifted (:phase st))) 5)]
    (is (= :lifted (:phase (:state lifted))))
    (is (= :yin.k/ok (:status (:state lifted))))
    (is (some? (get-in lifted [:state :detail :bytes])))
    (is (= [] (journal-of (:journal-frames lifted)))
        "no occurrence, no offer, no journal bracket")))


(defn- same-recovery?
  "Reopen the durable fenced snapshot and compare its carried state."
  [_world d]
  (let [machine (get-in d [:state :export :machine])
        reopened (driver/reopen (assoc (:config d)
                                       :machine machine
                                       :journal (reopened-journal
                                                  (:journal-frames d))))
        d' (assoc d :state reopened)]
    (is (some? reopened))
    (is (= (get-in d [:state :export :occurrence])
           (get-in d' [:state :export :occurrence]))
        "the same occurrence, never reminted")
    (is (= (set (map #(select-keys % [:dao.stream/identity :dao.stream/channel])
                     (vals (get-in d [:state :export :record :served]))))
           (set (map #(select-keys % [:dao.stream/identity :dao.stream/channel])
                     (vals (get-in d' [:state :export :record :served])))))
        "the same descriptors under fresh private resource ids")
    (when (nil? (get-in d [:state :export :address]))
      (is (= (get-in d [:state :export :record :wait-set])
             (get-in d' [:state :export :record :wait-set]))
          "the same waits, re-entered from the composition's machine"))
    d'))


(deftest a-crash-at-each-source-boundary-reopens-fenced-test
  (testing "after the mint, before prepare"
    (let [machine (source-machine)
          w (world :holders ["holder-a"])
          d (step-source w (source-driver w "holder-a" machine nil))
          _ (is (= [:yin.k/minted] (kinds-of (:journal-frames d))))
          recovered (driver/reopen (assoc (:config d) :journal
                                          (reopened-journal (:journal-frames d))))]
      (is (= :stalled (:phase recovered)))
      (is (nil? (:machine recovered)))
      (is (= recovered (driver/step recovered)))
      (is (= 1 (count (filter #(= :yin.k/minted %)
                              (kinds-of (:journal-frames d)))))
          "incomplete preparation never remints or manufactures waits")))
  (testing "after the fence, before the offer"
    (let [machine (source-machine)
          w (world :holders ["holder-a"])
          d (nth (iterate #(step-source w %)
                          (source-driver w "holder-a" machine nil))
                 3)
          _ (is (= [:yin.k/minted :yin.k/fenced]
                   (kinds-of (:journal-frames d))))
          d' (same-recovery? w d)
          run (drive-to w d' (phase-of :running) 10 :judge {:s 2})]
      (is (= :running (:phase (:state run))))
      (is (= 1 (count (filter #(= :yin.k/minted %)
                              (kinds-of (:journal-frames run))))))))
  (testing "after the offer send, before the acknowledgment"
    (let [machine (source-machine)
          w (world :holders ["holder-a"])
          d (nth (iterate #(step-source w %)
                          (source-driver w "holder-a" machine nil))
                 4)
          _ (is (= [:yin.k/minted :yin.k/fenced :yin.k/intent
                    :yin.k/attempt]
                   (kinds-of (:journal-frames d))))
          ;; the front already answered; the reply waits unread, as a
          ;; crash between send and acknowledgment leaves it
          d' (same-recovery? w d)
          run (drive-to w d' (phase-of :running) 10 :judge {:s 2})]
      (is (= :running (:phase (:state run))))
      (is (= 1 (count (distinct (map :yin.k/request-id
                                     (requests-of w "holder-a"
                                                  :yin.k/offer)))))
          "the resent offer request is the identical one")
      (is (= 1 (count (filter #(= :yin.k/offered (:yin.k/custody %))
                              (frame-facts (:frames w)))))
          "the ledger holds one offer fact: the resend replayed"))))


(deftest a-crash-between-proposal-send-and-answer-resends-the-stable-id-test
  (let [machine (source-machine)
        w (world :holders ["holder-a"])
        d (source-driver w "holder-a" machine nil)
        candidacy (nth (iterate #(step-source w %) d) 6)
        _ (is (= :proposing (:phase (:state candidacy))))
        pid (get-in candidacy [:state :proposal :id])
        _ (is (some? pid))
        ;; the judge grants on the sent proposal; the reply and the
        ;; grant are unread by the crashed process
        _ (judge! w {:s 2})
        reopened (driver/reopen (assoc (:config candidacy)
                                       :journal (reopened-journal
                                                  (:journal-frames candidacy))))
        d' (assoc candidacy :state reopened)]
    (is (= :proposing (:phase reopened)))
    (is (= pid (get-in reopened [:proposal :id]))
        "the unanswered proposal keeps its stable id")
    (is (= pid (:dao.lease/proposal
                 (first (requests-of w "holder-a" :yin.k/proposal))))
        "and it is the one that was sent")
    (let [run (drive-to w d' (phase-of :running) 8)]
      (is (= :running (:phase (:state run))))
      (is (= 1 (count (filter #(= :dao.lease/accepted
                                  (:dao.lease/status %))
                              (frame-facts (:frames w)))))
          "no second grant resulted: the resend was the same proposal
           and the occurrence was already held by it"))))


(deftest candidate-without-source-mint-restores-proposal-carriage-test
  (let [{:keys [sources]} (reader-source)
        world' (world :holders ["holder-a"] :sources sources)
        configured (driver-for world' "holder-a" sources "v1")
        proposed (nth (iterate #(drive world' %) configured) 2)
        original-id (get-in proposed [:state :proposal :id])
        state (:state proposed)
        config (merge (select-keys state [:me :bytes :address :receiver :protection
                                          :renewal-interval :units])
                      (:seams state) {:store (get-in state [:seams :content-store])})
        recovered (driver/reopen (assoc config :journal
                                        (reopened-journal (:journal-frames proposed))))]
    (is (some? original-id))
    (is (= :proposing (:phase recovered)))
    (is (= arb (:arbitration recovered)))
    (is (= original-id (get-in recovered [:proposal :id])))
    (let [running (drive-to world' (assoc proposed :state recovered)
                            (phase-of :running) 8 :judge {:s 2})]
      (is (= :running (:phase (:state running)))))))


(deftest accepted-grant-is-durable-before-lower-attachment-test
  (let [{:keys [sources]} (reader-source)
        world' (world :holders ["holder-a"] :sources sources)
        frames (atom [])
        progress (:dao.stream/handle (journal/open! (cut-backend frames 4 :after-frame-before-visible)))
        configured (driver-for world' "holder-a" sources "v1"
                               :journal-as {:journal progress :frames frames})
        stalled (drive-to world' configured (phase-of :stalled) 8 :judge {:s 2})]
    (is (zero? @(:attaches configured)))
    (is (nil? (get-in stalled [:state :machine])))
    (is (some #(= :yin.k/grant (:yin.k/action %)) (journal-of frames)))))


(deftest an-uncertain-journal-append-stalls-the-driver-test
  (let [machine (source-machine)
        w (world :holders ["holder-a"])
        frames (atom [])
        j (:dao.stream/handle
            (journal/open! (cut-backend frames 9
                                        :after-frame-before-visible)))
        rec (source-config w "holder-a" machine nil)
        d (assoc rec
                 :state (driver/source (assoc (:config rec) :journal j))
                 :journal-frames frames)
        ;; header is frame 0; the mint frame 1; the fence frame 2; the
        ;; offer intent's write cuts after the frame, before visibility
        d1 (step-source w d)
        d2 (step-source w d1)
        d3 (step-source w d2)
        _ (is (= [:yin.k/minted :yin.k/fenced] (kinds-of frames)))
        stalled (step-source w d3)
        st (:state stalled)
        before (inbound w "holder-a")]
    (testing "everything stops"
      (is (= :stalled (:phase st)))
      (is (= :yin.k/unsatisfied (:status st)))
      (is (= :journal-uncertain (:yin.k/reason (:detail st))))
      (let [again (step-source w stalled)
            again' (step-source w again)]
        (is (= st (:state again)))
        (is (= st (:state again')))
        (is (= before (inbound w "holder-a"))
            "no send of any kind left the stalled driver")))
    (testing "only a reopen unstalls it"
      (let [reopened (driver/reopen (assoc (:config stalled)
                                           :machine machine
                                           :journal (reopened-journal
                                                      frames)))
            d' (assoc stalled :state reopened)]
        (is (= :exporting (:phase reopened)))
        (is (= 10 (count @frames))
            "the cut frame stands: the intent is durable")
        (let [run (drive-to w d' (phase-of :running) 10 :judge {:s 2})]
          (is (= :running (:phase (:state run))))
          (is (= [:yin.k/minted :yin.k/fenced :yin.k/intent :yin.k/intent
                  :yin.k/attempt :yin.k/ack]
                 (take 6 (kinds-of frames)))
              "the reopened journal continues from the standing intent"))))))


(deftest a-restarted-holder-releases-and-re-proposes-test
  (let [machine (source-machine)
        w (world :holders ["holder-a"])
        d (source-driver w "holder-a" machine nil)
        running (drive-to w d (phase-of :running) 10 :judge {:s 2})
        _ (is (= :running (:phase (:state running))))
        ;; the crash: the process died with its machine
        reopened (driver/reopen (assoc (:config running)
                                       :journal (reopened-journal
                                                  (:journal-frames running))))
        d' (assoc running :state reopened)]
    (testing "the restarted driver never runs"
      (is (= :releasing (:phase reopened)))
      (is (= :yin.k/ended (:status reopened)))
      (is (= :restarted (:cause (:detail reopened))))
      (is (nil? (:machine reopened))))
    (testing "it releases, then re-enters as a candidate"
      (let [proposing (drive-to w d' (phase-of :proposing) 8 :judge {:s 3})
            st (:state proposing)]
        (is (= 1 (count (distinct (requests-of w "holder-a"
                                               :yin.k/release)))))
        (is (= :proposing (:phase st)))
        (is (nil? (:machine st)))
        (let [run (drive-to w proposing (phase-of :running) 10
                            :judge {:s 4})
              run-st (:state run)]
          (is (= :running (:phase run-st)))
          (is (= (get-in running [:state :occurrence])
                 (:occurrence run-st))
              "the regrant is of the same occurrence")
          (is (= 0 (get-in run-st [:machine :yin.k/custody :input :next]))
              "a regrant replays its input from zero"))))))


;; =============================================================================
;; D14: the exit half -- successor, report, release, closure
;; (14.2.4 row 7)
;; =============================================================================

(defn- to-halted-run
  "Drive a source through its own grant to a run whose machine has
   halted (the program stream fed one value), past the safepoint
   arming."
  [w d]
  (let [candidacy (nth (iterate #(step-source w %) d) 5)
        run (drive-to w candidacy (phase-of :running) 8 :judge {:s 2})
        _ (stream/append! (:prog run) "B")
        halted (drive-to w run (phase-of :safepoint) 8)]
    halted))


(defn- hand-off-run
  "Drive a source to a run parked on its read, then hand the park off:
   the composition's explicit export of a parked continuation."
  [w d]
  (let [candidacy (nth (iterate #(step-source w %) d) 5)
        run (drive-to w candidacy (phase-of :running) 8 :judge {:s 2})
        _ (is (= :running (:phase (:state run))))
        handed (driver/hand-off (:state run))]
    (is (= :safepoint (:phase handed)))
    (assoc run :state handed)))


(deftest a-halt-completes-its-exit-with-a-result-body-test
  (let [machine (source-machine)
        w (world :holders ["holder-a"])
        d (to-halted-run w (source-driver w "holder-a" machine nil))
        o (:occurrence (:state d))
        l (:lease (:state d))
        exited (drive-to w d (phase-of :exited) 20 :judge {:s 3})
        st (:state exited)
        projection (authority/projection (:a w))
        closed (get-in projection [:occurrences o :yin.k/closed])
        [mint fence] (take-last 2 (filter #(contains?
                                             #{:yin.k/minted :yin.k/fenced}
                                             (:yin.k/journal %))
                                          (journal-of (:journal-frames exited))))]
    (is (= :exited (:phase st)))
    (is (= :yin.k/ok (:status st)))
    (is (= :yin.k/result (:yin.k/role mint)))
    (is (= (:yin.k/result (:detail st)) (:yin.k/address fence)))
    (is (not= ::absent
              ((:get-bytes-fn (:store w)) (:yin.k/result (:detail st))
                                          ::absent))
        "the result body stands in the content store")
    (is (= l (:dao.lease/lease closed)))
    (is (= (:yin.k/result (:detail st)) (:yin.k/result closed))
        "the closure's edge is terminal to the result's address")
    (is (nil? (:yin.k/successor closed)))
    (is (= 1 (count (filter #(= :yin.k/resumed (:yin.k/custody %))
                            (frame-facts (:frames w)))))
        "one report, carrying the result body")
    (is (nil? (get-in projection [:occurrences o :dao.lease/lease]))
        "the occurrence is closed and unheld")))


(deftest a-parked-continuation-completes-its-exit-test
  (let [machine (source-machine)
        w (world :holders ["holder-a" "holder-b"])
        d (hand-off-run w (source-driver w "holder-a" machine nil))
        o (:occurrence (:state d))
        exited (drive-to w d (phase-of :exited) 24 :judge {:s 3})
        st (:state exited)
        projection (authority/projection (:a w))
        closed (get-in projection [:occurrences o :yin.k/closed])
        mints (filter #(= :yin.k/minted (:yin.k/journal %))
                      (journal-of (:journal-frames exited)))
        successor (:yin.k/successor (:detail st))]
    (is (= :exited (:phase st)))
    (is (= :yin.k/successor (:yin.k/role (last mints))))
    (is (= 2 (count mints))
        "the occurrence and its successor, one mint each")
    (is (some? successor))
    (is (= successor (:yin.k/successor closed))
        "the closure's edge names the reported successor")
    (testing "the successor's offer was admitted once"
      (is (contains? (get-in projection [:occurrences successor
                                         :yin.k/variants])
                     (:yin.k/address (:detail st))))
      (is (= 1 (count (filter #(and (= :yin.k/offered (:yin.k/custody %))
                                    (= successor (:yin.k/occurrence %)))
                              (frame-facts (:frames w)))))))
    (testing "the chain continues: a candidate runs the successor"
      (let [bytes ((:get-bytes-fn (:store w))
                   (:yin.k/address (:detail st)) ::absent)
            _ (is (not= ::absent bytes))
            b (driver-for w "holder-b" nil nil
                          :raw {:bytes bytes
                                :address (:yin.k/address (:detail st))})
            run (drive-to w b (phase-of :running) 10 :judge {:s 4})]
        (is (= :running (:phase (:state run))))
        (is (= successor (:occurrence (:state run))))))))


(deftest exit-cuts-at-each-boundary-recover-from-the-journal-test
  (testing "cut after the successor append, before the report"
    (let [machine (source-machine)
          w (world :holders ["holder-a"])
          d (hand-off-run w (source-driver w "holder-a" machine nil))
          ;; mint, prepare, fence, the offer send
          cut (nth (iterate #(step-source w %) d) 4)
          _ (is (= [:yin.k/minted :yin.k/fenced :yin.k/intent
                    :yin.k/attempt]
                   (take-last 4 (kinds-of (:journal-frames cut)))))
          successor (get-in cut [:state :exit :occurrence])
          reopened (driver/reopen (assoc (:config cut)
                                         :journal (reopened-journal
                                                    (:journal-frames cut))))
          d' (assoc cut :state reopened)
          exited (drive-to w d' (phase-of :proposing) 8 :judge {:s 3})
          st (:state exited)]
      (is (= :proposing (:phase st)))
      (is (nil? (:machine st)))
      (is (= successor (get-in reopened [:exit :occurrence])))
      (is (empty? (requests-of w "holder-a" :yin.k/resumed))
          "restart cannot mint fresh report authority from a saved clock")
      (is (= 0 (count (filter #(= :yin.k/succeeded (:yin.k/custody %))
                              (frame-facts (:frames w)))))
          "release alone is not completion")))
  (testing "cut after the report, before the release"
    (let [machine (source-machine)
          w (world :holders ["holder-a"])
          d (hand-off-run w (source-driver w "holder-a" machine nil))
          ;; to the report: mint, prepare, fence, offer, report
          cut (nth (iterate #(step-source w %) d) 5)
          _ (is (some #(and (= :yin.k/intent (:yin.k/journal %))
                            (= :yin.k/resumed (:yin.k/action %)))
                      (journal-of (:journal-frames cut))))
          reopened (driver/reopen (assoc (:config cut)
                                         :journal (reopened-journal
                                                    (:journal-frames cut))))
          d' (assoc cut :state reopened)
          exited (drive-to w d' (phase-of :exited) 24 :judge {:s 3})]
      (is (= :exited (:phase (:state exited))))
      (is (= 1 (count (filter #(= :yin.k/resumed (:yin.k/custody %))
                              (frame-facts (:frames w)))))
          "the resent report replayed")))
  (testing "cut after the release, before the closure"
    (let [machine (source-machine)
          w (world :holders ["holder-a"])
          d (hand-off-run w (source-driver w "holder-a" machine nil))
          ;; through the release send and its reply
          cut (nth (iterate #(step-source w %) d) 7)
          reopened (driver/reopen (assoc (:config cut)
                                         :journal (reopened-journal
                                                    (:journal-frames cut))))
          d' (assoc cut :state reopened)
          exited (drive-to w d' (phase-of :exited) 24 :judge {:s 4})]
      (is (= :exited (:phase (:state exited))))
      (is (= 1 (count (distinct (map :yin.k/request-id
                                     (requests-of w "holder-a"
                                                  :yin.k/release)))))
          "the release left once, the resend the identical request"))))


(deftest delayed-original-report-carriage-after-restart-keeps-the-exit-test
  (let [world' (world :holders ["holder-a"])
        original (hand-off-run world' (source-driver world' "holder-a" (source-machine) nil))
        offered (nth (iterate #(step-source world' %) original) 4)
        sent (driver/step (:state offered))
        reopened (driver/reopen (-> (:config offered)
                                    (dissoc :machine)
                                    (assoc :clock (constantly {:s 100})
                                           :journal (reopened-journal (:journal-frames offered)))))
        cleanup (driver/step reopened)
        _ (carry! world')
        _ (judge! world' {:s 3})
        completed (drive-to world' (assoc offered :state cleanup) (phase-of :exited) 24 :judge {:s 3})]
    (is (some? (get-in sent [:exit :report])))
    (is (nil? (:machine cleanup)))
    (is (= :exited (:phase (:state completed))))
    (is (= (get-in sent [:exit :occurrence]) (:yin.k/successor (:detail (:state completed)))))
    (is (= 1 (count (filter #(= :yin.k/resumed (:yin.k/custody %))
                            (frame-facts (:frames world'))))))))


(deftest exit-journal-cuts-never-restore-execution-test
  (let [reference-world (world :holders ["holder-a"])
        reference (hand-off-run reference-world
                                (source-driver reference-world "holder-a" (source-machine) nil))
        initial-count (count @(:journal-frames reference))
        completed (drive-to reference-world reference (phase-of :exited) 24 :judge {:s 3})
        delta (- (count @(:journal-frames completed)) initial-count)]
    (is (pos? delta))
    (doseq [offset (range delta)
            cut [:before-frame :after-frame-before-visible]]
      (testing (pr-str [offset cut])
        (let [world' (world :holders ["holder-a"])
              original (hand-off-run world' (source-driver world' "holder-a" (source-machine) nil))
              frames (atom (vec @(:journal-frames original)))
              progress (:dao.stream/handle
                         (journal/open! (cut-backend frames (+ (count @frames) offset) cut)))
              initial (assoc-in (:state original) [:seams :journal] progress)
              crashed (loop [state initial remaining 30]
                        (if (or (= :stalled (:phase state)) (zero? remaining))
                          state
                          (let [next-state (driver/step state)]
                            (carry! world')
                            (judge! world' {:s 3})
                            (recur next-state (dec remaining)))))
              recovered (driver/reopen (-> (:config original)
                                           (dissoc :machine)
                                           (assoc :receiver (s/new-machine)
                                                  :clock (constantly {:s 100})
                                                  :journal (reopened-journal frames))))]
          (is (= :stalled (:phase crashed)))
          (is (nil? (:machine recovered)))
          (is (not (contains? #{:running :safepoint} (:phase recovered))))
          (when (get-in recovered [:exit :m])
            (is (= :exporting (vm/gate-mode (get-in recovered [:exit :m]))))
            (is (empty? (get-in recovered [:exit :m :wait-set]))))
          (is (nil? (:machine (driver/step recovered)))))))))


(deftest a-later-grant-outranks-an-earlier-completed-exit-test
  (let [world' (world :holders ["holder-a"])
        handed (hand-off-run world' (source-driver world' "holder-a" (source-machine) nil))
        completed (drive-to world' handed (phase-of :exited) 24 :judge {:s 3})
        address (get-in completed [:state :detail :yin.k/address])
        bytes ((:get-bytes-fn (:store world')) address nil)
        journal-as {:journal (get-in completed [:state :seams :journal])
                    :frames (:journal-frames completed)}
        next-candidate (driver-for world' "holder-a" nil nil
                                   :raw {:bytes bytes :address address}
                                   :journal-as journal-as)
        next-run (drive-to world' next-candidate (phase-of :running) 10 :judge {:s 4})
        state (:state next-run)
        config (merge (select-keys state [:me :bytes :address :receiver :protection
                                          :renewal-interval :units])
                      (:seams state) {:store (get-in state [:seams :content-store])})
        recovered (driver/reopen (assoc config :journal (reopened-journal (:journal-frames completed))))]
    (is (= :releasing (:phase recovered)))
    (is (= (:lease state) (:lease recovered)))
    (is (= (:lease state) (get-in recovered [:release :request :dao.lease/lease])))
    (is (nil? (:machine recovered)))))


;; =============================================================================
;; D14 residual 2: a release never closes without accepted completion
;; evidence (14.2.4 row 7)
;; =============================================================================

(deftest a-release-without-accepted-evidence-never-closes-test
  (let [machine (source-machine)
        w (world :holders ["holder-a" "holder-b"])
        d (drive-to w (source-driver w "holder-a" machine nil)
                    (phase-of :running) 10 :judge {:s 2})
        o (:occurrence (:state d))
        l (:lease (:state d))
        ;; the tenure is reclaimed under the holder: the run ends stale
        ;; and the driver releases -- cleanup, with no report standing
        _ (lapse-direct! w l :policy)
        ended (drive-to w d (phase-of :failed) 8)
        projection (authority/projection (:a w))]
    (is (= :failed (:phase (:state ended))))
    (is (= :stale (:cause (:detail (:state ended)))))
    (is (nil? (get-in projection [:occurrences o :yin.k/closed]))
        "no accepted completion evidence, no closure")
    (testing "the occurrence is regrantable under the ordinary policy"
      (let [_ (judge! w {:s 4})
            b (driver-for w "holder-b" nil nil
                          :raw {:bytes (:bytes (:state d))
                                :address (:address (:state d))})
            run (drive-to w b (phase-of :running) 10 :judge {:s 5})]
        (is (= :running (:phase (:state run))))
        (is (= o (:occurrence (:state run))))))))


(deftest after-a-replay-divergence-the-released-occurrence-is-regrantable-test
  (let [machine (source-machine)
        w (world :holders ["holder-a" "holder-b"])
        d (drive-to w (source-driver w "holder-a" machine nil)
                    (phase-of :running) 10 :judge {:s 2})
        o (:occurrence (:state d))
        _ (stream/append! (:prog d) "B")
        ;; tenure 1 records its read as an input
        _ (loop [x d k 0]
            (if (or (seq (requests-of w "holder-a" :yin.k/input))
                    (>= k 6))
              x
              (recur (step-source w x) (inc k))))
        _ (is (seq (requests-of w "holder-a" :yin.k/input)))
        ;; the holder crashes; the regrant replays under the prefix
        _ (judge! w {:s 40})
        b (driver-for w "holder-b" nil nil
                      :raw {:bytes (:bytes (:state d))
                            :address (:address (:state d))})
        b (drive-to w b (phase-of :running) 10 :judge {:s 41})
        ;; the replayed record matches no waiting entry: the machine's
        ;; one wait names a stream the record's source does not
        [different-stream changed] (engine/attach-resource
                                     (get-in b [:state :machine]) (int-stream "different-input"))
        cursor-id (get-in changed [:wait-set 0 :cursor-ref :id])
        diverged (assoc-in b [:state :machine]
                           (assoc-in changed [:resources cursor-id :stream-id]
                                     (:id different-stream)))
        ended (drive-to w (assoc b :state (driver/step
                                            (assoc-in (:state diverged)
                                                      [:seams :read-ledger!]
                                                      (fn []
                                                        (attributed
                                                          (:frames w))))))
                        (phase-of :failed) 8)
        detail (:detail (:state ended))]
    (is (= :divergence (:cause detail)) "genuine portable-input mismatch")
    (is (nil? (get-in (authority/projection (:a w))
                      [:occurrences o :yin.k/closed]))
        "no report ever stood")
    (testing "the occurrence is regrantable"
      (let [_ (judge! w {:s 45})
            c (driver-for w "holder-a" nil nil
                          :raw {:bytes (:bytes (:state d))
                                :address (:address (:state d))})
            run (drive-to w c (phase-of :running) 10 :judge {:s 46})]
        (is (= :running (:phase (:state run))))))))


(deftest after-an-intent-conflict-the-released-occurrence-stays-quarantined-test
  (let [w (world :holders ["holder-a" "holder-b"] :sources {})
        target-stream (int-stream (:target w))
        source (int-stream "prog-r")
        base (s/load-ast (s/new-machine)
                         (s/then {:type :stream/next :source (s/v 'c)}
                                 {:type :stream/put :target (s/v 'w) :val (s/lit "v")}))
        [source-ref base] (engine/attach-resource base source)
        [cursor-ref base] (engine/handle-cursor base {:stream source-ref} :k1)
        [target-ref base] (engine/attach-resource base target-stream)
        machine (vm/run (assoc base :store {'c cursor-ref 'w target-ref} :yin.k/gate :running))
        lifted (lift-under machine
                           (header-of 0 #{(:target w)} false)
                           (fn [h]
                             {:dao.stream/identity (stream-id h)
                              :dao.stream/channel
                              {:dao.stream/type :dao.stream.test/channel
                               :dao.stream/identity (stream-id h)}}))
        _ (grant/offer! (:a w) (:store w) (:address lifted)
                        (:bytes lifted) "carrier")
        d (driver-for w "holder-a" nil nil
                      :raw {:bytes (:bytes lifted)
                            :address (:address lifted)}
                      :protection-as {"prog-r" :at-least-once
                                      (:target w) :enrolled}
                      :attach-table {(:target w) target-stream})
        d (drive-to w d (phase-of :running) 10 :judge {:s 2})
        o (:occurrence (:state d))
        captured (atom nil)
        append-request (get-in d [:state :seams :append-request!])
        armed (assoc-in (assoc-in d [:state :seams :read-outcome!] (constantly nil))
                        [:state :seams :append-request!]
                        (fn [request]
                          (if (= :yin.k/admit (:yin.k/request request))
                            (do (reset! captured request) {:dao.stream/outcome :dao.stream/ok})
                            (append-request request))))
        _ (stream/append! (:prog armed) "input")
        attempted (loop [x armed k 0]
                    (if (or @captured
                            (>= k 6))
                      x
                      (recur (drive w x) (inc k))))
        admit @captured
        _ (is (some? admit) "the writer assigned an id and sent")
        conflict (admission/admit! (:a w) (:store w) (:target w)
                                   "holder-a"
                                   (assoc (get admit :yin.k/fenced-envelope)
                                          :yin.k/value :other-value)
                                   (log))
        _ (is (= :committed (:yin.k/admission conflict))
              "the conflicting intent committed first")
        _ (append-request admit)
        ended (drive-to w (assoc-in attempted [:state :seams :append-request!] append-request)
                        (phase-of :failed) 10)
        projection (authority/projection (:a w))]
    (is (= :intent-conflict (:cause (:detail (:state ended)))))
    (is (true? (get-in projection [:occurrences o :yin.k/quarantined]))
        "the occurrence is quarantined")
    (is (nil? (get-in projection [:occurrences o :yin.k/closed]))
        "a quarantined occurrence never closes")
    (testing "and it is never granted again"
      (let [_ (judge! w {:s 45})
            b (driver-for w "holder-b" nil nil
                          :raw {:bytes (:bytes lifted)
                                :address (:address lifted)}
                          :protection-as {"prog-r" :at-least-once
                                          (:target w) :enrolled}
                          :attach-table {(:target w) target-stream})
            refused (drive-to w b (fn [st]
                                    (= :yin.k/not-holder
                                       (:status st))) 8
                              :judge {:s 46})]
        (is (true? (get-in (:state refused) [:detail :yin.k/quarantined]))
            "the state observed carries the quarantine")
        (is (nil? (get-in (authority/projection (:a w))
                          [:occurrences o :dao.lease/lease]))
            "no lease was ever granted")))))


;; =============================================================================
;; D14: abort over the journaled attempts; the enrollment discipline
;; =============================================================================

(deftest abort-reads-the-journaled-offer-attempts-test
  (testing "before any offer attempt: local execution restored"
    (let [machine (source-machine)
          w (world :holders ["holder-a"])
          d (nth (iterate #(step-source w %)
                          (source-driver w "holder-a" machine nil))
                 3)
          _ (is (= [:yin.k/minted :yin.k/fenced]
                   (kinds-of (:journal-frames d))))
          aborted (driver/abort (:state d))]
      (is (= :aborted (:phase aborted)))
      (is (= :yin.k/ok (:status aborted)))
      (is (= (:wait-set machine)
             (:wait-set (:machine aborted))))
      (is (= :running (vm/gate-mode (:machine aborted)))
          "the old local machine, as it was")))
  (testing "a provably unappended attempt may still abort"
    (let [machine (source-machine)
          w (world :holders ["holder-a"]
                   :inbound {"holder-a" (bounded-stream "in-a" 1)})
          d (source-driver w "holder-a" machine nil)
          _ (stream/append! (get (:inbounds w) "holder-a") :blocked)
          prepared (nth (iterate #(step-source w %) d) 3)
          _ (stream/append! (get (:inbounds w) "holder-a") :blocked)
          d4 (assoc prepared :state (driver/step (:state prepared)))
          _ (is (= [:dao.stream/full]
                   (mapv :yin.k/append
                         (filter #(= :yin.k/attempt (:yin.k/journal %))
                                 (journal-of (:journal-frames d4))))))
          aborted (driver/abort (:state d4))]
      (is (= :aborted (:phase aborted)))
      (is (= (:wait-set machine) (:wait-set (:machine aborted)))))))


(testing "an admitted offer never restores local execution"
  (let [machine (source-machine)
        w (world :holders ["holder-a"])
        d4 (nth (iterate #(step-source w %)
                         (source-driver w "holder-a" machine nil))
                4)
        ;; the send left and the front admitted the offer; the reply
        ;; is unread, exactly the uncertain window abort refuses
        _ (is (= [:dao.stream/ok]
                 (mapv :yin.k/append
                       (filter #(= :yin.k/attempt (:yin.k/journal %))
                               (journal-of (:journal-frames d4))))))
        offered-facts (count (filter #(= :yin.k/offered (:yin.k/custody %))
                                     (frame-facts (:frames w))))
        _ (is (= 1 offered-facts) "the offer was admitted")
        refused (driver/abort (:state d4))]
    (is (= :exporting (:phase refused)) "the source stays fenced")
    (is (= :yin.k/refused (:status refused)))
    (is (= :offer-possibly-accepted (:yin.k/reason (:detail refused))))))


(deftest a-holder-aborts-a-hand-off-only-with-tenure-test
  (let [machine (source-machine)
        w (world :holders ["holder-a"])
        d (hand-off-run w (source-driver w "holder-a" machine nil))
        ;; the successor is minted, fenced and offered; the authority
        ;; refuses the offer :awaiting-completion until the closure
        d3 (nth (iterate #(step-source w %) d) 3)
        _ (is (= [:yin.k/minted :yin.k/fenced]
                 (take-last 2 (kinds-of (:journal-frames d3)))))
        aborted (driver/abort (:state d3))]
    (is (= :running (:phase aborted)) "the run continues")
    (is (= :running (vm/gate-mode (:machine aborted))))
    (is (= :exporting (vm/gate-mode (get-in d3 [:state :machine])))
        "the pre-abort machine stays fenced, untouched")
    (testing "past the lease bound the old machine is never restored"
      (let [past (assoc (:state d3) :seams
                        (assoc (:seams (:state d3))
                               :clock (constantly {:s 100})))]
        (is (= :yin.k/refused
               (:status (driver/abort past))))))))


(deftest enrollment-follows-the-identity-discipline-test
  (testing "an uncertain answer whose attempt committed is not retried"
    (let [machine (source-machine)
          w (world :holders ["holder-a"])
          d (source-driver w "holder-a" machine nil)
          calls (atom 0)
          ;; the enrollment commits but its answer never arrives
          seam (fn []
                 (swap! calls inc)
                 (authority/enroll! (:a w))
                 {:yin.k/status :suspended :yin.k/reason :uncertain})
          armed (assoc-in d [:state :seams :enroll!] seam)
          ;; one step journals the derived intent; the next attempts;
          ;; the next reconciles
          s1 (driver/enroll (:state armed))
          derived (get-in s1 [:enroll :derived])
          _ (is (some? derived))
          _ (is (zero? @calls) "no blind attempt before the intent")
          s2 (driver/enroll s1)
          _ (is (= 1 @calls))
          _ (is (= :yin.k/ok (:status s2)))
          s3 (driver/enroll s2)
          _ (is (= 1 @calls)
                "the reread found the derived identity: no second attempt")
          _ (is (= derived (get-in s3 [:enroll :target])))
          _ (is (= :yin.k/ok (:status s3)))
          _ (is (= derived (:yin.k/enrolled (:detail s3))))
          enrolled (count (filter #(= :yin.k/enrolled (:yin.k/custody %))
                                  (frame-facts (:frames w))))]
      (is (= 2 enrolled)
          "the world's own target and this one, never a second mint")))
  (testing "an uncertain answer that did not commit retries once"
    (let [machine (source-machine)
          w (world :holders ["holder-a"])
          d (source-driver w "holder-a" machine nil)
          calls (atom 0)
          seam (fn []
                 (swap! calls inc)
                 ;; never commits, always uncertain
                 {:yin.k/status :suspended :yin.k/reason :poisoned})
          armed (assoc-in d [:state :seams :enroll!] seam)
          s1 (driver/enroll (:state armed))
          s2 (driver/enroll s1)
          _ (is (= 1 @calls))
          _ (is (= :yin.k/unsatisfied (:status s2)))
          _ (is (= :enroll-uncertain (:yin.k/reason (:detail s2))))
          _retried (driver/enroll s2)
          _ (is (= 2 @calls) "absent, so exactly one more attempt")
          enrolled (count (filter #(= :yin.k/enrolled
                                      (:yin.k/custody %))
                                  (frame-facts (:frames w))))]
      (is (= 1 enrolled) "only the world's own enrollment stands"))))


(defn- exit-with-report-answer
  [world' answer]
  (let [original (hand-off-run world' (source-driver world' "holder-a" (source-machine) nil))
        append-request (get-in original [:state :seams :append-request!])
        read-reply (get-in original [:state :seams :read-reply!])
        replies (atom [])
        reports (atom [])
        state (-> (:state original)
                  (assoc-in [:seams :append-request!]
                            (fn [request]
                              (if (= :yin.k/resumed (:yin.k/request request))
                                (do (swap! reports conj request)
                                    (swap! replies conj
                                           [arb {:yin.k/reply :yin.k/resumed
                                                 :yin.k/request-id (:yin.k/request-id request)
                                                 :yin.k/answer answer}])
                                    {:dao.stream/outcome :dao.stream/ok})
                                (append-request request))))
                  (assoc-in [:seams :read-reply!]
                            (fn []
                              (if-some [reply (first @replies)]
                                (do (swap! replies #(vec (rest %))) reply)
                                (read-reply)))))
        sent (nth (iterate #(step-source world' %) (assoc original :state state)) 5)]
    {:driver sent :reports reports}))


(deftest suspended-report-retries-the-identical-request-test
  (let [world' (world :holders ["holder-a"])
        {:keys [driver reports]} (exit-with-report-answer world' {:yin.k/status :suspended})
        retried (step-source world' driver)]
    (is (= 2 (count @reports)))
    (is (= (first @reports) (second @reports)))
    (is (= :safepoint (:phase (:state retried))))
    (is (empty? (requests-of world' "holder-a" :yin.k/release)))
    (is (empty? (read-all (:diagnostics retried))))))


(deftest refused-report-ends-the-run-and-releases-test
  (doseq [answer (conj (mapv (fn [reason] {:yin.k/status :refused :yin.k/reason reason})
                             [:ended-lease :quarantined :report-conflict :counter-regression])
                       {:yin.k/status :closed})]
    (let [world' (world :holders ["holder-a"])
          {:keys [driver reports]} (exit-with-report-answer world' answer)
          ended (drive-to world' driver (phase-of :failed) 8 :judge {:s 3})]
      (is (= :failed (:phase (:state ended))) (pr-str answer))
      (is (= :ended (vm/gate-mode (get-in ended [:state :machine]))))
      (is (= 1 (count @reports)))
      (is (= 1 (count (read-all (:diagnostics ended)))))
      (is (seq (requests-of world' "holder-a" :yin.k/release)))
      (is (nil? (get-in (authority/projection (:a world'))
                        [:occurrences (get-in ended [:state :occurrence]) :yin.k/closed]))))))


(deftest aborted-source-is-durable-and-never-reoffered-on-reopen-test
  (let [world' (world :holders ["holder-a"])
        prepared (nth (iterate #(step-source world' %)
                               (source-driver world' "holder-a" (source-machine) nil)) 3)
        aborted (driver/abort (:state prepared))
        reopened (driver/reopen (-> (:config prepared)
                                    (dissoc :machine)
                                    (assoc :journal (reopened-journal (:journal-frames prepared)))))
        advanced (driver/step reopened)]
    (is (= :aborted (:phase aborted)))
    (is (= :yin.k/aborted (:yin.k/journal (last (journal-of (:journal-frames prepared))))))
    (is (= :aborted (:phase reopened)))
    (is (nil? (:machine reopened)))
    (is (= reopened advanced))
    (is (zero? @(:attaches prepared)))
    (is (empty? (requests-of world' "holder-a" :yin.k/offer)))))


(deftest aborted-hand-off-reopen-closes-the-export-and-reenters-candidacy-test
  (let [world' (world :holders ["holder-a"])
        original (hand-off-run world' (source-driver world' "holder-a" (source-machine) nil))
        prepared (nth (iterate #(step-source world' %) original) 3)
        successor (get-in prepared [:state :exit :occurrence])
        aborted (driver/abort (:state prepared))
        reopened (driver/reopen (assoc (:config prepared)
                                       :journal (reopened-journal (:journal-frames prepared))))
        recovered (drive-to world' (assoc prepared :state reopened)
                            (phase-of :proposing) 8 :judge {:s 3})]
    (is (= :running (:phase aborted))
        "the live holder took its machine back before the crash")
    (is (= :releasing (:phase reopened)))
    (is (nil? (:machine reopened))
        "a journaled grant never restores execution")
    (is (= :proposing (:phase (:state recovered))))
    (is (seq (requests-of world' "holder-a" :yin.k/release)))
    (is (empty? (filter #(= successor (second (:yin.k/request-id %)))
                        (requests-of world' "holder-a" :yin.k/offer)))
        "the aborted successor's export is closed, never reoffered")))


(deftest abort-uncertain-journal-record-never-hands-back-execution-test
  (doseq [cut [:before-frame :after-frame-before-visible]]
    (let [world' (world :holders ["holder-a"])
          prepared (nth (iterate #(step-source world' %)
                                 (source-driver world' "holder-a" (source-machine) nil)) 3)
          frames (atom (vec @(:journal-frames prepared)))
          progress (:dao.stream/handle (journal/open! (cut-backend frames (count @frames) cut)))
          result (driver/abort (assoc-in (:state prepared) [:seams :journal] progress))]
      (is (= :stalled (:phase result)))
      (is (nil? (:machine result)))
      (is (= :exporting (vm/gate-mode (get-in result [:export :m]))))
      (let [reopened (driver/reopen (assoc (:config prepared) :journal (reopened-journal frames)))]
        (is (nil? (:machine reopened)))
        (when (= :after-frame-before-visible cut)
          (is (= :aborted (:phase reopened))))))))


(deftest incomplete-holder-exit-releases-and-reenters-candidacy-test
  (doseq [role [:yin.k/successor :yin.k/result]]
    (let [world' (world :holders ["holder-a"])
          original (hand-off-run world' (source-driver world' "holder-a" (source-machine) nil))
          armed (assoc-in original [:state :exit :role] role)
          minted (step-source world' armed)
          reopened (driver/reopen (-> (:config minted)
                                      (dissoc :machine)
                                      (assoc :journal (reopened-journal (:journal-frames minted)))))
          recovered (drive-to world' (assoc minted :state reopened) (phase-of :proposing) 8 :judge {:s 3})]
      (is (= :releasing (:phase reopened)))
      (is (nil? (:machine reopened)))
      (is (= :proposing (:phase (:state recovered))))
      (is (seq (requests-of world' "holder-a" :yin.k/release)))
      (is (= (:occurrence (:state original)) (:occurrence (:state recovered))))
      (is (= (get-in original [:state :address]) (get-in recovered [:state :address])))
      (is (nil? (get-in recovered [:state :exit]))))))


(deftest holder-abort-respects-cap-latches-and-stopped-state-test
  (let [world' (world :holders ["holder-a"])
        original (hand-off-run world' (source-driver world' "holder-a" (source-machine) nil))
        prepared (nth (iterate #(step-source world' %) original) 3)
        capped (-> (:state prepared)
                   (assoc-in [:holder :grant :dao.lease/max] {:s 5})
                   (assoc-in [:holder :last-renewal-at] {:s 4})
                   (assoc-in [:seams :clock] (constantly {:s 6})))
        latched (mapv #(assoc-in (:state prepared) [:holder %] true)
                      [:bound-reached? :undersized? :released?])]
    (doseq [state (into [capped] latched)]
      (is (false? (lease/holding? (:holder state) ((get-in state [:seams :clock])))))
      (let [refused (driver/abort state)]
        (is (= :yin.k/refused (:status refused)))
        (is (= :exporting (vm/gate-mode (:machine refused))))))))


(deftest first-proposal-send-has-exactly-one-intent-test
  (let [world' (world :holders ["holder-a"])
        sent (nth (iterate #(step-source world' %)
                           (source-driver world' "holder-a" (source-machine) nil)) 6)
        records (filter #(= :yin.k/proposal (:yin.k/action %))
                        (journal-of (:journal-frames sent)))]
    (is (= [:yin.k/intent :yin.k/attempt] (mapv :yin.k/journal records)))))


(deftest restarted-exit-keeps-its-cell-until-release-is-visible-test
  (let [world' (world :holders ["holder-a"])
        original (hand-off-run world' (source-driver world' "holder-a" (source-machine) nil))
        offered (nth (iterate #(step-source world' %) original) 4)
        before-release (attributed (:frames world'))
        reopened (driver/reopen (assoc (:config offered) :journal (reopened-journal (:journal-frames offered))))
        sent (driver/step reopened)
        _ (carry! world')
        delayed (assoc-in sent [:seams :read-ledger!] (constantly before-release))
        waiting (driver/step delayed)
        _ (judge! world' {:s 3})
        visible (assoc-in waiting [:seams :read-ledger!] (get-in original [:state :seams :read-ledger!]))
        recovered (drive-to world' (assoc offered :state visible) (phase-of :proposing) 8)]
    (is (true? (get-in waiting [:release :carried])))
    (is (= :exiting (:phase waiting)))
    (is (= (get-in reopened [:exit :occurrence]) (get-in waiting [:exit :occurrence])))
    (is (nil? (:machine waiting)))
    (is (= :proposing (:phase (:state recovered))))))


(deftest failed-recovery-store-does-not-attempt-the-body-store-test
  (let [world' (world :holders ["holder-a"])
        prepared (nth (iterate #(step-source world' %)
                               (source-driver world' "holder-a" (source-machine) nil)) 2)
        calls (atom 0)
        state (assoc-in (:state prepared) [:seams :content-store :put-bytes-fn]
                        (fn [_address _bytes] (swap! calls inc) :refused))
        refused (driver/step state)]
    (is (= 1 @calls))
    (is (= :yin.k/unsatisfied (:status refused)))
    (is (not-any? #(= :yin.k/fenced (:yin.k/journal %))
                  (journal-of (:journal-frames prepared))))))


(deftest abort-rechecks-tenure-after-its-durable-terminal-record-test
  (let [world' (world :holders ["holder-a"])
        original (hand-off-run world' (source-driver world' "holder-a" (source-machine) nil))
        prepared (nth (iterate #(step-source world' %) original) 3)
        progress (get-in prepared [:state :seams :journal])
        wrapped (reify
                  stream/IDaoStreamReader
                  (cursor [_ mode] (stream/cursor progress mode))

                  (next [_ cursor] (stream/next progress cursor))


                  stream/IDaoStreamWriter

                  (append!
                    [_ record]
                    (let [answer (stream/append! progress record)]
                      (when (= :yin.k/aborted (:yin.k/journal record))
                        (reset! (:clock prepared) {:s 100}))
                      answer)))
        refused (driver/abort (assoc-in (:state prepared) [:seams :journal] wrapped))]
    (is (= :yin.k/ended (:status refused)))
    (is (= :releasing (:phase refused)))
    (is (= :ended (vm/gate-mode (:machine refused))))
    (is (= :yin.k/aborted (:yin.k/journal (last (journal-of (:journal-frames prepared))))))
    (testing "the deferred cleanup release opens with the next step and leaves"
      (let [finished (drive-to world' (assoc prepared :state refused)
                               (phase-of :failed) 8 :judge {:s 3})]
        (is (= :failed (:phase (:state finished))))
        (is (seq (requests-of world' "holder-a" :yin.k/release)))
        (is (= 1 (count (read-all (:diagnostics prepared))))
            "one diagnostic, the run end's own")))))
