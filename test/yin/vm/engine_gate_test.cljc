(ns yin.vm.engine-gate-test
  "D4: the gate on the engine's immediate stream effects (put, next, poll)
   and the observe/apply split. Counting handles show that a gated machine
   makes zero stream calls."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


(defn- throws?
  [thunk]
  (try (thunk) false
       (catch #?(:clj Exception :cljs js/Error :cljd Object) _ true)))


(defn- counting-handle
  "A handle answering `read-outcome` to every `next`, with one counter for
   every call that reaches it."
  [calls read-outcome]
  (reify
    stream/IDaoStreamReader

    (cursor
      [_ _]
      (swap! calls inc)
      {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor ::at})

    (next
      [_ _]
      (swap! calls inc)
      read-outcome)


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


(def ^:private park-entry-fns
  {:stream/put (fn [_s _e r] {:reason :put, :stream-id (:stream-id r)}),
   :stream/next (fn [_s _e r]
                  {:reason :next,
                   :cursor-ref (:cursor-ref r),
                   :stream-id (:stream-id r)})})


(def ^:private opts {:park-entry-fns park-entry-fns})


(defn- setup
  "[state stream-ref cursor-ref calls] over a counting handle; the cursor
   is minted before the counter is zeroed, so `calls` counts only what
   the test does."
  [read-outcome]
  (let [calls (atom 0)
        [sref s0] (engine/attach-resource
                    (vm/empty-state {:make-stream tu/make-stream,
                                     :capability-secret tu/secret})
                    (counting-handle calls read-outcome))
        [cref s1] (engine/handle-cursor s0 {:stream sref} :cursor-0)]
    (reset! calls 0)
    [s1 sref cref calls]))


(defn- effect
  [kind m]
  (module/make-effect kind m))


(defn- effects
  [sref cref]
  {:put (effect :stream/put {:stream sref, :val 1}),
   :next (effect :stream/next {:cursor cref}),
   :poll (effect :stream/poll {:cursor cref}),
   :close (effect :stream/close {:stream sref}),
   :cursor (effect :stream/cursor {:stream sref})})


(deftest gate-mode-reads-the-one-key-test
  (is (nil? (vm/gate-mode {})))
  (is (= :running (vm/gate-mode {:yin.k/gate :running})))
  (is (= :exporting (vm/gate-mode {:yin.k/gate :exporting})))
  (is (= :ended (vm/gate-mode {:yin.k/gate :ended}))))


(deftest running-gate-parks-every-immediate-with-zero-calls-test
  (let [[s sref cref calls] (setup {:dao.stream/outcome :dao.stream/ok,
                                    :dao.stream/value :v,
                                    :dao.stream/cursor ::next})
        s (assoc s :yin.k/gate :running)
        e (effects sref cref)]
    (testing "put parks as the writer it would be on full"
      (let [r (engine/handle-effect s (:put e) opts)]
        (is (true? (:blocked? r)))
        (is (= :put (:reason (first (:wait-set (:state r))))))
        (is (= 1 (:datom (first (:wait-set (:state r))))))))
    (testing "next parks as the reader it would be on blocked"
      (let [r (engine/handle-effect s (:next e) opts)
            entry (first (:wait-set (:state r)))]
        (is (true? (:blocked? r)))
        (is (= :next (:reason entry)))
        (is (= cref (:cursor-ref entry)))))
    (testing "poll parks as a machine-only :observe entry"
      (let [r (engine/handle-effect s (:poll e) opts)
            entry (first (:wait-set (:state r)))]
        (is (true? (:blocked? r)))
        (is (= :observe (:reason entry)))
        (is (= :poll (:op entry)))
        (is (= cref (:cursor-ref entry)))
        (is (= :stream-0 (:stream-id entry)))
        (is (not (contains? entry :yin.k/held)) "nothing held until observed")))
    (is (zero? @calls) "no stream call was made")))


(deftest closed-gates-observe-nothing-test
  (doseq [mode [:exporting :ended]]
    (let [[s sref cref calls] (setup {:dao.stream/outcome :dao.stream/ok,
                                      :dao.stream/value :v,
                                      :dao.stream/cursor ::next})
          s (assoc s :yin.k/gate mode)]
      (doseq [[kind eff] (effects sref cref)]
        (is (throws? #(engine/handle-effect s eff opts))
            (str kind " is refused in " mode)))
      (is (zero? @calls) (str "no stream call in " mode)))))


(deftest the-sweep-skips-observe-entries-test
  (let [[s sref cref calls] (setup {:dao.stream/outcome :dao.stream/ok,
                                    :dao.stream/value :v,
                                    :dao.stream/cursor ::next})
        parked (:state (engine/handle-effect (assoc s :yin.k/gate :running)
                                             ((effects sref cref) :poll)
                                             opts))
        swept (engine/check-wait-set (dissoc parked :yin.k/gate))]
    (is (zero? @calls) "even an ungated sweep never reads an :observe entry")
    (is (= 1 (count (:wait-set swept))))
    (is (empty? (:ready-queue swept)))))


(deftest apply-observation-matches-the-ungated-poll-test
  (doseq [[label outcome]
          [["ok" {:dao.stream/outcome :dao.stream/ok,
                  :dao.stream/value :v,
                  :dao.stream/cursor ::next}]
           ["blocked" {:dao.stream/outcome :dao.stream/blocked}]
           ["gap" {:dao.stream/outcome :dao.stream/gap,
                   :dao.stream/cursor ::recovered}]]]
    (testing label
      (let [[s sref cref _] (setup outcome)
            poll ((effects sref cref) :poll)
            ungated (engine/handle-effect s poll opts)
            parked (:state (engine/handle-effect (assoc s :yin.k/gate :running)
                                                 poll
                                                 opts))
            entry (first (:wait-set parked))
            applied (engine/apply-observation parked entry outcome)
            ready (first (:ready-queue applied))]
        (is (= (:value ungated) (:value ready)))
        (is (= (:resources (:state ungated)) (:resources applied))
            "the cursor cell advances exactly as the ungated path does")
        (is (empty? (:wait-set applied)))
        (is (= (:reason entry) :observe))))))


(deftest apply-observation-makes-no-stream-call-and-refuses-late-test
  (let [outcome {:dao.stream/outcome :dao.stream/ok,
                 :dao.stream/value :v,
                 :dao.stream/cursor ::next}
        [s sref cref calls] (setup outcome)
        parked (:state (engine/handle-effect (assoc s :yin.k/gate :running)
                                             ((effects sref cref) :poll)
                                             opts))
        entry (first (:wait-set parked))]
    (engine/apply-observation parked entry outcome)
    (is (zero? @calls) "apply performs no IO")
    (doseq [mode [:exporting :ended]]
      (is (throws? #(engine/apply-observation (assoc parked :yin.k/gate mode)
                                              entry
                                              outcome))
          (str "a late result is refused in " mode)))
    (is (throws? #(engine/apply-observation parked {:reason :next} outcome))
        "only an :observe entry is applied")))


;; =============================================================================
;; D5: the sweep, the unminted cursor cell, the close queue
;; =============================================================================

(def ^:private read-ok
  {:dao.stream/outcome :dao.stream/ok,
   :dao.stream/value :v,
   :dao.stream/cursor ::next})


(defn- setup0
  "[state stream-ref calls] over a counting handle, no cursor minted."
  [read-outcome]
  (let [calls (atom 0)
        [sref s0] (engine/attach-resource
                    (vm/empty-state {:make-stream tu/make-stream,
                                     :capability-secret tu/secret})
                    (counting-handle calls read-outcome))]
    [s0 sref calls]))


(defn- run-effect
  [s eff]
  (engine/handle-effect s eff opts))


(deftest running-sweep-does-not-poll-gated-puts-and-nexts-test
  (let [[s sref cref calls] (setup read-ok)
        e (effects sref cref)
        g (assoc s :yin.k/gate :running)
        parked (-> g
                   (run-effect (:put e))
                   :state
                   (run-effect (:next e))
                   :state)]
    (is (= 2 (count (:wait-set parked))))
    (testing "a gated sweep makes zero handle calls and keeps both entries"
      (let [swept (engine/check-wait-set parked)]
        (is (zero? @calls))
        (is (= (set (:wait-set parked)) (set (:wait-set swept))))
        (is (empty? (:ready-queue swept)))))
    (testing "an ungated sweep retries them as before"
      (let [swept (engine/check-wait-set (dissoc parked :yin.k/gate))]
        (is (pos? @calls))
        (is (= 2 (count (:ready-queue swept))))))))


(deftest running-cursor-installs-an-unminted-cell-test
  (let [[s sref calls] (setup0 read-ok)
        s (assoc s :yin.k/gate :running)
        cursor (effect :stream/cursor {:stream sref})
        r1 (run-effect s cursor)
        r2 (run-effect (:state r1) cursor)
        c1 (:value r1)
        c2 (:value r2)
        cell (fn [r c] (get-in (:state r) [:resources (:id c)]))]
    (is (zero? @calls) "no handle call")
    (is (false? (:blocked? r1)) "not blocked")
    (is (= :cursor-ref (:type c1)))
    (is (= {:stream-id :stream-0,
            :yin.k/unminted {:origin :dao.stream/oldest}}
           (update (cell r1 c1) :yin.k/unminted dissoc :seq)))
    (is (< (:seq (:yin.k/unminted (cell r1 c1)))
           (:seq (:yin.k/unminted (cell r2 c2))))
        "creation order rises")
    (testing "a read through the unminted cell parks as an ordinary next"
      (let [r (run-effect (:state r2) (effect :stream/next {:cursor c1}))
            entry (first (:wait-set (:state r)))]
        (is (true? (:blocked? r)))
        (is (= :next (:reason entry)))
        (is (= c1 (:cursor-ref entry)))
        (is (zero? @calls))))))


(deftest apply-mint-matches-the-ungated-run-test
  (doseq [[label outcome]
          [["ok" read-ok]
           ["gap" {:dao.stream/outcome :dao.stream/gap,
                   :dao.stream/cursor ::recovered}]]]
    (testing label
      (let [[s sref calls] (setup0 outcome)
            cursor (effect :stream/cursor {:stream sref})
            ungated (run-effect s cursor)
            cref (:value ungated)
            poll (effect :stream/poll {:cursor cref})
            ungated-poll (run-effect (:state ungated) poll)
            gated (run-effect (assoc s :yin.k/gate :running) cursor)
            _ (reset! calls 0)
            minted (engine/apply-mint (:state gated) (:id cref) ::at)
            parked (:state (run-effect minted poll))
            entry (first (:wait-set parked))
            applied (engine/apply-observation parked entry outcome)]
        (is (zero? @calls) "apply-mint and the apply make no handle call")
        (is (= (:value ungated-poll) (:value (first (:ready-queue applied)))))
        (is (= (:resources (:state ungated-poll)) (:resources applied)))))))


(deftest apply-mint-is-refused-when-it-cannot-apply-test
  (let [[s sref calls] (setup0 read-ok)
        cursor (effect :stream/cursor {:stream sref})
        gated (run-effect (assoc s :yin.k/gate :running) cursor)
        id (:id (:value gated))
        minted (engine/apply-mint (:state gated) id ::at)]
    (is (throws? #(engine/apply-mint minted id ::at)) "a second mint")
    (is (throws? #(engine/apply-mint (:state gated) :stream-0 ::at))
        "not a cursor cell")
    (doseq [mode [:exporting :ended]]
      (let [late (assoc (:state gated) :yin.k/gate mode)]
        (is (throws? #(engine/apply-mint late id ::at)) (str mode))
        (is (contains? (get-in late [:resources id]) :yin.k/unminted))))
    (is (zero? @calls))))


(deftest ungated-cursor-mints-once-test
  (let [[s sref calls] (setup0 read-ok)
        r (run-effect s (effect :stream/cursor {:stream sref}))]
    (is (= 1 @calls))
    (is (= (vm/cursor-entry :stream-0 ::at)
           (get-in (:state r) [:resources (:id (:value r))])))))


(deftest running-close-queues-a-record-test
  (let [[s sref calls] (setup0 read-ok)
        g (assoc s :yin.k/gate :running)
        close (effect :stream/close {:stream sref})
        put (effect :stream/put {:stream sref, :val 1})
        r0 (run-effect g put)
        r1 (run-effect (:state r0) close)
        r2 (run-effect (:state r1) put)
        st (:state r2)
        puts (filterv #(= :put (:reason %)) (:wait-set st))]
    (is (zero? @calls) "no handle call")
    (is (false? (:blocked? (run-effect g close))) "not parked")
    (is (nil? (:value r1)))
    (is (= [{:stream-id :stream-0, :yin.k/issue 1}] (:yin.k/closes st)))
    (is (= [0 2] (mapv :yin.k/issue puts)) "issue order across put and close")
    (testing "apply-close removes exactly the record"
      (let [two (:state (run-effect st close))
            done (engine/apply-close two :stream-0 1)]
        (is (= [{:stream-id :stream-0, :yin.k/issue 3}] (:yin.k/closes done)))
        (is (zero? @calls))
        (doseq [mode [:exporting :ended]]
          (is (throws? #(engine/apply-close (assoc two :yin.k/gate mode)
                                            :stream-0
                                            1))))))
    (testing "a foreign reference fails and queues nothing"
      (is (throws? #(run-effect g
                                (effect :stream/close
                                        {:stream (assoc sref
                                                        :id :stream-9)})))))))


(deftest ungated-close-is-unchanged-test
  (let [[s sref calls] (setup0 read-ok)
        r (run-effect s (effect :stream/close {:stream sref}))]
    (is (= 1 @calls))
    (is (nil? (:value r)))
    (is (not (contains? (:state r) :yin.k/closes)))
    (is (not (contains? (:state r) :yin.k/issued)))))


;; --- the four kernels ---------------------------------------------------

(defn- counted-make-stream
  "`tu/make-stream` whose handles count every call into `calls`. Only the
   program's streams (capacity 4) count: a VM builds its call pair at
   construction, before the program runs."
  [calls]
  (fn [capacity]
    (let [r (tu/make-stream capacity)
          h (:dao.stream/handle r)]
      (if-not (= 4 capacity)
        r
        (assoc r
               :dao.stream/handle
               (reify
                 stream/IDaoStreamReader

                 (cursor
                   [_ a]
                   (swap! calls inc)
                   (stream/cursor h a))

                 (next
                   [_ c]
                   (swap! calls inc)
                   (stream/next h c))


                 stream/IDaoStreamWriter

                 (append!
                   [_ v]
                   (swap! calls inc)
                   (stream/append! h v))


                 stream/IDaoStreamClosable

                 (close!
                   [_]
                   (swap! calls inc)
                   (stream/close! h))))))))


(def ^:private load-semantic
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(def ^:private kernels
  "Each kernel's run of `ast` on a fresh VM from `opts`, gated with `gate`."
  {:ast-walker (fn [opts gate ast]
                 (vm/run (cond-> (ast-walker/vm-load-program
                                   (ast-walker/create-vm opts)
                                   (vm/ast->datoms ast)
                                   vm/ast-contract)
                           gate (assoc :yin.k/gate gate)))),
   :semantic (fn [opts gate ast]
               (vm/run (cond-> (load-semantic (semantic/create-vm opts)
                                              (vm/ast->datoms ast))
                         gate (assoc :yin.k/gate gate)))),
   :debruijn-stack
   (fn [opts gate ast]
     (vm/run (cond-> (dvm/create-vm (:image (dl/adapt (vm/ast->datoms ast)))
                                    (assoc opts
                                           :contract vm/stack-contract
                                           :primitives vm/primitives))
               gate (assoc :yin.k/gate gate)))),
   :debruijn-register
   (fn [opts gate ast]
     (vm/run (cond-> (rvm/create-vm (:image (rc/adapt (second
                                                        (vm/ast->datoms-with-root
                                                          ast))))
                                    (assoc opts
                                           :contract vm/register-contract))
               gate (assoc :yin.k/gate gate))))})


(defn- lit
  [v]
  {:type :literal, :value v})


(defn- with-stream
  "`body-fn` of a variable bound to a fresh stream."
  [body-fn]
  {:type :application,
   :operator {:type :lambda,
              :params ['s],
              :body (body-fn {:type :variable, :name 's})},
   :operands [{:type :stream/make, :buffer 4}]})


(defn- kernel-opts
  [calls]
  {:make-stream (counted-make-stream calls), :capability-secret tu/secret})


(defn- unminted-cells
  [result]
  (->> (:resources result)
       (filter (fn [[_ cell]] (and (map? cell) (:yin.k/unminted cell))))
       (sort-by (comp :seq :yin.k/unminted second))))


(deftest running-cursor-on-every-kernel-test
  (doseq [[kind run] kernels]
    (testing (str kind)
      (let [calls (atom 0)
            result (run (kernel-opts calls)
                        :running
                        (with-stream
                          (fn [s]
                            {:type :application,
                             :operator {:type :lambda,
                                        :params ['_],
                                        :body {:type :stream/cursor,
                                               :source s}},
                             :operands [{:type :stream/cursor, :source s}]})))
            cells (unminted-cells result)]
        (is (zero? @calls) "no handle call")
        (is (vm/halted? result) "the program continued past the cursors")
        (is (= :cursor-ref (:type (vm/value result))))
        (is (= 2 (count cells)))
        (is (= #{:dao.stream/oldest}
               (set (map (comp :origin :yin.k/unminted second) cells))))
        (is (apply < (map (comp :seq :yin.k/unminted second) cells)))
        (is (= (:id (vm/value result)) (first (last cells)))
            "the later cursor is the program's value"))
      (testing "a read through the unminted cell parks as a next"
        (let [calls (atom 0)
              result (run (kernel-opts calls)
                          :running
                          (with-stream
                            (fn [s]
                              {:type :application,
                               :operator {:type :lambda,
                                          :params ['c],
                                          :body {:type :stream/next,
                                                 :source {:type :variable,
                                                          :name 'c}}},
                               :operands [{:type :stream/cursor,
                                           :source s}]})))]
          (is (zero? @calls))
          (is (vm/blocked? result))
          (is (= [:next] (mapv :reason (:wait-set result))))))))
  (doseq [[kind run] kernels]
    (testing (str kind " ungated")
      (let [calls (atom 0)
            result (run (kernel-opts calls)
                        nil
                        (with-stream (fn [s]
                                       {:type :stream/cursor, :source s})))]
        (is (= 1 @calls) "one mint")
        (is (empty? (unminted-cells result)))))))


(deftest running-close-on-every-kernel-test
  (let [program (with-stream
                  (fn [s]
                    {:type :application,
                     :operator {:type :lambda,
                                :params ['_],
                                :body (lit 7)},
                     :operands [{:type :stream/close, :source s}]}))]
    (doseq [[kind run] kernels]
      (testing (str kind)
        (let [calls (atom 0)
              result (run (kernel-opts calls) :running program)]
          (is (zero? @calls) "no handle call")
          (is (vm/halted? result))
          (is (= 7 (vm/value result)) "the program continued")
          (is (= 1 (count (:yin.k/closes result))))
          (is (= 0 (:yin.k/issue (first (:yin.k/closes result))))))))
    (doseq [[kind run] kernels]
      (testing (str kind " ungated")
        (let [calls (atom 0)
              result (run (kernel-opts calls) nil program)]
          (is (= 1 @calls))
          (is (= 7 (vm/value result)))
          (is (not (contains? result :yin.k/closes))))))))


;; --- the fenced FFI request ---------------------------------------------

(defn- call-in-handle
  "A call-in handle recording appends; answers `outcome` to each."
  [attempts outcome]
  (reify
    stream/IDaoStreamReader

    (cursor
      [_ _]
      {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor :in-0})

    (next [_ _] {:dao.stream/outcome :dao.stream/blocked})


    stream/IDaoStreamWriter

    (append!
      [_ v]
      (swap! attempts conj v)
      {:dao.stream/outcome outcome})))


(defn- ffi-opts
  [attempts outcome]
  (let [call-out (tu/new-stream 8)]
    {:make-stream tu/make-stream,
     :capability-secret tu/secret,
     :call-in (call-in-handle attempts outcome),
     :call-out call-out,
     :call-out-cursor (vm/mint-oldest call-out :test)}))


(deftest running-ffi-request-parks-the-retained-request-test
  (let [program {:type :dao.stream.apply/call,
                 :op :op/echo,
                 :operands [(lit 3)]}]
    (doseq [[kind run] kernels]
      (testing (str kind)
        (let [attempts (atom [])
              gated (run (ffi-opts attempts :dao.stream/ok) :running program)
              plain (atom [])
              full (run (ffi-opts plain :dao.stream/full) nil program)
              entry (first (:wait-set gated))]
          (is (zero? (count @attempts)) "no put-request! call under the gate")
          (is (vm/blocked? gated))
          (is (= (:wait-set full) (:wait-set gated))
              "identical to the ungated full branch's retained entry")
          (is (= :put (:reason entry)))
          (is (not (contains? entry :yin.k/issue)) "FFI entries not stamped")
          (testing "the sweep leaves the retained request alone"
            (let [swept (engine/check-wait-set gated)]
              (is (zero? (count @attempts)))
              (is (= (:wait-set gated) (:wait-set swept))))))))))


(deftest walker-step-preserves-the-custody-keys-test
  (let [loaded (ast-walker/vm-load-program
                 (ast-walker/create-vm {})
                 (vm/ast->datoms {:type :application,
                                  :operator {:type :lambda,
                                             :params ['x],
                                             :body (lit 1)},
                                  :operands [(lit 2)]})
                 vm/ast-contract)
        gated (assoc loaded
                     :yin.k/gate :running
                     :yin.k/closes [{:stream-id :s, :yin.k/issue 0}]
                     :yin.k/issued 1)
        stepped (vm/step gated)]
    (is (not (identical? gated stepped)) "the step rebuilt the machine")
    (is (= :running (:yin.k/gate stepped)))
    (is (= [{:stream-id :s, :yin.k/issue 0}] (:yin.k/closes stepped)))
    (is (= 1 (:yin.k/issued stepped)))
    (is (not (contains? (vm/step loaded) :yin.k/gate)) "ungated stays bare")))


(deftest put-request-is-fenced-by-the-gate-test
  (let [attempts (atom [])
        call-in (call-in-handle attempts :dao.stream/ok)
        request (apply2/request :parked-0 :op/echo [3])]
    (is (= :dao.stream/ok
           (:dao.stream/outcome (ffi/put-request {} call-in request))))
    (is (= 1 (count @attempts)) "ungated appends, as put-request! does")
    (doseq [mode [:running :exporting :ended]]
      (is (= {:dao.stream/outcome :dao.stream/full}
             (ffi/put-request {:yin.k/gate mode} call-in request)))
      (is (= 1 (count @attempts)) (str "no handle call in " mode)))))


;; --- D6: links, install children, direct resume -------------------------

(def ^:private blocked-read {:dao.stream/outcome :dao.stream/blocked})


(defn- require-ast
  [module-name]
  {:type :application,
   :operator {:type :variable, :name 'require},
   :operands [(lit module-name)]})


(defn- link-opts
  "Kernel options over counting link streams."
  [calls]
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :modules (module/default-registry),
   :link-request (counting-handle calls blocked-read),
   :link-response (counting-handle calls blocked-read)})


(deftest running-require-miss-on-every-kernel-test
  (doseq [[kind run] kernels]
    (testing (str kind)
      (let [calls (atom 0)
            result (run (link-opts calls) :running (require-ast 'foo))
            entry (first (:wait-set result))]
        (is (zero? @calls) "zero appends and zero cursor mints")
        (is (vm/blocked? result))
        (is (= [:link-request] (mapv :reason (:wait-set result))))
        (is (not (contains? entry :cursor)) "no cursor on the entry")
        (is (some? (:envelope entry)) "the envelope is retained verbatim")
        (is (some? (:link-id entry)))
        (testing "the sweep neither retries nor scans"
          (let [swept (engine/check-wait-set result)]
            (is (zero? @calls))
            (is (= (:wait-set result) (:wait-set swept)))))))
    (testing (str kind " ungated")
      (let [calls (atom 0)
            result (run (link-opts calls) nil (require-ast 'foo))
            entry (first (:wait-set result))]
        (is (<= 2 @calls) "the cursor mint, the append, then the scan")
        (is (= :link-response (:reason entry)))
        (is (contains? entry :cursor))))))


(deftest gated-append-link-request-answers-full-test
  (let [calls (atom 0)
        resources {module/link-request-resource
                   (counting-handle calls blocked-read)}
        entry {:reason :link-request, :envelope {:yin.link/id 1}}]
    (is (= (assoc (dissoc entry :envelope) :reason :link-response)
           (module/append-link-request resources entry))
        "no gate argument: appends as before")
    (is (= 1 @calls))
    (doseq [mode [:running :exporting :ended]]
      (is (= entry (module/append-link-request resources entry mode)))
      (is (= 1 @calls) (str "no append in " mode)))))


(defn- link-response-state
  "A state holding a hand-built `:link-response` entry over a counting
   response stream."
  [calls mode]
  (let [h (counting-handle calls blocked-read)
        s (-> (vm/empty-state {:make-stream tu/make-stream,
                               :capability-secret tu/secret})
              (assoc-in [:resources module/link-response-resource] h)
              (assoc :wait-set [{:reason :link-response,
                                 :link-id [:t0 0],
                                 :name 'foo,
                                 :cursor ::at}]))]
    (cond-> s mode (assoc :yin.k/gate mode))))


(deftest link-response-scanning-is-gated-test
  (let [calls (atom 0)]
    (engine/check-wait-set (link-response-state calls nil))
    (is (pos? @calls) "ungated scans the response stream"))
  (doseq [mode [:running :exporting :ended]]
    (let [calls (atom 0)
          s (link-response-state calls mode)
          swept (engine/check-wait-set s)]
      (is (zero? @calls) (str "no scan in " mode))
      (is (= (:wait-set s) (:wait-set swept))))))


(deftest closed-modes-sweep-nothing-test
  (let [[s sref cref calls] (setup read-ok)
        e (effects sref cref)
        parked (-> (assoc s :yin.k/gate :running)
                   (run-effect (:put e))
                   :state
                   (run-effect (:next e))
                   :state)]
    (doseq [mode [:exporting :ended]]
      (let [swept (engine/check-wait-set (assoc parked :yin.k/gate mode))]
        (is (zero? @calls) (str "no retry in " mode))
        (is (= (:wait-set parked) (:wait-set swept)))
        (is (empty? (:ready-queue swept)))))))


(deftest ffi-response-routing-is-gated-on-every-kernel-test
  (let [program {:type :dao.stream.apply/call,
                 :op :op/echo,
                 :operands [(lit 3)]}]
    (doseq [[kind run] kernels]
      (testing (str kind)
        (let [attempts (atom [])
              plain (run (ffi-opts attempts :dao.stream/ok) nil program)
              entry (first (:wait-set plain))
              cell-id (:id (:cursor-ref entry))
              stream-id (get-in plain [:resources cell-id :stream-id])
              calls (atom 0)
              counted (assoc-in plain
                                [:resources stream-id]
                                (counting-handle calls blocked-read))]
          (is (vm/blocked? plain))
          (is (some? stream-id))
          (engine/check-wait-set counted)
          (is (pos? @calls) "ungated routing reads the response cell")
          (doseq [mode [:running :exporting :ended]]
            (reset! calls 0)
            (let [g (assoc counted :yin.k/gate mode)
                  swept (engine/check-wait-set g)]
              (is (zero? @calls) (str "no read in " mode))
              (is (= (:wait-set g) (:wait-set swept))))))))))


(defn- install-child
  "A not-yet-stamped semantic child blocked on a read of a fresh stream,
   its stream handles counting into `calls`."
  [calls]
  (load-semantic
    (semantic/create-vm (kernel-opts calls))
    (vm/ast->datoms
      (with-stream (fn [s]
                     {:type :application,
                      :operator {:type :lambda,
                                 :params ['c],
                                 :body {:type :stream/next,
                                        :source {:type :variable,
                                                 :name 'c}}},
                      :operands [{:type :stream/cursor, :source s}]})))))


(defn- install-state
  [child mode]
  (cond-> (assoc (vm/empty-state {:make-stream tu/make-stream,
                                  :capability-secret tu/secret})
                 :installs {'foo {:phase :running,
                                  :vm child,
                                  :parent [:t0 0],
                                  :response {}}})
    mode (assoc :yin.k/gate mode)))


(deftest running-install-child-is-stamped-and-advanced-test
  (let [calls (atom 0)
        swept (engine/check-wait-set
                (install-state (install-child calls) :running))
        inst (get-in swept [:installs 'foo])]
    (is (zero? @calls) "the child observes nothing")
    (is (= :running (:yin.k/gate (:vm inst))) "stamped before the run")
    (is (= :parked (:phase inst)) "and it advanced to its park")
    (is (vm/blocked? (:vm inst)))
    (is (= [:next] (mapv :reason (:wait-set (:vm inst)))))
    (is (not-any? #(contains? (:vm inst) %)
                  [:yin.k/custody :yin.k/closes :yin.k/issued :yin.k/lease])
        "the child carries the mode only")))


(deftest closed-modes-advance-no-install-child-test
  (doseq [mode [:exporting :ended]]
    (let [calls (atom 0)
          child (install-child calls)
          swept (engine/check-wait-set (install-state child mode))
          inst (get-in swept [:installs 'foo])]
      (is (zero? @calls))
      (is (= child (:vm inst)) (str "no child step in " mode))
      (is (= :running (:phase inst))))))


#?(:cljd nil
   :clj
   (deftest created-install-child-carries-the-mode-test
     (let [start (deref (var engine/start-install))
           calls (atom 0)
           image (:vector (linearize/lower-rows
                            (vm/ast->semantic-bytecode (lit 1))))
           root (semantic/create-vm (kernel-opts calls))]
       (doseq [mode [nil :running :exporting :ended]]
         (let [[s refusal] (start (cond-> root mode (assoc :yin.k/gate mode))
                                  'foo
                                  {:image {:value image}}
                                  [:t0 0])
               child (get-in s [:installs 'foo :vm])]
           (is (nil? refusal))
           (is (= mode (:yin.k/gate child))))))))


(deftest closed-modes-refuse-a-direct-resume-test
  (let [restored (atom 0)
        restore (fn [state _parked _val] (swap! restored inc) state)
        base (assoc (vm/empty-state {:make-stream tu/make-stream,
                                     :capability-secret tu/secret})
                    :parked {:parked-0 {:type :parked-continuation,
                                        :id :parked-0}})]
    (doseq [mode [nil :running]]
      (reset! restored 0)
      (engine/resume-continuation (cond-> base mode (assoc :yin.k/gate mode))
                                  :parked-0 1 restore)
      (is (= 1 @restored) (str "resume proceeds in " (or mode :ungated))))
    (doseq [mode [:exporting :ended]]
      (reset! restored 0)
      (is (throws? #(engine/resume-continuation
                      (assoc base :yin.k/gate mode)
                      :parked-0 1 restore)))
      (is (zero? @restored) (str "refused in " mode)))))


;; --- D6: the driver's link applies --------------------------------------

(defn- gated-miss
  "[state calls link-id] of a `require` miss on a gated semantic VM."
  []
  (let [calls (atom 0)
        s ((:semantic kernels) (link-opts calls) :running (require-ast 'foo))]
    [s calls (:link-id (first (:wait-set s)))]))


(defn- with-mode
  [s mode]
  (if mode (assoc s :yin.k/gate mode) (dissoc s :yin.k/gate)))


(deftest apply-link-cursor-installs-on-the-request-entry-test
  (let [[s calls id] (gated-miss)
        entry (first (:wait-set s))
        s' (engine/apply-link-cursor s id ::pos)
        entry' (first (:wait-set s'))]
    (is (= ::pos (:cursor entry')))
    (is (= (assoc entry :cursor ::pos) entry') "only the cursor is added")
    (is (= :link-request (:reason entry')))
    (is (= (:ready-queue s) (:ready-queue s')) "nothing woken")
    (is (zero? @calls) "zero stream calls")
    (testing "a double install is refused, even with an equal position"
      (is (throws? #(engine/apply-link-cursor s' id ::pos)))
      (is (throws? #(engine/apply-link-cursor s' id ::other))))
    (testing "an unknown id is refused"
      (is (throws? #(engine/apply-link-cursor s [:t0 99] ::pos))))
    (testing "a gated poll round after the install makes zero calls"
      (let [swept (engine/check-wait-set s')]
        (is (zero? @calls))
        (is (= (:wait-set s') (:wait-set swept)))))))


(deftest apply-link-cursor-targets-one-entry-test
  (let [[s _ id] (gated-miss)
        second-entry (assoc (first (:wait-set s)) :link-id [:t0 9])
        two (update s :wait-set conj second-entry)
        s' (engine/apply-link-cursor two [:t0 9] ::pos)]
    (is (not (contains? (first (:wait-set s')) :cursor)))
    (is (= ::pos (:cursor (second (:wait-set s')))))
    (is (= id (:link-id (first (:wait-set s')))))))


(deftest link-applies-are-refused-outside-running-test
  (let [[s _ id] (gated-miss)
        sent (engine/apply-link-sent (engine/apply-link-cursor s id ::pos)
                                     id)]
    (doseq [mode [nil :exporting :ended]]
      (testing (str (or mode :ungated))
        (is (throws? #(engine/apply-link-cursor (with-mode s mode) id ::pos)))
        (is (throws? #(engine/apply-link-sent
                        (with-mode (engine/apply-link-cursor s id ::pos) mode)
                        id)))
        (is (throws? #(engine/apply-link-read (with-mode sent mode)
                                              id
                                              {:dao.stream/outcome
                                               :dao.stream/blocked})))))))


(deftest apply-link-sent-moves-the-entry-to-link-response-test
  (let [[s calls id] (gated-miss)]
    (testing "an entry without a cursor cannot be sent"
      (is (throws? #(engine/apply-link-sent s id))))
    (let [s' (engine/apply-link-cursor s id ::pos)
          sent (engine/apply-link-sent s' id)
          entry (first (:wait-set sent))]
      (is (= :link-response (:reason entry)))
      (is (not (contains? entry :envelope)))
      (is (= ::pos (:cursor entry)))
      (is (= (:ready-queue s) (:ready-queue sent)))
      (is (zero? @calls))
      (is (throws? #(engine/apply-link-sent sent id)) "no longer a request")
      (is (throws? #(engine/apply-link-sent s' [:t0 99])))
      (testing "a gated round leaves it waiting with zero calls"
        (is (= (:wait-set sent)
               (:wait-set (engine/check-wait-set sent))))
        (is (zero? @calls))))))


(deftest apply-link-read-applies-one-outcome-test
  (let [[s calls id] (gated-miss)
        sent (-> s
                 (engine/apply-link-cursor id ::pos)
                 (engine/apply-link-sent id))
        ok (fn [v]
             {:dao.stream/outcome :dao.stream/ok,
              :dao.stream/value v,
              :dao.stream/cursor ::next})]
    (testing "blocked keeps waiting, state unchanged"
      (is (= sent
             (engine/apply-link-read sent id {:dao.stream/outcome
                                              :dao.stream/blocked}))))
    (testing "another id's response is skipped and the cursor advances"
      (let [s' (engine/apply-link-read sent id (ok {:yin.link/id [:t0 77]}))
            entry (first (:wait-set s'))]
        (is (= :link-response (:reason entry)))
        (is (= ::next (:cursor entry)))
        (is (empty? (:ready-queue s')))))
    (testing "its own refused response wakes the entry with the refusal"
      (let [s' (engine/apply-link-read
                 sent id (ok {:yin.link/id id, :status :refused,
                              :reason :absent}))]
        (is (empty? (:wait-set s')))
        (is (= 1 (count (:ready-queue s'))))
        (is (= :link-refused (:status (first (:ready-queue s')))))))
    (testing "gap and the rest refuse the entry"
      (doseq [o [:dao.stream/gap :dao.stream/end]]
        (let [s' (engine/apply-link-read sent id {:dao.stream/outcome o})]
          (is (empty? (:wait-set s')))
          (is (= :link-refused (:status (first (:ready-queue s'))))))))
    (testing "an unknown id, or a request still unsent, is refused"
      (is (throws? #(engine/apply-link-read sent [:t0 99]
                                            (ok {:yin.link/id id}))))
      (is (throws? #(engine/apply-link-read
                      (engine/apply-link-cursor s id ::pos) id
                      (ok {:yin.link/id id})))))
    (is (zero? @calls) "zero stream calls")))


;; --- D6: the driver's put and next applies ------------------------------

(defn- scripted-handle
  "A handle answering `put-outcome` to every append and `read-outcome` to
   every next, counting calls."
  [calls put-outcome read-outcome]
  (reify
    stream/IDaoStreamReader

    (cursor
      [_ _]
      (swap! calls inc)
      {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor ::at})

    (next
      [_ _]
      (swap! calls inc)
      read-outcome)


    stream/IDaoStreamWriter

    (append!
      [_ _]
      (swap! calls inc)
      put-outcome)))


(defn- parked-pair
  "[state put-entry next-entry cursor-id calls] of a gated machine holding
   one parked put and one parked next, over a handle answering the given
   outcomes to whatever is polled."
  [put-outcome read-outcome]
  (let [calls (atom 0)
        [sref s0] (engine/attach-resource
                    (vm/empty-state {:make-stream tu/make-stream,
                                     :capability-secret tu/secret})
                    (scripted-handle calls put-outcome read-outcome))
        [cref s1] (engine/handle-cursor s0 {:stream sref} :cursor-0)
        e (effects sref cref)
        parked (-> (assoc s1 :yin.k/gate :running)
                   (run-effect (:put e))
                   :state
                   (run-effect (:next e))
                   :state)
        [put-entry next-entry] (:wait-set parked)]
    (reset! calls 0)
    [parked put-entry next-entry (:id cref) calls]))


(def ^:private ok-put {:dao.stream/outcome :dao.stream/ok})


(def ^:private read-outcomes
  [{:dao.stream/outcome :dao.stream/ok,
    :dao.stream/value :v,
    :dao.stream/cursor ::next}
   {:dao.stream/outcome :dao.stream/blocked}
   {:dao.stream/outcome :dao.stream/end}
   {:dao.stream/outcome :dao.stream/gap, :dao.stream/cursor ::recovered}
   {:dao.stream/outcome :dao.stream/cursor-mismatch}
   {:dao.stream/outcome :dao.stream/refused}])


(deftest apply-put-matches-the-ungated-sweep-test
  (doseq [o [{:dao.stream/outcome :dao.stream/ok}
             {:dao.stream/outcome :dao.stream/full}
             {:dao.stream/outcome :dao.stream/closed}
             {:dao.stream/outcome :dao.stream/refused}]]
    (testing (str (:dao.stream/outcome o))
      (let [[parked put-entry _ _ calls] (parked-pair o read-ok)
            applied (engine/apply-put parked put-entry o)
            [ungated pe] (parked-pair o {:dao.stream/outcome
                                         :dao.stream/blocked})
            swept (engine/check-wait-set (dissoc ungated :yin.k/gate))]
        (is (= put-entry pe))
        (is (zero? @calls) "the apply makes no stream call")
        (is (= (filterv #(= :put (:reason %)) (:ready-queue swept))
               (:ready-queue applied))
            "the same ready entries as the ungated sweep's retry")
        (is (= (filterv #(= :put (:reason %)) (:wait-set swept))
               (filterv #(= :put (:reason %)) (:wait-set applied))))))))


(deftest apply-next-matches-the-ungated-sweep-test
  (doseq [o read-outcomes]
    (testing (str (:dao.stream/outcome o))
      (let [[parked _ next-entry cid calls] (parked-pair ok-put o)
            applied (engine/apply-next parked next-entry o)
            [ungated] (parked-pair {:dao.stream/outcome :dao.stream/full} o)
            swept (engine/check-wait-set (dissoc ungated :yin.k/gate))]
        (is (zero? @calls))
        (is (= (filterv #(= :next (:reason %)) (:ready-queue swept))
               (:ready-queue applied)))
        (is (= (filterv #(= :next (:reason %)) (:wait-set swept))
               (filterv #(= :next (:reason %)) (:wait-set applied))))
        (is (= (get-in swept [:resources cid :cursor])
               (get-in applied [:resources cid :cursor]))
            "the cell advances as the sweep advances it")))))


(deftest put-and-next-applies-refuse-test
  (let [[parked put-entry next-entry _ calls] (parked-pair ok-put read-ok)
        ok-read (first read-outcomes)]
    (doseq [mode [nil :exporting :ended]]
      (testing (str (or mode :ungated))
        (let [s (with-mode parked mode)]
          (is (throws? #(engine/apply-put s put-entry ok-put)))
          (is (throws? #(engine/apply-next s next-entry ok-read))))))
    (testing "an entry the wait set does not hold"
      (is (throws? #(engine/apply-put parked
                                      (assoc put-entry :stream-id :nope)
                                      ok-put)))
      (is (throws? #(engine/apply-next parked
                                       (assoc next-entry :stream-id :nope)
                                       ok-read))))
    (testing "an entry of the other kind"
      (is (throws? #(engine/apply-put parked next-entry ok-put)))
      (is (throws? #(engine/apply-next parked put-entry ok-read))))
    (testing "a second apply finds no entry"
      (let [after (engine/apply-put parked put-entry ok-put)]
        (is (throws? #(engine/apply-put after put-entry ok-put)))))
    (is (zero? @calls))))


;; --- D6: the driver's FFI applies ---------------------------------------

(defn- ffi-response
  [id value cursor]
  {:dao.stream/outcome :dao.stream/ok,
   :dao.stream/value (apply2/success-response id value),
   :dao.stream/cursor cursor})


(defn- out-cursor
  [s]
  (get-in s [:resources vm/call-out-cursor-key :cursor]))


(deftest ffi-sent-moves-the-request-to-its-reader-test
  (let [program {:type :dao.stream.apply/call,
                 :op :op/echo,
                 :operands [(lit 3)]}]
    (doseq [[kind run] kernels]
      (testing (str kind)
        (let [attempts (atom [])
              gated (run (ffi-opts attempts :dao.stream/ok) :running program)
              cid (ffi/request-call-id (first (:wait-set gated)))
              sent (engine/apply-ffi-sent gated cid)]
          (is (some? cid))
          (doseq [mode [nil :exporting :ended]]
            (is (throws? #(engine/apply-ffi-sent (with-mode gated mode)
                                                 cid))))
          (is (throws? #(engine/apply-ffi-sent gated :nope)))
          (is (empty? (:wait-set sent)))
          (is (= 1 (count (:ready-queue sent))))
          (is (throws? #(engine/apply-ffi-sent sent cid))
              "a second transition is refused")
          (let [waiting (vm/run sent)]
            (is (vm/blocked? waiting))
            (is (= cid (ffi/response-call-id (first (:wait-set waiting))))
                "restored as the call's response reader"))
          (is (zero? (count @attempts)) "no stream call"))))))


(deftest ffi-read-applies-one-response-test
  (let [program {:type :dao.stream.apply/call,
                 :op :op/echo,
                 :operands [(lit 3)]}
        blocked {:dao.stream/outcome :dao.stream/blocked}]
    (doseq [[kind run] kernels]
      (testing (str kind)
        (let [attempts (atom [])
              gated (run (ffi-opts attempts :dao.stream/ok) :running program)
              cid (ffi/request-call-id (first (:wait-set gated)))
              waiting (vm/run (engine/apply-ffi-sent gated cid))
              reader (first (:wait-set waiting))
              other (assoc reader :call-id :other)
              shared (update waiting :wait-set conj other)]
          (testing "refusals"
            (is (throws? #(engine/apply-ffi-read gated cid blocked))
                "a request is not yet a reader")
            (doseq [mode [nil :exporting :ended]]
              (is (throws? #(engine/apply-ffi-read (with-mode waiting mode)
                                                   cid
                                                   blocked))))
            (is (throws? #(engine/apply-ffi-read waiting :nope blocked)))
            (is (throws? #(engine/apply-next waiting reader blocked))
                "a response reader is not a plain next"))
          (testing "blocked keeps waiting"
            (is (= waiting (engine/apply-ffi-read waiting cid blocked))))
          (testing "its own response settles the call"
            (let [read (engine/apply-ffi-read waiting cid
                                              (ffi-response cid 3 ::after))
                  done (vm/run read)]
              (is (empty? (:wait-set read)))
              (is (= 1 (count (:ready-queue read))))
              (is (= ::after (out-cursor read)))
              (is (vm/halted? done))
              (is (= 3 (vm/value done)))))
          (testing "a response no reader owns is skipped and advances"
            (let [read (engine/apply-ffi-read waiting cid
                                              (ffi-response :nobody 9 ::after))]
              (is (= (:wait-set waiting) (:wait-set read)))
              (is (empty? (:ready-queue read)))
              (is (= ::after (out-cursor read)))
              (is (= :unmatched (:kind (first (:ffi-diagnostics read)))))))
          (testing "on a shared cell another reader's response wakes it"
            (let [read (engine/apply-ffi-read shared cid
                                              (ffi-response :other 4 ::after))]
              (is (= [reader] (:wait-set read)) "ours keeps waiting")
              (is (= [:other] (mapv :call-id (:ready-queue read))))
              (is (= ::after (out-cursor read)))))
          (testing "end wakes every reader of the cell"
            (let [read (engine/apply-ffi-read
                         shared cid {:dao.stream/outcome :dao.stream/end})]
              (is (empty? (:wait-set read)))
              (is (= [::ffi/response-ended ::ffi/response-ended]
                     (mapv :status (:ready-queue read))))))
          (is (zero? (count @attempts)) "zero stream calls throughout"))))))
