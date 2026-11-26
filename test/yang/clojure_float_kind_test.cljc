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
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.host.integer :as integer-host]
            [dao.stream :as stream]
            [dao.stream.base64 :as base64]
            [yang.clojure :as yang]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.data :as data]
            [yin.vm.module :as module]
            [yin.vm.test-utils :as tu]
            #?@(:clj [clojure.java.io :as io]
                :cljs [[yin.repl :as repl]
                       [yin.repl.frontends :as frontends]])))


;; =============================================================================
;; Shared helpers
;; =============================================================================


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
  #?(:clj (java.nio.file.Files/readAllBytes (.toPath (io/file path)))
     :cljs (.readFileSync (js/require "fs") path)
     :default nil))


(def ^:private repl-route?
  "The REPL/catalog route runs where the shell runs; the Dart and Jolt legs
  of this namespace exercise artifacts and capability claims only."
  #?(:clj true :cljs true :default false))


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
  #?(:clj (drain-result (first (repl/eval-input (frontends/create-state {:vm-type :semantic}) line)))
     :cljs (drain-result (first (repl/eval-input (frontends/create-state {:vm-type :semantic}) line)))
     :default nil))


(defn- repl-text
  "The shell text of evaluating `line` through the standard route."
  [line]
  #?(:clj (second (repl/eval-input (frontends/create-state {:vm-type :semantic}) line))
     :cljs (second (repl/eval-input (frontends/create-state {:vm-type :semantic}) line))
     :default nil))


(defn- repl-error?
  [line]
  (str/starts-with? (str (repl-text line)) "Error:"))


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
   ["9007199254740993.0" "4340000000000200"]
   ["1e19" "43e158e460913d000"]
   ["1.5e-5" "3eeb48116539a35a"]
   ["5e-324" "0000000000000001"]
   ["1e-400" "0000000000000000"]
   ["2." "4000000000000000"]
   ["+1.5" "3ff8000000000000"]
   ["1E5" "412e848000000000"]])


(def ^:private integer-tokens
  "Integer controls: plain, signed, radix, octal, and the N-suffixed big
   integer (the exact carrier). [token expected-value]."
  [["0" 0] ["1" 1] ["-1" -1] ["+7" 7] ["42" 42]
   ["0x10" 16] ["017" 15] ["2r101" 5] ["36rZZ" 1295]
   ["9007199254740991" 9007199254740991]])


(defn- host-bits-of
  "The IEEE-754 bits of float64 content `x`, 16 lowercase hex digits."
  [x]
  #?(:clj (String/format "%016x" (into-array Object [(Double/doubleToLongBits (double (data/float-value x)))]))
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
  (testing "every binary-floating token reads and compiles as float64 content"
    (doseq [[token bits] float-tokens]
      (testing token
        (let [value (compiled-value token)]
          (is (cbor/float64? value)
              (str token " must be float64 content, read " (pr-str value)))
          (is (= bits (host-bits-of value))
              (str token " must carry exactly these IEEE bits")))))))


(deftest gate1-integer-tokens-stay-exact-integers
  (testing "integer controls keep integer kind and value"
    (doseq [[token expected] integer-tokens]
      (testing token
        (let [value (compiled-value token)]
          (is (integer-host/exact-integer? value)
              (str token " must be exact-integer content, read " (pr-str value)))
          (is (data/equiv expected value)
              (str token " must read as " expected))))))
  (testing "big integers keep the exact big carrier"
    (let [value (compiled-value "9223372036854775808000N")]
      (is (integer-host/big-carrier? value)
          "an N-suffixed token is exact big-integer content, never a rounded number")
      (is (data/equiv 9223372036854775808000 value))
      (is (not (cbor/float64? value))))))


(deftest gate1-unsupported-categories-not-relabeled
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
          (str token " must be a reader refusal")))))


(def ^:private token-digest-reference
  "The recorded cross-host reference: the content hash of the canonical
   bytes of each token's read form, JVM-recorded under the repaired reader
   during the green round; every host must reproduce it exactly."
  {"0.0" "unrecorded"
   "1.0" "unrecorded"
   "1" "unrecorded"
   "1/2" "unrecorded"
   "1M" "unrecorded"})


(deftest gate1-cross-host-canonical-identity
  (testing "identical source mints identical canonical bytes on every host"
    (doseq [[token expected] token-digest-reference]
      (testing token
        (is (not= "unrecorded" expected) "reference recorded during the green round")
        (is (= expected (jing/content-hash (read-one token)))))))
  (testing "the float64 and integer tokens of one value mint distinct bytes"
    (is (not= (semantic-root "1") (semantic-root "1.0")))
    (is (not= (semantic-root "0") (semantic-root "-0.0")))))


;; =============================================================================
;; GATE 2 - structural and lexical preservation
;; =============================================================================


(deftest gate2-floats-preserve-through-structure
  (testing "every structural position keeps the token's kind"
    (let [let-form (read-one "(let [x 1.0] x)")
          positions {"top level" (read-one "1.0")
                     "let binding" (nth (nth let-form 2) 1)
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
    (is (cbor/float64? (compiled-value "(quote [1.0 1])")))
    (is (cbor/float64? (compiled-value "[1.0]")))
    (is (integer-host/exact-integer?
          (second (:value (yang/compile (read-one "[1.0 2]"))))))))
  (testing "repeated equal-valued tokens keep one occurrence each"
    (let [ast (yang/compile (read-one "(quote [0.0 0.0 0.0])"))]
      (is (= 3 (count (:value ast)))))
    (let [ast (yang/compile (read-one "(f 1.0 1.0 1.0)"))]
      (is (= 3 (count (:operands ast))))
      (is (every? cbor/float64? (map :value (:operands ast)))))))


(deftest gate2-lexical-contexts-are-not-floats
  (testing "numeric-looking text in non-numeric lexical contexts stays itself"
    (is (= "1.0" (read-one (str (char 34) "1.0" (char 34)))) "string content")
    (is (= 42 (read-one ";; 1.0 is a float in a comment\n42")) "comment skipped")
    (is (= 7 (read-one "#_1.0 7")) "discarded form skipped")
    (is (= 8 (read-one "#_[1.0 #_{2.0} 3.0] 8")) "nested discard skipped")
    (is (= 42 (read-one "1.0 #_ (+ 1.0 2.0) 42")) "discard of a whole expression"))
  (testing "metadata reads and attaches"
    (is (= {:m true} (meta (read-one "^:m [1.0]"))))
    (is (= {:a 1} (meta (read-one "^{:a 1} x")))))
  (testing "reader conditionals keep the explicit refusal"
    (is (thrown? #?(:clj Exception :cljs js/Error :default Exception)
                 (yang/read-forms "#?(:clj 1 :cljs 2)")))
    (is (thrown? #?(:clj Exception :cljs js/Error :default Exception)
                 (yang/read-forms "#?@(:clj [1])")))))


(deftest gate2-equal-valued-occurrences-not-coalesced
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
      (is (= 1 (count (filter cbor/float64? (keys (:value ast)))))))))


(deftest gate2-float-key-policy-parity
  (when repl-route?
    (testing "constant float data is quoted data; dynamic float keys refuse alike"
      (is (cbor/float64? (compiled-value "{1.0 :a}"))
          "a constant map literal with a float key is quoted data")
      (is (repl-error? "(assoc {} 1.0 :a)")
          "a dynamic float key hits the collection-key refusal on this host"))))


(deftest gate2-macro-generated-numeric-data
  (when repl-route?
    (testing "a macro returning a source float keeps its kind through expansion"
      (is (cbor/float64? (repl-session-result "(do (defmacro fm [] 1.0) (fm))"))))))


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
  (testing "inc/dec of floating input take the floating branch"
    (let [one-up (run-library '[(inc 1.0)])
          one-down (run-library '[(dec 2.0)])]
      (is (cbor/float64? one-up) "inc of 1.0 returns float kind")
      (is (= 2.0 (data/float-value one-up)))
      (is (cbor/float64? one-down))
      (is (= 1.0 (data/float-value one-down)))))
  (testing "integer input keeps integer behavior"
    (let [v (run-library '[(inc 1)])]
      (is (integer-host/exact-integer? v))
      (is (data/equiv 2 v))
      (is (not (cbor/float64? v))))))


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
    (is (= "0.0" (repl-text "-0.0")) "signed zero renders signed")))


(deftest gate4-unboxed-control-distinguishes
  "The control proving the oracle catches dropped boxing: the same value
   compiled from an unboxed host literal instead of the token-preserving
   reader must answer the INTEGER behavior on the host where a bare
   integral Number is an integer (JS), while the repaired source path
   answers float. The collapsed path cannot pass this oracle."
  (testing "the unboxed control"
    #?(:cljs
       (let [unboxed (run-ast (yang/compile 1.0))
             boxed (run-ast (yang/compile (read-one "1.0")))]
         (is (integer-host/exact-integer? unboxed)
             "an unboxed integral literal on JS IS integer content - the defect")
         (is (cbor/float64? boxed) "the source path answers float kind")
         (is (not (data/equiv unboxed boxed)))
         (is (not= (:root (vm/ast->semantic-bytecode (yang/compile 1.0)))
                   (semantic-root "1.0"))
             "the digest gate distinguishes them too"))
       :clj
       (let [direct (run-ast (yang/compile 1.0))
             sourced (run-ast (yang/compile (read-one "1.0")))]
         (is (cbor/float64? direct) "a JVM Double is float content either way")
         (is (data/equiv direct sourced))
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
    (is (= (jing/canonical-bytes (data/float64 1))
           (jing/canonical-bytes (:value (yang/compile (data/float64 1)))))
        "the compiled literal serializes to the carrier's canonical bytes")))


(def carrier-root-reference
  "The recorded cross-host root of the compiled explicit-carrier form
  ((data/float64 1) as the whole program): every forms-capable host - JVM,
  Node, Dart, Jolt - compiles the SAME carrier form to the SAME root.
  Recorded during the green round."
  "unrecorded")


(deftest gate5-carrier-forms-one-root-everywhere
  (let [root (:root (vm/ast->semantic-bytecode (yang/compile (data/float64 1))))]
    (is (not= "unrecorded" carrier-root-reference))
    (is (= carrier-root-reference (str root)))
    (is (not= root (:root (vm/ast->semantic-bytecode (yang/compile 1))))
        "the carrier root differs from the integer control's root")))


(deftest gate5-parked-float-result-crosses-the-round
  "A float-producing operation whose result is integral must keep float
   kind across the parked-round boundary (the shell's continuation
   custody of a blocked VM): the program blocks on its own stream with
   the addition pending, a later put! resumes it, and the answer 2.0 is
   float content, not the integer 2."
  (when repl-route?
    (let [lines ["(def e (stream/make 2))"
                 "(+ (stream/next! (stream/cursor e)) 0.5)"
                 "(stream/put! e 1.5)"]
          [state texts]
          (reduce (fn [[state texts] line]
                    (let [[state' text] (repl/eval-input state line)]
                      [state' (conj texts text)]))
                  [(frontends/create-state {:vm-type :semantic}) []]
                  lines)
          resumed (drain-result state)]
      (is (str/starts-with? (str (second texts)) "Error:"))
      (is (str/includes? (str (second texts)) "runnable"))
      (is (= "2.0" (last texts)))
      (is (cbor/float64? resumed)
          "the integral float result survives the parked round as float kind")
      (is (= 2.0 (data/float-value resumed))))))


#?(:clj
   (defn- integral-double-leaves
     "Every integral Double constant in host-quoted `form` data (the JVM is
     the host that can still see the Double/integer distinction)."
     [form]
     (let [acc (atom [])]
       (clojure.walk/postwalk
         (fn [x]
           (when (and (instance? Double x) (== x (Math/round (double x))))
             (swap! acc conj x))
           x)
         form)
       @acc)))


#?(:clj
   (deftest gate5-quoted-prelude-audit-no-integral-doubles
     "The JVM-side producer audit: the quoted prelude tables that build the
     clj/clojure.core.impl/clj.py runtime modules carry no bare integral
     Double constant a JS host would silently encode as an integer. Floats
     in prose are non-integral or explicitly constructed."
     (let [producers [(str "clj runtime definitions")
                      (concat @(resolve 'yang.clojure.prelude/definitions)
                              @(resolve 'yang.clojure.prelude/source-definitions))
                      (str "clojure.core.impl operations")
                      @(resolve 'yang.clojure.core-impl/operations)
                      (str "clj.py numeric table")
                      @(resolve 'yang.python.antlr.clj-runtime/numeric)]]
       (doseq [[label forms] (partition 2 producers)]
         (testing label
           (is (= [] (integral-double-leaves forms))
               (str label " quotes no integral Double constant")))))))
