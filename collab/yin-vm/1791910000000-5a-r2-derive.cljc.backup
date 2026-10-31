(ns yin.vm.semantic-register.derive
  "Pure native derivations from the admitted semantic register vector A."
  (:refer-clojure :exclude [resolve])
  (:require [yin.vm.semantic-register.code :as code]
            [yin.vm.debruijn :as db]))


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


(defn resolved-addresses
  "Read lexical references from the old resolver's occurrence tuples.
   Walk main then queued lambda bodies; definitions visit only their value.
   This diagnostic reader never lowers or assigns an identity."
  [{:keys [tuples]}]
  (let [attrs (reduce (fn [m [e a v]] (assoc-in m [e a] v)) {} tuples)
        root (first (keep (fn [[e m]] (when (:yin/root m) e)) attrs))
        queue (volatile! [])
        out (volatile! [])]
    (letfn [(walk
              [e]
              (let [m (get attrs e)
                    child (fn [k] (walk (get m k)))]
                (case (:yin/type m)
                  :variable (vswap! out conj
                                    (if (contains? m :yin.resolved/free)
                                      [:free (:yin.resolved/free m)]
                                      [:bound (:yin.resolved/depth m)
                                       (:yin.resolved/position m)]))
                  :lambda (vswap! queue conj (:yin/body m))
                  :application
                  (let [op (get attrs (:yin/operator m))
                        args (:yin/operands m)]
                    (if (= 'yin/def (:yin.resolved/free op))
                      (walk (nth args 1))
                      (do (child :yin/operator) (doseq [a args] (walk a)))))
                  :if (doseq [k [:yin/test :yin/consequent :yin/alternate]] (child k))
                  :stream/put (do (child :yin/target) (child :yin/val-node))
                  (:stream/cursor :stream/next :stream/close) (child :yin/source)
                  :dao.stream.apply/call (doseq [a (:yin/operands m)] (walk a))
                  :vm/resume (child :yin/val-node)
                  nil)))]
      (walk root)
      (loop [i 0]
        (when-let [e (get @queue i)] (walk e) (recur (inc i))))
      @out)))


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


(defn register-image
  "Reserved for slice 5b."
  [_v]
  (throw (ex-info "Register derivation is not in slice 5a" {:reason :not-in-slice-5a})))
