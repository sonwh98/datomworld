(ns dao.stream.http
  "The DaoStream HTTP boundary (docs/design/dao.stream.http.md).

   The boundary is an adapter (events in) and a host effect interpreter
   (effects out) over two media the composition supplies: an ingress medium it
   deposits into and an egress medium it reads.  It owns no stream, hands no
   handle upward, and never reads a request body, query or response chunk.

   This namespace knows no HTTP library.  A host listener owns its sockets and
   supplies, per exchange, a small synchronous socket seam:

     :head!  (fn [status headers])  write the response head
     :write! (fn [chunk])           write bytes or a string (UTF-8) and flush
     :end!   (fn [])                complete the response
     :close! (fn [])                drop the connection, no response

   A seam function reports failure by throwing or answering false.  Socket
   callbacks enter only through `accept!` and `peer-closed!`; the step takes no
   clock and holds no timer."
  (:require [clojure.string :as str]
            [dao.host.bytes :as bytes]
            [dao.host.text :as text]
            [dao.stream :as stream]))


(defn admission?
  "The ingress deposit declaration: evict-oldest retention with a positive
   declared capacity, value domain `:portable-values`, and a lifetime longer
   than the boundary (`:lifetime :outlives-boundary`).  A reject-mode
   destination, or one that cannot carry every envelope, is not admissible."
  [x]
  (and (map? x)
       (= :evict-oldest (:retention x))
       (integer? (:capacity x))
       (pos? (:capacity x))
       (= :portable-values (:value-domain x))
       (= :outlives-boundary (:lifetime x))))


(defn- target-with?
  [target surface pred]
  (and (map? target)
       (pred (:dao.stream/handle target))
       (contains? (:dao.stream/surface target) surface)))


(defn- positive-int?
  [x]
  (and (integer? x) (pos? x)))


(defn make-boundary
  "Construct the boundary.  Config:

     :ingress            writer target {:dao.stream/handle :dao.stream/surface}
     :ingress-admission  its declaration, checked by `admission?`
     :egress             reader target the boundary reads effects from
     :http/max-open      exchanges held open at once (positive integer)
     :http/max-body-bytes  largest request body deposited (positive integer)
     :http/incarnation   optional portable value distinguishing this
                         boundary from any earlier one on the same egress
                         medium; defaults to a fresh uuid string

   Wiring a reject-mode destination, or a destination that is not a writer,
   is an assembly defect and throws.  The egress cursor is minted at
   `:dao.stream/newest` here, before any listener starts."
  [{:keys [ingress ingress-admission egress]
    max-open :http/max-open
    max-body :http/max-body-bytes
    :as config}]
  (when-not (and (target-with? ingress :writer stream/writer?)
                 (admission? ingress-admission)
                 (target-with? egress :reader stream/reader?)
                 (positive-int? max-open)
                 (positive-int? max-body))
    (throw (ex-info "invalid DaoStream HTTP boundary composition"
                    {:config (dissoc config :ingress :egress)})))
  (let [minted (stream/cursor (:dao.stream/handle egress) stream/anchor-newest)]
    (when-not (= :dao.stream/ok (:dao.stream/outcome minted))
      (throw (ex-info "cannot mint the egress cursor" {:outcome minted})))
    {:ingress (:dao.stream/handle ingress)
     :egress (:dao.stream/handle egress)
     :max-open max-open
     :max-body-bytes max-body
     :incarnation (or (:http/incarnation config) (str (random-uuid)))
     :state (atom {:cursor (:dao.stream/cursor minted)
                   :n 0
                   :open {}
                   :stopped? false
                   :on-stop nil})}))


;; -----------------------------------------------------------------------------
;; Deposits and the seam
;; -----------------------------------------------------------------------------

(defn- deposit!
  "Deposit one event unjudged; true when the ingress medium admitted it."
  [boundary event]
  (= :dao.stream/ok
     (:dao.stream/outcome (stream/append! (:ingress boundary) event))))


(defn- seam-ok?
  "Run a socket seam call; false when it threw or answered false."
  [f & args]
  (try
    (not (false? (apply f args)))
    (catch #?(:cljd Object :clj Throwable :cljs :default) _ false)))


(defn- drop-socket!
  [entry]
  (when-let [close! (:close! (:socket entry))]
    (seam-ok? close!)))


(defn- terminal-event
  [id event]
  {:http/exchange id :http/event event})


(defn- finish!
  "Deposit the one terminal event of an already-claimed exchange.  An exchange
   whose request event is still being deposited has its terminal parked in the
   `:awaiting` slot; `accept!` deposits it straight after the request.  Which
   side deposits is decided by the committed transition (`swap-vals!`): the
   event parks only if the committed prior state still held the slot, and a
   slot `accept!` already removed means this call deposits directly."
  [boundary id entry event]
  (if (:requested? entry)
    (deposit! boundary (terminal-event id event))
    (let [[old _] (swap-vals! (:state boundary)
                              (fn [s]
                                (cond-> s
                                  (contains? (:awaiting s) id)
                                  (assoc-in [:awaiting id] event))))]
      (when-not (contains? (:awaiting old) id)
        (deposit! boundary (terminal-event id event))))))


(defn- claim!
  "Atomically remove exchange `id` from the open set; its entry, or nil when
   this boundary does not hold it open.  Ownership is the committed transition:
   the entry is read from the state the successful swap replaced, so of any
   number of competing claims exactly one answers non-nil."
  [boundary id]
  (let [[old _] (swap-vals! (:state boundary) update :open dissoc id)]
    (get-in old [:open id])))


(defn- close-claimed!
  "Observably gone: drop the socket this boundary holds and deposit the
   terminal `:http/closed`."
  [boundary id entry]
  (drop-socket! entry)
  (finish! boundary id entry :http/closed))


(defn close-exchange
  "The idempotent reclaim procedure for a `dao.lease.md` judge composed inside
   this boundary: close the socket of exchange `id` and deposit its terminal
   `:http/closed`.  Answers true when it closed an open exchange, false when
   there was none.  Not for code written against the contract."
  [boundary id]
  (if-let [entry (claim! boundary id)]
    (do (close-claimed! boundary id entry) true)
    false))


(defn open-exchanges
  "The ids of the exchanges currently held open, the inventory a
   `dao.lease.md` judge needs after a restart or an ingress `gap`."
  [boundary]
  (set (keys (:open @(:state boundary)))))


(defn- close-all!
  [boundary]
  (doseq [id (open-exchanges boundary)]
    (close-exchange boundary id)))


;; -----------------------------------------------------------------------------
;; Events in
;; -----------------------------------------------------------------------------

(defn- none?
  [x]
  (or (nil? x)
      (and (string? x) (empty? x))
      (and (bytes/bytes? x) (zero? (bytes/length x)))))


(defn- request-event
  [id {:keys [method path query headers body]}]
  (cond-> {:http/exchange id
           :http/event :http/request
           :http/method (keyword (str/lower-case (name method)))
           :http/path path
           :http/headers (into {} (map (fn [[k v]] [(str/lower-case (str k)) v])) headers)}
    (not (none? query)) (assoc :http/query query)
    (not (none? body)) (assoc :http/body body)))


(defn- body-size
  [body]
  (cond (nil? body) 0
        (string? body) (bytes/length (text/utf8-encode body))
        :else (bytes/length body)))


(defn accept!
  "Adapter entry: one valid, framed request arrived with its `socket` seam.
   `request` is `{:method :path :query :headers :body}` as the host parsed it.
   Answers `{:http/status :http/accepted :http/exchange id}`, or
   `{:http/status :http/full}`, `:http/too-large` or `:http/stopped` when the
   request must be answered below the transform (503, 413, 503) and was not
   deposited.  When the request event cannot be deposited the exchange's socket
   is closed with no synthesized response and `{:http/status :http/gone}` is
   answered."
  [boundary request socket]
  (cond
    (> (body-size (:body request)) (:max-body-bytes boundary))
    {:http/status :http/too-large}

    :else
    (let [outcome (volatile! nil)
          id (volatile! nil)]
      (swap! (:state boundary)
             (fn [s]
               (cond
                 (:stopped? s) (do (vreset! outcome :http/stopped) s)
                 (>= (count (:open s)) (:max-open boundary))
                 (do (vreset! outcome :http/full) s)
                 :else
                 (let [n (inc (:n s))
                       xid [(:incarnation boundary) n]]
                   (vreset! outcome :http/accepted)
                   (vreset! id xid)
                   (-> s
                       (assoc :n n)
                       (assoc-in [:open xid] {:socket socket :phase :new
                                              :requested? false})
                       (assoc-in [:awaiting xid] nil))))))
      (if-not (= :http/accepted @outcome)
        {:http/status @outcome}
        (let [xid @id
              ok? (deposit! boundary (request-event xid request))]
          (if-not ok?
            ;; Nowhere to land: the terminal event has none either.
            (let [entry (claim! boundary xid)]
              (swap! (:state boundary) update :awaiting dissoc xid)
              (when entry (drop-socket! entry))
              {:http/status :http/gone :http/exchange xid})
            (let [[old _] (swap-vals! (:state boundary)
                                      (fn [s]
                                        (cond-> (update s :awaiting dissoc xid)
                                          (contains? (:open s) xid)
                                          (assoc-in [:open xid :requested?] true))))]
              (when-let [event (get-in old [:awaiting xid])]
                (deposit! boundary (terminal-event xid event)))
              {:http/status :http/accepted :http/exchange xid})))))))


(defn peer-closed!
  "Adapter entry: the peer (or the connection) went away.  Deposits the
   terminal `:http/closed` once; a no-op for an exchange no longer open."
  [boundary id]
  (close-exchange boundary id))


;; -----------------------------------------------------------------------------
;; Effects out
;; -----------------------------------------------------------------------------

(defn- effect?
  [v]
  (and (map? v) (contains? v :http/exchange) (keyword? (:http/effect v))))


(defn- chunk-payload?
  [x]
  (or (string? x) (bytes/bytes? x)))


(defn- perform!
  "Perform one effect for an exchange this boundary holds open."
  [boundary id entry effect]
  (let [socket (:socket entry)
        phase (:phase entry)
        fail! (fn [] (when-let [e (claim! boundary id)] (close-claimed! boundary id e)))
        set-phase! (fn [p]
                     (swap! (:state boundary)
                            (fn [s]
                              (if (contains? (:open s) id)
                                (assoc-in s [:open id :phase] p)
                                s))))]
    (case (:http/effect effect)
      :http/head
      (if (and (= :new phase) (integer? (:http/status effect)))
        (if (seam-ok? (:head! socket) (:http/status effect) (or (:http/headers effect) {}))
          (set-phase! :streaming)
          (fail!))
        (fail!))

      :http/chunk
      (if (and (= :streaming phase) (chunk-payload? (:http/body effect)))
        (when-not (seam-ok? (:write! socket) (:http/body effect))
          (fail!))
        (fail!))

      :http/end
      (if (= :streaming phase)
        (when-let [e (claim! boundary id)]
          (if (seam-ok? (:end! socket))
            (finish! boundary id e :http/ended)
            (close-claimed! boundary id e)))
        (fail!))

      ;; An effect kind this interpreter does not know is not its own.
      nil)))


(defn- stop-listener!
  [boundary]
  (when-let [f (:on-stop @(:state boundary))]
    (try (f) (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil))))


(defn stop!
  "Boundary stop: close every open exchange (each deposits `:http/closed`),
   refuse further requests, and stop the listener.  Idempotent."
  [boundary]
  (swap! (:state boundary) assoc :stopped? true)
  (close-all! boundary)
  (stop-listener! boundary)
  boundary)


(defn on-stop!
  "Register the host listener's stop function, run when the boundary stops or
   its egress medium ends."
  [boundary f]
  (swap! (:state boundary) assoc :on-stop f)
  boundary)


(defn egress-step
  "Perform the effects the egress medium holds, in its order, until it has
   none more; never waits.  Answers the boundary."
  [boundary]
  (loop []
    (let [s @(:state boundary)]
      (when-not (:stopped? s)
        (let [r (stream/next (:egress boundary) (:cursor s))
              outcome (:dao.stream/outcome r)]
          (condp = outcome
            :dao.stream/ok
            (do (swap! (:state boundary) assoc :cursor (:dao.stream/cursor r))
                (let [v (:dao.stream/value r)]
                  (when (effect? v)
                    (let [id (:http/exchange v)]
                      (when-let [entry (get-in @(:state boundary) [:open id])]
                        (perform! boundary id entry v)))))
                (recur))

            :dao.stream/blocked nil

            ;; The step cannot say whose effects were lost.
            :dao.stream/gap
            (do (close-all! boundary)
                (swap! (:state boundary) assoc :cursor (:dao.stream/cursor r))
                (recur))

            ;; end, transport-error, or anything else: it can never answer again.
            (stop! boundary))))))
  boundary)
