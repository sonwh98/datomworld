(ns daodb.stream
  (:require [clojure.core.async :as async]))

;; Protocol Definition
;; ===================

(defprotocol DaoStream
  "A transport-agnostic, asynchronous stream abstraction.
   Follows the Plan 9 inspiration: open/close, read/write."

  (write! [this val]
    "Asynchronously write `val` to the stream.
     Returns a channel/promise that resolves when the write is accepted.")

  (read! [this]
    "Asynchronously read a value from the stream.
     Returns a channel/promise that resolves to the next value.")

  (close! [this]
    "Closes the stream. Subsequent writes will fail.
     Subsequent reads will drain the buffer then return nil/EOF marker."))

;; Reference Implementation: Core.Async Wrapper
;; ============================================

(defrecord AsyncStream [ch]
  DaoStream
  (write! [this val]
    (async/put! ch val))

  (read! [this]
    (let [out (async/promise-chan)]
      (async/take! ch (fn [v]
                        (if (nil? v)
                          (async/close! out)
                          (async/put! out v))))
      out))

  (close! [this]
    (async/close! ch)))

(defn make-stream
  "Creates a new async stream with optional buffer size."
  ([] (make-stream 0))
  ([buf-or-n]
   ;; core.async channels match our semantics well for the reference impl.
   (->AsyncStream (async/chan buf-or-n))))

;; Plan 9 Style API (User Space)
;; =============================
;; These would ideally be used inside a `go` block or with callbacks.

;; Seq Abstraction (Consuming View)
;; ================================

(defn stream-seq
  "Returns a lazy sequence view of the stream.
   The sequence caches values as they are consumed (standard LazySeq behavior).
   Blocking: realized elements block on stream read."
  [stream]
  ((fn step []
     (lazy-seq
      (let [val-ch (read! stream)
            v (clojure.core.async/<!! val-ch)]
        (if (nil? v)
          nil
          (cons v (step))))))))
