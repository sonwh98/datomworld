(ns yang.correspondence-lane-test
  (:require [clojure.test :refer [deftest is]]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.test-utils :as tu]
            [yang.clojure :as yang]
            [yang.correspondence-lane :as lane]))


(deftest measured-guest-steps-and-budget
  (let [ast (yang/compile-program '[(+ 1 2)])]
    (doseq [backend [:walker :semantic]]
      (let [{:keys [machine steps]} (lane/run-measured! ast {} backend 10000)]
        (is (vm/halted? machine))
        (is (= 3 (vm/value machine)))
        (is (pos-int? steps))
        (let [failure (try (lane/run-measured! ast {} backend (dec steps)) nil
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))]
          (is (= (dec steps) (:steps failure)))
          (is (= :instruction-limit (:stage failure))))))))


(deftest trace-drain-refuses-loss-and-measures-bytes
  (let [h (tu/new-stream 2)]
    (stream/append! h ['f 1 7])
    (stream/append! h ['g 2 8])
    (let [m (lane/read-trace! h)]
      (is (= [['f 1 7] ['g 2 8]] (:records m)))
      (is (= 2 (:record-count m)))
      (is (= (apply max (map #(alength (.getBytes (pr-str %) "UTF-8")) (:records m)))
             (:max-record-bytes m)))
      (is (= (alength (.getBytes (pr-str (:records m)) "UTF-8")) (:serialized-bytes m))))
    (stream/append! h ['h 3 9])
    (is (= :trace-loss (try (lane/read-trace! h) nil
                            (catch clojure.lang.ExceptionInfo e (:stage (ex-data e))))))))


(deftest trace-reachable-heap-measurement-must-be-valid
  (let [h (tu/new-stream 2)]
    (is (= 123 (lane/trace-reachable-heap! h (fn [_] 123))))
    (is (= :trace-memory (try (lane/trace-reachable-heap! h (fn [_] -1)) nil
                              (catch clojure.lang.ExceptionInfo e (:stage (ex-data e))))))))


(deftest trace-measurement-includes-host-heap-when-requested
  (let [h (tu/new-stream 2)]
    (stream/append! h ['f 1 7])
    (let [m (lane/measure-trace! h (fn [_] 123))]
      (is (= 123 (:reachable-heap-bytes m)))
      (is (= 1 (:record-count m)))
      (is (= 7 (:max-record-bytes m))))))


(deftest measured-run-calls-runtime-bound-check-before-stepping
  (let [ast (yang/compile-program '[(+ 1 2)])
        seen (atom [])
        failure (try (lane/run-measured!
                       ast {} :walker 10000
                       {:check! (fn [steps _]
                                  (swap! seen conj steps)
                                  (when (= steps 2)
                                    (throw (ex-info "cutoff" {:stage :runtime-limit :steps steps}))))})
                     nil (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= [0 1 2] @seen))
    (is (= :runtime-limit (:stage failure)))
    (is (= 2 (:steps failure)))))
