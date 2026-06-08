(ns dao.stream.whatsapp
  "WhatsApp transport for DaoStream over the Meta WhatsApp Business Cloud API.

   Descriptor:
     {:type :whatsapp
      :api-url \"https://graph.facebook.com/v21.0\"  ; optional, default
      :phone-number-id \"...\"   ; to send; else env WHATSAPP_PHONE_NUMBER_ID
      :access-token \"...\"      ; to send; else env WHATSAPP_ACCESS_TOKEN
      :capacity nil             ; inbound ringbuffer capacity
      :eviction-policy nil      ; :reject (default) | :evict-oldest
      :webhook {:port 3003 :path \"/webhook\" :verify-token \"...\"}}
                                ; verify-token else env WHATSAPP_VERIFY_TOKEN

   Realizes a bidirectional stream:
     IDaoStreamWriter — put! sends a message; the ack/error is emitted back onto
                        the inbound read stream as {:whatsapp/ack ...} or
                        {:whatsapp/error ...}.
     IDaoStreamReader — next reads inbound messages (delivered to the webhook
                        server) and send acks, cursor-based and non-destructive.
     IDaoStreamBound  — close! stops the webhook server and closes the buffer.

   Cross-platform: outbound send + ack forwarding work on clj/cljs/cljd through
   dao.stream.http. The inbound webhook server runs on clj (http-kit), cljs
   (Node 'http' module), and cljd (dart:io HttpServer).

   put! accepts {:to <phone> :text <string>} or {:raw <full Cloud API map>}.
   Inbound messages are normalized to
     {:whatsapp/from :whatsapp/id :whatsapp/timestamp :whatsapp/type
      :whatsapp/text :whatsapp/raw}
   and delivery receipts to {:whatsapp/status ... :whatsapp/id ... :whatsapp/raw}.

   The Cloud API has no polling: receiving requires a public HTTPS webhook
   (e.g. via a tunnel) registered in the Meta App dashboard."
  (:require
    #?@(:cljd [["dart:convert" :as convert]
               ["dart:async" :as async]
               ["dart:io" :as io]]
        :cljs []
        :clj [[clojure.data.json :as json]
              [org.httpkit.server :as http-server]
              [ring.util.codec :as codec]])
    [dao.stream :as ds]
    [dao.stream.http]
    [dao.stream.ringbuffer])
  #?(:cljs
     (:require-macros
       [dao.stream])))


(def default-api-url "https://graph.facebook.com/v21.0")


;; =============================================================================
;; Config / credentials (descriptor wins, env fallback — cf. agent.tzu/api-key)
;; =============================================================================

(defn- env
  [k]
  #?(:clj (System/getenv k)
     :cljs (some-> js/process .-env (aget k))
     :cljd (get (into {} (map (fn [e] [(key e) (val e)])) io/Platform.environment)
                k)))


;; =============================================================================
;; JSON (reader-conditional — cf. agent.tzu/json-encode|json-decode)
;; =============================================================================

(defn- json-encode
  [x]
  #?(:clj (json/write-str x)
     :cljs (js/JSON.stringify (clj->js x))
     :cljd (convert/jsonEncode x)))


#?(:cljd (defn- dart->clj
           [x]
           (cond (dart/is? x Map)
                 (into {} (map (fn [e] [(key e) (dart->clj (val e))])) x)
                 (dart/is? x List) (mapv dart->clj x)
                 :else x)))


(defn- json-decode
  [s]
  #?(:clj (json/read-str s)
     :cljs (js->clj (js/JSON.parse s))
     :cljd (dart->clj (convert/jsonDecode s))))


(defn- json-decode-safe
  "json-decode that returns nil instead of throwing on a malformed/empty body."
  [s]
  (try (json-decode s)
       (catch #?(:clj Exception
                 :cljs js/Error
                 :cljd Exception)
              _
         nil)))


;; =============================================================================
;; Pure core
;; =============================================================================

(defn send-url
  "Cloud API messages endpoint for a phone-number-id."
  [api-url phone-number-id]
  (str api-url "/" phone-number-id "/messages"))


(defn outgoing->payload
  "Normalize an outgoing message map to a Cloud API JSON payload.

   {:raw m}            -> m unchanged (templates, media, interactive, ...)
   {:to .. :text ..}   -> an individual text message."
  [{:keys [raw to text]}]
  (or raw
      {"messaging_product" "whatsapp",
       "recipient_type" "individual",
       "to" to,
       "type" "text",
       "text" {"body" text}}))


(defn- normalize-message
  [m]
  (let [type (get m "type")]
    (cond-> {:whatsapp/from (get m "from"),
             :whatsapp/id (get m "id"),
             :whatsapp/timestamp (get m "timestamp"),
             :whatsapp/type (some-> type keyword),
             :whatsapp/raw m}
      (= type "text") (assoc :whatsapp/text (get-in m ["text" "body"])))))


(defn- normalize-status
  [s]
  {:whatsapp/status (get s "status"),
   :whatsapp/id (get s "id"),
   :whatsapp/timestamp (get s "timestamp"),
   :whatsapp/recipient-id (get s "recipient_id"),
   :whatsapp/raw s})


(defn webhook->messages
  "Walk a parsed Cloud API webhook envelope into a vector of normalized events:
   inbound messages and delivery-status receipts."
  [envelope]
  (into []
        (mapcat (fn [change]
                  (let [value (get change "value")]
                    (concat (map normalize-message (get value "messages"))
                            (map normalize-status (get value "statuses"))))))
        (mapcat #(get % "changes") (get envelope "entry"))))


(defn verify-response
  "Meta webhook subscription handshake. Echoes hub.challenge on a token match."
  [params verify-token]
  (if (and (= "subscribe" (get params "hub.mode"))
           (= verify-token (get params "hub.verify_token")))
    {:status 200, :body (get params "hub.challenge")}
    {:status 403, :body "Forbidden"}))


;; =============================================================================
;; Outbound send acknowledgement
;; =============================================================================

(defn- emit-send-result!
  "Translate an HTTP send response into an ack/error event on the inbound buffer."
  [inbound {:keys [status body error]}]
  (if (or error (not= 200 status))
    (ds/put! inbound {:whatsapp/error {:status status,
                                       :body body,
                                       :error error}})
    (ds/put! inbound {:whatsapp/ack
                      {:id (get-in (json-decode-safe body) ["messages" 0 "id"]),
                       :status status}})))


(defn- forward-ack!
  "Asynchronously read the single HTTP send response and emit an ack/error onto
   the inbound buffer. Never blocks the caller on cljs/cljd."
  [inbound http]
  #?(:clj
     (future (when-let [resp (ds/take!! http)]
               (emit-send-result! inbound resp)))
     :cljs
     (letfn [(check
               []
               (let [r (ds/next http {:position 0})]
                 (cond (map? r) (emit-send-result! inbound (:ok r))
                       (= :end r) nil
                       :else (js/setTimeout check 10))))]
       (check))
     :cljd
     (letfn [(check
               []
               (let [r (ds/next http {:position 0})]
                 (cond (map? r) (emit-send-result! inbound (:ok r))
                       (= :end r) nil
                       :else (-> (async/Future.delayed
                                   (Duration .milliseconds 10))
                                 (.then (fn [_] (check)))))))]
       (check))))


;; =============================================================================
;; WhatsAppStream record
;; =============================================================================

(defrecord WhatsAppStream
  [config inbound server-atom status-atom]

  ds/IDaoStreamWriter

  (put!
    [_ val]
    (let [http (ds/open!
                 {:type :http,
                  :url (send-url (:api-url config) (:phone-number-id config)),
                  :method :post,
                  :headers {"Authorization" (str "Bearer " (:access-token config)),
                            "Content-Type" "application/json"},
                  :body (json-encode (outgoing->payload val))})]
      (forward-ack! inbound http)
      {:result :ok, :woke [], :response http}))


  ds/IDaoStreamReader

  (next [_ cursor] (ds/next inbound cursor))


  ds/IDaoStreamBound

  (close!
    [_]
    (when-let [stop @server-atom] (stop))
    (reset! status-atom :closed)
    (ds/close! inbound))


  (closed? [_] (= :closed @status-atom)))


(defn deliver-webhook!
  "Normalize a parsed webhook envelope and append every event onto the stream's
   inbound buffer. The explicit stream representation of an inbound callback.
   Returns the number of events delivered."
  [stream envelope]
  (let [msgs (webhook->messages envelope)]
    (doseq [m msgs] (ds/put! (:inbound stream) m))
    (count msgs)))


;; =============================================================================
;; Webhook server (clj http-kit / cljs Node http / cljd dart:io)
;; Returns a 0-arg stop function on every platform.
;; =============================================================================

(defn- start-webhook-server!
  [stream {:keys [port path verify-token]}]
  (let [path (or path "/webhook")
        verify-token (or verify-token (env "WHATSAPP_VERIFY_TOKEN"))]
    #?(:clj
       (http-server/run-server
         (fn [req]
           (cond
             (and (= :get (:request-method req)) (= path (:uri req)))
             (assoc (verify-response (codec/form-decode
                                       (or (:query-string req) ""))
                                     verify-token)
                    :headers {"Content-Type" "text/plain"})

             (and (= :post (:request-method req)) (= path (:uri req)))
             (do (when-let [b (:body req)]
                   (deliver-webhook! stream (json-decode (slurp b))))
                 {:status 200, :body "EVENT_RECEIVED"})

             :else {:status 404, :body "Not found"}))
         {:port port})

       :cljs
       (let [http (js/require "http")
             server
             (.createServer
               http
               (fn [req res]
                 (let [u (js/URL. (.-url req) "http://localhost")
                       uri (.-pathname u)
                       method (.-method req)]
                   (cond
                     (and (= method "GET") (= uri path))
                     (let [sp (.-searchParams u)
                           resp (verify-response
                                  {"hub.mode" (.get sp "hub.mode"),
                                   "hub.verify_token" (.get sp "hub.verify_token"),
                                   "hub.challenge" (.get sp "hub.challenge")}
                                  verify-token)]
                       (.writeHead res (:status resp)
                                   #js {"Content-Type" "text/plain"})
                       (.end res (str (:body resp))))

                     (and (= method "POST") (= uri path))
                     (let [chunks (atom "")]
                       (.on req "data" (fn [c] (swap! chunks str c)))
                       (.on req "end"
                            (fn []
                              (deliver-webhook! stream (json-decode @chunks))
                              (.writeHead res 200)
                              (.end res "EVENT_RECEIVED"))))

                     :else (do (.writeHead res 404) (.end res "Not found"))))))]
         (.listen server port)
         (fn [] (.close server)))

       :cljd
       (let [server-ref (atom nil)]
         (-> (io/HttpServer.bind "0.0.0.0" port)
             (.then
               (fn [server]
                 (reset! server-ref server)
                 (.listen
                   server
                   (fn [request]
                     (let [response (.-response request)
                           uri (.-uri request)
                           method (.-method request)
                           reqpath (.-path uri)]
                       (cond
                         (and (= method "GET") (= reqpath path))
                         (let [params (into {}
                                            (map (fn [e] [(key e) (val e)]))
                                            (.-queryParameters uri))
                               resp (verify-response params verify-token)]
                           (set! (.-statusCode response) (:status resp))
                           (.write response (str (:body resp)))
                           (.close response))

                         (and (= method "POST") (= reqpath path))
                         (-> (.join (.transform request
                                                (.-decoder convert/utf8)))
                             (.then (fn [body-str]
                                      (deliver-webhook! stream
                                                        (json-decode body-str))
                                      (set! (.-statusCode response) 200)
                                      (.write response "EVENT_RECEIVED")
                                      (.close response))))

                         :else (do (set! (.-statusCode response) 404)
                                   (.write response "Not found")
                                   (.close response)))
                       nil))))))
         (fn [] (when-let [s @server-ref] (.close s)))))))


;; =============================================================================
;; Registration
;; =============================================================================

(ds/defopen
  :whatsapp
  [descriptor]
  (let [{:keys [api-url phone-number-id access-token capacity eviction-policy
                webhook]}
        descriptor
        config {:api-url (or api-url default-api-url),
                :phone-number-id (or phone-number-id
                                     (env "WHATSAPP_PHONE_NUMBER_ID")),
                :access-token (or access-token (env "WHATSAPP_ACCESS_TOKEN"))}
        inbound (ds/open! {:type :ringbuffer,
                           :capacity capacity,
                           :eviction-policy eviction-policy})
        stream (->WhatsAppStream config inbound (atom nil) (atom :open))]
    (when webhook
      (reset! (:server-atom stream) (start-webhook-server! stream webhook)))
    stream))
