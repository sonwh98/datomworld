(ns yin.vm.module-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm.module :as module]))


(deftest registry-is-a-value-test
  (testing "An empty registry has no modules and no handlers"
    (let [r (module/empty-registry)]
      (is (= {} (:modules r)))
      (is (= {} (:effect-handlers r)))
      (is (empty? (module/list-modules r)))))
  (testing "Registration returns a new registry and leaves the old one alone"
    (let [r0 (module/empty-registry)
          r1 (module/register-module r0 'my.lib {'foo identity})]
      (is (nil? (module/resolve-module r0 'my.lib)))
      (is (some? (module/resolve-module r1 'my.lib)))
      (is (= identity (module/resolve-module r1 'my.lib.foo))))))


(deftest dotted-path-resolution-test
  (testing "A dotted symbol resolves through nested module segments"
    (let [r (module/register-module (module/empty-registry)
                                    'yin.io
                                    {'read (constantly :read)})]
      (is (= {'read (module/resolve-module r 'yin.io.read)}
             (module/resolve-module r 'yin.io)))
      (is (fn? (module/resolve-module r 'yin.io.read)))
      (is (nil? (module/resolve-module r 'yin.io.write))))))


(deftest effect-handler-registry-test
  (testing "Handlers are looked up in the supplied registry value"
    (let [handler (fn [state _e _o] {:state state, :value :handled, :blocked? false})
          r (module/register-effect-handler (module/empty-registry) :x/y handler)]
      (is (= handler (module/get-effect-handler r :x/y)))
      (is (nil? (module/get-effect-handler (module/empty-registry) :x/y))))))


(deftest stream-module-test
  (testing "The stream module is pure effect constructors"
    (is (= {:effect :stream/put, :stream :s, :val 1} (module/put! :s 1)))
    (is (= {:effect :stream/cursor, :stream :s} (module/cursor :s)))
    (is (= {:effect :stream/next, :cursor :c} (module/next! :c)))
    (is (= {:effect :stream/close, :stream :s} (module/close! :s)))
    (is (= {:effect :stream/make, :capacity nil} (module/make)))
    (is (= {:effect :stream/make, :capacity 8} (module/make 8))))
  (testing "take! is deliberately absent"
    (is (nil? (get module/stream-module 'take!)))
    (is (= #{'make 'put! 'cursor 'next! 'close!}
           (set (keys module/stream-module)))))
  (testing "Registering the stream module is a composition step"
    (let [r (module/register-stream-module (module/empty-registry))]
      (is (fn? (module/resolve-module r 'stream.put!))))))


(deftest require-handler-resolves-against-the-registry-only-test
  (testing "A registered module resolves"
    (let [r (module/register-module (module/empty-registry) 'my.lib {'a 1})
          {:keys [value blocked?]} (module/require-handler {:modules r}
                                                           {:effect
                                                            :module/require,
                                                            :module 'my.lib}
                                                           {})]
      (is (= 'my.lib value))
      (is (false? blocked?))))
  (testing "An absent module is an error; the host require does not survive"
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (module/require-handler {:modules (module/empty-registry)}
                                  {:effect :module/require,
                                   :module 'clojure.string}
                                  {})))))


(deftest default-registry-test
  (testing "The default registry carries the built-in require handler only"
    (let [r (module/default-registry)]
      (is (fn? (module/get-effect-handler r :module/require)))
      (is (empty? (module/list-modules r))))))
