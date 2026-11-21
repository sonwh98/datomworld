(ns yin.vm.float-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.host.float :as host]
            [yin.vm.data :as data]
            [yin.vm.float :as float]
            [yin.vm :as vm]
            [yin.vm.module :as module]))

(defn- call [op & args] (apply (get float/float-module op) args))
(defn- box [x] (data/float64 x))
(defn- bits [x] (host/float64->bits (data/float-value x)))

(deftest boxed-arithmetic
  (is (= "4008000000000000" (bits (call 'add (box 1.0) (box 2.0)))))
  (is (= "8000000000000000" (bits (call 'neg (box 0.0)))))
  (is (= "0000000000000000" (bits (call 'abs (call 'neg-zero)))))
  (is (= ::float/div-zero (call 'div-checked (box 1.0) (call 'neg-zero))))
  (is (= "fff0000000000000" (bits (call 'div (box 1.0) (call 'neg-zero)))))
  (is (= ::float/nan (call 'compare (call 'nan) (call 'nan))))
  (is (= 0 (call 'compare (call 'zero) (call 'neg-zero))))
  (is (false? ((get data/data-module 'equiv) (call 'nan) (call 'nan))))
  (is (true? ((get data/data-module 'equiv) (call 'zero) (call 'neg-zero)))))

(deftest division-and-powers
  (let [[q r] (call 'floordiv-mod (box 2.1) (box 0.7))]
    (is (= 3.0 (data/float-value q)))
    (is (>= (data/float-value r) 0.0)))
  (is (= "8000000000000000" (bits (first (call 'floordiv-mod (call 'neg-zero) (box 2.0))))))
  (is (= ::float/underflow (call 'neg-pow (box 0.5) 2000 :half)))
  (is (= 8.0 (data/float-value (call 'fpow-half (box 2.0) 3))))
  (is (= -2 (call 'floor-int (box -1.2))))
  (is (= ::float/out-of-range (call 'floor-int (box 9007199254740992.0))))
  (is (= 0.5 (data/float-value (call 'fmod-pos (box 5.5) (box 1.0))))))

(deftest float-format-and-profile
  (doseq [[x expected] [[1.0 "1.0"] [0.0 "0.0"] [-0.0 "-0.0"]
                        [##Inf "Infinity"] [##-Inf "-Infinity"] [##NaN "NaN"]]]
    (is (= expected (call 'format (box x)))))
  (is (= [6755399441055744 -52] (call 'parts (box 1.5))))
  (is (= (set (keys float/float-module)) (set (keys float/float-profiles))))
  (is (not (contains? float/float-module 'fpow-base))))

(deftest composition-registration
  (doseq [[sym profile] float/float-profiles]
    (is (= :pure (:yin.k/class profile)) (str sym))
    (is (= #{} (:yin.k/effects profile)) (str sym))
    (is (= :none (:yin.k/host-state profile)) (str sym)))
  (is (not-any? #(contains? vm/primitives (symbol "float" (name %)))
                (keys float/float-module)))
  (is (nil? (module/resolve-module (module/default-registry) 'float)))
  (let [registry (float/register-float-module (module/default-registry))]
    (is (some? (module/resolve-module registry 'float)))
    (is (some? (module/resolve-module registry 'float.add)))))
