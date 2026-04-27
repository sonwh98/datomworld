(ns datomworld.demo.flow-cube-state
  (:require
    [dao.db :as db]
    [dao.db.in-memory :as in-m]
    [dao.flow.animate :as anim]
    [dao.flow.hiccup :as hiccup]
    [dao.flow.walk :as walk]
    [dao.stream :as dao-stream]
    [dao.stream.ringbuffer :as ring]))


(def schema
  [{:db/ident :flow/parent, :db/valueType :db.type/ref}
   {:db/ident :flow/transform, :db/valueType :db.type/ref}
   {:db/ident :flow/material, :db/valueType :db.type/ref}
   {:db/ident :camera, :db/valueType :db.type/ref}])


(def scene
  [:flow/scene {:flow/clear-color [0.05 0.06 0.09 1.0]}
   [:camera/perspective
    {:fov 60.0, :near 0.1, :far 100.0, :transform {:translate [0 0 5]}}]
   [:geom/cube
    {:flow/id :spinner,
     :transform {:rotate [0.0 0.0 0.0]},
     :material {:color [0.2 0.6 0.95 1.0]}}]])


(def default-animation-delta [0.0 0.03 0.0])


(def default-animation-period-ms 16)


(defn- as-float
  [x]
  (+ 0.0 x))


(defn normalize-rotation
  [[rx ry rz]]
  [(as-float rx) (as-float ry) (as-float rz)])


(defn sync-scene!
  [{:keys [cursor-atom db-atom intent-stream]}]
  (loop [advanced? false]
    (let [res (dao-stream/next intent-stream @cursor-atom)]
      (if (map? res)
        (do (reset! cursor-atom (:cursor res))
            (swap! db-atom #(get (db/transact % (:ok res)) :db-after))
            (dao-stream/drain-one! intent-stream)
            (recur true))
        (when advanced? (walk/walk-once @db-atom))))))


(defn apply-rotation!
  [{:keys [angles-atom notifier-frame!], :as state} rotation]
  (let [rotation' (normalize-rotation rotation)]
    (reset! angles-atom rotation')
    (anim/rotation-tick (:intent-stream state) (:t-eid state) rotation')
    (when-let [frame (sync-scene! state)] (notifier-frame! frame))
    rotation'))


(defn rotate-by!
  [{:keys [angles-atom], :as state} delta]
  (apply-rotation! state (mapv + @angles-atom delta)))


(defn current-rotation
  [{:keys [angles-atom]}]
  @angles-atom)


(defn stop-animation!
  [{:keys [animation-stop-atom]}]
  (when-let [stop-fn @animation-stop-atom]
    (stop-fn)
    (reset! animation-stop-atom nil))
  {:running? false})


(defn animate!
  ([state] (animate! state default-animation-delta default-animation-period-ms))
  ([state delta] (animate! state delta default-animation-period-ms))
  ([{:keys [schedule-every! animation-stop-atom], :as state} delta period-ms]
   (when-not schedule-every!
     (throw (ex-info "Animation scheduler not installed"
                     {:period-ms period-ms})))
   (stop-animation! state)
   (let [delta' (normalize-rotation delta)
         stop-fn (schedule-every! period-ms #(rotate-by! state delta'))]
     (reset! animation-stop-atom stop-fn)
     {:running? true, :period-ms period-ms, :delta delta'})))


(defn repl-primitives
  [state]
  {'cube-rotation (fn [] (current-rotation state)),
   'set-cube-rotation! (fn [x y z] (apply-rotation! state [x y z])),
   'rotate-cube! (fn [dx dy dz] (rotate-by! state [dx dy dz])),
   'reset-cube-rotation! (fn [] (apply-rotation! state [0.0 0.0 0.0])),
   'animate! (fn [& args]
               (case (count args)
                 0 (animate! state)
                 3 (animate! state args)
                 4 (animate! state (take 3 args) (nth args 3))
                 (throw (ex-info "animate! expects 0, 3, or 4 args"
                                 {:args args})))),
   'stop-animation! (fn [] (stop-animation! state))})


(defn create-demo-state
  ([notifier-frame!] (create-demo-state notifier-frame! {}))
  ([notifier-frame! {:keys [schedule-every!]}]
   (let [db-with-schema (:db-after (db/transact (in-m/empty-db) schema))
         intent-stream (ring/make-ring-buffer-stream 100)
         datoms (hiccup/hiccup->datoms scene)
         tx-data (mapv (fn [[e a v]] [:db/add e a v]) datoms)
         _ (dao-stream/put! intent-stream tx-data)
         state {:intent-stream intent-stream,
                :db-atom (atom db-with-schema),
                :cursor-atom (atom {:position 0}),
                :angles-atom (atom [0.0 0.0 0.0]),
                :animation-stop-atom (atom nil),
                :schedule-every! schedule-every!,
                :notifier-frame! notifier-frame!}
         initial-frame (sync-scene! state)
         t-eid (first (first (db/run-q @(:db-atom state)
                                       '[:find ?t :where [?e :geom/kind :cube]
                                         [?e :flow/transform ?t]]
                                       [])))
         state' (assoc state :t-eid t-eid)]
     (when initial-frame (notifier-frame! initial-frame))
     state')))
