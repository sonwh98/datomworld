(ns dao.flow.compose-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.db :as db]
    [dao.db.in-memory :as in-m]
    [dao.flow :as flow]
    [dao.flow.hiccup :as hiccup]
    [dao.flow.walk :as walk]
    [dao.stream :as ds]
    [dao.stream.ringbuffer :as ring]))


(defn- test-db-atom
  [hiccup]
  (let [schema [{:db/ident :flow/parent, :db/valueType :db.type/ref}
                {:db/ident :flow/transform, :db/valueType :db.type/ref}
                {:db/ident :flow/material, :db/valueType :db.type/ref}
                {:db/ident :camera, :db/valueType :db.type/ref}]
        db-with-schema (:db-after (db/transact (in-m/empty-db) schema))]
    (atom db-with-schema)))


(defn- passthrough-xf
  [rf]
  (fn
    ([] (rf))
    ([acc] (rf acc))
    ([acc input]
     ;; just pass it through
     (rf acc input))))


(deftest insert-passthrough-stage
  (testing
    "assert a no-op middle stage can be inserted between walk and paint with identical output"
    (let [db-atom1 (test-db-atom [])
          db-atom2 (test-db-atom [])
          scene [:flow/scene
                 [:camera/orthographic
                  {:left 0, :right 100, :top 0, :bottom 100}]
                 [:geom/cube {:flow/id :c1, :transform {:translate [10 0 0]}}]]
          datoms (hiccup/hiccup->datoms scene)
          tx-data (mapv (fn [[e a v]] [:db/add e a v]) datoms)
          intent-stream1 (ring/make-ring-buffer-stream 10)
          intent-stream2 (ring/make-ring-buffer-stream 10)
          _ (ds/put! intent-stream1 tx-data)
          _ (ds/put! intent-stream2 tx-data)
          collect-rf (fn
                       ([] []) ([acc] acc) ([acc frame] (conj acc frame)))
          ;; without passthrough
          frames1 (flow/stream-transduce (walk/walk-xf db-atom1)
                                         collect-rf
                                         []
                                         intent-stream1
                                         {:position 0})
          ;; with passthrough
          frames2 (flow/stream-transduce (comp (walk/walk-xf db-atom2)
                                               passthrough-xf)
                                         collect-rf
                                         []
                                         intent-stream2
                                         {:position 0})]
      (is (= 1 (count frames1)))
      (is (= frames1 frames2)))))


(deftest fused-equals-streamed
  (testing
    "fused composition produces the same op-frame sequence as the stream-mediated equivalent"
    (let [db-atom1 (test-db-atom [])
          db-atom2 (test-db-atom [])
          scene [:flow/scene
                 [:camera/orthographic
                  {:left 0, :right 100, :top 0, :bottom 100}]
                 [:geom/cube {:flow/id :c1, :transform {:translate [10 0 0]}}]]
          datoms (hiccup/hiccup->datoms scene)
          tx-data (mapv (fn [[e a v]] [:db/add e a v]) datoms)
          intent-stream1 (ring/make-ring-buffer-stream 10)
          intent-stream2 (ring/make-ring-buffer-stream 10)
          _ (ds/put! intent-stream1 tx-data)
          _ (ds/put! intent-stream2 tx-data)
          ;; Find the transform entity id for the cube
          db-tmp (:db-after (db/transact @db-atom1 tx-data))
          t-eid (first (first (db/q '[:find ?t :where [?e :geom/kind :cube]
                                      [?e :flow/transform ?t]]
                                    db-tmp)))
          tx-data2 [[:db/add t-eid :transform/rotate [0 1 0]]]
          _ (ds/put! intent-stream1 tx-data2)
          _ (ds/put! intent-stream2 tx-data2)
          collect-rf (fn
                       ([] []) ([acc] acc) ([acc frame] (conj acc frame)))
          ;; 1. FUSED PIPELINE
          frames-fused (flow/stream-transduce (walk/walk-xf db-atom1)
                                              collect-rf
                                              []
                                              intent-stream1
                                              {:position 0})
          ;; 2. STREAM-MEDIATED PIPELINE
          op-stream (ring/make-ring-buffer-stream 10)
          ;; writer rf to push frames into op-stream
          append-rf (fn
                      ([] op-stream)
                      ([stream] stream)
                      ([stream frame] (ds/put! stream frame) stream))
          ;; Phase A: read intent stream, write to op stream
          _ (flow/stream-transduce (walk/walk-xf db-atom2)
                                   append-rf
                                   op-stream
                                   intent-stream2
                                   {:position 0})
          ;; Phase B: read from op-stream
          frames-streamed (flow/stream-transduce identity
                                                 collect-rf
                                                 []
                                                 op-stream
                                                 {:position 0})]
      (is (= 2 (count frames-fused)))
      (is (= frames-fused frames-streamed)))))
