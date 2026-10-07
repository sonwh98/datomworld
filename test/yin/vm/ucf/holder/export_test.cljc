(ns yin.vm.ucf.holder.export-test
  "D8: the exporting state, the prepare/encode split and the abort rule
   (UCF 7.7.4; r3 1.8 and 1.9).  Every machine is parked by the real
   engine; counting handles show what the gate and the encode touch."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.stream :as stream]
            [dao.stream.cbor :as legacy-cbor]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.values :as values]
            [yin.vm.test-utils :as tu]
            [yin.vm.ucf.holder.export :as export]))


(declare parked-machine parked-explicit parked-installer served-table new-machine)


(defn- content-address
  [bytes]
  (keyword "segment"
           (str (get-in jing/registry [jing/default-hash-algorithm :address-id])
                "-" (jing/digest-bytes jing/default-hash-algorithm bytes))))


(deftest recovery-validation-precedes-attachment-test
  (let [{:keys [machine record]} (export/enter (parked-machine))
        {:keys [serve!]} (served-table)
        prepared (export/prepare machine record serve! nil)
        frozen (export/freeze machine (:record prepared))
        recovery (cbor/decode (:bytes frozen))
        changed-snapshot (legacy-cbor/encode
                           (update (legacy-cbor/decode (jing/base64->bytes (:yin.k/snapshot-bytes recovery)))
                                   :yin.k/id-counter inc))
        other-entered (export/enter (parked-explicit))
        other-prepared (export/prepare (:machine other-entered) (:record other-entered) serve! nil)
        other-recovery (cbor/decode (:bytes (export/freeze (:machine other-entered) (:record other-prepared))))
        attachments (atom 0)]
    (doseq [invalid [(assoc recovery :yin.k/version 2)
                     (assoc recovery :yin.k/header {:yin.k/enrolled #{}})
                     (assoc recovery :yin.k/descriptors #{})
                     (assoc-in recovery [:yin.k/state :issues] [])
                     (assoc recovery :yin.k/snapshot-bytes (jing/bytes->base64 changed-snapshot)
                            :yin.k/snapshot-address (content-address changed-snapshot))
                     (merge recovery (select-keys other-recovery [:yin.k/snapshot-bytes :yin.k/snapshot-address]))]]
      (let [bytes (cbor/encode invalid)
            restored (export/rehydrate-fenced (new-machine) bytes
                                              {:address (content-address bytes)
                                               :attach (fn [_] (swap! attachments inc) nil)})]
        (is (not= :ok (:status restored)))
        (is (nil? (:machine restored)))
        (is (zero? @attachments))))))


(deftest complete-export-recovery-is-fenced-and-reproducible-test
  (doseq [source [(parked-machine) (parked-explicit) (parked-installer)]]
    (let [{machine :machine record :record} (export/enter source)
          {:keys [serve! table]} (served-table)
          prepared (export/prepare machine record serve! nil)
          frozen (export/freeze machine (:record prepared))
          by-identity (into {} (map (fn [[handle descriptor]]
                                      [(:dao.stream/identity descriptor) handle])) @table)
          restored (export/rehydrate-fenced (new-machine) (:bytes frozen)
                                            {:address (:address frozen)
                                             :attach (fn [descriptor]
                                                       {:dao.stream/outcome :dao.stream/ok
                                                        :dao.stream/handle
                                                        (by-identity (:dao.stream/identity descriptor))})})
          encoded (when (= :ok (:status restored))
                    (export/encode (:machine restored) (:record restored)))]
      (is (= :ok (:status frozen)) (pr-str frozen))
      (is (= :ok (:status restored)) (pr-str restored))
      (is (= :exporting (vm/gate-mode (:machine restored))))
      (is (empty? (:wait-set (:machine restored))))
      (is (= (count (:wait-set record)) (count (get-in restored [:record :wait-set]))))
      (is (= :yin.k/refused
             (:yin.k/status (export/abort (:machine restored) (:record restored) [] nil))))
      (is (= (vec (:body-bytes frozen)) (vec (:bytes encoded)))))))


(deftest recovery-restores-fresh-name-state-without-receiver-contamination-test
  (let [source (assoc (parked-machine) :origins 42 :id-counter 88 :yin.k/issued 77)
        {:keys [machine record]} (export/enter source)
        {:keys [serve! table]} (served-table)
        prepared (export/prepare machine record serve! nil)
        frozen (export/freeze machine (:record prepared))
        by-identity (into {} (map (fn [[handle descriptor]]
                                    [(:dao.stream/identity descriptor) handle])) @table)
        restored (export/rehydrate-fenced
                   (assoc (new-machine) :origins 99 :id-counter 100
                          :store {'receiver-local :wrong} :yin.k/issued 33
                          :yin.k/closes [{:receiver-local true}])
                   (:bytes frozen)
                   {:address (:address frozen)
                    :attach (fn [descriptor]
                              {:dao.stream/outcome :dao.stream/ok
                               :dao.stream/handle (by-identity (:dao.stream/identity descriptor))})})]
    (is (= :ok (:status restored)) (pr-str restored))
    (is (= 42 (:origins (:machine restored))))
    (is (= 88 (:id-counter (:machine restored))))
    (is (= 77 (:yin.k/issued (:machine restored))))
    (is (not (contains? (:store (:machine restored)) 'receiver-local)))
    (is (empty? (:yin.k/closes (:machine restored))))))


;; =============================================================================
;; Machines
;; =============================================================================

(defn- throws?
  [thunk]
  (try (thunk) false
       (catch #?(:clj Exception :cljs js/Error :cljd Object) _ true)))


(defn- ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key capacity})))


(defn- make-ring-stream
  [capacity]
  (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                       ringbuffer/capacity-key capacity}))


(def ^:private semantic-vector
  (comp :vector linearize/lower-rows vm/ast->semantic-bytecode))


(defn- load-ast
  [machine ast]
  (semantic/load-vector machine (semantic-vector ast)
                        vm/semantic-contract))


(defn- new-machine
  ([] (new-machine {}))
  ([opts]
   (semantic/create-vm
     (merge {:make-stream make-ring-stream
             :capability-secret tu/secret
             :modules (module/default-registry)
             :secret-source (fn [origin]
                              (str tu/secret "/" (name origin)))}
            opts))))


(defn- lit
  [x]
  {:type :literal, :value x})


(defn- v
  [n]
  {:type :variable, :name n})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [f & args]
  {:type :application, :operator f, :operands (vec args)})


(defn- then
  [a b]
  (app (lam ['_] b) a))


(defn- let1
  [param init body]
  (app (lam [param] body) init))


(defn- def!
  [k val]
  (app (v 'yin/def) (lit k) val))


(defn- make-stream-ast
  []
  {:type :stream/make, :buffer 4})


(defn- cursor-of
  [source]
  {:type :stream/cursor, :source source})


(defn- next-of
  [source]
  {:type :stream/next, :source source})


(defn- two-streams
  "Parks on a read of the first stream; the store also holds the second
   stream and a cursor on each, so the lift reaches two streams."
  []
  (let1 'a (make-stream-ast)
        (let1 'b (make-stream-ast)
              (then (def! 'held-a (cursor-of (v 'a)))
                    (then (def! 'held-b (cursor-of (v 'b)))
                          (next-of (v 'held-a)))))))


(defn- parked-machine
  []
  (vm/run (load-ast (new-machine) (two-streams))))


(defn- module-response
  []
  {:status :ok,
   :image {:value (semantic-vector
                    (let1 's (make-stream-ast)
                          (then (next-of (cursor-of (v 's)))
                                (def! 'f (lam [] (lit 42))))))},
   :manifest {:yin.module/name 'host.mod,
              :yin.module/exports #{'f}},
   :obligations []})


(defn- parked-installer
  "A real machine parked at :install with a live child blocked on its
   own read."
  []
  (let [request (ring 64)
        response (ring 64)
        m0 (vm/run (load-ast (new-machine {:link-request request
                                           :link-response response})
                             (then (app (v 'require) (lit 'host.mod))
                                   (app (v 'host.mod/f)))))
        entry (first (:wait-set m0))
        _ (stream/append! response
                          (assoc (module-response)
                                 :yin.link/id (:link-id entry)))]
    (vm/run m0)))


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
                                 :dao.stream/channel {:dao.stream/type
                                                      :test/channel}}]
                     (swap! table assoc h served)
                     served)))}))


;; =============================================================================
;; Gated machines over counting handles
;; =============================================================================

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


(defn- hold
  "The one-slot reason string for the kind of hold `m` has."
  [outcome]
  (:yin.k/hold outcome))


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
        child (vm/run (load-ast (new-machine
                                  {:make-stream
                                   (fn [capacity]
                                     (let [r (make-ring-stream capacity)]
                                       (assoc r
                                              :dao.stream/handle
                                              (counting-handle child-calls))))})
                                (next-of (cursor-of (make-stream-ast)))))
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
       (= kind (hold r))))


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
      (let [m (vm/run (assoc (load-ast (new-machine {:link-request (ring 8)
                                                     :link-response (ring 8)})
                                       (app (v 'require) (lit 'foo)))
                             :yin.k/gate :running))
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


;; =============================================================================
;; Prepare, then encode
;; =============================================================================

(deftest prepare-serves-once-per-stream-and-encode-serves-nothing-test
  (let [m (parked-machine)
        {:keys [calls table serve!]} (served-table)
        {m' :machine record :record} (export/enter m)
        prepared (export/prepare m' record serve! nil)
        record' (:record prepared)
        after-prepare @calls
        e1 (export/encode m' record')
        e2 (export/encode m' record')]
    (is (= :ok (:status prepared)) (pr-str prepared))
    (is (= 2 (count @table)) "two streams reached")
    (is (= (count @table) after-prepare) "serve! once per stream")
    (is (= (count @table) (count (:served record'))))
    (is (= after-prepare @calls) "encode calls nothing")
    (is (= :ok (:status e1)) (pr-str e1))
    (is (= (vec (:bytes e1)) (vec (:bytes e2))) "deterministic bytes")
    (is (= (:address e1) (:address e2)))
    (testing "preparing a prepared record again serves nothing new"
      (export/prepare m' record' serve! nil)
      (is (= after-prepare @calls)))
    (testing "an unprepared record is a defect, not a refusal"
      (is (throws? #(export/encode m' record))))))


(deftest prepare-reaches-install-children-once-test
  (let [m (parked-installer)
        {:keys [calls table serve!]} (served-table)
        {m' :machine record :record} (export/enter m)
        prepared (export/prepare m' record serve! nil)]
    (is (= :ok (:status prepared)) (pr-str prepared))
    (is (pos? @calls))
    (is (= @calls (count @table)) "each stream, child's included, once")
    (let [n @calls]
      (is (= :ok (:status (export/encode m' (:record prepared)))))
      (is (= n @calls)))))


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
        refused (export/prepare m' record stingy nil)]
    (is (= :yin.k/unsatisfied (:yin.k/status refused)) (pr-str refused))
    (is (= 1 (count (get-in refused [:record :served]))))
    (testing "a retry with the returned record never re-serves that stream"
      (let [asked (atom [])
            {:keys [serve!]} (served-table)
            retry (export/prepare m' (:record refused)
                                  (fn [h]
                                    (swap! asked conj h)
                                    (serve! h))
                                  nil)]
        (is (= :ok (:status retry)) (pr-str retry))
        (is (not-any? #(= @first-handle %) @asked))
        (is (= 1 (count @asked)) "only the other stream")))))


(defn- parked-explicit
  "A real machine at an explicit park, its record's environment holding
   a cursor on a stream."
  []
  (vm/run (load-ast (new-machine)
                    (let1 's (make-stream-ast)
                          (app (lam ['c] {:type :vm/park})
                               (cursor-of (v 's)))))))


(deftest an-explicit-park-travels-in-the-record-test
  (let [m (parked-explicit)
        {m' :machine record :record} (export/enter m)
        {:keys [serve!]} (served-table)
        prepared (export/prepare m' record serve! nil)]
    (is (seq (:parked m)) "the source holds a parked record")
    (is (seq (:parked record)) "the record holds it")
    (is (every? #(not (contains? (:parked m') %)) (keys (:parked record)))
        "the machine's :parked lacks it")
    (is (empty? (:wait-set m')))
    (is (= :ok (:status prepared)) (pr-str prepared))
    (let [e (export/encode m' (:record prepared))]
      (is (= :ok (:status e)) (pr-str e))
      (is (= :parked (:kind e))))
    (is (= m (:machine (export/abort m' record [] nil)))
        "abort restores machine equality")))


(deftest an-install-childs-parked-records-stay-in-the-child-test
  (let [m (parked-installer)
        parked {:p0 {:type :parked-continuation :id :p0}}
        m (assoc-in m [:installs 'host.mod :vm :parked] parked)
        {m' :machine record :record :as r} (export/enter m)]
    (is (= :ok (:status r)) (pr-str r))
    (is (= parked (get-in m' [:installs 'host.mod :vm :parked]))
        "the child's parked records are not moved")
    (is (not-any? #(contains? (:parked record) %) (keys parked)))))


(deftest abort-needs-an-exporting-machine-test
  (let [[m record _] (exporting (parked-machine))
        restored (:machine (export/abort m record [] nil))]
    (is (= :yin.k/refused
           (:yin.k/status (export/abort restored record [] nil))))))


(deftest recovery-preserves-aliases-distinct-cells-and-authentic-closures-test
  (let [source (vm/run
                 (load-ast (new-machine)
                           (let1 's (make-stream-ast)
                                 (let1 'c (cursor-of (v 's))
                                       (let1 'd (cursor-of (v 's))
                                             (then (def! 'first-c (v 'c))
                                                   (then (def! 'alias-c (v 'c))
                                                         (then (def! 'distinct-c (v 'd))
                                                               (then (def! 'closure (lam [] (next-of (v 'c))))
                                                                     (next-of (v 'c)))))))))))
        {:keys [machine record]} (export/enter source)
        {:keys [serve!]} (served-table)
        prepared (export/prepare machine record serve! nil)
        frozen (export/freeze machine (:record prepared))
        calls (atom 0)
        attachments (atom 0)
        restored (export/rehydrate-fenced
                   (new-machine {:origin :fresh :capability-secret "recovery-secret"})
                   (:bytes frozen)
                   {:address (:address frozen)
                    :attach (fn [_]
                              (swap! attachments inc)
                              {:dao.stream/outcome :dao.stream/ok
                               :dao.stream/handle (counting-handle calls)})})
        machine' (:machine restored)
        store (:store machine')
        closure (get store 'closure)]
    (is (= :ok (:status frozen)))
    (is (= :ok (:status restored)) (pr-str restored))
    (is (= 1 @attachments))
    (is (zero? @calls) "no read, mint, append or close during reconstruction")
    (is (= (get store 'first-c) (get store 'alias-c)))
    (is (not= (get store 'first-c) (get store 'distinct-c)))
    (is (engine/authentic-ref? machine' :cursor-ref (get store 'first-c)))
    (is (values/closure? closure))
    (is (values/owned-by? closure (:owner machine')))
    (is (not (values/owned-by? closure (:owner source))))
    (is (= (vec (:body-bytes frozen))
           (vec (:bytes (export/encode machine' (:record restored))))))))


(deftest halted-module-closure-is-a-recovery-root-test
  (let [request (ring 32)
        response (ring 32)
        blocked (vm/run (load-ast (new-machine {:link-request request :link-response response})
                                  (then (app (v 'require) (lit 'host.mod)) (v 'host.mod/f))))
        _ (stream/append! response
                          (assoc (module-response)
                                 :image {:value (semantic-vector (def! 'f (lam [] (lit 42))))}
                                 :yin.link/id (:link-id (first (:wait-set blocked)))))
        source (vm/run blocked)
        {:keys [machine record]} (export/enter source)
        {:keys [serve!]} (served-table)
        prepared (export/prepare machine record serve! nil)
        frozen (export/freeze machine (:record prepared))
        calls (atom 0)
        restored (export/rehydrate-fenced
                   (new-machine {:origin :fresh :capability-secret "fresh-secret"})
                   (:bytes frozen)
                   {:address (:address frozen)
                    :attach (fn [_]
                              {:dao.stream/outcome :dao.stream/ok
                               :dao.stream/handle (counting-handle calls)})})]
    (is (values/closure? (:value source)))
    (is (= :ok (:status frozen)) (pr-str frozen))
    (is (= :ok (:status restored)) (pr-str restored))
    (is (values/closure? (:value (:machine restored))))
    (is (= :exporting (vm/gate-mode (:machine restored))))
    (is (zero? @calls))
    (is (= (vec (:body-bytes frozen))
           (vec (:bytes (export/encode (:machine restored) (:record restored))))))))


(deftest module-store-dependencies-are-censused-to-a-fixed-point-test
  (let [request (ring 32)
        response (ring 32)
        root (new-machine {:link-request request :link-response response})
        first-wait (vm/run (load-ast root
                                     (then (app (v 'require) (lit 'host.mod))
                                           (then (app (v 'require) (lit 'other.mod))
                                                 (v 'host.mod/f)))))
        _ (stream/append! response
                          (assoc (module-response)
                                 :image {:value (semantic-vector (def! 'f (lam [] (lit 42))))}
                                 :yin.link/id (:link-id (first (:wait-set first-wait)))))
        second-wait (vm/run first-wait)
        _ (stream/append! response
                          (assoc (module-response)
                                 :manifest {:yin.module/name 'other.mod :yin.module/exports #{'g}}
                                 :image {:value (semantic-vector (def! 'g (lam [] (lit 7))))}
                                 :yin.link/id (:link-id (first (:wait-set second-wait)))))
        halted (vm/run second-wait)
        other-id (first (keep (fn [[identity store]] (when (contains? store 'g) identity))
                              (:module-stores halted)))
        host-id (first (keep (fn [[identity store]] (when (contains? store 'f) identity))
                             (:module-stores halted)))
        other (get-in halted [:module-stores other-id 'g])
        source (assoc-in halted [:module-stores host-id 'dependency] other)
        {:keys [machine record]} (export/enter source)
        {:keys [serve!]} (served-table)
        prepared (export/prepare machine record serve!
                                 {:yin.k/arbitration {} :yin.k/next-op-seq 0 :yin.k/enrolled #{}
                                  :yin.k/origin {:yin.k/occurrence "00000000-0000-0000-0000-000000000001"
                                                 :dao.lease/lease "lease" :yin.k/emitter "holder"}})
        encoded (export/encode machine (:record prepared))]
    (is (values/closure? other) (pr-str (:module-stores halted)))
    (is (= :ok (:status prepared)) (pr-str prepared))
    (is (= #{host-id other-id} (set (keys (get-in encoded [:body :yin.k/module-stores])))))
    (let [old-body (update (:body encoded) :yin.k/module-stores dissoc other-id)
          old-address (content-address (cbor/encode old-body))]
      (is (= :segment/blake3-2500853acb568f9a6b944c3bdefe4fdcd59a9799b0ce5c75b77ad5ccf2a00188 old-address))
      (is (= :segment/blake3-382606673b257f528b9cf233cf05e00af66cdbb073940f7c14bb98a97e945590 (:address encoded)))
      (is (not= old-address (:address encoded))))
    (let [legacy (export/prepare machine record serve! nil)
          published (export/encode machine (:record legacy))
          frozen (export/freeze machine (:record legacy))
          restored (when (= :ok (:status frozen))
                     (export/rehydrate-fenced (new-machine) (:bytes frozen)
                                              {:address (:address frozen)
                                               :attach (constantly nil)}))
          reproduced (when (= :ok (:status restored))
                       (export/encode (:machine restored) (:record restored)))]
      (is (= :ok (:status frozen)) (pr-str frozen))
      (is (= :ok (:status restored)) (pr-str restored))
      (is (= #{host-id other-id} (set (keys (:module-stores (:machine restored))))))
      (is (= (vec (:bytes published)) (vec (:bytes reproduced)))
          "complete recovery must not alter the published version-0 bytes"))))


(deftest closure-in-blocked-environment-survives-fenced-recovery-test
  (let [source (vm/run (load-ast (new-machine)
                                 (let1 'f (lam [] (lit 42))
                                       (let1 'input (make-stream-ast)
                                             (then (next-of (cursor-of (v 'input)))
                                                   (app (v 'f)))))))
        source-closure (get-in source [:wait-set 0 :env 'f])
        {:keys [machine record]} (export/enter source)
        {:keys [serve!]} (served-table)
        prepared (export/prepare machine record serve! nil)
        frozen (export/freeze machine (:record prepared))
        calls (atom 0)
        restored (export/rehydrate-fenced
                   (new-machine {:origin :fresh :capability-secret "fresh-secret"}) (:bytes frozen)
                   {:address (:address frozen)
                    :attach (fn [_descriptor]
                              {:dao.stream/outcome :dao.stream/ok
                               :dao.stream/handle (counting-handle calls)})})
        closure (get-in restored [:record :wait-set 0 :env 'f])]
    (is (values/closure? source-closure) (pr-str (:wait-set source)))
    (is (= :ok (:status restored)) (pr-str restored))
    (is (values/closure? closure))
    (is (values/owned-by? closure (:owner (:machine restored))))
    (is (not (values/owned-by? closure (:owner source))))
    (is (zero? @calls))
    (is (= :exporting (vm/gate-mode (:machine restored))))
    (is (empty? (:wait-set (:machine restored))))
    (is (= (vec (:body-bytes frozen))
           (vec (:bytes (export/encode (:machine restored) (:record restored))))))))


(deftest version-one-recovery-retains-operation-ids-issues-and-local-enrollment-test
  (let [sink (ring 1)
        [reference base] (engine/attach-resource (new-machine) sink)
        source (vm/run (load-ast (assoc base :store {'sink reference} :yin.k/gate :running)
                                 {:type :stream/put :target (v 'sink) :val (lit 42)}))
        occurrence "00000000-0000-0000-0000-000000000001"
        op-id {:yin.k/occurrence "00000000-0000-0000-0000-000000000002" :yin.k/seq 0}
        source (-> source
                   (assoc :yin.k/issued 10)
                   (assoc-in [:wait-set 0 :yin.k/issue] 9)
                   (assoc-in [:wait-set 0 :op-id] op-id))
        {:keys [machine record]} (export/enter source)
        {:keys [serve!]} (served-table)
        header {:yin.k/occurrence occurrence :yin.k/arbitration
                {:dao.stream/identity "arb" :dao.stream/descriptor {}}
                :yin.k/next-op-seq 1 :yin.k/enrolled #{"s0"}
                :yin.k/origin {:yin.k/occurrence "00000000-0000-0000-0000-000000000002"
                               :dao.lease/lease "lease" :yin.k/emitter "holder"}}
        prepared (export/prepare machine record serve! header)
        frozen (export/freeze machine (:record prepared))
        calls (atom 0)
        restored (export/rehydrate-fenced
                   (new-machine) (:bytes frozen)
                   {:address (:address frozen)
                    :attach (fn [_]
                              {:dao.stream/outcome :dao.stream/ok
                               :dao.stream/handle (counting-handle calls)})})
        encoded (when (= :ok (:status restored))
                  (export/encode (:machine restored) (:record restored)))]
    (is (= :ok (:status prepared)) (pr-str prepared))
    (is (= :ok (:status restored)) (pr-str restored))
    (is (= op-id (get-in restored [:record :wait-set 0 :op-id])))
    (is (= 9 (get-in restored [:record :wait-set 0 :yin.k/issue])))
    (is (= 10 (get-in restored [:machine :yin.k/issued])))
    (is (= #{"s0"} (get-in restored [:record :header :yin.k/enrolled])))
    (is (not (contains? (:body encoded) :yin.k/enrolled)))
    (is (nil? (get-in restored [:machine :yin.k/custody])))
    (is (zero? @calls))
    (is (= (vec (:body-bytes frozen)) (vec (:bytes encoded))))))


(deftest rehydrating-an-install-child-does-not-repeat-initialization-or-io-test
  (let [initializations (atom 0)
        request (ring 64)
        response (ring 64)
        make-stream (fn [capacity]
                      (swap! initializations inc)
                      (make-ring-stream capacity))
        blocked (vm/run (load-ast (new-machine {:make-stream make-stream
                                                :link-request request :link-response response})
                                  (then (app (v 'require) (lit 'host.mod)) (app (v 'host.mod/f)))))
        _ (stream/append! response (assoc (module-response)
                                          :yin.link/id (:link-id (first (:wait-set blocked)))))
        source (vm/run blocked)
        receiver (new-machine {:make-stream make-stream})
        carried @initializations
        {:keys [machine record]} (export/enter source)
        {:keys [serve!]} (served-table)
        prepared (export/prepare machine record serve! nil)
        frozen (export/freeze machine (:record prepared))
        calls (atom 0)
        restored (export/rehydrate-fenced
                   receiver (:bytes frozen)
                   {:address (:address frozen)
                    :attach (fn [_]
                              {:dao.stream/outcome :dao.stream/ok
                               :dao.stream/handle (counting-handle calls)})})]
    (is (pos? carried))
    (is (= :ok (:status restored)) (pr-str restored))
    (is (= carried @initializations))
    (is (zero? @calls))
    (is (= :exporting (vm/gate-mode (get-in restored [:machine :installs 'host.mod :vm]))))
    (is (empty? (get-in restored [:machine :installs 'host.mod :vm :wait-set])))
    (is (= 1 (count (get-in restored [:record :children 'host.mod :wait-set]))))))


(deftest recovery-retains-a-blocked-ffi-request-and-its-response-route-test
  (let [inbound (reify stream/IDaoStreamWriter
                  (append! [_ _] {:dao.stream/outcome :dao.stream/full}))
        outbound (ring 8)
        source (vm/run (load-ast
                         (new-machine {:call-in inbound :call-out outbound
                                       :call-out-cursor (vm/mint-oldest outbound :test)})
                         {:type :dao.stream.apply/call :op :op/echo :operands [(lit "hello")]}))
        _ (is (true? (:request-sent (first (:wait-set source)))) (pr-str (:wait-set source)))
        {:keys [machine record]} (export/enter source)
        {:keys [serve!]} (served-table)
        prepared (export/prepare machine record serve! nil)
        frozen (export/freeze machine (:record prepared))
        calls (atom 0)
        restored (export/rehydrate-fenced
                   (new-machine) (:bytes frozen)
                   {:address (:address frozen)
                    :attach (fn [_]
                              {:dao.stream/outcome :dao.stream/ok
                               :dao.stream/handle (counting-handle calls)})})]
    (is (= :ok (:status prepared)) (pr-str prepared))
    (is (= :ffi-request (get-in (export/encode machine (:record prepared))
                                [:body :yin.k/frames 0 :yin.k/pending :yin.k/reason])))
    (is (= :ok (:status restored)) (pr-str restored))
    (is (= (get-in record [:wait-set 0 :call-id])
           (get-in restored [:record :wait-set 0 :call-id])))
    (is (= (get-in record [:wait-set 0 :datom])
           (get-in restored [:record :wait-set 0 :datom])))
    (is (zero? @calls))))
