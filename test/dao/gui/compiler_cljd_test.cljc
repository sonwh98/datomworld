(ns dao.gui.compiler-cljd-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.gui.compiler :as compiler]))


#?(:cljd
   (deftest compile-ui-preserves-real-unary-root-errors-in-cljd-fallback
     (testing
       "CLJD root fallback should not swallow a real unary-root compile exception"
       (let [root (fn [_props]
                    (throw (ex-info "boom from unary root" {:phase :root})))]
         (try (compiler/compile-ui root nil {} {})
              (is false "expected unary root exception to propagate")
              (catch Object e
                (is (= "boom from unary root" (ex-message e)))))))))
