;; C4 P2 engineer probe (never staged): split a linked run's time between
;; serve rounds and VM runs.
(require '[yang.python.antlr.linked-harness :as h]
         '[yang.python.antlr.lower :as lower]
         '[yang.python.antlr.parser :as parser]
         '[yin.repl.link :as link]
         '[yin.vm :as vm])


(defn ms
  [t0]
  (quot (- (System/nanoTime) t0) 1000000))


(def p (let [t (System/nanoTime) r @h/published] (println "publish" (ms t)) r))
(def linked (lower/lower-packet (parser/parse-source "print(1)\n") {:prelude :linked}))


(doseq [k [:semantic :stack :register]]
  (let [pair (link/make-pair)
        src (h/source)
        t0 (System/nanoTime)
        task ((get h/backends k) linked (h/composition (h/registry) pair))
        tc (ms t0)]
    (loop [t1 (System/nanoTime) task (vm/run task) pair pair i 0 log [[:build tc]]]
      (let [log (conj log [:run (ms t1) (vm/blocked? task) (mapv :reason (:wait-set task))])]
        (if (and (vm/blocked? task) (< i 40))
          (let [t2 (System/nanoTime)
                served (link/serve {:pair pair :source src})
                log (conj log [:serve (ms t2) (:progress? served) (:pending served)])]
            (recur (System/nanoTime) task (:pair served) (inc i) log))
          (println k (vm/value task) log))))))


(shutdown-agents)
