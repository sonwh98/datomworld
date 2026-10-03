(ns dao.test-slow
  "Opt-in switch for the ^:slow tests on hosts whose runner has no tag
   filter. The JVM runner excludes ^:slow itself (`bb test:clj`), so there
   `enabled?` is always true and a test runs whenever the runner selects it.
   Node and Dart have no tag filter: a slow test is registered but its body
   runs only when DATOM_SLOW_TESTS=1, otherwise it prints a skip notice."
  #?@(:cljd [(:require ["dart:io" :as dart-io])]))


(def env-name "DATOM_SLOW_TESTS")


(defn enabled?
  "True when this host should run the slow tests."
  []
  #?(:cljd (= "1" (get (.-environment dart-io/Platform) env-name))
     :clj true
     :cljs (boolean (and (exists? js/process)
                         (= "1" (aget (.-env js/process) env-name))))))


(defn guard
  "Run `thunk` when slow tests are enabled on this host, else say that
   `test-name` was skipped."
  [test-name thunk]
  (if (enabled?)
    (thunk)
    (println "SKIP slow test" test-name "(set DATOM_SLOW_TESTS=1 to run)")))
