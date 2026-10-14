(ns yin.vm.linker.requirements
  "Derived module-level requirements, shared by publication and closure
   walking without making those namespaces depend on each other.")


(defn eager
  "The literal module names unconditionally required by `root`. `node-at`
   projects a node reference to its map fields; `identity` scans a map AST.
   Only applications and immediately applied lambda bodies are followed.
   A try-require can refuse as data, so it never makes a pin eager. Each
   node is scanned once, even when a content-addressed tree shares rows."
  ([ast] (eager ast identity))
  ([root node-at]
   (loop [queue [root]
          seen #{root}
          names #{}]
     (if (empty? queue)
       names
       (let [node (node-at (peek queue))
             queue (pop queue)]
         (if (= :application (:type node))
           (let [op (node-at (:operator node))
                 xs (:operands node)
                 arg (when (= 1 (count xs)) (node-at (first xs)))
                 names (if (and (= :variable (:type op))
                                (= 'require (:name op))
                                (= :literal (:type arg))
                                (symbol? (:value arg)))
                         (conj names (:value arg))
                         names)
                 kids (remove seen
                              (distinct (concat
                                          (when (= :lambda (:type op)) [(:body op)])
                                          xs)))]
             (recur (into queue kids) (into seen kids) names))
           (recur queue seen names)))))))
