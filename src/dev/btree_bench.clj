(ns btree-bench
  "Phase 3 benchmark gate, JVM leg (docs/design/dao.data.btree.md §6):
  dao.data.btree vs psset (Java host classes) vs clojure.core sorted-set on
  bulk build, incremental conj, transient conj!, point lookup, and slice
  extraction. Run with:

      clj -M:dev -m btree-bench [n]

  Reports best-of-5 wall time per workload and the btree/psset ratio."
  (:require [dao.data.btree :as bt]
            [me.tonsky.persistent-sorted-set :as psset]))


(defn- best-of
  [runs f]
  (reduce min
          (for [_ (range runs)]
            (let [t0 (System/nanoTime)]
              (f)
              (/ (- (System/nanoTime) t0) 1e6)))))


(defn- fmt
  [ms]
  (format "%9.2f ms" ms))


(defn -main
  [& args]
  (let [n (if (first args) (Long/parseLong (first args)) 100000)
        xs (vec (shuffle (range n)))
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
