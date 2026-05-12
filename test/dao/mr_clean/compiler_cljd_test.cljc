(ns dao.mr-clean.compiler-cljd-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.mr-clean.compiler :as compiler]))


#?(:cljd
   (deftest compile-ui-preserves-real-unary-root-errors-in-cljd-fallback
     (testing
       "CLJD root fallback should not swallow a real unary-root compile exception"
       (let [root (fn [_props]
                    (throw (ex-info "boom from unary root" {:phase :root})))]
         (is (thrown-with-msg? Object
                               #"boom from unary root"
               (compiler/compile-ui root nil {} {})))))))
