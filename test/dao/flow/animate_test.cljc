(ns dao.flow.animate-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.db :as db]
    [dao.db.in-memory :as in-m]
    [dao.flow :as flow]
    [dao.flow.animate :as anim]
    [dao.flow.hiccup :as hiccup]
    [dao.flow.walk :as walk]
    [dao.stream.ringbuffer :as ring]))


(deftest mutation-stream-redrives-walk
  (testing
    "apply rotate mutations; assert walker output rotation evolves at each cursor."
    (let [schema [{:db/ident :flow/parent, :db/valueType :db.type/ref}
                  {:db/ident :flow/transform, :db/valueType :db.type/ref}
                  {:db/ident :flow/material, :db/valueType :db.type/ref}
                  {:db/ident :camera, :db/valueType :db.type/ref}]
          db-with-schema (:db-after (db/transact (in-m/empty-db) schema))
          intent-stream (ring/make-ring-buffer-stream 10)
          cursor {:position 0}
          ;; initial scene
          scene [:flow/scene
                 [:camera/orthographic {:left -1, :right 1, :top -1, :bottom 1}]
                 [:geom/cube {:flow/id :spinner, :transform {:rotate [0 0 0]}}]]
          datoms (hiccup/hiccup->datoms scene)
          tx-data (mapv (fn [[e a v]] [:db/add e a v]) datoms)
          ;; Write the initial scene to the stream
          _ (dao.stream/put! intent-stream tx-data)
          db-atom (atom db-with-schema)
          ;; We collect frames
          frames-atom (atom [])
          collect-rf (fn
                       ([] @frames-atom)
                       ([acc] acc)
                       ([acc frame] (swap! frames-atom conj frame) acc))
          ;; Fused walk + collect
          _ (flow/stream-transduce (walk/walk-xf db-atom)
                                   collect-rf
                                   []
                                   intent-stream
                                   cursor)
          ;; Now we should have 1 frame.
          frames1 @frames-atom
          _ (is (= 1 (count frames1)))
          ;; Find the cube's transform entity in the database to mutate it
          ;; (In a real app, Yin.VM or the animator has this entity ID).
          ;; We can find it via the db-atom.
          t-eid (first (first (db/q '[:find ?t :where [?e :geom/kind :cube]
                                      [?e :flow/transform ?t]]
                                    @db-atom)))
          ;; Emit a rotation
          _ (anim/rotation-tick intent-stream t-eid [0 1.5 0])
          ;; Advance the stream by resuming from the next cursor. Wait, our
          ;; stream-transduce blocks or returns when blocked. Let's just
          ;; track the cursor or run it again. Actually, `stream-transduce`
          ;; returns the final acc. In this test, it returned because the
          ;; ringbuffer was empty (blocked). Let's capture the cursor
          ;; correctly. Our simple `stream-transduce` doesn't return the
          ;; cursor. Let's redefine a stateful cursor read for the test.
          cursor2 {:position 1}
          _ (flow/stream-transduce (walk/walk-xf db-atom)
                                   collect-rf
                                   []
                                   intent-stream
                                   cursor2)
          frames2 @frames-atom]
      (is (= 2 (count frames2)))
      ;; First frame had rotate [0 0 0] -> identity transform, so tx should
      ;; be unchanged. Second frame had rotate [0 1.5 0] -> rotated. Let's
      ;; just check the :op/world differs.
      (let [world1 (:op/world (first (first frames1)))
            world2 (:op/world (first (second frames2)))]
        (is (not= world1 world2))))))
