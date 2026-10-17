(require '[yin.vm.semantic-register.linearize :as linearize]
         '[yin.vm.semantic-register.corpus :as corpus]
         '[yin.vm.semantic-register.frame-test :as ft]
         '[yin.vm.semantic-register.frame :as frame]
         '[yin.vm.ucf.rows :as rows]
         '[yin.vm.ucf.rows-test :as rt]
         '[dao.jing :as jing]
         '[dao.jing.cbor :as cbor]
         '[clojure.pprint :as pp])


(println "=== worked-example vector (pc: inst) ===")
(doseq [[i t] (map-indexed vector ft/worked-vector)] (println i t))
(println "address" ft/worked-address)
(println)
(println "=== row 22 bare: raw frame, E2 chain, nil window ===")


(let [fx (first (filter #(and (= 22 (:row %)) (= :bare (:wrapper %))) (ft/reference-machines)))
      {:keys [frame code context task]} (first (ft/reference-frames fx))
      v (get code (:yin.k/segment frame))]
  (println "state" (:yin.k/state frame) "pc" (:yin.k/pc frame) "deliver" (:yin.k/deliver frame))
  (println "window" (:yin.k/window frame))
  (println "bindings keys" (keys (get-in frame [:yin.k/env :yin.k/bindings])))
  (println "k count" (count (:yin.k/k frame)))
  (println "inst at pc-1" (nth v (dec (:yin.k/pc frame))))
  (println "defect" (frame/defect frame code context task))
  (let [pf (#'rt/portable-frame (:machine fx) frame)
        wire (rows/frame->rows pf)
        index (into {} (map (fn [r] [(first r) r])) (:yin.k/rows wire))
        win-rows (filter #(= :window (second %)) (:yin.k/rows wire))]
    (println "root" (:yin.k/root wire) "rows" (count (:yin.k/rows wire)))
    (doseq [r win-rows] (println "window row" r))
    ;; independent Merkle recomputation: hash each body with jing directly
    (println "all ids = jing/segment-key body?" (every? (fn [[id & body]] (= id (jing/segment-key (vec body)))) (:yin.k/rows wire)))
    (println "all blake3?" (every? (fn [[id & _]] (= :blake3 (:algorithm (jing/parse-segment-address id)))) (:yin.k/rows wire)))
    ;; hand-built act body from independently constructed pieces
    (let [W (:yin.k/window pf)
          hand-window (into [:window] (mapcat (fn [[r v]] [r [:lit v]]) (sort-by first W)))
          hand-window-id (jing/segment-key hand-window)]
      (println "hand window body" hand-window)
      (println "hand window id" hand-window-id "present in wire?" (contains? index hand-window-id)))))


(println)
(println "=== row 21 bare: E and lexical chain ===")


(let [fx (first (filter #(and (= 21 (:row %)) (= :bare (:wrapper %))) (ft/reference-machines)))
      {:keys [frame code context task]} (first (ft/reference-frames fx))
      v (get code (:yin.k/segment frame))]
  (println "bindings" (into {} (map (fn [[k v]] [k (if (coll? v) (type v) v)])) (get-in frame [:yin.k/env :yin.k/bindings])))
  (println "free-env" (:free-env task))
  (println "pc" (:yin.k/pc frame) "inst pc-1" (nth v (dec (:yin.k/pc frame))))
  (println "lexical names" (#'frame/lexical-names v (:yin.k/pc frame)))
  (doseq [[i t] (map-indexed vector v)] (println i t)))


(println)
(println "=== probes ===")


(defn err
  [f]
  (try (f) :no-error (catch Throwable e (or (ex-data e) (str (type e) " " (ex-message e))))))


(println "pc 0 act:" (err #(frame/defect (assoc ft/worked-frame :yin.k/pc 0) ft/worked-code :wait ft/task)))
(println "k nil:" (err #(frame/defect (assoc ft/worked-frame :yin.k/k nil) ft/worked-code :wait ft/task)))
(println "window {3 :x} (extra+missing):" (err #(frame/defect (assoc ft/worked-frame :yin.k/window {3 :x}) ft/worked-code :wait ft/task)))
(println "pending not a map:" (err #(frame/defect (assoc ft/worked-frame :yin.k/pending 5) ft/worked-code :wait ft/task)))
(println "store-of nil explicit roundtrip:" (err #(let [f (assoc-in ft/worked-frame [:yin.k/env :yin.k/store-of] nil)] (= f (rows/rows->frame (rows/frame->rows f))))))
(println "K with pc 0 return frame:" (err #(frame/defect (assoc ft/worked-frame :yin.k/k [{:yin.k/frame-type :return :yin.k/segment ft/worked-address :yin.k/pc 0 :yin.k/env {:yin.k/bindings {}} :yin.k/window {} :yin.k/rd 2}]) ft/worked-code :wait ft/task)))


(let [wire (rows/emit (rt/task-node [] [:lit nil]))
      troot (:yin.k/root wire)
      index (into {} (map (fn [r] [(first r) r])) (:yin.k/rows wire))
      trow (get index troot)]
  (println "extra wire key:" (err #(rows/decode (assoc wire :extra 1))))
  (println "installs slot = 5 (gate reads before schema):" (err #(rows/decode (rt/replace-row wire troot (assoc (subvec trow 1) 13 5)))))
  (println "cells row with bad cell id (ordered-slots edn):" (err #(rows/decode (rt/replace-row wire (first (first (filter #(= :cells (second %)) (:yin.k/rows wire)))) [:cells :x (first (first (filter #(= :cell-body (second %)) (:yin.k/rows wire))))]))))
  (println "root not in rows:" (err #(rows/decode (assoc wire :yin.k/root "nope"))))
  (println "version float64 3:" (err #(rows/decode (assoc wire :yin.k/version (cbor/float64 3))))))


(println "wrong-numbering emit-numbered:" (err #(rows/emit-numbered (rt/fixture :s1 :s2 :x :y) {:x :yin.k/c-1})))
(println "canonical numbering of fixture:" (rows/canonical-numbering (rt/fixture :s1 :s2 :x :y)))
(println "canonical numbering of alias a:" (rows/canonical-numbering (rt/task-node [[:local/x (rt/cell-body :s 0)] [:local/y (rt/cell-body :s 0)]] [:row (rows/node :vec [[:cell :local/x] [:cell :local/x] [:cell :local/y]])])))
(println "root of fixture :s1 :s2 :x :y  =" (:yin.k/root (rows/emit (rt/fixture :s1 :s2 :x :y))))
(println "root of fixture :s1 :s2 :y :x  =" (:yin.k/root (rows/emit (rt/fixture :s1 :s2 :y :x))))
(println "root of worked-frame frame->rows =" (:yin.k/root (rows/frame->rows ft/worked-frame)))
(shutdown-agents)
