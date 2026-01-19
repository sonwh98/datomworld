(ns datomworld.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [yin.vm :as vm]
            [yin.bytecode :as bc]
            [yang.clojure :as yang]
            [cljs.reader :as reader]
            [cljs.pprint :as pprint]
            ["codemirror" :refer [basicSetup EditorView]]
            ["@codemirror/state" :refer [EditorState]]
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
                            :bytecode-result nil
                            :compiled nil
                            :error nil}))

(defn codemirror-editor [{:keys [value on-change read-only]}]
  (let [view-ref (r/atom nil)
        el-ref (atom nil)]
    (r/create-class
     {:display-name "codemirror-editor"
      :component-did-mount
      (fn [this]
        (when-let [node @el-ref]
          (let [extensions (cond-> #js [basicSetup (clojure)]
                             read-only (.concat #js [(.of (.-editable EditorView) false)])
                             on-change (.concat #js [(.of (.-updateListener EditorView)
                                                          (fn [update]
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
               :style (merge {:height "300px" :width "100%" :border "1px solid #ccc" :overflow "auto" :background-color "white"} style)}])})))

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
     [codemirror-editor {:value (:clojure-code @app-state)
                         :on-change #(swap! app-state assoc :clojure-code %)}]]
    
    ;; Arrow 1: Clojure -> AST
    [:div {:style {:margin "0 10px" :display "flex" :flex-direction "column" :justify-content "center" :height "300px"}}
     [:button {:on-click compile-clojure :style {:padding "5px"}}
      "Compile to AST ->"]] 
    
    ;; Column 2: Yin AST
    [:div {:style {:width "450px"}}
     [:label {:style {:font-weight "bold"}} "Yin AST (EDN):"]
     [:br]
     [codemirror-editor {:value (:ast-as-text @app-state)
                         :on-change #(swap! app-state assoc :ast-as-text %)}]]

    ;; Arrow 2: AST -> Bytecode
    [:div {:style {:margin "0 10px" :margin-right "10px" :display "flex" :flex-direction "column" :justify-content "center" :height "300px"}}
     [:button {:on-click compile-ast :style {:padding "5px"}}
      "Compile AST ->"]] 

    ;; Column 3: Compiled Bytecode
    [:div {:style {:width "450px"}}
     [:label {:style {:font-weight "bold"}} "Compiled Bytecode:"]
     [:br]
     [codemirror-editor {:value (if-let [compiled (:compiled @app-state)]
                                  (pretty-print compiled)
                                  "")
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
