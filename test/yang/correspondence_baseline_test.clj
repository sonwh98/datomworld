(ns yang.correspondence-baseline-test
  (:require [clojure.test :refer [deftest is]]
            [yang.correspondence-baseline :as baseline]))


(deftest baseline-refuses-unknown-program-assembly
  (let [prefix '[(def library 1)]
        prelude '[(def gate-execution-trace (stream/make 1000000))
                  (def gate-site-slot (cell/new :gate-site/none))
                  (defn gate-site!
                    [k args]
                    (cell/set! gate-site-slot k) args)]
        original '[(defn f
                     [x]
                     x)]
        traced '[(defn f
                   [x]
                   (stream/put! gate-execution-trace ['f 1 1]) x)]
        call '(f 1)
        program (vec (concat prefix prelude traced [call]))]
    (is (= (vec (concat prefix original [call]))
           (baseline/untraced-program! program original prelude traced call)))
    (is (thrown? Exception (baseline/untraced-program!
                             (assoc program 4 '(defn f
                                                 [x]
                                                 999)) original prelude traced call)))
    (is (thrown? Exception (baseline/untraced-program!
                             program original prelude traced '(f 2))))))


(deftest baseline-refuses-equal-but-differently-ordered-suffix
  (let [program (vector (array-map :a 1 :b 2) '(f))
        different (array-map :b 2 :a 1)]
    (is (= (first program) different))
    (is (thrown? Exception (baseline/untraced-program! program [] [different] [] '(f))))))


(deftest baseline-allows-only-bootstrap-location-metadata-difference
  (let [bootstrap '(def gate-execution-trace (stream/make 1000000))
        actual (with-meta bootstrap {:line 10})
        expected (with-meta bootstrap {:line 20})
        call '(f)
        program [actual call]]
    (is (= [call] (baseline/untraced-program! program [] [expected] [] call)))
    (is (thrown? Exception
          (baseline/untraced-program! [(with-meta '(def other 1) {:line 10}) call]
                                      [] [(with-meta '(def other 1) {:line 20})] [] call)))))


(deftest historical-two-field-tracer-instruments-each-arity
  (is (= '(defn f
            ([x] (stream/put! gate-execution-trace (quote [f 1])) x)
            ([x y] (stream/put! gate-execution-trace (quote [f 2])) y))
         (baseline/two-field-trace-form '(defn f
                                           ([x] x)
                                           ([x y] y)))))
  (is (= '(def x 1) (baseline/two-field-trace-form '(def x 1)))))
