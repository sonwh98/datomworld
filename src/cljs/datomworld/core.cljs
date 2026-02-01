(ns datomworld.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [yin.vm :as vm]
            [yin.assembly :as asm]
            [yang.clojure :as yang]
            [yang.python :as py]
            [cljs.reader :as reader]
            [cljs.pprint :as pprint]
            ["codemirror" :refer [basicSetup EditorView]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/theme-one-dark" :refer [oneDark]]
            ["@nextjournal/lang-clojure" :refer [clojure]]
            ["@codemirror/lang-python" :refer [python]]))

(defn pretty-print [data]
  (binding [pprint/*print-right-margin* 60]
    (with-out-str (pprint/pprint data))))

(defonce app-state (r/atom {:source-lang :clojure
                            :source-code "(+ 4 5)"
                            :ast-as-text ""
                            :result nil
                            :assembly-result nil
                            :compiled nil
                            :assembly-stats nil
                            :register-asm nil
                            :register-bc nil
                            :register-result nil
                            :stack-asm nil
                            :stack-bc nil
                            :stack-result nil
                            :error nil}))

(defn codemirror-editor [{:keys [value on-change read-only language]}]
  (let [view-ref (r/atom nil)
        el-ref (atom nil)]
    (r/create-class
     {:display-name "codemirror-editor"
      :component-did-mount
      (fn [this]
        (when-let [node @el-ref]
          (let [lang-ext (if (= language :python) (python) (clojure))
                extensions (cond-> #js [basicSetup lang-ext oneDark]
                             read-only (.concat #js [(.of (.-editable EditorView) false)])
                             on-change (.concat #js [(.of (.-updateListener EditorView)
                                                          (fn [^js update]
                                                            (when (and (.-docChanged update) on-change)
                                                              (on-change (.. update -state -doc toString)))))]))
                state (.create EditorState #js {:doc value :extensions extensions})
                view (new EditorView #js {:state state :parent node})]
            (reset! view-ref view))))
      :component-did-update
      (fn [this old-argv]
        (let [{:keys [value language]} (r/props this)
              {old-language :language} (second old-argv)]
          (when-let [view @view-ref]
            (let [current-value (.. view -state -doc toString)]
              (when (not= value current-value)
                (.dispatch view #js {:changes #js {:from 0
                                                   :to (.. view -state -doc -length)
                                                   :insert value}})))
            ;; Reconfigure if language changed (simple re-mount strategy is easier but let's try just updating doc first)
            ;; Actually re-mounting is safer for language change
            )))
      :component-will-unmount
      (fn [this]
        (when-let [view @view-ref]
          (.destroy view)))
      :reagent-render
      (fn [{:keys [style]}]
        [:div {:ref #(reset! el-ref %)
               :style (merge {:height "300px" :width "100%" :border "1px solid #ccc" :overflow "auto"} style)}])})))

(defn evaluate-ast []
  (let [input (:ast-as-text @app-state)]
    (try
      ;; Wrap input in a vector to read multiple forms
      (let [forms (reader/read-string (str "[" input "]"))
            initial-env vm/primitives
            initial-state (vm/make-state initial-env)
            ;; Evaluate each form sequentially, threading the state
            final-state (reduce (fn [state form]
                                  (vm/run state form))
                                initial-state
                                forms)]
        ;; Update state with the result of the last form
        (swap! app-state assoc :result (:value final-state) :error nil))
      (catch js/Error e
        (swap! app-state assoc :error (.-message e) :result nil)))))

(defn compile-stack []
  (let [input (:ast-as-text @app-state)]
    (try
      (let [forms (reader/read-string (str "[" input "]"))
            results (mapv (fn [ast]
                            (let [datoms (vm/ast->datoms ast)
                                  asm (asm/ast-datoms->stack-assembly datoms)
                                  [bc pool] (asm/stack-assembly->bytecode asm)]
                              {:asm asm :bc bc :pool pool}))
                          forms)]
        (swap! app-state assoc
               :stack-asm (mapv :asm results)
               :stack-bc (mapv :bc results)
               :stack-pool (mapv :pool results)
               :error nil)
        results)
      (catch js/Error e
        (swap! app-state assoc :error (str "Stack Compile Error: " (.-message e))
               :stack-asm nil :stack-bc nil)
        nil))))

(defn run-stack []
  (let [compiled (:stack-bc @app-state)
        pools (:stack-pool @app-state)
        ;; If not compiled yet, try to compile
        results (if (seq compiled)
                  (mapv vector compiled pools)
                  (mapv (juxt :bc :pool) (compile-stack)))]
    (when (seq results)
      (try
        (let [initial-env vm/primitives
              ;; Run stack-based numeric bytecode
              run-results (mapv (fn [[bytes pool]]
                                  (asm/run-bytes bytes pool initial-env))
                                results)]
          (swap! app-state assoc :stack-result (last run-results) :error nil))
        (catch js/Error e
          (swap! app-state assoc :error (str "Stack VM Error: " (.-message e)) :stack-result nil))))))

(defn compile-ast []
  (let [input (:ast-as-text @app-state)]
    (try
      (let [forms (reader/read-string (str "[" input "]"))
            ;; Reset node counter for consistent IDs
            _ (asm/reset-node-counter!)
            ;; Compile to semantic bytecode (datoms)
            compiled-results (mapv asm/compile forms)
            ;; Compute stats for all compiled forms
            all-datoms (mapcat :datoms compiled-results)
            stats {:total-datoms (count all-datoms)
                   :lambdas (count (asm/find-lambdas all-datoms))
                   :applications (count (asm/find-applications all-datoms))
                   :variables (count (asm/find-variables all-datoms))
                   :literals (count (asm/find-by-type all-datoms :literal))}]
        (swap! app-state assoc
               :compiled compiled-results
               :assembly-stats stats
               :error nil)
        compiled-results)
      (catch js/Error e
        (swap! app-state assoc :error (.-message e) :compiled nil :assembly-stats nil)
        nil))))

(defn run-assembly []
  (let [compiled (:compiled @app-state)
        ;; If not compiled yet, try to compile
        compiled-results (or compiled (compile-ast))]
    (when (seq compiled-results)
      (try
        (let [initial-env vm/primitives
              ;; Run semantic bytecode (traverses datom graph by node reference)
              results (mapv (fn [compiled]
                              (asm/run-semantic compiled initial-env))
                            compiled-results)]
          (swap! app-state assoc :assembly-result (last results) :error nil))
        (catch js/Error e
          (swap! app-state assoc :error (str "Assembly Error: " (.-message e)) :assembly-result nil))))))

(defn compile-register []
  (let [input (:ast-as-text @app-state)]
    (try
      (let [forms (reader/read-string (str "[" input "]"))
            results (mapv (fn [ast]
                            (let [datoms (vm/ast->datoms ast)
                                  asm (vm/ast-datoms->register-assembly datoms)
                                  bc (vm/register-assembly->bytecode asm)]
                              {:asm asm :bc bc}))
                          forms)]
        (swap! app-state assoc
               :register-asm (mapv :asm results)
               :register-bc (mapv :bc results)
               :error nil)
        results)
      (catch js/Error e
        (swap! app-state assoc :error (str "Register Compile Error: " (.-message e))
               :register-asm nil :register-bc nil)
        nil))))

(defn run-register []
  (let [compiled (:register-bc @app-state)
        ;; If not compiled yet, try to compile
        compiled-results (or compiled (map :bc (compile-register)))]
    (when (seq compiled-results)
      (try
        (let [initial-env vm/primitives
              ;; Run register-based numeric bytecode
              results (mapv (fn [bc-data]
                              (let [state (vm/make-rbc-bc-state bc-data initial-env)
                                    final-state (vm/rbc-run-bc state)]
                                (:value final-state)))
                            compiled-results)]
          (swap! app-state assoc :register-result (last results) :error nil))
        (catch js/Error e
          (swap! app-state assoc :error (str "Register VM Error: " (.-message e)) :register-result nil))))))

(defn compile-source []
  (let [input (:source-code @app-state)
        lang (:source-lang @app-state)]
    (try
      (let [asts (case lang
                   :clojure (let [forms (reader/read-string (str "[" input "]"))]
                              (mapv yang/compile forms))
                   :python (let [ast (py/compile input)]
                             [ast]))
            ast-strings (map pretty-print asts)
            result-text (clojure.string/join "\n" ast-strings)]
        (swap! app-state assoc :ast-as-text result-text :error nil))
      (catch js/Error e
        (swap! app-state assoc :error (str "Compile Error: " (.-message e)))))))

(defn hello-world []
  [:div {:style {:padding "20px" :font-family "sans-serif"}}
   [:h1 "Datomworld Yin VM Explorer"]

   [:div {:style {:display "flex" :flex-direction "row" :align-items "flex-start" :margin-bottom "20px" :overflow-x "auto"}}
    ;; Column 1: Source Code
    [:div {:style {:min-width "350px" :flex "1 0 auto" :margin-right "10px"}}
     [:div {:style {:display "flex" :justify-content "space-between" :align-items "center"}}
      [:label {:style {:font-weight "bold"}} "Source Code:"]
      [:select {:value (:source-lang @app-state)
                :on-change #(swap! app-state assoc :source-lang (keyword (.. % -target -value)))
                :style {:margin-bottom "5px"}}
       [:option {:value "clojure"} "Clojure"]
       [:option {:value "python"} "Python"]]]
     [codemirror-editor {:key (:source-lang @app-state) ;; Force remount on language change
                         :value (:source-code @app-state)
                         :language (:source-lang @app-state)
                         :on-change #(swap! app-state assoc :source-code %)}]]

    ;; Arrow 1: Source -> AST
    [:div {:style {:display "flex" :flex-direction "column" :justify-content "center" :height "300px" :margin-right "10px"}}
     [:button {:on-click compile-source :style {:padding "5px"}}
      "AST ->"]]

            ;; Column 2: Yin AST

    [:div {:style {:min-width "380px" :flex "1 0 auto" :margin-right "10px"}}

     [:label {:style {:font-weight "bold"}} "Yin AST (EDN):"]

     [:br]

     [codemirror-editor {:value (:ast-as-text @app-state)

                         :on-change #(swap! app-state assoc :ast-as-text %)}]

     [:button {:on-click evaluate-ast :style {:margin-top "5px"}}

      "Run AST"]

     (when (contains? @app-state :result)
       [:div {:style {:margin-top "10px"}}
        [:strong {:style {:color "#333"}} "AST Eval Result:"]
        [:pre {:style {:background "#eee" :color "#111" :padding "10px" :border-radius "4px"}}
         (pr-str (:result @app-state))]])]

            ;; Arrow 2: AST -> Assembly
    [:div {:style {:display "flex" :flex-direction "column" :justify-content "center" :height "300px" :margin-right "10px"}}
     [:button {:on-click compile-ast :style {:padding "5px"}}
      "Asm ->"]]

            ;; Column 3: Compiled Assembly
    [:div {:style {:min-width "400px" :flex "1 0 auto" :margin-right "10px"}}
     [:label {:style {:font-weight "bold"}} "Assembly (Datoms):"]
     [:br]
     [codemirror-editor {:value (if-let [compiled (:compiled @app-state)]
                                  (pretty-print (mapv :datoms compiled))
                                  "")
                         :read-only true}]
     [:button {:on-click run-assembly :style {:margin-top "5px"}}
      "Run Assembly"]
     (when (contains? @app-state :assembly-result)
       [:div {:style {:margin-top "10px"}}
        [:strong {:style {:color "#333"}} "Assembly Run Result:"]
        [:pre {:style {:background "#eef" :color "#111" :padding "10px" :border-radius "4px"}}
         (pr-str (:assembly-result @app-state))]])
     [:div {:style {:font-size "0.8em" :color "#666" :margin-top "5px"}}
      "Semantic assembly (Datoms). Queryable."]]

            ;; Arrow 3: Assembly -> VM targets
    [:div {:style {:display "flex" :flex-direction "column" :justify-content "center" :height "300px" :margin-right "10px"}}
     [:button {:on-click compile-register :style {:padding "5px" :margin-bottom "5px"}}
      "Reg ->"]
     [:button {:on-click compile-stack :style {:padding "5px"}}
      "Stack ->"]]

            ;; Column 4: Register VM
    [:div {:style {:min-width "350px" :flex "1 0 auto" :margin-right "10px"}}
     [:label {:style {:font-weight "bold"}} "Register VM:"]
     [:br]
     [codemirror-editor {:value (let [asm (:register-asm @app-state)
                                      bc (:register-bc @app-state)]
                                  (cond
                                    (and asm bc)
                                    (str "--- REG ASM ---\n" (pretty-print asm)
                                         "\n--- BYTECODE ---\n" (pretty-print bc))
                                    asm (pretty-print asm)
                                    :else ""))
                         :read-only true}]
     [:button {:on-click run-register :style {:margin-top "5px"}}
      "Run Register VM"]
     (when (contains? @app-state :register-result)
       [:div {:style {:margin-top "10px"}}
        [:strong {:style {:color "#333"}} "Reg VM Result:"]
        [:pre {:style {:background "#efe" :color "#111" :padding "10px" :border-radius "4px"}}
         (pr-str (:register-result @app-state))]])
     [:div {:style {:font-size "0.8em" :color "#666" :margin-top "5px"}}
      "Register-based assembly & bytecode."]]

            ;; Column 5: Stack VM
    [:div {:style {:min-width "350px" :flex "1 0 auto"}}
     [:label {:style {:font-weight "bold"}} "Stack VM:"]
     [:br]
     [codemirror-editor {:value (let [asm (:stack-asm @app-state)
                                      bc (:stack-bc @app-state)]
                                  (cond
                                    (and asm bc)
                                    (str "--- STACK ASM ---\n" (pretty-print asm)
                                         "\n--- BYTECODE ---\n" (pretty-print bc))
                                    asm (pretty-print asm)
                                    :else ""))
                         :read-only true}]
     [:button {:on-click run-stack :style {:margin-top "5px"}}
      "Run Stack VM"]
     (when (contains? @app-state :stack-result)
       [:div {:style {:margin-top "10px"}}
        [:strong {:style {:color "#333"}} "Stack VM Result:"]
        [:pre {:style {:background "#fee" :color "#111" :padding "10px" :border-radius "4px"}}
         (pr-str (:stack-result @app-state))]])
     [:div {:style {:font-size "0.8em" :color "#666" :margin-top "5px"}}
      "Stack-based assembly & bytecode."]]]

   (when-let [error (:error @app-state)]

     [:div {:style {:color "red"}}

      [:h3 "Error:"]

      [:pre error]])

   [:div {:style {:margin-top "20px" :font-size "0.8em"}}

    [:h4 "Examples:"]
    [:ul
     [:li [:a {:href "#" :on-click #(swap! app-state assoc :source-code "(+ 10 20)" :source-lang :clojure)} "Clojure: (+ 10 20)"]]
     [:li [:a {:href "#" :on-click #(swap! app-state assoc :source-code "10 + 20" :source-lang :python)} "Python: 10 + 20"]]
     [:li [:a {:href "#" :on-click #(swap! app-state assoc :source-code "(fn [x] (+ x 1))" :source-lang :clojure)} "Clojure: (fn [x] (+ x 1))"]]
     [:li [:a {:href "#" :on-click #(swap! app-state assoc :source-code "lambda x: x + 1" :source-lang :python)} "Python: lambda x: x + 1"]]
     [:li [:a {:href "#" :on-click #(swap! app-state assoc :source-code "(def fib (fn [n]\n           (if (< n 2)\n             n\n             (+ (fib (- n 1)) (fib (- n 2))))))\n(fib 7)" :source-lang :clojure)} "Clojure: Fibonacci"]]
     [:li [:a {:href "#" :on-click #(swap! app-state assoc :source-code "def fib(n):\n  if n < 2:\n    return n\n  else:\n    return fib(n-1) + fib(n-2)\nfib(7)" :source-lang :python)} "Python: Fibonacci"]]]]])
(defn init []
  (let [app (js/document.getElementById "app")]
    (rdom/render [hello-world] app)))