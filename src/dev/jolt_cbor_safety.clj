;; Run under jolt -Srepro -M:jolt:jolt-test. Resource and nesting regression
;; gates exercise the public boundary, including on the macOS worker stack.
(ns jolt-cbor-safety
  (:require [dao.stream.cbor :as cbor]
            [dao.stream.cbor.boring :as impl]
            [dao.host.bytes :as bytes]))


(def failures (atom 0))


(defn check
  [label ok detail]
  (println (if ok "PASS" "FAIL") label (pr-str detail))
  (when-not ok (swap! failures inc)))


(defn nest
  [f n]
  (nth (iterate f 0) n))


(defn elapsed
  [f]
  (let [start (System/nanoTime)] (f) (/ (- (System/nanoTime) start) 1000000.0)))


(defn best
  [f]
  (apply min (repeatedly 3 #(elapsed f))))


(defn round
  [x]
  (cbor/decode (cbor/encode x)))


(defn refusal
  [f]
  (try (f) nil (catch Exception e (ex-data e))))


(doseq [[shape f] [[:set hash-set] [:map-key #(hash-map % 0)]]]
  (let [times (mapv (fn [d]
                      (let [x (nest f d) ms (best #(round x))]
                        (println "RECORD" shape d ms "ms encode+decode")
                        (check (str shape " depth " d) (< ms 200) {:ms ms})
                        ms)) [8 12 16 24 32])]
    (doseq [[a b] (partition 2 1 times)]
      (check (str shape " growth ratio") (< (/ (max 1 b) (max 1 a)) 8)
             {:from a :to b :floor-ms 1 :max-ratio 8}))))


(doseq [[label x] [[:one-MiB (bytes/alloc 1048576)]
                   [:hundred-k-vector (vec (repeat 100000 0))]]]
  (let [ms (best #(round x))]
    (check label (< ms 5000) {:encode+decode-ms ms :ceiling-ms 5000})))


(defn nesting-checks
  []
  (doseq [[shape f limit] [[:vector vector impl/max-nesting]
                           [:set hash-set (quot impl/max-nesting 2)]
                           [:map-key #(hash-map % 0) impl/max-nesting]
                           [:metadata #(with-meta [] {0 %}) (quot impl/max-nesting 4)]]]
    (let [x (nest f limit)
          bs (cbor/encode x)
          above (nest f (inc limit))
          ;; Produce an over-budget frame independently of the guarded writer.
          too-deep (with-redefs [impl/max-nesting 512] (cbor/encode above))]
      (check (str shape " budget roundtrip") (some? (round x)) {:levels limit :bytes (bytes/length bs)})
      (check (str shape " encode above budget")
             (= :too-deep (:reason (refusal #(cbor/encode above)))) {:levels (inc limit)})
      (check (str shape " decode above budget")
             (= :too-deep (:reason (refusal #(cbor/decode too-deep)))) {:levels (inc limit)})))
  (check "544-level input refuses before recursion"
         (= :too-deep (:reason (refusal #(cbor/encode (nest vector 544))))) {})
  :done)


(nesting-checks)
(check "worker nesting gates complete" (= :done @(future (nesting-checks))) {})
(println "SUMMARY" (if (zero? @failures) "ok" (str @failures " failed")))
(shutdown-agents)
(System/exit (if (zero? @failures) 0 1))
