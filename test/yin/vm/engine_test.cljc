(ns yin.vm.engine-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.module :as module]
            [yin.vm.test-utils :as tu]))


(defn- throws?
  "True when thunk throws. `thrown?` needs a literal class name, which differs
   per host; a thunk keeps the corpus host-neutral."
  [thunk]
  (try (thunk) false
       (catch #?(:clj Exception :cljs js/Error :cljd Object) _ true)))


(defn- state
  ([] (state {}))
  ([opts] (vm/empty-state (merge {:make-stream tu/make-stream} opts))))


;; =============================================================================
;; The host supplies streams
;; =============================================================================

(deftest stream-make-uses-the-supplied-constructor-test
  (testing "handle-make calls :make-stream and stores the handle"
    (let [[ref s'] (engine/handle-make (state) {:capacity 4} :stream-0)]
      (is (= {:type :stream-ref, :id :stream-0} ref))
      (is (stream/writer? (get (:store s') :stream-0)))))
  (testing "A nil capacity takes the VM default, so both paths agree"
    (let [seen (atom nil)
          make (fn [c] (reset! seen c) (tu/make-stream c))
          [_ _] (engine/handle-make (state {:make-stream make})
                                    {:capacity nil}
                                    :stream-0)]
      (is (= vm/default-stream-capacity @seen))))
  (testing "Without :make-stream the effect is unsupported and says so"
    (let [bare (vm/empty-state {})]
      (is (nil? (:make-stream bare)))
      (is (throws? (fn [] (engine/handle-make bare {:capacity 4} :stream-0)))))))


;; =============================================================================
;; Cursors are opaque
;; =============================================================================

(deftest cursor-entries-carry-an-opaque-cursor-test
  (testing "handle-cursor mints against the stream rather than fabricating"
    (let [[_ s0] (engine/handle-make (state) {:capacity 4} :stream-0)
          [ref s1] (engine/handle-cursor s0
                                         {:stream {:type :stream-ref,
                                                   :id :stream-0}}
                                         :cursor-0)
          entry (get (:store s1) :cursor-0)]
      (is (= {:type :cursor-ref, :id :cursor-0} ref))
      (is (= :stream-0 (:stream-id entry)))
      (is (contains? entry :cursor))
      (is (not (contains? entry :position))
          "There is no position and no seek")))
  (testing "A non-ok mint outcome fails the handler rather than being ignored"
    (let [refusing (reify
                     stream/IDaoStreamReader
                     (cursor
                       [_ _]
                       {:dao.stream/outcome
                        :dao.stream/transport-error})

                     (next
                       [_ _]
                       {:dao.stream/outcome
                        :dao.stream/transport-error}))
          s0 (assoc-in (state) [:store :stream-0] refusing)]
      (is (throws? (fn []
                     (engine/handle-cursor s0
                                           {:stream {:type :stream-ref,
                                                     :id :stream-0}}
                                           :cursor-0)))))))


(deftest next-advances-to-the-returned-successor-test
  (let [[_ s0] (engine/handle-make (state) {:capacity 4} :stream-0)
        [_ s1] (engine/handle-cursor s0
                                     {:stream {:type :stream-ref,
                                               :id :stream-0}}
                                     :cursor-0)
        cursor-ref {:type :cursor-ref, :id :cursor-0}
        handle (get (:store s1) :stream-0)]
    (testing "An empty open stream parks the reader"
      (let [r (engine/handle-next s1 {:cursor cursor-ref})]
        (is (true? (:park r)))
        (is (= :stream-0 (:stream-id r)))))
    (stream/append! handle :a)
    (testing "A value advances the stored cursor to the exact successor"
      (let [before (get-in s1 [:store :cursor-0 :cursor])
            r (engine/handle-next s1 {:cursor cursor-ref})
            after (get-in r [:state :store :cursor-0 :cursor])]
        (is (= :a (:value r)))
        (is (not= before after))))
    (testing "A closed and drained stream ends with nil, as v1 did"
      (let [r (engine/handle-next s1 {:cursor cursor-ref})
            s2 (:state r)]
        (stream/close! handle)
        (is (nil? (:value (engine/handle-next s2 {:cursor cursor-ref}))))))))


(deftest gap-is-a-value-the-program-sees-test
  (testing "Eviction surfaces as :dao.stream/gap at the reader's cursor"
    (let [[_ s0] (engine/handle-make (state) {:capacity 2} :stream-0)
          [_ s1] (engine/handle-cursor s0
                                       {:stream {:type :stream-ref,
                                                 :id :stream-0}}
                                       :cursor-0)
          handle (get (:store s1) :stream-0)
          cursor-ref {:type :cursor-ref, :id :cursor-0}]
      (dotimes [n 5] (stream/append! handle n))
      (let [r (engine/handle-next s1 {:cursor cursor-ref})]
        (is (= :dao.stream/gap (:value r)))
        (is (not= (get-in s1 [:store :cursor-0 :cursor])
                  (get-in r [:state :store :cursor-0 :cursor]))
            "A gap advances to the recovery cursor")))))


;; =============================================================================
;; Append outcomes
;; =============================================================================

(deftest put-is-total-over-append-outcomes-test
  (let [[_ s0] (engine/handle-make (state) {:capacity 2} :stream-0)
        effect {:effect :stream/put,
                :stream {:type :stream-ref, :id :stream-0},
                :val 1}]
    (testing "ok returns the appended value"
      (is (= 1 (:value (engine/handle-put s0 effect)))))
    (testing "closed is an error naming its outcome, as v1's throw was"
      (stream/close! (get (:store s0) :stream-0))
      (is (throws? (fn [] (engine/handle-put s0 effect)))))
    (testing "An unknown stream reference is an error"
      (is (throws? (fn []
                     (engine/handle-put s0
                                        (assoc effect
                                               :stream {:type :stream-ref,
                                                        :id :nope}))))))))


;; =============================================================================
;; :stream/take does not exist
;; =============================================================================

(deftest take-is-removed-test
  (testing "A :stream/take effect reaches no branch and no registered handler"
    (is (throws? (fn []
                   (engine/handle-effect (state {:modules (module/default-registry)})
                                         {:effect :stream/take,
                                          :stream {:type :stream-ref,
                                                   :id :stream-0}}
                                         {}))))))


;; =============================================================================
;; Nil-fill parameter binding (§7.7.2)
;; =============================================================================

(deftest bind-params-nil-fills-missing-args-test
  (testing "Every param becomes a key; a param with no arg maps to nil"
    (is (= {'x 1, 'y nil} (engine/bind-params '[x y] [1])))
    (is (= {'x nil, 'y nil} (engine/bind-params '[x y] []))))
  (testing "Extra args beyond params are dropped"
    (is (= {'x 1} (engine/bind-params '[x] [1 2 3]))))
  (testing "Exact-arity calls are unaffected"
    (is (= {'x 1, 'y 2} (engine/bind-params '[x y] [1 2])))))


;; =============================================================================
;; The module registry is a value
;; =============================================================================

(deftest resolve-var-reads-a-supplied-registry-test
  (let [registry (module/register-module (module/empty-registry)
                                         'my.lib
                                         {'answer 42})]
    (testing "Resolution order is env, store, primitives, registry"
      (is (= 1 (engine/resolve-var {'x 1} {'x 2} {'x 3} registry 'x)))
      (is (= 2 (engine/resolve-var {} {'x 2} {'x 3} registry 'x)))
      (is (= 3 (engine/resolve-var {} {} {'x 3} registry 'x)))
      (is (= 42 (engine/resolve-var {} {} {} registry 'my.lib/answer))))
    (testing "An unresolvable symbol names itself"
      (is (throws? (fn [] (engine/resolve-var {} {} {} registry 'nope)))))
    (testing "Nothing resolves from a registry that was not supplied"
      (is (throws? (fn [] (engine/resolve-var {} {} {} nil 'my.lib/answer)))))))


(deftest effect-dispatch-reads-the-registry-value-test
  (testing "A handler registered in the value is dispatched"
    (let [registry (module/register-effect-handler
                     (module/empty-registry)
                     :test/ping
                     (fn [s _e _o] {:state s, :value :pong, :blocked? false}))
          r (engine/handle-effect (state {:modules registry})
                                  {:effect :test/ping}
                                  {})]
      (is (= :pong (:value r)))))
  (testing "An unknown effect is an error"
    (is (throws? (fn []
                   (engine/handle-effect (state {:modules
                                                 (module/empty-registry)})
                                         {:effect :test/nope}
                                         {}))))))


;; =============================================================================
;; Readiness gating for observer coordination
;; =============================================================================

(deftest ready-for-ingress-gates-program-input-test
  (let [idle {:halted? true, :blocked? false, :ready-queue [], :wait-set []}]
    (testing "An idle VM is ready for the next batch"
      (is (engine/ready-for-ingress? idle)))
    (testing "Not ready while blocked, scheduled, waiting, or mid-continuation"
      (is (not (engine/ready-for-ingress? (assoc idle :blocked? true))))
      (is (not (engine/ready-for-ingress? (assoc idle :ready-queue [:x]))))
      (is (not (engine/ready-for-ingress? (assoc idle :wait-set [:x]))))
      (is (not (engine/ready-for-ingress? (assoc idle :k {:type :frame})))))
    (testing "Loaded work holds the VM until it halts or clears its control"
      (is (not (engine/ready-for-ingress?
                 (assoc idle :halted? false :control {:type :literal}))))
      (is (engine/ready-for-ingress?
            (assoc idle :halted? false :control nil))))
    (testing "Empty bytecode does not hold a VM that has cleared its control"
      (is (engine/ready-for-ingress? (assoc idle :bytecode []
                                            :halted? false
                                            :control :some-control)))
      (is (not (engine/ready-for-ingress? (assoc idle :bytecode [:op]
                                                 :halted? false
                                                 :control :some-control)))))))


;; =============================================================================
;; The polling wait set is the mechanism
;; =============================================================================

(deftest wait-set-is-resolved-from-the-store-test
  (testing "A parked reader is woken by polling, and its cursor advances"
    (let [[_ s0] (engine/handle-make (state) {:capacity 4} :stream-0)
          [_ s1] (engine/handle-cursor s0
                                       {:stream {:type :stream-ref,
                                                 :id :stream-0}}
                                       :cursor-0)
          handle (get (:store s1) :stream-0)
          before (get-in s1 [:store :cursor-0 :cursor])
          parked (assoc s1
                        :blocked? true
                        :wait-set [{:reason :next,
                                    :cursor-ref {:type :cursor-ref,
                                                 :id :cursor-0}}])]
      (is (= 1 (count (:wait-set (engine/check-wait-set parked))))
          "Nothing to read: it stays parked")
      (stream/append! handle :v)
      (let [woken (engine/check-wait-set parked)
            entry (first (:ready-queue woken))]
        (is (empty? (:wait-set woken)))
        (is (= :v (:value entry)))
        (is (= {:cursor-0 {:stream-id :stream-0,
                           :cursor (:cursor entry)}}
               (:store-updates entry)))
        (is (not= before (:cursor entry)))))))


(defn- cursor-waiter
  "A parked reader on :cursor-0, distinguishable by its continuation."
  [k]
  {:reason :next,
   :cursor-ref {:type :cursor-ref, :id :cursor-0},
   :k k,
   :env {}})


(deftest waiters-sharing-a-cursor-read-distinct-values-test
  (testing "Each waiter polls from its predecessor's successor"
    (let [[_ s0] (engine/handle-make (state) {:capacity 4} :stream-0)
          [_ s1] (engine/handle-cursor s0
                                       {:stream {:type :stream-ref,
                                                 :id :stream-0}}
                                       :cursor-0)
          handle (get (:store s1) :stream-0)]
      (stream/append! handle :a)
      (stream/append! handle :b)
      (let [parked (assoc s1
                          :blocked? true
                          :wait-set [(cursor-waiter :k1) (cursor-waiter :k2)])
            woken (engine/check-wait-set parked)
            tasks (:ready-queue woken)]
        (is (empty? (:wait-set woken)))
        (is (= [:a :b] (mapv :value tasks))
            "Not both waiters reading the value at the shared pre-poll cursor")
        (is (not= (:cursor (first tasks)) (:cursor (second tasks)))
            "The second waiter holds its own successor")
        (is (= (:cursor (second tasks))
               (get-in (:store-updates (second tasks)) [:cursor-0 :cursor])))))))


(deftest retained-waiter-re-resolves-its-cursor-test
  (testing "A waiter that stayed parked polls from the cursor another waiter
            advanced, not from the value that waiter consumed"
    (let [[_ s0] (engine/handle-make (state) {:capacity 4} :stream-0)
          [_ s1] (engine/handle-cursor s0
                                       {:stream {:type :stream-ref,
                                                 :id :stream-0}}
                                       :cursor-0)
          handle (get (:store s1) :stream-0)
          parked (assoc s1
                        :blocked? true
                        :wait-set [(cursor-waiter :k1) (cursor-waiter :k2)])
          r1 (engine/check-wait-set parked)]
      (is (= 2 (count (:wait-set r1))) "Nothing to read: both stay parked")
      (stream/append! handle :a)
      (let [r2 (engine/check-wait-set r1)]
        (is (= [:a] (mapv :value (:ready-queue r2)))
            "The first waiter takes the value; the second does not re-read it")
        (is (= 1 (count (:wait-set r2)))
            "The second waiter stays parked at the advanced cursor")
        (stream/append! handle :b)
        (let [r3 (engine/check-wait-set r2)]
          ;; r2's :a is still queued because no driver resumed it between
          ;; rounds; the new round adds :b for the retained waiter, not a
          ;; second copy of a value it never consumed.
          (is (= [:a :b] (mapv :value (:ready-queue r3))))
          (is (empty? (:wait-set r3))))))))


;; =============================================================================
;; Construction is all-or-nothing
;; =============================================================================

(defn- tracked-handle
  "A minimal handle that records its own close under `tag`. Minting answers
   :transport-error when `mint-ok?` is false; everything else succeeds."
  [closed tag mint-ok?]
  (reify
    stream/IDaoStreamReader
    (cursor
      [_ _]
      (if mint-ok?
        {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor [::cursor tag]}
        {:dao.stream/outcome :dao.stream/transport-error}))

    (next [_ _] {:dao.stream/outcome :dao.stream/blocked})


    stream/IDaoStreamWriter

    (append! [_ _] {:dao.stream/outcome :dao.stream/ok})


    stream/IDaoStreamClosable

    (close!
      [_]
      (swap! closed conj tag)
      {:dao.stream/outcome :dao.stream/ok})))


(deftest failing-pair-construction-closes-what-it-created-test
  (testing "A second create failure closes the first created stream"
    (let [closed (atom [])
          calls (atom 0)
          make (fn [_capacity]
                 (if (= 1 (swap! calls inc))
                   {:dao.stream/outcome :dao.stream/ok,
                    :dao.stream/handle (tracked-handle closed ::created-in true)}
                   {:dao.stream/outcome :dao.stream/transport-error}))]
      (is (throws? (fn [] (vm/empty-state {:make-stream make}))))
      (is (= [::created-in] @closed)
          "The unreachable call-in does not survive the construction error"))))


(deftest failing-mint-closes-created-streams-only-test
  (testing "A mint failure after both streams exist closes both"
    (let [closed (atom [])
          calls (atom 0)
          make (fn [_capacity]
                 (let [n (swap! calls inc)]
                   {:dao.stream/outcome :dao.stream/ok,
                    :dao.stream/handle (tracked-handle closed
                                                       (keyword "test"
                                                                (str "created-"
                                                                     n))
                                                       (= n 1))}))]
      (is (throws? (fn [] (vm/empty-state {:make-stream make}))))
      (is (= [:test/created-1 :test/created-2] @closed))))
  (testing "A stream the composition supplied is never closed by this failure"
    (let [closed (atom [])
          make (fn [_capacity]
                 {:dao.stream/outcome :dao.stream/ok,
                  :dao.stream/handle (tracked-handle closed ::created-out
                                                     false)})]
      (is (throws? (fn []
                     (vm/empty-state {:make-stream make,
                                      :call-in (tracked-handle closed
                                                               ::supplied-in
                                                               true)}))))
      (is (= [::created-out] @closed)
          "The supplied call-in belongs to the composition, not the VM"))))
