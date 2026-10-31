;; C4 P2 engineer probe (never staged): sample the stack of a :trusted serve
;; of py, and time the VM run after it.
(require '[yang.python.antlr.linked-harness :as h]
         '[yang.python.antlr.lower :as lower]
         '[yang.python.antlr.parser :as parser]
         '[yin.repl.link :as link]
         '[yin.vm :as vm])


(defn ms
  [t0]
  (quot (- (System/nanoTime) t0) 1000000))


@h/published
(def linked (lower/lower-packet (parser/parse-source "print(1)\n") {:prelude :linked}))


(defn sample
  [f]
  (let [result (promise)
        th (Thread. #(deliver result (f)))
        counts (atom {}) tops (atom {}) n (atom 0)]
    (.start th)
    (while (.isAlive th)
      (Thread/sleep 10)
      (let [st (.getStackTrace th)
            frames (distinct (map #(str (.getClassName %) "/" (.getMethodName %)) st))
            top (first (filter #(re-find #"^(yin|dao)\." %) frames))]
        (swap! n inc)
        (swap! tops update top (fnil inc 0))
        (doseq [fr (filter #(re-find #"^(yin|dao)\." %) frames)] (swap! counts update fr (fnil inc 0)))))
    (println "samples" @n)
    (println "-- inclusive")
    (doseq [[k c] (take 30 (sort-by (comp - val) @counts))] (println c k))
    (println "-- top")
    (doseq [[k c] (take 15 (sort-by (comp - val) @tops))] (println c k))
    @result))


(doseq [k [:stack]]
  (let [pair (link/make-pair)
        src (h/source)
        task (vm/run ((get h/backends k) linked (h/composition (h/registry) pair)))
        served (sample #(link/serve {:pair pair :source src}))
        t1 (System/nanoTime)
        [done _] (h/drive task (:pair served) src)]
    (println k "run after serve ms" (ms t1) (vm/value done))))


(shutdown-agents)
