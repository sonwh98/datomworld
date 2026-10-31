;; C4 P2 engineer helper (never staged): linked-prelude-test alone on Dart,
;; slow bodies enabled.
(require '[babashka.process :as p])
(System/exit
  (:exit (p/shell {:continue true, :extra-env {"DATOM_SLOW_TESTS" "1"}}
                  "bb src/dev/cljd_agg.clj --only yang.python.antlr.linked-prelude-test")))
