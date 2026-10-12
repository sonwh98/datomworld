(ns yin.vm.semantic-register.layout-test
  "Phase 6c acceptance 1-9 (docs/design/yin.vm.semantic-register-vm.continuation.md
   §8.3): the register pairing and live sets, the register destination from
   A, the stack correspondence, operand layout and K slices, E
   reconstruction, closures both ways, and the unobservable fill of a
   lowered tail. The static items run over C, the B0 corpus and the
   phase-3 corpus (derive-test's wider C requires the Python ANTLR lane);
   the dynamic items run the harness's worlds on the de Bruijn lanes."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.engine :as engine]
            [yin.vm.values :as values]
            [yin.vm.semantic-register.analysis :as analysis]
            [yin.vm.semantic-register.code :as code]
            [yin.vm.semantic-register.corpus :as corpus]
            [yin.vm.semantic-register.layout :as layout]
            [yin.vm.semantic-register.linearize :as linearize]
            [yin.vm.semantic-register.safepoint-harness-test :as h]
            [yin.vm.ucf.v2-support :as s]))


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


(defn lifted-waits
  "The canonical wait entries of world machine m: the evaluator's own, or a
   de Bruijn kernel's through the layout."
  [m]
  (if (= :semantic-register (h/lane-of m))
    (:wait-set m)
    (:wait-set (layout/canonical-task m (h/kernel-space m) (h/on-lane :semantic-register h/machine)))))


(deftest register-rd-from-a
  (testing "the worked example parked at its inner call"
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
      (is (= #{1} (set (keys (:window p)))) "saved(4, 2)")))
  (testing "the K frames of :depth-3 take rd from A"
    (let [[_ wrap] (nth h/segments 2)
          build #(h/reader wrap h/read-op)
          reference (first (:wait-set (:machine (h/on-lane :semantic-register build))))
          m (:machine (h/on-lane :register build))
          entry (first (:wait-set m))
          [p] (lifted-waits m)]
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
        (let [k (:machine (h/on-lane :stack build))]
          (doseq [e (concat (:wait-set k) (vals (:parked k)))
                  :when (non-tail? e)]
            (let [frames (stack-frames k e)]
              (is (= (vec (:stack e)) (into [] (mapcat #(nth % 2)) frames)) "the slices partition the stack")
              (doseq [[sg pc slice] frames]
                (is (= (count (layout/stack-layout sg (dec pc))) (count slice))))
              (swap! checked inc))))))
    (is (< 20 @checked)))
  (testing "the multi-frame fixture"
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
            "and it continues")))))


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
        parked (if (= :semantic-register (h/lane-of m))
                 (vals (:parked m))
                 (vals (:parked (layout/canonical-task m (h/kernel-space m) (h/on-lane :semantic-register h/machine)))))]
    (h/normalize (mapv (fn [p] (conj (mapv :env (:k p)) (:env p))) (concat waits (sort-by :id parked))))))


(deftest env-reconstruction
  (let [checked (atom 0)
        cases (into (mapv (fn [[row label _ build]] [(str "row " row " " (name label)) build]) (h/lane-cases))
                    (map (fn [[label ast]] [(name label) #(h/reader identity ast)]))
                    env-programs)]
    (doseq [[label build] cases]
      (testing label
        (let [expected (envs (:machine (h/on-lane :semantic-register build)))]
          (doseq [lane [:stack :register]]
            (is (= expected (envs (:machine (h/on-lane lane build)))) (str (name lane) "'s E is the evaluator's"))
            (swap! checked inc)))))
    (is (= (* 2 (+ 61 3)) @checked)))
  (testing "row 21: the read's E binds x to 1, the free environment keeps 99"
    (let [build #(h/free-env-world identity {'x 99})]
      (doseq [lane h/lanes]
        (let [m (:machine (h/on-lane lane build))
              [p] (lifted-waits m)]
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


(deftest closures-round-trip
  (let [checked (atom 0)]
    (doseq [[label ast] closure-programs
            lane [:stack :register]]
      (testing (str (name label) " " (name lane))
        (let [sr (:machine (h/on-lane :semantic-register #(h/reader identity ast)))
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
              (swap! checked inc))))))
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
  (let [world (h/on-lane :semantic-register build)
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
;; The fixtures' shapes are this test's own: the harness's code-index is
;; file-wide, and an alpha-equivalent A of another test's vector would
;; widen that test's unscoped kernel-space.

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
  "Two alpha-equivalent closures in the store, then (b (a \"B\")) after a read."
  []
  (let [m1 (vm/run (h/load-program (h/machine) (alpha-closure 'x)))
        m2 (vm/run (h/load-program m1 (alpha-closure 'y)))
        m3 (assoc m2 :store {'a (vm/value m1) 'b (vm/value m2)})
        m (vm/run (h/load-program m3 (h/with-stream (s/then h/read-op (s/app (s/v 'b) (s/app (s/v 'a) (s/lit "B")))))))
        src (s/stream-of m)]
    {:machine m :kind :blocked :reason :next :steps [#(stream/append! src "B")] :trace (atom [])}))


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
                template (h/on-lane :semantic-register h/machine)
                d (h/error #(layout/lift-task m (layout/space [ax ay]) template counting (h/lift-opts)))]
            (is (vm/blocked? m))
            (is (contains? (set (map first (:images m))) (if (= :stack lane) (:stack-id ax) (:register-id ax)))
                "the kernel holds the one image")
            (is (= [:yin.k/non-portable :ambiguous-image] [(:yin.k/status d) (:yin.k/kind d)]) (pr-str d))
            (is (zero? @n) "no stream was served")
            (testing ", the file-wide code-index widens the space; the world's own does not"
              (is (= :ambiguous-image (:yin.k/kind (h/error #(h/lane-lift m counting)))))
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
            (is (zero? @n) "refused before any attachment"))))))
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
             (= :semantic-register (h/lane-of m0)) (update :env assoc 'f f))
        m (vm/run (h/load-program m1 (h/with-stream (s/then h/read-op (s/app (s/v 'f) (s/lit "B"))))))
        src (s/stream-of m)]
    {:machine m :kind :blocked :reason :next :steps [#(stream/append! src "B")] :trace (atom [])}))


(deftest free-env-values
  (testing "the reference lane and the carrier admit it"
    (let [world (h/on-lane :semantic-register free-closure-world)]
      (is (values/closure? (get-in world [:machine :free-env 'f])))
      (is (= "B" (:value (h/reference (h/on-lane :semantic-register free-closure-world)))))
      (is (= "B" (:value (h/handed-off world))))))
  (testing "a de Bruijn receiver refuses it before any attach"
    (doseq [lane [:stack :register]]
      (testing (name lane)
        (let [world (h/on-lane :semantic-register free-closure-world)
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
          (is (zero? @n) "no stream was served"))))))
