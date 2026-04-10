(ns dao.repl-test
  (:require
    #?(:cljs [cljs.test :refer-macros [async]])
    #?(:clj [clojure.edn :as edn]
       :cljs [cljs.reader :as edn]
       :cljd [clojure.edn :as edn])
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [dao.repl :as repl]
    [dao.stream :as ds]
    [dao.stream.apply :as dao-apply]
    [dao.stream.transport.ringbuffer]))


(defn- make-stream
  []
  (ds/open! {:transport {:type :ringbuffer :capacity nil}}))


(defn- fact?
  [datoms attr value]
  (boolean (some #(and (= attr (nth % 1))
                       (= value (nth % 2)))
                 datoms)))


#?(:cljs
   (defmacro async-deftest
     [name & body]
     `(deftest ~name
        (async ~'done
               (-> (identity nil)
                   ~@(map (fn [form] `(.then (fn [] ~form))) body)
                   (.then (fn [] (~'done)))
                   (.catch (fn [e#] (is nil e#) (~'done))))))))


(deftest eval-input-dispatch-test
  #?(:clj
     (do
       (testing "Clojure source evaluation persists VM state across inputs"
         (let [[state-1 result-1] @(repl/eval-input (repl/create-state) "(+ 20 22)")
               [state-2 result-2] @(repl/eval-input state-1 "(def x 10)")
               [_state-3 result-3] @(repl/eval-input state-2 "x")]
           (is (= "42" result-1))
           (is (= "10" result-2))
           (is (= "10" result-3))))
       (testing "AST maps are evaluated directly"
         (let [[_state result] @(repl/eval-input (repl/create-state)
                                                 "{:type :literal :value 7}")]
           (is (= "7" result))))
       (testing "Undefined symbols report an error instead of evaluating to nil"
         (let [[state-1 missing-result] @(repl/eval-input (repl/create-state)
                                                          "missing-symbol")
               [_state-2 nil-result] @(repl/eval-input state-1 "nil")]
           (is (str/includes? missing-result
                              "Unable to resolve symbol: missing-symbol in this context"))
           (is (= "nil" nil-result))))
       (testing "Raw datom streams are executed directly on datom VMs"
         (let [[_state result] @(repl/eval-input (repl/create-state)
                                                 "[[-1 :yin/type :literal 0 0]
                                                   [-1 :yin/value 99 0 0]]")]
           (is (= "99" result)))))
     :cljs
     (async done
            (-> (repl/eval-input (repl/create-state) "(+ 20 22)")
                (.then (fn [[state-1 result-1]]
                         (is (= "42" result-1))
                         (repl/eval-input state-1 "(def x 10)")))
                (.then (fn [[state-2 result-2]]
                         (is (= "10" result-2))
                         (repl/eval-input state-2 "x")))
                (.then (fn [[_state-3 result-3]]
                         (is (= "10" result-3))
                         (repl/eval-input (repl/create-state) "{:type :literal :value 7}")))
                (.then (fn [[_state result]]
                         (is (= "7" result))
                         (repl/eval-input (repl/create-state) "missing-symbol")))
                (.then (fn [[state-1 missing-result]]
                         (is (str/includes? missing-result
                                            "Unable to resolve symbol: missing-symbol in this context"))
                         (repl/eval-input state-1 "nil")))
                (.then (fn [[_state-2 nil-result]]
                         (is (= "nil" nil-result))
                         (repl/eval-input (repl/create-state)
                                          "[[-1 :yin/type :literal 0 0]
                                            [-1 :yin/value 99 0 0]]")))
                (.then (fn [[_state result]]
                         (is (= "99" result))
                         (done)))))))


(deftest command-dispatch-test
  #?(:clj
     (do
       (testing "vm and lang commands change subsequent evaluation semantics"
         (let [[state-1 vm-msg] @(repl/eval-input (repl/create-state) "(vm :register)")
               [_state-2 vm-result] @(repl/eval-input state-1 "(+ 1 2)")
               [state-3 lang-msg] @(repl/eval-input state-1 "(lang :python)")
               [_state-4 py-result] @(repl/eval-input state-3 "1 + 2")
               [state-5 php-msg] @(repl/eval-input state-3 "(lang :php)")
               [_state-6 php-result] @(repl/eval-input state-5 "1 + 2;")]
           (is (str/includes? vm-msg "RegisterVM"))
           (is (= "3" vm-result))
           (is (str/includes? lang-msg "Python"))
           (is (= "3" py-result))
           (is (str/includes? php-msg "PHP"))
           (is (= "3" php-result))))
       (testing "compile prints AST and datoms without executing"
         (let [[_state output] @(repl/eval-input (repl/create-state) "(compile (+ 1 2))")]
           (is (str/includes? output "AST:"))
           (is (str/includes? output "Datoms:"))
           (is (str/includes? output ":type :application"))))
       (testing "collection values are pretty-printed"
         (let [[_state result] @(repl/eval-input
                                  (repl/create-state)
                                  "{:type :literal
                                    :value {:alpha-long-keyword 1
                                            :beta-long-keyword 2
                                            :gamma-long-keyword 3
                                            :delta-long-keyword 4
                                            :epsilon-long-keyword 5}}")]
           (is (str/includes? result "\n"))
           (is (str/includes? result ":beta-long-keyword"))))
       (testing "quit marks the shell as no longer running"
         (let [[state result] @(repl/eval-input (repl/create-state) "(quit)")]
           (is (false? (:running? state)))
           (is (str/includes? result "Bye")))))
     :cljs
     (async done
            (-> (repl/eval-input (repl/create-state) "(vm :register)")
                (.then (fn [[state-1 vm-msg]]
                         (is (str/includes? vm-msg "RegisterVM"))
                         (repl/eval-input state-1 "(+ 1 2)")))
                (.then (fn [[state-2 vm-result]]
                         (is (= "3" vm-result))
                         (repl/eval-input state-2 "(lang :python)")))
                (.then (fn [[state-3 lang-msg]]
                         (is (str/includes? lang-msg "Python"))
                         (repl/eval-input state-3 "1 + 2")))
                (.then (fn [[state-4 py-result]]
                         (is (= "3" py-result))
                         (repl/eval-input state-4 "(lang :php)")))
                (.then (fn [[state-5 php-msg]]
                         (is (str/includes? php-msg "PHP"))
                         (repl/eval-input state-5 "1 + 2;")))
                (.then (fn [[_state-6 php-result]]
                         (is (= "3" php-result))
                         (repl/eval-input (repl/create-state) "(compile (+ 1 2))")))
                (.then (fn [[_state output]]
                         (is (str/includes? output "AST:"))
                         (is (str/includes? output "Datoms:"))
                         (is (str/includes? output ":type :application"))
                         (repl/eval-input
                           (repl/create-state)
                           "{:type :literal
                             :value {:alpha-long-keyword 1
                                     :beta-long-keyword 2
                                     :gamma-long-keyword 3
                                     :delta-long-keyword 4
                                     :epsilon-long-keyword 5}}")))
                (.then (fn [[_state result]]
                         (is (str/includes? result "\n"))
                         (is (str/includes? result ":beta-long-keyword"))
                         (repl/eval-input (repl/create-state) "(quit)")))
                (.then (fn [[state result]]
                         (is (false? (:running? state)))
                         (is (str/includes? result "Bye"))
                         (done)))))))


(deftest telemetry-command-test
  #?(:clj
     (testing "telemetry wires a stream into the active VM and records snapshots"
       (let [sink (ds/open! {:transport {:type :ringbuffer :capacity nil}})
             [state-1 _msg] @(repl/eval-input (repl/create-state) "(telemetry)")
             state-1 (assoc state-1 :telemetry-mode {:type :stream :stream sink})
             telemetry-stream (:telemetry-stream state-1)
             [state-2 result] @(repl/eval-input state-1 "(+ 1 2)")
             datoms (vec (ds/->seq nil sink))]
         (is (= "3" result))
         (is (= telemetry-stream (:telemetry-stream state-2)))
         (is (fact? datoms :vm/phase :step))
         (is (fact? datoms :vm/phase :halt))))
     :cljs
     (async done
            (let [sink (ds/open! {:transport {:type :ringbuffer :capacity nil}})]
              (-> (repl/eval-input (repl/create-state) "(telemetry)")
                  (.then (fn [[state-1 _msg]]
                           (let [state-1 (assoc state-1 :telemetry-mode {:type :stream :stream sink})]
                             (repl/eval-input state-1 "(+ 1 2)"))))
                  (.then (fn [[_state-2 result]]
                           (let [datoms (vec (ds/->seq nil sink))]
                             (is (= "3" result))
                             (is (fact? datoms :vm/phase :step))
                             (is (fact? datoms :vm/phase :halt))
                             (done)))))))))


(deftest print-primitives-test
  #?(:clj
     (testing "print, println, and prn emit through REPL output"
       (let [[state-1 print-result] @(repl/eval-input (repl/create-state)
                                                      "(print \"hello\")")
             [state-2 println-result] @(repl/eval-input state-1
                                                        "(println \"world\")")
             [_state-3 prn-result] @(repl/eval-input
                                      state-2
                                      "(prn {:alpha-long-keyword 1
                                             :beta-long-keyword 2
                                             :gamma-long-keyword 3})")]
         (is (= "hellonil" print-result))
         (is (= "world\nnil" println-result))
         (is (str/includes? prn-result ":beta-long-keyword"))
         (is (str/ends-with? prn-result "\nnil"))))
     :cljs
     (async done
            (-> (repl/eval-input (repl/create-state) "(print \"hello\")")
                (.then (fn [[state-1 print-result]]
                         (is (= "hellonil" print-result))
                         (repl/eval-input state-1 "(println \"world\")")))
                (.then (fn [[state-2 println-result]]
                         (is (= "world\nnil" println-result))
                         (repl/eval-input
                           state-2
                           "(prn {:alpha-long-keyword 1
                                  :beta-long-keyword 2
                                  :gamma-long-keyword 3})")))
                (.then (fn [[_state-3 prn-result]]
                         (is (str/includes? prn-result ":beta-long-keyword"))
                         (is (str/ends-with? prn-result "\nnil"))
                         (done)))))))


(deftest repl-state-command-test
  #?(:clj
     (testing "repl-state returns serializable shell state with stream and port exposure"
       (let [[state-1 _msg] @(repl/eval-input (repl/create-state) "(telemetry)")
             state-1 (-> state-1
                         (assoc-in [:exposed-streams :repl/server] (:telemetry-stream state-1))
                         (assoc-in [:exposed-ports :repl] {:transport :websocket
                                                           :port 7777
                                                           :stream :repl/server}))
             [state-2 result] @(repl/eval-input state-1 "(repl-state)")
             summary (edn/read-string result)]
         (is (= state-1 state-2))
         (is (= :clojure (:lang summary)))
         (is (= :semantic (get-in summary [:vm :type])))
         (is (= {:position 0} (get-in summary [:telemetry :cursor])))
         (is (true? (get-in summary [:telemetry :enabled?])))
         (is (= :stderr (get-in summary [:telemetry :mode])))
         (is (true? (get-in summary [:streams :telemetry :present?])))
         (is (= :open (get-in summary [:streams :telemetry :status])))
         (is (= [{:name :repl
                  :transport :websocket
                  :port 7777
                  :stream :repl/server}]
                (:ports summary)))
         (is (= {:present? true
                 :status :open}
                (get-in summary [:exposed-streams :repl/server])))))
     :cljs
     (async done
            (-> (repl/eval-input (repl/create-state) "(telemetry)")
                (.then (fn [[state-1 _msg]]
                         (let [state-1 (-> state-1
                                           (assoc-in [:exposed-streams :repl/server] (:telemetry-stream state-1))
                                           (assoc-in [:exposed-ports :repl] {:transport :websocket
                                                                             :port 7777
                                                                             :stream :repl/server}))]
                           (repl/eval-input state-1 "(repl-state)"))))
                (.then (fn [[_state-2 result]]
                         (let [summary (edn/read-string result)]
                           (is (= :clojure (:lang summary)))
                           (is (= :semantic (get-in summary [:vm :type])))
                           (is (= {:position 0} (get-in summary [:telemetry :cursor])))
                           (is (true? (get-in summary [:telemetry :enabled?])))
                           (is (= :stderr (get-in summary [:telemetry :mode])))
                           (is (true? (get-in summary [:streams :telemetry :present?])))
                           (is (= :open (get-in summary [:streams :telemetry :status])))
                           (is (= [{:name :repl
                                    :transport :websocket
                                    :port 7777
                                    :stream :repl/server}]
                                  (:ports summary)))
                           (is (= {:present? true
                                   :status :open}
                                  (get-in summary [:exposed-streams :repl/server])))
                           (done))))))))


(deftest request-handling-test
  #?(:clj
     (do
       (testing "handle-request evaluates input and returns a response payload"
         (let [[state response] @(repl/handle-request
                                   (repl/create-state)
                                   (dao-apply/request :req-1 :op/eval ["(+ 9 33)"]))]
           (is (= :req-1 (dao-apply/response-id response)))
           (is (= "42" (dao-apply/response-value response)))
           (is (= :semantic (:vm-type state)))))
       (testing "serve-once! persists shell state across requests"
         (let [state-atom (atom (repl/create-state))
               request-stream (make-stream)
               response-stream (make-stream)
               _ (dao-apply/put-request! request-stream :req-1 :op/eval ["(def x 7)"])
               served-1 @(repl/serve-once! state-atom
                                           request-stream
                                           response-stream
                                           {:position 0})
               _ (dao-apply/put-request! request-stream :req-2 :op/eval ["x"])
               served-2 @(repl/serve-once! state-atom
                                           request-stream
                                           response-stream
                                           (:cursor served-1))
               response-1 (dao-apply/next-response response-stream {:position 0})
               response-2 (dao-apply/next-response response-stream (:cursor response-1))]
           (is (= "7" (dao-apply/response-value (:ok response-1))))
           (is (= "7" (dao-apply/response-value (:ok response-2))))
           (is (= {:position 2} (:cursor served-2))))))
     :cljs
     (async done
            (let [state-atom (atom (repl/create-state))
                  request-stream (make-stream)
                  response-stream (make-stream)]
              (-> (repl/handle-request (repl/create-state)
                                       (dao-apply/request :req-1 :op/eval ["(+ 9 33)"]))
                  (.then (fn [[state response]]
                           (is (= :req-1 (dao-apply/response-id response)))
                           (is (= "42" (dao-apply/response-value response)))
                           (is (= :semantic (:vm-type state)))
                           (dao-apply/put-request! request-stream :req-1 :op/eval ["(def x 7)"])
                           (repl/serve-once! state-atom request-stream response-stream {:position 0})))
                  (.then (fn [served-1]
                           (dao-apply/put-request! request-stream :req-2 :op/eval ["x"])
                           (repl/serve-once! state-atom request-stream response-stream (:cursor served-1))))
                  (.then (fn [served-2]
                           (let [response-1 (dao-apply/next-response response-stream {:position 0})
                                 response-2 (dao-apply/next-response response-stream (:cursor response-1))]
                             (is (= "7" (dao-apply/response-value (:ok response-1))))
                             (is (= "7" (dao-apply/response-value (:ok response-2))))
                             (is (= {:position 2} (:cursor served-2)))
                             (done)))))))))
