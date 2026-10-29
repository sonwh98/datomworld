(ns yang.php.antlr.programs-p6
  "Slice P6 Stage 0 (S0) fixture data: include/require over the linker
   (P6 plan v2, S0). Same schema as `yang.php.antlr.programs-p3b`, plus:
     :units     {path source} for a multi-unit fixture, every unit including
                the entry unit; absent for a one-file fixture
     :main      the entry unit's path, default \"main.php\"
     :computed  true when the entry unit's computed includes resolve through
                the composition's published path map (every non-entry unit)
     :warnings  the lowering warnings the entry unit must report
   Every fixture is `:oracle :pending` until the shiva recording pass, and
   `:requires` names the P6 steps (:p6-s2 .. :p6-s5, :p6-s2-r8 for the
   per-unit strict_types rule) that must land before an implementation
   runner may execute it. `:out` and `:diag` are pinned only where the
   language fixes them; otherwise they are absent and the recorded output is
   taken. A mismatch is escalated to the Architect and never fixed by
   editing the expectation.

   Groups: :inclusion-v1, :missing-v1 (the oracle-pinned half; the
   pinned-but-unserved half is asserted in the linked tests), :namespaces-v1,
   :constants-v1, :autoload-v1, :strict-v1. `all-fixtures` is unconditional
   (the oracle iterates it); `enabled` is the subset whose :requires have all
   landed in `completed-steps`."
  (:require
    [yang.php.antlr.programs-p3b :as p3b]))


(def completed-steps
  "P6 steps that have landed: S1 (the linked profile, module emitter,
   publication), S2 (include/require, the two missing stages, the computed
   arm), S3 (the dynamic-scope profile), S4 (namespaces, `use`, the
   constants read path) and S5 (constant declarations, magic constants,
   the autoload runtime). `:p6-s2-r8` (per-unit strict_types) is not here."
  #{:p6-s0 :p6-s1 :p6-s2 :p6-s3 :p6-s4 :p6-s5})


(def include-path
  "The `include_path` the missing-include texts print: php:8.3-cli's
   default, PENDING the shiva pin (plan S0 item 2)."
  ".:/usr/local/lib/php")


(defn- fx
  [id group requires units main-source out & {:as more}]
  (merge (cond-> {:id id,
                  :group group,
                  :rows [],
                  :level :program,
                  :requires requires,
                  :source main-source,
                  :oracle :pending}
           (seq units) (assoc :units (assoc units "main.php" main-source))
           out (assoc :out out))
         more))


(defn- missing-warnings
  "The two warnings of a missing `include`/`include_once` of `path`."
  [kind path]
  [[2 (str kind "(" path "): Failed to open stream: No such file or directory")]
   [2 (str kind "(): Failed opening '" path "' for inclusion (include_path='" include-path "')")]])


(def inclusion-v1
  [(fx :inc-basic :inclusion-v1 #{:p6-s2} {"lib/a.php" "<?php echo 'a';"}
       "<?php echo 'm'; include 'lib/a.php'; echo 'z';" "maz")
   (fx :inc-return-value :inclusion-v1 #{:p6-s2} {"a.php" "<?php return 5;"}
       "<?php $r = include 'a.php'; var_dump($r);" "int(5)\n")
   (fx :inc-default-one :inclusion-v1 #{:p6-s2} {"a.php" "<?php echo 'a';"}
       "<?php var_dump(include 'a.php');" "aint(1)\n")
   (fx :inc-once-repeat :inclusion-v1 #{:p6-s2} {"a.php" "<?php echo 'a';"}
       "<?php var_dump(include_once 'a.php'); var_dump(include_once 'a.php');"
       "aint(1)\nbool(true)\n")
   (fx :inc-plain-then-once :inclusion-v1 #{:p6-s2} {"a.php" "<?php echo 'a';"}
       "<?php include 'a.php'; var_dump(include_once 'a.php');" "abool(true)\n")
   (fx :inc-plain-twice :inclusion-v1 #{:p6-s2} {"a.php" "<?php echo 'a';"}
       "<?php include 'a.php'; include 'a.php';" "aa")
   (fx :inc-require-once-repeat :inclusion-v1 #{:p6-s2} {"a.php" "<?php echo 'a';"}
       "<?php var_dump(require_once 'a.php'); var_dump(require_once 'a.php');"
       "aint(1)\nbool(true)\n")
   (fx :inc-scope-sharing :inclusion-v1 #{:p6-s2} {"a.php" "<?php $v = $v + 1;"}
       "<?php $v = 1; include 'a.php'; include 'a.php'; echo $v;" "3")
   (fx :inc-dir-literal :inclusion-v1 #{:p6-s2} {"lib/a.php" "<?php echo 'a';"}
       "<?php include __DIR__ . '/lib/a.php';" "a")
   (fx :inc-dirname-file :inclusion-v1 #{:p6-s2} {"lib/a.php" "<?php echo 'a';"}
       "<?php include dirname(__FILE__) . '/lib/a.php';" "a")
   (fx :inc-nested :inclusion-v1 #{:p6-s2}
       {"lib/a.php" "<?php echo 'a'; include 'lib/b.php';", "lib/b.php" "<?php echo 'b';"}
       "<?php include 'lib/a.php';" "ab")
   (fx :inc-return-top-level-entry :inclusion-v1 #{:p6-s2} nil
       "<?php echo 'a'; return; echo 'b';" "a")
   (fx :inc-return-in-included :inclusion-v1 #{:p6-s2}
       {"a.php" "<?php echo 'a'; return 3; echo 'x';"}
       "<?php var_dump(include 'a.php');" "aint(3)\n")
   (fx :inc-function-declared :inclusion-v1 #{:p6-s2}
       {"a.php" "<?php function f() { return 'f'; }"}
       "<?php include 'a.php'; echo f();" "f")
   (fx :inc-mutual-once :inclusion-v1 #{:p6-s2}
       {"a.php" "<?php echo 'a'; include_once 'b.php';",
        "b.php" "<?php echo 'b'; include_once 'a.php';"}
       "<?php include_once 'a.php';" "ab")
   (fx :inc-redeclare-across-units :inclusion-v1 #{:p6-s2}
       {"a.php" "<?php function f() {}"}
       "<?php function f() {} include 'a.php';" nil)
   (fx :inc-warning-in-included :inclusion-v1 #{:p6-s2}
       {"a.php" "<?php echo $undef;"}
       "<?php include 'a.php'; echo 'z';" "z"
       :diag [[2 "Undefined variable $undef"]])
   (fx :inc-throwing-body-still-marked :inclusion-v1 #{:p6-s2}
       {"a.php" "<?php throw new Exception('x');"}
       (str "<?php try { include_once 'a.php'; } catch (Exception $e) { echo 'c'; }"
            " var_dump(include_once 'a.php');")
       "cbool(true)\n")
   (fx :inc-once-path-forms :inclusion-v1 #{:p6-s2} {"lib/a.php" "<?php echo 'a';"}
       "<?php include_once 'lib/a.php'; var_dump(include_once './lib/a.php');"
       "abool(true)\n")
   (fx :inc-in-condition :inclusion-v1 #{:p6-s2} {"a.php" "<?php echo 'a';"}
       "<?php if ((include 'a.php') === 1) { echo 'one'; }" "aone")
   (fx :inc-computed-path :inclusion-v1 #{:p6-s2} {"lib/a.php" "<?php echo 'a';"}
       "<?php $p = 'lib/a.php'; include $p;" "a"
       :computed true
       :warnings [:yang.php/computed-include-path])
   (fx :inc-computed-path-unmapped :inclusion-v1 #{:p6-s2} {"lib/a.php" "<?php echo 'a';"}
       "<?php $p = 'lib/' . 'b.php'; var_dump(include $p);" "bool(false)\n"
       :computed true
       :diag (missing-warnings "include" "lib/b.php")
       :warnings [:yang.php/computed-include-path])])


(def missing-v1
  "The oracle-pinned half: a genuinely missing file, every kind, each run
   twice (a refusal never marks the path), and the not-marked probe."
  [(fx :miss-include :missing-v1 #{:p6-s2} nil
       "<?php var_dump(include 'x.php'); var_dump(include 'x.php');" "bool(false)\nbool(false)\n"
       :diag (into (missing-warnings "include" "x.php") (missing-warnings "include" "x.php")))
   (fx :miss-include-once :missing-v1 #{:p6-s2} nil
       "<?php var_dump(include_once 'x.php'); var_dump(include_once 'x.php');"
       "bool(false)\nbool(false)\n"
       :diag (into (missing-warnings "include_once" "x.php") (missing-warnings "include_once" "x.php")))
   (fx :miss-require :missing-v1 #{:p6-s2} nil
       (str "<?php for ($i = 0; $i < 2; $i++) {"
            " try { require 'x.php'; } catch (Error $e) { echo get_class($e), ': ', $e->getMessage(), \"\\n\"; } }")
       (str (apply str (repeat 2 (str "Error: Failed opening required 'x.php' (include_path='" include-path "')\n"))))
       :diag (vec (repeat 2 [2 "require(x.php): Failed to open stream: No such file or directory"])))
   (fx :miss-require-once :missing-v1 #{:p6-s2} nil
       (str "<?php for ($i = 0; $i < 2; $i++) {"
            " try { require_once 'x.php'; } catch (Error $e) { echo get_class($e), ': ', $e->getMessage(), \"\\n\"; } }")
       (str (apply str (repeat 2 (str "Error: Failed opening required 'x.php' (include_path='" include-path "')\n"))))
       :diag (vec (repeat 2 [2 "require_once(x.php): Failed to open stream: No such file or directory"])))
   (fx :miss-not-marked-probe :missing-v1 #{:p6-s2} nil
       (str "<?php try { require_once 'x.php'; } catch (Error $e) { echo get_class($e), \"\\n\"; }"
            " var_dump(include_once 'x.php');")
       "Error\nbool(false)\n"
       :diag (into [[2 "require_once(x.php): Failed to open stream: No such file or directory"]]
                   (missing-warnings "include_once" "x.php")))])


(def strict-v1
  "R8: the caller's unit governs argument coercion, the callee's the rest."
  [(fx :strict-caller-strict-callee-coercive :strict-v1 #{:p6-s2-r8}
       {"f.php" "<?php function f(int $x) { return $x; }"}
       (str "<?php declare(strict_types=1); include 'f.php';"
            " try { echo f('5'); } catch (TypeError $e) { echo 'TE'; }")
       "TE")
   (fx :strict-caller-coercive-callee-strict :strict-v1 #{:p6-s2-r8}
       {"f.php" "<?php declare(strict_types=1); function f(int $x) { return $x; }"}
       "<?php include 'f.php'; echo f('5');" "5")])


(def dynscope-v1
  "Includes inside functions (P6 S3): the including scope is the function's
   variables, shared by name with the included unit."
  [(fx :dyn-shares-locals :dynscope-v1 #{:p6-s3}
       {"a.php" "<?php $x = $x + 10; $y = 'y';"}
       "<?php function f() { $x = 1; include 'a.php'; return $x . $y; } echo f();"
       "11y")
   (fx :dyn-does-not-touch-globals :dynscope-v1 #{:p6-s3}
       {"a.php" "<?php $x = 'changed';"}
       "<?php $x = 'g'; function f() { $x = 'l'; include 'a.php'; return $x; } echo f(), $x;"
       "changedg")
   (fx :dyn-param-and-return :dynscope-v1 #{:p6-s3}
       {"a.php" "<?php return $n * 2;"}
       "<?php function f($n) { return include 'a.php'; } echo f(21);"
       "42")
   (fx :dyn-this-in-method :dynscope-v1 #{:p6-s3}
       {"a.php" "<?php echo $this->v;"}
       "<?php class C { public $v = 5; function m() { include 'a.php'; } } (new C)->m();"
       "5")
   (fx :dyn-destructor-at-return :dynscope-v1 #{:p6-s3}
       {"a.php" "<?php echo 'a';"}
       (str "<?php class D { function __destruct() { echo 'd'; } }"
            " function f() { $o = new D; include 'a.php'; echo 'f'; } f(); echo 'end';")
       "afdend")
   (fx :dyn-closure-capture :dynscope-v1 #{:p6-s3}
       {"a.php" "<?php echo $k;"}
       "<?php $k = 'k'; $f = function () use ($k) { include 'a.php'; }; $f();"
       "k")])


(def namespaces-v1
  [(fx :ns-class-not-found :namespaces-v1 #{:p6-s4} nil
       "<?php namespace A; try { new Exception; } catch (\\Error $e) { echo $e->getMessage(); }"
       "Class \"A\\Exception\" not found")
   (fx :ns-undefined-function :namespaces-v1 #{:p6-s4} nil
       "<?php namespace A; try { g(); } catch (\\Error $e) { echo $e->getMessage(); }"
       "Call to undefined function A\\g()")
   (fx :ns-undefined-constant :namespaces-v1 #{:p6-s4} nil
       "<?php namespace A; try { echo X; } catch (\\Error $e) { echo $e->getMessage(); }"
       "Undefined constant \"A\\X\"")
   (fx :ns-function-global-fallback :namespaces-v1 #{:p6-s4} nil
       "<?php namespace A; echo strlen('abc');" "3")
   (fx :ns-builtin-constant-global-fallback :namespaces-v1 #{:p6-s4} nil
       "<?php namespace A; echo PHP_EOL === \"\\n\" ? 'y' : 'n';" "y")
   (fx :ns-instanceof-across-units :namespaces-v1 #{:p6-s4}
       {"lib/c.php" "<?php namespace L; class C {}"}
       "<?php include 'lib/c.php'; $o = new L\\C; var_dump($o instanceof L\\C);"
       "bool(true)\n")
   (fx :ns-use-class-alias :namespaces-v1 #{:p6-s4}
       {"lib/c.php" "<?php namespace L; class C { const K = 7; static function s() { return 's'; } }"}
       (str "<?php use L\\C as D; include 'lib/c.php';"
            " echo D::K, D::s(), D::class, (new D) instanceof L\\C ? 'y' : 'n';")
       "7sL\\Cy")
   (fx :ns-function-and-magic :namespaces-v1 #{:p6-s4}
       nil
       (str "<?php namespace A\\B; function f() { return __FUNCTION__ . '|' . __NAMESPACE__; }"
            " echo f(), '|', \\A\\B\\f(), '|', namespace\\f();")
       "A\\B\\f|A\\B|A\\B\\f|A\\B|A\\B\\f|A\\B")
   (fx :ns-braced-blocks :namespaces-v1 #{:p6-s4}
       nil
       "<?php namespace A { const X = 1; function f() { return X; } } namespace B { echo \\A\\f(), \\A\\X; }"
       "11")
   (fx :ns-use-function-const :namespaces-v1 #{:p6-s4}
       {"lib/u.php" "<?php namespace U; const K = 'k'; function g() { return 'g'; }"}
       "<?php use function U\\g; use const U\\K; include 'lib/u.php'; echo g(), K;"
       "gk")
   (fx :ns-use-const-alias :namespaces-v1 #{:p6-s4 :p6-s5}
       {"lib/b.php" "<?php namespace B; const PHP_EOL = '[b-eol]';"}
       "<?php namespace A; use const B\\PHP_EOL as E; include 'lib/b.php'; echo E;"
       "[b-eol]")
   (fx :ns-shadows-global-function :namespaces-v1 #{:p6-s4}
       nil
       "<?php namespace A; function strlen($s) { return 99; } echo strlen('abc'), \\strlen('abc');"
       "993")
   (fx :ns-grouped-use :namespaces-v1 #{:p6-s4}
       nil
       (str "<?php namespace A { class X {} class Y {} }"
            " namespace { use A\\{X, Y as Z}; var_dump(new X instanceof A\\X, new Z instanceof A\\Y); }")
       "bool(true)\nbool(true)\n")])


(def constants-v1
  [(fx :const-namespaced-builtin :constants-v1 #{:p6-s4 :p6-s5}
       {"lib/b.php" "<?php namespace B; echo PHP_EOL === \"\\n\" ? 'g' : 'x';"}
       (str "<?php namespace A; const PHP_EOL = '[eol]'; echo PHP_EOL, \\PHP_EOL;"
            " function f() { return PHP_EOL; } echo f();"
            " var_dump(defined('A\\PHP_EOL'), constant('PHP_EOL'));"
            " include 'lib/b.php';")
       "[eol]\n[eol]bool(true)\nstring(1) \"\n\"\ng")
   (fx :const-define-redefinition :constants-v1 #{:p6-s5} nil
       "<?php define('X', 1); var_dump(define('X', 2)); echo X;" "bool(false)\n1"
       :diag [[2 "Constant X already defined"]])
   (fx :const-magic-function-closure :constants-v1 #{:p6-s5} nil
       "<?php $f = function () { return __FUNCTION__; }; echo $f();" "{closure}")
   (fx :const-magic-method :constants-v1 #{:p6-s5} nil
       "<?php class A { function m() { return __METHOD__; } } echo (new A)->m();" "A::m")
   (fx :const-declare-and-read :constants-v1 #{:p6-s5} nil
       "<?php const A = 'a', B = 'b' . 'c'; echo A, B, constant('A'); var_dump(defined('B'), defined('C'));"
       "abcabool(true)\nbool(false)\n")
   (fx :const-redeclare-warns :constants-v1 #{:p6-s5} nil
       "<?php const A = 1; const A = 2; echo A;" "1"
       :diag [[2 "Constant A already defined"]])
   (fx :const-undefined-error :constants-v1 #{:p6-s5} nil
       "<?php try { echo NOPE; } catch (Error $e) { echo $e->getMessage(); }"
       "Undefined constant \"NOPE\"")
   (fx :const-magic-line-namespace :constants-v1 #{:p6-s4 :p6-s5} nil
       "<?php namespace N; echo __LINE__, __NAMESPACE__;" "1N")
   (fx :const-magic-class-trait :constants-v1 #{:p6-s5} nil
       (str "<?php trait T { function m() { return __CLASS__ . '|' . __TRAIT__ . '|' . __METHOD__; } }"
            " class C { use T; } echo (new C)->m();")
       "C|T|T::m")
   (fx :const-file-dir-raw :constants-v1 #{:p6-s5} nil
       "<?php echo __FILE__, '|', __DIR__;" nil
       :departure-c37 true)])


(def autoload-v1
  [(fx :auto-loader-requires-computed-path :autoload-v1 #{:p6-s5}
       {"lib/Foo.php" "<?php class Foo {}"}
       (str "<?php spl_autoload_register(function ($c) { require __DIR__ . '/lib/' . $c . '.php'; });"
            " $o = new Foo; echo get_class($o);")
       "Foo"
       :computed true)
   (fx :auto-classmap-loader :autoload-v1 #{:p6-s5}
       {"lib/Foo.php" "<?php class Foo { function hi() { return 'foo'; } }"}
       (str "<?php spl_autoload_register(function ($c) { require __DIR__ . '/lib/' . $c . '.php'; });"
            " echo (new Foo)->hi();")
       "foo"
       :class-map {:classes {"Foo" "lib/Foo.php"}})
   (fx :auto-classmap-unmapped :autoload-v1 #{:p6-s5}
       {"lib/Foo.php" "<?php class Foo {}"}
       (str "<?php spl_autoload_register(function ($c) { require 'lib/' . $c . '.php'; });"
            " try { new Bar; } catch (Error $e) { echo get_class($e); }")
       "Error"
       :class-map {:classes {"Foo" "lib/Foo.php"}}
       :diag [[2 "require(lib/Bar.php): Failed to open stream: No such file or directory"]])
   (fx :auto-class-exists-without-autoload :autoload-v1 #{:p6-s5} nil
       (str "<?php spl_autoload_register(function ($c) { echo 'L'; });"
            " var_dump(class_exists('Nope', false)); var_dump(class_exists('Nope'));")
       "bool(false)\nLbool(false)\n")
   (fx :auto-unregister :autoload-v1 #{:p6-s5} nil
       (str "<?php $f = function ($c) { echo 'L'; }; spl_autoload_register($f);"
            " var_dump(spl_autoload_unregister($f)); var_dump(class_exists('Nope'));"
            " var_dump(count(spl_autoload_functions()));")
       "bool(true)\nbool(false)\nint(0)\n")
   (fx :auto-recursion-guard :autoload-v1 #{:p6-s5} nil
       "<?php spl_autoload_register(function ($c) { echo 'L'; class_exists($c); }); var_dump(class_exists('Nope'));"
       "Lbool(false)\n")
   (fx :auto-extends-loads-parent :autoload-v1 #{:p6-s5}
       {"lib/B.php" "<?php class B { function hi() { return 'B'; } }"}
       (str "<?php spl_autoload_register(function ($c) { include 'lib/' . $c . '.php'; });"
            " class K extends B {} echo (new K)->hi();")
       "B"
       :computed true)
   (fx :auto-static-call-loads :autoload-v1 #{:p6-s5}
       {"lib/S.php" "<?php class S { static function hi() { return 'S'; } }"}
       (str "<?php spl_autoload_register(function ($c) { include 'lib/' . $c . '.php'; });"
            " echo S::hi();")
       "S"
       :computed true)
   (fx :auto-instanceof-does-not-load :autoload-v1 #{:p6-s5} nil
       (str "<?php spl_autoload_register(function ($c) { echo 'L'; });"
            " $o = new stdClass; var_dump($o instanceof Nope);")
       "bool(false)\n")
   (fx :auto-loader-order :autoload-v1 #{:p6-s5} nil
       (str "<?php spl_autoload_register(function ($c) { echo '1'; });"
            " spl_autoload_register(function ($c) { echo '2'; });"
            " spl_autoload_register(function ($c) { echo '0'; }, true, true);"
            " class_exists('Nope');")
       "012")])


(def banner p3b/banner)


(def all-fixtures
  "Unconditional registry; the oracle iterates it regardless of activation."
  (vec (concat inclusion-v1 missing-v1 strict-v1 dynscope-v1 namespaces-v1 constants-v1 autoload-v1)))


(defn enabled?
  "True when every step the fixture requires has landed."
  [fixture]
  (p3b/enabled? fixture completed-steps))


(def enabled
  "The subset implementation runners execute."
  (vec (filter enabled? all-fixtures)))


(def awaiting-oracle
  "Every enabled fixture: none has been recorded yet (the shiva pass is the
   orchestrator's)."
  (set (map :id (filter #(= :pending (:oracle %)) enabled))))
