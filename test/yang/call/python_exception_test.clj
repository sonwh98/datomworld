(ns yang.call.python-exception-test
  "Python boundary pins for ruling v2 R2/R3 + egress. Receivers and rethrows
   are parsed source; malformed protocol delivery uses the shared boundary kit.
   The focused local gate for the exception-py-r2r3 round: the python subset of
   the shiva acceptance instrument, never a substitute for the full ledger."
  (:require [clojure.test :refer [deftest is testing]]
            [yang.call.exception-contract :as contract]
            [yang.call.exception-legs :as legs]
            [yang.call.exception-probes :as probes]))


(defn- check-fixture
  "Run one python fixture isolated on all three vector VMs and assert its
   compliant `:expected` value on each backend."
  [id]
  (let [fixture (first (filter #(= id (:id %)) contract/fixtures))]
    (testing (str id)
      (doseq [[backend actual] (probes/run-fixture fixture)]
        (is (= (:expected fixture) actual) (str backend))))))


(defn- check-leg
  "Run one directed source pair/scenario and assert `legs/expected` per VM."
  [id]
  (let [leg (first (filter #(= id (:id %)) contract/pair-matrix))]
    (testing (str id)
      (doseq [[backend actual] (legs/run-leg leg)]
        (is (= (legs/expected leg) actual) (str backend))))))


(deftest ^:slow python-foreign-reception-test
  ;; R2: an otherwise-foreign payload is wrapped in a generated local
  ;; ForeignException; an already-local exception is delivered unchanged.
  (check-fixture :python/local-reception)           ; stays green
  (check-fixture :python/foreign-reception)         ; R→G
  (check-fixture :python/retained-foreign-lifetime) ; R→G
  ;; The clojure→python typed reception and stored-wrapper rethrow legs.
  (check-leg [:clojure :python :typed-catch])
  (check-leg [:clojure :python :unchanged-rethrow])
  (check-leg [:clojure :python :retained-lifetime]))


(deftest ^:slow python-protocol-reception-test
  ;; R3: not-callable / invalid-invocation / unsupported-binding retain the
  ;; complete condition instead of collapsing into a bare TypeError.
  (check-fixture :python/protocol-not-callable)
  (check-fixture :python/protocol-invalid-invocation)
  (check-fixture :python/protocol-unsupported-binding)
  (check-fixture :python/saved-protocol-not-callable)
  (check-fixture :python/saved-protocol-invalid-invocation)
  (check-fixture :python/saved-protocol-unsupported-binding))
