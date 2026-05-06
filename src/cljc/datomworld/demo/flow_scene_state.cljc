(ns datomworld.demo.flow-scene-state
  (:require
    [dao.db :as db]
    [dao.db.in-memory :as in-m]
    [dao.flow :as flow]
    [dao.flow.hiccup :as hiccup]
    [dao.flow.walk :as walk]
    [dao.stream :as ds]
    [dao.stream.ringbuffer :as ring]))


(def schema
  [{:db/ident :flow/parent, :db/valueType :db.type/ref}
   {:db/ident :flow/transform, :db/valueType :db.type/ref}
   {:db/ident :flow/material, :db/valueType :db.type/ref}
   {:db/ident :camera, :db/valueType :db.type/ref}])


(def initial-scene
  [:flow/scene {:flow/clear-color [0.02 0.02 0.06 1.0]}
   [:camera/perspective
    {:fov 55.0,
     :near 0.1,
     :far 200.0,
     :transform {:translate [0 16 34], :rotate [-0.44 0 0]}}]
   [:geom/sphere
    {:flow/id :sun,
     :transform {:scale [3.0 3.0 3.0]},
     :material {:color [1.0 0.75 0.1 1.0]}}]
   [:geom/cube
    {:flow/id :mercury,
     :transform {:translate [5.5 0.0 0.0], :scale [0.35 0.35 0.35]},
     :material {:color [0.55 0.55 0.55 1.0]}}]
   [:geom/cube
    {:flow/id :venus,
     :transform {:translate [8.0 0.0 0.0], :scale [0.65 0.65 0.65]},
     :material {:color [0.9 0.75 0.4 1.0]}}]
   [:geom/cube
    {:flow/id :earth,
     :transform {:translate [11.0 0.0 0.0], :scale [0.7 0.7 0.7]},
     :material {:color [0.2 0.5 0.9 1.0]}}
    [:flow/group {:flow/id :moon-pivot, :transform {:rotate [0.0 0.0 0.0]}}
     [:geom/cube
      {:flow/id :moon,
       :transform {:translate [1.5 0.0 0.0], :scale [0.25 0.25 0.25]},
       :material {:color [0.75 0.75 0.78 1.0]}}]]]
   [:geom/cube
    {:flow/id :mars,
     :transform {:translate [14.5 0.0 0.0], :scale [0.5 0.5 0.5]},
     :material {:color [0.75 0.25 0.1 1.0]}}]])


(defn- last-rf
  "Reducing function that keeps the last value."
  ([] nil)
  ([acc] acc)
  ([_ v] v))


(defn- transact-via-stream!
  "Drive tx-data through walk-xf + stream-transduce on a closed finite stream.
   Returns the final rendered frame, or nil if the stream was empty."
  [db-atom tx-data]
  (let [stream (ring/make-ring-buffer-stream 2)]
    (ds/put! stream tx-data)
    (ds/close! stream)
    (flow/stream-transduce (walk/walk-xf db-atom)
                           last-rf
                           nil
                           stream
                           {:position 0})))


(defn- emit!
  [{:keys [db-atom notifier-frame!]} tx-data]
  (when-let [frame (transact-via-stream! db-atom tx-data)]
    (notifier-frame! frame)))


(defn- transform-eid-of
  [db target-id]
  (some (fn [[i t]] (when (= i target-id) t))
        (db/run-q db
                  '[:find ?i ?t :where [?e :flow/id ?i] [?e :flow/transform ?t]]
                  [])))


(defn- material-eid-of
  [db target-id]
  (some (fn [[i m]] (when (= i target-id) m))
        (db/run-q db
                  '[:find ?i ?m :where [?e :flow/id ?i] [?e :flow/material ?m]]
                  [])))


(defn- camera-transform-eid
  [db]
  (ffirst (db/run-q db
                    '[:find ?t :where [?e :camera/kind ?k]
                      [?e :flow/transform ?t]]
                    [])))


(defn- scene-root-eid
  [db]
  (ffirst (db/run-q db '[:find ?e :where [?e :flow/scene-root true]] [])))


(defn translate!
  [state id x y z]
  (when-let [t-eid (transform-eid-of @(:db-atom state) id)]
    (emit! state
           [[:db/add t-eid :transform/translate
             [(double x) (double y) (double z)]]])))


(defn rotate-body!
  [state id rx ry rz]
  (when-let [t-eid (transform-eid-of @(:db-atom state) id)]
    (emit! state
           [[:db/add t-eid :transform/rotate
             [(double rx) (double ry) (double rz)]]])))


(defn color!
  [state id r g b]
  (when-let [m-eid (material-eid-of @(:db-atom state) id)]
    (emit! state
           [[:db/add m-eid :material/color
             [(double r) (double g) (double b) 1.0]]])))


(defn scale!
  [state id s]
  (when-let [t-eid (transform-eid-of @(:db-atom state) id)]
    (emit! state
           [[:db/add t-eid :transform/scale
             [(double s) (double s) (double s)]]])))


(defn camera-translate!
  [state x y z]
  (when-let [t-eid (camera-transform-eid @(:db-atom state))]
    (emit! state
           [[:db/add t-eid :transform/translate
             [(double x) (double y) (double z)]]])))


(defn add-body!
  [state id x y z r g b]
  (let [db @(:db-atom state)
        root-eid (scene-root-eid db)
        e -1
        t-e -2
        m-e -3]
    (emit! state
           [[:db/add e :flow/tag :geom/cube] [:db/add e :geom/kind :cube]
            [:db/add e :flow/id id] [:db/add e :flow/parent root-eid]
            [:db/add t-e :transform/translate
             [(double x) (double y) (double z)]] [:db/add e :flow/transform t-e]
            [:db/add m-e :material/color [(double r) (double g) (double b) 1.0]]
            [:db/add e :flow/material m-e]])))


(defn bodies
  [{:keys [db-atom]}]
  (->> (db/run-q @db-atom '[:find ?e ?i :where [?e :flow/id ?i]] [])
       (map second)
       sort
       vec))


(defn stop-all!
  [{:keys [animations-atom]}]
  (doseq [[_ stop-fn] @animations-atom] (stop-fn))
  (reset! animations-atom {})
  :stopped)


(defn reset-scene!
  [{:keys [db-atom notifier-frame!], :as state}]
  (stop-all! state)
  (let [fresh-db (:db-after (db/transact (in-m/empty-db) schema))
        _ (reset! db-atom fresh-db)
        init-tx (mapv (fn [[e a v]] [:db/add e a v])
                      (hiccup/hiccup->datoms initial-scene))
        frame (transact-via-stream! db-atom init-tx)]
    (when frame (notifier-frame! frame))
    :reset))


(defn repl-primitives
  [state]
  {'bodies (fn [] (bodies state)),
   'translate! (fn [id x y z] (translate! state (keyword id) x y z)),
   'color! (fn [id r g b] (color! state (keyword id) r g b)),
   'scale! (fn [id s] (scale! state (keyword id) s)),
   'add-body! (fn [id x y z r g b] (add-body! state (keyword id) x y z r g b)),
   'camera! (fn [x y z] (camera-translate! state x y z)),
   'stop! (fn [] (stop-all! state)),
   'reset! (fn [] (reset-scene! state))})


(defn create-demo-state
  [notifier-frame! {:keys [schedule-every!]}]
  (let [fresh-db (:db-after (db/transact (in-m/empty-db) schema))
        db-atom (atom fresh-db)
        init-tx (mapv (fn [[e a v]] [:db/add e a v])
                      (hiccup/hiccup->datoms initial-scene))
        state {:db-atom db-atom,
               :animations-atom (atom {}),
               :schedule-every! schedule-every!,
               :notifier-frame! notifier-frame!}
        initial-frame (transact-via-stream! db-atom init-tx)]
    (when initial-frame (notifier-frame! initial-frame))
    state))
