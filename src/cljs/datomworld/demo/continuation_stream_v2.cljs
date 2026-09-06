(ns datomworld.demo.continuation-stream-v2
  "Two Yin VM v2 evaluators cooperatively run one program, passing
   continuations through a DaoStream v2 medium.

   The v1 demo stepped a register VM and a stack VM and handed the live
   continuation between them. `yin.vm.v2` is the ast-walker slice only —
   `register`, `stack`, `semantic` and `space` are not ported — so the two
   endpoints here are the same evaluator and the demo's question changes from
   \"can two different VM shapes share one continuation\" to \"can one
   evaluator's live continuation be lifted out, carried over a v2 medium, and
   resumed by another instance of the same evaluator\".

   What the port changed:

   - Programs are loaded with `ast-walker/vm-load-program` instead of queued
     on a fabricated `:in-stream`. The v2 VM owns no program medium; this demo
     loads explicitly because it animates one `vm/step` at a time, which is
     not the observer composition's run-to-halt cadence.
   - Continuations travel over `dao.stream.v2` (see
     `datomworld.demo.continuation-transport-v2`): batch appends, opaque
     cursors minted at `:dao.stream/oldest`, correlation by a composition
     sequence number instead of a stream position.
   - The handoff payload is one key list (see
     `datomworld.demo.continuation-handoff-v2`), and `:make-stream`,
     `:primitives` and `:modules` come from the receiving VM rather than
     travelling with the continuation.
   - The bytecode panels show canonical datoms. There is no v2 assembler."
  (:require ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/theme-one-dark" :refer [oneDark]]
            ["@codemirror/view" :refer [EditorView]]
            ["@nextjournal/lang-clojure" :refer [clojure]]
            ["codemirror" :refer [basicSetup]]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [dao.stream.v2.ringbuffer :as ringbuffer]
            [datomworld.demo.continuation-handoff-v2 :as handoff]
            [datomworld.demo.continuation-transport-v2 :as ct]
            [datomworld.demo.responsive :as responsive]
            [reagent.core :as r]
            [yang.clojure :as yang]
            [yin.demo.utils :as demo.utils]
            [yin.vm.v2 :as vm]
            [yin.vm.v2.ast-walker :as ast-walker]
            [yin.vm.v2.module :as module]))


(def source-example
  "(defn sum-to
  [n]
  (if (= n 0)
    0
    (+ n (sum-to (- n 1)))))

;; Example: (sum-to 3) => 6 (1 + 2 + 3)
(sum-to 100)")


(def ^:private pretty-print demo.utils/pretty-print)


;; =============================================================================
;; The composition
;; =============================================================================

(defn- make-stream
  "The `:make-stream` each VM is handed: one DaoStream v2 ring buffer per
   call. A nil capacity is the VM's default, not an unbounded stream."
  [capacity]
  (ringbuffer/create!
    {:dao.stream/type ringbuffer/transport-type,
     ringbuffer/capacity-key (or capacity vm/default-stream-capacity)}))


(defn make-vm
  "Construct one v2 evaluator for this demo. The composition supplies the
   transport, the primitives and the module registry; there is no default for
   the transport, and a VM built without one cannot create streams."
  [_vm-key]
  (ast-walker/create-vm
    {:primitives vm/primitives,
     :modules (module/register-stream-module (module/default-registry)),
     :make-stream make-stream}))


(defn create-loaded-vm
  "Load one program onto a fresh VM. This is the loader host composition
   hands to observer coordination; the demo calls it directly because it
   animates single steps rather than running to halt."
  [datoms]
  (ast-walker/vm-load-program (make-vm :vm-a) (vec datoms)))


(defn create-initial-vm
  [state vm-key]
  (when (:datoms state) (create-loaded-vm (:datoms state))))


(defn codemirror-editor
  [{:keys [value on-change read-only auto-scroll-bottom]}]
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
             (reset! view-ref view)
             (when auto-scroll-bottom
               (js/requestAnimationFrame
                 (fn []
                   (let [scroll-dom (.-scrollDOM view)]
                     (set! (.-scrollTop scroll-dom)
                           (.-scrollHeight scroll-dom))))))))),
       :component-did-update
       (fn [this _]
         (let [{:keys [value auto-scroll-bottom]} (r/props this)]
           (when-let [^js view @view-ref]
             (let [next-value (or value "")
                   current-value (.. view -state -doc toString)]
               (when (not= next-value current-value)
                 (.dispatch view
                            #js {:changes #js
                                          {:from 0,
                                           :to (.. view -state -doc -length),
                                           :insert next-value}})
                 (when auto-scroll-bottom
                   (js/requestAnimationFrame
                     (fn []
                       (let [scroll-dom (.-scrollDOM view)]
                         (set! (.-scrollTop scroll-dom)
                               (.-scrollHeight scroll-dom))))))))))),
       :component-will-unmount (fn [_]
                                 (when-let [^js view @view-ref]
                                   (.destroy view)
                                   (reset! view-ref nil))),
       :reagent-render
       (fn [{:keys [style]}]
         [:div
          {:ref #(reset! el-ref %),
           :style (merge {:height "100%",
                          :width "100%",
                          :border "1px solid #2d3b55",
                          :overflow "hidden"}
                         style)}])})))


(defonce app-state
  (let [{:keys [k-stream cursors pending-ks]} (ct/init-state [:vm-a :vm-b])]
    (r/atom
      {:source-code source-example,
       :ast nil,
       :datoms nil,
       :vm-a nil,
       :vm-b nil,
       :owner :vm-a,
       :steps 0,
       :handoffs 0,
       :steps-since-handoff 0,
       :handoff-interval 50,
       :running false,
       :completed? false,
       :result nil,
       :k-stream k-stream,
       :cursors cursors,
       :pending-ks pending-ks,
       :error nil})))


(defonce run-raf-id (atom nil))


;; =============================================================================
;; Observing VM state
;; =============================================================================

(defn vm-control-counter
  "v1's control field was an instruction index. The v2 ast-walker's control is
   the AST node being evaluated, so the demo shows its node type."
  [_vm-key vm-state]
  (when vm-state (:type (:control vm-state))))


(defn vm-k-depth
  [_ vm-state]
  (when vm-state (count (or (vm/continuation vm-state) []))))


(defn v2-k-frames
  "Summarize the reified continuation's frames. A v2 frame is
   {:type ... :frame <ast-node> :env ... :next ...}."
  [frames]
  (->> (or frames [])
       (take 12)
       (mapv (fn [frame]
               {:type (:type frame),
                :node-type (get-in frame [:frame :type]),
                :bound (count (or (:env frame) {}))}))))


(defn vm-state->k
  [vm-key vm-state]
  (handoff/vm-state->handoff vm-key vm-state))


(defn k->vm-state
  [vm-key k]
  (handoff/handoff->vm-state vm-key k make-vm))


(defn other-vm
  [vm-key]
  (if (= vm-key :vm-a) :vm-b :vm-a))


(defn- queue-vm
  "Load a program batch onto a VM. v2 loads work directly; no `:in-stream` is
   attached and no `{:position 0}` cursor is fabricated."
  [vm-state datoms]
  (assoc (ast-walker/vm-load-program vm-state (vec datoms)) :halted? false))


(defn enqueue-k
  [state from to from-vm-state]
  (let [recipient-vm-state (or (get state to) (create-initial-vm state to))
        k (vm-state->k to recipient-vm-state)
        summary {:kind :k,
                 :from from,
                 :to to,
                 :control (vm-control-counter from from-vm-state),
                 :k-depth (vm-k-depth from from-vm-state)}]
    (ct/enqueue-k state summary k)))


(defn activate-owner-from-stream
  [state]
  (let [owner (:owner state)]
    (if (get state owner)
      [state false]
      (let [[state* message] (ct/consume-k-for state owner)]
        (if message
          [(assoc state* owner (k->vm-state owner (:k message))) true]
          [state* false])))))


(defn stop-run-loop!
  []
  (when @run-raf-id
    (js/cancelAnimationFrame @run-raf-id)
    (reset! run-raf-id nil))
  (swap! app-state assoc :running false))


(defn invalidate-compiled-state!
  []
  (stop-run-loop!)
  (let [{:keys [k-stream cursors pending-ks]} (ct/init-state [:vm-a :vm-b])]
    (swap! app-state assoc
           :ast nil
           :datoms nil
           :vm-a nil
           :vm-b nil
           :owner :vm-a
           :steps 0
           :handoffs 0
           :steps-since-handoff 0
           :k-stream k-stream
           :cursors cursors
           :pending-ks pending-ks
           :completed? false
           :result nil
           :error nil)))


(defn set-source-code!
  [next-source]
  (let [current-source (:source-code @app-state)]
    (when (not= next-source current-source)
      (invalidate-compiled-state!)
      (swap! app-state assoc :source-code next-source))))


(defn reset-execution!
  []
  (let [{:keys [datoms]} @app-state]
    (when datoms
      (stop-run-loop!)
      (let [{:keys [k-stream cursors pending-ks]} (ct/init-state [:vm-a :vm-b])
            initial-vm (create-loaded-vm datoms)]
        (swap! app-state assoc
               :vm-a initial-vm
               :vm-b nil
               :owner :vm-a
               :steps 0
               :handoffs 0
               :steps-since-handoff 0
               :k-stream k-stream
               :cursors cursors
               :pending-ks pending-ks
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
            datoms (vec (vm/ast->datoms ast))
            {:keys [k-stream cursors pending-ks]} (ct/init-state [:vm-a :vm-b])
            initial-vm (create-loaded-vm datoms)]
        (swap! app-state assoc
               :ast ast
               :datoms datoms
               :vm-a initial-vm
               :vm-b nil
               :owner :vm-a
               :steps 0
               :handoffs 0
               :steps-since-handoff 0
               :k-stream k-stream
               :cursors cursors
               :pending-ks pending-ks
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
        (enqueue-k from to stepped-vm)
        (assoc :owner to)
        (assoc to nil)
        (update :handoffs inc)
        (assoc :steps-since-handoff 0))))


(defn step-state
  [state]
  (if (or (nil? (:datoms state))
          (:completed? state))
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


(defn step-once!
  []
  (stop-run-loop!) (swap! app-state step-state))


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
    (when (:datoms @app-state)
      (swap! app-state assoc :running true :error nil)
      (when @run-raf-id (js/cancelAnimationFrame @run-raf-id))
      (reset! run-raf-id (js/requestAnimationFrame run-frame!)))))


(defn vm->cesk
  [vm-key vm-state]
  (when vm-state
    (let [continuation (vm/continuation vm-state)]
      {:control {:node-type (vm-control-counter vm-key vm-state),
                 :halted? (:halted? vm-state),
                 :blocked? (:blocked? vm-state)},
       :environment (:env vm-state),
       :store (:store vm-state),
       :continuation {:depth (count (or continuation [])),
                      :frames (v2-k-frames continuation)},
       :parked (count (or (:parked vm-state) {})),
       :ready-queue-count (count (or (:ready-queue vm-state) [])),
       :wait-set-count (count (or (:wait-set vm-state) [])),
       :value (:value vm-state)})))


(defn datom-listing
  [datoms]
  (if (seq datoms)
    (str/join "\n" (map-indexed (fn [i d] (str i "  " (pr-str d))) datoms))
    "Compile source to view the canonical datoms."))


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
  (let [{:keys [owner]} @app-state
        vm-state (get @app-state vm-key)
        active? (= owner vm-key)
        cesk (or (vm->cesk vm-key vm-state)
                 {:state :waiting-for-continuation})
        status (cond (nil? vm-state) "Waiting"
                     (:halted? vm-state) (if (seq (:parked vm-state))
                                           "Parked"
                                           "Halted")
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
               :grid-template-rows "1fr",
               :gap "8px",
               :padding "8px",
               :flex "1",
               :min-height "0"}}
      [codemirror-editor
       {:value (pretty-print cesk),
        :read-only true,
        :style {:height "100%"}}]]]))


(defn controls-panel
  []
  (let [{:keys [running handoff-interval]} @app-state
        compiled? (boolean (:datoms @app-state))]
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
               :font-size "12px"}} "Compile -> Datoms"]
     [:button
      {:on-click reset-execution!,
       :disabled (not compiled?),
       :style {:background (if compiled? "#6e7681" "#333"),
               :color "#fff",
               :border "none",
               :padding "8px 12px",
               :border-radius "5px",
               :cursor (if compiled? "pointer" "not-allowed"),
               :font-size "12px"}} "Reset"]
     [:button
      {:on-click step-once!,
       :disabled (not compiled?),
       :style {:background (if compiled? "#238636" "#333"),
               :color "#fff",
               :border "none",
               :padding "8px 12px",
               :border-radius "5px",
               :cursor (if compiled? "pointer" "not-allowed"),
               :font-size "12px"}} "Step"]
     [:button
      {:on-click toggle-run!,
       :disabled (not compiled?),
       :style {:background (cond (not compiled?) "#333"
                                 running "#da3633"
                                 :else "#238636"),
               :color "#fff",
               :border "none",
               :padding "8px 12px",
               :border-radius "5px",
               :cursor (if compiled? "pointer" "not-allowed"),
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
    {:display-name "continuation-stream-v2-main",
     :component-did-mount (fn []
                            (when-not (:datoms @app-state)
                              (compile-source!))),
     :component-will-unmount (fn [] (stop-run-loop!)),
     :reagent-render
     (fn []
       (let [{:keys [source-code datoms k-stream cursors steps handoffs owner
                     completed? result error]}
             @app-state
             vm-a (:vm-a @app-state)
             vm-b (:vm-b @app-state)
             program-view
             (if datoms
               (pretty-print {:ast-datoms (count datoms),
                              :datoms (take 40 datoms)})
               "Compile source to generate canonical datoms.")
             queue-view (pretty-print (ct/in-flight-summary k-stream cursors))
             stream-view
             (pretty-print
               (let [[events _] (ct/read-events-from k-stream
                                                     (ct/oldest-cursor!
                                                       k-stream))]
                 (take-last 200 events)))
             run-summary
             {:owner owner,
              :steps steps,
              :handoffs handoffs,
              :vm-a-control (vm-control-counter :vm-a vm-a),
              :vm-b-control (vm-control-counter :vm-b vm-b),
              :vm-a-k-depth (vm-k-depth :vm-a vm-a),
              :vm-b-k-depth (vm-k-depth :vm-b vm-b),
              :pending-ks (count (:pending-ks @app-state)),
              :transport-capacity ct/transport-capacity,
              :completed? completed?,
              :result result}]
         [:div
          {:style {:min-height "100vh",
                   :background "#060817",
                   :color "#c5c6c7",
                   :padding "96px 16px 24px",
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
            "Yin VM v2 Continuation Stream"]] [controls-panel]
          [:div
           {:style {:display "grid",
                    :grid-template-columns (responsive/auto-fit-grid 320),
                    :gap "12px",
                    :flex "1",
                    :min-height "0"}}
           [:div {:style {:min-height (responsive/fluid-height 280 42 380)}}
            [card "Source" "CodeMirror editor: Clojure code"
             [codemirror-editor
              {:value source-code,
               :on-change set-source-code!,
               :style {:height "100%"}}]]]
           [:div {:style {:min-height (responsive/fluid-height 280 42 380)}}
            [card "Canonical Datoms"
             "Compiled from source via yang -> AST datoms (the v2 program representation)."
             [codemirror-editor
              {:value program-view,
               :read-only true,
               :style {:height "100%"}}]]]
           [:div {:style {:min-height (responsive/fluid-height 320 50 460)}}
            [card "VM-A (ast-walker)"
             "CESK state while ownership changes."
             [vm-window :vm-a "VM-A" "#3b82f6"]]]
           [:div {:style {:min-height (responsive/fluid-height 320 50 460)}}
            [card "VM-B (ast-walker)"
             "CESK state while ownership changes."
             [vm-window :vm-b "VM-B" "#22c55e"]]]
           [:div {:style {:min-height (responsive/fluid-height 240 34 320)}}
            [card "In-Flight"
             "Continuations between each VM cursor and stream head."
             [codemirror-editor
              {:value queue-view, :read-only true, :style {:height "100%"}}]]]
           [:div {:style {:min-height (responsive/fluid-height 240 34 320)}}
            [card "Stream Events"
             "Append-only DaoStream v2 events for continuation emit/deliver."
             [codemirror-editor
              {:value stream-view,
               :read-only true,
               :auto-scroll-bottom true,
               :style {:height "100%"}}]]]
           [:div {:style {:min-height (responsive/fluid-height 240 34 320)}}
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
