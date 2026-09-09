(ns dao.stream.v2.memory-log
  "In-memory, unbounded, append-only DaoStream v2 transport: the
   complete-retention local log.  Process-lifetime, not durable across
   restart — a restarted process sees a new, empty logical stream with a
   new identity.  Durability is dao.jing's job, not this transport's.

   Retention is complete, and the absence of eviction is structural:
   state is one vector dense from position 0, and this namespace contains
   no expression that removes an element.  `gap` is therefore excluded
   (`:oldest` is position 0 for the life of the stream), as are `full`
   (no capacity is declared; the transport is logically unbounded and
   refuses nothing) and `transport-error` (each operation is one
   in-memory state transition that either completes and returns or does
   not return — the contract's line between observable exhaustion and
   fatal host failure, which lies outside the outcome algebra).

   There are no attachments: every handle is the logical stream's owner,
   so `cursor` never answers `:dao.stream/closed` and keeps minting after
   close, and `attach!` is absent rather than excluded.  Values are held
   by reference and never encoded, so no value is uncarriable.

   Creation specification: `{:dao.stream/type :dao.stream/memory-log}`.
   The transport owns the `dao.stream.memory-log` namespace and declares
   nothing in it, so any `:dao.stream.memory-log/…` key is rejected with
   `:dao.stream/invalid-spec` — most importantly a capacity, which is the
   knob this transport exists to make impossible.  Any other qualified
   key is ignored per the open-map rule."
  (:require [dao.stream.v2 :as stream]))


(def transport-type :dao.stream/memory-log)

(def ^:private own-namespace "dao.stream.memory-log")


(defn- result
  [outcome]
  {:dao.stream/outcome outcome})


(defn- permitted-key?
  "The contract: `:dao.stream/type` is the dispatch key, and every other
   key is qualified (dao.stream.md, *Creation*).  So an unqualified or
   non-keyword key is a malformed specification rather than a foreign
   extension the open-map rule would have us ignore.  A key in this
   transport's own namespace names a knob that does not exist, since that
   namespace is owned and empty."
  [k]
  (and (keyword? k)
       (or (= :dao.stream/type k)
           (and (some? (namespace k))
                (not= own-namespace (namespace k))))))


(defn- valid-spec?
  "A map, typed as this transport, whose every key is permitted.  Foreign
   qualified keys are ignored per the open-map rule; unqualified,
   non-keyword, and own-namespace keys are rejected."
  [spec]
  (and (map? spec)
       (= transport-type (:dao.stream/type spec))
       (every? permitted-key? (keys spec))))


(defn- fresh-state
  []
  ;; As in the ring buffer, the logical identity is plain data — it
  ;; travels in descriptors and cursors — never a host UUID object.
  ;; A vector, dense from 0: positions are never reused and never
  ;; dropped, and the tail is (count values).
  (atom {:identity (str (random-uuid))
         :values []
         :closed? false}))


(deftype MemoryLogHandle
  [state]

  stream/IDaoStreamDescriptor

  (descriptor
    [_]
    (let [s @state]
      {:dao.stream/outcome :dao.stream/ok
       :dao.stream/descriptor {:dao.stream/type transport-type
                               :dao.stream/identity (:identity s)}
       :dao.stream/identity (:identity s)}))


  stream/IDaoStreamReader

  (cursor
    [_ anchor]
    (let [s @state]
      ;; Every handle is the logical stream's owner: there is no
      ;; attachment to be closed, and an owner keeps minting cursors
      ;; after close so retained history stays readable.
      (case anchor
        ;; Complete retention: :oldest is the origin, before the first
        ;; append and after close alike.
        :dao.stream/oldest
        (assoc (result :dao.stream/ok)
               :dao.stream/cursor
               {:dao.stream.memory-log/identity (:identity s)
                :dao.stream.memory-log/position 0})
        :dao.stream/newest
        (assoc (result :dao.stream/ok)
               :dao.stream/cursor
               {:dao.stream.memory-log/identity (:identity s)
                :dao.stream.memory-log/position (count (:values s))})
        (result :dao.stream/invalid-anchor))))


  (next
    [_ cursor-value]
    ;; Total: the position is range-checked before any indexed read, so a
    ;; fabricated negative position answers invalid-cursor instead of
    ;; reaching nth and throwing on the JVM.  No cursor this stream can
    ;; mint holds a position above the tail — positions never disappear
    ;; and the tail never retreats — so an above-tail position is a
    ;; fabrication, invalid like a negative one.
    (let [s @state]
      (cond
        (not (map? cursor-value)) (result :dao.stream/invalid-cursor)
        (not (contains? cursor-value :dao.stream.memory-log/identity))
        (result :dao.stream/invalid-cursor)
        (not= (:identity s) (:dao.stream.memory-log/identity cursor-value))
        (result :dao.stream/cursor-mismatch)
        (not (integer? (:dao.stream.memory-log/position cursor-value)))
        (result :dao.stream/invalid-cursor)
        :else
        (let [pos (:dao.stream.memory-log/position cursor-value)
              tail (count (:values s))]
          (cond
            (or (< pos 0) (> pos tail)) (result :dao.stream/invalid-cursor)
            ;; Retention is complete: every position below the tail is
            ;; retained for the life of the logical stream, so this read
            ;; can never gap.
            (< pos tail)
            {:dao.stream/outcome :dao.stream/ok
             :dao.stream/value (nth (:values s) pos)
             :dao.stream/cursor {:dao.stream.memory-log/identity (:identity s)
                                 :dao.stream.memory-log/position (inc pos)}}
            (:closed? s) (result :dao.stream/end)
            :else (result :dao.stream/blocked))))))


  stream/IDaoStreamWriter

  (append!
    [_ value]
    ;; The closed? check lives inside the one state transition: a deref
    ;; before swap! would let an append that began before a close land
    ;; after it.  Unbounded means conj is the whole write path — no
    ;; capacity to consult, no refusal branch, nothing removed.
    (let [out (volatile! nil)]
      (swap! state
             (fn [s]
               (if (:closed? s)
                 (do (vreset! out (result :dao.stream/closed)) s)
                 (do (vreset! out (result :dao.stream/ok))
                     (update s :values conj value)))))
      @out))


  stream/IDaoStreamClosable

  (close!
    [_]
    ;; Owner close of the logical stream: future availability only.  The
    ;; assoc erases nothing — retained history stays readable, :oldest
    ;; stays at the origin, and reads at the tail answer end instead of
    ;; blocked.
    (swap! state assoc :closed? true)
    (result :dao.stream/ok)))


(defn create!
  "Create a memory log from a creation specification."
  [spec]
  (if-not (valid-spec? spec)
    (result :dao.stream/invalid-spec)
    (let [state (fresh-state)]
      {:dao.stream/outcome :dao.stream/ok
       :dao.stream/handle (MemoryLogHandle. state)
       :dao.stream/identity (:identity @state)})))
