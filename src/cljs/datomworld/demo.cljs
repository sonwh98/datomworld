(ns datomworld.demo
  (:require
    ["@codemirror/lang-php" :refer [php]]
    ["@codemirror/lang-python" :refer [python]]
    ["@codemirror/state" :refer
     [EditorState StateField StateEffect RangeSet]]
    ["@codemirror/theme-one-dark" :refer [oneDark]]
    ["@codemirror/view" :refer [EditorView Decoration]]
    ["@nextjournal/lang-clojure" :refer [clojure]]
    ["codemirror" :refer [basicSetup]]
    [cljs.pprint :as pprint]
    [cljs.reader :as reader]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [datascript.core :as d]
    [datascript.db :as db]
    [datascript.query :as dq]
    [reagent.core :as r]
    [reagent.dom :as rdom]
    [yang.clojure :as yang]
    [yang.php :as php-comp]
    [yang.python :as py]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as walker]
    [yin.vm.register :as register]
    [yin.vm.semantic :as semantic]
    [yin.vm.stack :as stack]))


;; Fix DataScript query under Closure advanced compilation.
(let [original-lookup dq/lookup-pattern-db
      prop->idx {"e" 0, "a" 1, "v" 2, "tx" 3}]
  (set! dq/lookup-pattern-db
        (fn [context db pattern]
          (let [rel (original-lookup context db pattern)
                tuples (:tuples rel)]
            (if (and (seq tuples) (instance? db/Datom (first tuples)))
              (let [new-attrs (reduce-kv (fn [m k v]
                                           (assoc m k (get prop->idx v v)))
                                         {}
                                         (:attrs rel))
                    new-tuples (mapv (fn [d]
                                       (to-array [(nth d 0) (nth d 1) (nth d 2)
                                                  (nth d 3) (nth d 4)]))
                                     tuples)]
                (dq/->Relation new-attrs new-tuples))
              rel)))))


(defn pretty-print
  [data]
  (binding [pprint/*print-right-margin* 60]
    (with-out-str (pprint/pprint data))))


(defn add-yin-ids
  "Recursively add unique :yin/id to every AST node."
  [ast]
  (let [id (atom 0)]
    (walk/postwalk
      (fn [x] (if (and (map? x) (:type x)) (assoc x :yin/id (swap! id inc)) x))
      ast)))


(defn ast->text-with-map
  "Generate pretty-printed text for AST and a source map of node IDs to ranges."
  [ast]
  (let [source-map (atom {})
        out (atom "")]
    (letfn
      [(emit [s] (swap! out str s)) (pos [] (count @out))
       (indent-str [n] (apply str (repeat (* n 2) " ")))
       (print-node
         [node indent]
         (let [start (pos)
               id (:yin/id node)]
           (emit "{")
           (let [ks (keys (dissoc node :yin/id))
                 sorted-ks (cons :type (sort (remove #(= :type %) ks)))]
             (doseq [[i k] (map-indexed vector sorted-ks)]
               (if (> i 0)
                 (do (emit "\n") (emit (indent-str (inc indent))))
                 (emit " "))
               (emit (str k " "))
               (let [v (get node k)]
                 (cond (and (map? v) (:type v)) (print-node v (inc indent))
                       (vector? v) (do
                                     (emit "[")
                                     (let [long-vec? (> (count v) 1)]
                                       (doseq [[j item] (map-indexed vector v)]
                                         (if (and long-vec? (> j 0))
                                           (do (emit "\n")
                                               (emit (indent-str (+ indent 2))))
                                           (when long-vec? (emit " ")))
                                         (if (and (map? item) (:type item))
                                           (print-node item (+ indent 2))
                                           (emit (pr-str item)))))
                                     (if (> (count v) 1)
                                       (do (emit "\n")
                                           (emit (indent-str (inc indent)))
                                           (emit "]"))
                                       (emit " ]")))
                       :else (emit (pr-str v))))))
           (emit " }")
           (when id (swap! source-map assoc id [start (pos)]))))]
      (print-node ast 0) {:text @out, :source-map @source-map})))


(defn ast->pretty-text
  [ast]
  (pretty-print ast))


(def highlight-decoration (.mark Decoration #js {:class "cm-highlight-node"}))


(def set-highlight (.define StateEffect))


(def highlight-field
  (.define StateField
           #js {:create (fn [] (.-none Decoration)),
                :update (fn [decorations tr]
                          (let [effects (.-effects tr)]
                            (reduce (fn [decs effect]
                                      (if (.is effect set-highlight)
                                        (let [v (.-value effect)]
                                          (if (and (vector? v) (= 2 (count v)))
                                            (let [[start end] v]
                                              (try (.of RangeSet
                                                        #js
                                                        [(.range
                                                           highlight-decoration
                                                           start
                                                           end)])
                                                   (catch js/Error _ decs)))
                                            (.-none Decoration)))
                                        decs))
                                    decorations
                                    effects))),
                :provide (fn [f] (.from (.-decorations EditorView) f))}))


(def card-order [:source :walker :semantic :register :stack :query])


(def layout-style-options
  [{:id :circuit, :label "Circuit Board"} {:id :circular, :label "Circular"}])


(def layout-pairs
  (vec (for [i (range (count card-order))
             j (range (inc i) (count card-order))]
         [(nth card-order i) (nth card-order j)])))


(defn- clamp
  [n low high]
  (if (< high low)
    low
    (-> n
        (max low)
        (min high))))


(defn- rounded
  [n]
  (js/Math.round n))


(defn- compact-layout
  [width height]
  (let [padding-x 16
        top 80
        gap 20
        card-w (-> (- width (* 2 padding-x))
                   (clamp 280 720)
                   rounded)
        can-two-cols? (>= width (+ (* 2 card-w) (* 3 padding-x)))
        x-single (rounded (clamp (/ (- width card-w) 2)
                                 padding-x
                                 (- width card-w padding-x)))
        x-left (if can-two-cols? padding-x x-single)
        x-right (if can-two-cols? (- width card-w padding-x) x-single)
        row-h (-> (/ (- height 180) 3)
                  (clamp 230 340)
                  rounded)
        heights {:source (+ row-h 20),
                 :walker (+ row-h 20),
                 :semantic row-h,
                 :register row-h,
                 :stack row-h,
                 :query row-h}
        x-by-id {:source x-left,
                 :walker x-right,
                 :semantic x-left,
                 :register x-right,
                 :stack x-left,
                 :query x-right}]
    (loop [ids card-order
           y top
           acc {}]
      (if-let [id (first ids)]
        (let [h (get heights id row-h)]
          (recur (rest ids)
                 (+ y h gap)
                 (assoc acc
                        id {:x (get x-by-id id x-left), :y y, :w card-w, :h h})))
        acc))))


(defn- card-size-profile
  [width height]
  (let [main-w (-> (/ width 4.4)
                   (clamp 280 390)
                   rounded)
        side-w (-> (/ width 5.2)
                   (clamp 250 340)
                   rounded)
        main-h (-> (/ height 2.65)
                   (clamp 290 430)
                   rounded)
        side-h (-> (/ height 3.25)
                   (clamp 230 330)
                   rounded)]
    {:source {:w main-w, :h main-h},
     :walker {:w main-w, :h main-h},
     :semantic {:w main-w, :h main-h},
     :register {:w side-w, :h side-h},
     :stack {:w side-w, :h side-h},
     :query {:w side-w, :h side-h}}))


(defn- style-anchors
  [layout-style]
  (case layout-style
    :circular {:source [0.23 0.48],
               :walker [0.5 0.16],
               :semantic [0.56 0.5],
               :register [0.82 0.28],
               :stack [0.78 0.72],
               :query [0.36 0.82]}
    ;; Default to a circuit-board flow with explicit wire channels.
    {:source [0.14 0.31],
     :walker [0.57 0.16],
     :semantic [0.5 0.57],
     :register [0.87 0.26],
     :stack [0.85 0.74],
     :query [0.19 0.79]}))


(defn- overlap-deltas
  [a b gap]
  (let [ax (+ (:x a) (/ (:w a) 2))
        ay (+ (:y a) (/ (:h a) 2))
        bx (+ (:x b) (/ (:w b) 2))
        by (+ (:y b) (/ (:h b) 2))
        dx (- ax bx)
        dy (- ay by)
        req-x (+ (/ (+ (:w a) (:w b)) 2) gap)
        req-y (+ (/ (+ (:h a) (:h b)) 2) gap)
        overlap-x (- req-x (js/Math.abs dx))
        overlap-y (- req-y (js/Math.abs dy))]
    (when (and (> overlap-x 0) (> overlap-y 0))
      {:dx dx, :dy dy, :overlap-x overlap-x, :overlap-y overlap-y})))


(defn- normalize-layout-bounds
  [positions]
  (reduce-kv (fn [acc id p]
               (assoc acc
                      id (-> p
                             (update :x #(rounded (max 24 %)))
                             (update :y #(rounded (max 84 %))))))
             {}
             positions))


(defn- separate-overlap
  [positions [id-a id-b] gap]
  (let [a (get positions id-a)
        b (get positions id-b)]
    (if-let [{:keys [dx dy overlap-x overlap-y]}
             (and a b (overlap-deltas a b gap))]
      (let [move-x? (< overlap-x overlap-y)
            idx-a (.indexOf card-order id-a)
            idx-b (.indexOf card-order id-b)
            dir-x (cond (> dx 0) 1
                        (< dx 0) -1
                        (< idx-a idx-b) -1
                        :else 1)
            dir-y (cond (> dy 0) 1
                        (< dy 0) -1
                        (< idx-a idx-b) -1
                        :else 1)
            shift (/ (+ (if move-x? overlap-x overlap-y) 2) 2)
            [a* b*] (if move-x?
                      [(update a :x + (* dir-x shift))
                       (update b :x - (* dir-x shift))]
                      [(update a :y + (* dir-y shift))
                       (update b :y - (* dir-y shift))])]
        (assoc positions
               id-a a*
               id-b b*))
      positions)))


(defn- resolve-overlaps
  [positions gap]
  (loop [i 0
         pos (normalize-layout-bounds positions)]
    (if (>= i 120)
      (normalize-layout-bounds pos)
      (let [next-pos (normalize-layout-bounds
                       (reduce (fn [acc pair] (separate-overlap acc pair gap))
                               pos
                               layout-pairs))]
        (if (= next-pos pos) next-pos (recur (inc i) next-pos))))))


(defn responsive-layout
  [layout-style width height]
  (let [seed-positions (if (< width 980)
                         (compact-layout width height)
                         (let [anchors (style-anchors layout-style)
                               sizes (card-size-profile width height)
                               padding-x 24
                               padding-top 84
                               padding-bottom 24]
                           (reduce-kv
                             (fn [acc id [ax ay]]
                               (let [{:keys [w h]} (get sizes id)
                                     x (rounded (clamp (- (* width ax) (/ w 2))
                                                       padding-x
                                                       (- width w padding-x)))
                                     y (rounded (clamp
                                                  (- (* height ay) (/ h 2))
                                                  padding-top
                                                  (- height h padding-bottom)))]
                                 (assoc acc id {:x x, :y y, :w w, :h h})))
                             {}
                             anchors)))
        min-gap (cond (< width 1100) 24
                      (< width 1500) 36
                      :else 52)]
    (resolve-overlaps seed-positions min-gap)))


(def default-positions
  (let [width (or (some-> js/window
                          .-innerWidth)
                  1600)
        height (or (some-> js/window
                           .-innerHeight)
                   1000)]
    (responsive-layout :circuit width height)))


(def default-vm-pane-ratios
  {:walker {:top 0.6, :middle 0.3, :bottom 0.1},
   :semantic {:top 0.6, :middle 0.3, :bottom 0.1},
   :register {:top 0.6, :middle 0.3, :bottom 0.1},
   :stack {:top 0.6, :middle 0.3, :bottom 0.1}})


(defonce app-state
  (r/atom
    {:source-lang :clojure,
     :source-code "(+ 4 5)",
     :ast-as-text "",
     :compiled-asts nil,
     :walker-result nil,
     :semantic-result nil,
     :datoms nil,
     :ds-db nil,
     :root-ids nil,
     :root-eids nil,
     :semantic-stats nil,
     :register-asm nil,
     :register-bytecode nil,
     :register-result nil,
     :register-source-map nil,
     :stack-asm nil,
     :stack-bc nil,
     :stack-result nil,
     :stack-source-map nil,
     :query-text "[:find ?e ?type\n :where [?e :yin/type ?type]]",
     :query-inputs "",
     :query-result nil,
     :error nil,
     :layout-style :circuit,
     :layout-touched? false,
     :show-explainer-video? false,
     :ui-positions default-positions,
     :z-order (vec (keys default-positions)),
     :collapsed #{},
     :drag-state nil,
     :resize-state nil,
     :panel-resize-state nil,
     :vm-pane-ratios default-vm-pane-ratios,
     :walker-source-map nil,
     ;; Per-window VM states for stepping execution
     :vm-states {:register {:state nil, :running false, :expanded false},
                 :stack {:state nil, :running false, :expanded false},
                 :semantic {:state nil, :running false, :expanded false},
                 :walker {:state nil, :running false, :expanded false}}}))


(when (or (nil? (:ui-positions @app-state))
          (not (:h (:source (:ui-positions @app-state))))
          (= 750 (:y (:query (:ui-positions @app-state)))))
  (swap! app-state assoc :ui-positions default-positions))


(when (nil? (:layout-style @app-state))
  (swap! app-state assoc :layout-style :circuit))


(when (nil? (:layout-touched? @app-state))
  (swap! app-state assoc :layout-touched? false))


(when (nil? (:vm-pane-ratios @app-state))
  (swap! app-state assoc :vm-pane-ratios default-vm-pane-ratios))


(defn relayout-ui!
  ([] (relayout-ui! {}))
  ([{:keys [force? style mark-touched?],
     :or {force? false, mark-touched? false}}]
   (let [width (or (some-> js/window
                           .-innerWidth)
                   1600)
         height (or (some-> js/window
                            .-innerHeight)
                    1000)]
     (swap! app-state
            (fn [state]
              (let [layout-style (or style (:layout-style state) :circuit)
                    touched? (boolean (:layout-touched? state))
                    should-relayout? (or force? (not touched?))
                    positions (when should-relayout?
                                (responsive-layout layout-style width height))]
                (cond-> (assoc state :layout-style layout-style)
                  positions (assoc :ui-positions positions)
                  positions (update :z-order
                                    (fn [z-order]
                                      (if (and (vector? z-order)
                                               (= (set z-order) (set card-order)))
                                        z-order
                                        card-order)))
                  mark-touched? (assoc :layout-touched? true))))))))


(defn codemirror-editor
  [{:keys [value on-change read-only language highlight-range]}]
  (let [view-ref (r/atom nil)
        el-ref (atom nil)]
    (r/create-class
      {:display-name "codemirror-editor",
       :component-did-mount
       (fn [this]
         (when-let [node @el-ref]
           (let [lang-ext (case language
                            :python (python)
                            :php (php #js {:plain true})
                            (clojure))
                 theme (.theme EditorView
                               #js {"&" #js {:height "100%"},
                                    ".cm-scroller" #js {:overflow "auto"}})
                 extensions
                 (cond-> #js [basicSetup lang-ext oneDark theme
                              highlight-field]
                   read-only (.concat #js [(.of (.-editable EditorView)
                                                false)])
                   on-change
                   (.concat
                     #js [(.of
                            (.-updateListener EditorView)
                            (fn [^js update]
                              (when (and (.-docChanged update) on-change)
                                (on-change
                                  (.. update -state -doc toString)))))]))
                 state (.create EditorState
                                #js {:doc value, :extensions extensions})
                 view (new EditorView #js {:state state, :parent node})]
             (when highlight-range
               (.dispatch view
                          #js {:effects #js [(.of set-highlight
                                                  highlight-range)]}))
             (reset! view-ref view)))),
       :component-did-update
       (fn [this old-argv]
         (let [{:keys [value highlight-range]} (r/props this)
               old-highlight-range (:highlight-range (second old-argv))]
           (when-let [view @view-ref]
             (let [current-value (.. view -state -doc toString)]
               (when (not= value current-value)
                 (.dispatch view
                            #js {:changes #js
                                          {:from 0,
                                           :to (.. view -state -doc -length),
                                           :insert value}})))
             (when (not= highlight-range old-highlight-range)
               (let [effects #js [(.of set-highlight highlight-range)]
                     effects (if (and highlight-range
                                      (not= highlight-range
                                            old-highlight-range))
                               (.concat effects
                                        #js [(.scrollIntoView
                                               EditorView
                                               (first highlight-range))])
                               effects)]
                 (.dispatch view #js {:effects effects})))))),
       :component-will-unmount (fn [this]
                                 (when-let [view @view-ref] (.destroy view))),
       :reagent-render
       (fn [{:keys [style]}]
         [:div
          {:ref #(reset! el-ref %),
           :style (merge {:height "100%",
                          :width "100%",
                          :border "1px solid #2d3b55",
                          :overflow "hidden"}
                         style)}])})))


(defn- read-ast-forms
  []
  (let [{:keys [compiled-asts ast-as-text]} @app-state]
    (or compiled-asts (reader/read-string (str "[" ast-as-text "]")))))


(defn- set-vm-state!
  [vm-key state]
  (swap! app-state assoc-in [:vm-states vm-key :state] state)
  (swap! app-state assoc-in [:vm-states vm-key :running] false))


(defn- clear-vm-state!
  [vm-key]
  (set-vm-state! vm-key nil))


(defn- load-stack-state
  [bc pool]
  (-> (stack/create-vm {:env vm/primitives})
      (vm/load-program {:bc bc, :pool pool})))


(defn- load-register-state
  [bytecode pool reg-count]
  (-> (register/create-vm {:env vm/primitives})
      (vm/load-program {:bytecode bytecode, :pool pool, :reg-count reg-count})))


(defn- load-semantic-state
  [root-id datoms]
  (-> (semantic/create-vm {:env vm/primitives})
      (vm/load-program {:node root-id, :datoms datoms})))


(defn- load-walker-state
  [ast]
  (-> (walker/create-vm {:env vm/primitives})
      (vm/load-program ast)))


(defn compile-stack
  []
  (try (let [forms (read-ast-forms)
             results (mapv (fn [ast]
                             (let [datoms (vm/ast->datoms ast)
                                   asm (stack/ast-datoms->asm datoms)
                                   result (stack/asm->bytecode asm)]
                               {:asm asm,
                                :bc (:bc result),
                                :pool (:pool result),
                                :source-map (:source-map result)}))
                           forms)
             last-result (last results)
             initial-state (when last-result
                             (load-stack-state (:bc last-result)
                                               (:pool last-result)))]
         (swap! app-state assoc
                :stack-asm (mapv :asm results)
                :stack-bc (mapv :bc results)
                :stack-pool (mapv :pool results)
                :stack-source-map (mapv :source-map results)
                :error nil)
         (set-vm-state! :stack initial-state)
         results)
       (catch :default e
         (swap! app-state assoc
                :error (str "Stack Compile Error: " (.-message e))
                :stack-asm nil
                :stack-bc nil)
         (clear-vm-state! :stack)
         nil)))


(defn reset-stack
  "Reset stack VM to initial state from compiled bytecode."
  []
  (let [bc (last (:stack-bc @app-state))
        pool (last (:stack-pool @app-state))]
    (when (and bc pool)
      (let [initial-state (load-stack-state bc pool)]
        (set-vm-state! :stack initial-state)
        (swap! app-state assoc :stack-result nil)))))


(def steps-per-frame 100)


(defn step-vm
  "Execute one instruction in the given VM."
  [vm-key result-key]
  (let [state (get-in @app-state [:vm-states vm-key :state])]
    (when (and state (not (vm/halted? state)))
      (try (let [new-state (vm/step state)]
             (swap! app-state assoc-in [:vm-states vm-key :state] new-state)
             (when (vm/halted? new-state)
               (swap! app-state assoc result-key (vm/value new-state))
               (swap! app-state assoc-in [:vm-states vm-key :running] false)))
           (catch js/Error e
             (swap! app-state assoc
                    :error
                    (str (name vm-key) " VM Step Error: " (.-message e)))
             (swap! app-state assoc-in [:vm-states vm-key :running] false))))))


(defn run-vm-loop
  "Auto-step loop for the given VM using requestAnimationFrame."
  [vm-key result-key]
  (let [running (get-in @app-state [:vm-states vm-key :running])
        initial-state (get-in @app-state [:vm-states vm-key :state])]
    (when (and running initial-state (not (vm/halted? initial-state)))
      (try (loop [i 0
                  state initial-state]
             (if (or (>= i steps-per-frame) (vm/halted? state))
               (do (swap! app-state assoc-in [:vm-states vm-key :state] state)
                   (if (vm/halted? state)
                     (do (swap! app-state assoc result-key (vm/value state))
                         (swap! app-state assoc-in
                                [:vm-states vm-key :running]
                                false))
                     (js/requestAnimationFrame #(run-vm-loop vm-key
                                                             result-key))))
               (recur (inc i) (vm/step state))))
           (catch js/Error e
             (swap! app-state assoc
                    :error
                    (str (name vm-key) " VM Error: " (.-message e)))
             (swap! app-state assoc-in [:vm-states vm-key :running] false))))))


(defn toggle-run-vm
  "Toggle auto-stepping for the given VM."
  [vm-key result-key]
  (let [running (get-in @app-state [:vm-states vm-key :running])]
    (swap! app-state assoc-in [:vm-states vm-key :running] (not running))
    (when (not running) (run-vm-loop vm-key result-key))))


(defn reset-walker
  "Reset AST Walker VM to initial state."
  []
  (let [input (:ast-as-text @app-state)]
    (try (let [forms (reader/read-string (str "[" input "]"))
               last-form (last forms)
               ast-with-ids (add-yin-ids last-form)
               text (ast->pretty-text ast-with-ids)
               vm-loaded (load-walker-state ast-with-ids)]
           (swap! app-state assoc :ast-as-text text :walker-source-map nil)
           (set-vm-state! :walker vm-loaded)
           (swap! app-state assoc :walker-result nil))
         (catch js/Error e
           (.error js/console "AST Walker Reset Error:" e)
           (swap! app-state assoc
                  :error
                  (str "AST Walker Reset Error: " (.-message e)))))))


(defn compile-ast
  []
  (try
    (let [forms (read-ast-forms)
          all-datom-groups (mapv vm/ast->datoms forms)
          all-datoms-before (vec (mapcat identity all-datom-groups))
          root-ids (mapv (fn [dg] (apply max (map first dg))) all-datom-groups)
          empty-db (d/empty-db vm/schema)
          tx-data (vm/datoms->tx-data all-datoms-before)
          conn (d/conn-from-db empty-db)
          {:keys [tempids]} (d/transact! conn tx-data)
          db @conn
          all-datoms (mapv (fn [d]
                             [(nth d 0) (nth d 1) (nth d 2) (nth d 3)
                              nil])
                           (d/datoms db :eavt))
          root-eids (mapv #(get tempids % %) root-ids)
          stats {:total-datoms (count all-datoms),
                 :lambdas (count (semantic/find-lambdas db)),
                 :applications (count (semantic/find-applications db)),
                 :variables (count (semantic/find-variables db)),
                 :literals (count (semantic/find-by-type db :literal))}
          last-root (last root-eids)
          initial-state (when last-root
                          (load-semantic-state last-root all-datoms))]
      (swap! app-state assoc
             :datoms all-datoms
             :ds-db db
             :root-ids root-ids
             :root-eids root-eids
             :semantic-stats stats
             :error nil)
      (set-vm-state! :semantic initial-state)
      all-datoms)
    (catch :default e
      (swap! app-state assoc
             :error (.-message e)
             :datoms nil
             :ds-db nil
             :root-ids nil
             :semantic-stats nil)
      (clear-vm-state! :semantic)
      nil)))


(defn reset-semantic
  "Reset semantic VM to initial state."
  []
  (let [datoms (:datoms @app-state)
        root-ids (or (:root-eids @app-state) (:root-ids @app-state))
        last-root (last root-ids)]
    (when (and datoms last-root)
      (let [initial-state (load-semantic-state last-root datoms)]
        (set-vm-state! :semantic initial-state)
        (swap! app-state assoc :semantic-result nil)))))


(defn- parse-in-bindings
  "Extract extra binding symbols from a query's :in clause (everything after $)."
  [query]
  (let [pairs (partition 2 1 query)
        in-idx (some (fn [[a b]] (when (= a :in) b)) pairs)]
    (when in-idx
      (let [in-start (.indexOf query :in)
            ;; Collect symbols after :in until next keyword or end
            after-in (drop (inc in-start) query)
            bindings (take-while #(not (keyword? %)) after-in)]
        ;; Drop the implicit $ binding
        (filter #(not= % '$) bindings)))))


(defn run-query
  []
  (let [db (:ds-db @app-state)]
    (if (nil? db)
      (swap! app-state assoc
             :error "No DataScript db. Click \"Compile ->\" first."
             :query-result nil)
      (try (let [query (reader/read-string (:query-text @app-state))
                 extra-bindings (parse-in-bindings query)
                 extra-vals (when (seq extra-bindings)
                              (let [input-text (or (:query-inputs @app-state)
                                                   "")]
                                (reader/read-string (str "[" input-text "]"))))
                 result (apply d/q query db (or extra-vals []))]
             (swap! app-state assoc :query-result result :error nil))
           (catch js/Error e
             (swap! app-state assoc
                    :error (str "Query Error: " (.-message e))
                    :query-result nil))))))


(defn compile-register
  []
  (try
    (let [forms (read-ast-forms)
          results (mapv (fn [ast]
                          (let [datoms (vm/ast->datoms ast)
                                {:keys [asm reg-count]}
                                (register/ast-datoms->asm datoms)
                                result (register/asm->bytecode asm)]
                            {:asm asm,
                             :reg-count reg-count,
                             :bytecode (:bytecode result),
                             :pool (:pool result),
                             :source-map (:source-map result)}))
                        forms)
          last-result (last results)
          initial-state (when last-result
                          (load-register-state (:bytecode last-result)
                                               (:pool last-result)
                                               (:reg-count last-result)))]
      (swap! app-state assoc
             :register-asm (mapv :asm results)
             :register-bytecode (mapv :bytecode results)
             :register-pool (mapv :pool results)
             :register-reg-counts (mapv :reg-count results)
             :register-source-map (mapv :source-map results)
             :error nil)
      (set-vm-state! :register initial-state)
      results)
    (catch :default e
      (swap! app-state assoc
             :error (str "Register Compile Error: " (.-message e))
             :register-asm nil
             :register-bytecode nil)
      (clear-vm-state! :register)
      nil)))


(defn reset-register
  "Reset register VM to initial state from compiled bytecode."
  []
  (let [bytecode (last (:register-bytecode @app-state))
        pool (last (:register-pool @app-state))
        reg-count (last (:register-reg-counts @app-state))
        state (get-in @app-state [:vm-states :register :state])]
    (when (and bytecode pool)
      (let [initial-state (if (and state (satisfies? vm/IVMReset state))
                            (vm/reset state)
                            (load-register-state bytecode pool reg-count))]
        (set-vm-state! :register initial-state)
        (swap! app-state assoc :register-result nil)))))


(defn compile-source
  []
  (let [input (:source-code @app-state)
        lang (:source-lang @app-state)]
    (try (let [asts (case lang
                      :clojure (let [forms (reader/read-string
                                             (str "[" input "]"))]
                                 [(yang/compile-program forms)])
                      :python (let [ast (py/compile input)] [ast])
                      :php (let [ast (php-comp/compile input)] [ast]))
               last-ast (last asts)
               ast-with-ids (add-yin-ids last-ast)
               text (ast->pretty-text ast-with-ids)]
           (swap! app-state assoc
                  :ast-as-text text
                  :compiled-asts asts
                  :walker-source-map nil
                  :error nil)
           (set-vm-state! :walker (load-walker-state ast-with-ids))
           (swap! app-state assoc :walker-result nil))
         (catch :default e
           (swap! app-state assoc
                  :error (str "Compile Error: " (.-message e))
                  :compiled-asts nil)))))


(def code-examples
  [{:name "Clojure: Basic Math", :lang :clojure, :code "(+ 10 20)"}
   {:name "Clojure: Closure Power",
    :lang :clojure,
    :code
    "(def make-power\n  (fn [e]\n    (fn [b]\n      (if (= e 0)\n        1\n        (* b ((make-power (- e 1)) b))))))\n((make-power 3) 2)"}
   {:name "Clojure: Factorial",
    :lang :clojure,
    :code
    "(def fact (fn [n]\n  (if (= n 0)\n    1\n    (* n (fact (- n 1))))))\n(fact 5)"}
   {:name "Clojure: Fibonacci",
    :lang :clojure,
    :code
    "(def fib (fn [n]\n  (if (< n 2)\n    n\n    (+ (fib (- n 1)) (fib (- n 2))))))\n(fib 7)"}
   {:name "Python: Basic Math", :lang :python, :code "10 + 20"}
   {:name "Python: Closure Power",
    :lang :python,
    :code
    "def make_power(e):\n  return lambda b: 1 if e == 0 else b * (make_power(e - 1))(b)\n(make_power(3))(2)\n"}
   {:name "Python: Factorial",
    :lang :python,
    :code
    "def fact(n):\n  if n == 0:\n    return 1\n  else:\n    return n * fact(n-1)\nfact(5)"}
   {:name "Python: Fibonacci",
    :lang :python,
    :code
    "def fib(n):\n  if n < 2:\n    return n\n  else:\n    return fib(n-1) + fib(n-2)\nfib(7)"}
   {:name "PHP: Basic Math", :lang :php, :code "10 + 20;"}
   {:name "PHP: Closure Power",
    :lang :php,
    :code
    "$makePower = function (int $exponent) {\n  return function (int $base) use ($exponent): int {\n    $result = 1;\n    for ($i = 0; $i < $exponent; $i++) {\n      $result *= $base;\n    }\n    return $result;\n  };\n};\n$cube = $makePower(3);\n$cube(2);"}
   {:name "PHP: Factorial",
    :lang :php,
    :code
    "function fact($n) {\n  if ($n == 0) {\n    return 1;\n  } else {\n    return $n * fact($n - 1);\n  }\n}\nfact(5);"}
   {:name "PHP: Fibonacci",
    :lang :php,
    :code
    "function fib($n) {\n  if ($n < 2) {\n    return $n;\n  } else {\n    return fib($n - 1) + fib($n - 2);\n  }\n}\nfib(7);"}])


(defn dropdown-menu
  [items on-select]
  (let [open? (r/atom false)]
    (fn [items on-select]
      [:div {:style {:position "relative"}}
       [:button
        {:on-click #(swap! open? not),
         :style {:background "none",
                 :border "none",
                 :color "#f1f5ff",
                 :cursor "pointer",
                 :font-size "1.2rem",
                 :padding "0 5px",
                 :line-height "1"}} "☰"]
       (when @open?
         [:div
          {:style {:position "absolute",
                   :top "100%",
                   :right "0",
                   :background "#1a2035",
                   :border "1px solid #2d3b55",
                   :border-radius "4px",
                   :box-shadow "0 4px 12px rgba(0,0,0,0.5)",
                   :z-index "1000",
                   :width "220px",
                   :margin-top "5px",
                   :max-height "300px",
                   :overflow-y "auto"}}
          (for [{:keys [name], :as item} items]
            ^{:key name}
            [:div
             {:on-click (fn [] (on-select item) (reset! open? false)),
              :style {:padding "8px 12px",
                      :cursor "pointer",
                      :font-size "0.9rem",
                      :color "#c5c6c7",
                      :border-bottom "1px solid #2d3b55"},
              :on-mouse-over #(set! (.. % -target -style -background)
                                    "#2d3b55"),
              :on-mouse-out #(set! (.. % -target -style -background) "none")}
             name])])])))


(defn hamburger-menu
  []
  [dropdown-menu code-examples
   (fn [ex]
     (swap! app-state assoc :source-code (:code ex) :source-lang (:lang ex)))])


(def query-examples
  [{:name "All node types",
    :query "[:find ?e ?type\n :where [?e :yin/type ?type]]"}
   {:name "Find literals",
    :query
    "[:find ?e ?v\n :where\n [?e :yin/type :literal]\n [?e :yin/value ?v]]"}
   {:name "Find variables",
    :query
    "[:find ?e ?name\n :where\n [?e :yin/type :variable]\n [?e :yin/name ?name]]"}
   {:name "Find lambdas",
    :query
    "[:find ?e ?params\n :where\n [?e :yin/type :lambda]\n [?e :yin/params ?params]]"}
   {:name "Find applications",
    :query
    "[:find ?e ?op\n :where\n [?e :yin/type :application]\n [?e :yin/operator ?op]]"}
   {:name "Find conditionals", :query "[:find ?e\n :where [?e :yin/type :if]]"}
   {:name "All attributes for entity",
    :query "[:find ?e ?a ?v\n :where [?e ?a ?v]]"}
   {:name "Lambda body structure",
    :query
    "[:find ?lam ?body ?body-type\n :where\n [?lam :yin/type :lambda]\n [?lam :yin/body ?body]\n [?body :yin/type ?body-type]]"}
   {:name "AST depth (app nesting)",
    :query
    "[:find ?app ?op-type\n :where\n [?app :yin/type :application]\n [?app :yin/operator ?op]\n [?op :yin/type ?op-type]]"}
   {:name "Leaf nodes (no children)",
    :query
    "[:find ?e ?type\n :where\n [?e :yin/type ?type]\n (not [?e :yin/body _])\n (not [?e :yin/operator _])\n (not [?e :yin/test _])]"}
   {:name "Nested applications",
    :query
    "[:find ?outer ?inner\n :where\n [?outer :yin/type :application]\n [?outer :yin/operands ?inner]\n [?inner :yin/type :application]]"}
   {:name "If-branch types",
    :query
    "[:find ?if ?cons-type ?alt-type\n :where\n [?if :yin/type :if]\n [?if :yin/consequent ?cons]\n [?cons :yin/type ?cons-type]\n [?if :yin/alternate ?alt]\n [?alt :yin/type ?alt-type]]"}
   {:name "Lambda call sites",
    :query
    "[:find ?app ?lam\n :where\n [?app :yin/type :application]\n [?app :yin/operator ?lam]\n [?lam :yin/type :lambda]]"}
   {:name "Entity count by type",
    :query "[:find ?type (count ?e)\n :where [?e :yin/type ?type]]"}])


(defn query-menu
  []
  [dropdown-menu query-examples
   (fn [ex] (swap! app-state assoc :query-text (:query ex)))])


(defn layout-controls
  []
  (let [layout-style (:layout-style @app-state)]
    [:div
     {:style {:position "absolute",
              :top "16px",
              :right "20px",
              :display "flex",
              :align-items "center",
              :gap "8px",
              :padding "6px 8px",
              :background "rgba(14,19,40,0.9)",
              :border "1px solid #2d3b55",
              :border-radius "6px",
              :z-index "140"}}
     [:span {:style {:font-size "12px", :color "#8b949e"}} "Layout"]
     [:select
      {:value (name layout-style),
       :on-change (fn [e]
                    (let [next-style (keyword (.. e -target -value))]
                      (relayout-ui! {:force? true,
                                     :style next-style,
                                     :mark-touched? true}))),
       :style {:background "#0e1328",
               :color "#c5c6c7",
               :border "1px solid #2d3b55",
               :font-size "12px",
               :padding "2px 4px"}}
      (for [{:keys [id label]} layout-style-options]
        ^{:key (name id)} [:option {:value (name id)} label])]]))


(defn vm-control-buttons
  "Render Step/Run/Reset buttons for a VM."
  [{:keys [vm-key step-fn toggle-run-fn reset-fn]}]
  (let [vm-state (get-in @app-state [:vm-states vm-key])
        running (:running vm-state)
        state (:state vm-state)
        halted (when state
                 (try (if (satisfies? vm/IVMStep state)
                        (vm/halted? state)
                        (or (:halted state) false))
                      (catch js/Error _ (or (:halted state) false))))]
    [:div {:style {:display "flex", :gap "5px", :margin-bottom "4px"}}
     [:button
      {:on-click step-fn,
       :disabled (or running halted (nil? state)),
       :style
       {:background (if (or running halted (nil? state)) "#333" "#1f6feb"),
        :color "#fff",
        :border "none",
        :padding "5px 10px",
        :border-radius "4px",
        :cursor (if (or running halted (nil? state)) "not-allowed" "pointer"),
        :font-size "12px"}} "Step"]
     [:button
      {:on-click toggle-run-fn,
       :disabled (or halted (nil? state)),
       :style {:background (cond (or halted (nil? state)) "#333"
                                 running "#da3633"
                                 :else "#238636"),
               :color "#fff",
               :border "none",
               :padding "5px 10px",
               :border-radius "4px",
               :cursor (if (or halted (nil? state)) "not-allowed" "pointer"),
               :font-size "12px"}} (if running "Pause" "Run")]
     [:button
      {:on-click reset-fn,
       :disabled (nil? state),
       :style {:background (if (nil? state) "#333" "#6e7681"),
               :color "#fff",
               :border "none",
               :padding "5px 10px",
               :border-radius "4px",
               :cursor (if (nil? state) "not-allowed" "pointer"),
               :font-size "12px"}} "Reset"]]))


(defn vm-state-display
  "Common VM state display. Takes vm-key and three render fns:
   - summary-fn:  (fn [state] data-or-nil) - collapsed editor content
   - expanded-fn: (fn [state] data-or-nil) - expanded editor content"
  [{:keys [vm-key summary-fn expanded-fn]}]
  (let [vm-state (get-in @app-state [:vm-states vm-key])
        state (:state vm-state)
        expanded (:expanded vm-state)]
    (when state
      (let [display-data (or (when (and expanded expanded-fn)
                               (expanded-fn state))
                             (when summary-fn (summary-fn state))
                             state)]
        [:div
         {:style {:padding "5px",
                  :background "#0d1117",
                  :border "1px solid #30363d",
                  :display "flex",
                  :flex-direction "column",
                  :overflow "hidden",
                  :height "100%",
                  :min-height "0"}}
         [:div
          {:style {:display "flex",
                   :justify-content "flex-end",
                   :align-items "center"}}
          [:button
           {:on-click
            #(swap! app-state update-in [:vm-states vm-key :expanded] not),
            :style {:background "none",
                    :border "none",
                    :color "#58a6ff",
                    :cursor "pointer",
                    :font-size "10px"}} (if expanded "Collapse" "Expand")]]
         [codemirror-editor
          {:value (pretty-print display-data),
           :read-only true,
           :style {:flex "1", :min-height "0", :margin-top "5px"}}]]))))


(defn vm-result-editor
  [value show-result?]
  [:div
   {:style {:background "#0d1117",
            :padding "4px",
            :border "1px solid #30363d",
            :display "flex",
            :flex-direction "column",
            :overflow "hidden",
            :height "100%",
            :min-height "0"}}
   [:strong {:style {:margin-bottom "3px", :font-size "11px"}} "Result:"]
   [codemirror-editor
    {:value (if show-result? (pretty-print value) ""),
     :read-only true,
     :style {:flex "1", :min-height "0"}}]])


(def pane-min-ratio 0.12)


(defn- clamp-pane-ratios
  [{:keys [top middle]}]
  (let [top* (clamp top pane-min-ratio (- 1 (* 2 pane-min-ratio)))
        middle* (clamp middle pane-min-ratio (- 1 top* pane-min-ratio))
        bottom* (max pane-min-ratio (- 1 top* middle*))
        sum (+ top* middle* bottom*)]
    {:top (/ top* sum), :middle (/ middle* sum), :bottom (/ bottom* sum)}))


(defn- resize-pane-ratios
  [ratios divider dy container-height]
  (let [{:keys [top middle bottom]} (clamp-pane-ratios ratios)
        delta (/ dy (max 1 container-height))]
    (if (= divider :top-middle)
      (let [new-top
            (clamp (+ top delta) pane-min-ratio (- 1 pane-min-ratio bottom))
            new-middle (- 1 bottom new-top)]
        (clamp-pane-ratios {:top new-top, :middle new-middle, :bottom bottom}))
      (let [new-middle
            (clamp (+ middle delta) pane-min-ratio (- 1 pane-min-ratio top))
            new-bottom (- 1 top new-middle)]
        (clamp-pane-ratios
          {:top top, :middle new-middle, :bottom new-bottom})))))


(defn start-panel-resize!
  [e vm-key divider]
  (.preventDefault e)
  (.stopPropagation e)
  (let [container (.-parentElement (.-currentTarget e))
        height (or (some-> container
                           .-clientHeight)
                   1)
        ratios (get-in @app-state
                       [:vm-pane-ratios vm-key]
                       (get default-vm-pane-ratios vm-key))]
    (swap! app-state assoc
           :panel-resize-state
           {:vm-key vm-key,
            :divider divider,
            :start-y (.-clientY e),
            :height height,
            :ratios ratios})))


(defn vm-divider
  [vm-key divider]
  [:div
   {:style {:height "8px",
            :cursor "ns-resize",
            :background "#121a30",
            :border-top "1px solid #2d3b55",
            :border-bottom "1px solid #2d3b55"},
    :on-mouse-down #(start-panel-resize! % vm-key divider)}])


(defn vm-split-layout
  [vm-key top-content middle-content bottom-content]
  (let [ratios (get-in @app-state
                       [:vm-pane-ratios vm-key]
                       (get default-vm-pane-ratios vm-key))
        {:keys [top middle bottom]} (clamp-pane-ratios ratios)]
    [:div
     {:style {:display "grid",
              :grid-template-rows
              (str top "fr 8px " middle "fr 8px " bottom "fr"),
              :gap "0",
              :flex "1",
              :min-height "0",
              :overflow "hidden"}}
     [:div {:style {:min-height "0", :overflow "hidden"}} top-content]
     [vm-divider vm-key :top-middle]
     [:div {:style {:min-height "0", :overflow "hidden"}} middle-content]
     [vm-divider vm-key :middle-bottom]
     [:div {:style {:min-height "0", :overflow "hidden"}} bottom-content]]))


(defn bring-to-front!
  [id]
  (swap! app-state update
         :z-order
         (fn [order] (conj (vec (remove #(= % id) order)) id))))


(defn draggable-card
  [id title content]
  (let [positions (r/cursor app-state [:ui-positions])
        z-order (r/cursor app-state [:z-order])
        collapsed-state (r/cursor app-state [:collapsed])
        drag-state (r/cursor app-state [:drag-state])
        resize-state (r/cursor app-state [:resize-state])
        pos (get @positions id)
        collapsed? (contains? @collapsed-state id)
        base-z (or (some-> @z-order
                           (.indexOf id)
                           (+ 10))
                   10)
        z-index (cond (= (:id @drag-state) id) 1000
                      (= (:id @resize-state) id) 1000
                      :else base-z)]
    [:div
     {:on-mouse-down #(bring-to-front! id),
      :style {:position "absolute",
              :left (:x pos),
              :top (:y pos),
              :width (:w pos),
              :height (if collapsed? "auto" (:h pos)),
              :min-height (if collapsed? "auto" "200px"),
              :background "#0e1328",
              :border "1px solid #2d3b55",
              :box-shadow "0 10px 25px rgba(0,0,0,0.5)",
              :z-index z-index,
              :color "#c5c6c7",
              :display "flex",
              :flex-direction "column"}}
     [:div
      {:style {:background "#151b33",
               :padding "5px 10px",
               :cursor "move",
               :border-bottom "1px solid #2d3b55",
               :font-weight "bold",
               :color "#f1f5ff",
               :user-select "none",
               :display "flex",
               :justify-content "space-between",
               :align-items "center"},
       :on-mouse-down (fn [e]
                        (.preventDefault e)
                        (reset! drag-state {:id id,
                                            :start-x (.-clientX e),
                                            :start-y (.-clientY e),
                                            :initial-pos pos}))} [:span title]
      [:button
       {:on-click (fn [e]
                    (.stopPropagation e)
                    (swap! collapsed-state (if collapsed? disj conj) id)),
        :style {:background "none",
                :border "none",
                :color "#c5c6c7",
                :cursor "pointer",
                :font-size "16px",
                :line-height "1",
                :padding "0 5px"}} (if collapsed? "+" "−")]]
     (when-not collapsed?
       [:div
        {:style {:padding "10px",
                 :flex "1",
                 :display "flex",
                 :flex-direction "column",
                 :overflow "hidden"}} content])
     ;; Resize handle
     (when-not collapsed?
       [:div
        {:style {:position "absolute",
                 :bottom "0",
                 :right "0",
                 :width "15px",
                 :height "15px",
                 :cursor "nwse-resize",
                 :background
                 "linear-gradient(135deg, transparent 50%, #2d3b55 50%)"},
         :on-mouse-down (fn [e]
                          (.preventDefault e)
                          (.stopPropagation e)
                          (reset! resize-state {:id id,
                                                :start-x (.-clientX e),
                                                :start-y (.-clientY e),
                                                :initial-pos pos}))}])]))


(defn connection-line
  [from-id to-id label on-click]
  (let [positions (:ui-positions @app-state)
        collapsed (:collapsed @app-state)
        from (if (map? from-id) from-id (get positions from-id))
        to (if (map? to-id) to-id (get positions to-id))]
    (when (and from to)
      (let [from-is-map? (map? from-id)
            to-is-map? (map? to-id)
            from-collapsed? (and (not from-is-map?)
                                 (contains? collapsed from-id))
            vertical? (and (= from-id :semantic) (= to-id :query))
            from-h (if from-collapsed? 35 (or (:h from) 0))
            start-x (cond from-is-map? (:x from)
                          vertical? (+ (:x from) (/ (:w from) 2))
                          :else (+ (:x from) (:w from)))
            start-y (cond from-is-map? (:y from)
                          vertical? (+ (:y from) from-h)
                          :else (+ (:y from) (/ from-h 2)))
            end-x (cond to-is-map? (:x to)
                        vertical? (+ (:x to) (/ (:w to) 2))
                        :else (:x to))
            end-y (cond to-is-map? (:y to)
                        vertical? (:y to)
                        :else (+ (:y to) 20))
            dx (Math/abs (- end-x start-x))
            dy (Math/abs (- end-y start-y))
            cp1-x (if vertical? start-x (+ start-x (/ dx 2)))
            cp1-y (if vertical? (+ start-y (/ dy 2)) start-y)
            cp2-x (if vertical? end-x (- end-x (/ dx 2)))
            cp2-y (if vertical? (- end-y (/ dy 2)) end-y)
            path-d (str "M " start-x
                        " " start-y
                        " C " cp1-x
                        " " cp1-y
                        ", " cp2-x
                        " " cp2-y
                        ", " end-x
                        " " end-y)
            mid-x (+ start-x (/ (- end-x start-x) 2))
            mid-y (+ start-y (/ (- end-y start-y) 2))]
        [:g
         [:path {:d path-d, :fill "none", :stroke "#444c56", :stroke-width "2"}]
         (when-not to-is-map?
           [:circle {:cx end-x, :cy end-y, :r "4", :fill "#444c56"}])
         (when (and label on-click)
           [:foreignObject
            {:x (- mid-x 40),
             :y (- mid-y 15),
             :width "80",
             :height "30",
             :style {:pointer-events "auto"}}
            [:div {:style {:display "flex", :justify-content "center"}}
             [:button
              {:on-click on-click,
               :style {:background "#0e1328",
                       :border "1px solid #2d3b55",
                       :border-radius "4px",
                       :cursor "pointer",
                       :font-size "12px",
                       :color "#c5c6c7"}} label]]])]))))


(defn handle-mouse-move
  [e]
  (let [panel (:panel-resize-state @app-state)
        drag (:drag-state @app-state)
        resize (:resize-state @app-state)]
    (cond
      panel
      (let [dy (- (.-clientY e) (:start-y panel))
            vm-key (:vm-key panel)
            divider (:divider panel)
            new-ratios
            (resize-pane-ratios (:ratios panel) divider dy (:height panel))]
        (swap! app-state assoc-in [:vm-pane-ratios vm-key] new-ratios))
      drag (let [dx (- (.-clientX e) (:start-x drag))
                 dy (- (.-clientY e) (:start-y drag))
                 new-x (+ (:x (:initial-pos drag)) dx)
                 new-y (+ (:y (:initial-pos drag)) dy)]
             (when (or (not= dx 0) (not= dy 0))
               (swap! app-state assoc :layout-touched? true))
             (swap! app-state assoc-in [:ui-positions (:id drag) :x] new-x)
             (swap! app-state assoc-in [:ui-positions (:id drag) :y] new-y))
      resize
      (let [dx (- (.-clientX e) (:start-x resize))
            dy (- (.-clientY e) (:start-y resize))
            new-w (max 200 (+ (:w (:initial-pos resize)) dx))
            new-h (max 150 (+ (:h (:initial-pos resize)) dy))]
        (when (or (not= dx 0) (not= dy 0))
          (swap! app-state assoc :layout-touched? true))
        (swap! app-state assoc-in [:ui-positions (:id resize) :w] new-w)
        (swap! app-state assoc-in [:ui-positions (:id resize) :h] new-h)))))


(defn handle-mouse-up
  [e]
  (swap! app-state assoc
         :panel-resize-state nil
         :drag-state nil
         :resize-state nil))


(defonce resize-raf-id (atom nil))


(defn handle-window-resize
  [e]
  (when-not @resize-raf-id
    (reset! resize-raf-id (js/requestAnimationFrame (fn []
                                                      (reset! resize-raf-id nil)
                                                      (relayout-ui!))))))


(defn datom-list-view
  [datoms active-id]
  [:div
   {:style {:height "100%",
            :min-height "0",
            :overflow "auto",
            :font-family "monospace",
            :font-size "12px",
            :background "#0e1328",
            :color "#c5c6c7",
            :padding "5px"}}
   (for [[i d] (map-indexed vector datoms)]
     ^{:key i}
     [:div
      {:ref (fn [el]
              (when (and el (= (first d) active-id))
                (.scrollIntoView el
                                 #js {:block "nearest", :behavior "smooth"}))),
       :style {:background (if (= (first d) active-id) "#264f78" "transparent"),
               :padding "2px 4px",
               :border-bottom "1px solid #1e263a"}} (pr-str d)])])


(defn instruction-list-view
  [instructions active-idx]
  [:div
   {:style {:height "100%",
            :min-height "0",
            :overflow "auto",
            :font-family "monospace",
            :font-size "12px",
            :background "#0e1328",
            :color "#c5c6c7",
            :padding "5px"}}
   (for [[i instr] (map-indexed vector instructions)]
     ^{:key i}
     [:div
      {:ref (fn [el]
              (when (and el (= i active-idx))
                (.scrollIntoView el
                                 #js {:block "nearest", :behavior "smooth"}))),
       :style {:background (if (= i active-idx) "#264f78" "transparent"),
               :padding "2px 4px",
               :border-bottom "1px solid #1e263a"}}
      (str i ": " (pr-str instr))])])


(defn- active-reg-idx
  []
  (let [reg-state (get-in @app-state [:vm-states :register :state])
        reg-ip (:ip reg-state)
        reg-map (last (:register-source-map @app-state))]
    (get reg-map reg-ip)))


(defn- active-stack-idx
  []
  (let [stack-state (get-in @app-state [:vm-states :stack :state])
        stack-pc (:pc stack-state)
        stack-map (last (:stack-source-map @app-state))]
    (get stack-map stack-pc)))


(defn main-view
  []
  (r/create-class
    {:component-did-mount
     (fn []
       (js/window.addEventListener "mousemove" handle-mouse-move)
       (js/window.addEventListener "mouseup" handle-mouse-up)
       (js/window.addEventListener "resize" handle-window-resize)
       (relayout-ui!)),
     :component-will-unmount
     (fn []
       (js/window.removeEventListener "mousemove" handle-mouse-move)
       (js/window.removeEventListener "mouseup" handle-mouse-up)
       (js/window.removeEventListener "resize" handle-window-resize)),
     :reagent-render
     (fn []
       (let [asm-vm-state (get-in @app-state [:vm-states :semantic :state])
             asm-control (:control asm-vm-state)
             active-asm-id (when (= :node (:type asm-control))
                             (:id asm-control))
             reg-state (get-in @app-state [:vm-states :register :state])
             reg-asm (last (:register-asm @app-state))
             stack-state (get-in @app-state [:vm-states :stack :state])
             stack-asm (last (:stack-asm @app-state))
             active-reg-instr (active-reg-idx)
             active-stack-instr (active-stack-idx)
             max-dims (reduce (fn [acc [_ p]]
                                {:w (max (:w acc) (+ (:x p) (:w p) 100)),
                                 :h (max (:h acc) (+ (:y p) (:h p) 100))})
                              {:w 0, :h 0}
                              (:ui-positions @app-state))
             width (max js/window.innerWidth (:w max-dims))
             height (max js/window.innerHeight (:h max-dims))]
         [:div
          {:style {:width width,
                   :height height,
                   :position "relative",
                   :background "#060817",
                   :color "#c5c6c7"}}
          [:h1
           {:style {:margin "20px",
                    :position "absolute",
                    :top "0",
                    :left "0",
                    :pointer-events "none",
                    :color "#f1f5ff",
                    :z-index "100"}}
           [:a {:href "https://datom.world", :style {:pointer-events "auto"}}
            "Datom.world "]
           [:a {:href "/yin.chp", :style {:pointer-events "auto"}} "Yin VM "
            "Compilation Pipeline"]]
          [:button
           {:on-click #(swap! app-state assoc :show-explainer-video? true),
            :style {:position "absolute",
                    :top "20px",
                    :right "190px",
                    :z-index "120",
                    :background "#1f6feb",
                    :color "#f1f5ff",
                    :border "1px solid #2d3b55",
                    :border-radius "6px",
                    :padding "8px 12px",
                    :cursor "pointer",
                    :font-size "13px"}} "Explainer Video"] [layout-controls]
          (let [pos (:ui-positions @app-state)
                src (:source pos)
                walk (:walker pos)
                sem (:semantic pos)
                reg (:register pos)
                ;; Calculate where the Compile button would be
                start-x (+ (:x src) (:w src))
                start-y (+ (:y src) (/ (:h src) 2))
                end-x (:x walk)
                end-y (+ (:y walk) 20)
                fork-point {:x (+ start-x (/ (- end-x start-x) 2)),
                            :y (+ start-y (/ (- end-y start-y) 2))}
                ;; Calculate where the Bytecode button would be
                b-start-x (+ (:x sem) (:w sem))
                b-start-y (+ (:y sem) (/ (:h sem) 2))
                b-end-x (:x reg)
                bytecode-fork-point {:x (+ b-start-x
                                           (/ (- b-end-x b-start-x) 2)),
                                     :y b-start-y}]
            [:svg
             {:style {:position "absolute",
                      :top 0,
                      :left 0,
                      :width "100%",
                      :height "100%",
                      :pointer-events "none",
                      :z-index 0}}
             [connection-line :source fork-point "Compile ->"
              (fn [] (compile-source) (compile-ast))]
             [connection-line fork-point :walker nil nil]
             [connection-line fork-point :semantic nil nil]
             [connection-line :semantic bytecode-fork-point "-> Bytecode"
              (fn [] (compile-register) (compile-stack))]
             [connection-line bytecode-fork-point :register nil nil]
             [connection-line bytecode-fork-point :stack nil nil]
             #_[connection-line :semantic :query "d/q ->" run-query]])
          [draggable-card :source "Source Code"
           [:div
            {:style {:display "flex",
                     :flex-direction "column",
                     :flex "1",
                     :overflow "hidden"}}
            [:div
             {:style {:display "flex",
                      :justify-content "space-between",
                      :align-items "center",
                      :margin-bottom "5px"}}
             [:select
              {:value (:source-lang @app-state),
               :on-change (fn [e]
                            (swap! app-state assoc
                                   :source-lang
                                   (keyword (.. e -target -value)))),
               :style {:background "#0e1328",
                       :color "#c5c6c7",
                       :border "1px solid #2d3b55"}}
              [:option {:value "clojure"} "Clojure"]
              [:option {:value "python"} "Python"]
              [:option {:value "php"} "PHP"]] [hamburger-menu]]
            [codemirror-editor
             {:key (:source-lang @app-state),
              :value (:source-code @app-state),
              :language (:source-lang @app-state),
              :on-change (fn [v] (swap! app-state assoc :source-code v))}]
            [:div
             {:style {:marginTop "10px", :fontSize "0.8em", :color "#8b949e"}}
             "Select examples from the menu ☰ above."]]]
          [draggable-card :walker "AST Walker"
           (let [walker-vm-state (get-in @app-state
                                         [:vm-states :walker :state])
                 walker-ctrl (:control walker-vm-state)
                 walker-id (:yin/id walker-ctrl)
                 walker-range (get (:walker-source-map @app-state) walker-id)
                 walker-halted? (and walker-vm-state
                                     (vm/halted? walker-vm-state))
                 walker-value (when walker-halted?
                                (vm/value walker-vm-state))]
             [vm-split-layout :walker
              [codemirror-editor
               {:value (:ast-as-text @app-state),
                :highlight-range walker-range,
                :on-change (fn [v]
                             ;; Keep compiled ASTs when this change is
                             ;; just a programmatic refresh from
                             ;; compile-source.
                             (if (= v (:ast-as-text @app-state))
                               (swap! app-state assoc :ast-as-text v)
                               (swap! app-state assoc
                                      :ast-as-text v
                                      :compiled-asts nil)))}]
              [:div
               {:style {:display "flex",
                        :flex-direction "column",
                        :height "100%",
                        :min-height "0",
                        :padding-top "4px",
                        :overflow "hidden"}}
               [vm-control-buttons
                {:vm-key :walker,
                 :step-fn #(step-vm :walker :walker-result),
                 :toggle-run-fn #(toggle-run-vm :walker :walker-result),
                 :reset-fn reset-walker}]
               [:div {:style {:flex "1", :min-height "0", :overflow "hidden"}}
                [vm-state-display
                 {:vm-key :walker,
                  :status-fn
                  (fn [state]
                    (cond (vm/halted? state) "HALTED"
                          (:control state)
                          (str "Control: "
                               (or (get-in state [:control :type])
                                   (pr-str (:control state))))
                          (:continuation state)
                          (str "Cont: "
                               (or (get-in state [:continuation :type])
                                   "pending"))
                          :else "Returning...")),
                  :summary-fn (fn [state]
                                (when (not (vm/halted? state))
                                  (let [ctrl (:control state)]
                                    (when ctrl {:control ctrl})))),
                  :expanded-fn
                  (fn [state]
                    {:control (:control state),
                     :env-keys (vec (keys (:environment state))),
                     :continuation-depth
                     (loop [c (:continuation state)
                            depth 0]
                       (if c (recur (:parent c) (inc depth)) depth)),
                     :value (:value state)})}]]]
              [vm-result-editor walker-value walker-halted?]])]
          [draggable-card :semantic "Semantic VM"
           [vm-split-layout :semantic
            [datom-list-view (:datoms @app-state) active-asm-id]
            [:div
             {:style {:display "flex",
                      :flex-direction "column",
                      :height "100%",
                      :min-height "0",
                      :padding-top "4px",
                      :overflow "hidden"}}
             [vm-control-buttons
              {:vm-key :semantic,
               :step-fn #(step-vm :semantic :semantic-result),
               :toggle-run-fn #(toggle-run-vm :semantic :semantic-result),
               :reset-fn reset-semantic}]
             [:div {:style {:flex "1", :min-height "0", :overflow "hidden"}}
              [vm-state-display
               {:vm-key :semantic,
                :status-fn (fn [state]
                             (if (:halted state)
                               "HALTED"
                               (let [ctrl (:control state)]
                                 (if (= :node (:type ctrl))
                                   (str "Node: " (:id ctrl))
                                   (str "Val: " (pr-str (:val ctrl))))))),
                :summary-fn
                (fn [state]
                  (when (not (:halted state))
                    (let [ctrl (:control state)
                          info (if (= :node (:type ctrl))
                                 (let [attrs
                                       (let [tx-data (vm/datoms->tx-data
                                                       (:datoms state))
                                             conn (d/conn-from-db
                                                    (d/empty-db
                                                      vm/schema))
                                             _ (d/transact! conn tx-data)]
                                         (semantic/get-node-attrs
                                           @conn
                                           (:id ctrl)))]
                                   (str (:yin/type attrs)))
                                 "Returning...")]
                      {:control ctrl, :info info}))),
                :expanded-fn (fn [state]
                               {:control (:control state),
                                :env-keys (vec (keys (:env state))),
                                :stack-depth (count (:stack state)),
                                :value (:value state)})}]]]
            [vm-result-editor (:value asm-vm-state)
             (boolean (and asm-vm-state (:halted asm-vm-state)))]]]
          [draggable-card :register "Register VM"
           [vm-split-layout :register
            [instruction-list-view reg-asm active-reg-instr]
            [:div
             {:style {:display "flex",
                      :flex-direction "column",
                      :height "100%",
                      :min-height "0",
                      :padding-top "4px",
                      :overflow "hidden"}}
             [vm-control-buttons
              {:vm-key :register,
               :step-fn #(step-vm :register :register-result),
               :toggle-run-fn #(toggle-run-vm :register :register-result),
               :reset-fn reset-register}]
             [:div {:style {:flex "1", :min-height "0", :overflow "hidden"}}
              [vm-state-display
               {:vm-key :register,
                :status-fn
                (fn [state]
                  (if (:halted state) "HALTED" (str "ip: " (:ip state)))),
                :summary-fn (fn [state]
                              (let [regs (:regs state)
                                    active (filter (fn [[i v]] (some? v))
                                                   (map-indexed vector regs))]
                                {:ip (:ip state),
                                 :active-regs (into {} (take 4 active))})),
                :expanded-fn (fn [state]
                               {:ip (:ip state),
                                :regs (:regs state),
                                :env-keys (vec (keys (:env state))),
                                :continuation? (boolean (:k state)),
                                :value (:value state)})}]]]
            [vm-result-editor (:value reg-state)
             (boolean (and reg-state (:halted reg-state)))]]]
          [draggable-card :stack "Stack VM"
           [vm-split-layout :stack
            [instruction-list-view stack-asm active-stack-instr]
            [:div
             {:style {:display "flex",
                      :flex-direction "column",
                      :height "100%",
                      :min-height "0",
                      :padding-top "4px",
                      :overflow "hidden"}}
             [vm-control-buttons
              {:vm-key :stack,
               :step-fn #(step-vm :stack :stack-result),
               :toggle-run-fn #(toggle-run-vm :stack :stack-result),
               :reset-fn reset-stack}]
             [:div {:style {:flex "1", :min-height "0", :overflow "hidden"}}
              [vm-state-display
               {:vm-key :stack,
                :status-fn
                (fn [state]
                  (if (:halted state) "HALTED" (str "pc: " (:pc state)))),
                :summary-fn (fn [state]
                              {:pc (:pc state),
                               :stack-tail (vec (take-last 3 (:stack state))),
                               :stack-size (count (:stack state))}),
                :expanded-fn (fn [state]
                               {:pc (:pc state),
                                :stack (:stack state),
                                :env-keys (vec (keys (:env state))),
                                :call-stack-depth (count (:call-stack state)),
                                :value (:value state)})}]]]
            [vm-result-editor (:value stack-state)
             (boolean (and stack-state (:halted stack-state)))]]]
          [draggable-card :query "Datalog Query"
           [:div
            {:style {:display "flex",
                     :flex-direction "column",
                     :flex "1",
                     :overflow "hidden"}}
            [:div
             {:style {:display "flex",
                      :justify-content "flex-end",
                      :margin-bottom "5px"}} [query-menu]]
            [codemirror-editor
             {:value (:query-text @app-state),
              :on-change (fn [v] (swap! app-state assoc :query-text v)),
              :style {:flex "1", :min-height "80px"}}]
            [:div
             {:style {:display "flex",
                      :align-items "center",
                      :gap "5px",
                      :margin-top "5px"}}
             [:span {:style {:font-size "11px", :color "#8b949e"}} ":in"]
             [:input
              {:value (:query-inputs @app-state),
               :placeholder "extra inputs (e.g. 1)",
               :on-change (fn [e]
                            (swap! app-state assoc
                                   :query-inputs
                                   (.. e -target -value))),
               :style {:flex "1",
                       :background "#0a0f1e",
                       :border "1px solid #2d3b55",
                       :border-radius "4px",
                       :color "#c5c6c7",
                       :font-size "12px",
                       :font-family "monospace",
                       :padding "3px 6px",
                       :outline "none"}}]]
            [:button
             {:on-click run-query,
              :style {:marginTop "5px",
                      :background "#238636",
                      :color "#fff",
                      :border "none",
                      :padding "5px 10px",
                      :border-radius "4px",
                      :cursor "pointer"}} "Run Query"]
            [codemirror-editor
             {:value (if-let [result (:query-result @app-state)]
                       (pretty-print (vec (sort result)))
                       ""),
              :read-only true,
              :style {:flex "1", :min-height "80px", :marginTop "5px"}}]]]
          (when (:error @app-state)
            [:div
             {:style {:position "absolute",
                      :bottom "10px",
                      :left "10px",
                      :right "10px",
                      :background "rgba(255,0,0,0.2)",
                      :border "1px solid #da3633",
                      :padding "10px",
                      :color "#f85149"}} [:strong "Error: "]
             (:error @app-state)])
          (when (:show-explainer-video? @app-state)
            [:div
             {:style {:position "fixed",
                      :inset 0,
                      :z-index "300",
                      :background "rgba(6, 8, 23, 0.88)",
                      :display "flex",
                      :align-items "center",
                      :justify-content "center"},
              :on-click #(swap! app-state assoc :show-explainer-video? false)}
             [:div
              {:style {:position "relative",
                       :width "min(960px, 92vw)",
                       :max-height "90vh",
                       :background "#060817",
                       :border "1px solid #2d3b55",
                       :border-radius "10px",
                       :padding "12px"},
               :on-click #(.stopPropagation %)}
              [:button
               {:on-click
                #(swap! app-state assoc :show-explainer-video? false),
                :style {:position "absolute",
                        :top "8px",
                        :right "8px",
                        :z-index "2",
                        :background "#0e1328",
                        :color "#f1f5ff",
                        :border "1px solid #2d3b55",
                        :border-radius "4px",
                        :padding "4px 8px",
                        :cursor "pointer"}} "Close"]
              [:video
               {:src "/The_Performance_Paradox.mp4",
                :controls true,
                :autoPlay true,
                :style {:display "block",
                        :width "100%",
                        :height "auto",
                        :max-height "82vh",
                        :border-radius "6px"}}]]])]))}))


(defn init
  []
  (let [app (js/document.getElementById "app")] (rdom/render [main-view] app)))
