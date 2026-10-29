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
  (:require
    [clojure.string]
    [yang.php.antlr.programs-p3b :as p3b]))


(def completed-steps
  "P4 steps that have landed: S1 (descriptors) and S2 (objects, methods,
   visibility). A fixture whose :requires name a later step stays disabled."
  #{:p4-s1 :p4-s2 :p4-s3 :p4-s4 :p4-s5 :p4-s6})


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
       nil)
   (fx :decl-param-rejects-string :declarations-v1 [] #{:p4-s5}
       "<?php function f(int $x) { return $x; } try { f('x'); } catch (TypeError $e) { echo get_class($e); }"
       nil)
   (fx :decl-param-strict-rejects-numeric-string :declarations-v1 [] #{:p4-s5}
       (str "<?php declare(strict_types=1); function f(int $x) { return $x; }"
            " try { f('5'); } catch (TypeError $e) { echo get_class($e); }")
       nil)
   (fx :decl-param-strict-widens-int-to-float :declarations-v1 [] #{:p4-s5}
       "<?php declare(strict_types=1); function f(float $x) { var_dump($x); } f(5);"
       nil)
   (fx :decl-weak-coerces-numeric-string-strict-rejects :declarations-v1 [] #{:p4-s5}
       "<?php function f(int $x) { return $x + 1; } echo f('5');"
       nil)
   (fx :decl-nullable-and-default-null :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(?int $a, int $b = null) { var_dump($a, $b); } f(null); f(1, 2);"
            " try { (function (int $x) {})(null); } catch (TypeError $e) { echo 'T'; }")
       nil)
   (fx :decl-union-prefers-exact :declarations-v1 [] #{:p4-s5}
       "<?php function f(int|string $x) { var_dump($x); } f('5'); f(5); f(5.0);"
       nil)
   (fx :decl-class-type :declarations-v1 [] #{:p4-s5}
       (str "<?php class A {} class B extends A {} function f(A $a) { return get_class($a); }"
            " echo f(new B); try { f(new stdClass); } catch (TypeError $e) { echo get_class($e); }")
       nil)
   (fx :decl-return-coerces :declarations-v1 [] #{:p4-s5}
       "<?php function f(): int { return '7'; } var_dump(f());"
       nil)
   (fx :decl-return-rejects :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(): int { return 'x'; }"
            " try { f(); } catch (TypeError $e) { echo $e->getMessage(); }")
       nil)
   (fx :decl-return-none-returned :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(): int { }"
            " try { f(); } catch (TypeError $e) { echo $e->getMessage(); }")
       nil)
   (fx :decl-void-return :declarations-v1 [] #{:p4-s5}
       "<?php function f(): void { echo 'v'; return; } var_dump(f());"
       nil)
   (fx :decl-method-return-message :declarations-v1 [] #{:p4-s5}
       (str "<?php class A { function m(): int { return 'x'; } }"
            " $a = new A; try { $a->m(); } catch (TypeError $e) { echo $e->getMessage(); }")
       nil)
   (fx :decl-property-coerces :declarations-v1 [] #{:p4-s5}
       "<?php class A { public int $n = 0; } $a = new A; $a->n = '5'; var_dump($a->n);"
       nil)
   (fx :decl-property-rejects :declarations-v1 [] #{:p4-s5}
       (str "<?php class A { public int $n = 0; } $a = new A;"
            " try { $a->n = 'x'; } catch (TypeError $e) { echo $e->getMessage(); }")
       nil)
   (fx :decl-property-strict :declarations-v1 [] #{:p4-s5}
       (str "<?php declare(strict_types=1); class A { public int $n = 0; } $a = new A;"
            " try { $a->n = '5'; } catch (TypeError $e) { echo get_class($e); }")
       nil)
   (fx :decl-property-uninitialized-read :declarations-v1 [] #{:p4-s5}
       (str "<?php class A { public int $n; } $a = new A;"
            " try { echo $a->n; } catch (Error $e) { echo $e->getMessage(); }")
       nil)
   (fx :decl-property-reference-keeps-type :declarations-v1 [41 42] #{:p4-s5}
       (str "<?php class A { public int $n = 1; } $a = new A; $r = &$a->n;"
            " try { $r = 'x'; } catch (TypeError $e) { echo $e->getMessage(); } $r = '9'; var_dump($a->n);")
       nil)
   (fx :decl-promoted-typed :declarations-v1 [] #{:p4-s5}
       (str "<?php class P { function __construct(public int $x = 1, protected readonly string $s = 'a') {}"
            " function s() { return $this->s; } } $p = new P('7'); var_dump($p->x); echo $p->s();")
       nil)
   (fx :decl-promoted-readonly :declarations-v1 [66] #{:p4-s5}
       (str "<?php class P { function __construct(public readonly int $x) {} }"
            " $p = new P(3); try { $p->x = 4; } catch (Error $e) { echo $e->getMessage(); }")
       nil)
   (fx :decl-closure-types :declarations-v1 [] #{:p4-s5}
       (str "<?php $f = function (int $x): int { return $x * 2; }; echo $f('4');"
            " $g = fn(int $x) => $x + 1; var_dump($g('5'));")
       nil)
   (fx :decl-error-handler-throws-error-exception :declarations-v1 [] #{:p4-s5}
       (str "<?php set_error_handler(function ($no, $str) { throw new ErrorException($str, 0, $no); });"
            " $a = []; try { echo $a['k']; } catch (ErrorException $e) { echo get_class($e), $e->getSeverity(); }")
       nil)
   (fx :decl-exception-handler :declarations-v1 [] #{:p4-s5}
       (str "<?php set_exception_handler(function ($e) { echo 'H', $e->getMessage(); });"
            " echo 'a'; throw new Exception('m'); echo 'never';")
       nil)
   (assoc (fx :decl-float-fraction-to-int-param :declarations-v1 [] #{:p4-s5}
              "<?php function f(int $x) { var_dump($x); } f(5.9); f('5.5');"
              nil)
          :diag [[8192 "Implicit conversion from float 5.9 to int loses precision"]
                 [8192 "Implicit conversion from float-string \"5.5\" to int loses precision"]])
   (fx :decl-float-out-of-range-int-param :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(int $x) { return $x; }"
            " try { f(1e30); } catch (TypeError $e) { echo get_class($e); }")
       nil)
   (fx :decl-byref-param-typed-coerces :declarations-v1 [] #{:p4-s5}
       "<?php function f(int &$x) { var_dump($x); } $a = '5'; f($a); var_dump($a);"
       nil)
   (fx :decl-byref-param-typed-rejects :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(int &$x) { return $x; } $b = 'x';"
            " try { f($b); } catch (TypeError $e) { echo get_class($e); }")
       nil)
   (fx :decl-false-type-is-not-bool :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(false $x) { return 1; } echo f(false);"
            " try { f(true); } catch (TypeError $e) { echo get_class($e); }")
       nil)
   (fx :decl-exception-handler-restore-pops :declarations-v1 [] #{:p4-s5}
       (str "<?php set_exception_handler(function ($e) { echo 'A'; });"
            " set_exception_handler(function ($e) { echo 'B'; });"
            " restore_exception_handler(); throw new Exception('m');")
       nil)
   (fx :decl-property-default-widens-int-to-float :declarations-v1 [] #{:p4-s5}
       "<?php class A { public float $f = 1; } $a = new A; var_dump($a->f);"
       nil)
   (fx :decl-property-default-null-nullable :declarations-v1 [] #{:p4-s5}
       "<?php class A { public int|null $n = null; } $a = new A; var_dump($a->n);"
       nil)
   (fx :decl-byref-coercion-respects-property-type :declarations-v1 [41 45] #{:p4-s5}
       (str "<?php class A { public int $x = 1; } function f(float &$p) { var_dump($p); }"
            " $a = new A; try { f($a->x); } catch (TypeError $e) { echo get_class($e); } var_dump($a->x);")
       nil)
   (assoc (fx :decl-byref-rejection-releases-ownership :declarations-v1 [48] #{:p4-s5}
              (str "<?php class D { function __destruct() { echo 'D'; } } function f(int &$x) {}"
                   " $a = new D; try { f($a); } catch (TypeError $e) { echo 'c'; } unset($a); echo 'E';")
              nil)
          :departure {:gap "C30", :zend "cED"}
          :impl-out "cDE"
          :note "C30: PHP's TypeError trace holds the rejected argument until $e is freed at shutdown; here traces are empty and the argument is released at the throw")
   (fx :decl-byref-rejection-exception-freed-then-unset :declarations-v1 [48] #{:p4-s5}
       (str "<?php class D { function __destruct() { echo 'D'; } } function f(int &$x) {}"
            " $a = new D; try { f($a); } catch (TypeError $e) { echo 'c'; } $e = null; unset($a); echo 'E';")
       "cDE")
   (fx :decl-byref-later-rejection-releases-earlier-param :declarations-v1 [48] #{:p4-s6}
       (str "<?php class D { function __destruct() { echo 'D'; } } function f(D &$a, int &$b) {}"
            " $a = new D; $b = 'bad'; try { f($a, $b); } catch (TypeError $e) { echo 'c'; }"
            " $e = null; unset($a); echo 'E';")
       "cDE")
   (fx :decl-union-literal-false-stays-false :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(int|false $x) { var_dump($x); } f(false);"
            " function g(string|true $x) { var_dump($x); } g(true);")
       nil)
   (fx :decl-union-numeric-string-order-independent :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(int|float $x) { var_dump($x); } function g(float|int $x) { var_dump($x); }"
            " f('5.5'); g('5.5'); f('5'); g('5');")
       nil)
   (fx :decl-static-property-default-widens :declarations-v1 [] #{:p4-s5}
       "<?php class A { public static float $f = 1; } var_dump(A::$f);"
       nil)
   (assoc (fx :decl-property-default-literal-mismatch-is-fatal :declarations-v1 [] #{:p4-s5}
              "<?php class A { public int $x = 'bad'; } echo 'ran';"
              nil
              :oracle-only)
          :note "compile-time Fatal error: Cannot use string as default value for property A::$x of type int")
   (fx :decl-param-default-widens :declarations-v1 [] #{:p4-s5}
       "<?php function f(float $x = 1) { var_dump($x); } f();"
       nil)
   (assoc (fx :decl-param-default-literal-mismatch-is-fatal :declarations-v1 [] #{:p4-s5}
              "<?php function f(int $x = 'bad') {} echo 'ran';"
              nil
              :oracle-only)
          :note "compile-time Fatal error: Cannot use string as default value for parameter $x of type int")
   (fx :decl-byref-class-type-accepts-property-value :declarations-v1 [41 45] #{:p4-s5}
       (str "<?php class A {} class H { public A $p; function __construct() { $this->p = new A; } }"
            " function f(A &$x) { return 1; } $h = new H; echo f($h->p);")
       nil)
   (fx :decl-byref-iterable-accepts-array-property :declarations-v1 [41 45] #{:p4-s5}
       (str "<?php class H { public array $p = [1]; } function f(iterable &$x) { return 1; }"
            " $h = new H; echo f($h->p);")
       nil)
   (fx :decl-byref-callable-accepts-callable-string-property :declarations-v1 [41 45] #{:p4-s5}
       (str "<?php class H { public string $p = 'strlen'; } function f(callable &$x) { return 1; }"
            " $h = new H; echo f($h->p);")
       nil)
   (fx :decl-union-overflowing-numeric-string-param :declarations-v1 [] #{:p4-s5}
       (str "<?php function f(int|null $x) { var_dump($x); }"
            " try { f('9223372036854775808'); } catch (TypeError $e) { echo get_class($e); }")
       nil)
   (fx :decl-union-overflowing-numeric-string-property :declarations-v1 [] #{:p4-s5}
       (str "<?php class A { public int|null $n = null; } $a = new A;"
            " try { $a->n = '9223372036854775808'; } catch (TypeError $e) { echo get_class($e); }"
            " var_dump($a->n);")
       nil)
   (fx :decl-exception-handler-then-shutdown :declarations-v1 [22] #{:p4-s5}
       (str "<?php set_exception_handler(function ($e) { echo 'H'; });"
            " register_shutdown_function(function () { echo 'S'; }); throw new Exception('m');")
       nil)])


(def closures-v1
  "S6: closure binding and effective scope, callable forms, `static` and `never`
   return types. Every fixture waits for the oracle pass that records it."
  [(fx :clo-this-captured-in-method :closures-v1 [] #{:p4-s6}
       "<?php class A { private $p = 'a'; function f() { return function () { return $this->p; }; } } $a = new A; $g = $a->f(); echo $g();"
       "a")
   (fx :clo-static-closure-has-no-this :closures-v1 [] #{:p4-s6}
       "<?php class A { function f() { return static function () { return isset($this) ? 'y' : 'n'; }; } } $g = (new A)->f(); echo $g();"
       "n")
   (fx :clo-bind-grants-private-access :closures-v1 [] #{:p4-s6}
       "<?php class A { private $p = 'a'; } $f = function () { return $this->p; }; echo Closure::bind($f, new A, A::class)();"
       "a")
   (fx :clo-bindto-without-scope-keeps-scope :closures-v1 [] #{:p4-s6}
       "<?php class A { private $p = 1; } $f = function () { return $this->p; }; $b = $f->bindTo(new A); try { $b(); } catch (Error $e) { echo $e->getMessage(); }"
       "Cannot access private property A::$p")
   (fx :clo-bindto-scope-by-name :closures-v1 [] #{:p4-s6}
       "<?php class A { private $p = 'n'; } $f = function () { return $this->p; }; $b = $f->bindTo(new A, 'A'); echo $b();"
       "n")
   (fx :clo-call-binds-this-and-scope :closures-v1 [] #{:p4-s6}
       "<?php class A { private $p = 7; } $f = fn() => $this->p; echo $f->call(new A);"
       "7")
   (fx :clo-call-passes-arguments :closures-v1 [] #{:p4-s6}
       "<?php class P { public $v = 2; } $f = fn($x) => $x * $this->v; echo $f->call(new P, 4);"
       "8")
   (fx :clo-bound-this-sets-static :closures-v1 [] #{:p4-s6}
       "<?php class A {} class B extends A {} $f = function () { return static::class; }; echo $f->bindTo(new B)();"
       "B")
   (fx :clo-closure-keeps-called-class :closures-v1 [] #{:p4-s6}
       "<?php class A { static function make() { return function () { return static::class; }; } } class B extends A {} $f = B::make(); echo $f();"
       "B")
   (fx :clo-callable-array-string-forms :closures-v1 [] #{:p4-s6}
       "<?php class A { static function m($x) { return $x . 'm'; } function i($x) { return $x . 'i'; } } $f = [new A, 'i']; echo $f(1); $g = 'A::m'; echo $g(2); $h = ['A', 'm']; echo $h(3);"
       "1i2m3m")
   (fx :clo-callable-private-from-inside :closures-v1 [] #{:p4-s6}
       "<?php class A { private function p() { return 'x'; } function get() { return [$this, 'p']; } function run() { $c = $this->get(); return $c(); } } echo (new A)->run();"
       "x")
   (fx :clo-callable-private-from-outside :closures-v1 [] #{:p4-s6}
       "<?php class A { private function p() { return 'x'; } function get() { return [$this, 'p']; } } $c = (new A)->get(); try { $c(); } catch (Error $e) { echo $e->getMessage(); }"
       "Call to private method A::p() from global scope")
   (fx :clo-from-callable-function :closures-v1 [] #{:p4-s6}
       "<?php function twice($x) { return $x * 2; } $f = Closure::fromCallable('twice'); echo $f(4);"
       "8")
   (fx :clo-from-callable-invokable :closures-v1 [] #{:p4-s6}
       "<?php class A { function __invoke($x) { return $x + 1; } } $f = Closure::fromCallable(new A); echo $f(1);"
       "2")
   (fx :clo-from-callable-method :closures-v1 [] #{:p4-s6}
       "<?php class A { function m($x) { return $x . 'm'; } } $f = Closure::fromCallable([new A, 'm']); echo $f(5);"
       "5m")
   (fx :clo-trait-self-and-static :closures-v1 [] #{:p4-s6}
       "<?php trait T { function mk() { return function () { return self::class . static::class; }; } } class A { use T; } class B extends A {} $f = (new B)->mk(); echo $f();"
       "AB")
   (fx :clo-trait-closure-this :closures-v1 [] #{:p4-s6}
       "<?php trait T { function mk() { return fn() => $this->v; } } class A { use T; private $v = 'tv'; } $f = (new A)->mk(); echo $f();"
       "tv")
   (fx :clo-nested-closure-keeps-this :closures-v1 [] #{:p4-s6}
       "<?php class A { private $p = 'p'; function f() { return function () { return function () { return $this->p; }; }; } } $a = new A; echo $a->f()()();"
       "p")
   (fx :decl-static-return-type :closures-v1 [] #{:p4-s6}
       "<?php class A { static function make(): static { return new static; } } class B extends A {} echo get_class(B::make());"
       "B")
   (fx :decl-never-throws :closures-v1 [] #{:p4-s6}
       "<?php function f(): never { throw new Exception('x'); } try { f(); } catch (Exception $e) { echo $e->getMessage(); }"
       "x")
   (fx :decl-never-implicit-return-is-type-error :closures-v1 [] #{:p4-s6}
       "<?php function g(): never {} try { g(); } catch (TypeError $e) { echo $e->getMessage(); }"
       "g(): never-returning function must not implicitly return")
   (fx :decl-signed-literal-defaults-accepted :closures-v1 [] #{:p4-s6}
       "<?php class A { public int $n = -1; public float $f = -2; public array $a = [1]; public int|string $u = 'x'; } $a = new A; var_dump($a->n, $a->f);"
       "int(-1)\nfloat(-2)\n")])


(def objects-v1
  "The checkpoint-B object gates the earlier groups did not cover: typed static
   and destructured references (rows 46, 47), exit in a destructor (51), the
   read-write fetch on an uninitialized typed property (59), shutdown with
   destructors that create globals (22), named arguments with object values
   and the string-key spread departure (57, 65). Also the constructor,
   readonly and F3 programs of section 4.7. A fixture whose :requires names a
   step or feature that has not landed stays disabled and is the work list."
  [(fx :obj-row46-static-typed-ref-source :objects-v1 [46] #{:p4-s6}
       "<?php class A { public static int $s = 1; } $r = &A::$s; try { $r = 'x'; } catch (TypeError $e) { echo $e->getMessage(); } $r = '9'; var_dump(A::$s);"
       "Cannot assign string to reference held by property A::$s of type intint(9)\n")
   (fx :obj-row47-destructure-by-ref-into-typed-property :objects-v1 [47] #{:p4-s6 :p4-row47}
       "<?php class C { public int $p = 0; } $o = new C; $a = [5]; [&$o->p] = $a; try { $a[0] = 'x'; } catch (TypeError $e) { echo $e->getMessage(); } echo $o->p;"
       "Cannot assign string to reference held by property C::$p of type int5")
   (fx :obj-row51-exit-in-destructor :objects-v1 [51] #{:p4-s6 :p4-exit}
       "<?php class D { function __destruct() { echo 'd'; exit; } } $d = new D; $d = null; echo 'never';"
       "d")
   (fx :obj-row59-uninitialized-increment :objects-v1 [59] #{:p4-s6}
       "<?php class C { public int $p; } $c = new C; try { $c->p++; } catch (Error $e) { echo $e->getMessage(); }"
       "Typed property C::$p must not be accessed before initialization")
   (fx :obj-row59-uninitialized-concat-assign :objects-v1 [59] #{:p4-s6}
       "<?php class C { public string $p; } $c = new C; try { $c->p .= 'x'; } catch (Error $e) { echo $e->getMessage(); }"
       "Typed property C::$p must not be accessed before initialization")
   (fx :obj-row59-dim-write-autovivifies-array :objects-v1 [59] #{:p4-s6}
       "<?php class C { public array $p; } $c = new C; $c->p[] = 1; echo count($c->p);"
       "1")
   (fx :obj-row59-dim-write-refuses-int :objects-v1 [59] #{:p4-s6}
       "<?php class C { public int $p; } $c = new C; try { $c->p[] = 1; } catch (Error $e) { echo $e->getMessage(); }"
       "Cannot auto-initialize an array inside property C::$p of type int")
   (fx :obj-row22-destructor-creates-global :objects-v1 [22] #{:p4-s6}
       "<?php class B { function __destruct() { echo 'b'; } } class A { function __destruct() { global $next; $next = new B; echo 'a'; } } $x = new A;"
       "ab")
   (fx :obj-row57-unknown-named-object-argument-destroyed :objects-v1 [57] #{:p4-s6}
       "<?php class D { function __destruct() { echo 'D'; } } function f($x) {} try { f(bad: new D); } catch (Error $e) { echo get_class($e); }"
       "DError")
   (fx :obj-row57-unknown-named-by-ref-callee-no-vivification :objects-v1 [57] #{:p4-s6}
       "<?php function g(&$x) {} try { g(bad: $u); } catch (Error $e) { echo get_class($e); } var_dump(isset($u));"
       "Errorbool(false)\n")
   (assoc (fx :obj-row65-spread-string-keys :objects-v1 [65] #{:p4-s6}
              "<?php function f($a, $b) { return $a - $b; } echo f(...['b' => 1, 'a' => 2]);"
              nil)
          :departure {:gap "C25", :zend "1"}
          :impl-out ""
          :throws true)
   (fx :obj-row65-positional-after-string-key-spread :objects-v1 [65] #{:p4-s6}
       "<?php function f($a, $b) { return $a - $b; } try { echo f(...['a' => 1], ...[2]); } catch (Error $e) { echo 'E'; }"
       nil)
   (fx :ctor-no-constructor-zero-args :constructor-v1 [53] #{:p4-s6}
       "<?php class A {} $a = new A(); echo get_class($a);"
       "A")
   (fx :ctor-no-constructor-side-effecting-args :constructor-v1 [53] #{:p4-s6}
       "<?php class A {} function s() { echo 'S'; return 1; } new A(s()); echo 'x';"
       "Sx")
   (fx :ctor-private-constructor-text :constructor-v1 [53] #{:p4-s6}
       "<?php class A { private function __construct() {} } try { new A; } catch (Error $e) { echo $e->getMessage(); }"
       "Call to private A::__construct() from global scope")
   (fx :ro-clone-unset-twice :readonly-v1 [52 67] #{:p4-s6}
       "<?php class C { public readonly int $x; function __construct() { $this->x = 1; } function __clone() { unset($this->x); unset($this->x); $this->x = 2; } } $a = new C; $b = clone $a; echo $a->x, $b->x;"
       "12")
   (fx :ro-clone-uninitialized-unset :readonly-v1 [52 67] #{:p4-s6}
       "<?php class C { public readonly int $x; function __clone() { unset($this->x); $this->x = 5; } } $a = new C; $b = clone $a; echo $b->x;"
       "5")
   (fx :ro-clone-child-modifies-parent-readonly :readonly-v1 [52 66] #{:p4-s6}
       "<?php class P { public readonly int $x; function __construct() { $this->x = 1; } } class C extends P { function __clone() { $this->x = 2; } } $a = new C; try { $b = clone $a; echo $b->x; } catch (Error $e) { echo $e->getMessage(); }"
       nil)
   (fx :ro-f3-unset-then-reassign-in-clone :readonly-v1 [52 66 67] #{:p4-s6}
       "<?php class C { public readonly int $x; function __construct() { $this->x = 1; } function __clone() { unset($this->x); $this->x = 2; } } $a = new C; $b = clone $a; echo $b->x;"
       "2")
   (fx :ro-uninitialized-reference-text :readonly-v1 [41 68] #{:p4-s6}
       "<?php class C { public readonly int $x; } $c = new C; try { $r = &$c->x; } catch (Error $e) { echo $e->getMessage(); }"
       "Cannot indirectly modify readonly property C::$x")])


(def generators-v1
  "S7: generator control transfer and activation ownership over the C2
   continuation core. Kept outside `all-fixtures` while the checkpoint-B
   oracle batch is fixed at 50 sources. Register them for the checkpoint-C
   pass after B closes; they stay disabled until :p4-s7 lands."
  [(fx :gen-key-value-and-return :generators-v1 [] #{:p4-s7}
       "<?php function g() { yield 'k' => 'v'; return 9; } $g = g(); foreach ($g as $k => $v) { echo $k, $v; } var_dump($g->getReturn());"
       nil)
   (fx :gen-send-resumes-yield-expression :generators-v1 [] #{:p4-s7}
       "<?php function g() { $x = yield 1; yield $x; } $g = g(); echo $g->current(); echo $g->send(7);"
       nil)
   (fx :gen-throw-at-suspension :generators-v1 [] #{:p4-s7}
       "<?php function g() { try { yield 1; } catch (Exception $e) { yield $e->getMessage(); } } $g = g(); echo $g->current(); $g->throw(new Exception('x')); echo $g->current();"
       nil)
   (fx :gen-yield-from-array :generators-v1 [] #{:p4-s7}
       "<?php function g() { return yield from ['a', 'b']; } $g = g(); foreach ($g as $v) { echo $v; } var_dump($g->getReturn());"
       nil)
   (fx :gen-yield-from-generator-return :generators-v1 [] #{:p4-s7}
       "<?php function inner() { yield 1; return 4; } function outer() { $x = yield from inner(); yield $x; } foreach (outer() as $v) { echo $v; }"
       nil)
   (fx :gen-method-retains-this :generators-v1 [] #{:p4-s7}
       "<?php class A { public $v = 'a'; function values() { yield $this->v; } } foreach ((new A)->values() as $v) { echo $v; }"
       nil)
   (fx :gen-break-then-resume :generators-v1 [] #{:p4-s7}
       "<?php function g() { yield 1; yield 2; } $g = g(); foreach ($g as $v) { echo $v; break; } foreach ($g as $v) { echo $v; }"
       nil)
   (fx :gen-return-repeatedly-readable :generators-v1 [] #{:p4-s7}
       "<?php function g() { yield 1; return 7; } $g = g(); foreach ($g as $v) {} echo $g->getReturn(), $g->getReturn();"
       nil)
   (fx :gen-created-activation-releases-on-destroy :generators-v1 [48] #{:p4-s7}
       "<?php class D { function __destruct() { echo 'D'; } } function g($x) { yield 1; } $g = g(new D); unset($g); echo 'E';"
       nil)
   (fx :gen-suspended-cv-releases-on-destroy :generators-v1 [48] #{:p4-s7}
       "<?php class D { function __destruct() { echo 'D'; } } function g($x) { yield 1; } $g = g(new D); $g->current(); unset($g); echo 'E';"
       nil)
   (fx :gen-c8-pending-finally-departure :generators-v1 [48] #{:p4-s7}
       "<?php function g() { try { yield 1; } finally { echo 'F'; } } $g = g(); $g->current(); unset($g); echo 'E';"
       nil)
   (fx :gen-c19-clone-refused :generators-v1 [52] #{:p4-s7}
       "<?php function g() { yield 1; } $g = g(); try { clone $g; } catch (Error $e) { echo $e->getMessage(); }"
       nil)])


(def typed-ref-v1
  "The ten typed-reference texts of section 4.5 (rows 41 to 45), run at program
   level on the implementation. The vendored grammar cannot parse a nullable
   property type (`public ?int $n`), so each program is the recorded source with
   `?int` written `int|null` and `PHP_INT_MAX` as its literal; the pinned text is
   the recorded one, confirmed when the oracle records the rewritten source."
  (into []
        (comp (filter #(= :typed-ref-candidates (:group %)))
              (map (fn [f]
                     (-> f
                         (assoc :id (keyword (str "p4-" (name (:id f))))
                                :level :program
                                :requires #{:p4-s5}
                                :oracle :pending)
                         (cond-> (:uncaught f) (assoc :throws true))
                         (update :source
                                 #(-> %
                                      (clojure.string/replace "public ?int" "public int|null")
                                      (clojure.string/replace "PHP_INT_MAX" "9223372036854775807")))))))
        p3b/all-fixtures))


(def banner p3b/banner)


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
   :f3-shutdown-function-arguments {:out "xpq", :diag [], :uncaught nil}
   :f3-shutdown-after-uncaught {:out "S", :diag [], :uncaught {:class "Exception", :message "e"}}
   :decl-param-coerces-weak {:out "int(5)\nint(5)\nint(1)\n", :diag [], :uncaught nil}
   :decl-param-rejects-string {:out "TypeError", :diag [], :uncaught nil}
   :decl-param-strict-rejects-numeric-string {:out "TypeError", :diag [], :uncaught nil}
   :decl-param-strict-widens-int-to-float {:out "float(5)\n", :diag [], :uncaught nil}
   :decl-weak-coerces-numeric-string-strict-rejects {:out "6", :diag [], :uncaught nil}
   :decl-nullable-and-default-null {:out "NULL\nNULL\nint(1)\nint(2)\nT", :diag [], :uncaught nil}
   :decl-union-prefers-exact {:out "string(1) \"5\"\nint(5)\nint(5)\n", :diag [], :uncaught nil}
   :decl-class-type {:out "BTypeError", :diag [], :uncaught nil}
   :decl-return-coerces {:out "int(7)\n", :diag [], :uncaught nil}
   :decl-return-rejects {:out "f(): Return value must be of type int, string returned", :diag [], :uncaught nil}
   :decl-return-none-returned {:out "f(): Return value must be of type int, none returned", :diag [], :uncaught nil}
   :decl-void-return {:out "vNULL\n", :diag [], :uncaught nil}
   :decl-method-return-message {:out "A::m(): Return value must be of type int, string returned", :diag [], :uncaught nil}
   :decl-property-coerces {:out "int(5)\n", :diag [], :uncaught nil}
   :decl-property-rejects {:out "Cannot assign string to property A::$n of type int", :diag [], :uncaught nil}
   :decl-property-strict {:out "TypeError", :diag [], :uncaught nil}
   :decl-property-uninitialized-read {:out "Typed property A::$n must not be accessed before initialization", :diag [], :uncaught nil}
   :decl-property-reference-keeps-type {:out "Cannot assign string to reference held by property A::$n of type intint(9)\n", :diag [], :uncaught nil}
   :decl-promoted-typed {:out "int(7)\na", :diag [], :uncaught nil}
   :decl-promoted-readonly {:out "Cannot modify readonly property P::$x", :diag [], :uncaught nil}
   :decl-closure-types {:out "8int(6)\n", :diag [], :uncaught nil}
   :decl-error-handler-throws-error-exception {:out "ErrorException2", :diag [], :uncaught nil}
   :decl-exception-handler {:out "aHm", :diag [], :uncaught nil}
   :decl-float-fraction-to-int-param {:out "int(5)\nint(5)\n", :diag [[8192 "Implicit conversion from float 5.9 to int loses precision"] [8192 "Implicit conversion from float-string \"5.5\" to int loses precision"]], :uncaught nil}
   :decl-float-out-of-range-int-param {:out "TypeError", :diag [], :uncaught nil}
   :decl-byref-param-typed-coerces {:out "int(5)\nint(5)\n", :diag [], :uncaught nil}
   :decl-byref-param-typed-rejects {:out "TypeError", :diag [], :uncaught nil}
   :decl-false-type-is-not-bool {:out "1TypeError", :diag [], :uncaught nil}
   :decl-exception-handler-restore-pops {:out "A", :diag [], :uncaught nil}
   :decl-property-default-widens-int-to-float {:out "float(1)\n", :diag [], :uncaught nil}
   :decl-property-default-null-nullable {:out "NULL\n", :diag [], :uncaught nil}
   :decl-byref-coercion-respects-property-type {:out "TypeErrorint(1)\n", :diag [], :uncaught nil}
   :decl-union-literal-false-stays-false {:out "bool(false)\nbool(true)\n", :diag [], :uncaught nil}
   :decl-union-numeric-string-order-independent {:out "float(5.5)\nfloat(5.5)\nint(5)\nint(5)\n", :diag [], :uncaught nil}
   :decl-static-property-default-widens {:out "float(1)\n", :diag [], :uncaught nil}
   :decl-property-default-literal-mismatch-is-fatal {:out "", :diag [], :uncaught {:class "Error", :message "Cannot use string as default value for property A::$x of type int"}}
   :decl-param-default-widens {:out "float(1)\n", :diag [], :uncaught nil}
   :decl-param-default-literal-mismatch-is-fatal {:out "", :diag [], :uncaught {:class "Error", :message "Cannot use string as default value for parameter $x of type int"}}
   :decl-byref-class-type-accepts-property-value {:out "1", :diag [], :uncaught nil}
   :decl-byref-iterable-accepts-array-property {:out "1", :diag [], :uncaught nil}
   :decl-byref-callable-accepts-callable-string-property {:out "1", :diag [], :uncaught nil}
   :decl-union-overflowing-numeric-string-param {:out "TypeError", :diag [], :uncaught nil}
   :decl-union-overflowing-numeric-string-property {:out "TypeErrorNULL\n", :diag [], :uncaught nil}
   :decl-exception-handler-then-shutdown {:out "HS", :diag [], :uncaught nil}
   :decl-byref-rejection-releases-ownership {:out "cED", :diag [], :uncaught nil}
   :decl-byref-rejection-exception-freed-then-unset {:out "cDE", :diag [], :uncaught nil}
   :decl-byref-later-rejection-releases-earlier-param {:out "cDE", :diag [], :uncaught nil}})


(defn- pin
  "The fixture with its recorded oracle result folded in: `:oracle` becomes the
   banner, a fixture without a pinned `:out` takes the recorded one, and
   non-empty `:diag`/`:uncaught` are kept. Unrecorded fixtures stay pending."
  [fixture]
  (if-let [r (get recorded (:id fixture))]
    (cond-> (assoc fixture :oracle banner)
      (not (contains? fixture :out)) (assoc :out (:out r))
      (seq (:diag r)) (assoc :diag (:diag r))
      (:uncaught r) (assoc :uncaught (:uncaught r)))
    fixture))


(def all-fixtures
  "Unconditional registry; the oracle iterates it regardless of activation."
  (mapv pin (concat classes-v1 constructor-v1 exceptions-v1 readonly-v1 f3-v1 declarations-v1 closures-v1 objects-v1 typed-ref-v1)))


(defn enabled?
  "True when every step the fixture requires has landed. Oracle-only
   fixtures never run on the implementation."
  [fixture]
  (p3b/enabled? fixture completed-steps))


(def enabled
  "The subset implementation runners execute."
  (vec (filter enabled? all-fixtures)))


(def awaiting-oracle
  "The enabled fixtures that are still `:oracle :pending`: the S6 closure,
   callable and return-type fixtures and the checkpoint-B object, constructor,
   readonly and typed-reference fixtures, awaiting the next oracle pass. A fold
   shrinks this set; changing it is a reviewed edit (Architect ruling on P4
   activation: every enabled pending fixture is recorded before its
   checkpoint closes)."
  #{:clo-bind-grants-private-access
    :clo-bindto-scope-by-name
    :clo-bindto-without-scope-keeps-scope
    :clo-bound-this-sets-static
    :clo-call-binds-this-and-scope
    :clo-call-passes-arguments
    :clo-callable-array-string-forms
    :clo-callable-private-from-inside
    :clo-callable-private-from-outside
    :clo-closure-keeps-called-class
    :clo-from-callable-function
    :clo-from-callable-invokable
    :clo-from-callable-method
    :clo-nested-closure-keeps-this
    :clo-static-closure-has-no-this
    :clo-this-captured-in-method
    :clo-trait-closure-this
    :clo-trait-self-and-static
    :ctor-no-constructor-side-effecting-args
    :ctor-no-constructor-zero-args
    :ctor-private-constructor-text
    :decl-never-implicit-return-is-type-error
    :decl-never-throws
    :decl-signed-literal-defaults-accepted
    :decl-static-return-type
    :obj-row22-destructor-creates-global
    :obj-row46-static-typed-ref-source
    :obj-row57-unknown-named-by-ref-callee-no-vivification
    :obj-row57-unknown-named-object-argument-destroyed
    :obj-row59-dim-write-autovivifies-array
    :obj-row59-dim-write-refuses-int
    :obj-row59-uninitialized-concat-assign
    :obj-row59-uninitialized-increment
    :obj-row65-positional-after-string-key-spread
    :obj-row65-spread-string-keys
    :p4-typed-ref-row41-readonly-initialized
    :p4-typed-ref-row41-uninitialized-non-nullable
    :p4-typed-ref-row41-uninitialized-nullable
    :p4-typed-ref-row42-assign-null
    :p4-typed-ref-row42-assign-string
    :p4-typed-ref-row42-coerces-numeric-string
    :p4-typed-ref-row43-increment-past-max
    :p4-typed-ref-row44-unset-uninitializes
    :p4-typed-ref-row45-first-failing-source
    :p4-typed-ref-row45-second-source-mismatch
    :ro-clone-child-modifies-parent-readonly
    :ro-clone-uninitialized-unset
    :ro-clone-unset-twice
    :ro-f3-unset-then-reassign-in-clone
    :ro-uninitialized-reference-text})
