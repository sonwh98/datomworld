(ns yang.core.defquoted
  "AOT-safe definition of quoted prelude data.")

#?(:clj
   (defmacro defquoted
  [name doc form]
  (if (find-ns 'cljd.compiler)
    (list 'def name doc (list 'edn/read-string (pr-str (second form))))
    (list 'def name doc form))))
