(ns yang.call.python-exception-test
  "Python boundary pins for ruling v2 R2/R3 + egress. Receivers and rethrows
   are parsed source; malformed protocol delivery uses the shared boundary kit.
   The focused local gate for the exception-py-r2r3 round: the python subset of
   the shiva acceptance instrument, never a substitute for the full ledger."
  (:require [clojure.test :refer [deftest is testing]]
            [yang.call.conformance :as kit]
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


(deftest ^:slow python-egress-sanity-test
  ;; Egress forwards a retained generated exception's original condition and
  ;; still produces the plain thrown condition for fresh/forged payloads.
  (check-fixture :python/fresh-named)       ; stays green
  (check-fixture :python/fresh-closure)     ; stays green
  (check-fixture :python/binding)           ; stays green
  (check-fixture :python/forged-retention)) ; stays green (R5: no source-writable forgery)


(deftest ^:slow python-kit-parity-test
  ;; Match exception-probes/protocol-condition and injected exactly: the
  ;; refusal is produced by public validation, then delivered by a callable
  ;; cell's raise exit through a parsed Python f() and bare re-raise.
  (let [donor (kit/publish-donor! :python contract/boundary-source)
        source "def receive(f):\n    try:\n        return f()\n    except BaseException as e:\n        raise\n"
        call (fn [callee args]
               (kit/boundary-call-form callee (kit/observation-vector-form args) []))
        observation
        (kit/guard-exceptions-form
          [:python]
          (list 'let
                ['condition (list 'get (call {} []) :condition)
                 'injected
                 (list 'cell/new
                       (list 'assoc {} :yang.call/entry
                             (list 'fn ['invocation]
                                   (list (list 'get 'invocation :yang.call/raise)
                                         'condition))))
                 'result (call (kit/export-form :python "receive") ['injected])]
                (kit/observation-map-form
                  {:reason (list 'get (list 'get 'result :condition) :yang.call/reason)
                   :condition-identical (list '= 'condition (list 'get 'result :condition))})))
        results (kit/run-exception-program
                  donor (kit/compose-exception donor ["boundary_target"]
                                               :python source observation))]
    (is (= #{:semantic :stack :register} (set (keys results))))
    (doseq [[backend actual] results]
      (println "PYTHON-KIT-PARITY" backend actual)
      (is (= {:reason :yang.call/not-callable :condition-identical true} actual)
          (str backend)))))


(deftest ^:slow python-kit-batch-parity-test
  ;; Acceptance uses run-frontend, which shares a task across observations.
  ;; Isolated run-fixture pins alone cannot establish that batch path.
  (let [fixtures (filter #(= :python (:frontend %)) contract/fixtures)
        observations (probes/run-frontend :python)]
    (is (= (set (map :id fixtures)) (set (keys observations))))
    (doseq [{:keys [id expected]} fixtures
            :let [results (get observations id)]]
      (is (= #{:semantic :stack :register} (set (keys results))) (str id))
      (doseq [[backend actual] results]
        (println "PYTHON-KIT-BATCH" id backend actual)
        (is (= expected actual) (str id " " backend))))))


(def ^:private provenance-donor-source
  "(defn forge [e condition] (cell/new (assoc (cell/get e) :py/retained-condition condition)))
   (defn clone [e] (cell/new (cell/get e)))
   (defn overwrite [e condition]
     (do (cell/set! e (assoc (cell/get e) :py/retained-condition condition)) e))")


(defn- check-cell-provenance
  [attack]
  (let [donor (kit/publish-donor! :clojure provenance-donor-source)
        source (str "payload = None\n"
                    "def attacked(f, condition):\n    global payload\n"
                    (if (= attack :forge)
                      "    payload = forge(Exception('fresh'), condition)\n"
                      (str "    try:\n        f()\n    except BaseException as e:\n"
                           (if (= attack :clone)
                             "        payload = clone(e)\n"
                             "        payload = overwrite(e, condition)\n")))
                    "    raise payload\n"
                    "def attacked_payload():\n    return payload\n")
        call (fn [callee args]
               (kit/boundary-call-form callee (kit/observation-vector-form args) []))
        observation
        (kit/guard-exceptions-form
          [:python]
          (list 'let
                ['condition (list 'get (call {} []) :condition)
                 'replacement (list 'assoc 'condition :probe/forged true)
                 'injected
                 (list 'cell/new
                       (list 'assoc {} :yang.call/entry
                             (list 'fn ['invocation]
                                   (list (list 'get 'invocation :yang.call/raise)
                                         'condition))))
                 'result (call (kit/export-form :python "attacked")
                               ['injected 'replacement])
                 'payload (list 'get (call (kit/export-form :python "attacked_payload") [])
                                :value)]
                (kit/observation-map-form
                  (cond->
                    {:route (list 'get 'result :route)
                     :transfers (list 'get 'result :transfers)
                     :after (list 'get 'result :after)
                     :reason (list 'get (list 'get 'result :condition) :yang.call/reason)
                     :original-forwarded (list '= 'condition (list 'get 'result :condition))
                     :replacement-forwarded (list '= 'replacement (list 'get 'result :condition))}
                    (not= attack :overwrite)
                    (assoc :payload-identical
                           (list '= 'payload (list 'get (list 'get 'result :condition)
                                                   :yang.call/value)))))))
        results (kit/run-exception-program
                  donor (kit/compose-exception donor ["forge" "clone" "overwrite"]
                                               :python source observation))
        expected (cond-> {:route :raise :transfers 1 :after false
                          :reason (if (= attack :overwrite)
                                    :yang.call/not-callable :yang.call/thrown)
                          :original-forwarded (= attack :overwrite)
                          :replacement-forwarded false}
                   (not= attack :overwrite) (assoc :payload-identical true))]
    (is (= #{:semantic :stack :register} (set (keys results))))
    (doseq [[backend actual] results]
      (println "PYTHON-CELL-PROVENANCE" attack backend actual)
      (is (= expected actual) (str attack " " backend)))))


(deftest ^:slow python-cell-forgery-test
  (check-cell-provenance :forge))


(deftest ^:slow python-retained-clone-test
  (check-cell-provenance :clone))


(deftest ^:slow python-retained-overwrite-test
  (check-cell-provenance :overwrite))
