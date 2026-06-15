(ns datomworld.whatsapp-gateway
  "WhatsApp gateway for dao.stream, compiled to a Node script.

   Plumbing BELOW the dao.stream boundary: drives a single real WhatsApp account
   via the multi-device Web protocol (Baileys handles the Noise handshake, Signal
   E2E and protobuf) and exposes it to dao.stream over a WebSocket carrying a
   small JSON command/event protocol. No Meta Cloud API; just a valid account
   paired once by QR.

   Stays dumb: translate Baileys events <-> JSON frames, broadcast inbound events
   to every connected client. See gateway/whatsapp/README.md for the protocol.

   Build & run:
     npx shadow-cljs compile whatsapp-gateway
     node target/whatsapp-gateway.js"
  (:require
    ["@whiskeysockets/baileys" :default make-wa-socket :refer
     [useMultiFileAuthState DisconnectReason fetchLatestBaileysVersion
      Browsers]]
    ["pino" :default pino]
    ["qrcode-terminal" :as qrcode]
    ["ws" :refer [WebSocketServer]]
    [clojure.string :as str]))


(def port
  (js/parseInt (or (.. js/process -env -WHATSAPP_GATEWAY_PORT) "3003") 10))


(def auth-dir (or (.. js/process -env -WHATSAPP_AUTH_DIR) "./.whatsapp-auth"))
(def logger (pino #js {:level (or (.. js/process -env -LOG_LEVEL) "warn")}))


;; clients: connected dao.stream WebSocket clients (broadcast targets).
;; node-state: last-qr / connected? replayed to late clients, plus the live
;; sock.
(defonce clients (atom #{}))
(defonce node-state (atom {:last-qr nil, :connected? false, :sock nil}))


(declare start-socket!)


(defn- broadcast!
  "JSON-encode a JS frame and send it to every open client."
  [^js frame-obj]
  (let [frame (js/JSON.stringify frame-obj)]
    (doseq [^js ws @clients]
      (when (= (.-readyState ws) 1) ; WebSocket.OPEN
        (.send ws frame)))))


(defn- ->jid
  [to]
  (if (and (string? to) (str/includes? to "@"))
    to
    (str (str/replace (str to) #"[^0-9]" "") "@s.whatsapp.net")))


;; ---------------------------------------------------------------------------
;; Baileys event handlers
;; ---------------------------------------------------------------------------

(defn- handle-messages!
  [^js upsert]
  (when (= (.-type upsert) "notify")
    (doseq [^js m (.-messages upsert)]
      (when (and (.-message m) (not (.. m -key -fromMe)))
        (let [^js msg (.-message m)
              text (or (.-conversation msg)
                       (some-> (.-extendedTextMessage msg)
                               (.-text)))]
          (broadcast! #js {:event "message",
                           :data #js {:from (.. m -key -remoteJid),
                                      :id (.. m -key -id),
                                      :timestamp (str (.-messageTimestamp m)),
                                      :type (if (some? text)
                                              "text"
                                              (aget (js/Object.keys msg) 0)),
                                      :text text,
                                      :raw m}}))))))


(defn- handle-updates!
  [updates]
  (doseq [^js u updates]
    (when (some-> (.-update u)
                  (.-status)
                  some?)
      (broadcast! #js {:event "ack",
                       :data #js {:id (.. u -key -id),
                                  :status (str (.. u -update -status))}}))))


(defn- handle-connection!
  [^js update]
  (let [conn (.-connection update)
        qr (.-qr update)
        reason (some-> (.-lastDisconnect update)
                       (.-error)
                       (.-output)
                       (.-statusCode))]
    (when qr
      (swap! node-state assoc :last-qr qr)
      (.generate qrcode qr #js {:small true})
      (println "[gateway] scan the QR above:"
               "WhatsApp > Linked Devices > Link a Device")
      (broadcast! #js {:event "qr", :data qr}))
    (when (= conn "open")
      (swap! node-state assoc :last-qr nil :connected? true)
      (println "[gateway] connected to WhatsApp")
      (broadcast! #js {:event "connected"}))
    (when (= conn "close")
      (swap! node-state assoc :connected? false)
      (broadcast! #js {:event "disconnected",
                       :data #js {:reason (or reason nil)}})
      (if (= reason (.-loggedOut DisconnectReason))
        (println "[gateway] logged out; delete the auth dir and re-pair")
        (do (println "[gateway] connection closed, reconnecting...")
            (js/setTimeout #(start-socket!) 2000))))))


(defn- start-socket!
  "Open (or reopen) the Baileys socket. Reconnect/backoff lives here. Pins the
   current WhatsApp Web version (fetchLatestBaileysVersion) so the server doesn't
   reject an outdated bundled version with a 405."
  []
  (.then (js/Promise.all #js [(fetchLatestBaileysVersion)
                              (useMultiFileAuthState auth-dir)])
         (fn [^js results]
           (let [version (.-version ^js (aget results 0))
                 ^js res (aget results 1)
                 auth-state (.-state res)
                 save-creds (.-saveCreds res)
                 ^js sock (make-wa-socket #js {:version version,
                                               :auth auth-state,
                                               :logger logger,
                                               :browser (.macOS Browsers
                                                                "Chrome"),
                                               :printQRInTerminal false})
                 ev (.-ev sock)]
             (swap! node-state assoc :sock sock)
             (.on ev "creds.update" save-creds)
             (.on ev "connection.update" handle-connection!)
             (.on ev "messages.upsert" handle-messages!)
             (.on ev "messages.update" handle-updates!)))))


;; ---------------------------------------------------------------------------
;; Command handling (client -> gateway)
;; ---------------------------------------------------------------------------

(defn- handle-command!
  [^js cmd]
  (when (= (.-cmd cmd) "send")
    (let [{:keys [sock connected?]} @node-state]
      (if (and sock connected?)
        (let [jid (->jid (.-to cmd))
              content (if (.-raw cmd)
                        (.-raw cmd)
                        #js {:text (str (or (.-text cmd) ""))})]
          (-> (.sendMessage ^js sock jid content)
              (.catch (fn [e] (.error logger e "send failed")))))
        (.warn logger "drop send: not connected")))))


(defn -main
  [& _args]
  (let [wss (WebSocketServer. #js {:port port})]
    (.on wss
         "connection"
         (fn [^js ws]
           (swap! clients conj ws)
           (let [{:keys [last-qr connected?]} @node-state]
             (when last-qr
               (.send ws (js/JSON.stringify #js {:event "qr", :data last-qr})))
             (when connected?
               (.send ws (js/JSON.stringify #js {:event "connected"}))))
           (.on ws
                "message"
                (fn [raw]
                  (when-let [cmd (try (js/JSON.parse (.toString raw))
                                      (catch :default _ nil))]
                    (handle-command! cmd))))
           (.on ws "close" (fn [] (swap! clients disj ws)))
           (.on ws "error" (fn [] (swap! clients disj ws)))))
    (println (str "[gateway] WebSocket listening on ws://localhost:" port))
    (start-socket!)))
