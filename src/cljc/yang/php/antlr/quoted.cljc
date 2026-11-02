(ns ^{:clj-kondo/config '{:lint-as {yang.php.antlr.quoted/defquoted clojure.core/def}}}
 yang.php.antlr.quoted
  "The `defquoted` macro the prelude's definition vectors share."
  #?(:cljs (:require-macros [yang.php.antlr.quoted :refer [defquoted]]))
  #?(:cljd (:require [clojure.edn :as edn])))


#?(:clj
   (defmacro defquoted
     "`def` of the quoted data `form`; ClojureDart reads it back from one EDN
      string (its quoted data compiles to straight-line Dart)."
     [name doc form]
     (if (find-ns 'cljd.compiler)
       (list 'def name doc (list 'edn/read-string (pr-str (second form))))
       (list 'def name doc form))))
