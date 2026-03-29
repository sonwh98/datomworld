(ns dao.stream.link
  "Pure peer-sync protocol for stream replication.

   Link state: {:local-stream IStream
                :remote-stream IStream
                :local-pos int
                :remote-pos int
                :status keyword}

   All handlers are pure functions from state × message → [state' response-or-nil].
   No I/O."
  (:require
    [dao.stream :as ds]))


;; =============================================================================
;; Message constructors
;; =============================================================================

(defn put-msg
  [datoms from-pos]
  {:type :datom/put, :datoms datoms, :from-pos from-pos})


(defn sync-request-msg
  [from-pos]
  {:type :datom/sync-request, :from-pos from-pos})


(defn sync-response-msg
  [datoms from to]
  {:type :datom/sync-response, :datoms datoms, :from-pos from, :to-pos to})


(defn close-msg
  []
  {:type :stream/close})


(defn connect-msg
  [state]
  (sync-request-msg (:remote-pos state)))


;; =============================================================================
;; Internal: read stream into vec from position
;; =============================================================================

(defn- stream->vec-from
  [stream from-pos]
  (loop [c {:position from-pos}
         acc []]
    (let [r (ds/next stream c)]
      (if (map? r) (recur (:cursor r) (conj acc (:ok r))) acc))))


;; =============================================================================
;; Link state
;; =============================================================================

(defn make-link-state
  [local-stream]
  {:local-stream local-stream,
   :remote-stream (ds/make-ring-buffer-stream nil),
   :local-pos 0,
   :remote-pos 0,
   :status :connecting})


;; =============================================================================
;; Handlers
;; =============================================================================

(defn local-put
  "Put a datom into local-stream. Returns [state' put-msg].
   state' has :local-pos incremented."
  [state datom]
  (let [pos (:local-pos state)]
    (ds/put! (:local-stream state) datom)
    [(update state :local-pos inc) (put-msg [datom] pos)]))


(defn handle-put
  "Append incoming datoms to remote-stream. Returns state'."
  [state msg]
  (doseq [d (:datoms msg)] (ds/put! (:remote-stream state) d))
  (update state :remote-pos + (count (:datoms msg))))


(defn handle-sync-request
  "Read local-stream from msg's from-pos. Returns [state sync-response-msg]."
  [state msg]
  (let [from (:from-pos msg)
        datoms (stream->vec-from (:local-stream state) from)
        to (+ from (count datoms))]
    [state (sync-response-msg datoms from to)]))


(defn handle-sync-response
  "Append incoming datoms to remote-stream, set :status :connected. Returns state'."
  [state msg]
  (doseq [d (:datoms msg)] (ds/put! (:remote-stream state) d))
  (-> state
      (assoc :remote-pos (:to-pos msg))
      (assoc :status :connected)))


(defn handle-close
  "Close remote-stream and set :status :closed. Returns state'."
  [state]
  (ds/close! (:remote-stream state))
  (assoc state :status :closed))


(defn dispatch
  "Route msg to the correct handler. Returns [state' response-or-nil]."
  [state msg]
  (case (:type msg)
    :datom/put [(handle-put state msg) nil]
    :datom/sync-request (handle-sync-request state msg)
    :datom/sync-response [(handle-sync-response state msg) nil]
    :stream/close [(handle-close state) nil]))
