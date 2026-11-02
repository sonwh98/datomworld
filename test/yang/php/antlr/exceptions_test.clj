(ns yang.php.antlr.exceptions-test
  "P4 S4 end to end: the Throwable hierarchy, throw (as a statement and as an
   expression), try/catch/finally and match, from PHP source on the JVM."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.e2e-harness :as h]))


(defn- out
  [source]
  (:php/out (h/run-raw source)))


(defn- uncaught
  [source]
  (-> (h/run-raw source) :php/uncaught))


(deftest hierarchy-test
  (testing "the SPL tree and the Error/Exception split"
    (is (= "1,1,1,0,1"
           (out (str "<?php $e = new BadMethodCallException;"
                     " echo $e instanceof BadFunctionCallException ? 1 : 0, ',';"
                     " echo $e instanceof LogicException ? 1 : 0, ',';"
                     " echo $e instanceof Throwable ? 1 : 0, ',';"
                     " echo $e instanceof Error ? 1 : 0, ',';"
                     " echo (new JsonException) instanceof Exception ? 1 : 0;")))))
  (testing "ErrorException and the engine errors extend the right roots"
    (is (= "11" (out (str "<?php echo (new ErrorException) instanceof Exception ? 1 : 0;"
                          " echo (new DivisionByZeroError) instanceof Error ? 1 : 0;"))))))


(deftest throw-test
  (testing "throw as an expression"
    (is (= "caught"
           (out (str "<?php function f($x) { return $x ?? throw new Exception('caught'); }"
                     " try { f(null); } catch (Exception $e) { echo $e->getMessage(); }"))))
    (is (= "ok" (out "<?php $v = null; $r = $v ?? 'ok'; echo $r;"))))
  (testing "throwing a non-Throwable"
    (is (= "Can only throw objects"
           (-> (uncaught "<?php throw 5;") :message))))
  (testing "rethrow keeps the same object"
    (is (= "1"
           (out (str "<?php try { try { throw new Exception('a'); } catch (Exception $e) { throw $e; } }"
                     " catch (Exception $f) { echo $f->getMessage() == 'a' ? 1 : 0; }"))))))


(deftest catch-test
  (testing "multi-type catch, optional variable, first match wins"
    (is (= "A" (out (str "<?php try { throw new TypeError('t'); }"
                         " catch (RuntimeException | TypeError $e) { echo 'A'; } catch (Error $e) { echo 'B'; }"))))
    (is (= "c" (out "<?php try { throw new Exception; } catch (Exception) { echo 'c'; }")))
    (is (= "B" (out (str "<?php try { throw new Error; }"
                         " catch (Exception $e) { echo 'A'; } catch (Throwable $e) { echo 'B'; }")))))
  (testing "an unmatched exception propagates out of the function"
    (is (= "outer"
           (out (str "<?php function f() { try { throw new Exception('x'); } catch (TypeError $e) { echo 'no'; } }"
                     " try { f(); } catch (Exception $e) { echo 'outer'; }")))))
  (testing "a leading backslash names the same class"
    (is (= "k" (out "<?php try { throw new Exception; } catch (\\Exception $e) { echo 'k'; }")))))


(deftest finally-test
  (testing "finally runs on every exit and can override a return"
    (is (= "F1" (out "<?php function f() { try { return 1; } finally { echo 'F'; } } echo f();")))
    (is (= "2" (out "<?php function f() { try { return 1; } finally { return 2; } } echo f();")))
    (is (= "Fa" (out (str "<?php try { try { throw new Exception('a'); } finally { echo 'F'; } }"
                          " catch (Exception $e) { echo $e->getMessage(); }")))))
  (testing "an exception from finally replaces the one in flight"
    (is (= "b" (out (str "<?php try { try { throw new Exception('a'); } finally { throw new Exception('b'); } }"
                         " catch (Exception $e) { echo $e->getMessage(); }")))))
  (testing "finally after a catch, in a loop with break"
    (is (= "cF" (out "<?php try { throw new Exception; } catch (Exception $e) { echo 'c'; } finally { echo 'F'; }")))
    (is (= "F" (out "<?php while (true) { try { break; } finally { echo 'F'; } }")))))


(deftest match-test
  (testing "strict comparison, several arms, default"
    (is (= "b" (out "<?php echo match (2) { 1 => 'a', 2, 3 => 'b', default => 'z' };")))
    (is (= "z" (out "<?php echo match ('2') { 2 => 'a', default => 'z' };"))))
  (testing "objects match by identity"
    (is (= "same"
           (out (str "<?php class A {} $a = new A; $b = new A;"
                     " echo match ($a) { $b => 'other', $a => 'same' };")))))
  (testing "no arm: UnhandledMatchError with PHP's text"
    (is (= {:class "UnhandledMatchError", :message "Unhandled match case 5"}
           (uncaught "<?php echo match (5) { 1 => 'a' };")))
    (is (= {:class "UnhandledMatchError", :message "Unhandled match case 'x'"}
           (uncaught "<?php echo match ('x') { 1 => 'a' };")))
    (is (= {:class "UnhandledMatchError", :message "Unhandled match case of type A"}
           (uncaught "<?php class A {} echo match (new A) { 1 => 'a' };")))))


(deftest ownership-test
  (testing "the catch variable owns the exception; a destructor runs when it goes"
    (is (= "cxDy"
           (out (str "<?php class E extends Exception { function __destruct() { echo 'D'; } }"
                     " try { throw new E('m'); } catch (E $e) { echo 'c'; } echo 'x'; unset($e); echo 'y';")))))
  (testing "an exception nobody catches still destructs at shutdown, after the error"
    (is (= "D"
           (out (str "<?php class E extends Exception { function __destruct() { echo 'D'; } }"
                     " throw new E('m');"))))))
