(ns dao.stream.ble.mesh
  "The flooding mesh of docs/design/dao.stream.ble.md 7: the value channel
   over the raw BLE layer, what dao.stream.udp is to the datagram layer.

   One CBOR value, canonical profile (dao.stream.cbor), rides one mesh
   frame, which rides one raw frame -- no fragmentation in S1; an encoded
   value over the raw budget is refused at send! as the raw writer's
   invalid-value. A mesh frame is a CBOR map keyed under
   :dao.stream.ble.mesh/ whose :v key is the discriminator this layer
   owns on a shared radio (dao.stream.datagram.md 6): the mesh owns
   exactly the raw frames whose decoded value is a map carrying it, and
   drops maps carrying no such key. A frame with the key but malformed
   fields is foreign the same way -- a newer or broken dialect, not a
   defect to diagnose on any shared stream.

   Routing is flooding and flooding is all of it. A received frame whose
   [origin seq] is new is remembered (bounded, oldest evicted), delivered
   locally, and relayed with ttl decremented to every current neighbor
   except the source peer; a frame remembered or at ttl 0 delivers only
   the first thing or nothing. The neighbor set is the raw layer's, read
   from the traffic stream's link-up/link-down lifecycle events as the
   mesh steps. There is no route computation: a peer address is opaque
   and link-scoped (dao.stream.ble.md 2), so no route table above the
   raw layer could be honest. Flooding tolerates loss: a failed relay is
   a lost frame, never retried here, the raw traffic stream's send-failed
   events being the data a composition that wants to observe loss
   observes.

   Deduplication is bounded, not timely: :max-remembered [origin seq]
   keys, oldest evicted, no clock, no freshness judgment. A replayed old
   sequence outside the cache window is re-delivered and re-relayed, and
   idempotence of the payload is the payload's -- the same rule
   dao.stream.md (Writing) states for retries.

   The mesh value, `make-mesh`, is an atom-backed protocol state, the
   dao.stream.udp port shape. The composition drives `mesh-step!` at its
   cadence, one owner per mesh; sends answer immediately what is true
   now (dao.stream.md, The IO Model) -- a send before the first step
   floods to zero neighbors and answers ok, the cadence being the
   composition's."
  (:require [dao.stream :as stream]
            [dao.stream.base64 :as base64]
            [dao.stream.ble :as ble]
            [dao.stream.cbor :as cbor]))


(def version
  "The mesh dialect this namespace speaks."
  1)


(def default-ttl
  "The default hop budget of an origin send: six relays is three to five
   hops of margin around the Bluetooth Mesh default without the
   thundering herd a large ttl makes of a dense mesh."
  6)


(def default-max-remembered
  "The default dedup cache bound, in [origin seq] keys, oldest evicted
   first."
  4096)


(def default-step-budget
  "The default mesh-step! budget: raw events consumed in one step when
   the composition does not name one."
  64)


(def max-frame-bytes-default
  "The raw budget a composition that names none is expected to bind its
   seam with. The mesh does not enforce it -- the raw layer does, both
   ways -- and carries no copy of it; send! answers whatever the raw
   writer answers for an oversize value: invalid-value."
  ble/default-max-frame-bytes)


;; =============================================================================
;; Frames and deposits (section 7)
;; =============================================================================


(defn frame?
  "True iff `m` is one of this layer's frames: the discriminator at this
   dialect's version, an origin identity, a non-negative seq and ttl,
   and a payload value the CBOR codec already admitted (decode
   guarantees it; a hand-built frame is judged by the same fields)."
  [m]
  (and (map? m)
       (= version (:dao.stream.ble.mesh/v m))
       (string? (:dao.stream.ble.mesh/origin m))
       (pos? (count (:dao.stream.ble.mesh/origin m)))
       (integer? (:dao.stream.ble.mesh/seq m))
       (not (neg? (:dao.stream.ble.mesh/seq m)))
       (integer? (:dao.stream.ble.mesh/ttl m))
       (not (neg? (:dao.stream.ble.mesh/ttl m)))))


(defn frame
  "One mesh frame over `origin`, `seq`, `ttl` and the payload `value`."
  [origin seq ttl value]
  {:dao.stream.ble.mesh/v version
   :dao.stream.ble.mesh/origin origin
   :dao.stream.ble.mesh/seq seq
   :dao.stream.ble.mesh/ttl ttl
   :dao.stream.ble.mesh/value value})


(defn delivery-event
  "One mesh value delivered to this node: the payload, its origin and
   seq for correlation, and the source peer the frame arrived on."
  [peer origin seq value]
  {:dao.stream.ble.mesh/source peer
   :dao.stream.ble.mesh/origin origin
   :dao.stream.ble.mesh/seq seq
   :dao.stream.ble.mesh/value value})


(defn delivery-event?
  "True iff `v` is one local delivery of section 7. Observers of a mesh's
   deposit medium judge every value with this."
  [v]
  (and (map? v)
       (string? (:dao.stream.ble.mesh/source v))
       (pos? (count (:dao.stream.ble.mesh/source v)))
       (string? (:dao.stream.ble.mesh/origin v))
       (pos? (count (:dao.stream.ble.mesh/origin v)))
       (integer? (:dao.stream.ble.mesh/seq v))
       (not (neg? (:dao.stream.ble.mesh/seq v)))))


(defn- decode-frame
  "The frame one raw frame event carries, or nil when it is not this
   layer's: not strict Base64 (the raw layer would never deposit that),
   not decodable CBOR, or not a frame of section 7. A foreign or
   malformed frame is skipped, never answered, never diagnosed on any
   shared stream."
  [text]
  (when-let [bs (base64/decode text)]
    (try
      (let [m (cbor/decode bs)]
        (when (frame? m) m))
      (catch #?(:cljd Object :clj Throwable :cljs :default) _
        nil))))


(defn- encode-frame
  "The Base64 of `frame`'s canonical CBOR, or nil when the frame is
   outside the portable domain -- a refusal the raw writer never has to
   see."
  [m]
  (try
    (base64/encode (cbor/encode m))
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      nil)))


;; =============================================================================
;; The mesh value
;; =============================================================================


(defn make-mesh
  "The mesh over one bound raw node: `:raw-writer` is the node's writer
   handle (dao.stream.ble/writer), `:raw-traffic` its traffic ring,
   `:raw-cursor` the cursor this mesh reads it from -- the composition's
   choice of :oldest (replay what the ring still holds) or :newest (live
   only); `:deposit` is the mesh's own deposit medium, the composition
   wires an evicting ring for the datagram reasons (design 7).
   `:identity` is the origin identity frames carry, composition minted,
   unauthenticated, whose only office is loop prevention and
   correlation. `:ttl` bounds a send's hops, `:max-remembered` bounds
   the dedup cache."
  [{:keys [identity raw-writer raw-traffic raw-cursor deposit
           ttl max-remembered]}]
  (when-not (and (string? identity) (pos? (count identity)))
    (throw (ex-info "a dao.stream.ble mesh needs :identity, its origin"
                    {:identity identity})))
  (when-not (stream/writer? raw-writer)
    (throw (ex-info "a dao.stream.ble mesh needs :raw-writer, the node's writer handle"
                    {:raw-writer raw-writer})))
  (when-not (stream/reader? raw-traffic)
    (throw (ex-info "a dao.stream.ble mesh needs :raw-traffic, the node's traffic ring"
                    {:raw-traffic raw-traffic})))
  (when-not (stream/writer? deposit)
    (throw (ex-info "a dao.stream.ble mesh needs :deposit, an evicting ring the composition wires"
                    {:deposit deposit})))
  (let [c (:dao.stream/cursor (stream/cursor raw-traffic raw-cursor))]
    (when-not c
      (throw (ex-info "the raw traffic ring refused :raw-cursor"
                      {:raw-cursor raw-cursor})))
    (atom {:identity identity
           :raw-writer raw-writer
           :raw-traffic raw-traffic
           :raw-cursor c
           :deposit deposit
           :ttl (or ttl default-ttl)
           :max-remembered (or max-remembered default-max-remembered)
           :next-seq 0
           :neighbors #{}
           :seen {}
           :seen-queue []})))


(defn- remember
  "One [origin seq] key remembered: into the set and the eviction queue,
   oldest evicted past :max-remembered."
  [s k]
  (let [seen (assoc (:seen s) k true)
        q (conj (:seen-queue s) k)
        over (- (count q) (:max-remembered s))]
    (if (pos? over)
      (let [dropped (subvec q 0 over)]
        (assoc s
               :seen (apply dissoc seen dropped)
               :seen-queue (subvec q over)))
      (assoc s :seen seen :seen-queue q))))


(defn- fan-out!
  "One frame's Base64 appended toward EVERY neighbor in `peers` through
   the raw writer -- every append happens, whatever the one before it
   answered: a lazy sequence here would let one stale neighbor silently
   skip the append to every neighbor after it, and the loss of a flood
   is exactly what flooding's redundancy is not allowed to multiply.
   Answers {:outcomes [...] :first-refusal r} -- one outcome per peer in
   order, and the first non-ok outcome among them, nil when every append
   answered ok. The send-failed events on the raw traffic ring are the
   loss record; flooding tolerates loss."
  [raw-writer peers b64]
  (let [outcomes (mapv (fn [peer]
                         (stream/append!
                           raw-writer
                           {:dao.stream.ble/destination peer
                            :dao.stream.ble/bytes b64}))
                       peers)
        first-refusal (some (fn [r]
                              (when-not (= :dao.stream/ok (:dao.stream/outcome r))
                                r))
                            outcomes)]
    {:outcomes outcomes :first-refusal first-refusal}))


(defn send!
  "One origin send: `value` encoded canonical CBOR, wrapped in a frame
   with this mesh's origin, its next seq and its configured ttl, fanned
   out to every current neighbor. Answers the raw writer's first non-ok
   outcome -- an unencodable value is refused as invalid-value before
   the raw writer is touched, nothing was appended -- and ok when every
   append answered ok, which asserts nothing about the air. The seq
   advances when at least one link accepted the frame: a seq no link
   accepted was heard by nobody, and reusing it is safe; a seq any link
   accepted is spent forever, for the dedup cache's sake."
  [mesh value]
  (let [{:keys [identity raw-writer ttl neighbors next-seq]} @mesh
        f (frame identity next-seq ttl value)]
    (if-some [b64 (encode-frame f)]
      (if (empty? neighbors)
        ;; Nobody to flood to is not a refusal: the frame crossed no
        ;; link, the seq stays unspent, and the composition's cadence
        ;; drives when links have been learned.
        {:dao.stream/outcome :dao.stream/ok}
        (let [{:keys [outcomes first-refusal]}
              (fan-out! raw-writer neighbors b64)
              accepted? (some #(= :dao.stream/ok (:dao.stream/outcome %))
                              outcomes)]
          (when accepted?
            ;; The seq is spent when at least one link accepted the
            ;; frame, and the spend is remembered: this node's own send
            ;; occupies the dedup cache exactly as a received frame
            ;; would, so the copy a redundant link brings back delivers
            ;; nothing and relays nothing. A seq any link accepted is
            ;; spent forever -- the node that accepted the first copy
            ;; would dedup a reused seq's second value away -- and a seq
            ;; no link accepted is forgotten by everyone, ourselves
            ;; included.
            (swap! mesh (fn [s]
                          (-> s
                              (assoc :next-seq (inc next-seq))
                              (remember [identity next-seq])))))
          (or first-refusal {:dao.stream/outcome :dao.stream/ok})))
      {:dao.stream/outcome :dao.stream/invalid-value
       :dao.stream.ble.mesh/reason "value is outside the portable domain"})))


(defn- step-event!
  "One raw traffic event consumed into the mesh: lifecycle events keep
   the neighbor set current, frame events deliver and relay. Answers
   {:delivered n :relayed n}, zeros for a skipped event."
  [mesh v]
  (cond
    (ble/lifecycle-event? v)
    (do (case (:dao.stream.ble/event v)
          :dao.stream.ble/link-up
          (swap! mesh update :neighbors conj (:dao.stream.ble/peer v))
          :dao.stream.ble/link-down
          (swap! mesh update :neighbors disj (:dao.stream.ble/peer v))
          :dao.stream.ble/peer-lost
          (swap! mesh update :neighbors disj (:dao.stream.ble/peer v))
          ;; The raw layer says the node itself is gone: no link fact
          ;; this mesh held survives it, and unlike a gap there is
          ;; nothing stale-but-usable here -- the neighbors are gone.
          :dao.stream.ble/closed
          (swap! mesh assoc :neighbors #{})
          nil)
        {:delivered 0 :relayed 0})

    (ble/frame-event? v)
    (let [source (:dao.stream.ble/source v)
          f (decode-frame (:dao.stream.ble/bytes v))]
      (if-not f
        {:delivered 0 :relayed 0}
        (let [origin (:dao.stream.ble.mesh/origin f)
              sq (:dao.stream.ble.mesh/seq f)
              k [origin sq]]
          (if (contains? (:seen @mesh) k)
            {:delivered 0 :relayed 0}
            (do (swap! mesh remember k)
                (try (stream/append!
                       (:deposit @mesh)
                       (delivery-event source origin sq
                                       (:dao.stream.ble.mesh/value f)))
                     (catch #?(:cljd Object :clj Throwable :cljs :default) _
                       nil))
                (let [ttl (min (:dao.stream.ble.mesh/ttl f) (:ttl @mesh))]
                  ;; A wire ttl above this mesh's own budget is clamped
                  ;; to it: amplification is bounded by the configuration
                  ;; this composition chose, not by what a frame claims.
                  (if-not (pos? ttl)
                    {:delivered 1 :relayed 0}
                    (let [b64 (encode-frame
                                (assoc f :dao.stream.ble.mesh/ttl (dec ttl)))
                          r (when b64
                              (fan-out! (:raw-writer @mesh)
                                        (remove #(= source %) (:neighbors @mesh))
                                        b64))]
                      ;; A failed relay is a lost frame, never retried
                      ;; here; the refusal is dropped, the raw
                      ;; send-failed events being the loss record.
                      {:delivered 1
                       :relayed (cond
                                  (not b64) 0
                                  (some #(= :dao.stream/ok (:dao.stream/outcome %))
                                        (:outcomes r)) 1
                                  :else 0)}))))))))

    :else
    ;; Neither this layer's: another interpreter's value, or a host fact
    ;; no rule here -- skipped, never diagnosed.
    {:delivered 0 :relayed 0}))


(defn mesh-step!
  "One step reading the raw traffic ring into the mesh: read to blocked
   from the mesh's own raw cursor, bounded by `budget` events, keep the
   neighbor set current from lifecycle events, deliver and relay owned
   frames. A raw gap adopts the recovery cursor and the step continues --
   those events are lost, indistinguishable from radio loss, and
   flooding's redundancy recovers exactly as it does for network loss.
   Answers {:processed n :delivered n :relayed n :status :drained |
   :budget}. One owner per mesh drives this, at the composition's
   cadence."
  ([mesh] (mesh-step! mesh default-step-budget))
  ([mesh budget]
   (let [{:keys [raw-traffic]} @mesh
         stop (volatile! nil)
         counts (volatile! {:processed 0 :delivered 0 :relayed 0})]
     (loop [left budget]
       (when (pos? left)
         (let [r (stream/next raw-traffic (:raw-cursor @mesh))]
           (case (:dao.stream/outcome r)
             :dao.stream/ok
             (do (vswap! counts
                         (fn [c]
                           (let [{:keys [delivered relayed]}
                                 (step-event! mesh (:dao.stream/value r))]
                             (-> c
                                 (update :processed inc)
                                 (update :delivered + delivered)
                                 (update :relayed + relayed)))))
                 (swap! mesh assoc :raw-cursor (:dao.stream/cursor r))
                 (recur (dec left)))

             :dao.stream/blocked
             (vreset! stop :drained)

             :dao.stream/gap
             ;; A raw gap adopts the recovery cursor and the step
             ;; continues: those events are lost, indistinguishable
             ;; from radio loss, and flooding's redundancy recovers
             ;; exactly as it does for network loss. Link facts the
             ;; gap swallowed may leave the neighbor set stale -- a
             ;; dead neighbor's relays answer send-failed and cost
             ;; nothing, while a cleared set would cost the mesh every
             ;; relay it has, and a stable link emits no link-up to
             ;; refill it. Stale-but-usable is the honest choice, and
             ;; the next link-down corrects it.
             (do (swap! mesh assoc :raw-cursor (:dao.stream/cursor r))
                 (recur (dec left)))

             ;; A closed or failed raw ring stops the step; the mesh
             ;; answers what it processed. The composition re-binds or
             ;; tears down; this layer invents neither.
             (vreset! stop :drained)))))
     (assoc @counts :status (or @stop :budget)))))
