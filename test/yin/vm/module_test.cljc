(ns yin.vm.module-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.module :as module]))


(defn- profile
  "A UCF 7.5.2 profile record, as `yin.vm/primitive-profiles` publishes.
   One arity: `:pure`. Two arities: `:effectful` with an effect set."
  ([sym arities]
   (vm/primitive-profile sym :pure arities #{} :none))
  ([sym arities effects]
   (vm/primitive-profile sym :effectful arities effects :none)))


(defn- refusal-of
  "Evaluate f, answering the ex-data of the defect it throws, or nil."
  [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs js/Error :cljd Object) e
      (ex-data e))))


(deftest registry-is-a-value-test
  (testing "An empty registry has no modules and no handlers"
    (let [r (module/empty-registry)]
      (is (= {} (:modules r)))
      (is (= {} (:effect-handlers r)))
      (is (empty? (module/list-modules r)))))
  (testing "Registration returns a new registry and leaves the old one alone"
    (let [r0 (module/empty-registry)
          r1 (module/register-host-module r0 'my.lib
                                          {'foo identity}
                                          {'foo (profile 'foo [1])})]
      (is (nil? (module/resolve-module r0 'my.lib)))
      (is (some? (module/resolve-module r1 'my.lib)))
      (is (= identity (module/resolve-module r1 'my.lib.foo))))))


(deftest register-host-module-enforces-profile-classes-test
  (testing "A well-profiled binding registers and resolves"
    (let [r (module/register-host-module
              (module/empty-registry)
              'my.lib
              {'inc* inc, 'emit (fn [x] {:effect :test/x, :val x})}
              {'inc* (profile 'inc* [1]),
               'emit (profile 'emit [1] #{:test/x})})]
      (is (= inc (module/resolve-module r 'my.lib.inc*)))
      (is (fn? (module/resolve-module r 'my.lib.emit)))))
  (testing "A :host-class profile is refused"
    (is (= :host-class
           (:rule (refusal-of
                    #(module/register-host-module
                       (module/empty-registry) 'my.lib {'f identity}
                       {'f (vm/primitive-profile 'f :host [1] #{} :none)}))))))
  (testing "A profile declaring host state is refused"
    (is (= :host-state
           (:rule (refusal-of
                    #(module/register-host-module
                       (module/empty-registry) 'my.lib {'f identity}
                       {'f (vm/primitive-profile 'f :pure [1] #{} :host)}))))))
  (testing "A binding with no profile is refused"
    (is (= :missing-profile
           (:rule (refusal-of
                    #(module/register-host-module
                       (module/empty-registry) 'my.lib {'f identity} {}))))))
  (testing "An :effectful profile with an empty effect set is refused"
    (is (= :undeclared-effects
           (:rule (refusal-of
                    #(module/register-host-module
                       (module/empty-registry) 'my.lib {'f identity}
                       {'f (profile 'f [1] #{})}))))))
  (testing "The refusal names the module and the first defective binding"
    (let [data (refusal-of #(module/register-host-module
                              (module/empty-registry) 'my.lib
                              {'g identity, 'f identity} {}))]
      (is (= 'my.lib (:module data)))
      (is (= 'f (:name data))))))


(deftest host-module-entry-shape-test
  (let [p (profile 'foo [1])
        r (module/register-host-module (module/empty-registry)
                                       'my.lib {'foo identity} {'foo p})
        entry (module/resolve-module r 'my.lib)]
    (testing "The entry is the linked-registry value shape"
      (is (= #{:manifest :address :derivation :slice :stores}
             (set (keys entry))))
      (is (= {'foo identity} (:slice entry)))
      (is (= {} (:stores entry)))
      (is (nil? (:address entry)))
      (is (nil? (:derivation entry))))
    (testing "A host module is entered as an already-linked manifest"
      (let [m (:manifest entry)]
        (is (= 'my.lib (:yin.module/name m)))
        (is (false? (contains? m :yin.module/tree)))
        (is (= {} (:yin.module/derivations m)))
        (is (= {'foo (:yin.k/profile p)} (:yin.module/primitives m)))))))


(deftest dotted-path-resolution-test
  (testing "A dotted symbol resolves through nested module segments"
    (let [r (module/register-host-module (module/empty-registry)
                                         'yin.io
                                         {'read (constantly :read)}
                                         {'read (profile 'read [1])})]
      (is (fn? (module/resolve-module r 'yin.io.read)))
      (is (nil? (module/resolve-module r 'yin.io.write)))
      (is (some? (module/resolve-module r 'yin.io))))))


(deftest effect-handler-registry-test
  (testing "Handlers are looked up in the supplied registry value"
    (let [handler (fn [state _e _o]
                    {:state state, :value :handled, :blocked? false})
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
  (testing "Every stream binding carries a profile in the shape of
            yin.vm/primitive-profiles"
    (is (= (set (keys module/stream-module))
           (set (keys module/stream-profiles))))
    (let [shapes (set (map #(set (keys %)) (vals vm/primitive-profiles)))]
      (is (= 1 (count shapes)))
      (is (every? #(contains? shapes (set (keys %)))
                  (vals module/stream-profiles))))
    (is (= #{:stream/make :stream/put :stream/cursor :stream/next
             :stream/close}
           (set (mapcat :yin.k/effects (vals module/stream-profiles))))))
  (testing "Registering the stream module is a composition step"
    (let [r (module/register-stream-module (module/empty-registry))]
      (is (fn? (module/resolve-module r 'stream.put!)))
      (is (some? (module/resolve-module r 'stream))))))


(deftest require-handler-resolves-against-the-registry-only-test
  (testing "A registered module resolves"
    (let [r (module/register-host-module (module/empty-registry)
                                         'my.lib
                                         {'a (constantly 1)}
                                         {'a (profile 'a [0])})
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
