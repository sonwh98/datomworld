(require '[yin.vm.semantic-register.derive-test :as dt]
         '[yang.python.antlr.linked-harness :as h]
         '[yang.python.antlr.lower :as lower]
         '[yang.python.antlr.import-programs :as programs]
         '[yin.vm :as vm])


(defmacro t
  [label & body]
  `(let [s# (System/nanoTime) r# (do ~@body)] (println ~label (quot (- (System/nanoTime) s#) 1000000) "ms") r#))


(def py (t "publish py" (:address @h/published)))
(def reg (h/registry))
(def m (t "publish m" (h/publish-guest-module! (lower/module-spec reg programs/m-packet {:name "m" :py-address py :deps {}}))))
(def src (h/source {'py py (lower/module-symbol "m") (:address m)}))
(def ast (lower/lower-packet programs/o-packet {:prelude :linked}))


(doseq [i (range 2) fam [:stack :register] p [:old :new]]
  (let [task (t (str "run o " fam p " #" i) (dt/c4-task fam p ast src))]
    (println (vm/halted? task) (vm/blocked? task))))


(shutdown-agents)
