(ns yin.vm.semantic.layout-test
  "Phase 6c acceptance 1-9 (docs/design/yin.vm.semantic.continuation.md
   §8.3): the register pairing and live sets, the register destination from
   A, the stack correspondence, operand layout and K slices, E
   reconstruction, closures both ways, and the unobservable fill of a
   lowered tail. The static items run over C, the B0 corpus and the
   phase-3 corpus (derive-test's wider C requires the Python ANTLR lane);
   the dynamic items run the harness's worlds on the de Bruijn lanes."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [clojure.set :as set]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.debruijn-code :as dcode]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.engine :as engine]
            [yin.vm.values :as values]
            [yin.vm.semantic.analysis :as analysis]
            [yin.vm.semantic.code :as code]
            [yin.vm.semantic.corpus :as corpus]
            [yin.vm.semantic.derive :as derive]
            [yin.vm.semantic.layout :as layout]
            [yin.vm.semantic.linearize :as linearize]
            [yin.vm.semantic.safepoint-harness-test :as h]
            [yin.vm.ucf.rows :as rows]
            [yin.vm.ucf.rows-support :as s]))


(defn retained-ffi-refusal
  [lane]
  (doseq [source h/lanes]
    (testing (str (name source) " -> " (name lane))
      (h/scoped
        (fn []
          (let [world (h/on-lane source #(h/retained-ffi identity))
                entry (first (:wait-set (:machine world)))
                {:keys [toy serve!]} (h/served (:surfaces world))
                wire (h/lane-lift (:machine world) serve!)
                task (rows/decode wire rows/default-budget)
                pending (second (:slots (first (nth (:slots task) 13))))
                expected {:yin.k/status :yin.k/unsupported :yin.k/kind :retained-ffi}
                {:keys [attach!] n :count} (h/recording-attacher toy)
                receiver ((:receiver world))]
            (is (= :put (:reason entry)))
            (is (true? (:request-sent entry)))
            (is (= :ffi-request (first (:slots pending))))
            (is (= expected (layout/lower-defect task lane)))
            (is (= expected (h/error #(h/lane-lower lane (merge {:attach-stream attach!} (:opts receiver)) wire attach!))))
            (is (zero? @n) "refused before any attachment"))))))
  (testing "an FFI response wait without a retained request still lowers and continues"
    (h/scoped
      (fn []
        (let [world (h/on-lane :semantic #(h/sent-ffi identity))
              {:keys [serve!]} (h/served #{:reader})
              wire (h/lane-lift (:machine world) serve!)]
          (is (nil? (:request-sent (first (:wait-set (:machine world))))))
          (is (nil? (layout/lower-defect (rows/decode wire rows/default-budget) lane))))))
    (let [got (h/crossed :semantic lane #(h/sent-ffi identity))]
      (is (pos? (:attaches got)))
      (is (:halted got))
      (is (= "echo!" (:value got))))))


(deftest stack-refuses-retained-ffi-before-attach
  (retained-ffi-refusal :stack))


(deftest register-refuses-retained-ffi-before-attach
  (retained-ffi-refusal :register))


(def C
  "[label ast]: the harness's layout corpus."
  h/layout-corpus)


(defn projected
  [ast]
  (:vector (linearize/project ast)))


(defn reachable-pcs
  "Every pc reachable from its body's start."
  [v]
  (into #{} (mapcat #(analysis/reachable v (:start %))) (code/body-ranges v)))


(defn boundary?
  [t]
  (contains? rcode/boundary-opcodes (nth t 0)))


(defn parking?
  "A non-tail parking instruction (rule A1)."
  [t]
  (or (contains? #{:stream-next :stream-put :ffi-call :park :current-continuation} (nth t 0))
      (and (= :call (nth t 0)) (not (code/tail-call? t)))))


;; =============================================================================
;; 1-2: the register kernel's static layout
;; =============================================================================

(deftest register-pairing
  (let [checked (atom [0 0])]
    (doseq [[label ast] C]
      (testing (str label)
        (let [v (projected ast)
              sg (layout/segment v)
              bodies (:bodies sg)]
          ;; phys is total on every body's named virtuals and single-valued
          (doseq [[pc x s] (:pairs sg)]
            (is (= s (layout/phys sg pc x)) (str "pc " pc " virtual " x)))
          (doseq [b (range (count bodies))]
            (let [named (into #{} (keep (fn [[pc x]] (when (= b (code/body-index bodies pc)) x))) (:pairs sg))]
              (is (= named (set (keys (nth (:phys sg) b)))))))
          ;; virt is single-valued on live-out's physical image
          (doseq [p (sort (reachable-pcs v))
                  :when (boundary? (nth v p))]
            (let [lo (layout/live-out sg p)]
              (is (= (count lo) (count (set (map #(layout/phys sg p %) lo))))
                  (str "distinct live virtuals hold distinct slots at pc " p))
              (doseq [x lo]
                (is (= x (layout/virt sg p (layout/phys sg p x))))))
            (swap! checked update 1 inc))
          (swap! checked update 0 inc))))
    (is (= (count C) (first @checked)))
    (is (< 50 (second @checked)) "reachable boundaries checked")))


(deftest register-live-is-live-out
  (let [tails (atom 0)]
    (doseq [[label ast] C]
      (testing (str label)
        (let [v (projected ast)
              sg (layout/segment v)]
          (doseq [p (sort (reachable-pcs v))
                  :let [t (nth v p)]
                  :when (boundary? t)]
            (let [live (nth (nth (:instructions (:register sg)) p) (rcode/live-slot-index (nth t 0)))]
              (is (= (layout/register-live sg p) live) (str "pc " p))
              (when (code/tail-call? t)
                (swap! tails inc)
                (is (= [] live (layout/register-live sg p)) "a tail boundary's live is []")))))))
    (is (pos? @tails))))


;; =============================================================================
;; 3: rd from A, never the physical destination
;; =============================================================================

(def worked-ast (second (first (filter #(= :worked-example (first %)) corpus/programs))))


(defn in-world
  "(f machine) of the world build makes on lane, its construction and its
   layout lookups in one h/scoped: no other fixture's vector is in its
   space."
  [lane build f]
  (h/scoped #(f (:machine (h/on-lane lane build)))))


(defn lifted-waits
  "The canonical wait entries of world machine m: the evaluator's own, or a
   de Bruijn kernel's through the layout. m's kernel-space is its scope's."
  [m]
  (if (= :semantic (h/lane-of m))
    (:wait-set m)
    (:wait-set (layout/canonical-task m (h/kernel-space m) (h/on-lane :semantic h/machine)))))


(deftest register-rd-from-a
  (testing "the worked example parked at its inner call"
    (h/scoped
      (fn []
        (let [m0 (h/on-lane :register h/machine)
              [sref m1] (engine/attach-resource m0 (s/ring 4))
              [cref m2] (engine/handle-cursor m1 {:stream sref} :k1)
              next! (engine/resolve-var {} {} vm/primitives h/stream-modules 'stream/next!)
              m (vm/run (h/load-program (assoc m2 :free-env {'f (get vm/primitives 'conj) 'g next! 'x cref 'y 2})
                                        worked-ast))
              entry (first (:wait-set m))
              [p] (lifted-waits m)
              v (projected worked-ast)]
          (is (vm/blocked? m))
          (is (= [:call 2 3 [4] false] (nth v 3)) "the inner call")
          (is (= 4 (:pc p)))
          (is (= {:deliver :rd :rd 2} (:deliver p)) "the canonical rd is A's 2")
          (is (= 3 (:dest entry)) "the kernel's dest is the physical 3")
          (is (= #{1} (set (keys (:window p)))) "saved(4, 2)")))))
  (testing "the K frames of :depth-3 take rd from A"
    (let [[_ wrap] (nth h/segments 2)
          build #(h/reader wrap h/read-op)
          reference (first (:wait-set (:machine (h/on-lane :semantic build))))
          [m [p]] (in-world :register build (juxt identity lifted-waits))
          entry (first (:wait-set m))]
      (is (= :depth-3 (first (nth h/segments 2))))
      (is (= 6 (count (:k p))) "three bindings, three nest applications")
      (is (= (mapv :rd (:k reference)) (mapv :rd (:k p))) "the evaluator's return destinations")
      (is (some (fn [[f kf]] (not= (:rd f) (:dest kf))) (map vector (:k p) (:continuation entry)))
          "some physical dest differs from its virtual rd"))))


;; =============================================================================
;; 4-5: the stack correspondence and operand layout
;; =============================================================================

(defn family
  [op]
  (case op (:load-bound :load-free) :var op))


(deftest stack-correspondence
  (doseq [[label ast] C]
    (testing (str label)
      (let [v (projected ast)
            sg (layout/segment v)
            h (:stack sg)]
        (is (= (count v) (count (:a->h sg))) "one non-push H instruction per A pc")
        (is (= (count h) (+ (count v) (count (filter #(= :push (first %)) h)))))
        (doseq [k (range (count v))]
          (is (= (nth (nth v k) 0) (family (nth (nth h (nth (:a->h sg) k)) 0)))
              (str "the k-th non-push instruction, k = " k))
          (is (= k (get (:h->a sg) (nth (:a->h sg) k)))))))))


(deftest stack-layout-is-the-saved-set
  (let [checked (atom 0)]
    (doseq [[label ast] C]
      (testing (str label)
        (let [v (projected ast)
              sg (layout/segment v)
              a (:analysis sg)]
          (doseq [p (sort (reachable-pcs v))
                  :let [t (nth v p)]
                  :when (parking? t)]
            (is (= (analysis/saved a (inc p) (code/rd t)) (set (layout/stack-layout sg p))) (str "pc " p))
            (is (apply distinct? (cons ::bottom (layout/stack-layout sg p))))
            (swap! checked inc)))))
    (is (< 20 @checked)))
  (testing "the worked cases"
    (let [first-call (fn [v] (first (keep-indexed (fn [pc t] (when (= :call (first t)) pc)) v)))]
      (doseq [ast [worked-ast
                   (corpus/app (corpus/v 'f) (corpus/if-node (corpus/v 'c) (corpus/app (corpus/v 'g)) (corpus/lit 2))
                               (corpus/v 'y))]]
        (let [v (projected ast)
              sg (layout/segment v)]
          (is (= [1] (layout/stack-layout sg (first-call v))) "[f], f in register 1"))))))


;; =============================================================================
;; 6: K slices partition the operand stack
;; =============================================================================

(defn stack-frames
  "[[A-pc slice] …] for return frames then the current activation of
   stack entry e of kernel k, reading the kernel's own offset table."
  [k e]
  (let [sp (h/kernel-space k)
        a-pc (fn [abs]
               (let [[ident rel] (dvm/image-pc k abs)
                     sg (some #(when (= ident (:stack-id %)) %) (vals (:segments sp)))]
                 [sg (inc (get (:h->a sg) (dec rel)))]))
        parts (layout/slices (vec (:stack e)) (mapv :stack-base (:continuation e)))]
    (mapv (fn [abs slice] (conj (a-pc abs) slice))
          (conj (mapv :return-pc (:continuation e)) (:pc e))
          parts)))


(defn non-tail?
  "6c-pre marks a tail call's park :return-result."
  [e]
  (not= :return-result (:resume-mode e)))


(deftest stack-slices-partition
  (let [checked (atom 0)]
    (doseq [[row label _ build] (h/lane-cases)
            :when (contains? #{:bare :depth-3 :unwrapped} label)]
      (testing (str "row " row " " (name label))
        (in-world :stack build
                  (fn [k]
                    (doseq [e (concat (:wait-set k) (vals (:parked k)))
                            :when (non-tail? e)]
                      (let [frames (stack-frames k e)]
                        (is (= (vec (:stack e)) (into [] (mapcat #(nth % 2)) frames)) "the slices partition the stack")
                        (doseq [[sg pc slice] frames]
                          (is (= (count (layout/stack-layout sg (dec pc))) (count slice))))
                        (swap! checked inc)))))))
    (is (< 20 @checked)))
  (testing "the multi-frame fixture"
    (h/scoped
      (fn []
        (let [expr (h/cat-of (s/lit [1])
                             (s/app (s/lam ['x] (h/cat-of (s/lit [2])
                                                          (s/app (s/lam ['y] (h/cat-of (s/lit [3]) h/read-op))
                                                                 (s/lit 2))))
                                    (s/lit 1)))
              world (h/on-lane :stack #(h/reader identity expr))
              k (:machine world)
              e (first (:wait-set k))
              st (:stack e)]
          (is (= [0 0 0 2 4] (mapv :stack-base (:continuation e))) "the bases, pinned from the kernel")
          (is (= 6 (count st)))
          (is (= [[] [] [] 2 2 2] (mapv #(if (empty? %) [] (count %)) (layout/slices (vec st) [0 0 0 2 4]))))
          (is (= [[1] [2] [3]] (mapv #(nth st %) [1 3 5])) "each slice holds its activation's cat and literal")
          (is (every? values/closure? (map #(nth st %) [0 2 4])))
          (is (= (vec st) (into [] cat (layout/slices (vec st) [0 0 0 2 4]))))
          (let [sp (h/kernel-space k)
                p (layout/lift-state (layout/lift-context k sp (:owner k)) e)
                fresh (layout/attach-space (h/on-lane :stack h/machine) sp)
                lowered (layout/lower-state (layout/lower-context fresh sp) p)]
            (is (= 5 (count (:k p))))
            (is (= [0 0 0 2 4] (mapv :stack-base (:continuation lowered))) "the lowering emits the same bases")
            (is (= 6 (count (:stack lowered))))
            (is (= (h/normalize (:stack e)) (h/normalize (:stack lowered))))
            (stream/append! (s/stream-of k) "B")
            (is (= [1 [2 [3 "B"]]] (h/normalize (vm/value (h/drive-local (assoc fresh :wait-set [lowered] :blocked? true
                                                                                :halted? false :value :yin/blocked
                                                                                :resources (:resources k))))))
                "and it continues")))))))


;; =============================================================================
;; 7: E reconstruction
;; =============================================================================

(def env-programs
  "The B2 duplicate-parameter, nested-closure and nested-lambdas shapes,
   each blocked on a read inside its innermost body."
  (let [read-into (fn [& xs] (reduce (fn [acc x] (h/cat-of acc x)) (s/lit []) (concat xs [h/read-op])))]
    [[:duplicate-param (s/app (s/lam ['x 'x] (read-into (s/v 'x))) (s/lit 1) (s/lit 2)) [2 "B"]]
     [:nested-closure (s/app (s/app (s/lam ['a 'a] (s/lam ['b] (read-into (s/v 'a) (s/v 'b)))) (s/lit 1)) (s/lit 2))
      [nil 2 "B"]]
     [:nested-lambdas (s/app (s/app (s/lam ['a] (s/lam ['b] (read-into (s/v 'a) (s/v 'b)))) (s/lit 1)) (s/lit 2))
      [1 2 "B"]]]))


(defn envs
  "Every E of a machine's canonical waits and parked records, outermost
   K frame first, values normalised."
  [m]
  (let [waits (lifted-waits m)
        parked (if (= :semantic (h/lane-of m))
                 (vals (:parked m))
                 (vals (:parked (layout/canonical-task m (h/kernel-space m) (h/on-lane :semantic h/machine)))))]
    (h/normalize (mapv (fn [p] (conj (mapv :env (:k p)) (:env p))) (concat waits (sort-by :id parked))))))


(deftest env-reconstruction
  (let [checked (atom 0)
        cases (into (mapv (fn [[row label _ build]] [(str "row " row " " (name label)) build]) (h/lane-cases))
                    (map (fn [[label ast]] [(name label) #(h/reader identity ast)]))
                    env-programs)]
    (doseq [[label build] cases]
      (testing label
        (let [expected (envs (:machine (h/on-lane :semantic build)))]
          (doseq [lane [:stack :register]]
            (is (= expected (in-world lane build envs)) (str (name lane) "'s E is the evaluator's"))
            (swap! checked inc)))))
    (is (= (* 2 (+ 61 3)) @checked)))
  (testing "row 21: the read's E binds x to 1, the free environment keeps 99"
    (let [build #(h/free-env-world identity {'x 99})]
      (doseq [lane h/lanes]
        (let [[m [p]] (in-world lane build (juxt identity lifted-waits))]
          (is (= 1 (get-in p [:env 'x])))
          (is (= {'x 99} (:free-env m)))))))
  (testing "the env programs answer"
    (doseq [[label ast expected] env-programs
            lane h/lanes]
      (is (= expected (:value (h/reference (h/on-lane lane #(h/reader identity ast))))) (str label " " lane)))))


;; =============================================================================
;; 8: closures, canonical and positional, both ways
;; =============================================================================

(def closure-programs
  "\"closure value\", \"higher order\" (its closure held across a read)
   and :duplicate-param, as halted values or lexical bindings."
  [[:closure-value (s/lam ['x] (s/v 'x))]
   [:higher-order (s/app (s/lam ['f] (h/cat-of (s/app (s/v 'f) (s/lit 5)) h/read-op)) (s/lam ['x] (s/v 'x)))]
   [:duplicate-param (s/app (s/lam ['x 'x] (s/lam ['y] (s/v 'x))) (s/lit 1) (s/lit 2))]])


(defn closures-in
  [x]
  (cond
    (values/closure? x) [x]
    (map? x) (mapcat closures-in (concat (keys x) (vals x)))
    (coll? x) (mapcat closures-in x)
    :else nil))


(defn kernel-closures
  "Every positional closure of de Bruijn kernel k's value and its waits'
   frames."
  [k]
  (closures-in [(:value k) (mapv :frames (:wait-set k))]))


(defn canonical-shape
  "A canonical closure payload with its segment named by A."
  [address c]
  (h/normalize (assoc (select-keys c [:params :entry :env]) :segment (address (:segment c)))))


(def alpha-renames
  "Every binder of closure-programs renamed: an alpha-equivalent A of each,
   one native image with it."
  '{x other-x y other-y f other-f})


(deftest closures-round-trip
  ;; an unrelated alpha-equivalent of every fixture, projected file-wide
  ;; first: each world's own scope must not see it
  (doseq [[_ ast] closure-programs]
    (h/project (h/with-stream (h/with-cat (walk/postwalk-replace alpha-renames ast)))))
  (let [checked (atom 0)]
    (doseq [[label ast] closure-programs
            lane [:stack :register]]
      (testing (str (name label) " " (name lane))
        (h/scoped
          (fn []
            (let [sr (:machine (h/on-lane :semantic #(h/reader identity ast)))
                  k (:machine (h/on-lane lane #(h/reader identity ast)))
                  sp (h/kernel-space k)
                  cx (layout/lift-context k sp (:owner k))
                  lx (layout/lower-context k sp)
                  expected (mapv #(canonical-shape (fn [seg] (get-in sr [:code seg :address])) (values/payload %))
                                 (closures-in [(:value sr) (mapv :env (:wait-set sr))]))
                  positional (kernel-closures k)
                  canonical (mapv #(layout/canonical-value cx %) positional)]
              (is (seq positional))
              (is (= (set expected) (set (map #(canonical-shape identity (values/payload %)) canonical)))
                  "positional -> canonical is the evaluator's closure")
              (doseq [[p c] (map vector positional canonical)]
                (let [back (values/payload (layout/positional-value lx c))]
                  (is (= (select-keys (values/payload p) [:arity :body-pc :store-of]) (select-keys back [:arity :body-pc :store-of])))
                  (is (= (h/normalize (values/payload c))
                         (h/normalize (values/payload (layout/canonical-value cx (layout/positional-value lx c)))))
                      "canonical -> positional -> canonical is the identity")
                  (if (and (= :duplicate-param label) (= [1 2] (peek (:frames (values/payload p)))))
                    (is (= [2 2] (peek (:frames back))) "the shadowed position reads as the visible x")
                    (is (= (h/normalize (:frames (values/payload p))) (h/normalize (:frames back)))
                        "positional -> canonical -> positional keeps every frame"))
                  (swap! checked inc))))))))
    (is (<= 6 @checked)))
  (testing "the duplicate binder's visible value survives"
    (let [k (:machine (h/on-lane :stack #(h/reader identity (s/app (second (nth closure-programs 2)) (s/lit 7)))))]
      (is (= 2 (vm/value k))))))


;; =============================================================================
;; 9: a lowered tail's frames and store-of are unobservable
;; =============================================================================

(defn tail-lowered
  "Row n lifted on the reference lane and lowered into lane, its tail
   entries' :frames and :store-of replaced by fill when given: the run's
   observables and the entries as lowered."
  [build lane fill]
  (let [world (h/on-lane :semantic build)
        {:keys [toy peer serve!]} (h/served #{:reader})
        wire (h/lane-lift (:machine world) serve!)
        {:keys [attach!]} (h/recording-attacher toy)
        recv (h/lane-lower lane {:attach-stream attach!} wire attach!)
        entries (:wait-set recv)
        recv (if fill
               (assoc recv :wait-set (mapv #(if (= :return-result (:resume-mode %)) (merge % fill) %) entries))
               recv)
        done (h/satisfy world recv #(h/drive-peers % [peer]))]
    {:entries entries :observed [(vm/halted? done) (h/normalize (vm/value done)) (h/normalize (vm/store done))]}))


(deftest tail-lowering-is-unobservable
  (let [checked (atom 0)]
    (doseq [[n build] [[6 #(h/bare 6)] [20 h/tail-effect-halt]]
            lane [:stack :register]]
      (testing (str "row " n " " (name lane))
        (let [plain (tail-lowered build lane nil)
              junk (tail-lowered build lane {:frames [[:junk 1] [:junk 2]] :store-of 'junk.mod})
              [entry] (:entries plain)]
          (is (= :return-result (:resume-mode entry)))
          (is (= [] (:frames entry)) "lowered with []")
          (is (nil? (:store-of entry)) "and no store-of")
          (is (= (:observed plain) (:observed junk)) "arbitrary values change nothing")
          (is (= "B" (second (:observed plain))))
          (swap! checked inc))))
    (is (= 4 @checked) "rows 6 and 20 on both de Bruijn lanes; 9 and 12 are link rows")))


;; =============================================================================
;; §4.1: alpha-equivalent segments, one native image, two A
;; =============================================================================
;;
;; Every world's layout lookups run in its own h/scoped; only the
;; contamination case below reads the file-wide index, explicitly.

(defn binder-program
  "A reader parked inside ((fn [sym] (then :alpha (next-of c))) 0)."
  [sym]
  (h/with-stream (h/with-cat (s/app (s/lam [sym] (s/then (s/lit :alpha) h/read-op)) (s/lit 0)))))


(defn binder-world
  [sym]
  (h/reader (fn [_] (s/app (s/lam [sym] (s/then (s/lit :alpha) h/read-op)) (s/lit 0))) h/read-op))


(defn alpha-closure
  [sym]
  (s/lam [sym] (s/then (s/lit :alpha) (s/v sym))))


(defn alpha-world
  "Two alpha-equivalent closures in the store, then (b (a \"B\")) after a
   read; with :one, both names hold the first and the second is only
   projected."
  ([] (alpha-world :both))
  ([carried]
   (let [m1 (vm/run (h/load-program (h/machine) (alpha-closure 'x)))
         m2 (vm/run (h/load-program m1 (alpha-closure 'y)))
         m3 (assoc m2 :store {'a (vm/value m1) 'b (vm/value (if (= :one carried) m1 m2))})
         m (vm/run (h/load-program m3 (h/with-stream (s/then h/read-op (s/app (s/v 'b) (s/app (s/v 'a) (s/lit "B")))))))
         src (s/stream-of m)]
     {:machine m :kind :blocked :reason :next :steps [#(stream/append! src "B")] :trace (atom [])})))


(defn receiver-image
  "The image identity a de Bruijn receiver of kind derives for v (§4.1)."
  [kind v]
  (if (= :stack kind)
    (dcode/image-hash (:image (derive/stack-image v)))
    (rcode/register-hash (derive/register-image v))))


(deftest alpha-equivalent-segments
  (testing "distinct A, one register image, one stack image"
    (let [sx (layout/segment (projected (s/lam ['x] (s/v 'x))))
          sy (layout/segment (projected (s/lam ['y] (s/v 'y))))]
      (is (not= (:address sx) (:address sy)))
      (is (= (:register-id sx) (:register-id sy)))
      (is (= (:stack-id sx) (:stack-id sy)))))
  (testing "a de Bruijn source whose space resolves one image to two A refuses the lift before serving"
    (let [ax (layout/segment (h/project (binder-program 'x)))
          ay (layout/segment (h/project (binder-program 'y)))]
      (is (not= (:address ax) (:address ay)))
      (is (= [(:register-id ax) (:stack-id ax)] [(:register-id ay) (:stack-id ay)]))
      (doseq [lane [:stack :register]]
        (testing (name lane)
          (let [m (:machine (h/on-lane lane #(binder-world 'x)))
                n (atom 0)
                {:keys [serve!]} (h/served #{:reader})
                counting #(do (swap! n inc) (serve! %))
                template (h/on-lane :semantic h/machine)
                d (h/error #(layout/lift-task m (layout/space [ax ay]) template counting (h/lift-opts)))]
            (is (vm/blocked? m))
            (is (contains? (set (map first (:images m))) (if (= :stack lane) (:stack-id ax) (:register-id ax)))
                "the kernel holds the one image")
            (is (= [:yin.k/non-portable :ambiguous-image] [(:yin.k/status d) (:yin.k/kind d)]) (pr-str d))
            (is (zero? @n) "no stream was served")
            (testing ", the file-wide code-index widens the space; the world's own does not"
              (is (= :ambiguous-image (:yin.k/kind (h/file-wide (fn [] (h/error #(h/lane-lift m counting)))))))
              (is (zero? @n))
              (h/scoped (fn []
                          (let [m (:machine (h/on-lane lane #(binder-world 'x)))]
                            (is (= #{(:address ax)} (set (keys (:segments (h/kernel-space m))))))
                            (is (map? (h/lane-lift m serve!)) "the world's own space lifts"))))))))))
  (testing "a de Bruijn receiver of two A of one image refuses before any attach"
    (let [world (alpha-world)
          addresses (into #{} (map #(:address (layout/segment (projected (alpha-closure %))))) '[x y])]
      (is (= 2 (count addresses)))
      (doseq [lane [:stack :register]]
        (testing (name lane)
          (let [{:keys [serve! toy]} (h/served #{:reader})
                wire (h/lane-lift (:machine world) serve!)
                {:keys [attach!] n :count} (h/recording-attacher toy)
                d (h/error #(h/lane-lower lane {:attach-stream attach!} wire attach!))]
            (is (= [:yin.k/unsupported :ambiguous-image] [(:yin.k/status d) (:yin.k/kind d)]) (pr-str d))
            (is (= addresses (:yin.k/segments d)))
            (is (= (receiver-image lane (projected (alpha-closure 'x))) (:yin.k/image d))
                "the receiver's own image identity")
            (is (= (receiver-image lane (projected (alpha-closure 'y))) (:yin.k/image d)))
            (is (zero? @n) "refused before any attachment"))))))
  (testing "one of the pair carried, the other only projected: no collision"
    (doseq [lane [:stack :register]]
      (testing (name lane)
        (let [ref (h/reference (alpha-world :one))
              got (h/crossed :semantic lane #(alpha-world :one))]
          (is (= "B" (:value ref)))
          (is (= 1 (count (h/stream-identities (:wire got)))))
          (is (= 1 (:attaches got)) "lowered, one attach")
          (is (= (select-keys ref [:halted :value]) (select-keys got [:halted :value])))))))
  (testing "the reference lane holds both: laws 1 and 2"
    (let [expected (h/reference (alpha-world))]
      (is (= "B" (:value expected)))
      (is (= "B" (:value (h/laws (alpha-world) expected)))))))


;; =============================================================================
;; §6.3: a closure in the free environment, refused on the de Bruijn lanes
;; =============================================================================

(defn free-closure-world
  "An identity closure f the machine's own lane made, held in the free
   environment, then (then (next-of c) (f \"B\")) parked on the read."
  []
  (let [m0 (vm/run (h/load-program (h/machine) (s/lam ['arg] (s/v 'arg))))
        f (vm/value m0)
        m1 (cond-> (update m0 :free-env assoc 'f f)
             (= :semantic (h/lane-of m0)) (update :env assoc 'f f))
        m (vm/run (h/load-program m1 (h/with-stream (s/then h/read-op (s/app (s/v 'f) (s/lit "B"))))))
        src (s/stream-of m)]
    {:machine m :kind :blocked :reason :next :steps [#(stream/append! src "B")] :trace (atom [])}))


(deftest free-env-values
  (testing "the reference lane and the carrier admit it"
    (let [world (h/on-lane :semantic free-closure-world)]
      (is (values/closure? (get-in world [:machine :free-env 'f])))
      (is (= "B" (:value (h/reference (h/on-lane :semantic free-closure-world)))))
      (is (= "B" (:value (h/handed-off world))))))
  (testing "a de Bruijn receiver refuses it before any attach"
    (doseq [lane [:stack :register]]
      (testing (name lane)
        (let [world (h/on-lane :semantic free-closure-world)
              {:keys [serve! toy]} (h/served #{:reader})
              wire (h/lane-lift (:machine world) serve!)
              {:keys [attach!] n :count} (h/recording-attacher toy)
              d (h/error #(h/lane-lower lane {:attach-stream attach!} wire attach!))]
          (is (= [:yin.k/unsupported :free-env-value] [(:yin.k/status d) (:yin.k/kind d)]) (pr-str d))
          (is (= '[:free-env f] (:yin.k/path d)))
          (is (zero? @n) "refused before any attachment")))))
  (testing "a de Bruijn source refuses it before anything is served"
    (doseq [lane [:stack :register]]
      (testing (name lane)
        (h/scoped
          (fn []
            (let [world (h/on-lane lane free-closure-world)
                  m (:machine world)
                  n (atom 0)
                  {:keys [serve!]} (h/served #{:reader})
                  d (h/error #(h/lane-lift m (fn [x] (swap! n inc) (serve! x))))]
              (is (= lane (h/lane-of m)))
              (is (vm/blocked? m))
              (is (values/closure? (get-in m [:free-env 'f])))
              (is (= [:yin.k/non-portable :free-env-value] [(:yin.k/status d) (:yin.k/kind d)]) (pr-str d))
              (is (= 'f (:yin.k/key d)))
              (is (zero? @n) "no stream was served"))))))))


(def nestings
  "[label wrap extract path]: a value one guest collection deep under box,
   the AST that reads it back, and the refusal's path to it."
  [[:vector (fn [x] [1 [x]]) (s/app (s/v 'first) (s/app (s/v 'first) (s/app (s/v 'rest) (s/v 'box))))
    '[:free-env box 1 0]]
   [:map (fn [x] {:nested x}) (s/app (s/v 'get) (s/v 'box) (s/lit :nested)) '[:free-env box 1]]])


(def held
  "[label made program expected]: (made m) is [value m feed], a value made
   on m's own lane (feed, a ring the read is answered on, else the
   program's own stream); program uses the value through extract."
  [[:closure (fn [m] (let [m (vm/run (h/load-program m (s/lam ['arg] (s/v 'arg))))] [(vm/value m) m nil]))
    (fn [extract] (h/with-stream (s/app extract h/read-op))) "B"]
   ;; captured where box is lexical: its E agrees with any free environment
   ;; on every other name, so the carrier lifts it beside box (§5.5 frames)
   [:continuation (fn [m]
                    (let [m (vm/run (h/load-program m (h/tail (s/app (s/lam ['box] h/capture) (s/lit 0)))))]
                      [(vm/value m) m nil]))
    (fn [_] (h/with-stream h/read-op)) "B"]
   [:primitive (fn [m] [(get vm/primitives 'conj) m nil])
    (fn [extract] (h/with-stream (s/then h/read-op extract))) (get vm/primitives 'conj)]
   [:cursor (fn [m]
              (let [ring (s/ring 4)
                    [sref m] (engine/attach-resource m ring)
                    [cref m] (engine/handle-cursor m {:stream sref} :held)]
                [cref m ring]))
    (fn [extract] (s/next-of extract)) "B"]])


(defn boxed-world
  "A world on the machine's own lane: made's value wrapped under box in
   the free environment, program parked on its read."
  [made wrap program extract]
  (let [[x m0 feed] (made (h/machine))
        box (wrap x)
        m1 (cond-> (update m0 :free-env assoc 'box box)
             (= :semantic (h/lane-of m0)) (update :env assoc 'box box))
        m (vm/run (h/load-program m1 (program extract)))
        src (or feed (s/stream-of m))]
    {:machine m :kind :blocked :reason :next :steps [#(stream/append! src "B")] :trace (atom [])}))


(deftest free-env-nested-values
  ;; §6.3 through guest collections: a closure or continuation refuses both
  ;; ways; a primitive and an authentic cursor reference cross and are used
  (doseq [[nest wrap extract path] nestings
          [label made program expected] held]
    (testing (str (name label) " in a " (name nest))
      (let [build #(boxed-world made wrap program extract)
            procedure? (contains? #{:closure :continuation} label)]
        (is (= (h/normalize expected) (:value (h/reference (h/on-lane :semantic build)))) "the reference lane")
        (doseq [lane [:stack :register]]
          (testing (str ", lowered into " (name lane))
            (if procedure?
              (let [world (h/on-lane :semantic build)
                    {:keys [serve! toy]} (h/served #{:reader})
                    wire (h/lane-lift (:machine world) serve!)
                    {:keys [attach!] n :count} (h/recording-attacher toy)
                    d (h/error #(h/lane-lower lane {:attach-stream attach!} wire attach!))]
                (is (= [:yin.k/unsupported :free-env-value] [(:yin.k/status d) (:yin.k/kind d)]) (pr-str d))
                (is (= path (:yin.k/path d)))
                (is (zero? @n) "refused before any attachment"))
              (let [got (h/crossed :semantic lane build)]
                (is (pos? (:attaches got)))
                (is (= (count (h/stream-identities (:wire got))) (:attaches got)))
                (is (= (h/normalize expected) (:value got)) "admitted, and used through the collection"))))
          (testing (str ", lifted from " (name lane))
            (if procedure?
              (h/scoped
                (fn []
                  (let [m (:machine (h/on-lane lane build))
                        n (atom 0)
                        {:keys [serve!]} (h/served #{:reader})
                        d (h/error #(h/lane-lift m (fn [x] (swap! n inc) (serve! x))))]
                    (is (= lane (h/lane-of m)))
                    (is (vm/blocked? m))
                    (is (= [:yin.k/non-portable :free-env-value] [(:yin.k/status d) (:yin.k/kind d)]) (pr-str d))
                    (is (= 'box (:yin.k/key d)))
                    (is (zero? @n) "no stream was served"))))
              (is (= (h/normalize expected) (:value (h/crossed lane :semantic build)))
                  "admitted, and used through the collection"))))))))


(def dead-ambient-pcs
  #{[:resume-operand 1] [:resume-operand 2]
    [:tail-operand 1] [:tail-operand 2]})


(deftest static-layouts-at-every-pc
  (let [excluded (atom #{})]
    (doseq [[label ast] C]
      (let [v (projected ast) sg (layout/segment v) a (:analysis sg)]
        (doseq [p (sort (reachable-pcs v))]
          (testing (str label " pc " p)
            (let [t (nth v p)
                  ordered (layout/stack-layout sg p)
                  live (analysis/live a p)
                  named (cond-> live (some? (code/rd t)) (conj (code/rd t)))
                  uses (set (code/uses t))
                  region (into (set ordered) uses)]
              (is (vector? ordered))
              (is (= (count named) (count (set (map #(layout/phys sg p %) named))))
                  "physical registers are injective on live registers and the definition")
              (if (code/tail-call? t)
                (is (= uses live))
                (do
                  (is (set/subset? uses live))
                  (is (set/subset? live region))
                  (if (contains? dead-ambient-pcs [label p])
                    (do
                      (swap! excluded conj [label p])
                      (is (= #{1} (set/difference (set ordered) live))
                          "exactly the enclosing application's abandoned operator")
                      (is (= :var (first (nth v 0))))
                      (is (some #(or (= :resume (first %)) (code/tail-call? %))
                                (subvec v p))
                          "the pending application is abandoned by an operand transfer"))
                    (is (= live region))))))))))
    (is (= dead-ambient-pcs @excluded))))


(deftest executed-ordered-regions
  (doseq [ast [(corpus/app (corpus/v '+) (corpus/lit 1) (corpus/lit 2))
               (corpus/app (corpus/lam ['x] (corpus/app (corpus/v '+) (corpus/v 'x) (corpus/v 'x))) (corpus/lit 7))
               (corpus/app (corpus/v '+) (corpus/lit 10)
                           (corpus/if-node (corpus/lit true) (corpus/lit 2) (corpus/lit 3)))
               (corpus/app (corpus/v '+) (corpus/lit 10)
                           (corpus/if-node (corpus/lit false) (corpus/lit 2) (corpus/lit 3)))]]
    (h/scoped
      (fn []
        (let [sg (layout/segment (projected ast))
              semantic (h/load-program (h/on-lane :semantic h/machine) ast)
              stack (h/on-lane :stack #(h/load-program (h/machine) ast))
              sem (loop [m semantic out []]
                    (if-let [{:keys [pc]} (:control m)]
                      (recur (vm/step m) (conj out [pc (:window m)])) out))
              stk (loop [k stack out [] n 0]
                    (when (> n 1000) (throw (ex-info "Pure stack path did not halt" {})))
                    (if (vm/halted? k) out
                        (let [[_ hp] (dvm/image-pc k (:pc k))
                              pc (get (:h->a sg) hp)
                              base (or (:stack-base (peek (:continuation k))) 0)]
                          (recur (vm/step k)
                                 (cond-> out (some? pc) (conj [pc (subvec (vec (:stack k)) base)]))
                                 (inc n)))))
              repeated (atom 0)]
          (is (= (mapv first sem) (mapv first stk)))
          (doseq [[[pc w] [_ region]] (map vector sem stk)]
            (let [sequence (into (layout/stack-layout sg pc) (code/uses (nth (:vector sg) pc)))
                  expected (mapv #(get w %) sequence)]
              (is (= (count sequence) (count region)) (str "pc " pc))
              (is (= (h/normalize expected) (h/normalize region)))
              (doseq [[_ entries] (group-by first (map vector sequence region)) :when (< 1 (count entries))]
                (swap! repeated inc)
                (is (apply = (map second entries))))))
          ;; Pinned as measured: linearize gives every operand a fresh
          ;; register ((+ x x) is [:var 2 x] [:var 3 x] [:call 0 1 [2 3]]), so
          ;; no projected program repeats one; a repeat would fail here loudly.
          ;; Per-group agreement is live only for non-projected vectors
          ;; (6e's hand-built cases).
          (is (zero? @repeated)))))))
