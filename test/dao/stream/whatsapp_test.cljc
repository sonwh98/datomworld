(ns dao.stream.whatsapp-test
  "Tests for the :whatsapp DaoStream transport (Meta Cloud API).

   Layered like the plan in docs/design/dao.stream.whatsapp.md:
     1. Pure core      — outgoing->payload, webhook->messages, verify-response, send-url
     2. Stream contract — deliver-webhook! + next/close!/closed! via ds/open!
     3. Send path       — put! against an inline http-kit stub, ack on next (JVM)
     4. Webhook server  — real GET verify + POST delivery (JVM)"
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [dao.stream.whatsapp :as wa]
    #?@(:cljd []
        :cljs []
        :clj [[org.httpkit.server :as http-server]
              [clojure.data.json :as json]])))


;; =============================================================================
;; 1. Pure core
;; =============================================================================

(deftest outgoing->payload-test
  (testing "text message builds the Cloud API individual-text payload"
    (is (= {"messaging_product" "whatsapp",
            "recipient_type" "individual",
            "to" "15551234567",
            "type" "text",
            "text" {"body" "hello"}}
           (wa/outgoing->payload {:to "15551234567", :text "hello"}))))
  (testing ":raw is passed through untouched (templates / media)"
    (let [raw {"messaging_product" "whatsapp",
               "to" "15551234567",
               "type" "template",
               "template" {"name" "hello_world", "language" {"code" "en_US"}}}]
      (is (= raw (wa/outgoing->payload {:raw raw}))))))


(def ^:private sample-message-envelope
  {"object" "whatsapp_business_account",
   "entry"
   [{"id" "WABA_ID",
     "changes"
     [{"field" "messages",
       "value"
       {"messaging_product" "whatsapp",
        "metadata" {"display_phone_number" "15550000000",
                    "phone_number_id" "PNID"},
        "contacts" [{"profile" {"name" "Ada"}, "wa_id" "15551234567"}],
        "messages"
        [{"from" "15551234567",
          "id" "wamid.ABC",
          "timestamp" "1700000000",
          "type" "text",
          "text" {"body" "Hello there"}}]}}]}]})


(def ^:private sample-status-envelope
  {"object" "whatsapp_business_account",
   "entry"
   [{"id" "WABA_ID",
     "changes"
     [{"field" "messages",
       "value"
       {"messaging_product" "whatsapp",
        "statuses"
        [{"id" "wamid.OUT",
          "status" "delivered",
          "timestamp" "1700000005",
          "recipient_id" "15551234567"}]}}]}]})


(deftest webhook->messages-test
  (testing "extracts and normalizes an inbound text message"
    (is (= [{:whatsapp/from "15551234567",
             :whatsapp/id "wamid.ABC",
             :whatsapp/timestamp "1700000000",
             :whatsapp/type :text,
             :whatsapp/text "Hello there",
             :whatsapp/raw {"from" "15551234567",
                            "id" "wamid.ABC",
                            "timestamp" "1700000000",
                            "type" "text",
                            "text" {"body" "Hello there"}}}]
           (wa/webhook->messages sample-message-envelope))))
  (testing "maps delivery statuses to :whatsapp/status events"
    (let [[evt :as evts] (wa/webhook->messages sample-status-envelope)]
      (is (= 1 (count evts)))
      (is (= "delivered" (:whatsapp/status evt)))
      (is (= "wamid.OUT" (:whatsapp/id evt)))
      (is (= "15551234567" (:whatsapp/recipient-id evt)))))
  (testing "empty / unrelated envelope yields no events"
    (is (= [] (wa/webhook->messages {"object" "whatsapp_business_account",
                                     "entry" []})))))


(deftest verify-response-test
  (testing "matching mode + token echoes the challenge"
    (is (= {:status 200, :body "CHALLENGE_123"}
           (wa/verify-response {"hub.mode" "subscribe",
                                "hub.verify_token" "s3cr3t",
                                "hub.challenge" "CHALLENGE_123"}
                               "s3cr3t"))))
  (testing "wrong token is rejected with 403"
    (is (= 403 (:status (wa/verify-response {"hub.mode" "subscribe",
                                             "hub.verify_token" "nope",
                                             "hub.challenge" "X"}
                                            "s3cr3t"))))))


(deftest send-url-test
  (is (= "https://graph.facebook.com/v21.0/PNID/messages"
         (wa/send-url "https://graph.facebook.com/v21.0" "PNID"))))


;; =============================================================================
;; 2. Stream contract (no network)
;; =============================================================================

(deftest deliver-and-read-test
  (let [stream (ds/open! {:type :whatsapp,
                          :phone-number-id "PNID",
                          :access-token "TOKEN"})]
    (testing "deliver-webhook! puts normalized inbound messages onto the stream"
      (is (= 1 (wa/deliver-webhook! stream sample-message-envelope))))
    (testing "next reads them in order, non-destructively"
      (let [r1 (ds/next stream {:position 0})
            r2 (ds/next stream {:position 0})]
        (is (= "Hello there" (:whatsapp/text (:ok r1))))
        (is (= "Hello there" (:whatsapp/text (:ok r2))))))
    (testing "close! / closed?"
      (is (false? (ds/closed? stream)))
      (ds/close! stream)
      (is (true? (ds/closed? stream))))))


;; =============================================================================
;; 3. Send path (JVM inline stub server)
;; =============================================================================

#?(:clj
   (defn- poll-next
     "Poll a stream for the first value, up to timeout-ms. Returns the value or nil."
     [stream timeout-ms]
     (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
       (loop []
         (let [r (ds/next stream {:position 0})]
           (cond (map? r) (:ok r)
                 (> (System/currentTimeMillis) deadline) nil
                 :else (do (Thread/sleep 10) (recur))))))))


(deftest send-path-test
  #?(:clj
     (let [port 18991
           received (atom nil)
           stop (http-server/run-server
                  (fn [req]
                    (reset! received {:method (:request-method req),
                                      :uri (:uri req),
                                      :auth (get-in req [:headers "authorization"]),
                                      :body (when-let [b (:body req)] (slurp b))})
                    {:status 200,
                     :headers {"Content-Type" "application/json"},
                     :body (json/write-str {"messages" [{"id" "wamid.SENT"}]})})
                  {:port port})
           stream (ds/open! {:type :whatsapp,
                             :api-url (str "http://localhost:" port),
                             :phone-number-id "PNID",
                             :access-token "TOKEN"})]
       (try
         (ds/put! stream {:to "15551234567", :text "hi"})
         (let [ack (poll-next stream 2000)
               req @received]
           (testing "request hit the right URL with auth + JSON body"
             (is (= :post (:method req)))
             (is (= "/PNID/messages" (:uri req)))
             (is (= "Bearer TOKEN" (:auth req)))
             (is (= {"messaging_product" "whatsapp",
                     "recipient_type" "individual",
                     "to" "15551234567",
                     "type" "text",
                     "text" {"body" "hi"}}
                    (json/read-str (:body req)))))
           (testing "send ack appears on the read stream"
             (is (= "wamid.SENT" (get-in ack [:whatsapp/ack :id])) (pr-str ack))))
         (finally (stop) (ds/close! stream))))))


(deftest send-path-malformed-body-test
  #?(:clj
     (let [port 18993
           stop (http-server/run-server
                  (fn [_req]
                    {:status 200,
                     :headers {"Content-Type" "application/json"},
                     :body "not json at all"})
                  {:port port})
           stream (ds/open! {:type :whatsapp,
                             :api-url (str "http://localhost:" port),
                             :phone-number-id "PNID",
                             :access-token "TOKEN"})]
       (try
         (ds/put! stream {:to "15551234567", :text "hi"})
         (testing "a 200 with an unparseable body yields an ack with nil id, no crash"
           (let [ack (poll-next stream 2000)]
             (is (contains? ack :whatsapp/ack) (pr-str ack))
             (is (nil? (get-in ack [:whatsapp/ack :id])) (pr-str ack))
             (is (= 200 (get-in ack [:whatsapp/ack :status])))))
         (finally (stop) (ds/close! stream))))))


;; =============================================================================
;; 4. Webhook server (JVM, real GET verify + POST delivery)
;; =============================================================================

(deftest webhook-server-test
  #?(:clj
     (let [port 18992
           base (str "http://localhost:" port)
           stream (ds/open! {:type :whatsapp,
                             :phone-number-id "PNID",
                             :access-token "TOKEN",
                             :webhook {:port port,
                                       :path "/webhook",
                                       :verify-token "s3cr3t"}})]
       (try
         (testing "GET handshake echoes the challenge when token matches"
           (let [resp (ds/take!!
                        (ds/open!
                          {:type :http,
                           :method :get,
                           :url (str base
                                     "/webhook?hub.mode=subscribe"
                                     "&hub.verify_token=s3cr3t"
                                     "&hub.challenge=CH42")}))]
             (is (= 200 (:status resp)) (pr-str resp))
             (is (= "CH42" (:body resp)))))
         (testing "POST delivers an inbound message onto the stream"
           (ds/take!! (ds/open! {:type :http,
                                 :method :post,
                                 :url (str base "/webhook"),
                                 :headers {"Content-Type" "application/json"},
                                 :body (json/write-str sample-message-envelope)}))
           (let [msg (poll-next stream 2000)]
             (is (= "Hello there" (:whatsapp/text msg)) (pr-str msg))))
         (finally (ds/close! stream))))))
