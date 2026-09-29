(ns dao.stream.apply-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.apply :as apply]
            [dao.stream.ringbuffer :as ring]))


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
      (is (not (apply/request? {:dao.stream.apply/id nil
                                :dao.stream.apply/op :yin/eval
                                :dao.stream.apply/args []})))
      (is (not (apply/request? {:dao.stream.apply/id :id
                                :dao.stream.apply/op "yin/eval"
                                :dao.stream.apply/args []})))
      (is (not (apply/request? {:dao.stream.apply/id :id
                                :dao.stream.apply/op :yin/eval
                                :dao.stream.apply/args '()})))))
  (testing "responses carry exactly one of success or structured error"
    (let [ok (apply/success-response :id {:value 3})
          error (apply/error-response :id :dao.stream.apply/unknown-operation
                                      "No handler for operation")]
      (is (apply/response? ok))
      (is (= {:value 3} (apply/response-ok ok)))
      (is (nil? (apply/response-error ok)))
      (is (apply/response? error))
      (is (= :dao.stream.apply/unknown-operation
             (get-in error [:dao.stream.apply/error
                            :dao.stream.apply/code])))
      (is (not (apply/response?
                 {:dao.stream.apply/id :id
                  :dao.stream.apply/ok :one
                  :dao.stream.apply/error {:dao.stream.apply/code :x/y
                                           :dao.stream.apply/message "no"}}))))))


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
    (is (= :dao.stream.apply/unknown-operation
           (get-in (apply/dispatch-request {} request)
                   [:dao.stream.apply/error :dao.stream.apply/code])))
    (is (= :dao.stream.apply/handler-error
           (get-in (apply/dispatch-request {:math/add (fn [& _]
                                                        (throw (ex-info "boom" {})))}
                                           request)
                   [:dao.stream.apply/error :dao.stream.apply/code])))))


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
    (is (= :dao.stream.apply/responded (:dao.stream.apply/outcome first-step)))
    (is (= 3 (apply/response-ok
               (:dao.stream/value
                 (apply/next-response response-handle response-cursor)))))
    (is (= :dao.stream.apply/idle
           (:dao.stream.apply/outcome
             (apply/serve-once! {:math/add +}
                                request-handle response-handle
                                (:dao.stream.apply/state first-step)))))))


(deftest serve-once-consumes-malformed-elements-without-calling-a-handler
  (let [request-handle (handle)
        response-handle (handle)
        request-cursor (cursor request-handle)
        response-cursor (cursor response-handle)
        calls (atom 0)
        _ (stream/append! request-handle
                          {:dao.stream.apply/id :request-4
                           :dao.stream.apply/op :math/add
                           :dao.stream.apply/args '()})
        step (apply/serve-once! {:math/add (fn [& _] (swap! calls inc))}
                                request-handle response-handle
                                (apply/server-state request-cursor))]
    (is (zero? @calls))
    (is (= :dao.stream.apply/malformed-request
           (get-in (:dao.stream.apply/response step)
                   [:dao.stream.apply/error :dao.stream.apply/code])))
    (is (= :request-4
           (apply/response-id
             (:dao.stream/value
               (apply/next-response response-handle response-cursor)))))))


;; =============================================================================
;; Apply is independent of RPC: it can run over a framebuffer
;; =============================================================================

(defn- framebuffer
  "A framebuffer-like medium: a fixed array of cells written in order and
   read by cell index.  It knows nothing of requests, clients, or ids; a full
   frame refuses further writes."
  [size]
  (let [cells (atom [])]
    (reify
      stream/IDaoStreamReader
      (cursor
        [_ anchor]
        (case anchor
          :dao.stream/oldest {:dao.stream/outcome :dao.stream/ok
                              :dao.stream/cursor 0}
          :dao.stream/newest {:dao.stream/outcome :dao.stream/ok
                              :dao.stream/cursor (count @cells)}
          {:dao.stream/outcome :dao.stream/invalid-anchor}))

      (next
        [_ cell]
        (if (< cell (count @cells))
          {:dao.stream/outcome :dao.stream/ok
           :dao.stream/value (nth @cells cell)
           :dao.stream/cursor (inc cell)}
          {:dao.stream/outcome :dao.stream/blocked}))


      stream/IDaoStreamWriter

      (append!
        [_ value]
        (if (< (count @cells) size)
          (do (swap! cells conj value)
              {:dao.stream/outcome :dao.stream/ok})
          {:dao.stream/outcome :dao.stream/full})))))


(deftest apply-serves-over-a-framebuffer-with-opaque-ids
  (let [requests (framebuffer 4)
        responses (framebuffer 4)
        request-cursor (:dao.stream/cursor (stream/cursor requests :dao.stream/oldest))
        response-cursor (:dao.stream/cursor (stream/cursor responses :dao.stream/oldest))
        id {:frame 3 :pixel [10 20]}
        put (apply/put-request! requests (apply/request id :pixel/shade [21]))
        step (apply/serve-once! {:pixel/shade (fn [x] (* 2 x))}
                                requests responses
                                (apply/server-state request-cursor))
        read (apply/next-response responses response-cursor)]
    (is (= :dao.stream/ok (:dao.stream/outcome put)))
    (is (= :dao.stream.apply/responded (:dao.stream.apply/outcome step)))
    (is (= :dao.stream/ok (:dao.stream/outcome read)))
    (is (= id (apply/response-id (:dao.stream/value read)))
        "a non-numeric opaque id is carried and correlated unchanged")
    (is (= 42 (apply/response-ok (:dao.stream/value read))))
    (testing "an application-owned error code is carried as given"
      (let [error (apply/error-response :frame-7 :pixel/out-of-gamut "no")]
        (is (apply/response? error))
        (is (= :pixel/out-of-gamut
               (get-in error [:dao.stream.apply/error
                              :dao.stream.apply/code])))))))


#?(:cljd nil
   :clj
   (deftest apply-has-no-rpc-dependency-or-vocabulary
     (let [source (slurp (.getResource (clojure.lang.RT/baseLoader)
                                       "dao/stream/apply.cljc"))
           ns-form (read {:read-cond :allow :features #{:clj}}
                         (java.io.PushbackReader. (java.io.StringReader. source)))
           requires (->> ns-form
                         (filter #(and (seq? %) (= :require (first %))))
                         (mapcat rest)
                         (map #(if (vector? %) (first %) %))
                         set)
           texts (cons (:doc (meta (the-ns 'dao.stream.apply)))
                       (keep (comp :doc meta) (vals (ns-publics 'dao.stream.apply))))
           forbidden #"(?i)rpc|transport|client|remote|socket|wire|network"]
       (is (= #{'dao.stream} requires)
           "apply depends on the stream contract alone")
       (is (< 10 (count texts)) "the docstrings are actually inspected")
       (is (= [] (filterv #(re-find forbidden %) texts))
           "apply's namespace text and docstrings name no rpc or transport concept"))))
