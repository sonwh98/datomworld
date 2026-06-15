(ns dao.stream.whatsapp-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.ringbuffer]
    [dao.stream.whatsapp :as wa]
    #?@(:cljd []
        :cljs []
        :clj [[clojure.data.json :as json]
              [org.httpkit.server :as http-server]])))


;; =============================================================================
;; 1. Pure core (no network) — cross-platform
;; =============================================================================

(deftest outgoing->command-test
  (testing "text message normalizes :to to a JID and carries the body"
    (is (= {"cmd" "send", "to" "15551234567@s.whatsapp.net", "text" "hi"}
           (wa/outgoing->command {:to "15551234567", :text "hi"}))))
  (testing "a bare + and punctuation in the number are stripped"
    (is (= "15551234567@s.whatsapp.net"
           (get (wa/outgoing->command {:to "+1 (555) 123-4567", :text "x"})
                "to"))))
  (testing "an already-qualified JID passes through untouched"
    (is (= "12345@g.us"
           (get (wa/outgoing->command {:to "12345@g.us", :text "x"}) "to"))))
  (testing ":raw passes through under \"raw\" and suppresses :text"
    (let [cmd (wa/outgoing->command
                {:to "15551234567", :raw {"foo" "bar"}, :text "ignored"})]
      (is (= {"foo" "bar"} (get cmd "raw")))
      (is (not (contains? cmd "text"))))))


(deftest event->datom-test
  (testing "message event → normalized inbound datom"
    (is (= {:whatsapp/from "15551112222@s.whatsapp.net",
            :whatsapp/id "ABC",
            :whatsapp/timestamp "1700000000",
            :whatsapp/type :text,
            :whatsapp/raw {"from" "15551112222@s.whatsapp.net",
                           "id" "ABC",
                           "timestamp" "1700000000",
                           "type" "text",
                           "text" "hello"},
            :whatsapp/text "hello"}
           (wa/event->datom {"event" "message",
                             "data" {"from" "15551112222@s.whatsapp.net",
                                     "id" "ABC",
                                     "timestamp" "1700000000",
                                     "type" "text",
                                     "text" "hello"}}))))
  (testing "ack event → fire-and-forget ack datom"
    (is (= {:whatsapp/ack {:id "ABC", :status "sent"}}
           (wa/event->datom {"event" "ack",
                             "data" {"id" "ABC", "status" "sent"}}))))
  (testing "qr event → qr datom carrying the pairing string"
    (is (= {:whatsapp/qr "QR-STRING"}
           (wa/event->datom {"event" "qr", "data" "QR-STRING"}))))
  (testing "connected / disconnected → status datoms"
    (is (= {:whatsapp/status :connected}
           (wa/event->datom {"event" "connected"})))
    (is (= {:whatsapp/status :disconnected}
           (wa/event->datom {"event" "disconnected", "data" {"reason" 401}}))))
  (testing "unknown / nil events are dropped"
    (is (nil? (wa/event->datom {"event" "typing"})))
    (is (nil? (wa/event->datom nil)))))


;; =============================================================================
;; 2. Stream contract via the inner ringbuffer (no socket)
;; =============================================================================

(deftest stream-contract-test
  (let [inbound (ds/open! {:type :ringbuffer})
        s (wa/->WhatsAppStream inbound (atom nil) (atom :open))]
    (testing "inbound values read in order, non-destructively, via cursor"
      (ds/put! inbound {:whatsapp/from "a", :whatsapp/text "1"})
      (ds/put! inbound {:whatsapp/from "b", :whatsapp/text "2"})
      (let [r0 (ds/next s {:position 0})]
        (is (= "1" (:whatsapp/text (:ok r0))))
        (let [r1 (ds/next s (:cursor r0))]
          (is (= "2" (:whatsapp/text (:ok r1)))))
        ;; non-destructive: re-reading position 0 yields the same value
        (is (= "1" (:whatsapp/text (:ok (ds/next s {:position 0})))))))
    (testing "take!!/drain delegate to the inner ringbuffer"
      #?(:clj (is (= "1" (:whatsapp/text (ds/take!! s)))))
      (is (= "1" (:whatsapp/text (:ok (ds/drain-one! s))))))
    (testing "close!/closed?"
      (is (false? (ds/closed? s)))
      (ds/close! s)
      (is (true? (ds/closed? s))))))


;; =============================================================================
;; 3. Round-trip against a stub gateway (JVM only)
;; =============================================================================

#?(:clj
   (defn- poll-next
     "Block up to ~2s for a value at cursor; returns the next-outcome map or nil."
     [stream cursor]
     (loop [n 0]
       (let [r (ds/next stream cursor)]
         (cond (map? r) r
               (= :end r) nil
               (< n 200) (do (Thread/sleep 10) (recur (inc n)))
               :else nil)))))


#?(:clj (defn- await-true
          [pred]
          (loop [n 0]
            (when (and (not (pred)) (< n 200))
              (Thread/sleep 10)
              (recur (inc n))))))


(deftest gateway-round-trip-test
  #?(:clj
     (let [port 18995
           received (atom [])
           ch-atom (atom nil)
           stop (http-server/run-server
                  (fn [req]
                    (http-server/as-channel
                      req
                      {:on-open (fn [ch] (reset! ch-atom ch)),
                       :on-receive (fn [_ch msg] (swap! received conj msg))}))
                  {:port port})]
       (try
         (let [s (ds/open! {:type :whatsapp,
                            :gateway-url (str "ws://localhost:" port)})]
           (await-true #(wa/connected? s))
           (is (wa/connected? s) "client wired its send-fn on open")
           (testing "put! sends a normalized send command to the gateway"
             (ds/put! s {:to "15551234567", :text "hi there"})
             (await-true #(seq @received))
             (is (= 1 (count @received)))
             (let [cmd (json/read-str (first @received))]
               (is (= "send" (get cmd "cmd")))
               (is (= "15551234567@s.whatsapp.net" (get cmd "to")))
               (is (= "hi there" (get cmd "text")))))
           (testing "inbound message + qr frames surface as datoms in order"
             (http-server/send!
               @ch-atom
               (json/write-str {"event" "message",
                                "data" {"from" "15551112222@s.whatsapp.net",
                                        "id" "ABC",
                                        "timestamp" "1700000000",
                                        "type" "text",
                                        "text" "hello"}}))
             (http-server/send! @ch-atom
                                (json/write-str {"event" "qr",
                                                 "data" "QR-123"}))
             (let [r0 (poll-next s {:position 0})]
               (is (= "15551112222@s.whatsapp.net" (:whatsapp/from (:ok r0))))
               (is (= "hello" (:whatsapp/text (:ok r0))))
               (let [r1 (poll-next s (:cursor r0))]
                 (is (= "QR-123" (:whatsapp/qr (:ok r1)))))))
           (ds/close! s))
         (finally (stop))))))


(comment
  (require '[dao.stream :as ds] '[dao.stream.whatsapp])
  (def s0 (ds/open! {:type :whatsapp})) ; default ws://localhost:3003
  (ds/put! s0 {:to "12677025334", :text (str "from sonny " (rand-int 100))})
  (ds/->seq nil s0) ; inbound messages, acks, qr, status
  (def s (ds/open! {:type :whatsapp}))
  ;; run in a background thread so it doesn't block the REPL
  (def f
    (future (loop [cursor {:position 0}]
              (let [r (ds/next s cursor)]
                (cond (map? r) (let [v (:ok r)]
                                 (when (:whatsapp/from v)
                                   (println "📩" (:whatsapp/from v)
                                            "→" (:whatsapp/text v)))
                                 (recur (:cursor r)))
                      (= r :blocked) (do (Thread/sleep 200) (recur cursor)) ; nothing
                                                                            ; new,
                                                                            ; keep
                                                                            ; the
                                                                            ; cursor
                      (= r :end) (println "stream closed")))))))
