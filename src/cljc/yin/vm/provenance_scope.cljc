(ns yin.vm.provenance-scope
  "Executable-address grants for the bundled Clojure provenance doors."
  (:require [dao.jing :as jing]
            [yin.vm :as vm]))


(def ^:private marker :clj/bundled-runtime)

(def ^:private trusted
  ;; The closed clj bundle's row address and its three executable projections.
  ;; Each executable grant names an exact call in the normalized body graph.
  {:row :segment/blake3-e03d4f18a55e2186cb59899e6c3c8f5512e2de02ee3565178ef81c9a70bbb3db
   :semantic
   {:address :segment/blake3-4133595e69de3dd4ff63c254699bfe56c3d7cb7fe3a38b9e8138b95b1566a31c
    :calls [[256 :cell/provenance] [489 :cell/provenance]
            [521 :cell/new-provenance] [692 :cell/provenance]]}
   :stack
   {:address :segment/blake3-7c04121ffc7828759de3e6e0f8948c962342e966aaab4b3b9b44b96aa534a50a
    :calls [[391 :cell/provenance] [766 :cell/provenance]
            [818 :cell/new-provenance] [1100 :cell/provenance]]}
   :register
   {:address :segment/blake3-08405b15735ef385073bdcd5cbb3fcef56f46f0b7100ec3db0c6045d277b3277
    :calls [[256 :cell/provenance] [489 :cell/provenance]
            [521 :cell/new-provenance] [692 :cell/provenance]]}})

(def doors
  {'cell/dsg__new-provenance :cell/new-provenance
   'cell/dsg__provenance :cell/provenance})


(defn- node-children [node]
  (mapcat (fn [[key kind]]
            (case kind
              :node [(get node key)]
              :nodes (get node key)
              []))
          (get vm/semantic-bytecode-grammar (:type node))))


(defn mark-bundled-calls
  "Find bundled scopes in a loader-owned AST. Mark door application occurrences
   only below the verified closed lambda. Metadata on incoming ASTs has no
   authority: VM loaders reconstruct their nodes from datoms or rows."
  [ast]
  (let [bundled? (volatile! false)]
    (letfn [(visit [node granted?]
              (let [starts-scope? (and (= :lambda (:type node))
                                       (= marker (get-in node [:body :operands 0 :value])))
                    _ (when starts-scope? (vreset! bundled? true))
                    granted? (or granted?
                                 (and starts-scope?
                                      (= (:row trusted) (:root (vm/ast->semantic-bytecode node)))))
                    rebuilt (reduce (fn [n [key kind]]
                                      (case kind
                                        :node (update n key visit granted?)
                                        :nodes (update n key #(mapv (fn [child]
                                                                      (visit child granted?)) %))
                                        n))
                                    node
                                    (get vm/semantic-bytecode-grammar (:type node)))]
                (if-let [door (when (and granted? (= :application (:type node)))
                                (get doors (get-in node [:operator :name])))]
                  (vary-meta rebuilt assoc ::door door)
                  rebuilt)))]
      ;; Skip rebuilding legacy ASTs entirely.
      (if (some #(= marker (:value %)) (tree-seq (comp seq node-children) node-children ast))
        (let [tree (visit ast false)]
          {:ast tree :bundled? @bundled?})
        {:ast ast}))))


(defn ast-door? [application door]
  (= door (::door (meta application))))


(def ^:private target-slots
  ;; The three PC operands in the frozen executable formats.
  {:semantic {:closure 3 :jump 1 :branch-false 2}
   :stack {:closure 2 :jump 1 :branch-false 1}
   :register {:closure 3 :jump 1 :branch-false 2}})


(defn image-instructions
  "Project the selected positional image from the relocated instruction space."
  [format instructions base size]
  (let [slice (subvec instructions base (+ base size))
        slots (get target-slots format)]
    (if (zero? base)
      slice
      (mapv (fn [t]
              (if-let [slot (get slots (first t))]
                (update t slot - base)
                t))
            slice))))


(defn bundle-graph
  "Derive a relocated bundle's stable code address and its instruction PCs.
   The marker only identifies candidates. All bodies reachable by closure
   creation participate in the address, with canonical relative targets."
  [format instructions]
  (let [slots (get target-slots format)
        closure-target (:closure slots)
        markers (keep-indexed (fn [pc t]
                                (when (and (= :const (first t)) (= marker (peek t))) pc))
                              instructions)]
    (when (seq markers)
      (let [starts (vec (sort (into #{0}
                                   (keep #(when (= :closure (first %))
                                            (nth % closure-target)))
                                   instructions)))
            ends (zipmap starts (concat (rest starts) [(count instructions)]))
            graph (fn [entry]
                    (loop [pending [entry], body-ids {entry 0}, i 0, pcs [], bodies []]
                      (if-some [start (get pending i)]
                        (let [end (get ends start)
                              body-pcs (range start end)
                              [pending body-ids body]
                              (reduce
                                (fn [[todo ids body] pc]
                                  (let [t (nth instructions pc)
                                        op (first t)]
                                    (if (= op :closure)
                                      (let [child (nth t closure-target)]
                                        (if-some [id (get ids child)]
                                          [todo ids (conj body (assoc t closure-target id))]
                                          [(conj todo child) (assoc ids child (count todo))
                                           (conj body (assoc t closure-target (count todo)))]))
                                      [todo ids
                                       (conj body
                                             (if-let [slot (get slots op)]
                                               (update t slot - start)
                                               t))])))
                                [pending body-ids []]
                                body-pcs)]
                          (recur pending body-ids (inc i) (into pcs body-pcs) (conj bodies body)))
                        {:address (jing/segment-key bodies) :pcs pcs})))]
        (mapv graph (distinct (map (fn [pc] (last (take-while #(<= % pc) starts)))
                                   markers)))))))


(defn code-grants
  "The private door granted at each executable call PC, if this is a clj bundle."
  [format instructions]
  (when-let [graphs (seq (bundle-graph format instructions))]
    (let [{:keys [address calls]} (get trusted format)]
      (into {}
            (mapcat (fn [graph]
                      (when (= address (:address graph))
                        (map (fn [[i door]] [(nth (:pcs graph) i) door]) calls))))
            graphs))))
