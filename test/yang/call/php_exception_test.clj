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


(def ^:private egress-source
  "The contract's php exception shapes with the module-level `$original`
   replaced by a function-seeded global. php's run-main shutdown releases
   the caller unit's globals when the unit returns (PHP script-end
   semantics), and the kit's boundary observations run AFTER the caller
   unit — so a module-level `$original = new Exception(...)` reads back
   null through `payload()` in this lane (a pre-existing instrument/lane
   conflict recorded in the round's findings; the base commit behaves the
   same). A global WRITTEN by a called function after the unit's run
   persists for the later observation calls, so `seed` mints the
   identity-bearing original the directed legs below throw, rethrow and
   retain — the same assertions the three lane-blocked fixtures
   (:php/fresh-named, :php/fresh-closure, :php/local-reception) make on
   module-level state. `retain` keeps its wrapper in `$caught`, never
   touching the original's `$saved`."
  (str "<?php $saved = null; $caught = null;"
       "function seed() { global $saved; $saved = new Exception('original'); return $saved; }"
       "function payload() { global $saved; return $saved; }"
       "function donor_raise() { global $saved; throw $saved; }"
       "function make_exploder() { return function() { global $saved; throw $saved; }; }"
       "function receive($f) { try { return $f(); } catch (Throwable $e) { throw $e; } }"
       "function retain($f) { global $caught; try { return $f(); }"
       " catch (Throwable $e) { $caught = $e; return $e; } }"))


(defn- run-egress-observation
  "The egress source as caller, the python boundary donor beside it, and
   `observation` appended: all three linked vector VMs."
  [observation]
  (let [donor (kit/publish-donor! :python contract/boundary-source)]
    (kit/run-exception-program
      donor (kit/compose-exception donor ["boundary_target"] :php
                                   egress-source observation))))


(deftest ^:slow php-fresh-egress-directed-test
  ;; R1, directed (the in-lane form of :php/fresh-named and
  ;; :php/fresh-closure): the function entry and the Closure entry deliver
  ;; the escaping source Exception to the supplied raise exit exactly once
  ;; as {:yang.call/thrown, original} — the payload IS the thrown object.
  (let [observation
        (kit/guard-exceptions-form
          [:php]
          (list 'let ['probe.seeded (boundary-call (kit/export-form :php "seed") [])
                      'original (list 'get 'probe.seeded :value)
                      'probe.named (boundary-call (kit/export-form :php "donor_raise") [])
                      'probe.closure (boundary-call
                                       (list 'get (boundary-call
                                                    (kit/export-form :php "make_exploder") [])
                                             :value) [])]
                (kit/observation-map-form
                  {:seeded (list '= :return (list 'get 'probe.seeded :route))
                   :named-transferred (list '= :raise (list 'get 'probe.named :route))
                   :named-once (list '= 1 (list 'get 'probe.named :transfers))
                   :named-after (list 'get 'probe.named :after)
                   :named-reason (list 'get (list 'get 'probe.named :condition)
                                       :yang.call/reason)
                   :named-payload-identical
                   (list '= 'original (list 'get (list 'get 'probe.named :condition)
                                            :yang.call/value))
                   :closure-transferred (list '= :raise (list 'get 'probe.closure :route))
                   :closure-once (list '= 1 (list 'get 'probe.closure :transfers))
                   :closure-after (list 'get 'probe.closure :after)
                   :closure-reason (list 'get (list 'get 'probe.closure :condition)
                                         :yang.call/reason)
                   :closure-payload-identical
                   (list '= 'original (list 'get (list 'get 'probe.closure :condition)
                                            :yang.call/value))})))
        expected {:seeded true
                  :named-transferred true :named-once true :named-after false
                  :named-reason :yang.call/thrown :named-payload-identical true
                  :closure-transferred true :closure-once true :closure-after false
                  :closure-reason :yang.call/thrown :closure-payload-identical true}]
    (doseq [[backend actual] (run-egress-observation observation)]
      (is (= expected actual) (str backend)))))


(deftest ^:slow php-local-reception-directed-test
  ;; R2, directed (the in-lane form of :php/local-reception): a thrown
  ;; condition whose payload is already a php Throwable keeps its ordinary
  ;; catching and identity — re-raised UNCHANGED (typed catch, unchanged
  ;; rethrow, returned identity), never wrapped.
  (let [observation
        (kit/guard-exceptions-form
          [:php]
          (list 'let ['probe.seeded (boundary-call (kit/export-form :php "seed") [])
                      'original (list 'get 'probe.seeded :value)
                      'probe.condition (kit/observation-map-form
                                         {:yang.call/reason :yang.call/thrown
                                          :yang.call/value 'original})
                      'probe.injected (injected 'probe.condition)
                      'probe.reres (boundary-call (kit/export-form :php "receive")
                                                  ['probe.injected])
                      'probe.rtres (boundary-call (kit/export-form :php "retain")
                                                  ['probe.injected])]
                (kit/observation-map-form
                  {:seeded (list '= :return (list 'get 'probe.seeded :route))
                   :rethrow-transferred (list '= :raise (list 'get 'probe.reres :route))
                   :rethrow-once (list '= 1 (list 'get 'probe.reres :transfers))
                   :rethrow-reason (list 'get (list 'get 'probe.reres :condition)
                                         :yang.call/reason)
                   :rethrow-payload-identical
                   (list '= 'original (list 'get (list 'get 'probe.reres :condition)
                                            :yang.call/value))
                   :retain-returned (list '= :return (list 'get 'probe.rtres :route))
                   :retain-payload-identical
                   (list '= 'original (list 'get 'probe.rtres :value))})))
        expected {:seeded true
                  :rethrow-transferred true :rethrow-once true
                  :rethrow-reason :yang.call/thrown
                  :rethrow-payload-identical true
                  :retain-returned true :retain-payload-identical true}]
    (doseq [[backend actual] (run-egress-observation observation)]
      (is (= expected actual) (str backend)))))


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
  ;; binding can silently answer for it. The r7 steward addendum's F2
  ;; mirror: the GENERAL surface half — no reserved-door (dsg__) name and
  ;; no mint/retention name appears in EITHER surface, not just the
  ;; individually named ones (the definition-graph half lives in
  ;; php-mint-reachability-test).
  (is (not-any? #(str/includes? (str %) "retained") php-prelude/runtime-keys)
      "no retained store/seal runtime key for admitted source to name")
  (is (not-any? #(str/includes? (str %) "dsg__") php-prelude/runtime-keys)
      "no reserved-door runtime key for admitted source to name")
  (is (not-any? #(str/includes? (str %) "dsg__") php-prelude/module-exports)
      "no reserved-door name is a module export")
  (is (not-any? #(re-find #"mint|retained|raise-foreign|raise-protocol"
                          (str %))
                php-prelude/module-exports)
      "no mint/retention name is a module export")
  (is (not (contains? php-prelude/module-exports 'retained-condition))
      "retained-condition is not a module export: no frozen pre-init binding")
  (is (not (contains? php-prelude/module-exports 'raise-protocol))
      "raise-protocol is not a module export: no frozen pre-init binding")
  (is (not (contains? php-prelude/module-exports 'raise-foreign))
      "raise-foreign is not a module export: no frozen pre-init binding"))


(deftest php-provenance-adoption-test
  ;; The r3 adoption pins (host side, the php twin of the py adoption audit
  ;; the step-7 review asked for): wrapper retention goes through the VM
  ;; provenance primitive's reserved doors — cell/dsg__new-provenance mints,
  ;; cell/dsg__provenance reads — declared as host dependencies of the php
  ;; module, never reimplemented in prelude code; and no minting or retention
  ;; helper is a module export (the exported-helper bypass class the ruling
  ;; names: a guest able to call the mint could forge provenance of its own
  ;; choosing).
  (is (contains? php-prelude/host-names 'cell/dsg__new-provenance)
      "the reserved mint door is a declared host dependency")
  (is (contains? php-prelude/host-names 'cell/dsg__provenance)
      "the reserved read door is a declared host dependency")
  (is (not (contains? php-prelude/module-exports 'mint-retained))
      "the mint helper is not a module export")
  ;; r4 (sol P1): the php.rt/-prefix assertion above cannot catch a
  ;; dangerous helper under a stripped php/ name — module-exports holds
  ;; STRIPPED names, so the condition-accepting receiver must be named
  ;; explicitly too.
  (is (not (contains? php-prelude/module-exports 'receive-condition))
      "the condition-accepting receiver is not a module export")
  (is (not-any? #(str/starts-with? (str %) "php.rt/") php-prelude/module-exports)
      "no php.rt name is a module export: no guest-reachable retention surface"))


(deftest php-mint-reachability-test
  ;; r4 (sol P1): no module export — no php/-prefixed definition, the only
  ;; ones core/exports takes — DIRECTLY reaches the provenance mint or the
  ;; condition-accepting receiver. One hop is the discriminating depth: a
  ;; direct mention is a guest-reachable name whose own parameter can flow
  ;; to the mint as a condition (the php/receive-condition bypass), while
  ;; deeper reachability runs through the call helpers' callback wiring,
  ;; where every condition is bridge- or validation-built, never a guest
  ;; argument. The three call helpers are whitelisted for exactly that
  ;; wiring: they mention the receiver only as the raise exit they hand to
  ;; yang.call/invoke. RED while php/receive-condition is exported (its
  ;; body references php.rt/raise-foreign and php.rt/raise-protocol).
  (let [defs (into {} @#'yang.php.antlr.prelude/core-definitions)
        exports php-prelude/module-exports
        minters '#{php.rt/mint-retained php.rt/raise-foreign
                   php.rt/raise-protocol php.rt/receive-condition}
        whitelist '#{php/call php/call-with-binding-profile php/call-named}
        exported-keys (filter #(contains? exports (php-prelude/strip %)) (keys defs))
        mentions (fn [form] (some minters (tree-seq coll? seq form)))
        offenders (remove whitelist (filter (fn [k] (mentions (get defs k))) exported-keys))]
    (is (pos? (count exported-keys)) "the audit saw the export surface")
    (is (empty? offenders)
        (str "exported definitions reach the mint surface: " (pr-str offenders)))))


(defn- resolution-refusal?
  "True when `x` is exactly the expected outcome of calling the retired
   receiver name: the engine's variable-resolution refusal naming
   php/receive-condition — never an unrelated failure."
  [x]
  (and (instance? Throwable x)
       (map? (ex-data x))
       (= 'php/receive-condition (get (ex-data x) :symbol))))


(deftest ^:slow php-exported-mint-forgery-test
  ;; r4 (sol P1), the executed bypass: while the receiver is exported, a
  ;; composed unit's boundary form can call it directly with a forged
  ;; condition — the receiver mints a GENUINE provenance-carrying
  ;; ForeignException whose retained condition is the attacker's map, and
  ;; the unchanged rethrow forwards it as if it were a real retained
  ;; condition. After the repair the receiver is php.rt/receive-condition
  ;; (compiler-private, never exported), so the forged call cannot even
  ;; link: no green run ever delivers the forged condition. The assertion
  ;; (tightened per sol's r5 P3 follow-up) accepts ONLY the expected
  ;; resolution refusal of the retired name, or an explicitly successful
  ;; observation answering :forged-forwarded false — an unrelated host
  ;; exception, nil or a malformed observation fails the pin.
  (let [observation
        (kit/guard-exceptions-form
          [:php]
          (list 'let ['probe.forged (kit/observation-map-form
                                      {:yang.call/reason :yang.call/not-callable
                                       :yang.call/data {:forged true}})
                      'probe.box (list 'cell/new nil)
                      'probe.caught (list 'php/try
                                          (list 'fn []
                                                (list 'php/receive-condition 'probe.forged))
                                          (list 'fn ['e]
                                                (list 'do (list 'cell/set! 'probe.box 'e) nil))
                                          (list 'fn [] nil))
                      'probe.wrapper (list 'cell/get 'probe.box)
                      'probe.result (boundary-call (kit/export-form :php "raise_value")
                                                   ['probe.wrapper])]
                (kit/observation-map-form
                  {:caught-a-wrapper (list 'not (list 'nil? 'probe.wrapper))
                   :forged-forwarded
                   (list 'if (list '= :raise (list 'get 'probe.result :route))
                         (list '= 'probe.forged (list 'get 'probe.result :condition))
                         false)})))
        results (run-provenance-observation observation)]
    (is (= #{:semantic :stack :register} (set (keys results))))
    (doseq [[backend actual] results]
      (println "PHP-MINT-FORGERY" backend (type actual))
      (is (or (resolution-refusal? actual)
              (and (map? actual) (false? (:forged-forwarded actual))))
          (str backend ": only the resolution refusal of the retired name, or an "
               "explicit no-forgery observation, is acceptable (got "
               (pr-str actual) ")")))))


(deftest ^:slow php-handle-shaped-foreign-payload-test
  ;; r4 (sol P2) + r5 (sol's re-check counterexamples): ordinary foreign
  ;; data shaped like a php object is FOREIGN — reception retains the
  ;; complete original condition (reason, evidence, payload identity) in
  ;; the ForeignException instead of refusing at the unauthenticated cell
  ;; read, the class lookup, or the ownership acquisition. The r5 shapes
  ;; pin the representation mismatches (MIXED TAGS — instance-of selects
  ;; by the non-nil :php/obj key; AUTHENTIC CELL WITH INVALID STATE —
  ;; authenticity proves liveness, not object state); the r6 shapes pin
  ;; the ownership consumer (addref's slot-kind selects :php/ref FIRST,
  ;; so COMPETING MARKERS on an otherwise-valid map or real handle are
  ;; consumed unchecked; and a string class alone is insufficient when
  ;; :rc is absent — addref's count acquisition consumes it); the r7
  ;; shapes pin the count domain's endpoints (a count AT the native long
  ;; maximum or minimum is invalid counted-object state, not a count php's
  ;; machinery could have produced, and is retained whole rather than
  ;; consumed). All twelve shapes run in ONE composed program (the
  ;; batch-parity task-sharing shape), not one kit run per shape: the
  ;; laptop lane's dao.jing stall reliably catches the 8th consecutive
  ;; per-shape run in one JVM.
  (let [shapes [["non-reference" {:php/obj 42}]
                ["forged-ref" {:php/obj {:type :cell-ref, :id 99999, :seal "forged"}}]
                ["forged-class" {:php/type :obj, :class 42, :props {}}]
                ["mixed-tags" {:php/type :obj, :class "Exception", :php/obj 42}]
                ["authentic-cell-bad-class"
                 ;; :rc 1 keeps the class check independently discriminating
                 ;; (the re-check3 coverage note): without a valid count the
                 ;; count rejection could mask a class-validation regression
                 (kit/observation-map-form {:php/obj (list 'cell/new {:class 42, :rc 1})})]
                ["map-mixed-ref" {:php/type :obj, :class "Exception", :php/ref 42}]
                ["real-handle-competing-ref"
                 (list 'assoc (list 'php/make-error "Exception" "real") :php/ref 42)]
                ["string-class-no-rc"
                 (kit/observation-map-form {:php/obj (list 'cell/new {:class "Exception"})})]
                ;; r7, the last P2 residual: the endpoints of php's own int
                ;; encoding are not admitted counts. integer/parse of the
                ;; native maximum is a legal guest construction, and at the
                ;; pre-fix tip the raw host (+ rc 1) the object machinery
                ;; performed threw clojure's "long overflow" on it. r10
                ;; repairs that arithmetic (integer/add, exact over every
                ;; carrier on every host) but keeps the endpoints FOREIGN:
                ;; admission stays STRICTLY inside the bounds, so every
                ;; admitted count is also inside the composition's
                ;; ::integer/max-bits, where integer/add still answers an
                ;; integer rather than the ::bit-limit keyword.
                ["max-long-count"
                 (kit/observation-map-form
                   {:php/obj (list 'cell/new
                                   (list 'assoc {:class "Exception"}
                                         :rc (list 'integer/parse "9223372036854775807")))})]
                ;; the same class one operation later: the decrement threw
                ;; at the native minimum, so the minimum count is not
                ;; admitted either (at the pre-fix tip this shape is red the
                ;; other way: admitted and delivered as a LOCAL payload)
                ["min-long-count"
                 (kit/observation-map-form
                   {:php/obj (list 'cell/new
                                   (list 'assoc {:class "Exception"}
                                         :rc (list 'integer/parse "-9223372036854775808")))})]
                ;; non-number counts stay rejected (the r6 rule; pinned so
                ;; the incrementability refinement cannot silently drop it)
                ["non-number-count"
                 (kit/observation-map-form
                   {:php/obj (list 'cell/new (list 'assoc {:class "Exception"}
                                                   :rc "bad"))})]
                ;; r10 residual 2: a computed RATIO count — data/number? and
                ;; php/int? both pass it (kind's float64 content test), but
                ;; it is not an exact-integer carrier and the integer
                ;; primitives refuse it; the count test must use the EXACT
                ;; domain predicate (integer/integer?), not a php
                ;; representation classifier.
                ["ratio-count"
                 (kit/observation-map-form
                   {:php/obj (list 'cell/new (list 'assoc {:class "Exception"}
                                                   :rc (list '/ 1 2)))})]]
        leg (fn [label payload]
              (let [n (fn [k] (symbol (str "probe." (name label) "." (name k))))
                    p (n :payload) c (n :condition) i (n :injected)
                    r (n :retained) w (n :wrapper) loc (n :local) l (n :later)]
                {:bindings [p payload
                            c (kit/observation-map-form
                                {:yang.call/reason :yang.call/thrown
                                 :yang.call/value p
                                 :yang.call/data {:complete [:evidence]}})
                            i (injected c)
                            r (boundary-call (kit/export-form :php "retain") [i])
                            w (list 'get r :value)
                            loc (list 'if (list '= :return (list 'get r :route))
                                      (boundary-call (kit/export-form :php "is_foreign") [w])
                                      {:value false})
                            ;; immediately after this shape's retain: later
                            ;; reads the $saved this retain stored, before
                            ;; the next shape's retain overwrites it
                            l (boundary-call (kit/export-form :php "later") [])]
                 :entries {(keyword (str label "-retained"))
                           (list '= :return (list 'get r :route))
                           (keyword (str label "-local"))
                           (list '= true (list 'get loc :value))
                           (keyword (str label "-condition"))
                           (list '= c (list 'get l :condition))
                           (keyword (str label "-payload"))
                           (list '= p (list 'get (list 'get l :condition)
                                            :yang.call/value))}}))
        legs (map (partial apply leg) shapes)
        observation (kit/guard-exceptions-form
                      [:php]
                      (list 'let (into [] (mapcat :bindings) legs)
                            (kit/observation-map-form (into {} (mapcat :entries) legs))))
        expected (into {} (mapcat (fn [[label _]]
                                    (map (fn [k] [(keyword (str label "-" k)) true])
                                         ["retained" "local" "condition" "payload"])))
                       shapes)
        results (run-provenance-observation observation)]
    (is (= #{:semantic :stack :register} (set (keys results))))
    (doseq [[backend actual] results]
      (println "PHP-HANDLE-SHAPED" backend actual)
      (is (= expected actual)
          (str backend ": every handle-shaped foreign payload retains the "
               "complete condition")))))


(def ^:private count-source
  "The r10 count-domain acquisition probe: an ORDINARY php catch/return —
   no numeric operation anywhere in the source — whose `return $e` runs
   the lowerer's routine php/addref acquisition on the caught value, so a
   count that survives reception must survive the next acquisition too.
   Both counts below are admitted local state: exact-integer carriers
   strictly inside php's own int encoding. MAX-1 is the count reception's
   acquisition moves ONTO the native long maximum and this source's return
   then moves past it, where the raw host + threw. 2^53 is the JS
   safe-Number boundary: a plain Long on the JVM, which is what this lane
   verifies, and a BigInt carrier on JS, where the same decimal parses past
   the safe range and a raw (+ rc 1) mixes a BigInt with a Number and
   throws a TypeError (the re-check's Node primitive diagnostic, both
   signs; the tri-host acceptance lane covers Node). The exact-integer
   domain admits both and increments both."
  (str "<?php function catch_return($f) {"
       " try { return $f(); } catch (Throwable $e) { return $e; } }"))


(deftest ^:slow php-count-acquisition-test
  ;; r10 residual 1: the count arithmetic must be safe across the WHOLE
  ;; consumed path — reception's addref, then any ordinary catch/return
  ;; acquisition. At the pre-fix tip, :rc = MAX-1 passes admission,
  ;; reception's addref increments it to MAX, and this ordinary source
  ;; (which requests no arithmetic) overflows at lower-return's addref.
  ;; Under the exact-integer domain both shapes return the caught value
  ;; with its identity preserved — rule 1 of the retention contract, a
  ;; payload valid in its own runtime keeping its ordinary catching and
  ;; identity. The endpoints themselves stay FOREIGN in the shape set
  ;; above (rule 3), retained with their complete condition, evidence and
  ;; payload identity.
  (let [shapes [["near-max" "9223372036854775806"]
                ["js-number-boundary" "9007199254740992"]]
        leg (fn [[label digits]]
              (let [n (fn [k] (symbol (str "probe." (name label) "." (name k))))
                    p (n :payload) c (n :condition) i (n :injected) r (n :result)]
                {:bindings [p (kit/observation-map-form
                                {:php/obj (list 'cell/new
                                                (list 'assoc {:class "Exception"}
                                                      :rc (list 'integer/parse digits)))})
                            c (kit/observation-map-form
                                {:yang.call/reason :yang.call/thrown
                                 :yang.call/value p})
                            i (injected c)
                            r (boundary-call (kit/export-form :php "catch_return") [i])]
                 :entries {(keyword (str label "-returned"))
                           (list '= :return (list 'get r :route))
                           (keyword (str label "-identical"))
                           (list '= p (list 'get r :value))}}))
        legs (map leg shapes)
        observation (kit/guard-exceptions-form
                      [:php]
                      (list 'let (into [] (mapcat :bindings) legs)
                            (kit/observation-map-form (into {} (mapcat :entries) legs))))
        expected (into {} (mapcat (fn [[label _]]
                                    (map (fn [k] [(keyword (str label "-" k)) true])
                                         ["returned" "identical"]))
                                  shapes))
        donor (kit/publish-donor! :python contract/boundary-source)
        results (kit/run-exception-program
                  donor (kit/compose-exception donor ["boundary_target"] :php
                                               count-source observation))]
    (is (= #{:semantic :stack :register} (set (keys results))))
    (doseq [[backend actual] results]
      (println "PHP-COUNT-ACQUISITION" backend actual)
      (is (= expected actual)
          (str backend ": every boundary count survives ordinary catch/return "
               "acquisition with identity preserved")))))


(deftest ^:slow php-nil-condition-reception-test
  ;; The steward addendum's F1 nil-guard choice, taken DELIBERATELY as
  ;; option (a): a non-map or nil condition delivered to reception is NOT
  ;; provenance-minted (nil provenance is refused by the reserved door —
  ;; an operational failure that would escape the boundary behavior); it
  ;; raises the documented plain local Error — no ForeignException, no
  ;; wrapper, nothing retained. The kit never builds such a condition;
  ;; this pins the boundary behavior for the instrument-level anomaly.
  (let [observation
        (kit/guard-exceptions-form
          [:php]
          (list 'let ['probe.injected (injected nil)
                      'probe.retained (boundary-call (kit/export-form :php "retain")
                                                     ['probe.injected])
                      'probe.wrapper (list 'get 'probe.retained :value)
                      'probe.local (list 'if
                                         (list '= :return
                                               (list 'get 'probe.retained :route))
                                         (boundary-call (kit/export-form :php "is_foreign")
                                                        ['probe.wrapper])
                                         {:value false})]
                (kit/observation-map-form
                  {:retained (list '= :return (list 'get 'probe.retained :route))
                   :plain-local-error (list '= false (list 'get 'probe.local :value))})))
        results (run-provenance-observation observation)]
    (is (= #{:semantic :stack :register} (set (keys results))))
    (doseq [[backend actual] results]
      (println "PHP-NIL-CONDITION" backend actual)
      (is (= {:retained true :plain-local-error true} actual)
          (str backend ": a nil condition raises the plain local Error, "
               "never a provenance mint (got " (pr-str actual) ")")))))
