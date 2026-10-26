(ns yang.typescript.antlr.float-address-test
  "Float kind is the producer's to carry (design 8.5.5): a number is
   float64 content at the producer, and the `js` prelude holds no integral
   float literal, so a float-bearing program and the full prelude have one
   canonical form on every host. The goldens below were computed on the
   JVM; Node and Dart run the same assertions, so these tests passing on
   every lane is the cross-host byte and address identity the ruling's
   release gate asks for. Also: kind survives projection and decoding,
   `1` and `1.0` are one literal and distinct from the bigint 1, a zero
   keeps its sign, and a decoded float-bearing program runs on all four
   VMs."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [yang.core.uast :as u]
            [yang.typescript.antlr.prelude :as prelude]
            [yang.typescript.antlr.prelude-parity-test :as parity]
            [yang.typescript.antlr.render :as render]
            [yin.vm :as vm]))


(defn- b64
  [v]
  (jing/bytes->base64 (jing/canonical-bytes v)))


(defn- neg-zero
  "-0.0, built by multiplication: ClojureDart reads one-argument `-` as
   (0 - x), which is +0.0 for x = 0.0."
  []
  (* -1.0 0.0))


(deftest canonical-bytes-test
  (testing "byte-identical on every host (JVM goldens)"
    (is (= "2BuCcGRhby5qaW5nL2Zsb2F0NjRIP/AAAAAAAAA="
           (b64 (cbor/float64 1))))
    (is (= "AQ==" (b64 1)))
    (is (= "2BuCcGRhby5qaW5nL2Zsb2F0NjRIgAAAAAAAAAA="
           (b64 (cbor/float64 (neg-zero))))))
  (testing "1 and 1.0 are one literal, distinct from the bigint 1"
    (is (= (jing/segment-key (cbor/float64 1)) (jing/segment-key (cbor/float64 1.0))))
    (is (not= (jing/segment-key 1) (jing/segment-key (cbor/float64 1)))))
  (testing "a zero keeps its sign, through decoding too"
    (is (not= (jing/segment-key (cbor/float64 0))
              (jing/segment-key (cbor/float64 (neg-zero)))))
    (is (= "-0"
           (render/inspect
             (cbor/decode (jing/canonical-bytes (cbor/float64 (neg-zero)))))))))


;; =============================================================================
;; The prelude
;; =============================================================================

(defn- float-literal-defects
  "Literal values in `ast` that break the prelude's float discipline: an
   integral float anywhere (on JS it would already be an integer, so only a
   host that tells them apart sees it), or any float outside an operand of
   `data/float-value`."
  [ast]
  (letfn [(floats-in
            [v]
            (filter cbor/float64? (tree-seq coll? seq v)))
          (integral-float?
            [x]
            #?(:cljd (and (dart/is? x double) (.-isFinite ^double x)
                          (== x (.roundToDouble ^double x)))
               :clj (and (instance? Double x) (Double/isFinite x)
                         (== x (Math/rint x)))
               ;; JS has read an integral float as an integer by now
               :cljs (and (number? x) false)))
          (walk
            [node bridged?]
            (cond
              (and (map? node) (= :literal (:type node)))
              (concat (filter integral-float? (floats-in (:value node)))
                      (when-not bridged? (floats-in (:value node))))
              (and (map? node) (= :application (:type node)))
              (let [bridge? (= {:type :variable, :name 'data/float-value}
                               (select-keys (:operator node) [:type :name]))]
                (concat (walk (:operator node) false)
                        (mapcat #(walk % bridge?) (:operands node))))
              (map? node) (mapcat #(walk % false) (vals node))
              (coll? node) (mapcat #(walk % false) node)
              :else nil))]
    (vec (walk ast false))))


(deftest prelude-float-discipline-test
  (testing "no integral float literal in the bundled prelude, and every
            float literal is a `data/float-value` operand"
    (is (= [] (float-literal-defects prelude/uast)))))


(deftest prelude-address-test
  (testing "the bundled prelude: one address on every host (JVM golden; it
            moves with every prelude change and is re-minted from source)"
    (is (= (keyword
             (str "segment/blake3-"
                  "c74a4265d4eb5579c158f4b2fee50a01"
                  "5a8d2ebd2ce00f41049b82f1c10453b2"))
           (:root (vm/ast->semantic-bytecode prelude/uast))))))


;; =============================================================================
;; A float-bearing program
;; =============================================================================

(def ^:private float-program
  "console.log(0.1 + 0.2, -0, 2, 5 % 3, 1n): the prelude, then run-main."
  (u/mark-tails
    (u/then
      prelude/uast
      (u/sexp->uast
        '(js/run-main
           (fn [this]
             (js/log!
               (js/vconj
                 (js/vconj
                   (js/vconj
                     (js/vconj
                       (js/vconj []
                                 (js/add (data/float64 0.1) (data/float64 0.2)))
                       (js/neg (data/float64 0)))
                     (data/float64 2))
                   (js/mod (data/float64 5) (data/float64 3)))
                 1))))))))


(def ^:private printed "0.30000000000000004 -0 2 2 1n")


(deftest program-address-test
  (testing "the float-bearing program: one address on every host (JVM
            golden)"
    (is (= (keyword
             (str "segment/blake3-"
                  "08f7b820fe499c1f339cfdfabc53b6ef"
                  "4ddc17b7773fc05ea28d7d1d5909a568"))
           (:root (vm/ast->semantic-bytecode float-program)))))
  (testing "decoding the program and projecting it again keeps the address"
    (let [bytecode (vm/ast->semantic-bytecode float-program)
          decoded (cbor/decode (jing/canonical-bytes bytecode))]
      (is (= (:root bytecode) (:root decoded)))
      (is (= (jing/segment-key bytecode)
             (jing/segment-key (vm/ast->semantic-bytecode
                                 (vm/semantic-bytecode->ast decoded))))))))


(defn- runs-on-every-vm
  []
  (let [ast (vm/semantic-bytecode->ast
              (cbor/decode
                (jing/canonical-bytes
                  (vm/ast->semantic-bytecode float-program))))]
    (testing "the program as built, and as decoded from its canonical bytes:
              the float64 content the decoder hands back crosses into
              arithmetic through data/float-value"
      (doseq [program [float-program ast]
              [k run] parity/runners]
        (is (= {:js/out [printed], :js/uncaught nil}
               (render/output (run program)))
            (str k))))))


(deftest runs-on-every-vm-test
  (runs-on-every-vm))
