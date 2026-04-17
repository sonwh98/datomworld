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
    [yin.module :as module]))


(deftest io-module-registers-on-require-test
  (testing "requiring yin.io.file-input-stream registers file-input-stream into 'io module"
    (is (fn? (get (module/get-module 'io) 'file-input-stream))))

  (testing "requiring yin.io.file-output-stream registers file-output-stream into 'io module"
    (is (fn? (get (module/get-module 'io) 'file-output-stream))))

  (testing "requiring yin.io.file-input-stream registers its effect handler"
    (is (fn? (module/get-effect-handler :io/file-input-stream))))

  (testing "requiring yin.io.file-output-stream registers its effect handler"
    (is (fn? (module/get-effect-handler :io/file-output-stream)))))
