(ns datomworld.core
  (:require ["@codemirror/lang-python" :refer [python]]
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
            [reagent.core :as r]
            [reagent.dom :as rdom]
            [yang.clojure :as yang]
            [yang.python :as py]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as walker]
            [yin.vm.protocols :as proto]
            [yin.vm.register :as register]
            [yin.vm.semantic :as semantic]
            [yin.vm.stack :as stack]))


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
       (print-node [node indent]
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


(def default-positions
  {:source {:x 20, :y 180, :w 350, :h 450},
   :semantic {:x 900, :y 180, :w 400, :h 450},
   :register {:x 1400, :y 80, :w 350, :h 450},
   :stack {:x 1400, :y 650, :w 350, :h 450},
   :walker {:x 420, :y 180, :w 380, :h 450},
   :query {:x 900, :y 650, :w 400, :h 450}})


(defonce app-state
  (r/atom
    {:source-lang :clojure,
     :source-code "(+ 4 5)",
     :ast-as-text "",
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
     :ui-positions default-positions,
     :z-order (vec (keys default-positions)),
     :collapsed #{},
     :drag-state nil,
     :resize-state nil,
     :walker-source-map nil,
     ;; Per-window VM states for stepping execution
     :vm-states {:register {:state nil, :running false, :expanded false},
                 :stack {:state nil, :running false, :expanded false},
                 :semantic {:state nil, :running false, :expanded false},
                 :walker {:state nil, :running false, :expanded false}}}))


(when (or (nil? (:ui-positions @app-state))
          (not (:h (:source (:ui-positions @app-state))))
          (= 80 (:y (:source (:ui-positions @app-state)))))
  (swap! app-state assoc :ui-positions default-positions))


(defn codemirror-editor
  [{:keys [value on-change read-only language highlight-range]}]
  (let [view-ref (r/atom nil)
        el-ref (atom nil)]
    (r/create-class
      {:display-name "codemirror-editor",
       :component-did-mount
         (fn [this]
           (when-let [node @el-ref]
             (let [lang-ext (if (= language :python) (python) (clojure))
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
         (fn [{:keys [style]}] [:div
                                {:ref #(reset! el-ref %),
                                 :style (merge {:height "100%",
                                                :width "100%",
                                                :border "1px solid #2d3b55",
                                                :overflow "hidden"}
                                               style)}])})))


(defn compile-stack
  []
  (let [input (:ast-as-text @app-state)]
    (try
      (let [forms (reader/read-string (str "[" input "]"))
            results
              (mapv (fn [ast]
                      (let [datoms (vm/ast->datoms ast)
                            asm (stack/ast-datoms->asm datoms)
                            result (stack/stack-assembly->bytecode asm)
                            bc (:bc result)
                            pool (:pool result)
                            source-map (:source-map result)]
                        {:asm asm, :bc bc, :pool pool, :source-map source-map}))
                forms)
            ;; Initialize VM state from last compiled result
            last-result (last results)
            initial-state (when last-result
                            (stack/make-stack-state (:bc last-result)
                                                    (:pool last-result)
                                                    vm/primitives))]
        (swap! app-state assoc
          :stack-asm (mapv :asm results)
          :stack-bc (mapv :bc results)
          :stack-pool (mapv :pool results)
          :stack-source-map (mapv :source-map results)
          :error nil)
        ;; Initialize stepping state
        (swap! app-state assoc-in [:vm-states :stack :state] initial-state)
        (swap! app-state assoc-in [:vm-states :stack :running] false)
        results)
      (catch js/Error e
        (swap! app-state assoc
          :error (str "Stack Compile Error: " (.-message e))
          :stack-asm nil
          :stack-bc nil)
        (swap! app-state assoc-in [:vm-states :stack :state] nil)
        nil))))


(defn step-stack
  "Execute one instruction in the stack VM."
  []
  (let [state (get-in @app-state [:vm-states :stack :state])]
    (when (and state (not (:halted state)))
      (try (let [new-state (stack/stack-step state)]
             (swap! app-state assoc-in [:vm-states :stack :state] new-state)
             (when (:halted new-state)
               (swap! app-state assoc :stack-result (:value new-state))
               (swap! app-state assoc-in [:vm-states :stack :running] false)))
           (catch js/Error e
             (swap! app-state assoc
               :error
               (str "Stack VM Step Error: " (.-message e)))
             (swap! app-state assoc-in [:vm-states :stack :running] false))))))


(defn reset-stack
  "Reset stack VM to initial state from compiled bytecode."
  []
  (let [bc (last (:stack-bc @app-state))
        pool (last (:stack-pool @app-state))]
    (when (and bc pool)
      (let [initial-state (stack/make-stack-state bc pool vm/primitives)]
        (swap! app-state assoc-in [:vm-states :stack :state] initial-state)
        (swap! app-state assoc-in [:vm-states :stack :running] false)
        (swap! app-state assoc :stack-result nil)))))


(declare run-stack-loop)


(def steps-per-frame 100)


(defn toggle-run-stack
  "Toggle auto-stepping for stack VM."
  []
  (let [running (get-in @app-state [:vm-states :stack :running])]
    (swap! app-state assoc-in [:vm-states :stack :running] (not running))
    (when (not running) (run-stack-loop))))


(defn run-stack-loop
  "Auto-step loop for stack VM using requestAnimationFrame."
  []
  (let [running (get-in @app-state [:vm-states :stack :running])
        initial-state (get-in @app-state [:vm-states :stack :state])]
    (when (and running initial-state (not (:halted initial-state)))
      (try
        (loop [i 0
               state initial-state]
          (if (or (>= i steps-per-frame) (:halted state))
            (do (swap! app-state assoc-in [:vm-states :stack :state] state)
                (if (:halted state)
                  (do (swap! app-state assoc :stack-result (:value state))
                      (swap! app-state assoc-in
                        [:vm-states :stack :running]
                        false))
                  (js/requestAnimationFrame run-stack-loop)))
            (recur (inc i) (stack/stack-step state))))
        (catch js/Error e
          (swap! app-state assoc :error (str "Stack VM Error: " (.-message e)))
          (swap! app-state assoc-in [:vm-states :stack :running] false))))))


(defn reset-walker
  "Reset AST Walker VM to initial state."
  []
  (let [input (:ast-as-text @app-state)]
    (try
      (let [forms (reader/read-string (str "[" input "]"))
            last-form (last forms)
            ast-with-ids (add-yin-ids last-form)
            {:keys [text source-map]} (ast->text-with-map ast-with-ids)
            initial-env vm/primitives
            vm (walker/create initial-env)
            vm-loaded (proto/load-program vm ast-with-ids)]
        (.log js/console "Resetting walker with AST:" (clj->js ast-with-ids))
        (swap! app-state assoc :ast-as-text text :walker-source-map source-map)
        (swap! app-state assoc-in [:vm-states :walker :state] vm-loaded)
        (swap! app-state assoc-in [:vm-states :walker :running] false)
        (swap! app-state assoc :walker-result nil))
      (catch js/Error e
        (.error js/console "AST Walker Reset Error:" e)
        (swap! app-state assoc
          :error
          (str "AST Walker Reset Error: " (.-message e)))))))


(defn step-walker
  "Execute one instruction in the AST Walker VM."
  []
  (let [state (get-in @app-state [:vm-states :walker :state])]
    (when (and state (not (proto/halted? state)))
      (try (let [new-state (proto/step state)]
             (.log js/console "Stepped walker:" (clj->js new-state))
             (swap! app-state assoc-in [:vm-states :walker :state] new-state)
             (when (proto/halted? new-state)
               (swap! app-state assoc :walker-result (proto/value new-state))
               (swap! app-state assoc-in [:vm-states :walker :running] false)))
           (catch js/Error e
             (.error js/console "AST Walker Step Error:" e)
             (swap! app-state assoc
               :error
               (str "AST Walker Step Error: " (.-message e)))
             (swap! app-state assoc-in [:vm-states :walker :running] false))))))


(declare run-walker-loop)


(defn toggle-run-walker
  "Toggle auto-stepping for AST Walker VM."
  []
  (let [running (get-in @app-state [:vm-states :walker :running])]
    (swap! app-state assoc-in [:vm-states :walker :running] (not running))
    (when (not running) (run-walker-loop))))


(defn run-walker-loop
  "Auto-step loop for AST Walker VM using requestAnimationFrame."
  []
  (let [running (get-in @app-state [:vm-states :walker :running])
        initial-state (get-in @app-state [:vm-states :walker :state])]
    (when (and running initial-state (not (proto/halted? initial-state)))
      (try
        (loop [i 0
               state initial-state]
          (if (or (>= i steps-per-frame) (proto/halted? state))
            (do (swap! app-state assoc-in [:vm-states :walker :state] state)
                (if (proto/halted? state)
                  (do (swap! app-state assoc :walker-result (proto/value state))
                      (swap! app-state assoc-in
                        [:vm-states :walker :running]
                        false))
                  (js/requestAnimationFrame run-walker-loop)))
            (recur (inc i) (proto/step state))))
        (catch js/Error e
          (swap! app-state assoc
            :error
            (str "AST Walker VM Error: " (.-message e)))
          (swap! app-state assoc-in [:vm-states :walker :running] false))))))


(defn compile-ast
  []
  (let [input (:ast-as-text @app-state)]
    (try
      (let [forms (reader/read-string (str "[" input "]"))
            all-datom-groups (mapv vm/ast->datoms forms)
            all-datoms (vec (mapcat identity all-datom-groups))
            root-ids (mapv ffirst all-datom-groups)
            stats
              {:total-datoms (count all-datoms),
               :lambdas (count (semantic/find-lambdas all-datoms)),
               :applications (count (semantic/find-applications all-datoms)),
               :variables (count (semantic/find-variables all-datoms)),
               :literals (count (semantic/find-by-type all-datoms :literal))}]
        (swap! app-state assoc
          :datoms all-datoms
          :root-ids root-ids
          :semantic-stats stats
          :error nil)
        ;; Initialize stepping state
        (let [last-root (last root-ids)]
          (when last-root
            (let [initial-state (semantic/make-semantic-state
                                  {:node last-root, :datoms all-datoms}
                                  vm/primitives)]
              (swap! app-state assoc-in
                [:vm-states :semantic :state]
                initial-state)
              (swap! app-state assoc-in
                [:vm-states :semantic :running]
                false))))
        all-datoms)
      (catch js/Error e
        (swap! app-state assoc
          :error (.-message e)
          :datoms nil
          :root-ids nil
          :semantic-stats nil)
        (swap! app-state assoc-in [:vm-states :semantic :state] nil)
        nil))))


(defn step-semantic
  "Execute one instruction in the semantic VM."
  []
  (let [state (get-in @app-state [:vm-states :semantic :state])]
    (when (and state (not (:halted state)))
      (try
        (let [new-state (semantic/semantic-step state)]
          (swap! app-state assoc-in [:vm-states :semantic :state] new-state)
          (when (:halted new-state)
            (swap! app-state assoc :semantic-result (:value new-state))
            (swap! app-state assoc-in [:vm-states :semantic :running] false)))
        (catch js/Error e
          (swap! app-state assoc
            :error
            (str "Semantic VM Step Error: " (.-message e)))
          (swap! app-state assoc-in [:vm-states :semantic :running] false))))))


(defn reset-semantic
  "Reset semantic VM to initial state."
  []
  (let [datoms (:datoms @app-state)
        root-ids (:root-ids @app-state)
        last-root (last root-ids)]
    (when (and datoms last-root)
      (let [initial-state (semantic/make-semantic-state {:node last-root,
                                                         :datoms datoms}
                                                        vm/primitives)]
        (swap! app-state assoc-in [:vm-states :semantic :state] initial-state)
        (swap! app-state assoc-in [:vm-states :semantic :running] false)
        (swap! app-state assoc :semantic-result nil)))))


(declare run-semantic-loop)


(defn toggle-run-semantic
  "Toggle auto-stepping for semantic VM."
  []
  (let [running (get-in @app-state [:vm-states :semantic :running])]
    (swap! app-state assoc-in [:vm-states :semantic :running] (not running))
    (when (not running) (run-semantic-loop))))


(defn run-semantic-loop
  "Auto-step loop for semantic VM using requestAnimationFrame."
  []
  (let [running (get-in @app-state [:vm-states :semantic :running])
        initial-state (get-in @app-state [:vm-states :semantic :state])]
    (when (and running initial-state (not (:halted initial-state)))
      (try
        (loop [i 0
               state initial-state]
          (if (or (>= i steps-per-frame) (:halted state))
            (do (swap! app-state assoc-in [:vm-states :semantic :state] state)
                (if (:halted state)
                  (do (swap! app-state assoc :semantic-result (:value state))
                      (swap! app-state assoc-in
                        [:vm-states :semantic :running]
                        false))
                  (js/requestAnimationFrame run-semantic-loop)))
            (recur (inc i) (semantic/semantic-step state))))
        (catch js/Error e
          (swap! app-state assoc
            :error
            (str "Semantic VM Error: " (.-message e)))
          (swap! app-state assoc-in [:vm-states :semantic :running] false))))))


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
        :error "No DataScript db. Click \"Sem ->\" first."
        :query-result nil)
      (try (let [query (reader/read-string (:query-text @app-state))
                 extra-bindings (parse-in-bindings query)
                 extra-vals (when (seq extra-bindings)
                              (let [input-text (or (:query-inputs @app-state)
                                                   "")]
                                (reader/read-string (str "[" input-text "]"))))
                 args (into [query db] extra-vals)
                 result []
                 #_(apply vm/q args)]
             (swap! app-state assoc :query-result result :error nil))
           (catch js/Error e
             (swap! app-state assoc
               :error (str "Query Error: " (.-message e))
               :query-result nil))))))


(defn compile-register
  []
  (let [input (:ast-as-text @app-state)]
    (try
      (let [forms (reader/read-string (str "[" input "]"))
            results (mapv (fn [ast]
                            (let [datoms (vm/ast->datoms ast)
                                  asm (register/ast-datoms->asm datoms)
                                  result (register/assembly->bytecode asm)
                                  bytecode (:bytecode result)
                                  pool (:pool result)
                                  source-map (:source-map result)]
                              {:asm asm,
                               :bytecode bytecode,
                               :pool pool,
                               :source-map source-map}))
                      forms)
            ;; Initialize VM state from last compiled result
            last-result (last results)
            initial-state (when last-result
                            (-> (register/create vm/primitives)
                                (proto/load-program
                                  {:bytecode (:bytecode last-result),
                                   :pool (:pool last-result)})))]
        (swap! app-state assoc
          :register-asm (mapv :asm results)
          :register-bytecode (mapv :bytecode results)
          :register-pool (mapv :pool results)
          :register-source-map (mapv :source-map results)
          :error nil)
        ;; Initialize stepping state
        (swap! app-state assoc-in [:vm-states :register :state] initial-state)
        (swap! app-state assoc-in [:vm-states :register :running] false)
        results)
      (catch js/Error e
        (swap! app-state assoc
          :error (str "Register Compile Error: " (.-message e))
          :register-asm nil
          :register-bytecode nil)
        (swap! app-state assoc-in [:vm-states :register :state] nil)
        nil))))


(defn step-register
  "Execute one instruction in the register VM."
  []
  (let [state (get-in @app-state [:vm-states :register :state])]
    (when (and state (not (:halted state)))
      (try
        (let [new-state (register/step-bc state)]
          (swap! app-state assoc-in [:vm-states :register :state] new-state)
          (when (:halted new-state)
            (swap! app-state assoc :register-result (:value new-state))
            (swap! app-state assoc-in [:vm-states :register :running] false)))
        (catch js/Error e
          (swap! app-state assoc
            :error
            (str "Register VM Step Error: " (.-message e)))
          (swap! app-state assoc-in [:vm-states :register :running] false))))))


(defn reset-register
  "Reset register VM to initial state from compiled bytecode."
  []
  (let [bytecode (last (:register-bytecode @app-state))
        pool (last (:register-pool @app-state))]
    (when (and bytecode pool)
      (let [initial-state (register/make-bc-state {:bytecode bytecode,
                                                   :pool pool}
                                                  vm/primitives)]
        (swap! app-state assoc-in [:vm-states :register :state] initial-state)
        (swap! app-state assoc-in [:vm-states :register :running] false)
        (swap! app-state assoc :register-result nil)))))


(declare run-register-loop)


(defn toggle-run-register
  "Toggle auto-stepping for register VM."
  []
  (let [running (get-in @app-state [:vm-states :register :running])]
    (swap! app-state assoc-in [:vm-states :register :running] (not running))
    (when (not running) (run-register-loop))))


(defn run-register-loop
  "Auto-step loop for register VM using requestAnimationFrame."
  []
  (let [running (get-in @app-state [:vm-states :register :running])
        initial-state (get-in @app-state [:vm-states :register :state])]
    (when (and running initial-state (not (:halted initial-state)))
      (try
        (loop [i 0
               state initial-state]
          (if (or (>= i steps-per-frame) (:halted state))
            (do (swap! app-state assoc-in [:vm-states :register :state] state)
                (if (:halted state)
                  (do (swap! app-state assoc :register-result (:value state))
                      (swap! app-state assoc-in
                        [:vm-states :register :running]
                        false))
                  (js/requestAnimationFrame run-register-loop)))
            (recur (inc i) (register/step-bc state))))
        (catch js/Error e
          (swap! app-state assoc
            :error
            (str "Register VM Error: " (.-message e)))
          (swap! app-state assoc-in [:vm-states :register :running] false))))))


(defn compile-source
  []
  (let [input (:source-code @app-state)
        lang (:source-lang @app-state)]
    (try
      (let [asts (case lang
                   :clojure (let [forms (reader/read-string
                                          (str "[" input "]"))]
                              [(yang/compile-program forms)])
                   :python (let [ast (py/compile input)] [ast]))
            last-ast (last asts)
            ast-with-ids (add-yin-ids last-ast)
            {:keys [text source-map]} (ast->text-with-map ast-with-ids)]
        (swap! app-state assoc
          :ast-as-text text
          :walker-source-map source-map
          :error nil)
        ;; Initialize AST Walker state
        (let [initial-env vm/primitives
              vm (walker/create initial-env)
              vm-loaded (proto/load-program vm ast-with-ids)]
          (swap! app-state assoc-in [:vm-states :walker :state] vm-loaded)
          (swap! app-state assoc-in [:vm-states :walker :running] false)
          (swap! app-state assoc :walker-result nil)))
      (catch js/Error e
        (swap! app-state assoc :error (str "Compile Error: " (.-message e)))))))


(def code-examples
  [{:name "Clojure: Basic Math", :lang :clojure, :code "(+ 10 20)"}
   {:name "Clojure: Factorial",
    :lang :clojure,
    :code
      "(def fact (fn [n]\n  (if (= n 0)\n    1\n    (* n (fact (- n 1))))))\n(fact 5)"}
   {:name "Clojure: Fibonacci",
    :lang :clojure,
    :code
      "(def fib (fn [n]\n  (if (< n 2)\n    n\n    (+ (fib (- n 1)) (fib (- n 2))))))\n(fib 7)"}
   {:name "Python: Basic Math", :lang :python, :code "10 + 20"}
   {:name "Python: Factorial",
    :lang :python,
    :code
      "def fact(n):\n  if n == 0:\n    return 1\n  else:\n    return n * fact(n-1)\nfact(5)"}
   {:name "Python: Fibonacci",
    :lang :python,
    :code
      "def fib(n):\n  if n < 2:\n    return n\n  else:\n    return fib(n-1) + fib(n-2)\nfib(7)"}])


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


(defn vm-control-buttons
  "Render Step/Run/Reset buttons for a VM."
  [{:keys [vm-key step-fn toggle-run-fn reset-fn]}]
  (let [vm-state (get-in @app-state [:vm-states vm-key])
        running (:running vm-state)
        state (:state vm-state)
        halted (when state
                 (try (if (satisfies? proto/IVMStep state)
                        (proto/halted? state)
                        (or (:halted state) false))
                      (catch js/Error _ (or (:halted state) false))))]
    [:div {:style {:display "flex", :gap "5px", :margin-top "5px"}}
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


(defn stack-vm-state-display
  "Display current state of stack VM."
  []
  (let [vm-state (get-in @app-state [:vm-states :stack])
        state (:state vm-state)
        expanded (:expanded vm-state)]
    (when state
      [:div
       {:style {:margin-top "5px",
                :padding "5px",
                :background "#0d1117",
                :border "1px solid #30363d",
                :font-size "11px",
                :font-family "monospace",
                :overflow "auto",
                :max-height "150px"}}
       [:div
        {:style {:display "flex",
                 :justify-content "space-between",
                 :align-items "center"}}
        [:span {:style {:color "#8b949e"}}
         (if (:halted state) "HALTED" (str "pc: " (:pc state)))]
        [:button
         {:on-click
            #(swap! app-state update-in [:vm-states :stack :expanded] not),
          :style {:background "none",
                  :border "none",
                  :color "#58a6ff",
                  :cursor "pointer",
                  :font-size "10px"}} (if expanded "Collapse" "Expand")]]
       [:div {:style {:color "#c5c6c7"}} "Stack: "
        (pr-str (take-last 3 (:stack state)))
        (when (> (count (:stack state)) 3) " ...")]
       (when expanded
         [:div {:style {:margin-top "5px", :color "#8b949e"}}
          [:div "Full stack: " (pr-str (:stack state))]
          [:div "Env: " (pr-str (keys (:env state)))]
          [:div "Call stack depth: " (count (:call-stack state))]])])))


(defn register-vm-state-display
  "Display current state of register VM."
  []
  (let [vm-state (get-in @app-state [:vm-states :register])
        state (:state vm-state)
        expanded (:expanded vm-state)]
    (when state
      [:div
       {:style {:margin-top "5px",
                :padding "5px",
                :background "#0d1117",
                :border "1px solid #30363d",
                :font-size "11px",
                :font-family "monospace",
                :overflow "auto",
                :max-height "150px"}}
       [:div
        {:style {:display "flex",
                 :justify-content "space-between",
                 :align-items "center"}}
        [:span {:style {:color "#8b949e"}}
         (if (:halted state) "HALTED" (str "ip: " (:ip state)))]
        [:button
         {:on-click
            #(swap! app-state update-in [:vm-states :register :expanded] not),
          :style {:background "none",
                  :border "none",
                  :color "#58a6ff",
                  :cursor "pointer",
                  :font-size "10px"}} (if expanded "Collapse" "Expand")]]
       [:div {:style {:color "#c5c6c7"}} "Regs: "
        (let [regs (:regs state)
              active (filter (fn [[i v]] (some? v)) (map-indexed vector regs))]
          (pr-str (into {} (take 4 active))))]
       (when expanded
         [:div {:style {:margin-top "5px", :color "#8b949e"}}
          [:div "All regs: " (pr-str (:regs state))]
          [:div "Env: " (pr-str (keys (:env state)))]
          [:div "Continuation: " (if (:k state) "yes" "none")]])])))


(defn semantic-vm-state-display
  "Display current state of semantic VM."
  []
  (let [vm-state (get-in @app-state [:vm-states :semantic])
        state (:state vm-state)
        expanded (:expanded vm-state)]
    (when state
      [:div
       {:style {:margin-top "5px",
                :padding "5px",
                :background "#0d1117",
                :border "1px solid #30363d",
                :font-size "11px",
                :font-family "monospace",
                :overflow "auto",
                :max-height "150px"}}
       [:div
        {:style {:display "flex",
                 :justify-content "space-between",
                 :align-items "center"}}
        [:span {:style {:color "#8b949e"}}
         (if (:halted state)
           "HALTED"
           (let [ctrl (:control state)]
             (if (= :node (:type ctrl))
               (str "Node: " (:id ctrl))
               (str "Val: " (pr-str (:val ctrl))))))]
        [:button
         {:on-click
            #(swap! app-state update-in [:vm-states :semantic :expanded] not),
          :style {:background "none",
                  :border "none",
                  :color "#58a6ff",
                  :cursor "pointer",
                  :font-size "10px"}} (if expanded "Collapse" "Expand")]]
       (when (and state (not (:halted state)))
         (let [ctrl (:control state)
               info (if (= :node (:type ctrl))
                      (let [attrs (semantic/get-node-attrs (:datoms state)
                                                           (:id ctrl))]
                        (str (:yin/type attrs)))
                      "Returning...")]
           [:div {:style {:color "#c5c6c7"}} info]))
       (when expanded
         [:div {:style {:margin-top "5px", :color "#8b949e"}}
          [:div "Env: " (pr-str (keys (:env state)))]
          [:div "Stack depth: " (count (:stack state))]])])))


(defn ast-walker-state-display
  "Display current state of AST Walker VM."
  []
  (let [vm-state (get-in @app-state [:vm-states :walker])
        state (:state vm-state)
        expanded (:expanded vm-state)]
    (when state
      [:div
       {:style {:margin-top "5px",
                :padding "5px",
                :background "#0d1117",
                :border "1px solid #30363d",
                :font-size "11px",
                :font-family "monospace",
                :overflow "auto",
                :max-height "150px"}}
       [:div
        {:style {:display "flex",
                 :justify-content "space-between",
                 :align-items "center"}}
        [:span {:style {:color "#8b949e"}}
         (cond (proto/halted? state) "HALTED"
               (:control state) (str "Control: "
                                     (or (get-in state [:control :type])
                                         (pr-str (:control state))))
               (:continuation state)
                 (str "Cont: "
                      (or (get-in state [:continuation :type]) "pending"))
               :else "Returning...")]
        [:button
         {:on-click
            #(swap! app-state update-in [:vm-states :walker :expanded] not),
          :style {:background "none",
                  :border "none",
                  :color "#58a6ff",
                  :cursor "pointer",
                  :font-size "10px"}} (if expanded "Collapse" "Expand")]]
       (when (and state (not (proto/halted? state)))
         (let [ctrl (:control state)]
           (when ctrl [:div {:style {:color "#c5c6c7"}} (pr-str ctrl)])))
       (when expanded
         [:div {:style {:margin-top "5px", :color "#8b949e"}}
          [:div "Env keys: " (pr-str (keys (:environment state)))]
          [:div "Continuation depth: "
           (loop [c (:continuation state)
                  depth 0]
             (if c (recur (:parent c) (inc depth)) depth))]
          [:div "Value: " (pr-str (:value state))]])])))


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
        from (get positions from-id)
        to (get positions to-id)]
    (when (and from to)
      (let [from-collapsed? (contains? collapsed from-id)
            vertical? (and (= from-id :semantic) (= to-id :query))
            from-h (if from-collapsed? 35 (:h from))
            start-x (if vertical?
                      (+ (:x from) (/ (:w from) 2))
                      (+ (:x from) (:w from)))
            start-y
              (if vertical? (+ (:y from) from-h) (+ (:y from) (/ from-h 2)))
            end-x (if vertical? (+ (:x to) (/ (:w to) 2)) (:x to))
            end-y (if vertical? (:y to) (+ (:y to) 20))
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
         [:circle {:cx end-x, :cy end-y, :r "4", :fill "#444c56"}]
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
                     :color "#c5c6c7"}} label]]]]))))


(defn handle-mouse-move
  [e]
  (let [drag (:drag-state @app-state)
        resize (:resize-state @app-state)]
    (cond drag (let [dx (- (.-clientX e) (:start-x drag))
                     dy (- (.-clientY e) (:start-y drag))
                     new-x (+ (:x (:initial-pos drag)) dx)
                     new-y (+ (:y (:initial-pos drag)) dy)]
                 (swap! app-state assoc-in [:ui-positions (:id drag) :x] new-x)
                 (swap! app-state assoc-in [:ui-positions (:id drag) :y] new-y))
          resize
            (let [dx (- (.-clientX e) (:start-x resize))
                  dy (- (.-clientY e) (:start-y resize))
                  new-w (max 200 (+ (:w (:initial-pos resize)) dx))
                  new-h (max 150 (+ (:h (:initial-pos resize)) dy))]
              (swap! app-state assoc-in [:ui-positions (:id resize) :w] new-w)
              (swap! app-state assoc-in
                [:ui-positions (:id resize) :h]
                new-h)))))


(defn handle-mouse-up
  [e]
  (swap! app-state assoc :drag-state nil :resize-state nil))


(defn datom-list-view
  [datoms active-id]
  [:div
   {:style {:flex "1",
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
   {:style {:flex "1",
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


(defn main-view
  []
  (r/create-class
    {:component-did-mount
       (fn []
         (js/window.addEventListener "mousemove" handle-mouse-move)
         (js/window.addEventListener "mouseup" handle-mouse-up)),
     :component-will-unmount
       (fn []
         (js/window.removeEventListener "mousemove" handle-mouse-move)
         (js/window.removeEventListener "mouseup" handle-mouse-up)),
     :reagent-render
       (fn []
         (let [asm-vm-state (get-in @app-state [:vm-states :semantic :state])
               asm-control (:control asm-vm-state)
               active-asm-id (when (= :node (:type asm-control))
                               (:id asm-control))
               ;; Register VM state
               reg-state (get-in @app-state [:vm-states :register :state])
               reg-ip (:ip reg-state)
               reg-asm (last (:register-asm @app-state))
               reg-map (last (:register-source-map @app-state))
               active-reg-idx (get reg-map reg-ip)
               ;; Stack VM state
               stack-state (get-in @app-state [:vm-states :stack :state])
               stack-pc (:pc stack-state)
               stack-asm (last (:stack-asm @app-state))
               stack-map (last (:stack-source-map @app-state))
               active-stack-idx (get stack-map stack-pc)]
           [:div
            {:style {:width "100%",
                     :height "100vh",
                     :position "relative",
                     :overflow "hidden",
                     :background "#060817",
                     :color "#c5c6c7"}}
            [:h1
             {:style {:margin "20px", :pointer-events "none", :color "#f1f5ff"}}
             "Datomworld Yin VM Compilation Pipeline"]
            [:svg
             {:style {:position "absolute",
                      :top 0,
                      :left 0,
                      :width "100%",
                      :height "100%",
                      :pointer-events "none",
                      :z-index 0}}
             [connection-line :source :walker "AST ->" compile-source]
             [connection-line :walker :semantic "Sem ->" compile-ast]
             [connection-line :semantic :register "Reg ->" compile-register]
             [connection-line :semantic :stack "Stack ->" compile-stack]
             [connection-line :semantic :query "d/q ->" run-query]]
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
                [:option {:value "python"} "Python"]] [hamburger-menu]]
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
                   walker-range (get (:walker-source-map @app-state) walker-id)]
               [:div
                {:style {:display "flex",
                         :flex-direction "column",
                         :flex "1",
                         :overflow "hidden"}}
                [codemirror-editor
                 {:value (:ast-as-text @app-state),
                  :highlight-range walker-range,
                  :on-change (fn [v] (swap! app-state assoc :ast-as-text v))}]
                [vm-control-buttons
                 {:vm-key :walker,
                  :step-fn step-walker,
                  :toggle-run-fn toggle-run-walker,
                  :reset-fn reset-walker}] [ast-walker-state-display]
                (let [vm-state (get-in @app-state [:vm-states :walker :state])]
                  (when (and vm-state (proto/halted? vm-state))
                    [:div
                     {:style {:marginTop "5px",
                              :background "#0d1117",
                              :padding "5px",
                              :border "1px solid #30363d"}} [:strong "Result: "]
                     (pr-str (proto/value vm-state))]))])]
            [draggable-card :semantic "Semantic VM"
             [:div
              {:style {:display "flex",
                       :flex-direction "column",
                       :flex "1",
                       :overflow "hidden"}}
              [datom-list-view (:datoms @app-state) active-asm-id]
              [vm-control-buttons
               {:vm-key :semantic,
                :step-fn step-semantic,
                :toggle-run-fn toggle-run-semantic,
                :reset-fn reset-semantic}] [semantic-vm-state-display]
              (when (and asm-vm-state (:halted asm-vm-state))
                [:div
                 {:style {:marginTop "5px",
                          :background "#0d1117",
                          :padding "5px",
                          :border "1px solid #30363d",
                          :overflow "auto",
                          :max-height "150px"}} [:strong "Result: "]
                 (pr-str (:value asm-vm-state))])]]
            [draggable-card :register "Register VM"
             [:div
              {:style {:display "flex",
                       :flex-direction "column",
                       :flex "1",
                       :overflow "hidden"}}
              [instruction-list-view reg-asm active-reg-idx]
              [vm-control-buttons
               {:vm-key :register,
                :step-fn step-register,
                :toggle-run-fn toggle-run-register,
                :reset-fn reset-register}] [register-vm-state-display]
              (when (and reg-state (:halted reg-state))
                [:div
                 {:style {:marginTop "5px",
                          :background "#0d1117",
                          :padding "5px",
                          :border "1px solid #30363d",
                          :overflow "auto",
                          :max-height "150px"}} [:strong "Result: "]
                 (pr-str (:value reg-state))])]]
            [draggable-card :stack "Stack VM"
             [:div
              {:style {:display "flex",
                       :flex-direction "column",
                       :flex "1",
                       :overflow "hidden"}}
              [instruction-list-view stack-asm active-stack-idx]
              [vm-control-buttons
               {:vm-key :stack,
                :step-fn step-stack,
                :toggle-run-fn toggle-run-stack,
                :reset-fn reset-stack}] [stack-vm-state-display]
              (when (and stack-state (:halted stack-state))
                [:div
                 {:style {:marginTop "5px",
                          :background "#0d1117",
                          :padding "5px",
                          :border "1px solid #30363d",
                          :overflow "auto",
                          :max-height "150px"}} [:strong "Result: "]
                 (pr-str (:value stack-state))])]]
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
               (:error @app-state)])]))}))


(defn init
  []
  (let [app (js/document.getElementById "app")] (rdom/render [main-view] app)))
