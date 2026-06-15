(ns gpu.bench
  "Benchmark comparing matrix multiplication on GPU vs CPU.
   
   GPU: dao.stream/gemm (includes transfer overhead)
   CPU: Neanderthal native (optimized MKL/OpenBLAS)"
  (:gen-class)
  (:require
    [criterium.core :as crit]
    [dao.stream :as ds]
    [dao.stream.gemm]
    [uncomplicate.neanderthal.core :as nc]
    [uncomplicate.neanderthal.native :as native]))


(defn random-matrix-rows
  "Generate an m x n matrix as a sequence of rows."
  [m n]
  (vec (repeatedly m #(vec (repeatedly n rand)))))


(defn bench-gpu
  [a b]
  (ds/take!! (ds/open! {:type :gpu/gemm :a a :b b})))


(defn bench-cpu
  [m k n a-flat b-flat]
  (let [a (native/dge m k a-flat)
        b (native/dge k n b-flat)
        c (nc/mm a b)]
    (nc/transfer c)))


(defn -main
  [& args]
  (let [size (if (seq args) (Integer/parseInt (first args)) 512)
        m size k size n size
        _ (println "Generating" m "x" k "and" k "x" n "matrices...")
        a (random-matrix-rows m k)
        b (random-matrix-rows k n)
        ;; Flatten for CPU neanderthal to avoid row->col-major conversion inside the timed loop
        a-flat (flatten a)
        b-flat (flatten b)]

    (println "\nWarmup GPU...")
    (bench-gpu a b)

    (println "Benchmarking GPU (dao.stream/gemm - includes transfers)...")
    (crit/quick-bench (bench-gpu a b))

    (println "\nWarmup CPU...")
    (bench-cpu m k n a-flat b-flat)

    (println "Benchmarking CPU (Neanderthal Native MKL)...")
    (crit/quick-bench (bench-cpu m k n a-flat b-flat))))
