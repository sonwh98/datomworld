(ns dao.stream.ble.sim
  "A deterministic in-process BLE radio for the dao.stream.ble tests: the
   virtual air, not the seam. The seam under test is always
   dao.stream.ble.links/seam -- the same host-free adapter the dart seam
   composes -- so what the simulation adds is only the medium: nodes
   that advertise on every `advertise!` round, GATT connections formed
   by the machines' own [:connect ...] actions, frames delivered along
   connections, and controls a real radio offers no test: `unlink!`
   tears connections down, `die!` kills a radio outright, `set-loss!`
   drops frames in flight, `set-write-fail!` fails the host write.

   Peer addresses are opaque strings of no structure, minted per
   observer and per connection -- the same physical node may present
   different addresses to the two ends of one pairing, which is the
   BLE fact the design's opacity rule protects. The sim never lets a
   test read a peer address as a node id, because nothing above the raw
   layer may.

   There are no threads and no clocks here: time is `:now`, advanced by
   `advance!`, and every radio fact is pumped by `pump!` in FIFO order,
   including everything pumping puts in flight. A test that drives the
   pump drives the world."
  (:require [dao.stream :as stream]
            [dao.stream.ble :as ble]
            [dao.stream.ble.links :as links]
            [dao.stream.ble.mesh :as mesh]
            [dao.stream.ringbuffer :as ringbuffer]))


(def default-ring-capacity
  "The traffic and deposit ring capacity of a sim node, in events."
  256)


(defn radio-net
  "The net: nodes, physical GATT connections keyed by id (each with the
   two endpoints and the peer address each end reports the other by),
   the advertisement addresses per observer pair, unreachable pairs,
   dead radios, a frame-loss predicate, and the queue pump! runs.
   `:now` starts at zero; `:rssi` is every advertisement's reported
   strength."
  []
  (atom {:nodes {}
         :conns {}
         :addr {}
         :peer-of {}
         :unreachable #{}
         :dead #{}
         :stopped #{}
         :write-fail #{}
         :loss-fn nil
         :queue []
         :now 0
         :rssi -60
         :counter 0}))


(defn advance!
  "Time moves. The discovery throttle's windows read this clock."
  [net ms]
  (swap! net update :now + ms)
  nil)


(defn unreachable!
  "`a` and `b` never hear each other: no advertisements, no links, no
   frames, as if out of range. Existing connections are left alone;
   `unlink!` tears those down."
  [net a b]
  (swap! net update :unreachable conj (if (neg? (compare a b)) [a b] [b a]))
  nil)


(defn set-loss!
  "A frame-loss predicate: (f from to bytes) -> truthy drops one frame
   in flight, silently, as the air does."
  [net f]
  (swap! net assoc :loss-fn f)
  nil)


(defn set-write-fail!
  "Every frame `a` sends toward `b` fails at the host write, reported as
   a [:send-fail ...] radio event -- the plugin-level failure a real
   stack answers with a write error, distinct from air loss."
  [net a b fail?]
  (swap! net (fn [n]
               (assoc n :write-fail
                      (if fail?
                        (conj (:write-fail n) [a b])
                        (disj (:write-fail n) [a b])))))
  nil)


(defn- reachable?
  [net a b]
  (and (not (contains? (:dead @net) a))
       (not (contains? (:dead @net) b))
       (not (contains? (:unreachable @net)
                       (if (neg? (compare a b)) [a b] [b a])))))


(defn- fresh-addr!
  "One opaque peer address, never reused."
  [net]
  (let [n (swap! net update :counter inc)]
    (str "peer-" (:counter n))))


(defn- note-addr!
  [net addr node-id]
  (swap! net assoc-in [:peer-of addr] node-id)
  addr)


(defn- addr-of!
  "The peer address `observer`'s radio reports `other` by in
   advertisements, minted on first observation, stable per pair --
   the advertisement is the stable thing; connections mint their own."
  [net observer other]
  (or (get-in @net [:addr observer other])
      (let [addr (note-addr! net (fresh-addr! net) other)]
        (swap! net assoc-in [:addr observer other] addr)
        addr)))


(defn addr
  "The advertisement address `observer` reports `other` by, or nil if
   they have never observed each other."
  [net observer other]
  (get-in @net [:addr observer other]))


(defn- conn-between
  "One [id conn role] for `owner`'s connection addressed `peer`, the
   role (:a or :b) saying which end `owner` holds, or nil."
  [net owner peer]
  (some (fn [[id c]]
          (cond
            (and (= (:a c) owner) (= (:addr-at-a c) peer)) [id c :a]
            (and (= (:b c) owner) (= (:addr-at-b c) peer)) [id c :b]))
        (:conns @net)))


(defn- feed!
  "One radio event for `node-id`'s machine, queued."
  [net node-id ev]
  (swap! net update :queue conj [node-id :event ev])
  nil)


(defn- establish!
  "One physical GATT connection out of `a`'s connect action: `a` dialed
   the advertisement address it observed, `b` mints a fresh address for
   the incoming connection -- the same pairing, two views, possibly two
   addresses. Both machines hear [:link-up ...] with the role each
   holds."
  [net a b dial-addr]
  (let [in-addr (note-addr! net (fresh-addr! net) a)
        id (fresh-addr! net)]
    (swap! net assoc-in [:conns id]
           {:a a :b b :addr-at-a dial-addr :addr-at-b in-addr})
    (feed! net a [:link-up dial-addr :central])
    (feed! net b [:link-up in-addr :peripheral])))


(defn- perform-connect!
  [net a peer]
  (let [b (get (:peer-of @net) peer)]
    (cond
      (nil? b)
      (feed! net a [:link-failed peer])

      (not (reachable? net a b))
      (feed! net a [:link-failed peer])

      :else
      (establish! net a b peer))))


(defn- perform-send-frame!
  [net a peer bs]
  (let [[_id c role] (conn-between net a peer)
        other (cond
                (nil? c) nil
                (= role :a) (:b c)
                :else (:a c))]
    (cond
      (nil? c)
      (feed! net a [:send-fail peer "no link"])

      (contains? (:write-fail @net) [a other])
      (feed! net a [:send-fail peer "write refused"])

      (and (:loss-fn @net) ((:loss-fn @net) a other bs))
      ;; The air ate it: no report ever reaches the sender.
      nil

      :else
      (feed! net other
             [:frame (if (= role :a) (:addr-at-b c) (:addr-at-a c)) bs]))
    nil))


(defn- perform!
  [net node-id action]
  (let [[kind peer arg] action]
    (case kind
      :connect (perform-connect! net node-id peer)
      :send-frame (perform-send-frame! net node-id peer arg)
      :stop-radio (swap! net update :stopped conj node-id)
      nil)))


(defn pump!
  "Run everything in flight -- radio events and the actions they emit,
   including everything pumping puts in flight -- in FIFO order, until
   the world is quiescent."
  [net]
  (loop []
    (let [q (:queue @net)]
      (when (seq q)
        (swap! net assoc :queue [])
        (doseq [[node-id kind x] q]
          (if (= kind :event)
            (when-some [seam (:seam (get (:nodes @net) node-id))]
              ((:radio! seam) x))
            (perform! net node-id x)))
        (recur))))
  nil)


(defn advertise!
  "One advertisement round: every live, unstopped node's radio is
   observed by every other live, unstopped, reachable node. The
   machines' connect policy and the throttle do the rest; `pump!` runs
   at the end, so everything the round emits has settled."
  [net]
  (let [ids (->> (:nodes @net)
                 (filter (fn [[id _n]]
                           (and (not (contains? (:dead @net) id))
                                (not (contains? (:stopped @net) id)))))
                 (map first)
                 sort)]
    (doseq [a ids
            b ids
            :when (and (not= a b) (reachable? net a b))]
      (feed! net a [:adv (addr-of! net a b) (:rssi @net)]))
    (pump! net)))


(defn join!
  "Add `node-id` to the net as a bound radio node: a traffic ring, a
   deposit ring, the host-free seam over the net's virtual radio, and
   the writer composed over both, per the design's two moves collapsed
   by the sim's synchronous bind. Answers the node map
   {:id id :traffic h :deposit h :seam s :writer h :descriptor d}. The
   mesh above it is the test's composition, not this one's."
  [net node-id & [{:keys [capacity max-links announce-window-ms
                          max-frame-bytes]}]]
  (when (contains? (:nodes @net) node-id)
    (throw (ex-info "a sim node id is joined once" {:node-id node-id})))
  (let [capacity (or capacity default-ring-capacity)
        ring (fn []
               (:dao.stream/handle
                 (ringbuffer/create!
                   {:dao.stream/type ringbuffer/transport-type
                    ringbuffer/capacity-key capacity})))
        traffic (ring)
        deposit (ring)
        clock (fn [] (:now @net))
        seam (links/seam
               {:identity node-id
                :deposit traffic
                :max-frame-bytes max-frame-bytes
                :max-links max-links
                :announce-window-ms announce-window-ms
                :now-ms clock
                :radio {:perform! (fn [action] (swap! net update :queue conj [node-id :action action]))}})
        descriptor {:dao.stream/type ble/transport-type
                    :dao.stream/identity node-id
                    :dao.stream.ble/self node-id}
        node {:id node-id
              :traffic traffic
              :deposit deposit
              :seam seam
              :writer (ble/writer seam descriptor
                                  (or max-frame-bytes
                                      ble/default-max-frame-bytes))
              :descriptor descriptor}]
    (swap! net assoc-in [:nodes node-id] node)
    (feed! net node-id [:radio-up node-id])
    (pump! net)
    node))


(defn node
  "The node map `join!` answered, by id."
  [net node-id]
  (get (:nodes @net) node-id))


(defn unlink!
  "Tear down every physical GATT connection between `a` and `b`: each
   end's machine hears [:link-down ...] for the address it holds for
   that connection, and the sim's connection records are cleared."
  [net a b]
  (let [conns (->> (:conns @net)
                   (filter (fn [[_ c]]
                             (or (and (= (:a c) a) (= (:b c) b))
                                 (and (= (:a c) b) (= (:b c) a))))))]
    (doseq [[id c] conns]
      (swap! net update :conns dissoc id)
      (feed! net (:a c) [:link-down (:addr-at-a c)])
      (feed! net (:b c) [:link-down (:addr-at-b c)]))
    (pump! net)))


(defn die!
  "Kill `node-id`'s radio outright: every peer's connection to it goes
   down (the peer hears [:link-down ...]; the dead node hears nothing,
   as a dead radio reports nothing) and the node itself is taken off
   the air -- no advertisements, no deliveries, and connects to it fail
   with [:link-failed ...]."
  [net node-id]
  (let [conns (->> (:conns @net)
                   (filter (fn [[_ c]]
                             (or (= (:a c) node-id) (= (:b c) node-id)))))]
    (doseq [[id c] conns]
      (swap! net update :conns dissoc id)
      (let [[survivor addr]
            (if (= (:a c) node-id)
              [(:b c) (:addr-at-b c)]
              [(:a c) (:addr-at-a c)])]
        (feed! net survivor [:link-down addr])))
    (swap! net update :dead conj node-id)
    (pump! net)))


(defn linked?
  "True iff the sim holds at least one physical GATT connection between
   `a` and `b`."
  [net a b]
  (boolean
    (some (fn [[_ c]]
            (or (and (= (:a c) a) (= (:b c) b))
                (and (= (:a c) b) (= (:b c) a))))
          (:conns @net))))


(defn values
  "Every value a reader handle currently retains, oldest first -- the
   tests' one observation primitive."
  [h]
  (loop [c (:dao.stream/cursor (stream/cursor h stream/anchor-oldest))
         acc []]
    (let [r (stream/next h c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn events
  "The lifecycle events on `node`'s traffic ring, oldest first."
  [net node-id]
  (->> (values (:traffic (node net node-id)))
       (filter ble/lifecycle-event?)))


(defn frames
  "The frame events on `node`'s traffic ring, oldest first."
  [net node-id]
  (->> (values (:traffic (node net node-id)))
       (filter ble/frame-event?)))


(defn deliveries
  "The mesh delivery events on `node`'s deposit ring, oldest first."
  [net node-id]
  (->> (values (:deposit (node net node-id)))
       (filter mesh/delivery-event?)))
