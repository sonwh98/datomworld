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
   subset whose :requires have all landed in `completed-steps`.")


(def completed-steps
  "P4 steps that have landed: S1 (descriptors) and S2 (objects, methods,
   visibility). A fixture whose :requires name a later step stays disabled."
  #{:p4-s1 :p4-s2})


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
   (fx :ctor-destruct-order-at-end :constructor-v1 [22 48] #{:p4-s3}
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
       (str "<?php class E extends Exception { function __construct($m) { parent::__construct(strtoupper($m)); } }"
            " try { throw new E('abc'); } catch (E $e) { echo $e->getMessage(); }")
       "ABC")
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
   (fx :exc-uncaught-fatal :exceptions-v1 [22] #{:p4-s4}
       "<?php class E extends Exception {} echo 'a'; throw new E('boom');"
       nil)
   (fx :exc-named-arg-validation-order :exceptions-v1 [57] #{:p4-s4}
       (str "<?php function f($a) { return $a; } function side() { echo 'S'; return 1; }"
            " try { f(1, z: 2, w: side()); } catch (Error $e) { echo $e->getMessage(); }")
       nil)])


(def readonly-v1
  [(fx :ro-init-once :readonly-v1 [66] #{:p4-s3 :p4-s5}
       "<?php class C { public readonly int $v; function __construct() { $this->v = 1; } } echo (new C)->v;"
       "1")
   (fx :ro-modify-throws :readonly-v1 [66] #{:p4-s3 :p4-s5}
       (str "<?php class C { function __construct(public readonly int $v) {} }"
            " $c = new C(1); try { $c->v = 2; } catch (Error $e) { echo $e->getMessage(); }")
       "Cannot modify readonly property C::$v")
   (fx :ro-init-outside-scope :readonly-v1 [66] #{:p4-s3 :p4-s5}
       (str "<?php class C { public readonly int $v; }"
            " $c = new C; try { $c->v = 2; } catch (Error $e) { echo $e->getMessage(); }")
       "Cannot initialize readonly property C::$v from global scope")
   (fx :ro-unset-uninitialized-in-scope :readonly-v1 [67] #{:p4-s3 :p4-s5}
       (str "<?php class C { public readonly int $v; function __construct() { unset($this->v); $this->v = 5; } }"
            " echo (new C)->v;")
       "5")
   (fx :ro-unset-initialized :readonly-v1 [67] #{:p4-s3 :p4-s5}
       (str "<?php class C { function __construct(public readonly int $v) {} function u() { unset($this->v); } }"
            " try { (new C(1))->u(); } catch (Error $e) { echo $e->getMessage(); }")
       "Cannot unset readonly property C::$v")
   (fx :ro-increment :readonly-v1 [68] #{:p4-s3 :p4-s5}
       (str "<?php class C { function __construct(public readonly int $v) {} function i() { $this->v++; } }"
            " try { (new C(1))->i(); } catch (Error $e) { echo $e->getMessage(); }")
       "Cannot modify readonly property C::$v")
   (fx :ro-reference-refused :readonly-v1 [41 66] #{:p4-s3 :p4-s5}
       (str "<?php class C { function __construct(public readonly int $v) {} }"
            " $c = new C(1); try { $r = &$c->v; } catch (Error $e) { echo $e->getMessage(); }")
       "Cannot modify readonly property C::$v")
   (fx :ro-clone-keeps-value :readonly-v1 [52 66] #{:p4-s3 :p4-s5}
       "<?php class C { function __construct(public readonly int $v) {} } $c = new C(3); $d = clone $c; echo $d->v;"
       "3")])


(def all-fixtures
  "Unconditional registry; the oracle iterates it regardless of activation."
  (vec (concat classes-v1 constructor-v1 exceptions-v1 readonly-v1)))


(defn enabled?
  "True when every step the fixture requires has landed. Oracle-only
   fixtures never run on the implementation."
  [fixture]
  (and (not= :oracle-only (:level fixture))
       (every? completed-steps (:requires fixture))))


(def enabled
  "The subset implementation runners execute."
  (vec (filter enabled? all-fixtures)))
