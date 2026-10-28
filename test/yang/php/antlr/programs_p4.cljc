(ns yang.php.antlr.programs-p4
  "Slice P4 Stage 0 (S0) fixture data: classes, exceptions, constructors and
   readonly groups (P4 plan v2, S0). Same schema as
   `yang.php.antlr.programs-p3b`; every fixture here is `:oracle :pending`
   until the shiva recording pass, and `:requires` names the P4 steps
   (:p4-s1 .. :p4-s12) that must land before an implementation runner may
   execute it. `:out` is pinned only where the row or the language fixes it;
   otherwise it is absent and the recorded output is taken. A mismatch is
   escalated to the architect and never fixed by editing :out.

   `all-fixtures` is unconditional (the oracle iterates it). `enabled` is the
   subset whose :requires have all landed in `completed-steps`."
  (:require [yang.php.antlr.programs-p3b :as p3b]))


(def completed-steps
  "P4 steps that have landed: S1 (descriptors) and S2 (objects, methods,
   visibility). A fixture whose :requires name a later step stays disabled."
  #{:p4-s1 :p4-s2 :p4-s3 :p4-s4 :p4-s5})


(defn- fx
  ([id group rows requires source out]
   (fx id group rows requires source out :program))
  ([id group rows requires source out level]
   (cond-> {:id id,
            :group group,
            :rows rows,
            :level level,
            :requires requires,
            :source source,
            :oracle :pending}
     out (assoc :out out))))


(def classes-v1
  [(fx :cls-new-and-method :classes-v1 [] #{:p4-s2}
       "<?php class A { public $x = 1; function get() { return $this->x; } } $a = new A; echo $a->get();"
       "1")
   (fx :cls-static-prop-shared :classes-v1 [] #{:p4-s2}
       "<?php class A { public static $n = 0; } class B extends A {} A::$n++; B::$n++; echo A::$n, B::$n;"
       "22")
   (fx :cls-static-prop-redeclared-unshared :classes-v1 [] #{:p4-s2}
       "<?php class A { public static $n = 0; } class B extends A { public static $n = 5; } A::$n++; echo A::$n, B::$n;"
       "15")
   (fx :cls-const-and-self :classes-v1 [] #{:p4-s2}
       "<?php class A { const X = 3; const Y = self::X; } echo A::Y;"
       "3")
   (fx :cls-private-identity-per-class :classes-v1 [19] #{:p4-s2}
       (str "<?php class A { private $p = 'a'; function a() { return $this->p; } }"
            " class B extends A { public $p = 'b'; function b() { return $this->p; } }"
            " $o = new B; echo $o->a(), $o->b();")
       "ab")
   (fx :cls-private-not-visible-outside :classes-v1 [] #{:p4-s2}
       (str "<?php class A { private $p = 1; }"
            " try { echo (new A)->p; } catch (Error $e) { echo $e->getMessage(); }")
       "Cannot access private property A::$p")
   (fx :cls-protected-from-subclass :classes-v1 [] #{:p4-s2}
       "<?php class A { protected $p = 4; } class B extends A { function f() { return $this->p; } } echo (new B)->f();"
       "4")
   (fx :cls-late-static-binding :classes-v1 [] #{:p4-s2}
       (str "<?php class A { static function make() { return new static; } function n() { return static::class; } }"
            " class B extends A {} $o = B::make(); echo $o->n();")
       "B")
   (fx :cls-abstract-instantiation :classes-v1 [] #{:p4-s2}
       (str "<?php abstract class A {}"
            " try { new A; } catch (Error $e) { echo $e->getMessage(); }")
       "Cannot instantiate abstract class A")
   (fx :cls-interface-instanceof :classes-v1 [] #{:p4-s2}
       "<?php interface I {} class A implements I {} var_dump(new A instanceof I);"
       "bool(true)\n")
   (fx :cls-interface-constant :classes-v1 [] #{:p4-s2}
       "<?php interface I { const K = 'k'; } class A implements I {} echo A::K, I::K;"
       "kk")
   (fx :cls-trait-method :classes-v1 [] #{:p4-s2}
       "<?php trait T { function hi() { return 'hi'; } } class A { use T; } echo (new A)->hi();"
       "hi")
   (fx :cls-trait-static-per-user :classes-v1 [] #{:p4-s2}
       (str "<?php trait T { static function c() { static $n = 0; return ++$n; } }"
            " class A { use T; } class B { use T; } A::c(); A::c(); echo A::c(), B::c();")
       "31")
   (fx :cls-trait-self-is-user :classes-v1 [] #{:p4-s2}
       (str "<?php trait T { function who() { return self::class; } } class A { use T; } class B extends A {}"
            " echo (new B)->who();")
       "A")
   (fx :cls-trait-conflict-insteadof :classes-v1 [] #{:p4-s2}
       (str "<?php trait T1 { function f() { return 1; } } trait T2 { function f() { return 2; } }"
            " class A { use T1, T2 { T1::f insteadof T2; T2::f as g; } } $a = new A; echo $a->f(), $a->g();")
       "12")
   (fx :cls-enum-pure :classes-v1 [] #{:p4-s2}
       "<?php enum S { case On; case Off; } var_dump(S::On === S::On, S::On === S::Off);"
       "bool(true)\nbool(false)\n")
   (fx :cls-enum-backed-value :classes-v1 [] #{:p4-s2}
       "<?php enum S: string { case On = 'on'; case Off = 'off'; } $a = S::On; $b = S::Off; echo $a->value, $b->name;"
       "onOff")
   (fx :cls-enum-cases :classes-v1 [] #{:p4-s2}
       "<?php enum S { case A; case B; } foreach (S::cases() as $c) { echo $c->name; }"
       "AB")
   (fx :cls-enum-from-tryfrom :classes-v1 [] #{:p4-s9}
       (str "<?php enum S: int { case One = 1; } echo S::from(1)->name; var_dump(S::tryFrom(2));"
            " try { S::from(2); } catch (ValueError $e) { echo $e->getMessage(); }")
       nil)
   (fx :cls-hoisting :classes-v1 [] #{:p4-s1}
       "<?php echo (new A)->v(); class A { function v() { return 7; } }"
       nil)
   (fx :cls-anonymous-refused :classes-v1 [] #{:p4-s1}
       "<?php $o = new class { public $x = 1; }; echo $o->x;"
       nil
       :oracle-only)
   (fx :cls-magic-get-set-call :classes-v1 [] #{:p4-s2}
       (str "<?php class M { private $d = []; function __get($n) { return $this->d[$n] ?? 'none'; }"
            " function __set($n, $v) { $this->d[$n] = $v; } function __call($n, $a) { return $n . count($a); } }"
            " $m = new M; $m->k = 5; echo $m->k, $m->z, $m->go(1, 2);")
       "5nonego2")
   (fx :cls-magic-tostring :classes-v1 [] #{:p4-s2}
       "<?php class S { function __toString() { return 'str'; } } echo new S, strlen(new S);"
       "str3")
   (fx :cls-object-var-dump :classes-v1 [] #{:p4-s2}
       "<?php class P { public $a = 1; protected $b = 2; private $c = 3; } var_dump(new P);"
       nil)
   (fx :cls-iterate-properties-visibility :classes-v1 [] #{:p4-s8}
       (str "<?php class P { public $a = 1; protected $b = 2; function it() { foreach ($this as $k => $v) echo $k; } }"
            " foreach (new P as $k => $v) echo $k; (new P)->it();")
       "aab")])


(def constructor-v1
  [(fx :ctor-runs-with-args :constructor-v1 [] #{:p4-s2}
       "<?php class A { public $v; function __construct($v) { $this->v = $v; } } echo (new A(5))->v;"
       "5")
   (fx :ctor-promotion :constructor-v1 [] #{:p4-s5}
       (str "<?php class A { function __construct(public int $x = 3, private $y = 4) {} function y() { return $this->y; } }"
            " $a = new A; echo $a->x, $a->y();")
       "34")
   (fx :ctor-arg-throws-skips-destructor :constructor-v1 [62] #{:p4-s3}
       (str "<?php class A { function __destruct() { echo 'D'; } function __construct($x) { echo 'C'; } }"
            " function boom() { throw new Exception('b'); }"
            " try { new A(boom()); } catch (Exception $e) { echo 'caught'; }")
       "caught")
   (fx :ctor-throws-skips-destructor :constructor-v1 [] #{:p4-s3}
       (str "<?php class A { function __construct() { throw new Exception('x'); } function __destruct() { echo 'D'; } }"
            " try { new A; } catch (Exception $e) { echo 'caught'; }")
       "caught")
   (fx :ctor-named-args :constructor-v1 [65] #{:p4-s5}
       "<?php class A { function __construct(public $a = 1, public $b = 2) {} } $o = new A(b: 9); echo $o->a, $o->b;"
       "19")
   (fx :ctor-parent-call :constructor-v1 [] #{:p4-s2}
       (str "<?php class A { public $x; function __construct() { $this->x = 'a'; } }"
            " class B extends A { function __construct() { parent::__construct(); echo $this->x; } } new B;")
       "a")
   (fx :ctor-destruct-order-at-end :constructor-v1 [22 48] #{:p4-s5}
       (str "<?php class A { function __construct(public $n) {} function __destruct() { echo $this->n; } }"
            " $a = new A(1); $b = new A(2); echo 'x';")
       nil)
   (fx :ctor-destruct-on-unset :constructor-v1 [48] #{:p4-s3}
       "<?php class A { function __destruct() { echo 'D'; } } $a = new A; unset($a); echo 'x';"
       "Dx")
   (fx :ctor-destruct-resurrect :constructor-v1 [49] #{:p4-s3}
       (str "<?php class A { function __destruct() { global $keep; $keep = $this; echo 'D'; } }"
            " $a = new A; $a = null; echo 'x'; $keep = null; echo 'y';")
       nil)
   (fx :ctor-clone :constructor-v1 [52] #{:p4-s3}
       (str "<?php class A { public $v = 1; function __clone() { echo 'C'; } }"
            " $a = new A; $b = clone $a; $b->v = 2; echo $a->v, $b->v;")
       "C12")])


(def exceptions-v1
  [(fx :exc-throw-catch-object :exceptions-v1 [] #{:p4-s4}
       "<?php class E extends Exception {} try { throw new E('m'); } catch (E $e) { echo get_class($e), $e->getMessage(); }"
       "Em")
   (fx :exc-hierarchy-catch-base :exceptions-v1 [] #{:p4-s4}
       "<?php try { throw new InvalidArgumentException('i'); } catch (LogicException $e) { echo 'L'; }"
       "L")
   (fx :exc-error-not-exception :exceptions-v1 [] #{:p4-s4}
       "<?php try { try { throw new Error('e'); } catch (Exception $e) { echo 'X'; } } catch (Throwable $t) { echo 'T'; }"
       "T")
   (fx :exc-multi-catch :exceptions-v1 [] #{:p4-s4}
       "<?php try { throw new TypeError('t'); } catch (RuntimeException | TypeError $e) { echo get_class($e); }"
       "TypeError")
   (fx :exc-catch-without-variable :exceptions-v1 [] #{:p4-s4}
       "<?php try { throw new Exception; } catch (Exception) { echo 'c'; }"
       "c")
   (fx :exc-previous-chain :exceptions-v1 [] #{:p4-s4}
       "<?php $e = new Exception('o', 3, new Exception('p')); echo $e->getPrevious()->getMessage(), $e->getCode();"
       "p3")
   (fx :exc-finally-overrides-return :exceptions-v1 [] #{:p4-s4}
       "<?php function f() { try { return 1; } finally { return 2; } } echo f();"
       "2")
   (fx :exc-finally-rethrow-runs :exceptions-v1 [] #{:p4-s4}
       "<?php try { try { throw new Exception('a'); } finally { echo 'F'; } } catch (Exception $e) { echo $e->getMessage(); }"
       "Fa")
   (fx :exc-user-ctor-override :exceptions-v1 [] #{:p4-s4}
       (str "<?php class E extends Exception { function __construct($m) { parent::__construct('x' . $m); } }"
            " try { throw new E('abc'); } catch (E $e) { echo $e->getMessage(); }")
       nil)
   (fx :exc-line-number :exceptions-v1 [] #{:p4-s4}
       "<?php\n\ntry { throw new Exception('x'); } catch (Exception $e) { echo $e->getLine(); }"
       "3")
   (fx :exc-destructor-throws :exceptions-v1 [50] #{:p4-s4}
       (str "<?php class A { function __destruct() { throw new Exception('d'); } }"
            " try { $a = new A; unset($a); } catch (Exception $e) { echo $e->getMessage(); }")
       "d")
   (fx :exc-match-unhandled :exceptions-v1 [] #{:p4-s4}
       "<?php try { echo match (5) { 1 => 'a' }; } catch (\\UnhandledMatchError $e) { echo $e->getMessage(); }"
       nil)
   (assoc (fx :exc-uncaught-fatal :exceptions-v1 [22] #{:p4-s4}
              "<?php class E extends Exception {} echo 'a'; throw new E('boom');"
              "a")
          :throws true
          :uncaught {:class "E", :message "boom"})
   (assoc (fx :exc-named-arg-validation-order :exceptions-v1 [57] #{:p4-s4}
              (str "<?php function f($a) { return $a; } function side() { echo 'S'; return 1; }"
                   " try { f(1, z: 2, w: side()); } catch (Error $e) { echo $e->getMessage(); }")
              nil)
          :departure (some (fn [f]
                             (when (= :call-unknown-named-later-arg-runs
                                      (:id f))
                               f))
                           p3b/all-fixtures)
          :impl-out "SUnknown named parameter $z")])


(def readonly-v1
  [(fx :ro-init-once :readonly-v1 [66] #{:p4-s3}
       "<?php class C { public readonly int $v; function __construct() { $this->v = 1; } } $c = new C; echo $c->v;"
       "1")
   (fx :ro-modify-throws :readonly-v1 [66] #{:p4-s3}
       (str "<?php class C { public readonly int $v; function __construct($v) { $this->v = $v; } }"
            " $c = new C(1); try { $c->v = 2; } catch (Error $e) { echo $e->getMessage(); }")
       "Cannot modify readonly property C::$v")
   (fx :ro-init-outside-scope :readonly-v1 [66] #{:p4-s3}
       (str "<?php class C { public readonly int $v; }"
            " $c = new C; try { $c->v = 2; } catch (Error $e) { echo $e->getMessage(); }")
       "Cannot initialize readonly property C::$v from global scope")
   (fx :ro-unset-uninitialized-in-scope :readonly-v1 [67] #{:p4-s3}
       (str "<?php class C { public readonly int $v; function __construct() { unset($this->v); $this->v = 5; } }"
            " $c = new C; echo $c->v;")
       "5")
   (fx :ro-unset-initialized :readonly-v1 [67] #{:p4-s3}
       (str "<?php class C { public readonly int $v; function __construct($v) { $this->v = $v; }"
            " function u() { unset($this->v); } }"
            " $c = new C(1); try { $c->u(); } catch (Error $e) { echo $e->getMessage(); }")
       "Cannot unset readonly property C::$v")
   (fx :ro-unset-outside-scope :readonly-v1 [67] #{:p4-s3}
       (str "<?php class C { public readonly int $v; }"
            " $c = new C; try { unset($c->v); } catch (Error $e) { echo $e->getMessage(); }")
       "Cannot unset readonly property C::$v from global scope")
   (fx :ro-increment :readonly-v1 [68] #{:p4-s3}
       (str "<?php class C { public readonly int $v; function __construct($v) { $this->v = $v; }"
            " function i() { $this->v++; } }"
            " $c = new C(1); try { $c->i(); } catch (Error $e) { echo $e->getMessage(); }")
       "Cannot modify readonly property C::$v")
   (fx :ro-reference-refused :readonly-v1 [41 68] #{:p4-s3}
       (str "<?php class C { public readonly int $v; function __construct($v) { $this->v = $v; } }"
            " $c = new C(1); try { $r = &$c->v; } catch (Error $e) { echo $e->getMessage(); }")
       "Cannot modify readonly property C::$v")
   (fx :ro-clone-keeps-value :readonly-v1 [52 66] #{:p4-s3}
       (str "<?php class C { public readonly int $v; function __construct($v) { $this->v = $v; } }"
            " $c = new C(3); $d = clone $c; echo $d->v;")
       "3")
   (fx :ro-clone-reinit-in-clone :readonly-v1 [52 66] #{:p4-s3}
       (str "<?php class C { public readonly int $v; function __construct($v) { $this->v = $v; }"
            " function __clone() { $this->v = $this->v + 1; } }"
            " $c = new C(1); $d = clone $c; echo $c->v, $d->v;")
       "12")
   (fx :ro-clone-flag-clears :readonly-v1 [52 66] #{:p4-s3}
       (str "<?php class C { public readonly int $v; function __construct($v) { $this->v = $v; }"
            " function __clone() { $this->v = 9; } function again() { $this->v = 1; } }"
            " $c = new C(1); $d = clone $c; try { $d->again(); } catch (Error $e) { echo $e->getMessage(); }")
       "Cannot modify readonly property C::$v")
   (fx :ro-nested-object-write-allowed :readonly-v1 [66] #{:p4-s3}
       (str "<?php class D { public $x = 1; } class C { public readonly D $d;"
            " function __construct() { $this->d = new D; } }"
            " $c = new C; $c->d->x = 5; echo $c->d->x;")
       nil)
   (fx :ro-nested-array-write-refused :readonly-v1 [66] #{:p4-s3}
       (str "<?php class C { public readonly array $a; function __construct() { $this->a = [1]; } }"
            " $c = new C; try { $c->a[0] = 2; } catch (Error $e) { echo $e->getMessage(); }")
       "Cannot modify readonly property C::$a")])


(def f3-v1
  [(fx :f3-unset-destructs :f3-v1 [48] #{:p4-s3}
       "<?php class A { function __destruct() { echo 'D'; } } $a = new A; unset($a); echo 'x';"
       nil)
   (fx :f3-reassign-destructs :f3-v1 [48] #{:p4-s3}
       "<?php class A { function __destruct() { echo 'D'; } } $a = new A; $a = null; echo 'x';"
       "Dx")
   (fx :f3-temporary-destructs-at-statement-end :f3-v1 [48 37] #{:p4-s3}
       "<?php class A { function __destruct() { echo 'D'; } } new A; echo 'x';"
       "Dx")
   (fx :f3-scope-exit-destructs :f3-v1 [48] #{:p4-s3}
       (str "<?php class A { function __destruct() { echo 'D'; } }"
            " function f() { $a = new A; echo 'in'; } f(); echo 'out';")
       "inDout")
   (fx :f3-last-holder :f3-v1 [48] #{:p4-s3}
       (str "<?php class A { function __destruct() { echo 'D'; } }"
            " $a = new A; $b = $a; unset($a); echo 'x'; unset($b); echo 'y';")
       "xDy")
   (fx :f3-resurrection-destructs-once :f3-v1 [49] #{:p4-s3}
       (str "<?php class A { function __destruct() { global $keep; $keep = $this; echo 'D'; } }"
            " $a = new A; $a = null; echo 'x'; $keep = null; echo 'y';")
       "Dxy")
   (fx :f3-destructor-throws-propagates :f3-v1 [50] #{:p4-s3}
       (str "<?php class A { function __destruct() { throw new Exception('d'); } }"
            " $a = new A; try { unset($a); } catch (Exception $e) { echo $e->getMessage(); } echo 'z';")
       "dz")
   (fx :f3-destructor-throw-completes-releases :f3-v1 [50] #{:p4-s3}
       (str "<?php class A { function __destruct() { echo 'A'; throw new Exception('d'); } }"
            " class B { function __destruct() { echo 'B'; } }"
            " function f() { $a = new A; $b = new B; } try { f(); } catch (Exception $e) { echo $e->getMessage(); }")
       "ABd")
   (fx :f3-shutdown-order :f3-v1 [22] #{:p4-s3}
       (str "<?php class A { public $n; function __construct($n) { $this->n = $n; }"
            " function __destruct() { echo $this->n; } }"
            " $a = new A(1); $b = new A(2); echo 'x';")
       "x21")
   (fx :f3-shutdown-shared-object-ord-order :f3-v1 [22] #{:p4-s3}
       (str "<?php class A { public $n; function __construct($n) { $this->n = $n; }"
            " function __destruct() { echo $this->n; } }"
            " $a = new A(1); $b = new A(2); $c = $b; echo 'x';")
       "x12")
   (fx :f3-clone-copies-properties :f3-v1 [52] #{:p4-s3}
       "<?php class A { public $v = 0; } $a = new A; $a->v = 1; $b = clone $a; $b->v = 2; echo $a->v, $b->v;"
       "12")
   (fx :f3-clone-is-shallow :f3-v1 [52] #{:p4-s3}
       (str "<?php class A { public $o; } $a = new A; $a->o = new stdClass; $a->o->x = 1;"
            " $b = clone $a; $b->o->x = 2; echo $a->o->x;")
       "2")
   (fx :f3-clone-runs-clone-method :f3-v1 [52] #{:p4-s3}
       (str "<?php class A { public $v = 1; function __clone() { echo 'C'; $this->v = 5; } }"
            " $a = new A; $b = clone $a; echo $a->v, $b->v;")
       "C15")
   (fx :f3-clone-destructs-both :f3-v1 [48 52] #{:p4-s3}
       (str "<?php class A { function __destruct() { echo 'D'; } }"
            " $a = new A; $b = clone $a; unset($a); echo 'x'; unset($b); echo 'y';")
       "DxDy")
   (fx :f3-clone-non-object :f3-v1 [52] #{:p4-s3}
       "<?php try { $x = clone 5; } catch (Error $e) { echo $e->getMessage(); }"
       "__clone method called on non-object")
   (fx :f3-exception-caught-var-owns :f3-v1 [48] #{:p4-s3}
       (str "<?php class E extends Exception { function __destruct() { echo 'D'; } }"
            " try { throw new E('m'); } catch (E $e) { echo 'c'; } echo 'x'; unset($e); echo 'y';")
       "cxDy")
   (fx :f3-exception-without-variable-destructs :f3-v1 [48] #{:p4-s3}
       (str "<?php class E extends Exception { function __destruct() { echo 'D'; } }"
            " try { throw new E('m'); } catch (E) { echo 'c'; } echo 'x';")
       "Dcx")
   (fx :f3-shutdown-function-runs-first :f3-v1 [22] #{:p4-s3}
       (str "<?php class A { function __destruct() { echo 'D'; } }"
            " $a = new A; register_shutdown_function(function () { echo 'S'; }); echo 'x';")
       nil)
   (fx :f3-shutdown-functions-in-order :f3-v1 [22] #{:p4-s3}
       (str "<?php register_shutdown_function(function () { echo 'a'; });"
            " register_shutdown_function(function () { echo 'b'; }); echo 'x';")
       nil)
   (fx :f3-shutdown-function-registers-another :f3-v1 [22] #{:p4-s3}
       (str "<?php register_shutdown_function(function () {"
            " echo 'a'; register_shutdown_function(function () { echo 'c'; }); });"
            " register_shutdown_function(function () { echo 'b'; }); echo 'x';")
       nil)
   (fx :f3-shutdown-function-arguments :f3-v1 [22] #{:p4-s3}
       "<?php register_shutdown_function(function ($m, $n) { echo $m, $n; }, 'p', 'q'); echo 'x';"
       nil)
   (assoc (fx :f3-shutdown-after-uncaught :f3-v1 [22] #{:p4-s3}
              "<?php register_shutdown_function(function () { echo 'S'; }); throw new Exception('e');"
              nil)
          :throws true)])


(def declarations-v1
  "S5: parameter, return and property declarations, strict_types, promotion,
   the exception handler. Messages that PHP suffixes with the call site
   (`called in FILE on line N`) are not printed: the fixtures print the class."
  [(fx :decl-param-coerces-weak :declarations-v1 [] #{:p4-s5}
       "<?php function f(int $x) { var_dump($x); } f('5'); f(5.0); f(true);"
       "int(5)\nint(5)\nint(1)\n")
   (fx :decl-param-rejects-string :declarations-v1 [] #{:p4-s5}
       "<?php function f(int $x) { return $x; } try { f('x'); } catch (TypeError $e) { echo get_class($e); }"
       "TypeError")
   (fx :decl-param-strict-rejects-numeric-string :declarations-v1 [] #{:p4-s5}
       (str "<?php declare(strict_types=1); function f(int $x) { return $x; }"
            " try { f('5'); } catch (TypeError $e) { echo get_class($e); }")
       "TypeError")
   (fx :decl-param-strict-widens-int-to-float :declarations-v1 [] #{:p4-s5}
       "<?php declare(strict_types=1); function f(float $x) { var_dump($x); } f(5);"
       "float(5)\n")
   (fx :decl-weak-coerces-numeric-string-strict-rejects :declarations-v1 [] #{:p4-s5}
       "<?php function f(int $x) { return $x + 1; } echo f('5');"
       "6")
   (fx :decl-nullable-and-default-null :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(?int $a, int $b = null) { var_dump($a, $b); } f(null); f(1, 2);"
            " try { (function (int $x) {})(null); } catch (TypeError $e) { echo 'T'; }")
       "NULL\nNULL\nint(1)\nint(2)\nT")
   (fx :decl-union-prefers-exact :declarations-v1 [] #{:p4-s5}
       "<?php function f(int|string $x) { var_dump($x); } f('5'); f(5); f(5.0);"
       "string(1) \"5\"\nint(5)\nint(5)\n")
   (fx :decl-class-type :declarations-v1 [] #{:p4-s5}
       (str "<?php class A {} class B extends A {} function f(A $a) { return get_class($a); }"
            " echo f(new B); try { f(new stdClass); } catch (TypeError $e) { echo get_class($e); }")
       "BTypeError")
   (fx :decl-return-coerces :declarations-v1 [] #{:p4-s5}
       "<?php function f(): int { return '7'; } var_dump(f());"
       "int(7)\n")
   (fx :decl-return-rejects :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(): int { return 'x'; }"
            " try { f(); } catch (TypeError $e) { echo $e->getMessage(); }")
       "f(): Return value must be of type int, string returned")
   (fx :decl-return-none-returned :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(): int { }"
            " try { f(); } catch (TypeError $e) { echo $e->getMessage(); }")
       "f(): Return value must be of type int, none returned")
   (fx :decl-void-return :declarations-v1 [] #{:p4-s5}
       "<?php function f(): void { echo 'v'; return; } var_dump(f());"
       "vNULL\n")
   (fx :decl-method-return-message :declarations-v1 [] #{:p4-s5}
       (str "<?php class A { function m(): int { return 'x'; } }"
            " $a = new A; try { $a->m(); } catch (TypeError $e) { echo $e->getMessage(); }")
       "A::m(): Return value must be of type int, string returned")
   (fx :decl-property-coerces :declarations-v1 [] #{:p4-s5}
       "<?php class A { public int $n = 0; } $a = new A; $a->n = '5'; var_dump($a->n);"
       "int(5)\n")
   (fx :decl-property-rejects :declarations-v1 [] #{:p4-s5}
       (str "<?php class A { public int $n = 0; } $a = new A;"
            " try { $a->n = 'x'; } catch (TypeError $e) { echo $e->getMessage(); }")
       "Cannot assign string to property A::$n of type int")
   (fx :decl-property-strict :declarations-v1 [] #{:p4-s5}
       (str "<?php declare(strict_types=1); class A { public int $n = 0; } $a = new A;"
            " try { $a->n = '5'; } catch (TypeError $e) { echo get_class($e); }")
       "TypeError")
   (fx :decl-property-uninitialized-read :declarations-v1 [] #{:p4-s5}
       (str "<?php class A { public int $n; } $a = new A;"
            " try { echo $a->n; } catch (Error $e) { echo $e->getMessage(); }")
       "Typed property A::$n must not be accessed before initialization")
   (fx :decl-property-reference-keeps-type :declarations-v1 [41 42] #{:p4-s5}
       (str "<?php class A { public int $n = 1; } $a = new A; $r = &$a->n;"
            " try { $r = 'x'; } catch (TypeError $e) { echo $e->getMessage(); } $r = '9'; var_dump($a->n);")
       "Cannot assign string to reference held by property A::$n of type intint(9)\n")
   (fx :decl-promoted-typed :declarations-v1 [] #{:p4-s5}
       (str "<?php class P { function __construct(public int $x = 1, protected readonly string $s = 'a') {}"
            " function s() { return $this->s; } } $p = new P('7'); var_dump($p->x); echo $p->s();")
       "int(7)\na")
   (fx :decl-promoted-readonly :declarations-v1 [66] #{:p4-s5}
       (str "<?php class P { function __construct(public readonly int $x) {} }"
            " $p = new P(3); try { $p->x = 4; } catch (Error $e) { echo $e->getMessage(); }")
       "Cannot modify readonly property P::$x")
   (fx :decl-closure-types :declarations-v1 [] #{:p4-s5}
       (str "<?php $f = function (int $x): int { return $x * 2; }; echo $f('4');"
            " $g = fn(int $x) => $x + 1; var_dump($g('5'));")
       "8int(6)\n")
   (fx :decl-error-handler-throws-error-exception :declarations-v1 [] #{:p4-s5}
       (str "<?php set_error_handler(function ($no, $str) { throw new ErrorException($str, 0, $no); });"
            " $a = []; try { echo $a['k']; } catch (ErrorException $e) { echo get_class($e), $e->getSeverity(); }")
       "ErrorException2")
   (fx :decl-exception-handler :declarations-v1 [] #{:p4-s5}
       (str "<?php set_exception_handler(function ($e) { echo 'H', $e->getMessage(); });"
            " echo 'a'; throw new Exception('m'); echo 'never';")
       "aHm")
   (assoc (fx :decl-float-fraction-to-int-param :declarations-v1 [] #{:p4-s5}
              "<?php function f(int $x) { var_dump($x); } f(5.9); f('5.5');"
              "int(5)\nint(5)\n")
          :diag [[8192 "Implicit conversion from float 5.9 to int loses precision"]
                 [8192 "Implicit conversion from float-string \"5.5\" to int loses precision"]])
   (fx :decl-float-out-of-range-int-param :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(int $x) { return $x; }"
            " try { f(1e30); } catch (TypeError $e) { echo get_class($e); }")
       "TypeError")
   (fx :decl-byref-param-typed-coerces :declarations-v1 [] #{:p4-s5}
       "<?php function f(int &$x) { var_dump($x); } $a = '5'; f($a); var_dump($a);"
       "int(5)\nint(5)\n")
   (fx :decl-byref-param-typed-rejects :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(int &$x) { return $x; } $b = 'x';"
            " try { f($b); } catch (TypeError $e) { echo get_class($e); }")
       "TypeError")
   (fx :decl-false-type-is-not-bool :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(false $x) { return 1; } echo f(false);"
            " try { f(true); } catch (TypeError $e) { echo get_class($e); }")
       "1TypeError")
   (fx :decl-exception-handler-restore-pops :declarations-v1 [] #{:p4-s5}
       (str "<?php set_exception_handler(function ($e) { echo 'A'; });"
            " set_exception_handler(function ($e) { echo 'B'; });"
            " restore_exception_handler(); throw new Exception('m');")
       "A")
   (fx :decl-property-default-widens-int-to-float :declarations-v1 [] #{:p4-s5}
       "<?php class A { public float $f = 1; } $a = new A; var_dump($a->f);"
       "float(1)\n")
   (fx :decl-property-default-null-nullable :declarations-v1 [] #{:p4-s5}
       "<?php class A { public int|null $n = null; } $a = new A; var_dump($a->n);"
       "NULL\n")
   (fx :decl-byref-coercion-respects-property-type :declarations-v1 [41 45] #{:p4-s5}
       (str "<?php class A { public int $x = 1; } function f(float &$p) { var_dump($p); }"
            " $a = new A; try { f($a->x); } catch (TypeError $e) { echo get_class($e); } var_dump($a->x);")
       "TypeErrorint(1)\n")
   (fx :decl-byref-rejection-releases-ownership :declarations-v1 [48] #{:p4-s5}
       (str "<?php class D { function __destruct() { echo 'D'; } } function f(int &$x) {}"
            " $a = new D; try { f($a); } catch (TypeError $e) { echo 'c'; } unset($a); echo 'E';")
       "cDE")
   (fx :decl-union-literal-false-stays-false :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(int|false $x) { var_dump($x); } f(false);"
            " function g(string|true $x) { var_dump($x); } g(true);")
       "bool(false)\nbool(true)\n")
   (fx :decl-union-numeric-string-order-independent :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(int|float $x) { var_dump($x); } function g(float|int $x) { var_dump($x); }"
            " f('5.5'); g('5.5'); f('5'); g('5');")
       "float(5.5)\nfloat(5.5)\nint(5)\nint(5)\n")
   (fx :decl-static-property-default-widens :declarations-v1 [] #{:p4-s5}
       "<?php class A { public static float $f = 1; } var_dump(A::$f);"
       "float(1)\n")
   (assoc (fx :decl-property-default-literal-mismatch-is-fatal :declarations-v1 [] #{:p4-s5}
              "<?php class A { public int $x = 'bad'; } echo 'ran';"
              nil
              :oracle-only)
          :note "compile-time Fatal error: Cannot use string as default value for property A::$x of type int")
   (fx :decl-param-default-widens :declarations-v1 [] #{:p4-s5}
       "<?php function f(float $x = 1) { var_dump($x); } f();"
       "float(1)\n")
   (assoc (fx :decl-param-default-literal-mismatch-is-fatal :declarations-v1 [] #{:p4-s5}
              "<?php function f(int $x = 'bad') {} echo 'ran';"
              nil
              :oracle-only)
          :note "compile-time Fatal error: Cannot use string as default value for parameter $x of type int")
   (fx :decl-byref-class-type-accepts-property-value :declarations-v1 [41 45] #{:p4-s5}
       (str "<?php class A {} class H { public A $p; function __construct() { $this->p = new A; } }"
            " function f(A &$x) { return 1; } $h = new H; echo f($h->p);")
       "1")
   (fx :decl-byref-iterable-accepts-array-property :declarations-v1 [41 45] #{:p4-s5}
       (str "<?php class H { public array $p = [1]; } function f(iterable &$x) { return 1; }"
            " $h = new H; echo f($h->p);")
       "1")
   (fx :decl-byref-callable-accepts-callable-string-property :declarations-v1 [41 45] #{:p4-s5}
       (str "<?php class H { public string $p = 'strlen'; } function f(callable &$x) { return 1; }"
            " $h = new H; echo f($h->p);")
       "1")
   (fx :decl-union-overflowing-numeric-string-param :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(int|null $x) { var_dump($x); }"
            " try { f('9223372036854775808'); } catch (TypeError $e) { echo get_class($e); }")
       "TypeError")
   (fx :decl-union-overflowing-numeric-string-property :declarations-v1 [] #{:p4-s5}
       (str "<?php class A { public int|null $n = null; } $a = new A;"
            " try { $a->n = '9223372036854775808'; } catch (TypeError $e) { echo get_class($e); }"
            " var_dump($a->n);")
       "TypeErrorNULL\n")
   (fx :decl-exception-handler-then-shutdown :declarations-v1 [22] #{:p4-s5}
       (str "<?php set_exception_handler(function ($e) { echo 'H'; });"
            " register_shutdown_function(function () { echo 'S'; }); throw new Exception('m');")
       "HS")])


(def banner "php-8.3.35 shiva 2026-10-25")


(def recorded
  "Oracle RECORD values from shiva pass 6 (php-8.3.35, docker php:8.3-cli, branch
   tip ec54182f), copied exactly from the pass log, plus the seven pass-7
   records (branch tip 56b622ad; :exc-user-ctor-override was absent from pass 6,
   which ran its earlier strtoupper source). Withheld, escalated to the
   Architect: :f3-shutdown-after-uncaught (see `awaiting-oracle`)."
  {:cls-new-and-method {:out "1", :diag [], :uncaught nil}
   :cls-static-prop-shared {:out "22", :diag [], :uncaught nil}
   :cls-static-prop-redeclared-unshared {:out "15", :diag [], :uncaught nil}
   :cls-const-and-self {:out "3", :diag [], :uncaught nil}
   :cls-private-identity-per-class {:out "ab", :diag [], :uncaught nil}
   :cls-private-not-visible-outside {:out "Cannot access private property A::$p", :diag [], :uncaught nil}
   :cls-protected-from-subclass {:out "4", :diag [], :uncaught nil}
   :cls-late-static-binding {:out "B", :diag [], :uncaught nil}
   :cls-abstract-instantiation {:out "Cannot instantiate abstract class A", :diag [], :uncaught nil}
   :cls-interface-instanceof {:out "bool(true)\n", :diag [], :uncaught nil}
   :cls-interface-constant {:out "kk", :diag [], :uncaught nil}
   :cls-trait-method {:out "hi", :diag [], :uncaught nil}
   :cls-trait-static-per-user {:out "31", :diag [], :uncaught nil}
   :cls-trait-self-is-user {:out "A", :diag [], :uncaught nil}
   :cls-trait-conflict-insteadof {:out "12", :diag [], :uncaught nil}
   :cls-enum-pure {:out "bool(true)\nbool(false)\n", :diag [], :uncaught nil}
   :cls-enum-backed-value {:out "onOff", :diag [], :uncaught nil}
   :cls-enum-cases {:out "AB", :diag [], :uncaught nil}
   :cls-enum-from-tryfrom {:out "OneNULL\n2 is not a valid backing value for enum S", :diag [], :uncaught nil}
   :cls-hoisting {:out "7", :diag [], :uncaught nil}
   :cls-anonymous-refused {:out "1", :diag [], :uncaught nil}
   :cls-magic-get-set-call {:out "5nonego2", :diag [], :uncaught nil}
   :cls-magic-tostring {:out "str3", :diag [], :uncaught nil}
   :cls-object-var-dump {:out "object(P)#1 (3) {\n  [\"a\"]=>\n  int(1)\n  [\"b\":protected]=>\n  int(2)\n  [\"c\":\"P\":private]=>\n  int(3)\n}\n", :diag [], :uncaught nil}
   :cls-iterate-properties-visibility {:out "aab", :diag [], :uncaught nil}
   :ctor-runs-with-args {:out "5", :diag [], :uncaught nil}
   :ctor-promotion {:out "34", :diag [], :uncaught nil}
   :ctor-arg-throws-skips-destructor {:out "caught", :diag [], :uncaught nil}
   :ctor-throws-skips-destructor {:out "caught", :diag [], :uncaught nil}
   :ctor-named-args {:out "19", :diag [], :uncaught nil}
   :ctor-parent-call {:out "a", :diag [], :uncaught nil}
   :ctor-destruct-order-at-end {:out "x21", :diag [], :uncaught nil}
   :ctor-destruct-on-unset {:out "Dx", :diag [], :uncaught nil}
   :ctor-destruct-resurrect {:out "Dxy", :diag [], :uncaught nil}
   :ctor-clone {:out "C12", :diag [], :uncaught nil}
   :exc-throw-catch-object {:out "Em", :diag [], :uncaught nil}
   :exc-hierarchy-catch-base {:out "L", :diag [], :uncaught nil}
   :exc-error-not-exception {:out "T", :diag [], :uncaught nil}
   :exc-multi-catch {:out "TypeError", :diag [], :uncaught nil}
   :exc-catch-without-variable {:out "c", :diag [], :uncaught nil}
   :exc-previous-chain {:out "p3", :diag [], :uncaught nil}
   :exc-finally-overrides-return {:out "2", :diag [], :uncaught nil}
   :exc-finally-rethrow-runs {:out "Fa", :diag [], :uncaught nil}
   :exc-line-number {:out "3", :diag [], :uncaught nil}
   :exc-destructor-throws {:out "d", :diag [], :uncaught nil}
   :exc-match-unhandled {:out "Unhandled match case 5", :diag [], :uncaught nil}
   :exc-uncaught-fatal {:out "a", :diag [], :uncaught {:class "E", :message "boom"}}
   :exc-named-arg-validation-order {:out "Unknown named parameter $z", :diag [], :uncaught nil}
   :ro-init-once {:out "1", :diag [], :uncaught nil}
   :ro-modify-throws {:out "Cannot modify readonly property C::$v", :diag [], :uncaught nil}
   :ro-init-outside-scope {:out "Cannot initialize readonly property C::$v from global scope", :diag [], :uncaught nil}
   :ro-unset-uninitialized-in-scope {:out "5", :diag [], :uncaught nil}
   :ro-unset-initialized {:out "Cannot unset readonly property C::$v", :diag [], :uncaught nil}
   :ro-unset-outside-scope {:out "Cannot unset readonly property C::$v from global scope", :diag [], :uncaught nil}
   :ro-increment {:out "Cannot modify readonly property C::$v", :diag [], :uncaught nil}
   :ro-reference-refused {:out "Cannot modify readonly property C::$v", :diag [], :uncaught nil}
   :ro-clone-keeps-value {:out "3", :diag [], :uncaught nil}
   :ro-clone-reinit-in-clone {:out "12", :diag [], :uncaught nil}
   :ro-clone-flag-clears {:out "Cannot modify readonly property C::$v", :diag [], :uncaught nil}
   :f3-unset-destructs {:out "Dx", :diag [], :uncaught nil}
   :f3-reassign-destructs {:out "Dx", :diag [], :uncaught nil}
   :f3-temporary-destructs-at-statement-end {:out "Dx", :diag [], :uncaught nil}
   :f3-scope-exit-destructs {:out "inDout", :diag [], :uncaught nil}
   :f3-last-holder {:out "xDy", :diag [], :uncaught nil}
   :f3-resurrection-destructs-once {:out "Dxy", :diag [], :uncaught nil}
   :f3-destructor-throws-propagates {:out "dz", :diag [], :uncaught nil}
   :f3-destructor-throw-completes-releases {:out "ABd", :diag [], :uncaught nil}
   :f3-shutdown-order {:out "x21", :diag [], :uncaught nil}
   :f3-shutdown-shared-object-ord-order {:out "x12", :diag [], :uncaught nil}
   :f3-clone-copies-properties {:out "12", :diag [], :uncaught nil}
   :f3-clone-is-shallow {:out "2", :diag [], :uncaught nil}
   :f3-clone-runs-clone-method {:out "C15", :diag [], :uncaught nil}
   :f3-clone-destructs-both {:out "DxDy", :diag [], :uncaught nil}
   :f3-clone-non-object {:out "__clone method called on non-object", :diag [], :uncaught nil}
   :f3-exception-caught-var-owns {:out "cxDy", :diag [], :uncaught nil}
   :f3-exception-without-variable-destructs {:out "Dcx", :diag [], :uncaught nil}
   :exc-user-ctor-override {:out "xabc", :diag [], :uncaught nil}
   :ro-nested-object-write-allowed {:out "5", :diag [], :uncaught nil}
   :ro-nested-array-write-refused {:out "Cannot modify readonly property C::$a", :diag [], :uncaught nil}
   :f3-shutdown-function-runs-first {:out "xSD", :diag [], :uncaught nil}
   :f3-shutdown-functions-in-order {:out "xab", :diag [], :uncaught nil}
   :f3-shutdown-function-registers-another {:out "xabc", :diag [], :uncaught nil}
   :f3-shutdown-function-arguments {:out "xpq", :diag [], :uncaught nil}})


(defn- pin
  "The fixture with its recorded oracle result folded in: `:oracle` becomes the
   banner and a fixture without a pinned `:out` takes the recorded one."
  [fixture]
  (if-let [r (get recorded (:id fixture))]
    (cond-> (assoc fixture :oracle banner)
      (not (contains? fixture :out)) (assoc :out (:out r)))
    fixture))


(def all-fixtures
  "Unconditional registry; the oracle iterates it regardless of activation."
  (mapv pin (concat classes-v1 constructor-v1 exceptions-v1 readonly-v1 f3-v1 declarations-v1)))


(defn enabled?
  "True when every step the fixture requires has landed. Oracle-only
   fixtures never run on the implementation."
  [fixture]
  (and (not= :oracle-only (:level fixture))
       (every? completed-steps (:requires fixture))))


(def enabled
  "The subset implementation runners execute."
  (vec (filter enabled? all-fixtures)))


(def awaiting-oracle
  "The enabled fixtures that are still `:oracle :pending`: the S5 declarations
   not yet recorded by an oracle pass, and :f3-shutdown-after-uncaught, whose
   pass-7 record (no shutdown function output after an uncaught exception)
   contradicts the design's row 22 and is escalated to the Architect (17.3), not
   folded. A fold shrinks this set; changing it is a reviewed edit (Architect
   ruling on P4 activation: every enabled pending fixture is recorded before
   its checkpoint closes)."
  #{:decl-byref-class-type-accepts-property-value
    :decl-byref-iterable-accepts-array-property
    :decl-byref-callable-accepts-callable-string-property
    :decl-union-overflowing-numeric-string-param
    :decl-union-overflowing-numeric-string-property
    :decl-byref-coercion-respects-property-type
    :decl-byref-param-typed-coerces
    :decl-byref-param-typed-rejects
    :decl-byref-rejection-releases-ownership
    :decl-class-type
    :decl-closure-types
    :decl-error-handler-throws-error-exception
    :decl-exception-handler
    :decl-exception-handler-restore-pops
    :decl-exception-handler-then-shutdown
    :decl-false-type-is-not-bool
    :decl-float-fraction-to-int-param
    :decl-float-out-of-range-int-param
    :decl-method-return-message
    :decl-nullable-and-default-null
    :decl-param-coerces-weak
    :decl-param-default-widens
    :decl-param-rejects-string
    :decl-param-strict-rejects-numeric-string
    :decl-param-strict-widens-int-to-float
    :decl-promoted-readonly
    :decl-promoted-typed
    :decl-property-coerces
    :decl-property-default-null-nullable
    :decl-property-default-widens-int-to-float
    :decl-property-reference-keeps-type
    :decl-property-rejects
    :decl-property-strict
    :decl-property-uninitialized-read
    :decl-return-coerces
    :decl-return-none-returned
    :decl-return-rejects
    :decl-static-property-default-widens
    :decl-union-literal-false-stays-false
    :decl-union-numeric-string-order-independent
    :decl-union-prefers-exact
    :decl-void-return
    :decl-weak-coerces-numeric-string-strict-rejects
    :f3-shutdown-after-uncaught})
