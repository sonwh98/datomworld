(ns datomworld.demo.compilation-pipeline-v2
  "The Yin compilation pipeline on Yin VM v2: Source -> AST -> canonical
   datoms -> ast-walker execution.

   The v1 demo (`datomworld.demo.compilation-pipeline`) compiled the same
   source through four evaluators — stack, register, semantic and ast-walker —
   and showed each one's bytecode beside the shared datom store, with a
   `dao.space` query panel over the transacted datoms.

   `yin.vm.v2` ships one evaluator. The divergence register states it
   directly: `semantic`, `register`, `stack`, `space`, `macro` and `wasm` are
   not ported, and user macros throw. So this pipeline has one compilation
   path and one execution path:

   - **Source -> AST** is unchanged: `yang.clojure/compile-program`.
   - **AST -> canonical datoms** is unchanged: `vm/ast->datoms` is shared
     kernel, reused by v2 rather than forked.
   - **Execution** is `yin.vm.v2.ast-walker`, constructed with a
     composition-supplied `:make-stream` over the DaoStream v2 ring buffer.
     There is no bytecode, no assembler, no pool and no source map to show.
   - Programs are loaded with `ast-walker/vm-load-program`, the loader host
     composition hands to observer coordination. The demo calls it directly
     because it animates one `vm/step` at a time; the VM owns no program
     medium of its own and accepts no `:in-stream`.

   What the v1 demo showed that this one cannot: register and stack
   instruction listings with active-instruction highlighting, the semantic
   evaluator's AST database, and `dao.space` queries over the transacted
   datoms. Those are v1 evaluator features, not v2 gaps in a demo."
  (:require ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/theme-one-dark" :refer [oneDark]]
            ["@codemirror/view" :refer [EditorView]]
            ["@nextjournal/lang-clojure" :refer [clojure]]
            ["codemirror" :refer [basicSetup]]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [dao.stream.v2.ringbuffer :as ringbuffer]
            [datomworld.demo.responsive :as responsive]
            [reagent.core :as r]
            [yang.clojure :as yang]
            [yin.demo.utils :as demo.utils]
            [yin.vm.v2 :as vm]
            [yin.vm.v2.ast-walker :as ast-walker]
            [yin.vm.v2.module :as module]))


(def ^:private pretty-print demo.utils/pretty-print)


(def default-source
  "(defn fact [n acc]
  (if (= n 0)
    acc
    (fact (- n 1) (* n acc))))

(fact 10 1)")


;; =============================================================================
;; The composition
;; =============================================================================

(defn- make-stream
  "The `:make-stream` this composition hands the VM: one DaoStream v2 ring
   buffer per call. A nil capacity is the VM's default, not an unbounded
   stream; v2 has no unbounded mode."
  [capacity]
  (ringbuffer/create!
    {:dao.stream/type ringbuffer/transport-type,
     ringbuffer/capacity-key (or capacity vm/default-stream-capacity)}))


(defn- make-vm
  []
  (ast-walker/create-vm
    {:primitives vm/primitives,
     :modules (module/register-stream-module (module/default-registry)),
     :make-stream make-stream}))


(defn- load-program!
  "Load one canonical datom batch onto a fresh VM."
  [datoms]
  (ast-walker/vm-load-program (make-vm) (vec datoms)))


(defonce app-state
  (r/atom {:source default-source,
           :ast nil,
           :datom-groups nil,
           :datoms nil,
           :walker nil,
           :walker-running false,
           :walker-result nil,
           :steps 0,
           :error nil}))


(defonce run-raf-id (atom nil))


(defn stop-run-loop!
  []
  (when @run-raf-id
    (js/cancelAnimationFrame @run-raf-id)
    (reset! run-raf-id nil))
  (swap! app-state assoc :walker-running false))


(defn set-source!
  [next-source]
  (stop-run-loop!)
  (swap! app-state assoc
         :source next-source
         :ast nil
         :datom-groups nil
         :datoms nil
         :walker nil
         :walker-result nil
         :steps 0
         :error nil))


(defn compile-source!
  []
  (stop-run-loop!)
  (try (let [source (:source @app-state)
             forms (reader/read-string (str "[" source "]"))
             ast (yang/compile-program forms)
             datoms (vec (vm/ast->datoms ast))
             walker (load-program! datoms)]
         (swap! app-state assoc
                :ast ast
                :datoms datoms
                :walker walker
                :walker-running false
                :walker-result nil
                :steps 0
                :error nil))
       (catch :default e
         (swap! app-state assoc
                :error (str "Compile error: " (.-message e))
                :ast nil
                :datoms nil
                :walker nil
                :walker-running false
                :walker-result nil))))


(defn reset-walker!
  []
  (let [{:keys [datoms]} @app-state]
    (when (seq datoms)
      (stop-run-loop!)
      (swap! app-state assoc
             :walker (load-program! datoms)
             :walker-running false
             :walker-result nil
             :steps 0
             :error nil))))


(defn- step-state
  "One step as a pure state transition, shared by `step-walker!` and
   `run-frame!`'s inner loop."
  [state]
  (let [vm (:walker state)]
    (cond (nil? vm) (assoc state :walker-running false)
          (vm/halted? vm) (assoc state :walker-running false)
          :else (try (let [vm' (vm/step vm)]
                       (assoc state
                              :walker vm'
                              :steps (inc (:steps state))
                              :walker-result (when (vm/halted? vm')
                                               (vm/value vm'))
                              :walker-running (not (vm/halted? vm'))
                              :error nil))
                     (catch :default e
                       (assoc state
                              :walker-running false
                              :error (str "VM step error: "
                                          (.-message e))))))))


(defn step-walker!
  []
  (stop-run-loop!)
  (swap! app-state step-state))


(def steps-per-frame 200)


(defn run-frame!
  []
  (swap! app-state
         (fn [state]
           (loop [i 0
                  s state]
             (if (or (>= i steps-per-frame)
                     (not (:walker-running s))
                     (:walker-result s))
               s
               (recur (inc i) (step-state s))))))
  (let [running (:walker-running @app-state)]
    (if running
      (reset! run-raf-id (js/requestAnimationFrame run-frame!))
      (reset! run-raf-id nil))))


(defn toggle-run!
  []
  (let [{:keys [walker walker-running]} @app-state]
    (cond (nil? walker) nil
          walker-running (stop-run-loop!)
          (vm/halted? walker) nil
          :else (do (swap! app-state assoc :walker-running true :error nil)
                    (reset! run-raf-id
                            (js/requestAnimationFrame run-frame!))))))


;; =============================================================================
;; CodeMirror editor
;; =============================================================================

(defn codemirror-editor
  [{:keys [value on-change read-only]}]
  (let [view-ref (r/atom nil)
        el-ref (atom nil)]
    (r/create-class
      {:display-name "pipeline-v2-codemirror-editor",
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
             (let [current (.. view -state -doc toString)]
               (when (not= value current)
                 (.dispatch view
                            #js {:changes #js
                                          {:from 0,
                                           :to (.. view -state -doc -length),
                                           :insert (or value "")}})))))),
       :component-will-unmount (fn [_]
                                 (when-let [view @view-ref]
                                   (.destroy view))),
       :reagent-render
       (fn [{:keys [style]}]
         [:div
          {:ref #(reset! el-ref %),
           :style (merge {:height "100%",
                          :width "100%",
                          :border "1px solid #2d3b55",
                          :overflow "hidden"}
                         style)}])})))


;; =============================================================================
;; UI
;; =============================================================================

(defn- card
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


(defn- walker-cesk
  [vm-state]
  (when vm-state
    (let [continuation (vm/continuation vm-state)]
      {:control (let [c (vm/control vm-state)]
                  (or (:type c) (when (some? c) :program-root))),
       :environment (:env vm-state),
       :store (:store vm-state),
       :continuation {:depth (count (or continuation [])),
                      :frames (->> (or continuation [])
                                   (take 12)
                                   (mapv (fn [frame]
                                           {:type (:type frame),
                                            :node-type (get-in frame
                                                               [:frame
                                                                :type])})))},
       :parked (count (or (:parked vm-state) {})),
       :ready-queue-count (count (or (:ready-queue vm-state) [])),
       :wait-set-count (count (or (:wait-set vm-state) [])),
       :halted? (:halted? vm-state),
       :blocked? (:blocked? vm-state),
       :value (:value vm-state)})))


(defn- datom-listing
  [datoms]
  (str/join "\n"
            (map-indexed (fn [i d] (str i ": " (pr-str d))) datoms)))


(defn- ast-text
  [ast]
  (if ast (pretty-print ast) "Compile source to view the AST."))


(defn main-view
  []
  (r/create-class
    {:display-name "compilation-pipeline-v2-main",
     :component-did-mount
     (fn []
       (when-not (:datoms @app-state) (compile-source!))),
     :component-will-unmount (fn [] (stop-run-loop!)),
     :reagent-render
     (fn []
       (let [{:keys [source ast datoms walker walker-running walker-result
                     steps error]}
             @app-state
             cesk (walker-cesk walker)
             halted? (when walker (vm/halted? walker))]
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
            "Yin VM v2 Compilation Pipeline"]
           [:div
            {:style {:display "flex",
                     :gap "8px",
                     :align-items "center",
                     :flex-wrap "wrap"}}
            [:button
             {:on-click compile-source!,
              :style {:background "#1f6feb",
                      :color "#fff",
                      :border "none",
                      :padding "8px 12px",
                      :border-radius "5px",
                      :cursor "pointer",
                      :font-size "12px"}} "Compile"]
            [:button
             {:on-click reset-walker!,
              :disabled (not (seq datoms)),
              :style {:background (if (seq datoms) "#6e7681" "#333"),
                      :color "#fff",
                      :border "none",
                      :padding "8px 12px",
                      :border-radius "5px",
                      :cursor (if (seq datoms) "pointer" "not-allowed"),
                      :font-size "12px"}} "Reset"]
            [:button
             {:on-click step-walker!,
              :disabled (or (not (seq datoms)) halted?),
              :style {:background (if (and (seq datoms) (not halted?))
                                    "#238636"
                                    "#333"),
                      :color "#fff",
                      :border "none",
                      :padding "8px 12px",
                      :border-radius "5px",
                      :cursor (if (and (seq datoms) (not halted?))
                                "pointer"
                                "not-allowed"),
                      :font-size "12px"}} "Step"]
            [:button
             {:on-click toggle-run!,
              :disabled (or (not (seq datoms)) halted?),
              :style {:background (cond (not (seq datoms)) "#333"
                                        halted? "#333"
                                        walker-running "#da3633"
                                        :else "#238636"),
                      :color "#fff",
                      :border "none",
                      :padding "8px 12px",
                      :border-radius "5px",
                      :cursor (if (and (seq datoms) (not halted?))
                                "pointer"
                                "not-allowed"),
                      :font-size "12px"}}
             (if walker-running "Pause" "Run")]
            [:span {:style {:color "#8b949e", :font-size "12px"}}
             (str steps " steps")]]]
          [:div
           {:style {:display "grid",
                    :grid-template-columns (responsive/auto-fit-grid 320),
                    :gap "12px",
                    :flex "1",
                    :min-height "0"}}
           [:div {:style {:min-height (responsive/fluid-height 280 42 380)}}
            [card "Source" "CodeMirror editor: Clojure code"
             [codemirror-editor
              {:value source,
               :on-change set-source!,
               :style {:height "100%"}}]]]
           [:div {:style {:min-height (responsive/fluid-height 280 42 380)}}
            [card "AST"
             "yang.clojure/compile-program"
             [codemirror-editor
              {:value (ast-text ast),
               :read-only true,
               :style {:height "100%"}}]]]
           [:div {:style {:min-height (responsive/fluid-height 280 42 380)}}
            [card (str "Canonical Datoms" (when datoms
                                            (str " (" (count datoms) ")")))
             "vm/ast->datoms — the v2 program representation"
             [codemirror-editor
              {:value (if (seq datoms)
                        (datom-listing datoms)
                        "Compile source to view the canonical datoms."),
               :read-only true,
               :style {:height "100%"}}]]]
           [:div {:style {:min-height (responsive/fluid-height 320 50 460)}}
            [card "ASTWalker VM (v2)"
             "CESK state, one vm/step at a time"
             [codemirror-editor
              {:value (or (some-> cesk pretty-print)
                          "Compile source to construct the VM."),
               :read-only true,
               :style {:height "100%"}}]]]
           [:div {:style {:min-height (responsive/fluid-height 240 34 320)}}
            [card "Result"
             "The value the evaluator computed"
             [codemirror-editor
              {:value (cond walker-result (pr-str walker-result)
                            halted? (str "Halted: " (pr-str (vm/value walker)))
                            :else "Step or Run to compute a value."),
               :read-only true,
               :style {:height "100%"}}]]]]
          (when error
            [:div
             {:style {:background "rgba(255,0,0,0.2)",
                      :border "1px solid #da3633",
                      :padding "8px",
                      :font-size "12px",
                      :color "#f85149"}} error])]))}))
