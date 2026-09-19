(ns dao.lease-composition-test
  "Composition deftests for dao.lease Phase 4 (C1-C8), per the revised
  implementation plan's §4.4 prove list: the refusal matrix, the
  process-scoped fallback, the end-to-end grant->renew->lapse cycle
  through make-judge with a hand-turned tick stream, the non-ok :lapsed
  append leaving the lease pending, and the three use-case sketches as
  commented compositions -- runnable wirings, not products.

  Everything is scripted as in lease_test: hand-appended ticks and facts
  over ring buffers, per-author-media attribution, no host clock anywhere
  in this file (the reference tick driver and its host clock live in
  lease_test, the test tree's host policy). The real serving session in
  the served-connection sketch is JVM-only, gated #?(:cljd nil :clj ...)."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.lease :as lease]
            [dao.stream :as ds]
            [dao.stream.ringbuffer :as ringbuffer]
            [dao.stream.serving :as serving]
            [dao.stream.ws :as ws]))


;; =============================================================================
;; Harness
;; =============================================================================

(defn- new-buffer
  [capacity]
  (let [result (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                                    ringbuffer/capacity-key capacity})]
    (if (= :dao.stream/ok (:dao.stream/outcome result))
      (:dao.stream/handle result)
      (throw (ex-info "test buffer creation failed" {:result result})))))


(defn- oldest-cursor
  [handle]
  (let [minted (ds/cursor handle ds/anchor-oldest)]
    (if (= :dao.stream/ok (:dao.stream/outcome minted))
      (:dao.stream/cursor minted)
      (throw (ex-info "test cursor mint failed" {:result minted})))))


(defn- drain-values
  "Every value currently retained on handle, oldest first."
  [handle]
  (loop [cursor (oldest-cursor handle)
         out []]
    (let [read (ds/next handle cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome read))
        (recur (:dao.stream/cursor read) (conj out (:dao.stream/value read)))
        out))))


(defn- append-ok!
  [handle value]
  (let [result (ds/append! handle value)]
    (when-not (= :dao.stream/ok (:dao.stream/outcome result))
      (throw (ex-info "test append failed" {:result result :value value})))
    handle))


(defn- resolver
  "The composition's attribution: per-author media identity by default
  (see the attachment-identity sketch for the :ws/attachment binding)."
  [source _fact]
  source)


(defn- fake-writer
  "A writer whose every append answers one outcome -- the ring buffer
  evicts instead of answering :full, so the non-ok :lapsed append cases
  need a scripted writer."
  [outcome]
  (reify
    ds/IDaoStreamWriter
    (append!
      [_ _val]
      {:dao.stream/outcome outcome})))


(def standard-medium
  "The reference declaration for a per-author fact medium, with an honest
  capacity matching its test handle (r4-r3/F1: no capacity is derived
  against the drain budget -- a larger budget is the safe case)."
  {:retention :evict-oldest
   :capacity 8
   :value-domain :portable-values
   :attribution :per-author-media})


(defn- judge-config
  "A valid make-judge config over fresh ring buffers, then overrides."
  [overrides]
  (let [ticks (new-buffer 8)
        facts (new-buffer 8)
        writer (new-buffer 8)
        base {:cadence {:ms 5}
              :units {:ms 1}
              :tolerance {:ms 0}
              :resolver resolver
              :resolver-bindings #{:per-author-media}
              :reclaim (fn [_] true)
              :writer writer
              :self :grantor
              :ticks [{:handle ticks :cursor (oldest-cursor ticks)}]
              :media [{:handle facts
                       :cursor (oldest-cursor facts)
                       :source :holder-a
                       :medium standard-medium}]}]
    (merge base overrides)))


(defn- holder-config
  "A valid make-holder config over a fresh fact medium, then overrides."
  [overrides]
  (let [facts (new-buffer 8)
        ticks (new-buffer 8)
        outbound (new-buffer 8)
        base {:self :holder-a
              :grantor :grantor
              :units {:ms 1}
              :resolver resolver
              :resolver-bindings #{:per-author-media}
              :renewal-interval {:ms 4}
              :tick {:handle ticks :cursor (oldest-cursor ticks)}
              :fact {:handle facts
                     :cursor (oldest-cursor facts)
                     :source :grantor
                     :medium standard-medium}
              :writer {:handle outbound
                       :medium standard-medium}}]
    (merge base overrides)))


(defn- refusal-key
  "The :refused key of the assembly refusal's ex-data, or :assembled when
  the constructor succeeded. Every composition refusal names the seam it
  refused under an explicit :refused key -- no map key-order dependence
  (r4-P3)."
  [config]
  (try (lease/make-judge config) :assembled
    (catch #?(:cljd Object :clj Exception :cljs :default) e
      (:refused (ex-data e)))))


(defn- holder-refusal-key
  [config]
  (try (lease/make-holder config) :assembled
    (catch #?(:cljd Object :clj Exception :cljs :default) e
      (:refused (ex-data e)))))


(defn- composed
  "A make-judge system over ring buffers, driven by hand: fresh
  ticks/facts/writer, the composed judge's :step/:judge on the map, and
  the Phase 2 driving helpers re-keyed to it."
  [options]
  (let [ticks (new-buffer 16)
        facts (new-buffer 16)
        writer (new-buffer 16)
        composed (lease/make-judge
                   {:cadence {:ms 5}
                    :units {:ms 1}
                    :tolerance (get options :tolerance {:ms 0})
                    :resolver (get options :resolver resolver)
                    :resolver-bindings
                    (get options :resolver-bindings #{:per-author-media})
                    :reclaim (get options :reclaim (fn [_] true))
                    :writer writer
                    :self :grantor
                    :ticks [{:handle ticks :cursor (oldest-cursor ticks)}]
                    :media [{:handle facts
                             :cursor (oldest-cursor facts)
                             :source :holder-a
                             :medium (get options :medium standard-medium)}]})]
    {:ticks ticks
     :facts facts
     :writer writer
     :composed composed
     :judge (:judge composed)}))


(defn- tick!
  [system ms]
  (append-ok! (:ticks system) (lease/tick {:ms ms}))
  system)


(defn- fact!
  [system fact]
  (append-ok! (:facts system) fact)
  system)


(defn- step!
  "One pass through the composed judge's own :step -- the same fn the
  composition's runtime would drive at :cadence."
  [system]
  (assoc system :judge ((get-in system [:composed :step]) (:judge system))))


(defn- records
  "The writer's records after the grant."
  [system]
  (rest (drain-values (:writer system))))


;; =============================================================================
;; 4.4#1: the refusal matrix (C2, C3, C4, D4)
;; =============================================================================

(deftest refusal-matrix-test
  (testing "C2/C3/C4/D4: every missing or incompatible seam throws at
            assembly, before any stream is wired"
    (let [cases [[:cadence (fn [c] (dissoc c :cadence))]
                 [:cadence (fn [c] (assoc c :cadence {:ms 0}))]
                 [:cadence (fn [c] (assoc c :cadence {:hr 3}))]
                 [:ticks (fn [c] (assoc c :ticks []))]
                 [:ticks (fn [c] (assoc c :ticks [{:handle 1}]))]
                 [:media (fn [c] (assoc c :media []))]
                 [:medium-entry
                  (fn [c] (assoc-in c [:media 0] {:handle 1 :cursor 2}))]
                 [:medium (fn [c] (assoc-in c [:media 0 :medium] nil))]
                 [:medium (fn [c] (update-in c [:media 0 :medium]
                                             dissoc :retention))]
                 [:medium (fn [c] (assoc-in c [:media 0 :medium :retention]
                                            :neither))]
                 [:medium (fn [c] (assoc-in c [:media 0 :medium :capacity] 0))]
                 [:medium (fn [c] (assoc-in c [:media 0 :medium :value-domain]
                                            :anything))]
                 [:medium (fn [c] (assoc-in c [:media 0 :medium :attribution]
                                            :wire))]
                 [:tolerance (fn [c] (assoc c :tolerance {:hr 3}))]
                 [:tolerance (fn [c] (dissoc c :tolerance))]
                 [:self (fn [c] (dissoc c :self))]
                 [:resolver (fn [c] (dissoc c :resolver))]
                 [:resolver-bindings (fn [c] (dissoc c :resolver-bindings))]
                 [:attribution
                  (fn [c] (assoc c :resolver-bindings #{:envelope-key}))]
                 [:writer (fn [c] (dissoc c :writer))]
                 [:reclaim (fn [c] (dissoc c :reclaim))]
                 [:host-values
                  (fn [c]
                    (let [b (new-buffer 8)]
                      (assoc c :durable? true
                             :durable-judge :dj :incarnation-rule :ir
                             :fencing :f
                             :media [{:handle b
                                      :cursor (oldest-cursor b)
                                      :source :holder-a
                                      :medium (assoc standard-medium
                                                     :value-domain
                                                     :host-values)}])))]
                 [:durable-judge (fn [c] (assoc c :durable? true))]
                 [:incarnation-rule
                  (fn [c] (assoc c :durable? true :durable-judge :dj))]
                 [:fencing
                  (fn [c] (assoc c :durable? true :durable-judge :dj
                                 :incarnation-rule :ir))]]]
      (doseq [[expected modify] cases]
        (is (= expected (refusal-key (modify (judge-config {}))))
            (str "the config refusing with " expected))))
    (is (some? (lease/make-judge (judge-config {})))
        "the unmodified config assembles")
    (let [complete (new-buffer 8)]
      (is (some? (lease/make-judge
                   (judge-config
                     {:media [{:handle complete
                               :cursor (oldest-cursor complete)
                               :source :holder-a
                               :medium (-> standard-medium
                                           (assoc :retention :complete)
                                           (dissoc :capacity))}]})))
          "r4-P3: a :complete medium needs no :capacity -- retention is
           complete, nothing evicts"))))


(deftest holder-refusal-matrix-test
  (testing "C2/C3/C4: the holder half refuses the same way"
    (let [cases [[:tick (fn [c] (dissoc c :tick))]
                 [:tick (fn [c] (assoc c :tick {:handle 1}))]
                 [:fact (fn [c] (dissoc c :fact))]
                 [:fact (fn [c] (assoc c :fact {:handle 1 :cursor 2}))]
                 [:medium (fn [c] (assoc-in c [:fact :medium :retention] :neither))]
                 [:medium (fn [c] (assoc-in c [:fact :medium :capacity] 0))]
                 [:writer (fn [c] (dissoc c :writer))]
                 [:medium (fn [c] (assoc c :writer {:handle 1}))]
                 [:medium (fn [c] (assoc-in c [:writer :medium :capacity] 0))]
                 [:resolver (fn [c] (dissoc c :resolver))]
                 [:resolver-bindings (fn [c] (dissoc c :resolver-bindings))]
                 [:attribution
                  (fn [c] (assoc c :resolver-bindings #{:attachment-identity}))]
                 [:renewal-interval (fn [c] (dissoc c :renewal-interval))]
                 [:host-values
                  (fn [c] (assoc-in (assoc c :durable? true
                                           :durable-judge :dj
                                           :incarnation-rule :ir
                                           :fencing :f)
                                     [:fact :medium :value-domain]
                                     :host-values))]
                 [:durable-judge (fn [c] (assoc c :durable? true))]]]
      (doseq [[expected modify] cases]
        (is (= expected (holder-refusal-key (modify (holder-config {}))))
            (str "the holder config refusing with " expected))))
    (is (some? (lease/make-holder (holder-config {})))
        "the unmodified holder config assembles")))


;; =============================================================================
;; 4.4#2: the process-scoped fallback (C4)
;; =============================================================================

(deftest process-scoped-fallback-test
  (testing "C4: a non-durable config is declared process-scoped on the value"
    (is (= :process-scoped (:scope (lease/make-judge (judge-config {})))))
    (is (= :process-scoped (:scope (lease/make-holder (holder-config {})))))
    (is (= {:ms 5} (:cadence (lease/make-judge (judge-config {}))))
        "the declared cadence rides the value for the runtime to keep"))
  (testing "C4: durable with all three prerequisites constructs durable"
    (let [prereqs {:durable? true
                   :durable-judge :the-durable-judge
                   :incarnation-rule (fn [_incarnation] true)
                   :fencing :the-fence}]
      (is (= :durable (:scope (lease/make-judge (judge-config prereqs)))))
      (is (= :durable (:scope (lease/make-holder (holder-config prereqs)))))))
  (testing "C4: a :durable? config without all three does not construct at all"
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
                 (lease/make-judge (judge-config {:durable? true}))))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
                 (lease/make-holder (holder-config
                                      {:durable? true
                                       :durable-judge :only-one}))))))


;; =============================================================================
;; 4.4#3: end-to-end through make-judge (C1, J8)
;; =============================================================================

(deftest end-to-end-grant-renew-lapse-test
  (testing "C1: a composed judge drives grant -> renew -> lapse, exactly once"
    (let [log (atom [])
          system (-> (composed {:reclaim (fn [subject]
                                           (swap! log conj subject)
                                           true)})
                     (update :judge lease/author-grant
                             (lease/grant :l1 :db :holder-a {:ms 10}))
                     (tick! 1)
                     step!)]
      (is (= [(lease/grant :l1 :db :holder-a {:ms 10})]
             (drain-values (:writer system)))
          "the grant was delivered on the writer and seeded")
      (is (= {:ms 1} (get-in system [:judge :ledger :l1 :tenure-start])))
      (let [system (-> system
                       (tick! 7)
                       (fact! (lease/renewal :l1))
                       step!)]
        (is (= {:ms 7} (get-in system
                               [:judge :ledger :l1 :last-observation]))
            "the holder's renewal counted through the wired medium")
        (let [system (-> system (tick! 18) step!)]
          (is (= [:db] @log) "the reclaim was performed exactly once")
          (is (= [(lease/lapsed :l1 :silence)] (records system))
              "and recorded once: the record survives the reclaimed
               resource -- a reclaim frees the resource, never the record")
          (is (empty? (get-in system [:judge :ledger]))
              "on ok the lease left the ledger")
          (let [system (-> system (tick! 19) step!)
                system (-> system (tick! 20) step!)]
            (is (= [:db] @log)
                "no later pass reclaims or re-records a completed lapse")
            (is (= [(lease/lapsed :l1 :silence)] (records system)))))))))

(deftest non-ok-lapsed-append-leaves-lease-pending-test
  (testing "J8: a :lapsed append answering a non-ok outcome leaves the lease
            pending for the next pass"
    (doseq [outcome [:dao.stream/full
                     :dao.stream/closed
                     :dao.stream/transport-error]]
      (testing (str "outcome " outcome)
        (let [log (atom [])
              system (-> (composed {:reclaim (fn [subject]
                                               (swap! log conj subject)
                                               true)})
                         (update :judge lease/author-grant
                                 (lease/grant :l1 :db :holder-a {:ms 10}))
                         (tick! 1)
                         step!)]
          (is (= 1 (count (drain-values (:writer system)))))
          ;; the writer fails before the record: the transport's failure,
          ;; swapped in as a composition would on failover
          (let [system (-> system
                           (update :judge assoc :writer (fake-writer outcome))
                           (tick! 12)
                           step!)]
            (is (= [:db] @log) "the reclaim itself succeeded")
            (is (= {:state :pending :cause :silence :success true}
                   (get-in system [:judge :ledger :l1 :reclaim]))
                "the lease stays pending: the record never landed")
            (let [system (-> system (tick! 13) step!)]
              (is (= [:db :db] @log)
                  "the retry re-attempted the idempotent reclaim")
              (is (= :silence
                     (get-in system [:judge :ledger :l1 :reclaim :cause]))
                  "a pending lease keeps its cause, not reclassified")
              (is (= 1 (count (drain-values (:writer system))))
                  "the healthy writer still holds only the grant: the
                   :lapsed never landed on it"))))))))


(deftest nil-resolver-answer-never-counts-as-grantor-test
  (testing "P1/r4: a resolver answering nil for an unknown source authors
            nothing -- only a non-nil :self makes that certain"
    ;; With :self required and non-nil at assembly, the nil answer of a
    ;; resolver meeting an unknown source or a missing envelope key can
    ;; never equal the grantor: a forged :accepted on a stranger's medium
    ;; establishes no tenure.
    (let [known-only (fn [source _fact]
                       (get {:holder-a :holder-a} source))
          stranger (new-buffer 8)
          system (-> (composed {:resolver known-only})
                     (update :judge assoc :self :grantor)
                     (update :judge lease/wire-facts
                             stranger (oldest-cursor stranger) :a-stranger))
          system (-> system
                     (update :judge lease/author-grant
                             (lease/grant :l1 :db :holder-a {:ms 10}))
                     (tick! 1)
                     step!)]
      (is (= [(lease/grant :l1 :db :holder-a {:ms 10})]
             (drain-values (:writer system)))
          "the composed grantor's own grant was delivered")
      ;; the forged :accepted arrives on a medium the resolver does not
      ;; know: its author answers nil
      (append-ok! stranger (lease/grant :l9 :db :holder-a {:ms 10}))
      (let [system (-> system
                       (tick! 2)
                       step!)]
        (is (= #{:l1} (set (keys (get-in system [:judge :ledger]))))
            "only the composed grantor's own :l1 holds tenure")
        (is (false? (contains? (get-in system [:judge :ledger]) :l9))
            "a nil attribution authored no term: the forged :accepted
             established nothing")
        (is (= 0 (get-in system [:judge :dropped]))
            "it is valid data about no one, not a defect")))))


(deftest composed-both-halves-cycle-test
  (testing "C1/H1-H4: a composed holder observes, renews and releases
            through its own wiring"
    (let [log (atom [])
          judge-ticks (new-buffer 16)
          facts (new-buffer 16)         ;; the holder's outbound medium:
                                        ;; what the judge reads
          writer (new-buffer 16)        ;; the grantor's record stream
          holder-facts (new-buffer 16)  ;; where the holder observes grants
          holder-ticks (new-buffer 16)  ;; the holder's OWN tick stream
          composed-judge
          (lease/make-judge
            {:cadence {:ms 5}
             :units {:ms 1}
             :tolerance {:ms 0}
             :resolver resolver
             :resolver-bindings #{:per-author-media}
             :reclaim (fn [subject] (swap! log conj subject) true)
             :writer writer
             :self :grantor
             :ticks [{:handle judge-ticks
                      :cursor (oldest-cursor judge-ticks)}]
             :media [{:handle facts
                      :cursor (oldest-cursor facts)
                      :source :holder-a
                      :medium standard-medium}]})
          composed-holder
          (lease/make-holder
            {:self :holder-a
             :grantor :grantor
             :units {:ms 1}
             :resolver resolver
             :resolver-bindings #{:per-author-media}
             :renewal-interval {:ms 4}
             :tick {:handle holder-ticks
                    :cursor (oldest-cursor holder-ticks)}
             :fact {:handle holder-facts
                    :cursor (oldest-cursor holder-facts)
                    :source :grantor
                    :medium standard-medium}
             :writer {:handle facts
                      :medium standard-medium}})
          grant (lease/grant :l1 :db :holder-a {:ms 10})
          judge (atom (lease/author-grant (:judge composed-judge) grant))
          judge-tick! (fn [ms]
                        (append-ok! judge-ticks (lease/tick {:ms ms})))
          step-judge! (fn []
                        (reset! judge
                                ((:step composed-judge) @judge)))
          _ (do (judge-tick! 1)
                (step-judge!)
                ;; the composition's carriage delivers the grant to the
                ;; holder's inbound medium
                (append-ok! holder-facts grant)
                (append-ok! holder-ticks (lease/tick {:ms 1})))
          holder (lease/observe-grant (:holder composed-holder)
                                      :grantor grant {:ms 1})]
      (is (= grant (:grant holder))
          "H1: the holder observed the grantor-authored grant")
      (is (true? (lease/holding? holder {:ms 2})))
      (is (false? (lease/due-to-renew? holder {:ms 4})))
      (append-ok! holder-ticks (lease/tick {:ms 5}))
      ;; the holder's control flow DRAINS its own tick medium for the
      ;; reading (r4-r3/F3: the wiring is exercised as a medium, not
      ;; only appended to)
      (let [newest (get (last (drain-values holder-ticks))
                        :dao.lease/reading)]
        (is (= {:ms 5} newest)
            "the drained reading is the tick the driver deposited")
        (is (true? (lease/due-to-renew? holder newest))
            "due at the interval on its own tick stream"))
      (let [;; the renewal is appended to the holder's outbound writer --
            ;; the judge's medium -- and recorded at the reading drained
            ;; BEFORE the append
            append (ds/append! (get-in composed-holder [:writer :handle])
                               (lease/renewal :l1))
            holder (lease/observe-renewal holder {:ms 5} append)]
        (is (= :dao.stream/ok (:dao.stream/outcome append)))
        (is (= {:ms 5} (:last-renewal-at holder)))
        (judge-tick! 7)
        (step-judge!)
        (is (= {:ms 7} (get-in @judge [:ledger :l1 :last-observation]))
            "the judge's pass counted the composed holder's renewal")
        ;; done: stop, carry the release, and the judge reclaims :release
        (let [{:keys [holder release]} (lease/stop holder)]
          (is (false? (lease/holding? holder {:ms 8})))
          (is (= :dao.lease/released
                 (get release :dao.lease/status)))
          (append-ok! facts release)
          (judge-tick! 8)
          (step-judge!)
          (is (= [:db] @log) "the grantor performed the reclaim")
          (is (= [(lease/lapsed :l1 :release)]
                 (rest (drain-values writer)))
              "and recorded it: a :release lapse, authored by the holder's
               :released, never a :lapsed from the holder")
          (is (empty? (get-in @judge [:ledger]))
              "the :release lapse left the ledger"))))))


;; =============================================================================
;; 4.4: the three use-case sketches -- commented compositions,
;; lightly exercised, not products
;; =============================================================================

(deftest forwarder-pause-sketch-test
  (testing "sketch: the forwarder pause -- reclaim = cease driving the
            forward step, then close-session!"
    ;; A forwarder holds a lease while it advances a serving session's
    ;; forward step (the driving shape of dao/jing/remote.cljc:981-988 and
    ;; serving/step!). The driver loop below stands in for that daemon
    ;; thread: it keeps stepping the session while :stepping? holds, and
    ;; the lease's reclaim procedure is exactly the release discipline --
    ;; cease stepping, then close-session! (serving.cljc:179-183's shape).
    (let [session (atom {:stepping? true :closed? false})
          system (composed {:reclaim (fn [_subject]
                                       (swap! session assoc
                                              :stepping? false
                                              :closed? true)
                                       true)})
          system (-> system
                     (update :judge lease/author-grant
                             (lease/grant :l1 :forwarder :holder-a {:ms 10}))
                     (tick! 1)
                     step!)
          system (-> system (tick! 12) step!)]
      (is (true? (:closed? @session))
          "the reclaim closed the session")
      (is (false? (:stepping? @session))
          "and ceased the driving: the driver loop's condition is gone")
      (is (= [(lease/lapsed :l1 :silence)] (records system))))))


(defn- buffer
  "A plain ring-buffer handle (the serving fixture's transport)."
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key capacity})))


(def ^:private ws-admission
  {:retention :evict-oldest :capacity 16 :value-domain :portable-values})


(def ^:private handoff-admission
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


(def ^:private served-descriptor
  {:dao.stream/type :dao.stream/ws
   :dao.stream/identity "lease-composition-sketch"
   :ws/host "127.0.0.1"
   :ws/port 9183
   :ws/path "/lease/sketch"})


(defn- serving-fixture
  "One real serving composition over in-memory buffers -- the serving
  test's fixture shape, pared to what the sketch drives. The endpoint
  never binds a listener (:start-endpoint! is a stub); the connection is
  accepted in-process."
  [{:keys [service traffic]}]
  (let [offer (buffer 1)
        ack (buffer 1)
        control (buffer 16)
        newest (fn [handle]
                 (:dao.stream/cursor (ds/cursor handle ds/anchor-newest)))
        endpoint (ws/make-endpoint
                   {:served {"/lease/sketch" served-descriptor}
                    :control {:dao.stream/handle control
                              :dao.stream/surface #{:writer}}
                    :control-admission ws-admission
                    :slots [{:offer {:dao.stream/handle offer
                                     :dao.stream/surface #{:writer}}
                             :offer-admission handoff-admission
                             :ack {:dao.stream/handle ack
                                   :dao.stream/surface #{:writer}}
                             :ack-admission handoff-admission
                             :ack-cursor (newest ack)}]
                    :expiry-ms nil})
        ended (atom [])
        composition (serving/make-serving
                      {:endpoint endpoint
                       :served {"/lease/sketch"
                                {:descriptor served-descriptor
                                 :stream service}}
                       :control-reader control
                       :control-cursor (newest control)
                       :slots [{:offer-reader offer
                                :offer-cursor (newest offer)
                                :ack-writer {:dao.stream/handle ack
                                             :dao.stream/surface #{:writer}}}]
                       :make-traffic (fn [_]
                                       {:traffic {:dao.stream/handle traffic
                                                  :dao.stream/surface
                                                  #{:writer}}
                                        :admission ws-admission
                                        :reader traffic
                                        :cursor (newest traffic)})
                       :forward-options {:batch-budget 8 :gap-policy :terminate}
                       :start-endpoint! (fn [_ep] {:host :started})
                       :stop-endpoint! (fn [_ep] {:host :stopped})
                       :close-ended! (fn [handle]
                                       (swap! ended conj handle)
                                       {:dao.stream/outcome :dao.stream/ok})})]
    {:endpoint endpoint
     :composition composition
     :ended ended}))


(defn- carriage
  "A composition's carry step from a grantor's stream to a recipient's
  inbound medium: copies every fact EXCEPT :lapsed -- :lapsed does not
  cross a boundary (C8); the remote holder observes the reclaim as the
  resource event, not as the grantor's record. Cursor-threaded, so each
  step carries only what is new."
  [source destination]
  (let [cursor (atom (oldest-cursor source))]
    (fn []
      (loop []
        (let [read (ds/next source @cursor)]
          (when (= :dao.stream/ok (:dao.stream/outcome read))
            (reset! cursor (:dao.stream/cursor read))
            (when (not= :dao.lease/lapsed
                        (get (:dao.stream/value read) :dao.lease/status))
              (append-ok! destination (:dao.stream/value read)))
            (recur)))))))


(deftest served-connection-lifetime-sketch-test
  (testing "sketch: the served connection lifetime -- envelope-key
            attribution and :lapsed carriage (C8)"
    ;; Attribution by ENVELOPE KEY (D4; the :ws/attachment envelope key of
    ;; ws.cljc:167-174): each fact carries its attachment id in the
    ;; envelope, and the resolver reads it FROM THE FACT -- the wired
    ;; source is never consulted, which is exactly what declaring
    ;; :attribution :envelope-key means (r4-P2: the earlier sketch
    ;; declared :attachment-identity while resolving like per-author
    ;; media, and nothing noticed).
    ;;
    ;; OWED (r4-r3/F2, recorded by the orchestrator in the plan's §6):
    ;; the flattened shape modelled here -- :ws/attachment sitting
    ;; directly on the lease fact -- is NOT the transport's. ws.cljc
    ;; deposits {:ws/attachment <id> :ws/event ... :ws/value <payload>};
    ;; a real composition unwraps :ws/value before the judge sees a
    ;; lease fact, and the envelope key, as modelled here, is
    ;; SELF-ASSERTED: whoever appends to the medium chooses it. The
    ;; transport's authoritativeness comes from the connection, not the
    ;; key -- an unwrap seam and a trusted binding are owed work.
    (let [connection (atom {:open? true})
          traffic (new-buffer 16)
          holder-reads (new-buffer 16)
          envelope-resolver (fn [_source fact] (get fact :ws/attachment))
          medium (assoc standard-medium :attribution :envelope-key)
          system (composed
                   {:resolver envelope-resolver
                    :resolver-bindings #{:envelope-key}
                    :medium medium
                    :reclaim (fn [_subject]
                               (swap! connection assoc :open? false)
                               true)})
          grant (lease/grant :l1 :socket-handle :holder-a {:ms 10})
          system (-> system
                     (update :judge lease/author-grant grant)
                     (tick! 1)
                     step!)
          ;; the holder's renewal rides its envelope: :ws/attachment
          system (-> system
                     (fact! (assoc (lease/renewal :l1)
                                   :ws/attachment :holder-a))
                     (tick! 7)
                     step!)]
      (is (= {:ms 7} (get-in system [:judge :ledger :l1 :last-observation]))
          "the envelope-keyed renewal counted as :holder-a's evidence -- the
           resolver read the author from the envelope, not the source")
      (let [system (-> system (tick! 18) step!)]
        (is (false? (:open? @connection))
            "reclaim closed the served connection")
        (is (= [(lease/lapsed :l1 :silence)] (records system))
            "the :lapsed record is on the grantor's writer")
        ;; CARRIAGE: the composition's step copying grantor facts to the
        ;; holder's inbound medium, with :lapsed filtered out. The writer
        ;; HELD a :lapsed; the holder's medium must not receive it.
        (let [carry! (carriage (:writer system) holder-reads)]
          (carry!)
          (is (= [grant] (drain-values holder-reads))
              "the grant was carried across; the :lapsed was filtered: the
               holder observes the reclaim as the connection's closing --
               the resource event -- never as the grantor's record"))))))

#?(:cljd nil
   :clj
   (deftest served-connection-real-close-path-sketch-test
     (testing "sketch (JVM): one real serving session, reclaimed through the
               real close path"
       ;; The served-connection sketch against the real transport: one
       ;; ws endpoint, one accepted connection, the serving driver
       ;; stepping it. The lease's subject is the served stream; the
       ;; reclaim is the real close -- close the served stream, then one
       ;; serving step retires the session and runs the WebSocket close
       ;; (:close-ended!).
       ;;
       ;; OWED (r4-r3/F2, recorded by the orchestrator in the plan's
       ;; §6): the envelope shape flattened onto the renewal below is
       ;; not the transport's -- ws.cljc deposits {:ws/attachment <id>
       ;; :ws/event ... :ws/value <payload>}, so a real composition
       ;; unwraps :ws/value before the judge sees a lease fact, and the
       ;; :ws/attachment key, as modelled here, is SELF-ASSERTED:
       ;; whoever appends to the traffic medium chooses it. The
       ;; transport's authoritativeness is the connection's, not the
       ;; key's -- an unwrap seam and a trusted binding are owed work.
       (let [service (buffer 16)
             traffic (buffer 16)
             {:keys [endpoint composition ended]}
             (serving-fixture {:service service :traffic traffic})
             accepted (ws/accept-connection! endpoint "/lease/sketch"
                                             {:send! (fn [_])
                                              :close! (fn [& _] nil)}
                                             10)
             attachment (:ws/attachment accepted)
             writer (new-buffer 16)
             ticks (buffer 8)
             envelope-resolver (fn [_source fact]
                                 (get fact :ws/attachment))
             composed-judge
             (lease/make-judge
               {:cadence {:ms 5}
                :units {:ms 1}
                :tolerance {:ms 0}
                :resolver envelope-resolver
                :resolver-bindings #{:envelope-key}
                :reclaim (fn [_subject]
                           (= :dao.stream/ok
                              (:dao.stream/outcome (ds/close! service))))
                :writer writer
                :self :grantor
                :ticks [{:handle ticks :cursor (oldest-cursor ticks)}]
                :media [{:handle traffic
                         :cursor (oldest-cursor traffic)
                         :source :traffic
                         :medium (assoc standard-medium
                                        :attribution :envelope-key)}]})
             judge (atom (lease/author-grant
                          (:judge composed-judge)
                          (lease/grant :l1 :served-session attachment {:ms 10})))
             judge-tick! (fn [ms]
                           (append-ok! ticks (lease/tick {:ms ms})))
             step-judge! (fn [] (reset! judge ((:step composed-judge) @judge)))]
         (serving/start! composition)
         (serving/step! composition 10)
         (is (contains? (:sessions (serving/state composition)) attachment)
             "the session was accepted and is being served")
         (judge-tick! 1) (step-judge!)
         ;; the holder's renewal rides its attachment envelope
         (append-ok! traffic (assoc (lease/renewal :l1)
                                    :ws/attachment attachment))
         (judge-tick! 7) (step-judge!)
         (is (= {:ms 7} (get-in @judge [:ledger :l1 :last-observation]))
             "the renewal counted through the real traffic medium")
         ;; silence past duration: the reclaim closes the served stream
         (judge-tick! 18) (step-judge!)
         (is (= [(lease/lapsed :l1 :silence)]
                (rest (drain-values writer)))
             "the lease lapsed and was recorded")
         ;; one serving step drives the real close: the session retires
         ;; and the WebSocket close runs
         (serving/step! composition 19)
         (is (empty? (:sessions (serving/state composition)))
             "the session was retired by the real close path")
         (is (= 1 (count @ended))
             "and the WebSocket close (:close-ended!) ran")))))


(deftest shared-work-claim-sketch-test
  (testing "sketch: the shared-work claim -- a composition-supplied
            reclaim following call-close!'s idempotency"
    ;; A shared-work claim's reclaim is a composition-supplied procedure
    ;; in call-close!'s idempotency shape (serving.cljc:38-43): the first
    ;; invocation performs the release and reports success; later
    ;; invocations see the work already released and report success again
    ;; without acting twice -- which is what the judge's pending-retry
    ;; path demands of every reclaim it may invoke more than once.
    (let [work (atom {:released? false})
          releases (atom 0)
          reclaim (fn [_subject]
                    (when-not (:released? @work)
                      (swap! work assoc :released? true)
                      (swap! releases inc))
                    true)
          system (composed {:reclaim reclaim})
          system (-> system
                     (update :judge lease/author-grant
                             (lease/grant :l1 :shared-work :holder-a {:ms 10}))
                     (tick! 1)
                     step!)
          ;; the writer fails before the record, so the lease sits
          ;; pending and the next pass invokes the procedure AGAIN
          system (-> system
                     (update :judge assoc :writer
                             (fake-writer :dao.stream/full))
                     (tick! 12)
                     step!)
          system (-> system
                     (update :judge assoc :writer (:writer system))
                     (tick! 13)
                     step!)]
      (is (= 1 @releases)
          "two reclaim invocations, one release: the procedure is idempotent")
      (is (true? (:released? @work)))
      (is (= [(lease/lapsed :l1 :silence)] (records system))
          "the retry recorded after the writer recovered"))))


;; C5/C6/C7 are pinned outside the runtime tests, as the plan's §7 states:
;; no timer or clock call exists in src/cljc/dao/lease.cljc (the grep),
;; dao.lease requires dao.stream and never the reverse (by construction),
;; and no machinery renews on a holder's behalf (the holder section has no
;; renewal-appending function at all -- appending is the control flow's
;; own act, recorded through observe-renewal).
