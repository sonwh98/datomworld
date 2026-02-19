(ns dep-graph
  "Namespace dependency graph and coupling metrics tool.
   Scans source paths, computes Ca/Ce/Instability per namespace,
   detects cycles, and emits a Graphviz DOT file."
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [clojure.tools.namespace.find :as ns-find]
    [clojure.tools.namespace.parse :as ns-parse]))


;; ---------------------------------------------------------------------------
;; Scanning
;; ---------------------------------------------------------------------------

(def source-paths ["src/clj" "src/cljc" "src/cljs"])


(defn find-ns-decls
  "Return all ns declaration forms found under `paths`.
   Scans for .clj, .cljc, and .cljs files."
  [paths]
  (let [platforms [ns-find/clj ns-find/cljs]]
    (mapcat (fn [p]
              (let [dir (io/file p)]
                (when (.isDirectory dir)
                  (mapcat #(ns-find/find-ns-decls [dir] %) platforms))))
            paths)))


(defn parse-deps
  "Given ns declarations, return {ns-sym #{dep-ns-sym ...}} filtered to
   only namespaces within the project."
  [decls]
  (let [all-ns (set (map second decls))
        dep-map (into {}
                      (map (fn [decl]
                             (let [ns-sym (second decl)
                                   deps (ns-parse/deps-from-ns-decl decl)]
                               [ns-sym (set (filter all-ns deps))])))
                      decls)]
    dep-map))


;; ---------------------------------------------------------------------------
;; Cycle detection (Tarjan's algorithm)
;; ---------------------------------------------------------------------------

(defn tarjan-scc
  "Return strongly connected components of `graph` {node -> #{neighbor}}.
   Each SCC is a set of nodes. Only SCCs with size > 1 indicate cycles."
  [graph]
  (let [state (atom {:index 0,
                     :stack [],
                     :on-stack #{},
                     :indices {},
                     :lowlinks {},
                     :sccs []})]
    (letfn
      [(strongconnect
         [v]
         (let [idx (:index @state)]
           (swap! state assoc-in [:indices v] idx)
           (swap! state assoc-in [:lowlinks v] idx)
           (swap! state update :index inc)
           (swap! state update :stack conj v)
           (swap! state update :on-stack conj v)
           (doseq [w (get graph v #{})]
             (cond (nil? (get-in @state [:indices w]))
                   (do (strongconnect w)
                       (swap! state assoc-in
                              [:lowlinks v]
                              (min (get-in @state [:lowlinks v])
                                   (get-in @state [:lowlinks w]))))
                   ((:on-stack @state) w) (swap! state assoc-in
                                                 [:lowlinks v]
                                                 (min (get-in @state [:lowlinks v])
                                                      (get-in @state
                                                              [:indices w])))))
           (when (= (get-in @state [:lowlinks v]) (get-in @state [:indices v]))
             (let [stack (:stack @state)
                   idx (.indexOf stack v)
                   scc (set (subvec stack idx))]
               (swap! state assoc :stack (subvec stack 0 idx))
               (swap! state update :on-stack #(reduce disj % scc))
               (swap! state update :sccs conj scc)))))]
      (doseq [v (keys graph)]
        (when (nil? (get-in @state [:indices v])) (strongconnect v)))
      (:sccs @state))))


(defn find-cycle-nodes
  "Return the set of all namespace symbols that participate in a cycle."
  [dep-map]
  (let [sccs (tarjan-scc dep-map)]
    (reduce into #{} (filter #(> (count %) 1) sccs))))


;; ---------------------------------------------------------------------------
;; Metrics
;; ---------------------------------------------------------------------------

(defn compute-metrics
  "Return {ns-sym {:ca n :ce n :instability f :circular? bool}} for each namespace."
  [dep-map]
  (let [all-ns (set (keys dep-map))
        cycle-nodes (find-cycle-nodes dep-map)
        ;; Ca: count of namespaces that depend on ns
        afferent
        (reduce (fn [acc [_ns deps]]
                  (reduce (fn [a d] (update a d (fnil inc 0))) acc deps))
                {}
                dep-map)]
    (into {}
          (map (fn [ns-sym]
                 (let [ce (count (get dep-map ns-sym #{}))
                       ca (get afferent ns-sym 0)
                       total (+ ca ce)
                       instability (if (zero? total) 0.0 (double (/ ce total)))]
                   [ns-sym
                    {:ca ca,
                     :ce ce,
                     :instability instability,
                     :circular? (contains? cycle-nodes ns-sym)}])))
          all-ns)))


;; ---------------------------------------------------------------------------
;; DOT generation
;; ---------------------------------------------------------------------------

(defn instability-color
  "HSL-based color: green (stable, I=0) -> yellow (I=0.5) -> red (unstable, I=1).
   Returns a hex color string."
  [instability]
  ;; Map instability 0..1 to hue 120..0 (green..red)
  (let [hue (* (- 1.0 instability) 120.0)
        ;; Convert HSL(hue, 80%, 45%) to RGB
        s 0.8
        l 0.45
        c (* (- 1.0 (Math/abs (- (* 2.0 l) 1.0))) s)
        h (/ hue 60.0)
        x (* c (- 1.0 (Math/abs (- (mod h 2.0) 1.0))))
        [r1 g1 b1] (cond (< h 1) [c x 0.0]
                         (< h 2) [x c 0.0]
                         (< h 3) [0.0 c x]
                         :else [0.0 x c])
        m (- l (/ c 2.0))
        to-hex (fn [v] (format "%02x" (int (* (+ v m) 255))))]
    (str "#" (to-hex r1) (to-hex g1) (to-hex b1))))


(defn generate-dot
  "Generate DOT string for the dependency graph."
  [dep-map metrics]
  (let [cycle-nodes (set (filter #(:circular? (metrics %)) (keys metrics)))
        cycle-edges (set (for [ns-sym cycle-nodes
                               dep (get dep-map ns-sym #{})
                               :when (contains? cycle-nodes dep)]
                           [ns-sym dep]))
        sb (StringBuilder.)]
    (.append sb "digraph deps {\n")
    (.append sb "  rankdir=TB;\n")
    (.append
      sb
      "  node [shape=box style=filled fontname=\"Helvetica\" fontsize=10];\n")
    (.append sb "  edge [color=\"#666666\" arrowsize=0.7];\n\n")
    ;; Nodes
    (doseq [[ns-sym m] (sort-by (comp :instability val) > metrics)]
      (let [color (instability-color (:instability m))
            label (format "%s\\nCa=%d Ce=%d I=%.2f%s"
                          (str ns-sym)
                          (:ca m)
                          (:ce m)
                          (:instability m)
                          (if (:circular? m) " CYCLE" ""))
            font-color (if (> (:instability m) 0.6) "white" "black")]
        (.append
          sb
          (format "  \"%s\" [label=\"%s\" fillcolor=\"%s\" fontcolor=\"%s\"];\n"
                  ns-sym
                  label
                  color
                  font-color))))
    (.append sb "\n")
    ;; Edges
    (doseq [[ns-sym deps] (sort-by key dep-map)
            dep (sort deps)]
      (if (contains? cycle-edges [ns-sym dep])
        (.append
          sb
          (format "  \"%s\" -> \"%s\" [color=red penwidth=2.0];\n" ns-sym dep))
        (.append sb (format "  \"%s\" -> \"%s\";\n" ns-sym dep))))
    (.append sb "}\n")
    (str sb)))


;; ---------------------------------------------------------------------------
;; Report
;; ---------------------------------------------------------------------------

(defn print-report
  "Print a console table of metrics sorted by instability descending."
  [metrics]
  (let [sorted (sort-by (comp :instability val) > metrics)
        header
        (format "%-40s %4s %4s %8s %s" "Namespace" "Ca" "Ce" "I" "Circular?")
        sep (apply str (repeat (count header) "-"))]
    (println)
    (println header)
    (println sep)
    (doseq [[ns-sym m] sorted]
      (println (format "%-40s %4d %4d %8.2f %s"
                       (str ns-sym)
                       (:ca m)
                       (:ce m)
                       (:instability m)
                       (if (:circular? m) "YES" ""))))
    (println sep)
    (println (format "  %d namespaces, %d in cycles"
                     (count metrics)
                     (count (filter (comp :circular? val) metrics))))
    (println)))


;; ---------------------------------------------------------------------------
;; CLI
;; ---------------------------------------------------------------------------

(defn parse-args
  [args]
  (loop [args args
         opts {}]
    (if (empty? args)
      opts
      (case (first args)
        "--filter" (recur (drop 2 args) (assoc opts :filter (second args)))
        "--layout" (recur (drop 2 args) (assoc opts :layout (second args)))
        (recur (rest args) opts)))))


(defn -main
  [& args]
  (try (let [opts (parse-args args)
             layout (or (:layout opts) "dot")
             decls (find-ns-decls source-paths)
             dep-map (parse-deps decls)
             dep-map
             (if-let [pat (:filter opts)]
               (let [re (re-pattern pat)]
                 (into {} (filter (fn [[k _]] (re-find re (str k)))) dep-map))
               dep-map)
             metrics (compute-metrics dep-map)
             dot-str (generate-dot dep-map metrics)
             dot-file "dep-graph.dot"
             svg-file "dep-graph.svg"]
         (spit dot-file dot-str)
         (println (format "Wrote %s (%d namespaces, %d edges)"
                          dot-file
                          (count metrics)
                          (reduce + (map (comp count val) dep-map))))
         (let [{:keys [exit err]} (sh/sh layout "-Tsvg" dot-file "-o" svg-file)]
           (if (zero? exit)
             (println (format "Wrote %s (layout=%s)" svg-file layout))
             (println (format "Could not generate %s with Graphviz `%s`: %s"
                              svg-file
                              layout
                              (str/trim (or err ""))))))
         (print-report metrics))
       (finally
         ;; clojure.java.shell/sh uses futures; ensure JVM exits promptly
         ;; for CLI usage.
         (shutdown-agents))))
