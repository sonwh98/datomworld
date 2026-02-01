(ns datomworld.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [yin.vm :as vm]
            [yin.assembly :as asm]
            [yang.clojure :as yang]
            [cljs.reader :as reader]
            [cljs.pprint :as pprint]
            ["codemirror" :refer [basicSetup EditorView]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/theme-one-dark" :refer [oneDark]]
            ["@nextjournal/lang-clojure" :refer [clojure]]))

(defn pretty-print [data]
  (binding [pprint/*print-right-margin* 60]
    (with-out-str (pprint/pprint data))))

(defonce app-state (r/atom {:clojure-code "(+ 4 5)"
                            :ast-as-text (pretty-print
                                          {:type :application
                                           :operator {:type :lambda
                                                      :params '[x y]
                                                      :body {:type :application
                                                             :operator {:type :variable :name '+}
                                                             :operands [{:type :variable :name 'x}
                                                                        {:type :variable :name 'y}]}}
                                           :operands [{:type :literal :value 4}
                                                      {:type :literal :value 5}]})
                            :result nil
                            :assembly-result nil
                            :compiled nil
                            :assembly-stats nil
                            :register-asm nil
                            :register-bc nil
                            :register-result nil
                            :error nil}))

(defn codemirror-editor [{:keys [value on-change read-only]}]
  (let [view-ref (r/atom nil)
        el-ref (atom nil)]
    (r/create-class
     {:display-name "codemirror-editor"
      :component-did-mount
      (fn [this]
        (when-let [node @el-ref]
          (let [extensions (cond-> #js [basicSetup (clojure) oneDark]
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
        (let [{:keys [value]} (r/props this)]
          (when-let [view @view-ref]
            (let [current-value (.. view -state -doc toString)]
              (when (not= value current-value)
                (.dispatch view #js {:changes #js {:from 0
                                                   :to (.. view -state -doc -length)
                                                   :insert value}}))))))
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

(defn compile-legacy-bytecode []
  (let [input (:ast-as-text @app-state)]
    (try
      (let [forms (reader/read-string (str "[" input "]"))
            ;; Compile to legacy numeric bytecode
            results (mapv asm/compile-legacy forms)]
        (swap! app-state assoc :legacy-compiled results :error nil)
        results)
      (catch js/Error e
        (swap! app-state assoc :error (.-message e) :legacy-compiled nil)
        nil))))

(defn run-legacy-bytecode []
  (let [compiled (:legacy-compiled @app-state)
        ;; If not compiled yet, try to compile
        compiled-results (or compiled (compile-legacy-bytecode))]
    (when (seq compiled-results)
      (try
        (let [initial-env vm/primitives
              ;; Run legacy numeric bytecode
              results (mapv (fn [[bytes pool]]
                              (asm/run-bytes bytes pool initial-env))
                            compiled-results)]
          (swap! app-state assoc :legacy-result (last results) :error nil))
        (catch js/Error e
          (swap! app-state assoc :error (str "Legacy Bytecode Error: " (.-message e)) :legacy-result nil))))))

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

(defn compile-clojure []
  (let [input (:clojure-code @app-state)]
    (try
      (let [forms (reader/read-string (str "[" input "]"))
            asts (mapv yang/compile forms)
            ast-strings (map pretty-print asts)
            result-text (clojure.string/join "\n" ast-strings)]
        (swap! app-state assoc :ast-as-text result-text :error nil))
      (catch js/Error e
        (swap! app-state assoc :error (str "Clojure Compile Error: " (.-message e)))))))

(defn hello-world []
  [:div {:style {:padding "20px" :font-family "sans-serif"}}
   [:h1 "Datomworld Yin VM Explorer"]

   [:div {:style {:display "flex" :flex-direction "row" :align-items "flex-start" :margin-bottom "20px" :overflow-x "auto"}}
    ;; Column 1: Clojure Code
    [:div {:style {:min-width "350px" :flex "1 0 auto" :margin-right "10px"}}
     [:label {:style {:font-weight "bold"}} "Enter Clojure Code:"]
     [:br]
     [codemirror-editor {:value (:clojure-code @app-state)
                         :on-change #(swap! app-state assoc :clojure-code %)}]]

    ;; Arrow 1: Clojure -> AST
    [:div {:style {:display "flex" :flex-direction "column" :justify-content "center" :height "300px" :margin-right "10px"}}
     [:button {:on-click compile-clojure :style {:padding "5px"}}
      "AST ->"]]

    ;; Column 2: Yin AST
    [:div {:style {:min-width "380px" :flex "1 0 auto" :margin-right "10px"}}
     [:label {:style {:font-weight "bold"}} "Yin AST (EDN):"]
     [:br]
     [codemirror-editor {:value (:ast-as-text @app-state)
                         :on-change #(swap! app-state assoc :ast-as-text %)}]]

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
     ;; Show queryable stats
     (when-let [stats (:assembly-stats @app-state)]
       [:div {:style {:font-size "0.8em" :color "#666" :margin-top "5px"
                      :background "#f5f5f5" :padding "8px" :border-radius "4px"}}
        [:strong "Queryable Stats:"]
        [:ul {:style {:margin "5px 0" :padding-left "20px"}}
         [:li "Total datoms: " (:total-datoms stats)]
         [:li "Lambdas: " (:lambdas stats)]
         [:li "Applications: " (:applications stats)]
         [:li "Variables: " (:variables stats)]
         [:li "Literals: " (:literals stats)]]])
     [:div {:style {:font-size "0.8em" :color "#666" :margin-top "5px"}}
      "Semantic assembly preserves meaning and is queryable."]]

    ;; Arrow 3: Assembly -> Register VM
    [:div {:style {:display "flex" :flex-direction "column" :justify-content "center" :height "300px" :margin-right "10px"}}
     [:button {:on-click compile-register :style {:padding "5px"}}
      "Reg ->"]]

    ;; Column 4: Register VM
    [:div {:style {:min-width "400px" :flex "1 0 auto"}}
     [:label {:style {:font-weight "bold"}} "Register VM (Asm & BC):"]
     [:br]
     [codemirror-editor {:value (let [asm (:register-asm @app-state)
                                      bc (:register-bc @app-state)]
                                  (cond
                                    (and asm bc)
                                    (str "--- ASSEMBLY ---\n"
                                         (pretty-print asm)
                                         "\n\n--- BYTECODE ---\n"
                                         (pretty-print bc))
                                    asm (pretty-print asm)
                                    :else ""))
                         :read-only true}]
     [:div {:style {:font-size "0.8em" :color "#666" :margin-top "5px"}}
      "Register-based bytecode for high performance."]]]

   [:div {:style {:margin-bottom "20px"}}
    [:button {:on-click evaluate-ast :style {:margin-right "10px"}}
     "Evaluate AST"]
    [:button {:on-click run-assembly :style {:margin-right "10px"}}
     "Run Assembly"]
    [:button {:on-click run-register}
     "Run Register VM"]]

   (when-let [error (:error @app-state)]
     [:div {:style {:color "red"}}
      [:h3 "Error:"]
      [:pre error]])

   [:div {:style {:display "flex" :gap "20px"}}
    (when (contains? @app-state :result)
      [:div {:style {:flex "1"}}
       [:h3 "AST Eval Result:"]
       [:pre {:style {:background "#eee" :padding "10px"}} (pr-str (:result @app-state))]])

    (when (contains? @app-state :assembly-result)
      [:div {:style {:flex "1"}}
       [:h3 "Assembly Result:"]
       [:pre {:style {:background "#eef" :padding "10px"}} (pr-str (:assembly-result @app-state))]])

    (when (contains? @app-state :register-result)
      [:div {:style {:flex "1"}}
       [:h3 "Register VM Result:"]
       [:pre {:style {:background "#efe" :padding "10px"}} (pr-str (:register-result @app-state))]])]

   [:div {:style {:margin-top "20px" :font-size "0.8em"}}
    [:h4 "Examples:"]
    [:ul
     [:li [:a {:href "#" :on-click #(swap! app-state assoc :ast-as-text (pretty-print {:type :literal :value 42}))} "Literal 42"]]
     [:li [:a {:href "#" :on-click #(swap! app-state assoc :ast-as-text (pretty-print {:type :application
                                                                                       :operator {:type :variable :name '+}
                                                                                       :operands [{:type :literal :value 10}
                                                                                                  {:type :literal :value 20}]}))} "Addition (10 + 20)"]]
     [:li [:a {:href "#" :on-click #(swap! app-state assoc :ast-as-text (pretty-print {:type :application
                                                                                       :operator {:type :lambda
                                                                                                  :params '[x]
                                                                                                  :body {:type :application
                                                                                                         :operator {:type :variable :name '+}
                                                                                                         :operands [{:type :variable :name 'x}
                                                                                                                    {:type :literal :value 1}]}}
                                                                                       :operands [{:type :literal :value 5}]}))} "Lambda Application ((lambda (x) (+ x 1)) 5)"]]
     [:li [:a {:href "#" :on-click #(swap! app-state assoc :clojure-code "(def fib (fn [n]\n           (if (< n 2)\n             n\n             (+ (fib (- n 1)) (fib (- n 2))))))\n(fib 7)")} "Fibonacci (Clojure)"]]]]])
(defn init []
  (let [app (js/document.getElementById "app")]
    (rdom/render [hello-world] app)))
