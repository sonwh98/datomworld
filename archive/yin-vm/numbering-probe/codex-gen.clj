;; Codex's independent 5,000-state generator (r3 findings, "Reproducible
;; independent generator"), rerun against the base prototype. Everything
;; through `gen` is codex's text verbatim (seed 179221, two independently
;; renamed and shuffled copies per state); the D5/D6 comparison lines are
;; removed, as codex did in r4 (they consumed no randomness, so the
;; generator/copy sequence is the same). Added after the loop: the base
;; probe's own refusal classification (exhaustive permissive entry when at
;; most 720 listed orders, else 48 samples) over the refused states, with a
;; separate RNG, so it does not perturb the sequence either.
;;
;;   mise exec -- java -Xmx2g @java.args clojure.main codex-gen.clj
(require '[probe.discovery :as p] '[yin.vm.ucf.rows :as r] '[yin.vm.ucf.rows-test :as t])
(def rng (java.util.Random. 179221))


(defn sh
  [xs]
  (let [a (java.util.ArrayList. (vec xs))] (java.util.Collections/shuffle a rng) (vec a)))


(defn c
  [x]
  [:cursor-cell x])


(defn v
  [xs]
  [:row (r/node :vec (vec xs))])


(defn dv
  [x]
  (case (first x) :row (let [n (second x) ss (:slots n)] (case (:kind n) :vec (mapv dv ss) :set (set (map dv ss)) :map (into {} (map (fn [[a b]] [(dv a) (dv b)]) (partition 2 ss))))) x))


(defn s
  [xs]
  [:row (r/node :set (second (reduce (fn [[seen out] x] (let [k (dv x)] (if (contains? seen k) [seen out] [(conj seen k) (conj out x)]))) [#{} []] xs)))])


(defn m
  [ps]
  [:row (r/node :map (vec (mapcat identity ps)))])


(def b (t/cursor-cell-body :s 0))


(defn task
  [value]
  (t/task-node (mapv #(vector % b) (sort (p/reached value))) value))


(defn rename2
  [n bij]
  (letfn [(val2 [x] (case (first x) :cursor-cell (c (bij (second x))) :row [:row (go (second x))] x))
          (go
            [n]
            (let [ss (:slots n) k (:kind n)]
              (r/node k (case k
                          :set (sh (mapv val2 ss))
                          :map (vec (mapcat identity (sh (mapv (fn [[a z]] [(val2 a) (val2 z)]) (partition 2 ss)))))
                          :cursor-cells (vec (mapcat identity (sh (mapv (fn [[a z]] [(bij a) (go z)]) (partition 2 ss)))))
                          (mapv (fn [d x] (cond (p/ref? d) (go x) (p/refs? d) (mapv go x) (= :C d) (bij x) (p/v? d) (when x (val2 x)) :else x)) (p/descriptors k ss) ss)))))] (go n)))


(defn gen
  [i]
  (let [n (+ 2 (.nextInt rng 7)) cs (vec (range n)) cc #(c (nth cs (.nextInt rng n)))
        edge #(v [(cc) (cc)]) es (vec (repeatedly (+ 2 (.nextInt rng 9)) edge))
        group #(s (repeatedly (+ 2 (.nextInt rng 4)) cc))
        value (case (mod i 10)
                0 (s es)
                1 (v [(s es) (s (sh es))])
                2 (s (repeatedly 4 group))
                3 (v [(s es) (cc)])
                4 (s (map #(v [% (group)]) es))
                5 (m (mapv (fn [x] [(c x) (cc)]) cs))
                6 (v [(s es) (s (map #(v (reverse (:slots (second %)))) es))])
                7 (s (map #(m [[(cc) %] [[:lit :anchor] (group)]]) es))
                8 (s [(v [(s es) (group)]) (v [(group) (s (sh es))])])
                9 (v [(s es) (s (repeatedly 4 group)) (cc)]))
        root (task value)]
    (if (zero? (mod i 20)) (p/with-child (t/task-node [] [:lit nil]) root) root)))


(def stats (atom {}))
(def refused (atom []))


;; a refusal is compared by verdict and row path, never by the local source ids it names
(defn norm
  [x]
  (if (map? x) (if-let [d (:refused x)] [:refused (:yin.k/kind d) (:yin.k/path d)] x) x))


(doseq [i (range 5000)]
  (let [q (gen i) ids (p/all-census-cells q)
        copies (vec (repeatedly 2 #(rename2 q (zipmap ids (sh (mapv (fn [j] (keyword "fresh" (str j))) (range (count ids))))))))
        outcomes (mapv p/do-root (cons q copies))
        o (first outcomes)]
    (when (zero? (mod i 20)) (swap! stats update :install-child-states (fnil inc 0)))
    (swap! stats update (cond (keyword? o) :numbered (:refused o) :refused :else :resource-limit) (fnil inc 0))
    (when (:refused o) (swap! refused conj [i q]))
    (when-not (apply = (map norm outcomes))
      (println "MISMATCH" i (pr-str q) outcomes)
      (swap! stats update :mismatch (fnil inc 0)))
    (when (and (< i 100) (keyword? o))
      (let [e (p/error #(p/decode* (p/emit* q)))]
        (swap! stats update [:decode-first-100 (if (nil? e) :ok (:yin.k/kind e))] (fnil inc 0)))))
  (when (zero? (mod (inc i) 1000)) (println "PROGRESS" (inc i) @stats)))


(println "COUNTS" @stats)
(println "classifying" (count @refused) "refused states (exhaustive when <= 720 listed orders, else 48 samples)")
(def classes (atom {}))


(doseq [[i q] @refused]
  (swap! classes update (p/classify-refused q 720 48) (fnil inc 0)))


(println "REFUSED-CLASSES" @classes)
