(ns dao.stream.http.jvm
  "The JVM host edge for the DaoStream HTTP boundary (http-kit).

   Wire framing, header parsing and size limits live here, below the
   transform: an over-limit or malformed request is answered 400, 413 or 503
   and never deposited.  The listener holds each socket open, performs the
   effects the core asks of it by writing to the socket, and records no role
   and reads no path, token, query or body content."
  (:require [dao.stream.http :as http]
            [org.httpkit.server :as kit])
  (:import [java.io InputStream]))


(defn- read-body
  "The request body bytes http-kit already bounded by `:max-body`, or nil."
  [body]
  (when (instance? InputStream body)
    (let [bs (.readAllBytes ^InputStream body)]
      (when (pos? (alength bs)) bs))))


(defn- socket
  "The core's socket seam over one http-kit async channel.  `kit/send!`
   answers false once the channel is closed, which the core reads as a failed
   write."
  [ch]
  {:head! (fn [status headers]
            (kit/send! ch {:status status :headers headers} false))
   :write! (fn [chunk] (kit/send! ch chunk false))
   :end! (fn [] (kit/close ch) true)
   :close! (fn [] (kit/close ch) true)})


(defn- answer!
  [ch status]
  (kit/send! ch {:status status :headers {"content-type" "text/plain"}} true))


(defn handler
  "The listener's ring handler for `boundary`. Public by architect ruling
   2026-10-26 (collab/1793033722326) so a composition's host edge can
   dispatch to multiple boundaries on one port."
  [boundary]
  (fn [req]
    (let [exchange (atom nil)]
      (kit/as-channel
        req
        {:on-open (fn [ch]
                    (try
                      (let [result (http/accept!
                                     boundary
                                     {:method (:request-method req)
                                      :path (:uri req)
                                      :query (:query-string req)
                                      :headers (:headers req)
                                      :body (read-body (:body req))}
                                     (socket ch))]
                        (case (:http/status result)
                          :http/accepted (reset! exchange (:http/exchange result))
                          :http/too-large (answer! ch 413)
                          (:http/full :http/stopped) (answer! ch 503)
                          :http/gone nil))
                      (catch Throwable _ (answer! ch 400))))
         :on-close (fn [_ch _status]
                     (when-let [id @exchange]
                       (http/peer-closed! boundary id)))}))))


(defn listen!
  "Bind an http-kit listener for `boundary`.  Options: `:bind-host`,
   `:bind-port` (0 for ephemeral).  The boundary's `:max-body-bytes` bounds
   http-kit's own body read, so an oversize request is answered 413 before it
   is assembled.  Registers the listener's stop with the boundary and answers
   `{:http.jvm/server server :http.jvm/port port}`."
  [boundary {:keys [bind-host bind-port]}]
  (let [server (kit/run-server (handler boundary)
                               {:ip bind-host
                                :port bind-port
                                :max-body (:max-body-bytes boundary)
                                :legacy-return-value? false})]
    (http/on-stop! boundary #(kit/server-stop! server {:timeout 100}))
    {:http.jvm/server server
     :http.jvm/port (kit/server-port server)}))


(defn stop-listening!
  [{:http.jvm/keys [server]}]
  (kit/server-stop! server {:timeout 100}))
