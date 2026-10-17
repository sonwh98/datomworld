(require '[yin.vm :as vm]
         '[yin.vm.completion :as completion]
         '[yin.vm.linearize :as linearize]
         '[yang.python.antlr.prelude :as prelude])


(let [v (:vector (linearize/lower-rows (vm/ast->semantic-bytecode prelude/module-uast)))
      scope (completion/segment-scope v)
      owner (into {} (map (fn [[_ pc closure]] [pc closure])) scope)
      depth (fn [pc]
              (loop [pc pc n 0]
                (if-let [parent (get owner pc)] (recur parent (inc n)) n)))
      variables (keep-indexed (fn [pc t] (when (= :var (first t)) pc)) v)]
  (println :instructions (count v) :closures (count (filter #(= :closure (first %)) v))
           :variables (count variables) :scope-facts (count scope)
           :max-variable-depth (apply max (map depth variables))))
