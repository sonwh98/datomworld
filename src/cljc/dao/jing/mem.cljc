(ns dao.jing.mem
  "An ephemeral, thread-safe, content-addressed in-memory DaoJing content
   store (docs/design/dao.jing.md, Materialization rule and Reads).

   (create-content-mem) returns a plain-data content handle carrying explicit
   private state plus :put-content-fn, :get-content-fn, and :close-fn,
   accepted by dao.jing/materialize!, dao.jing/get, and dao.jing/close!.
   The handle embeds no intake stream and identifies no source: an address
   is derived solely from a payload, and the store never records which
   stream carried a value, so equal payloads converge on exactly one entry.

   :put-content-fn is an atomic insert-if-absent over the state atom. The
   caller-supplied address must be a :segment/sha256-... content address
   whose hash matches the payload; otherwise it throws. An address already
   holding the same payload reports :present and is never overwritten; an
   address already holding an unequal payload is an integrity-failure
   collision and throws loudly. The CAS loop is linearizable under JVM
   contention. Operations after close throw; close itself is idempotent."
  (:require [dao.jing :as jing]))


(defn- validate-address-payload!
  "Reject a put before any write: the address must be a valid
   :segment/sha256-... content address and must hash to the exact payload."
  [address payload]
  (when-not (jing/segment-address? address)
    (throw (ex-info "dao.jing.mem: not a sha256 segment content address"
                    {:address address, :payload payload})))
  (when-not (= (jing/segment-hash address) (jing/content-hash payload))
    (throw (ex-info "dao.jing.mem: content address does not hash to the payload"
                    {:address address, :payload payload}))))


(defn- put-content-fn
  "Backend effect for one content store: atomic insert-if-absent. The winner
   of the CAS returns :inserted; an existing equal payload returns :present;
   an existing unequal payload is a collision and throws. The closed check
   happens inside the loop so the operation is linearizable against close."
  [state]
  (fn [address payload]
    (validate-address-payload! address payload)
    (loop []
      (let [s @state
            content (:content s)]
        (when (:closed? s)
          (throw (ex-info "dao.jing.mem: content store is closed"
                          {:address address, :payload payload})))
        (if (contains? content address)
          (let [stored (get content address)]
            (if (= stored payload)
              :present
              (throw
                (ex-info
                  "dao.jing.mem: content collision: unequal payloads at the same content address"
                  {:address address, :stored stored, :payload payload}))))
          (if (compare-and-set! state
                                s
                                (assoc s
                                       :content (assoc content address payload)))
            :inserted
            (recur)))))))


(defn- get-content-fn
  "Backend effect for one content store: content lookup with a
   caller-supplied not-found, so a stored nil is never conflated with
   absence."
  [state]
  (fn [address not-found]
    (let [{:keys [closed? content]} @state]
      (when closed?
        (throw (ex-info "dao.jing.mem: content store is closed"
                        {:address address})))
      (get content address not-found))))


(defn- close-fn
  "Backend effect for one content store: idempotent close that returns nil."
  [state]
  (fn [] (swap! state assoc :closed? true) nil))


(defn create-content-mem
  "Create an ephemeral, thread-safe, content-addressed in-memory DaoJing
   content store.

   Returns plain data:

     {:state (atom {:closed? false, :content {}})
      :put-content-fn f
      :get-content-fn g
      :close-fn c}

   :state is the store's explicit private state; :content maps content
   addresses to the exact opaque payload stored at them and never carries a
   source stream or provenance stamp."
  []
  (let [state (atom {:closed? false, :content {}})]
    {:state state,
     :put-content-fn (put-content-fn state),
     :get-content-fn (get-content-fn state),
     :close-fn (close-fn state)}))
