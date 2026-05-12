(ns dao.mr-clean.compiler-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.mr-clean.compiler :as compiler]))


(deftest raw-op-vector-as-child-is-accepted
  (testing "a primitive container should accept a raw op-vector child"
    (let [ops [{:op/kind :draw/fill-rect, :rect [0 0 10 10]}]
          result (compiler/compile-ui [:stack ops] {} {} {})]
      (is (some #(= :draw/fill-rect (:op/kind %)) result)
          "raw op-vector child should land in the frame program"))))


(deftest raw-op-vector-is-rejected-in-size-dependent-layout
  (testing
    "row should reject raw op-vector children until they carry layout size"
    (is (thrown? clojure.lang.ExceptionInfo
          (compiler/compile-ui
            [:row [{:op/kind :draw/fill-rect, :rect [0 0 10 10]}]
             [:rect {:width 5, :height 5}]]
            {}
            {}
            {}))))
  (testing
    "column should reject raw op-vector children until they carry layout size"
    (is (thrown? clojure.lang.ExceptionInfo
          (compiler/compile-ui
            [:column [{:op/kind :draw/fill-rect, :rect [0 0 10 10]}]
             [:rect {:width 5, :height 5}]]
            {}
            {}
            {})))))


(deftest overlay-accepts-hiccup
  (testing "overlay helper should compile Hiccup arguments"
    (let [result (binding [compiler/*current-context*
                           {:translate-only? true, :abs-x 10, :abs-y 20}
                           compiler/*constraints* {:max-width :unbounded,
                                                   :max-height :unbounded}
                           compiler/*capabilities*
                           {:measure-text (fn [_] {:width 100, :height 20})}
                           compiler/*snapshot* {}]
                   (compiler/overlay [:rect {:width 50, :height 50}]))]
      (is (= 0 (count (:flow result))))
      (let [ops (:overlay result)]
        (is (= :transform/push (:op/kind (first ops))))
        (is (= [10 20] (:translate (first ops))))
        (is (some #(= :draw/fill-rect (:op/kind %)) ops))
        (is (= :transform/pop (:op/kind (last ops))))))))


(deftest overlay-shifts-screen-space-clip-ops-in-raw-op-vectors
  (testing "overlaying raw ops should preserve absolute clip rect positioning"
    (let [result (binding [compiler/*current-context*
                           {:translate-only? true, :abs-x 10, :abs-y 20}
                           compiler/*constraints* {:max-width :unbounded,
                                                   :max-height :unbounded}
                           compiler/*capabilities*
                           {:measure-text (fn [_] {:width 100, :height 20})}
                           compiler/*snapshot* {}]
                   (compiler/overlay [{:op/kind :clip/push-rect,
                                       :rect [0 0 5 6]}
                                      {:op/kind :draw/fill-rect,
                                       :rect [0 0 5 6]} {:op/kind :clip/pop}]))
          clip-op (some #(when (= :clip/push-rect (:op/kind %)) %)
                        (:overlay result))]
      (is clip-op)
      (is (= [10 20 5 6] (:rect clip-op))))))


(deftest overlay-does-not-double-translate-clips-from-compiled-hiccup
  (testing
    "overlaying compiled hiccup with a clip should keep the clip rect at the current screen position"
    (let [result (binding [compiler/*current-context*
                           {:translate-only? true, :abs-x 10, :abs-y 20}
                           compiler/*constraints* {:max-width :unbounded,
                                                   :max-height :unbounded}
                           compiler/*capabilities*
                           {:measure-text (fn [_] {:width 100, :height 20})}
                           compiler/*snapshot* {}]
                   (compiler/overlay [:clip {:width 5, :height 6}
                                      [:rect {:width 5, :height 6}]]))
          clip-op (some #(when (= :clip/push-rect (:op/kind %)) %)
                        (:overlay result))]
      (is clip-op)
      (is (= [10 20 5 6] (:rect clip-op))))))


(deftest compile-ui-invokes-function-root-with-props
  (testing "compile-ui should invoke a function root with the supplied props"
    (let [root (fn [{:keys [width height]}]
                 [:rect
                  {:width width, :height height}])
          result (compiler/compile-ui root {:width 12, :height 7} {} {})]
      (is (= [{:op/kind :draw/fill-rect, :rect [0 0 12 7]}] result)))))


(deftest compile-ui-preserves-explicit-nil-root-props
  (testing
    "a unary root component should still receive an explicit nil props value"
    (let [seen-props (atom ::unset)
          root (fn [props]
                 (reset! seen-props props)
                 [:rect {:width 4, :height 5}])]
      (is (= [{:op/kind :draw/fill-rect, :rect [0 0 4 5]}]
             (compiler/compile-ui root nil {} {})))
      (is (nil? @seen-props)))))


(deftest compile-ui-preserves-explicit-nil-for-multi-arity-roots
  (testing
    "a root with both [] and [props] arities should still receive explicit nil props"
    (let [seen-props (atom ::unset)
          root (fn
                 ([] [:rect {:width 1, :height 1}])
                 ([props]
                  (reset! seen-props props)
                  [:rect {:width 9, :height 10}]))]
      (is (= [{:op/kind :draw/fill-rect, :rect [0 0 9 10]}]
             (compiler/compile-ui root nil {} {})))
      (is (nil? @seen-props)))))


(deftest compile-ui-does-not-force-nil-into-bare-zero-arity-roots-on-non-jvm
  (testing
    "when zero-arity introspection is unavailable, a bare zero-arity root should still compile"
    (let [root (fn [] [:rect {:width 11, :height 12}])]
      (with-redefs-fn {#'dao.mr-clean.compiler/fn-accepts-zero-arity? (fn [_]
                                                                        false)}
        (fn []
          (is (= [{:op/kind :draw/fill-rect, :rect [0 0 11 12]}]
                 (compiler/compile-ui root nil {} {}))))))))


(deftest compile-ui-does-not-force-nil-into-variadic-roots
  (testing
    "when props are omitted, a variadic root should be invoked with zero args rather than [nil]"
    (let [seen-args (atom ::unset)
          root
          (fn [& xs] (reset! seen-args xs) [:rect {:width 13, :height 14}])]
      (is (= [{:op/kind :draw/fill-rect, :rect [0 0 13 14]}]
             (compiler/compile-ui root nil {} {})))
      ;; Clojure binds `& xs` to nil when invoked with zero args; what we
      ;; must NOT see is `(nil)`, which would mean the compiler forced a
      ;; nil arg through.
      (is (not= ::unset @seen-args) "root must have been invoked")
      (is (empty? @seen-args)
          "& xs should hold no rest args (nil or ()), never (nil)"))))


(deftest compile-ui-preserves-real-unary-root-errors-in-non-jvm-fallback
  (testing
    "non-JVM root fallback should not swallow a real unary-root compile exception"
    (let [root (fn [_props]
                 (throw (ex-info "boom from unary root" {:phase :root})))]
      (with-redefs-fn {#'dao.mr-clean.compiler/fn-accepts-one-arity? (fn [_]
                                                                       false),
                       #'dao.mr-clean.compiler/fn-accepts-zero-arity? (fn [_]
                                                                        false)}
        (fn []
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"boom from unary root"
                (compiler/compile-ui root nil {} {}))))))))


(deftest components-can-omit-props
  (testing "a zero-arity component should compile when used as [my-comp]"
    (let [zero-arity (fn [] [:rect {:width 3, :height 4}])]
      (is (= [{:op/kind :draw/fill-rect, :rect [0 0 3 4]}]
             (compiler/compile-ui [zero-arity] {} {} {})))))
  (testing "a child-only component should compile when used as [my-comp child]"
    (let [child-only (fn [child] [:stack child])]
      (is (= [{:op/kind :draw/fill-rect, :rect [0 0 5 6]}]
             (compiler/compile-ui [child-only [:rect {:width 5, :height 6}]]
                                  {}
                                  {}
                                  {})))))
  (testing
    "a zero-arity root component should compile without forcing an empty props map"
    (let [root (fn [] [:rect {:width 7, :height 8}])]
      (is (= [{:op/kind :draw/fill-rect, :rect [0 0 7 8]}]
             (compiler/compile-ui root nil {} {}))))))


(deftest empty-op-vectors-compile-as-no-ops
  (testing "an empty raw-op vector should compile as a no-op contribution"
    (is (= [] (compiler/compile-ui [] {} {} {}))))
  (testing "a component that conditionally returns [] should compile as a no-op"
    (let [maybe-op (fn [] [])]
      (is (= [] (compiler/compile-ui [maybe-op] {} {} {})))))
  (testing "overlay should accept an empty raw-op vector as a no-op"
    (let [result (binding [compiler/*current-context*
                           {:translate-only? true, :abs-x 10, :abs-y 20}
                           compiler/*constraints* {:max-width :unbounded,
                                                   :max-height :unbounded}
                           compiler/*capabilities*
                           {:measure-text (fn [_] {:width 100, :height 20})}
                           compiler/*snapshot* {}]
                   (compiler/overlay []))]
      (is (= [] (:flow result)))
      (is (= [{:op/kind :transform/push, :translate [10 20], :absolute? true}
              {:op/kind :transform/pop}]
             (:overlay result))))))


(deftest emitted-frame-matches-current-postgraphics-vm-shape
  (testing
    "mr-clean should emit the field shapes the current Flutter VM validates"
    (let [capabilities {:measure-text (fn [_] {:width 40, :height 12})}
          frame
          (compiler/compile-ui
            [:column [:rect {:width 12, :height 7, :color [1.0 0.0 0.0 1.0]}]
             [:text {:value "hi", :font-size 14, :color [1.0 1.0 1.0 1.0]}]
             [:clip {:width 30, :height 20}
              [:image {:image/source :logo, :width 30, :height 20}]]]
            {}
            {}
            capabilities)]
      (is (= {:op/kind :draw/fill-rect,
              :rect [0 0 12 7],
              :color [1.0 0.0 0.0 1.0]}
             (first frame)))
      (is (= {:op/kind :transform/push, :translate [0 7]} (nth frame 1)))
      (is (= {:op/kind :draw/text,
              :text "hi",
              :position [0 0],
              :font-size 14,
              :color [1.0 1.0 1.0 1.0]}
             (nth frame 2)))
      (is (= {:op/kind :transform/pop} (nth frame 3)))
      (is (= {:op/kind :transform/push, :translate [0 19]} (nth frame 4)))
      (is (= {:op/kind :clip/push-rect, :rect [0 19 30 20]} (nth frame 5)))
      (is (= {:op/kind :draw/image, :image/source :logo, :rect [0 0 30 20]}
             (nth frame 6)))
      (is (= {:op/kind :clip/pop} (nth frame 7)))
      (is (= {:op/kind :transform/pop} (nth frame 8))))))


(deftest image-requires-image-source
  (testing ":image should fail fast when :image/source is omitted"
    (is (thrown?
          clojure.lang.ExceptionInfo
          (compiler/compile-ui [:image {:width 10, :height 10}] {} {} {}))))
  (testing ":image should also fail when the source key is misspelled"
    (is (thrown? clojure.lang.ExceptionInfo
          (compiler/compile-ui
            [:image {:image-src :logo, :width 10, :height 10}]
            {}
            {}
            {})))))


(deftest clip-enforces-translate-only
  (testing "emitting a clip under scale should throw a compiler error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Clip emitted under non-translation transform"
          (compiler/compile-ui [:transform {:scale [2 2]}
                                [:clip {:width 10, :height 10}]]
                               {}
                               {}
                               {}))))
  (testing "emitting a clip under nested translation is fine"
    (let [result (compiler/compile-ui [:transform {:translate [10 10]}
                                       [:clip {:width 10, :height 10}]]
                                      {}
                                      {}
                                      {})
          clip (first (filter #(= :clip/push-rect (:op/kind %)) result))]
      (is clip)
      (is (= [10 10 10 10] (:rect clip))
          "explicit translate should be baked into clip :rect origin"))))


(deftest clip-screen-space-under-layout-placement
  (testing
    "a clip nested inside a layout container should carry the placement in :rect"
    (let [result (compiler/compile-ui [:column [:rect {:width 0, :height 20}]
                                       [:clip {:width 30, :height 30}]]
                                      {}
                                      {}
                                      {})
          clip (first (filter #(= :clip/push-rect (:op/kind %)) result))]
      (is clip)
      (is
        (= [0 20 30 30] (:rect clip))
        "column should shift clip's screen-space :rect by the first child's height"))))


(deftest clip-constrains-child-measurement
  (testing "clip should measure children against its own width and height"
    (let [measure-calls (atom [])
          capabilities {:measure-text (fn [req]
                                        (swap! measure-calls conj req)
                                        {:width 10, :height 10})}
          _ (compiler/compile-ui [:column {:max-width 100, :max-height 80}
                                  [:clip {:width 30, :height 20}
                                   [:text {:value "wrapped"}]]]
                                 {}
                                 {}
                                 capabilities)
          req (first @measure-calls)]
      (is (= 30 (:max-width req)))
      (is (= 20 (:max-height req))))))


(deftest overlay-anchor-shifts-with-layout-placement
  (testing
    "an overlay called inside a layout-placed component should anchor at the placed screen position"
    (let [with-overlay (fn [] (compiler/overlay [:rect {:width 5, :height 5}]))
          result (compiler/compile-ui [:column [:rect {:width 0, :height 40}]
                                       [with-overlay]]
                                      {}
                                      {}
                                      {})
          anchor (first (filter #(and (= :transform/push (:op/kind %))
                                      (:absolute? %))
                                result))]
      (is anchor "overlay anchor should be in the assembled program")
      (is
        (= [0 40] (:translate anchor))
        "anchor should reflect the column's placement of the overlay-emitting child"))))


(deftest overlay-preserves-child-size-in-layout
  (testing
    "a component that returns overlay should still contribute its child's size to row layout"
    (let [with-overlay (fn [] (compiler/overlay [:rect {:width 10, :height 5}]))
          result (compiler/compile-ui [:row [with-overlay]
                                       [:rect {:width 7, :height 5}]]
                                      {}
                                      {}
                                      {})
          second-child-translate (some (fn [op]
                                         (and (= :transform/push (:op/kind op))
                                              (= [10 0] (:translate op))
                                              op))
                                       result)]
      (is
        second-child-translate
        "second child should be translated by the overlaid child's width instead of overlapping at the origin"))))


(deftest constraints-apportionment
  (testing "column should reduce max-height for subsequent children"
    (let [measure-calls (atom [])
          capabilities {:measure-text (fn [req]
                                        (swap! measure-calls conj req)
                                        {:width 10, :height 10})}
          _ (compiler/compile-ui [:column {:max-width 100, :max-height 50}
                                  [:text {:value "a"}] [:text {:value "b"}]]
                                 {}
                                 {}
                                 capabilities)]
      (is (= 50 (:max-height (first @measure-calls))))
      (is (= 40 (:max-height (second @measure-calls)))))))


(deftest explicit-zero-max-size-overrides-are-preserved
  (testing
    "primitive-local :max-width 0 and :max-height 0 should override parent constraints"
    (let [measure-calls (atom [])
          capabilities {:measure-text (fn [req]
                                        (swap! measure-calls conj req)
                                        {:width 0, :height 0})}
          _ (compiler/compile-ui
              [:column {:max-width 100, :max-height 50}
               [:text {:value "clamped", :max-width 0, :max-height 0}]]
              {}
              {}
              capabilities)
          req (first @measure-calls)]
      (is (= 0 (:max-width req)))
      (is (= 0 (:max-height req))))))


(deftest eval-path-includes-index
  (testing "evaluation path should track child indices"
    (let [paths (atom [])
          my-comp (fn []
                    (swap! paths conj compiler/*evaluation-path*)
                    [:rect {:width 1, :height 1}])]
      (compiler/compile-ui [:column [my-comp] [my-comp]] {} {} {})
      (is (= 2 (count @paths)))
      (is (= [:column 0 "dao.mr-clean.compiler-test/my-comp"] (first @paths)))
      (is (= [:column 1 "dao.mr-clean.compiler-test/my-comp"]
             (second @paths)))))
  (testing ":clip and :transform should also conj child indices onto the path"
    (let [paths (atom [])
          my-comp (fn []
                    (swap! paths conj compiler/*evaluation-path*)
                    [:rect {:width 1, :height 1}])]
      (compiler/compile-ui [:clip {:width 10, :height 10} [my-comp] [my-comp]]
                           {}
                           {}
                           {})
      (is (= [:clip 0 "dao.mr-clean.compiler-test/my-comp"] (first @paths)))
      (is (= [:clip 1 "dao.mr-clean.compiler-test/my-comp"] (second @paths)))
      (reset! paths [])
      (compiler/compile-ui [:transform {:translate [0 0]} [my-comp] [my-comp]]
                           {}
                           {}
                           {})
      (is (= [:transform 0 "dao.mr-clean.compiler-test/my-comp"]
             (first @paths)))
      (is (= [:transform 1 "dao.mr-clean.compiler-test/my-comp"]
             (second @paths))))))


(deftest direct-contribution-preserves-size-in-layout
  (testing
    "row layout should honor width from a component that returns a direct contribution map"
    (let [contribution (fn []
                         {:width 10,
                          :height 5,
                          :flow [{:op/kind :draw/fill-rect, :rect [0 0 10 5]}],
                          :overlay []})
          result
          (compiler/compile-ui [:row [contribution] [contribution]] {} {} {})]
      (is (some #(and (= :transform/push (:op/kind %))
                      (= [10 0] (:translate %)))
                result)
          "second child should be translated by the first child's width"))))


(deftest transform-bounds-impact-layout
  (testing "transform with scale should affect sibling placement in a row"
    (let [result (compiler/compile-ui [:row
                                       [:transform {:scale [2 2]}
                                        [:rect {:width 10, :height 10}]]
                                       [:rect {:width 10, :height 10}]]
                                      {}
                                      {}
                                      {})
          second-child-translate (some (fn [op]
                                         (and (= :transform/push (:op/kind op))
                                              (:translate op)))
                                       (drop 3 result))]
      (is
        (= [20.0 0.0] (mapv double second-child-translate))
        "second child should be translated by the scaled width (20) of the first child"))))


(deftest transform-bounds-translate-impact-layout
  (testing "transform with translate should affect sibling placement in a row"
    (let [result (compiler/compile-ui [:row
                                       [:transform {:translate [10 0]}
                                        [:rect {:width 10, :height 10}]]
                                       [:rect {:width 10, :height 10}]]
                                      {}
                                      {}
                                      {})
          second-child-translate (some (fn [op]
                                         (and (= :transform/push (:op/kind op))
                                              (:translate op)))
                                       (drop 3 result))]
      (is
        (= [20.0 0.0] (mapv double second-child-translate))
        "second child should be translated by the translated extent (20) of the first child"))))


(deftest transform-bounds-impact-layout-column
  (testing "transform with scale should affect sibling placement in a column"
    (let [result (compiler/compile-ui [:column
                                       [:transform {:scale [2 2]}
                                        [:rect {:width 10, :height 10}]]
                                       [:rect {:width 10, :height 10}]]
                                      {}
                                      {}
                                      {})
          second-child-translate (some (fn [op]
                                         (and (= :transform/push (:op/kind op))
                                              (:translate op)))
                                       (drop 3 result))]
      (is
        (= [0.0 20.0] (mapv double second-child-translate))
        "second child should be translated by the scaled height (20) of the first child"))))


(deftest transform-bounds-negative-translate
  (testing
    "negative translate should expand the reported width to avoid overlap with following siblings"
    (let [result (compiler/compile-ui [:row
                                       [:transform {:translate [-10 0]}
                                        [:rect {:width 10, :height 10}]]
                                       [:rect {:width 10, :height 10}]]
                                      {}
                                      {}
                                      {})
          second-child-translate (some (fn [op]
                                         (and (= :transform/push (:op/kind op))
                                              (:translate op)))
                                       (drop 3 result))]
      ;; Child 1 is 10x10, translated by -10. It spans [-10, 0].
      ;; Our implementation reports width 10 (max(0,0) - min(-10,0) = 10).
      ;; Sibling should be at x=10.
      (is
        (= [10.0 0.0] (mapv double second-child-translate))
        "second child should be translated by 10 to avoid overlap with the negatively translated first child")))
  (testing "large negative translate should report the full span from origin"
    (let [result (compiler/compile-ui [:row
                                       [:transform {:translate [-20 0]}
                                        [:rect {:width 10, :height 10}]]
                                       [:rect {:width 10, :height 10}]]
                                      {}
                                      {}
                                      {})
          second-child-translate (some (fn [op]
                                         (and (= :transform/push (:op/kind op))
                                              (:translate op)))
                                       (drop 3 result))]
      ;; spans [-20, -10]. max(0, -10) - min(-20, 0) = 0 - (-20) = 20.
      (is (= [20.0 0.0] (mapv double second-child-translate))))))


(deftest transform-bounds-rotation-negative
  (testing "rotation into negative coordinates should expand reported size"
    (let [result (compiler/compile-ui [:row
                                       [:transform {:rotate 3.1415926535} ; 180
                                        ;; deg
                                        [:rect {:width 10, :height 10}]]
                                       [:rect {:width 10, :height 10}]]
                                      {}
                                      {}
                                      {})
          second-child-translate (some (fn [op]
                                         (and (= :transform/push (:op/kind op))
                                              (:translate op)))
                                       (drop 3 result))]
      ;; Rotated 10x10 spans [-10, 0] in x and [-10, 0] in y.
      ;; width should be 10.
      (is (< (abs (- 10.0 (double (first second-child-translate)))) 1e-6)))))


(deftest transform-rejects-3d-forms
  (testing
    "3D-looking transform attrs should be rejected before emitting 2D draw ops"
    (doseq [attrs [{:translate [1 2 3]} {:scale [2 2 2]} {:rotate [1 0 0]}]]
      (is (thrown? clojure.lang.ExceptionInfo
            (compiler/compile-ui [:transform attrs
                                  [:rect {:width 10, :height 10}]]
                                 {}
                                 {}
                                 {}))
          (str "expected compiler to reject " attrs))))
  (testing "4x4 matrices should be rejected before reaching the Flutter backend"
    (let [matrix [2.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 1.0 0.0 10.0 0.0 0.0
                  1.0]]
      (is (thrown? clojure.lang.ExceptionInfo
            (compiler/compile-ui [:transform {:matrix matrix}
                                  [:rect {:width 10, :height 10}]]
                                 {}
                                 {}
                                 {})))))
  (testing
    "projective 3x3 matrices should be rejected because Flutter only accepts affine 2D matrices for 2D draw ops"
    (let [projective-matrix [1.0 0.0 0.0 0.0 1.0 0.0 0.1 0.0 1.0]]
      (is (thrown? clojure.lang.ExceptionInfo
            (compiler/compile-ui [:transform {:matrix projective-matrix}
                                  [:rect {:width 10, :height 10}]]
                                 {}
                                 {}
                                 {})))))
  (testing "3x3 matrices with non-unit homogeneous scale should be rejected"
    (let [scaled-homogeneous-matrix [1.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 2.0]]
      (is (thrown? clojure.lang.ExceptionInfo
            (compiler/compile-ui [:transform
                                  {:matrix scaled-homogeneous-matrix}
                                  [:rect {:width 10, :height 10}]]
                                 {}
                                 {}
                                 {})))))
  (testing ":matrix combined with :translate/:scale/:rotate should be rejected"
    (let [identity-3x3 [1.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 1.0]]
      (doseq [extra [{:translate [10 0]} {:scale [2 2]} {:rotate 1.0}]]
        (is (thrown? clojure.lang.ExceptionInfo
              (compiler/compile-ui [:transform
                                    (merge {:matrix identity-3x3} extra)
                                    [:rect {:width 10, :height 10}]]
                                   {}
                                   {}
                                   {}))
            (str "expected compiler to reject :matrix combined with " extra)))))
  (testing
    "non-vector or non-numeric translate/scale values should be rejected at compile time"
    (doseq [attrs [{:translate "ab"} {:translate '(1 2)} {:scale "ab"}
                   {:scale '(2 3)}]]
      (is (thrown? clojure.lang.ExceptionInfo
            (compiler/compile-ui [:transform attrs
                                  [:rect {:width 10, :height 10}]]
                                 {}
                                 {}
                                 {}))
          (str "expected compiler to reject malformed transform attrs "
               attrs))))
  (testing
    "3x3 matrices with non-numeric entries should be rejected before bound calculation"
    (let [bad-matrix [:x 0.0 0.0 0.0 1.0 0.0 0.0 0.0 1.0]]
      (is (thrown? clojure.lang.ExceptionInfo
            (compiler/compile-ui [:transform {:matrix bad-matrix}
                                  [:rect {:width 10, :height 10}]]
                                 {}
                                 {}
                                 {}))))))


(deftest layout-only-attrs-do-not-leak-into-emitted-ops
  (testing
    ":max-width/:max-height carried on a primitive should not appear in the emitted op"
    (let [capabilities {:measure-text (fn [_] {:width 10, :height 10})}
          frame (compiler/compile-ui [:column
                                      [:rect
                                       {:width 10,
                                        :height 10,
                                        :max-width 50,
                                        :max-height 50,
                                        :color [1.0 0.0 0.0 1.0]}]
                                      [:text {:value "x", :max-width 99}]
                                      [:transform
                                       {:translate [1 1], :max-width 7}
                                       [:rect {:width 1, :height 1}]]]
                                     {}
                                     {}
                                     capabilities)]
      (doseq [op frame]
        (is (not (contains? op :max-width))
            (str "no op should carry :max-width — found in " op))
        (is (not (contains? op :max-height))
            (str "no op should carry :max-height — found in " op)))
      (is (some #(= [1.0 0.0 0.0 1.0] (:color %)) frame)
          "drawing attrs like :color must still pass through"))))


(deftest rect-image-clip-reject-bad-dimensions
  (testing ":rect rejects negative width/height"
    (is (thrown?
          clojure.lang.ExceptionInfo
          (compiler/compile-ui [:rect {:width -1, :height 10}] {} {} {})))
    (is (thrown?
          clojure.lang.ExceptionInfo
          (compiler/compile-ui [:rect {:width 10, :height -1}] {} {} {}))))
  (testing ":image rejects negative width/height"
    (is (thrown? clojure.lang.ExceptionInfo
          (compiler/compile-ui
            [:image {:image/source :x, :width -1, :height 10}]
            {}
            {}
            {}))))
  (testing ":clip rejects negative width/height"
    (is (thrown?
          clojure.lang.ExceptionInfo
          (compiler/compile-ui [:clip {:width 10, :height -1}] {} {} {}))))
  (testing ":rect rejects non-numeric width/height"
    (is (thrown?
          clojure.lang.ExceptionInfo
          (compiler/compile-ui [:rect {:width "ten", :height 10}] {} {} {})))))
