(ns btree-bench
  "Phase 3 benchmark gate, JVM leg (docs/design/dao.data.btree.md §6):
  dao.data.btree vs psset (Java host classes) vs clojure.core sorted-set on
  bulk build, incremental conj, transient conj!, point lookup, and slice
  extraction.

  Two modes:

    clj -M:dev -m btree-bench [n]
      Quick best-of-5 wall-clock comparison (the Phase 3 gate number).

    clj -M:profile-btree btree|psset <outfile.jfr> [n] [seconds] [warmup-s]
      JFR profiling mode: a fixed-duration mixed workload (bulk build,
      persistent conj, transient conj!, lookup, slice, full reduce) run
      under a programmatically-controlled jdk.jfr.Recording using the
      'profile' preset (execution + allocation sampling). Warmup runs
      untimed and unrecorded so JIT compilation and class loading don't
      pollute the profile. `impl` selects which library's workload runs,
      so btree and psset get separate .jfr files — no cross-attribution
      ambiguity in the resulting stacks."
  (:require [dao.data.btree :as bt]
            [me.tonsky.persistent-sorted-set :as psset])
  (:import [java.nio.file Paths]
           [jdk.jfr Configuration Recording]))


(defn- best-of
  "3 untimed warmup calls (JIT tiering needs more than one hit before a
   hot method compiles; a bare best-of-N with N in the single digits and
   no warmup can catch an op still in the interpreter/C1 tier, as seen
   with lookup here — 3.5x psset cold, 1.2x warm), then min of `runs`
   timed calls."
  [runs f]
  (dotimes [_ 3] (f))
  (reduce min
          (for [_ (range runs)]
            (let [t0 (System/nanoTime)]
              (f)
              (/ (- (System/nanoTime) t0) 1e6)))))


(defn- fmt
  [ms]
  (format "%9.2f ms" ms))


;; ---------------------------------------------------------------------------
;; JFR profiling mode

(defn- one-round!
  "One pass of a realistic mixed workload for `impl`, over n elements.
  Returns a scalar summing real results from every op, so nothing the JIT
  could prove dead gets eliminated across rounds."
  [impl xs probes]
  (case impl
    "btree"
    (let [built (bt/from-sequential compare xs)
          _conjed (reduce bt/conj (bt/sorted-set* {:cmp compare}) xs)
          transed (persistent! (reduce conj!
                                       (transient (bt/sorted-set* {:cmp compare}))
                                       xs))
          lookups
          (reduce (fn [a k] (if (contains? built k) (inc a) a)) 0 probes)
          sliced (reduce (fn [a k]
                           (+ a
                              (clojure.core/count
                                (take 100 (bt/slice built k nil)))))
                         0
                         (take 1000 probes))
          reduced (reduce + 0 built)]
      (+ (clojure.core/count transed) lookups sliced reduced))
    "psset"
    (let [built (psset/from-sequential compare xs)
          _conjed (reduce clojure.core/conj (psset/sorted-set-by compare) xs)
          transed (persistent! (reduce clojure.core/conj!
                                       (transient (psset/sorted-set-by compare))
                                       xs))
          lookups
          (reduce (fn [a k] (if (contains? built k) (inc a) a)) 0 probes)
          sliced (reduce (fn [a k]
                           (+ a
                              (clojure.core/count
                                (take 100 (psset/slice built k nil)))))
                         0
                         (take 1000 probes))
          reduced (reduce + 0 built)]
      (+ (clojure.core/count transed) lookups sliced reduced))
    (throw (ex-info "impl must be \"btree\" or \"psset\"" {:impl impl}))))


(defn- run-for
  "Run one-round! back to back for `seconds` wall-clock time (or until
  round `limit` if given, for warmup). Returns [rounds sink-sum]."
  [impl xs probes seconds]
  (let [deadline (+ (System/nanoTime) (* seconds 1e9))]
    (loop [rounds 0
           sink 0]
      (if (>= (System/nanoTime) deadline)
        [rounds sink]
        (recur (inc rounds) (+ sink (one-round! impl xs probes)))))))


(defn profile-main
  [impl outfile n seconds warmup-seconds]
  (let [xs (vec (shuffle (range n)))
        probes (vec (take 10000 (shuffle (range (* 2 n)))))]
    (println (format "warming up %s for %ds (untimed, unrecorded)..."
                     impl
                     warmup-seconds))
    (let [[warm-rounds _] (run-for impl xs probes warmup-seconds)]
      (println (format "  %d warmup rounds" warm-rounds)))
    (let [cfg (Configuration/getConfiguration "profile")
          rec (Recording. cfg)]
      (println (format "recording %s for %ds -> %s" impl seconds outfile))
      (.start rec)
      (let [t0 (System/nanoTime)
            [rounds sink] (run-for impl xs probes seconds)
            elapsed-s (/ (- (System/nanoTime) t0) 1e9)]
        (.stop rec)
        (.dump rec (Paths/get outfile (make-array String 0)))
        (println (format
                   "  %d rounds in %.2fs (%.1f ms/round), sink=%d (ignore)"
                   rounds
                   elapsed-s
                   (* 1000 (/ elapsed-s rounds))
                   sink))
        (println (format "wrote %s" outfile))))))


;; ---------------------------------------------------------------------------
;; Quick comparison mode

(defn- compare-main
  [n]
  (let [xs (vec (shuffle (range n)))
        probes (vec (take 10000 (shuffle (range (* 2 n)))))
        bt-e (bt/sorted-set* {:cmp compare})
        bt-s (bt/from-sequential compare xs)
        ps-s (psset/from-sequential compare xs)
        cs-s (apply sorted-set xs)
        runs 5
        report (fn [label b p c]
                 (println
                   (format
                     "%-22s btree %s   psset %s   core %s   btree/psset %.2fx"
                     label
                     (fmt b)
                     (fmt p)
                     (fmt c)
                     (/ b p))))]
    (println (format "n=%d, best of %d runs" n runs))
    (report "bulk build"
            (best-of runs #(bt/from-sequential compare xs))
            (best-of runs #(psset/from-sequential compare xs))
            (best-of runs #(into (sorted-set) xs)))
    (report "persistent conj"
            (best-of runs #(reduce bt/conj bt-e xs))
            (best-of
              runs
              #(reduce clojure.core/conj (psset/sorted-set-by compare) xs))
            (best-of runs #(reduce clojure.core/conj (sorted-set) xs)))
    (report "transient conj!"
            (best-of runs #(persistent! (reduce conj! (transient bt-e) xs)))
            ;; psset transients need sorted-set* settings; use its public
            ;; path
            (best-of runs
                     #(persistent! (reduce conj!
                                           (transient (psset/sorted-set-by compare))
                                           xs)))
            ;; core sorted-set has no transient; persistent conj as
            ;; reference
            (best-of runs #(reduce clojure.core/conj (sorted-set) xs)))
    (report
      "lookup (10k probes)"
      (best-of runs
               #(reduce (fn [a k] (if (contains? bt-s k) (inc a) a)) 0 probes))
      (best-of runs
               #(reduce (fn [a k] (if (contains? ps-s k) (inc a) a)) 0 probes))
      (best-of runs
               #(reduce (fn [a k] (if (contains? cs-s k) (inc a) a)) 0 probes)))
    (report
      "slice 1k x 100 wide"
      (best-of runs
               #(reduce (fn [a k]
                          (+ a (count (take 100 (bt/slice bt-s k nil)))))
                        0
                        (take 1000 probes)))
      (best-of runs
               #(reduce (fn [a k]
                          (+ a (count (take 100 (psset/slice ps-s k nil)))))
                        0
                        (take 1000 probes)))
      (best-of runs
               #(reduce (fn [a k] (+ a (count (take 100 (subseq cs-s >= k)))))
                        0
                        (take 1000 probes))))
    (report "full seq reduce"
            (best-of runs #(reduce + 0 bt-s))
            (best-of runs #(reduce + 0 ps-s))
            (best-of runs #(reduce + 0 cs-s)))))


;; ---------------------------------------------------------------------------
;; Entry point

(defn -main
  [& args]
  (let [[a1 a2 a3 a4 a5] args]
    (if (#{"btree" "psset"} a1)
      (profile-main
        a1
        (or a2 (throw (ex-info "profile mode requires <outfile.jfr>" {})))
        (if a3 (Long/parseLong a3) 200000)
        (if a4 (Long/parseLong a4) 15)
        (if a5 (Long/parseLong a5) 3))
      (compare-main (if a1 (Long/parseLong a1) 100000)))))
