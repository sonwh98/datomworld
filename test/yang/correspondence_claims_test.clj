(ns yang.correspondence-claims-test
  (:require [clojure.test :refer [deftest is]]
            [yang.correspondence-claims :as claims]))


(deftest builds-deterministic-claim-plan-with-scoped-carriers
  (let [inventory [{:symbol 'latte-kernel.foo/f :disposition :admitted :arities [2 1]}
                   {:symbol 'latte-kernel.foo/g :disposition :excluded :arities [1]}]
        carriers {['latte-kernel.foo/f 1] #{['carrier 2 :context-shift]}}]
    (is (= [{:upstream-symbol 'latte-kernel.foo/f :guest-symbol 'gate-foo-f
             :requested-arity 1 :carriers [['carrier 2 :context-shift]]}
            {:upstream-symbol 'latte-kernel.foo/f :guest-symbol 'gate-foo-f
             :requested-arity 2 :carriers []}]
           (claims/claim-plan! inventory carriers)))
    (is (thrown? Exception (claims/claim-plan! inventory {['latte-kernel.foo/f 1] #{['carrier 2 :invented]}})))
    (is (thrown? Exception (claims/claim-plan! inventory {['latte-kernel.foo/g 1] #{['carrier 2 :context-shift]}})))
    (is (thrown? Exception (claims/claim-plan! (conj inventory (first inventory)) carriers)))))


(deftest independently-derives-ambient-shift-and-copy-carriers
  (let [f 'latte-kernel.foo/f
        g 'latte-kernel.foo/g
        ns-id 'latte_kernel.foo
        inventory [{:ns ns-id :name 'f :symbol f :kind 'defn :disposition :admitted :arities [1 2]}
                   {:ns ns-id :name 'g :symbol g :kind 'defn :disposition :admitted :arities [1]}]
        upstream {[ns-id 'f] '(defn f
                                ([x] (f empty-env x))
                                ([env x] x))
                  [ns-id 'g] '(defn g
                                [x]
                                x)}
        context '[(defn carrier
                    [kernel x]
                    x) (defn copy-g
                         [x]
                         x)]
        integration {f 'carrier g 'copy-g}
        resolutions {[ns-id 'f] f [ns-id 'empty-env] 'latte-kernel.foo/empty-env}
        result (claims/derive-carriers! inventory upstream context [] [] integration #{}
                                        #{'latte-kernel.foo/empty-env} resolutions)]
    (is (= {[f 1] #{['carrier 2 :ambient-delegation]}
            [f 2] #{['carrier 2 :context-shift]}
            [g 1] #{['copy-g 1 :context-copy]}} result))
    (is (thrown? Exception
          (claims/check-carriers! result (assoc result [f 1] #{['forged 2 :context-shift]}))))))


(deftest refuses-malformed-upstream-callable
  (is (thrown? Exception
        (claims/derive-carriers!
          [{:ns 'example :name 'f :symbol 'example/f :kind 'defn :disposition :admitted}]
          {['example 'f] '(defn f)} [] [] [] {} #{} #{} {}))))
