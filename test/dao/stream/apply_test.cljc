(ns dao.stream.apply-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.apply :as dao-apply]
    [dao.stream.transport.ringbuffer]))


(defn- make-stream
  []
  (ds/open! {:transport {:type :ringbuffer :capacity nil}}))


(deftest endpoint-construction-test
  (testing "make-endpoint stores request and response descriptors verbatim"
    (let [request-desc {:transport {:type :ringbuffer :capacity nil}}
          response-desc {:transport {:type :ringbuffer :capacity 8}}
          endpoint (dao-apply/make-endpoint request-desc response-desc)]
      (is (= request-desc (dao-apply/endpoint-request endpoint)))
      (is (= response-desc (dao-apply/endpoint-response endpoint))))))


(deftest request-and-response-shape-test
  (testing "request helpers round-trip the mandatory fields"
    (let [request (dao-apply/request :call-7 :op/add [10 20])]
      (is (dao-apply/request? request))
      (is (= :call-7 (dao-apply/request-id request)))
      (is (= :op/add (dao-apply/request-op request)))
      (is (= [10 20] (dao-apply/request-args request)))))
  (testing "response helpers round-trip the mandatory fields, including nil values"
    (let [response (dao-apply/response :call-7 nil)]
      (is (dao-apply/response? response))
      (is (= :call-7 (dao-apply/response-id response)))
      (is (= nil (dao-apply/response-value response)))))
  (testing "request constructor enforces protocol shape"
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (dao-apply/request :call-7 "op/add" [10 20])))
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (dao-apply/request :call-7 :op/add '(10 20))))))


(deftest request-and-response-stream-helpers-test
  (testing "put-request! and next-request round-trip a request value"
    (let [request-stream (make-stream)
          put-result (dao-apply/put-request! request-stream :call-1 :op/echo [42])
          read-result (dao-apply/next-request request-stream {:position 0})]
      (is (= :ok (:result put-result)))
      (is (= (dao-apply/request :call-1 :op/echo [42]) (:ok read-result)))
      (is (= {:position 1} (:cursor read-result)))))
  (testing "put-response! and next-response round-trip a response value"
    (let [response-stream (make-stream)
          response (dao-apply/response :call-1 {:ok true})
          _ (dao-apply/put-response! response-stream response)
          read-result (dao-apply/next-response response-stream {:position 0})]
      (is (= response (:ok read-result)))
      (is (= {:position 1} (:cursor read-result)))))
  (testing "next-request rejects non-request payloads"
    (let [request-stream (make-stream)]
      (ds/put! request-stream {:not "a request"})
      (is (thrown? #?(:clj Exception :cljs js/Error)
            (dao-apply/next-request request-stream {:position 0})))))
  (testing "next-response rejects non-response payloads"
    (let [response-stream (make-stream)]
      (ds/put! response-stream {:not "a response"})
      (is (thrown? #?(:clj Exception :cljs js/Error)
            (dao-apply/next-response response-stream {:position 0}))))))


(deftest dispatch-request-test
  (testing "dispatch-request preserves opaque id and handler result"
    (let [request (dao-apply/request [:opaque 9] :op/add [10 20])
          response (dao-apply/dispatch-request {:op/add +} request)]
      (is (= [:opaque 9] (dao-apply/response-id response)))
      (is (= 30 (dao-apply/response-value response)))))
  (testing "missing handler is an explicit callee error"
    (let [request (dao-apply/request :call-missing :op/missing [])]
      (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                            #"No dao.stream.apply handler for op"
            (dao-apply/dispatch-request {} request))))))


(deftest serve-once-test
  (testing "serve-once! reads one request, dispatches it, and appends one response"
    (let [request-stream (make-stream)
          response-stream (make-stream)
          _ (dao-apply/put-request! request-stream :call-9 :op/add [7 8])
          served (dao-apply/serve-once! {:op/add +}
                                        request-stream
                                        response-stream
                                        {:position 0})
          response-read (dao-apply/next-response response-stream {:position 0})]
      (is (= (dao-apply/request :call-9 :op/add [7 8]) (:request served)))
      (is (= (dao-apply/response :call-9 15) (:response served)))
      (is (= {:position 1} (:cursor served)))
      (is (= :ok (get-in served [:put-result :result])))
      (is (= (:response served) (:ok response-read)))))
  (testing "serve-once! preserves DaoStream sentinels when nothing is readable"
    (let [request-stream (make-stream)
          response-stream (make-stream)]
      (is (= :blocked
             (dao-apply/serve-once! {:op/add +}
                                    request-stream
                                    response-stream
                                    {:position 0})))
      (ds/close! request-stream)
      (is (= :end
             (dao-apply/serve-once! {:op/add +}
                                    request-stream
                                    response-stream
                                    {:position 0}))))))
