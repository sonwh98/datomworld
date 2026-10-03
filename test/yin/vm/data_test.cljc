(ns yin.vm.data-test
  "The `data` host module: pure collection and string primitives, code
   point indexed strings, qualified refusals, composition-only
   installation, and every export reached through the module path on all
   four VMs -- the AST walker, the semantic VM, and the de Bruijn stack
   and register VMs."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]
            [yin.vm.values :as values]))


;; =============================================================================
;; Direct calls
;; =============================================================================

(defn- call
  [op & args]
  (apply (get data/data-module op) args))


(defn- refusal
  "`[message ex-data]` of the refusal `(apply call op args)` throws, or
   `[:returned value]`."
  [op & args]
  (try
    [:returned (apply call op args)]
    (catch #?(:cljd Object :clj Exception :cljs :default) e
      [(ex-message e) (ex-data e)])))


(defn- refused
  [op reason data]
  [data/refusal-message
   (merge {::data/op op, ::data/reason reason} data)])


(defn- wrong-type
  [op arg expected]
  (refused op :wrong-type {::data/arg arg, ::data/expected expected}))


(defn- out-of-range
  [op i lo hi]
  (refused op :out-of-range {::data/index i, ::data/lo lo, ::data/hi hi}))


(defn- from-units
  "A string built at run time from UTF-16 code units by the host itself,
   independent of the module. A lone surrogate never appears as a source
   literal: the CLJD build writes its generated Dart source as UTF-8, where
   a lone surrogate cannot be encoded and becomes \"?\"."
  [& units]
  (apply str
         (map (fn [u]
                #?(:cljd (dart:core/String.fromCharCode u)
                   :clj (str (char u))
                   :cljs (.fromCharCode js/String u)))
              units)))


(defn- host-unit-count
  [s]
  #?(:cljd (.-length ^String s)
     :clj (.length ^String s)
     :cljs (.-length s)))


(def ^:private grin
  "U+1F600, outside the BMP: two UTF-16 code units, one code point."
  "😀")


;; =============================================================================
;; Collections
;; =============================================================================

(deftest count-test
  (is (= 0 (call 'count nil)))
  (is (= 0 (call 'count [])))
  (is (= 3 (call 'count [1 2 3])))
  (is (= 2 (call 'count {:a 1, :b 2})))
  (is (= 2 (call 'count #{:a :b})))
  (testing "a string is not a collection: str-length counts code points"
    (is (= (wrong-type 'count 0 :collection) (refusal 'count "ab"))))
  (is (= (wrong-type 'count 0 :collection) (refusal 'count 7))))


(deftest nth-test
  (is (= :b (call 'nth [:a :b :c] 1)))
  (is (= :b (call 'nth [:a :b :c] 1.0)) "an integral double is an index")
  (is (= (out-of-range 'nth 3 0 2) (refusal 'nth [:a :b :c] 3)))
  (is (= (out-of-range 'nth -1 0 2) (refusal 'nth [:a :b :c] -1)))
  (is (= (out-of-range 'nth 0 0 -1) (refusal 'nth [] 0)))
  (is (= (wrong-type 'nth 1 :integer) (refusal 'nth [:a] 0.5)))
  (is (= (wrong-type 'nth 1 :integer) (refusal 'nth [:a] :k)))
  (testing "the index guard: non-finite and unsafe integers are no index"
    (doseq [x [(/ 0.0 0.0) (/ 1.0 0.0) (/ -1.0 0.0)
               9007199254740992 -9007199254740992]]
      (is (= (wrong-type 'nth 1 :integer) (refusal 'nth [:a] x)) (str x)))
    (is (= (out-of-range 'nth 9007199254740991 0 0)
           (refusal 'nth [:a] 9007199254740991))
        "2^53 - 1 is still an index"))
  (is (= (wrong-type 'nth 0 :vector) (refusal 'nth nil 0)))
  (is (= (wrong-type 'nth 0 :vector) (refusal 'nth "abc" 0))))


(deftest contains?-test
  (is (true? (call 'contains? {:a nil} :a)))
  (is (false? (call 'contains? {:a 1} :b)))
  (is (true? (call 'contains? #{nil} nil)))
  (is (false? (call 'contains? nil :a)))
  (testing "a vector contains its indices"
    (is (true? (call 'contains? [:x :y] 1)))
    (is (true? (call 'contains? [:x :y] 1.0)))
    (is (false? (call 'contains? [:x :y] 2)))
    (is (false? (call 'contains? [:x :y] -1)))
    (is (false? (call 'contains? [:x :y] :x))))
  (is (= (wrong-type 'contains? 0 :map-set-or-vector)
         (refusal 'contains? "ab" 0))))


(deftest dissoc-disj-test
  (is (= {:b 2} (call 'dissoc {:a 1, :b 2} :a)))
  (is (= {} (call 'dissoc {:a 1, :b 2} :a :b :c)))
  (is (= {:a 1} (call 'dissoc {:a 1})))
  (is (nil? (call 'dissoc nil :a)))
  (is (= (wrong-type 'dissoc 0 :map) (refusal 'dissoc [1] 0)))
  (is (= #{2} (call 'disj #{1 2} 1)))
  (is (= #{} (call 'disj #{1 2} 1 2 3)))
  (is (nil? (call 'disj nil 1)))
  (is (= (wrong-type 'disj 0 :set) (refusal 'disj {:a 1} :a))))


(deftest peek-pop-test
  (is (= 3 (call 'peek [1 2 3])))
  (is (nil? (call 'peek [])))
  (is (nil? (call 'peek nil)))
  (is (= [1 2] (call 'pop [1 2 3])))
  (is (= [] (call 'pop [1])))
  (is (nil? (call 'pop nil)))
  (is (= (out-of-range 'pop -1 0 -1) (refusal 'pop [])))
  (is (= (wrong-type 'peek 0 :vector) (refusal 'peek '(1 2))))
  (is (= (wrong-type 'pop 0 :vector) (refusal 'pop #{1}))))


(deftest subvec-test
  (is (= [2 3] (call 'subvec [1 2 3] 1)))
  (is (= [2] (call 'subvec [1 2 3] 1 2)))
  (is (= [] (call 'subvec [1 2 3] 3)))
  (is (= [] (call 'subvec [] 0 0)))
  (is (vector? (call 'subvec [1 2 3] 1)))
  (is (= (out-of-range 'subvec 4 0 3) (refusal 'subvec [1 2 3] 0 4)))
  (is (= (out-of-range 'subvec 2 0 1) (refusal 'subvec [1 2 3] 2 1)))
  (is (= (out-of-range 'subvec -1 0 3) (refusal 'subvec [1 2 3] -1)))
  (is (= (wrong-type 'subvec 0 :vector) (refusal 'subvec nil 0))))


(deftest hash-set-into-test
  (is (= #{} (call 'hash-set)))
  (is (= #{1 2} (call 'hash-set 1 2 2)))
  (is (= [1 2 3] (call 'into [1] [2 3])))
  (is (= [1] (call 'into [1] nil)))
  (is (= #{1 2 3} (call 'into #{1} [2 3])))
  (is (= #{1 2 3} (call 'into #{1} #{2 3})))
  (testing "no host set or map is iterated into an ordered result"
    (is (= (wrong-type 'into 1 :sequential) (refusal 'into [] #{1 2})))
    (is (= (wrong-type 'into 1 :sequential) (refusal 'into [] {:a 1})))
    (is (= (wrong-type 'into 1 :sequential-or-set)
           (refusal 'into #{} {:a 1}))))
  (is (= (wrong-type 'into 0 :vector-or-set) (refusal 'into {} [[:a 1]])))
  (is (= (wrong-type 'into 0 :vector-or-set) (refusal 'into nil [1]))))


;; =============================================================================
;; Strings, code point indexed
;; =============================================================================

(deftest non-bmp-code-points-test
  (testing "one code point per supplementary character on every host"
    (is (= 2 (call 'str-length (str "a" grin))))
    (is (= [97 0x1F600] (call 'str->code-points (str "a" grin))))
    (is (= (str "a" grin) (call 'code-points->str [97 0x1F600])))
    (is (= grin (call 'char-at (str "a" grin "b") 1)))
    (is (= "b" (call 'char-at (str "a" grin "b") 2)))
    (is (= (str grin "b") (call 'substring (str "a" grin "b") 1)))
    (is (= 2 (call 'str-index-of (str "a" grin "b") "b")))
    (is (= [(str "a" grin) "c"] (call 'str-split (str "a" grin "b" "c") "b")))
    (is (= ["a" "b"] (call 'str-split (str "a" grin "b") grin))))
  (testing "code point order, not UTF-16 unit order"
    (is (= 1 (call 'str-compare grin "￿")))
    (is (= -1 (call 'str-compare "￿" grin))))
  (testing "an unpaired surrogate is its own code point"
    (let [lone-high (from-units 0xD800 97)
          lone-low (from-units 0xDE00)]
      (is (= 2 (host-unit-count lone-high)) "the input really is two units")
      (is (= [0xD800 97] (call 'str->code-points lone-high)))
      (is (= 2 (call 'str-length lone-high)))
      (is (= [0xDE00] (call 'str->code-points lone-low)))
      (is (= [0xD800] (call 'str->code-points (call 'char-at lone-high 0))))
      (is (= [0xDE00 0xD800]
             (call 'str->code-points
                   (call 'code-points->str [0xDE00 0xD800])))
          "reversed lone surrogates stay two code points")))
  (testing "code points round-trip"
    (let [cps [0 97 0xFFFF 0x10000 0x1F600 0x10FFFF]]
      (is (= cps (call 'str->code-points (call 'code-points->str cps)))))))


(deftest str-concat-length-test
  (is (= "" (call 'str-concat)))
  (is (= "abc" (call 'str-concat "a" "" "bc")))
  (is (= (wrong-type 'str-concat 1 :string) (refusal 'str-concat "a" 1)))
  (is (= (wrong-type 'str-concat 0 :string) (refusal 'str-concat nil)))
  (is (= 0 (call 'str-length "")))
  (is (= (wrong-type 'str-length 0 :string) (refusal 'str-length nil))))


(deftest substring-char-at-test
  (is (= "" (call 'substring "" 0)))
  (is (= "" (call 'substring "abc" 3)))
  (is (= "bc" (call 'substring "abc" 1 3)))
  (is (= "" (call 'substring "abc" 2 2)))
  (is (= (out-of-range 'substring 4 0 3) (refusal 'substring "abc" 0 4)))
  (is (= (out-of-range 'substring 2 0 1) (refusal 'substring "abc" 2 1)))
  (is (= (out-of-range 'substring 4 0 3) (refusal 'substring "abc" 4)))
  (is (= (wrong-type 'substring 1 :integer) (refusal 'substring "abc" nil)))
  (is (= "a" (call 'char-at "abc" 0)))
  (is (= (out-of-range 'char-at 3 0 2) (refusal 'char-at "abc" 3)))
  (is (= (out-of-range 'char-at 0 0 -1) (refusal 'char-at "" 0)))
  (is (= (wrong-type 'char-at 0 :string) (refusal 'char-at [1] 0))))


(deftest str-index-of-test
  (is (= 1 (call 'str-index-of "abcb" "b")))
  (is (= 3 (call 'str-index-of "abcb" "b" 2)))
  (is (nil? (call 'str-index-of "abc" "z")))
  (is (nil? (call 'str-index-of "" "a")))
  (is (= 0 (call 'str-index-of "abc" "")))
  (is (= 3 (call 'str-index-of "abc" "" 3)))
  (is (= (out-of-range 'str-index-of 4 0 3) (refusal 'str-index-of "abc" "" 4)))
  (is (= (wrong-type 'str-index-of 1 :string) (refusal 'str-index-of "a" nil))))


(deftest str-split-join-test
  (testing "literal separator; every empty field is kept"
    (is (= ["a" "" "b" ""] (call 'str-split "a,,b," ",")))
    (is (= [""] (call 'str-split "" ",")))
    (is (= ["a.b"] (call 'str-split "a.b" "x")))
    (is (= ["a" "b"] (call 'str-split "a.b" "."))))
  (is (= (refused 'str-split :empty-separator {}) (refusal 'str-split "a" "")))
  (is (= "a, b" (call 'str-join ", " ["a" "b"])))
  (is (= "" (call 'str-join "," [])))
  (is (= "" (call 'str-join "," nil)))
  (is (= (wrong-type 'str-join 1 :strings) (refusal 'str-join "," ["a" 1])))
  (is (= (wrong-type 'str-join 1 :sequential) (refusal 'str-join "," #{"a"})))
  (is (= (wrong-type 'str-join 0 :string) (refusal 'str-join nil ["a"]))))


(deftest code-points-str-compare-test
  (is (= [] (call 'str->code-points "")))
  (is (= "" (call 'code-points->str [])))
  (is (= "A" (call 'code-points->str [65.0])))
  (is (= (wrong-type 'code-points->str 0 :code-points)
         (refusal 'code-points->str [0x110000])))
  (is (= (wrong-type 'code-points->str 0 :code-points)
         (refusal 'code-points->str [-1])))
  (is (= (wrong-type 'code-points->str 0 :code-points)
         (refusal 'code-points->str ["a"])))
  (is (= (wrong-type 'code-points->str 0 :sequential)
         (refusal 'code-points->str "a")))
  (is (= 0 (call 'str-compare "" "")))
  (is (= -1 (call 'str-compare "" "a")))
  (is (= -1 (call 'str-compare "ab" "b")))
  (is (= 1 (call 'str-compare "b" "ab")))
  (is (= 1 (call 'str-compare "ab" "a")))
  (is (= (wrong-type 'str-compare 1 :string) (refusal 'str-compare "a" nil))))


(deftest number?-callable?-test
  (let [closure (values/closure nil {:type :closure, :params [], :env {}})
        k (values/continuation nil {:type :reified-continuation})]
    (is (true? (call 'number? 1)))
    (is (true? (call 'number? 1.5)))
    (doseq [x [nil true "1" :n {} [] closure k
               {:type :closure, :params [], :env {}}]]
      (is (false? (call 'number? x)) (pr-str x)))
    (is (true? (call 'callable? inc)) "a host function")
    (is (true? (call 'callable? closure)))
    (is (true? (call 'callable? k)))
    (doseq [x [nil 1 "f" :f {} [] {:type :closure, :params [], :env {}}
               {:type :reified-continuation}]]
      (is (false? (call 'callable? x)) (pr-str x)))
    (is (= (refused 'number? :arity {::data/argc 2}) (refusal 'number? 1 2)))))


(deftest float-seam-test
  (testing "float64 is Jing float64 content, kind kept for an integral
            value on every host; float-value is the host double back"
    (is (cbor/float64? (call 'float64 1)))
    (is (= (jing/segment-key (cbor/float64 1))
           (jing/segment-key (call 'float64 1))))
    (is (not= (jing/segment-key 1) (jing/segment-key (call 'float64 1))))
    (is (number? (call 'float-value (cbor/float64 2.5))))
    (is (== 2.5 (call 'float-value (cbor/float64 2.5))))
    (is (== 2 (call 'float-value 2)))
    (is (= (wrong-type 'float-value 0 :number) (refusal 'float-value "2"))))
  (testing "numeric-key: integral values within 2^53 - 1 key as the integer,
            -0.0 as 0, anything else as float64 content"
    (is (= 1 (call 'numeric-key (cbor/float64 1))))
    (is (= 1 (call 'numeric-key 1)))
    (is (= 0 (call 'numeric-key (cbor/float64 (* -1.0 0.0)))))
    (is (= "AA==" (jing/bytes->base64
                    (jing/canonical-bytes (call 'numeric-key (* -1.0 0.0))))))
    (is (= (cbor/float64 0.5) (call 'numeric-key 0.5)))
    (is (= (jing/segment-key (cbor/float64 9007199254740992))
           (jing/segment-key
             (call 'numeric-key (cbor/float64 9007199254740992)))))))


(deftest arity-refusal-test
  (is (= (refused 'count :arity {::data/argc 0}) (refusal 'count)))
  (is (= (refused 'nth :arity {::data/argc 3}) (refusal 'nth [1] 0 :x)))
  (is (= (refused 'dissoc :arity {::data/argc 0}) (refusal 'dissoc)))
  (is (= #{1 2 3} (call 'hash-set 1 2 3)) "variadic from 0"))


;; =============================================================================
;; Composition
;; =============================================================================

(deftest every-export-is-pure-test
  (is (= #{'count 'nth 'contains? 'dissoc 'disj 'peek 'pop 'subvec 'hash-set
           'into 'str-concat 'str-length 'substring 'str-index-of 'str-split
           'str-join 'char-at 'str->code-points 'code-points->str
           'str-compare 'number? 'float64 'float-value 'numeric-key
           'callable?}
         (set (keys data/data-module))
         (set (keys data/data-profiles))))
  (doseq [[sym profile] data/data-profiles]
    (is (= :pure (:yin.k/class profile)) (str sym))
    (is (= #{} (:yin.k/effects profile)) (str sym))
    (is (= :none (:yin.k/host-state profile)) (str sym))))


(deftest installed-only-by-composition-test
  (testing "no data export is a standard primitive"
    (is (not-any? #(contains? vm/primitives %) (keys data/data-module)))
    (let [data-fns (set (vals data/data-module))]
      (is (not-any? #(contains? data-fns (vm/primitive-function %))
                    (vals vm/primitives))))
    (is (nil? (module/resolve-module (module/default-registry) 'data))))
  (testing "register-data-module installs it under `data`"
    (let [r (data/register-data-module (module/default-registry))]
      (is (some? (module/resolve-module r 'data)))
      (is (identical? (get data/data-module 'count)
                      (module/resolve-module r 'data.count)))
      (is (= #{} (get-in r [:callable-effects
                            (get data/data-module 'str-split)]))))))


;; =============================================================================
;; The four VMs
;; =============================================================================

(def ^:private base-opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives})


(def ^:private load-semantic-ast
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(def ^:private runners
  {:ast-walker (fn [opts ast] (vm/eval (tu/create-vm opts) ast)),
   :semantic (fn [opts ast]
               (vm/run (load-semantic-ast (semantic/create-vm opts)
                                          (vm/ast->datoms ast)))),
   :stack (fn [opts ast]
            (vm/run (dvm/create-vm (:image (dl/adapt (vm/ast->datoms ast)))
                                   (assoc opts :contract vm/stack-contract)))),
   :register (fn [opts ast]
               (vm/run (rvm/create-vm
                         (:image (rc/adapt (second (vm/ast->datoms-with-root
                                                     ast))))
                         (assoc opts :contract vm/register-contract))))})


(defn- on-every-vm
  "`[vm-key halted-vm]` for each VM; a throw becomes
   `[:thrown message ex-data]`."
  [opts ast]
  (into {}
        (map (fn [[k run]]
               [k (try (run (merge base-opts opts) ast)
                       (catch #?(:cljd Object :clj Exception :cljs :default) e
                         [:thrown (ex-message e) (ex-data e)]))]))
        runners))


(def ^:private with-data
  {:modules (data/register-data-module (module/default-registry))})


(defn- lit
  [x]
  {:type :literal, :value x})


(defn- v
  [sym]
  {:type :variable, :name sym})


(defn- app
  [op & args]
  {:type :application, :operator op, :operands (vec args)})


(defn- d
  "A call of export `sym` of the `data` module."
  [sym & args]
  (apply app (v (symbol "data" (name sym))) args))


(deftest program-runs-on-every-vm-test
  (testing "every export through the module path"
    (doseq [[ast expected]
            [[(d 'count (lit [1 2 3])) 3]
             [(d 'nth (lit [:a :b]) (lit 1)) :b]
             [(d 'contains? (lit {:a 1}) (lit :a)) true]
             [(d 'dissoc (lit {:a 1, :b 2}) (lit :a)) {:b 2}]
             [(d 'disj (lit #{1 2}) (lit 1)) #{2}]
             [(d 'peek (lit [1 2])) 2]
             [(d 'pop (lit [1 2])) [1]]
             [(d 'subvec (lit [1 2 3]) (lit 1) (lit 2)) [2]]
             [(d 'hash-set (lit 1) (lit 2)) #{1 2}]
             [(d 'into (lit [1]) (lit [2])) [1 2]]
             [(d 'str-concat (lit "a") (lit grin)) (str "a" grin)]
             [(d 'str-length (lit (str "a" grin))) 2]
             [(d 'substring (lit (str "a" grin "b")) (lit 1) (lit 2)) grin]
             [(d 'str-index-of (lit (str grin "b")) (lit "b")) 1]
             [(d 'str-split (lit "a,b") (lit ",")) ["a" "b"]]
             [(d 'str-join (lit "-") (lit ["a" "b"])) "a-b"]
             [(d 'char-at (lit (str grin "b")) (lit 0)) grin]
             [(d 'str->code-points (lit grin)) [0x1F600]]
             [(d 'code-points->str (lit [0x1F600])) grin]
             [(d 'str-compare (lit grin) (lit "￿")) 1]
             [(d 'number? (lit 7)) true]
             [(d 'number? (lit {:type :closure})) false]
             [(d 'number? {:type :lambda, :params [], :body (lit 1)}) false]
             [(d 'callable? {:type :lambda, :params [], :body (lit 1)}) true]
             [(d 'callable? (v '+)) true]
             [(d 'callable? (lit {:type :closure})) false]]]
      (doseq [[k result] (on-every-vm with-data ast)]
        (is (= expected (vm/value result)) (str k " " (:operator ast))))))
  (testing "a composed program: the length of the second split field"
    (doseq [[k result] (on-every-vm
                         with-data
                         (d 'str-length
                            (d 'nth
                               (d 'str-split (lit (str "x," grin grin ",y"))
                                  (lit ","))
                               (lit 1))))]
      (is (= 2 (vm/value result)) (str k))))
  (testing "a refusal reaches the caller as the same qualified data"
    (doseq [[k result] (on-every-vm with-data
                                    (d 'nth (lit [1]) (lit 5)))]
      (is (= (into [:thrown] (out-of-range 'nth 5 0 0)) result) (str k)))))


(deftest absent-without-registration-on-every-vm-test
  (doseq [[k result] (on-every-vm {:modules (module/default-registry)}
                                  (d 'count (lit [1])))]
    (is (= :thrown (first result)) (str k))
    (is (= 'data/count (:symbol (nth result 2))) (str k))))


(deftest effect-shaped-map-is-data-test
  (testing "a :pure data export returning an effect-shaped map returns it
            as data: the D4 profile check passes it and nothing is stored"
    (doseq [[k result] (on-every-vm
                         with-data
                         (d 'dissoc
                            (lit {:effect :vm/store-put,
                                  :key 'kd,
                                  :val 1,
                                  :extra 2})
                            (lit :extra)))]
      (is (= {:effect :vm/store-put, :key 'kd, :val 1} (vm/value result))
          (str k))
      (is (not (contains? (vm/store result) 'kd)) (str k)))))
