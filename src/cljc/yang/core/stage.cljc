(ns yang.core.stage
  "The lowering stage every language shares (docs/design/yang.typescript.md
   §17, lift L2): CST packets in, program batches on port `:program`,
   diagnostics on port `:diagnostics`. A language supplies a `config`:

     :diagnostic-ns   string, the namespace of its diagnostic keys
     :program-medium  the program medium's default logical identity
     :ok?             (fn [packet]) true when the packet is lowered
     :lower           (fn [state packet]) the program tree of an ok packet
     :option-keys     the state keys an options map may carry besides
                      `:medium`"
  (:require
    [yang.stage :as stage]
    [yin.vm.encoder :as encoder]))


(defn diagnostic
  "The record of a lowering diagnostic `data` for `unit`."
  [diagnostic-ns unit data message]
  (merge {:yang.cst/unit unit, :message message}
         (select-keys data [(keyword diagnostic-ns "diagnostic") :rule
                            :construct :span :name])))


(defn lower-transform
  "The lowering stage's transform: an ok packet becomes one program batch on
   port `:program`, a source envelope (`yin.vm.encoder/source-envelope`)
   whose one member is the map AST `(:lower config)` returns, so its
   `:yang/site` marks reach the frontend-metadata side table when the
   encoder projects it. The medium is the state's `:medium` and the batch
   token is the packet's unit. A packet that is not ok, or a lowering
   diagnostic, becomes one record on port `:diagnostics` and no program.
   Stateless."
  [{:keys [diagnostic-ns program-medium ok? lower]} state packet]
  (let [unit (:yang.cst/unit packet)
        diagnostic-key (keyword diagnostic-ns "diagnostic")]
    (if (ok? packet)
      (try
        [state [[:program (encoder/source-envelope
                            (get state :medium program-medium)
                            unit
                            [(lower state packet)])]]]
        ;; ClojureDart's ex-info type is not catchable by name portably;
        ;; anything without a diagnostic in its ex-data is rethrown below
        (catch #?(:cljd Object
                  :clj clojure.lang.ExceptionInfo
                  :cljs ExceptionInfo)
               e
          (if (get (ex-data e) diagnostic-key)
            [state
             [[:diagnostics
               (diagnostic diagnostic-ns unit (ex-data e) (ex-message e))]]]
            (throw e))))
      [state
       [[:diagnostics {:yang.cst/unit unit,
                       diagnostic-key (keyword diagnostic-ns
                                               (name (:yang.cst/outcome
                                                       packet))),
                       :errors (:yang.cst/errors packet)}]]])))


(defn open-stage
  "A lowering stage reading CST packets from `cst-stream`, writing program
   batches to `program-stream`, whose logical identity is `medium`, and
   diagnostics to `diagnostics-stream`. The fourth argument may instead be
   an options map of `:medium` and the config's `:option-keys`; the fifth,
   `max-digits`, is the digit budget of every packet that declares none."
  ([config cst-stream program-stream diagnostics-stream]
   (open-stage config
               cst-stream
               program-stream
               diagnostics-stream
               (:program-medium config)))
  ([{:keys [program-medium option-keys]}
    cst-stream
    program-stream
    diagnostics-stream
    medium-or-opts]
   (stage/open cst-stream
               {:program program-stream, :diagnostics diagnostics-stream}
               (if (map? medium-or-opts)
                 (let [{:keys [medium], :or {medium program-medium}}
                       medium-or-opts]
                   (assoc (select-keys medium-or-opts option-keys)
                          :medium medium))
                 {:medium medium-or-opts})))
  ([_config cst-stream program-stream diagnostics-stream medium max-digits]
   (stage/open cst-stream
               {:program program-stream, :diagnostics diagnostics-stream}
               {:medium medium, :max-digits max-digits})))


(defn step-stage
  [config s]
  (stage/step s (partial lower-transform config)))
