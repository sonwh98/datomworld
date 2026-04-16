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
    [dao.stream.ringbuffer :as ringbuffer]))


(defn- make-stream
  []
  (ds/open! {:type :ringbuffer :capacity nil}))


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
       (let [sink (ds/open! {:type :ringbuffer :capacity nil})
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
            (let [sink (ds/open! {:type :ringbuffer :capacity nil})]
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
                         (assoc-in [:exposed-ports :repl] {:transport :websocket
                                                           :port 7777
                                                           :server {:dummy :server-handle}}))
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
                  :port 7777}]
                (:ports summary)))))
     :cljs
     (async done
            (-> (repl/eval-input (repl/create-state) "(telemetry)")
                (.then (fn [[state-1 _msg]]
                         (let [state-1 (-> state-1
                                           (assoc-in [:exposed-ports :repl] {:transport :websocket
                                                                             :port 7777
                                                                             :server {:dummy :server-handle}}))]
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
                                    :port 7777}]
                                  (:ports summary)))
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


(deftest remote-eval-input-contract-test
  #?(:clj
     (testing "remote eval keeps the same derefable contract as local eval"
       (with-redefs [dao.stream.apply/put-request!
                     (fn [_request-stream _request-id _op _args]
                       {:result :ok})
                     dao.repl/wait-for-response
                     (fn [state request-id]
                       [state (dao-apply/response request-id "3")])]
         (let [state (assoc (repl/create-state)
                            :remote-endpoint {:request-stream ::request
                                              :response-stream ::response})
               result (repl/eval-input state "(+ 1 2)")]
           (is (instance? clojure.lang.IDeref result))
           (is (= [(update state :request-id inc) "3"]
                  @result)))))
     :default
     (is true)))


(deftest remote-connect-ignores-stale-response-history-test
  #?(:clj
     (testing "reconnecting to a persistent remote endpoint does not reuse stale response ids"
       (let [request-stream (make-stream)
             response-stream (make-stream)
             stale-id :dao.repl/request-0
             endpoint {:request-stream request-stream
                       :response-stream response-stream}]
         (dao-apply/put-response! response-stream stale-id "STALE")
         (with-redefs [dao.repl/open-remote-endpoint
                       (fn [_url] endpoint)
                       dao.stream.apply/put-request!
                       (fn [_request-stream request-id _op _args]
                         (dao-apply/put-response! response-stream request-id "FRESH")
                         {:result :ok})]
           (let [[state-1 _] @(repl/eval-input (repl/create-state)
                                               "(connect \"ws://remote\")")
                 [_state-2 result] @(repl/eval-input state-1 "(+ 1 2)")]
             (is (= "FRESH" result))))))
     :default
     (is true)))


(deftest repl-state-stays-local-while-remote-connected-test
  #?(:clj
     (testing "repl-state reports the local shell even when a remote endpoint is attached"
       (with-redefs [dao.repl/eval-remote-input
                     (fn [state _input-str]
                       (let [p (promise)]
                         (deliver p [state "REMOTE"])
                         p))]
         (let [state (assoc (repl/create-state)
                            :remote-endpoint {:request-stream ::request
                                              :response-stream ::response})
               result @(repl/eval-input state "(repl-state)")
               [_state summary-str] result]
           (is (not= "REMOTE" summary-str))
           (is (= {:connected? true
                   :request-id 0
                   :response-cursor {:position 0}
                   :streams {:request {:present? true
                                       :status :unknown}
                             :response {:present? true
                                        :status :unknown}}}
                  (-> summary-str edn/read-string :remote))))))
     :default
     (is true)))


(deftest reconnect-closes-previous-remote-endpoint-test
  #?(:clj
     (testing "connecting again closes the previous remote request and response streams"
       (let [closed-streams (atom [])
             endpoint-1 {:request-stream ::request-1
                         :response-stream ::response-1}
             endpoint-2 {:request-stream ::request-2
                         :response-stream ::response-2}]
         (with-redefs [dao.repl/open-remote-endpoint
                       (fn [url]
                         (case url
                           "ws://one" endpoint-1
                           "ws://two" endpoint-2))
                       dao.stream/close!
                       (fn [stream]
                         (swap! closed-streams conj stream)
                         {:woke []})]
           (let [[state-1 _] @(repl/eval-input (repl/create-state) "(connect \"ws://one\")")
                 [state-2 _] @(repl/eval-input state-1 "(connect \"ws://two\")")]
             (is (= endpoint-2 (:remote-endpoint state-2)))
             (is (= [::request-1 ::response-1]
                    @closed-streams))))))
     :default
     (is true)))


(deftest repl-server-worker-retries-after-stream-end-test
  #?(:clj
     (testing "serve! keeps polling after a transient :end so reconnects can resume"
       (let [calls (atom [])
             results (atom [:end :blocked :blocked])
             state-atom (atom (repl/create-state))
             stream (ringbuffer/make-ring-buffer-stream nil)]
         (with-redefs [dao.stream/open!
                       (fn [descriptor]
                         (if-let [on-connect (:on-connect descriptor)]
                           (do
                             ;; simulate a connection happening immediately
                             (on-connect stream)
                             {:stop-fn (constantly nil)
                              :conns (atom #{stream})})
                           stream))
                       dao.repl/serve-once!
                       (fn [_state-atom _request-stream _response-stream cursor]
                         (swap! calls conj cursor)
                         (let [result (or (first @results) :blocked)
                               p (promise)]
                           (swap! results #(if (seq %) (vec (rest %)) %))
                           (deliver p result)
                           p))
                       dao.stream/close!
                       (fn [_] {:woke []})]
           (let [server (repl/serve! state-atom 7779 {:sleep-ms 1})]
             (Thread/sleep 100)
             ((:stop! server))
             (is (<= 2 (count @calls)))
             (is (= [{:position 0} {:position 0}]
                    (take 2 @calls)))))))
     :default
     (is true)))


(deftest telemetry-reroute-closes-previous-sink-test
  #?(:clj
     (testing "routing telemetry to a new sink closes the previous stream sink"
       (let [closed-streams (atom [])
             telemetry-stream ::telemetry
             old-sink ::old-sink
             new-sink ::new-sink]
         (with-redefs [dao.repl/open-telemetry-sink
                       (fn [_url] new-sink)
                       dao.stream/close!
                       (fn [stream]
                         (swap! closed-streams conj stream)
                         {:woke []})]
           (let [state (assoc (repl/create-state {:telemetry-stream telemetry-stream})
                              :telemetry-mode {:type :stream
                                               :stream old-sink
                                               :url "ws://old"})
                 [state' _] @(repl/eval-input state "(telemetry \"ws://new\")")]
             (is (= {:type :stream
                     :stream new-sink
                     :url "ws://new"}
                    (:telemetry-mode state')))
             (is (= [old-sink]
                    @closed-streams))))))
     :default
     (is true)))


(deftest last-value-test
  #?(:clj
     (testing "*1, *2, *3 hold the last three evaluated values"
       (let [[state-1 result-1] @(repl/eval-input (repl/create-state) "10")
             [state-2 result-2] @(repl/eval-input state-1 "20")
             [state-3 result-3] @(repl/eval-input state-2 "30")
             [_state-4 result-4] @(repl/eval-input state-3 "(+ *1 *2 *3)")]
         (is (= "10" result-1))
         (is (= "20" result-2))
         (is (= "30" result-3))
         (is (= "60" result-4))
         ;; If *1 is a function (from a previous eval), it should be callable
         (let [[state-5 result-5] @(repl/eval-input (repl/create-state) "(fn [x] (* x x))")
               [_state-6 result-6] @(repl/eval-input state-5 "(*1 5)")]
           (is (= "25" result-6)))))
     :cljs
     (async done
            (-> (repl/eval-input (repl/create-state) "10")
                (.then (fn [[state-1 result-1]]
                         (is (= "10" result-1))
                         (repl/eval-input state-1 "20")))
                (.then (fn [[state-2 result-2]]
                         (is (= "20" result-2))
                         (repl/eval-input state-2 "30")))
                (.then (fn [[state-3 result-3]]
                         (is (= "30" result-3))
                         (repl/eval-input state-3 "(+ *1 *2 *3)")))
                (.then (fn [[state-4 result-4]]
                         (is (= "60" result-4))
                         (repl/eval-input (repl/create-state) "(fn [x] (* x x))")))
                (.then (fn [[state-5 result-5]]
                         (repl/eval-input state-5 "(*1 5)")))
                (.then (fn [[_state-6 result-6]]
                         (is (= "25" result-6))
                         (done)))))))


(deftest symbol-quoting-test
  #?(:clj
     (testing "Symbols in REPL output are quoted for copy-paste safety"
       (let [[_state-1 result-1] @(repl/eval-input (repl/create-state) "(quote foo)")
             [_state-2 result-2] @(repl/eval-input (repl/create-state) "[1 (quote bar) :baz]")
             [_state-3 result-3] @(repl/eval-input (repl/create-state) "{(quote a) 1 :b 2}")]
         (is (= "'foo" result-1))
         (is (= "[1 'bar :baz]" result-2))
         (is (= "{'a 1, :b 2}" result-3))))
     :cljs
     (async done
            (-> (repl/eval-input (repl/create-state) "(quote foo)")
                (.then (fn [[_state-1 result-1]]
                         (is (= "'foo" result-1))
                         (repl/eval-input (repl/create-state) "[1 (quote bar) :baz]")))
                (.then (fn [[_state-2 result-2]]
                         (is (= "[1 'bar :baz]" result-2))
                         (repl/eval-input (repl/create-state) "{(quote a) 1 :b 2}")))
                (.then (fn [[_state-3 result-3]]
                         (is (= "{'a 1, :b 2}" result-3))
                         (done)))))))


(deftest concurrent-clients-test
  #?(:clj
     (testing "serve! handles multiple concurrent websocket clients independently"
       (let [port 7781
             server-state (atom (repl/create-state))
             server (repl/serve! server-state port {:sleep-ms 10})
             ;; wait for server to start
             _ (Thread/sleep 200)
             ;; Client A
             client-a-stream (ds/open! {:type :websocket
                                        :mode :connect
                                        :url (str "ws://localhost:" port)})
             client-a-req-id (atom 100)
             ;; Client B
             client-b-stream (ds/open! {:type :websocket
                                        :mode :connect
                                        :url (str "ws://localhost:" port)})
             client-b-req-id (atom 200)

             ;; Helper to send request and await response
             send-req (fn [stream req-id-atom expr]
                        (let [req-id (swap! req-id-atom inc)
                              req (dao-apply/request req-id :op/eval [expr])]
                          (dao-apply/put-request! stream req)
                          (loop [attempts 100
                                 cursor {:position 0}]
                            (if (zero? attempts)
                              (throw (ex-info "Timeout waiting for response" {:req req}))
                              (let [res (dao-apply/next-response stream cursor)]
                                (cond
                                  (map? res)
                                  (let [response (:ok res)]
                                    (if (= req-id (dao-apply/response-id response))
                                      (dao-apply/response-value response)
                                      (recur (dec attempts) (:cursor res))))
                                  (= :blocked res)
                                  (do (Thread/sleep 50) (recur (dec attempts) cursor))
                                  (= :daostream/gap res)
                                  (recur (dec attempts) {:position 0})
                                  :else (recur (dec attempts) cursor)))))))]
         (try
           ;; Both clients connect successfully
           (Thread/sleep 300)

           ;; Client A sends a request
           (let [res-a (send-req client-a-stream client-a-req-id "(+ 1 1)")]
             (is (= "2" res-a)))

           ;; Client B sends a request
           (let [res-b (send-req client-b-stream client-b-req-id "(+ 3 3)")]
             (is (= "6" res-b)))

           ;; Close client A
           (ds/close! client-a-stream)
           (Thread/sleep 200)

           ;; Client B should still work perfectly
           (let [res-b2 (send-req client-b-stream client-b-req-id "(+ 10 10)")]
             (is (= "20" res-b2)))

           (finally
             (ds/close! client-a-stream)
             (ds/close! client-b-stream)
             ((:stop! server))))))))
