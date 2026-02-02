(ns datomworld.core
  (:require ["@codemirror/lang-python" :refer [python]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/theme-one-dark" :refer [oneDark]]
            ["@nextjournal/lang-clojure" :refer [clojure]]
            ["codemirror" :refer [basicSetup EditorView]]
            [cljs.pprint :as pprint]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [datascript.core :as d]
            [reagent.core :as r]
            [reagent.dom :as rdom]
            [yang.clojure :as yang]
            [yang.python :as py]
            [yin.assembly :as asm]
            [yin.vm :as vm]))


(defn pretty-print
  [data]
  (binding [pprint/*print-right-margin* 60]
    (with-out-str (pprint/pprint data))))


(def default-positions
  {:source {:x 20, :y 180, :w 350, :h 450},
   :ast {:x 420, :y 180, :w 380, :h 450},
   :assembly {:x 850, :y 180, :w 400, :h 450},
   :register {:x 1300, :y 180, :w 350, :h 450},
   :stack {:x 1300, :y 650, :w 350, :h 450},
   :query {:x 850, :y 650, :w 400, :h 450}})


(defonce ds-conn (d/create-conn vm/schema))


(defonce app-state
  (r/atom
    {:source-lang :clojure,
     :source-code "(+ 4 5)",
     :ast-as-text "",
     :result nil,
     :assembly-result nil,
     :datoms nil,
     :ds-db nil,
     :root-ids nil,
     :root-eids nil,
     :assembly-stats nil,
     :register-asm nil,
     :register-bc nil,
     :register-result nil,
     :stack-asm nil,
     :stack-bc nil,
     :stack-result nil,
     :query-text "[:find ?e ?type\n :where [?e :yin/type ?type]]",
     :query-result nil,
     :error nil,
     :ui-positions default-positions,
     :drag-state nil,
     :resize-state nil}))


(when (or (nil? (:ui-positions @app-state))
          (not (:h (:source (:ui-positions @app-state))))
          (= 80 (:y (:source (:ui-positions @app-state)))))
  (swap! app-state assoc :ui-positions default-positions))


(defn codemirror-editor
  [{:keys [value on-change read-only language]}]
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
                     (cond-> #js [basicSetup lang-ext oneDark theme]
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
               (reset! view-ref view)))),
       :component-did-update
         (fn [this old-argv]
           (let [{:keys [value language]} (r/props this)]
             (when-let [view @view-ref]
               (let [current-value (.. view -state -doc toString)]
                 (when (not= value current-value)
                   (.dispatch view
                              #js {:changes #js
                                             {:from 0,
                                              :to (.. view -state -doc -length),
                                              :insert value}})))))),
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


(defn evaluate-ast
  []
  (let [input (:ast-as-text @app-state)]
    (try (let [forms (reader/read-string (str "[" input "]"))
               initial-env vm/primitives
               initial-state (vm/make-state initial-env)
               final-state (reduce (fn [state form] (vm/run state form))
                             initial-state
                             forms)]
           (swap! app-state assoc :result (:value final-state) :error nil))
         (catch js/Error e
           (swap! app-state assoc :error (.-message e) :result nil)))))


(defn compile-stack
  []
  (let [input (:ast-as-text @app-state)]
    (try (let [forms (reader/read-string (str "[" input "]"))
               results (mapv (fn [ast]
                               (let [datoms (vm/ast->datoms ast)
                                     asm (asm/ast-datoms->stack-assembly datoms)
                                     [bc pool] (asm/stack-assembly->bytecode
                                                 asm)]
                                 {:asm asm, :bc bc, :pool pool}))
                         forms)]
           (swap! app-state assoc
             :stack-asm (mapv :asm results)
             :stack-bc (mapv :bc results)
             :stack-pool (mapv :pool results)
             :error nil)
           results)
         (catch js/Error e
           (swap! app-state assoc
             :error (str "Stack Compile Error: " (.-message e))
             :stack-asm nil
             :stack-bc nil)
           nil))))


(defn run-stack
  []
  (let [compiled (:stack-bc @app-state)
        pools (:stack-pool @app-state)
        results (if (seq compiled)
                  (mapv vector compiled pools)
                  (mapv (juxt :bc :pool) (compile-stack)))]
    (when (seq results)
      (try
        (let [initial-env vm/primitives
              run-results (mapv (fn [[bytes pool]]
                                  (asm/run-bytes bytes pool initial-env))
                            results)]
          (swap! app-state assoc :stack-result (last run-results) :error nil))
        (catch js/Error e
          (swap! app-state assoc
            :error (str "Stack VM Error: " (.-message e))
            :stack-result nil))))))


(defn compile-ast
  []
  (let [input (:ast-as-text @app-state)]
    (try
      (let [forms (reader/read-string (str "[" input "]"))
            all-datom-groups (mapv vm/ast->datoms forms)
            all-datoms (vec (mapcat identity all-datom-groups))
            root-ids (mapv ffirst all-datom-groups)
            ;; Transact into DataScript
            tx-data (vm/datoms->tx-data all-datoms)
            conn (d/create-conn vm/schema)
            {:keys [tempids]} (d/transact! conn tx-data)
            db @conn
            root-eids (mapv #(get tempids %) root-ids)
            ;; root-ids are tempids for raw datom traversal (run-semantic)
            ;; root-eids are resolved DataScript entity IDs (for d/q)
            stats {:total-datoms (count all-datoms),
                   :lambdas (count (asm/find-lambdas all-datoms)),
                   :applications (count (asm/find-applications all-datoms)),
                   :variables (count (asm/find-variables all-datoms)),
                   :literals (count (asm/find-by-type all-datoms :literal))}]
        (swap! app-state assoc
          :datoms all-datoms
          :ds-db db
          :root-ids root-ids
          :root-eids root-eids
          :assembly-stats stats
          :error nil)
        all-datoms)
      (catch js/Error e
        (swap! app-state assoc
          :error (.-message e)
          :datoms nil
          :ds-db nil
          :root-ids nil
          :root-eids nil
          :assembly-stats nil)
        nil))))


(defn run-assembly
  []
  (let [datoms (:datoms @app-state)
        datoms (or datoms (compile-ast))
        root-ids (:root-ids @app-state)]
    (when (and (seq datoms) (seq root-ids))
      (try (let [initial-env vm/primitives
                 results (mapv (fn [root-id]
                                 (asm/run-semantic {:node root-id,
                                                    :datoms datoms}
                                                   initial-env))
                           root-ids)]
             (swap! app-state assoc :assembly-result (last results) :error nil))
           (catch js/Error e
             (swap! app-state assoc
               :error (str "Assembly Error: " (.-message e))
               :assembly-result nil))))))


(defn run-query
  []
  (let [db (:ds-db @app-state)]
    (if (nil? db)
      (swap! app-state assoc
        :error "No DataScript db. Click \"Asm ->\" first."
        :query-result nil)
      (try (let [query (reader/read-string (:query-text @app-state))
                 result (d/q query db)]
             (swap! app-state assoc :query-result result :error nil))
           (catch js/Error e
             (swap! app-state assoc
               :error (str "Query Error: " (.-message e))
               :query-result nil))))))


(defn compile-register
  []
  (let [input (:ast-as-text @app-state)]
    (try (let [forms (reader/read-string (str "[" input "]"))
               results (mapv (fn [ast]
                               (let [datoms (vm/ast->datoms ast)
                                     asm (vm/ast-datoms->register-assembly
                                           datoms)
                                     bc (vm/register-assembly->bytecode asm)]
                                 {:asm asm, :bc bc}))
                         forms)]
           (swap! app-state assoc
             :register-asm (mapv :asm results)
             :register-bc (mapv :bc results)
             :error nil)
           results)
         (catch js/Error e
           (swap! app-state assoc
             :error (str "Register Compile Error: " (.-message e))
             :register-asm nil
             :register-bc nil)
           nil))))


(defn run-register
  []
  (let [compiled (:register-bc @app-state)
        compiled-results (or compiled (map :bc (compile-register)))]
    (when (seq compiled-results)
      (try (let [initial-env vm/primitives
                 results (mapv (fn [bc-data]
                                 (let [state (vm/make-rbc-bc-state bc-data
                                                                   initial-env)
                                       final-state (vm/rbc-run-bc state)]
                                   (:value final-state)))
                           compiled-results)]
             (swap! app-state assoc :register-result (last results) :error nil))
           (catch js/Error e
             (swap! app-state assoc
               :error (str "Register VM Error: " (.-message e))
               :register-result nil))))))


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
            ast-strings (map pretty-print asts)
            result-text (str/join "\n" ast-strings)]
        (swap! app-state assoc :ast-as-text result-text :error nil))
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


(defn hamburger-menu
  []
  (let [open? (r/atom false)]
    (fn [] [:div {:style {:position "relative"}}
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
                        :width "200px",
                        :margin-top "5px"}}
               (for [ex code-examples]
                 ^{:key (:name ex)}
                 [:div
                  {:on-click (fn []
                               (swap! app-state assoc
                                 :source-code (:code ex)
                                 :source-lang (:lang ex))
                               (reset! open? false)),
                   :style {:padding "8px 12px",
                           :cursor "pointer",
                           :font-size "0.9rem",
                           :color "#c5c6c7",
                           :border-bottom "1px solid #2d3b55"},
                   :on-mouse-over #(set! (.. % -target -style -background)
                                         "#2d3b55"),
                   :on-mouse-out #(set! (.. % -target -style -background)
                                        "none")} (:name ex)])])])))


(defn draggable-card
  [id title content]
  (let [positions (r/cursor app-state [:ui-positions])
        drag-state (r/cursor app-state [:drag-state])
        resize-state (r/cursor app-state [:resize-state])
        pos (get @positions id)
        z-index (cond (= (:id @drag-state) id) 100
                      (= (:id @resize-state) id) 100
                      :else 10)]
    [:div
     {:style {:position "absolute",
              :left (:x pos),
              :top (:y pos),
              :width (:w pos),
              :height (:h pos),
              :min-height "200px",
              :background "#0e1328",
              :border "1px solid #2d3b55",
              :box-shadow "0 10px 25px rgba(0,0,0,0.5)",
              :z-index z-index,
              :color "#c5c6c7",
              :display "flex",
              :flex-direction "column",
              :overflow "hidden"}}
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
                                            :initial-pos pos}))} title]
     [:div
      {:style {:padding "10px",
               :flex "1",
               :display "flex",
               :flex-direction "column",
               :overflow "hidden"}} content]
     ;; Resize handle
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
                                              :initial-pos pos}))}]]))


(defn connection-line
  [from-id to-id label on-click]
  (let [positions (:ui-positions @app-state)
        from (get positions from-id)
        to (get positions to-id)]
    (when (and from to)
      (let [start-x (+ (:x from) (:w from))
            start-y (+ (:y from) 50) ; Header offset approx
            end-x (:x to)
            end-y (+ (:y to) 50)
            dist (Math/abs (- end-x start-x))
            cp1-x (+ start-x (/ dist 2))
            cp1-y start-y
            cp2-x (- end-x (/ dist 2))
            cp2-y end-y
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


(defn hello-world
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
         [:div
          {:style {:width "100%",
                   :height "100vh",
                   :position "relative",
                   :overflow "hidden",
                   :background "#060817",
                   :color "#c5c6c7"}}
          [:h1
           {:style {:margin "20px", :pointer-events "none", :color "#f1f5ff"}}
           "Datomworld Yin VM Explorer"]
          [:svg
           {:style {:position "absolute",
                    :top 0,
                    :left 0,
                    :width "100%",
                    :height "100%",
                    :pointer-events "none",
                    :z-index 0}}
           [connection-line :source :ast "AST ->" compile-source]
           [connection-line :ast :assembly "Asm ->" compile-ast]
           [connection-line :assembly :register "Reg ->" compile-register]
           [connection-line :assembly :stack "Stack ->" compile-stack]
           [connection-line :assembly :query "d/q ->" run-query]]
          [draggable-card :source
           [:div
            {:style {:display "flex",
                     :justify-content "space-between",
                     :align-items "center",
                     :width "100%"}} "Source Code" [hamburger-menu]]
           [:div
            {:style {:display "flex",
                     :flex-direction "column",
                     :flex "1",
                     :overflow "hidden"}}
            [:div
             {:style {:display "flex",
                      :justify-content "space-between",
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
              [:option {:value "python"} "Python"]]]
            [codemirror-editor
             {:key (:source-lang @app-state),
              :value (:source-code @app-state),
              :language (:source-lang @app-state),
              :on-change (fn [v] (swap! app-state assoc :source-code v))}]
            [:div
             {:style {:marginTop "10px", :fontSize "0.8em", :color "#8b949e"}}
             "Select examples from the menu ☰ above."]]]
          [draggable-card :ast "Yin AST"
           [:div
            {:style {:display "flex",
                     :flex-direction "column",
                     :flex "1",
                     :overflow "hidden"}}
            [codemirror-editor
             {:value (:ast-as-text @app-state),
              :on-change (fn [v] (swap! app-state assoc :ast-as-text v))}]
            [:button
             {:on-click evaluate-ast,
              :style {:marginTop "5px",
                      :background "#238636",
                      :color "#fff",
                      :border "none",
                      :padding "5px 10px",
                      :border-radius "4px",
                      :cursor "pointer"}} "Run AST"]
            (when (:result @app-state)
              [:div
               {:style {:marginTop "5px",
                        :background "#0d1117",
                        :padding "5px",
                        :border "1px solid #30363d"}}
               (pr-str (:result @app-state))])]]
          [draggable-card :assembly "Assembly (Datoms)"
           [:div
            {:style {:display "flex",
                     :flex-direction "column",
                     :flex "1",
                     :overflow "hidden"}}
            [codemirror-editor
             {:value (if-let [datoms (:datoms @app-state)]
                       (pretty-print datoms)
                       ""),
              :read-only true}]
            [:button
             {:on-click run-assembly,
              :style {:marginTop "5px",
                      :background "#238636",
                      :color "#fff",
                      :border "none",
                      :padding "5px 10px",
                      :border-radius "4px",
                      :cursor "pointer"}} "Run Assembly"]
            (when (:assembly-result @app-state)
              [:div
               {:style {:marginTop "5px",
                        :background "#0d1117",
                        :padding "5px",
                        :border "1px solid #30363d"}}
               (pr-str (:assembly-result @app-state))])]]
          [draggable-card :register "Register VM"
           [:div
            {:style {:display "flex",
                     :flex-direction "column",
                     :flex "1",
                     :overflow "hidden"}}
            [codemirror-editor
             {:value (str (if (:register-asm @app-state)
                            (pretty-print (:register-asm @app-state))
                            "")
                          (if (:register-bc @app-state)
                            (str "\n--- BC ---\n"
                                 (pretty-print (:register-bc @app-state)))
                            "")),
              :read-only true}]
            [:button
             {:on-click run-register,
              :style {:marginTop "5px",
                      :background "#238636",
                      :color "#fff",
                      :border "none",
                      :padding "5px 10px",
                      :border-radius "4px",
                      :cursor "pointer"}} "Run Reg VM"]
            (when (:register-result @app-state)
              [:div
               {:style {:marginTop "5px",
                        :background "#0d1117",
                        :padding "5px",
                        :border "1px solid #30363d"}}
               (pr-str (:register-result @app-state))])]]
          [draggable-card :stack "Stack VM"
           [:div
            {:style {:display "flex",
                     :flex-direction "column",
                     :flex "1",
                     :overflow "hidden"}}
            [codemirror-editor
             {:value (str (if (:stack-asm @app-state)
                            (pretty-print (:stack-asm @app-state))
                            "")
                          (if (:stack-bc @app-state)
                            (str "\n--- BC ---\n"
                                 (pretty-print (:stack-bc @app-state)))
                            "")),
              :read-only true}]
            [:button
             {:on-click run-stack,
              :style {:marginTop "5px",
                      :background "#238636",
                      :color "#fff",
                      :border "none",
                      :padding "5px 10px",
                      :border-radius "4px",
                      :cursor "pointer"}} "Run Stack VM"]
            (when (:stack-result @app-state)
              [:div
               {:style {:marginTop "5px",
                        :background "#0d1117",
                        :padding "5px",
                        :border "1px solid #30363d"}}
               (pr-str (:stack-result @app-state))])]]
          [draggable-card :query "Datalog Query"
           [:div
            {:style {:display "flex",
                     :flex-direction "column",
                     :flex "1",
                     :overflow "hidden"}}
            [codemirror-editor
             {:value (:query-text @app-state),
              :on-change (fn [v] (swap! app-state assoc :query-text v)),
              :style {:flex "1", :min-height "80px"}}]
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
             (:error @app-state)])])}))


(defn init
  []
  (let [app (js/document.getElementById "app")]
    (rdom/render [hello-world] app)))
