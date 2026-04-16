(ns datomworld.demo.equation-plotter
  (:require
    ["@codemirror/state" :refer [EditorState]]
    ["@codemirror/theme-one-dark" :refer [oneDark]]
    ["@codemirror/view" :refer [EditorView]]
    ["@nextjournal/lang-clojure" :refer [clojure]]
    ["codemirror" :refer [basicSetup]]
    [cljs.reader :as reader]
    [clojure.string :as str]
    [dao.stream :as ds]
    [dao.stream.transport.ringbuffer]
    [reagent.core :as r]
    [yang.clojure :as yang]
    [yin.vm :as vm]
    [yin.vm.register :as register]))


(declare app-state plot-state)


(defn- make-plot-handler
  [points* call-count*]
  (fn [x y]
    (when (and (number? x) (number? y)
               (js/isFinite x) (js/isFinite y))
      (vswap! points* conj [x y]))
    (vswap! call-count* inc)
    nil))


(defn- flush-plot-batch!
  [points* call-count*]
  (let [points @points*
        call-count @call-count*]
    (when (or (seq points) (pos? call-count))
      (swap! plot-state
             (fn [state]
               (-> state
                   (update :points into points)
                   (update :call-count + call-count))))
      (vreset! points* [])
      (vreset! call-count* 0))))


;; =============================================================================
;; plot-point: ClojureScript function that plots [x y] on an SVG cartesian grid
;; =============================================================================

(defonce plot-state
  (r/atom {:points []
           :call-count 0}))


(defn plot-point
  "ClojureScript function called from Yin VM via dao.stream.apply.
   Adds [x y] to the point buffer for SVG rendering."
  [x y]
  (when (and (number? x) (number? y)
             (js/isFinite x) (js/isFinite y))
    (swap! plot-state update :points conj [x y]))
  (swap! plot-state update :call-count inc)
  nil)


(defn clear-plot!
  []
  (reset! plot-state {:points [] :call-count 0}))


;; =============================================================================
;; SVG Cartesian Grid
;; =============================================================================

(defn- compute-bounds
  [points]
  (if (empty? points)
    {:x-min -10 :x-max 10 :y-min -1 :y-max 1}
    (let [xs (mapv first points)
          ys (mapv second points)
          x-min (apply min xs)
          x-max (apply max xs)
          y-min (apply min ys)
          y-max (apply max ys)
          x-pad (max 1.0 (* 0.05 (- x-max x-min)))
          y-pad (max 1.0 (* 0.1 (- y-max y-min)))]
      {:x-min (- x-min x-pad) :x-max (+ x-max x-pad)
       :y-min (- y-min y-pad) :y-max (+ y-max y-pad)})))


(defn- split-segments
  [points y-range]
  (let [threshold (* 0.5 y-range)]
    (reduce (fn [segments [_ y :as pt]]
              (let [last-seg (peek segments)]
                (if (empty? last-seg)
                  [(conj last-seg pt)]
                  (let [[_ prev-y] (peek last-seg)]
                    (if (> (js/Math.abs (- y prev-y)) threshold)
                      (conj segments [pt])
                      (conj (pop segments) (conj last-seg pt)))))))
            [[]]
            points)))


(defn svg-plot
  "Renders collected points on an SVG cartesian grid."
  []
  (let [{:keys [points]} @plot-state
        bounds (compute-bounds points)
        {:keys [x-min x-max y-min y-max]} bounds
        x-range (- x-max x-min)
        y-range (- y-max y-min)
        ;; SVG y is flipped (negate)
        svg-y-min (- y-max)
        svg-y-max (- y-min)
        vb-h (- svg-y-max svg-y-min)
        font-size (* 0.03 (min x-range y-range))
        label-offset (* 0.06 y-range)
        x-step (cond (> x-range 40) 10 (> x-range 20) 5 (> x-range 8) 2 :else 1)
        y-step (cond (> y-range 40) 10 (> y-range 20) 5 (> y-range 8) 2 :else 1)
        segments (when (seq points) (split-segments points y-range))]
    [:svg {:viewBox (str x-min " " svg-y-min " " x-range " " vb-h)
           ;; Use independent x/y scaling so large y-ranges (e.g. cubic on [-10,10])
           ;; do not collapse the curve into a near-invisible vertical sliver.
           :preserveAspectRatio "none"
           :style {:width "100%" :height "100%"
                   :background "#0d1117"
                   :border "1px solid #30363d"
                   :border-radius "6px"}}
     ;; Grid lines
     (for [x (range (js/Math.ceil x-min) (inc (js/Math.floor x-max)))]
       ^{:key (str "vg" x)}
       [:line {:x1 x :y1 svg-y-min :x2 x :y2 svg-y-max
               :stroke (if (zero? x) "#8b949e" "#21262d")
               :stroke-width (if (zero? x) 0.06 0.03)}])
     (for [y (range (js/Math.ceil y-min) (inc (js/Math.floor y-max)))]
       ^{:key (str "hg" y)}
       [:line {:x1 x-min :y1 (- y) :x2 x-max :y2 (- y)
               :stroke (if (zero? y) "#8b949e" "#21262d")
               :stroke-width (if (zero? y) 0.06 0.03)}])
     ;; Axis labels
     (for [x (range (js/Math.ceil x-min) (inc (js/Math.floor x-max)) x-step)]
       (when (not (zero? x))
         ^{:key (str "xl" x)}
         [:text {:x x :y (+ 0 label-offset)
                 :text-anchor "middle" :fill "#8b949e" :font-size font-size}
          (str x)]))
     (for [y (range (js/Math.ceil y-min) (inc (js/Math.floor y-max)) y-step)]
       (when (not (zero? y))
         ^{:key (str "yl" y)}
         [:text {:x (- 0 label-offset) :y (+ (- y) (* 0.3 font-size))
                 :text-anchor "end" :fill "#8b949e" :font-size font-size}
          (str y)]))
     ;; Curve
     (when segments
       [:<>
        (for [[i seg] (map-indexed vector segments)]
          (when (> (count seg) 1)
            ^{:key (str "seg" i)}
            [:polyline {:points (str/join " " (map (fn [[x y]] (str x "," (- y))) seg))
                        :fill "none"
                        :stroke "#1f6feb"
                        :stroke-width 2
                        :vector-effect "non-scaling-stroke"
                        :stroke-linecap "round"
                        :stroke-linejoin "round"}]))])]))


;; =============================================================================
;; Math primitives available to Yin programs
;; =============================================================================

(def math-primitives
  {'pow js/Math.pow
   'sin js/Math.sin
   'cos js/Math.cos
   'tan js/Math.tan
   'sqrt js/Math.sqrt
   'abs js/Math.abs
   'log js/Math.log
   'exp js/Math.exp})


;; =============================================================================
;; State
;; =============================================================================

(def default-source
  "(defn f [x] (* x (sin x)))
(defn plot-loop [x-min x-max]
  (if (> x-min x-max)
    nil
    (do
      (dao.stream.apply/call :plot/point x-min (f x-min))
      (plot-loop (+ x-min 0.05) x-max))))
(plot-loop -10 10)")


(defonce app-state
  (r/atom {:source default-source
           :asm nil
           :bytecode nil
           :pool nil
           :reg-count nil
           :ast nil
           :program nil
           :running? false
           :result nil
           :error nil}))


;; =============================================================================
;; Compile: source -> register VM bytecode
;; =============================================================================

(defn- ast->program
  [ast]
  {:datoms (vec (vm/ast->datoms ast))})


(defn- queue-vm
  [vm-state datoms]
  (let [in-stream (ds/open! {:type :ringbuffer
                             :capacity nil})
        queued-vm (assoc vm-state :in-stream in-stream :in-cursor {:position 0})]
    (ds/put! in-stream (vec datoms))
    queued-vm))


(defn compile!
  "Compile the source editor contents to register VM bytecode."
  []
  (try
    (let [source (:source @app-state)
          forms (reader/read-string (str "[" source "]"))
          ast (yang/compile-program forms)
          {:keys [datoms] :as program} (ast->program ast)
          {:keys [asm reg-count]} (register/ast-datoms->asm datoms)
          result (register/assemble asm)]
      (swap! app-state assoc
             :ast ast
             :program program
             :asm asm
             :bytecode (:bytecode result)
             :pool (:pool result)
             :reg-count reg-count
             :running? false
             :result nil
             :error nil))
    (catch :default e
      (swap! app-state assoc
             :error (str "Compile error: " (.-message e))
             :asm nil :bytecode nil :pool nil :reg-count nil
             :ast nil :program nil :running? false :result nil))))


;; =============================================================================
;; Execute: run bytecode in register VM with dao.stream.apply bridge -> plot-point
;; =============================================================================

(defn execute!
  "Run the compiled program in the register VM.
   dao.stream.apply :plot/point calls are dispatched to the ClojureScript plot-point function."
  []
  (try
    (let [{:keys [program]} @app-state]
      (when-not program
        (throw (ex-info "Nothing compiled. Click Compile first." {})))
      (clear-plot!)
      (let [{:keys [datoms]} program
            points* (volatile! [])
            call-count* (volatile! 0)
            bridge-handlers {:plot/point (make-plot-handler points* call-count*)}
            vm-loaded (queue-vm (register/create-vm
                                  {:primitives (merge vm/primitives math-primitives)
                                   :bridge bridge-handlers})
                                datoms)
            result-vm (vm/run vm-loaded)]
        (flush-plot-batch! points* call-count*)
        (swap! app-state assoc
               :running? false
               :result (vm/value result-vm)
               :error nil)))
    (catch :default e
      (swap! app-state assoc
             :running? false
             :error (str "Runtime error: " (.-message e))))))


;; =============================================================================
;; CodeMirror editor component
;; =============================================================================

(defn codemirror-editor
  [{:keys [value on-change read-only]}]
  (let [view-ref (r/atom nil)
        el-ref (atom nil)]
    (r/create-class
      {:display-name "plotter-codemirror-editor"
       :component-did-mount
       (fn [_]
         (when-let [node @el-ref]
           (let [theme (.theme EditorView
                               #js {"&" #js {:height "100%"}
                                    ".cm-scroller" #js {:overflow "auto"}})
                 extensions
                 (cond-> #js [basicSetup (clojure) oneDark theme]
                   read-only (.concat #js [(.of (.-editable EditorView) false)])
                   on-change
                   (.concat
                     #js [(.of (.-updateListener EditorView)
                               (fn [^js update]
                                 (when (and (.-docChanged update) on-change)
                                   (on-change
                                     (.. update -state -doc toString)))))]))
                 state (.create EditorState
                                #js {:doc (or value "")
                                     :extensions extensions})
                 view (new EditorView #js {:state state :parent node})]
             (reset! view-ref view))))
       :component-did-update
       (fn [this _]
         (let [{:keys [value]} (r/props this)]
           (when-let [view @view-ref]
             (let [current (.. view -state -doc toString)]
               (when (not= value current)
                 (.dispatch view
                            #js {:changes #js {:from 0
                                               :to (.. view -state -doc -length)
                                               :insert (or value "")}}))))))
       :component-will-unmount
       (fn [_] (when-let [view @view-ref] (.destroy view)))
       :reagent-render
       (fn [{:keys [style]}]
         [:div {:ref #(reset! el-ref %)
                :style (merge {:height "100%"
                               :width "100%"
                               :border "1px solid #2d3b55"
                               :overflow "hidden"}
                              style)}])})))


;; =============================================================================
;; UI
;; =============================================================================

(def ^:private btn-style
  {:background "#21262d"
   :color "#f1f5ff"
   :border "1px solid #30363d"
   :border-radius "4px"
   :padding "6px 14px"
   :cursor "pointer"
   :font-size "13px"})


(defn main-view
  []
  (let [{:keys [source asm error running?]} @app-state
        {:keys [points call-count]} @plot-state]
    [:div {:style {:min-height "100vh"
                   :background "#060817"
                   :color "#c5c6c7"
                   :padding "20px"}}
     ;; Title
     [:h1 {:style {:color "#f1f5ff" :margin-bottom "4px"}}
      [:a {:href "https://datom.world"} "Datom.world "]
      "Equation Plotter"]
     [:p {:style {:color "#8b949e" :margin-top "0" :margin-bottom "20px"
                  :font-size "14px"}}
      "Yin source code compiled to register VM bytecode. "
      "dao.stream.apply :plot/point bridges to a ClojureScript function that renders SVG."]

     ;; Source editor (CodeMirror)
     [:div {:style {:max-width "900px" :height "220px" :margin-bottom "12px"}}
      [codemirror-editor
       {:value source
        :on-change #(swap! app-state assoc :source %)}]]

     ;; Compile + Execute buttons
     [:div {:style {:display "flex" :gap "8px" :align-items "center"
                    :margin-bottom "16px"}}
      [:button {:on-click compile!
                :disabled running?
                :style (merge btn-style {:background "#238636"})}
       "Compile"]
      [:button {:on-click execute!
                :disabled (or running? (nil? (:program @app-state)))
                :style (merge btn-style
                              (if (and (:program @app-state) (not running?))
                                {:background "#1f6feb"}
                                {:background "#333" :cursor "not-allowed"}))}
       (if running? "Running..." "Execute")]]

     ;; Register assembly (shown after compile)
     (when asm
       [:div {:style {:max-width "900px" :margin-bottom "16px"}}
        [:div {:style {:color "#8b949e" :font-size "12px" :margin-bottom "4px"}}
         (str (count asm) " register instructions")]
        [:pre {:style {:background "#0d1117"
                       :border "1px solid #30363d"
                       :border-radius "6px"
                       :padding "12px"
                       :font-size "12px"
                       :color "#c9d1d9"
                       :max-height "200px"
                       :overflow-y "auto"
                       :margin "0"}}
         (str/join "\n" (map-indexed (fn [i instr] (str i ": " (pr-str instr))) asm))]])

     ;; SVG Plot
     [:div {:style {:width "100%"
                    :max-width "900px"
                    :height "500px"
                    :margin-bottom "12px"}}
      [svg-plot]]

     ;; Stats
     (when (pos? call-count)
       [:div {:style {:font-size "13px" :color "#8b949e" :margin-bottom "12px"}}
        (str (count points) " points plotted, " call-count " dao.stream.apply calls")])

     ;; Error
     (when error
       [:div {:style {:position "fixed"
                      :bottom "10px" :left "10px" :right "10px"
                      :background "rgba(255,0,0,0.2)"
                      :border "1px solid #da3633"
                      :padding "10px"
                      :color "#f85149"
                      :border-radius "6px"
                      :z-index "100"}}
        [:strong "Error: "] error])]))
