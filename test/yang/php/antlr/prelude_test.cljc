(ns yang.php.antlr.prelude-test
  "The PHP prelude's static contract on every host: the bundled program is
   closed over its own definitions, the declared host names and the
   primitives; it never reaches `dao.host.*`; the lowered program names no
   runtime key; the module emitter's exports and manifest cover the same
   list."
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [clojure.test :refer [deftest is testing]]
    [yang.core.uast :as u]
    [yang.php.antlr.lower :as lower]
    [yang.php.antlr.packets :as packets]
    [yang.php.antlr.portable-harness :as h]
    [yang.php.antlr.prelude :as prelude]
    [yin.vm :as vm]
    [yin.vm.test-utils :as tu]))


(def ^:private closed-names
  (into (conj (prelude/defined-keys prelude/uast) 'yin/def)
        (concat prelude/host-names (keys vm/primitives))))


(deftest bundled-prelude-is-closed-test
  (is (empty? (remove closed-names (prelude/free-names prelude/uast)))))


(deftest prelude-reaches-no-dao-host-test
  (is (not-any? #(str/starts-with? (str %) "dao.host") (prelude/free-names prelude/uast)))
  (is (every? #(contains? #{"cell" "data" "integer"} (namespace %))
              prelude/host-names)))


(deftest lowered-program-names-no-runtime-key-test
  (doseq [[id form] packets/forms
          :let [free (prelude/free-names (lower/lower-module-body (h/packet form)))]]
    (is (empty? (remove closed-names free)) (name id))
    (is (not-any? #(= "php.rt" (namespace %)) free) (name id))))


(deftest module-exports-cover-the-definitions-test
  (is (contains? prelude/module-exports 'init!))
  (is (contains? prelude/module-exports 'echo))
  (is (not-any? #(= "php.rt" (namespace %)) prelude/module-exports))
  (is (every? symbol? prelude/runtime-keys)))


(deftest admit-refuses-a-registry-without-the-host-names-test
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo :cljd Object)
        (prelude/admit {}))))


(deftest module-spec-declares-every-free-name-test
  (testing "over the real host registry the manifest builds, or refuses"
    (let [spec (prelude/module-spec (h/host-registry))]
      (is (= 'php (:name spec)))
      (is (= prelude/module-exports (:exports spec)))
      (is (every? (:primitives spec) (filter #(= "integer" (namespace %)) prelude/host-names))))))


(deftest static-slot-initializes-once-when-first-reached-test
  (let [body (u/sexp->uast
               '(let [f (php/make-function
                          "f"
                          0
                          (fn [me as]
                            (do (php/echo "enter;")
                                (php/static-init! me "n" (fn [] (do (php/echo "init;") 5)))
                                (cell/set! (php/static-slot me "n")
                                           (+ (cell/get (php/static-slot me "n")) 1))
                                (php/echo (php/to-string (cell/get (php/static-slot me "n"))))
                                (if (< (data/count as) 1) :php/null (php/call me []))))
                          ["n"])]
                  (do (php/call f []) (php/call f []) (php/call f []) (php/call f [0]))))
        program (u/mark-tails (u/then prelude/uast (u/app (u/v 'php/run-main) (u/lam [] body))))]
    (is (= "enter;init;6enter;7enter;8enter;9enter;10"
           (h/render (vm/value (vm/eval (tu/create-vm {:modules (h/host-registry)}) program)))))))


;; -----------------------------------------------------------------------------
;; The array value (P2 step 1): direct prelude tests, on every host
;; -----------------------------------------------------------------------------

(defn- run-raw
  "The unit value of the prelude expression `form` run under `php/run-main`."
  [form]
  (let [program (u/mark-tails
                  (u/then prelude/uast
                          (u/app (u/v 'php/run-main) (u/lam [] (u/sexp->uast form)))))]
    (vm/value (vm/eval (tu/create-vm {:modules (h/host-registry)}) program))))


(defn- run
  [form]
  (h/render (run-raw form)))


(defn- diag
  [r]
  (mapv (juxt :errno :message) (:php/diag r)))


(defn- dump
  "`php/var-dump` of the expression `form`, as text."
  [form]
  (run (list 'php/var-dump form)))


(defn- echo-next
  "The `:next-index` of the array expression `form`, as text (`none` if absent)."
  [form]
  (run (list 'let ['n (list 'get form :next-index)]
             (list 'php/echo
                   (list 'if '(nil? n) "none" '(php/to-string n))))))


(defn- echo-key
  "The i-th key of the array expression `form`, as text."
  [form i]
  (run (list 'php/echo (list 'php/to-string (list 'get (list 'php/array-keys form) i)))))


(deftest array-key-coercion-test
  (testing "ints, canonical decimal strings, bools and null share slots"
    (is (= (str "int(1)\nint(1)\nint(0)\nint(1)\nint(0)\nstring(0) \"\"\n"
                "int(-5)\nint(9223372036854775807)\nint(-9223372036854775808)\n")
           (run '(do (php/var-dump (php/array-key 1))
                     (php/var-dump (php/array-key "1"))
                     (php/var-dump (php/array-key "0"))
                     (php/var-dump (php/array-key true))
                     (php/var-dump (php/array-key false))
                     (php/var-dump (php/array-key :php/null))
                     (php/var-dump (php/array-key "-5"))
                     (php/var-dump (php/array-key "9223372036854775807"))
                     (php/var-dump (php/array-key "-9223372036854775808")))))))
  (testing "non-canonical strings stay strings"
    (is (= (str "string(2) \"01\"\nstring(2) \"-0\"\nstring(3) \"1.5\"\nstring(2) \" 1\"\n"
                "string(2) \"+1\"\nstring(19) \"9223372036854775808\"\n"
                "string(20) \"-9223372036854775809\"\nstring(0) \"\"\nstring(1) \"-\"\n")
           (run '(do (php/var-dump (php/array-key "01"))
                     (php/var-dump (php/array-key "-0"))
                     (php/var-dump (php/array-key "1.5"))
                     (php/var-dump (php/array-key " 1"))
                     (php/var-dump (php/array-key "+1"))
                     (php/var-dump (php/array-key "9223372036854775808"))
                     (php/var-dump (php/array-key "-9223372036854775809"))
                     (php/var-dump (php/array-key ""))
                     (php/var-dump (php/array-key "-")))))))
  (testing "a float truncates, with the deprecation unless it was exact"
    (let [r (run-raw '(do (php/var-dump (php/array-key (php/f (data/float-value 3))))
                          (php/var-dump (php/array-key (php/f (data/float-value 1.5))))
                          (php/var-dump (php/array-key (php/f (data/float-value -1.5))))))]
      (is (= "int(3)\nint(1)\nint(-1)\n" (:php/out r)))
      (is (= [[8192 "Implicit conversion from float 1.5 to int loses precision"]
              [8192 "Implicit conversion from float -1.5 to int loses precision"]]
             (diag r)))))
  (testing "an array key is a TypeError, worded for the operation"
    (is (= "PHP Fatal error:  Uncaught TypeError: Cannot access offset of type array on array"
           (run '(php/array-key (php/empty-array)))))
    (is (= "PHP Fatal error:  Uncaught TypeError: Cannot unset offset of type array on array"
           (run '(php/path-unset (php/array-put (php/empty-array) 0 1)
                                 (php/vconj [] (php/empty-array))))))
    (is (= "NULL\n" (dump '(php/dim-peek :php/null (php/empty-array)))))
    (is (= "PHP Fatal error:  Uncaught TypeError: Cannot access offset of type array in isset or empty"
           (run '(php/dim-peek (php/empty-array) (php/empty-array)))))))


(deftest array-same-slot-across-key-kinds-test
  (is (= "1:1"
         (run '(let [a (php/array-put (php/empty-array) 1 "a")
                     b (php/array-put a "1" "b")
                     c (php/array-put b (php/f (data/float-value 1)) "c")
                     d (php/array-put c true "d")]
                 (php/echo (data/str-concat (php/to-string (php/array-count d))
                                            ":"
                                            (php/to-string (get (php/array-keys d) 0)))))))))


(deftest array-order-and-unset-test
  (testing "insertion order; a duplicate key keeps its first position"
    (is (= (str "array(3) {\n  [\"b\"]=>\n  int(3)\n  [0]=>\n  int(2)\n  [\"a\"]=>\n"
                "  int(4)\n}\n")
           (dump '(php/array-put
                    (php/array-put
                      (php/array-put (php/array-put (php/empty-array) "b" 1) 0 2)
                      "a" 4)
                    "b" 3)))))
  (testing "unset keeps the order of the rest; a re-added key goes last; append uses the old next index"
    (is (= (str "array(4) {\n  [0]=>\n  int(10)\n  [2]=>\n  int(12)\n  [1]=>\n  int(9)\n"
                "  [3]=>\n  int(7)\n}\n")
           (dump '(let [a (php/array-append
                            (php/array-append (php/array-append (php/empty-array) 10) 11)
                            12)]
                    (php/array-append (php/array-set (php/array-unset a 1) 1 9) 7))))))
  (testing "unsetting a missing key changes nothing"
    (is (= "1" (run '(php/echo (php/to-string
                                 (php/array-count
                                   (php/array-unset (php/array-put (php/empty-array) 0 1) 5)))))))))


(deftest array-next-index-test
  (testing "absent until the first integer key"
    (is (= "none" (echo-next '(php/empty-array))))
    (is (= "none" (echo-next '(php/array-put (php/empty-array) "x" 1))))
    (is (= "1" (echo-next '(php/array-put (php/empty-array) 0 1)))))
  (testing "the highest integer key plus one, never lowered by unset"
    (is (= "6" (echo-next '(php/array-unset
                             (php/array-put (php/array-put (php/empty-array) 5 1) 2 1)
                             5)))))
  (testing "a negative first key starts the sequence one above it (PHP 8.3)"
    (let [a '(php/array-append (php/array-put (php/empty-array) -5 1) 2)]
      (is (= "-4" (echo-key a 1)))
      (is (= "-3" (echo-next a)))))
  (testing "the first append is key 0; append goes past a high key"
    (is (= "0" (echo-key '(php/array-append (php/empty-array) 1) 0)))
    (is (= "11" (echo-key '(php/array-append (php/array-put (php/empty-array) 10 1) 2) 1))))
  (testing "PHP_INT_MAX saturates; the next append is an Error"
    (is (= "9223372036854775807"
           (echo-next '(php/array-put (php/empty-array) php.rt/int-max 1))))
    (is (= (str "PHP Fatal error:  Uncaught Error: Cannot add element to the array "
                "as the next element is already occupied")
           (run '(php/array-append (php/array-put (php/empty-array) php.rt/int-max 1) 2))))))


(deftest array-path-assign-test
  (testing "nested writes vivify null and absent levels silently"
    (let [r (run-raw '(php/var-dump
                        (php/path-assign (php/path-assign :php/null ["x" 0] 1)
                                         ["x" :php/append]
                                         2)))]
      (is (= (str "array(1) {\n  [\"x\"]=>\n  array(2) {\n    [0]=>\n    int(1)\n"
                  "    [1]=>\n    int(2)\n  }\n}\n")
             (:php/out r)))
      (is (= [] (diag r))))
    (is (= "array(1) {\n  [0]=>\n  array(1) {\n    [0]=>\n    int(1)\n  }\n}\n"
           (dump '(php/path-assign (php/empty-array) [:php/append :php/append] 1)))))
  (testing "false vivifies with a deprecation; true and scalars are an Error"
    (is (= [[8192 "Automatic conversion of false to array is deprecated"]]
           (diag (run-raw '(php/var-dump (php/path-assign false [0] 1))))))
    (is (= "PHP Fatal error:  Uncaught Error: Cannot use a scalar value as an array"
           (run '(php/path-assign 5 [0] 1))))
    (is (= "PHP Fatal error:  Uncaught Error: Cannot use a scalar value as an array"
           (run '(php/path-assign true [0] 1)))))
  (testing "a string write is the known gap"
    (is (= (str "PHP Fatal error:  Uncaught Error: string offset writes are not "
                "supported yet (yang.php known gap G-str-offset-write)")
           (run '(php/path-assign "abc" [0] "x")))))
  (testing "copy on write: the original is unchanged"
    (is (= "1,2"
           (run '(let [a (php/array-append (php/empty-array) 1)
                       b (php/path-assign a [:php/append] 2)]
                   (php/echo (data/str-concat (php/to-string (php/array-count a))
                                              ","
                                              (php/to-string (php/array-count b)))))))))
  (testing "unset never vivifies"
    (is (= "NULL\n" (dump '(php/path-unset :php/null [0 1]))))
    (is (= "array(0) {\n}\n" (dump '(php/path-unset (php/empty-array) ["x" 1]))))
    (is (= "PHP Fatal error:  Uncaught Error: Cannot unset string offsets"
           (run '(php/path-unset "abc" [0]))))
    (is (= "PHP Fatal error:  Uncaught Error: Cannot unset offset in a non-array variable"
           (run '(php/path-unset 5 [0]))))))


(deftest array-reads-test
  (let [a '(php/array-put (php/array-put (php/empty-array) "x" 1) 5 2)
        r (run-raw (list 'do
                         (list 'php/var-dump (list 'php/dim-get a "x"))
                         (list 'php/var-dump (list 'php/dim-get a "5"))
                         (list 'php/var-dump (list 'php/dim-get a "y"))
                         (list 'php/var-dump (list 'php/dim-get a 6))
                         (list 'php/var-dump (list 'php/dim-get :php/null 0))
                         (list 'php/var-dump (list 'php/dim-get 3 0))
                         (list 'php/var-dump (list 'php/dim-peek a "y"))
                         (list 'php/var-dump (list 'php/dim-peek :php/null 0))))]
    (is (= "int(1)\nint(2)\nNULL\nNULL\nNULL\nNULL\nNULL\nNULL\n" (:php/out r)))
    (is (= [[2 "Undefined array key \"y\""]
            [2 "Undefined array key 6"]
            [2 "Trying to access array offset on null"]
            [2 "Trying to access array offset on int"]]
           (diag r))))
  (testing "string offsets"
    (let [r (run-raw '(do (php/var-dump (php/dim-get "abc" 1))
                          (php/var-dump (php/dim-get "abc" -1))
                          (php/var-dump (php/dim-get "abc" 3))
                          (php/var-dump (php/dim-peek "abc" 3))))]
      (is (= "string(1) \"b\"\nstring(1) \"c\"\nstring(0) \"\"\nNULL\n" (:php/out r)))
      (is (= [[2 "Uninitialized string offset 3"]] (diag r))))))


(deftest array-conversions-and-operators-test
  (testing "juggling"
    (is (= "bool(false)\nbool(true)\nint(0)\nint(1)\n"
           (run '(do (php/var-dump (php/to-bool (php/empty-array)))
                     (php/var-dump (php/to-bool (php/array-append (php/empty-array) 0)))
                     (php/var-dump (php/to-int (php/empty-array)))
                     (php/var-dump (php/to-int (php/array-append (php/empty-array) 0)))))))
    (let [r (run-raw '(php/echo (php/to-string (php/empty-array))))]
      (is (= "Array" (:php/out r)))
      (is (= [[2 "Array to string conversion"]] (diag r)))))
  (testing "+ is union, left wins; arrays are not numbers"
    (is (= (str "array(3) {\n  [0]=>\n  int(1)\n  [\"k\"]=>\n  int(2)\n  [1]=>\n"
                "  int(5)\n}\n")
           (dump '(php/add (php/array-put (php/array-append (php/empty-array) 1) "k" 2)
                           (php/array-put (php/array-put (php/empty-array) 0 9) 1 5)))))
    (is (= "PHP Fatal error:  Uncaught TypeError: Unsupported operand types: array + int"
           (run '(php/add (php/empty-array) 1))))
    (is (= "PHP Fatal error:  Uncaught TypeError: Unsupported operand types: int * array"
           (run '(php/mul 2 (php/empty-array))))))
  (testing "comparison"
    (let [a '(php/array-put (php/array-put (php/empty-array) "a" 1) "b" 2)
          b '(php/array-put (php/array-put (php/empty-array) "b" 2) "a" 1)]
      (is (= "bool(true)\nbool(false)\nbool(true)\nbool(false)\n"
             (run (list 'do
                        (list 'php/var-dump (list 'php/loose-eq a b))
                        (list 'php/var-dump (list 'php/identical a b))
                        (list 'php/var-dump (list 'php/identical a a))
                        (list 'php/var-dump (list 'php/loose-eq a '(php/empty-array)))))))
      (is (= "int(-1)\nint(1)\nint(1)\nint(-1)\nint(0)\n"
             (run (list 'do
                        (list 'php/var-dump (list 'php/spaceship '(php/empty-array) a))
                        (list 'php/var-dump (list 'php/spaceship a '(php/empty-array)))
                        (list 'php/var-dump (list 'php/spaceship a 5))
                        (list 'php/var-dump (list 'php/spaceship 5 a))
                        (list 'php/var-dump (list 'php/spaceship '(php/empty-array) :php/null))))))))
  (testing "a key missing on the right is uncomparable"
    (is (= "int(1)\nint(1)\nbool(false)\nbool(false)\n"
           (run '(let [x (php/array-put (php/empty-array) "a" 1)
                       y (php/array-put (php/empty-array) "b" 1)]
                   (do (php/var-dump (php/spaceship x y))
                       (php/var-dump (php/spaceship y x))
                       (php/var-dump (php/lt x y))
                       (php/var-dump (php/gt x y))))))))
  (testing "var_dump nests"
    (is (= "array(2) {\n  [0]=>\n  int(1)\n  [\"x\"]=>\n  array(0) {\n  }\n}\n"
           (dump '(php/array-put (php/array-append (php/empty-array) 1) "x" (php/empty-array)))))))


;; -----------------------------------------------------------------------------
;; The counted-cell substrate (P3 step S1): cell-level, no lowering
;; -----------------------------------------------------------------------------

(defn- nums
  "A form that echoes the integer forms `xs`, comma separated."
  [& xs]
  (list 'php/echo
        (cons 'data/str-concat (interpose "," (map #(list 'php/to-string %) xs)))))


(defn- slot-kind-is
  "A form that is 1 when `(php/slot-kind v)` is `kw`, else 0."
  [v kw]
  (list 'if (list '= (list 'php/slot-kind v) kw) 1 0))


(deftest kinds-test
  (testing "kind keeps its contract for handles, markers and objects"
    (is (= "array,array,obj,int,array"
           (run '(let [h (php/new-arr (php/empty-array) {})
                       o (php/new-obj "C" (php/empty-array))]
                   (php/echo (data/str-concat
                               (php/type-name (php/kind (php/empty-array))) ","
                               (php/type-name (php/kind h)) ","
                               (if (= (php/kind o) :obj) "obj" "other") ","
                               (php/type-name (php/kind (php/new-ref 5))) ","
                               (php/type-name (php/kind (php/new-ref h))))))))))
  (testing "slot-kind is the finer answer"
    (is (= "1,1,1,1,1,1,1"
           (run (nums (slot-kind-is 1 :scalar)
                      (slot-kind-is :php/null :scalar)
                      (slot-kind-is '(php/empty-array) :plain)
                      (slot-kind-is '(php/new-arr (php/empty-array) {}) :arr-handle)
                      (slot-kind-is '(php/new-obj "C" (php/empty-array)) :obj-handle)
                      (slot-kind-is '(php/new-ref 1) :ref-marker)
                      (slot-kind-is "s" :scalar)))))))


(deftest value-helpers-deref-and-raw-slot-test
  (is (= "2,1,1,1"
         (run (nums '(php/array-count (php/new-arr (php/array-append (php/array-append (php/empty-array) 1) 2) {}))
                    '(php/array-get (php/new-arr (php/array-append (php/empty-array) 1) {}) 0)
                    '(php/array-get (php/array-put (php/empty-array) 0 (php/new-ref 1)) 0)
                    '(if (php/array-has? (php/new-arr (php/array-append (php/empty-array) 1) {}) 0) 1 0)))))
  (testing "slot-get answers the marker, array-get the value"
    (is (= "1,0"
           (run (nums '(let [m (php/new-ref 7)
                             t (php/array-put (php/empty-array) 0 m)]
                         (if (= (php/slot-kind (php/slot-get t 0)) :ref-marker) 1 0))
                      '(let [m (php/new-ref 7)
                             t (php/array-put (php/empty-array) 0 m)]
                         (if (= (php/slot-kind (php/array-get t 0)) :ref-marker) 1 0))))))))


(deftest anchor-5-one-reference-rc-3-no-nesting-test
  (is (= "3,0,0"
         (run (nums '(let [a (cell/new 1)
                           b (cell/new :php/undef)
                           c (cell/new :php/undef)]
                       (do (php/assign-ref b a)
                           (php/assign-ref c a)
                           (php/rc-of (cell/get a))))
                    '(let [a (cell/new 1)
                           b (cell/new :php/undef)
                           c (cell/new :php/undef)]
                       (do (php/assign-ref b a)
                           (php/assign-ref c a)
                           (if (= (php/slot-kind (php/ref-value (cell/get a))) :ref-marker) 1 0)))
                    '(let [a (cell/new 1)
                           b (cell/new :php/undef)]
                       (do (php/assign-ref b a)
                           (php/assign-ref b a)
                           (- (php/rc-of (cell/get a)) 2))))))))


(defn- ref-into-array
  "Forms binding `a` to [1 (2)] and `r` to a marker of `$a[0]` (rc 2)."
  [& body]
  (list 'let '[a (cell/new (php/empty-array)) b (cell/new :php/undef)
               c (cell/new :php/undef) r (cell/new :php/undef)]
        (list 'do
              '(php/assign a (php/array-put (php/array-put (php/empty-array) 0 1) 1 2) :owned)
              '(php/bind-marker r (php/make-ref-dim a 0))
              (cons 'do body))))


(deftest anchors-1-2-3-copy-with-a-shared-reference-test
  (testing "anchor 1: $b=$a; $b[0]=2 writes through the shared reference"
    (is (= "2"
           (run (ref-into-array
                  '(php/assign b (cell/get a) :borrowed)
                  '(php/assign-dim b 0 2 :owned)
                  '(php/echo (php/to-string (php/array-get (cell/get a) 0))))))))
  (testing "anchor 2: after unset($r) the copy unwraps and a is untouched"
    (is (= "1"
           (run (ref-into-array
                  '(php/unset-cv r)
                  '(php/assign b (cell/get a) :borrowed)
                  '(php/assign-dim b 0 2 :owned)
                  '(php/echo (php/to-string (php/array-get (cell/get a) 0))))))))
  (testing "anchor 3: unset($r) after the copy: the reference is rc 1, b's write is its own"
    (is (= "1"
           (run (ref-into-array
                  '(php/assign b (cell/get a) :borrowed)
                  '(php/unset-cv r)
                  '(php/assign-dim b 0 2 :owned)
                  '(php/echo (php/to-string (php/array-get (cell/get a) 0))))))))
  (testing "anchor 4: unset($b[0]) leaves the reference with $a and $r"
    (is (= "2"
           (run (ref-into-array
                  '(php/assign b (cell/get a) :borrowed)
                  '(php/assign b (php/unset-dim (php/separate (cell/get b)) 0) :owned)
                  '(php/assign-dim a 0 2 :owned)
                  '(php/echo (php/to-string (php/ref-value (cell/get r)))))))))
  (testing "anchor 6: a reference made in a copy is its own (a[1] unchanged)"
    (is (= "2"
           (run (ref-into-array
                  '(php/assign b (cell/get a) :borrowed)
                  '(php/assign-dim b 1 9 :owned)
                  '(php/echo (php/to-string (php/array-get (cell/get a) 1)))))))))


(deftest anchor-8-and-permutation-l-test
  (testing "anchor 8: $x=&$a; $a=0; $b=[&$r]; unset($r); $c=$b; $c[0]=2; echo $b[0]"
    (is (= "1"
           (run (ref-into-array
                  '(php/assign-ref (cell/new :php/undef) a)
                  '(php/assign a 0 :owned)
                  '(php/assign b (php/store (php/empty-array) 0 (php/addref (cell/get r))) :owned)
                  '(php/unset-cv r)
                  '(php/assign c (cell/get b) :borrowed)
                  '(php/assign-dim c 0 2 :owned)
                  '(php/echo (php/to-string (php/array-get (cell/get b) 0))))))))
  (testing "permutation L: $q=[&$r]; unset($r) leaves the reference with a and q"
    (is (= "2,1"
           (run (ref-into-array
                  '(php/assign b (php/store (php/empty-array) 0 (php/addref (cell/get r))) :owned)
                  '(php/unset-cv r)
                  (nums '(php/rc-of (php/slot-get (cell/get a) 0))
                        '(php/rc-of (cell/get b)))))))
    (is (= "0"
           (run (nums '(data/count (php/check-invariants (php/empty-array)))))))))


(deftest transfer-mode-test
  (testing "a borrowed assign counts, an owned assign moves"
    (is (= "2,1"
           (run (nums '(let [h (php/new-arr (php/array-append (php/empty-array) (php/new-ref 1)) {0 true})
                             v (cell/new :php/undef)]
                         (do (php/assign v h :borrowed) (php/rc-of h)))
                      '(let [h (php/new-arr (php/empty-array) {})
                             v (cell/new :php/undef)]
                         (do (php/assign v h :owned) (php/rc-of h))))))))
  (testing "assigning over a counted value releases it after the store"
    (is (= "0"
           (run (nums '(let [h (php/new-arr (php/empty-array) {})
                             v (cell/new h)]
                         (do (php/assign v 5 :owned) (php/rc-of h)))))))))


(deftest delref-recursion-and-dup-test
  (testing "delref to zero releases the slots, recursively"
    (is (= "0,0"
           (run (nums '(let [inner (php/new-arr (php/empty-array) {})
                             outer (php/store (php/new-arr (php/empty-array) {}) 0 inner)]
                         (do (php/delref outer) (php/rc-of inner)))
                      '(let [o (php/new-obj "C" (php/array-put (php/empty-array) "p" (php/new-arr (php/empty-array) {})))
                             p (php/slot-get (get (cell/get (get o :php/obj)) :props) "p")]
                         (do (php/delref o) (php/rc-of p))))))))
  (testing "unwrap on dup: an rc-1 marker becomes its value; the source is unchanged"
    (is (= "1,1,1"
           (run (nums '(let [h (php/new-arr (php/empty-array) {})
                             r (php/make-ref (php/assign-slot h 0 5 :owned) 0)
                             d (php/dup (get r 0))]
                         (if (= (php/slot-kind (php/slot-get d 0)) :scalar) 1 0))
                      '(let [h (php/new-arr (php/empty-array) {})
                             r (php/make-ref (php/assign-slot h 0 5 :owned) 0)
                             d (php/dup (get r 0))]
                         (php/rc-of (php/slot-get (get r 0) 0)))
                      '(let [h (php/new-arr (php/empty-array) {})
                             r (php/make-ref (php/assign-slot h 0 5 :owned) 0)
                             d (php/dup (get r 0))]
                         (if (= (php/slot-kind (php/slot-get (get r 0) 0)) :ref-marker) 1 0)))))))
  (testing "self-reference exception: a marker holding its own table survives dup"
    (is (= "2"
           (run (nums '(let [h (php/new-arr (php/empty-array) {})
                             r (php/make-ref (php/assign-slot h 0 1 :owned) 0)
                             t (php/assign-slot (get r 0) 0 (get r 0) :borrowed)
                             d (php/dup t)]
                         (php/rc-of (get r 1))))))))
  (testing "invariants: markers never nest, plain arrays hold nothing counted"
    (is (= "0"
           (run (nums '(data/count
                         (php/check-invariants
                           (php/new-arr (php/array-put (php/empty-array) 0 (php/new-ref 1)) {0 true})))))))
    (is (= "1"
           (run (nums '(data/count
                         (php/check-invariants
                           (php/array-put (php/empty-array) 0 (php/new-ref 1))))))))))


(def ^:private row18
  ;; $a = [1]; $r = &$a[0]; $a[0] = $a  (the marker holds its own table,
  ;; uncounted: :owned moves instead of ADDREFing)
  '(let [h (php/new-arr (php/empty-array) {})
         r (php/make-ref (php/assign-slot h 0 1 :owned) 0)]
     (php/assign-slot (get r 0) 0 (get r 0) :owned)))


(defn- with-fix
  "Substitute the symbol FIX in `tmpl` with `form`."
  [tmpl form]
  (walk/postwalk-replace {'FIX form} tmpl))


(deftest cycle-release-test
  (testing "row 18: releasing the last reference terminates, nothing goes negative"
    (is (= "1,0,0"
           (run (nums (with-fix '(let [t FIX] (do (php/delref t) (if (php/released? t) 1 0))) row18)
                      (with-fix '(let [t FIX] (do (php/delref t) (data/count (php/check-invariants t)))) row18)
                      (with-fix '(let [t FIX] (do (php/delref t) (if (< (php/rc-of t) 0) 1 0))) row18))))))
  (testing "the same cycle nested under an array and under an object"
    (is (= "1,1"
           (run (nums (with-fix '(let [t FIX
                                       o (php/store (php/new-arr (php/empty-array) {}) 0 t)]
                                   (do (php/delref o) (if (php/released? t) 1 0)))
                        row18)
                      (with-fix '(let [t FIX
                                       o (php/new-obj "C" (php/array-put (php/empty-array) "p" t))]
                                   (do (php/delref o) (if (php/released? t) 1 0)))
                        row18))))))
  (testing "a two-member cycle: A's marker holds B, B's marker holds A"
    (is (= "1,1,0"
           (run (nums '(let [a (php/new-arr (php/empty-array) {})
                             ra (php/make-ref (php/assign-slot a 0 1 :owned) 0)
                             b (php/new-arr (php/empty-array) {})
                             rb (php/make-ref (php/assign-slot b 0 1 :owned) 0)]
                         (do (php/assign-slot (get ra 0) 0 (get rb 0) :owned)
                             (php/assign-slot (get rb 0) 0 (get ra 0) :owned)
                             (php/delref (get ra 0))
                             (if (php/released? (get ra 0)) 1 0)))
                      '(let [a (php/new-arr (php/empty-array) {})
                             ra (php/make-ref (php/assign-slot a 0 1 :owned) 0)
                             b (php/new-arr (php/empty-array) {})
                             rb (php/make-ref (php/assign-slot b 0 1 :owned) 0)]
                         (do (php/assign-slot (get ra 0) 0 (get rb 0) :owned)
                             (php/assign-slot (get rb 0) 0 (get ra 0) :owned)
                             (php/delref (get ra 0))
                             (if (php/released? (get rb 0)) 1 0)))
                      '(let [a (php/new-arr (php/empty-array) {})
                             ra (php/make-ref (php/assign-slot a 0 1 :owned) 0)
                             b (php/new-arr (php/empty-array) {})
                             rb (php/make-ref (php/assign-slot b 0 1 :owned) 0)]
                         (do (php/assign-slot (get ra 0) 0 (get rb 0) :owned)
                             (php/assign-slot (get rb 0) 0 (get ra 0) :owned)
                             (php/delref (get ra 0))
                             (+ (if (< (php/rc-of (get ra 0)) 0) 1 0)
                                (if (< (php/rc-of (get rb 0)) 0) 1 0)))))))))
  (testing "addref/delref on a released cell are no-ops"
    (is (= "0,0"
           (run (nums '(let [h (php/new-arr (php/empty-array) {})]
                         (do (php/delref h) (php/addref h) (php/rc-of h)))
                      '(let [h (php/new-arr (php/empty-array) {})]
                         (do (php/delref h) (php/delref h) (php/rc-of h))))))))
  (testing "check-invariants accepts released cells, flags a negative live count"
    (is (= "0,1"
           (run (nums '(let [h (php/new-arr (php/empty-array) {})]
                         (do (php/delref h) (data/count (php/check-invariants h))))
                      '(let [h (php/new-arr (php/empty-array) {})]
                         (do (cell/set! (get h :php/arr) (assoc (cell/get (get h :php/arr)) :rc -1))
                             (data/count (php/check-invariants h)))))))))
  (testing "dup does not copy the released flag"
    (is (= "0"
           (run (nums '(let [h (php/new-arr (php/empty-array) {})
                             d (php/dup h)]
                         (do (php/delref h) (if (php/released? d) 1 0)))))))))


(deftest dim-reads-deref-test
  (let [fixture '(let [h (php/new-arr (php/empty-array) {})
                       r (php/make-ref (php/assign-slot h 0 7 :owned) 0)]
                   (get r 0))
        write '(let [m (php/slot-get t 0)]
                 (cell/set! (get m :php/ref) (assoc (cell/get (get m :php/ref)) :value 9)))]
    (testing "dim-get, dim-peek and array-get agree and never return a marker"
      (is (= "7,7,7,0"
             (run (nums (with-fix '(let [t FIX] (php/dim-get t 0)) fixture)
                        (with-fix '(let [t FIX] (php/dim-peek t 0)) fixture)
                        (with-fix '(let [t FIX] (php/array-get t 0)) fixture)
                        (with-fix '(let [t FIX]
                                     (if (= (php/slot-kind (php/dim-get t 0)) :ref-marker) 1 0))
                          fixture))))))
    (testing "after writing through the reference all three see the new value"
      (is (= "9,9,9"
             (run (nums (with-fix (list 'let '[t FIX] (list 'do write '(php/dim-get t 0))) fixture)
                        (with-fix (list 'let '[t FIX] (list 'do write '(php/dim-peek t 0))) fixture)
                        (with-fix (list 'let '[t FIX] (list 'do write '(php/array-get t 0))) fixture))))))))
