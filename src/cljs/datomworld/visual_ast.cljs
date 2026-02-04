(ns datomworld.visual-ast
  (:require [cljs.pprint :as pprint]
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
            {:template {:type :if}, :label "If"}]}])


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
                 {:keys [db tempids]} (vm/transact! datoms)
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
               (str "Visual AST Error: " (.-message e)))))
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


(defn- add-operand-slot!
  "Ensure an application block has room for one more operand."
  [parent-id]
  ;; Operand slots are [:operands 0], [:operands 1], etc.
  ;; This is implicit: we just need the next index.
  nil)


;; =============================================================================
;; Colors
;; =============================================================================

(def type-colors
  {:literal "#1a3a2a",
   :variable "#1a2a4a",
   :lambda "#2a1a3a",
   :application "#3a2a1a",
   :if "#3a3a1a"})


(def type-border-colors
  {:literal "#2d6b45",
   :variable "#2d4580",
   :lambda "#5a3d7a",
   :application "#7a5a2d",
   :if "#7a7a2d"})


(def type-labels
  {:literal "Lit",
   :variable "Var",
   :lambda "Lambda",
   :application "Apply",
   :if "If"})


;; =============================================================================
;; Block rendering (tree-recursive, layout from structure)
;; =============================================================================

(declare block-view)


(defn- slot-view
  "Render a drop target slot. If occupied, render the child block. If empty,
   show a dashed placeholder that accepts drops from the palette."
  [parent-id slot-key label app-state]
  (let [st @editor-state
        child-id (get-in st [:children [parent-id slot-key]])]
    (if child-id
      [:div {:style {:margin "3px 0", :position "relative"}}
       [:div {:style {:position "absolute", :top "0", :right "0", :z-index 1}}
        [:button
         {:on-click (fn [e]
                      (.stopPropagation e)
                      (detach! parent-id slot-key)
                      (remove-block! child-id)
                      (sync! app-state)),
          :style {:background "none",
                  :border "none",
                  :color "#8b4444",
                  :cursor "pointer",
                  :font-size "10px",
                  :padding "1px 3px"}} "x"]] [block-view child-id app-state]]
      ;; Empty slot
      [:div
       {:style {:border "1px dashed #2d3b55",
                :border-radius "4px",
                :padding "4px 8px",
                :margin "3px 0",
                :color "#555",
                :font-size "11px",
                :min-height "24px",
                :display "flex",
                :align-items "center"},
        :on-drag-over (fn [e] (.preventDefault e)),
        :on-drop
          (fn [e]
            (.preventDefault e)
            (let [template-json (.getData (.-dataTransfer e) "text/plain")]
              (when (seq template-json)
                (try (let [template (cljs.reader/read-string template-json)
                           new-id (add-block! template)]
                       (attach! parent-id slot-key new-id)
                       (sync! app-state))
                     (catch js/Error _ nil)))))} (str label " ▾")])))


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
                ;; Keep raw string while typing
                (swap! editor-state assoc-in [:blocks block-id :value] raw))))),
      :style {:background "#0a0f1e",
              :border "1px solid #2d3b55",
              :border-radius "3px",
              :color "#c5c6c7",
              :font-size "12px",
              :font-family "monospace",
              :padding "2px 5px",
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
      :style {:background "#0a0f1e",
              :border "1px solid #2d3b55",
              :border-radius "3px",
              :color "#c5c6c7",
              :font-size "12px",
              :font-family "monospace",
              :padding "2px 5px",
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
      :style {:background "#0a0f1e",
              :border "1px solid #2d3b55",
              :border-radius "3px",
              :color "#c5c6c7",
              :font-size "12px",
              :font-family "monospace",
              :padding "2px 5px",
              :width "100px",
              :outline "none"}}]))


(defn- operand-count
  "Count how many operand slots are filled for a block."
  [block-id]
  (let [children (:children @editor-state)]
    (loop [i 0]
      (if (get children [block-id [:operands i]]) (recur (inc i)) i))))


(defn block-view
  "Render a single block. Layout is determined by type and slot structure,
   not by user-controlled coordinates."
  [block-id app-state]
  (let [block (get-in @editor-state [:blocks block-id])
        btype (:type block)
        bg (get type-colors btype "#1a1a2e")
        border-color (get type-border-colors btype "#2d3b55")]
    [:div
     {:style {:background bg,
              :border (str "1px solid " border-color),
              :border-radius (if (#{:literal :variable} btype) "12px" "6px"),
              :padding "6px 10px",
              :margin "2px 0",
              :font-size "12px",
              :font-family "monospace"}}
     (case btype
       :literal [:div
                 {:style {:display "flex", :align-items "center", :gap "6px"}}
                 [:span {:style {:color "#6bba7b", :font-size "10px"}} "lit"]
                 [value-input block-id app-state]]
       :variable [:div
                  {:style {:display "flex", :align-items "center", :gap "6px"}}
                  [:span {:style {:color "#5b8bd6", :font-size "10px"}} "var"]
                  [name-input block-id app-state]]
       :lambda [:div
                [:div
                 {:style {:display "flex",
                          :align-items "center",
                          :gap "6px",
                          :margin-bottom "4px"}}
                 [:span {:style {:color "#a06bd6", :font-size "10px"}} "fn"]
                 [params-input block-id app-state]]
                [:div
                 {:style {:padding-left "10px",
                          :border-left "2px solid #5a3d7a"}}
                 [slot-view block-id :body "body" app-state]]]
       :application
         (let [n (operand-count block-id)]
           [:div
            [:div
             {:style {:display "flex",
                      :align-items "center",
                      :gap "6px",
                      :margin-bottom "4px"}}
             [:span {:style {:color "#d6a04b", :font-size "10px"}} "apply"]]
            [slot-view block-id :operator "operator" app-state]
            (for [i (range (inc n))]
              ^{:key i}
              [slot-view block-id [:operands i] (str "arg " i) app-state])
            [:button
             {:on-click (fn [e]
                          (.stopPropagation e)
                          ;; Adding an operand slot is implicit: just sync
                          (sync! app-state)),
              :style {:background "none",
                      :border "1px dashed #2d3b55",
                      :color "#555",
                      :cursor "pointer",
                      :font-size "10px",
                      :padding "1px 6px",
                      :border-radius "3px",
                      :margin-top "2px"}} "+ arg"]])
       :if [:div [:span {:style {:color "#c6c64b", :font-size "10px"}} "if"]
            [:div
             {:style {:padding-left "10px",
                      :border-left "2px solid #7a7a2d",
                      :margin-top "4px"}}
             [slot-view block-id :test "test" app-state]
             [slot-view block-id :consequent "then" app-state]
             [slot-view block-id :alternate "else" app-state]]]
       [:div (str "?" btype)])]))


;; =============================================================================
;; Palette
;; =============================================================================

(defn- palette-item
  [template label]
  [:div
   {:draggable true,
    :on-drag-start
      (fn [e] (.setData (.-dataTransfer e) "text/plain" (pr-str template))),
    :style {:background "#0e1328",
            :border "1px solid #2d3b55",
            :border-radius "4px",
            :padding "3px 8px",
            :margin "2px 0",
            :cursor "grab",
            :font-size "11px",
            :color "#c5c6c7",
            :user-select "none"}} label])


(defn- palette-view
  []
  (let [collapsed-groups (r/atom #{})]
    (fn [] [:div
            {:style {:width "110px",
                     :flex-shrink "0",
                     :overflow-y "auto",
                     :border-right "1px solid #2d3b55",
                     :padding "4px"}}
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
       [:div [block-view root-id app-state]
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
  "Main visual AST editor component. Palette on the left, structured canvas
   on the right. Layout is interpreted from tree topology."
  [app-state]
  [:div
   {:style {:display "flex", :flex "1", :overflow "hidden", :height "100%"}}
   [palette-view] [canvas-view app-state]])
