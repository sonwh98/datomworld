(ns datomworld.demo.flow-cube-state-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [datomworld.demo.flow-cube-state :as cube]
    [yin.repl :as repl]))


(deftest repl-primitives-drive-cube-rotation
  (testing "remote repl primitives update cube rotation state"
    (let [frames (atom [])
          cube-state (cube/create-demo-state #(swap! frames conj %))
          repl-state (-> (repl/create-state {:vm-type :semantic})
                         (update :vm
                                 update
                                 :primitives
                                 merge
                                 (cube/repl-primitives cube-state)))
          [_ result-1] @(repl/eval-input repl-state
                                         "(set-cube-rotation! 0 1.5 0)")
          [_ result-2] @(repl/eval-input repl-state "(rotate-cube! 0.25 0 0)")
          [_ result-3] @(repl/eval-input repl-state "(cube-rotation)")]
      (is (= "[0.0 1.5 0.0]" result-1))
      (is (= "[0.25 1.5 0.0]" result-2))
      (is (= "[0.25 1.5 0.0]" result-3))
      (is (= [0.25 1.5 0.0] (cube/current-rotation cube-state)))
      (is (= 3 (count @frames))))))


(deftest touch-like-delta-rotation-drives-the-same-stream
  (testing "local drag deltas emit the same rotation datoms as the REPL path"
    (let [frames (atom [])
          cube-state (cube/create-demo-state #(swap! frames conj %))]
      (is (= [0.1 0.2 0.0] (cube/rotate-by! cube-state [0.1 0.2 0.0])))
      (is (= [0.1 0.2 0.0] (cube/current-rotation cube-state)))
      (is (= 2 (count @frames))))))


(deftest animation-emits-rotation-datoms-onto-the-same-stream
  (testing
    "animate! and stop-animation! manage a periodic producer over the same stream path"
    (let [frames (atom [])
          scheduled (atom nil)
          stop-count (atom 0)
          cube-state (cube/create-demo-state
                       #(swap! frames conj %)
                       {:schedule-every! (fn [period-ms tick!]
                                           (reset! scheduled {:period-ms
                                                              period-ms,
                                                              :tick! tick!})
                                           (fn [] (swap! stop-count inc)))})]
      (is (= {:running? true, :period-ms 16, :delta [0.0 0.03 0.0]}
             (cube/animate! cube-state)))
      (is (= 16 (:period-ms @scheduled)))
      ((:tick! @scheduled))
      (is (= [0.0 0.03 0.0] (cube/current-rotation cube-state)))
      (is (= 2 (count @frames)))
      (is (= {:running? false} (cube/stop-animation! cube-state)))
      (is (= 1 @stop-count)))))
