;; C4 P2 engineer probe (never staged): the linked run's time with
;; dao.jing.cbor/blen replaced by a type-hinted alength (diagnostic only).
(require '[yang.python.antlr.linked-harness :as h]
         '[yang.python.antlr.lower :as lower]
         '[yang.python.antlr.parser :as parser]
         '[dao.jing.cbor])


(set! *warn-on-reflection* true)


(defn ms
  [t0]
  (quot (- (System/nanoTime) t0) 1000000))


(def linked (lower/lower-packet (parser/parse-source "print(1)\n") {:prelude :linked}))
(let [t (System/nanoTime)] @h/published (println "publish, unhinted" (ms t)))


(doseq [k [:semantic :stack :register]]
  (let [t (System/nanoTime) v ((get h/runners k) linked)] (println k "unhinted" (ms t) v)))


(alter-var-root #'dao.jing.cbor/blen (constantly (fn [^bytes bs] (alength bs))))


(doseq [k [:semantic :stack :register]]
  (let [t (System/nanoTime) v ((get h/runners k) linked)] (println k "hinted" (ms t) v)))


(shutdown-agents)
