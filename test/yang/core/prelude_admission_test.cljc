(ns yang.core.prelude-admission-test
  (:require [clojure.test :refer [deftest is testing]]
            [yang.core.prelude-admission :as admission]
            [yang.core.prelude-recipes :as recipes]
            [yang.core.uast :as u]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.module :as module]))

(defn- tree [names]
  (vm/ast->semantic-bytecode
    (apply u/app (u/lam (mapv #(symbol (str "x" %)) (range (count names))) (u/lit nil))
           (map #(u/def! % (u/lit nil)) names))))

(defn- fixture []
  (let [a (tree ['a]) b (tree ['b])
        manifest (fn [name t req]
                   {:yin.module/name name :yin.module/tree (:root t)
                    :yin.module/requires req :yin.module/exports #{'value}
                    :yin.module/primitives {} :yin.module/footprint {:effects #{}}})]
    [{:yang.prelude/schema 1 :yang.prelude/id :test/prelude
      :yang.prelude/revision "1" :yang.prelude/module 'root
      :yang.prelude/root-address :root :yang.prelude/components [['dep :dep]]
      :yang.prelude/host-modules {} :yang.prelude/max-effects nil}
     {:root (manifest 'root a {'dep :dep}) :dep (manifest 'dep b {})
      (:root a) a (:root b) b}]))

(defn- refused? [f]
  (try (f) false (catch #?(:clj Throwable :cljs :default :cljd Object) _ true)))

(deftest verified-closure-and-admission
  (let [[p s] (fixture)]
    (is (= p (admission/validate-profile p s {})))
    (is (= {} (admission/admit-profile p s {} {})))
    (testing "missing manifest, missing tree, mismatched root pins and cycles fail"
      (is (refused? #(admission/validate-profile p (dissoc s :dep) {})))
      (is (refused? #(admission/validate-profile p (dissoc s (get-in s [:dep :yin.module/tree])) {})))
      (is (refused? #(admission/validate-profile (assoc p :yang.prelude/components []) s {})))
      (is (refused? #(admission/validate-profile p (assoc-in s [:dep :yin.module/requires] {'root :root}) {}))))
    (testing "duplicate module bindings and local export definitions fail"
      (is (refused? #(admission/validate-profile (assoc p :yang.prelude/components [['dep :dep] ['dep :other]]) s {})))
      (let [t (tree ['x 'x])]
        (is (refused? #(admission/validate-profile p (assoc (assoc-in s [:dep :yin.module/tree] (:root t)) (:root t) t) {})))))
    (testing "provenance declarations and exports are direct; effect ceiling is transitive"
      (is (refused? #(admission/validate-profile p (assoc-in s [:dep :yin.module/primitives 'cell/dsg__provenance] :p) {})))
      (is (refused? #(admission/validate-profile p (assoc-in s [:dep :yin.module/exports] #{'dsg__secret}) {})))
      (let [s (assoc-in s [:dep :yin.module/footprint :effects] #{:cell})]
        (is (= p (admission/validate-profile p s {})))
        (is (refused? #(admission/validate-profile (assoc p :yang.prelude/max-effects #{}) s {})))))
    (testing "host limits fail closed"
      (let [p (assoc p :yang.prelude/host-modules {:integer {:required false :min-bits 53 :min-digits 3}})]
        (is (refused? #(admission/admit-profile p s {} {})))
        (is (refused? #(admission/admit-profile p s {} {:integer {:bits 52}})))
        (is (= {} (admission/admit-profile p s {} {:integer {:bits 53 :digits 3}})))
        (is (refused? #(admission/admit-profile p s {} {:integer {:bits 53 :digits 2}})))))))


(deftest sequenced-definition-collisions
  (let [[p s] (fixture)
        validate (fn [ast]
                   (let [t (vm/ast->semantic-bytecode ast)]
                     (admission/validate-profile p
                       (assoc (assoc-in s [:dep :yin.module/tree] (:root t)) (:root t) t) {})))
        definition #(u/def! % (u/lit nil))]
    (is (refused? #(validate (u/then (definition 'x) (definition 'x)))))
    (is (refused? #(validate (u/seq-nodes (map definition ['x 'y 'x])))))
    (is (refused? #(validate (u/then (u/then (definition 'x) (definition 'y))
                                    (u/then (definition 'z) (definition 'x))))))
    (is (= p (validate (u/then (definition 'x)
                                (u/def! 'y (u/lam [] (definition 'x)))))))
    (is (= p (validate (u/then (definition 'x) (u/lit (definition 'x))))))))


(deftest host-export-and-trust-admission
  (let [[p s] (fixture)
        p (assoc p :yang.prelude/host-modules {:data {:required true :min-max-items 10}})
        s (assoc-in s [:dep :yin.module/primitives 'data/count] :profile)
        registry (data/register-data-module (module/default-registry) {::data/max-items 10})]
    (is (= registry (admission/admit-profile p s registry {:data {:max-items 10}})))
    (is (refused? #(admission/admit-profile p s registry {})))
    (is (refused? #(admission/admit-profile p s registry {:data {:max-items 9}})))
    (is (refused? #(admission/admit-profile p s {} {:data {:max-items 10}})))
    (is (refused? #(admission/admit-profile p (assoc-in s [:dep :yin.module/primitives 'data/missing] :p)
                                             registry {:data {:max-items 10}})))
    (is (refused? #(admission/admit-profile (assoc p :yang.prelude/host-modules {}) s registry {})))
    (let [s (assoc-in s [:dep :yin.module/primitives 'cell/dsg__provenance] :p)]
      (is (refused? #(admission/validate-profile p s {})))
      (is (= p (admission/validate-profile p s {:trusted-prelude-modules {'dep :dep}})))
      (is (refused? #(admission/validate-profile p s {:trusted-prelude-modules {'dep :wrong}}))))))


(deftest recorded-emitter-modes
  (let [records (recipes/base-recipes :call-address (zipmap ['py 'js 'php 'clj 'yang.call] (repeat {})))]
    (is (= {:py :linear :js :balanced :php :balanced}
           (into {} (map (fn [n] [(keyword (name n)) (get-in records [n :yang.prelude/emitter :mode])])
                         ['py 'js 'php]))))
    (is (= ['yang.call] (get-in records ['py :yang.prelude/emitter :trailing-requires])))
    (is (false? (get-in records ['py :yang.prelude/emitter :finalize-c1?])))
    (is (true? (get-in records ['js :yang.prelude/emitter :finalize-c1?])))
    (is (= [['yang.call :call-address]] (get-in records ['php :yang.prelude/components])))))
