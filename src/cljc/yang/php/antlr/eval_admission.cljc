(ns yang.php.antlr.eval-admission
  "Strict UTF-8 admission for an eval source carried as one character per byte.
   This boundary does not execute or compile the admitted source."
  (:require [dao.host.text :as text])
  #?(:cljd (:import ["dart:typed_data" Uint8List])))


(def profile-revision :yang.php.eval/utf8-v1)


(defn- bad-sequence
  "Return the first malformed interval. An invalid continuation is included;
   truncation ends at EOF. Illegal leaders occupy one byte."
  [bs]
  (let [n (count bs)]
    (loop [i 0]
      (when (< i n)
        (let [b (nth bs i)
              width (cond (< b 0x80) 1 (<= 0xc2 b 0xdf) 2
                          (<= 0xe0 b 0xef) 3 (<= 0xf0 b 0xf4) 4
                          :else 0)]
          (cond
            (zero? width) [i (inc i)]
            (= width 1) (recur (inc i))
            :else
            (let [end (loop [j 1]
                        (if (= j width)
                          nil
                          (let [p (+ i j)]
                            (cond
                              (>= p n) n
                              (let [v (nth bs p)]
                                (not (and (<= 0x80 v 0xbf)
                                          (or (not= j 1)
                                              (and (or (not= b 0xe0) (>= v 0xa0))
                                                   (or (not= b 0xed) (< v 0xa0))
                                                   (or (not= b 0xf0) (>= v 0x90))
                                                   (or (not= b 0xf4) (< v 0x90)))))))
                              (inc p)
                              :else (recur (inc j))))))]
              (if end [i end] (recur (+ i width))))))))))


(defn- host-bytes
  [bs]
  #?(:cljd (Uint8List.fromList bs)
     :clj (byte-array (map unchecked-byte bs))
     :cljs (js/Uint8Array. (clj->js bs))))


(defn admit
  "Decode a byte carrier once. Malformed carriers are protocol violations;
   malformed UTF-8 produces the classified PHP diagnostic."
  [carrier]
  (if-not (string? carrier)
    {:reason :carrier-representation}
    (let [bs (mapv #(text/code-unit carrier %) (range (text/unit-count carrier)))]
      (if (some #(> % 255) bs)
        {:reason :carrier-representation}
        (if-let [[start end] (bad-sequence bs)]
          {:diagnostic {:yang.php/diagnostic :yang.php/unsupported
                        :construct :eval :reason :source-encoding :encoding :utf-8
                        :byte-offset start :span [start end]}}
          (let [source (text/utf8-decode (host-bytes bs))
                encoded (mapv #(bit-and % 255) (text/utf8-encode source))]
            (if (= bs encoded)
              {:source source}
              (throw (ex-info "UTF-8 round trip failed" {:reason :utf8-round-trip})))))))))


(defn admit-request
  "Admit a sealed eval request, preserving correlation and provenance on refusal.
   On success the original request and its derived parser view travel together."
  [request]
  (let [{:keys [source diagnostic reason]} (admit (:yang.php/source request))]
    (cond
      diagnostic (assoc (select-keys request [:yang.compile/request :yang.compile/unit
                                              :yang.compile/provenance])
                        :yang.compile/outcome :yang.compile/rejected
                        :yang.compile/reason :yang.compile/unsupported
                        :yang.compile/diagnostic diagnostic)
      reason (assoc (select-keys request [:yang.compile/request :yang.compile/unit
                                          :yang.compile/provenance])
                    :yang.compile/outcome :yang.compile/rejected
                    :yang.compile/reason :yang.compile/invalid-request
                    :yang.compile/detail reason)
      :else {:request request :source source})))
