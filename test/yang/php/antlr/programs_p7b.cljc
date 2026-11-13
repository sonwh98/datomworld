(ns yang.php.antlr.programs-p7b
  "Tick observations and provisional expectations pending the pinned PHP oracle pass.")


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
   {:id :do-while-selection,
    :source "declare(ticks=1) { echo 'A'; do { echo 'B'; } while (false); echo 'C'; }",
    :out "ATBTCT", :oracle :pending}
   {:id :for-selection,
    :source "declare(ticks=1) { echo 'A'; for ($i=0; $i<2; $i++) { echo 'B'; } echo 'C'; }",
    :out "ATBTBTCT", :oracle :pending}
   {:id :foreach-selection,
    :source "declare(ticks=1) { echo 'A'; foreach ([1,2] as $v) { echo 'B'; } echo 'C'; }",
    :out "ATBTBTCT", :oracle :pending}
   {:id :switch-selection,
    :source "declare(ticks=1) { echo 'A'; switch (1) { case 1: echo 'B'; break; default: echo 'X'; } echo 'C'; }",
    :out "ATBTCT", :oracle :pending}
   {:id :adjacent-marked,
    :source "declare(ticks=1) { echo 'A'; $x=1; echo 'B'; }",
    :out "ATTBT", :oracle :pending}
   {:id :nested-zero-n2,
    :source "declare(ticks=2) { echo 'A'; declare(ticks=0) { echo 'B'; } echo 'C'; }",
    :out "ABTC", :oracle :pending}
   {:id :dispatch-add,
    :source "unregister_tick_function('tick_handler'); function added() { echo 'N'; } function add_handler() { echo 'H'; register_tick_function('added'); } register_tick_function('add_handler'); declare(ticks=1) { echo 'A'; echo 'B'; }",
    :out "AHBHN", :oracle :pending}
   {:id :dispatch-remove,
    :source "unregister_tick_function('tick_handler'); function remove_handler() { echo 'H'; unregister_tick_function('victim'); } function victim() { echo 'V'; } register_tick_function('remove_handler'); register_tick_function('victim'); declare(ticks=1) { echo 'A'; echo 'B'; }",
    :out "AHVBH", :oracle :pending}
   {:id :generator-foreach,
    :source "function g() { declare(ticks=1) { yield 1; echo 'G'; } } declare(ticks=1) { echo 'A'; foreach (g() as $v) { echo 'B'; } echo 'C'; }",
    :out "ATBTTGTCT", :oracle :pending}
   {:id :include-handoff, :source "include 'lib.php';",
    :units {"lib.php" "<?php declare(ticks=1) { echo 'L'; }"},
    :out "LT", :oracle :pending}])
