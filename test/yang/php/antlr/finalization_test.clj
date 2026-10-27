(ns yang.php.antlr.finalization-test
  "P4 S3 end to end: destructors, the chaining rule, resurrection, shutdown,
   clone and readonly, from PHP source on the JVM (the enabled fixtures run in
   `objects-test`; these cover behavior they do not pin)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.e2e-harness :as h]))


(defn- out
  [source]
  (:php/out (h/run-raw source)))


(def ^:private thrower
  (str "class T { public $m; function __construct($m) { $this->m = $m; }"
       " function __destruct() { throw new Exception($this->m); } } "))


(deftest destructor-exceptions-test
  (testing "row 50: the scope's remaining releases complete, then the exception propagates"
    (is (= "ABd"
           (out (str "<?php class A { function __destruct() { echo 'A'; throw new Exception('d'); } }"
                     " class B { function __destruct() { echo 'B'; } }"
                     " function f() { $a = new A; $b = new B; }"
                     " try { f(); } catch (Exception $e) { echo $e->getMessage(); }")))))
  (testing "the chaining rule: a later destructor exception takes the place of the pending one"
    (is (= "2,1"
           (out (str "<?php " thrower
                     "function f() { $a = new T('1'); $b = new T('2'); }"
                     " try { f(); } catch (Exception $e) { echo $e->getMessage(), ',', $e->getPrevious()->getMessage(); }")))))
  (testing "an exception from a destructor at shutdown is uncaught"
    (is (= {:class "Exception", :message "s"}
           (-> (h/run-raw (str "<?php " thrower "$t = new T('s');")) :php/uncaught)))))


(deftest shutdown-test
  (testing "row 22: objects held only by globals go in reverse insertion order"
    (is (= "x21" (out (str "<?php class A { public $n; function __construct($n) { $this->n = $n; }"
                           " function __destruct() { echo $this->n; } }"
                           " $a = new A(1); $b = new A(2); echo 'x';")))))
  (testing "an object also held elsewhere is destructed by the finalizable walk, in creation order"
    (is (= "x12"
           (out (str "<?php class A { public $n; function __construct($n) { $this->n = $n; }"
                     " function __destruct() { echo $this->n; } }"
                     " $a = new A(1); $b = new A(2); $keep = [$a, $b]; echo 'x';")))))
  (testing "a constructor that throws leaves no destructor behind, at shutdown either"
    (is (= "c"
           (out (str "<?php class A { function __construct() { throw new Exception('x'); }"
                     " function __destruct() { echo 'D'; } }"
                     " try { new A; } catch (Exception $e) { echo 'c'; }"))))))


(deftest clone-and-readonly-test
  (testing "clone of a non-cloneable visibility and the private __clone"
    (is (= "Call to private A::__clone() from global scope"
           (-> (h/run-raw "<?php class A { private function __clone() {} } $a = new A; $b = clone $a;")
               :php/uncaught :message))))
  (testing "an uninitialized readonly property can be initialized once from its class"
    (is (= "Cannot modify readonly property C::$v"
           (-> (h/run-raw (str "<?php class C { public readonly int $v; function set() { $this->v = 1; $this->v = 2; } }"
                               " $c = new C; $c->set();"))
               :php/uncaught :message))))
  (testing "reading an uninitialized typed property"
    (is (= "Typed property C::$v must not be accessed before initialization"
           (-> (h/run-raw "<?php class C { public int $v; } $c = new C; echo $c->v;") :php/uncaught :message)))))
