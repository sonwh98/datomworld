(ns dao.stream.storage
  "Storage protocol for DaoStream.

  Streams are append-only logs. Storage is the pluggable backend
  that holds the datom sequence. This namespace defines the protocol
  and provides an in-memory implementation.

  Storage is a pure data structure: s-append returns a new storage,
  it does not mutate in place.")


;; =============================================================================
;; Storage Protocol
;; =============================================================================

(defprotocol IStreamStorage

  (s-append
    [this datom]
    "Append a datom to the log. Returns updated storage.")

  (s-read-at
    [this pos]
    "Read the datom at position pos. Returns nil if out of range.")

  (s-length
    [this]
    "Current number of datoms in the log."))


;; =============================================================================
;; In-Memory Backend
;; =============================================================================

(defrecord MemoryStorage
  [log]

  IStreamStorage

  (s-append [this datom] (assoc this :log (conj log datom)))


  (s-read-at [this pos] (get log pos))


  (s-length [this] (count log)))


(defn memory-storage
  "Create an empty in-memory storage backed by a vector."
  []
  (->MemoryStorage []))
