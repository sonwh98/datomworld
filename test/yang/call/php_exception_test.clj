(ns yang.call.php-exception-test
  "PHP boundary pins for ruling v2 R1+R2+R3 (one coherent round). Receivers
   and rethrows are parsed source; malformed protocol delivery uses the
   shared boundary kit. The focused local gate for the exception-php-r1r2r3
   round: the php subset of the shiva acceptance instrument, never a
   substitute for the full ledger."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [yang.call.conformance :as kit]
            [yang.call.exception-contract :as contract]
            [yang.call.exception-legs :as legs]
            [yang.call.exception-probes :as probes]
            [yang.php.antlr.prelude :as php-prelude]))


(defn- check-fixture
  "Run one php fixture isolated on all three vector VMs and assert its
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
        (is (= (legs/expected leg) actual) (str id " " backend))))))


(deftest ^:slow php-fresh-egress-test
  ;; R1: the function entry and the Closure entry read the supplied
  ;; :yang.call/raise and deliver every escaping exception (and binding
  ;; failure) exactly once as the thrown condition; nothing runs after.
  (check-fixture :php/fresh-named)
  (check-fixture :php/fresh-closure)
  (check-fixture :php/binding))


(deftest ^:slow php-foreign-reception-test
  ;; R2: an already-local Throwable is delivered unchanged (identity kept);
  ;; an otherwise-foreign payload becomes the generated local
  ;; ForeignException retaining the complete condition.
  (check-fixture :php/local-reception)
  (check-fixture :php/foreign-reception)
  (check-fixture :php/retained-foreign-lifetime)
  ;; Directed legs with php as the RECEIVER: typed catch matches the local
  ;; hierarchy, unchanged rethrow forwards, stored wrappers rethrow from a
  ;; new activation.
  (check-leg [:python :php :typed-catch])
  (check-leg [:python :php :unchanged-rethrow])
  (check-leg [:python :php :retained-lifetime])
  (check-leg [:clojure :php :catch-all])
  ;; The bridge as DONOR: a php escape crossing into a landed frontend.
  (check-leg [:php :typescript :catch-all]))


(deftest ^:slow php-protocol-reception-test
  ;; R3: not-callable / invalid-invocation / unsupported-binding retain the
  ;; complete condition instead of a fresh Error with selected text; the
  ;; saved-wrapper rethrow forwards the original condition, no wrapper
  ;; stacking.
  (check-fixture :php/protocol-not-callable)
  (check-fixture :php/protocol-invalid-invocation)
  (check-fixture :php/protocol-unsupported-binding)
  (check-fixture :php/saved-protocol-not-callable)
  (check-fixture :php/saved-protocol-invalid-invocation)
  (check-fixture :php/saved-protocol-unsupported-binding))


(deftest ^:slow php-forged-retention-test
  ;; R5: source-writable attributes never authenticate a retained
  ;; condition — a forged Exception carrying condition-looking props crosses
  ;; as a fresh :yang.call/thrown with the forged object as payload.
  (check-fixture :php/forged-retention))


(defn- injected
  "The kit's synthetic entry delivering `condition` to the raise exit."
  [condition]
  (list 'cell/new
        (list 'assoc {} :yang.call/entry
              (list 'fn ['invocation]
                    (list (list 'get 'invocation :yang.call/raise) condition)))))


(defn- boundary-call
  [callee args]
  (kit/boundary-call-form callee (kit/observation-vector-form args) []))


(defn- genuine-wrapper-bindings
  "Guest bindings that mint a genuine ForeignException wrapper through php
   reception: the contract `retain` catches it and returns it normally."
  []
  ['probe.condition (kit/observation-map-form
                      {:yang.call/reason :yang.call/thrown
                       :yang.call/value (list 'cell/new {:foreign/payload :identity})
                       :yang.call/data {:complete [:extra :evidence]}})
   'probe.injected (injected 'probe.condition)
   'probe.retained (boundary-call (kit/export-form :php "retain") ['probe.injected])
   'probe.wrapper (list 'get 'probe.retained :value)])


(defn- run-provenance-observation
  "The contract php source as caller, the python boundary donor beside it,
   and `observation` appended: all three linked vector VMs."
  [observation]
  (let [donor (kit/publish-donor! :python contract/boundary-source)]
    (kit/run-exception-program
      donor (kit/compose-exception donor ["boundary_target"] :php
                                   (contract/sources :php) observation))))


(deftest ^:slow php-copied-wrapper-forgery-test
  ;; A fresh state cell carrying a genuine wrapper's complete contents is a
  ;; DIFFERENT object: provenance is bound to the wrapper's identity (its
  ;; sealed state-cell reference), never to its copyable contents. Throwing
  ;; the copy from a new activation must NOT forward the original condition.
  (let [observation
        (kit/guard-exceptions-form
          [:php]
          (list 'let (into (genuine-wrapper-bindings)
                           ['probe.copy (list 'assoc {} :php/obj
                                              (list 'cell/new
                                                    (list 'cell/get
                                                          (list 'get 'probe.wrapper :php/obj))))
                            'probe.result (boundary-call (kit/export-form :php "raise_value")
                                                         ['probe.copy])])
                (kit/observation-map-form
                  {:retained (list '= :return (list 'get 'probe.retained :route))
                   :copy-refused
                   (list 'if (list '= :raise (list 'get 'probe.result :route))
                         (list 'if (list '= 1 (list 'get 'probe.result :transfers))
                               (list 'if (list '= :yang.call/thrown
                                               (list 'get (list 'get 'probe.result :condition)
                                                     :yang.call/reason))
                                     (list '= 'probe.copy
                                           (list 'get (list 'get 'probe.result :condition)
                                                 :yang.call/value))
                                     false)
                               false)
                         false)})))]
    (doseq [[backend actual] (run-provenance-observation observation)]
      (is (= {:retained true :copy-refused true} actual) (str backend)))))


(deftest ^:slow php-mutated-wrapper-forwarding-test
  ;; Mutating the genuine wrapper's own state (its copied-visible contents)
  ;; neither breaks nor replaces the association: rethrowing the SAME
  ;; wrapper still forwards the ORIGINAL condition (sol's round-2 attack,
  ;; adapted to the php handle layout).
  (let [observation
        (kit/guard-exceptions-form
          [:php]
          (list 'let (into (genuine-wrapper-bindings)
                           ['probe.attack
                            (list 'do (list 'cell/set!
                                            (list 'get 'probe.wrapper :php/obj)
                                            (list 'assoc
                                                  (list 'cell/get
                                                        (list 'get 'probe.wrapper :php/obj))
                                                  :ord 9999)))
                            'probe.result (boundary-call (kit/export-form :php "raise_value")
                                                         ['probe.wrapper])])
                (kit/observation-map-form
                  {:retained (list '= :return (list 'get 'probe.retained :route))
                   :original-forwarded
                   (list 'if (list '= :raise (list 'get 'probe.result :route))
                         (list 'if (list '= 1 (list 'get 'probe.result :transfers))
                               (list '= 'probe.condition (list 'get 'probe.result :condition))
                               false)
                         false)})))]
    (doseq [[backend actual] (run-provenance-observation observation)]
      (is (= {:retained true :original-forwarded true} actual) (str backend)))))


(deftest ^:slow php-kit-parity-test
  ;; The hard-rule pin: exercise :reason classification AND
  ;; :condition-identical payload identity at the ADMITTED guest-call
  ;; boundary the way yang.call.exception-contract-test does — the refusal
  ;; is produced by public validation (not-callable on an empty map),
  ;; delivered by a callable cell's raise exit through parsed PHP `receive`
  ;; and its bare `throw $e` rethrow.
  (let [source (str "<?php "
                    "function receive($f) { try { return $f(); } catch (Throwable $e) { throw $e; } }")
        observation
        (kit/guard-exceptions-form
          [:php]
          (list 'let ['condition (list 'get (boundary-call {} []) :condition)
                      'injected (injected 'condition)
                      'result (boundary-call (kit/export-form :php "receive") ['injected])]
                (kit/observation-map-form
                  {:reason (list 'get (list 'get 'result :condition) :yang.call/reason)
                   :condition-identical (list '= 'condition (list 'get 'result :condition))})))
        donor (kit/publish-donor! :python contract/boundary-source)
        results (kit/run-exception-program
                  donor (kit/compose-exception donor ["boundary_target"]
                                               :php source observation))]
    (is (= #{:semantic :stack :register} (set (keys results))))
    (doseq [[backend actual] results]
      (println "PHP-KIT-PARITY" backend actual)
      (is (= {:reason :yang.call/not-callable :condition-identical true} actual)
          (str backend)))))


(deftest ^:slow php-kit-batch-parity-test
  ;; Acceptance uses run-frontend, which shares a task across observations.
  ;; Isolated run-fixture pins alone cannot establish that batch path.
  (let [fixtures (filter #(= :php (:frontend %)) contract/fixtures)
        observations (probes/run-frontend :php)]
    (is (= (set (map :id fixtures)) (set (keys observations))))
    (doseq [{:keys [id expected]} fixtures
            :let [results (get observations id)]]
      (is (= #{:semantic :stack :register} (set (keys results))) (str id))
      (doseq [[backend actual] results]
        (println "PHP-KIT-BATCH" id backend actual)
        (is (= expected actual) (str id " " backend))))))


(deftest php-retention-store-surface-test
  ;; Host side (the php analog of the ts store-root pin's host half): the
  ;; private retained-condition store is not a runtime key and none of the
  ;; installed retention names is a module export, so admitted source cannot
  ;; reach the store by enumerating either, and no frozen pre-init export
  ;; binding can silently answer for it.
  (is (not-any? #(str/includes? (str %) "retained") php-prelude/runtime-keys)
      "no retained store/seal runtime key for admitted source to name")
  (is (not (contains? php-prelude/module-exports 'retained-condition))
      "retained-condition is not a module export: no frozen pre-init binding")
  (is (not (contains? php-prelude/module-exports 'raise-protocol))
      "raise-protocol is not a module export: no frozen pre-init binding")
  (is (not (contains? php-prelude/module-exports 'raise-foreign))
      "raise-foreign is not a module export: no frozen pre-init binding"))
