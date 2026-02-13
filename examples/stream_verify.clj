(ns stream-verify
  (:require [clojure.core.async :as async]
            [clojure.pprint :refer [pprint]]
            [daodb.stream :as s]
            [yin.vm :as vm]))


(defn log [msg] (println "[VERIFY]" msg))


(defn verify-host-stream
  []
  (log "--- Verifying Host AsyncStream ---")
  (let [stream (s/make-stream 10)]
    (log "Stream created.")
    (log "Writing 'hello'...")
    (s/write! stream "hello")
    (log "Reading...")
    (let [val-ch (s/read! stream)
          val (async/<!! val-ch)]
      (log (str "Read value: " val))
      (assert (= val "hello") "Host stream read failed!")))
  (log "--- Verifying StreamSeq ---")
  (let [stream (s/make-stream 10)]
    (s/write! stream 1)
    (s/write! stream 2)
    (s/write! stream 3)
    (s/close! stream)
    (let [sq (s/stream-seq stream)]
      (log (str "Seq first: " (first sq)))
      (log (str "Seq second: " (second sq)))
      (log (str "Seq third: " (last sq)))
      (assert (= (first sq) 1))
      (assert (= (second sq) 2))
      (assert (= (last sq) 3))))
  (log "Host Stream Verification Passed!"))


(defn verify-yin-vm-stream
  []
  (log "--- Verifying Yin.VM Stream AST ---")
  (let [initial-state {:store {}, :environment {}}
        ;; AST: (let [s (stream/make 5)] (stream/put s 42) (stream/take s))
        ;; Simplified AST structure manually constructed
        program-ast {:type :application,
                     :operator
                       {:type :lambda,
                        :params [:s],
                        :body {:type :application,
                               :operator {:type :lambda,
                                          :params [:_ignored],
                                          :body {:type :stream/take,
                                                 :source {:type :variable,
                                                          :name :s}}},
                               :operands [{:type :stream/put,
                                           :target {:type :variable, :name :s},
                                           :val {:type :literal, :value 42}}]}},
                     :operands [{:type :stream/make, :buffer 5}]}]
    (log "Running VM...")
    (let [final-state (vm/run initial-state program-ast)]
      (log "VM Finished.")
      (log (str "Result Value: " (:value final-state)))
      (assert (= (:value final-state) 42) "Yin.VM Stream test failed!")))
  (log "Yin.VM Verification Passed!"))


(defn -main [] (verify-host-stream) (verify-yin-vm-stream) (shutdown-agents))
