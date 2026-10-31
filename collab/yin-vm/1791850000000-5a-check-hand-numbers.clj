(require '[yin.vm.semantic-register.derive-test :as t]
         '[yin.vm.semantic-register.code :as c]
         '[yin.vm.semantic-register.analysis :as a]
         '[yin.vm.semantic-register.derive :as d]
         '[yin.vm.debruijn-register-code :as r])


(def pinned
  '[[[:load-free 0 +] [:const 1 1] [:const 2 2] [:call 3 0 [1 2] false []] [:halt 3]]
    [[:load-free 0 f] [:load-free 1 g] [:load-free 2 x] [:call 3 1 [2] false [0]] [:load-free 1 y] [:call 2 0 [3 1] false []] [:halt 2]]
    [[:load-free 0 f] [:call 1 0 [] false []] [:call 0 1 [] false []] [:halt 0]]
    [[:load-free 0 c] [:branch-false 0 4] [:const 0 1] [:jump 5] [:const 0 2] [:halt 0]]
    [[:load-free 0 f] [:load-free 1 c] [:branch-false 1 5] [:const 1 1] [:jump 6] [:const 1 2] [:load-free 2 y] [:call 3 0 [1 2] false []] [:halt 3]]
    [[:closure 0 1 4] [:const 1 10] [:call 2 0 [1] false []] [:halt 2] [:load-free 1 +] [:load-bound 2 0 0] [:const 3 1] [:call 4 1 [2 3] true []] [:return 4]]
    [[:const 0 5] [:define 1 x 0] [:halt 1]]
    [[:load-free 0 +] [:const 1 1] [:const 2 2] [:call 3 0 [1 2] false []] [:define 0 y 3] [:halt 0]]
    [[:load-free 0 f] [:load-free 1 x] [:resume :p 1] [:load-free 1 y] [:call 2 0 [3 1] false []] [:halt 2]]
    [[:load-free 0 f] [:load-free 1 g] [:load-free 2 x] [:call 3 1 [2] true []] [:load-free 1 y] [:call 2 0 [3 1] false []] [:halt 2]]])


(doseq [[row ast expected] (map vector (range 1 11) t/golden-asts pinned)]
  (let [v (t/projected ast) live (:live (a/analyze v)) rv (:vector (d/resolve v))
        bodies (c/parse v)]
    (doseq [{:keys [start end owner]} bodies]
      (let [locals (if owner (count (nth (nth v owner) 2)) 0)
            defs (reduce (fn [m pc]
                           (if-let [id (c/rd (nth v pc))]
                             (update m id (fnil conj []) pc) m)) {} (range start end))
            intervals (into {} (map (fn [[id ps]]
                                      (let [points (concat ps (filter #(contains? (nth live %) id) (range start end)))]
                                        [id [(apply min points) (apply max points)]]))) defs)
            state (reduce (fn [{:keys [slots active next free] :as st} pc]
                            (let [expired (sort-by (fn [id] [(second (get intervals id)) id])
                                                   (filter #(< (second (get intervals %)) pc) active))
                                  free (into free (map slots expired))
                                  active (reduce disj active expired)]
                              (reduce (fn [st id]
                                        (let [slot (if (seq (:free st)) (first (:free st)) (:next st))]
                                          (-> st (assoc-in [:slots id] slot)
                                              (update :active conj id)
                                              (update :free disj slot)
                                              (update :next #(if (= slot %) (inc %) %)))))
                                      (assoc st :free free :active active)
                                      (sort (keep (fn [[id [s _]]] (when (= s pc) id)) intervals)))))
                          {:slots {} :active #{} :free (sorted-set) :next 0} (range start end))
            physical (fn [id] (+ locals (get (:slots state) id (:next state))))]
        (prn {:row row :body [start end] :locals locals :intervals intervals
              :slots (:slots state) :peak (:next state)})
        (doseq [pc (range start end)]
          (let [x (nth rv pc) y (nth expected pc)]
            (assert (= (first x) (first y)))
            (doseq [[i [_ kind]] (map-indexed vector (get c/operand-table (first (nth v pc))))]
              (when (contains? #{:reg :regs} kind)
                (assert (= (if (= kind :regs) (mapv physical (nth x (inc i))) (physical (nth x (inc i))))
                           (nth y (inc i))))))))))
    (let [image {:instructions expected
                 :bodies (mapv (fn [{:keys [start end owner]}]
                                 {:start start :end (dec end)
                                  :locals (if owner (count (nth (nth v owner) 2)) 0)
                                  :registers (inc (apply max 0 (mapcat (fn [pc]
                                                                         (let [t (nth expected pc)] (mapcat (fn [[i [_ kind]]] (case kind :reg [(nth t (inc i))] :regs (nth t (inc i)) [])) (map-indexed vector (get r/opcode-table (first t)))))) (range start end))))}) bodies)}]
      (assert (nil? (r/register-image-defect image)))
      (prn {:row row :register-validator :pass}))))
