(ns yin.repl.connect-test
  "The client side, over the request-and-response service of
   docs/design/dao.stream.remote.md section 5.

   URL canonicalization is pure and needs no host at all.  The
   composition tests below use a fake `:connect!` -- a real
   `dao.stream.ws.WsHandle` over a captured socket, exactly the seam a
   real host package fills -- to prove `open` attaches both reflections
   and returns immediately; the demo REPL flow over a real wire, and
   reattachment, are proven end to end in
   yin.repl.serve-connect-wire-test (JVM), and the reflection-read
   terminal translation is proven directly in dao.stream.rpc-test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dao.stream :as stream]
            [yin.repl.connect :as connect]))


;; =============================================================================
;; URL normalization
;; =============================================================================

(deftest an-absent-path-names-the-repl-and-an-explicit-slash-does-not
  (is (= "/repl" (:ws/path (connect/descriptor-key
                             (connect/parse-url "daostream:ws://localhost:8080")))))
  (is (= "/" (:ws/path (connect/descriptor-key
                         (connect/parse-url "daostream:ws://localhost:8080/")))))
  (is (= "/repl" (connect/repl-target "")))
  (is (= "/" (connect/repl-target "/")))
  (is (= "/repl" (connect/repl-target "?x=1")))
  (is (= "/repl" (connect/repl-target "#y")))
  (is (= "/repl" (:ws/path (connect/descriptor-key
                             (connect/parse-url "ws://h:1?x=1"))))))


(deftest paths-are-canonicalized-to-the-request-target-form
  (is (= "/repl" (connect/canonical-path "/a/../repl")))
  (is (= "/a/repl" (connect/canonical-path "/a/./repl")))
  (is (= "/a//repl/" (connect/canonical-path "/a//repl/")))
  (is (= "/" (connect/canonical-path "/a/..")))
  (is (= "/repl/" (connect/canonical-path "/repl/.")))
  (is (= "/repl-x" (connect/canonical-path "/%72epl%2Dx")))
  (is (= "/a%2Fb" (connect/canonical-path "/a%2fb")))
  (is (= "/a%20b" (connect/canonical-path "/a%20b")))
  (is (= "/repl" (connect/canonical-path "/repl?x=1#y")))
  (is (= "/repl" (:ws/path (connect/descriptor-key
                             (connect/parse-url "ws://h:1/repl?x=/y"))))))


(deftest a-descriptor-carries-reachability-and-one-logical-identity
  (let [d (connect/descriptor-key (connect/parse-url "daostream:ws://example.org:9090/x"))]
    (is (= :dao.stream/ws (:dao.stream/type d)))
    (is (= "example.org" (:ws/host d)))
    (is (= 9090 (:ws/port d)))
    (is (= connect/service-identity (:dao.stream/identity d)))
    (is (= connect/default-port
           (:ws/port (connect/descriptor-key (connect/parse-url "ws://example.org")))))))


(deftest an-unusable-url-is-answered-as-data
  (doseq [url ["http://localhost:8080" "daostream:wss://localhost:8080"
               "ws://localhost:0" "ws://localhost:x" "ws://" "ws://[::1]:9"]]
    (is (= :yin.repl.connect/invalid-url
           (get (connect/parse-url url) connect/outcome-key))
        (str url " must be reported, never guessed at"))))


(deftest connect-without-a-host-package-reports-the-seam
  (let [result (connect/open {:url "daostream:ws://localhost:8080" :host nil})]
    (is (= :yin.repl.connect/no-host-adapter (get result connect/outcome-key)))
    (is (str/includes? (get result connect/message-key) "no host WebSocket package"))))


;; =============================================================================
;; The two-reflection boundary
;; =============================================================================

(defn- fake-host
  "A `:connect!` seam whose socket is captured, not opened: a real
   `dao.stream.ws.WsHandle` composes over it, exactly the seam a host
   package fills, but nothing here ever completes the handshake.  Good
   enough to prove `open`'s composition shape; the demo REPL flow over a
   real wire is proven in yin.repl.serve-connect-wire-test."
  []
  (let [sent (atom [])
        closed (atom [])]
    {:sent sent
     :closed closed
     :adapter {:connect! (fn [_descriptor _adapter]
                           {:send! (fn [text] (swap! sent conj text) nil)
                            :close! (fn [code reason]
                                      (swap! closed conj [code reason])
                                      nil)})}}))


(deftest open-attaches-both-reflections-and-reports-established-at-once
  (let [h (fake-host)
        result (connect/open {:url "daostream:ws://localhost:8080"
                              :host (:adapter h)})
        connection (get result connect/connection-key)
        client (get result connect/client-key)]
    (is (= :yin.repl.connect/attached (get result connect/outcome-key)))
    (is (= :established (:status connection)))
    (is (some? (:dial connection)))
    (is (stream/writer? (:writer client))
        "the requests reflection is the client's writer")
    (is (stream/reader? (:reader client))
        "the answers reflection is the client's reader")
    (is (= :dao.stream/newest (:cursor client))
        "the answers cursor is the Linda mint, resolved by a later poll!")))


(deftest close-ends-the-dialed-channel-and-records-who-asked
  (let [h (fake-host)
        result (connect/open {:url "daostream:ws://localhost:8080"
                              :host (:adapter h)})
        connection (get result connect/connection-key)
        closed (connect/close! connection)]
    (is (= :closing (:status closed)))
    (is (true? (connect/operator-detached? closed)))
    (is (= 1 (count @(:closed h))))))


(deftest a-connection-summary-is-plain-data
  (let [h (fake-host)
        connection (get (connect/open {:url "daostream:ws://localhost:8080"
                                       :host (:adapter h)})
                        connect/connection-key)
        summary (connect/summary connection)]
    (is (true? (:connected? summary)))
    (is (= "/repl" (:path summary)))
    (is (= :established (:status summary)))
    (is (nil? (:handle summary)) "no host object appears in the summary")))


;; =============================================================================
;; Terminal status, observed from the RPC client
;; =============================================================================

(deftest observe-terminal-publishes-the-first-terminal-fact-once
  (let [h (fake-host)
        connection (get (connect/open {:url "daostream:ws://localhost:8080"
                                       :host (:adapter h)})
                        connect/connection-key)
        [detached event] (connect/observe-terminal
                           connection :dao.stream.rpc/detached)]
    (is (= :detached (:status detached)))
    (is (= :yin.repl.connect/detached (connect/event-key event)))
    (is (str/includes? (connect/text-key event) "reattaches"))
    (let [[again nothing] (connect/observe-terminal
                            detached :dao.stream.rpc/detached)]
      (is (= detached again))
      (is (nil? nothing) "a terminal status is not republished"))))


(deftest every-terminal-reason-maps-to-its-own-status-and-notice
  (let [h (fake-host)
        base (get (connect/open {:url "daostream:ws://localhost:8080"
                                 :host (:adapter h)})
                  connect/connection-key)]
    (doseq [[reason status fragment]
            [[:dao.stream.rpc/ended :ended "nothing to reattach"]
             [:dao.stream.rpc/not-found :not-found "not retried"]
             [:dao.stream.rpc/transport-error :transport-error
              "reachability failure"]]]
      (let [[connection event] (connect/observe-terminal base reason)]
        (is (= status (:status connection)) reason)
        (is (str/includes? (connect/text-key event) fragment) reason)))))


(deftest reattachable-is-true-for-a-detached-client-only
  (is (true? (connect/reattachable? {:terminal :dao.stream.rpc/detached})))
  (is (false? (connect/reattachable? {:terminal :dao.stream.rpc/ended})))
  (is (false? (connect/reattachable? {:terminal nil}))))
