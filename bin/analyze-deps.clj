#!/usr/bin/env clj -M
"Generate dependency graph and analyze for coupling issues.

 Generates dep-graph.dot and dep-graph.svg (via clj -M:dep-graph), then reports:
 - Circular dependencies
 - High-degree nodes (over-coupled namespaces)
 - Strongly connected components (tightly coupled clusters)
 - Actionable recommendations for decoupling

 Usage: clj ./bin/analyze-deps.clj"


(require '[clojure.string :as str]
         '[clojure.java.shell :as shell])


(defn parse-dot-file
  "Parse a DOT file and extract edges as [from to] pairs."
  [path]
  (let [content (slurp path)
        lines (str/split-lines content)
        edge-pattern #"\"([^\"]+)\"\s*->\s*\"([^\"]+)\""]
    (reduce
      (fn [edges line]
        (if-let [match (re-find edge-pattern line)]
          (conj edges [(second match) (nth match 2)])
          edges))
      []
      lines)))


(defn build-graph
  "Convert edge list to adjacency lists: {node -> [neighbors]}"
  [edges]
  (let [graph (reduce
                (fn [g [from to]]
                  (-> g
                      (update from (fnil conj []) to)
                      (update to (fnil conj []))))
                {}
                edges)]
    graph))


(defn find-cycles
  "Detect cycles using DFS. Returns list of cycles found."
  [graph]
  (let [visited (atom {})
        rec-stack (atom {})
        cycles (atom [])]
    (letfn [(dfs
              [node path]
              (swap! visited assoc node :visiting)
              (swap! rec-stack assoc node true)

              (doseq [neighbor (get graph node [])]
                (cond
                  (not (contains? @visited neighbor))
                  (dfs neighbor (conj path neighbor))

                  (get @rec-stack neighbor)
                  (let [cycle-start (.indexOf path neighbor)
                        cycle (if (>= cycle-start 0)
                                (subvec path cycle-start)
                                [neighbor])]
                    (swap! cycles conj (conj cycle node)))))

              (swap! visited assoc node :done)
              (swap! rec-stack dissoc node))]

      (doseq [node (keys graph)]
        (when (not (contains? @visited node))
          (dfs node [node]))))

    @cycles))


(defn find-high-degree-nodes
  "Find namespaces with many incoming or outgoing edges."
  [edges threshold]
  (let [in-degree (frequencies (map second edges))
        out-degree (frequencies (map first edges))
        all-nodes (set (mapcat (juxt first second) edges))]
    (filter
      (fn [node]
        (let [in (get in-degree node 0)
              out (get out-degree node 0)]
          (or (>= in threshold) (>= out threshold))))
      all-nodes)))


(defn find-strongly-connected-components
  "Find clusters of tightly coupled namespaces (Tarjan's algorithm)."
  [graph]
  (let [index (atom 0)
        indices (atom {})
        lowlinks (atom {})
        on-stack (atom #{})
        stack (atom [])
        components (atom [])]

    (letfn [(strongconnect
              [v]
              (swap! indices assoc v @index)
              (swap! lowlinks assoc v @index)
              (swap! index inc)
              (swap! stack conj v)
              (swap! on-stack conj v)

              (doseq [w (get graph v [])]
                (if (not (contains? @indices w))
                  (do
                    (strongconnect w)
                    (swap! lowlinks assoc v (min (get @lowlinks v) (get @lowlinks w))))
                  (when (contains? @on-stack w)
                    (swap! lowlinks assoc v (min (get @lowlinks v) (get @indices w))))))

              (when (= (get @lowlinks v) (get @indices v))
                (let [component (atom [])]
                  (loop []
                    (let [w (peek @stack)]
                      (swap! stack pop)
                      (swap! on-stack disj w)
                      (swap! component conj w)
                      (when (not= w v)
                        (recur))))
                  (swap! components conj @component))))]

      (doseq [v (keys graph)]
        (when (not (contains? @indices v))
          (strongconnect v))))

    (filter #(> (count %) 1) @components)))


(defn generate-graph
  "Generate dep-graph.dot and dep-graph.svg using the dep-graph alias."
  []
  (println "Generating dependency graph...")
  (let [result (shell/sh "clj" "-M:dep-graph")]
    (when (not= (:exit result) 0)
      (println "Error generating dependency graph:")
      (println (:err result))
      (System/exit 1))))


(defn analyze-deps
  "Main analysis function."
  []
  (generate-graph)
  (let [dot-file "dep-graph.dot"
        edges (parse-dot-file dot-file)
        graph (build-graph edges)
        cycles (find-cycles graph)
        high-degree (find-high-degree-nodes edges 5)
        sccs (find-strongly-connected-components graph)]

    (println "\n=== DEPENDENCY COUPLING ANALYSIS ===\n")

    ;; Circular dependencies
    (if (empty? cycles)
      (println "✓ No circular dependencies detected")
      (do
        (println (str "⚠ CIRCULAR DEPENDENCIES (" (count cycles) " found)"))
        (doseq [cycle cycles]
          (println (str "  " (str/join " → " cycle) " → " (first cycle))))))

    (println)

    ;; High-degree nodes
    (if (empty? high-degree)
      (println "✓ No over-coupled namespaces (degree > 5)")
      (do
        (println (str "⚠ HIGH-DEGREE NODES (" (count high-degree) " namespaces with 6+ connections)"))
        (doseq [node (sort high-degree)]
          (let [in (count (filter #(= (second %) node) edges))
                out (count (filter #(= (first %) node) edges))]
            (println (str "  " node " (in:" in ", out:" out ")"))))))

    (println)

    ;; Strongly connected components
    (if (empty? sccs)
      (println "✓ No tightly coupled clusters (SCCs size > 1)")
      (do
        (println (str "⚠ TIGHTLY COUPLED CLUSTERS (" (count sccs) " found)"))
        (doseq [scc (sort-by count > sccs)]
          (println (str "  [" (count scc) " namespaces] " (str/join ", " (sort scc)))))))

    (println)
    (println "=== RECOMMENDATIONS ===")
    (println)

    (when (not (empty? cycles))
      (println "1. Break circular dependencies:")
      (println "   - Identify the interface between coupled namespaces")
      (println "   - Extract shared functions into a new namespace")
      (println "   - Have both depend on the new namespace instead of each other")
      (println))

    (when (not (empty? high-degree))
      (println "2. Reduce high-degree nodes:")
      (println "   - These namespaces have too many responsibilities")
      (println "   - Analyze internal clusters and extract into separate namespaces")
      (println "   - Minimize public API to essential entry points")
      (println))

    (when (not (empty? sccs))
      (println "3. Extract tightly coupled clusters:")
      (println "   - These clusters are natural candidates for new namespaces")
      (println "   - Create domain-appropriate names (see vocabulary.md)")
      (println "   - Define minimal public interfaces between clusters")
      (println))

    (println "\nFor detailed guidance, see .claude/rules/malleability.md")))


(analyze-deps)
