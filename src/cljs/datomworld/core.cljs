(ns datomworld.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [yin.vm :as vm]
            [yin.bytecode :as bc]
            [cljs.reader :as reader]))

(defonce app-state (r/atom {:ast-as-text "{:type :application\n :operator {:type :lambda\n            :params [x y]\n            :body {:type :application\n                   :operator {:type :variable :name +}\n                   :operands [{:type :variable :name x}\n                              {:type :variable :name y}]}}\n :operands [{:type :literal :value 4}\n            {:type :literal :value 5}]}"
                            :result nil
                            :bytecode-result nil
                            :compiled nil
                            :error nil}))

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

(defn hello-world []
  [:div
   [:h1 "Datomworld Yin VM Explorer"]
   [:div
    [:label "Enter Yin AST (EDN): "]
    [:br]
    [:textarea {:rows 10
                :cols 60
                :value (:ast-as-text @app-state)
                :on-change #(swap! app-state assoc :ast-as-text (-> % .-target .-value))}]]
   [:div
    [:button {:on-click evaluate-ast :style {:margin-right "10px"}}
     "Evaluate AST"]
    [:button {:on-click compile-ast :style {:margin-right "10px"}}
     "Compile AST"]
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

   (when-let [compiled (:compiled @app-state)]
     [:div
      [:h3 "Compiled Bytecode:"]
      [:div
       [:strong "Bytes: "] [:span (pr-str (:bytes compiled))]
       [:br]
       [:strong "Constant Pool: "] [:span (pr-str (:pool compiled))]]])

   [:div {:style {:margin-top "20px" :font-size "0.8em"}}
    [:h4 "Examples:"]
    [:ul
     [:li [:a {:href "#" :on-click #(swap! app-state assoc :ast-as-text "{:type :literal :value 42}")} "Literal 42"]]
     [:li [:a {:href "#" :on-click #(swap! app-state assoc :ast-as-text "{:type :application\n :operator {:type :variable :name +}\n :operands [{:type :literal :value 10}\n            {:type :literal :value 20}]}")} "Addition (10 + 20)"]]
     [:li [:a {:href "#" :on-click #(swap! app-state assoc :ast-as-text "{:type :application\n :operator {:type :lambda\n            :params [x]\n            :body {:type :application\n                   :operator {:type :variable :name +}\n                   :operands [{:type :variable :name x}\n                              {:type :literal :value 1}]}}\n :operands [{:type :literal :value 5}]}")} "Lambda Application ((lambda (x) (+ x 1)) 5)"]]]]])

(defn init []
  (let [app (js/document.getElementById "app")]
    (rdom/render [hello-world] app)))
