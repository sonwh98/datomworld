(ns dao.flow.hiccup)


(defn- next-eid
  [state]
  (let [eid (:next-eid @state)]
    (swap! state update :next-eid dec)
    eid))


(defn- hiccup-node?
  [x]
  (and (vector? x) (keyword? (first x))))


(defn- attrs?
  [x]
  (and (map? x) (not (hiccup-node? x))))


(defn- parse-node
  [node]
  (let [tag (first node)
        has-attrs? (and (> (count node) 1) (attrs? (second node)))
        attrs (if has-attrs? (second node) {})
        children (if has-attrs? (drop 2 node) (drop 1 node))]
    {:tag tag, :attrs attrs, :children children}))


(defn- parse-namespace-attrs
  [ns-key attrs state emit!]
  (when-let [sub-attrs (get attrs ns-key)]
    (let [eid (next-eid state)]
      (doseq [[k v] sub-attrs]
        (let [kw (keyword (name ns-key) (name k))] (emit! [eid kw v])))
      eid)))


(defn- process-node!
  [node parent-eid sort-key is-root? state emit!]
  (let [{:keys [tag attrs children]} (parse-node node)
        eid (next-eid state)]
    (when is-root? (emit! [eid :flow/scene-root true]))
    (emit! [eid :flow/tag tag])
    (when parent-eid
      (emit! [eid :flow/parent parent-eid])
      (when sort-key (emit! [eid :flow/sort-key sort-key])))
    (let [geom-ns (namespace tag)
          geom-name (name tag)]
      (when (= geom-ns "geom") (emit! [eid :geom/kind (keyword geom-name)]))
      (when (= geom-ns "camera") (emit! [eid :camera/kind (keyword geom-name)]))
      (when (= geom-ns "light") (emit! [eid :light/kind (keyword geom-name)])))
    (doseq [[k v] attrs]
      (cond (= k :transform)
            (let [t-eid (parse-namespace-attrs :transform attrs state emit!)]
              (emit! [eid :flow/transform t-eid]))
            (= k :material)
            (let [m-eid (parse-namespace-attrs :material attrs state emit!)]
              (emit! [eid :flow/material m-eid]))
            (= k :camera) (if (hiccup-node? v)
                            (let [cam-eid
                                  (process-node! v nil nil false state emit!)]
                              (emit! [eid k cam-eid]))
                            (emit! [eid k v]))
            :else
            (let [kw (if (namespace k) k (keyword (namespace tag) (name k)))]
              (emit! [eid kw v]))))
    (loop [idx 0
           [child & rest-children] children]
      (when child
        (process-node! child eid (* (inc idx) 1000) false state emit!)
        (recur (inc idx) rest-children)))
    eid))


(defn hiccup->datoms
  "Converts a Hiccup scene graph into a sequence of intent datoms."
  [hiccup]
  (let [state (atom {:next-eid -1})
        datoms (atom [])
        emit! (fn [datom] (swap! datoms conj datom))]
    (process-node! hiccup nil nil true state emit!)
    @datoms))


(defn datoms->hiccup
  "Converts a sequence of intent datoms back into a Hiccup scene graph."
  [datoms]
  (let [by-eid (group-by first datoms)
        entity (fn [eid] (into {} (map (fn [[_ a v]] [a v]) (get by-eid eid))))
        root-eid (first (for [[e a v] datoms :when (= a :flow/scene-root)] e))
        build-node
        (fn build-node
          [eid]
          (let [ent (entity eid)
                tag (:flow/tag ent)
                children (->> datoms
                              (filter (fn [[_ a v]]
                                        (and (= a :flow/parent) (= v eid))))
                              (map first)
                              (sort-by (fn [ce]
                                         (:flow/sort-key (entity ce) 0)))
                              (map build-node))
                attrs
                (reduce-kv
                  (fn [acc k v]
                    (cond
                      (#{:flow/scene-root :flow/tag :flow/parent
                         :flow/sort-key :geom/kind :camera/kind :light/kind}
                       k)
                      acc
                      (= k :flow/transform)
                      (let [t-ent (entity v)
                            t-attrs (into {}
                                          (map (fn [[tk tv]]
                                                 [(keyword
                                                    (name tk))
                                                  tv])
                                               t-ent))]
                        (assoc acc :transform t-attrs))
                      (= k :flow/material)
                      (let [m-ent (entity v)
                            m-attrs (into {}
                                          (map (fn [[mk mv]]
                                                 [(keyword
                                                    (name mk))
                                                  mv])
                                               m-ent))]
                        (assoc acc :material m-attrs))
                      (= (namespace k) (namespace tag))
                      (assoc acc (keyword (name k)) v)
                      :else (assoc acc k v)))
                  {}
                  ent)]
            (if (empty? attrs)
              (into [tag] children)
              (into [tag attrs] children))))]
    (if root-eid (build-node root-eid) [])))
