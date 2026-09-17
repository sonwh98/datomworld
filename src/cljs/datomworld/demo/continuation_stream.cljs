(ns datomworld.demo.continuation-stream
  "Two semantic VMs cooperatively run one program, shipping the continuation
   between them through a DaoStream v2 medium.

   Source compiles through yang to a Universal AST, which
   `yin.vm.linearize` lowers to one `:yin.code/*` segment. VM-A loads it
   and steps; every handoff interval the owner's registers — segment, pc,
   accumulator, operand stack, environment, continuation frames — are
   appended to the medium as one batch together with the segment's datoms
   (`datomworld.demo.continuation-handoff`, §7 of yin.vm.semantic.md).

   Nothing is handed over off-stream. The registers travel as EDN text, and
   the receiver is a fresh VM that loads the shipped segment through the
   ordinary loader and installs the registers it read back, so the only
   thing the two machines share is the medium. Primitives, modules, and the
   FFI pair are each receiving composition's own.

   The demo loads explicitly instead of through the observer composition
   because it animates one `vm/step` at a time. A handoff happens only at an
   instruction boundary with no wait set, ready queue, parked continuation,
   or stream handle in the sender's store.

   There is no namespace-global state. Each mounted view owns a session: its
   state, its host-event medium, and its interpreter's animation frame. Host
   adapters only append clicks and edits to that medium as events; the
   session interpreter, driven by animation frames, reads them, folds them
   through `handle-event`, and advances a live run."
  (:require ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/theme-one-dark" :refer [oneDark]]
            ["@codemirror/view" :refer [EditorView]]
            ["@nextjournal/lang-clojure" :refer [clojure]]
            ["codemirror" :refer [basicSetup]]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ringbuffer]
            [datomworld.demo.continuation-handoff :as handoff]
            [datomworld.demo.continuation-transport :as ct]
            [datomworld.demo.responsive :as responsive]
            [reagent.core :as r]
            [yang.clojure :as yang]
            [yin.demo.utils :as demo.utils]
            [yin.vm :as vm]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]))


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
  (semantic/create-vm
    {:primitives vm/primitives,
     :modules (module/register-stream-module (module/default-registry)),
     :make-stream make-stream}))


(defn create-loaded-vm
  "Load one code segment onto a fresh VM. This is the loader host
   composition hands to observer coordination; the demo calls it directly
   because it animates single steps rather than running to halt."
  [code-datoms]
  (semantic/vm-load-program (make-vm :vm-a) (vec code-datoms)))


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


(defn- fresh-run
  "`state` with no VMs and a fresh continuation medium: the run starts over."
  [state]
  (merge state
         (ct/init-state [:vm-a :vm-b])
         {:vm-a nil,
          :vm-b nil,
          :owner :vm-a,
          :steps 0,
          :handoffs 0,
          :steps-since-handoff 0,
          :running false,
          :completed? false,
          :result nil,
          :error nil}))


(defn initial-state
  []
  (fresh-run {:source-code source-example,
              :ast nil,
              :datoms nil,
              :handoff-interval 50}))


;; =============================================================================
;; Observing VM state
;; =============================================================================

(defn vm-control-counter
  "The semantic VM's control is `{:segment id :pc n}`; the pc is the counter."
  [_vm-key vm-state]
  (when vm-state (get-in vm-state [:control :pc])))


(defn vm-k-depth
  [_ vm-state]
  (when vm-state (count (or (vm/continuation vm-state) []))))


(defn v2-k-frames
  "Summarize the continuation's frames, innermost last. A semantic frame is
   {:type :return :segment id :pc n :env E :stack-base n}."
  [frames]
  (->> (or frames [])
       (take-last 12)
       (mapv (fn [frame]
               {:type (:type frame),
                :return-pc (:pc frame),
                :bound (count (or (:env frame) {}))}))))


(defn other-vm
  [vm-key]
  (if (= vm-key :vm-a) :vm-b :vm-a))


(defn enqueue-k
  "Ship the sender's continuation: the segment datoms and its registers, as
   one batch on the medium."
  [state from to from-vm-state]
  (let [summary {:kind :k,
                 :from from,
                 :to to,
                 :control (vm-control-counter from from-vm-state),
                 :k-depth (vm-k-depth from from-vm-state)}]
    (ct/enqueue-batch state
                      summary
                      (handoff/continuation-datoms (:datoms state)
                                                   from-vm-state))))


(defn activate-owner-from-stream
  "Rebuild the owner from the next batch addressed to it, if one has
   arrived."
  [state]
  (let [owner (:owner state)]
    (if (get state owner)
      [state false]
      (let [[state* message] (ct/consume-k-for state owner)]
        (if message
          [(assoc state*
                  owner
                  (handoff/datoms->semantic-vm (:batch message)
                                               #(make-vm owner)))
           true]
          [state* false])))))


;; =============================================================================
;; State transitions
;; =============================================================================

(defn invalidate-compiled-state
  [state]
  (assoc (fresh-run state) :ast nil :datoms nil))


(defn reset-execution
  [state]
  (if-let [datoms (:datoms state)]
    (assoc (fresh-run state) :vm-a (create-loaded-vm datoms))
    state))


(defn compile-source
  [state]
  (try
    (let [forms (reader/read-string (str "[" (:source-code state) "]"))
          ast (yang/compile-program forms)
          datoms (linearize/lower-ast ast)]
      (assoc (fresh-run state)
             :ast ast
             :datoms datoms
             :vm-a (create-loaded-vm datoms)))
    (catch :default e
      (assoc state
             :error (str "Compile error: " (.-message e))
             :running false
             :completed? false
             :result nil))))


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
                               (and (>= (:steps-since-handoff stepped-state)
                                        (:handoff-interval stepped-state))
                                    (handoff/shippable? stepped))
                               (emit-handoff stepped-state owner stepped)
                               :else stepped-state))
                       (catch :default e
                         (-> state*
                             (assoc :running false)
                             (assoc :error (str "VM step error: "
                                                (.-message e))))))))))


(def steps-per-frame 80)


(defn run-frame
  [state]
  (loop [i 0
         s state]
    (if (or (>= i steps-per-frame)
            (not (:running s))
            (:completed? s))
      s
      (recur (inc i) (step-state s)))))


(defn handle-event
  "Fold one host event into the session state."
  [state {:keys [event] :as e}]
  (case event
    :compile (compile-source state)
    :reset (reset-execution state)
    :step (step-state (assoc state :running false))
    :toggle-run (cond (:running state) (assoc state :running false)
                      (:datoms state) (assoc state :running true :error nil)
                      :else state)
    :set-source (if (= (:source e) (:source-code state))
                  state
                  (assoc (invalidate-compiled-state state)
                         :source-code (:source e)))
    :set-interval (assoc state :handoff-interval (:interval e))
    :unmount (assoc state :running false :unmounted? true)
    state))


;; =============================================================================
;; The session: one mounted composition's state, events, and frame
;; =============================================================================

(def ^:private host-event-attr :demo.continuation/host-event)


(defn make-session
  "Everything one mounted view owns. Host events are appended to `:events`
   and read back from `:cursor`; `:raf-id` is the interpreter's pending
   frame, if any."
  []
  (let [events (:dao.stream/handle (ct/make-medium))]
    {:state (r/atom (initial-state)),
     :events events,
     :cursor (atom (ct/oldest-cursor! events)),
     :raf-id (atom nil)}))


(defn- deposit!
  "The only operation a host adapter is handed: append one host event to
   `events` and return nil."
  [events event]
  (let [outcome (:dao.stream/outcome
                  (stream/append! events [[0 host-event-attr event 0 0]]))]
    (when-not (= :dao.stream/ok outcome)
      (throw (ex-info "Host event not appended"
                      {:event event, :outcome outcome}))))
  nil)


(def host-transforms
  "Each entry turns the host's arguments into one plain-data event. DOM
   events are classified here and never cross onto the medium."
  {:mount (constantly {:event :compile}),
   :unmount (constantly {:event :unmount}),
   :compile (constantly {:event :compile}),
   :reset (constantly {:event :reset}),
   :step (constantly {:event :step}),
   :toggle-run (constantly {:event :toggle-run}),
   :set-source (fn [source] {:event :set-source, :source source}),
   :set-interval (fn [^js dom-event]
                   (let [parsed (js/parseInt (.. dom-event -target -value) 10)]
                     {:event :set-interval,
                      :interval (if (js/isNaN parsed) 1 (max 1 parsed))}))})


(defn make-adapter
  "A map of host entry points over `deposit`. Each entry transforms the
   host's arguments to one event and deposits it; it returns nil to the host
   and invokes no application code."
  [deposit transforms]
  (into {}
        (map (fn [[k transform]]
               [k (fn [& args] (deposit (apply transform args)) nil)]))
        transforms))


(defn- tick!
  "One frame of the session interpreter: fold every host event now visible
   past the cursor, advance a live run, and render. It schedules its next
   frame until it has read `:unmount`."
  [{:keys [state events cursor raf-id], :as session}]
  (let [[datoms cursor'] (ct/read-events-from events @cursor)]
    (reset! cursor cursor')
    (swap! state
           #(run-frame (reduce handle-event % (map (fn [[_ _ v]] v) datoms)))))
  ;; Render now, so no render ever sees state behind the events already read.
  (r/flush)
  (reset! raf-id (when-not (:unmounted? @state)
                   (js/requestAnimationFrame (fn [_] (tick! session))))))


(defn start-interpreter!
  "Drive the session interpreter from animation frames, independently of the
   host events it consumes."
  [{:keys [raf-id], :as session}]
  (when-not @raf-id
    (reset! raf-id (js/requestAnimationFrame (fn [_] (tick! session))))))


;; =============================================================================
;; Views
;; =============================================================================

(defn vm->cesk
  [vm-key vm-state]
  (when vm-state
    (let [continuation (vm/continuation vm-state)]
      {:control {:segment (get-in vm-state [:control :segment]),
                 :pc (vm-control-counter vm-key vm-state),
                 :stack (:stack vm-state),
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
    "Compile source to view the code datoms."))


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
  [state vm-key title border-color]
  (let [{:keys [owner]} state
        vm-state (get state vm-key)
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
  [state adapter]
  (let [{:keys [running handoff-interval]} state
        compiled? (boolean (:datoms state))]
    [:div
     {:style
      {:display "flex", :flex-wrap "wrap", :align-items "center", :gap "8px"}}
     [:button
      {:on-click (:compile adapter),
       :style {:background "#1f6feb",
               :color "#fff",
               :border "none",
               :padding "8px 12px",
               :border-radius "5px",
               :cursor "pointer",
               :font-size "12px"}} "Compile -> Datoms"]
     [:button
      {:on-click (:reset adapter),
       :disabled (not compiled?),
       :style {:background (if compiled? "#6e7681" "#333"),
               :color "#fff",
               :border "none",
               :padding "8px 12px",
               :border-radius "5px",
               :cursor (if compiled? "pointer" "not-allowed"),
               :font-size "12px"}} "Reset"]
     [:button
      {:on-click (:step adapter),
       :disabled (not compiled?),
       :style {:background (if compiled? "#238636" "#333"),
               :color "#fff",
               :border "none",
               :padding "8px 12px",
               :border-radius "5px",
               :cursor (if compiled? "pointer" "not-allowed"),
               :font-size "12px"}} "Step"]
     [:button
      {:on-click (:toggle-run adapter),
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
        :on-change (:set-interval adapter),
        :style {:width "60px",
                :background "#0a0f1e",
                :border "1px solid #2d3b55",
                :border-radius "4px",
                :color "#c5c6c7",
                :padding "4px 6px"}}] "steps"]]))


(defn main-view
  []
  (let [session (make-session)
        adapter (make-adapter (partial deposit! (:events session))
                              host-transforms)]
    (r/create-class
      {:display-name "continuation-stream-main",
       :component-did-mount (fn []
                              ((:mount adapter))
                              (start-interpreter! session)),
       :component-will-unmount (:unmount adapter),
       :reagent-render
       (fn []
         (let [state @(:state session)
               {:keys [source-code datoms k-stream cursors steps handoffs owner
                       completed? result error vm-a vm-b]}
               state
               program-view
               (if datoms
                 (pretty-print {:code-datoms (count datoms),
                                :datoms (take 40 datoms)})
                 "Compile source to generate code datoms.")
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
                :pending-ks (count (:pending-ks state)),
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
              "Semantic VM Continuation Stream"]] [controls-panel state adapter]
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
                 :on-change (:set-source adapter),
                 :style {:height "100%"}}]]]
             [:div {:style {:min-height (responsive/fluid-height 280 42 380)}}
              [card "Code Segment"
               "yang -> AST -> linearize: the :yin.code/* datoms shipped with every handoff."
               [codemirror-editor
                {:value program-view,
                 :read-only true,
                 :style {:height "100%"}}]]]
             [:div {:style {:min-height (responsive/fluid-height 320 50 460)}}
              [card "VM-A (semantic)"
               "CESK state while ownership changes."
               [vm-window state :vm-a "VM-A" "#3b82f6"]]]
             [:div {:style {:min-height (responsive/fluid-height 320 50 460)}}
              [card "VM-B (semantic)"
               "CESK state while ownership changes."
               [vm-window state :vm-b "VM-B" "#22c55e"]]]
             [:div {:style {:min-height (responsive/fluid-height 240 34 320)}}
              [card "In-Flight"
               "Continuations between each VM cursor and stream head."
               [codemirror-editor
                {:value queue-view, :read-only true, :style {:height "100%"}}]]]
             [:div {:style {:min-height (responsive/fluid-height 240 34 320)}}
              [card "Stream Events"
               "Handoff batches on the medium: segment datoms, EDN registers, summary."
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
                        :color "#f85149"}} error])]))})))
