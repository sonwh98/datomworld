(ns datomworld.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [yin.vm :as vm]
            [yin.bytecode :as bc]
            [yang.clojure :as yang]
            [cljs.reader :as reader]
            [cljs.pprint :as pprint]
            ["monaco-editor/esm/vs/editor/editor.api.js" :as monaco]))

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
                            :bytecode-result nil
                            :compiled nil
                            :error nil}))

(defn monaco-editor [{:keys [value on-change language read-only]}]
  (let [editor-ref (r/atom nil)
        el-ref (atom nil)]
    (r/create-class
     {:display-name "monaco-editor"
      :component-did-mount
      (fn [this]
        (when-let [node @el-ref]
          (let [editor (.create ^js monaco/editor node
                                (clj->js {:value value
                                          :language language
                                          :readOnly (boolean read-only)
                                          :theme "vs-dark"
                                          :automaticLayout true
                                          :minimap {:enabled false}}))]
            (reset! editor-ref editor)
            (.onDidChangeModelContent ^js editor
                                       (fn [_]
                                         (when on-change
                                           (let [current-value (.getValue ^js editor)]
                                             (when (not= value current-value)
                                               (on-change current-value)))))))))
      :component-did-update
      (fn [this old-argv]
        (let [{:keys [value]} (r/props this)]
          (when-let [editor ^js @editor-ref]
            (when (not= value (.getValue editor))
              (let [position (.getPosition editor)]
                (.setValue editor value)
                (.setPosition editor position))))))
      :component-will-unmount
      (fn [this]
        (when-let [editor ^js @editor-ref]
          (.dispose editor)))
      :reagent-render
      (fn [{:keys [style]}]
        [:div {:ref #(reset! el-ref %)
               :style (merge {:height "300px" :width "100%" :border "1px solid #ccc"} style)}])})))

(defn evaluate-ast []
  (let [input (:ast-as-text @app-state)]
    (try
      (let [ast (reader/read-string input)
            ;; Initialize environment with standard primitives
            initial-env vm/primitives
            state (vm/make-state initial-env)
            result (vm/run state ast)]
        (swap! app-state assoc :result (:value result) :error nil))
      (catch js/Error e
        (swap! app-state assoc :error (.-message e) :result nil)))))

(defn compile-ast []
  (let [input (:ast-as-text @app-state)]
    (try
      (let [ast (reader/read-string input)
            [bytes pool] (bc/compile ast)]
        (swap! app-state assoc :compiled {:bytes bytes :pool pool} :error nil)
        {:bytes bytes :pool pool})
      (catch js/Error e
        (swap! app-state assoc :error (.-message e) :compiled nil)
        nil))))

(defn run-bytecode []
  (let [compiled (:compiled @app-state)
        ;; If not compiled yet, try to compile
        {:keys [bytes pool]} (or compiled (compile-ast))]
    (when (and bytes pool)
      (try
        (let [initial-env vm/primitives
              result (bc/run-bytes bytes pool initial-env)]
          (swap! app-state assoc :bytecode-result result :error nil))
        (catch js/Error e
          (swap! app-state assoc :error (str "Bytecode Error: " (.-message e)) :bytecode-result nil))))))

(defn compile-clojure []
  (let [input (:clojure-code @app-state)]
    (try
      (let [form (reader/read-string input)
            ast (yang/compile form)]
        (swap! app-state assoc :ast-as-text (pretty-print ast) :error nil))
      (catch js/Error e
        (swap! app-state assoc :error (str "Clojure Compile Error: " (.-message e)))))))

(defn hello-world []
  [:div
   [:h1 "Datomworld Yin VM Explorer"]
   
   [:div {:style {:display "flex" :flex-direction "row" :align-items "flex-start" :margin-bottom "20px"}}
    ;; Column 1: Clojure Code
    [:div {:style {:width "400px"}}
     [:label {:style {:font-weight "bold"}} "Enter Clojure Code:"]
     [:br]
     [monaco-editor {:value (:clojure-code @app-state)
                     :language "clojure"
                     :on-change #(swap! app-state assoc :clojure-code %)}]]
    
    ;; Arrow 1: Clojure -> AST
    [:div {:style {:margin "0 10px" :display "flex" :flex-direction "column" :justify-content "center" :height "300px"}}
     [:button {:on-click compile-clojure :style {:padding "5px"}}
      "Compile to AST ->"]] 
    
    ;; Column 2: Yin AST
    [:div {:style {:width "450px"}}
     [:label {:style {:font-weight "bold"}} "Yin AST (EDN):"]
     [:br]
     [monaco-editor {:value (:ast-as-text @app-state)
                     :language "clojure"
                     :on-change #(swap! app-state assoc :ast-as-text %)}]]

    ;; Arrow 2: AST -> Bytecode
    [:div {:style {:margin "0 10px" :margin-right "10px" :display "flex" :flex-direction "column" :justify-content "center" :height "300px"}}
     [:button {:on-click compile-ast :style {:padding "5px"}}
      "Compile AST ->"]] 

    ;; Column 3: Compiled Bytecode
    [:div {:style {:width "450px"}}
     [:label {:style {:font-weight "bold"}} "Compiled Bytecode:"]
     [:br]
     [monaco-editor {:value (if-let [compiled (:compiled @app-state)]
                              (pretty-print compiled)
                              "")
                     :language "clojure"
                     :read-only true}]]]

   [:div {:style {:margin-bottom "20px"}}
    [:button {:on-click evaluate-ast :style {:margin-right "10px"}}
     "Evaluate AST"]
    [:button {:on-click run-bytecode}
     "Run Bytecode"]]

   (when-let [error (:error @app-state)]
     [:div {:style {:color "red"}}
      [:h3 "Error:"]
      [:pre error]])

   (when (contains? @app-state :result)
     [:div
      [:h3 "AST Eval Result:"]
      [:pre (pr-str (:result @app-state))]])

   (when (contains? @app-state :bytecode-result)
     [:div
      [:h3 "Bytecode Run Result:"]
      [:pre (pr-str (:bytecode-result @app-state))]])

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
                                                                                     :operands [{:type :literal :value 5}]}))} "Lambda Application ((lambda (x) (+ x 1)) 5)"]]]]])

(defn init []
  (let [app (js/document.getElementById "app")]
    (rdom/render [hello-world] app)))
