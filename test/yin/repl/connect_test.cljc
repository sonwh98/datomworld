(ns yin.repl.connect-test
  "The client side, over the request-and-response service of
   docs/design/dao.stream.remote.md section 5.

   URL canonicalization is pure and needs no host at all.  The
   composition-shape tests use a fake `:connect!` whose socket is captured,
   never opened, to prove `open` attaches both reflections and returns
   immediately.  Reattachment, an absent entry, and a connection that never
   opens run over the in-process loopback net against a
   `dao.stream.remote-channel` server of the REPL's two-entry table, so
   every host runs them; the demo REPL flow over a real wire is proven end
   to end in yin.repl.serve-connect-wire-test (JVM), and the
   reflection-read terminal translation directly in dao.stream.rpc-test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.loopback-net :as net]
            [dao.stream.remote-channel :as remote-channel]
            [dao.stream.ringbuffer :as ringbuffer]
            [dao.stream.rpc :as rpc]
            [yin.repl.connect :as connect]))


;; =============================================================================
;; URL normalization
;; =============================================================================

(defn- parsed-spec
  [url]
  (connect/spec-key (connect/parse-url url)))


(deftest an-absent-path-names-the-repl-and-an-explicit-slash-does-not
  (is (= "/repl" (:path (parsed-spec "daostream:ws://localhost:8080"))))
  (is (= "/" (:path (parsed-spec "daostream:ws://localhost:8080/"))))
  (is (= "/repl" (connect/repl-target "")))
  (is (= "/" (connect/repl-target "/")))
  (is (= "/repl" (connect/repl-target "?x=1")))
  (is (= "/repl" (connect/repl-target "#y")))
  (is (= "/repl" (:path (parsed-spec "ws://h:1?x=1")))))


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
  (is (= "/repl" (:path (parsed-spec "ws://h:1/repl?x=/y")))))


(deftest a-spec-carries-reachability-and-no-transport-key
  (let [spec (parsed-spec "daostream:ws://example.org:9090/x")]
    (is (= {:host "example.org" :port 9090 :path "/x"} spec))
    (is (not-any? namespace (keys spec)) "a portable spec, no transport key")
    (is (= connect/default-port (:port (parsed-spec "ws://example.org"))))))


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
  "A `:connect!` seam whose socket is captured, not opened: a real ws
   handle composes over it, exactly the seam a host package fills, but
   nothing here ever completes the handshake.  Good enough to prove
   `open`'s composition shape."
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
                              :host (:adapter h) :now 0})
        connection (get result connect/connection-key)
        client (get result connect/client-key)]
    (is (= :yin.repl.connect/attached (get result connect/outcome-key)))
    (is (= :established (:status connection)))
    (is (= :attached (:status (:dial connection))))
    (is (stream/writer? (:writer client))
        "the requests reflection is the client's writer")
    (is (stream/reader? (:reader client))
        "the answers reflection is the client's reader")
    (is (= :dao.stream/newest (:cursor client))
        "the answers cursor is the Linda mint, resolved by a later poll!")))


(deftest close-ends-the-dialed-channel-and-records-who-asked
  (let [h (fake-host)
        result (connect/open {:url "daostream:ws://localhost:8080"
                              :host (:adapter h) :now 0})
        connection (get result connect/connection-key)
        closed (connect/close! connection)]
    (is (= :closing (:status closed)))
    (is (true? (connect/operator-detached? closed)))
    (is (= :attached (:status (:dial closed)))
        "detached, not closed: the loss is still to be observed")
    (is (true? (:detaching? (:dial closed))))
    (is (= 1 (count @(:closed h))))))


(deftest a-connection-summary-is-plain-data
  (let [h (fake-host)
        connection (get (connect/open {:url "daostream:ws://localhost:8080"
                                       :host (:adapter h) :now 0})
                        connect/connection-key)
        summary (connect/summary connection)]
    (is (true? (:connected? summary)))
    (is (= "/repl" (:path summary)))
    (is (= :established (:status summary)))
    (is (= :attached (:dial-status summary)))
    (is (string? (:attachment summary)))
    (is (nil? (:handle summary)) "no host object appears in the summary")))


;; =============================================================================
;; Over the loopback net, against the REPL's two-entry table
;; =============================================================================

(def ^:private url "daostream:ws://127.0.0.1:9")


(defn- ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key capacity})))


(defn- host-of
  [lnet]
  {:connect! (net/connect-on lnet)
   :bind! (net/listen-on lnet)
   :unbind! (net/unbind-on lnet)})


(defn- world
  "A served requests ring (#{:writer}) and answers ring (#{:reader}),
   or only the requests entry when `answers?` is false."
  ([] (world true))
  ([answers?]
   (let [lnet (net/loopback-net)
         requests (ring 64)
         answers (ring 64)]
     {:net lnet :requests requests :answers answers
      :server (atom (remote-channel/serve
                      {:spec {:host "127.0.0.1" :port 9 :path "/repl"}
                       :host (host-of lnet)
                       :table (cond-> {connect/requests-identity
                                       {:handle requests :surface #{:writer}}}
                                answers?
                                (assoc connect/answers-identity
                                       {:handle answers :surface #{:reader}}))}))
      :connection (atom nil)
      :client (atom nil)})))


(defn- open!
  [w now]
  (let [result (connect/open {:url url :host (host-of (:net w)) :now now})]
    (reset! (:connection w) (get result connect/connection-key))
    (reset! (:client w) (get result connect/client-key))
    result))


(defn- tick!
  "One driver turn at `now`: the net, the server, the connection, the
   net, then one poll of the RPC client."
  [{:keys [net server connection client]} now]
  (net/pump! net)
  (when @server (swap! server remote-channel/serve-step now))
  (swap! connection connect/step! now)
  (net/pump! net)
  (swap! client #(:dao.stream.rpc/state (rpc/poll! %))))


(defn- tick-until!
  [w now done?]
  (loop [now now left 50]
    (tick! w now)
    (if (or (done?) (zero? left))
      now
      (recur (inc now) (dec left)))))


(defn- values
  [h]
  (loop [c (:dao.stream/cursor (stream/cursor h stream/anchor-oldest))
         acc []]
    (let [r (stream/next h c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(deftest a-detached-connection-reattaches-with-a-fresh-attachment-and-the-same-cursor
  (let [w (world)
        _ (open! w 0)
        now (tick-until! w 0 #(not (rpc/cursor-pending? @(:client w))))
        _ (is (not (rpc/cursor-pending? @(:client w))) "the cursor is minted")
        cursor (:cursor @(:client w))
        before (:attachment (connect/summary @(:connection w)))]
    (swap! (:connection w) connect/close!)
    (let [now (tick-until! w (inc now)
                           #(= :dao.stream.rpc/detached (:terminal @(:client w))))
          _ (is (= :dao.stream.rpc/detached (:terminal @(:client w))))
          result (connect/reattach @(:connection w) @(:client w) now)]
      (is (= :yin.repl.connect/reattached (get result connect/outcome-key)))
      (reset! (:connection w) (get result connect/connection-key))
      (reset! (:client w) (get result connect/client-key))
      (is (= :established (:status @(:connection w))))
      (is (nil? (:detached-by @(:connection w))))
      (is (not= before (:attachment (connect/summary @(:connection w))))
          "a fresh attachment")
      (is (= cursor (:cursor @(:client w))) "the same response cursor")
      (testing "a round trip succeeds over the fresh pair"
        (let [requested (loop [now (inc now) left 50]
                          (let [r (rpc/request! @(:client w) :op/eval ["(+ 1 2)"])]
                            (reset! (:client w) (:dao.stream.rpc/state r))
                            (if (and (pos? left)
                                     (= :dao.stream.rpc/pending-request
                                        (:dao.stream.rpc/outcome r)))
                              (do (tick! w now) (recur (inc now) (dec left)))
                              r)))
              id (:dao.stream.rpc/id requested)]
          (is (= :dao.stream.rpc/requested (:dao.stream.rpc/outcome requested)))
          (tick-until! w (+ now 100) #(seq (values (:requests w))))
          (is (= [id] (mapv rpc/request-id (values (:requests w)))))
          (stream/append! (:answers w) (rpc/success-answer id "3"))
          (tick-until! w (+ now 200) #(seq (:completed @(:client w))))
          (is (= [(rpc/success-answer id "3")]
                 (mapv :dao.stream.rpc/response (:completed @(:client w))))))))))


(deftest an-absent-answers-entry-is-not-found
  (let [w (world false)]
    (open! w 0)
    (tick-until! w 0 #(:terminal @(:client w)))
    (is (= :dao.stream.rpc/not-found (:terminal @(:client w))))
    (let [[connection event] (connect/observe-terminal
                               @(:connection w) (:terminal @(:client w)))]
      (is (= :not-found (:status connection)))
      (is (str/includes? (connect/text-key event) "not retried"))
      (is (str/includes? (connect/text-key event) "/repl")))))


(def ^:private give-up-after
  (:dao.stream.remote/give-up-after remote-channel/production-bounds))


(defn- unserved
  "A world with no server and nothing listening."
  []
  (let [w (assoc (world) :server (atom nil))]
    (swap! (:net w) update :listeners dissoc 9)
    w))


(defn- never-opening
  "A world whose listener accepts and never acknowledges."
  []
  (let [w (unserved)]
    (swap! (:net w) assoc-in [:listeners 9]
           {:accept! (fn [& _] {}) :deposit! (fn [& _] nil)})
    w))


(deftest a-connection-that-never-opens-is-detached-at-give-up-after
  (let [w (never-opening)
        result (open! w 1000)]
    (is (= :yin.repl.connect/attached (get result connect/outcome-key))
        "established: the deferred confirmation")
    (is (= :dao.stream.rpc/cursor-pending
           (:dao.stream.rpc/outcome (rpc/request! @(:client w) :op/eval ["1"]))))
    (tick! w 1000)
    (is (nil? (:terminal @(:client w))) "poll! stays idle")
    (tick! w (+ 1000 give-up-after -1))
    (is (= :attached (:status (:dial @(:connection w)))) "not before the deadline")
    (is (nil? (:terminal @(:client w))))
    (tick! w (+ 1000 give-up-after))
    (is (= :lost (:status (:dial @(:connection w)))))
    (is (= :dao.stream.remote/channel-gone
           (:dao.stream.remote/reason (:outcome (:dial @(:connection w))))))
    (is (= :dao.stream.rpc/detached (:terminal @(:client w))))
    (is (= :detached (:status (first (connect/observe-terminal
                                       @(:connection w)
                                       (:terminal @(:client w))))))))
  (testing "a refused connection is detached within two ticks, no deadline"
    (let [w (unserved)]
      (open! w 1000)
      (tick! w 1000)
      (tick! w 1001)
      (is (= :lost (:status (:dial @(:connection w)))))
      (is (= :dao.stream.rpc/detached (:terminal @(:client w)))))))


(deftest open-without-now-never-expires
  (let [w (never-opening)
        result (connect/open {:url url :host (host-of (:net w))})]
    (reset! (:connection w) (get result connect/connection-key))
    (reset! (:client w) (get result connect/client-key))
    (tick! w 0)
    (tick! w (* 10 give-up-after))
    (is (= :attached (:status (:dial @(:connection w)))))
    (is (nil? (:terminal @(:client w))) "idle, never expired")))


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
