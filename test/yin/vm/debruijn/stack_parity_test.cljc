(ns yin.vm.debruijn.stack-parity-test
  "B5 (docs/design/yin.vm.debruijn.stack.md, 'B5: differential
   integration'): the two executable pipelines compared end to end.

     A: ast->datoms-with-root -> adapt (B2) -> yin.vm.debruijn.stack
     B: ast->datoms-with-root -> linearize/lower -> yin.vm.semantic

   Execution fixtures are the actual corpora, reused rather than copied:
   `yin.vm.parity-test/corpus` and `yin.vm-test/semantic-bytecode-corpus`
   (the every-tag corpus content_test and completion_test reuse). Every
   run is on a fresh VM (D4), and outcomes compare under B0's normalizer
   (`yin.vm.debruijn-vm-contract-test/normalize`).

   H laws: binder renames share H; exact scalar spelling, front-end tail
   flags, and free names split it. Golden bytes and H below are committed
   literals asserted on every host lane, which is B5's cross-runtime
   identity check; the real cross-process transfer is B6's. The stream
   transport test sends images through an in-memory `dao.stream` ring
   buffer as EDN text and has the receiver verify H, validate, and run.

   Test-only: no source namespace, named storage, or linearizer changes."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.debruijn-code :as dc]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-vm-contract-test :as b0]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.linearize :as linearize]
            [yin.vm.parity-test :as parity]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]
            [yin.vm-test :as vm-test]))


;; =============================================================================
;; AST constructors
;; =============================================================================

(defn- lit
  [x]
  {:type :literal, :value x})


(defn- v
  [s]
  {:type :variable, :name s})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [op & args]
  {:type :application, :operator op, :operands (vec args)})


(defn- tail
  [node]
  (assoc node :tail? true))


(defn- code-units
  "A string built from UTF-16 code units, keeping this source pure ASCII
   while still spelling non-ASCII scalars exactly."
  [& units]
  (apply str
         (map (fn [u]
                #?(:cljd (String/fromCharCode u)
                   :clj (str (char u))
                   :cljs (.fromCharCode js/String u)))
              units)))


;; =============================================================================
;; The two pipelines
;; =============================================================================

(defn- named-datoms
  [ast]
  (second (vm/ast->datoms-with-root ast)))


(defn- adapted
  [ast]
  (dl/adapt (named-datoms ast)))


(defn- image-of
  [ast]
  (:image (adapted ast)))


(defn- h-of
  [ast]
  (dc/image-hash (image-of ast)))


(defn- outcome
  "The normalized outcome of `thunk`'s VM: value and blocked flag, or the
   B0-normalized error when running throws."
  [thunk]
  (try (let [vm (thunk)]
         {:value (b0/normalize (vm/value vm)), :blocked? (vm/blocked? vm)})
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         {:error (b0/normalize-error e)})))


(def ^:private load-ast (linearize/ast-loader semantic/vm-load-program))


(defn- named-outcome
  "Pipeline B: `linearize/lower` onto a fresh semantic VM."
  [ast]
  (outcome #(vm/run (load-ast (semantic/create-vm
                                {:make-stream tu/make-stream})
                              (named-datoms ast)))))


(defn- image-outcome
  "Run a de Bruijn `image` on a fresh stack VM with the standard
   primitive registry, as the semantic VM's own default."
  [image]
  (outcome #(vm/run (dvm/create-vm image
                                   {:make-stream tu/make-stream,
                                    :primitives vm/primitives}))))


(defn- debruijn-outcome
  "Pipeline A: B2's `adapt` onto a fresh de Bruijn stack VM."
  [ast]
  (image-outcome (image-of ast)))


;; =============================================================================
;; 1. Differential execution parity over the actual corpora
;; =============================================================================

(def ^:private execution-corpus
  "[label ast] for the v2 parity corpus plus the every-tag corpus."
  (concat (map (fn [[label ast _]] [label ast]) parity/corpus)
          (map (fn [ast] [(pr-str ast) ast])
               vm-test/semantic-bytecode-corpus)))


(deftest every-corpus-image-is-valid
  (doseq [[label ast] execution-corpus]
    (testing label
      (is (nil? (dc/image-defect (image-of ast)))
          "B1's validator admits every image B2 emits"))))


(deftest differential-execution-parity
  (doseq [[label ast] execution-corpus]
    (testing label
      (is (= (named-outcome ast) (debruijn-outcome ast))
          "adapt + stack VM agrees with lower + semantic VM"))))


(deftest parity-corpus-pinned-values
  (testing "the de Bruijn pipeline reproduces the parity corpus's pinned
            values under the normalizer, not merely the semantic VM's"
    (doseq [[label ast expected] parity/corpus]
      (testing label
        (is (= {:value (b0/normalize expected), :blocked? false}
               (debruijn-outcome ast)))))))


(deftest outcome-classes-are-exercised
  (testing "the corpus reaches values, blocked states, and errors, so
            parity is not vacuous on any of the three outcome classes"
    (let [outcomes (map (comp debruijn-outcome second) execution-corpus)]
      (is (some :error outcomes))
      (is (some :blocked? outcomes))
      (is (some #(and (contains? % :value) (not (:blocked? %)))
                outcomes)))))


;; =============================================================================
;; 2. Alpha-equivalence: binder renames share the image and H
;; =============================================================================

(def ^:private alpha-classes
  "Each entry is a set of programs differing only in binder names."
  {:identity [(lam '[x] (v 'x)) (lam '[y] (v 'y))],
   :increment [(lam '[x] (tail (app (v '+) (v 'x) (lit 1))))
               (lam '[y] (tail (app (v '+) (v 'y) (lit 1))))],
   :applied [(app (lam '[x] (tail (app (v '+) (v 'x) (lit 1)))) (lit 10))
             (app (lam '[n] (tail (app (v '+) (v 'n) (lit 1)))) (lit 10))],
   :two-deep [(lam '[a] (lam '[b] (app (v '-) (v 'a) (v 'b))))
              (lam '[p] (lam '[q] (app (v '-) (v 'p) (v 'q))))],
   :three-deep [(lam '[a] (lam '[b] (lam '[c] (app (v 'vector)
                                                   (v 'a)
                                                   (v 'b)
                                                   (v 'c)))))
                (lam '[x] (lam '[y] (lam '[z] (app (v 'vector)
                                                   (v 'x)
                                                   (v 'y)
                                                   (v 'z)))))],
   :shadowing [(lam '[x] (lam '[x] (v 'x)))
               (lam '[a] (lam '[b] (v 'b)))],
   :swapped [(lam '[f x] (app (v 'f) (v 'x)))
             (lam '[x f] (app (v 'x) (v 'f)))],
   :duplicate [(lam '[x x] (v 'x))
               (lam '[z z] (v 'z))
               (lam '[x y] (v 'y))],
   :duplicate-nested [(lam '[a a] (lam '[b] (app (v '+) (v 'a) (v 'b))))
                      (lam '[m n] (lam '[k] (app (v '+) (v 'n) (v 'k))))]})


(deftest binder-renames-share-image-and-h
  (doseq [[label programs] alpha-classes]
    (testing label
      (let [adapts (map adapted programs)
            images (map :image adapts)]
        (is (apply = images) "the canonical instruction vector is identical")
        (is (apply = (map dc/encode-image images)) "the hashed bytes too")
        (is (apply = (map dc/image-hash images)) "so H is identical")
        (is (not (apply = programs)) "the source programs do differ")))))


(deftest binder-names-live-only-in-the-side-table
  (testing "the renamed binders are recorded, outside H, in the side table"
    (let [[a b] (:identity alpha-classes)
          params (fn [ast]
                   (keep :params (vals (:side-table (adapted ast)))))]
      (is (= '[[x]] (params a)))
      (is (= '[[y]] (params b))))))


(deftest alpha-equivalent-programs-run-identically
  (testing "sharing H means the shared image runs to one result"
    (doseq [[label programs] {:applied (:applied alpha-classes),
                              :three-deep
                              (map #(app (app (app % (lit 1)) (lit 2))
                                         (lit 3))
                                   (:three-deep alpha-classes)),
                              :duplicate
                              (map #(app % (lit 1) (lit 2))
                                   (:duplicate alpha-classes))}]
      (testing label
        (let [outcomes (map debruijn-outcome programs)]
          (is (apply = outcomes))
          (is (= (first outcomes) (named-outcome (first programs)))))))))


;; =============================================================================
;; 3. Non-alpha distinctions split H
;; =============================================================================

(defn- distinct-h?
  [a b]
  (not= (h-of a) (h-of b)))


(deftest exact-scalar-spelling-splits-h
  (testing "composed vs decomposed e-acute: no NFC folding (all hosts)"
    (let [composed-e (code-units 0xe9)
          decomposed-e (code-units 0x65 0x301)
          composed (lam '[x] (app (v 'str) (v 'x) (lit composed-e)))
          decomposed (lam '[x] (app (v 'str) (v 'x) (lit decomposed-e)))]
      (is (not= composed-e decomposed-e) "the spellings differ as data")
      (is (distinct-h? composed decomposed))))
  (testing "a keyword and a string of the same spelling"
    (is (distinct-h? (lit :a) (lit "a"))))
  (testing "1 vs 1.0 under contract v1 numeric preservation (JVM and Dart;
            a JS number has no integral-double provenance, S2)"
    #?(:cljs (is true "not representable on CLJS")
       :default (is (distinct-h? (lam '[x] (app (v '+) (v 'x) (lit 1)))
                                 (lam '[x] (app (v '+) (v 'x) (lit 1.0))))))))


(deftest front-end-tail-flags-split-h
  (let [tailed (app (lam '[x] (tail (app (v '+) (v 'x) (lit 1)))) (lit 10))
        untailed (app (lam '[x] (app (v '+) (v 'x) (lit 1))) (lit 10))]
    (testing "the flag is copied, not inferred"
      (is (some #(= [:call 2 true] %) (image-of tailed)))
      (is (some #(= [:call 2 false] %) (image-of untailed))))
    (testing "so otherwise identical programs hash apart"
      (is (distinct-h? tailed untailed)))
    (testing "yet run to the same value"
      (is (= (debruijn-outcome tailed) (debruijn-outcome untailed))))))


(deftest free-names-split-h
  (testing "two different free operator names"
    (is (distinct-h? (lam '[x] (app (v 'foo) (v 'x)))
                     (lam '[x] (app (v 'bar) (v 'x))))))
  (testing "a bound reference vs the same spelling left free"
    (is (distinct-h? (lam '[x] (v 'x)) (lam '[y] (v 'x)))))
  (testing "a rename that captures a free name is not alpha-equivalent"
    (is (distinct-h? (lam '[x] (app (v '+) (v 'x) (v 'y)))
                     (lam '[y] (app (v '+) (v 'y) (v 'y))))))
  (testing "duplicate parameters: rightmost wins, so the leftmost differs"
    (is (distinct-h? (lam '[x x] (v 'x)) (lam '[x y] (v 'x))))))


;; =============================================================================
;; 4. Golden bytes and H, asserted identically on every host lane
;; =============================================================================

(def ^:private golden
  "label -> [ast image encoded-hex H], computed once on the JVM and
   committed; common scalar domain only (longs, symbols), so every lane
   must reproduce every column byte for byte."
  {:identity
   [(lam '[x] (v 'x))
    '[[:closure 1 2] [:halt] [:load-bound 0 0] [:return]]
    (str "0201000000000000000200000000000000070900000000000000000000"
         "0000000000000e")
    "fd6b85020a903d79e580830722593a8b8fd1952c9ea3057fcf86ed7f3eb4db4e"],
   :applied
   [(app (lam '[x] (tail (app (v '+) (v 'x) (lit 1)))) (lit 10))
    '[[:closure 1 6] [:push] [:const 10] [:push] [:call 1 false] [:halt]
      [:load-free +] [:push] [:load-bound 0 0] [:push] [:const 1] [:push]
      [:call 2 true] [:return]]
    (str "02010000000000000006000000000000000c0302000000100a000000000000"
         "000c010100000000000000010000000200070a09000000150000000000070000"
         "0001+0c09000000000000000000000000000000000c03020000001001000000"
         "000000000c0102000000000000000100000002010e")
    "784ec567b17d91a9bde4f23bd180d09bebf69e8da0e57503659c95f1ef2a7b50"],
   :two-deep
   [(lam '[a] (lam '[b] (app (v '-) (v 'a) (v 'b))))
    '[[:closure 1 2] [:halt] [:closure 1 4] [:return] [:load-free -]
      [:push] [:load-bound 1 0] [:push] [:load-bound 0 0] [:push]
      [:call 2 false] [:return]]
    (str "02010000000000000002000000000000000702010000000000000004000000"
         "000000000e0a090000001500000000000700000001-0c090100000000000000"
         "00000000000000000c09000000000000000000000000000000000c01020000"
         "00000000000100000002000e")
    "63de575e609bac6e81a17eb190768d825c28301d60e8627b7587aa78db0f3125"],
   :duplicate
   [(lam '[x x] (v 'x))
    '[[:closure 2 2] [:halt] [:load-bound 0 1] [:return]]
    (str "0202000000000000000200000000000000070900000000000000000100000000"
         "0000000e")
    "83bda4ee2b491febab564c3ccb8ea87a1329b28b962d5e64eba5f188b7676720"]})


(deftest golden-images-bytes-and-h
  (doseq [[label [ast image hex h]] golden]
    (testing label
      (is (= image (image-of ast)) "adapt reproduces the committed image")
      (is (= hex (dc/encode-image image)) "the committed wire bytes")
      (is (= h (dc/image-hash image)) "the committed H"))))


(deftest golden-h-is-shared-by-renamed-binders
  (testing "every lane gives a binder-renamed program the committed H"
    (let [[_ _ _ identity-h] (:identity golden)
          [_ _ _ applied-h] (:applied golden)
          [_ _ _ two-deep-h] (:two-deep golden)
          [_ _ _ duplicate-h] (:duplicate golden)]
      (is (= identity-h (h-of (lam '[q] (v 'q)))))
      (is (= applied-h
             (h-of (app (lam '[w] (tail (app (v '+) (v 'w) (lit 1))))
                        (lit 10)))))
      (is (= two-deep-h
             (h-of (lam '[u] (lam '[w] (app (v '-) (v 'u) (v 'w)))))))
      (is (= duplicate-h
             (h-of (lam '[p q] (v 'q)))
             (h-of (lam '[r r] (v 'r))))))))


;; =============================================================================
;; 5. Transport over dao.stream: send, receive, verify, validate, run
;; =============================================================================

(defn- send-image!
  "Sender side: append one image to `handle` as EDN wire text with the
   sender's H claim. The payload is data only; no closure crosses."
  [handle image]
  (stream/append! handle
                  (pr-str {:yin.debruijn.code/hash (dc/image-hash image),
                           :yin.debruijn.code/image image})))


(defn- receive-images
  "Receiver side: read every message from `handle` in order, oldest
   first, until the stream reports anything but ok."
  [handle]
  (loop [cursor (:dao.stream/cursor
                  (stream/cursor handle :dao.stream/oldest))
         acc []]
    (let [r (stream/next handle cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r)
               (conj acc (edn/read-string (:dao.stream/value r))))
        acc))))


(defn- admit
  "Receiver verification before anything runs: H recomputed over the
   received image must equal the claim, then B1's validator and the host
   scalar-class check must pass. Returns the image or a refusal."
  [{claimed :yin.debruijn.code/hash, image :yin.debruijn.code/image}]
  (let [h (dc/image-hash image)]
    (cond (not= claimed h) {:refused :hash-mismatch, :claimed claimed, :h h}
          (dc/image-defect image) {:refused :invalid-image}
          (dc/validate-host-support image) {:refused :unsupported-value}
          :else {:image image, :h h})))


(def ^:private transport-corpus
  "Programs sent over the stream: the full execution corpus plus the
   golden fixtures applied to arguments."
  (concat execution-corpus
          [["golden identity applied" (app (first (:identity golden))
                                           (lit :x))]
           ["golden two-deep applied" (app (app (first (:two-deep golden))
                                                (lit 10))
                                           (lit 3))]
           ["golden duplicate applied" (app (first (:duplicate golden))
                                            (lit 1)
                                            (lit 2))]]))


(deftest images-cross-dao-stream-and-run-like-local
  (let [programs (vec transport-corpus)
        handle (tu/new-stream (* 2 (count programs)))
        images (mapv (comp image-of second) programs)]
    (doseq [image images] (send-image! handle image))
    (let [received (receive-images handle)]
      (is (= (count programs) (count received)) "every message arrives")
      (doseq [[[label _] image msg] (map vector programs images received)]
        (testing label
          (let [{admitted :image, :as verdict} (admit msg)]
            (is (nil? (:refused verdict)) "admitted by the receiver")
            (is (= image admitted) "the received image is the sent image")
            (is (= (dc/image-hash image) (:h verdict)) "H survives transit")
            (is (= (image-outcome image) (image-outcome admitted))
                "remote execution matches local execution")))))))


(deftest golden-bytes-survive-the-stream
  (let [handle (tu/new-stream 8)]
    (doseq [[_ [_ image]] golden] (send-image! handle image))
    (doseq [[[label [_ _ hex h]] msg] (map vector golden
                                           (receive-images handle))]
      (testing label
        (let [{:keys [image] :as verdict} (admit msg)]
          (is (= h (:h verdict)) "the receiver recomputes the golden H")
          (is (= hex (dc/encode-image image))
              "and re-encodes the golden bytes"))))))


(deftest a-tampered-image-is-refused-before-running
  (let [handle (tu/new-stream 4)
        [_ image _ h] (:applied golden)
        tampered (assoc image 10 [:const 2])]
    (stream/append! handle
                    (pr-str {:yin.debruijn.code/hash h,
                             :yin.debruijn.code/image tampered}))
    (let [[msg] (receive-images handle)]
      (is (= :hash-mismatch (:refused (admit msg)))
          "a same-shape image with a changed constant fails H"))))


(deftest an-invalid-image-is-refused-even-with-a-matching-claim
  (let [handle (tu/new-stream 4)
        bad '[[:load-bound 5 0] [:halt]]]
    (send-image! handle bad)
    (let [[msg] (receive-images handle)]
      (is (= :invalid-image (:refused (admit msg)))
          "an honest H over an out-of-scope image still fails B1"))))
