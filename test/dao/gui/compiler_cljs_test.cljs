(ns dao.gui.compiler-cljs-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [dao.gui.compiler :as compiler]))


(deftest compile-ui-preserves-real-unary-root-errors-in-cljs-fallback
  (testing
    "CLJS root fallback should not swallow a real unary-root compile exception"
    (let [root (fn [_props]
                 (throw (ex-info "boom from unary root" {:phase :root})))]
      (is (thrown-with-msg? js/Error
                            #"boom from unary root"
            (compiler/compile-ui root nil {} {}))))))
