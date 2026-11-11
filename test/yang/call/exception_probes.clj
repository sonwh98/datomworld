(ns yang.call.exception-probes
  "Boundary materialization of exception-contract/fixtures. Synthetic entries
   occur ONLY for delivery of boundary-generated conditions; source pairs
   live in exception-legs. Every receiver/rethrow body is parsed source."
  (:require [yang.call.conformance :as kit]
            [yang.call.exception-contract :as contract]))


(defn- read-export
  [frontend name]
  (kit/export-form frontend name))


(defn- call
  [callee args]
  (kit/boundary-call-form callee (kit/observation-vector-form args) []))


(def ^:private boundary-donor
  (delay (kit/publish-donor! :python contract/boundary-source)))


(defn- transfer-summary
  [result]
  {:transferred (list '= (list 'get result :route) :raise)
   :once (list '= 1 (list 'get result :transfers))
   :after (list 'get result :after)})


(defn- reason
  [result]
  (list 'get (list 'get result :condition) :yang.call/reason))


(defn- payload
  [result]
  (list 'get (list 'get result :condition) :yang.call/value))


(defn- protocol-condition
  [reason]
  (let [target (read-export :python "boundary_target")]
    (list 'get
          (case reason
            :yang.call/not-callable (call {} [])
            :yang.call/invalid-invocation
            (kit/boundary-call-form target :malformed-positional [])
            :yang.call/unsupported-binding
            (kit/boundary-call-form target [] []
                                    {:yang.call/binding-profile :contract/unsupported}))
          :condition)))


(defn- injected
  [condition]
  ;; This is a boundary delivery fixture, never counted as a real-source
  ;; directed pair. The source receiver still uses its ordinary call helper.
  (list 'cell/new
        (list 'assoc {} :yang.call/entry
              (list 'fn ['invocation]
                    (list (list 'get 'invocation :yang.call/raise) condition)))))


(defn observation-form
  "A fixture's guest-side assertions as plain booleans and qualified reasons.
   Neither live payloads nor wrappers are rendered or ferried between tasks."
  [{:keys [scenario frontend protocol-reason]}]
  (let [export #(read-export frontend %)
        result 'probe.result
        original 'probe.original
        condition 'probe.condition
        retained 'probe.retained
        wrapper 'probe.wrapper
        fresh-summary (assoc (transfer-summary result)
                             :reason (reason result)
                             :payload-identical (list '= original (payload result)))
        protocol-summary (assoc (transfer-summary result)
                                :reason (reason result)
                                :condition-identical
                                (list '= condition (list 'get result :condition)))]
    (kit/guard-exceptions-form
      [:python frontend]
      (cond
        (#{:fresh-named :fresh-closure :forged-retention} scenario)
        (list 'let [original (list 'get (call (export (if (= scenario :forged-retention)
                                                        "forged" "payload"))
                                              (if (= scenario :forged-retention)
                                                [(protocol-condition :yang.call/not-callable)] [])) :value)
                    result (case scenario
                             :fresh-named (call (export "donor_raise") [])
                             :fresh-closure
                             (call (list 'get (call (export "make_exploder") []) :value) [])
                             :forged-retention (call (export "raise_value") [original]))]
              (kit/observation-map-form fresh-summary))

        (= scenario :binding)
        (list 'let [result (call (export "needs_argument") [])]
              (kit/observation-map-form
                (assoc (transfer-summary result) :reason (reason result))))

        (#{:shape-return :shape-throw} scenario)
        (list 'let [original {:yang.call/reason :yang.call/not-callable
                              :yang.call/data {:nested [:ordinary :data]}}
                    result (call (export (if (= scenario :shape-return) "echo" "raise_value"))
                                 [original])]
              (kit/observation-map-form (if (= scenario :shape-return)
                                          {:returned (list '= (list 'get result :route) :return)
                                           :payload-identical (list '= original (list 'get result :value))}
                                          fresh-summary)))

        protocol-reason
        (list 'let [condition (protocol-condition protocol-reason)
                    'probe.injected (injected condition)]
              (if (.startsWith (name scenario) "saved-protocol-")
                (list 'let [retained (call (export "retain") ['probe.injected])
                            result (call (export "later") [])]
                      (kit/observation-map-form
                        (assoc protocol-summary
                               :retained (list '= (list 'get retained :route) :return))))
                (list 'let [result (call (export "receive") ['probe.injected])]
                      (kit/observation-map-form protocol-summary))))

        (= scenario :local-reception)
        (list 'let [original (list 'get (call (export "payload") []) :value)
                    condition (kit/observation-map-form
                                {:yang.call/reason :yang.call/thrown
                                 :yang.call/value original})
                    'probe.injected (injected condition)
                    result (call (export "retain") ['probe.injected])]
              (kit/observation-map-form
                {:returned (list '= :return (list 'get result :route))
                 :payload-identical (list '= original (list 'get result :value))}))

        (#{:foreign-reception :retained-foreign-lifetime} scenario)
        (list 'let [original (list 'cell/new {:foreign/payload :identity})
                    condition (kit/observation-map-form
                                {:yang.call/reason :yang.call/thrown
                                 :yang.call/value original
                                 :yang.call/data {:complete [:extra :evidence]}})
                    'probe.injected (injected condition)
                    retained (call (export "retain") ['probe.injected])
                    wrapper (list 'get retained :value)
                    'probe.local (list 'if (list '= :return (list 'get retained :route))
                                       (call (export "is_foreign") [wrapper])
                                       {:value false})]
              (let [local (list '= true (list 'get 'probe.local :value))
                    returned (list '= (list 'get retained :route) :return)]
                (if (= scenario :foreign-reception)
                  (kit/observation-map-form
                    {:returned returned :local-foreign-exception local})
                  (list 'let [result (call (export "later") [])]
                        (kit/observation-map-form
                          (assoc (transfer-summary result)
                                 :retained returned :local-foreign-exception local
                                 :condition-identical (list '= condition (list 'get result :condition))
                                 :payload-identical (list '= original (payload result))))))))

        :else (throw (ex-info "unknown provenance fixture" {:scenario scenario}))))))


(defn- refused-as-thrown?
  "Guest form: the boundary result refused retained-condition forwarding —
   one raise carrying a fresh :yang.call/thrown whose payload is `thrown`
   itself, never the forged condition."
  [result thrown]
  (list 'if (list '= :raise (list 'get result :route))
        (list 'if (list '= 1 (list 'get result :transfers))
              (list 'if (list '= :yang.call/thrown (reason result))
                    (list '= thrown (payload result))
                    false)
              false)
        false))


(defn forgery-observation-form
  "The architect's two R5 forgery probes against the TypeScript donor.
   :minted — admitted Clojure source mints an error-looking cell (and a
   second cell mimicking a companion-provenance layout with an
   attacker-chosen seal) and throws each through the donor's raise_value.
   :copied — the probe copies a genuine protocol wrapper's complete cell
   contents into a fresh cell and throws the copy from a new activation.
   Both must refuse: a fresh :yang.call/thrown carrying the thrown cell
   itself as payload, never the forged/original condition forwarded."
  [kind]
  (let [export #(read-export :typescript %)
        forged-condition {:yang.call/reason :yang.call/not-callable
                          :yang.call/data {:forged true}}]
    (kit/guard-exceptions-form
      [:python :typescript]
      (case kind
        :minted
        (list 'let ['forged (list 'cell/new
                                  (list 'assoc
                                        (list 'assoc
                                              (list 'assoc {} :js/type :object)
                                              :js/class :error)
                                        :yang.call/retained-condition forged-condition))
                    'minted-result (call (export "raise_value") ['forged])
                    'wannabe (list 'cell/new
                                   (list 'assoc
                                         (list 'assoc {} :js/type :object)
                                         :js/class :error))
                    'companion (list 'cell/new
                                     (list 'assoc
                                           (list 'assoc
                                                 (list 'assoc {}
                                                       :yang.call/wrapper 'wannabe)
                                                 :yang.call/seal (list 'cell/new :attacker-seal))
                                           :yang.call/condition forged-condition))
                    'armed (list 'do
                                 (list 'cell/set! 'wannabe
                                       (list 'assoc (list 'cell/get 'wannabe)
                                             :yang.call/retained-provenance 'companion))
                                 'wannabe)
                    'mimic-result (call (export "raise_value") ['armed])]
              (kit/observation-map-form
                {:minted-refused (refused-as-thrown? 'minted-result 'forged)
                 :mimic-refused (refused-as-thrown? 'mimic-result 'armed)}))
        :copied
        (list 'let ['probe.condition (protocol-condition :yang.call/not-callable)
                    'probe.injected (injected 'probe.condition)
                    'retained (call (export "retain") ['probe.injected])
                    'wrapper (list 'get 'retained :value)
                    'copy (list 'cell/new (list 'cell/get 'wrapper))
                    'result (call (export "raise_value") ['copy])]
              (kit/observation-map-form
                {:retained (list '= :return (list 'get 'retained :route))
                 :copy-refused (refused-as-thrown? 'result 'copy)}))))))


(defn- cell-ref?
  "Guest form: `x` is a cell reference (the `js/cell?` test, inlined so
   the probe needs no runtime-private name)."
  [x]
  (list '= (list 'get x :type) :cell-ref))


(defn- genuine-wrapper-bindings
  "Guest bindings shared by the identity-store probes: a genuine
   protocol wrapper obtained through `retain`, returned normally and
   therefore alive across activations, plus its original condition."
  [export]
  ['probe.condition (protocol-condition :yang.call/not-callable)
   'probe.injected (injected 'probe.condition)
   'retained (call (export "retain") ['probe.injected])
   'wrapper (list 'get 'retained :value)])


(defn identity-store-observation-form
  "The architect's round-2 R5 probes against the TypeScript donor.
   :mutated — obtain a genuine wrapper via `retain`, then perform the
   round-2 attack: overwrite the wrapper-reachable provenance path
   (companion cell contents and/or the provenance slot itself) with a
   forged condition, and rethrow the same wrapper from a new
   activation. The ORIGINAL condition must still forward.
   :extracted-seal — read whatever seal the wrapper's references expose,
   mint a NEW wrapper stamped with that seal and a companion whose
   back-reference names the new wrapper itself, and throw it. It must
   end as ordinary :yang.call/thrown carrying the minted cell.
   :store-root — no admitted path reaches the private store: the
   wrapper carries no provenance/condition/seal slot, and no global
   name holds the store or a seal."
  [kind]
  (let [export #(read-export :typescript %)
        forged-condition {:yang.call/reason :yang.call/not-callable
                          :yang.call/data {:forged true}}]
    (kit/guard-exceptions-form
      [:python :typescript]
      (case kind
        :mutated
        (list 'let (into (genuine-wrapper-bindings export)
                         ['provenance (list 'get (list 'cell/get 'wrapper)
                                            :yang.call/retained-provenance)
                          'attack (list 'do
                                        (list 'if (cell-ref? 'provenance)
                                              (list 'cell/set! 'provenance
                                                    (list 'assoc (list 'cell/get 'provenance)
                                                          :yang.call/condition forged-condition))
                                              :no-companion)
                                        (list 'cell/set! 'wrapper
                                              (list 'assoc (list 'cell/get 'wrapper)
                                                    :yang.call/retained-condition forged-condition))
                                        :attacked)
                          'result (call (export "raise_value") ['wrapper])])
              (kit/observation-map-form
                {:retained (list '= :return (list 'get 'retained :route))
                 :original-forwarded
                 (list 'if (list '= :raise (list 'get 'result :route))
                       (list 'if (list '= 1 (list 'get 'result :transfers))
                             (list '= 'probe.condition (list 'get 'result :condition))
                             false)
                       false)}))
        :extracted-seal
        (list 'let (into (genuine-wrapper-bindings export)
                         ['provenance (list 'get (list 'cell/get 'wrapper)
                                            :yang.call/retained-provenance)
                          'seal (list 'if (cell-ref? 'provenance)
                                      (list 'get (list 'cell/get 'provenance) :yang.call/seal)
                                      :no-seal-exposed)
                          'wannabe (list 'cell/new
                                         (list 'assoc
                                               (list 'assoc {} :js/type :object)
                                               :js/class :error))
                          'companion (list 'cell/new
                                           (list 'assoc
                                                 (list 'assoc
                                                       (list 'assoc {}
                                                             :yang.call/wrapper 'wannabe)
                                                       :yang.call/seal 'seal)
                                                 :yang.call/condition forged-condition))
                          'armed (list 'do
                                       (list 'cell/set! 'wannabe
                                             (list 'assoc (list 'cell/get 'wannabe)
                                                   :yang.call/retained-provenance 'companion))
                                       'wannabe)
                          'result (call (export "raise_value") ['armed])])
              (kit/observation-map-form
                {:retained (list '= :return (list 'get 'retained :route))
                 :stamped-refused (refused-as-thrown? 'result 'armed)}))
        :store-root
        (list 'let (into (genuine-wrapper-bindings export)
                         ['contents (list 'cell/get 'wrapper)])
              (kit/observation-map-form
                {:retained (list '= :return (list 'get 'retained :route))
                 :wrapper-clean
                 (list 'if (list 'nil? (list 'get 'contents :yang.call/retained-provenance))
                       (list 'if (list 'nil? (list 'get 'contents :yang.call/retained-condition))
                             (list 'nil? (list 'get 'contents :yang.call/seal))
                             false)
                       false)
                 :globals-clean
                 (list 'if (list '= {:js/str "undefined"}
                                 (list 'js/global-typeof {:js/str "retained-store"}))
                       (list 'if (list '= {:js/str "undefined"}
                                       (list 'js/global-typeof {:js/str "retained-seal"}))
                             (list '= {:js/str "undefined"}
                                   (list 'js/global-typeof {:js/str "retained-provenance"}))
                             false)
                       false)}))))))


(defn run-typescript-forgery
  "Run one forgery probe kind against the TypeScript donor on all three
   linked vector VMs; answers {backend observation}."
  [kind]
  (try
    (let [donor @boundary-donor]
      (kit/run-exception-program
        donor
        (kit/compose-exception donor ["boundary_target"] :typescript
                               (contract/sources :typescript)
                               (forgery-observation-form kind))))
    (catch Throwable e
      (kit/error-results e))))


(defn run-typescript-identity-store
  "Run one identity-store probe kind against the TypeScript donor on
   all three linked vector VMs; answers {backend observation}."
  [kind]
  (try
    (let [donor @boundary-donor]
      (kit/run-exception-program
        donor
        (kit/compose-exception donor ["boundary_target"] :typescript
                               (contract/sources :typescript)
                               (identity-store-observation-form kind))))
    (catch Throwable e
      (kit/error-results e))))


(defn run-fixture
  "One source-created receiver per task. All three linked vector VMs run.
   A fresh task prevents a deliberately broken bridge from poisoning the
   next fixture's handler context."
  [{:keys [frontend source] :as fixture}]
  (try
    (let [donor @boundary-donor]
      (kit/run-exception-program
        donor
        (kit/compose-exception donor ["boundary_target"] frontend source
                               (observation-form fixture))))
    (catch Throwable e
      (kit/error-results e))))


(defn run-frontend
  "Run one source unit per frontend and observe each fixture's separate
   public activations. Full context snapshot proof remains a frontend gate.
   `saved` is intentionally task-owned: lifetime probes
   must finish retain before entering later. `run-fixture` remains available
   for an isolated diagnostic when a frontend's broken state poisons a batch."
  [frontend]
  (let [fixtures (filter #(= frontend (:frontend %)) contract/fixtures)]
    (try
      (let [donor @boundary-donor
            observations (reduce (fn [acc fixture]
                                   (list 'assoc acc (:id fixture) (observation-form fixture)))
                                 {} fixtures)
            results (kit/run-exception-program
                      donor
                      (kit/compose-exception donor ["boundary_target"] frontend
                                             (contract/sources frontend) observations))]
        (if (some #(instance? Throwable %) (vals results))
          ;; An operational defect is not a source exception. Isolate fixtures
          ;; so one host refusal neither erases nor invents the other outcomes.
          (into {} (for [{:keys [id] :as fixture} fixtures]
                     [id (run-fixture fixture)]))
          (into {} (for [{:keys [id]} fixtures]
                     [id (update-vals results #(get % id))]))))
      (catch Throwable e
        (let [err (kit/error-results e)]
          (into {} (for [{:keys [id]} fixtures]
                     [id err])))))))
