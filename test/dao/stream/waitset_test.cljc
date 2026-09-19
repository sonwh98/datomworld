(ns dao.stream.waitset-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.waitset :as waitset]))


(def ring-spec
  {:dao.stream/type ring/transport-type
   :dao.stream.ringbuffer/capacity 8})


(defn ring-handle
  "A fresh v2 ring buffer of `capacity`."
  ([]
   (:dao.stream/handle (ring/create! ring-spec)))
  ([capacity]
   (:dao.stream/handle (ring/create! (assoc ring-spec
                                            ring/capacity-key capacity)))))


(defn cursor
  [h anchor]
  (:dao.stream/cursor (stream/cursor h anchor)))


;; -----------------------------------------------------------------------------
;; The store: an association list of [ref binding] pairs, keyed by host refs
;; the library has never heard of — not the VM's map of {:id …} records.
;; Each binding carries a handle and the host's current cursor for that ref.
;; -----------------------------------------------------------------------------

(defn alist-store
  "Build the store from {ref [handle cursor]} bindings."
  [bindings]
  (mapv (fn [[ref [handle cursor]]]
          [ref {:handle handle :cursor cursor}])
        bindings))


(defn alist-fetch
  [store ref]
  (some (fn [[r binding]] (when (= r ref) binding)) store))


(defn alist-cursor
  [store ref]
  (:cursor (alist-fetch store ref)))


(defn alist-advance
  "Move one ref's cursor and return the successor store."
  [store ref cursor]
  (mapv (fn [[r binding]]
          (if (= r ref)
            [r (assoc binding :cursor cursor)]
            [r binding]))
        store))


(defn alist-resolver
  []
  {:resolve (fn [store entry]
              (when-let [binding (alist-fetch store (:my/ref entry))]
                {:stream (:handle binding)
                 :cursor (:cursor binding)
                 :value (:my/value entry)}))
   :advance (fn [store entry cursor]
              (alist-advance store (:my/ref entry) cursor))})


(defn counting-reader
  "A reader that counts its `next` calls and always answers `blocked`."
  []
  (let [calls (atom 0)]
    {:calls calls
     :handle (reify stream/IDaoStreamReader
               (next
                 [_ _]
                 (swap! calls inc)
                 {:dao.stream/outcome :dao.stream/blocked}))}))


(defn scripted-reader
  "A reader whose `next` answers `outcome` with the keys the contract
   requires of it, and counts its calls."
  [outcome]
  (let [calls (atom 0)]
    {:calls calls
     :handle (reify stream/IDaoStreamReader
               (next
                 [_ _]
                 (swap! calls inc)
                 (case outcome
                   :dao.stream/ok {:dao.stream/outcome :dao.stream/ok
                                   :dao.stream/value :sent
                                   :dao.stream/cursor {:pos 1}}
                   :dao.stream/gap {:dao.stream/outcome :dao.stream/gap
                                    :dao.stream/cursor {:pos 7}}
                   {:dao.stream/outcome outcome})))}))


(defn scripted-writer
  "A writer whose `append!` always answers `outcome`, and counts its
   calls."
  [outcome]
  (let [calls (atom 0)]
    {:calls calls
     :handle (reify stream/IDaoStreamWriter
               (append!
                 [_ _]
                 (swap! calls inc)
                 {:dao.stream/outcome outcome}))}))


(defn full-once-writer
  "A writer whose first append answers `full` and whose later appends
   answer `ok`; counts every append."
  []
  (let [calls (atom 0)]
    {:calls calls
     :handle (reify stream/IDaoStreamWriter
               (append!
                 [_ _]
                 (if (= 1 (swap! calls inc))
                   {:dao.stream/outcome :dao.stream/full}
                   {:dao.stream/outcome :dao.stream/ok})))}))


(defn answering-reader
  "A reader whose `next` returns `answer` verbatim — a fixture for answers
   that are not outcome maps at all. Counts its calls."
  [answer]
  (let [calls (atom 0)]
    {:calls calls
     :handle (reify stream/IDaoStreamReader
               (next
                 [_ _]
                 (swap! calls inc)
                 answer))}))


(defn answering-writer
  "A writer whose `append!` returns `answer` verbatim; counts its calls."
  [answer]
  (let [calls (atom 0)]
    {:calls calls
     :handle (reify stream/IDaoStreamWriter
               (append!
                 [_ _]
                 (swap! calls inc)
                 answer))}))


(defn fixture-resolver
  "Resolve every entry to `handle`; a fixture for the reified-handle
   suites. `advances` counts the sweep's write-backs."
  [handle advances]
  {:resolve (fn [_store _entry]
              {:stream handle :cursor {:pos 0} :value :payload})
   :advance (fn [store _entry _cursor]
              (swap! advances inc)
              store)})


(defn check-one
  "Park `entry` alone, resolve it to `handle`, and sweep once. Returns the
   check's result beside the number of `:advance` calls it made, so the
   classification loops can pin the write-back count per outcome."
  [entry handle]
  (let [advances (atom 0)
        check (waitset/check (waitset/park (waitset/empty-waitset) entry)
                             (fixture-resolver handle advances)
                             [])]
    {:check check :advances @advances}))


(def next-outcome-plan
  "Literal classification for a `:next` entry: the status the woken result
   carries, or `:wait` — the only reader outcome that can change on its
   own. The key set is pinned to `dao.stream/outcomes-next`, so a future
   contract outcome fails here on domain inequality instead of passing
   through a default branch."
  {:dao.stream/ok :ok
   :dao.stream/blocked :wait
   :dao.stream/end :end
   :dao.stream/gap :dao.stream/gap
   :dao.stream/cursor-mismatch :dao.stream/cursor-mismatch
   :dao.stream/invalid-cursor :dao.stream/invalid-cursor
   :dao.stream/transport-error :dao.stream/transport-error})


(def put-outcome-plan
  "Literal classification for a `:put` entry, pinned to
   `dao.stream/outcomes-append`. `closed` is a terminal append failure —
   never a reader's `end`."
  {:dao.stream/ok :ok
   :dao.stream/full :wait
   :dao.stream/invalid-value :dao.stream/invalid-value
   :dao.stream/closed :dao.stream/closed
   :dao.stream/transport-error :dao.stream/transport-error})


(deftest classification-is-pinned-per-operation
  (testing "the literal plans cover exactly the contract outcome sets"
    (is (= stream/outcomes-next (set (keys next-outcome-plan))))
    (is (= stream/outcomes-append (set (keys put-outcome-plan)))))
  (testing "for a :next entry, exactly blocked waits"
    (doseq [[outcome status] next-outcome-plan]
      (let [entry {:reason :next :my/ref :r}
            {:keys [check advances]} (check-one entry
                                                (:handle (scripted-reader
                                                           outcome)))
            result check]
        (if (= :wait status)
          (do (is (= [] (:woken result)) (str outcome " must wait"))
              (is (= [entry] (get-in result [:waitset :waiting]))
                  (str outcome " must stay parked as stored")))
          (do (is (= 1 (count (:woken result))) (str outcome " must wake"))
              (is (= status (:status (first (:woken result)))))
              (is (= [] (get-in result [:waitset :waiting])))))
        ;; The write-back happens only when the poll returned a cursor:
        ;; the successor on ok, the recovery position on gap.
        (is (= (if (contains? #{:dao.stream/ok :dao.stream/gap} outcome) 1 0)
               advances)
            (str outcome " advance count")))))
  (testing "for a :put entry, exactly full waits"
    (doseq [[outcome status] put-outcome-plan]
      (let [entry {:reason :put :my/ref :r :my/value :payload}
            {:keys [check advances]} (check-one entry
                                                (:handle (scripted-writer
                                                           outcome)))
            result check]
        (if (= :wait status)
          (do (is (= [] (:woken result)) (str outcome " must wait"))
              (is (= [entry] (get-in result [:waitset :waiting]))
                  (str outcome " must stay parked as stored")))
          (do (is (= 1 (count (:woken result))) (str outcome " must wake"))
              (is (= status (:status (first (:woken result)))))
              (is (= [] (get-in result [:waitset :waiting])))))
        ;; A writer's poll never moves the store.
        (is (zero? advances) (str outcome " advance count"))))))


(deftest invalid-answers-fold-into-a-qualified-terminal-diagnostic
  (testing "a map answer with no outcome key"
    (let [entry {:reason :next :my/ref :r}
          {:keys [handle calls]} (answering-reader {:dao.stream/value :sent})
          {:keys [check advances]} (check-one entry handle)
          wake (first (:woken check))]
      (is (= 1 @calls) "the poll was attempted once")
      (is (= 1 (count (:woken check))))
      (is (= entry (:entry wake)))
      (is (= :dao.stream.waitset/invalid-answer (:status wake)))
      (is (= [] (get-in check [:waitset :waiting])))
      (is (= [] (:store check)))
      (is (zero? advances) "no cursor was interpretable, so no write-back")))
  (testing "a non-map answer"
    (let [entry {:reason :next :my/ref :r}
          {:keys [handle calls]} (answering-reader :not-a-map)
          {:keys [check advances]} (check-one entry handle)]
      (is (= 1 @calls))
      (is (= :dao.stream.waitset/invalid-answer
             (:status (first (:woken check)))))
      (is (= [] (get-in check [:waitset :waiting])))
      (is (zero? advances))))
  (testing "a nil answer"
    (let [entry {:reason :put :my/ref :r :my/value :payload}
          {:keys [handle calls]} (answering-writer nil)
          {:keys [check advances]} (check-one entry handle)]
      (is (= 1 @calls) "the append was attempted once")
      (is (= :dao.stream.waitset/invalid-answer
             (:status (first (:woken check)))))
      (is (= [] (get-in check [:waitset :waiting])))
      (is (zero? advances))))
  (testing "a handle that fails its protocol throws into the same diagnostic"
    (let [entry {:reason :next :my/ref :r}
          ;; A truthy :stream that satisfies no reader protocol: the poll
          ;; dispatch fails at the host, and the entry cannot be trusted
          ;; to poll again.
          {:keys [check advances]} (check-one entry :not-a-handle)
          wake (first (:woken check))]
      (is (= 1 (count (:woken check))))
      (is (= entry (:entry wake)))
      (is (= :dao.stream.waitset/invalid-answer (:status wake)))
      (is (= [] (get-in check [:waitset :waiting])))
      (is (= [] (:store check)))
      (is (zero? advances)))))


(deftest park-is-total-over-missing-and-nil-waiting
  (let [entry {:reason :next :my/ref :r}
        entry-b {:reason :next :my/ref :r2}]
    (testing "a waitset without :waiting parks onto a fresh vector"
      (is (= {:waiting [entry]} (waitset/park {} entry)))
      (is (= {:waiting [entry]} (waitset/park {:waiting nil} entry))))
    (testing "park order — wake order among co-waiters — is preserved"
      (is (= {:waiting [entry entry-b]}
             (-> {:waiting nil}
                 (waitset/park entry)
                 (waitset/park entry-b)))))))


(deftest undeclared-outcome-is-terminal-under-its-own-keyword
  (let [rogue :dao.stream/quantum-flux]
    (is (not (contains? stream/outcomes-next rogue)))
    (is (not (contains? stream/outcomes-append rogue)))
    (testing "for a :next entry"
      (let [entry {:reason :next :my/ref :r}
            {:keys [check advances]}
            (check-one entry (:handle (scripted-reader rogue)))
            wake (first (:woken check))]
        (is (= 1 (count (:woken check))))
        (is (= rogue (:status wake)))
        (is (= rogue (:value wake)))
        (is (not (contains? wake :cursor)))
        (is (= [] (get-in check [:waitset :waiting])))
        (is (zero? advances) "no cursor was returned, so no write-back")))
    (testing "for a :put entry"
      (let [entry {:reason :put :my/ref :r :my/value :payload}
            {:keys [check advances]}
            (check-one entry (:handle (scripted-writer rogue)))]
        (is (= 1 (count (:woken check))))
        (is (= rogue (:status (first (:woken check)))))
        (is (= rogue (:value (first (:woken check)))))
        (is (= [] (get-in check [:waitset :waiting])))
        (is (zero? advances))))))


(deftest woken-results-carry-what-the-poll-yielded
  (testing "a reader's ok carries the value and its successor cursor"
    (let [entry {:reason :next :my/ref :r}
          result (:check (check-one entry
                                    (:handle (scripted-reader
                                               :dao.stream/ok))))]
      (is (= [{:entry entry :status :ok :value :sent :cursor {:pos 1}}]
             (:woken result)))))
  (testing "end carries no value and no cursor"
    (let [entry {:reason :next :my/ref :r}
          result (:check (check-one entry
                                    (:handle (scripted-reader
                                               :dao.stream/end))))]
      (is (= [{:entry entry :status :end :value nil}] (:woken result)))))
  (testing "a reader failure resolves under its own keyword"
    (let [entry {:reason :next :my/ref :r}
          result (:check (check-one entry
                                    (:handle (scripted-reader
                                               :dao.stream/transport-error))))]
      (is (= [{:entry entry
               :status :dao.stream/transport-error
               :value :dao.stream/transport-error}]
             (:woken result)))))
  (testing "a writer's ok resolves with the appended value and no cursor"
    (let [entry {:reason :put :my/ref :r :my/value :payload}
          result (:check (check-one entry
                                    (:handle (scripted-writer
                                               :dao.stream/ok))))]
      (is (= [{:entry entry :status :ok :value :payload}]
             (:woken result))))))


(deftest shared-cursor-wakes-co-waiters-on-distinct-values-in-wait-set-order
  (let [h (ring-handle)
        _ (doseq [v [:a :b :c]] (stream/append! h v))
        entry-a {:reason :next :my/ref :shared :my/tag :first}
        entry-b {:reason :next :my/ref :shared :my/tag :second}
        store (alist-store [[:shared [h (cursor h :dao.stream/oldest)]]])
        result (waitset/check (-> (waitset/empty-waitset)
                                  (waitset/park entry-a)
                                  (waitset/park entry-b))
                              (alist-resolver)
                              store)]
    ;; Wait-set order is wake order, and one cursor wakes two distinct
    ;; values: b resolved against the store a's write-back advanced.
    (is (= [:first :second] (mapv (comp :my/tag :entry) (:woken result))))
    (is (= [:a :b] (mapv :value (:woken result))))
    (is (= [:ok :ok] (mapv :status (:woken result))))
    ;; Entries are returned exactly as stored.
    (is (= entry-a (:entry (first (:woken result)))))
    (is (= entry-b (:entry (second (:woken result)))))
    ;; Both write-backs landed: the store ends at b's successor.
    (is (= (:cursor (second (:woken result)))
           (alist-cursor (:store result) :shared)))
    (is (= [] (get-in result [:waitset :waiting])))))


(deftest parked-writer-retries-by-appending
  (let [{:keys [handle calls]} (full-once-writer)
        advances (atom 0)
        entry {:reason :put :my/ref :w :my/value :payload}
        resolver (fixture-resolver handle advances)
        first-check (waitset/check (waitset/park (waitset/empty-waitset) entry)
                                   resolver
                                   [:no-store-motion])]
    ;; Exactly one append per poll: the first sweep appended once and the
    ;; handle answered full, so the entry is retained as stored.
    (is (= 1 @calls))
    (is (zero? @advances) "a writer's poll never moves the store")
    (is (= [] (:woken first-check)))
    (is (= [entry] (get-in first-check [:waitset :waiting])))
    ;; Threading the returned waitset and store retries by appending — no
    ;; polled state discarded — and resolves the writer with its host
    ;; value.
    (let [second-check (waitset/check (:waitset first-check)
                                      resolver
                                      (:store first-check))]
      (is (= 2 @calls))
      (is (= [{:entry entry :status :ok :value :payload}]
             (:woken second-check)))
      (is (= [] (get-in second-check [:waitset :waiting]))))))


(deftest gap-recovery-wakes-co-waiters-from-the-recovery-position
  (let [h (ring-handle 2)
        _ (do (stream/append! h :old)
              (stream/append! h :new))
        stale (cursor h :dao.stream/oldest)
        _ (stream/append! h :newest)   ; evicts :old at position 0
        entry-a {:reason :next :my/ref :shared :my/tag :first}
        entry-b {:reason :next :my/ref :shared :my/tag :second}
        store (alist-store [[:shared [h stale]]])
        result (waitset/check (-> (waitset/empty-waitset)
                                  (waitset/park entry-a)
                                  (waitset/park entry-b))
                              (alist-resolver)
                              store)
        [a b] (:woken result)]
    ;; a wakes first, with the gap and its recovery cursor: the earliest
    ;; retained position.
    (is (= :first (:my/tag (:entry a))))
    (is (= :dao.stream/gap (:status a)))
    (is (= :dao.stream/gap (:value a)))
    (is (= 1 (:dao.stream.ringbuffer/position (:cursor a))))
    ;; b resolved against a's write-back in the same call and read the
    ;; earliest retained value.
    (is (= :second (:my/tag (:entry b))))
    (is (= :ok (:status b)))
    (is (= :new (:value b)))
    (is (= 2 (:dao.stream.ringbuffer/position (:cursor b))))
    ;; The store ends at b's successor.
    (is (= 2 (:dao.stream.ringbuffer/position
               (alist-cursor (:store result) :shared))))))


(deftest sweeps-a-non-vm-store-and-returns-host-key-entries-intact
  (let [h (ring-handle)
        _ (doseq [v [:a :b]] (stream/append! h v))
        entry {:reason :next
               :my/ref :my.host.dev/stream-7
               :my.host.dev/private-thing {:nested [:data 42]}}
        store (alist-store [[:my.host.dev/stream-7
                             [h (cursor h :dao.stream/oldest)]]])
        result (waitset/check (waitset/park (waitset/empty-waitset) entry)
                              (alist-resolver)
                              store)
        wake (first (:woken result))]
    ;; The store is an association list with host refs, not the VM's map of
    ;; {:id …} records; the sweep resolved, read and advanced anyway.
    (is (vector? store))
    (is (= 1 (count (:woken result))))
    (is (= [:a] (mapv :value (:woken result))))
    ;; The entry — exotic host keys included — came back verbatim, and the
    ;; host's own advance moved the host's own ref.
    (is (= entry (:entry wake)))
    (is (= 1 (:dao.stream.ringbuffer/position
               (alist-cursor (:store result) :my.host.dev/stream-7))))))


(deftest diagnostics-never-wait-and-never-touch-a-stream
  (let [{:keys [handle calls]} (counting-reader)
        store (alist-store [[:r [handle {:pos 0}]]])
        advances (atom 0)
        advancing-resolver (let [r (alist-resolver)]
                             (assoc r :advance
                                    (fn [s e c]
                                      (swap! advances inc)
                                      ((:advance r) s e c))))]
    (testing "a nil resolve wakes unresolved, with no stream operation"
      (let [entry {:reason :next :my/ref :missing :my/tag :lost}
            result (waitset/check (waitset/park (waitset/empty-waitset) entry)
                                  advancing-resolver
                                  store)
            wake (first (:woken result))]
        (is (= 1 (count (:woken result))))
        (is (= entry (:entry wake)))
        (is (= :dao.stream.waitset/unresolved (:status wake)))
        (is (= [] (get-in result [:waitset :waiting])))
        (is (= store (:store result)))
        (is (zero? @calls))
        (is (zero? @advances))))
    (testing "a resolve without a stream wakes unresolved too"
      (let [entry {:reason :next :my/ref :r :my/tag :blind}
            result (waitset/check (waitset/park (waitset/empty-waitset) entry)
                                  {:resolve (fn [_store _entry]
                                              {:cursor {:pos 0}})
                                   :advance (:advance advancing-resolver)}
                                  store)]
        (is (= :dao.stream.waitset/unresolved
               (:status (first (:woken result)))))
        (is (= [] (get-in result [:waitset :waiting])))
        (is (= store (:store result)))
        (is (zero? @calls))
        (is (zero? @advances) "no poll happened, so no write-back")))
    (testing "an unknown reason wakes unsupported-reason, with no stream operation"
      (let [entry {:reason :frobnicate :my/ref :r :my/tag :odd}
            result (waitset/check (waitset/park (waitset/empty-waitset) entry)
                                  advancing-resolver
                                  store)
            wake (first (:woken result))]
        (is (= 1 (count (:woken result))))
        (is (= entry (:entry wake)))
        (is (= :dao.stream.waitset/unsupported-reason (:status wake)))
        (is (= [] (get-in result [:waitset :waiting])))
        (is (= store (:store result)))
        (is (zero? @calls))
        (is (zero? @advances))))))


(deftest checking-an-empty-waitset-touches-nothing
  (let [{:keys [handle calls]} (counting-reader)
        store (alist-store [[:r [handle {:pos 0}]]])
        result (waitset/check (waitset/empty-waitset)
                              (alist-resolver)
                              store)]
    (is (= {:waitset {:waiting []} :woken [] :store store} result))
    (is (zero? @calls))))


(deftest woken-is-per-call-and-entries-come-back-as-given
  (let [h (ring-handle)
        _ (stream/append! h :a)
        blocked-h (:handle (counting-reader))
        parked {:reason :next :my/ref :r2 :my/tag :hold}
        waking {:reason :next :my/ref :r1 :my/tag :wake}
        store (alist-store [[:r1 [h (cursor h :dao.stream/oldest)]]
                            [:r2 [blocked-h {:pos 0}]]])
        first-check (waitset/check (-> (waitset/empty-waitset)
                                       (waitset/park parked)
                                       (waitset/park waking))
                                   (alist-resolver)
                                   store)]
    (testing "one call's woken is one call's results"
      (is (= [:wake] (mapv (comp :my/tag :entry) (:woken first-check))))
      ;; `:waiting` holds the entries as stored, not woken-result maps.
      (is (= [:hold]
             (mapv :my/tag (get-in first-check [:waitset :waiting])))))
    (testing "a repeated check returns only what moved since"
      (let [second-check (waitset/check (:waitset first-check)
                                        (alist-resolver)
                                        (:store first-check))]
        (is (= [] (:woken second-check)))
        (is (= (get-in first-check [:waitset :waiting])
               (get-in second-check [:waitset :waiting])))))
    (testing "no entry carries a key the host did not put there"
      (doseq [e (concat (map :entry (:woken first-check))
                        (get-in first-check [:waitset :waiting]))]
        (is (= #{:reason :my/ref :my/tag} (set (keys e))))
        (is (not (contains? e :stream)) "no resolved handle injected")
        (is (not (contains? e :cursor)) "no polling cursor injected")))
    (testing "plain-data fixtures round-trip through pr-str/read-string"
      (let [payload {:waitset (:waitset first-check)
                     :woken (:woken first-check)}]
        (is (= payload (edn/read-string (pr-str payload))))))))


(deftest probe-observes-without-committing-and-reparks-to-the-next-value
  (let [h (ring-handle)
        _ (stream/append! h :a)
        store {:my/committed :nothing}
        advances (atom 0)
        ;; The probe's cursor state is the consumer's own (`:my/cursor` on
        ;; the entry), and its advance is identity: the waitset commits
        ;; nothing the consumer's compound step does not.
        resolver {:resolve (fn [_store entry]
                             {:stream h :cursor (:my/cursor entry)})
                  :advance (fn [store _entry _cursor]
                             (swap! advances inc)
                             store)}
        probe (fn [c] {:reason :next :my/ref :probe :my/cursor c})
        first-check (waitset/check
                      (waitset/park (waitset/empty-waitset)
                                    (probe (cursor h :dao.stream/oldest)))
                      resolver
                      store)]
    ;; Wakes on readiness and leaves the store identical: the sweep
    ;; attempted the write-back and the identity advance committed nothing.
    (is (= [:a] (mapv :value (:woken first-check))))
    (is (= store (:store first-check)))
    (is (= 1 @advances))
    ;; The consumer's own step advances its own cursor; re-parking the
    ;; probe with it wakes on the NEXT value, not the same one.
    (let [stepped (stream/next h
                               (:my/cursor (:entry (first (:woken first-check)))))
          _ (is (= :a (:dao.stream/value stepped)))
          _ (stream/append! h :b)
          second-check (waitset/check
                         (waitset/park (waitset/empty-waitset)
                                       (probe (:dao.stream/cursor stepped)))
                         resolver
                         store)]
      (is (= [:b] (mapv :value (:woken second-check))))
      (is (= store (:store second-check))))))
