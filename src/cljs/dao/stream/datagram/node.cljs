(ns dao.stream.datagram.node
  "The Node host seam of docs/design/dao.stream.datagram.md 3: one `dgram`
   socket, bound once, whose events deposit every host fact and every
   received datagram on the composition's deposit writer as plain
   dao.stream.datagram values. The seam takes {:identity id :deposit w
   :bind-host h :bind-port p :max-bytes n} -- a deposit writer and nothing
   else, no function to invoke -- and returns at once {:send! f :close!
   f}; binding is asynchronous here, so the bound event arrives from the
   listening event and a failed bind arrives as an error event deposited
   as bind-failed. Node reports most send failures only later: an error
   event on a bound socket is deposited as send-failed, the channel this
   transport declares for an answer it cannot give at call time, and a
   send the host refuses at call time answers
   :dao.stream/transport-error and deposits the same event. The max-bytes
   drop is protocol validation below the transform: never deposited.
   Closing before the listening event deposits no closed event because
   this socket has not announced a bound identity. A Node Buffer already
   satisfies dao.stream.base64's input (it extends Uint8Array)."
  (:require ["dgram" :as dgram]
            [clojure.string :as str]
            [dao.stream :as stream]
            [dao.stream.datagram :as datagram]))


(defn bind!
  "Bind one dgram socket per the seam contract. `:bind-host` is the local
   interface address (the wildcard address is legal; an IPv6 literal
   selects an udp6 socket), `:bind-port` 0 asks for an ephemeral port,
   `:max-bytes` defaults to 1200 and is the symmetric datagram budget.
   Returns {:send! f :close! f} at once: `:send!` is (send! host port
   bytes) -> an outcome map, and every send failure on a bound socket,
   at call time or later, is also deposited as a send-failed event;
   `:close!` closes the host socket -- the close event deposits the
   closed fact while the deposit writer still can -- and is idempotent."
  [{:keys [identity deposit bind-host bind-port max-bytes]
    :or {bind-host "0.0.0.0" bind-port 0}}]
  (let [max-bytes (or max-bytes datagram/default-max-bytes)
        bound? (atom false)
        failed? (atom false)
        closed? (atom false)
        deposit-event!
        (fn [event]
          (try (stream/append! deposit event)
               (catch :default _ nil)))
        family (if (str/includes? bind-host ":") "udp6" "udp4")
        socket (.createSocket dgram family)]
    (.on socket "listening"
         (fn []
           (let [address (.address socket)]
             (when (and (not @failed?) (not @closed?))
               (reset! bound? true)
               (deposit-event!
                 (datagram/bound-event identity
                                       (.-address ^js address)
                                       (.-port ^js address)))))))
    (.on socket "message"
         (fn [msg rinfo]
           (when (<= (.-length ^js msg) max-bytes)
             (deposit-event!
               (datagram/datagram-event identity
                                        (.-address ^js rinfo)
                                        (.-port ^js rinfo)
                                        msg)))))
    (.on socket "error"
         (fn [err]
           (if-not @bound?
             ;; an error before listening is a failed bind: there is no
             ;; writer, and nothing else about this socket will be
             (when (compare-and-set! failed? false true)
               (deposit-event! (datagram/bind-failed-event
                                 identity (str (.-message ^js err)))))
             ;; after binding, an error event is a send or receive fault
             ;; this host reports only now
             (deposit-event! (datagram/send-failed-event
                               identity (str (.-message ^js err)))))))
    (.on socket "close"
         (fn []
           (when (and @bound? (not @failed?)
                      (compare-and-set! closed? false true))
             (deposit-event! (datagram/closed-event identity)))))
    (.bind socket #js {:address bind-host :port bind-port})
    {:send!
     (fn [host port bytes]
       (if (or @failed? (and (not @bound?) (not @closed?)))
         ;; A socket that never bound has no working send: Node would
         ;; silently bind a fresh port for it, which is not this socket.
         ;; A closed one still reaches the host, which refuses.
         {:dao.stream/outcome :dao.stream/transport-error
          :dao.stream.datagram/reason (if @failed?
                                        "bind failed"
                                        "not bound")}
         (if (not= (= family "udp6") (str/includes? host ":"))
           (do (deposit-event!
                 (datagram/send-failed-event identity "IP family mismatch"))
               {:dao.stream/outcome :dao.stream/transport-error
                :dao.stream.datagram/reason "IP family mismatch"})
           (try
             (.send socket bytes port host)
             {:dao.stream/outcome :dao.stream/ok}
             (catch :default e
               (when @bound?
                 (deposit-event! (datagram/send-failed-event identity (str e))))
               {:dao.stream/outcome :dao.stream/transport-error
                :dao.stream.datagram/reason (str e)})))))
     :close!
     (fn []
       (try (.close socket) (catch :default _ nil))
       nil)}))
