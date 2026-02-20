(ns dao.stream.storage
  "Storage protocol for DaoStream.

  Streams are append-only logs. Storage is the pluggable backend
  that holds the value sequence. This namespace defines the protocol
  and provides an in-memory implementation.

  Storage is a pure data structure: append returns a new storage,
  it does not mutate in place."
  (:refer-clojure :exclude [length]))


;; =============================================================================
;; Storage Protocol
;; =============================================================================

(defprotocol IStreamStorage

  (append
    [this val]
    "Append a value to the log. Returns updated storage.")

  (read-at
    [this pos]
    "Read the value at position pos. Returns nil if out of range.")

  (length
    [this]
    "Current number of values in the log."))


;; =============================================================================
;; In-Memory Backend
;; =============================================================================

(defrecord MemoryStorage
  [log]

  IStreamStorage

  (append [this val] (assoc this :log (conj log val)))


  (read-at [this pos] (get log pos))


  (length [this] (count log)))


(defn memory-storage
  "Create an empty in-memory storage backed by a vector."
  []
  (->MemoryStorage []))
