(ns yang.core.call
  "Language-neutral guest calling convention (docs/design/yang.antlr.md
   §8.10). This namespace emits the same pure definitions into bundled
   preludes and the linked `yang.call` module."
  (:require [yang.core.prelude :as core]
            [yang.core.uast :as u]))


(def module-name
  "The linker module that publishes the shared calling runtime."
  'yang.call)


(def host-names
  "Host module names read by the guest definitions."
  '#{cell/get data/callable? data/contains? data/count data/map?
     data/string? data/vector?})


(def definitions
  "The one ordered definition list used by both emitters."
  '[;; Descriptor projection remains structural after callable admission.
    [yang.call/_descriptor
     (fn [callable]
       (if (data/map? callable)
         (if (= (get callable :type) :cell-ref)
           (cell/get callable)
           callable)
         nil))]
    [yang.call/entry
     (fn [callable]
       (get (yang.call/_descriptor callable) :yang.call/entry))]
    [yang.call/eligible?
     (fn [callable]
       (let [descriptor (yang.call/_descriptor callable)]
         (if (nil? (yang.call/entry callable))
           false
           (if (data/contains? descriptor :yang.call/eligible?)
             ((get descriptor :yang.call/eligible?) callable)
             true))))]
    [yang.call/invocation
     (fn [callee positional named raise]
       (assoc (assoc (assoc (assoc {} :yang.call/callee callee)
                            :yang.call/positional positional)
                     :yang.call/named named)
              :yang.call/raise raise))]
    [yang.call/invocation-with
     (fn [callee positional named raise options]
       (assoc (assoc (assoc (assoc options :yang.call/callee callee)
                            :yang.call/positional positional)
                     :yang.call/named named)
              :yang.call/raise raise))]
    [yang.call/_pair?
     (fn [pair]
       (if (data/vector? pair)
         (= (data/count pair) 2)
         false))]
    [yang.call/_named-pairs?
     (fn [pairs index]
       (if (= index (data/count pairs))
         true
         (let [pair (data/nth pairs index)]
           (if (yang.call/_pair? pair)
             (if (data/string? (data/nth pair 0))
               (yang.call/_named-pairs? pairs (+ index 1))
               false)
             false))))]
    [yang.call/valid-invocation?
     (fn [invocation]
       (if (data/map? invocation)
         (if (data/contains? invocation :yang.call/callee)
           (if (data/contains? invocation :yang.call/positional)
             (if (data/contains? invocation :yang.call/named)
               (if (data/contains? invocation :yang.call/raise)
                 (if (data/vector? (get invocation :yang.call/positional))
                   (if (data/vector? (get invocation :yang.call/named))
                     (yang.call/_named-pairs?
                       (get invocation :yang.call/named) 0)
                     false)
                   false)
                 false)
               false)
             false)
           false)
         false))]
    [yang.call/condition
     (fn [reason evidence]
       (assoc (assoc {} :yang.call/reason reason)
              :yang.call/data evidence))]
    [yang.call/thrown
     (fn [value]
       (assoc (assoc {} :yang.call/reason :yang.call/thrown)
              :yang.call/value value))]
    [yang.call/transfer
     (fn [raise condition]
       (raise condition))]
    [yang.call/invoke
     (fn [callable positional named raise]
       (if (data/callable? raise)
         (if (yang.call/eligible? callable)
           (let [target (yang.call/entry callable)
                 record (yang.call/invocation callable positional named raise)]
             (if (not (nil? target))
               (if (yang.call/valid-invocation? record)
                 (target record)
                 (yang.call/transfer
                   raise
                   (yang.call/condition :yang.call/invalid-invocation
                                        {:yang.call/callee callable})))
               (yang.call/transfer
                 raise
                 (yang.call/condition :yang.call/invalid-invocation
                                      {:yang.call/callee callable}))))
           (yang.call/transfer
             raise
             (yang.call/condition :yang.call/not-callable
                                  {:yang.call/callee callable})))
         (raise (yang.call/condition :yang.call/invalid-invocation
                                     {:yang.call/reason :missing-raise-exit}))))]
    [yang.call/invoke-with
     (fn [callable positional named raise options]
       (if (data/callable? raise)
         (if (yang.call/eligible? callable)
           (let [target (yang.call/entry callable)
                 record (yang.call/invocation-with
                          callable positional named raise options)]
             (if (not (nil? target))
               (if (yang.call/valid-invocation? record)
                 (target record)
                 (yang.call/transfer
                   raise
                   (yang.call/condition :yang.call/invalid-invocation
                                        {:yang.call/callee callable})))
               (yang.call/transfer
                 raise
                 (yang.call/condition :yang.call/invalid-invocation
                                      {:yang.call/callee callable}))))
           (yang.call/transfer
             raise
             (yang.call/condition :yang.call/not-callable
                                  {:yang.call/callee callable})))
         (raise (yang.call/condition :yang.call/invalid-invocation
                                     {:yang.call/reason :missing-raise-exit}))))]])


(def ^:private public-exports
  '#{entry eligible? invocation invocation-with valid-invocation? condition
     thrown transfer invoke invoke-with})


(def uast
  "The bundled definitions, in source order and tail marked."
  (u/mark-tails
    (reduce (fn [ast [key form]]
              (u/then ast (u/def! key (u/sexp->uast form))))
            (u/def! (ffirst definitions)
                    (u/sexp->uast (second (first definitions))))
            (rest definitions))))


(def module-uast
  "The linked module tree built from the same `definitions`."
  (let [stripped (core/module-definitions "yang.call" :yang.call/uninit
                                          [] definitions)]
    (core/module-tree stripped [])))


(def module-exports
  "Public module exports (private helper definitions stay internal)."
  public-exports)


(def ^:private module-free-names
  (core/free-name-vec module-uast))


(defn module-spec
  "The linker module spec for `yang.call` over `registry`."
  [registry]
  (core/module-spec {:module-name module-name
                     :ast module-uast
                     :exports module-exports
                     :requires {}
                     :free-names module-free-names
                     :registry registry
                     :message "the yang.call module reads an undeclared name"
                     :refusal-ns "yang.call"}))
