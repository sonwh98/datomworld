(ns datomworld.continuation-stream-demo
  (:require ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/theme-one-dark" :refer [oneDark]]
            ["@codemirror/view" :refer [EditorView]]
            ["@nextjournal/lang-clojure" :refer [clojure]]
            ["codemirror" :refer [basicSetup]]
            [cljs.pprint :as pprint]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [reagent.core :as r]
            [yang.clojure :as yang]
            [yin.vm :as vm]
            [yin.vm.register :as register]))


(def source-example
  "(def sum-to
  (fn [n]
    (if (= n 0)
      0
      (+ n (sum-to (- n 1))))))

;; Example: (sum-to 3) => 6 (1 + 2 + 3)
(sum-to 100)")


(defn pretty-print
  [data]
  (binding [pprint/*print-right-margin* 72]
    (with-out-str (pprint/pprint data))))


(defn codemirror-editor
  [{:keys [value on-change read-only]}]
  (let [view-ref (r/atom nil)
        el-ref (atom nil)]
    (r/create-class
      {:display-name "continuation-codemirror-editor",
       :component-did-mount
         (fn [_]
           (when-let [node @el-ref]
             (let [theme (.theme EditorView
                                 #js {"&" #js {:height "100%"},
                                      ".cm-scroller" #js {:overflow "auto"}})
                   extensions
                     (cond-> #js [basicSetup (clojure) oneDark theme]
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
                                  #js {:doc (or value ""),
                                       :extensions extensions})
                   view (new EditorView #js {:state state, :parent node})]
               (reset! view-ref view)))),
       :component-did-update
         (fn [this _]
           (let [{:keys [value]} (r/props this)]
             (when-let [view @view-ref]
               (let [next-value (or value "")
                     current-value (.. view -state -doc toString)]
                 (when (not= next-value current-value)
                   (.dispatch view
                              #js {:changes #js
                                             {:from 0,
                                              :to (.. view -state -doc -length),
                                              :insert next-value}})))))),
       :component-will-unmount (fn [_]
                                 (when-let [view @view-ref]
                                   (.destroy view)
                                   (reset! view-ref nil))),
       :reagent-render
         (fn [{:keys [style]}] [:div
                                {:ref #(reset! el-ref %),
                                 :style (merge {:height "100%",
                                                :width "100%",
                                                :border "1px solid #2d3b55",
                                                :overflow "hidden"}
                                               style)}])})))


(defn empty-stream [] {:queue [], :datoms [], :next-e 7000, :next-t 1})


(defonce app-state
  (r/atom
    {:source-code source-example,
     :ast nil,
     :ast-datoms nil,
     :asm nil,
     :bytecode nil,
     :pool nil,
     :source-map nil,
     :reg-count nil,
     :vm-a nil,
     :vm-b nil,
     :owner :vm-a,
     :steps 0,
     :handoffs 0,
     :steps-since-handoff 0,
     :handoff-interval 9,
     :running false,
     :completed? false,
     :result nil,
     :stream (empty-stream),
     :error nil}))


(defonce run-raf-id (atom nil))


(def continuation-keys
  [:regs :k :env :ip :bytecode :pool :halted :value :store :parked :id-counter
   :blocked :run-queue :wait-set])


(defn continuation-depth
  [k]
  (loop [frame k depth 0] (if frame (recur (:parent frame) (inc depth)) depth)))


(defn continuation-frames
  [k]
  (loop [frame k
         frames []
         n 0]
    (if (or (nil? frame) (>= n 12))
      frames
      (recur (:parent frame)
             (conj frames
                   {:type (:type frame),
                    :return-ip (:return-ip frame),
                    :return-reg (:return-reg frame)})
             (inc n)))))


(defn vm-state->continuation
  [vm-state]
  (select-keys vm-state continuation-keys))


(defn continuation->vm-state
  [continuation]
  (let [base (register/create-vm {:env vm/primitives})
        resumed (reduce-kv (fn [acc k v] (assoc acc k v)) base continuation)]
    (assoc resumed :primitives vm/primitives)))


(defn other-vm [vm-key] (if (= vm-key :vm-a) :vm-b :vm-a))


(defn append-stream-datom
  [stream a v]
  (let [e (:next-e stream)
        t (:next-t stream)
        datom [e a v t 0]
        datoms (-> (or (:datoms stream) [])
                   (conj datom)
                   (->> (take-last 200)
                        vec))]
    [(assoc stream
       :next-e (inc e)
       :next-t (inc t)
       :datoms datoms) datom]))


(defn enqueue-continuation
  [state from to vm-state]
  (let [continuation (vm-state->continuation vm-state)
        summary {:kind :continuation,
                 :from from,
                 :to to,
                 :ip (:ip vm-state),
                 :k-depth (continuation-depth (:k vm-state))}
        [stream* datom]
          (append-stream-datom (:stream state) :stream/continuation summary)
        message {:id (first datom),
                 :from from,
                 :to to,
                 :summary summary,
                 :continuation continuation}]
    (assoc state :stream (update stream* :queue conj message))))


(defn pop-continuation-for
  [state vm-key]
  (let [queue (get-in state [:stream :queue] [])
        idx (first (keep-indexed (fn [i msg] (when (= vm-key (:to msg)) i))
                                 queue))]
    (if (nil? idx)
      [state nil]
      (let [message (nth queue idx)
            next-queue (vec (concat (subvec queue 0 idx)
                                    (subvec queue (inc idx))))
            stream-with-queue (assoc (:stream state) :queue next-queue)
            [stream* _] (append-stream-datom stream-with-queue
                                             :stream/deliver
                                             {:message (:id message),
                                              :to vm-key,
                                              :ip (get-in message
                                                          [:summary :ip])})]
        [(assoc state :stream stream*) message]))))


(defn activate-owner-from-stream
  [state]
  (let [owner (:owner state)]
    (if (get state owner)
      [state false]
      (let [[state* message] (pop-continuation-for state owner)]
        (if message
          [(assoc state* owner (continuation->vm-state (:continuation message)))
           true]
          [state* false])))))


(defn create-loaded-vm
  [{:keys [bytecode pool reg-count]}]
  (-> (register/create-vm {:env vm/primitives})
      (vm/load-program {:bytecode bytecode, :pool pool, :reg-count reg-count})))


(defn stop-run-loop!
  []
  (when @run-raf-id
    (js/cancelAnimationFrame @run-raf-id)
    (reset! run-raf-id nil))
  (swap! app-state assoc :running false))


(defn invalidate-compiled-state!
  []
  (stop-run-loop!)
  (swap! app-state assoc
    :ast nil
    :ast-datoms nil
    :asm nil
    :bytecode nil
    :pool nil
    :source-map nil
    :reg-count nil
    :vm-a nil
    :vm-b nil
    :owner :vm-a
    :steps 0
    :handoffs 0
    :steps-since-handoff 0
    :stream (empty-stream)
    :completed? false
    :result nil
    :error nil))


(defn set-source-code!
  [next-source]
  (let [current-source (:source-code @app-state)]
    (when (not= next-source current-source)
      (invalidate-compiled-state!)
      (swap! app-state assoc :source-code next-source))))


(defn reset-execution!
  []
  (let [{:keys [bytecode pool reg-count]} @app-state]
    (when (and bytecode pool reg-count)
      (stop-run-loop!)
      (let [initial-vm (create-loaded-vm {:bytecode bytecode,
                                          :pool pool,
                                          :reg-count reg-count})]
        (swap! app-state assoc
          :vm-a initial-vm
          :vm-b nil
          :owner :vm-a
          :steps 0
          :handoffs 0
          :steps-since-handoff 0
          :stream (empty-stream)
          :completed? false
          :result nil
          :error nil)))))


(defn compile-source!
  []
  (stop-run-loop!)
  (let [source (:source-code @app-state)]
    (try
      (let [forms (reader/read-string (str "[" source "]"))
            ast (yang/compile-program forms)
            ast-datoms (vm/ast->datoms ast)
            {:keys [asm reg-count]} (register/ast-datoms->asm ast-datoms)
            {:keys [bytecode pool source-map]} (register/asm->bytecode asm)
            initial-vm (create-loaded-vm {:bytecode bytecode,
                                          :pool pool,
                                          :reg-count reg-count})]
        (swap! app-state assoc
          :ast ast
          :ast-datoms ast-datoms
          :asm asm
          :bytecode bytecode
          :pool pool
          :source-map source-map
          :reg-count reg-count
          :vm-a initial-vm
          :vm-b nil
          :owner :vm-a
          :steps 0
          :handoffs 0
          :steps-since-handoff 0
          :stream (empty-stream)
          :running false
          :completed? false
          :result nil
          :error nil))
      (catch :default e
        (swap! app-state assoc
          :error (str "Compile error: " (.-message e))
          :running false
          :completed? false
          :result nil)))))


(defn emit-handoff
  [state from stepped-vm]
  (let [to (other-vm from)]
    (-> state
        (enqueue-continuation from to stepped-vm)
        (assoc :owner to)
        (assoc to nil)
        (update :handoffs inc)
        (assoc :steps-since-handoff 0))))


(defn step-state
  [state]
  (if (or (nil? (:bytecode state)) (:completed? state))
    (assoc state :running false)
    (let [owner (:owner state)
          [state* just-activated?] (activate-owner-from-stream state)
          active-vm (get state* owner)]
      (cond just-activated? state*
            (nil? active-vm) (-> state*
                                 (assoc :running false)
                                 (assoc :error
                                          (str "No continuation available for "
                                               (name owner)
                                               ".")))
            :else (try (let [stepped (vm/step active-vm)
                             stepped-state (-> state*
                                               (assoc owner stepped)
                                               (update :steps inc)
                                               (update :steps-since-handoff inc)
                                               (assoc :error nil))]
                         (cond (vm/halted? stepped)
                                 (-> stepped-state
                                     (assoc :completed? true)
                                     (assoc :running false)
                                     (assoc :result (vm/value stepped)))
                               (>= (:steps-since-handoff stepped-state)
                                   (:handoff-interval stepped-state))
                                 (emit-handoff stepped-state owner stepped)
                               :else stepped-state))
                       (catch :default e
                         (-> state*
                             (assoc :running false)
                             (assoc :error (str "VM step error: "
                                                (.-message e))))))))))


(def steps-per-frame 80)


(declare run-frame!)


(defn step-once! [] (stop-run-loop!) (swap! app-state step-state))


(defn run-frame!
  []
  (swap! app-state (fn [state]
                     (loop [i 0
                            s state]
                       (if (or (>= i steps-per-frame)
                               (not (:running s))
                               (:completed? s))
                         s
                         (recur (inc i) (step-state s))))))
  (if (:running @app-state)
    (reset! run-raf-id (js/requestAnimationFrame run-frame!))
    (reset! run-raf-id nil)))


(defn toggle-run!
  []
  (if (:running @app-state)
    (stop-run-loop!)
    (when (:bytecode @app-state)
      (swap! app-state assoc :running true :error nil)
      (when @run-raf-id (js/cancelAnimationFrame @run-raf-id))
      (reset! run-raf-id (js/requestAnimationFrame run-frame!)))))


(defn vm->cesk
  [vm-state asm source-map]
  (when vm-state
    (let [instr-idx (get source-map (:ip vm-state))
          instr (when (number? instr-idx) (get asm instr-idx))]
      {:control {:ip (:ip vm-state),
                 :instruction-index instr-idx,
                 :instruction instr,
                 :halted (:halted vm-state),
                 :blocked (:blocked vm-state)},
       :environment (:env vm-state),
       :store (:store vm-state),
       :continuation {:depth (continuation-depth (:k vm-state)),
                      :frames (continuation-frames (:k vm-state))},
       :registers (:regs vm-state),
       :run-queue-count (count (or (:run-queue vm-state) [])),
       :wait-set-count (count (or (:wait-set vm-state) [])),
       :value (:value vm-state)})))


(defn asm-listing
  [asm active-idx]
  (if (seq asm)
    (str/join
      "\n"
      (map-indexed
        (fn [idx instr]
          (str (if (= idx active-idx) "=> " "   ") idx "  " (pr-str instr)))
        asm))
    "Compile source to view assembly."))


(defn stream-queue-summary
  [queue]
  (mapv (fn [{:keys [id summary]}]
          {:id id,
           :from (:from summary),
           :to (:to summary),
           :ip (:ip summary),
           :k-depth (:k-depth summary)})
    queue))


(defn card
  [title subtitle body]
  [:div
   {:style {:background "#0e1328",
            :border "1px solid #2d3b55",
            :box-shadow "0 10px 25px rgba(0,0,0,0.5)",
            :display "flex",
            :flex-direction "column",
            :min-height "0"}}
   [:div
    {:style {:background "#151b33",
             :padding "8px 10px",
             :border-bottom "1px solid #2d3b55",
             :display "flex",
             :flex-direction "column",
             :gap "2px"}}
    [:strong {:style {:color "#f1f5ff", :font-size "13px"}} title]
    [:span {:style {:color "#8b949e", :font-size "11px"}} subtitle]]
   [:div
    {:style {:padding "10px",
             :display "flex",
             :flex-direction "column",
             :gap "8px",
             :flex "1",
             :min-height "0"}} body]])


(defn vm-window
  [vm-key title border-color]
  (let [{:keys [asm source-map owner]} @app-state
        vm-state (get @app-state vm-key)
        active? (= owner vm-key)
        active-idx (when vm-state (get source-map (:ip vm-state)))
        cesk (or (vm->cesk vm-state asm source-map)
                 {:state :waiting-for-continuation})
        status (cond (nil? vm-state) "Waiting"
                     (:halted vm-state) "Halted"
                     active? "Running"
                     :else "Parked snapshot")]
    [:div
     {:style {:border (str "1px solid " border-color),
              :background "#0d1117",
              :display "flex",
              :flex-direction "column",
              :min-height "0"}}
     [:div
      {:style {:display "flex",
               :justify-content "space-between",
               :align-items "center",
               :padding "8px 10px",
               :border-bottom "1px solid #30363d",
               :background "#111827"}}
      [:strong {:style {:color "#f1f5ff", :font-size "12px"}} title]
      [:span
       {:style {:color (if active? "#58a6ff" "#8b949e"), :font-size "11px"}}
       status]]
     [:div
      {:style {:display "grid",
               :grid-template-rows "140px 1fr",
               :gap "8px",
               :padding "8px",
               :flex "1",
               :min-height "0"}}
      [codemirror-editor
       {:value (asm-listing asm active-idx),
        :read-only true,
        :style {:height "140px"}}]
      [codemirror-editor
       {:value (pretty-print cesk),
        :read-only true,
        :style {:height "100%"}}]]]))


(defn controls-panel
  []
  (let [{:keys [running bytecode handoff-interval]} @app-state]
    [:div
     {:style
        {:display "flex", :flex-wrap "wrap", :align-items "center", :gap "8px"}}
     [:button
      {:on-click compile-source!,
       :style {:background "#1f6feb",
               :color "#fff",
               :border "none",
               :padding "8px 12px",
               :border-radius "5px",
               :cursor "pointer",
               :font-size "12px"}} "Compile -> Bytecode"]
     [:button
      {:on-click reset-execution!,
       :disabled (nil? bytecode),
       :style {:background (if bytecode "#6e7681" "#333"),
               :color "#fff",
               :border "none",
               :padding "8px 12px",
               :border-radius "5px",
               :cursor (if bytecode "pointer" "not-allowed"),
               :font-size "12px"}} "Reset"]
     [:button
      {:on-click step-once!,
       :disabled (nil? bytecode),
       :style {:background (if bytecode "#238636" "#333"),
               :color "#fff",
               :border "none",
               :padding "8px 12px",
               :border-radius "5px",
               :cursor (if bytecode "pointer" "not-allowed"),
               :font-size "12px"}} "Step"]
     [:button
      {:on-click toggle-run!,
       :disabled (nil? bytecode),
       :style {:background (cond (nil? bytecode) "#333"
                                 running "#da3633"
                                 :else "#238636"),
               :color "#fff",
               :border "none",
               :padding "8px 12px",
               :border-radius "5px",
               :cursor (if bytecode "pointer" "not-allowed"),
               :font-size "12px"}} (if running "Pause" "Run")]
     [:label
      {:style {:display "flex",
               :align-items "center",
               :gap "6px",
               :font-size "12px",
               :color "#8b949e"}} "Handoff every"
      [:input
       {:type "number",
        :min 1,
        :value handoff-interval,
        :on-change (fn [e]
                     (let [raw (.. e -target -value)
                           parsed (js/parseInt raw 10)
                           interval (if (js/isNaN parsed) 1 (max 1 parsed))]
                       (swap! app-state assoc :handoff-interval interval))),
        :style {:width "60px",
                :background "#0a0f1e",
                :border "1px solid #2d3b55",
                :border-radius "4px",
                :color "#c5c6c7",
                :padding "4px 6px"}}] "steps"]]))


(defn main-view
  []
  (r/create-class
    {:display-name "continuation-stream-main",
     :component-did-mount
       (fn [] (when-not (:bytecode @app-state) (compile-source!))),
     :component-will-unmount (fn [] (stop-run-loop!)),
     :reagent-render
       (fn []
         (let [{:keys [source-code asm bytecode pool reg-count stream steps
                       handoffs owner completed? result error]}
                 @app-state
               vm-a (:vm-a @app-state)
               vm-b (:vm-b @app-state)
               bytecode-view
                 (if bytecode
                   (pretty-print {:reg-count reg-count,
                                  :pool pool,
                                  :bytecode bytecode,
                                  :asm (mapv vector (range (count asm)) asm)})
                   "Compile source to generate register VM bytecode.")
               queue-view (pretty-print (stream-queue-summary (:queue stream)))
               stream-view (pretty-print (:datoms stream))
               run-summary {:owner owner,
                            :steps steps,
                            :handoffs handoffs,
                            :vm-a-ip (when vm-a (:ip vm-a)),
                            :vm-b-ip (when vm-b (:ip vm-b)),
                            :completed? completed?,
                            :result result}]
           [:div
            {:style {:min-height "100vh",
                     :background "#060817",
                     :color "#c5c6c7",
                     :padding "16px",
                     :display "flex",
                     :flex-direction "column",
                     :gap "12px",
                     :box-sizing "border-box"}}
            [:div
             {:style {:display "flex",
                      :justify-content "space-between",
                      :align-items "center",
                      :gap "12px",
                      :flex-wrap "wrap"}}
             [:h1 {:style {:margin 0, :font-size "1.4rem", :color "#f1f5ff"}}
              "Two Register VMs with Continuation Stream"]
             [:div {:style {:font-size "12px", :color "#8b949e"}}
              "Use the hamburger menu to switch demos."]] [controls-panel]
            [:div
             {:style {:display "grid",
                      :grid-template-columns "repeat(2, minmax(0, 1fr))",
                      :grid-template-rows "300px 360px 320px",
                      :gap "12px",
                      :flex "1",
                      :min-height "0"}}
             [card "Source" "CodeMirror editor: Clojure code"
              [codemirror-editor
               {:value source-code,
                :on-change set-source-code!,
                :style {:height "100%"}}]]
             [card "Register Bytecode"
              "Compiled from source via yang -> AST datoms -> register asm."
              [codemirror-editor
               {:value bytecode-view,
                :read-only true,
                :style {:height "100%"}}]]
             [card "VM A"
              "CESK state snapshot while continuation ownership changes."
              [vm-window :vm-a "VM A" "#3b82f6"]]
             [card "VM B"
              "Receives continuations from stream and continues execution."
              [vm-window :vm-b "VM B" "#22c55e"]]
             [:div
              {:style {:grid-column "1 / span 2",
                       :display "grid",
                       :grid-template-columns "repeat(3, minmax(0, 1fr))",
                       :gap "12px",
                       :min-height "0"}}
              [card "Stream Queue" "Continuation packets currently in flight."
               [codemirror-editor
                {:value queue-view, :read-only true, :style {:height "100%"}}]]
              [card "Stream Datoms"
               "Append-only stream facts for continuation emit/deliver events."
               [codemirror-editor
                {:value stream-view, :read-only true, :style {:height "100%"}}]]
              [card "Run Summary" "Execution totals and final value."
               [codemirror-editor
                {:value (pretty-print run-summary),
                 :read-only true,
                 :style {:height "100%"}}]]]]
            (when error
              [:div
               {:style {:background "rgba(255,0,0,0.2)",
                        :border "1px solid #da3633",
                        :padding "8px",
                        :font-size "12px",
                        :color "#f85149"}} error])]))}))
