(ns dao.stream.whatsapp
  "WhatsApp transport for DaoStream over a self-hosted gateway.

   No Meta Cloud API and no app registration: the only requirement is a valid
   WhatsApp account, paired once by scanning a QR code. The multi-device
   protocol (Noise handshake, Signal E2E, protobuf) is NOT implemented here. It
   lives in a separate gateway process (Node + Baileys, see gateway/whatsapp/)
   that drives the account and exposes it over a WebSocket carrying a small JSON
   command/event protocol. dao.stream connects to that gateway as a client.

   Descriptor:
     {:type :whatsapp
      :gateway-url \"ws://localhost:3003\"  ; optional; else env WHATSAPP_GATEWAY_URL
      :capacity nil                         ; inbound ringbuffer capacity
      :eviction-policy nil}                 ; :reject (default) | :evict-oldest

   Realizes a bidirectional stream:
     IDaoStreamWriter — put! normalizes {:to .. :text ..} (or {:raw ..}) to a
                        `send` command frame and writes it on the gateway WS.
                        Fire-and-forget: always returns {:result :ok}.
     IDaoStreamReader — next reads inbound messages, acks, qr and connection
                        status events, cursor-based and non-destructive.
     IDaoStreamBound  — close! closes the WS and the inbound buffer.
     IDaoStreamWaitable / IDaoStreamDrainable — delegated to the inner ringbuffer
                        so blocking reads (take!!) and drains work directly.

   Acks are fire-and-forget read events ({:whatsapp/ack ...}); put! does not
   block. On a gateway disconnect the stream emits {:whatsapp/status
   :disconnected}, nulls its send-fn (further put! no-op), and does not
   auto-reconnect.

   Cross-platform via reader conditionals in this single file (no .cljd sibling):
   the only platform-specific work is opening one WebSocket and wiring its
   send/receive callbacks."
  (:require
    #?@(:cljd [["dart:convert" :as convert] ["dart:io" :as io]]
        :cljs []
        :clj [[clojure.data.json :as json]])
    [clojure.string :as str]
    [dao.stream :as ds]
    [dao.stream.ringbuffer])
  #?(:cljs
     (:require-macros
       [dao.stream])))


(def default-gateway-url "ws://localhost:3003")


;; =============================================================================
;; Config (descriptor wins, env fallback — cf. agent.tzu/api-key)
;; =============================================================================

(defn- env
  [k]
  #?(:clj (System/getenv k)
     :cljs (some-> js/process
                   .-env
                   (aget k))
     :cljd (get
             (into {} (map (fn [e] [(key e) (val e)])) io/Platform.environment)
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


(defn- json-decode-safe
  "Parse a JSON string into a clj value with string keys; nil on empty/malformed."
  [s]
  (when s
    (try #?(:clj (json/read-str s)
            :cljs (js->clj (js/JSON.parse s))
            :cljd (dart->clj (convert/jsonDecode s)))
         (catch #?(:clj Exception
                   :cljs js/Error
                   :cljd Exception)
                _
           nil))))


;; =============================================================================
;; Pure core (cross-platform, the unit-testable surface)
;; =============================================================================

(defn ->jid
  "Normalize a recipient to a WhatsApp JID. A value already containing '@' (a
   full JID such as a group id) passes through; a bare phone number is stripped
   to digits and suffixed with @s.whatsapp.net."
  [to]
  (if (and to (str/includes? to "@"))
    to
    (str (str/replace (or to "") #"[^0-9]" "") "@s.whatsapp.net")))


(defn outgoing->command
  "Normalize an outgoing message map to a gateway `send` command frame
   (string keys, ready for JSON encoding on every platform).

   {:raw m}            -> {\"cmd\" \"send\" \"to\" <jid> \"raw\" m}
   {:to .. :text ..}   -> {\"cmd\" \"send\" \"to\" <jid> \"text\" ..}"
  [{:keys [raw to text]}]
  (cond-> {"cmd" "send", "to" (->jid to)}
    raw (assoc "raw" raw)
    (and (not raw) text) (assoc "text" text)))


(defn event->datom
  "Translate a parsed gateway event frame into a normalized inbound stream value,
   or nil for events that carry nothing for a consumer."
  [frame]
  (when (map? frame)
    (let [data (get frame "data")]
      (case (get frame "event")
        "message" (cond-> {:whatsapp/from (get data "from"),
                           :whatsapp/id (get data "id"),
                           :whatsapp/timestamp (get data "timestamp"),
                           :whatsapp/type (some-> (get data "type")
                                                  keyword),
                           :whatsapp/raw data}
                    (get data "text") (assoc :whatsapp/text (get data "text")))
        "ack" {:whatsapp/ack {:id (get data "id"), :status (get data "status")}}
        "qr" {:whatsapp/qr data}
        "connected" {:whatsapp/status :connected}
        "disconnected" {:whatsapp/status :disconnected}
        nil))))


;; =============================================================================
;; WhatsAppStream record
;; =============================================================================

(defrecord WhatsAppStream
  [inbound send-fn-atom status-atom]

  ds/IDaoStreamWriter

  (put!
    [_ val]
    (when-let [send-fn @send-fn-atom]
      (send-fn (json-encode (outgoing->command val))))
    {:result :ok, :woke []})


  ds/IDaoStreamReader

  (next [_ cursor] (ds/next inbound cursor))


  ds/IDaoStreamBound

  (close!
    [_]
    (reset! send-fn-atom nil)
    (reset! status-atom :closed)
    (ds/close! inbound))


  (closed? [_] (= :closed @status-atom))


  ds/IDaoStreamWaitable

  (register-reader-waiter!
    [_ position entry]
    (ds/register-reader-waiter! inbound position entry))


  (register-writer-waiter!
    [_ entry]
    (ds/register-writer-waiter! inbound entry))


  ds/IDaoStreamDrainable

  (-drain-one! [_] (ds/-drain-one! inbound)))


(defn connected?
  "True when the stream currently has a live send path to the gateway."
  [stream]
  (and (instance? WhatsAppStream stream) (some? @(:send-fn-atom stream))))


;; =============================================================================
;; Inbound frame routing (the explicit stream representation of the WS
;; callback)
;; =============================================================================

(defn- handle-frame!
  [stream raw]
  (when-let [datom (event->datom (json-decode-safe raw))]
    (when-not (ds/closed? (:inbound stream))
      (ds/put! (:inbound stream) datom))))


(defn- handle-disconnect!
  [stream]
  (reset! (:send-fn-atom stream) nil)
  (when-not (ds/closed? (:inbound stream))
    (ds/put! (:inbound stream) {:whatsapp/status :disconnected})))


;; =============================================================================
;; Raw WS client wiring (per-platform; mirrors dao.stream.ws/connect!)
;; =============================================================================

(defn- connect-ws!
  "Open a WebSocket client to the gateway at url and wire it onto stream: set the
   send-fn on open, route inbound frames, emit :disconnected on close/error."
  [stream url]
  #?(:clj (let [client (java.net.http.HttpClient/newHttpClient)
                listener
                (reify
                  java.net.http.WebSocket$Listener
                  (onOpen
                    [_ ws]
                    (reset! (:send-fn-atom stream)
                            (fn [msg] (.join (.sendText ws msg true)) nil))
                    (.request ws 1))

                  (onText
                    [_ ws data _last?]
                    (handle-frame! stream (str data))
                    (.request ws 1)
                    (java.util.concurrent.CompletableFuture/completedFuture
                      nil))

                  (onClose
                    [_ _ws _code _reason]
                    (handle-disconnect! stream)
                    (java.util.concurrent.CompletableFuture/completedFuture
                      nil))

                  (onError [_ _ws _err] (handle-disconnect! stream)))]
            (.thenAccept (.buildAsync (.. client newWebSocketBuilder)
                                      (java.net.URI/create url)
                                      listener)
                         (fn [_ws] nil)))
     :cljs (let [ws (js/WebSocket. url)]
             (set! (.-onopen ws)
                   (fn [_]
                     (reset! (:send-fn-atom stream) (fn [msg] (.send ws msg)))))
             (set! (.-onmessage ws) (fn [e] (handle-frame! stream (.-data e))))
             (set! (.-onclose ws) (fn [_] (handle-disconnect! stream)))
             (set! (.-onerror ws) (fn [_] (handle-disconnect! stream))))
     :cljd (.then (io/WebSocket.connect url)
                  (fn [ws]
                    (let [ws ^io/WebSocket ws]
                      (reset! (:send-fn-atom stream) (fn [msg] (.add ws msg)))
                      (.listen ws
                               (fn [raw] (handle-frame! stream (str raw)))
                               .onDone
                               (fn [] (handle-disconnect! stream))))))))


;; =============================================================================
;; Registration
;; =============================================================================

(ds/defopen
  :whatsapp
  [descriptor]
  (let [{:keys [gateway-url capacity eviction-policy]} descriptor
        url (or gateway-url (env "WHATSAPP_GATEWAY_URL") default-gateway-url)
        inbound (ds/open! {:type :ringbuffer,
                           :capacity capacity,
                           :eviction-policy eviction-policy})
        stream (->WhatsAppStream inbound (atom nil) (atom :open))]
    (connect-ws! stream url)
    stream))
