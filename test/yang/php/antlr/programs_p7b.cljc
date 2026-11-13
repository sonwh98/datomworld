(ns yang.php.antlr.programs-p7b
  "Tick observations recorded from the pinned PHP oracle pass.")


(def prologue
  "function tick_handler() { echo 'T'; } register_tick_function('tick_handler'); ")


(def fixtures
  [{:id :sequence, :source "declare(ticks=1) { echo 'A'; $x = 1; echo 'B'; }",
    :out "ATTBT", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :loop-body, :source "for ($i=0; $i<2; $i++) { declare(ticks=1) { echo 'A'; } }",
    :out "ATAT", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :function-call, :source "function f() { echo 'F'; } declare(ticks=1) { f(); }",
    :out "FT", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :generator-resume,
    :source "function g() { declare(ticks=1) { yield 1; echo 'B'; } } $g=g(); $g->current(); echo 'A'; $g->next();",
    :out "ATBT", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :sequence-n2, :source "declare(ticks=2) { echo 'A'; $x = 1; echo 'B'; }",
    :out "ATB", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :nested-threshold,
    :source "declare(ticks=5) { echo 'A'; $x=1; echo 'B'; declare(ticks=2) { echo 'C'; } echo 'D'; }",
    :out "ABCTD", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :cross-function,
    :source "function f() { declare(ticks=2) { echo 'F'; } } declare(ticks=2) { echo 'A'; } f();",
    :out "AFT", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :semicolon-directive, :source "declare(ticks=2); echo 'A'; echo 'B';",
    :out "ATB", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :nested-zero-completion,
    :source "declare(ticks=1) { declare(ticks=0) { echo 'A'; } echo 'B'; }",
    :out "ATBT", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :hoisted-threshold,
    :source "declare(ticks=1); function f() { echo 'F'; } f();",
    :out "TFTT", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :unregister-executing,
    :source "unregister_tick_function('tick_handler'); function self_remove() { echo 'S'; unregister_tick_function('self_remove'); } register_tick_function('self_remove'); declare(ticks=1) { echo 'A'; echo 'B'; }",
    :out "AS", :diag [], :uncaught {:class "Error", :message "Registered tick function cannot be unregistered while it is being executed"}, :status 255, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :per-entry-reentry,
    :source "unregister_tick_function('tick_handler'); function helper() { declare(ticks=1) { echo 'X'; } } function first_handler() { echo 'H'; helper(); } function second_handler() { echo 'Y'; } register_tick_function('first_handler'); register_tick_function('second_handler'); declare(ticks=1) { echo 'A'; echo 'B'; }",
    :out "AHXYYBHXYY", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :do-while-selection,
    :source "declare(ticks=1) { echo 'A'; do { echo 'B'; } while (false); echo 'C'; }",
    :out "ATBTTCT", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :for-selection,
    :source "declare(ticks=1) { echo 'A'; for ($i=0; $i<2; $i++) { echo 'B'; } echo 'C'; }",
    :out "ATBTBTTCT", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :foreach-selection,
    :source "declare(ticks=1) { echo 'A'; foreach ([1,2] as $v) { echo 'B'; } echo 'C'; }",
    :out "ATBTBTTCT", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :switch-selection,
    :source "declare(ticks=1) { echo 'A'; switch (1) { case 1: echo 'B'; break; default: echo 'X'; } echo 'C'; }",
    :out "ATBTCT", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :adjacent-marked,
    :source "declare(ticks=1) { echo 'A'; $x=1; echo 'B'; }",
    :out "ATTBT", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :nested-zero-n2,
    :source "declare(ticks=2) { echo 'A'; declare(ticks=0) { echo 'B'; } echo 'C'; }",
    :out "ABTC", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :dispatch-add,
    :source "unregister_tick_function('tick_handler'); function added() { echo 'N'; } function add_handler() { echo 'H'; register_tick_function('added'); } register_tick_function('add_handler'); declare(ticks=1) { echo 'A'; echo 'B'; }",
    :out "AHNBHNN", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :dispatch-remove,
    :source "unregister_tick_function('tick_handler'); function remove_handler() { echo 'H'; unregister_tick_function('victim'); } function victim() { echo 'V'; } register_tick_function('remove_handler'); register_tick_function('victim'); declare(ticks=1) { echo 'A'; echo 'B'; }",
    :out "AHBH", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :generator-foreach,
    :source "function g() { declare(ticks=1) { yield 1; echo 'G'; } } declare(ticks=1) { echo 'A'; foreach (g() as $v) { echo 'B'; } echo 'C'; }",
    :out "ATBTTGTTCT", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}
   {:id :include-handoff, :source "include 'lib.php';",
    :units {"lib.php" "<?php declare(ticks=1) { echo 'L'; }"},
    :out "LT", :diag [], :uncaught nil, :status 0, :oracle "php-8.3.35 shiva 2026-11-13"}])
