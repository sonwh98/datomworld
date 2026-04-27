(ns dao.flow.walk
  (:require
    [dao.db :as db]
    [dao.flow.ops :as ops]
    [dao.flow.transform :as t]))


(defn- find-scene-root
  [db]
  (first (first (db/q '[:find ?e :where [?e :flow/scene-root true]] db))))


(defn- find-active-camera
  [db scene-root]
  (first (first (db/q '[:find ?e :where [?e :camera/kind]] db))))


(defn- camera->clip-from-world
  [db camera-eid]
  (if-not camera-eid
    t/identity-mat4
    (let [ent (db/entity db camera-eid)
          kind (:camera/kind ent)
          proj (if (= kind :perspective)
                 (t/perspective-mat4 (or (:camera/fov ent) 60.0)
                                     (or (:camera/aspect ent) 1.0)
                                     (or (:camera/near ent) 0.1)
                                     (or (:camera/far ent) 100.0))
                 (t/orthographic-mat4 (or (:camera/left ent) -1.0)
                                      (or (:camera/right ent) 1.0)
                                      (or (:camera/bottom ent) -1.0)
                                      (or (:camera/top ent) 1.0)
                                      (or (:camera/near ent) -1.0)
                                      (or (:camera/far ent) 1.0)))
          t-eid (:flow/transform ent)
          view (if-not t-eid
                 t/identity-mat4
                 (let [t-ent (db/entity db t-eid)]
                   (t/invert-trs (:transform/translate t-ent)
                                 (:transform/rotate t-ent)
                                 (:transform/scale t-ent))))]
      (t/mul-mat4 proj view))))


(defn- lights-of
  [db scene-root]
  (map first (db/q '[:find ?e :where [?e :light/kind]] db)))


(defn- entity-local-trs
  [db eid]
  (let [ent (db/entity db eid)
        t-eid (:flow/transform ent)]
    (if-not t-eid
      t/identity-mat4
      (let [t-ent (db/entity db t-eid)]
        (t/compose-trs (:transform/translate t-ent)
                       (:transform/rotate t-ent)
                       (:transform/scale t-ent))))))


(defn- children-sorted
  [db parent-eid]
  (->> (db/q '[:find ?e :in $ ?parent :where [?e :flow/parent ?parent]]
             db
             parent-eid)
       (map first)
       (sort-by (fn [e] (:flow/sort-key (db/entity db e) 0)))))


(defn- geometry-tag?
  [tag]
  (and (keyword? tag) (= "geom" (namespace tag))))


(defn- geom->op*
  [db eid tag world cam-mat]
  (let [ent (db/entity db eid)
        kind (:geom/kind ent)
        clip (t/mul-mat4 cam-mat world)
        z (nth clip 14)
        w (nth clip 15)
        depth (if (zero? w) z (/ z w))
        attrs (reduce-kv (fn [acc k v]
                           (if (and (keyword? k) (= (namespace k) (name kind)))
                             (assoc acc (keyword (name k)) v)
                             acc))
                         {}
                         ent)
        m-eid (:flow/material ent)
        m-attrs (if m-eid
                  (reduce-kv (fn [acc k v]
                               (if (= (namespace k) "material")
                                 (assoc acc (keyword (name k)) v)
                                 acc))
                             {}
                             (db/entity db m-eid))
                  {})
        text (:geom/text ent)
        font-size (:geom/font-size ent)
        size (:geom/size ent)
        radius (:geom/radius ent)
        combined-attrs (cond-> (merge attrs m-attrs)
                         text (assoc :text text)
                         font-size (assoc :font-size font-size)
                         size (assoc :size size)
                         radius (assoc :radius radius))]
    (ops/geom->op kind combined-attrs world cam-mat depth eid)))


(defn walk-once
  [db]
  (let [scene-root (find-scene-root db)
        camera (find-active-camera db scene-root)
        cam-mat (camera->clip-from-world db camera)
        out (transient [])
        _ (doseq [l-eid (lights-of db scene-root)]
            (conj! out (ops/op-light (db/entity db l-eid))))
        rec (fn rec
              [eid parent-world]
              (let [local (entity-local-trs db eid)
                    world (t/mul-mat4 parent-world local)
                    tag (:flow/tag (db/entity db eid))]
                (when (geometry-tag? tag)
                  (conj! out (geom->op* db eid tag world cam-mat)))
                (doseq [child (children-sorted db eid)] (rec child world))))]
    (when scene-root (rec scene-root t/identity-mat4))
    (conj (vec (sort-by :op/depth > (persistent! out))) (ops/op-end-frame))))


(defn walk-xf
  [db-atom]
  (fn [rf]
    (fn
      ([] (rf))
      ([acc] (rf acc))
      ([acc mutation-datoms]
       (let [tx-data (mapv (fn [d]
                             (if (= (first d) :db/add)
                               d
                               (let [[e a v] d] [:db/add e a v])))
                           mutation-datoms)]
         (swap! db-atom #(get (db/transact % tx-data) :db-after))
         (rf acc (walk-once @db-atom)))))))
