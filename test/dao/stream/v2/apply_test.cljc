(ns dao.stream.v2.apply-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.apply :as apply]
            [dao.stream.v2.ringbuffer :as ring]))


(defn- handle
  []
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key 8})))


(defn- cursor
  [h]
  (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)))


(deftest envelope-shapes-are-validated
  (testing "request maps preserve a non-nil opaque correlation id"
    (let [request (apply/request [:client 7] :yin/eval ["(+ 1 2)"])]
      (is (apply/request? request))
      (is (= [:client 7] (apply/request-id request)))
      (is (= :yin/eval (apply/request-op request)))
      (is (= ["(+ 1 2)"] (apply/request-args request)))
      (is (not (apply/request? {:dao.stream.v2.apply/id nil
                                :dao.stream.v2.apply/op :yin/eval
                                :dao.stream.v2.apply/args []})))
      (is (not (apply/request? {:dao.stream.v2.apply/id :id
                                :dao.stream.v2.apply/op "yin/eval"
                                :dao.stream.v2.apply/args []})))
      (is (not (apply/request? {:dao.stream.v2.apply/id :id
                                :dao.stream.v2.apply/op :yin/eval
                                :dao.stream.v2.apply/args '()})))))
  (testing "responses carry exactly one of success or structured error"
    (let [ok (apply/success-response :id {:value 3})
          error (apply/error-response :id :dao.stream.v2.apply/unknown-operation
                                      "No handler for operation")]
      (is (apply/response? ok))
      (is (= {:value 3} (apply/response-ok ok)))
      (is (nil? (apply/response-error ok)))
      (is (apply/response? error))
      (is (= :dao.stream.v2.apply/unknown-operation
             (get-in error [:dao.stream.v2.apply/error
                            :dao.stream.v2.apply/code])))
      (is (not (apply/response?
                 {:dao.stream.v2.apply/id :id
                  :dao.stream.v2.apply/ok :one
                  :dao.stream.v2.apply/error {:dao.stream.v2.apply/code :x/y
                                              :dao.stream.v2.apply/message "no"}}))))))


(deftest endpoint-and-explicit-cursor-helpers
  (let [request-handle (handle)
        response-handle (handle)
        request-descriptor (:dao.stream/descriptor
                             (stream/descriptor request-handle))
        response-descriptor (:dao.stream/descriptor
                              (stream/descriptor response-handle))
        endpoint (apply/endpoint request-descriptor response-descriptor)
        request-cursor (cursor request-handle)
        response-cursor (cursor response-handle)
        request (apply/request :request-1 :yin/echo [:hello])]
    (is (apply/endpoint? endpoint))
    (is (= request-descriptor (apply/endpoint-request endpoint)))
    (is (= response-descriptor (apply/endpoint-response endpoint)))
    (is (= :dao.stream/ok
           (:dao.stream/outcome (apply/put-request! request-handle request))))
    (let [read-request (apply/next-request request-handle request-cursor)]
      (is (= :dao.stream/ok (:dao.stream/outcome read-request)))
      (is (= request (:dao.stream/value read-request)))
      (is (= :dao.stream/ok
             (:dao.stream/outcome
               (apply/put-response! response-handle
                                    (apply/success-response :request-1 :hello)))))
      (is (= :hello
             (apply/response-ok
               (:dao.stream/value
                 (apply/next-response response-handle response-cursor))))))))


(deftest dispatch-is-correlated-and-never-leaks-handler-errors
  (let [request (apply/request :request-2 :math/add [20 22])]
    (is (= 42 (apply/response-ok
                (apply/dispatch-request {:math/add +} request))))
    (is (= :dao.stream.v2.apply/unknown-operation
           (get-in (apply/dispatch-request {} request)
                   [:dao.stream.v2.apply/error :dao.stream.v2.apply/code])))
    (is (= :dao.stream.v2.apply/handler-error
           (get-in (apply/dispatch-request {:math/add (fn [& _]
                                                        (throw (ex-info "boom" {})))}
                                           request)
                   [:dao.stream.v2.apply/error :dao.stream.v2.apply/code])))))


(deftest serve-once-retains-a-response-until-it-is-delivered
  (let [request-handle (handle)
        response-handle (handle)
        request-cursor (cursor request-handle)
        response-cursor (cursor response-handle)
        calls (atom 0)
        _ (apply/put-request! request-handle
                              (apply/request :request-3 :math/add [1 2]))
        first-step (apply/serve-once!
                     {:math/add (fn [a b] (swap! calls inc) (+ a b))}
                     request-handle response-handle
                     (apply/server-state request-cursor))]
    (is (= 1 @calls))
    (is (= :dao.stream.v2.apply/responded (:dao.stream.v2.apply/outcome first-step)))
    (is (= 3 (apply/response-ok
               (:dao.stream/value
                 (apply/next-response response-handle response-cursor)))))
    (is (= :dao.stream.v2.apply/idle
           (:dao.stream.v2.apply/outcome
             (apply/serve-once! {:math/add +}
                                request-handle response-handle
                                (:dao.stream.v2.apply/state first-step)))))))


(deftest serve-once-consumes-malformed-elements-without-calling-a-handler
  (let [request-handle (handle)
        response-handle (handle)
        request-cursor (cursor request-handle)
        response-cursor (cursor response-handle)
        calls (atom 0)
        _ (stream/append! request-handle
                          {:dao.stream.v2.apply/id :request-4
                           :dao.stream.v2.apply/op :math/add
                           :dao.stream.v2.apply/args '()})
        step (apply/serve-once! {:math/add (fn [& _] (swap! calls inc))}
                                request-handle response-handle
                                (apply/server-state request-cursor))]
    (is (zero? @calls))
    (is (= :dao.stream.v2.apply/malformed-request
           (get-in (:dao.stream.v2.apply/response step)
                   [:dao.stream.v2.apply/error :dao.stream.v2.apply/code])))
    (is (= :request-4
           (apply/response-id
             (:dao.stream/value
               (apply/next-response response-handle response-cursor)))))))
