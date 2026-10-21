;; J0c round 2 harness stand-in (src/dev/jolt_j0c_parity.clj puts this root first
;; through -Sdeps; never on a JVM, Node or Dart path): the one thing
;; yang.python.antlr.repl-probe uses from yang.python.antlr.parser is
;; `make-worker`; the real namespace's :clj branch imports the generated Java
;; recognizers, which do not exist on jolt. The worker is the jolt adapter's.
(ns yang.python.antlr.parser
  (:require [yang.antlr.cst :as cst]))


(def grammar-id
  "grammars-v4/python/python3@8af0d4c26c796ea27c15c3d85418f2d0f77c3adb antlr-4.13.2")


(defn make-worker
  []
  (cst/make-worker {:grammar-id grammar-id}))
