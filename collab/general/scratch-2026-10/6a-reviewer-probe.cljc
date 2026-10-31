

;; ---- REVIEWER PROBE (temporary mutant P0; restored from backup) ----
(defn- rv-err
  [f]
  (try (f) (catch Throwable e (or (ex-data e) (str (type e) " " (ex-message e))))))


(defn- rv-task-node
  [cells result]
  (node :task [3 profile :halted :root 0 (node :bindings [])
               (node :store []) [] (node :parked-table [])
               (node :cells (mapcat identity cells)) {} {} [] [] nil result nil]))


(defn- rv-cell-body
  [stream position]
  (node :cell-body [(node :stream [stream {:peer stream}]) position]))


(defn- rv-fixture
  [a b x y]
  (let [payload [:row (node :map
                            [[:lit 'both] [:row (node :set [[:cell x] [:cell y]])]
                             [:lit 'one] [:cell x]
                             [:lit 'streams] [:row (node :set [[:row (node :stream [a {:peer a}])]
                                                               [:row (node :stream [b {:peer b}])]])]
                             [:lit 'cursors] [:row (node :map [[:cell x] [:lit :x] [:cell y] [:lit :y]])]])]
        task (rv-task-node [[x (rv-cell-body a 0)] [y (rv-cell-body b 0)]] [:lit nil])]
    (node :task (assoc (:slots task) 6 (node :store ['payload payload])))))


(try
  (require 'yin.vm.semantic-register.frame-test 'yin.vm.semantic-register.corpus 'yin.vm.semantic-register.linearize)
  (let [ft (find-ns 'yin.vm.semantic-register.frame-test)
        reference-machines (ns-resolve ft 'reference-machines)
        reference-frames (ns-resolve ft 'reference-frames)
        worked-vector @(ns-resolve ft 'worked-vector)
        worked-address @(ns-resolve ft 'worked-address)
        worked-code @(ns-resolve ft 'worked-code)
        worked-frame @(ns-resolve ft 'worked-frame)
        task0 @(ns-resolve ft 'task)
        machines (reference-machines)]
    (println "RVPROBE === worked-example vector ===")
    (doseq [[i t] (map-indexed vector worked-vector)] (println "RVPROBE" i t))
    (println "RVPROBE address" worked-address)
    (println "RVPROBE === row 22 bare: frame, nil window, hand Merkle ===")
    (let [fx (first (filter #(and (= 22 (:row %)) (= :bare (:wrapper %))) machines))
          {:keys [frame code context task]} (first (reference-frames fx))
          v (get code (:yin.k/segment frame))]
      (println "RVPROBE state" (:yin.k/state frame) "pc" (:yin.k/pc frame) "deliver" (:yin.k/deliver frame) "k-count" (count (:yin.k/k frame)))
      (println "RVPROBE window keys->type" (into (sorted-map) (map (fn [[k x]] [k (if (nil? x) nil (str (type x)))])) (:yin.k/window frame)))
      (println "RVPROBE bindings keys" (sort (keys (get-in frame [:yin.k/env :yin.k/bindings]))) "store-of" (get-in frame [:yin.k/env :yin.k/store-of]))
      (println "RVPROBE inst at pc-1" (nth v (dec (:yin.k/pc frame))) "body registers" (:registers (nth (code/parse v) (code/body-index (code/body-ranges v) (:yin.k/pc frame)))))
      (println "RVPROBE saved" (yin.vm.semantic-register.analysis/saved (yin.vm.semantic-register.analysis/analyze v) (:yin.k/pc frame) (get-in frame [:yin.k/deliver :yin.k/rd])))
      (println "RVPROBE k frames" (mapv (fn [k] [(:yin.k/pc k) (:yin.k/rd k) (sort (keys (:yin.k/window k))) (sort (keys (get-in k [:yin.k/env :yin.k/bindings])))]) (:yin.k/k frame)))
      (println "RVPROBE defect" (frame/defect frame code context task))
      ;; opaque-value variant: every non-nil value -> :v, so the frame is pure literals
      (let [opaque (fn [m] (into {} (map (fn [[k x]] [k (if (nil? x) nil :v)])) m))
            f (-> frame
                  (update :yin.k/window opaque)
                  (update-in [:yin.k/env :yin.k/bindings] opaque)
                  (update :yin.k/k (fn [ks] (mapv #(-> % (update :yin.k/window opaque) (update-in [:yin.k/env :yin.k/bindings] opaque)) ks)))
                  (assoc-in [:yin.k/pending :yin.k/cell] :test/cursor))
            wire (frame->rows f)
            index (into {} (map (fn [r] [(first r) r])) (:yin.k/rows wire))
            sym-sort (fn [m] (sort (fn [a b] (byte-compare (first a) (first b))) m))
            hand-bindings (fn [b] (jing/segment-key (into [:bindings] (mapcat (fn [[s x]] [s [:lit x]]) (sym-sort b)))))
            hand-env (fn [e] (jing/segment-key [:env (hand-bindings (:yin.k/bindings e)) (:yin.k/store-of e)]))
            hand-window (fn [w] (jing/segment-key (into [:window] (mapcat (fn [[r x]] [r [:lit x]]) (sort-by first w)))))
            hand-kframe (fn [k] (jing/segment-key [:kframe (:yin.k/segment k) (:yin.k/pc k) (hand-env (:yin.k/env k)) (hand-window (:yin.k/window k)) (:yin.k/rd k)]))
            hand-k (jing/segment-key (into [:k] (map hand-kframe (:yin.k/k f))))
            hand-act (jing/segment-key [:act (:yin.k/segment f) (:yin.k/pc f) (hand-env (:yin.k/env f)) (hand-window (:yin.k/window f)) hand-k (get-in f [:yin.k/deliver :yin.k/rd])])
            hand-pending (jing/segment-key [:pending :next :test/cursor])
            hand-root (jing/segment-key [:wait hand-act hand-pending])
            hand-window-body (into [:window] (mapcat (fn [[r x]] [r [:lit x]]) (sort-by first (:yin.k/window f))))]
        (println "RVPROBE opaque frame roundtrip equal?" (= f (rows->frame wire)))
        (println "RVPROBE hand window body" hand-window-body)
        (println "RVPROBE hand window id in wire?" (contains? index (hand-window (:yin.k/window f))) (hand-window (:yin.k/window f)))
        (println "RVPROBE wire window rows" (filter #(= :window (second %)) (:yin.k/rows wire)))
        (println "RVPROBE hand act id in wire?" (contains? index hand-act))
        (println "RVPROBE hand root" hand-root)
        (println "RVPROBE wire root" (:yin.k/root wire) "equal?" (= hand-root (:yin.k/root wire)))
        (println "RVPROBE row count" (count (:yin.k/rows wire)) "kinds" (frequencies (map second (:yin.k/rows wire))))))
    (println "RVPROBE === row 21 bare: E and lexical chain ===")
    (let [fx (first (filter #(and (= 21 (:row %)) (= :bare (:wrapper %))) machines))
          {:keys [frame code context task]} (first (reference-frames fx))
          v (get code (:yin.k/segment frame))]
      (println "RVPROBE bindings" (into (sorted-map-by #(compare (str %1) (str %2))) (map (fn [[k x]] [k (if (coll? x) (str (type x)) x)])) (get-in frame [:yin.k/env :yin.k/bindings])))
      (println "RVPROBE free-env" (:free-env task))
      (println "RVPROBE pc" (:yin.k/pc frame) "inst pc-1" (nth v (dec (:yin.k/pc frame))))
      (println "RVPROBE lexical-names (impl)" (#'frame/lexical-names v (:yin.k/pc frame)))
      (println "RVPROBE body-ranges" (code/body-ranges v))
      (doseq [[i t] (map-indexed vector v)] (println "RVPROBE" i t)))
    (println "RVPROBE === probes ===")
    (println "RVPROBE pc 0 act:" (rv-err #(frame/defect (assoc worked-frame :yin.k/pc 0) worked-code :wait task0)))
    (println "RVPROBE k nil:" (rv-err #(frame/defect (assoc worked-frame :yin.k/k nil) worked-code :wait task0)))
    (println "RVPROBE k return pc 0:" (rv-err #(frame/defect (assoc worked-frame :yin.k/k [{:yin.k/frame-type :return :yin.k/segment worked-address :yin.k/pc 0 :yin.k/env {:yin.k/bindings {}} :yin.k/window {} :yin.k/rd 2}]) worked-code :wait task0)))
    (println "RVPROBE window {3 :x} extra+missing:" (rv-err #(frame/defect (assoc worked-frame :yin.k/window {3 :x}) worked-code :wait task0)))
    (println "RVPROBE pending not a map:" (rv-err #(frame/defect (assoc worked-frame :yin.k/pending 5) worked-code :wait task0)))
    (println "RVPROBE segment not a string:" (rv-err #(frame/defect (assoc worked-frame :yin.k/segment 7) worked-code :wait task0)))
    (println "RVPROBE deliver rd nil (park-like):" (rv-err #(frame/defect (assoc worked-frame :yin.k/deliver {:yin.k/deliver :rd}) worked-code :wait task0)))
    (println "RVPROBE store-of nil explicit roundtrip:" (rv-err #(let [f (assoc-in worked-frame [:yin.k/env :yin.k/store-of] nil)] (= f (rows->frame (frame->rows f))))))
    (println "RVPROBE window value {:yin.k/row 1} literal roundtrip:" (rv-err #(let [f (assoc worked-frame :yin.k/window {1 {:yin.k/row 1}})] (= f (rows->frame (frame->rows f))))))
    (let [wire (emit (rv-task-node [] [:lit nil]))
          troot (:yin.k/root wire)
          index (into {} (map (fn [r] [(first r) r])) (:yin.k/rows wire))
          trow (get index troot)
          rehash (fn [w id body]
                   (let [idx (into {} (map (fn [r] [(first r) r])) (:yin.k/rows w)) memo (atom {})]
                     (letfn [(rw
                               [x]
                               (cond (contains? idx x) (or (get @memo x) (let [rb (if (= x id) body (subvec (get idx x) 1)) b (mapv rw rb) nid (jing/segment-key b)] (swap! memo assoc x nid) nid))
                                     (vector? x) (mapv rw x) :else x))]
                       (let [root (rw (:yin.k/root w)) rs (mapv (fn [[old new]] (into [new] (mapv rw (if (= old id) body (subvec (get idx old) 1))))) @memo)]
                         (assoc w :yin.k/root root :yin.k/rows rs)))))]
      (println "RVPROBE extra wire key:" (rv-err #(decode (assoc wire :extra 1))))
      (println "RVPROBE installs slot = 5:" (rv-err #(decode (rehash wire troot (assoc (subvec trow 1) 13 5)))))
      (println "RVPROBE frames slot = 5:" (rv-err #(decode (rehash wire troot (assoc (subvec trow 1) 14 5)))))
      (println "RVPROBE root not in rows:" (rv-err #(decode (assoc wire :yin.k/root "nope"))))
      (println "RVPROBE version float64 3:" (rv-err #(decode (assoc wire :yin.k/version (cbor/float64 3)))))
      (println "RVPROBE rows not vector:" (rv-err #(decode (assoc wire :yin.k/rows (seq (:yin.k/rows wire)))))))
    (let [wire (emit (rv-fixture :s1 :s2 :x :y))
          cells-row (first (filter #(= :cells (second %)) (:yin.k/rows wire)))
          idx (into {} (map (fn [r] [(first r) r])) (:yin.k/rows wire))
          memo (atom {})
          rw (fn rw
               [x id body]
               (cond (contains? idx x) (or (get @memo x) (let [rb (if (= x id) body (subvec (get idx x) 1)) b (mapv #(rw % id body) rb) nid (jing/segment-key b)] (swap! memo assoc x nid) nid))
                     (vector? x) (mapv #(rw % id body) x) :else x))]
      (println "RVPROBE cells row" cells-row)
      (let [body (vec (concat [:cells :x] (drop 3 cells-row)))
            root (rw (:yin.k/root wire) (first cells-row) body)
            rs (mapv (fn [[old new]] (into [new] (mapv #(rw % (first cells-row) body) (if (= old (first cells-row)) body (subvec (get idx old) 1))))) @memo)]
        (println "RVPROBE cells row with non c-N key :x:" (rv-err #(decode (assoc wire :yin.k/root root :yin.k/rows rs))))))
    (println "RVPROBE wrong-numbering emit-numbered:" (rv-err #(emit-numbered (rv-fixture :s1 :s2 :x :y) {:x :yin.k/c-1})))
    (println "RVPROBE canonical numbering fixture:" (canonical-numbering (rv-fixture :s1 :s2 :x :y)))
    (println "RVPROBE canonical numbering fixture swapped names:" (canonical-numbering (rv-fixture :s1 :s2 :y :x)))
    (println "RVPROBE root fixture :s1 :s2 :x :y =" (:yin.k/root (emit (rv-fixture :s1 :s2 :x :y))))
    (println "RVPROBE root fixture :s1 :s2 :y :x =" (:yin.k/root (emit (rv-fixture :s1 :s2 :y :x))))
    (println "RVPROBE root worked-frame frame->rows =" (:yin.k/root (frame->rows worked-frame)))
    (println "RVPROBE ten cells numbering order:" (let [t (rv-task-node (mapv (fn [i] [(keyword "c" (str i)) (rv-cell-body :s i)]) (range 10)) [:row (node :vec (mapv (fn [i] [:cell (keyword "c" (str i))]) (range 10)))])
                                                        w (emit t)]
                                                    [(rv-err #(decode w)) (first (filter #(= :cells (second %)) (:yin.k/rows w)))])))
  (catch Throwable e (println "RVPROBE ERROR" (str e)) (.printStackTrace e)))


;; ---- END REVIEWER PROBE ----
