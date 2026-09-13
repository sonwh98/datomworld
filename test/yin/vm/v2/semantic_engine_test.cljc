(ns yin.vm.v2.semantic-engine-test
  "The blocked-read and put cases of `engine_test.cljc`, reached through the
   semantic VM: lowered `:stream-*` instructions dispatch the engine's effects,
   and a read that cannot proceed parks into a pure-data wait set."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dao.stream.v2 :as stream]
            [yin.vm.v2 :as vm]
            [yin.vm.v2.linearize :as linearize]
            [yin.vm.v2.semantic :as semantic]
            [yin.vm.v2.test-utils :as tu]))


(defn- throws?
  [thunk]
  (try (thunk) false
       (catch #?(:clj Exception :cljs js/Error :cljd Object) _ true)))


(def ^:private load-ast (linearize/ast-loader semantic/vm-load-program))


(defn- make-vm
  []
  (semantic/create-vm {:make-stream tu/make-stream}))


(defn- run-ast
  ([ast] (run-ast (make-vm) ast))
  ([vm ast] (vm/run (load-ast vm (vm/ast->datoms ast)))))


(defn- lit
  [v]
  {:type :literal, :value v})


(defn- var-ref
  [n]
  {:type :variable, :name n})


(defn- let1
  "`((fn [param] body) init)`."
  [param init body]
  {:type :application,
   :operator {:type :lambda, :params [param], :body body},
   :operands [init]})


(defn- read-first
  "Make a stream of `capacity`, mint a cursor on it, and read one value."
  [capacity]
  (let1 's
        {:type :stream/make, :buffer capacity}
        {:type :stream/next,
         :source {:type :stream/cursor, :source (var-ref 's)}}))


(defn- blocked-reader
  "Run `read-first` on an empty stream: the parked VM and the stream handle."
  [capacity]
  (let [parked (run-ast (read-first capacity))
        entry (first (:wait-set parked))]
    [parked (get (vm/store parked) (:stream-id entry))]))


(defn- host-value?
  [x]
  (some fn? (tree-seq coll? seq x)))


;; =============================================================================
;; Blocked reads
;; =============================================================================

(deftest an-empty-stream-parks-the-reader-test
  (let [[parked handle] (blocked-reader 4)
        entry (first (:wait-set parked))]
    (testing "The reader parks with one polling wait entry"
      (is (vm/blocked? parked))
      (is (stream/writer? handle))
      (is (= 1 (count (:wait-set parked))))
      (is (= :next (:reason entry)))
      (is (= :cursor-ref (get-in entry [:cursor-ref :type]))))
    (testing "The entry is machine registers, not a resume closure"
      (is (integer? (:pc entry)))
      (is (contains? (:code parked) (:segment entry)))
      (is (not (contains? entry :resume)))
      (is (not (host-value? (:wait-set parked))))
      (is (= (:wait-set parked)
             (edn/read-string (pr-str (:wait-set parked))))
          "The wait set survives an EDN round-trip"))))


(deftest a-value-wakes-the-parked-reader-test
  (let [[parked handle] (blocked-reader 4)
        cursor-id (get-in (first (:wait-set parked)) [:cursor-ref :id])
        before (get-in parked [:store cursor-id :cursor])]
    (testing "Polling without a value leaves the reader parked"
      (is (vm/blocked? (vm/run parked))))
    (stream/append! handle :a)
    (let [done (vm/run parked)]
      (testing "An appended value resumes the continuation after :stream-next"
        (is (vm/halted? done))
        (is (= :a (vm/value done)))
        (is (empty? (:wait-set done))))
      (testing "The stored cursor advances to the returned successor"
        (is (not= before (get-in done [:store cursor-id :cursor])))))))


(deftest a-wait-set-read-back-from-edn-resumes-test
  (testing "The scheduler restores an entry that was never a live object"
    (let [[parked handle] (blocked-reader 4)
          revived (assoc parked
                         :wait-set
                         (edn/read-string (pr-str (:wait-set parked))))]
      (stream/append! handle :from-edn)
      (is (= :from-edn (vm/value (vm/run revived)))))))


(deftest a-closed-and-drained-stream-ends-with-nil-test
  (let [[parked handle] (blocked-reader 4)]
    (stream/close! handle)
    (let [done (vm/run parked)]
      (is (vm/halted? done))
      (is (nil? (vm/value done))))))


(deftest gap-is-a-value-the-program-sees-test
  (testing "Eviction under the reader's cursor surfaces as :dao.stream/gap"
    (let [[parked handle] (blocked-reader 2)]
      (dotimes [n 5] (stream/append! handle n))
      (is (= :dao.stream/gap (vm/value (vm/run parked)))))))


;; =============================================================================
;; Puts
;; =============================================================================

(deftest put-returns-the-appended-value-test
  (let [ref-vm (run-ast (let1 's
                              {:type :stream/make, :buffer 4}
                              (let1 '_
                                    {:type :stream/put,
                                     :target (var-ref 's),
                                     :val (lit 7)}
                                    (var-ref 's))))
        handle (get (vm/store ref-vm) (:id (vm/value ref-vm)))
        cursor (:dao.stream/cursor (stream/cursor handle stream/anchor-oldest))]
    (testing "The put's value is in the accumulator and on the stream"
      (is (= 7 (vm/value (run-ast (let1 's
                                        {:type :stream/make, :buffer 4}
                                        {:type :stream/put,
                                         :target (var-ref 's),
                                         :val (lit 7)})))))
      (is (= 7 (:dao.stream/value (stream/next handle cursor)))))))


(deftest put-on-a-closed-stream-is-an-error-test
  (is (throws? (fn []
                 (run-ast (let1 's
                                {:type :stream/make, :buffer 4}
                                (let1 '_
                                      {:type :stream/close, :source (var-ref 's)}
                                      {:type :stream/put,
                                       :target (var-ref 's),
                                       :val (lit 1)})))))))


(deftest put-on-an-unknown-stream-reference-is-an-error-test
  (is (throws? (fn []
                 (run-ast {:type :stream/put,
                           :target (lit {:type :stream-ref, :id :nope}),
                           :val (lit 1)})))))
