;; Rename-equivalence probe: old codec (backup, loaded as yin.vm.ucf.rows-old)
;; versus renamed codec over the same fixtures in each spelling.
(require '[dao.jing :as jing] '[yin.vm.ucf.rows :as rows]
         '[yin.vm.ucf.rows-test :as rt] '[yin.vm.semantic-register.frame-test :as ft])


(let [src (clojure.string/replace (slurp "collab/1792090000000-backup-rows.cljc.orig")
                                  "(ns yin.vm.ucf.rows\n" "(ns yin.vm.ucf.rows-old\n")
      rdr (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. src))]
  (binding [*ns* *ns*]
    (loop []
      (let [form (read {:read-cond :allow :features #{:clj} :eof ::eof} rdr)]
        (when-not (= ::eof form) (eval form) (recur))))))


(def old-emit (resolve 'yin.vm.ucf.rows-old/emit))
(def old-numbering (resolve 'yin.vm.ucf.rows-old/canonical-numbering))
(def old-decode (resolve 'yin.vm.ucf.rows-old/decode))
(def old-node (resolve 'yin.vm.ucf.rows-old/node))
(def old-ordered (var-get (ns-resolve 'yin.vm.ucf.rows-old 'ordered-slots)))


(defn cur->c
  [k]
  (if-let [[_ n] (and (keyword? k) (= "yin.k" (namespace k)) (re-matches #"cur-(.*)" (name k)))]
    (keyword "yin.k" (str "c-" n)) k))


(defn to-old-abstract
  "New-spelled abstract row tree -> old spelling (built with the old node fn)."
  [x]
  (cond
    (and (map? x) (contains? x :kind))
    (old-node (get {:cursors :cells :cursor-body :cell-body} (:kind x) (:kind x))
              (mapv to-old-abstract (:slots x)))
    (and (vector? x) (= 2 (count x)) (= :cursor (first x))) [:cell (cur->c (second x))]
    (and (vector? x) (= 2 (count x)) (= :row (first x))) [:row (to-old-abstract (second x))]
    (vector? x) (mapv to-old-abstract x)
    :else (cur->c x)))


(defn respell-wire
  "Respell a new wire to the old tags/ids and rehash every row bottom-up."
  [wire]
  (let [index (into {} (map (fn [r] [(first r) r])) (:yin.k/rows wire)) memo (atom {})]
    (letfn [(rw
              [x]
              (cond
                (contains? index x) (or (first (get @memo x))
                                        (let [[k & ss] (mapv rw (subvec (get index x) 1))
                                              ;; Re-sort under the old ordering rules: unordered
                                              ;; containers order by child ids, which the respell changed.
                                              b (into [k] (old-ordered k (vec ss)))
                                              id (jing/segment-key b)]
                                          (swap! memo assoc x [id (into [id] b)]) id))
                (= x :cursors) :cells
                (= x :cursor-body) :cell-body
                (and (vector? x) (= 2 (count x)) (= :cursor (first x))) [:cell (cur->c (second x))]
                (vector? x) (mapv rw x)
                (map? x) (into {} (map (fn [[k v]] [(rw k) (rw v)])) x)
                :else (cur->c x)))]
      (let [root (rw (:yin.k/root wire))]
        {:root root :rows (set (map second (vals @memo)))}))))


(def loop-task
  (let [shape (fn [[l a b]]
                [[:row (rows/node :vec [[:cursor l] [:cursor l]])]
                 [:row (rows/node :vec [[:cursor a] [:cursor b]])]
                 [:row (rows/node :vec [[:cursor b] [:cursor a]])]])]
    (rt/task-node (mapv #(vector % (rt/cursor-body :s 0)) [:l :a :b])
                  [:row (rows/node :set (shape [:l :a :b]))])))


(def ten-task
  (let [ids (mapv #(keyword "src" (str "x" %)) (range 10))]
    (rt/task-node (mapv (fn [id i] [id (rt/cursor-body :s i)]) ids (range))
                  [:row (rows/node :vec (mapv #(vector :cursor %) ids))])))


(def child-task
  (let [child (rows/node :task (assoc (:slots (rt/task-node [[:x (rt/cursor-body :child/s 2)]] [:cursor :x])) 3 :child))
        root (rt/task-node [[:x (rt/cursor-body :root/s 1)]] [:cursor :x])]
    (rows/node :task (assoc (:slots root) 12 [(rows/node :install ['a :running nil {} child])
                                              (rows/node :install ['b :running nil {} child])]))))


(def fixtures
  {:fixture-s1-s2-x-y (rt/fixture :s1 :s2 :x :y)
   :fixture-lane-a (rt/fixture :lane-a/s1 :lane-a/s2 :source/x :source/y)
   :fixture-reversed (rt/fixture :lane-b/s2 :lane-b/s1 :other/y :other/x)
   :loop-plus-2-cycle loop-task
   :ten-cursors ten-task
   :child-scope child-task})


(doseq [[label task] fixtures]
  (let [new-wire (rows/emit task)
        old-task (to-old-abstract task)
        old-wire (old-emit old-task)
        respelled (respell-wire new-wire)
        new-num (rows/canonical-numbering task)
        old-num (old-numbering old-task)]
    (println label
             "\n  new root" (:yin.k/root new-wire)
             "\n  old root" (:yin.k/root old-wire)
             "\n  numbering identical (cur-N<->c-N):" (= old-num (update-vals new-num cur->c))
             "numbering" (into (sorted-map) new-num)
             "\n  respelled new wire root = old root:" (= (:root respelled) (:yin.k/root old-wire))
             "rows equal:" (= (:rows respelled) (set (:yin.k/rows old-wire)))
             "\n  old decode of old wire ok:" (some? (old-decode old-wire))
             "new decode of new wire ok:" (some? (rows/decode new-wire)))))


(let [nw (rows/frame->rows ft/worked-frame)]
  (println :worked-frame "root" (:yin.k/root nw)
           "respelled = itself:" (= (:root (respell-wire nw)) (:yin.k/root nw))))
