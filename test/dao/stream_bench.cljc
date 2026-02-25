(ns dao.stream-bench
  (:require
    [dao.stream :as ds]))


;; Ad-hoc implementation (current demo code)
(defn adhoc-append
  [stream a v]
  (let [e (:next-e stream)
        t (:next-t stream)
        datom [e a v t 0]
        datoms (-> (conj (:datoms stream) datom)
                   (->> (take-last 200)
                        vec))]
    (assoc stream
           :next-e (inc e)
           :next-t (inc t)
           :datoms datoms)))


;; dao.stream implementation (proposed)
(defn ds-append
  [stream a v]
  (let [e (:next-e stream)
        t (:next-t stream)
        datom [e a v t 0]
        result (ds/put nil (:log stream) datom)]
    (assoc stream
           :next-e (inc e)
           :next-t (inc t)
           :log (:ok result))))


(defn bench
  [label f n]
  (let [start (.now js/Date)]
    (dotimes [_ n] (f))
    (let [elapsed (- (.now js/Date) start)]
      (println (str label ": " elapsed "ms for " n " iterations")))))


(defn -main
  []
  (let [n 10000
        adhoc-stream {:datoms [], :next-e 7000, :next-t 1}
        ds-stream {:log (ds/make), :next-e 7000, :next-t 1}]
    (bench "adhoc-append"
           #(loop [s adhoc-stream
                   i 0]
              (if (< i 500)
                (recur (adhoc-append s :stream/test {:data i}) (inc i))
                s))
           n)
    (bench
      "ds-append"
      #(loop [s ds-stream
              i 0]
         (if (< i 500) (recur (ds-append s :stream/test {:data i}) (inc i)) s))
      n)
    ;; Benchmark seq/read (display path)
    (let [filled-adhoc (loop [s adhoc-stream
                              i 0]
                         (if (< i 500) (recur (adhoc-append s :k i) (inc i)) s))
          filled-ds (loop [s ds-stream
                           i 0]
                      (if (< i 500) (recur (ds-append s :k i) (inc i)) s))]
      (bench "adhoc-read (take-last 200)"
             #(vec (take-last 200 (:datoms filled-adhoc)))
             n)
      (bench "ds-read (->seq take-last 200)"
             #(vec (take-last 200 (ds/->seq nil (:log filled-ds))))
             n))))
