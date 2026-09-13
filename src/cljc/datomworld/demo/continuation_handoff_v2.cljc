(ns datomworld.demo.continuation-handoff-v2
  "Handoff payload keys for Yin VM v2 continuation transfer.

   v1 handed a continuation between a register VM and a stack VM, so it needed
   one key list per engine shape. `yin.vm.v2` is the ast-walker slice only —
   `register`, `stack`, `semantic` and `space` are not ported — so there is
   one payload shape and one key list, and the two endpoints differ only in
   which VM they are.

   What the payload carries: the CESK machine (`:control`, `:env`, `:k`,
   `:store`), the scheduler (`:ready-queue`, `:wait-set`, `:parked`,
   `:id-counter`), the observable state (`:halted?`, `:blocked?`, `:value`),
   and the loaded program (`:program`). What it deliberately omits:

   - `:in-stream` and `:in-cursor`. The v2 VM owns no program medium —
     `dao.stream.v2.observer` does — and `create-vm` rejects `:in-stream`.
   - `:make-stream`, `:primitives`, `:modules`, `:bridge`, `:call-capacity`,
     `:telemetry`. Those are composition, not execution state. `handoff->vm`
     takes them from the receiving side's freshly constructed VM, which is
     what makes a payload movable: a function is not a value this demo
     pretends to serialize."
  (:require [yin.vm.v2 :as vm]
            [yin.vm.v2.ast-walker :as ast-walker]))


(def handoff-keys
  [:program :control :env :k :store :parked :id-counter :ready-queue :wait-set
   :halted? :blocked? :value])


(defn vm-key->model
  "The evaluator model a vm-key names. Both endpoints are the ast-walker; the
   keys are kept so a v2 demo reads like the v1 one it replaced."
  [_vm-key]
  :ast-walker)


(defn vm-state->handoff
  "Project a v2 VM onto the portable handoff payload."
  [vm-key vm-state]
  (when vm-state
    (assoc (select-keys vm-state handoff-keys) :vm-model (vm-key->model vm-key))))


(defn handoff->vm-state
  "Rebuild a v2 VM from a handoff payload.

   The base VM supplies the composition — `:make-stream`, `:primitives`,
   `:modules`, `:bridge`, `:call-capacity` — so the payload never has to carry
   a function. `:primitives` is not re-associated afterwards the way the v1
   version did: the ast-walker resolves variables through the store and the
   module registry value, not through a mutable primitives field on the
   payload, so overwriting the base's would discard the registry this
   composition supplied."
  [vm-key payload make-vm]
  (let [base (make-vm vm-key)]
    (merge base (vm-state->handoff vm-key payload))))
