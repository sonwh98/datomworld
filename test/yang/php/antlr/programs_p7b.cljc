(ns yang.php.antlr.programs-p7b
  "Hand-derived tick expectations pending the pinned PHP oracle pass.")


(def prologue
  "function tick_handler() { echo 'T'; } register_tick_function('tick_handler'); ")


(def fixtures
  [{:id :sequence, :source "declare(ticks=1) { echo 'A'; $x = 1; echo 'B'; }",
    :out "ATTBT", :oracle :pending}
   {:id :loop-body, :source "for ($i=0; $i<2; $i++) { declare(ticks=1) { echo 'A'; } }",
    :out "ATAT", :oracle :pending}
   {:id :function-call, :source "function f() { echo 'F'; } declare(ticks=1) { f(); }",
    :out "FT", :oracle :pending}
   {:id :generator-resume,
    :source "function g() { declare(ticks=1) { yield 1; echo 'B'; } } $g=g(); $g->current(); echo 'A'; $g->next();",
    :out "ATBT", :oracle :pending}
   {:id :include-handoff, :source "include 'lib.php';",
    :units {"lib.php" "<?php declare(ticks=1) { echo 'L'; }"},
    :out "LT", :oracle :pending}])
