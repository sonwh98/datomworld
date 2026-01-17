(ns datomworld.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [yin.vm :as vm]
            [cljs.reader :as reader]))

(defonce app-state (r/atom {:ast-as-text "{:type :application\n :operator {:type :lambda\n            :params [x y]\n            :body {:type :application\n                   :operator {:type :variable :name +}\n                   :operands [{:type :variable :name x}\n                              {:type :variable :name y}]}}\n :operands [{:type :literal :value 4}\n            {:type :literal :value 5}]}"
                            :result nil
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
    [:button {:on-click evaluate-ast}
     "Evaluate AST"]]

   (when-let [error (:error @app-state)]
     [:div {:style {:color "red"}}
      [:h3 "Error:"]
      [:pre error]])

   (when (contains? @app-state :result)
     [:div
      [:h3 "Result:"]
      [:pre (pr-str (:result @app-state))]])

   [:div {:style {:margin-top "20px" :font-size "0.8em"}}
    [:h4 "Examples:"]
    [:ul
     [:li [:a {:href "#" :on-click #(swap! app-state assoc :ast-as-text "{:type :literal :value 42}")} "Literal 42"]]
     [:li [:a {:href "#" :on-click #(swap! app-state assoc :ast-as-text "{:type :application\n :operator {:type :variable :name +}\n :operands [{:type :literal :value 10}\n            {:type :literal :value 20}]}")} "Addition (10 + 20)"]]
     [:li [:a {:href "#" :on-click #(swap! app-state assoc :ast-as-text "{:type :application\n :operator {:type :lambda\n            :params [x]\n            :body {:type :application\n                   :operator {:type :variable :name +}\n                   :operands [{:type :variable :name x}\n                              {:type :literal :value 1}]}}\n :operands [{:type :literal :value 5}]}")} "Lambda Application ((lambda (x) (+ x 1)) 5)"]]]]])

(defn init []
  (let [app (js/document.getElementById "app")]
    (rdom/render [hello-world] app)))
