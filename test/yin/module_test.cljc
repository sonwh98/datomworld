(ns yin.module-test
  "Tests for the yin.module registry mechanism.

   Modules self-register by calling module/register-module! and
   module/register-effect-handler! at namespace load time.
   Requiring the namespace is sufficient to trigger registration — no
   explicit init call needed."
  (:require
    [clojure.test :refer [deftest is testing]]
    ;; Required for side effects: each namespace calls register-module!
    ;; and register-effect-handler! at top level when loaded.
    [yin.io.file-input-stream]
    [yin.io.file-output-stream]
    [yin.module :as module]
    #?(:clj [yin.vm :as vm])))


#?(:clj
   (deftest module-require-effect-handler-test
     (testing ":module/require effect handler is registered"
       (is (fn? (module/get-effect-handler :module/require))))

     (testing ":module/require handler loads namespace and makes module binding available"
       (let [handler (module/get-effect-handler :module/require)
             state {}
             effect {:effect :module/require :module 'yin.io.file-input-stream}
             result (handler state effect nil)]
         (is (= 'yin.io.file-input-stream (:value result)))
         (is (= state (:state result)))
         (is (fn? (module/resolve-module 'yin.io.file-input-stream)))))))


(deftest io-module-registers-on-require-test
  (testing "requiring yin.io.file-input-stream registers file-input-stream into 'yin.io module"
    (is (fn? (module/resolve-module 'yin.io.file-input-stream))))

  (testing "requiring yin.io.file-output-stream registers file-output-stream into 'yin.io module"
    (is (fn? (module/resolve-module 'yin.io.file-output-stream))))

  (testing "requiring yin.io.file-input-stream registers its effect handler"
    (is (fn? (module/get-effect-handler :io/file-input-stream))))

  (testing "requiring yin.io.file-output-stream registers its effect handler"
    (is (fn? (module/get-effect-handler :io/file-output-stream)))))
