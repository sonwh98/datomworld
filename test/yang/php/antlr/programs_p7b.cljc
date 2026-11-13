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
   {:id :sequence-n2, :source "declare(ticks=2) { echo 'A'; $x = 1; echo 'B'; }",
    :out "ATB", :oracle :pending}
   {:id :nested-threshold,
    :source "declare(ticks=5) { echo 'A'; $x=1; echo 'B'; declare(ticks=2) { echo 'C'; } echo 'D'; }",
    :out "ABCTD", :oracle :pending}
   {:id :cross-function,
    :source "function f() { declare(ticks=2) { echo 'F'; } } declare(ticks=2) { echo 'A'; } f();",
    :out "AFT", :oracle :pending}
   {:id :semicolon-directive, :source "declare(ticks=2); echo 'A'; echo 'B';",
    :out "ATB", :oracle :pending}
   {:id :nested-zero-completion,
    :source "declare(ticks=1) { declare(ticks=0) { echo 'A'; } echo 'B'; }",
    :out "ATBT", :oracle :pending}
   {:id :hoisted-threshold,
    :source "declare(ticks=1); function f() { echo 'F'; } f();",
    :out "TFTT", :oracle :pending}
   {:id :unregister-executing,
    :source "unregister_tick_function('tick_handler'); function self_remove() { echo 'S'; unregister_tick_function('self_remove'); } register_tick_function('self_remove'); declare(ticks=1) { echo 'A'; echo 'B'; }",
    :out "ASB", :oracle :pending}
   {:id :per-entry-reentry,
    :source "unregister_tick_function('tick_handler'); function helper() { declare(ticks=1) { echo 'X'; } } function first_handler() { echo 'H'; helper(); } function second_handler() { echo 'Y'; } register_tick_function('first_handler'); register_tick_function('second_handler'); declare(ticks=1) { echo 'A'; echo 'B'; }",
    :out "AHXYYBHXYY", :oracle :pending}
   {:id :include-handoff, :source "include 'lib.php';",
    :units {"lib.php" "<?php declare(ticks=1) { echo 'L'; }"},
    :out "LT", :oracle :pending}])
