;; Architect remediation probe: tree size and a direct-walk free-occurrence scan
;; over the wide layout. Never staged.
(load-file "collab/p2probe-walk-defs.clj")
(require '[yin.vm :as vm])
(def tree (vm/ast->semantic-bytecode (u/mark-tails wide-layout)))
(def rows (:rows tree))
(def tags (frequencies (map (fn [r] (nth r 1)) (vals rows))))
(println "rows" (count rows) "tags" tags)
(def t0 (System/nanoTime))
(def occ (vm/occurrences tree))
(println "occurrences" (count occ) "in" (quot (- (System/nanoTime) t0) 1000000) "ms")
(println "variable occurrences" (count (filter (fn [[_ _ id]] (= :variable (nth (get rows id) 1))) occ)))
(println "lambda occurrences" (count (filter (fn [[_ _ id]] (= :lambda (nth (get rows id) 1))) occ)))
(println "max depth" (apply max (map (fn [[_ p _]] (count p)) occ)))


;; direct walk: free variable occurrences with path, bound set threaded down
(defn direct-free
  [tree]
  (let [rows (:rows tree)
        grammar vm/semantic-bytecode-grammar]
    (loop [frontier [[[] (:root tree) #{} false]] acc (transient [])]
      (if (empty? frontier)
        (persistent! acc)
        (let [[path id bound in-body?] (peek frontier)
              row (get rows id)
              tag (nth row 1)
              slots (get grammar tag)
              frontier (pop frontier)]
          (if (= :variable tag)
            (recur frontier (if (contains? bound (nth row 2)) acc (conj! acc {:name (nth row 2) :at [(:root tree) path] :in-body? in-body?})))
            (let [bound' (if (= :lambda tag) (into bound (nth row 2)) bound)
                  kids (keep-indexed
                         (fn [j [_ kind]]
                           (let [pos (+ j 2) v (nth row pos)]
                             (case kind
                               :node (when v [[(conj path pos) v (if (and (= :lambda tag) (= pos 3)) bound' bound) (or in-body? (and (= :lambda tag) (= pos 3)))]])
                               :nodes (map-indexed (fn [i cid] [(conj path [pos i]) cid bound in-body?]) v)
                               nil)))
                         slots)]
              (recur (into frontier (reverse (apply concat kids))) acc))))))))


(def t1 (System/nanoTime))
(def free (direct-free tree))
(println "direct walk free occurrences" (count free) "distinct" (count (distinct (map :name free))) "in" (quot (- (System/nanoTime) t1) 1000000) "ms")
(println "distinct free names" (sort (distinct (map :name free))))
(shutdown-agents)
