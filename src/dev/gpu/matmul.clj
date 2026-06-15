(ns gpu.matmul
  "Driver for dao.nao v1: GPU GEMM via dao.stream.

   Builds two hand-checkable matrices, sends a :gpu/gemm descriptor
   through dao.stream, and cross-checks the GPU result against a
   plain JVM matmul."
  (:gen-class)
  (:require
    [dao.stream :as ds]
    [dao.stream.gemm]))


(defn- cpu-matmul
  "Plain JVM matrix multiply: A (m×k) * B (k×n) -> C (m×n).
   Input: sequences of rows. Output: flat row-major vector."
  [a b]
  (let [m (count a)
        k (count (first a))
        n (count (first b))]
    (vec (for [i (range m)
               j (range n)]
           (reduce + 0.0
                   (for [p (range k)]
                     (* (double (nth (nth a i) p))
                        (double (nth (nth b p) j)))))))))


(defn -main
  []
  (let [A [[1.0 2.0 3.0]
           [4.0 5.0 6.0]]
        B [[7.0 8.0]
           [9.0 10.0]
           [11.0 12.0]]
        result (ds/take!! (ds/open! {:type :gpu/gemm :a A :b B}))]
    (if-let [err (:error result)]
      (do (println "ERROR:" (:kind err) "-" (:message err))
          (System/exit 1))
      (let [gpu-product (:result result)
            [m n] (:shape result)
            expected (cpu-matmul A B)]
        (println "GPU GEMM" m "x" n "product (row-major):")
        (doseq [i (range m)]
          (println (subvec gpu-product (* i n) (* (inc i) n))))
        (let [pass? (every? (fn [i]
                              (< (Math/abs (- (nth gpu-product i)
                                              (nth expected i)))
                                 1.0e-4))
                            (range (count expected)))]
          (if pass?
            (println "PASS")
            (do (println "FAIL")
                (println "expected:" expected)
                (println "got:     " gpu-product)
                (System/exit 1))))))))
