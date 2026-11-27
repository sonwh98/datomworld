(ns yang.clojure-float-kind-test
  "Float-kind portability r1 (architect ruling 1795693415000, RULING 5):
   the seven acceptance gates of Clojure floating-literal kind preservation
   from source to Data/UAST - real-source kind through the frontend source
   entry and the standard REPL/catalog route, structural and lexical
   preservation, the runtime semantics oracle independent of digests, the
   API/build carrier boundary, cross-host artifact and legacy custody, and
   the profile/revision claims.

   The reader under test is the frontend's own source entry
   (yang.clojure/read-forms), the same one the standard catalog installs,
   so a green gate here is a claim about the shipped frontend, not about a
   test-private reader. The REPL-route gates run where the shell runs
   (JVM and Node); the artifact gates run on every host that can load this
   namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.host.bytes :as bytes]
            [dao.host.integer :as integer-host]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.stream :as stream]
            [dao.stream.base64 :as base64]
            [yang.clojure :as yang]
            [yang.clojure.numeric :as clj-numeric]
            [yang.clojure.prelude]
            [yang.frontend :as frontend]
            [yin.repl :as repl]
            [yin.repl.frontends :as frontends]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.data :as data]
            [yin.vm.module :as module]
            [yin.vm.test-utils :as tu]
            #?@(:jolt []
                :cljd [["dart:io" :as dart-io]]
                :clj [[clojure.java.io :as io]
                      [clojure.tools.reader :as tools-reader]
                      [clojure.tools.reader.reader-types :as tools-rt]
                      [clojure.walk :as walk]
                      [dao.stream.ringbuffer :as ringbuffer]
                      [yang.clojure.core-impl]
                      [yang.python.antlr.clj-runtime]
                      [yin.vm.semantic :as sr]
                      [yin.vm.semantic.code :as semantic-code]
                      [yin.vm.semantic.linearize :as semantic-linearize]
                      [yin.vm.ucf.rows :as ucf-rows]]
                :cljs [[cljs.tools.reader :as tools-reader]
                       [cljs.tools.reader.reader-types :as tools-rt]
                       [dao.stream.ringbuffer :as ringbuffer]
                       [yin.vm.semantic :as sr]
                       [yin.vm.semantic.code :as semantic-code]
                       [yin.vm.semantic.linearize :as semantic-linearize]
                       [yin.vm.ucf.rows :as ucf-rows]])))


;; =============================================================================
;; Shared helpers
;; =============================================================================

(def ^:private data-equiv
  "The exported kind-strict Data equivalence predicate."
  (get data/data-module 'equiv))


(declare run-library)


(defn- read-one
  "The single form of `source` through the frontend source entry."
  [source]
  (first (yang/read-forms source)))


(defn- compiled-value
  "The literal :value of the compiled single form of `source`."
  [source]
  (:value (yang/compile (read-one source))))


(defn- run-ast
  "Run compiled `ast` on the ast-walker with the plain registry."
  [ast]
  (-> (tu/create-vm {:primitives vm/primitives
                     :make-stream tu/make-stream
                     :capability-secret tu/secret})
      (vm/eval ast)
      vm/value))


(defn- semantic-root
  "The canonical semantic-bytecode root of the compiled form of `source`."
  [source]
  (:root (vm/ast->semantic-bytecode (yang/compile (read-one source)))))


(defn- host-read-file
  [path]
  #?(:jolt nil
     :cljd (.readAsBytesSync (dart-io/File. path))
     :clj (java.nio.file.Files/readAllBytes (.toPath (io/file path)))
     :cljs (.readFileSync (js/require "fs") path)
     :default nil))


(def ^:private repl-route?
  "The REPL/catalog source-reading route runs where the source reader ships
   (JVM and Node); the Dart and Jolt legs exercise carrier compilation,
   cross-host artifact loading/execution, legacy custody, and capability claims."
  #?(:jolt false :cljd false :clj true :cljs true :default false))


(defn- drain-result
  "The :repl/result value of a shell's output medium, oldest first."
  [state]
  (let [output (:output-stream state)
        cursor (:dao.stream/cursor (stream/cursor output :dao.stream/oldest))]
    (loop [cursor cursor, value ::none]
      (let [step (stream/next output cursor)]
        (if (= :dao.stream/ok (:dao.stream/outcome step))
          (let [v (:dao.stream/value step)]
            (if (= :repl/result (:type v))
              (recur (:dao.stream/cursor step) (:value v))
              (recur (:dao.stream/cursor step) value)))
          value)))))


(defn- repl-session-result
  "The result value of evaluating `line` through the standard REPL/catalog
  route (the frontends the shell installs), semantic VM."
  [line]
  #?(:jolt nil
     :cljd nil
     :clj (drain-result (first (repl/eval-input (frontends/create-state {:vm-type :semantic}) line)))
     :cljs (drain-result (first (repl/eval-input (frontends/create-state {:vm-type :semantic}) line)))
     :default nil))


(defn- repl-text
  "The shell text of evaluating `line` through the standard route."
  [line]
  #?(:jolt nil
     :cljd nil
     :clj (second (repl/eval-input (frontends/create-state {:vm-type :semantic}) line))
     :cljs (second (repl/eval-input (frontends/create-state {:vm-type :semantic}) line))
     :default nil))


;; =============================================================================
;; GATE 1 - real source strings, every supported numeric category
;; =============================================================================


(def ^:private float-tokens
  "Every binary-floating token class the ruling names: integral floats at
   0, 1, -1, at and beyond 2^53, exponent spellings, negative zero, a
   small positive token that rounds to zero (underflow), and a value at
   the top of the range. [token expected-double-bits-hex]."
  [["0.0" "0000000000000000"]
   ["1.0" "3ff0000000000000"]
   ["-1.0" "bff0000000000000"]
   ["1e0" "3ff0000000000000"]
   ["1.5" "3ff8000000000000"]
   ["-0.0" "8000000000000000"]
   ["9007199254740992.0" "4340000000000000"]
   ["9007199254740994.0" "4340000000000001"]
   ["1e19" "43e158e460913d00"]
   ["1.5e-5" "3eef75104d551d69"]
   ["5e-324" "0000000000000001"]
   ["1e-400" "0000000000000000"]
   ["2." "4000000000000000"]
   ["+1.5" "3ff8000000000000"]
   ["1E5" "40f86a0000000000"]])


(def ^:private integer-tokens
  "Integer controls: plain, signed, radix, octal, and the N-suffixed big
   integer (the exact carrier). [token expected-value]."
  [["0" 0] ["1" 1] ["-1" -1] ["+7" 7] ["42" 42]
   ["0x10" 16] ["017" 15] ["2r101" 5] ["36rZZ" 1295]
   ["9007199254740991" 9007199254740991]])


(defn- host-bits-of
  "The IEEE-754 bits of float64 content `x`, 16 lowercase hex digits."
  [x]
  #?(:jolt nil
     :cljd nil
     :clj (String/format "%016x" (into-array Object [(Double/doubleToLongBits (double (data/float-value x)))]))
     :cljs (let [v (data/float-value x)
                 buf (js/ArrayBuffer. 8)
                 view (js/DataView. buf)]
             (.setFloat64 view 0 v false)
             (let [out (atom "")]
               (dotimes [i 8]
                 (let [b (.getUint8 view i)]
                   (reset! out (str @out (when (< b 16) "0") (.toString b 16)))))
               @out))
     :default nil))


(deftest gate1-float-tokens-are-float64-content
  (when repl-route?
    (testing "every binary-floating token reads and compiles as float64 content"
      (doseq [[token bits] float-tokens]
        (testing token
          (let [value (compiled-value token)]
            (is (cbor/float64? value)
                (str token " must be float64 content, read " (pr-str value)))
            (is (= bits (host-bits-of value))
                (str token " must carry exactly these IEEE bits"))))))))


(deftest gate1-integer-tokens-stay-exact-integers
  (when repl-route?
    (testing "integer controls keep integer kind and value"
      (doseq [[token expected] integer-tokens]
        (testing token
          (let [value (compiled-value token)]
            (is (integer-host/exact-integer? value)
                (str token " must be exact-integer content, read " (pr-str value)))
            (is (data-equiv expected value)
                (str token " must read as " expected))))))
    (testing "big integers keep the exact big carrier"
      (let [value (compiled-value "9223372036854775808000N")
            expected (integer-host/demote (integer-host/parse-decimal "9223372036854775808000"))]
        (is (integer-host/big-carrier? value)
            "an N-suffixed token is exact big-integer content, never a rounded number")
        (is (data-equiv expected value))
        (is (not (cbor/float64? value)))))))


(deftest gate1-unsupported-categories-not-relabeled
  (when repl-route?
    (testing "ratio tokens stay ratio content"
      (let [value (compiled-value "1/2")]
        (is (cbor/ratio? value)
            (str "1/2 must stay ratio content, read " (pr-str value)))
        (is (not (cbor/float64? value)))))
    (testing "decimal M tokens stay decimal content"
      (doseq [token ["1M" "10.03M" "-2.5M"]]
        (let [value (compiled-value token)]
          (is (cbor/decimal? value)
              (str token " must stay decimal content, read " (pr-str value)))
          (is (not (cbor/float64? value))))))
    (testing "invalid numeric tokens are refused, not guessed"
      (doseq [token ["1.2.3" "08" "0x" "1e" "2r"]]
        (is (thrown? #?(:clj Exception :cljs js/Error :default Exception)
              (yang/read-forms token))
            (str token " must be a reader refusal"))))))


(def ^:private token-digest-reference
  "The recorded cross-host reference: the content hash of the canonical
   bytes of each token's read form, verified bit-identical on JVM and Node
   under the repaired frontend-owned reader (c1-3)."
  {"0.0" "4f3fd1e3c562a4c8690929fb1fd3b8e607d7d604bffdd4d5a406c737b735e578"
   "1.0" "1962a94c09507f12a3de739a3f584ce18ffbca152f16ebfb82ea15f3a650e90b"
   "1" "48fc721fbbc172e0925fa27af1671de225ba927134802998b10a1568a188652b"
   "1/2" "598aa35607a7350acc43a2ea659e1c94e8a8738705131d7670170d9a6a8b0b83"
   "1M" "28d6a5ec777e7e798d4be8e68242b049bc8794be9c3f6577f0af4451b0e0d05f"})


(deftest gate1-cross-host-canonical-identity
  (when repl-route?
    (testing "identical source mints identical canonical bytes on every host"
      (doseq [[token expected] token-digest-reference]
        (testing token
          (is (not= "unrecorded" expected) "reference recorded during the green round")
          (is (= expected (jing/content-hash (read-one token)))))))
    (testing "the float64 and integer tokens of one value mint distinct bytes"
      (is (not= (semantic-root "1") (semantic-root "1.0")))
      (is (not= (semantic-root "0") (semantic-root "-0.0"))))))


;; =============================================================================
;; GATE 2 - structural and lexical preservation
;; =============================================================================


(deftest gate2-floats-preserve-through-structure
  (when repl-route?
    (testing "every structural position keeps the token's kind"
      (let [let-form (read-one "(let [x 1.0] x)")
            positions {"top level" (read-one "1.0")
                       "let binding" (nth (nth let-form 1) 1)
                       "vector element" (nth (read-one "[1.0 2]") 0)
                       "quoted data" (nth (nth (read-one "(quote [1.0 1])") 1) 0)
                       "nested quoted" (nth (get (nth (read-one "'{:a [1.0]}") 1) :a) 0)
                       "map value" (nth (vals (read-one "{:a 1.0}")) 0)
                       "set element" (first (read-one "#{1.0}"))
                       "call argument" (nth (read-one "(f 1.0)") 1)}]
        (doseq [[label form] positions]
          (testing label
            (is (cbor/float64? form) (str label " keeps float64 kind"))))))
    (testing "compiled literal payloads keep the carrier"
      (is (cbor/float64? (first (compiled-value "(quote [1.0 1])"))))
      (is (cbor/float64? (first (compiled-value "[1.0]"))))
      (is (integer-host/exact-integer?
            (second (:value (yang/compile (read-one "[1.0 2]")))))))
    (testing "repeated equal-valued tokens keep one occurrence each"
      (let [ast (yang/compile (read-one "(quote [0.0 0.0 0.0])"))]
        (is (= 3 (count (:value ast)))))
      (let [ast (yang/compile (read-one "(f 1.0 1.0 1.0)"))]
        (is (= 3 (count (:operands ast))))
        (is (every? cbor/float64? (map :value (:operands ast))))))))


(deftest gate2-lexical-contexts-are-not-floats
  (when repl-route?
    (testing "numeric-looking text in non-numeric lexical contexts stays itself"
      (is (= "1.0" (read-one (str (char 34) "1.0" (char 34)))) "string content")
      (is (= 42 (read-one ";; 1.0 is a float in a comment\n42")) "comment skipped")
      (is (= 7 (read-one "#_1.0 7")) "discarded form skipped")
      (is (= 8 (read-one "#_[1.0 #_{2.0 3.0} 4.0] 8")) "nested discard skipped")
      (let [forms (yang/read-forms "1.0 #_ (+ 1.0 2.0) 42")]
        (is (= 2 (count forms)) "discard of a whole expression")
        (is (cbor/float64? (first forms)))
        (is (= 42 (second forms)))))
    (testing "metadata reads and attaches"
      (is (= {:m true} (meta (read-one "^:m [1.0]"))))
      (is (= {:a 1} (meta (read-one "^{:a 1} x")))))
    (testing "reader conditionals keep the explicit refusal"
      (is (thrown? #?(:clj Exception :cljs js/Error :default Exception)
            (yang/read-forms "#?(:clj 1 :cljs 2)")))
      (is (thrown? #?(:clj Exception :cljs js/Error :default Exception)
            (yang/read-forms "#?@(:clj [1])"))))))


(deftest gate2-equal-valued-occurrences-not-coalesced
  (when repl-route?
    (testing "a host map cannot discard the 1.0 occurrence of a 1 key"
      (let [m (read-one "{1 :a 1.0 :b}")]
        (is (= 2 (count m)) "both the integer and float key survive reading")
        (is (= :a (get m 1)))
        (is (= :b (get m (compiled-value "1.0"))))))
    (testing "a host set keeps both members"
      (let [s (read-one "#{1 1.0}")]
        (is (= 2 (count s)))))
    (testing "compiled constants keep both occurrences"
      (let [ast (yang/compile (read-one "{1 :a 1.0 :b}"))]
        (is (= 2 (count (:value ast))))
        (is (= 1 (count (filter cbor/float64? (keys (:value ast))))))))))


(deftest gate2-float-key-policy-parity
  (when repl-route?
    (testing "constant float data is quoted data; library float keys refuse alike"
      (is (cbor/float64? (first (keys (compiled-value "{1.0 :a}"))))
          "a constant map literal with a float key is quoted data")
      (let [[status err] (run-library (yang/read-forms "(assoc {} 1.0 :a)"))
            [nstatus nerr] (run-library (yang/read-forms "(assoc {} [1.0] :a)"))
            [ok-status ok-val] (run-library (yang/read-forms "(assoc {} 1 :a)"))]
        (is (= :yin.k/error status)
            "a dynamic float key hits the collection-key refusal on this host")
        (is (= :coll/unsupported-key (:clj/policy err)))
        (is (= :yin.k/error nstatus)
            "a nested float key hits the collection-key refusal on this host")
        (is (= :coll/unsupported-key (:clj/policy nerr)))
        (is (= :yin.k/ok ok-status)
            "an integer key is admitted")
        (is (= {1 :a} ok-val))))))


(deftest gate2-macro-generated-numeric-data
  (when repl-route?
    (testing "a macro returning a source float keeps its kind through expansion"
      (let [s0 (frontends/create-state {:vm-type :semantic})
            [s1 _] (repl/eval-input s0 "(defmacro fm [] (yin/literal 1.0))")
            [s2 _] (repl/eval-input s1 "(fm)")
            res (drain-result s2)]
        (is (cbor/float64? res) (str "macro result must be float64, got " (pr-str res)))
        (is (= 1.0 (data/float-value res)))))))


;; =============================================================================
;; GATE 4 - the runtime semantics oracle, independent of digests
;; =============================================================================


(def library-registry
  "The guest-library registry: the data/float/integer host modules and the
   kind surface - the profile the clojure library runs on."
  (-> (module/default-registry)
      module/register-cell-module
      (data/register-data-module {::data/max-items 1048576})
      (yang.clojure.prelude/admit)))


(defn- run-library
  "Run `forms` under the bundled library profile (the outcome lane over the
   full profile registry), answering the outcome's value."
  [forms]
  (let [ast (yang/compile-program
              forms true
              {:convention :arg-vector
               :outcomes true
               :native (set (keys vm/primitives))})]
    (-> (tu/make-observer-session
          (tu/create-vm {:modules library-registry
                         :make-stream tu/make-stream
                         :capability-secret tu/secret}))
        (tu/queue-ast! ast)
        tu/run-session
        :consumer
        vm/value)))


(deftest gate4-data-oracle
  (testing "exported Data operations are kind-strict"
    (let [dataf (fn [op & args] (apply (get data/data-module op) args))]
      (is (false? (dataf 'equiv (data/float64 1) 1)))
      (is (false? (dataf 'equiv [(data/float64 1)] [1])))
      (is (true? (dataf 'equiv (data/float64 1) (data/float64 1))))
      (is (true? (dataf 'equiv 1 1)))
      (is (false? (dataf 'admit-key? (data/float64 1))))
      (is (true? (dataf 'admit-key? 1)))
      (is (false? (dataf 'content= (data/float64 0.0) (data/float64 -0.0))))
      (is (true? (dataf 'equiv (data/float64 0.0) (data/float64 -0.0))))
      (is (false? (dataf 'equiv (data/float64 ##NaN) (data/float64 ##NaN))))
      (is (true? (dataf 'content= (data/float64 ##NaN) (data/float64 ##NaN)))))))


(deftest gate4-library-inc-dec-float-behavior
  (testing "inc/dec of floating carrier input take the floating branch on every host"
    (let [[ok-up one-up] (run-library [(list 'inc (data/float64 1.0))])
          [ok-down one-down] (run-library [(list 'dec (data/float64 2.0))])
          [ok-int iv] (run-library [(list 'inc 1)])]
      (is (= :yin.k/ok ok-up))
      (is (cbor/float64? one-up) "inc of float64 1.0 returns float kind")
      (is (= 2.0 (data/float-value one-up)))
      (is (= :yin.k/ok ok-down))
      (is (cbor/float64? one-down))
      (is (= 1.0 (data/float-value one-down)))
      (is (= :yin.k/ok ok-int))
      (is (integer-host/exact-integer? iv))
      (is (data-equiv 2 iv))
      (is (not (cbor/float64? iv)))))
  (when repl-route?
    (testing "inc/dec of source floating input take the floating branch"
      (let [[ok-up one-up] (run-library (yang/read-forms "(inc 1.0)"))
            [ok-down one-down] (run-library (yang/read-forms "(dec 2.0)"))]
        (is (= :yin.k/ok ok-up))
        (is (cbor/float64? one-up) "inc of 1.0 returns float kind")
        (is (= 2.0 (data/float-value one-up)))
        (is (= :yin.k/ok ok-down))
        (is (cbor/float64? one-down))
        (is (= 1.0 (data/float-value one-down)))))
    (testing "source integer input keeps integer behavior"
      (let [[ok v] (run-library (yang/read-forms "(inc 1)"))]
        (is (= :yin.k/ok ok))
        (is (integer-host/exact-integer? v))
        (is (data-equiv 2 v))
        (is (not (cbor/float64? v)))))))


(deftest gate4-repl-arithmetic-reboxed
  (when repl-route?
    (testing "the repaired default/catalog route computes in float kind"
      (let [cases {"(+ 1.5 1.5)" 3.0
                   "(+ 1.0 2.0)" 3.0
                   "(* 2.5 2.0)" 5.0
                   "(- 1.0 4.0)" -3.0
                   "(+ 1 0.5)" 1.5}]
        (doseq [[line expected] cases]
          (testing line
            (let [value (repl-session-result line)]
              (is (cbor/float64? value)
                  (str line " must answer float kind, got " (pr-str value)))
              (is (= expected (data/float-value value)))))))
      (is (true? (repl-session-result "(< 1.0 2)")))
      (is (false? (repl-session-result "(< 2.5 2.5)")))
      (is (true? (repl-session-result "(<= 2.5 2.5)")))
      (is (true? (repl-session-result "(> 0.5 0.25)")))
      (is (false? (repl-session-result "(= 1.0 1)"))
          "source = stays kind-strict numeric equality")
      (is (true? (repl-session-result "(= 1.5 1.5)")))
      (is (true? (repl-session-result "(== 1.0 1)"))
          "source == is Clojure's cross-kind numeric equality")
      (is (false? (repl-session-result "(= [1.0] [1])")))
      (is (true? (repl-session-result "(= [1.0 2.0] [1.0 2.0])"))))))


(deftest gate4-repl-literal-kind-through-the-catalog
  (when repl-route?
    (is (cbor/float64? (repl-session-result "1.0")))
    (is (integer-host/exact-integer? (repl-session-result "1")))
    (is (cbor/float64? (repl-session-result "-0.0")))
    (is (= 1.0 (data/float-value (repl-session-result "1.0"))))
    (is (= "1.0" (repl-text "1.0")) "the shell renders the float as a float")
    (is (= "-0.0" (repl-text "-0.0")) "signed zero renders signed")))


(deftest gate4-unboxed-control-distinguishes
  "The control proving the oracle catches dropped boxing: the same value
   compiled from an unboxed host literal instead of the token-preserving
   reader must answer the INTEGER behavior on the host where a bare
   integral Number is an integer (JS), while the repaired source path
   answers float. The collapsed path cannot pass this oracle."
  (testing "the unboxed control"
    #?(:jolt nil
       :cljd nil
       :cljs
       (let [unboxed (run-ast (yang/compile 1.0))
             boxed (run-ast (yang/compile (read-one "1.0")))]
         (is (integer-host/exact-integer? unboxed)
             "an unboxed integral literal on JS IS integer content - the defect")
         (is (cbor/float64? boxed) "the source path answers float kind")
         (is (not (data-equiv unboxed boxed)))
         (is (not= (:root (vm/ast->semantic-bytecode (yang/compile 1.0)))
                   (semantic-root "1.0"))
             "the digest gate distinguishes them too"))
       :clj
       (let [direct (run-ast (yang/compile 1.0))
             sourced (run-ast (yang/compile (read-one "1.0")))]
         (is (cbor/float64? direct) "a JVM Double is float content either way")
         (is (data-equiv direct sourced))
         (is (= (:root (vm/ast->semantic-bytecode (yang/compile 1.0)))
                (semantic-root "1.0"))
             "on the JVM both spellings mint the same float bytes")))))


;; =============================================================================
;; GATE 5 - the API/build boundary
;; =============================================================================


(deftest gate5-explicit-carrier-forms-compile-and-run
  (testing "explicit Float64 forms compile directly, nested and quoted"
    (let [direct (yang/compile (data/float64 1))
          nested (yang/compile [(data/float64 1) 2])
          quoted (yang/compile (list 'quote {:f (data/float64 1)}))]
      (is (= :literal (:type direct)))
      (is (cbor/float64? (:value direct)))
      (is (cbor/float64? (first (:value nested))))
      (is (integer-host/exact-integer? (second (:value nested)))
          "integer Number controls remain integers")
      (is (cbor/float64? (get-in quoted [:value :f])))))
  (testing "they execute and serialize as float64 content"
    (let [value (run-ast (yang/compile [(data/float64 1) 2]))]
      (is (cbor/float64? (first value)))
      (is (= 1.0 (data/float-value (first value))))
      (is (integer-host/exact-integer? (second value))))
    (is (cbor/bytes= (jing/canonical-bytes (data/float64 1))
                     (jing/canonical-bytes (:value (yang/compile (data/float64 1)))))
        "the compiled literal serializes to the carrier's canonical bytes")))


(def carrier-root-reference
  "The recorded cross-host root of the compiled explicit-carrier form
  ((data/float64 1) as the whole program): every forms-capable host - JVM,
  Node, Dart, Jolt - compiles the SAME carrier form to the SAME root."
  ":segment/blake3-3eaa6d2a6a66538adbe59324cbe5b9e9b5f05da946f7793187a559e7b4971bca")


(deftest gate5-carrier-forms-one-root-everywhere
  (let [root (:root (vm/ast->semantic-bytecode (yang/compile (data/float64 1))))]
    (is (not= "unrecorded" carrier-root-reference))
    (is (= carrier-root-reference (str root)))
    (is (not= root (:root (vm/ast->semantic-bytecode (yang/compile 1))))
        "the carrier root differs from the integer control's root")))


(deftest gate5-parked-float-result-crosses-the-round
  "A float-producing operation whose result is integral must keep float
   kind across a continuation/snapshot boundary: a VM blocks on its own
   stream read with `(+ <hole> 0.5)` pending on its continuation, a later
   append of a CBOR-round-tripped `1.5` resumes it, and the answer `2.0` is
   float64 content both immediately and after canonical serialization."
  (when repl-route?
    (let [catalog (frontends/standard)
          selected (frontend/select catalog {:yang.frontend/id :yang/clojure})
          prims (merge vm/primitives (or (:primitives (:binding selected)) {}))
          created (atom nil)
          make-stream (fn [cap]
                        (let [res (tu/make-stream cap)]
                          (reset! created (:dao.stream/handle res))
                          res))
          ast (yang/compile-program
                (yang/read-forms "(def s (stream/make 4)) (+ (stream/next! (stream/cursor s)) 0.5)"))
          vm0 (tu/create-vm {:primitives prims
                             :modules (-> (module/default-registry)
                                          module/register-stream-module)
                             :make-stream make-stream
                             :capability-secret tu/secret})
          parked (vm/eval vm0 ast)]
      (is (vm/blocked? parked) "VM parks on empty stream with (+ <hole> 0.5) on continuation")
      (is (some? @created) "VM created its stream handle via composition :make-stream")
      (stream/append! @created (cbor/decode (jing/canonical-bytes (read-one "1.5"))))
      (let [resumed-vm (vm/run parked)
            resumed (vm/value resumed-vm)
            decoded (cbor/decode (jing/canonical-bytes resumed))]
        (is (vm/halted? resumed-vm))
        (is (cbor/float64? resumed)
            (str "the integral float result survives continuation resume as float kind, got " (pr-str resumed)))
        (is (not (integer-host/exact-integer? resumed)))
        (is (= 2.0 (data/float-value resumed)))
        (is (cbor/float64? decoded))
        (is (= 2.0 (data/float-value decoded)))))))


(deftest gate5-pre-pause-integral-float-across-continuation-and-snapshot
  "B-7 (Gate 5): integral floats computed BEFORE the continuation pause
   (in both the VM store and the lexical continuation environment) retain
   float64 kind, value, IEEE-754 bits, and canonical identity across both
   direct pause/resume and full UCF row-snapshot CBOR serialization/deserialization."
  #?(:jolt nil
     :cljd nil
     :default
     (let [catalog (frontends/standard)
           selected (frontend/select catalog {:yang.frontend/id :yang/clojure})
           prims (merge vm/primitives (or (:primitives (:binding selected)) {}))
           src "(def s (stream/make 4))
                (def stored-f (+ 1.5 0.5))
                (let [local-f (* 2.5 2.0)
                      neg-zero (* -1.0 0.0)]
                  (let [sig (stream/next! (stream/cursor s))]
                    [stored-f local-f neg-zero sig]))"
           ast (yang/compile-program (yang/read-forms src))
           proj (:vector (semantic-linearize/project ast))
           src-idx {(jing/segment-key proj) #{(jing/segment-key [:yin/tree ast])}}
           make-ring (fn [cap]
                       (:dao.stream/handle
                         (ringbuffer/create!
                           {:dao.stream/type ringbuffer/transport-type
                            ringbuffer/capacity-key cap})))
           verify-result
           (fn [label v]
             (testing label
               (is (= 4 (count v)))
               (let [[sf lf nz sig] v]
                 (is (= :go sig))
                 (doseq [[tag fv expected-val expected-bits]
                         [["stored 2.0" sf 2.0 "4000000000000000"]
                          ["local 5.0" lf 5.0 "4014000000000000"]
                          ["local -0.0" nz -0.0 "8000000000000000"]]]
                   (testing tag
                     (is (cbor/float64? fv) (str tag " must keep float64 kind, got " (pr-str fv)))
                     (is (not (integer-host/exact-integer? fv)) (str tag " must not collapse to integer"))
                     (is (= expected-val (data/float-value fv)))
                     (is (= expected-bits (host-bits-of fv)))
                     (is (cbor/bytes= (jing/canonical-bytes (data/float64 expected-val))
                                      (jing/canonical-bytes fv))))))))
           ;; (a) Direct pause / resume
           direct-stream (atom nil)
           m-direct (-> (sr/create-vm {:primitives prims
                                       :modules (-> (module/default-registry)
                                                    module/register-stream-module)
                                       :make-stream (fn [cap]
                                                      (let [h (make-ring cap)]
                                                        (reset! direct-stream h)
                                                        {:dao.stream/outcome :dao.stream/ok
                                                         :dao.stream/handle h}))
                                       :capability-secret tu/secret
                                       :secret-source (fn [o] (str tu/secret "/" (name o)))})
                        (sr/load-vector proj semantic-code/contract))
           parked-direct (vm/run m-direct)
           _ (is (vm/blocked? parked-direct) "VM pauses after computing stored-f, local-f, neg-zero")
           _ (stream/append! @direct-stream :go)
           resumed-direct (vm/run parked-direct)
           _ (is (vm/halted? resumed-direct))
           _ (verify-result "direct pause/resume" (vm/value resumed-direct))
           ;; (b) UCF snapshot CBOR serialization -> deserialization -> receiver resume
           m-snap (-> (sr/create-vm {:primitives prims
                                     :modules (-> (module/default-registry)
                                                  module/register-stream-module)
                                     :make-stream (fn [cap]
                                                    {:dao.stream/outcome :dao.stream/ok
                                                     :dao.stream/handle (make-ring cap)})
                                     :capability-secret tu/secret
                                     :secret-source (fn [o] (str tu/secret "/" (name o)))})
                      (sr/load-vector proj semantic-code/contract))
           parked-snap (vm/run m-snap)
           _ (is (vm/blocked? parked-snap) "snapshot VM pauses with pre-computed integral floats live")
           served-streams (atom {})
           serve! (let [table (atom {})]
                    (fn [h]
                      (or (get @table h)
                          (let [id (str "s" (count @table))
                                desc {:dao.stream/identity id
                                      :dao.stream/channel {:dao.stream/type :test/channel}}]
                            (swap! table assoc h desc)
                            (swap! served-streams assoc id h)
                            desc))))
           pack (ucf-rows/lift-task parked-snap serve! {:source-index src-idx})
           pack-bytes (cbor/encode pack)
           pack-decoded (cbor/decode pack-bytes)
           attach! (fn [d]
                     {:dao.stream/outcome :dao.stream/ok
                      :dao.stream/handle (get @served-streams (:dao.stream/identity d))})
           receiver (sr/create-vm {:primitives prims
                                   :modules (-> (module/default-registry)
                                                module/register-stream-module)
                                   :make-stream (fn [cap]
                                                  {:dao.stream/outcome :dao.stream/ok
                                                   :dao.stream/handle (make-ring cap)})
                                   :capability-secret tu/secret
                                   :secret-source (fn [o] (str tu/secret "/" (name o)))})
           restored (ucf-rows/lower-task receiver pack-decoded attach! {})]
       (is (vm/blocked? restored) "deserialized snapshot restores in blocked state")
       (stream/append! (get @served-streams "s0") :go)
       (let [resumed-snap (vm/run restored)]
         (is (vm/halted? resumed-snap))
         (verify-result "UCF CBOR snapshot round-trip" (vm/value resumed-snap))))))


;; =============================================================================
;; R2 SIGN-OFF REGRESSIONS (B-2 .. B-6)
;; =============================================================================


(deftest b2-set-and-map-false-nil-elements-and-duplicates
  "B-2: false/nil elements in sets (at any position) and maps must never
   truncate reading or bypass duplicate checks after them."
  (when repl-route?
    (testing "sets retain false and nil at first, middle, and last positions"
      (doseq [[src expected-false? expected-nil?]
              [["#{false 1.0 2}" true false]
               ["#{1.0 false 2}" true false]
               ["#{1.0 2 false}" true false]
               ["#{nil 1.0 2}" false true]
               ["#{1.0 nil 2}" false true]
               ["#{1.0 2 nil}" false true]]]
        (testing src
          (let [s (read-one src)
                floats (filter cbor/float64? s)]
            (is (set? s))
            (is (= 3 (count s)) (str src " read as " (pr-str s)))
            (is (= expected-false? (contains? s false)))
            (is (= expected-nil? (contains? s nil)))
            (is (contains? s 2))
            (is (= 1 (count floats)))
            (is (= 1.0 (data/float-value (first floats)))))))
      (let [s (read-one "#{false nil 1.0 2}")]
        (is (= 4 (count s)))
        (is (contains? s false))
        (is (contains? s nil))
        (is (contains? s 2))
        (is (= 1 (count (filter cbor/float64? s))))))
    (testing "duplicate set elements after false/nil are still refused"
      (doseq [src ["#{false 1 1}"
                   "#{nil 1 1}"
                   "#{false 1.0 1.0}"
                   "#{nil 1.0 1.0}"
                   "#{false false}"
                   "#{nil nil}"
                   "#{false nil false}"
                   "#{nil false nil}"]]
        (is (thrown? #?(:clj Exception :cljs js/Error :default Exception)
              (yang/read-forms src))
            (str src " must refuse duplicate element"))))
    (testing "maps retain false/nil keys and refuse duplicates after them"
      (let [m (read-one "{false 1.0 nil 2 1 :a}")]
        (is (= 3 (count m)))
        (is (cbor/float64? (get m false)))
        (is (= 1.0 (data/float-value (get m false))))
        (is (= 2 (get m nil)))
        (is (= :a (get m 1))))
      (doseq [src ["{false 1 false 2}"
                   "{nil 1 nil 2}"
                   "{false 1 2 :a 2 :b}"
                   "{nil 1 1.0 :a 1.0 :b}"]]
        (is (thrown? #?(:clj Exception :cljs js/Error :default Exception)
              (yang/read-forms src))
            (str src " must refuse duplicate key"))))))


(deftest b3-variadic-equality-and-inequality-truthiness
  "B-3: variadic clj-= and clj-!= must test sequence presence, never
   operand truthiness, including operands after false/nil."
  (testing "direct variadic clj-= and clj-!= calls"
    (is (false? (clj-numeric/clj-= 1 1 false)))
    (is (false? (clj-numeric/clj-= 1 1 nil)))
    (is (false? (clj-numeric/clj-= 1 1 false 1)))
    (is (false? (clj-numeric/clj-= 1 1 nil 1)))
    (is (false? (clj-numeric/clj-= (data/float64 1.0) (data/float64 1.0) false)))
    (is (false? (clj-numeric/clj-= (data/float64 1.0) (data/float64 1.0) nil)))
    (is (true? (clj-numeric/clj-= false false false)))
    (is (false? (clj-numeric/clj-= false false true)))
    (is (false? (clj-numeric/clj-= false false nil)))
    (is (false? (clj-numeric/clj-= false false false 1)))
    (is (true? (clj-numeric/clj-= nil nil nil)))
    (is (false? (clj-numeric/clj-= nil nil false)))
    (is (false? (clj-numeric/clj-= nil nil 1)))
    (is (true? (clj-numeric/clj-!= 1 1 false)))
    (is (true? (clj-numeric/clj-!= 1 1 nil)))
    (is (true? (clj-numeric/clj-!= 1 1 false 1)))
    (is (false? (clj-numeric/clj-!= false false false)))
    (is (true? (clj-numeric/clj-!= false false nil)))
    (is (false? (clj-numeric/clj-!= nil nil nil)))
    (is (true? (clj-numeric/clj-!= nil nil false))))
  (when repl-route?
    (testing "variadic = and != through the REPL/catalog source path"
      (doseq [[line expected]
              [["(= 1 1 false)" false]
               ["(= 1 1 nil)" false]
               ["(= 1 1 false 1)" false]
               ["(= 1 1 nil 1)" false]
               ["(= 1.0 1.0 false)" false]
               ["(= 1.0 1.0 nil)" false]
               ["(= 1.0 1.0 false 1.0)" false]
               ["(= false false false)" true]
               ["(= false false true)" false]
               ["(= false false nil)" false]
               ["(= false false false 1)" false]
               ["(= nil nil nil)" true]
               ["(= nil nil false)" false]
               ["(= nil nil 1.0)" false]
               ["(= 1.0 1.0 1.0)" true]
               ["(= 1.0 1.0 1)" false]
               ["(!= 1 1 false)" true]
               ["(!= 1 1 nil)" true]
               ["(!= 1 1 false 1)" true]
               ["(!= 1.0 1.0 false)" true]
               ["(!= false false false)" false]
               ["(!= false false nil)" true]
               ["(!= nil nil nil)" false]
               ["(!= nil nil false)" true]
               ["(!= 1.0 1.0 1.0)" false]]]
        (is (= expected (repl-session-result line)) line)))))


(deftest b4-discard-sentinel-cannot-collide-with-guest-values
  "B-4: valid guest literals (especially :yang.clojure.reader/discard,
   false, nil, :discard, and vector shapes) must remain representable in
   top-level, collection, and required-form positions."
  (when repl-route?
    (testing "top-level positions"
      (is (= [:yang.clojure.reader/discard]
             (yang/read-forms ":yang.clojure.reader/discard")))
      (is (= [:yang.clojure.reader/discard 1]
             (yang/read-forms ":yang.clojure.reader/discard 1")))
      (is (= [false nil :discard [:discard nil 0]]
             (yang/read-forms "false nil :discard [:discard nil 0]")))
      (is (= :yang.clojure.reader/discard
             (repl-session-result ":yang.clojure.reader/discard"))))
    (testing "collection positions"
      (let [v (read-one "[:yang.clojure.reader/discard 1.0]")
            l (read-one "(:yang.clojure.reader/discard 1.0)")
            m (read-one "{:yang.clojure.reader/discard 1.0 :k :yang.clojure.reader/discard}")
            s (read-one "#{:yang.clojure.reader/discard false nil 1.0}")]
        (is (= 2 (count v)))
        (is (= :yang.clojure.reader/discard (first v)))
        (is (cbor/float64? (second v)))
        (is (= 2 (count l)))
        (is (= :yang.clojure.reader/discard (first l)))
        (is (cbor/float64? (second l)))
        (is (= 2 (count m)))
        (is (cbor/float64? (get m :yang.clojure.reader/discard)))
        (is (= :yang.clojure.reader/discard (get m :k)))
        (is (= 4 (count s)))
        (is (contains? s :yang.clojure.reader/discard))
        (is (contains? s false))
        (is (contains? s nil))
        (is (= 1 (count (filter cbor/float64? s))))))
    (testing "required-form positions"
      (is (= '(quote :yang.clojure.reader/discard)
             (read-one "':yang.clojure.reader/discard")))
      (is (= {:yang.clojure.reader/discard true}
             (meta (read-one "^:yang.clojure.reader/discard [1.0]"))))
      (let [after-discard (yang/read-forms "#_:yang.clojure.reader/discard 1.0")]
        (is (= 1 (count after-discard)))
        (is (cbor/float64? (first after-discard))))
      (is (= '(clojure.core/unquote :yang.clojure.reader/discard)
             (read-one "~:yang.clojure.reader/discard")))
      (is (= '(clojure.core/deref :yang.clojure.reader/discard)
             (read-one "@:yang.clojure.reader/discard")))
      (is (= '(var :yang.clojure.reader/discard)
             (read-one "#':yang.clojure.reader/discard")))
      (let [fn-form (read-one "#(:yang.clojure.reader/discard %)")]
        (is (= 'fn (first fn-form)))
        (is (= :yang.clojure.reader/discard (first (nth fn-form 2))))))))


(deftest b5-anon-fn-capture-avoiding-and-deterministic
  "B-5: #(...) expansion must be capture-avoiding (never capturing user
   bindings like p__1/p__2/p__rest or generated-pattern names), parse-scoped,
   and deterministic in canonical output."
  (when repl-route?
    (testing "the 4-vs-12 counterexample and float variant"
      (is (= 12 (repl-session-result "(let [p__1 10] (#(+ p__1 %) 2))")))
      (let [fv (repl-session-result "(let [p__1 10.0] (#(+ p__1 %) 2.0))")]
        (is (cbor/float64? fv))
        (is (= 12.0 (data/float-value fv)))))
    (testing "positional and rest bindings against colliding user names"
      (let [form (read-one "#(+ p__1 p__2 p__rest %1 %2 (first %&))")
            params (second form)
            body (nth form 2)
            [p1 p2 amp r] params]
        (is (= 'fn (first form)))
        (is (= 4 (count params)))
        (is (= '& amp))
        (is (not-any? #{'p__1 'p__2 'p__rest} [p1 p2 r]))
        (is (= (list '+ 'p__1 'p__2 'p__rest p1 p2 (list 'first r)) body)))
      (let [form (read-one "#(+ p1__1# p2__1# rest__1# %1 %2 (first %&))")
            params (second form)
            body (nth form 2)
            [p1 p2 amp r] params]
        (is (= 4 (count params)))
        (is (= '& amp))
        (is (not-any? #{'p1__1# 'p2__1# 'rest__1#} [p1 p2 r]))
        (is (= (list '+ 'p1__1# 'p2__1# 'rest__1# p1 p2 (list 'first r)) body)))
      (let [form (read-one "#(+ rest__1# p__rest (first %&))")
            params (second form)
            body (nth form 2)
            [amp r] params]
        (is (= 2 (count params)))
        (is (= '& amp))
        (is (not (contains? #{'rest__1# 'p__rest} r)))
        (is (= (list '+ 'rest__1# 'p__rest (list 'first r)) body)))
      #?(:clj
         (do
           (is (= 66 (eval (read-one "(let [p__1 10 p__2 20 p__rest 30] (#(+ p__1 p__2 p__rest %1 %2 (first %&)) 1 2 3))"))))
           (is (= 305 (eval (read-one "(let [rest__1# 100 p__rest 200] (#(+ rest__1# p__rest (first %&)) 5 6))"))))))
      (is (= 33 (repl-session-result "(let [p__1 10 p__2 20] (#(+ p__1 p__2 %1 %2) 1 2))")))
      (is (= 12 (repl-session-result "(let [p1__1# 10] (#(+ p1__1# %) 2))")))
      (is (= 33 (repl-session-result "(let [p1__1# 10 p1__2# 20] (#(+ p1__1# p1__2# %) 3))")))
      (is (= 22 (repl-session-result "(let [p__1 10] (#(let [p__1 20] (+ p__1 %)) 2))")))
      (is (= 12 (repl-session-result "(let [p1__1# 10] (#(+ p1__1# %2) 99 2))"))))
    (testing "deterministic canonical output across reads and sibling forms"
      (is (= (read-one "#(+ % 1.0)") (read-one "#(+ % 1.0)")))
      (is (= (semantic-root "#(+ % 1.0)") (semantic-root "#(+ % 1.0)")))
      (is (= (semantic-root "#(+ p__1 % 1.0)") (semantic-root "#(+ p__1 % 1.0)")))
      (let [[f1 f2] (read-one "[#(+ % 1.0) #(+ % 1.0)]")]
        (is (= f1 f2))))
    (testing "duplicate argument in set inside #() is still caught"
      (is (thrown? #?(:clj Exception :cljs js/Error :default Exception)
            (yang/read-forms "#(#{% %1})"))))))


(deftest b6-lexical-compatibility-and-syntax-quote
  "B-6: restore previously supported grammar (apostrophe-in-symbol, scalar
   syntax-quote, deprecated #^ meta, @x -> clojure.core/deref) and check
   differential lexical parity against tools.reader."
  #?(:jolt nil
     :cljd nil
     :default
     (do
       (testing "apostrophe-in-symbol reads and executes"
         (is (= 'x' (read-one "x'")))
         (is (= 'foo'bar (read-one "foo'bar")))
         (is (= 'a'' (read-one "a''")))
         (is (= 'ns/x' (read-one "ns/x'")))
         (is (= '(quote x') (read-one "'x'")))
         (is (= :k' (read-one ":k'")))
         (let [fv (repl-session-result "(let [x' 1.0] (+ x' 2.0))")]
           (is (cbor/float64? fv))
           (is (= 3.0 (data/float-value fv)))))
       (testing "scalar syntax-quote reads and executes as before, preserving float kind"
         (is (= 1 (read-one "`1")))
         (is (= 1 (repl-session-result "`1")))
         (let [f-read (read-one "`1.0")
               f-eval (repl-session-result "`1.0")
               nz-eval (repl-session-result "`-0.0")]
           (is (cbor/float64? f-read))
           (is (= 1.0 (data/float-value f-read)))
           (is (cbor/float64? f-eval))
           (is (= 1.0 (data/float-value f-eval)))
           (is (cbor/float64? nz-eval))
           (is (= "8000000000000000" (host-bits-of nz-eval))))
         (is (= true (repl-session-result "`true")))
         (is (= false (repl-session-result "`false")))
         (is (= nil (repl-session-result "`nil")))
         (is (= "hi" (repl-session-result "`\"hi\"")))
         (is (= :k (repl-session-result "`:k")))
         (is (cbor/ratio? (read-one "`1/2")))
         (is (cbor/decimal? (read-one "`1M")))
         (let [unquoted (repl-session-result "(let [x 1.0] `~x)")]
           (is (cbor/float64? unquoted))
           (is (= 1.0 (data/float-value unquoted))))
         (is (thrown? #?(:clj Exception :cljs js/Error :default Exception)
               (yang/read-forms "`~@x")))
         (let [sq-vec (read-one "`[1.0 ~x]")]
           (is (seq? sq-vec)))
         (let [sq-gs (read-one "`[x# x# y#]")]
           (is (= sq-gs (read-one "`[x# x# y#]")) "auto-gensym in syntax-quote is deterministic")))
       (testing "differential lexical parity against tools.reader on non-float grammar"
         (let [old-read-all (fn [src]
                              (let [rd (tools-rt/string-push-back-reader src)]
                                (loop [acc []]
                                  (let [f (tools-reader/read rd false ::eof)]
                                    (if (= f ::eof)
                                      acc
                                      (recur (conj acc f)))))))
               corpus ["x'"
                       "foo'bar"
                       "a''"
                       "ns/x'"
                       "'x'"
                       ":k'"
                       "x#"
                       "foo:bar"
                       "foo%bar"
                       "\\'"
                       "\\newline"
                       "\\u0041"
                       "\\o101"
                       "\"a\\nb\\t\\\"\\\\\\u0041\\101\""
                       "; comment\n42"
                       "#! shebang\n42"
                       "#_#_1 2 3"
                       "#^:m [1]"
                       "^:a ^{:b 2} [1]"
                       "@x"
                       "#'x"
                       "~x"
                       "~@x"
                       "#{false nil 1 2}"
                       "{false 1 nil 2}"
                       "#:a{:b 1 _/c 2}"
                       "`1"
                       "`true"
                       "`false"
                       "`nil"
                       "`\"hi\""
                       "`:k"
                       "`~x"]]
           (doseq [src corpus]
             (testing src
               (let [expected (old-read-all src)
                     actual (yang/read-forms src)]
                 (is (= expected actual) (str "differential mismatch for " (pr-str src)))
                 (is (= (mapv meta expected) (mapv meta actual))
                     (str "metadata mismatch for " (pr-str src)))))))))))


#?(:jolt nil
   :cljd nil
   :default
   (defn- eval-syntax-quote-form
     "Portable test evaluator for standard syntax-quote expansion output
      (clojure.core/{sequence,seq,concat,list,vec,apply,hash-map,array-map,
      hash-set,with-meta} + quote), returning the constructed guest form on
      both JVM and Node."
     [form]
     (cond
       (and (seq? form) (= 'quote (first form)))
       (second form)

       (seq? form)
       (let [[op & args] form
             ev-args (mapv eval-syntax-quote-form args)]
         (case op
           clojure.core/sequence (sequence (first ev-args))
           clojure.core/seq (seq (first ev-args))
           clojure.core/concat (apply concat ev-args)
           clojure.core/list (apply list ev-args)
           clojure.core/vec (vec (first ev-args))
           clojure.core/apply (let [[f coll] ev-args]
                                (case f
                                  clojure.core/hash-map (apply hash-map coll)
                                  clojure.core/array-map (apply array-map coll)
                                  clojure.core/hash-set (apply hash-set coll)
                                  (throw (ex-info "Unsupported apply fn in syntax-quote test eval" {:fn f}))))
           clojure.core/with-meta (with-meta (first ev-args) (second ev-args))
           (throw (ex-info "Unsupported op in syntax-quote test eval" {:op op :form form}))))

       :else form)))


(deftest b6a-syntax-quote-symbol-qualification
  "B-6a: syntax-quote must qualify symbols through an explicit portable
   reader context (core symbols -> clojure.core/*, special symbols bare,
   unqualified symbols -> <ns>/*, alias expansion, refer/exclude overrides)
   with zero global mutation, verified differentially against tools.reader
   and via actual execution."
  #?(:jolt nil
     :cljd nil
     :default
     (do
       (testing "default portable context: core, special, user, qualified, and interop symbols"
         (doseq [[src expected]
                 [["`+" '(quote clojure.core/+)]
                  ["`-" '(quote clojure.core/-)]
                  ["`/" '(quote clojure.core//)]
                  ["`let" '(quote clojure.core/let)]
                  ["`fn" '(quote clojure.core/fn)]
                  ["`defn" '(quote clojure.core/defn)]
                  ["`defmacro" '(quote clojure.core/defmacro)]
                  ["`loop" '(quote clojure.core/loop)]
                  ["`map" '(quote clojure.core/map)]
                  ["`if" '(quote if)]
                  ["`do" '(quote do)]
                  ["`def" '(quote def)]
                  ["`fn*" '(quote fn*)]
                  ["`let*" '(quote let*)]
                  ["`loop*" '(quote loop*)]
                  ["`quote" '(quote quote)]
                  ["`var" '(quote var)]
                  ["`recur" '(quote recur)]
                  ["`&" '(quote &)]
                  ["`try" '(quote try)]
                  ["`catch" '(quote catch)]
                  ["`finally" '(quote finally)]
                  ["`throw" '(quote throw)]
                  ["`new" '(quote new)]
                  ["`." '(quote .)]
                  ["`x" '(quote user/x)]
                  ["`x'" '(quote user/x')]
                  ["`foo/bar" '(quote foo/bar)]
                  ["`clojure.core/+" '(quote clojure.core/+)]
                  ["`clojure.core//" '(quote clojure.core//)]
                  ["`foo/x#" '(quote foo/x#)]
                  ["`.foo" '(quote .foo)]
                  ["`.." '(quote ..)]
                  ["`Foo." '(quote Foo.)]
                  ["`java.lang.String" '(quote java.lang.String)]]]
           (testing src
             (is (= expected (read-one src))
                 (str src " should read as " (pr-str expected))))))

       (testing "explicit portable reader context (:ns, :aliases, :refers, :exclude, :refer-clojure?) without global mutation"
         (is (= '(quote my.app/x)
                (first (yang/read-forms "`x" {:ns 'my.app}))))
         (is (= '(quote clojure.core/+)
                (first (yang/read-forms "`+" {:ns 'my.app}))))
         (is (= '(quote if)
                (first (yang/read-forms "`if" {:ns 'my.app}))))
         (is (= '(quote clojure.string/join)
                (first (yang/read-forms "`str/join" {:ns 'my.app
                                                     :aliases {'str 'clojure.string}}))))
         (is (= '(quote other/fn)
                (first (yang/read-forms "`other/fn" {:ns 'my.app
                                                     :aliases {'str 'clojure.string}}))))
         (is (= '(quote clojure.string/join)
                (first (yang/read-forms "`join" {:ns 'my.app
                                                 :refers {'join 'clojure.string}}))))
         (is (= '(quote my.lib/helper)
                (first (yang/read-forms "`helper" {:ns 'my.app
                                                   :refers {'helper 'my.lib/helper}}))))
         (is (= '(quote my.app/+)
                (first (yang/read-forms "`+" {:ns 'my.app
                                              :exclude #{'+}}))))
         (is (= '(quote clojure.core/-)
                (first (yang/read-forms "`-" {:ns 'my.app
                                              :exclude #{'+}}))))
         (is (= '(quote my.app/+)
                (first (yang/read-forms "`+" {:ns 'my.app
                                              :refer-clojure? false}))))
         ;; Confirm no global state leaked from custom context
         (is (= '(quote user/x) (read-one "`x")))
         (is (= '(quote str/join) (read-one "`str/join")))
         (is (= '(quote clojure.core/+) (read-one "`+"))))

       (testing "actual execution of syntax-quoted symbols through Yin REPL/VM"
         (doseq [[src expected-sym]
                 [["`+" 'clojure.core/+]
                  ["`/" 'clojure.core//]
                  ["`let" 'clojure.core/let]
                  ["`fn" 'clojure.core/fn]
                  ["`if" 'if]
                  ["`fn*" 'fn*]
                  ["`do" 'do]
                  ["`&" '&]
                  ["`x" 'user/x]
                  ["`x'" 'user/x']
                  ["`foo/bar" 'foo/bar]
                  ["`.toString" '.toString]
                  ["`Foo." 'Foo.]]]
           (testing (str "REPL eval " src)
             (is (= expected-sym (repl-session-result src))
                 (str "executing " src " must return " (pr-str expected-sym)))))
         (testing "compound syntax-quote evaluates with qualified symbols and preserved float64"
           (let [form (eval-syntax-quote-form (read-one "`(+ 1.0 x)"))]
             (is (= 'clojure.core/+ (first form)))
             (is (cbor/float64? (second form)))
             (is (= 1.0 (data/float-value (second form))))
             (is (= 'user/x (nth form 2)))))
         (testing "cross-host canonical root of syntax-quoted symbols differs from bare symbols"
           (is (not= (semantic-root "`+") (semantic-root "'+")))
           (is (not= (semantic-root "`x") (semantic-root "'x")))
           (is (= (semantic-root "`+") (semantic-root "'clojure.core/+")))
           (is (= (semantic-root "`x") (semantic-root "'user/x")))
           (is (= (semantic-root "`if") (semantic-root "'if")))))

       #?(:clj
          (testing "JVM differential parity against clojure.tools.reader for quoted symbols & compound forms"
            (let [old-read-user (fn [src]
                                  (binding [*ns* (the-ns 'user)]
                                    (let [rd (tools-rt/string-push-back-reader src)]
                                      (loop [acc []]
                                        (let [f (tools-reader/read rd false ::eof)]
                                          (if (= f ::eof)
                                            acc
                                            (recur (conj acc f))))))))
                  sym-corpus ["`+"
                              "`-"
                              "`/"
                              "`let"
                              "`fn"
                              "`defn"
                              "`defmacro"
                              "`if"
                              "`do"
                              "`def"
                              "`fn*"
                              "`let*"
                              "`loop*"
                              "`quote"
                              "`var"
                              "`recur"
                              "`&"
                              "`x"
                              "`x'"
                              "`foo/bar"
                              "`clojure.core/+"
                              "`clojure.core//"
                              "`foo/x#"
                              "`.foo"
                              "`.."
                              "`Foo."
                              "`java.lang.String"
                              "`(+ 1 2)"
                              "`(let [x 1] (+ x y))"
                              "`[+ x if foo/bar]"
                              "`{:op + :sym x}"
                              "`#{+ x}"
                              "`^:m x"
                              "`(+ ~a ~@bs)"]]
              (doseq [src sym-corpus]
                (testing src
                  (is (= (old-read-user src) (yang/read-forms src))
                      (str "tools.reader differential mismatch for " (pr-str src)))))
              (testing "alias expansion matches tools.reader with ns alias"
                (let [tmp-ns (create-ns 'yang.clojure.reader-test-alias-ns)]
                  (try
                    (binding [*ns* tmp-ns]
                      (clojure.core/refer-clojure)
                      (clojure.core/alias 'str 'clojure.string)
                      (is (= (tools-reader/read-string "`(str/join x + if)")
                             (first (yang/read-forms "`(str/join x + if)"
                                                     {:ns 'yang.clojure.reader-test-alias-ns
                                                      :aliases {'str 'clojure.string}})))))
                    (finally
                      (remove-ns 'yang.clojure.reader-test-alias-ns)))))))))))


(deftest b6b-syntax-quote-auto-gensym-hygiene-and-scopes
  "B-6b: auto-gensym in syntax-quote must be deterministic AND hygienic
   across user references, unquoted forms, nested syntax-quote scopes, and
   sibling scopes (no capture of x__1__auto__, no per-quote counter reset)."
  #?(:jolt nil
     :cljd nil
     :default
     (do
       (testing "sign-off reproducer: x# must not collide with ~'x__1__auto__ (12 vs 4)"
         (let [src "`(~'let [x# 2] (~'+ x# ~'x__1__auto__))"
               expanded (eval-syntax-quote-form (read-one src))
               binding-sym (first (second expanded))
               body (nth expanded 2)
               bound-ref (second body)
               user-ref (nth body 2)]
           (is (= 'let (first expanded)))
           (is (symbol? binding-sym))
           (is (nil? (namespace binding-sym)))
           (is (re-matches #"^x__\d+__auto__$" (name binding-sym)))
           (is (= binding-sym bound-ref) "both x# occurrences resolve to the same gensym")
           (is (= 'x__1__auto__ user-ref) "user reference stays untouched")
           (is (not= binding-sym user-ref) "auto-gensym must not collide with user x__1__auto__")
           ;; Semantic execution in Yin VM on BOTH JVM and Node
           (is (= 12 (run-ast (yang/compile (list 'let ['x__1__auto__ 10] expanded))))
               "semantic result must be 12, never 4")
           ;; Float64 semantic variant
           (let [f-src "`(~'let [x# 2.0] (~'+ x# ~'x__1__auto__))"
                 f-expanded (eval-syntax-quote-form (read-one f-src))
                 catalog (frontends/standard)
                 selected (frontend/select catalog {:yang.frontend/id :yang/clojure})
                 prims (merge vm/primitives (or (:primitives (:binding selected)) {}))
                 fv (-> (tu/create-vm {:primitives prims
                                       :make-stream tu/make-stream
                                       :capability-secret tu/secret})
                        (vm/eval (yang/compile (list 'let ['x__1__auto__ (data/float64 10.0)] f-expanded)))
                        vm/value)]
             (is (cbor/float64? fv))
             (is (= 12.0 (data/float-value fv))))
           #?(:clj
              (let [jvm-val (eval (list 'let ['x__1__auto__ 10] (eval (read-one src))))
                    upstream-val (eval (list 'let ['x__1__auto__ 10]
                                             (eval (binding [*ns* (the-ns 'user)]
                                                     (tools-reader/read-string src)))))]
                (is (= 12 jvm-val))
                (is (= upstream-val jvm-val))))))

       (testing "[x# x__1__auto__] and reversed order never collide"
         (let [[g1 u1] (eval-syntax-quote-form (read-one "`[x# x__1__auto__]"))
               [g2 u2] (eval-syntax-quote-form (read-one "`[x# ~'x__1__auto__]"))
               [u3 g3] (eval-syntax-quote-form (read-one "`[~'x__1__auto__ x#]"))]
           (is (not= g1 u1))
           (is (not= (name g1) (name u1)) "x# must avoid base name x__1__auto__ even when qualified")
           (is (not= g2 u2))
           (is (= 'x__1__auto__ u2))
           (is (not= g3 u3))
           (is (= 'x__1__auto__ u3))))

       (testing "multi-collision avoidance across x__1__auto__, x__2__auto__, x__3__auto__"
         (let [src "`(~'let [x# 2] (~'+ x# (~'+ ~'x__1__auto__ (~'+ ~'x__2__auto__ ~'x__3__auto__))))"
               expanded (eval-syntax-quote-form (read-one src))
               gs (first (second expanded))]
           (is (not (contains? #{'x__1__auto__ 'x__2__auto__ 'x__3__auto__} gs)))
           (is (= 62 (run-ast (yang/compile
                                (list 'let ['x__1__auto__ 10 'x__2__auto__ 20 'x__3__auto__ 30]
                                      expanded)))))))

       (testing "nested syntax-quote scopes via unquote do not reuse counters"
         (let [src "`(~'let [x# 10] (~'+ x# ~`(~'let [x# 2] (~'+ x# ~'x__1__auto__))))"
               expanded (eval-syntax-quote-form (read-one src))
               outer-sym (first (second expanded))
               inner-let (nth (nth expanded 2) 2)
               inner-sym (first (second inner-let))]
           (is (not= outer-sym inner-sym) "outer and inner syntax-quote scopes must get distinct gensyms")
           (is (not= outer-sym 'x__1__auto__))
           (is (not= inner-sym 'x__1__auto__))
           (is (= 112 (run-ast (yang/compile (list 'let ['x__1__auto__ 100] expanded)))))
           #?(:clj
              (is (= 112 (eval (list 'let ['x__1__auto__ 100] (eval (read-one src)))))))))

       (testing "double-nested syntax-quote (macro generating macro) preserves distinct scopes"
         (let [src "`(~'let [x# 10] `(~'let [x# 2] [x# ~x#]))"
               outer-form (eval-syntax-quote-form (read-one src))
               outer-sym (first (second outer-form))]
           (is (symbol? outer-sym))
           #?(:clj
              (let [inner-form (eval outer-form)
                    inner-sym (first (second inner-form))]
                (is (not= outer-sym inner-sym))
                (is (= [2 10] (eval inner-form)))))))

       (testing "sibling syntax-quote scopes in one read-forms call do not reset counters"
         (let [forms (yang/read-forms "`(~'let [t# 10] t#) `(~'let [t# 2] (~'+ t# ~'y))")
               f1 (eval-syntax-quote-form (first forms))
               f2 (eval-syntax-quote-form (second forms))
               t1 (first (second f1))
               t2 (first (second f2))]
           (is (not= t1 t2) "sibling syntax-quotes must not reuse the same gensym")
           ;; Simulate collaborating macros: outer binds t1=10 and passes t1 to inner which binds t2=2
           (let [combined (list 'let [t1 10] (list 'let [t2 2] (list '+ t2 t1)))]
             (is (= 12 (run-ast (yang/compile combined)))))))

       (testing "collision avoidance sees symbols in surrounding forms of the same read-forms call"
         (let [forms1 (yang/read-forms "(let [x__1__auto__ 10] `(~'let [x# 2] (~'+ x# ~'y)))")
               sq1 (eval-syntax-quote-form (nth (first forms1) 2))
               g1 (first (second sq1))
               forms2 (yang/read-forms "`(~'let [x# 2] (~'+ x# ~'y)) (let [x__1__auto__ 10] x__1__auto__)")
               sq2 (eval-syntax-quote-form (first forms2))
               g2 (first (second sq2))]
           (is (not= 'x__1__auto__ g1))
           (is (not= 'x__1__auto__ g2))))

       (testing "determinism across repeated reads"
         (let [src "`(~'let [x# 2] (~'+ x# ~'x__1__auto__))"]
           (is (= (read-one src) (read-one src)))
           (is (= (semantic-root src) (semantic-root src))))))))


#?(:jolt nil
   :cljd nil
   :clj
   (defn- integral-double-leaves
     "Every integral Double constant in host-quoted `form` data (the JVM is
     the host that can still see the Double/integer distinction)."
     [form]
     (let [acc (atom [])]
       (walk/postwalk
         (fn [x]
           (when (and (instance? Double x)
                      (Double/isFinite (double x))
                      (== (double x) (Math/rint (double x))))
             (swap! acc conj x))
           x)
         form)
       @acc)))


(deftest gate5-quoted-prelude-audit-no-integral-doubles
  "The JVM-side producer audit: the quoted prelude tables that build the
   clj/clojure.core.impl/clj.py runtime modules carry no bare integral
   Double constant a JS host would silently encode as an integer. Floats
   in prose are non-integral or explicitly constructed."
  #?(:jolt nil
     :cljd nil
     :clj
     (let [producers ["clj runtime definitions"
                      (concat @(resolve 'yang.clojure.prelude/definitions)
                              @(resolve 'yang.clojure.prelude/source-definitions))
                      "clojure.core.impl operations"
                      @(resolve 'yang.clojure.core-impl/operations)
                      "clj.py numeric table"
                      @(resolve 'yang.python.antlr.clj-runtime/numeric)]]
       (doseq [[label forms] (partition 2 producers)]
         (testing label
           (is (= [] (integral-double-leaves forms))
               (str label " quotes no integral Double constant")))))))


;; =============================================================================
;; GATE 4 (linked/library path arithmetic with scoped Clojure primitives)
;; =============================================================================

(deftest gate4-linked-library-arithmetic-reboxed
  (when repl-route?
    (testing "linked/library execution with the repaired Clojure profile primitives computes and reboxes floats"
      (let [catalog (frontends/standard)
            selected (frontend/select catalog {:yang.frontend/id :yang/clojure})
            prims (merge vm/primitives (or (:primitives (:binding selected)) {}))
            run-lib (fn [source]
                      (let [ast (yang/compile-program
                                  (yang/read-forms source) true
                                  {:convention :arg-vector
                                   :outcomes true
                                   :native (set (keys vm/primitives))})]
                        (-> (tu/make-observer-session
                              (tu/create-vm {:primitives prims
                                             :modules library-registry
                                             :make-stream tu/make-stream
                                             :capability-secret tu/secret}))
                            (tu/queue-ast! ast)
                            tu/run-session
                            :consumer
                            vm/value)))]
        (doseq [[source expected] [["(+ 1.5 1.5)" 3.0]
                                   ["(* 2.5 2.0)" 5.0]
                                   ["(- 1.0 4.0)" -3.0]
                                   ["(+ 1 0.5)" 1.5]]]
          (testing source
            (let [[status v] (run-lib source)]
              (is (= :yin.k/ok status))
              (is (cbor/float64? v) (str source " must return float64, got " (pr-str v)))
              (is (= expected (data/float-value v))))))
        (is (= [:yin.k/ok true] (run-lib "(< 1.0 2)")))
        (is (= [:yin.k/ok false] (run-lib "(= 1.0 1)")))
        (is (= [:yin.k/ok true] (run-lib "(== 1.0 1)")))))))


;; =============================================================================
;; GATE 6 - cross-host artifacts and genuine pre-fix legacy custody
;; =============================================================================

(def ^:private legacy-jvm-float-b64
  "Genuine pre-fix c1-2 JVM float-bearing artifact of `((fn [] 1.0))`
   (1,415 bytes, sha256 71dc93298c26773dc8383f5d12e03d732840052692a272c16c4aaf4ec90783ec),
   captured at 7f4898f4 before the repair."
  (str "p9gbgnBkYW8uamluZy9rZXl3b3JkgnZ5YW5nLmNsb2p1cmUuZmxvYXRraW5kZGhvc3TYG4JwZGFvLmppbmcva2V5d29yZIL2aWp2"
       "bS1mbG9hdNgbgnBkYW8uamluZy9rZXl3b3JkgnZ5YW5nLmNsb2p1cmUuZmxvYXRraW5kZmxlZ2FjefXYG4JwZGFvLmppbmcva2V5"
       "d29yZIJ2eWFuZy5jbG9qdXJlLmZsb2F0a2luZGZzb3VyY2VtKChmbiBbXSAxLjApKdgbgnBkYW8uamluZy9rZXl3b3JkgnZ5YW5n"
       "LmNsb2p1cmUuZmxvYXRraW5ka3ByZS1maXgtcmVmbTdmNDg5OGY0IGMxLTLYG4JwZGFvLmppbmcva2V5d29yZIL2ZHJvb3TYG4Jw"
       "ZGFvLmppbmcva2V5d29yZIJnc2VnbWVudHhHYmxha2UzLTdjZGFlOWRmZWU3ZjE5ODBjY2RkOTU0M2YyNzlkMGQ3ZDIwZTRhNGE0"
       "YTQ0ZGRhMTRmODhmNTRiYTkwMDIwNDnYG4JwZGFvLmppbmcva2V5d29yZIL2ZHJvd3Oj2BuCcGRhby5qaW5nL2tleXdvcmSCZ3Nl"
       "Z21lbnR4R2JsYWtlMy0xZWM4Y2ExYzNkYzJmZjQxZjI4Mzk5MDY0NmJmZTcxMDBjNTk1ZDNjYmY1YmEzN2NhZWU0MzVlMTA0ZGVh"
       "OWJjhNgbgnBkYW8uamluZy9rZXl3b3JkgmdzZWdtZW50eEdibGFrZTMtMWVjOGNhMWMzZGMyZmY0MWYyODM5OTA2NDZiZmU3MTAw"
       "YzU5NWQzY2JmNWJhMzdjYWVlNDM1ZTEwNGRlYTliY9gbgnBkYW8uamluZy9rZXl3b3JkgvZmbGFtYmRhgNgbgnBkYW8uamluZy9r"
       "ZXl3b3JkgmdzZWdtZW50eEdibGFrZTMtM2VhYTZkMmE2YTY2NTM4YWRiZTU5MzI0Y2JlNWI5ZTliNWYwNWRhOTQ2Zjc3OTMxODdh"
       "NTU5ZTdiNDk3MWJjYdgbgnBkYW8uamluZy9rZXl3b3JkgmdzZWdtZW50eEdibGFrZTMtM2VhYTZkMmE2YTY2NTM4YWRiZTU5MzI0"
       "Y2JlNWI5ZTliNWYwNWRhOTQ2Zjc3OTMxODdhNTU5ZTdiNDk3MWJjYYPYG4JwZGFvLmppbmcva2V5d29yZIJnc2VnbWVudHhHYmxh"
       "a2UzLTNlYWE2ZDJhNmE2NjUzOGFkYmU1OTMyNGNiZTViOWU5YjVmMDVkYTk0NmY3NzkzMTg3YTU1OWU3YjQ5NzFiY2HYG4JwZGFv"
       "Lmppbmcva2V5d29yZIL2Z2xpdGVyYWzYG4JwZGFvLmppbmcvZmxvYXQ2NEg/8AAAAAAAANgbgnBkYW8uamluZy9rZXl3b3Jkgmdz"
       "ZWdtZW50eEdibGFrZTMtN2NkYWU5ZGZlZTdmMTk4MGNjZGQ5NTQzZjI3OWQwZDdkMjBlNGE0YTRhNDRkZGExNGY4OGY1NGJhOTAw"
       "MjA0OYXYG4JwZGFvLmppbmcva2V5d29yZIJnc2VnbWVudHhHYmxha2UzLTdjZGFlOWRmZWU3ZjE5ODBjY2RkOTU0M2YyNzlkMGQ3"
       "ZDIwZTRhNGE0YTQ0ZGRhMTRmODhmNTRiYTkwMDIwNDnYG4JwZGFvLmppbmcva2V5d29yZIL2a2FwcGxpY2F0aW9u2BuCcGRhby5q"
       "aW5nL2tleXdvcmSCZ3NlZ21lbnR4R2JsYWtlMy0xZWM4Y2ExYzNkYzJmZjQxZjI4Mzk5MDY0NmJmZTcxMDBjNTk1ZDNjYmY1YmEz"
       "N2NhZWU0MzVlMTA0ZGVhOWJjgPXYG4JwZGFvLmppbmcva2V5d29yZIL2cW1hbmlmZXN0LXJldmlzaW9uZGMxLTI="))


(def ^:private legacy-node-integer-b64
  "Genuine pre-fix c1-2 Node integer-bearing artifact of `((fn [] 1.0))`
   (1,390 bytes, sha256 ffb9db68eb953ccadd681b916a8df6d262206baa055d6f91eda461d179174ef2),
   captured at 7f4898f4 before the repair."
  (str "p9gbgnBkYW8uamluZy9rZXl3b3JkgnZ5YW5nLmNsb2p1cmUuZmxvYXRraW5kZGhvc3TYG4JwZGFvLmppbmcva2V5d29yZIL2bG5v"
       "ZGUtaW50ZWdlctgbgnBkYW8uamluZy9rZXl3b3JkgnZ5YW5nLmNsb2p1cmUuZmxvYXRraW5kZmxlZ2FjefXYG4JwZGFvLmppbmcv"
       "a2V5d29yZIJ2eWFuZy5jbG9qdXJlLmZsb2F0a2luZGZzb3VyY2VtKChmbiBbXSAxLjApKdgbgnBkYW8uamluZy9rZXl3b3JkgnZ5"
       "YW5nLmNsb2p1cmUuZmxvYXRraW5ka3ByZS1maXgtcmVmbTdmNDg5OGY0IGMxLTLYG4JwZGFvLmppbmcva2V5d29yZIL2ZHJvb3TY"
       "G4JwZGFvLmppbmcva2V5d29yZIJnc2VnbWVudHhHYmxha2UzLTU0MWJhMmZmYzZiNzQzMjdmNzAwMDhkMjA2OWEzNTI3OTYxMmMy"
       "ZTk2YzgyY2MxOThkN2IwYjY3ODI1MDFlNGPYG4JwZGFvLmppbmcva2V5d29yZIL2ZHJvd3Oj2BuCcGRhby5qaW5nL2tleXdvcmSC"
       "Z3NlZ21lbnR4R2JsYWtlMy00MWJkOWNhMGM2Mzg0ODQ3YmM0ZTAyNGE3ZmZlYWU4ODg1YzkyMDY0OGFmYjFiNjlkNjIyNmZiYzFh"
       "YzE2OTllg9gbgnBkYW8uamluZy9rZXl3b3JkgmdzZWdtZW50eEdibGFrZTMtNDFiZDljYTBjNjM4NDg0N2JjNGUwMjRhN2ZmZWFl"
       "ODg4NWM5MjA2NDhhZmIxYjY5ZDYyMjZmYmMxYWMxNjk5ZdgbgnBkYW8uamluZy9rZXl3b3JkgvZnbGl0ZXJhbAHYG4JwZGFvLmpp"
       "bmcva2V5d29yZIJnc2VnbWVudHhHYmxha2UzLTU0MWJhMmZmYzZiNzQzMjdmNzAwMDhkMjA2OWEzNTI3OTYxMmMyZTk2YzgyY2Mx"
       "OThkN2IwYjY3ODI1MDFlNGOF2BuCcGRhby5qaW5nL2tleXdvcmSCZ3NlZ21lbnR4R2JsYWtlMy01NDFiYTJmZmM2Yjc0MzI3Zjcw"
       "MDA4ZDIwNjlhMzUyNzk2MTJjMmU5NmM4MmNjMTk4ZDdiMGI2NzgyNTAxZTRj2BuCcGRhby5qaW5nL2tleXdvcmSC9mthcHBsaWNh"
       "dGlvbtgbgnBkYW8uamluZy9rZXl3b3JkgmdzZWdtZW50eEdibGFrZTMtNmQ5OGE1ODljNzFiZjE2MzE0MGQ5MTNkYzBlNGFkMmQ3"
       "ZGZiODFkMzg1YmUyYTAxYjA5YzcyMGI5NjI4N2M5OID12BuCcGRhby5qaW5nL2tleXdvcmSCZ3NlZ21lbnR4R2JsYWtlMy02ZDk4"
       "YTU4OWM3MWJmMTYzMTQwZDkxM2RjMGU0YWQyZDdkZmI4MWQzODViZTJhMDFiMDljNzIwYjk2Mjg3Yzk4hNgbgnBkYW8uamluZy9r"
       "ZXl3b3JkgmdzZWdtZW50eEdibGFrZTMtNmQ5OGE1ODljNzFiZjE2MzE0MGQ5MTNkYzBlNGFkMmQ3ZGZiODFkMzg1YmUyYTAxYjA5"
       "YzcyMGI5NjI4N2M5ONgbgnBkYW8uamluZy9rZXl3b3JkgvZmbGFtYmRhgNgbgnBkYW8uamluZy9rZXl3b3JkgmdzZWdtZW50eEdi"
       "bGFrZTMtNDFiZDljYTBjNjM4NDg0N2JjNGUwMjRhN2ZmZWFlODg4NWM5MjA2NDhhZmIxYjY5ZDYyMjZmYmMxYWMxNjk5ZdgbgnBk"
       "YW8uamluZy9rZXl3b3JkgvZxbWFuaWZlc3QtcmV2aXNpb25kYzEtMg=="))


(defn- run-rows
  [rows-tree]
  (let [vm (ast-walker/vm-load-rows
             (tu/create-vm {:primitives vm/primitives
                            :make-stream tu/make-stream
                            :capability-secret tu/secret})
             rows-tree
             vm/ast-contract)]
    (vm/value (vm/run vm))))


(deftest gate6-legacy-custody-and-cross-host-artifacts
  (testing "both pre-fix binary files match their embedded portable copies"
    (let [jvm-embedded (base64/decode legacy-jvm-float-b64)
          node-embedded (base64/decode legacy-node-integer-b64)]
      (when-let [jvm-file (host-read-file "test/resources/yang/clojure/float-kind-legacy-jvm-float.cbor")]
        (is (= 1415 (bytes/length jvm-file)))
        (is (bytes/equal? jvm-embedded jvm-file)))
      (when-let [node-file (host-read-file "test/resources/yang/clojure/float-kind-legacy-node-integer.cbor")]
        (is (= 1390 (bytes/length node-file)))
        (is (bytes/equal? node-embedded node-file)))))
  (testing "pre-fix artifacts load and execute without recompilation, preserving historical meanings"
    (let [jvm-payload (cbor/decode (base64/decode legacy-jvm-float-b64))
          node-payload (cbor/decode (base64/decode legacy-node-integer-b64))
          jvm-val (run-rows (select-keys jvm-payload [:root :rows]))
          node-val (run-rows (select-keys node-payload [:root :rows]))]
      (is (= "c1-2" (:manifest-revision jvm-payload)))
      (is (= "c1-2" (:manifest-revision node-payload)))
      (is (= :segment/blake3-7cdae9dfee7f1980ccdd9543f279d0d7d20e4a4a4a44dda14f88f54ba9002049
             (:root jvm-payload)))
      (is (= :segment/blake3-541ba2ffc6b74327f70008d2069a35279612c2e96c82cc198d7b0b6782501e4c
             (:root node-payload)))
      (is (cbor/float64? jvm-val) "pre-fix JVM artifact keeps float64 meaning")
      (is (= 1.0 (data/float-value jvm-val)))
      (is (integer-host/exact-integer? node-val) "pre-fix Node artifact keeps integer meaning")
      (is (not (cbor/float64? node-val)))
      (is (data-equiv 1 node-val))
      (is (not (data-equiv jvm-val node-val)))
      (testing "decode and re-encode preserve canonical identity on every host"
        (is (cbor/bytes= (jing/canonical-bytes (select-keys jvm-payload [:root :rows]))
                         (jing/canonical-bytes (cbor/decode (jing/canonical-bytes (select-keys jvm-payload [:root :rows]))))))
        (is (cbor/bytes= (jing/canonical-bytes (select-keys node-payload [:root :rows]))
                         (jing/canonical-bytes (cbor/decode (jing/canonical-bytes (select-keys node-payload [:root :rows])))))))
      (when repl-route?
        (testing "recompiling from authoritative source under the repaired frontend yields the float64 root on both JVM and Node"
          (let [recompiled (vm/ast->semantic-bytecode
                             (yang/compile-program (yang/read-forms "((fn [] 1.0))")))]
            (is (= (:root jvm-payload) (:root recompiled))
                "repaired compilation matches the float64 root")
            (is (not= (:root node-payload) (:root recompiled))
                "repaired compilation is distinct from the pre-fix Node integer artifact")
            (let [v (run-rows recompiled)]
              (is (cbor/float64? v))
              (is (= 1.0 (data/float-value v))))))))))


;; =============================================================================
;; GATE 7 - profiles, revisions, capabilities, and unchanged contracts
;; =============================================================================

(deftest gate7-repaired-manifest-revisions-and-capabilities
  (testing "repaired Clojure manifest declares the new revision, profiles, and accurate host capability"
    (let [m (frontends/clojure-manifest)]
      (is (= :yang/clojure (:yang.frontend/id m)))
      (is (= "c1-3" (:yang.frontend/revision m)))
      (is (= :yang.clojure/constructed-collections-float-kind-v1
             (:yang.frontend/lowering-profile m)))
      (is (= :yang.clojure/c1-float-kind (:yang.frontend/support-profile m)))
      (is (= #?(:jolt :forms-only
                :cljd :forms-only
                :clj :source-text
                :cljs :source-text
                :default :forms-only)
             (get-in m [:yang.frontend/grammar :yang.grammar/capability]))))
    (when-not repl-route?
      (testing "forms-only hosts refuse direct source-string reading honestly"
        (is (= #?(:jolt :unavailable-on-jolt :cljd :unavailable-on-cljd :default nil)
               (try
                 (yang/read-forms "1.0")
                 nil
                 (catch #?(:cljd Object :cljs :default :default Exception) e
                   (:yang.clojure/reader (ex-data e)))))))))
  (testing "C1 marker version and Jing/UAST contracts are unchanged"
    (is (= 1 yang/call-site-version))
    (is (= :yang/call-site yang/call-site-key))
    (is (= 1 (:yang.frontend/spi (frontends/clojure-manifest)))))
  (testing "other frontends' revisions in the standard catalog are untouched"
    (let [catalog (frontends/standard)]
      (is (frontend/ok? (frontend/select catalog {:yang.frontend/id :yang/clojure
                                                  :yang.frontend/revision "c1-3"})))
      (when (seq (frontend/revisions catalog :yang/python))
        (is (= ["clojure-core-v1" "py-v1"] (frontend/revisions catalog :yang/python))))
      (when (seq (frontend/revisions catalog :yang/php))
        (is (= ["antlr-p8"] (frontend/revisions catalog :yang/php)))))))
