(ns yin.vm.semantic.derive
  "Pure native derivations from the admitted semantic register vector A."
  (:refer-clojure :exclude [resolve])
  (:require [yin.vm.semantic.code :as code]
            [yin.vm.debruijn :as db]
            [yin.vm.semantic.analysis :as analysis]
            [yin.vm.debruijn-register-code :as rcode]))


(defn- chains
  [v bodies]
  (letfn [(chain
            [i]
            (if-let [owner (:owner (nth bodies i))]
              (into [(nth (nth v owner) 2)]
                    (chain (code/body-index bodies owner)))
              []))]
    (mapv chain (range (count bodies)))))


(defn resolve
  "Rewrite only variables and closure binders. The result is an unhashed
   stage value; pcs and virtual registers are preserved."
  [v]
  (let [bodies (code/parse v)
        frames (chains v bodies)
        names (volatile! {})]
    {:vector
     (mapv (fn [pc]
             (let [[op rd x y :as t] (nth v pc)]
               (case op
                 :var (let [{:keys [bound free]}
                            (db/resolve-name (nth frames (code/body-index bodies pc)) x)]
                        (if bound
                          (do (vswap! names assoc pc x)
                              (into [:load-bound rd] bound))
                          [:load-free rd free]))
                 :closure [:closure rd (count x) y]
                 t)))
           (range (count v)))
     :bodies bodies
     :names @names}))


(defn addresses
  "Lexical references in pc order, from A, a resolved vector or a native
   instruction vector (register or stack). Returns a vector, no identity."
  [v]
  (let [v (if (map? v) (or (:vector v) (:instructions v)) v)
        v (if (some #(= :var (first %)) v) (:vector (resolve v)) v)]
    (into []
          (keep (fn [t]
                  (case (first t)
                    :load-bound (into [:bound] (subvec t (- (count t) 2)))
                    :load-free [:free (peek t)]
                    nil)))
          v)))


(defn stack-image
  "Stackify A's parsed expressions, placing closure bodies in FIFO order.
   Labels and diagnostics are derived solely from A."
  [v]
  (let [{rv :vector bodies :bodies} (resolve v)
        by-start (into {} (map (juxt :start identity)) bodies)
        image (volatile! [])
        side (volatile! {})
        labels (volatile! {})
        queue (volatile! [])
        counter (volatile! 0)
        label! (fn [] (vswap! counter inc))
        mark! (fn [l] (vswap! labels assoc l (count @image)))
        emit! (fn [t] (vswap! image conj t))]
    (letfn [(walk
              [node]
              (let [pc (:pc node)
                    [op _ x y z] (nth rv pc)
                    children (:children node)
                    diagnostic! (fn [info] (vswap! side assoc (count @image) info))]
                (case (:op node)
                  :if (let [[c th el] children
                            else (label!) end (label!)]
                        (walk c) (emit! [:branch-false else])
                        (walk th) (emit! [:jump end])
                        (mark! else) (walk el) (mark! end))
                  :closure (let [l (label!)]
                             (vswap! queue conj [l (get by-start y)])
                             (diagnostic! {:kind :closure :params (nth (nth v pc) 2) :source pc})
                             (emit! [:closure x l]))
                  :var (do (diagnostic! {:kind :var :source pc})
                           (emit! (if (= op :load-bound) [:load-bound x y] [:load-free x])))
                  :call (do (doseq [child children] (walk child) (emit! [:push]))
                            (emit! [:call (count y) z]))
                  :ffi-call (do (doseq [child children] (walk child) (emit! [:push]))
                                (emit! [:ffi-call x (count y)]))
                  :stream-put (do (walk (nth children 0)) (emit! [:push])
                                  (walk (nth children 1)) (emit! [:stream-put]))
                  (:define :stream-cursor :stream-next :stream-close :resume)
                  (do (walk (first children))
                      (emit! (case op
                               :define [:define x]
                               :resume [:resume (nth (nth rv pc) 1)]
                               [op])))
                  (:const :gensym :store-get :stream-make) (emit! [op x])
                  :store-put (emit! [op x y])
                  (:current-continuation :park) (emit! [op]))))
            (body!
              [{:keys [expr owner]}]
              (walk expr)
              (emit! [(if (nil? owner) :halt :return)]))]
      (body! (first bodies))
      (loop [i 0]
        (when-let [[l body] (get @queue i)]
          (mark! l) (body! body) (recur (inc i))))
      {:image (mapv (fn [t]
                      (case (first t)
                        (:jump :branch-false) (assoc t 1 (get @labels (nth t 1)))
                        :closure (assoc t 2 (get @labels (nth t 2)))
                        t)) @image)
       :side-table @side})))


(defn- allocation
  [v a {:keys [start end owner]}]
  (let [locals (if (nil? owner) 0 (count (nth (nth v owner) 2)))
        defined (into #{} (keep #(code/rd (nth v %))) (range start end))
        points (reduce (fn [m pc]
                         (reduce (fn [m r] (update m r (fnil conj []) pc)) m
                                 (into (analysis/live a pc) (analysis/defs v pc))))
                       {} (range start end))
        intervals (mapv (fn [r] [r (apply min (get points r)) (apply max (get points r))])
                        (sort defined))
        starts-by-pc (group-by second intervals)
        missing (seq (remove defined (mapcat #(code/uses (nth v %)) (range start end))))]
    (loop [pc start active [] free (sorted-set) next-slot 0 slots {}]
      (if (= pc end)
        {:locals locals :registers (+ locals next-slot (if missing 1 0))
         :start start :end (dec end)
         :slots slots :structural (+ locals next-slot)}
        (let [expired (sort-by (juxt #(nth % 2) first)
                               (filter #(< (nth % 2) pc) active))
              free (into free (map #(get slots (first %))) expired)
              active (filterv #(>= (nth % 2) pc) active)
              starts (get starts-by-pc pc [])
              [free next-slot slots]
              (reduce (fn [[free next-slot slots] [r]]
                        (let [i (if (seq free) (first free) next-slot)]
                          [(disj free i) (if (seq free) next-slot (inc next-slot))
                           (assoc slots r i)]))
                      [free next-slot slots] starts)]
          (recur (inc pc) (into active starts) free next-slot slots))))))


(defn register-image
  "Allocate temporaries by linear scan over A's live points; preserve A's
   layout and fill saved windows using the register format's liveness."
  [v]
  (let [{rv :vector bodies :bodies} (resolve v)
        a (analysis/analyze v)
        allocations (mapv #(allocation v a %) bodies)
        instructions
        (mapv (fn [pc t]
                (let [{:keys [slots locals structural]}
                      (nth allocations (code/body-index bodies pc))
                      physical (fn [r] (if (contains? slots r) (+ locals (get slots r)) structural))
                      op (first t)
                      fields (get rcode/opcode-table op)]
                  (into [op]
                        (map-indexed (fn [i [k kind]]
                                       (if (= k :yin.debruijn.register/live) []
                                           (let [x (nth t (inc i))]
                                             (case kind
                                               :reg (physical x)
                                               :regs (mapv physical x)
                                               x)))) fields))))
              (range (count rv)) rv)
        image {:bodies (mapv #(select-keys % [:locals :registers :start :end]) allocations)
               :instructions instructions}]
    (reduce (fn [image bi]
              (reduce (fn [image [pc live]]
                        (assoc-in image [:instructions pc
                                         (rcode/live-slot-index (first (nth instructions pc)))] live))
                      image (rcode/body-liveness image bi)))
            image (range (count bodies)))))
