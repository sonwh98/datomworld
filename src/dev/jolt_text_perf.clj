;; bb test:jolt-text-perf — the linearity guard for the jolt dao.host.text
;; override (src/jolt/dao/host/text.jolt), run UNDER jolt with the :jolt alias.
;;
;; A Chez string is indexed by scalar value, not by UTF-16 code unit, so a
;; code-unit view rebuilt per index makes a whole-string walk quadratic (the
;; J0c parity oversize case stalled on exactly that: collab
;; 1792640000000-engineer-jolt-j1b-shiva findings F1). This probe times every
;; whole-string walk over that view on three shapes (BMP-only, astral-heavy,
;; mixed) at 64, 256 and 1024 Ki code points:
;;
;;   - text/code-points;
;;   - text/unit-count;
;;   - an ascending text/code-unit consumer, the shape of
;;     yin.vm.debruijn/well-formed-utf16? (reads i, and i+1 after a high
;;     surrogate);
;;   - text/utf8-encode.
;;
;; Each timing is the best of 3 runs. For each 4x step in input it requires
;; the time ratio below 8 (each time floored at 10 ms, so a sub-10 ms base
;; cannot fail on noise), and the 1024 Ki run below 5000 ms. Prints RECORD per
;; timing and PASS/FAIL per check; exits nonzero on any FAIL.
;;
;; Sizes in Ki given as arguments (e.g. `... jolt_text_perf.clj 1 4 16`)
;; replace the default; the absolute ceiling then applies to the largest.
(ns jolt-text-perf
  (:require
    [dao.host.text :as text]))


(def failures (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail)))


(def sizes-kib
  (if (seq *command-line-args*)
    (mapv #(Long/parseLong %) *command-line-args*)
    [64 256 1024]))


(def max-ratio 8)
(def floor-ms 10.0)
(def ceiling-ms 5000.0)


;; Each shape is a cycle of code points repeated to n code points.
(def shapes
  [[:bmp [0x61 0xE9 0x4E2D 0x20]]
   [:astral [0x1F600 0x10000 0x10FFFF 0x1D11E]]
   [:mixed [0x61 0x1F600 0xE9 0x4E2D 0x1D11E 0x20]]])


(defn- cp->str
  [cp]
  (text/units->string
    (if (< cp 0x10000)
      [cp]
      (let [v (- cp 0x10000)]
        [(+ 0xD800 (quot v 0x400)) (+ 0xDC00 (rem v 0x400))]))))


(defn- build
  [cycle n]
  (let [unit (apply str (map cp->str cycle))
        k (count cycle)]
    (str (apply str (repeat (quot n k) unit))
         (apply str (map cp->str (take (rem n k) cycle))))))


(defn- ascending-code-unit-walk
  "Every surrogate paired? Read the way yin.vm.debruijn/well-formed-utf16?
   reads: unit i, then unit i+1 after a high surrogate."
  [s]
  (let [n (text/unit-count s)]
    (loop [i 0]
      (if (>= i n)
        true
        (let [c (text/code-unit s i)]
          (cond
            (<= 0xDC00 c 0xDFFF) false
            (<= 0xD800 c 0xDBFF) (if (and (< (inc i) n)
                                          (<= 0xDC00 (text/code-unit s (inc i)) 0xDFFF))
                                   (recur (+ i 2))
                                   false)
            :else (recur (inc i))))))))


(def walks
  [[:code-points #(count (text/code-points %))]
   [:unit-count text/unit-count]
   [:code-unit-walk ascending-code-unit-walk]
   [:utf8-encode #(alength ^bytes (text/utf8-encode %))]])


(defn- time-ms
  [f s]
  (let [t0 (System/nanoTime)
        r (f s)]
    [(/ (- (System/nanoTime) t0) 1e6) r]))


(defn- best-of-3
  [f s]
  (let [runs (repeatedly 3 #(time-ms f s))]
    [(apply min (map first runs)) (second (first runs))]))


(defn- round1
  [x]
  (/ (Math/round (* 10.0 x)) 10.0))


(doseq [[shape cycle] shapes
        [walk f] walks]
  (let [timings (vec (for [kib sizes-kib]
                       (let [s (build cycle (* 1024 kib))
                             [ms r] (best-of-3 f s)]
                         (println "RECORD" (pr-str {:shape shape :walk walk :kib kib
                                                    :ms (round1 ms) :result r}))
                         [kib ms])))]
    (doseq [[[k0 t0] [k1 t1]] (partition 2 1 timings)]
      (let [ratio (/ (max t1 floor-ms) (max t0 floor-ms))]
        (check (str shape " " walk ": " k1 " Ki is under " max-ratio "x the time of " k0 " Ki")
               (< ratio (* max-ratio (/ k1 k0 4)))
               {:ms [(round1 t0) (round1 t1)] :ratio (round1 ratio)})))
    (let [[k t] (peek timings)]
      (check (str shape " " walk ": " k " Ki under " (long ceiling-ms) " ms")
             (< t ceiling-ms)
             {:ms (round1 t)}))))


(println (if (zero? @failures) "SUMMARY ok" (str "SUMMARY " @failures " FAIL")))
(System/exit (if (zero? @failures) 0 1))
