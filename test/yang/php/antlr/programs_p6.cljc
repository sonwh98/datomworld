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
   publication) and S2 (include/require, the two missing stages, the
   computed arm)."
  #{:p6-s0 :p6-s1 :p6-s2})


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
       "bool(true)\n")])


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
   (fx :auto-class-exists-without-autoload :autoload-v1 #{:p6-s5} nil
       (str "<?php spl_autoload_register(function ($c) { echo 'L'; });"
            " var_dump(class_exists('Nope', false)); var_dump(class_exists('Nope'));")
       "bool(false)\nLbool(false)\n")
   (fx :auto-loader-order :autoload-v1 #{:p6-s5} nil
       (str "<?php spl_autoload_register(function ($c) { echo '1'; });"
            " spl_autoload_register(function ($c) { echo '2'; });"
            " spl_autoload_register(function ($c) { echo '0'; }, true, true);"
            " class_exists('Nope');")
       "012")])


(def banner p3b/banner)


(def all-fixtures
  "Unconditional registry; the oracle iterates it regardless of activation."
  (vec (concat inclusion-v1 missing-v1 strict-v1 namespaces-v1 constants-v1 autoload-v1)))


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
