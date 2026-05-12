(ns dao.mr-clean.compiler
  {:clj-kondo/config '{:linters {:unused-namespace {:exclude
                                                    [clojure.string]}}}}
  (:require
    #?(:cljd ["dart:math" :as math])
    [clojure.string :as str]))


(def ^:dynamic *evaluation-path* [])
(def ^:dynamic *snapshot* nil)
(def ^:dynamic *capabilities* nil)
(def ^:dynamic *current-context* nil)
(def ^:dynamic *constraints* nil)


(defn read-atom
  "Reads an atom's value from the current snapshot.
   Must only be called during compilation, and only on atoms that the
   runtime declared in :atoms (so it has snapshotted and is watching them)."
  [a]
  (cond
    (nil? *snapshot*)
    (throw
      (ex-info
        "read-atom called without an active snapshot (ensure you are inside compile-ui and a snapshot was provided)"
        {:atom a}))
    (not (contains? *snapshot* a))
    (throw
      (ex-info
        "read-atom called on an atom missing from the snapshot; declare it in the runtime's :atoms so it is watched"
        {:atom a, :path *evaluation-path*}))
    :else (get *snapshot* a)))


(declare compile-node)


(declare shift-absolute-coords)


(defn overlay
  "Wraps child ops in the overlay layer, anchored at the current absolute translation."
  [child-ops]
  (when-not *current-context*
    (throw (ex-info "overlay must be called during compilation" {})))
  (let [ctx *current-context*
        constraints *constraints*]
    (when-not (:translate-only? ctx)
      (throw (ex-info "Overlay emitted under non-translation transform"
                      {:path *evaluation-path*,
                       :enclosing-transform (:non-translate-cause ctx)})))
    (let [ax (:abs-x ctx)
          ay (:abs-y ctx)
          ;; If child-ops is a contribution map, use it. If it's a vector
          ;; of ops, use it. Otherwise, treat as Hiccup and compile it.
          contribution (cond (and (map? child-ops) (:flow child-ops)) child-ops
                             (and (vector? child-ops)
                                  (not-empty child-ops)
                                  (map? (first child-ops))
                                  (:op/kind (first child-ops)))
                             {:flow child-ops, :overlay []}
                             :else (compile-node child-ops constraints ctx))
          flow (:flow contribution)
          nested-overlay (:overlay contribution)
          ;; The transform/push anchor only moves ops positioned by the
          ;; VM's transform stack; screen-space ops (e.g. :clip/push-rect)
          ;; need the anchor translation baked into their coordinates.
          shifted-flow (shift-absolute-coords flow ax ay)]
      {:width (:width contribution 0),
       :height (:height contribution 0),
       :flow [],
       :overlay (vec (concat [{:op/kind :transform/push,
                               :translate [ax ay],
                               :absolute? true}]
                             shifted-flow
                             [{:op/kind :transform/pop}]
                             nested-overlay))})))


(defn- fn-display-name
  "Best-effort display string for an anonymous or let-bound component fn.
   On the JVM, parses the host class repr to recover the lexical name (e.g.
   dao.foo$bar$my_comp__1234@deadbeef becomes dao.foo/my-comp). On CLJS and
   CLJD the host repr is not parseable, so falls back to the runtime-provided
   name when available, otherwise a generic placeholder.
   Prefer attaching {:name \"...\"} via metadata for stable, portable names."
  [f]
  #?(:clj (let [s (str f)
                s (str/replace s #"@[0-9a-fA-F]+$" "")
                parts (str/split s #"\$")]
            (if (>= (count parts) 2)
              (let [ns-part (first parts)
                    name-part (str/replace (last parts) #"__\d+$" "")]
                (str (str/replace ns-part "_" "-")
                     "/"
                     (str/replace name-part "_" "-")))
              s))
     :cljs (let [n (.-name f)] (if (and n (not= "" n)) n "anonymous-fn"))
     :cljd "anonymous-fn"))


(defn- resolve-child-placements
  [children-results layout-type _constraints]
  ;; A simple layout solver for :column, :row, :stack
  ;; For v1, we just do a basic translate.
  (case layout-type
    :stack
    ;; All children at 0,0. Container size is max of children.
    (let [w (reduce max 0 (map :width children-results))
          h (reduce max 0 (map :height children-results))
          placed (mapv (fn [res]
                         (assoc res
                                :x 0
                                :y 0))
                       children-results)]
      {:width w, :height h, :children placed})
    :column
    ;; Vertical stack
    (let [w (reduce max 0 (map :width children-results))
          h (reduce + 0 (map :height children-results))
          placed (second (reduce (fn [[y acc] res]
                                   [(+ y (:height res))
                                    (conj acc
                                          (assoc res
                                                 :x 0
                                                 :y y))])
                                 [0 []]
                                 children-results))]
      {:width w, :height h, :children placed})
    :row
    ;; Horizontal stack
    (let [w (reduce + 0 (map :width children-results))
          h (reduce max 0 (map :height children-results))
          placed (second (reduce (fn [[x acc] res]
                                   [(+ x (:width res))
                                    (conj acc
                                          (assoc res
                                                 :x x
                                                 :y 0))])
                                 [0 []]
                                 children-results))]
      {:width w, :height h, :children placed})
    (throw (ex-info "Unknown layout type" {:layout-type layout-type}))))


(defn- shift-absolute-coords
  "Adds [dx dy] to every screen-space op in ops: :clip/push-rect (whose
   :rect is screen-space) and :transform/push ops marked :absolute?
   (overlay anchors). Other ops are positioned by the VM's transform
   stack and are unaffected."
  [ops dx dy]
  (mapv (fn [op]
          (cond (= :clip/push-rect (:op/kind op))
                (update op :rect (fn [[x y w h]] [(+ x dx) (+ y dy) w h]))
                (and (= :transform/push (:op/kind op)) (:absolute? op))
                (update op :translate (fn [[x y]] [(+ x dx) (+ y dy)]))
                :else op))
        ops))


(defn- emit-placed-children
  [placed-children]
  (let [flow (volatile! [])
        overlay (volatile! [])]
    (doseq [child placed-children]
      (let [x (:x child)
            y (:y child)
            needs-translate? (or (not= x 0) (not= y 0))
            child-flow (:flow child)
            child-overlay (:overlay child)
            [shifted-flow shifted-overlay]
            (if needs-translate?
              [(shift-absolute-coords child-flow x y)
               (shift-absolute-coords child-overlay x y)]
              [child-flow child-overlay])]
        (when needs-translate?
          (vswap! flow conj {:op/kind :transform/push, :translate [x y]}))
        (vswap! flow into shifted-flow)
        (when needs-translate? (vswap! flow conj {:op/kind :transform/pop}))
        (vswap! overlay into shifted-overlay)))
    {:flow @flow, :overlay @overlay}))


(defn- reject-raw-ops-in-sized-layout
  [res]
  (when (:raw-ops? res)
    (throw
      (ex-info
        "Raw op vector not allowed in size-dependent layout (row/column). Wrap in a container with explicit :width/:height."
        {:path *evaluation-path*})))
  (dissoc res :raw-ops?))


(defn- compile-children
  [children constraints ctx layout-type]
  (case layout-type
    :column (let [max-h (:max-height constraints)]
              (second
                (reduce
                  (fn [[remaining-h acc idx] child]
                    (let [child-constraints (if (= remaining-h :unbounded)
                                              constraints
                                              (assoc constraints
                                                     :max-height remaining-h))
                          res (-> (binding [*evaluation-path*
                                            (conj *evaluation-path* idx)]
                                    (compile-node child child-constraints ctx))
                                  reject-raw-ops-in-sized-layout)
                          new-remaining-h
                          (if (= remaining-h :unbounded)
                            :unbounded
                            (max 0 (- remaining-h (:height res))))]
                      [new-remaining-h (conj acc res) (inc idx)]))
                  [max-h [] 0]
                  children)))
    :row (let [max-w (:max-width constraints)]
           (second
             (reduce (fn [[remaining-w acc idx] child]
                       (let [child-constraints (if (= remaining-w :unbounded)
                                                 constraints
                                                 (assoc constraints
                                                        :max-width remaining-w))
                             res (->
                                   (binding [*evaluation-path*
                                             (conj *evaluation-path* idx)]
                                     (compile-node child child-constraints ctx))
                                   reject-raw-ops-in-sized-layout)
                             new-remaining-w
                             (if (= remaining-w :unbounded)
                               :unbounded
                               (max 0 (- remaining-w (:width res))))]
                         [new-remaining-w (conj acc res) (inc idx)]))
                     [max-w [] 0]
                     children)))
    ;; :stack or others — raw op vectors are allowed here, but strip the
    ;; internal flag so it does not leak into downstream contribution maps.
    (map-indexed (fn [idx child]
                   (-> (binding [*evaluation-path* (conj *evaluation-path* idx)]
                         (compile-node child constraints ctx))
                       (dissoc :raw-ops?)))
                 children)))


(defn- vec-of-2-numbers?
  [v]
  (and (vector? v) (= 2 (count v)) (every? number? v)))


(defn- validate-transform-attrs
  [attrs]
  (let [{:keys [translate scale rotate matrix]} attrs]
    (when (and translate (not (vec-of-2-numbers? translate)))
      (throw
        (ex-info
          "transform :translate must be a 2-element vector of numbers [x y]; mr-clean is 2D"
          {:path *evaluation-path*, :translate translate})))
    (when (and scale (not (vec-of-2-numbers? scale)))
      (throw
        (ex-info
          "transform :scale must be a 2-element vector of numbers [sx sy]; mr-clean is 2D"
          {:path *evaluation-path*, :scale scale})))
    (when (and rotate (not (number? rotate)))
      (throw (ex-info
               "transform :rotate must be a scalar (radians); mr-clean is 2D"
               {:path *evaluation-path*, :rotate rotate})))
    (when matrix
      (when (or translate scale rotate)
        (throw
          (ex-info
            "transform :matrix cannot be combined with :translate, :scale, or :rotate; pre-multiply into the matrix instead"
            {:path *evaluation-path*,
             :conflicts (cond-> []
                          translate (conj :translate)
                          scale (conj :scale)
                          rotate (conj :rotate))})))
      (when-not (= 9 (count matrix))
        (throw
          (ex-info
            "transform :matrix must be a 9-element 3x3 affine matrix; mr-clean is 2D"
            {:path *evaluation-path*, :matrix-count (count matrix)})))
      (when-not (every? number? matrix)
        (throw (ex-info "transform :matrix entries must all be numbers"
                        {:path *evaluation-path*, :matrix matrix})))
      (let [[_ _ _ _ _ _ m20 m21 m22] matrix]
        (when (or (not= 0.0 (double m20))
                  (not= 0.0 (double m21))
                  (not= 1.0 (double m22)))
          (throw
            (ex-info
              "transform :matrix must be an affine 2D matrix (m20=0, m21=0, m22=1); mr-clean is 2D"
              {:path *evaluation-path*, :matrix matrix})))))))


(defn- transform-bounds
  [w h attrs]
  (let [{:keys [translate scale rotate matrix]} attrs
        points [[0 0] [w 0] [0 h] [w h]]
        ;; 1. Compute transformed points
        t-points (cond matrix
                       (let [[m00 m01 m02 m10 m11 m12 m20 m21 m22] matrix]
                         (map (fn [[x y]]
                                (let [w' (+ (* x (or m20 0))
                                            (* y (or m21 0))
                                            (or m22 1))
                                      x' (/ (+ (* x m00) (* y m01) m02) w')
                                      y' (/ (+ (* x m10) (* y m11) m12) w')]
                                  [x' y']))
                              points))
                       :else (let [sx (if scale (first scale) 1)
                                   sy (if scale (second scale) 1)
                                   r (or rotate 0)
                                   c #?(:clj (java.lang.Math/cos r)
                                        :cljs (.cos js/Math r)
                                        :cljd (math/cos r))
                                   s #?(:clj (java.lang.Math/sin r)
                                        :cljs (.sin js/Math r)
                                        :cljd (math/sin r))
                                   [tx ty] (or translate [0 0])]
                               (map (fn [[x y]]
                                      (let [x (* x sx)
                                            y (* y sy)
                                            x' (- (* x c) (* y s))
                                            y' (+ (* x s) (* y c))]
                                        [(+ x' tx) (+ y' ty)]))
                                    points)))
        ;; 2. The layout size must represent the "occupied slot" from the
        ;; origin. If content moves left (min-x < 0), we must account for
        ;; that span. If content moves right (max-x > w), we must account
        ;; for that expansion.
        max-x (reduce max (map first t-points))
        min-x (reduce min (map first t-points))
        max-y (reduce max (map second t-points))
        min-y (reduce min (map second t-points))]
    {:width (max 0 (- (max max-x 0) (min min-x 0))),
     :height (max 0 (- (max max-y 0) (min min-y 0)))}))


(def ^:private layout-only-attrs
  "Keys consumed by the compiler for layout/constraint reasons; never emitted
   into the frame program, regardless of which primitive carries them."
  #{:max-width :max-height})


(defn- op-attrs
  "Strips compiler-private keys (layout-only + per-op consumed keys) from
   attrs so the residue can be merged onto an emitted op without leaking."
  [attrs & consumed]
  (apply dissoc attrs (concat layout-only-attrs consumed)))


(defn- check-non-negative-dim!
  [tag k v]
  (when-not (and (number? v) (>= v 0))
    (throw (ex-info (str tag " " k " must be a non-negative number")
                    {:path *evaluation-path*, k v}))))


(defn- handle-primitive
  [tag attrs children constraints ctx]
  (let [translate-only? (:translate-only? ctx)
        abs-x (:abs-x ctx)
        abs-y (:abs-y ctx)
        ;; Allow overriding constraints via attributes
        constraints (cond-> constraints
                      (:max-width attrs) (assoc :max-width (:max-width attrs))
                      (:max-height attrs) (assoc :max-height
                                                 (:max-height attrs)))]
    (case tag
      (:column :row :stack)
      (let [child-results (compile-children children constraints ctx tag)
            layout (resolve-child-placements child-results tag constraints)
            emitted (emit-placed-children (:children layout))]
        (assoc emitted
               :width (:width layout)
               :height (:height layout)))
      :text (let [measure-text (:measure-text *capabilities*)
                  _ (when-not measure-text
                      (throw (ex-info "measure-text capability missing"
                                      {:path *evaluation-path*})))
                  value (:value attrs "")
                  font-size (:font-size attrs 14)
                  font-family (:font-family attrs)
                  max-w (if (= (:max-width constraints) :unbounded)
                          10000
                          (:max-width constraints))
                  max-h (if (= (:max-height constraints) :unbounded)
                          10000
                          (:max-height constraints))
                  measured (measure-text {:text/value value,
                                          :text/font-size font-size,
                                          :text/font-family (or font-family
                                                                "sans-serif"),
                                          :max-width max-w,
                                          :max-height max-h})]
              {:width (:width measured),
               :height (:height measured),
               :flow [(merge {:op/kind :draw/text,
                              :text value,
                              :position [0 0],
                              :font-size font-size}
                             (op-attrs attrs :value :font-size))],
               :overlay []})
      :rect (let [w (:width attrs 0)
                  h (:height attrs 0)
                  _ (check-non-negative-dim! :rect :width w)
                  _ (check-non-negative-dim! :rect :height h)]
              {:width w,
               :height h,
               :flow [(merge {:op/kind :draw/fill-rect, :rect [0 0 w h]}
                             (op-attrs attrs :width :height))],
               :overlay []})
      :image (let [w (:width attrs)
                   h (:height attrs)]
               (when (or (nil? w) (nil? h))
                 (throw (ex-info
                          "Images in v1 must carry explicit width and height"
                          {:path *evaluation-path*, :attrs attrs})))
               (when-not (contains? attrs :image/source)
                 (throw (ex-info ":image must carry an :image/source key"
                                 {:path *evaluation-path*, :attrs attrs})))
               (check-non-negative-dim! :image :width w)
               (check-non-negative-dim! :image :height h)
               {:width w,
                :height h,
                :flow [(merge {:op/kind :draw/image, :rect [0 0 w h]}
                              (op-attrs attrs :width :height))],
                :overlay []})
      :clip (let [w (:width attrs 0)
                  h (:height attrs 0)]
              (check-non-negative-dim! :clip :width w)
              (check-non-negative-dim! :clip :height h)
              (when-not translate-only?
                (throw (ex-info "Clip emitted under non-translation transform"
                                {:path *evaluation-path*,
                                 :enclosing-transform (:non-translate-cause
                                                        ctx)})))
              (let [clip-constraints (assoc constraints
                                            :max-width w
                                            :max-height h)
                    child-results
                    (compile-children children clip-constraints ctx :stack)
                    layout (resolve-child-placements child-results
                                                     :stack
                                                     clip-constraints)
                    emitted (emit-placed-children (:children layout))]
                {:width w,
                 :height h,
                 :flow (vec (concat [{:op/kind :clip/push-rect,
                                      :rect [abs-x abs-y w h]}]
                                    (:flow emitted)
                                    [{:op/kind :clip/pop}])),
                 :overlay (:overlay emitted)}))
      :transform
      (let [_ (validate-transform-attrs attrs)
            translate (:translate attrs [0 0])
            scale (:scale attrs)
            rotate (:rotate attrs)
            matrix (:matrix attrs)
            is-translate-only? (and (nil? scale) (nil? rotate) (nil? matrix))
            new-ctx (if is-translate-only?
                      (assoc ctx
                             :abs-x (+ abs-x (first translate))
                             :abs-y (+ abs-y (second translate)))
                      (assoc ctx
                             :translate-only? false
                             :non-translate-cause *evaluation-path*))
            child-results
            (compile-children children constraints new-ctx :stack)
            layout (resolve-child-placements child-results :stack constraints)
            emitted (emit-placed-children (:children layout))
            push-op (merge {:op/kind :transform/push} (op-attrs attrs))
            bounds (transform-bounds (:width layout) (:height layout) attrs)]
        {:width (:width bounds),
         :height (:height bounds),
         :flow (vec (concat [push-op]
                            (:flow emitted)
                            [{:op/kind :transform/pop}])),
         :overlay (:overlay emitted)}))))


(defn- normalize-hiccup
  [node]
  (if (and (vector? node) (keyword? (first node)))
    (let [tag (first node)
          has-attrs? (and (> (count node) 1) (map? (second node)))
          attrs (if has-attrs? (second node) {})
          children (if has-attrs? (drop 2 node) (drop 1 node))]
      [tag attrs children])
    node))


(defn- compile-node
  [node constraints ctx]
  (binding [*current-context* ctx
            *constraints* constraints]
    (cond
      (nil? node) {:width 0, :height 0, :flow [], :overlay []}
      ;; Vector form: Hiccup or raw ops
      (vector? node)
      (cond
        (empty? node)
        ;; Empty vector — no-op contribution
        {:width 0, :height 0, :flow [], :overlay []}
        (and (map? (first node)) (:op/kind (first node)))
        ;; Raw op vector
        {:width 0, :height 0, :flow node, :overlay [], :raw-ops? true}
        (keyword? (first node))
        ;; Primitive
        (let [[tag attrs children] (normalize-hiccup node)]
          (if (#{:column :row :stack :text :image :rect :clip :transform}
               tag)
            (binding [*evaluation-path* (conj *evaluation-path* tag)]
              (handle-primitive tag attrs children constraints ctx))
            (throw (ex-info "Unknown primitive tag"
                            {:tag tag, :path *evaluation-path*}))))
        (fn? (first node))
        ;; User component as function: [my-comp props? child*]
        (let [comp-fn (first node)
              has-props? (and (> (count node) 1) (map? (second node)))
              children (if has-props? (drop 2 node) (drop 1 node))
              args (if has-props? (cons (second node) children) children)
              comp-name (or (:name (meta comp-fn))
                            (fn-display-name comp-fn))]
          (binding [*evaluation-path* (conj *evaluation-path* comp-name)]
            (let [result (apply comp-fn args)]
              ;; Result could be a map {:flow [] :overlay []}, an op
              ;; vector, or hiccup
              (cond (and (map? result) (:flow result))
                    (merge {:width 0, :height 0} result)
                    (vector? result)
                    (if (and (not-empty result)
                             (map? (first result))
                             (:op/kind (first result)))
                      ;; Plain op-vector
                      {:width 0,
                       :height 0,
                       :flow result,
                       :overlay [],
                       :raw-ops? true}
                      ;; Returned Hiccup
                      (compile-node result constraints ctx))
                    :else (compile-node result constraints ctx)))))
        :else (throw (ex-info "Invalid hiccup vector"
                              {:node node, :path *evaluation-path*})))
      ;; Map contribution
      (and (map? node) (:flow node)) (merge {:width 0, :height 0} node)
      :else (throw (ex-info "Invalid node type"
                            {:node node, :path *evaluation-path*})))))


(defn- fn-accepts-arity?
  "JVM-only: true if f exposes an `invoke` method with exactly n params."
  [f n]
  #?(:clj (boolean (some (fn [^java.lang.reflect.Method m]
                           (and (= "invoke" (.getName m))
                                (= n (alength (.getParameterTypes m)))))
                         (.getDeclaredMethods (class f))))
     :default (and f n false)))


(defn- fn-accepts-zero-arity?
  [f]
  (fn-accepts-arity? f 0))


(defn- fn-accepts-one-arity?
  [f]
  (fn-accepts-arity? f 1))


(defn compile-ui
  "Pure compiler entry point.
   Inputs:
   - root-form: The hiccup/component to evaluate.
   - props: Props for the root component (if any).
   - snapshot: Atom snapshot map.
   - capabilities: Map containing capabilities like :measure-text.
   Returns a complete dao.postgraphics frame program (a vector of ops):
   the root contribution's :flow followed by its :overlay."
  [root-form props snapshot capabilities]
  (binding [*snapshot* snapshot
            *capabilities* capabilities
            *evaluation-path* []]
    (let [ctx {:translate-only? true, :abs-x 0, :abs-y 0}
          constraints {:max-width :unbounded, :max-height :unbounded}
          result
          (cond (not (fn? root-form)) (compile-node root-form constraints ctx)
                (some? props) (compile-node [root-form props] constraints ctx)
                ;; Bare-fn root, props omitted. Honor an explicit unary
                ;; arm so nil reaches the component; only call zero-arity
                ;; if that's the only arm. When introspection fails
                ;; (non-JVM), try unary first and recover from
                ;; ArityException.
                (fn-accepts-one-arity? root-form)
                (compile-node [root-form nil] constraints ctx)
                (fn-accepts-zero-arity? root-form)
                (compile-node [root-form] constraints ctx)
                :else (try (compile-node [root-form nil] constraints ctx)
                           (catch #?(:clj clojure.lang.ArityException
                                     :cljs :default
                                     :cljd Error)
                                  _
                             (compile-node [root-form] constraints ctx))))]
      (vec (concat (:flow result) (:overlay result))))))
