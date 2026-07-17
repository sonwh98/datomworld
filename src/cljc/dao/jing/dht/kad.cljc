(ns dao.jing.dht.kad
  "Pure Kademlia routing primitives: XOR distance over hex node ids and a
  k-bucket routing table. No IO lives here; the table is a plain value the
  node threads through an atom. Ids are equal-length lowercase hex strings
  (SHA-256, 64 chars) sharing one key space with content hashes, so peers
  and keys route uniformly.")


(def k
  "Bucket capacity and replication factor (Kademlia's k)."
  20)


(def ^:private hex-val (zipmap "0123456789abcdef" (range 16)))


(defn distance
  "XOR distance between two equal-length hex ids, as a vector of nibble
  xors. Vectors compare lexicographically, so
  (compare (distance a t) (distance b t)) orders a and b by closeness to t."
  [id-a id-b]
  (mapv (fn [ca cb] (bit-xor (hex-val ca) (hex-val cb))) id-a id-b))


(defn- nibble-leading-zeros
  [n]
  (cond (>= n 8) 0
        (>= n 4) 1
        (>= n 2) 2
        :else 3))


(defn bucket-index
  "The k-bucket a peer belongs to: the number of leading bits its id shares
  with self-id. nil when the ids are equal (a node never buckets itself)."
  [self-id peer-id]
  (loop [nibbles (seq (distance self-id peer-id))
         acc 0]
    (when-let [[n & more] nibbles]
      (if (zero? n) (recur more (+ acc 4)) (+ acc (nibble-leading-zeros n))))))


(defn observe
  "Record contact with peer (a {:id :host :port} map), returning the new
  table. The peer becomes most-recently-seen in its bucket; when a full
  bucket overflows, the least-recently-seen peer is dropped. (Canonical
  Kademlia pings the oldest and keeps it if it answers; that liveness
  refinement can layer on later.)"
  [table self-id peer]
  (if-let [idx (bucket-index self-id (:id peer))]
    (update table
            idx
            (fn [bucket]
              (let [bucket
                    (into [] (remove #(= (:id %) (:id peer))) (or bucket []))
                    bucket (if (>= (count bucket) k) (subvec bucket 1) bucket)]
                (conj bucket peer))))
    table))


(defn nearest
  "The n known peers nearest target-id, nearest first."
  [table target-id n]
  (->> (vals table)
       (mapcat identity)
       (sort-by #(distance (:id %) target-id))
       (take n)
       vec))
