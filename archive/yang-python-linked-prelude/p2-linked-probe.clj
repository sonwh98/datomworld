;; C4 P2 engineer probe (never staged): publish py, then time one linked
;; run per vector VM against the same program bundled.
;;   clj -Sdeps '{:paths ["src/cljc" "src/clj" "test"]}' -M:test-paths collab/p2-linked-probe.clj
(require '[yang.python.antlr.linked-harness :as h]
         '[yang.python.antlr.lower :as lower]
         '[yang.python.antlr.parser :as parser]
         '[yang.python.antlr.render :as render]
         '[yang.python.antlr.int-ops-test :as ops])


(defn ms
  [t0]
  (quot (- (System/nanoTime) t0) 1000000))


(let [t0 (System/nanoTime)
      p @h/published]
  (println "publish ms" (ms t0) "address" (:address p))
  (doseq [[f o] (:links (:result p))]
    (println " " f (:status o) (:trust o) (select-keys o [:reason :name]))))


(def src "class C:\n    pass\nx = [1, 2]\nprint(len(x), isinstance(C(), C))\ntry:\n    raise ValueError('v')\nexcept Exception as e:\n    print('caught', e)\n")
(def pk (parser/parse-source src))
(def bundled (lower/lower-packet pk))
(def linked (lower/lower-packet pk {:prelude :linked}))


(doseq [k [:semantic :stack :register]]
  (let [t0 (System/nanoTime)
        b (render/output ((get ops/runners k) bundled))
        tb (ms t0)
        t1 (System/nanoTime)
        l (try (render/output ((get h/runners k) linked))
               (catch Exception e [:thrown (ex-message e) (ex-data e)]))
        tl (ms t1)]
    (println k "bundled" tb "ms" b)
    (println k "linked " tl "ms" l)))


(doseq [k [:semantic :stack :register]]
  (let [t1 (System/nanoTime)
        l (render/output ((get h/runners k) linked))]
    (println k "linked (2nd)" (ms t1) "ms")))


(shutdown-agents)
