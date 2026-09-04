(ns yin.repl.v2-connect-test
  "Phase R3 — the client side, with the host socket injected.

   No test here opens a socket.  `:connect!` is a deterministic in-process
   adapter: the boundary's own callbacks are driven by hand, which is exactly
   the seam a real host package will fill."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.apply :as apply]
            [dao.stream.v2.transit :as transit]
            [yin.repl.v2.connect :as connect]
            [yin.repl.v2.driver :as driver]))


;; =============================================================================
;; An injected host socket
;; =============================================================================

(defn- host
  "A host adapter whose sockets are captured, not opened.  `:wire` collects one
   boundary adapter per attachment, `:sent` every encoded outbound frame, and
   `:closed` every close code.

   `:full?`, when set, makes an *open* socket refuse the write the way a host
   send buffer under backpressure does: `false` is the host answer the boundary
   classifies as `:dao.stream/full`.  Before the attachment opens the boundary
   answers `full` on its own, so this is the only way to hold a request unsent
   on a connection that can still end as a reattachable drop."
  []
  (let [wire (atom [])
        sent (atom [])
        closed (atom [])
        full? (atom false)]
    {:wire wire
     :sent sent
     :closed closed
     :full? full?
     :adapter {:connect! (fn [_descriptor adapter]
                           (swap! wire conj adapter)
                           {:send! (fn [text]
                                     (if @full?
                                       false
                                       (do (swap! sent conj text) nil)))
                            :close! (fn [code reason]
                                      (swap! closed conj [code reason])
                                      nil)})}}))


(defn- latest-wire
  [h]
  (last @(:wire h)))


(defn- deliver!
  [h value]
  ((:message! (latest-wire h)) (transit/encode {:ws/frame :ws/value :ws/value value})))


(defn- texts
  [state]
  (mapv :yin.repl.v2.driver/text (first (driver/take-outbox state))))


(defn- text-of
  [state]
  (str/join "\n" (texts state)))


(defn- sent-requests
  [h]
  (->> @(:sent h)
       (map transit/decode)
       (filter #(= :ws/value (:ws/frame %)))
       (mapv :ws/value)))


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
  (testing "a query or fragment is not a path"
    (is (= "/repl" (connect/repl-target "?x=1")))
    (is (= "/repl" (connect/repl-target "#y")))
    (is (= "/repl" (:ws/path (connect/descriptor-key
                               (connect/parse-url "ws://h:1?x=1")))))))


(deftest paths-are-canonicalized-to-the-request-target-form
  (testing "dot segments are removed, repeated and trailing slashes are kept"
    (is (= "/repl" (connect/canonical-path "/a/../repl")))
    (is (= "/a/repl" (connect/canonical-path "/a/./repl")))
    (is (= "/a//repl/" (connect/canonical-path "/a//repl/")))
    (is (= "/" (connect/canonical-path "/a/..")))
    (is (= "/repl/" (connect/canonical-path "/repl/."))))
  (testing "unreserved octets decode, other hex is upper-cased, %2F stays encoded"
    (is (= "/repl-x" (connect/canonical-path "/%72epl%2Dx")))
    (is (= "/a%2Fb" (connect/canonical-path "/a%2fb")))
    (is (= "/a%20b" (connect/canonical-path "/a%20b"))))
  (testing "query and fragment are ignored for lookup and absent from the descriptor"
    (is (= "/repl" (connect/canonical-path "/repl?x=1#y")))
    (is (= "/repl" (:ws/path (connect/descriptor-key
                               (connect/parse-url "ws://h:1/repl?x=/y")))))))


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
    (is (= :yin.repl.v2.connect/invalid-url
           (get (connect/parse-url url) connect/outcome-key))
        (str url " must be reported, never guessed at"))))


(deftest connect-without-a-host-package-reports-the-seam
  (let [result (connect/open {:url "daostream:ws://localhost:8080" :host nil})]
    (is (= :yin.repl.v2.connect/no-host-adapter (get result connect/outcome-key)))
    (is (str/includes? (get result connect/message-key) "no host WebSocket package"))))


;; =============================================================================
;; The boundary
;; =============================================================================

(deftest the-medium-and-both-cursors-exist-before-attach
  (let [h (host)
        result (connect/open {:url "daostream:ws://localhost:8080"
                              :host (:adapter h)})
        connection (get result connect/connection-key)]
    (is (= :yin.repl.v2.connect/attached (get result connect/outcome-key)))
    (is (some? (:response-cursor connection)))
    (is (some? (:lifecycle-cursor connection)))
    (is (some? (:attachment connection)))
    (is (= 1 (count @(:wire h))) "attach! composes exactly one boundary")
    (is (= :connecting (:status connection))
        "no host answer can be claimed before one was heard")))


(deftest lifecycle-is-observed-neutrally-and-once
  (let [h (host)
        result (connect/open {:url "daostream:ws://localhost:8080"
                              :host (:adapter h)})
        connection (get result connect/connection-key)]
    ((:opened! (latest-wire h)))
    (let [[connection events] (connect/observe connection)]
      (is (= :established (:status connection)))
      (is (= [:yin.repl.v2.connect/connected]
             (mapv connect/event-key events)))
      (is (str/includes? (connect/text-key (first events)) "Connected to"))
      (testing "a second observation republishes nothing"
        (let [[connection' events'] (connect/observe connection)]
          (is (empty? events'))
          (is (= :established (:status connection')))))
      (testing "a dropped connection is reconnectable, an ended stream is not"
        ((:closed! (latest-wire h)) 1000 "peer")
        (let [[connection' events'] (connect/observe connection)]
          (is (= :detached (:status connection')))
          (is (str/includes? (connect/text-key (first events')) "reattaches")))))))


(deftest an-ended-served-stream-is-distinguished-from-a-dropped-connection
  (let [h (host)
        connection (get (connect/open {:url "daostream:ws://localhost:8080"
                                       :host (:adapter h)})
                        connect/connection-key)]
    ((:opened! (latest-wire h)))
    ((:closed! (latest-wire h)) 4000 "dao.stream/ended")
    (let [[connection events] (connect/observe connection)]
      (is (= :ended (:status connection)))
      (is (str/includes? (str/join " " (map connect/text-key events))
                         "nothing to reattach to")))))


(deftest a-terminal-reachability-failure-is-not-overwritten-by-close
  (let [h (host)
        connection (get (connect/open {:url "daostream:ws://localhost:8080"
                                       :host (:adapter h)})
                        connect/connection-key)]
    ;; A socket that closes before the handshake resolves deposits both the
    ;; reachability failure and its close.  The first terminal fact wins.
    ((:closed! (latest-wire h)) 1006 "unreachable")
    (let [[connection events] (connect/observe connection)]
      (is (= :transport-error (:status connection)))
      (is (= [:yin.repl.v2.connect/transport-error]
             (mapv connect/event-key events))
          "the later close cannot rewrite or republish the conclusion"))))


(deftest an-authoritative-disclaimer-is-not-a-reachability-failure
  (let [h (host)
        connection (get (connect/open {:url "daostream:ws://localhost:8080/nope"
                                       :host (:adapter h)})
                        connect/connection-key)]
    ((:disclaimed! (latest-wire h)))
    (let [[connection events] (connect/observe connection)]
      (is (= :not-found (:status connection)))
      (is (str/includes? (str/join " " (map connect/text-key events))
                         "not retried")))))


;; =============================================================================
;; Through the driver: one round trip, disconnect, reattach
;; =============================================================================

(defn- step
  [state now]
  (driver/repl-step state now))


(defn- pump
  "One step, returning `[state lines]` with the outbox consumed exactly once."
  [state now]
  (let [[entries state'] (driver/take-outbox (driver/repl-step state now))]
    [state' (mapv :yin.repl.v2.driver/text entries)]))


(deftest a-round-trip-through-injected-host-functions
  (let [h (host)
        state (driver/create-state {:host (:adapter h)})
        _ (driver/submit-line! (:input state)
                               "(connect \"daostream:ws://localhost:8080\")")
        [state lines] (pump state 0)]
    (is (str/includes? (str/join lines) "Attaching to"))
    (is (some? (:connection state)))

    ;; An establishing attachment answers `full`, so the request is retried with
    ;; its already-allocated id rather than lost or re-minted.
    (driver/submit-line! (:input state) "(+ 1 2)")
    (let [[state _] (pump state 1)
          _ (is (empty? (sent-requests h)))
          _ ((:opened! (latest-wire h)))
          [state lines] (pump state 2)
          requests (sent-requests h)]
      (is (str/includes? (str/join lines) "Connected to"))
      (is (= 1 (count requests)))
      (is (= :op/eval (apply/request-op (first requests))))
      (is (= ["(+ 1 2)"] (apply/request-args (first requests))))

      (deliver! h (apply/success-response (apply/request-id (first requests)) "3"))
      (let [[state lines] (pump state 3)]
        (is (= ["3"] lines))
        (is (empty? (second (pump state 4)))
            "a published completion is never republished")

        (driver/submit-line! (:input state) "(disconnect)")
        (let [[state lines] (pump state 5)]
          (is (str/includes? (str/join lines) "Disconnecting"))
          (is (= [[1000 "dao.stream/detached"]] @(:closed h)))
          ((:closed! (latest-wire h)) 1000 "dao.stream/detached")
          (let [[state lines] (pump state 6)
                medium (:traffic (:connection state))
                attachment (:attachment (:connection state))]
            (is (str/includes? (str/join lines) "Disconnected from"))
            (is (= :detached (:status (:connection state))))

            (driver/submit-line! (:input state)
                                 "(connect \"daostream:ws://localhost:8080\")")
            (let [[state lines] (pump state 7)]
              (is (str/includes? (str/join lines) "Reattaching"))
              (is (identical? medium (:traffic (:connection state)))
                  "one capacity-bounded medium is reused across reconnects")
              (is (= 2 (count @(:wire h))))
              (is (not= attachment (:attachment (:connection state)))
                  "the new attachment id is taken")
              ((:opened! (latest-wire h)))
              (driver/submit-line! (:input state) "(+ 2 2)")
              (let [[state _] (pump state 8)
                    requests (sent-requests h)]
                (is (= 2 (count requests)))
                (is (= 1 (apply/request-id (second requests)))
                    "ids stay monotonic across the reconnect")
                (deliver! h (apply/success-response 1 "4"))
                (is (= ["4"] (second (pump state 9))))))))))))


(deftest evaluation-typed-while-disconnected-is-queued-not-lost
  (let [h (host)
        state (driver/create-state {:host (:adapter h)})]
    (driver/submit-line! (:input state) "(connect \"daostream:ws://localhost:8080\")")
    (let [state (step state 0)
          _ ((:opened! (latest-wire h)))
          state (step state 1)
          _ ((:closed! (latest-wire h)) 1000 "peer")
          state (step state 2)]
      (is (= :detached (:status (:connection state))))
      (driver/submit-line! (:input state) "(+ 1 2)")
      (let [state (step state 3)]
        (is (= ["(+ 1 2)"] (:queued state)))
        (is (str/includes? (text-of state) "queued until"))
        (is (empty? (sent-requests h)))
        (testing "and is sent once the reattachment establishes"
          (driver/submit-line! (:input state)
                               "(connect \"daostream:ws://localhost:8080\")")
          (let [state (step state 4)
                _ ((:opened! (latest-wire h)))
                state (step state 5)]
            (is (= ["(+ 1 2)"] (apply/request-args (first (sent-requests h)))))
            (is (empty? (:queued state)))))))))


(deftest an-operator-disconnect-returns-input-to-local-evaluation
  (let [h (host)
        state (driver/create-state {:host (:adapter h)})]
    (driver/submit-line! (:input state) "(connect \"daostream:ws://localhost:8080\")")
    (let [[state _] (pump state 0)
          _ ((:opened! (latest-wire h)))
          [state _] (pump state 1)]
      (driver/submit-line! (:input state) "(+ 1 2)")
      (driver/submit-line! (:input state) "(+ 2 2)")
      (let [[state _] (pump state 2)]
        (is (= ["(+ 2 2)"] (:queued state)))
        (driver/submit-line! (:input state) "(disconnect)")
        (let [[state lines] (pump state 3)]
          (is (str/includes? (str/join lines) "Disconnecting"))
          (is (empty? (:queued state))
              "input queued for the remote shell is dropped, not carried across")

          (testing "and ordinary input is local again from the next line"
            ;; Local evaluation resumes immediately, before the boundary has
            ;; deposited the terminal fact the operator's close! will produce.
            (driver/submit-line! (:input state) "(+ 3 3)")
            (let [[state lines] (pump state 4)]
              (is (= ["6"] lines))
              (is (empty? (:queued state)))
              (is (= 1 (count (sent-requests h)))
                  "nothing further reached the remote shell")

              ;; The terminal event lands; the shell stays local.
              ((:closed! (latest-wire h)) 1000 "dao.stream/detached")
              (driver/submit-line! (:input state) "(+ 4 4)")
              (let [[state lines] (pump state 5)]
                (is (= :detached (:status (:connection state))))
                (is (some #(= "8" %) lines))
                (is (empty? (:queued state)))
                (is (= 1 (count (sent-requests h))))

                (testing "a reattachment routes it remotely again"
                  (driver/submit-line!
                    (:input state) "(connect \"daostream:ws://localhost:8080\")")
                  (let [[state lines] (pump state 6)]
                    (is (str/includes? (str/join lines) "Reattaching"))
                    ((:opened! (latest-wire h)))
                    (driver/submit-line! (:input state) "(+ 5 5)")
                    (let [[state _] (pump state 7)]
                      (is (= 2 (count (sent-requests h))))
                      (is (= ["(+ 5 5)"]
                             (apply/request-args (last (sent-requests h)))))
                      (is (empty? (:queued state))))))))))))))


(deftest a-non-reattachable-terminal-does-not-trap-a-later-connect
  (let [h (host)
        state (driver/create-state {:host (:adapter h)})]
    (driver/submit-line! (:input state) "(connect \"daostream:ws://localhost:8080\")")
    (let [[state _] (pump state 0)]
      ;; The endpoint authoritatively disclaims the path: terminal for this
      ;; binding, and nothing a reattachment could revive.
      ((:disclaimed! (latest-wire h)))
      (let [[state lines] (pump state 1)]
        (is (str/includes? (str/join lines) "not retried"))
        (driver/submit-line! (:input state) "(disconnect)")
        (let [[state _] (pump state 2)]
          (is (= :not-found (:status (:connection state)))
              "the terminal outcome survives the operator's own close")

          (testing "and a later (connect …) composes a fresh boundary"
            (driver/submit-line! (:input state)
                                 "(connect \"daostream:ws://localhost:8080\")")
            (let [[state lines] (pump state 3)]
              (is (str/includes? (str/join lines) "Attaching to"))
              (is (= 2 (count @(:wire h))))
              ((:opened! (latest-wire h)))
              (driver/submit-line! (:input state) "(+ 1 2)")
              (let [[state _] (pump state 4)]
                (is (= ["(+ 1 2)"] (apply/request-args (first (sent-requests h)))))
                (is (empty? (:queued state)))))))))))


(deftest an-ended-stream-returns-ordinary-input-to-the-local-shell
  (let [h (host)
        state (driver/create-state {:host (:adapter h)})]
    (driver/submit-line! (:input state) "(connect \"daostream:ws://localhost:8080\")")
    (let [[state _] (pump state 0)
          _ ((:opened! (latest-wire h)))
          [state _] (pump state 1)
          _ ((:closed! (latest-wire h)) 4000 "dao.stream/ended")
          [state lines] (pump state 2)]
      (is (str/includes? (str/join lines) "nothing to reattach to"))
      (driver/submit-line! (:input state) "(+ 1 2)")
      (let [[state lines] (pump state 3)]
        (is (= ["3"] lines)
            "queueing for a reattachment that cannot happen would trap the line")
        (is (empty? (:queued state)))
        (is (empty? (sent-requests h)))))))


(deftest a-transport-error-returns-ordinary-input-to-the-local-shell
  (let [h (host)
        state (driver/create-state {:host (:adapter h)})]
    (driver/submit-line! (:input state) "(connect \"daostream:ws://localhost:8080\")")
    (let [[state _] (pump state 0)]
      ((:closed! (latest-wire h)) 1006 "unreachable")
      (let [[state lines] (pump state 1)]
        (is (= :transport-error (:status (:connection state))))
        (is (str/includes? (str/join lines) "reachability failure"))
        (driver/submit-line! (:input state) "(+ 1 2)")
        (let [[state lines] (pump state 2)]
          (is (= ["3"] lines)
              "a fresh connection cannot inherit queued work, so input is local")
          (is (empty? (:queued state)))
          (is (empty? (sent-requests h))))))))


(deftest a-line-typed-while-a-request-is-unsent-is-queued-not-clobbered
  (let [h (host)
        state (driver/create-state {:host (:adapter h)})]
    (driver/submit-line! (:input state) "(connect \"daostream:ws://localhost:8080\")")
    (let [[state _] (pump state 0)]
      ;; Typed before the attachment established: the writer answers `full`, so
      ;; the request is allocated and retained unsent.
      (driver/submit-line! (:input state) "(+ 1 2)")
      (let [[state _] (pump state 1)]
        (is (empty? (sent-requests h)))
        (driver/submit-line! (:input state) "(+ 2 2)")
        (let [[state _] (pump state 2)]
          (is (= ["(+ 2 2)"] (:queued state))
              "an unsent request owns the request path until it is sent or abandoned")
          ((:opened! (latest-wire h)))
          (let [[state _] (pump state 3)]
            (is (= ["(+ 1 2)"] (apply/request-args (last (sent-requests h)))))
            (deliver! h (apply/success-response 0 "3"))
            (let [[state lines] (pump state 4)]
              (is (= ["3"] lines))
              (is (= ["(+ 2 2)"] (apply/request-args (last (sent-requests h)))))
              (is (empty? (:queued state))))))))))


(defn- backpressured
  "A connected shell holding one request the socket refused to take."
  [now]
  (let [h (host)
        state (driver/create-state {:host (:adapter h)})]
    (driver/submit-line! (:input state) "(connect \"daostream:ws://localhost:8080\")")
    (let [[state _] (pump state now)
          _ ((:opened! (latest-wire h)))
          [state _] (pump state (inc now))]
      (reset! (:full? h) true)
      (driver/submit-line! (:input state) "(+ 1 2)")
      (let [[state _] (pump state (+ now 2))]
        (is (empty? (sent-requests h))
            "allocated against an open socket that would not take it")
        [h state]))))


(deftest an-unsent-request-is-abandoned-on-disconnect-rather-than-resent
  (let [[h state] (backpressured 0)]
    (driver/submit-line! (:input state) "(disconnect)")
    (let [[state _] (pump state 3)]
      (reset! (:full? h) false)
      ((:closed! (latest-wire h)) 1000 "dao.stream/detached")
      (let [[state lines] (pump state 4)]
        (is (str/includes? (str/join lines) "lost")
            "the operator is told the line never went out, not silently robbed of it")
        (is (str/includes? (str/join lines) "operator-disconnect"))

        (testing "and a reattachment sends the next typed line, not the stale one"
          (driver/submit-line! (:input state)
                               "(connect \"daostream:ws://localhost:8080\")")
          (let [[state lines] (pump state 5)]
            (is (str/includes? (str/join lines) "Reattaching"))
            ((:opened! (latest-wire h)))
            (driver/submit-line! (:input state) "(+ 2 2)")
            (let [[state _] (pump state 6)
                  requests (sent-requests h)]
              (is (= 1 (count requests)))
              (is (= ["(+ 2 2)"] (apply/request-args (first requests))))
              (is (= 1 (apply/request-id (first requests)))
                  "the abandoned id is retired, never reused")
              (is (empty? (:queued state))))))))))


(deftest an-unsent-request-does-not-survive-an-uninvited-drop-and-reattach
  (let [[h state] (backpressured 0)]
    ;; Nobody asked: the peer drops the connection while the request is held.
    ((:closed! (latest-wire h)) 1000 "dao.stream/detached")
    (reset! (:full? h) false)
    (let [[state lines] (pump state 3)]
      (is (str/includes? (str/join lines) "lost")
          "the retry meets a closed writer, and the line is reported undeliverable")
      (is (str/includes? (str/join lines) "reattaches"))
      (driver/submit-line! (:input state)
                           "(connect \"daostream:ws://localhost:8080\")")
      (let [[state lines] (pump state 4)]
        (is (str/includes? (str/join lines) "Reattaching"))
        ((:opened! (latest-wire h)))
        (driver/submit-line! (:input state) "(+ 2 2)")
        (let [[state _] (pump state 5)
              requests (sent-requests h)]
          (is (= ["(+ 2 2)"] (apply/request-args (first requests)))
              "the new binding says what the operator typed, not what died with the old one")
          (is (= 1 (count requests)))
          (is (empty? (:queued state))))))))


(deftest a-fresh-connection-reports-an-old-unsent-request-before-replacing-its-adapter
  (let [[h state] (backpressured 0)
        ;; This is the defensive host seam Fable identified: a terminal writer
        ;; that continues to answer `full`, so the old RPC state still owns an
        ;; unsent envelope when a fresh connection is requested.
        state (assoc-in state
                        [:adapter :yin.repl.v2.adapter/rpc :terminal]
                        :dao.stream.v2.apply/transport-error)]
    (reset! (:full? h) false)
    (driver/submit-line! (:input state)
                         "(connect \"daostream:ws://localhost:9999\")")
    (let [[state lines] (pump state 3)]
      (is (= 2 (count @(:wire h))))
      (is (str/includes? (str/join lines) "abandoned-on-fresh-connection")
          "the old request is published before the old adapter is replaced")
      ((:opened! (latest-wire h)))
      (driver/submit-line! (:input state) "(+ 2 2)")
      (let [[state _] (pump state 4)
            requests (sent-requests h)]
        (is (= 1 (count requests)))
        (is (= ["(+ 2 2)"] (apply/request-args (first requests)))
            "the new binding sends only the newly typed line")
        (is (empty? (:queued state)))))))


(deftest a-fresh-connection-publishes-an-old-completion-with-no-unsent-envelope
  (let [[h state] (backpressured 0)
        ;; The terminal permits a fresh connection. `disconnect` below then
        ;; creates the completion *after* this tick's remote poll; the following
        ;; `connect` must publish it before replacing the old adapter.
        state (assoc-in state
                        [:adapter :yin.repl.v2.adapter/rpc :terminal]
                        :dao.stream.v2.apply/transport-error)]
    (reset! (:full? h) false)
    (driver/submit-line! (:input state) "(disconnect)")
    (driver/submit-line! (:input state)
                         "(connect \"daostream:ws://localhost:9999\")")
    (let [[_state lines] (pump state 3)]
      (is (= 2 (count @(:wire h))))
      (is (str/includes? (str/join lines) "operator-disconnect")
          "adapter replacement flushes completions even when :unsent is nil"))))


(deftest changing-url-reports-lines-dropped-from-a-detached-binding
  (let [h (host)
        state (driver/create-state {:host (:adapter h)})]
    (driver/submit-line! (:input state) "(connect \"daostream:ws://localhost:8080\")")
    (let [[state _] (pump state 0)
          _ ((:opened! (latest-wire h)))
          [state _] (pump state 1)
          _ ((:closed! (latest-wire h)) 1000 "peer")
          [state _] (pump state 2)]
      (driver/submit-line! (:input state) "(+ 1 2)")
      (let [[state _] (pump state 3)]
        (is (= ["(+ 1 2)"] (:queued state)))
        (driver/submit-line! (:input state)
                             "(connect \"daostream:ws://localhost:9999\")")
        (let [[state lines] (pump state 4)]
          (is (= 2 (count @(:wire h))))
          (is (str/includes? (str/join lines) "Discarded 1 queued remote line"))
          (is (empty? (:queued state))))))))


(deftest local-commands-still-answer-while-connected
  (let [h (host)
        state (driver/create-state {:host (:adapter h)})]
    (driver/submit-line! (:input state) "(connect \"daostream:ws://localhost:8080\")")
    (let [state (step state 0)
          _ ((:opened! (latest-wire h)))
          state (step state 1)]
      (driver/submit-line! (:input state) "(repl-state)")
      (let [state (step state 2)
            text (text-of state)]
        (is (str/includes? text "daostream:ws://localhost:8080")
            "(repl-state) reports the connection the driver owns")
        (is (str/includes? text "/repl"))
        (is (empty? (sent-requests h))
            "a local command sends nothing to the remote")))))


(deftest connecting-twice-without-disconnecting-is-refused
  (let [h (host)
        state (driver/create-state {:host (:adapter h)})]
    (driver/submit-line! (:input state) "(connect \"daostream:ws://localhost:8080\")")
    (let [state (step state 0)]
      (driver/submit-line! (:input state) "(connect \"daostream:ws://localhost:9999\")")
      (let [state (step state 1)]
        (is (str/includes? (text-of state) "Already connected"))
        (is (= 1 (count @(:wire h))))))))


(deftest a-connection-summary-is-plain-data
  (let [h (host)
        connection (get (connect/open {:url "daostream:ws://localhost:8080"
                                       :host (:adapter h)})
                        connect/connection-key)
        summary (connect/summary connection)]
    (is (false? (:connected? summary)))
    (is (= "/repl" (:path summary)))
    (is (= :connecting (:status summary)))
    (is (= :untried (:last-outcome summary)))
    (is (nil? (:handle summary)) "no host object appears in the summary")))


(deftest the-traffic-medium-is-total-over-next
  (let [h (host)
        connection (get (connect/open {:url "daostream:ws://localhost:8080"
                                       :host (:adapter h)})
                        connect/connection-key)]
    (stream/close! (:traffic connection))
    (let [[connection events] (connect/observe connection)]
      (is (= :dao.stream/end (:ledger connection)))
      (is (str/includes? (connect/text-key (first events)) "end")))))
