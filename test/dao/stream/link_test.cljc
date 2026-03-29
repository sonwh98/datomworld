(ns dao.stream.link-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.link :as link]
    [dao.stream.transit :as transit]))


;; =============================================================================
;; Test helpers
;; =============================================================================

(defn- make-stream
  []
  (ds/->RingBufferStream nil (atom {:log [], :head 0, :closed false})))


(defn- make-stream-with
  [& vals]
  (let [s (make-stream)]
    (doseq [v vals] (ds/put! s v))
    s))


(defn- stream->vec
  [s]
  (loop [c {:position 0}
         acc []]
    (let [r (ds/next s c)]
      (if (map? r) (recur (:cursor r) (conj acc (:ok r))) acc))))


;; =============================================================================
;; Message Constructor Tests
;; =============================================================================

(deftest put-msg-test
  (testing "put-msg constructs correct message"
    (let [msg (link/put-msg [[:e :a :v 0 0]] 5)]
      (is (= :datom/put (:type msg)))
      (is (= [[:e :a :v 0 0]] (:datoms msg)))
      (is (= 5 (:from-pos msg))))))


(deftest sync-request-msg-test
  (testing "sync-request-msg constructs correct message"
    (let [msg (link/sync-request-msg 3)]
      (is (= :datom/sync-request (:type msg)))
      (is (= 3 (:from-pos msg))))))


(deftest sync-response-msg-test
  (testing "sync-response-msg constructs correct message"
    (let [msg (link/sync-response-msg [[:a] [:b]] 0 2)]
      (is (= :datom/sync-response (:type msg)))
      (is (= [[:a] [:b]] (:datoms msg)))
      (is (= 0 (:from-pos msg)))
      (is (= 2 (:to-pos msg))))))


(deftest close-msg-test
  (testing "close-msg constructs correct message"
    (let [msg (link/close-msg)] (is (= :stream/close (:type msg))))))


;; =============================================================================
;; Link State Tests
;; =============================================================================

(deftest make-link-state-test
  (testing "make-link-state creates initial state"
    (let [local (make-stream)
          state (link/make-link-state local)]
      (is (= local (:local-stream state)))
      (is (= 0 (ds/length (:remote-stream state))))
      (is (= 0 (:local-pos state)))
      (is (= 0 (:remote-pos state)))
      (is (= :connecting (:status state))))))


;; =============================================================================
;; Handle Put Tests
;; =============================================================================

(deftest handle-put-test
  (testing "handle-put appends datoms to remote-stream"
    (let [local (make-stream)
          state (link/make-link-state local)
          msg (link/put-msg [[:e1 :a1 :v1 0 0] [:e2 :a2 :v2 1 0]] 0)
          state' (link/handle-put state msg)]
      (is (= 2 (ds/length (:remote-stream state'))))
      (is (= 2 (:remote-pos state')))
      (is (= [:e1 :a1 :v1 0 0] (first (stream->vec (:remote-stream state')))))
      (is (= [:e2 :a2 :v2 1 0]
             (second (stream->vec (:remote-stream state')))))))
  (testing "handle-put accumulates across multiple messages"
    (let [local (make-stream)
          state (link/make-link-state local)
          state' (-> state
                     (link/handle-put (link/put-msg [[:a]] 0))
                     (link/handle-put (link/put-msg [[:b]] 1)))]
      (is (= 2 (ds/length (:remote-stream state'))))
      (is (= [[:a] [:b]] (stream->vec (:remote-stream state')))))))


;; =============================================================================
;; Handle Sync Request Tests
;; =============================================================================

(deftest handle-sync-request-test
  (testing "sync-request returns datoms from requested position"
    (let [local (make-stream-with [:d1] [:d2] [:d3])
          state (link/make-link-state local)
          msg (link/sync-request-msg 0)
          [_state' response] (link/handle-sync-request state msg)]
      (is (= :datom/sync-response (:type response)))
      (is (= [[:d1] [:d2] [:d3]] (:datoms response)))
      (is (= 0 (:from-pos response)))
      (is (= 3 (:to-pos response)))))
  (testing "sync-request from middle position returns remaining"
    (let [local (make-stream-with [:d1] [:d2] [:d3])
          state (link/make-link-state local)
          msg (link/sync-request-msg 1)
          [_ response] (link/handle-sync-request state msg)]
      (is (= [[:d2] [:d3]] (:datoms response)))
      (is (= 1 (:from-pos response)))
      (is (= 3 (:to-pos response)))))
  (testing "sync-request on empty stream returns empty"
    (let [local (make-stream)
          state (link/make-link-state local)
          [_ response] (link/handle-sync-request state
                                                 (link/sync-request-msg 0))]
      (is (= [] (:datoms response)))
      (is (= 0 (:to-pos response))))))


;; =============================================================================
;; Handle Sync Response Tests
;; =============================================================================

(deftest handle-sync-response-test
  (testing "sync-response appends datoms and updates status"
    (let [local (make-stream)
          state (link/make-link-state local)
          msg (link/sync-response-msg [[:d1] [:d2]] 0 2)
          state' (link/handle-sync-response state msg)]
      (is (= :connected (:status state')))
      (is (= 2 (:remote-pos state')))
      (is (= 2 (ds/length (:remote-stream state'))))
      (is (= [[:d1] [:d2]] (stream->vec (:remote-stream state')))))))


;; =============================================================================
;; Handle Close Tests
;; =============================================================================

(deftest handle-close-test
  (testing "handle-close closes remote-stream"
    (let [local (make-stream)
          state (link/make-link-state local)
          state' (link/handle-close state)]
      (is (ds/closed? (:remote-stream state')))
      (is (= :closed (:status state'))))))


;; =============================================================================
;; Local Put Tests
;; =============================================================================

(deftest local-put-test
  (testing "local-put appends to local-stream and produces put-msg"
    (let [local (make-stream)
          state (link/make-link-state local)
          [state' msg] (link/local-put state [:e :a :v 0 0])]
      (is (= 1 (ds/length (:local-stream state'))))
      (is (= 1 (:local-pos state')))
      (is (= :datom/put (:type msg)))
      (is (= [[:e :a :v 0 0]] (:datoms msg)))
      (is (= 0 (:from-pos msg)))))
  (testing "local-put increments local-pos"
    (let [local (make-stream)
          state (link/make-link-state local)
          [state' _] (link/local-put state [:d1])
          [state'' msg] (link/local-put state' [:d2])]
      (is (= 2 (:local-pos state'')))
      (is (= 1 (:from-pos msg))))))


;; =============================================================================
;; Dispatch Tests
;; =============================================================================

(deftest dispatch-test
  (testing "dispatch routes to correct handler"
    (let [local (make-stream)
          state (link/make-link-state local)]
      (testing "put dispatch"
        (let [[state' response] (link/dispatch state (link/put-msg [[:d1]] 0))]
          (is (= 1 (ds/length (:remote-stream state'))))
          (is (nil? response))))
      (testing "sync-request dispatch"
        (let [[_ response] (link/dispatch state (link/sync-request-msg 0))]
          (is (= :datom/sync-response (:type response)))))
      (testing "sync-response dispatch"
        (let [[state' response]
              (link/dispatch state (link/sync-response-msg [[:d1]] 0 1))]
          (is (= :connected (:status state')))
          (is (nil? response))))
      (testing "close dispatch"
        (let [[state' response] (link/dispatch state (link/close-msg))]
          (is (= :closed (:status state')))
          (is (nil? response)))))))


;; =============================================================================
;; Connect Message Tests
;; =============================================================================

(deftest connect-msg-test
  (testing "connect-msg produces sync-request from remote-pos"
    (let [local (make-stream)
          state (link/make-link-state local)
          msg (link/connect-msg state)]
      (is (= :datom/sync-request (:type msg)))
      (is (= 0 (:from-pos msg))))))


;; =============================================================================
;; Two-Peer Simulation (pure, no WebSocket)
;; =============================================================================

(deftest two-peer-simulation-test
  (testing "Two peers exchange datoms through pure message passing"
    (let [;; Peer A has datoms already
          local-a (make-stream-with [-1 :yin/type :literal 0 0]
                                    [-1 :yin/value 42 0 0])
          state-a (link/make-link-state local-a)
          ;; Peer B starts empty
          local-b (make-stream)
          state-b (link/make-link-state local-b)
          ;; Both send connect messages
          msg-from-a (link/connect-msg state-a)
          msg-from-b (link/connect-msg state-b)
          ;; B handles A's sync-request
          [state-b' resp-b] (link/dispatch state-b msg-from-a)
          ;; A handles B's sync-request
          [state-a' resp-a] (link/dispatch state-a msg-from-b)
          ;; A handles B's sync-response (B had nothing)
          [state-a'' _] (link/dispatch state-a' resp-b)
          ;; B handles A's sync-response (A had two datoms)
          [state-b'' _] (link/dispatch state-b' resp-a)]
      ;; A's remote-stream should be empty (B had nothing)
      (is (= 0 (ds/length (:remote-stream state-a''))))
      (is (= :connected (:status state-a'')))
      ;; B's remote-stream should have A's two datoms
      (is (= 2 (ds/length (:remote-stream state-b''))))
      (is (= :connected (:status state-b'')))
      (is (= [-1 :yin/type :literal 0 0]
             (first (stream->vec (:remote-stream state-b'')))))
      (is (= [-1 :yin/value 42 0 0]
             (second (stream->vec (:remote-stream state-b''))))))))


;; =============================================================================
;; Transit Round-Trip Tests
;; =============================================================================

(deftest transit-round-trip-test
  (testing "Messages survive transit encode/decode"
    (let [msgs [(link/put-msg [[-1 :yin/type :literal 0 0]] 0)
                (link/sync-request-msg 5)
                (link/sync-response-msg [[:d1] [:d2]] 0 2) (link/close-msg)]]
      (doseq [msg msgs]
        (is (= msg (transit/decode (transit/encode msg)))
            (str "Round-trip failed for " (:type msg)))))))
