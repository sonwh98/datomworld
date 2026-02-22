(ns datomworld.structural-editor
  (:require [cljs.pprint :as pprint]
            [cljs.reader :as reader]
            [datascript.core :as d]
            [reagent.core :as r]
            [yin.vm :as vm]))


;; =============================================================================
;; State
;; =============================================================================
;;
;; The block tree is a structured object. Layout is interpreted from topology.
;; No coordinates. A block's position is determined by which slot it occupies.
;;
;; {:blocks   {id {:type :literal :value 42}}
;;  :children {[parent-id slot-key] child-id}
;;  :root-id  id-or-nil
;;  :next-id  int}
;;
;; :children maps [parent-id slot-key] -> child-id for single slots.
;; For ordered multi-slots (:operands), slot-key is [:operands idx].

(defonce editor-state
  (r/atom {:blocks {}, :children {}, :root-id nil, :next-id 0, :drag nil}))


(defn- gen-id!
  [state]
  (let [id (:next-id @state)]
    (swap! state update :next-id inc)
    id))


;; =============================================================================
;; Block templates
;; =============================================================================

(def primitive-names
  '[+ - * / = == != < > <= >= and or not nil? empty? first rest conj assoc get
    vec])


(def palette-groups
  [{:label "Values",
    :items [{:template {:type :literal, :value 0}, :label "Number"}
            {:template {:type :literal, :value ""}, :label "String"}
            {:template {:type :literal, :value true}, :label "Boolean"}
            {:template {:type :literal, :value :k}, :label "Keyword"}]}
   {:label "Variable",
    :items [{:template {:type :variable, :name 'x}, :label "Variable"}]}
   {:label "Operators",
    :items (mapv (fn [sym]
                   {:template {:type :variable, :name sym}, :label (str sym)})
             primitive-names)}
   {:label "Forms",
    :items [{:template {:type :application}, :label "Apply"}
            {:template {:type :lambda, :params '[]}, :label "Lambda"}
            {:template {:type :if}, :label "If"}
            {:template {:type :edn, :text ""}, :label "EDN"}]}])


;; =============================================================================
;; Tree -> AST map (pure function)
;; =============================================================================

(defn blocks->ast
  "Walk the block tree from root, produce AST map.
   Returns nil if no root or root block is missing."
  ([state] (blocks->ast state (:root-id state)))
  ([{:keys [blocks children]} block-id]
   (when-let [block (get blocks block-id)]
     (let [child-of (fn [slot] (get children [block-id slot]))
           recurse (fn [slot]
                     (when-let [cid (child-of slot)]
                       (blocks->ast {:blocks blocks, :children children} cid)))]
       (case (:type block)
         :literal {:type :literal, :value (:value block)}
         :variable {:type :variable, :name (:name block)}
         :edn (try (let [parsed (reader/read-string (:text block))]
                     {:type :literal, :value parsed})
                   (catch js/Error _ nil))
         :lambda (let [body-ast (recurse :body)]
                   (when body-ast
                     {:type :lambda, :params (:params block), :body body-ast}))
         :application
           (let [op-ast (recurse :operator)
                 ;; Collect operand slots in order
                 operand-ids (loop [i 0
                                    acc []]
                               (if-let [cid (child-of [:operands i])]
                                 (recur (inc i) (conj acc cid))
                                 acc))
                 operand-asts
                   (mapv #(blocks->ast {:blocks blocks, :children children} %)
                     operand-ids)]
             (when (and op-ast (every? some? operand-asts))
               {:type :application, :operator op-ast, :operands operand-asts}))
         :if (let [test-ast (recurse :test)
                   cons-ast (recurse :consequent)
                   alt-ast (recurse :alternate)]
               (when (and test-ast cons-ast alt-ast)
                 {:type :if,
                  :test test-ast,
                  :consequent cons-ast,
                  :alternate alt-ast}))
         nil)))))


;; =============================================================================
;; Sync: blocks -> datoms -> DataScript
;; =============================================================================

(defn sync!
  "Reconstruct AST from block tree, convert to datoms, transact into DataScript,
   and write results into app-state."
  [app-state]
  (let [st @editor-state
        ast (blocks->ast st)]
    (if ast
      (try (let [ast-text (binding [pprint/*print-right-margin* 60]
                            (with-out-str (pprint/pprint ast)))
                 datoms (vec (vm/ast->datoms ast))
                 root-id (ffirst datoms)
                 tx-data (vm/datoms->tx-data datoms)
                 conn (d/conn-from-db (d/empty-db vm/schema))
                 {:keys [tempids]} (d/transact! conn tx-data)
                 db @conn
                 root-eid (get tempids root-id)]
             (swap! app-state assoc
               :ast-as-text ast-text
               :datoms datoms
               :ds-db db
               :root-ids [root-id]
               :root-eids [root-eid]
               :error nil))
           (catch js/Error e
             (swap! app-state assoc
               :error
               (str "Structural Editor Error: " (.-message e)))))
      ;; No complete AST yet - clear downstream
      (swap! app-state assoc
        :ast-as-text ""
        :datoms nil
        :ds-db nil
        :root-ids nil
        :root-eids nil))))


;; =============================================================================
;; Structural mutations
;; =============================================================================

(defn- add-block!
  "Add a new block from template. Returns the new block id."
  [template]
  (let [id (gen-id! editor-state)]
    (swap! editor-state assoc-in [:blocks id] (assoc template :id id))
    id))


(defn- attach!
  "Attach block-id into parent-id's slot. Detaches any previous occupant."
  [parent-id slot block-id]
  (swap! editor-state assoc-in [:children [parent-id slot]] block-id))


(defn- detach!
  "Remove block from its parent slot. Returns the detached block-id or nil."
  [parent-id slot]
  (let [child-id (get-in @editor-state [:children [parent-id slot]])]
    (swap! editor-state update :children dissoc [parent-id slot])
    child-id))


(defn- remove-block!
  "Remove a block and all its descendants from the tree."
  [block-id]
  (let [st @editor-state
        ;; Find all children of this block
        child-entries (filter (fn [[[pid _] _]] (= pid block-id))
                        (:children st))
        child-ids (map second child-entries)]
    ;; Remove children recursively
    (doseq [cid child-ids] (remove-block! cid))
    ;; Remove this block's child entries and the block itself
    (swap! editor-state
      (fn [s]
        (-> s
            (update :children
                    #(into {} (remove (fn [[[pid _] _]] (= pid block-id)) %)))
            (update :blocks dissoc block-id)
            (cond-> (= (:root-id s) block-id) (assoc :root-id nil)))))
    ;; Also remove any parent->this entries
    (swap! editor-state update
      :children
      #(into {} (remove (fn [[_ cid]] (= cid block-id)) %)))))


(defn- set-root! [block-id] (swap! editor-state assoc :root-id block-id))


;; =============================================================================
;; Colors
;; =============================================================================

(def type-colors
  {:literal "#2d8a4e",
   :variable "#3b6fc4",
   :lambda "#7b4ba0",
   :application "#c47a28",
   :if "#b8a828",
   :edn "#6b6b6b"})


(def type-border-colors
  {:literal "#3cb06a",
   :variable "#5b8fe4",
   :lambda "#9b6bc0",
   :application "#e49a48",
   :if "#d8c848",
   :edn "#9b9b9b"})


(def type-labels
  {:literal "lit",
   :variable "var",
   :lambda "fn",
   :application "apply",
   :if "if"})


;; =============================================================================
;; Block rendering (tree-recursive, layout from structure)
;; =============================================================================

(declare block-view)


(defn- slot-view
  "Render a drop target slot. If occupied, render the child block. If empty,
   show a notch-shaped cutout that accepts drops from the palette."
  [_parent-id _slot-key _label _app-state]
  (let [drag-over? (r/atom false)]
    (fn [parent-id slot-key label app-state]
      (let [st @editor-state
            child-id (get-in st [:children [parent-id slot-key]])]
        (if child-id
          [:div
           {:style {:margin "4px 0", :position "relative"},
            :on-drag-over (fn [e] (.stopPropagation e)),
            :on-drop (fn [e] (.stopPropagation e))}
           [:div
            {:style
               {:position "absolute", :top "2px", :right "2px", :z-index 10}}
            [:button
             {:on-click (fn [e]
                          (.stopPropagation e)
                          (detach! parent-id slot-key)
                          (remove-block! child-id)
                          (sync! app-state)),
              :style {:background "rgba(0,0,0,0.4)",
                      :border "none",
                      :border-radius "50%",
                      :color "#ff6b6b",
                      :cursor "pointer",
                      :font-size "11px",
                      :font-weight "bold",
                      :width "18px",
                      :height "18px",
                      :line-height "16px",
                      :text-align "center",
                      :padding "0"}} "\u00d7"]] [block-view child-id app-state]]
          ;; Empty slot: notch-shaped cutout
          [:div
           {:style
              {:background
                 (if @drag-over? "rgba(255,255,255,0.12)" "rgba(0,0,0,0.25)"),
               :border-radius "12px 4px 4px 12px",
               :padding "6px 10px",
               :margin "4px 0",
               :color (if @drag-over? "#fff" "#888"),
               :font-size "11px",
               :font-family "monospace",
               :min-height "28px",
               :display "flex",
               :align-items "center",
               :box-shadow
                 (if @drag-over?
                   "inset 0 0 8px rgba(255,255,255,0.2), 0 0 6px rgba(100,180,255,0.3)"
                   "inset 0 2px 4px rgba(0,0,0,0.3)"),
               :transition "all 0.15s ease",
               :position "relative",
               :z-index 1},
            :on-drag-enter (fn [e]
                             (.preventDefault e)
                             (.stopPropagation e)
                             (reset! drag-over? true)),
            :on-drag-over (fn [e] (.preventDefault e) (.stopPropagation e)),
            :on-drag-leave (fn [e]
                             (.stopPropagation e)
                             ;; Only reset if leaving this element, not
                             ;; entering a child
                             (when (= (.-target e) (.-currentTarget e))
                               (reset! drag-over? false))),
            :on-drop
              (fn [e]
                (.preventDefault e)
                (.stopPropagation e)
                (reset! drag-over? false)
                (let [template-json (.getData (.-dataTransfer e) "text/plain")]
                  (when (seq template-json)
                    (try (let [template (cljs.reader/read-string template-json)
                               new-id (add-block! template)]
                           (attach! parent-id slot-key new-id)
                           (sync! app-state))
                         (catch js/Error _ nil)))))}
           ;; Notch tab indicator on the left
           [:div
            {:style {:width "8px",
                     :height "16px",
                     :background (if @drag-over?
                                   "rgba(100,180,255,0.5)"
                                   "rgba(255,255,255,0.08)"),
                     :border-radius "4px",
                     :margin-right "8px",
                     :flex-shrink "0",
                     :transition "all 0.15s ease"}}] [:span label]])))))


(defn- value-input
  "Inline input for editing a literal value."
  [block-id app-state]
  (let [block (get-in @editor-state [:blocks block-id])
        v (:value block)]
    [:input
     {:type "text",
      :value (pr-str v),
      :on-click (fn [e] (.stopPropagation e)),
      :on-change
        (fn [e]
          (let [raw (.. e -target -value)]
            (try
              (let [parsed (cljs.reader/read-string raw)]
                (swap! editor-state assoc-in [:blocks block-id :value] parsed)
                (sync! app-state))
              (catch js/Error _
                (swap! editor-state assoc-in [:blocks block-id :value] raw))))),
      :style {:background "rgba(0,0,0,0.3)",
              :border "1px solid rgba(255,255,255,0.2)",
              :border-radius "10px",
              :color "#fff",
              :font-size "12px",
              :font-family "monospace",
              :padding "3px 8px",
              :width "80px",
              :outline "none"}}]))


(defn- name-input
  "Inline input for editing a variable name."
  [block-id app-state]
  (let [block (get-in @editor-state [:blocks block-id])
        n (:name block)]
    [:input
     {:type "text",
      :value (str n),
      :on-click (fn [e] (.stopPropagation e)),
      :on-change (fn [e]
                   (let [raw (.. e -target -value)
                         sym (symbol raw)]
                     (swap! editor-state assoc-in [:blocks block-id :name] sym)
                     (sync! app-state))),
      :style {:background "rgba(0,0,0,0.3)",
              :border "1px solid rgba(255,255,255,0.2)",
              :border-radius "10px",
              :color "#fff",
              :font-size "12px",
              :font-family "monospace",
              :padding "3px 8px",
              :width "60px",
              :outline "none"}}]))


(defn- params-input
  "Inline input for editing lambda params."
  [block-id app-state]
  (let [block (get-in @editor-state [:blocks block-id])
        params (:params block)]
    [:input
     {:type "text",
      :value (pr-str params),
      :on-click (fn [e] (.stopPropagation e)),
      :on-change (fn [e]
                   (let [raw (.. e -target -value)]
                     (try (let [parsed (cljs.reader/read-string raw)]
                            (when (vector? parsed)
                              (swap! editor-state assoc-in
                                [:blocks block-id :params]
                                parsed)
                              (sync! app-state)))
                          (catch js/Error _ nil)))),
      :style {:background "rgba(0,0,0,0.3)",
              :border "1px solid rgba(255,255,255,0.2)",
              :border-radius "10px",
              :color "#fff",
              :font-size "12px",
              :font-family "monospace",
              :padding "3px 8px",
              :width "100px",
              :outline "none"}}]))


(defn- edn-input
  "Textarea for editing arbitrary EDN expressions."
  [block-id app-state]
  (let [block (get-in @editor-state [:blocks block-id])
        text (:text block)
        valid?
          (try (cljs.reader/read-string text) true (catch js/Error _ false))]
    [:textarea
     {:value text,
      :placeholder "{:key value}",
      :on-click (fn [e] (.stopPropagation e)),
      :on-change (fn [e]
                   (let [raw (.. e -target -value)]
                     (swap! editor-state assoc-in [:blocks block-id :text] raw)
                     (sync! app-state))),
      :style {:background "rgba(0,0,0,0.3)",
              :border (str "1px solid "
                           (if valid?
                             "rgba(255,255,255,0.2)"
                             "rgba(255,100,100,0.5)")),
              :border-radius "6px",
              :color "#fff",
              :font-size "11px",
              :font-family "monospace",
              :padding "4px 8px",
              :width "150px",
              :min-height "40px",
              :resize "both",
              :outline "none"}}]))


(defn- operand-count
  "Count how many operand slots are filled for a block."
  [block-id]
  (let [children (:children @editor-state)]
    (loop [i 0]
      (if (get children [block-id [:operands i]]) (recur (inc i)) i))))


(defn- output-tab
  "A small colored protrusion on the left side of expression blocks."
  [color]
  [:div
   {:style {:width "8px",
            :height "20px",
            :background color,
            :border-radius "0 4px 4px 0",
            :margin-right "-1px",
            :flex-shrink "0",
            :align-self "center"}}])


(defn- pill-block
  "Pill-shaped expression block with output tab (for literal/variable)."
  [bg border-color label-text _label-color content]
  [:div
   {:style {:display "flex",
            :align-items "center",
            :filter "drop-shadow(0 2px 4px rgba(0,0,0,0.4))"}} [output-tab bg]
   [:div
    {:style {:background bg,
             :border (str "2px solid " border-color),
             :border-radius "20px",
             :padding "6px 14px",
             :font-size "12px",
             :font-family "monospace",
             :display "flex",
             :align-items "center",
             :gap "8px"}}
    [:span
     {:style {:color "#fff",
              :font-size "10px",
              :font-weight "bold",
              :text-transform "uppercase",
              :opacity "0.8"}} label-text] content]])


(defn- c-block-top-bar
  "Top bar of a C-shaped compound block."
  [bg border-color content]
  [:div
   {:style {:background bg,
            :border-top (str "2px solid " border-color),
            :border-left (str "2px solid " border-color),
            :border-right (str "2px solid " border-color),
            :border-radius "6px 6px 0 0",
            :padding "6px 12px",
            :display "flex",
            :align-items "center",
            :gap "8px",
            :font-size "12px",
            :font-family "monospace"}} content])


(defn- c-block-body
  "Body area of a C-shaped block with left rail."
  [bg border-color children]
  [:div
   {:style {:border-left (str "4px solid " bg),
            :margin-left "0px",
            :padding "6px 6px 6px 10px",
            :background "rgba(0,0,0,0.15)",
            :border-right (str "2px solid " border-color)}} children])


(defn- c-block-bottom-bar
  "Bottom bar to close the C-shape."
  [bg border-color content]
  [:div
   {:style {:background bg,
            :border-bottom (str "2px solid " border-color),
            :border-left (str "2px solid " border-color),
            :border-right (str "2px solid " border-color),
            :border-radius "0 0 6px 6px",
            :padding "4px 12px",
            :min-height "8px"}} content])


(defn- c-block
  "C-shaped compound block wrapper."
  [bg border-color top-content body-content bottom-content]
  [:div
   {:style {:filter "drop-shadow(0 2px 6px rgba(0,0,0,0.4))", :margin "2px 0"}}
   [c-block-top-bar bg border-color top-content]
   [c-block-body bg border-color body-content]
   [c-block-bottom-bar bg border-color bottom-content]])


(defn block-view
  "Render a single block. Layout is determined by type and slot structure,
   not by user-controlled coordinates."
  [block-id app-state]
  (let [block (get-in @editor-state [:blocks block-id])
        btype (:type block)
        bg (get type-colors btype "#1a1a2e")
        border-color (get type-border-colors btype "#2d3b55")]
    (case btype
      :literal [pill-block bg border-color "lit" "#fff"
                [value-input block-id app-state]]
      :variable [pill-block bg border-color "var" "#fff"
                 [name-input block-id app-state]]
      :edn [:div
            {:style {:display "flex",
                     :align-items "flex-start",
                     :filter "drop-shadow(0 2px 4px rgba(0,0,0,0.4))"}}
            [output-tab bg]
            [:div
             {:style {:background bg,
                      :border (str "2px solid " border-color),
                      :border-radius "8px",
                      :padding "6px 10px",
                      :font-size "12px",
                      :font-family "monospace"}}
             [:div
              {:style {:display "flex",
                       :align-items "center",
                       :gap "6px",
                       :margin-bottom "4px"}}
              [:span
               {:style {:color "#fff",
                        :font-size "10px",
                        :font-weight "bold",
                        :text-transform "uppercase",
                        :opacity "0.8"}} "edn"]]
             [edn-input block-id app-state]]]
      :lambda [c-block bg border-color
               ;; Top bar: fn label + params
               [:div
                {:style {:display "flex", :align-items "center", :gap "8px"}}
                [:span
                 {:style {:color "#fff",
                          :font-size "11px",
                          :font-weight "bold",
                          :text-transform "uppercase"}} "fn"]
                [params-input block-id app-state]]
               ;; Body: body slot
               [:div
                [:div
                 {:style {:color "rgba(255,255,255,0.5)",
                          :font-size "9px",
                          :margin-bottom "2px",
                          :text-transform "uppercase"}} "body"]
                [slot-view block-id :body "body" app-state]]
               ;; Bottom bar: empty
               nil]
      :application
        (let [n (operand-count block-id)]
          [c-block bg border-color
           ;; Top bar: apply label only
           [:span
            {:style {:color "#fff",
                     :font-size "11px",
                     :font-weight "bold",
                     :text-transform "uppercase"}} "apply"]
           ;; Body: operator slot + operand slots
           [:div
            [:div
             {:style {:color "rgba(255,255,255,0.5)",
                      :font-size "9px",
                      :margin-bottom "2px",
                      :text-transform "uppercase"}} "operator"]
            [slot-view block-id :operator "operator" app-state]
            (for [i (range (inc n))]
              ^{:key i}
              [:div
               [:div
                {:style {:color "rgba(255,255,255,0.5)",
                         :font-size "9px",
                         :margin-bottom "1px",
                         :margin-top "4px",
                         :text-transform "uppercase"}} (str "arg " i)]
               [slot-view block-id [:operands i] (str "arg " i) app-state]])]
           ;; Bottom bar: + arg button
           [:button
            {:on-click (fn [e] (.stopPropagation e) (sync! app-state)),
             :style {:background "rgba(255,255,255,0.1)",
                     :border "1px solid rgba(255,255,255,0.2)",
                     :color "#fff",
                     :cursor "pointer",
                     :font-size "10px",
                     :font-weight "bold",
                     :padding "2px 10px",
                     :border-radius "10px"}} "+ arg"]])
      :if [c-block bg
           border-color
             ;; Top bar
             [:span
              {:style {:color "#fff",
                       :font-size "11px",
                       :font-weight "bold",
                       :text-transform "uppercase"}} "if"]
           ;; Body: test / then / else sections with dividers
           [:div
            [:div
             {:style {:color "rgba(255,255,255,0.5)",
                      :font-size "9px",
                      :text-transform "uppercase",
                      :margin-bottom "2px"}} "test"]
            [slot-view block-id :test "test" app-state]
            [:div
             {:style {:border-top (str "2px solid " bg),
                      :margin "6px 0",
                      :opacity "0.6",
                      :pointer-events "none"}}]
            [:div
             {:style {:color "rgba(255,255,255,0.5)",
                      :font-size "9px",
                      :text-transform "uppercase",
                      :margin-bottom "2px"}} "then"]
            [slot-view block-id :consequent "then" app-state]
            [:div
             {:style {:border-top (str "2px solid " bg),
                      :margin "6px 0",
                      :opacity "0.6",
                      :pointer-events "none"}}]
            [:div
             {:style {:color "rgba(255,255,255,0.5)",
                      :font-size "9px",
                      :text-transform "uppercase",
                      :margin-bottom "2px"}} "else"]
            [slot-view block-id :alternate "else" app-state]]
             ;; Bottom bar: empty
             nil]
      [:div (str "?" btype)])))


;; =============================================================================
;; Palette
;; =============================================================================

(defn- palette-item
  [template label]
  (let [btype (:type template)
        bg (get type-colors btype "#1a1a2e")
        border-color (get type-border-colors btype "#2d3b55")
        expression? (#{:literal :variable} btype)]
    [:div
     {:draggable true,
      :on-drag-start
        (fn [e] (.setData (.-dataTransfer e) "text/plain" (pr-str template))),
      :style {:display "flex",
              :align-items "center",
              :margin "3px 0",
              :cursor "grab",
              :user-select "none",
              :filter "drop-shadow(0 1px 2px rgba(0,0,0,0.3))"}}
     ;; Mini output tab for expression blocks
     (when expression?
       [:div
        {:style {:width "5px",
                 :height "14px",
                 :background bg,
                 :border-radius "0 3px 3px 0",
                 :margin-right "-1px",
                 :flex-shrink "0"}}])
     [:div
      {:style {:background bg,
               :border (str "1.5px solid " border-color),
               :border-radius (if expression? "14px" "4px"),
               :padding "3px 10px",
               :font-size "11px",
               :font-family "monospace",
               :color "#fff",
               :flex "1"}} label]]))


(defn- palette-view
  []
  (let [collapsed-groups (r/atom #{})]
    (fn [] [:div
            {:style {:width "130px",
                     :flex-shrink "0",
                     :overflow-y "auto",
                     :border-right "1px solid #2d3b55",
                     :padding "6px"}}
            (for [{:keys [label items]} palette-groups]
              ^{:key label}
              [:div {:style {:margin-bottom "4px"}}
               [:div
                {:on-click #(swap! collapsed-groups (fn [s]
                                                      (if (contains? s label)
                                                        (disj s label)
                                                        (conj s label)))),
                 :style {:color "#8b949e",
                         :font-size "10px",
                         :cursor "pointer",
                         :margin-bottom "2px",
                         :user-select "none"}}
                (if (contains? @collapsed-groups label) "▸ " "▾ ") label]
               (when-not (contains? @collapsed-groups label)
                 (for [{:keys [template label]} items]
                   ^{:key (str label (hash template))}
                   [palette-item template label]))])])))


;; =============================================================================
;; Canvas
;; =============================================================================

(defn- canvas-view
  [app-state]
  (let [st @editor-state
        root-id (:root-id st)]
    [:div
     {:style {:flex "1", :overflow "auto", :padding "8px"},
      :on-drag-over (fn [e] (.preventDefault e)),
      :on-drop (fn [e]
                 (.preventDefault e)
                 (let [template-json (.getData (.-dataTransfer e) "text/plain")]
                   (when (seq template-json)
                     (try (let [template (cljs.reader/read-string template-json)
                                new-id (add-block! template)]
                            (set-root! new-id)
                            (sync! app-state))
                          (catch js/Error _ nil)))))}
     (if root-id
       [:div
        {:on-drag-over (fn [e] (.stopPropagation e)),
         :on-drop (fn [e] (.stopPropagation e))} [block-view root-id app-state]
        [:button
         {:on-click (fn [] (remove-block! root-id) (sync! app-state)),
          :style {:background "#3a1a1a",
                  :border "1px solid #6b2d2d",
                  :border-radius "4px",
                  :color "#c5c6c7",
                  :cursor "pointer",
                  :font-size "11px",
                  :padding "3px 8px",
                  :margin-top "8px"}} "Clear"]]
       [:div {:style {:color "#555", :font-size "12px", :padding "20px"}}
        "Drop a block here to start."])]))


;; =============================================================================
;; Editor component (exported)
;; =============================================================================

(defn editor
  "Main structural editor component. Palette on the left, structured canvas
   on the right. Layout is interpreted from tree topology."
  [app-state]
  [:div
   {:style {:display "flex", :flex "1", :overflow "hidden", :height "100%"}}
   [palette-view] [canvas-view app-state]])
