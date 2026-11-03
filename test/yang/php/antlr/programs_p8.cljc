(ns yang.php.antlr.programs-p8
  "P8 corpus for the programs formerly run through the legacy compiler.
   Expectations are PHP semantics, not the legacy compiler's returned values.")


(def banner "php-8.3 semantics; hand-derived at P8 migration")


(defn- fx
  [id source out]
  {:id id,
   :group :legacy-v1,
   :rows [],
   :level :program,
   :requires #{},
   :source source,
   :out out,
   :oracle banner})


(def fixtures
  [(fx :literal-integer "<?php var_dump(42);" "int(42)\n")
   (fx :literal-negative "<?php var_dump(-10);" "int(-10)\n")
   (fx :literal-float "<?php var_dump(3.14);" "float(3.14)\n")
   (fx :literal-double-quoted "<?php echo \"hello\";" "hello")
   (fx :literal-single-quoted "<?php echo 'world';" "world")
   (fx :literal-true "<?php var_dump(true);" "bool(true)\n")
   (fx :literal-false "<?php var_dump(false);" "bool(false)\n")
   (fx :literal-null "<?php var_dump(null);" "NULL\n")
   (fx :variable "<?php $x = 100; var_dump($x);" "int(100)\n")
   (fx :addition "<?php var_dump(1 + 2);" "int(3)\n")
   (fx :function-application
       "<?php function double($x) { return $x * 2; } var_dump(double(21));"
       "int(42)\n")
   (fx :multiple-parameters
       "<?php function add($x, $y) { return $x + $y; } var_dump(add(10, 20));"
       "int(30)\n")
   (fx :operator-precedence "<?php var_dump(2 + 3 * 4);" "int(14)\n")
   (fx :nested-operations "<?php var_dump((2 + 3) * 4);" "int(20)\n")
   (fx :comparison "<?php var_dump(3 < 5);" "bool(true)\n")
   (fx :if-true "<?php if (true) { var_dump(10); } else { var_dump(20); }" "int(10)\n")
   (fx :if-false "<?php if (false) { var_dump(10); } else { var_dump(20); }" "int(20)\n")
   (fx :if-comparison
       "<?php if (3 < 5) { var_dump(100); } else { var_dump(200); }"
       "int(100)\n")
   (fx :elseif
       "<?php if (3 > 5) { var_dump(1); } elseif (4 < 5) { var_dump(2); } else { var_dump(3); }"
       "int(2)\n")
   (fx :closure
       (str "<?php $makePower = function (int $exponent) {"
            " return function (int $base) use ($exponent): int {"
            " $result = 1; $result = $result * $base; return $result; }; };"
            " $pow2 = $makePower(2); var_dump($pow2(3));")
       "int(3)\n")
   (fx :closure-loop
       (str "<?php $makePower = function (int $exponent) {"
            " return function (int $base) use ($exponent): int {"
            " $result = 1; for ($i = 0; $i < $exponent; $i++) { $result *= $base; }"
            " return $result; }; }; $cube = $makePower(3); var_dump($cube(2));")
       "int(8)\n")
   (fx :large-integer
       "<?php echo 9007199254740993;"
       "9007199254740993")
   (fx :float-suffix "<?php var_dump(1.);" "float(1)\n")])
