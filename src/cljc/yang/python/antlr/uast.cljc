(ns yang.python.antlr.uast
  "The Python lowering's name for `yang.core.uast` (docs/design/yang.typescript.md
   §17, lift L2): every var is the shared one."
  (:require
    [yang.core.uast :as core]))


(def lit core/lit)


(def v core/v)


(def lam core/lam)


(def app core/app)


(def if-node core/if-node)


(def capture core/capture)


(def def! core/def!)


(def discard core/discard)


(def then core/then)


(def seq-nodes core/seq-nodes)


(def let1 core/let1)


(def sexp->uast core/sexp->uast)


(def mark-tails core/mark-tails)
