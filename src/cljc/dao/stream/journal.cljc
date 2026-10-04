(ns dao.stream.journal
  "A durable, complete-retention DaoStream transport: every appended value
   is one canonical CBOR frame on a backend, and the visible log is a
   dao.stream.memory-log rebuilt from those frames at open
   (docs/design/dao.stream.journal.md).

   Frames: frame 0 is the header
   `{:dao.stream.journal/header {:version 1 :identity i}}`; frame p + 1 is
   `{:dao.stream.journal/position p :dao.stream.journal/value v}`, p dense
   from 0.  Equal values at different positions are different frames, so
   repeated equal appends are kept.  The identity lives in the header,
   and cursors are the visible log's positions, so both are the same
   after a reopen of the same frames.

   `append!` encodes, writes one frame, then makes the value visible.  A
   value outside the canonical codec answers `:dao.stream/invalid-value`
   and writes nothing.  A backend failure or throw, or a failure between
   the write and visibility, answers `:dao.stream/transport-error` and
   poisons the handle for appends: whether the frame persisted is
   unknown, so only a reopen, which reads the frames, can say.  Reads
   keep serving the visible log.  At position `max-position` the next
   append is refused with transport-error and writes nothing; nothing
   wraps.

   `open!` is a host act over a backend, not `create!`: an empty backend
   gets a header with a fresh identity, a non-empty one is replayed.  A
   final frame that does not decode is a torn write and is truncated; a
   missing header, a position gap, a malformed frame or an unreadable
   frame before the tail refuses the open.  There is no close surface
   and no attachment: every handle is the logical stream's owner.

   The backend is a map of three functions answering outcome maps, so a
   file backend (slice C2) plugs in behind the same seam:
     ::frames       (fn [])   -> ok with ::frames, a vector of byte arrays
     ::write-frame! (fn [bs]) -> ok once the frame is durable
     ::truncate!    (fn [n])  -> ok once only the first n frames remain
   A throw is a failure like any non-ok answer.  `memory-backend` is the
   in-process one, over a frame vector its caller holds."
  (:require [dao.jing.cbor :as cbor]
            [dao.stream :as stream]
            [dao.stream.chunks :as chunks]
            [dao.stream.memory-log :as log]))


(def transport-type :dao.stream/journal)

(def ^:private version 1)


(def max-position
  "The largest position a frame may hold, 2^52 - 1: exact on every host."
  4503599627370495)


(defn- result
  [outcome]
  {:dao.stream/outcome outcome})


(defn- ok?
  [answer]
  (= :dao.stream/ok (:dao.stream/outcome answer)))


(defn- failure
  "A transport-error naming why, under ::defect."
  [defect]
  (assoc (result :dao.stream/transport-error) ::defect defect))


(defn- call
  "Run one backend operation.  A throw answers as a failure."
  [f & args]
  (try
    (apply f args)
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      (failure :backend-threw))))


(defn- with-lock
  "Serialize appends on hosts with shared-memory threads.  ClojureScript
   and ClojureDart calls are synchronous within one isolate."
  #_{:clj-kondo/ignore [:unused-binding]}
  [lock f]
  #?(:cljd (f)
     :clj (locking lock (f))
     :default (f)))


;; ==========================================================================
;; Memory backend
;; ==========================================================================

(defn- crash
  [cut]
  (ex-info "dao.stream.journal crash cut" {::cut cut}))


(def memory-durability
  "What a memory journal survives: nothing beyond the caller's atom.  The
   file backend's declaration shape (dao.stream.journal.file/durability),
   so every backend answers one shape."
  {::backend :memory
   ::failure-model :none
   ::lock-kind :none
   ::persisted #{}})


(defn memory-backend
  "A backend over `frames`, an atom holding a vector of byte arrays that
   the caller keeps and reopens.  `cut`, when not nil, is a crash cut the
   first frame write through this backend suffers, then a throw:
     :before-frame               nothing is persisted
     :after-frame-before-visible the whole frame is persisted
     :torn-frame                 the first half of the frame is persisted
   It carries `memory-durability` under ::durability."
  [frames cut]
  (let [armed (atom cut)]
    {::durability (fn [] memory-durability)
     ::frames (fn [] (assoc (result :dao.stream/ok) ::frames @frames))
     ::write-frame!
     (fn [bs]
       (let [c @armed]
         (reset! armed nil)
         (case c
           nil (do (swap! frames conj bs) (result :dao.stream/ok))
           :before-frame (throw (crash c))
           :after-frame-before-visible (do (swap! frames conj bs)
                                           (throw (crash c)))
           :torn-frame (do (swap! frames conj
                                  (chunks/slice bs 0
                                                (quot (chunks/length bs) 2)))
                           (throw (crash c))))))
     ::truncate! (fn [n]
                   (swap! frames #(into [] (take n) %))
                   (result :dao.stream/ok))}))


;; ==========================================================================
;; Frames
;; ==========================================================================

(defn- decode-frame
  "`{:frame v}` for readable bytes, else nil: no decoded value can be
   mistaken for the unreadable answer."
  [bs]
  (try
    {:frame (cbor/decode bs)}
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      nil)))


(defn- header-identity
  "The identity a header frame names, or nil when v is not one."
  [v]
  (let [h (when (and (map? v) (= #{::header} (set (keys v))))
            (::header v))]
    (when (and (map? h)
               (= #{:version :identity} (set (keys h)))
               (= version (:version h))
               (string? (:identity h)))
      (:identity h))))


(defn- entry-defect
  "Why v is not the entry frame for position p, or nil when it is."
  [v p]
  (cond
    (not (and (map? v) (= #{::position ::value} (set (keys v)))))
    :malformed-frame
    (not= p (::position v)) :position-gap
    :else nil))


(defn- replay
  "Read decoded frames.  Answers ::kept, the count of frames that stand
   once a torn final frame is dropped, with ::identity and ::values when
   a header stands, or ::defect."
  [frames]
  (let [decoded (mapv decode-frame frames)
        kept (if (and (seq decoded) (nil? (peek decoded)))
               (pop decoded)
               decoded)]
    (cond
      (empty? kept) {::kept 0}
      (some nil? kept) {::defect :unreadable-frame}
      :else
      (if-let [i (header-identity (:frame (first kept)))]
        (loop [p 0 vs [] es (map :frame (rest kept))]
          (if (seq es)
            (if-let [d (entry-defect (first es) p)]
              {::defect d}
              (recur (inc p) (conj vs (::value (first es))) (rest es)))
            {::kept (count kept) ::identity i ::values vs}))
        {::defect :missing-header}))))


;; ==========================================================================
;; Handle
;; ==========================================================================

(defn- append-frame!
  "Encode, persist, then make visible.  Runs under the handle's lock."
  [backend vlog state bound value]
  (let [{:keys [position poisoned?]} @state
        poison! (fn [defect]
                  (swap! state assoc :poisoned? true)
                  (failure defect))]
    (if poisoned?
      (failure :poisoned)
      (if (> position bound)
        (failure :position-bound)
        (let [bs (try
                   (cbor/encode {::position position ::value value})
                   (catch #?(:cljd Object :clj Throwable :cljs :default) _
                     nil))]
          (if (nil? bs)
            (result :dao.stream/invalid-value)
            (if-not (ok? (call (::write-frame! backend) bs))
              (poison! :write-failed)
              ;; Visible is the persisted value as a reopen will decode
              ;; it, never the caller's host value.
              (if (ok? (call (fn []
                               (stream/append!
                                 vlog
                                 (::value (cbor/decode-snapshot bs))))))
                (do (swap! state update :position inc)
                    (result :dao.stream/ok))
                (poison! :visibility-failed)))))))))


(deftype JournalHandle
  [backend vlog state identity bound]

  stream/IDaoStreamDescriptor

  (descriptor
    [_]
    {:dao.stream/outcome :dao.stream/ok
     :dao.stream/descriptor {:dao.stream/type transport-type
                             :dao.stream/identity identity}
     :dao.stream/identity identity})


  stream/IDaoStreamReader

  (cursor
    [_ anchor]
    (stream/cursor vlog anchor))


  (next
    [_ cursor-value]
    (stream/next vlog cursor-value))


  stream/IDaoStreamWriter

  (append!
    [_ value]
    (with-lock state #(append-frame! backend vlog state bound value))))


(defn- opened
  [backend bound identity values]
  {:dao.stream/outcome :dao.stream/ok
   :dao.stream/handle (JournalHandle. backend
                                      (log/restore identity values)
                                      (atom {:position (count values)
                                             :poisoned? false})
                                      identity
                                      bound)
   :dao.stream/identity identity})


(defn open!
  "Open the journal on `backend`.  `opts` may carry ::max-position, a
   lower position bound for tests.  Answers ok with the handle and its
   identity, or transport-error with ::defect, having written nothing
   except a header on an empty backend or the truncation of a torn
   final frame."
  ([backend] (open! backend nil))
  ([backend opts]
   (let [bound (get opts ::max-position max-position)
         read (call (::frames backend))
         frames (::frames read)
         r (when (ok? read) (replay frames))]
     (cond
       (not (ok? read)) (failure :read-failed)
       (::defect r) (failure (::defect r))
       (and (< (::kept r) (count frames))
            (not (ok? (call (::truncate! backend) (::kept r)))))
       (failure :truncate-failed)
       (::identity r) (opened backend bound (::identity r) (::values r))
       :else
       (let [i (str (random-uuid))
             header {::header {:version version :identity i}}]
         (if (ok? (call (::write-frame! backend) (cbor/encode header)))
           (opened backend bound i [])
           (failure :write-failed)))))))
