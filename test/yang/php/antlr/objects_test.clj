(ns yang.php.antlr.objects-test
  "P4 S2 end to end: classes, objects, properties, methods, visibility and
   Throwable objects, from PHP source on the JVM. The enabled P4 fixtures run
   against their pinned `:out`; a fixture without one (to be recorded on
   shiva) must run to completion without an uncaught error. The tests after
   it cover S2 behavior the fixtures do not."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.e2e-harness :as h]
    [yang.php.antlr.programs-p4 :as p4]))


(defn- out
  [source]
  (:php/out (h/run-raw source)))


(defn- diag
  [source]
  (mapv (juxt :errno :message) (:php/diag (h/run-raw source))))


(defn- uncaught
  [source]
  (-> (h/run-raw source) :php/uncaught :message))


(deftest enabled-p4-fixtures-test
  (doseq [{:keys [id source] expected :out} p4/enabled]
    (let [r (h/run-raw source)]
      (is (nil? (:php/uncaught r)) (name id))
      (when expected
        (is (= expected (:php/out r)) (name id))))))


(deftest objects-s2-test
  (testing "a static method call forwards the static class"
    (is (= "AB"
           (out (str "<?php class A { static function n() { return static::class; }"
                     " static function m() { return self::n(); } }"
                     " class B extends A {} echo A::m(), B::n();")))))
  (testing "dynamic properties and undefined property reads"
    (is (= "5" (out "<?php class A {} $a = new A; $a->k = 5; echo $a->k;")))
    (is (= "" (out "<?php class A {} $a = new A; echo $a->zz;")))
    (is (= [[2 "Undefined property: A::$zz"]]
           (diag "<?php class A {} $a = new A; echo $a->zz;"))))
  (testing "isset, unset and nested property writes"
    (is (= "10" (out (str "<?php class A { public $x = 1; } $a = new A; echo isset($a->x) ? 1 : 0;"
                          " unset($a->x); echo isset($a->x) ? 1 : 0;"))))
    (is (= "7" (out "<?php class A { public $d = []; } $a = new A; $a->d['k'] = 7; echo $a->d['k'];"))))
  (testing "property access on a non-object"
    (is (= [[2 "Attempt to read property \"p\" on null"]]
           (diag "<?php $n = null; echo $n->p;")))
    (is (= "Attempt to assign property \"p\" on null"
           (uncaught "<?php $n = null; $n->p = 1;"))))
  (testing "visibility of methods and constructors"
    (is (= "Call to private method A::f() from global scope"
           (uncaught "<?php class A { private function f() {} } (new A)->f();")))
    (is (= "Call to protected method A::f() from global scope"
           (uncaught "<?php class A { protected function f() {} } $a = new A; $a->f();")))
    (is (= "Call to private A::__construct() from global scope"
           (uncaught "<?php class A { private function __construct() {} } new A;")))
    (is (= "x" (out (str "<?php class A { private function f() { return 'x'; }"
                         " function g() { return $this->f(); } } $a = new A; echo $a->g();")))))
  (testing "a private method of the calling class is not overridden by the subclass"
    (is (= "ab"
           (out (str "<?php class A { private function f() { return 'a'; }"
                     " function g() { return $this->f(); } }"
                     " class B extends A { function f() { return 'b'; } }"
                     " $b = new B; echo $b->g(), $b->f();")))))
  (testing "__call, __callStatic and __invoke"
    (is (= "m2" (out "<?php class A { function __call($n, $a) { return $n . count($a); } } $a = new A; echo $a->m(1, 2);")))
    (is (= "s1" (out "<?php class A { static function __callStatic($n, $a) { return $n . count($a); } } echo A::s(1);")))
    (is (= "7" (out "<?php class A { function __invoke($x) { return $x + 1; } } $f = new A; echo $f(6);"))))
  (testing "class-level errors"
    (is (= "Class \"Nope\" not found" (uncaught "<?php new Nope;")))
    (is (= "Cannot instantiate interface I" (uncaught "<?php interface I {} new I;")))
    (is (= "Non-static method A::f() cannot be called statically"
           (uncaught "<?php class A { function f() {} } A::f();"))))
  (testing "anonymous classes stay refused"
    (is (= :anonymous-class
           (-> (h/run-php "<?php $o = new class { public $x = 1; };") :diagnostics first :construct))))
  (testing "Throwable objects: previous and code, a user constructor, an uncaught fatal"
    (is (= "p3" (out (str "<?php $e = new Exception('o', 3, new Exception('p'));"
                          " echo $e->getPrevious()->getMessage(), $e->getCode();"))))
    (is (= "xabc"
           (out (str "<?php class E extends Exception { function __construct($m) { parent::__construct('x' . $m); } }"
                     " try { throw new E('abc'); } catch (E $e) { echo $e->getMessage(); }"))))
    (is (= {:class "E", :message "boom"}
           (-> (h/run-raw "<?php class E extends Exception {} throw new E('boom');") :php/uncaught)))))
