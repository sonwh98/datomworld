(ns yin.vm.stream-driver-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as ds]
            [dao.stream.ringbuffer]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.stream-driver :as driver]))


(deftest ready-for-ingress-test
  (testing
    "ready-for-ingress? returns true when VM is idle and ready for new ingress program"
    (let [idle-vm (ast-walker/create-vm)]
      (is (true? (driver/ready-for-ingress? idle-vm)))))
  (testing
    "ready-for-ingress? returns false when VM is blocked or has pending work"
    (let [blocked-vm (assoc (ast-walker/create-vm) :blocked? true)
          queued-vm (assoc (ast-walker/create-vm) :ready-queue [{:task :dummy}])
          k-vm (assoc (ast-walker/create-vm) :k (fn [x] x))]
      (is (false? (driver/ready-for-ingress? blocked-vm)))
      (is (false? (driver/ready-for-ingress? queued-vm)))
      (is (false? (driver/ready-for-ingress? k-vm))))))


(deftest ingest-next-program-test
  (testing "ingest-next-program polls next item from stream and calls load-fn"
    (let [in-stream (ds/open! {:type :ringbuffer, :capacity 5})
          _ (ds/append! in-stream [:add 1 2])
          vm (ast-walker/create-vm)
          load-fn (fn [v program] (assoc v :loaded program))
          res (driver/ingest-next-program vm in-stream load-fn)]
      (is (= :ok (:status res)))
      (is (= [:add 1 2] (:loaded (:state res))))
      (is (= 1 (:position (:in-cursor (:state res)))))))
  (testing
    "ingest-next-program returns blocked or end when stream state warrants it"
    (let [in-stream (ds/open! {:type :ringbuffer, :capacity 5})
          vm (ast-walker/create-vm)
          load-fn (fn [v _] v)]
      (is (= :blocked
             (:status (driver/ingest-next-program vm in-stream load-fn))))
      (ds/close! in-stream)
      (is (= :end
             (:status (driver/ingest-next-program vm in-stream load-fn))))))
  (testing "ingest-next-program throws when the cursor has fallen behind (gap)"
    (let [in-stream (ds/open! {:type :ringbuffer, :capacity 5})
          _ (ds/append! in-stream :a)
          _ (ds/append! in-stream :b)
          _ (ds/drain-one! in-stream)
          vm (assoc (ast-walker/create-vm) :in-cursor {:position 0})
          load-fn (fn [v _] v)]
      (is (thrown-with-msg?
            #?(:cljs js/Error
               :cljd Object
               :default Exception)
            #"fell behind"
            (driver/ingest-next-program vm in-stream load-fn)))))
  (testing
    "ingest-next-program throws on a DaoStream response outside the
     documented {:ok/:cursor}, :blocked, :end, :daostream/gap contract"
    (let [malformed-stream (reify
                             ds/IDaoStreamReader
                             (next [_ _] :some-unexpected-status))
          vm (ast-walker/create-vm)
          load-fn (fn [v _] v)]
      (is (thrown-with-msg?
            #?(:cljs js/Error
               :cljd Object
               :default Exception)
            #"Unexpected DaoStream response"
            (driver/ingest-next-program vm malformed-stream load-fn))))))


(deftest step-on-stream-test
  (testing "step-on-stream ingests and steps when idle"
    (let [in-stream (ds/open! {:type :ringbuffer, :capacity 5})
          _ (ds/append! in-stream [:step])
          vm (ast-walker/create-vm)
          load-fn (fn [v p] (assoc v :prog p))
          step-fn (fn [v] (assoc v :stepped true))
          res (driver/step-on-stream vm in-stream load-fn step-fn)]
      (is (true? (:stepped res)))
      (is (= [:step] (:prog res)))))
  (testing
    "step-on-stream steps directly, without touching in-stream, when the VM
     is not ready for ingress (mid-evaluation)"
    (let [in-stream (ds/open! {:type :ringbuffer, :capacity 5})
          _ (ds/append! in-stream [:should-not-be-ingested])
          busy-vm (assoc (ast-walker/create-vm) :k {:next nil})
          load-fn (fn [_ _] (throw (ex-info "load-fn must not run" {})))
          step-fn (fn [v] (assoc v :stepped true))
          res (driver/step-on-stream busy-vm in-stream load-fn step-fn)]
      (is (true? (:stepped res)))
      (is (= {:next nil} (:k res)))
      (is (= {:ok [:should-not-be-ingested], :cursor {:position 1}}
             (ds/next in-stream {:position 0}))
          "in-stream is untouched: the pending program was never ingested"))))
