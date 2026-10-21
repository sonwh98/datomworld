(ns dao.stream.chunks
  "Transport-neutral splitting and bounded reassembly of host byte payloads,
   over the profile's `dao.host.bytes` (docs/design/dao.stream.host-boundary.md
   §4.2). `concat-bytes` writes only the fresh array it allocates, before
   returning it (§2.4)."
  (:require
    [dao.host.bytes :as bytes]))


(defn length
  [bs]
  (bytes/length bs))


(defn empty-bytes
  [n]
  (bytes/alloc n))


(defn slice
  [bs start end]
  (bytes/slice bs start end))


(defn concat-bytes
  [pieces]
  (let [out (bytes/alloc (reduce + (map bytes/length pieces)))]
    (reduce (fn [offset bs]
              (let [n (bytes/length bs)]
                (bytes/copy! bs 0 out offset n)
                (+ offset n)))
            0 pieces)
    out))


(defn split
  "Return protocol-specific envelopes fitting budget. envelope receives part,
   parts, bytes; encode measures the actual wire size."
  [payload budget envelope encode]
  (let [total (length payload)]
    (loop [size (min total budget)]
      (when-not (pos? size)
        (throw (ex-info "chunk envelope cannot fit datagram" {:budget budget})))
      (let [parts (max 1 (quot (+ total (dec size)) size))]
        (if (<= (length (encode (envelope (dec parts) parts (empty-bytes size))))
                budget)
          (mapv (fn [part]
                  (let [start (* part size)]
                    (envelope part parts
                              (slice payload start (min total (+ start size))))))
                (range parts))
          (recur (dec size)))))))


(defn absorb
  "Return [state completed-bytes-or-nil]. Malformed, duplicate, inconsistent,
   and overbound pieces cannot increase held bytes. Evict oldest partial at
   the global count bound. Caller supplies a source-qualified key."
  [state key part parts piece max-bytes max-partials]
  (if-not (and (integer? part) (integer? parts) (pos? parts)
               (<= parts max-bytes) (<= 0 part) (< part parts)
               (bytes/bytes? piece) (pos? (length piece))
               (<= (length piece) max-bytes) (pos? max-partials))
    [state nil]
    (let [held (get-in state [:partial key])]
      (if (or (and held (not= parts (:parts-count held)))
              (and held (contains? (:parts held) part)))
        [state nil]
        (let [state (if held state
                        (-> state
                            (assoc-in [:partial key] {:parts-count parts :parts {} :bytes-total 0})
                            (update :partial-order (fnil conj []) key)))
              oldest (first (:partial-order state))
              state (if (> (count (:partial state)) max-partials)
                      (-> state (update :partial dissoc oldest)
                          (update :partial-order #(vec (rest %)))) state)
              held (get-in state [:partial key])]
          (if-not held [state nil]
                  (let [total (+ (:bytes-total held) (length piece))]
                    (if (> total max-bytes)
                      [(-> state (update :partial dissoc key)
                           (update :partial-order #(vec (remove #{key} %)))) nil]
                      (let [state (-> state (assoc-in [:partial key :parts part] piece)
                                      (assoc-in [:partial key :bytes-total] total))
                            held (get-in state [:partial key])]
                        (if (= parts (count (:parts held)))
                          [(-> state (update :partial dissoc key)
                               (update :partial-order #(vec (remove #{key} %))))
                           (concat-bytes (mapv (:parts held) (range parts)))]
                          [state nil]))))))))))
