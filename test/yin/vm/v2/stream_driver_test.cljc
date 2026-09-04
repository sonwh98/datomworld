(ns yin.vm.v2.stream-driver-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream.v2 :as stream]
            [yin.vm.v2 :as vm]
            [yin.vm.v2.stream-driver :as driver]
            [yin.vm.v2.test-utils :as tu]))


(defn- throws?
  [thunk]
  (try (thunk) false
       (catch #?(:clj Exception :cljs js/Error :cljd Object) _ true)))


(defn- ingress
  "A VM-shaped map holding a minted cursor on a fresh ingress stream."
  ([] (ingress tu/default-capacity))
  ([capacity]
   (let [handle (tu/new-stream capacity)]
     {:in-stream handle,
      :in-cursor (vm/mint-oldest handle :in-stream),
      :halted? true,
      :blocked? false,
      :ready-queue [],
      :wait-set []})))


(def ^:private load-fn
  (fn [vm batch] (update vm :batches (fnil conj []) batch)))


(deftest ready-for-ingress-test
  (testing "Idle between evaluations"
    (is (driver/ready-for-ingress? (ingress))))
  (testing "Not while blocked, scheduled, or mid-continuation"
    (is (not (driver/ready-for-ingress? (assoc (ingress) :blocked? true))))
    (is (not (driver/ready-for-ingress? (assoc (ingress) :ready-queue [:x]))))
    (is (not (driver/ready-for-ingress? (assoc (ingress) :wait-set [:x]))))
    (is (not (driver/ready-for-ingress? (assoc (ingress) :k {:type :frame}))))))


(deftest ingest-is-total-over-read-outcomes-test
  (testing "blocked leaves the VM idle without advancing"
    (let [vm (ingress)
          {:keys [status state]} (driver/ingest-next-program vm
                                                             (:in-stream vm)
                                                             load-fn)]
      (is (= :blocked status))
      (is (true? (:halted? state)))
      (is (= (:in-cursor vm) (:in-cursor state)))))
  (testing "ok hands the batch to load-fn and advances to the successor"
    (let [vm (ingress)]
      (stream/append! (:in-stream vm) [:batch-1])
      (let [{:keys [status state]} (driver/ingest-next-program vm
                                                               (:in-stream vm)
                                                               load-fn)]
        (is (= :ok status))
        (is (= [[:batch-1]] (:batches state)))
        (is (not= (:in-cursor vm) (:in-cursor state))))))
  (testing "end halts"
    (let [vm (ingress)]
      (stream/close! (:in-stream vm))
      (is (= :end (:status (driver/ingest-next-program vm
                                                       (:in-stream vm)
                                                       load-fn))))))
  (testing "A terminal outcome is an error naming itself"
    (let [refusing (reify
                     stream/IDaoStreamReader
                     (cursor [_ _] {:dao.stream/outcome :dao.stream/ok})

                     (next
                       [_ _]
                       {:dao.stream/outcome
                        :dao.stream/invalid-cursor}))]
      (is (throws? (fn []
                     (driver/ingest-next-program (ingress)
                                                 refusing
                                                 load-fn)))))))


(deftest ingest-across-a-gap-test
  (testing "An evicted batch is counted and ingestion continues"
    (let [vm (ingress 2)
          handle (:in-stream vm)]
      (doseq [b [[:one] [:two] [:three]]] (stream/append! handle b))
      (let [{:keys [status state]} (driver/ingest-next-program vm
                                                               handle
                                                               load-fn)]
        (is (= :gap status) "The lost batch is reported, not hidden")
        (is (= 1 (:ingress-gaps state)))
        (is (nil? (:batches state)) "No program was ingested from the gap")
        (let [{:keys [status state]} (driver/ingest-next-program state
                                                                 handle
                                                                 load-fn)]
          (is (= :ok status))
          (is (= [[:two]] (:batches state))
              "The next retained batch is ingested normally"))))))


(deftest step-on-stream-test
  (testing "An idle VM ingests then steps"
    (let [vm (ingress)
          _ (stream/append! (:in-stream vm) [:batch])
          stepped (driver/step-on-stream vm
                                         (:in-stream vm)
                                         load-fn
                                         #(assoc % :stepped true))]
      (is (= [[:batch]] (:batches stepped)))
      (is (true? (:stepped stepped)))))
  (testing "A busy VM steps without polling"
    (let [vm (assoc (ingress) :halted? false :control {:type :literal})
          stepped (driver/step-on-stream vm
                                         (:in-stream vm)
                                         load-fn
                                         #(assoc % :stepped true))]
      (is (true? (:stepped stepped)))
      (is (nil? (:batches stepped))))))
