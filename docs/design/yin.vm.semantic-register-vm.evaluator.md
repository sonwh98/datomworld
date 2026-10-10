# yin.vm.semantic-register-vm.evaluator: the phase-4 evaluator, grounded in the engine

Status: **Companion design, phase 4 of `yin.vm.semantic-register-vm.md`
§10.** Written 2026-10-10 (Architect: claude-fable-5-1) from the frozen
design, the engine as it stands at `d21ee43f`, and the landed phase-3
code (`f46935ad`). The frozen document is not changed by this one: where
the engine's reality differs from its text, §6 records a finding with a
proposed amendment. Subordinate to [`datom.world.md`](./datom.world.md)
and to [`yin.vm.semantic-register-vm.md`](./yin.vm.semantic-register-vm.md)
("the frozen design" below). Review: a non-Claude Architect before any
implementation brief.

Every section marks its content **DECIDED** (follows from frozen text or
from code that exists) or **PROPOSED** (a design choice of this
document, open to the reviewer). File references are to the worktree at
`d21ee43f`; line numbers are those of that commit.

Contents: §1 state shapes · §2 the restore helper · §3 link and install
· §4 the walker trace test · §5 slices 4a/4b and gates · §6 findings and
proposed amendments · §7 questions for the owner · §8 portability notes.

---

## 1. Concrete in-VM state shapes

### 1.1 Where the evaluator lives

**DECIDED.** Namespace `yin.vm.semantic-register`, file
`src/cljc/yin/vm/semantic_register.cljc`, beside `yin.vm.semantic`
(frozen §10 phase 4 names it so; §10 phase 8 renames it). It requires
the three landed phase-3 namespaces under
`src/cljc/yin/vm/semantic_register/` (`code`, `linearize`, `analysis`)
and the same engine seams `yin.vm.semantic` requires
(`src/cljc/yin/vm/semantic.cljc:35-44`): `yin.vm`, `yin.vm.engine`,
`yin.vm.ffi`, `yin.vm.module`, `yin.vm.telemetry`, `yin.vm.ucf`,
`yin.vm.values`. It does **not** require `yin.vm.code` (the stack
operand table) or `yin.vm.linearize` (the stack lowering).

### 1.2 The machine state map

The engine treats a VM as a map and reads a fixed set of keys. The
table lists every key the stack-shaped `SemanticVM` record carries
(`semantic.cljc:51-80`, plus the extension keys `vm/empty-state` adds,
`src/cljc/yin/vm.cljc:2053`), and what the register evaluator does with
each.

| Key | Stack VM today (`semantic.cljc`) | Register VM | Status |
|---|---|---|---|
| `:control` | `{:segment seg :pc pc}` or nil (`:107`) | same shape, same nil-when-halted rule | reused unchanged |
| `:value` | the accumulator `val`, written back every exit (`:103`) | **not an accumulator.** The exit value only: the halt result, the parked record on `:park`, `:yin/blocked` when blocked (`engine/handle-stream-block` sets it, `engine.cljc:2329`) | extended in meaning |
| `:stack` | the operand stack `St` (`:104`) | **gone.** Never present on the record | removed |
| `:window` | — | `W`: a persistent map `{reg value}` for the current activation, body-local ids, sparse. **Always a map, never nil** (§8.4) | new |
| `:env` | `E` (`:105`) | same; carries `engine/store-of-key` as today (`engine.cljc:177-202`) | reused unchanged |
| `:k` | vector of frames or **nil when empty** (`:106`, because `engine/ready-for-ingress?` asks `(nil? (:k vm))`, `engine.cljc:280`) | same vector-or-nil rule; frames of §1.3 | reused, frame shape changed |
| `:code` | `{seg {:segment :length :code :address}}` (`:684-689`, `:805-808`) | `{seg image}`, image = §1.7 | extended |
| `:code-aliases` | `{address seg}` (`:711-724`) | same, keyed by `ucf/code-address` of the v4 vector | reused unchanged |
| `:program` | last loaded segment id (`:747`) | same | reused unchanged |
| `:halted?` `:blocked?` | engine flags | same meanings (`engine.cljc:245-261`) | reused unchanged |
| `:parked` `:wait-set` `:ready-queue` `:id-counter` | scheduler tables | same tables; entry shapes of §1.4-1.6 | reused, entry payloads changed |
| `:store` `:module-stores` `:resources` `:heap` `:gc` | engine-owned | same, untouched by the evaluator except through `engine/put-active`, `engine/handle-effect` | reused unchanged |
| `:primitives` `:primitive-profiles` `:primitive-canonical-names` `:modules` `:make-stream` `:call-capacity` `:bridge` `:owner` `:capability-secret` `:secret-source` `:attach-stream` `:origin` `:origins` `:ancestry` `:installs` `:link-retired` `:link-diagnostics` `:ffi-caller-id` `:ffi-diagnostics` `:callable-effects` | composition and engine fields | same | reused unchanged |
| `:telemetry` `:telemetry-step` `:telemetry-t` `:telemetry-eid` `:vm-model` `:vm-id` | telemetry | same; `:vm-model :semantic-register` until cutover | reused |

**DECIDED** (frozen §2.1): the configuration `⟨seg pc W E S K⟩` sits in
the record as `:control` (seg, pc), `:window` (W), `:env` (E), the
store keys (S), `:k` (K). The hot loop keeps `seg pc W E K` in `loop`
locals exactly as `vm-hot` keeps `seg pc val St E K` today
(`semantic.cljc:284-292`) and writes them back through one
`put-registers` (`semantic.cljc:90-107` is the template; its `val St`
parameters become `W`, and `:value` is written only at exits). The
derived-fact table (§8.1 of the frozen design) is read from the image
(§1.7), never recomputed in the loop.

**PROPOSED** record definition: a `defrecord RegisterVM` with the
`SemanticVM` field list minus `stack` plus `window` (`semantic.cljc:51-80`).
Extension keys (`:resources`, `:owner`, …) land in the record's
extension map through `map->RegisterVM`, as they do for `SemanticVM`
today (`semantic.cljc:937`). A record rather than a bare map keeps
`extend-type` dispatch for `vm/IVM`, `vm/IVMState` and
`module/IModuleKernel` identical to the stack VM's (`semantic.cljc:879-895`,
`:972-1058`).

### 1.3 The `ret` frame

| | Stack VM today (`semantic.cljc:237-238`) | Register VM |
|---|---|---|
| shape | `{:type :return :segment seg :pc (inc pc) :env E :stack-base (count St)}` | `{:type :return :segment seg :pc (inc pc) :env E :window saved :rd rd}` |
| `:type :return` | | reused unchanged |
| `:segment` `:pc` `:env` | | reused unchanged (`:pc` is the resume pc, already `pc+1`) |
| `:stack-base` | | removed |
| `:window` | | new: `(select-keys W (analysis/saved analysis (inc pc) rd))`, i.e. `W↾(L(pc+1) − {rd})` (frozen §2.4 saved-window rule; `analysis.cljc:92-97`) |
| `:rd` | | new: the call's destination register |

**DECIDED** (frozen §2.1, §2.4). Store context is not a frame field:
the semantic kernel carries `store-of` in `E` (`engine.cljc:183-202`),
so a frame's `:env` restores it, exactly as today.

Pop (`:return r`, frozen §2.4): `⟨frame.segment, frame.pc,
(assoc frame.window frame.rd v), frame.env, S, (pop K)⟩` with
`v = (get W r)`. Empty `K`: halted with `v`, env
`(engine/without-store-of E)` — the stack VM's `:return` arm
(`semantic.cljc:333-340`) with `(subvec St 0 base)` replaced by the
window write.

### 1.4 The `act` wait entry

An `act` entry is the VM payload plus the engine's own fields. The
engine builds the entry by calling the VM's `park-entry-fns` builder
and then adding its fields (`engine/handle-effect`, `engine.cljc:2582`,
`:2606-2617`, `:2624-2626`, `:2633-2643`; `module/require-handler`,
`module.cljc:548-549`, `:581-592`). The VM payload is the only part this
design decides.

| | Stack VM payload today (`semantic.cljc:184-201`) | Register VM payload |
|---|---|---|
| shape | `{:segment seg :pc (inc pc) :env E :stack St :k K}` | `{:segment seg :pc (inc pc) :env E :window saved :k K :deliver {:deliver :rd :rd rd}}` |
| `:segment` `:pc` `:env` `:k` | | reused unchanged |
| `:stack` | `St` with the instruction's operands popped | removed |
| `:window` | | new: `saved(pc+1, rd)` as in §1.3 |
| `:deliver` | | new: the closed delivery record (frozen §2.4) |

Engine fields added on top, unchanged from today: `:reason` (`:next`
`:put` `:observe` `:link-request` `:link-response` `:install`),
`:cursor-ref`, `:stream-id`, `:datom` (the retained put value,
`engine.cljc:2609-2611`), `:yin.k/issue` (gated put, `:2613-2616`),
`:op` (gated poll, `:2640`), the link fields `:name :link-id :envelope
:request :response :cursor` (`module.cljc:581-592`), and the FFI
reader fields `:call-id :cursor-ref :stream-id` (`ffi/response-wait-entry`,
`ffi.cljc:148-167`). The woken ready entry gains `:value :status
:resource-updates :cursor` (`engine.cljc:438-458`). **None of these
collide** with `:window`, `:deliver` or `:site`: the engine, `module`,
`ffi` and `linker` namespaces contain no reference to those three keys
(checked by grep at `d21ee43f`).

**DECIDED**: the retained value of a blocking `:stream-put` stays in the
engine's `:datom` field (frozen §2.4 "retained value … in the pending
record"); the register VM never puts it in the window.

### 1.5 The `tail` wait entry

| | Stack VM today | Register VM payload |
|---|---|---|
| shape | no counterpart: a tail effect parked as an ordinary `{segment pc+1 env stack k}` entry and the body's own `:return` delivered `val` (frozen §4.1 last paragraph) | `{:segment seg :site pc :k K :deliver {:deliver :return}}` |
| `:segment` `:k` | | reused |
| `:site` | | new: the tail call's pc, provenance only; never a resume pc |
| `:pc` `:env` `:window` | | **absent** (frozen §2.4: "a `tail` state carries no window and no `E`") |

**DECIDED** (frozen §2.4, §4.1). The engine reads no `:pc` from any
wait, ready or parked entry (`engine.cljc` has no `(:pc entry)` read;
checked by grep), so an entry without `:pc` is legal engine data. The
same engine fields as §1.4 are added on top. The FFI response reader
built from a tail entry keeps `:site :k :deliver` because
`ffi/response-wait-entry` strips only its own keys and preserves the
payload verbatim (`ffi.cljc:160-167`).

### 1.6 The `deliver` record, the `:parked` record, the reified continuation

**The delivery record** — **DECIDED** (frozen §2.4), two closed shapes,
unqualified keys in the VM, `:yin.k/`-qualified on the UCF wire (frozen
§4.2, phase 6):

```clojure
{:deliver :rd :rd r}      ; write-result
{:deliver :return}        ; through-return
```

**The `:parked` record** — `engine/park-continuation` merges
`{:type :parked-continuation :id id}` with whatever the VM hands it
(`engine.cljc:2288-2296`).

| | Stack VM today (`semantic.cljc:373-379`, `:480-484`) | Register VM |
|---|---|---|
| `:park rd` | `{:type :parked-continuation :id :parked-N :segment :pc (inc pc) :env :stack :k}` | `{:type :parked-continuation :id :parked-N :segment :pc (inc pc) :env :window saved :k :deliver {:deliver :rd :rd rd}}` — the `act` payload of §1.4 |
| FFI call bookkeeping (under the call id) | the same registers map | the `act` payload (non-tail `:ffi-call`) or, for an effectful **tail** `:call` that reaches an FFI primitive, the `tail` payload of §1.5 |

**DECIDED** (frozen §2.4 "park", §4.1 "explicit park is the no-wait
shape"). The `:park` instruction raises no wait entry; the task halts
with the record as `:value` (`engine.cljc:2293-2295`). Note `:ffi-call`
is itself an instruction, so its park is always an `act`; only a
primitive that *returns* an FFI-shaped effect through a tail `:call`
could park a `tail` record, and no such primitive exists today
(`ffi-call` is the only FFI path, `semantic.cljc:465-518`). The
`tail`-shaped FFI parked record is specified for closure of the shape
set, not because 4b must exercise it.

**The reified continuation** — a `values/continuation` host type
(`src/cljc/yin/vm/values.cljc:140-144`) over a payload map.

| | Stack VM today (`semantic.cljc:363-371`) | Register VM |
|---|---|---|
| payload | `{:type :reified-continuation :segment seg :pc (inc pc) :env E :stack St :k K}` | `{:type :reified-continuation :segment seg :pc (inc pc) :env E :window saved :k K :deliver {:deliver :rd :rd rd}}` |
| owner | `(:owner vm)` | same (`values/continuation (:owner vm) payload`) |
| invocation (`apply-call` `:continuation` arm, `:258-263`) | `⟨seg', pc', v, stack, env, k⟩` with `v` in `val` | `⟨seg', pc', (assoc window rd v), env, k⟩` per the recorded `:deliver`; the caller's `W E K` are discarded |
| arity and ownership | `engine/continuation-argument` (`engine.cljc:103-112`), `engine/operator-kind` `:foreign-value` (`:77-90`) | reused unchanged |

**DECIDED** (frozen §2.4 "current-continuation", "`f = κ`"). `:deliver`
on a `κ` is always `{:deliver :rd}`; it is carried so that invocation is
one code path with §2's `:rd` arm and so that phase 6 lifts it without
a special case.

### 1.7 The loaded image

| | Stack VM today (`semantic.cljc:805-808`) | Register VM |
|---|---|---|
| shape | `{:segment seg :length n :code [[opcode-int …] …] :address A}` | `{:segment seg :length n :vector v :address A :bodies [{:start :end :owner :registers} …] :analysis a}` |
| `:segment` | local id minted at `(dec (vm/loaded-code-floor (:code vm)))` or the alias column's id (`:800-804`) | same rule; needs `:length` for the floor (`vm.cljc:1037-1050`) |
| `:code` | integer-opcode tuples (`decode-tuple`, `:758-772`) | **PROPOSED**: none. The loop dispatches `case` on the mnemonic keyword of `(nth v pc)`; the phase-3 validator already dispatches so on all three hosts (`code.cljc:368-398`). An integer decode is a later, A-preserving optimisation |
| `:vector` `:address` `:bodies` | — | exactly what `code/load-vector` returns (`code.cljc:864-879`), `:end` exclusive |
| `:analysis` | — | `(analysis/analyze v)` (`analysis.cljc:70-83`), computed **once per admitted image**, engine-local derived data (frozen §10 phase 3). Never enters A |

**DECIDED**: the loader is `yin.vm.semantic-register/load-vector [vm v
stamp opts]` = `code/load-vector` (stamp `"v4"`, rules 1-13) → wrap as
above → `store-image`/`store-alias` logic copied from
`semantic.cljc:692-724` (identical-reload acceptance, live-id conflict,
one address one id) → `:control {:segment seg :pc 0}`, `:window {}`,
`:k nil`, `:halted? false`, `:blocked? false`, `:value nil`.
`attach-image` is the same without the control reset
(`semantic.cljc:821-846` is the template). An AST reaches the evaluator
only through the phase-3 projection: **PROPOSED** helper
`yin.vm.semantic-register/ast-loader` mirroring
`yin.vm.linearize/ast-loader` (`src/cljc/yin/vm/linearize.cljc:446-471`):
check `vm/ast-contract`, `(:vector (linearize/project-datoms datoms))`,
then `load-vector` under `code/contract`.

### 1.8 The transitions, as code paths

Each row names the stack-VM arm that is the template and what changes.
**DECIDED** by frozen §2.4 unless marked.

| Instruction | Template (`semantic.cljc`) | Register transition |
|---|---|---|
| `[:const rd v]` | `:299` | `W[rd ← v]`, pc+1 |
| `[:var rd name]` | `:301-308` | `W[rd ← (engine/resolve-var E (engine/active-store vm (engine/env-store-of E)) prims modules name)]` |
| `[:closure rd params body-pc]` | `:309-318` | `W[rd ← (values/closure owner {:type :closure :params (vm/check-params! params) :entry body-pc :segment seg :env E})]`. Payload keys unchanged from today, so `lift-closure`/`lower-closure` (`:999-1051`) port by replacing the opcode test `(= 4 (nth inst 0))` with `(= :closure (nth t 0))` and reading params/body at tuple positions 2 and 3 |
| `[:jump t]` | `:323` | pc ← t |
| `[:branch-false c t]` | `:325-326` | pc ← `(if (get W c) (inc pc) t)` |
| `[:define rd name rs]` | `:356-361` | `(engine/put-active vm (engine/env-store-of E) name (get W rs))`, then `W[rd ← (get W rs)]`. `put-active` routes to the module store and calls `engine/store-put`, which refuses the reserved key (`engine.cljc:152-161`, `:216-224`) |
| `[:gensym rd prefix]` | `:342-344` | `[id vm'] = (engine/gensym vm prefix)`, `W[rd ← id]` |
| `[:store-get rd key]` | `:347-350` | `W[rd ← (get (engine/active-store vm (env-store-of E)) key)]` |
| `[:store-put rd key value]` | `:352-355` | `put-active` then `W[rd ← value]` |
| `[:halt r]` | `:330-331` | `put-registers vm nil nil {} (engine/without-store-of E) nil` with `:value (get W r)` |
| `[:return r]` | `:333-340` | §1.3 pop; empty K halts as `:halt` with `v = (get W r)` |
| `[:call rd f args tail?]` | `apply-call`, `:224-263` | §1.9 |
| `[:stream-make rd buffer]` `[:stream-cursor rd s]` `[:stream-close rd s]` | `:423-430`, `:442-448`, `:458-464` | `run-effect` with the effect's operands read from `W`; no park builders; `W[rd ← value]` |
| `[:stream-put rd s v]` | `:432-440` | `run-effect` with `{:stream (get W s) :val (get W v)}` and the `act` builders; on `:continue` `W[rd ← value]` |
| `[:stream-next rd c]` | `:450-456` | likewise with `{:cursor (get W c)}` |
| `[:ffi-call rd op args]` | `:466-518` | §1.10 |
| `[:current-continuation rd]` | `:363-371` | `W[rd ← κ]`, §1.6; `saved` computed at `(inc pc)` with destination `rd` |
| `[:park rd]` | `:373-379` | `(engine/park-continuation (put-registers …) act-payload)`, then `:control nil :k nil` |
| `[:resume id v]` | `:381-397` | `(engine/resume-continuation (put-registers …) id (get W v) register-restore)`; the loop re-enters from the returned state's `:control :window :env :k` exactly as `:394-397` re-enters from `:control :stack :env :k`. A restored `:return`-mode record may **halt** here (§2.3 step 6); the loop must test `(:halted? vm')` before re-entering, which the stack arm never had to |

### 1.9 `apply-call` in register form

**DECIDED** (frozen §2.4 "call"). Template: `semantic.cljc:224-263`.
Inputs: `f = (get W fn-reg)`, `args = (mapv #(get W %) arg-regs)`
(`mapv`, not `for`: §8.4).

| `operator-kind` | `tail?` | Transition |
|---|---|---|
| `:closure` | false | `E' = (merge (:env c) (engine/bind-params (:params c) args))`; `K' = (conj K {:type :return :segment seg :pc (inc pc) :env E :window (select-keys W (analysis/saved a (inc pc) rd)) :rd rd})`; `⟨(:segment c), (:entry c), {}, E', K'⟩` |
| `:closure` | true | same with `K' = K`; the caller's `W` is dropped |
| `:host-fn`, value `v` | false | `W[rd ← v]`, pc+1 |
| `:host-fn`, value `v` | true | `return(v)` against `K` inline (§1.3 pop, or halt) — the de Bruijn register kernel does exactly this today (`src/cljc/yin/vm/debruijn/register.cljc:711-713`) |
| `:host-fn`, effect `ε` | false | `engine/check-callee-effect!` then `engine/handle-effect` with `{:park-entry-fns (act-builders seg pc E W K rd)}`; `:blocked?` → stop with `:control nil :k nil`; else `W[rd ← value]`, pc+1 |
| `:host-fn`, effect `ε` | true | as above with `(tail-builders seg pc K)`; not blocked → `return(value)` inline (`register.cljc:565-579` is the live precedent) |
| `:continuation` | any | `v = (engine/continuation-argument args)`; payload `c`; `⟨(:segment c), (:pc c), (assoc (:window c) (:rd (:deliver c)) v), (:env c), (:k c)⟩` |
| anything else | | `engine/operator-kind` throws `:not-applicable` / `:foreign-value` (`engine.cljc:77-90`) |

The builders (template `call-park-entries`, `semantic.cljc:184-201`):

```clojure
(defn- act-builders [seg pc E W K rd analysis]
  (let [payload {:segment seg :pc (inc pc) :env E
                 :window (select-keys W (analysis/saved analysis (inc pc) rd))
                 :k K :deliver {:deliver :rd :rd rd}}]
    {:stream/put      (fn [_ _ r] (assoc payload :reason :put :stream-id (:stream-id r)))
     :stream/next     (fn [_ _ r] (assoc payload :reason :next
                                         :cursor-ref (:cursor-ref r) :stream-id (:stream-id r)))
     :module/require  (fn [_ _ _] payload)}))

(defn- tail-builders [seg pc K]
  (let [payload {:segment seg :site pc :k K :deliver {:deliver :return}}]
    …same three keys over this payload…))
```

`:stream/poll` needs no builder of its own: `handle-effect` falls back
to the `:stream/next` builder (`engine.cljc:2633-2634`). The `:module/require`
builder is handed `nil` as its result argument by `require-handler`
(`module.cljc:549`), hence the ignored third parameter.

### 1.10 `:ffi-call` in register form

**DECIDED** (frozen §2.4 "ffi-call"). Template `semantic.cljc:466-518`.
`args = (mapv #(get W %) arg-regs)`; `ffi/require-call-pair!` before
any park; `vm' = (put-registers (engine/pin-refs vm args) seg pc W E K)`;
`call-id = (ffi/call-id vm' (engine/park-id vm'))`; the `act` payload of
§1.4 with `(inc pc)` and `rd`; `(engine/park-continuation vm' payload
call-id)`; `(ffi/put-request vm' call-in (apply2/request call-id op args))`:

- `:dao.stream/ok` → `(update :wait-set conj (ffi/response-wait-entry payload call-id))` — **PROPOSED**: use `ffi/response-wait-entry` (`ffi.cljc:148-167`), which preserves the payload verbatim, instead of a hand-written `response-wait-entry` as `semantic.cljc:110-119` has; then `:control nil :k nil :value :yin/blocked :blocked? true :halted? false`.
- `:dao.stream/full` → the retained writer `(assoc payload :request-sent true :call-id call-id :op op :reason :put :stream-id vm/call-in-stream-key :datom request)`, same blocked flags.
- otherwise throw "FFI request could not be appended" as today.

---

## 2. The semantic restore helper

### 2.1 The seam, verified against the engine

**DECIDED, with a finding (§6 F1).** The engine calls a VM's restore
function from exactly two places, both with the signature
`(restore-fn base entry val)`:

- `engine/resume-from-run-queue` (`engine.cljc:2224-2256`): pops the
  first ready entry, merges `:resource-updates` into `:resources`, sets
  `:blocked? false :halted? false` on `base`, runs
  `terminal-resume-outcome` (`:2177-2196`) and **throws before any
  restore** for a waitset diagnostic, a `:link-refused` status, an FFI
  response-loss status, or a declared stream error outcome
  (`throw-terminal-resume!`, `:2199-2221`); then
  `(restore-fn base entry (:value entry))`.
- `engine/resume-continuation` (`engine.cljc:2299-2309`): gate-refuses
  `:exporting`/`:ended`, dissocs the parked id, then
  `(restore-fn new-state parked resume-val)`.

`engine/handle-effect` (`engine.cljc:2565-2683`) never calls a restore
function. The `:restore-fn` the walker passes in `opts`
(`ast_walker.cljc:189`) is forwarded only to module effect handlers
(`:2674`), and `require-handler` reads only `:park-entry-fns`
(`module.cljc:546-549`). The stack VM passes no `:restore-fn` at all
(`semantic.cljc:204-221`). So the frozen design's sentence "the engine
seam already supports this: `handle-effect`'s `restore-fn(base, entry,
value)` …" names the wrong function; the claim it makes is true of the
two functions above.

**No current activation is required**, verified: at both call sites
`base` is the blocked or halted machine. A blocked stack machine has
`:control nil :k nil` (`semantic.cljc:219`, `:493-497`, `:509-513`), and a
restore is free to leave `:control nil :k nil :halted? true` — which
`engine/run-loop` (`engine.cljc:2149-2163`) handles: `active?` is false,
`:blocked?` is false, so it either pops the next ready entry or exits
through the `:else` arm with the `:halt` snapshot. The de Bruijn
register kernel already restores a `:return-result` entry by running its
`return-transition` with no activation and halting on an empty
continuation (`register.cljc:457-487`, `:403-432`); this evaluator does
the same thing with the `tail` state. Nothing synthesizes a trampoline
activation.

### 2.2 `register-restore [base entry val]`, the algorithm

**DECIDED.** Public (as `semantic-restore` is, and because tests reflect
on it; §8.6). Template `semantic-restore` (`semantic.cljc:135-181`) and
`register-restore` of the de Bruijn kernel (`register.cljc:490-518`).

```
1. if (:request-sent entry)                       ; FFI retained request, now appended
     wait := (ffi/response-wait-entry entry (:call-id entry))
     if entry carries :response-cursor and :response-stream           ; a lowered retained
        wait := (assoc wait :cursor-ref {:type :cursor-ref :id response-cursor}   ; writer's route,
                            :stream-id response-stream)               ; semantic.cljc:122-132
     return (assoc base :wait-set (conj (vec (:wait-set base)) wait)
                        :control nil :k nil :value :yin/blocked
                        :blocked? true :halted? false)
     ;; delivers nothing to guest code (frozen §2.5 request-sent row)

2. call-id := (:call-id entry)
   if call-id: base := (update base :parked dissoc call-id)           ; bookkeeping removal
               val  := (ffi/call-result val call-id)                  ; may THROW: error response,
                                                                       ; malformed, miscorrelated
   ;; order matters: the parked entry is removed BEFORE call-result can throw, so a failed
   ;; call leaves no stranded record (semantic.cljc:172-174; ffi.cljc:170-187)

3. case (:deliver (:deliver entry))
   :rd     → rd := (:rd (:deliver entry))
             W' := (assoc (:window entry) rd val)                      ; exactly one write
             return (put-registers base (:segment entry) (:pc entry) W' (:env entry) (:k entry))
             ;; :control {:segment :pc}, :halted? false; the loop resumes at :pc

   :return → K := (:k entry)
             if (seq K):
                 frame := (peek K)
                 W' := (assoc (:window frame) (:rd frame) val)         ; exactly one write
                 return (put-registers base (:segment frame) (:pc frame) W' (:env frame) (pop K))
             else:                                                      ; the empty-K halt
                 return (put-registers base nil nil {} (engine/without-store-of (:env base)) nil)
                        with :value val                                 ; :halted? true

   else    → throw (ex-info "Entry carries no delivery record" {:rule :deliver :entry …})
```

`put-registers` here is the register VM's: `:control (when seg {:segment
seg :pc pc})`, `:window W`, `:env E`, `:k (if (seq K) K nil)`,
`:halted? (and (not (:blocked? vm)) (nil? seg))`, and `:value` only
when the caller supplies one (halt).

**The empty-K halt's environment** (§6 F2): a `tail` entry has no `E`.
The halted machine's `:env` is `(engine/without-store-of (:env base))`,
where `(:env base)` is what `put-registers` wrote before the effect ran:
the tail site's `E` (the stack VM's `:halt` arm leaves the same thing,
`semantic.cljc:330-331`). Under `vm-eval` the engine then restores the
initial env anyway (`engine/restore-initial-env`, `engine.cljc:251-255`).

### 2.3 Per pending reason

How each reason reaches step 3, and what `val` is. **DECIDED**: every
row is engine behaviour today; the evaluator adds only the two delivery
arms.

| In-VM reason | Who wakes it | `val` at step 3 | Terminal cases (thrown before restore) |
|---|---|---|---|
| `:next` (blocked read) | `check-wait-set` sweep (`engine.cljc:2069-2143`) → `make-woken-run-queue-entries` (`:420-458`) | the read value; `nil` on `end`; `:dao.stream/gap` on gap; a shaped refusal map for an out-of-contract outcome | `cursor-mismatch` `invalid-cursor` `transport-error` (`wake-error-outcomes`, `:384-394`) |
| `:put` (blocked write) | same sweep; or `apply-put` under a gate (`:1912-1935`) | the retained `:datom` (the written value) | `closed` `invalid-value` `transport-error` |
| `:observe` (gated poll) | `apply-observation` (`:710-736`) | the poll's answer, `:dao.stream/blocked` when still blocked | none |
| `:next` + `:call-id` (FFI sent; UCF reason `:ffi`) | `poll-ffi-responses` router (`:1848-1876`) by call id | the response envelope → step 2 unwraps with `ffi/call-result` | `::ffi/response-ended` `::ffi/response-gap` (`ffi/response-loss-statuses`); `ffi/call-result` throws on `error` |
| `:put` + `:request-sent` (FFI retained; UCF `:ffi-request`) | sweep retries the `:datom`; or `apply-ffi-sent`/`apply-ffi-outcome` | the appended request | the put error outcomes; step 1 then re-parks as a reader and delivers nothing |
| `:link-request` | `poll-link-entry` → `module/append-link-request` (`module.cljc:501-520`) flips the reason to `:link-response` in place | never restored from this state | append terminal outcomes throw inside the poll |
| `:link-response` | `poll-link-response` → `settle` (`:1459-1501`) | **(a)** already linked: `(ready entry module-name)` → `val` = the module symbol; **(b)** installing: entry becomes `:install` (no restore); **(c)** refusal / `:module-name-mismatch` / failed discharge: `refused-entry` | `:link-refused` status → `throw-terminal-resume!` ("Module link refused: …") |
| `:install` | `wake-installed` (`:1306-1318`) at `linked` or `refused` | the module symbol | `:link-refused` on `refused`, `:lost` on `abandon-installs` |
| (none) `:park` record | `engine/resume-continuation` from `:resume` | the resume value `(get W v)` | gate refusal `:exporting`/`:ended` |

For an **`act`** entry every row ends in the `:rd` arm; for a **`tail`**
entry every row ends in the `:return` arm. There is no reason-specific
code in the helper beyond steps 1-2, which is why one helper serves
both shapes.

### 2.4 Immediate (non-parking) effect completion

A primitive effect that does not park returns `{:state :value
:blocked? false}` from `handle-effect`; the call arm delivers inline:
non-tail → `W[rd ← value]`, pc+1; tail → `return(value)` against `K`
(§1.9). A `:vm/store-put` effect, `:stream/make`, `:stream/cursor`,
`:stream/close`, `:cell/*` and a `:module/require` hit all complete this
way. **DECIDED** (frozen §2.4; precedent `register.cljc:573-579`).

### 2.5 Exactly-once

Exactly one write of `val` happens, in step 3, into either the entry's
own window (`:rd`) or the popped frame's window (`:return`), or into
`:value` on halt. The entry itself leaves its table before the helper
runs: `resume-from-run-queue` pops the ready queue (`:2249-2250`),
`resume-continuation` dissocs `:parked` (`:2305`), step 2 dissocs the
FFI bookkeeping. A `tail` completion therefore "never writes a register
of a body that no longer runs" (frozen §2.4): no window of the tail
site exists to write.

---

## 3. Link and install interplay

**DECIDED**: everything in this section is engine or linker behaviour
at `d21ee43f`; the evaluator contributes only (i) the payload the
`:module/require` builder returns and (ii) the `module/IModuleKernel`
implementation. The three link phases are those of `yin.vm.linker.md`
§7.2 steps 3-7; the install child is §7.3.

### 3.1 Which transitions are whose

| Step | Owner | Code | Evaluator's part |
|---|---|---|---|
| `(require 'foo)` is a `:call` of the primitive `require`, which returns `{:effect :module/require}` (`vm.cljc:410-415`) | evaluator | `apply-call` `:host-fn` effect arm (§1.9) | passes `act-builders` (non-tail) or `tail-builders` (tail) |
| registry hit → answers now | engine | `require-handler` cond 1 (`module.cljc:553-554`) | inline completion (§2.4) |
| `:require-cycle` | engine | cond 2 (`:556-560`) throws | none |
| module installing → join as `:install` waiter | engine | cond 3 (`:562-563`): `(assoc payload :reason :install :name m)` | the payload |
| miss → mint newest cursor, `:link-request` entry, append | engine | `:570-593`; `block-on` (`:480-489`) | the payload |
| `:link-request` → `:link-response` | engine | `poll-link-entry` (`engine.cljc:1552-1568`), `append-link-request` dissocs `:envelope`, flips `:reason` | none; payload keys untouched |
| `:link-response` → settle | engine | `link-read-step` (`:1504-1530`), `settle` (`:1459-1501`) | none |
| discharge 5b | engine + linker | `discharge-defect` (`:1193-1230`), `linker/discharge` | none |
| spawn the child | engine + kernel | `start-install` → `spawn-child` (`:1271-1303`) → `module/spawn-module` | **kernel**: a fresh register VM over the verified vector (§3.3) |
| step the child | engine | `advance-install` (`:1404-1437`) runs `vm/run` on the child each round | the child's own `run` |
| `validated` → `linked` | engine + kernel | `link-install` (`:1342-1401`): `lift-slice` → `module/lift-closure`; `verified-images` → `module/image-holds?`; `receive-module` → `module/attach-module`, `module/lower-closure` | **kernel** methods |
| wake waiters | engine | `wake-installed` → `(ready e module-name)` | restore delivers via §2 |
| `refused` | engine | `refuse-install` → `refused-entry` → thrown at restore | none |

### 3.2 Where the delivery record is held while the child runs

In the requiring task's **`:install` wait entry**: the act or tail
payload (with its `:deliver`), plus `:reason :install :name 'foo`.
`settle` builds it as `(-> entry (dissoc :envelope :request :response
:cursor :link-id) (assoc :reason :install))` (`engine.cljc:1469-1472`),
so `:segment :pc :env :window :k :deliver` (act) or `:segment :site :k
:deliver` (tail) survive untouched. The child never sees it: the child
is a separate VM value under `(:installs state)` with its own tables.
A second requirer that arrives while the child runs is a second
`:install` entry with its own payload (cond 3 above); `wake-installed`
wakes every entry naming the module (`:1310-1312`), each delivering into
its own window or through its own `K`.

### 3.3 The kernel protocol for the register evaluator

**DECIDED** by `module/IModuleKernel` (`module.cljc:395-461`) and the
stack VM's implementation (`semantic.cljc:972-1058`), ported:

| Method | Register VM |
|---|---|
| `link-format` | `{:format <interim keyword, §6 F6> :contract code/contract}` ("v4") |
| `spawn-module [vm image opts]` | `(load-vector (create-vm {…the same composition keys as semantic.cljc:978-993…}) image code/contract)` |
| `image-identity [_ image]` | `(ucf/code-address image)` = `jing/segment-key` |
| `image-holds? [_ image segment]` | `(jing/segment-matches? segment image)` |
| `attach-module [vm image]` | `(attach-image vm image code/contract)` |
| `lift-closure [vm closure encode]` | as `semantic.cljc:999-1018` with `:yin.k/format` = the interim keyword; `:yin.k/entry (:entry closure)`, `:yin.k/params`, `:yin.k/env` encoded without store-of, `:yin.k/store-of` when present |
| `lower-closure [vm marker decode]` | as `semantic.cljc:1019-1051`; the attached-image check becomes: some tuple `t` of `(:vector image)` with `(= :closure (nth t 0))`, `(= entry (nth t 3))`, `(= params (nth t 2))` |
| `gc-roots [vm]` | `{:kernel [(:control vm) (:k vm)] :values [(:env vm) (:window vm) (:value vm)]}` |
| `gc-children [_ _x]` | `nil` (plain data, as the stack VM; parameter spelled `_x`, §8.5) |

The install path consults no linker format record: `module/link-module`
(`module.cljc:77-97`) stores manifest, address, derivation and the
lifted slice; `verified-images` uses `image-holds?`. A **linker-side**
format record for the register vector (scanners over the new table,
`linker/semantic-format`'s shape at `linker.cljc:692-712`) is the
frozen design's **phase 5** and is not needed to pass the 4b install
gate with the stub responder of `linker_require_test.cljc:226-254`,
which supplies `:obligations []` (`:257-267`).

### 3.4 What `:resume`-ing a parked child means over windows

Nothing new. A child that blocked (phase `:parked`, `engine.cljc:1427-1429`)
is a whole register VM whose `:wait-set` holds act/tail entries of its
own. On the next parent round `advance-install` calls `vm/run` on it;
the child's `run` is `engine/run-loop` with **the child's**
`register-restore`, which polls the child's wait set and delivers into
the child's windows (§2). The parent's windows, `K` and `:install`
waiter are not touched; no window crosses between tasks. A child whose
module body executes a `:park` instruction halts with a parked record
as its value: `advance-install` sees `(:halted? child)` and proceeds to
`link-install`, where every export must be a store key — an export left
unbound by the park is `:export-missing` (`:1352-1361`). That is the
behaviour today for every backend and is unchanged.

Module store context travels in `E` (`engine/store-of-key`) across
calls, frames, windows and captures as today; `active-store`/`put-active`
read it from `E` (`engine.cljc:205-224`). The 4b row
"input after a tail-applied module closure is the task's own"
(`linker_require_test.cljc:944`) holds because the empty-K halt strips
`store-of` (§2.2 step 3 and the `:halt`/`:return` arms).

---

## 4. The walker trace-test procedure

**PROPOSED** (frozen §2.5 fixes the correspondence table; this section
fixes the procedure, which the frozen text leaves as "a trace test
aligns walker value-producing steps with register writes").

### 4.1 What a "value-producing step" is on each side

**Walker.** Drive `vm/step` on an `ASTWalkerVM` (`ast_walker.cljc:1066-1069`
→ `cesk-transition`, `:298-613`). Inspect every post-state `S'`.

> A walker step is **value-producing** iff `(:control S')` is nil and
> `(:blocked? S')` is false. Its value is `(:value S')`.

Justification from the transition table: every administrative step
leaves `:control` non-nil (an `:application` sets it to the operator,
`:if` to the test, `eval-operator`/`eval-operand` to the next operand,
`eval-test` to the chosen arm, a closure application to the body,
`:vm/resume` and the four stream nodes to their operand,
`eval-stream-put-target` to the value node, a definition to its value
operand: `:494-612`, `:306-398`). Every step that yields a value sets
`:control` nil with that value: `:literal :variable :lambda :vm/gensym
:vm/store-get :vm/store-put :vm/current-continuation :stream/make`, a
host-function application (`handle-primitive-result`, `:179-210`), the
effect completions (`eval-stream-put-val` …`eval-stream-next-cursor`,
`:399-457`), `eval-define` (`:458-466`), `eval-call` (`:379-388`),
`eval-resume-val` through `resume-continuation` (`:467-475`), and an
abortive continuation invoke (`apply-function`, `:289-295`). A blocked
step sets `:control` nil **and** `:blocked? true` and is excluded; a
`:vm/park` sets `:control` nil with `:halted? true` and the parked
record as value, and is treated as a terminal event (§4.3).

**Register machine.** Drive `vm/step` with fuel 1. Inspect each
transition.

> A register step is **value-producing** iff it writes a register with
> a value that was not already in a register of the machine, or
> delivers a fresh value through `return(v)`. Concretely, the writes
> of: `:const :var :closure :gensym :store-get :store-put :stream-make
> :stream-cursor :stream-close :current-continuation`; `:define`'s
> write of `rd`; a `:call` whose operator is a host function
> returning a value (non-tail: `rd`; tail: the popped frame's `rd`, or
> the halt result when `K` is empty); a `:stream-put`/`:stream-next`
> that completes without parking; the delivery of a woken entry
> (§2.2 step 3); a continuation invoke's write of the captured `rd`.
> **Not** value-producing: `:jump`, `:branch-false`, a `:call` entering
> a closure (tail or not), `:return r` (it relocates `(get W r)`, a value
> already produced by the body), `:halt r` (likewise), `:park`, and any
> step that parks or blocks.

Why `:return r` is administrative: the walker has no return step. A
body's last expression produces its value once and the frame chain
(`:next k`) carries it; the register machine produces it once at the
body's write and `:return` moves it into the caller's `rd`. Counting
`:return` would misalign every closure call by one event. The same
value does appear twice for `:define` on **both** sides (the value
operand's step, then `eval-define`/`:define`), so `:define`'s write
counts. (§6 F5 proposes adding this row to the frozen §2.5 table.)

### 4.2 The recorded trace

Each side records a vector of **events**:

```clojure
[:value v-normalized]      ; one per value-producing step, in order
[:halt v-normalized]       ; the final value, once, when halted with an empty ready queue
[:park record-normalized]  ; a :vm/park / [:park rd] halt: the record's :env and :k are dropped,
                           ; its :id kept
[:error message]           ; the ex-message of a throw, then the trace ends
```

Normalisation is `yin.vm.parity-test/normalize` (`test/yin/vm/parity_test.cljc:122-134`)
extended: a closure → `{:type :closure :params p}` (the body is an AST
node on one side and `{:segment :entry}` on the other, so only `params`
compare); a continuation → `:continuation`; a `:stream-ref`/`:cursor-ref`
→ `{:type t :id id}`; a host fn → `:host-fn`; a parked record →
`{:type :parked-continuation :id id}`; everything else as is.

### 4.3 The algorithm

```
trace-walker(ast):
  vm := (walker/vm-load-rows (tu/create-vm) (vm/ast->semantic-bytecode ast) vm/ast-contract)
  events := []
  loop:
    if (engine/halted-with-empty-queue? vm): events += [:halt (normalize (vm/value vm))]; stop
    if (:blocked? vm): stop                      ; the trace lane runs non-blocking programs only
    vm' := try (vm/step vm) catch e: events += [:error (ex-message e)]; stop
    if (and (nil? (:control vm')) (not (:blocked? vm')))
       if (:value vm') is a parked record: events += [:park …]; stop
       else events += [:value (normalize (:value vm'))]
    vm := vm'
  ;; the walker's final value-producing step sets :control nil, :k nil and :halted? true in
  ;; one transition (cesk-return derives :halted? from the two nils), so it is recorded as
  ;; [:value v] here and the next iteration records [:halt v]

trace-register(ast):
  vm := (sr/load-vector (sr/create-vm opts) (:vector (sr-linearize/project ast)) code/contract)
  events := []
  loop:
    if (engine/halted-with-empty-queue? vm): events += [:halt …]; stop
    if (:blocked? vm): stop
    inst := (nth (:vector image) pc) of (:control vm)
    vm' := try (vm/step vm) catch e: events += [:error …]; stop
    if value-producing(inst, vm, vm') per §4.1: events += [:value (normalize (written value))]
    if (:halted? vm') and parked: events += [:park …]; stop
    vm := vm'
```

Detecting the written value on the register side without reading the
loop's locals: compare `(:window vm')` with `(:window vm)` when
`(:control vm')` is in the same body and `K` did not shrink (the write
is the one new or changed key); when `K` shrank by one (a `:return`, or
a tail primitive's `return(v)`), the write is `(get (:window vm') (:rd
frame))` for the popped frame and counts only in the tail-primitive
case; when the machine halted, the value is `(:value vm')` and counts
only when the halting instruction was a tail `:call` of a host function
(the `:halt r` / `:return r`-with-empty-K cases are administrative).
The classification therefore needs `inst` (read before the step) and
`(:k vm)`/`(:k vm')`, both available from `vm/IVMState`.

**The final walker value.** The walker's last value-producing step
leaves `:control nil :k nil` and `:halted? true` in one transition, so
the loop above records it as `[:value …]` and then `[:halt …]`; the
register side records the body's last write as `[:value …]` and the
`:halt` as `[:halt …]`. Both traces end `[:value v] [:halt v]`.

### 4.4 Mismatch

The test asserts `(= (trace-walker ast) (trace-register ast))` per
program and reports the first differing index with both events. A
mismatch is any of: different lengths; a differing event at any index;
a `[:error m]` on one side only. Equal error messages count as a match
(error parity: unbound symbols, `:not-applicable`, continuation arity).

### 4.5 Corpus

The trace lane runs over every program of:

1. `yin.vm.parity-test/corpus` (`test/yin/vm/parity_test.cljc:39-115`,
   28 rows) — **the B0 parity corpus**; pinned expected values also
   feed the value lane (§5.2).
2. `yin.vm.semantic-register.corpus/programs` (`test/yin/vm/semantic_register/corpus.cljc:54-118`,
   33 rows): most reference unbound names and therefore end in
   `[:error "Unable to resolve symbol: …"]` on both sides, which is
   exactly the error-parity check; the runnable ones (`:literal`,
   `:define`, `:define-call`, `:gensym`, `:store-ops`,
   `:stream-make-default`, `:lambda-application`, `:nested-lambdas`,
   `:if-in-test`, `:define-then-call`, `:resume-body` which errors
   "not found" on both) exercise the productions.
3. The five programs of `yin.vm.continuation-invoke-test`
   (`test/yin/vm/continuation_invoke_test.cljc:131-202`), respelled in
   the trace test (they are inline there) — 4b only, since they
   invoke continuations.
4. `definition-programs` of `yin.vm.rule-r-test`
   (`test/yin/vm/rule_r_test.cljc:172-189`), respelled (private there).

Blocking programs (the four effect deftests of `parity_test.cljc:163-230`,
the `:park`-bearing corpus rows) are **not** traced step-wise: stepping
a blocked walker is undefined (`cesk-transition` with nil control and
nil `k` reaches the unknown-node throw, `ast_walker.cljc:613`). They
are covered by the run-to-completion parity lane (§5.2, §5.3), which
compares values, store, effect traces and halting after `vm/run`.

---

## 5. Slices and gates

Two slices; 4a ships alone. Verification follows `docs/agents/build-n-test.md`:
G0 `clojure -M:test -n <ns>` per iteration, G1 `bb test:sub yin.vm`
at the slice checkpoint, G2 `bb test:changed` before landing, G3 `bb
test` on master. The register evaluator is inside the `yin.vm`
subsystem by prefix (`src/dev/subsystems.edn:41-42`); no registration
change is needed.

### 5.1 Slice 4a — core

**Files to create.**

- `src/cljc/yin/vm/semantic_register.cljc` (ns `yin.vm.semantic-register`):
  the record (§1.2), `create-vm` (template `semantic.cljc:898-951`,
  `:vm-model :semantic-register`), `load-vector`, `attach-image`,
  `ast-loader` (§1.7), `put-registers`, `vm-hot` with the arms
  `:const :var :closure :jump :branch-false :define :gensym :store-get
  :store-put :halt :return :call` (closure and host-fn **value**
  operators only; the effect and `:continuation` arms throw
  `{:reason :not-in-slice-4a}` until 4b), `register-restore` with
  steps 2-3 of §2.2 (step 1 and the `:call-id` handling land in 4b; in
  4a nothing reaches the helper, but `run` must be `engine/run-loop`
  with it from the start), the `vm/IVM` and `vm/IVMState` extensions
  (`step` = `vm-hot` with fuel 1; `run` = `ffi/maybe-run` over the
  scheduler as `semantic.cljc:885`; `eval` refuses an AST as `:865-876`).
- `test/yin/vm/semantic_register/vm_test.cljc` (ns `yin.vm.semantic-register.vm-test`).
- `test/yin/vm/semantic_register/walker_trace_test.cljc` (ns `yin.vm.semantic-register.walker-trace-test`).
- `test/yin/vm/semantic_register/parity_test.cljc` (ns `yin.vm.semantic-register.parity-test`).

No existing source or test file changes in 4a.

**Golden and parity rows, by name.**

- Value parity: every row of `yin.vm.parity-test/corpus` except
  `"stream make"` (needs `:make-stream`; 4b): the register VM's
  normalized value equals the pinned `expected` column.
- Error parity: every `yin.vm.semantic-register.corpus/programs` row
  whose walker run throws, throws with an equal `ex-message` on the
  register VM; every row whose walker run halts, halts with an equal
  normalized value — rows `:streams :stream-make-default :ffi-call
  :ffi-call-no-args :current-continuation :park :resume-arm` are
  deferred to 4b.
- Rule R: `definition-programs` of `rule_r_test.cljc:172-189` (value and
  store slice), plus the `reserved-operands-are-refused-by-the-semantic-loaders`
  pattern (`:283`) against `semantic-register/load-vector`: the
  `[11 {:rule :reserved-name …}]` rows of
  `yin.vm.semantic-register.code-test/refusals` (`code_test.cljc:66-70`)
  are refused by the loader with that rule.
- Trace: §4 over corpora 1, 2 (runnable subset), 4.

**Acceptance list (4a).** Each item is checked by a named test or a
command.

1. `load-vector` refuses a missing or `"v3"` stamp with
   `:contract-missing` / `:contract-mismatch` before any rule runs
   (`vm-test/stamp-before-rules`; mirrors `code_test.cljc:150`).
2. `load-vector` refuses every `code-test/refusals` vector with the same
   `{:rule :pc}` defect as `code/well-formed?` (`vm-test/loader-refusals-are-the-validators`).
3. A loaded image holds `:vector :address :bodies :analysis :segment
   :length`; `:analysis` equals `(analysis/analyze v)`; `:address`
   equals the corpus golden; the alias column maps it to the segment
   id; `:control` is `{:segment seg :pc 0}`, `:window {}`, `:k nil`
   (`vm-test/image-shape`).
4. Loading a second vector mints a segment id below
   `(vm/loaded-code-floor (:code vm))`; reloading an identical vector
   is accepted; a different image under a live id throws "already
   holds different code" (`vm-test/segment-ids`; mirrors
   `semantic_test.cljc:183-246`).
5. `vm/step` executes one instruction: after one step on
   `:worked-example`'s vector with `f g x y` bound in `:env`,
   `(vm/control vm')` is `{:segment seg :pc 1}` and `(:window vm')` is
   `{1 <f>}` (`vm-test/one-step-one-write`).
6. After a non-tail `:call` enters a closure, `(peek (:k vm'))` equals
   `{:type :return :segment seg :pc (inc pc) :env E :window
   (select-keys W (analysis/saved a (inc pc) rd)) :rd rd}` and
   `(:window vm')` is `{}`; for `:worked-example` at pc 3 the frame's
   `:window` is `{1 <f>}` and `:rd` is 2 (`vm-test/return-frame-shape`;
   the numbers are `analysis_test.cljc:131-139`'s).
7. A tail `:call` to a closure leaves `(count (:k vm'))` unchanged
   (`vm-test/tail-call-grows-no-frame`); the `tail-countdown-test`
   program of `semantic_test.cljc:500` runs 10⁵ iterations with `K`
   bounded by 1 (`vm-test/tail-countdown`).
8. `:return` with a non-empty `K` writes exactly the popped frame's
   `:rd` and nothing else: `(dissoc (:window vm') rd)` equals the frame's
   `:window` (`vm-test/return-writes-rd-once`).
9. `:return`/`:halt` with empty `K` halts: `:control nil :k nil
   :halted? true`, `:value` the result, `:env` without
   `engine/store-of-key`; `(vm/halted? vm')` (`vm-test/halt-shape`).
10. A pure host-fn tail call delivers through `K`: for
    `((fn [n] (+ n 1)) 1)` with the body call marked `:tail? true`, the
    machine halts with 2 and the body's `rd` is never written (assert
    via a step trace that no window of the body ever contains key 0)
    (`vm-test/tail-primitive-returns-through-k`).
11. `:define` writes through `engine/put-active`: inside a closure
    carrying `engine/store-of-key` in `E`, the write lands in
    `:module-stores`, not `:store` (`vm-test/define-routes-to-the-module-store`;
    build `E` by hand as `linker_require_test.cljc:673` does indirectly).
12. `:branch-false` on a nil/false test register jumps; both arms write
    the conditional's `rd` and no other register (`vm-test/branch-arms-share-rd`,
    over the `:if`, `:nested-if-same-rd`, `:if-operand` goldens with
    `c a b f y` bound).
13. `engine/operator-kind` refusals are unchanged: calling a non-function
    throws `:not-applicable`; a closure owned by another task throws
    `:foreign-value` (`vm-test/application-refusals`; mint the foreign
    closure with `values/closure :other-owner …` in `:env`).
14. `yin.vm.semantic-register.parity-test/b0-values`: the 27 runnable
    `parity-test/corpus` rows match their pinned `expected` after
    normalisation.
15. `parity-test/corpus-outcomes`: every `corpus/programs` row not
    deferred to 4b has the same outcome (value or `ex-message`) on the
    walker and the register VM.
16. `parity-test/rule-r-definitions`: value and store slice equal for
    the five `definition-programs`.
17. `walker-trace-test/traces-align`: §4.3 traces equal for corpora 1,
    2 (runnable subset), 4; the test prints the first differing index on
    failure.
18. `walker-trace-test/return-is-administrative`: the trace of
    `"lambda application"` (`parity_test.cljc:67-75`) has exactly six
    `[:value …]` events on both sides (the closure, `10`, `+`, `x`, `1`,
    the sum `11`) followed by `[:halt 11]`, proving that entering the
    closure and `:return` added none.
19. `engine/ready-for-ingress?` is true on a fresh `create-vm` and on a
    halted machine (`vm-test/ready-for-ingress`; relies on `:k nil`).
20. `clojure -M:kondo --lint src/cljc/yin/vm/semantic_register.cljc test/yin/vm/semantic_register` reports no errors.
21. `bb test:sub yin.vm` green on JVM, Node and Dart; the Node log shows
    `Testing yin.vm.semantic-register.vm-test`,
    `… .walker-trace-test`, `… .parity-test` (shadow auto-discovers
    `*-test` namespaces; confirm the lines appear).

### 5.2 Slice 4b — effects, waits, continuations, link

**Files to change / create.**

- `src/cljc/yin/vm/semantic_register.cljc`: the remaining arms
  (`:stream-make :stream-cursor :stream-close :stream-put :stream-next
  :ffi-call :current-continuation :park :resume`), the `:host-fn` effect
  arms and the `:continuation` arm of `apply-call` (§1.9), `act-builders`
  / `tail-builders`, `register-restore` step 1 and the `:call-id`
  handling (§2.2), the `module/IModuleKernel` extension (§3.3).
- `test/yin/vm/semantic_register/effects_test.cljc` (ns `yin.vm.semantic-register.effects-test`).
- `test/yin/vm/semantic_register/link_test.cljc` (ns `yin.vm.semantic-register.link-test`).
- **PROPOSED** one-entry additions to four existing backend maps so the
  cross-VM rows run on the fifth kernel without duplication:
  `yin.vm.continuation-invoke-test/runners` (`continuation_invoke_test.cljc:32-48`),
  `yin.vm.ffi-test/vm-kinds` (`ffi_test.cljc:380-397`),
  `yin.vm.rule-r-test/backends` (`rule_r_test.cljc:141-145`),
  `yin.vm.linker-require-test/backends` (`linker_require_test.cljc:115-188`:
  `:image` = `(:vector (sr-linearize/project ast))`, `:vm` = `ast-loader`
  over `create-vm`, `:continue` = `ast-loader` over the halted task,
  `:obligations` = `(constantly [])` until phase 5 supplies the
  scanners — mark the entry with that comment).
- `test/yin/vm/semantic_register/walker_trace_test.cljc` and
  `parity_test.cljc`: lift the 4a deferrals (corpus 3, the seven
  deferred corpus rows, `"stream make"`).

**Golden and parity rows, by name.**

- `yin.vm.semantic-test`: `park-resume-test` (`:538`),
  `current-continuation-test` (`:607`), `blocked-stream-next-wakes-test`
  (`:684`), `blocked-entries-are-pure-data-test` (`:804`),
  `ffi-call-test` (`:865`), `semantic-restore-retained-response-route-test`
  (`:885`) — respelled over register vectors with the §1 shapes in the
  expected maps.
- `yin.vm.semantic-engine-test` (`semantic_engine_test.cljc:82-176`): the
  eight stream rows, respelled.
- `yin.vm.semantic-ffi-test` (`semantic_ffi_test.cljc:65-340`): the
  fourteen bridge rows, respelled.
- `yin.vm.parity-test`: `ffi-round-trip-parity-test`,
  `blocked-read-parity-test`, `stream-round-trip-parity-test`,
  `stream-close-parity-test` (`:163-230`).
- `yin.vm.continuation-invoke-test`: all five deftests on the fifth
  runner.
- `yin.vm.ffi-test/every-vm-kind-parks-and-resumes-under-a-composite-id-test` (`:400`).
- `yin.vm.rule-r-test/a-parked-read-in-a-definition-resumes-and-writes` (`:222`,
  extend its `select-keys` to the fifth backend).
- `yin.vm.linker-require-test`: every deftest that iterates `backends`,
  in particular `a-require-links-installs-and-resumes-with-the-module-test`
  (`:302`), `a-module-closure-reads-and-writes-its-own-store-test` (`:673`),
  `a-module-closure-calling-another-writes-each-store-test` (`:709`),
  `input-after-a-tail-applied-module-closure-is-the-tasks-own-test` (`:944`),
  `a-full-request-stream-retries-the-envelope-verbatim-test` (`:983`),
  `an-install-refuses-a-missing-export-and-a-loader-defect-test` (`:1064`).

**Acceptance list (4b).**

1. `:stream-make` / `:stream-cursor` / `:stream-close` complete inline
   and write `rd`; the values are the engine's sealed references
   (`effects-test/non-parking-stream-ops`).
2. A blocked `:stream-next` parks an `act` entry exactly equal to
   `{:segment seg :pc (inc pc) :env E :window saved :k K :deliver
   {:deliver :rd :rd rd} :reason :next :cursor-ref … :stream-id …}`;
   the entry is pure data and survives `pr-str`/`read-string`
   (`effects-test/blocked-read-entry-shape`; template
   `semantic_test.cljc:804-830`).
3. The saved window of that entry equals `(select-keys W (analysis/saved
   a (inc pc) rd))` and does not contain `rd`; for a program that
   reads a register after the blocked `:stream-next`, that register is
   present in `:window` with its value (nil-valued registers included)
   (`effects-test/saved-window-membership`).
4. Appending a value wakes the entry; the delivered value lands in
   `rd` and nowhere else; the machine halts with the program's result
   (`effects-test/wake-delivers-to-rd`).
5. A blocked `:stream-put` keeps the written value in `:datom`, not in
   `:window`; freeing capacity wakes it and `rd` receives the value
   (`effects-test/blocked-put`).
6. A woken terminal outcome throws before restore, exactly as the
   immediate operation would (`effects-test/terminal-wake-raises`;
   template `semantic_test.cljc:849-858`).
7. An effectful **tail** call (`(fn [c] (stream/next c))` applied in
   tail position with the read blocked) parks a `tail` entry
   `{:segment seg :site pc :k K :deliver {:deliver :return} :reason
   :next …}` with no `:pc`, `:env` or `:window`; waking it pops the
   caller's frame and writes that frame's `:rd`; with an empty `K` it
   halts with the value and `:env` free of `store-of`
   (`effects-test/tail-effect-returns-through-k`, two cases).
8. A **pure** host-fn tail call inside a non-tail-called closure writes
   the caller frame's `rd` once (4a item 10 extended to a caller with a
   frame) (`effects-test/tail-primitive-pops-frame`).
9. `:park` halts with `{:type :parked-continuation :id :parked-0
   :segment seg :pc (inc pc) :env E :window saved :k K :deliver
   {:deliver :rd :rd rd}}` as `:value`; the record round-trips EDN; a
   second segment's `:resume` delivers into `rd`, leaves `:parked`
   empty, and an unknown id throws "not found"
   (`effects-test/park-resume`; template `semantic_test.cljc:538-568`).
10. `:resume` of a record whose `:deliver` is `:return` (a tail-FFI
    bookkeeping record is the only such record; construct it by hand in
    `:parked`) pops `K` or halts, and the loop re-enters correctly from
    a halted state (`effects-test/resume-return-mode`).
11. `:current-continuation` writes a `values/continuation` whose payload
    is `{:type :reified-continuation :segment :pc (inc pc) :env :window
    saved :k :deliver {:deliver :rd :rd rd}}`; the payload round-trips
    EDN (`effects-test/reified-shape`; template `semantic_test.cljc:607-643`).
12. Invoking it is abortive and exactly-once: the five
    `continuation-invoke-test` programs give `9`, `8`, `[30 3]`,
    `[1 :written]`, and `[:thrown "Continuation expects exactly one
    argument"]` for 0 and 2 arguments, on the fifth runner.
13. Invoking a continuation minted by another owner throws
    `:foreign-value` with `:kind :continuation`
    (`effects-test/foreign-continuation-refused`; mint with
    `values/continuation :other …` in `:env`).
14. `:ffi-call` parks under the call id; `ok` yields a response reader
    whose payload keys are the act payload's (`:window :deliver` present,
    `:datom :request-sent :op` absent); the bridge answers; `rd` receives
    the value; `:parked` is empty afterwards (`effects-test/ffi-sent`;
    `ffi_test.cljc:400-425` on the fifth kind).
15. A `full` call-in retains the request verbatim in a `:request-sent`
    writer; the retry's `ok` turns it into a reader through
    `register-restore` step 1 and delivers nothing; the response then
    delivers (`effects-test/ffi-retained`; template
    `semantic_ffi_test.cljc:232-288`).
16. A lowered retained writer carrying `:response-cursor`/`:response-stream`
    waits on the carried route (`effects-test/retained-response-route`;
    template `semantic_test.cljc:885-919`).
17. An FFI `error` response throws "FFI call failed" and leaves no
    parked entry (`effects-test/ffi-error-leaves-no-parked`; template
    `semantic_ffi_test.cljc:105-118`).
18. `gensym` advances `:id-counter` and interleaves with stream ids as
    today (`store-and-gensym-test`, `semantic_test.cljc:401`, respelled).
19. Link: `a-require-links-installs-and-resumes-with-the-module-test`
    passes on the fifth backend: one request, the slice holds a
    `:yin.k/closure` marker, no installs or waits remain, the program
    resumes with `42`.
20. Link, tail: a program whose last expression is a tail
    `(require 'foo)` resumes through `K`: halts with `'foo` and an env
    without `store-of` (`link-test/tail-require`).
21. Store-of: `a-module-closure-reads-and-writes-its-own-store-test`,
    `a-module-closure-calling-another-writes-each-store-test` and
    `input-after-a-tail-applied-module-closure-is-the-tasks-own-test`
    pass on the fifth backend.
22. Install refusals: `an-install-refuses-a-missing-export-and-a-loader-defect-test`
    passes on the fifth backend (a `"v3"` or malformed vector in the
    response is refused by `spawn-module`'s `load-vector`).
23. `lower-closure` refuses a marker whose `params` or `entry` match no
    `:closure` tuple of the attached image with `:marker-mismatch`, and
    a marker of another format with `:binding-mismatch`
    (`link-test/marker-checks`; template `linker_require_test.cljc:1092`).
24. `gc-roots` names `:window`: a cell ref held only in a register
    survives `engine/collect` (`effects-test/window-is-a-gc-root`;
    pattern `heap_reclamation_test.cljc`).
25. Walker trace and parity lanes now cover corpus 3 and the deferred
    rows; `walker-trace-test/traces-align` and `parity-test/*` green.
26. `clojure -M:kondo` clean on the changed files; `bb test:sub yin.vm`
    green on three hosts, then `bb test:changed` (G2) green on three
    hosts. The Node log shows `Testing yin.vm.semantic-register.effects-test`
    and `… .link-test`.

---

## 6. Findings and proposed amendments to the frozen design

Each finding names the evidence, the proposed text, and the section it
amends. None changes a transition or a shape; F1-F3 correct what the
frozen text says the engine *is*; F5-F6 close gaps the phase-4
implementer would otherwise fill ad hoc.

**F1 — the restore seam is not `handle-effect`.** Evidence: §2.1 above
(`engine.cljc:2224-2256`, `:2299-2309`, `:2565-2683`; `module.cljc:546-549`).
Amends §2.4 "Delivery" paragraph and §11 item 4. Proposed text for
§2.4: "The engine seam already supports this: a VM's restore function
`restore-fn(base, entry, value)` is called only by
`engine/resume-from-run-queue` and `engine/resume-continuation`, after
the entry has left its table and after the terminal-outcome check, with
`base` the blocked machine (`:control nil :k nil`); it treats the VM
payload as data and does not require a current activation, …". §11
item 4: replace "the engine seam's `restore-fn(base, entry, value)`"
with "the engine's restore seam (`resume-from-run-queue`,
`resume-continuation`)".

**F2 — the empty-K halt of a `tail` state needs an `E` to leave.**
Evidence: `:halt` and `:return`-on-empty-K leave `(engine/without-store-of
E)` as `:env` (`semantic.cljc:330-331`, `:339-340`); a `tail` state has
no `E` (frozen §2.4). Amends §2.4 "Delivery". Proposed sentence after
"runs `return(v)` against `K`, which pops a frame or halts": "When it
halts, the machine's `:env` is the blocked machine's own `:env` (the
tail site's `E`, written back before the effect ran) cleared of the
module-store key, as `:halt` leaves it."

**F3 — K has one frame kind; the FFI phases are wait-entry markers,
not frames.** Evidence: the stack VM's `K` holds only `:return` frames
(`semantic.cljc:237-238`); `:dao.stream.apply/eval-call` and
`:request-sent` are walker continuation types (`ast_walker.cljc:229`,
`:252`), while the semantic VM marks the FFI phases on the **wait
entry** (`:call-id`, `:request-sent true`; `semantic.cljc:110-119`,
`:498-508`; `ffi/response-call-id`, `ffi.cljc:78-99`). Amends §2.1
bullet **K** (and §4.2's `:yin.k/k` comment inherits the correction).
Proposed text: "**K**: a vector of frames, innermost last, of one kind,
`{:type :return :segment :pc :env :window :rd}` (§2.4). The FFI
phases (`sent`, `retained`) are markers on the wait entry
(`:call-id`, `:request-sent`), as today, never frames of `K`."

**F4 — in-VM reason names.** Evidence: the engine's reasons are `:next
:put :observe :link-request :link-response :install`; `:ffi` and
`:ffi-request` are UCF wire reasons only (UCF §7.4.3; `ffi/response-call-id`
reads `:reason :next` with `:call-id`). Amends §4.1 table, column
"State" for the two FFI rows: add "(in-VM: `:reason :next` + `:call-id`
/ `:reason :put` + `:request-sent`)". Notational.

**F5 — `:return r` is administrative in the trace correspondence.**
Evidence: §4.1 above. Amends §2.5 table: add the row "| a closure
body's value flowing to the caller through `:next k` | `:return r`: no
new value; the body's write of `r` is the event. `return(v)` of a value
not in the window (a tail primitive, a tail effect completion) is the
event |". Without this row the §2.5 instruction "administrative steps
skipped" is ambiguous for returns.

**F6 — the phase-4 evaluator needs an interim link format keyword.**
Evidence: `linker/semantic-format` binds `:yin.semantic/code` to the
`"v3"` stack vector and `yin.vm.code/well-formed-vector?`
(`linker.cljc:692-712`); `module/IModuleKernel/link-format` must answer
something for the install child's envelope (`module.cljc:580-590`);
frozen §6 and §10 phase 8 only say the stamp becomes `"v4"` and the
name becomes `yin.vm.semantic` at cutover. Amends §10 phase 4.
**PROPOSED** text: "The evaluator links under the interim format
`:yin.semantic-register/code`, contract `"v4"`, which phase 5 gives a
linker format record (scanners over the new table) and phase 8 retires
in favour of `:yin.semantic/code` `"v4"`." The keyword is the one
decision in this document that is purely conventional; the reviewer may
prefer another spelling.

**F7 — `define`'s store write goes through `put-active`.** Evidence:
`semantic.cljc:356-361`, `engine.cljc:216-224`. Amends §2.4 "`define`
writes through `engine/store-put`": "…through `engine/put-active`
(which routes to the active module store and calls `engine/store-put`),
…". Notational; the Rule R refusal is unchanged.

Checked and found consistent with the engine (no amendment): §2.4
`call` (all seven arms), `return`, `stream-next` outcomes
(`engine.cljc:622-649`), `stream-put` retained value (`:2609-2611`),
`ffi-call` (`semantic.cljc:466-518`), `park` as the no-wait shape
(`engine.cljc:2280-2296`), `resume` gate refusal (`:2303`),
`current-continuation` as a value not a safepoint, ownership refusal
(`engine.cljc:77-90`), §3.4 image shape (`code.cljc:864-879`), §8.1
`saved` (`analysis.cljc:92-97`), §10 phase-3 image note.

---

## 7. Questions for the owner

None. Every decision above follows from the frozen design, the owner's
three rulings quoted there, or code that exists. Two items are for the
**Architect reviewer**, not the owner: the interim format keyword (F6)
and whether 4b may add one entry to the four existing backend maps
(§5.2) rather than duplicating those suites.

---

## 8. Portability notes for the implementer

Every item names the repo-known host trap it guards against.

1. **Reader conditionals.** Catch clauses are
   `#?(:cljd Object :clj Throwable :cljs :default)` with `:cljd` first
   (`engine.cljc:1363`); any `:clj`-only form must be spelled
   `#?(:cljd nil :clj …)` because the ClojureDart host-eval pass also
   has `:clj`.
2. **Multi-key `assoc` on nil (Dart).** The window is always a map:
   `:window {}` at load, `{}` on closure entry, `(select-keys W …)`
   (returns `{}` on an empty set) for saved windows. Never `nil`.
   `(assoc nil r v)` with one key is safe, but keep the invariant
   anyway so `(:window frame)` is never nil in a restore.
3. **`for` over long seqs (Dart).** Argument vectors are
   `(mapv #(get W %) arg-regs)`; saved windows are `select-keys`; never
   `for`.
4. **nil-valued registers.** `[:const 0 nil]` is legal; `(get W r)`
   returns nil for both "nil" and "absent", which is safe at runtime by
   definite assignment (§3.4 item 8), but saved windows must be built
   with `select-keys`, never by filtering `some?` values, or the §4.2
   membership check (phase 6) fails on a nil-valued live register.
5. **Protocol parameter names (Dart).** `gc-children [_ _x]`, never
   `[_ _]` (`semantic.cljc:1058` already does this).
6. **Private var access (Dart).** `register-restore`, `load-vector`,
   `attach-image`, `ast-loader` are public; tests must not reach
   private vars through `#'`.
7. **EDN round trips in tests.** `(edn/read-string (pr-str x))` is fine;
   hand-written EDN strings must have no whitespace before a closer.
8. **Keyword `case` dispatch.** Dispatching on mnemonic keywords in the
   hot loop is portable (the phase-3 validator does it on all three
   hosts, `code.cljc:368`); no integer opcode table is needed for the
   gate.
9. **Unary minus on floats.** Not touched by this evaluator (no float
   arithmetic in the kernel); primitives are the engine's.
10. **Fresh worktree.** `mise trust` and `npm ci` before the Node lane.
