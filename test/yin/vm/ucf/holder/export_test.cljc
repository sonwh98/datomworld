(ns yin.vm.ucf.holder.export-test
  "D8 over the row carrier (UCF 7.7.4; r3 1.8 and 1.9; phase 7b): the
   exporting state, the prepare/encode split over rows/lift-task, the
   export-recovery object version 2, its fenced rehydration and the abort
   rule. Every machine is a semantic machine the real engine
   parked (yin.vm.ucf.rows-support); counting handles show what the gate,
   the encode and the rehydration touch."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.module :as module]
            [yin.vm.values :as values]
            [yin.vm.test-utils :as tu]
            [yin.vm.ucf.holder.export :as export]
            [yin.vm.ucf.rows :as rows]
            [yin.vm.ucf.rows-support :as rs]))


;; =============================================================================
;; Machines, the exporter, the receiver's attach
;; =============================================================================

(defn- throws?
  [thunk]
  (try (thunk) false
       (catch #?(:cljd Object :clj Throwable :cljs :default) _ true)))


(defn- ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key capacity})))


(defn- index
  "The composition's source index, as the composition reads it."
  []
  @rs/source-index)


(defn- two-streams
  "Parks on a read of the first stream; the store also holds the second
   stream and a cursor on each, so the lift reaches two streams."
  []
  (rs/let1 'a {:type :stream/make :buffer 4}
           (rs/let1 'b {:type :stream/make :buffer 4}
                    (rs/then (rs/def! 'held-a (rs/cursor-of (rs/v 'a)))
                             (rs/then (rs/def! 'held-b (rs/cursor-of (rs/v 'b)))
                                      (rs/next-of (rs/v 'held-a)))))))


(defn- parked-machine
  []
  (vm/run (rs/load-ast (rs/new-machine) (two-streams))))


(defn- served-table
  "A `serve!` that counts its calls and answers one stable identity per
   handle."
  []
  (let [calls (atom 0)
        table (atom {})]
    {:calls calls
     :table table
     :serve! (fn [h]
               (swap! calls inc)
               (or (get @table h)
                   (let [served {:dao.stream/identity (str "s" (count @table))
                                 :dao.stream/channel {:dao.stream/type :test/channel}}]
                     (swap! table assoc h served)
                     served)))}))


(defn- counting-handle
  [calls]
  (reify
    stream/IDaoStreamReader

    (cursor
      [_ _]
      (swap! calls inc)
      {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor ::at})

    (next
      [_ _]
      (swap! calls inc)
      {:dao.stream/outcome :dao.stream/blocked})


    stream/IDaoStreamWriter

    (append!
      [_ _]
      (swap! calls inc)
      {:dao.stream/outcome :dao.stream/ok})


    stream/IDaoStreamClosable

    (close!
      [_]
      (swap! calls inc)
      {:dao.stream/outcome :dao.stream/ok})))


(defn- counting-attach
  "A receiver's attach!: a fresh counting handle per descriptor; every
   attach and every handle operation counted."
  []
  (let [attaches (atom 0) calls (atom 0)]
    {:attaches attaches
     :calls calls
     :attach (fn [_]
               (swap! attaches inc)
               {:dao.stream/outcome :dao.stream/ok
                :dao.stream/handle (counting-handle calls)})}))


(defn- frozen-of
  "Enter, prepare under header with the composition's index, freeze:
   {:machine :record :prepared :frozen :table}."
  ([source] (frozen-of source nil))
  ([source header]
   (let [{:keys [machine record]} (export/enter source)
         {:keys [serve! table]} (served-table)
         prepared (export/prepare machine record serve! header (index))]
     {:machine machine :record record :prepared prepared :table table
      :frozen (when (= :ok (:status prepared)) (export/freeze machine (:record prepared)))})))


(defn- rehydrate
  ([frozen] (rehydrate frozen (rs/new-machine) (:attach (counting-attach))))
  ([frozen receiver attach]
   (export/rehydrate-fenced receiver (:bytes frozen) {:address (:address frozen) :attach attach})))


(defn- reproduces?
  [frozen restored]
  (= (vec (:pack-bytes frozen))
     (vec (:bytes (export/encode (:machine restored) (:record restored))))))


;; =============================================================================
;; Gated machines over counting handles (machine-level, kernel-agnostic)
;; =============================================================================

(def ^:private park-entry-fns
  {:stream/put (fn [_s _e r] {:reason :put, :stream-id (:stream-id r)}),
   :stream/next (fn [_s _e r]
                  {:reason :next,
                   :cursor-ref (:cursor-ref r),
                   :stream-id (:stream-id r)})})


(def ^:private opts {:park-entry-fns park-entry-fns})


(defn- gated
  "[state stream-ref cursor-ref calls] under the :running gate."
  []
  (let [calls (atom 0)
        [sref s0] (engine/attach-resource
                    (vm/empty-state {:make-stream tu/make-stream,
                                     :capability-secret tu/secret})
                    (counting-handle calls))
        [cref s1] (engine/handle-cursor s0 {:stream sref} :cursor-0)]
    (reset! calls 0)
    [(assoc s1 :yin.k/gate :running) sref cref calls]))


(defn- run-effect
  [state kind m]
  (:state (engine/handle-effect state (module/make-effect kind m) opts)))


;; =============================================================================
;; Entering exporting
;; =============================================================================

(deftest enter-moves-waits-and-fences-the-machine-test
  (let [[s sref _ _] (gated)
        s (run-effect s :stream/put {:stream sref, :val 1})
        waits (:wait-set s)
        r (export/enter s)]
    (is (= :ok (:status r)) (pr-str r))
    (is (= :exporting (vm/gate-mode (:machine r))))
    (is (empty? (:wait-set (:machine r))) "nothing left to poll")
    (is (= waits (get-in r [:record :wait-set])) "waits kept in order")
    (is (= :running (get-in r [:record :gate])))
    (testing "an exporting machine cannot enter again"
      (is (= :yin.k/refused (:yin.k/status (export/enter (:machine r))))))))


(deftest exporting-appends-nothing-test
  (let [[s sref _ calls] (gated)
        s (run-effect s :stream/put {:stream sref, :val 1})
        child-calls (atom 0)
        child (vm/run (rs/load-ast (rs/new-machine
                                     {:make-stream
                                      (fn [_capacity]
                                        {:dao.stream/outcome :dao.stream/ok
                                         :dao.stream/handle (counting-handle child-calls)})})
                                   (rs/next-of (rs/cursor-of {:type :stream/make :buffer 4}))))
        s (assoc s :installs {'foo {:phase :running
                                    :vm child
                                    :parent [:t0 0]
                                    :response {}}})
        _ (reset! child-calls 0)
        {m :machine} (export/enter s)
        stamped (assoc s :parked {:parked-0 {:type :parked-continuation
                                             :id :parked-0}})]
    (testing "polling the source"
      (engine/check-wait-set m)
      (is (zero? @calls)))
    (testing "ticking its install child"
      (let [swept (engine/check-wait-set m)]
        (is (zero? @child-calls))
        (is (= child (get-in swept [:installs 'foo :vm])))))
    (testing "a direct resume"
      (let [restored (atom 0)]
        (is (throws? #(engine/resume-continuation
                        (assoc m :parked (:parked stamped))
                        :parked-0 1
                        (fn [st _ _] (swap! restored inc) st))))
        (is (zero? @restored))
        (is (zero? @calls))))))


(deftest a-non-empty-ready-queue-is-not-quiescent-test
  (let [[s _ _ _] (gated)
        r (export/enter (assoc s :ready-queue [{:id 1}]))]
    (is (= :yin.k/not-quiescent (:yin.k/status r)))
    (testing "a child's queue too"
      (let [r (export/enter
                (assoc s :installs {'foo {:vm (assoc (vm/empty-state {})
                                                     :ready-queue [{:id 1}])}}))]
        (is (= :yin.k/not-quiescent (:yin.k/status r)))))))


(defn- refused-as
  [kind r]
  (and (= :yin.k/non-portable (:yin.k/status r))
       (= :reason-mismatch (:yin.k/kind r))
       (= kind (:yin.k/hold r))))


(deftest each-export-refusal-fires-on-a-real-parked-machine-test
  (let [[s sref cref calls] (gated)]
    (testing "an :observe entry"
      (let [m (run-effect s :stream/poll {:cursor cref})]
        (is (= :observe (:reason (first (:wait-set m)))))
        (is (refused-as :observe (export/enter m)))))
    (testing "an entry carrying :yin.k/held"
      (let [m (run-effect s :stream/next {:cursor cref})
            m (assoc-in m [:wait-set 0 :yin.k/held] {:state :observed})]
        (is (refused-as :held (export/enter m)))))
    (testing "an unminted cursor cell"
      (let [m (run-effect s :stream/cursor {:stream sref})]
        (is (refused-as :unminted-cursor (export/enter m)))))
    (testing "a pending close"
      (let [m (run-effect s :stream/close {:stream sref})]
        (is (seq (:yin.k/closes m)))
        (is (refused-as :pending-close (export/enter m)))))
    (testing "a link request with no response cursor"
      (let [m (rs/parked-linker)
            entry (first (:wait-set m))]
        (is (= :link-request (:reason entry)))
        (is (not (contains? entry :cursor)))
        (is (refused-as :link-cursor-not-installed (export/enter m)))))
    (testing "a refusal in an install child names its path"
      (let [child (run-effect s :stream/close {:stream sref})
            m (assoc (vm/empty-state {})
                     :installs {'foo {:vm child}})
            r (export/enter m)]
        (is (refused-as :pending-close r))
        (is (= ['foo] (:yin.k/path r)))))
    (testing "a refusal leaves the machine as it was"
      (is (zero? @calls)))))


(deftest an-explicit-park-travels-in-the-record-test
  (let [m (rs/parked-explicit)
        {m' :machine record :record} (export/enter m)
        {:keys [serve!]} (served-table)
        prepared (export/prepare m' record serve! nil (index))]
    (is (seq (:parked m)) "the source holds a parked record")
    (is (= (:parked m) (:parked record)) "the record holds every parked record")
    (is (empty? (:parked m')) "the machine's :parked holds none")
    (is (empty? (:wait-set m')))
    (is (= :ok (:status prepared)) (pr-str prepared))
    (let [e (export/encode m' (:record prepared))]
      (is (= :ok (:status e)) (pr-str e))
      (is (= :parked (:kind e))))
    (is (= m (:machine (export/abort m' record [] nil)))
        "abort restores machine equality")))


(deftest an-install-childs-parked-records-stay-in-the-child-test
  (let [m (rs/parked-installer)
        parked {:p0 {:type :parked-continuation :id :p0}}
        m (assoc-in m [:installs 'host.mod :vm :parked] parked)
        {m' :machine record :record :as r} (export/enter m)]
    (is (= :ok (:status r)) (pr-str r))
    (is (= parked (get-in m' [:installs 'host.mod :vm :parked]))
        "the child's parked records are not moved")
    (is (not-any? #(contains? (:parked record) %) (keys parked)))))


;; =============================================================================
;; Prepare, then encode
;; =============================================================================

(deftest prepare-serves-once-per-stream-and-encode-serves-nothing-test
  (let [m (parked-machine)
        {:keys [calls table serve!]} (served-table)
        {m' :machine record :record} (export/enter m)
        prepared (export/prepare m' record serve! nil (index))
        record' (:record prepared)
        after-prepare @calls
        e1 (export/encode m' record')
        e2 (export/encode m' record')]
    (is (= :ok (:status prepared)) (pr-str prepared))
    (is (= 2 (count @table)) "two streams reached")
    (is (= (count @table) after-prepare) "serve! once per stream")
    (is (= (count @table) (count (:served record'))))
    (is (every? (fn [[path id]] (and (= [] path) (keyword? id))) (keys (:served record')))
        "keyed by task path and resource id")
    (is (= after-prepare @calls) "encode calls nothing")
    (is (= :ok (:status e1)) (pr-str e1))
    (is (= (vec (:bytes e1)) (vec (:bytes e2))) "deterministic bytes")
    (is (= (:address e1) (:address e2)))
    (testing "the encode is the row pack: its address is the bytes' BLAKE3, distinct from its root"
      (is (jing/segment-bytes-match? (:address e1) (:bytes e1)))
      (is (= (:address e1) (rows/pack-address (cbor/decode (:bytes e1)))))
      (is (= (:root e1) (:yin.k/root (cbor/decode (:bytes e1)))))
      (is (not= (:root e1) (:address e1))))
    (testing "preparing a prepared record again serves nothing new"
      (export/prepare m' record' serve! nil (index))
      (is (= after-prepare @calls)))
    (testing "an unprepared record is a defect, not a refusal"
      (is (throws? #(export/encode m' (assoc record :source-index (index))))))))


(deftest the-lift-takes-trees-from-the-composition-source-index-test
  (let [m (parked-machine)
        {m' :machine record :record} (export/enter m)
        {:keys [serve!]} (served-table)
        prepared (export/prepare m' record serve! nil (index))
        wire (cbor/decode (:bytes (export/encode m' (:record prepared))))
        task (rows/decode wire)
        trees (get-in task [:slots 10 :yin.k/trees])]
    (is (= (index) (:source-index (:record prepared))) "the record retains the index it lifted under")
    (is (seq trees))
    (is (every? (fn [[a ts]] (= ts (get (index) a))) trees) "every carried A names its trees from the index")
    (testing "without the composition's index the export refuses: the AST is never lost"
      (let [{m2 :machine r2 :record} (export/enter (parked-machine))
            refused (export/prepare m2 r2 serve! nil {})]
        (is (= :yin.k/unsatisfied (:yin.k/status refused)) (pr-str refused))
        (is (seq (get-in refused [:yin.k/missing :yin.k/trees])))))))


(deftest prepare-reaches-install-children-once-test
  (let [m (rs/parked-installer)
        {:keys [calls table serve!]} (served-table)
        {m' :machine record :record} (export/enter m)
        prepared (export/prepare m' record serve! nil (index))]
    (is (= :ok (:status prepared)) (pr-str prepared))
    (is (pos? @calls))
    (is (= @calls (count @table)) "each stream, child's included, once")
    (is (some (fn [[path _]] (= ['host.mod] path)) (keys (get-in prepared [:record :served])))
        "a child's stream is keyed under the child's path")
    (let [n @calls]
      (is (= :ok (:status (export/encode m' (:record prepared)))))
      (is (= n @calls)))))


(def ^:private served-answer
  {:dao.stream/identity "s0"
   :dao.stream/channel {:dao.stream/type :test/channel}})


(deftest a-refused-prepare-keeps-what-it-served-test
  (let [m (parked-machine)
        {m' :machine record :record} (export/enter m)
        first-handle (atom nil)
        ;; serves the first stream it is asked about, refuses the rest
        stingy (fn [h]
                 (compare-and-set! first-handle nil h)
                 (when (= h @first-handle) served-answer))
        refused (export/prepare m' record stingy nil (index))]
    (is (= :yin.k/unsatisfied (:yin.k/status refused)) (pr-str refused))
    (is (= 1 (count (get-in refused [:record :served]))))
    (testing "a retry with the returned record never re-serves that stream"
      (let [asked (atom [])
            {:keys [serve!]} (served-table)
            retry (export/prepare m' (:record refused)
                                  (fn [h]
                                    (swap! asked conj h)
                                    (serve! h))
                                  nil (index))]
        (is (= :ok (:status retry)) (pr-str retry))
        (is (not-any? #(= @first-handle %) @asked))
        (is (= 1 (count @asked)) "only the other stream")))))


(deftest a-custody-refusal-after-the-walk-keeps-what-the-walk-served-test
  ;; phase-7a D4: the row lift serves while it walks and decides the
  ;; protection and exhaustion refusals after; the record accounts for it
  (doseq [[label header expected]
          [["an enrolled write without an operation id"
            (rs/header 0 rs/origin #{"s0"}) :unprotected-pending]
           ["an exhausted counter"
            (rs/header rows/max-exact rs/origin #{}) :op-seq-exhausted]]]
    (testing label
      (let [{m :machine record :record} (export/enter (rs/parked-writer))
            {:keys [calls serve!]} (served-table)
            refused (export/prepare m record serve! header (index))
            served @calls]
        (is (= :yin.k/non-portable (:yin.k/status refused)) (pr-str refused))
        (is (= expected (:yin.k/kind refused)))
        (is (pos? served) "the walk served before the refusal")
        (is (= served (count (get-in refused [:record :served]))) "and the record retains every answer")
        (export/prepare m (:record refused) serve! header (index))
        (is (= served @calls) "a retry serves nothing twice")))))


;; =============================================================================
;; Freeze: recovery version 2
;; =============================================================================

(deftest the-recovery-object-is-the-pack-and-holder-local-state-test
  (let [{:keys [frozen prepared machine]} (frozen-of (rs/parked-explicit))
        recovery (cbor/decode (:bytes frozen))
        published (export/encode machine (:record prepared))]
    (is (= :ok (:status frozen)) (pr-str frozen))
    (is (= #{:yin.k/export-recovery :yin.k/version :yin.k/pack-address :yin.k/root
             :yin.k/pack-bytes :yin.k/header :yin.k/state}
           (set (keys recovery)))
        "one body: no snapshot, no descriptors (the pack carries them)")
    (is (= 2 (:yin.k/version recovery)))
    (is (= (:address published) (:pack-address frozen) (:yin.k/pack-address recovery)))
    (is (= (:root published) (:root frozen) (:yin.k/root recovery)))
    (is (= (vec (:bytes published)) (vec (:pack-bytes frozen))
           (vec (jing/base64->bytes (:yin.k/pack-bytes recovery)))))
    (is (jing/segment-bytes-match? (:address frozen) (:bytes frozen)))
    (is (= #{:gate :origins :issues :issued :children} (set (keys (:yin.k/state recovery)))))))


;; =============================================================================
;; Rehydrate: fenced, reproducible, without program IO
;; =============================================================================

(deftest complete-export-recovery-is-fenced-and-reproducible-test
  (doseq [[label source header] [["a parked reader" (rs/parked-reader) nil]
                                 ["an explicit park" (rs/parked-explicit) nil]
                                 ["an installer" (rs/parked-installer) nil]
                                 ["a parked reader under a header" (rs/parked-reader) (rs/header 0 nil #{})]
                                 ["a halted root under a header" (rs/halted-machine) (rs/header 0 rs/origin #{})]]]
    (testing label
      (let [{:keys [frozen record prepared]} (frozen-of source header)
            {:keys [attaches calls attach]} (counting-attach)
            restored (rehydrate frozen (rs/new-machine) attach)]
        (is (= :ok (:status frozen)) (pr-str frozen))
        (is (= :ok (:status restored)) (pr-str restored))
        (is (= :exporting (vm/gate-mode (:machine restored))))
        (is (empty? (:wait-set (:machine restored))))
        (is (empty? (:parked (:machine restored))))
        (is (= (count (:wait-set record)) (count (get-in restored [:record :wait-set]))))
        (is (= (set (keys (:parked record))) (set (keys (get-in restored [:record :parked])))))
        (is (nil? (get-in restored [:machine :yin.k/custody])) "no custody is restored")
        (is (zero? @calls) "no read, write, mint or close")
        (is (= (count (into #{} (map :dao.stream/identity) (vals (get-in prepared [:record :served]))))
               @attaches)
            "every stream attached, once")
        (is (reproduces? frozen restored) "the published pack, byte for byte")))))


(deftest a-restarted-abort-is-refused-test
  (let [{:keys [frozen]} (frozen-of (rs/parked-reader))
        restored (rehydrate frozen)]
    (is (= :ok (:status restored)) (pr-str restored))
    (is (true? (get-in restored [:record :recovered?])))
    (is (= [:yin.k/refused :restarted]
           ((juxt :yin.k/status :yin.k/reason)
            (export/abort (:machine restored) (:record restored) [] nil))))))


(deftest recovery-restores-fresh-name-state-without-receiver-contamination-test
  (let [source (assoc (rs/parked-reader) :origins 42 :id-counter 88 :yin.k/issued 77)
        {:keys [frozen]} (frozen-of source)
        restored (rehydrate frozen
                            (assoc (rs/new-machine) :origins 99 :id-counter 100
                                   :store {'receiver-local :wrong} :yin.k/issued 33
                                   :yin.k/closes [{:receiver-local true}])
                            (:attach (counting-attach)))]
    (is (= :ok (:status restored)) (pr-str restored))
    (is (= 42 (:origins (:machine restored))))
    (is (= 88 (:id-counter (:machine restored))) "the carried counter exactly, never max(local, carried)")
    (is (= 77 (:yin.k/issued (:machine restored))))
    (is (not (contains? (:store (:machine restored)) 'receiver-local)))
    (is (empty? (:yin.k/closes (:machine restored))))
    (is (reproduces? frozen restored))))


(deftest the-id-counter-is-carried-exactly-test
  (testing "a receiver whose counter is below the carried one"
    (let [source (assoc (rs/parked-reader) :id-counter 500)
          {:keys [frozen]} (frozen-of source)
          restored (rehydrate frozen (assoc (rs/new-machine) :id-counter 3) (:attach (counting-attach)))]
      (is (= 500 (get-in restored [:machine :id-counter])))
      (is (reproduces? frozen restored)))))


(deftest recovery-validation-precedes-attachment-test
  (let [{:keys [frozen]} (frozen-of (rs/parked-reader))
        recovery (cbor/decode (:bytes frozen))
        other (cbor/decode (:bytes (:frozen (frozen-of (rs/parked-explicit)))))
        pack (cbor/decode (:pack-bytes frozen))
        reordered (cbor/encode (update pack :yin.k/rows (comp vec reverse)))
        {:keys [attaches attach]} (counting-attach)]
    (doseq [[label invalid status]
            [["version 1" (assoc recovery :yin.k/version 1) :yin.k/profile-mismatch]
             ["no tag" (dissoc recovery :yin.k/export-recovery) :yin.k/profile-mismatch]
             ["a header the root does not carry" (assoc recovery :yin.k/header (rs/header 0 nil #{})) :yin.k/undecodable]
             ["a malformed header" (assoc recovery :yin.k/header {:yin.k/enrolled #{}}) :yin.k/undecodable]
             ["issues of another wait count" (assoc-in recovery [:yin.k/state :issues] []) :yin.k/undecodable]
             ["children the tree does not carry" (assoc-in recovery [:yin.k/state :children] {'x {}}) :yin.k/undecodable]
             ["another root id" (assoc recovery :yin.k/root (:yin.k/root other)) :yin.k/undecodable]
             ["another recovery's pack" (merge recovery (select-keys other [:yin.k/pack-bytes :yin.k/pack-address :yin.k/root]))
              :yin.k/undecodable]
             ["a pack that is not its address" (assoc recovery :yin.k/pack-address (:yin.k/pack-address other))
              :yin.k/hash-mismatch]
             ["the same root in another row order"
              (assoc recovery :yin.k/pack-bytes (jing/bytes->base64 reordered)
                     :yin.k/pack-address (keyword "segment" (str "blake3-" (jing/digest-bytes :blake3 reordered))))
              nil]]]
      (testing label
        (let [bytes (cbor/encode invalid)
              address (keyword "segment" (str "blake3-" (jing/digest-bytes :blake3 bytes)))
              before @attaches
              restored (export/rehydrate-fenced (rs/new-machine) bytes {:address address :attach attach})]
          (is (not= :ok (:status restored)) (pr-str restored))
          (is (nil? (:machine restored)))
          (when (some? status) (is (= status (:yin.k/status restored)) (pr-str restored)))
          (when (some? status) (is (= before @attaches) "refused before any attachment")))))
    (testing "the recovery's own address is checked"
      (is (= :yin.k/hash-mismatch
             (:yin.k/status (export/rehydrate-fenced (rs/new-machine) (:bytes frozen)
                                                     {:address (:pack-address frozen) :attach attach})))))
    (testing "a pack in another row order is not the published pack: recovery is inconsistent"
      (let [invalid (assoc recovery :yin.k/pack-bytes (jing/bytes->base64 reordered)
                           :yin.k/pack-address (keyword "segment" (str "blake3-" (jing/digest-bytes :blake3 reordered))))
            bytes (cbor/encode invalid)
            restored (export/rehydrate-fenced (rs/new-machine) bytes
                                              {:address (keyword "segment" (str "blake3-" (jing/digest-bytes :blake3 bytes)))
                                               :attach attach})]
        (is (= [:yin.k/undecodable :recovery-inconsistent]
               ((juxt :yin.k/status :yin.k/reason) restored)))))))


(deftest recovery-preserves-aliases-distinct-cells-and-authentic-closures-test
  (let [source (vm/run
                 (rs/load-ast (rs/new-machine)
                              (rs/let1 's {:type :stream/make :buffer 4}
                                       (rs/let1 'c (rs/cursor-of (rs/v 's))
                                                (rs/let1 'd (rs/cursor-of (rs/v 's))
                                                         (rs/then (rs/def! 'first-c (rs/v 'c))
                                                                  (rs/then (rs/def! 'alias-c (rs/v 'c))
                                                                           (rs/then (rs/def! 'distinct-c (rs/v 'd))
                                                                                    (rs/then (rs/def! 'closure (rs/lam [] (rs/next-of (rs/v 'c))))
                                                                                             (rs/next-of (rs/v 'c)))))))))))
        {:keys [frozen]} (frozen-of source)
        {:keys [attaches calls attach]} (counting-attach)
        restored (rehydrate frozen (rs/new-machine {:origin :fresh :capability-secret "recovery-secret"}) attach)
        machine' (:machine restored)
        store (:store machine')
        closure (get store 'closure)
        cell-ids (:yin.k/recovery-cell-ids machine')]
    (is (= :ok (:status frozen)))
    (is (= :ok (:status restored)) (pr-str restored))
    (is (= 1 @attaches) "one stream, one attachment")
    (is (zero? @calls) "no read, mint, append or close during reconstruction")
    (is (= (get store 'first-c) (get store 'alias-c)) "an alias stays one cursor cell")
    (is (not= (get store 'first-c) (get store 'distinct-c)) "two cursor cells stay two")
    (is (= 2 (count (set (vals cell-ids)))))
    (is (not= (get cell-ids (:id (get store 'first-c))) (get cell-ids (:id (get store 'distinct-c)))))
    (is (engine/authentic-ref? machine' :cursor-ref (get store 'first-c)))
    (is (values/closure? closure))
    (is (values/owned-by? closure (:owner machine')))
    (is (not (values/owned-by? closure (:owner source))))
    (is (reproduces? frozen restored))))


(deftest a-halted-closure-result-is-a-recovery-root-test
  (let [source (vm/run (rs/load-ast (rs/new-machine) (rs/lam [] (rs/lit 42))))
        {:keys [frozen]} (frozen-of source (rs/header 0 rs/origin #{}))
        {:keys [calls attach]} (counting-attach)
        restored (rehydrate frozen (rs/new-machine {:origin :fresh :capability-secret "fresh-secret"}) attach)]
    (is (values/closure? (:value source)))
    (is (= :ok (:status frozen)) (pr-str frozen))
    (is (= :ok (:status restored)) (pr-str restored))
    (is (values/closure? (:value (:machine restored))))
    (is (= :exporting (vm/gate-mode (:machine restored))))
    (is (zero? @calls))
    (is (reproduces? frozen restored))))


(deftest module-store-cursors-travel-in-the-one-pack-test
  (let [source (rs/parked-module-store-cursors)
        {:keys [frozen]} (frozen-of source)
        {:keys [calls attach]} (counting-attach)
        restored (rehydrate frozen (rs/new-machine) attach)]
    (is (= :ok (:status frozen)) (pr-str frozen))
    (is (= :ok (:status restored)) (pr-str restored))
    (is (= #{'c 'd} (set (keys (get-in restored [:machine :module-stores 'host.mod])))))
    (is (zero? @calls))
    (is (reproduces? frozen restored))))


(deftest closure-in-blocked-environment-survives-fenced-recovery-test
  (let [source (vm/run (rs/load-ast (rs/new-machine)
                                    (rs/let1 'f (rs/lam [] (rs/lit 42))
                                             (rs/let1 'input {:type :stream/make :buffer 4}
                                                      (rs/then (rs/next-of (rs/cursor-of (rs/v 'input)))
                                                               (rs/app (rs/v 'f)))))))
        source-closure (get-in source [:wait-set 0 :env 'f])
        {:keys [frozen]} (frozen-of source)
        {:keys [calls attach]} (counting-attach)
        restored (rehydrate frozen (rs/new-machine {:origin :fresh :capability-secret "fresh-secret"}) attach)
        closure (get-in restored [:record :wait-set 0 :env 'f])]
    (is (values/closure? source-closure) (pr-str (:wait-set source)))
    (is (= :ok (:status restored)) (pr-str restored))
    (is (values/closure? closure))
    (is (values/owned-by? closure (:owner (:machine restored))))
    (is (not (values/owned-by? closure (:owner source))))
    (is (zero? @calls))
    (is (= :exporting (vm/gate-mode (:machine restored))))
    (is (empty? (:wait-set (:machine restored))))
    (is (reproduces? frozen restored))))


(deftest exclusive-recovery-retains-operation-ids-issues-and-local-enrollment-test
  (let [op-id (rs/op-id 0)
        source (-> (rs/parked-writer)
                   (assoc :yin.k/issued 10)
                   (assoc-in [:wait-set 0 :yin.k/issue] 9)
                   (rs/with-op-id :put op-id))
        header (rs/header 1 rs/origin #{"s0"})
        {:keys [frozen prepared]} (frozen-of source header)
        {:keys [calls attach]} (counting-attach)
        restored (rehydrate frozen (rs/new-machine) attach)
        recovery (cbor/decode (:bytes frozen))]
    (is (= :ok (:status prepared)) (pr-str prepared))
    (is (= :ok (:status restored)) (pr-str restored))
    (is (= op-id (get-in restored [:record :wait-set 0 :op-id])))
    (is (= 9 (get-in restored [:record :wait-set 0 :yin.k/issue])) "the recovered stamp, not the lower's numbering")
    (is (= 10 (get-in restored [:machine :yin.k/issued])))
    (is (= #{"s0"} (get-in restored [:record :header :yin.k/enrolled])) "the enrolled set stays local")
    (is (= #{"s0"} (get-in recovery [:yin.k/header :yin.k/enrolled])))
    (is (not (some #(= :yin.k/enrolled %) (tree-seq coll? seq (cbor/decode (:pack-bytes frozen)))))
        "and never enters the pack")
    (is (nil? (get-in restored [:machine :yin.k/custody])))
    (is (zero? @calls))
    (is (reproduces? frozen restored))))


(deftest rehydrating-an-install-child-does-not-repeat-initialization-or-io-test
  (let [initializations (atom 0)
        counting-make (fn [capacity]
                        (swap! initializations inc)
                        (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                                             ringbuffer/capacity-key capacity}))
        request (ring 64)
        response (ring 64)
        blocked (vm/run (rs/load-ast (rs/new-machine {:make-stream counting-make
                                                      :link-request request :link-response response})
                                     (rs/then (rs/app (rs/v 'require) (rs/lit 'host.mod))
                                              (rs/app (rs/v 'host.mod/f)))))
        _ (stream/append! response (assoc (rs/module-response)
                                          :yin.link/id (:link-id (first (:wait-set blocked)))))
        source (vm/run blocked)
        receiver (rs/new-machine {:make-stream counting-make})
        carried @initializations
        {:keys [frozen]} (frozen-of source)
        {:keys [calls attach]} (counting-attach)
        restored (rehydrate frozen receiver attach)]
    (is (pos? carried))
    (is (= :ok (:status restored)) (pr-str restored))
    (is (= carried @initializations) "the child's initialization is not repeated")
    (is (zero? @calls))
    (is (= :exporting (vm/gate-mode (get-in restored [:machine :installs 'host.mod :vm]))))
    (is (empty? (get-in restored [:machine :installs 'host.mod :vm :wait-set])))
    (is (= 1 (count (get-in restored [:record :children 'host.mod :wait-set]))))
    (is (reproduces? frozen restored))))


(deftest recovery-retains-a-blocked-ffi-request-and-its-response-route-test
  (let [inbound (reify
                  stream/IDaoStreamDescriptor
                  (descriptor [_] {:dao.stream/outcome :dao.stream/ok :dao.stream/identity "full-in"})


                  stream/IDaoStreamWriter

                  (append! [_ _] {:dao.stream/outcome :dao.stream/full}))
        outbound (ring 8)
        source (vm/run (rs/load-ast
                         (rs/new-machine {:call-in inbound :call-out outbound
                                          :call-out-cursor (vm/mint-oldest outbound :test)})
                         {:type :dao.stream.apply/call :op :op/echo :operands [(rs/lit "hello")]}))
        _ (is (true? (:request-sent (first (:wait-set source)))) (pr-str (:wait-set source)))
        {:keys [frozen record]} (frozen-of source)
        {:keys [calls attach]} (counting-attach)
        restored (rehydrate frozen (rs/new-machine) attach)]
    (is (= :ok (:status frozen)) (pr-str frozen))
    (is (= :ok (:status restored)) (pr-str restored))
    (is (= (get-in record [:wait-set 0 :call-id])
           (get-in restored [:record :wait-set 0 :call-id])))
    (is (= (get-in record [:wait-set 0 :datom])
           (get-in restored [:record :wait-set 0 :datom])))
    (is (zero? @calls))
    (is (reproduces? frozen restored))))


;; =============================================================================
;; Abort
;; =============================================================================

(defn- exporting
  [machine]
  (let [r (export/enter machine)]
    [(:machine r) (:record r) machine]))


(deftest abort-with-no-persisted-intent-restores-the-machine-test
  (let [[m record original] (exporting (parked-machine))
        r (export/abort m record [] nil)]
    (is (= :ok (:status r)))
    (is (= original (:machine r)) "the old local machine, as it was")))


(deftest abort-restores-the-prior-gate-test
  (let [[s sref _ _] (gated)
        s (run-effect s :stream/put {:stream sref, :val 1})
        [m record _] (exporting s)]
    (is (= s (:machine (export/abort m record [] nil))))))


(deftest abort-after-provably-not-appended-attempts-test
  (let [[m record original] (exporting (parked-machine))]
    (doseq [outcome [:dao.stream/full :dao.stream/invalid-value
                     :dao.stream/closed :dao.stream/refused]]
      (is (= original
             (:machine (export/abort m record
                                     [{:append outcome} {:append outcome}]
                                     nil)))
          (str outcome " proves nothing was appended")))))


(deftest abort-is-refused-when-the-offer-may-have-been-accepted-test
  (let [[m record _] (exporting (parked-machine))
        refused? (fn [attempts]
                   (let [r (export/abort m record attempts nil)]
                     (and (= :yin.k/refused (:yin.k/status r))
                          (= :offer-possibly-accepted (:yin.k/reason r)))))]
    (testing "an unknown append (intent persisted, no outcome)"
      (is (refused? [{}])))
    (testing "an unknown append among provable ones"
      (is (refused? [{:append :dao.stream/full} {}])))
    (testing "an accepted append"
      (is (refused? [{:append :dao.stream/ok}])))
    (testing "a transport error is unknown delivery"
      (is (refused? [{:append :dao.stream/transport-error}])))
    (testing "a refusal that followed an unknown append is no evidence"
      (is (refused? [{} {:append :dao.stream/full
                         :reply :yin.k/refused}])))
    (testing "a refusal of one request is no evidence of the others"
      (is (refused? [{:append :dao.stream/ok :reply :yin.k/refused}])))
    (testing "the machine stays fenced"
      (is (= :exporting (vm/gate-mode m))))))


(deftest a-holder-past-its-lease-bound-cannot-abort-test
  (let [[s sref _ _] (gated)
        s (-> (run-effect s :stream/put {:stream sref, :val 1})
              (assoc :yin.k/custody {:yin.k/occurrence "o"}))
        [m record original] (exporting s)
        abort (fn [tenure] (export/abort m record [] tenure))
        reason (fn [r] [(:yin.k/status r) (:yin.k/reason r)])]
    (testing "current, ledger-confirmed tenure may abort"
      (is (= original (:machine (abort {:now 10 :bound 20 :live true})))))
    (testing "at the bound and past it, never"
      (doseq [t [{:now 20 :bound 20 :live true}
                 {:now 21 :bound 20 :live true}
                 {:now 21 :bound 20}]]
        (is (= [:yin.k/refused :tenure-ended] (reason (abort t))))))
    (testing "inside the bound with no ledger evidence is unconfirmed"
      (doseq [t [{:now 10 :bound 20} {:now 10 :bound 20 :live false} nil]]
        (is (= [:yin.k/refused :tenure-unconfirmed] (reason (abort t))))))
    (testing "tenure alone does not license an uncertain offer"
      (is (= :offer-possibly-accepted
             (:yin.k/reason (export/abort m record [{}]
                                          {:now 10 :bound 20 :live true})))))))


(deftest abort-needs-an-exporting-machine-test
  (let [[m record _] (exporting (parked-machine))
        restored (:machine (export/abort m record [] nil))]
    (is (= :yin.k/refused
           (:yin.k/status (export/abort restored record [] nil))))))


;; Holder preparation checks moved from the old-codec lift suite.

(defn- same-bytes?
  [a b]
  (= (vec a) (vec b)))


(defn- prepare
  [m record serve! header]
  (export/prepare m record serve! header (index)))


(defn- prepared
  [m header]
  (let [e (export/enter m)]
    {:machine (:machine e)
     :prepared (prepare (:machine e) (:record e) (rs/serve-table) header)}))


(deftest a-malformed-header-is-a-caller-defect-test
  (let [{m :machine record :record} (export/enter (rs/parked-reader))]
    (doseq [h [:x {} (dissoc (rs/header 0) :yin.k/arbitration)
               (dissoc (rs/header 0) :yin.k/next-op-seq)
               (dissoc (rs/header 0) :yin.k/enrolled)]]
      (is (throws? #(prepare m record (rs/serve-table) h))
          (pr-str h)))))


(deftest two-encodes-of-one-prepared-record-give-equal-bytes-test
  (doseq [[n machine h] [["reader" (rs/parked-reader) (rs/header 0 nil #{})]
                         ["installer" (rs/parked-installer) (rs/header 6)]
                         ["explicit" (rs/parked-explicit) (rs/header 1)]
                         ["fork" (rs/parked-reader) nil]]]
    (let [{m :machine p :prepared} (prepared machine h)
          record (:record p)
          a (export/encode m record)
          b (export/encode m record)]
      (is (= :ok (:status p)) (pr-str (dissoc p :record)))
      (is (same-bytes? (:bytes a) (:bytes b)) n)
      (is (= (:address a) (:address b)) n))))


(deftest the-served-table-is-plain-data-keyed-by-path-and-resource-test
  (let [{p :prepared} (prepared (rs/parked-installer) (rs/header 6))
        served (:served (:record p))]
    (is (seq served))
    (is (every? (fn [[[path id] descriptor]]
                  (and (vector? path) (some? id)
                       (string? (:dao.stream/identity descriptor))))
                served))
    (is (some (fn [[[path _] _]] (= ['host.mod] path)) served)
        "the child's streams are keyed by its path"))
  (testing "one handle under two resource ids: served once, keyed twice"
    (let [calls (atom 0)
          serve! (fn [_]
                   (swap! calls inc) {:dao.stream/identity "s0"
                                      :dao.stream/channel
                                      {:dao.stream/type
                                       :dao.stream.test/channel}})
          {m :machine record :record} (export/enter (rs/parked-writer-shared))
          p (prepare m record serve! (rs/header 3 rs/origin #{}))
          e (export/encode m (:record p))]
      (is (= :ok (:status p)) (pr-str (dissoc p :record)))
      (is (= 1 @calls) "the one handle asked once")
      (is (= 2 (count (:served (:record p)))) "both resource keys retained")
      (is (= :ok (:status e)) (pr-str e))
      (is (throws? #(export/encode
                      m (update (:record p) :served
                                dissoc (ffirst (:served (:record p))))))
          "a key the record lacks is still a defect"))))


(deftest a-retry-serves-no-already-served-handle-test
  (let [served {:dao.stream/identity "s0"
                :dao.stream/channel {:dao.stream/type
                                     :dao.stream.test/channel}}
        seen (atom #{})
        ;; answers the first distinct handle it is asked about, and that
        ;; one whenever it is asked again; every other handle gets no
        ;; answer, so the prepare refuses once the walk reaches the
        ;; independent third stream, after the shared handle's aliases
        first-only (fn [h]
                     (when (or (contains? @seen h) (empty? @seen))
                       (swap! seen conj h)
                       served))
        {m :machine record :record} (export/enter
                                      (rs/parked-writer-shared-late))
        refused (prepare m record first-only (rs/header 3 rs/origin #{}))]
    (is (= :yin.k/unsatisfied (:yin.k/status refused)) (pr-str refused))
    (is (= 1 (count @seen)) "exactly one handle ever answered")
    (is (every? #(= served %) (vals (:served (:record refused))))
        "what was served is retained")
    (testing "the retry resolves the shared handle's every alias from the
              retained table and the machine's bindings: the handle is
              not served again"
      (let [calls (atom 0)
            table (atom {})
            counting (fn [h]
                       (swap! calls inc)
                       (if-some [d (get @table h)]
                         d
                         (let [d {:dao.stream/identity
                                  (str "s" (count @table))}]
                           (swap! table assoc h d)
                           d)))
            p (prepare m (:record refused) counting
                       (rs/header 3 rs/origin #{}))
            e (export/encode m (:record p))]
        (is (= :ok (:status p)) (pr-str (dissoc p :record)))
        (is (= 1 @calls) "only the never-served third stream is asked")
        (is (= 3 (count (:served (:record p)))) "every resource key")
        (is (= 2 (count (distinct (vals (:served (:record p))))))
            "one identity for the shared handle's two keys")
        (is (= :ok (:status e)) (pr-str e))
        (is (= 1 (count @seen)) "the first attempt served once")))
    (testing "a retry over a machine whose bindings moved -- a fresh
              resource alias of the served handle reached first"
      (let [[path id :as retained] (first (keys (:served (:record refused))))
            h (get-in m (into path [:resources id]))
            _ (is (some? retained) "one key is retained")
            _ (is (some? h) "the retained key resolves to its handle")
            [alias m2] (engine/attach-resource m h)
            ;; prepare reads the record's waits, so the fresh alias rides
            ;; the record's first wait entry's environment
            record' (assoc-in (:record refused) [:wait-set 0 :env 'again]
                              alias)
            calls (atom 0)
            counting (fn [_] (swap! calls inc) served)
            p (prepare m2 record' counting (rs/header 3 rs/origin #{}))]
        (is (= :ok (:status p)) (pr-str (dissoc p :record)))
        (is (= 1 @calls) "only the never-served third stream is asked")
        (is (= 4 (count (:served (:record p))))
            "the fresh alias, the retained alias, the pending's and the
             third stream's")
        (is (= :ok (:status (export/encode m2 (:record p))))
            "the record encodes with no serving")))))


(deftest the-record-round-trips-the-canonical-codec-test
  (let [{m :machine p :prepared} (prepared (rs/parked-explicit) (rs/header 1))
        record (:record p)
        back (cbor/decode (cbor/encode record))]
    (is (= :ok (:status p)) (pr-str (dissoc p :record)))
    (is (same-bytes? (cbor/encode record) (cbor/encode back)))
    (is (same-bytes? (:bytes (export/encode m record))
                     (:bytes (export/encode m back)))
        "the stored record encodes to the same bytes")))


(deftest recovery-retains-every-parked-record-test
  (let [source (rs/parked-explicit)
        [active saved] (first (:parked source))
        extra :unreachable-park
        source (assoc-in source [:parked extra] (assoc saved :id extra))
        {:keys [frozen]} (frozen-of source)
        {:keys [calls attach]} (counting-attach)
        restored (rehydrate frozen (rs/new-machine) attach)]
    (is (= :ok (:status frozen)))
    (is (= :ok (:status restored)))
    (is (= #{active extra} (set (keys (get-in restored [:record :parked])))))
    (is (empty? (get-in restored [:machine :parked])))
    (is (zero? @calls))
    (is (reproduces? frozen restored))))
