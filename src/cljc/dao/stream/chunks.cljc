(ns dao.stream.chunks
  "Transport-neutral splitting and bounded reassembly of host byte payloads."
  #?(:cljd (:import ["dart:typed_data" Uint8List])))


(defn length
  [bs]
  #?(:cljd (.-length ^Uint8List bs)
     :clj (alength ^bytes bs)
     :cljs (.-length bs)))


(defn empty-bytes
  [n]
  #?(:cljd (Uint8List. n)
     :clj (byte-array n)
     :cljs (js/Uint8Array. n)))


(defn slice
  [bs start end]
  #?(:cljd (Uint8List.fromList (.sublist ^Uint8List bs start end))
     :clj (java.util.Arrays/copyOfRange ^bytes bs (int start) (int end))
     :cljs (.slice bs start end)))


(defn concat-bytes
  [pieces]
  (let [out (empty-bytes (reduce + (map length pieces)))]
    (reduce (fn [offset bs]
              (let [n (length bs)]
                #?(:cljd (.setRange ^Uint8List out offset (+ offset n) bs)
                   :clj (System/arraycopy bs 0 out offset n)
                   :cljs (.set out bs offset))
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
  [state key part parts bytes max-bytes max-partials]
  (if-not (and (integer? part) (integer? parts) (pos? parts)
               (<= parts max-bytes) (<= 0 part) (< part parts)
               (some? bytes) (pos? (length bytes))
               (<= (length bytes) max-bytes) (pos? max-partials))
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
                  (let [total (+ (:bytes-total held) (length bytes))]
                    (if (> total max-bytes)
                      [(-> state (update :partial dissoc key)
                           (update :partial-order #(vec (remove #{key} %)))) nil]
                      (let [state (-> state (assoc-in [:partial key :parts part] bytes)
                                      (assoc-in [:partial key :bytes-total] total))
                            held (get-in state [:partial key])]
                        (if (= parts (count (:parts held)))
                          [(-> state (update :partial dissoc key)
                               (update :partial-order #(vec (remove #{key} %))))
                           (concat-bytes (mapv (:parts held) (range parts)))]
                          [state nil]))))))))))
