Created-GMT: 2026-09-22 09:25:41 GMT
Created-Local: 2026-09-22 16:25:41 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: pending (new session, caller-generated)
# Task: architect-vm-restore-protocol — formalize the shared VM restore/park-entry interface in yin.vm.engine
Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-22 16:25:41 +07 | Status: active | Rationale: owner-directed, front-loading a shared abstraction before B4 (stack VM effects/continuations) starts, so B4 and the eventual register-VM effects phase both implement against a formal interface instead of duplicating ad hoc glue by hand a second and third time

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master). This is a DESIGN task. You may edit
docs/design/yin.vm.debruijn.stack.md (the B4 phase box and section 4 only)
and docs/design/yin.vm.debruijn.register.md (only where it already
discusses the register kernel's state/continuation shape, section 5) and
docs/design/yin.vm.engine.md if that is the right home for a new document
-- decide and say which. Do not edit any source file; this phase produces
a design, not an implementation.

## Why this exists (owner's own words)

"all the vm should share common infrastructure like the main dispatch loop
and scheduling continuations and stream effects. i think there's already a
common scheduler used to pause/resume stream effects"

A research pass this session (quoted in full below, trust it as fact --
it was verified against the actual files, not guessed) confirmed:
`src/cljc/yin/vm/engine.cljc` already IS a VM-agnostic shared scheduler:
`run-loop` (the generic dispatch loop, parameterized by `active?`/
`step-fn`/`resume-fn`), `check-wait-set`/`resume-from-run-queue` (wait-set
polling and continuation resumption), `park-continuation`/
`resume-continuation`, and `handle-effect` plus the stream-effect handlers
(`handle-put`/`handle-next`/`handle-cursor`/`handle-make`/`handle-close`)
are all genuinely VM-shape-agnostic already, in production use by the
named VM (`yin.vm.semantic`). `yin.vm.debruijn.stack` (B3) already depends
on this namespace for one helper (`resolve-var`).

The gap: nothing abstracts the VM-SHAPE-specific glue each VM must supply.
The named VM hand-writes `semantic-restore` (a closure that reconstitutes
its own `{:control :value :stack :env :k}` registers from a woken/ready
entry), `scheduler-round` (a 2-line wrapper baking in `semantic-restore`),
and `call-park-entries`/`response-wait-entry` (its own park-entry
builders), all private to `semantic.cljc` and hard-coded to its register
shape. `docs/design/yin.vm.debruijn.stack.md`'s B4 phase box already plans
for the stack VM to hand-write the SAME KIND of glue for its own
`{:segment :pc :frames :free-env :stack :continuation}` shape -- and a
future register-VM kernel (R4 in `yin.vm.debruijn.register.md`, gated,
not yet authorized) would do it a third time. There is no protocol,
multimethod, or higher-order interface over "what a VM's restore-fn and
park-entry-fns must look like" -- just a convention proven twice by hand
so far, about to be proven a third time by copy-and-adapt.

## Read first, in full

- src/cljc/yin/vm/engine.cljc, in full (631 lines) -- this is the namespace
  you are adding a protocol/interface to, or deciding not to.
- src/cljc/yin/vm/semantic.cljc -- specifically `semantic-restore`
  (~lines 117-154), `scheduler-round` (~471-477), `resume-from-run-queue`
  (~480-484), `semantic-run-scheduler` (~487-498), `call-park-entries`
  (~157-172), `response-wait-entry` (~105-114), and the `:park`/`:resume`/
  `:current-continuation`/stream-effect opcode bodies that call into these
  and into `engine/park-continuation`/`engine/resume-continuation`/
  `engine/handle-effect`. This is VM instance #1 of the pattern you are
  formalizing.
- src/cljc/yin/vm/debruijn/stack.cljc, in full -- VM instance #2, which
  currently implements none of this (confirmed: `blocked?` hardcoded
  false, no park/resume/effect opcodes, `run` is a plain loop with no
  wait-set involvement). This is where the SAME pattern is about to be
  written a second time, per the design doc below.
- docs/design/yin.vm.debruijn.stack.md section 4 (state, park/resume
  payload) and the B4 phase box (near the end of section 6) -- read
  exactly what B4 currently commits to: "it may reuse data-only engine
  helpers... it reimplements any helper that serializes the named
  register layout, including private response wait entries and named
  telemetry snapshots." This is the plan you are either formalizing or
  leaving as-is with reasons.
- docs/design/yin.vm.debruijn.register.md section 5 ("Execution
  boundary") -- the gated, not-yet-authorized register kernel's state
  shape `{:image :pc :registers :frames :free-env :continuation :store
  :status :primitives :modules}` and its note that it "uses the same
  frame, free-name, store, stream, and continuation contracts as B3."
  This is VM instance #3, hypothetical, gated behind R3's benchmark --
  your design should consider it but must not assume it will ever be
  built.
- docs/agents/architecture.md, the AGENTS section (continuations as
  first-class reified execution contexts, agents unified with functions/
  closures/continuations) -- the general invariant this pattern already
  serves.

## The full research report, verbatim (trust this; it was verified
## against the actual files by a prior pass this session, not guessed)

<research>
## 1. `scheduler-round` in semantic.cljc

`src/cljc/yin/vm/semantic.cljc:471-477`:

```clojure
(defn- scheduler-round
  [vm]
  (let [v' (engine/check-wait-set vm)]
    (or (engine/resume-from-run-queue v' semantic-restore) v')))
```

Called from `vm-hot` (semantic.cljc:241-242) whenever the loop is entered
with `(nil? (:control vm))` and `(nil? (:k vm))`. It is a two-line
wrapper: polls the wait-set via `engine/check-wait-set` (moves woken
entries onto the ready-queue), then pops+restores one ready-queue entry
via `engine/resume-from-run-queue` passing `semantic-restore` as the
restore callback. If nothing woke and nothing was ready, it returns
unchanged.

It depends entirely on `yin.vm.engine` (`check-wait-set`,
`resume-from-run-queue`) and, transitively, on `dao.stream.waitset/check`
(engine.cljc:302-336) and `dao.stream` (`append!`, `next`, `close!`). The
genuinely VM-specific piece is `semantic-restore` (semantic.cljc:117-154),
which knows how to reconstitute the SemanticVM's five registers
(`:control :value :stack :env :k`) from a woken/ready entry, including the
FFI request/response two-step. `scheduler-round` itself is generic
dispatch glue; `semantic-restore` is the tightly-coupled, register-shape-
specific part it is parameterized with.

Compare `resume-from-run-queue` (semantic.cljc:480-484), a thin private
wrapper over `engine/resume-from-run-queue` with `semantic-restore` baked
in, and `semantic-run-scheduler` (semantic.cljc:487-498), which drives the
whole thing through `engine/run-loop` (engine.cljc:342-356) -- the actual
generic scheduling loop (`step-fn`/`active?`/`resume-fn` params).

## 2. Shared scheduler namespace: `yin.vm.engine`

Yes -- `src/cljc/yin/vm/engine.cljc` (631 lines) is exactly this, already
written to be VM-agnostic. `(:require [dao.stream :as stream]
[dao.stream.waitset :as waitset] [yin.vm :as vm] [yin.vm.module :as
module] [yin.vm.telemetry :as telemetry])` -- no dependency on
`yin.vm.semantic`. Documented as "Shared evaluation machinery for Yin VMs
on DaoStream v2" (engine.cljc:1-2). Contents:

- Data-only helpers: `bind-params`, `resolve-var`, `gensym`,
  `vm-blocked?`, `vm-value`, `halted-with-empty-queue?`,
  `restore-initial-env`, `active-continuation?`, `ready-for-ingress?`.
- Effect handlers: `handle-make`, `handle-put`, `handle-cursor`,
  `handle-next`, `handle-close`, dispatcher `handle-effect`
  (477-542, switches on `:effect` keyword).
- Genuine dispatch loop: `run-loop` (342-356) -- `(loop [v state] (cond
  (active? v) (recur (step-fn v)) (:blocked? v) ... (seq q) ... :else
  ...))`. Parameterized entirely by caller-supplied `active?`/`step-fn`/
  `resume-fn`; no reference to `:control/:k/:value/:stack/:env` anywhere
  in it.
- Wait-set/ready-queue machinery: `check-wait-set` (302-336),
  `make-woken-run-queue-entries` (133-159), `resume-from-run-queue`
  (404-425, pops one ready entry, merges `:store-updates`, checks
  `terminal-resume-outcome`, then calls the caller-supplied `restore-fn`).
- Park/resume primitives: `park-continuation` (428-439, mints a
  `:parked-N` id, stores it under `:parked`, marks `:halted? true`) and
  `resume-continuation` (442-450, looks it up, calls caller's
  `restore-fn`).
- Program-cache machinery (unrelated to scheduling): `build-program-index`,
  `frame-versions`, `pinned-compiled-versions`, `trim-compiled-cache`,
  `cache-compiled-artifact`, `ensure-compiled-version`,
  `maybe-recompile-at-boundary`.

`yin.vm.engine` already IS the shared scheduler/dispatch-loop/park-resume/
wait-set namespace. It is VM-agnostic by construction -- everywhere it
needs VM-shape knowledge it takes that as a function parameter
(`restore-fn`, `active?`, `step-fn`, `park-entry-fns`) rather than assuming
a shape. The only place it reads/writes specific keys directly is generic
bookkeeping shared by convention across VMs: `:blocked?`, `:halted?`,
`:wait-set`, `:ready-queue`, `:parked`, `:store`, `:id-counter`, `:k` (only
in `ready-for-ingress?`, as a generic "is anything running" check),
`:bytecode`/`:control` (also only in `ready-for-ingress?`).

## 3. Named VM park/resume/current-continuation to dao.stream call path

- `:current-continuation` (opcode 19, semantic.cljc:307-315): reifies the
  five registers into `{:type :reified-continuation :segment :pc :env
  :stack :k}`, pushes as a value -- no engine call, pure data capture.
- `:park` (opcode 17, semantic.cljc:316-323): calls
  `engine/park-continuation` (engine.cljc:428-439) with the post-park
  continuation `{:segment :pc :env :stack :k}`; mints `:parked-N`, stores
  under `(:parked vm)`, sets `:halted? true`, returns the id as `:value`.
- `:resume` (opcode 18, semantic.cljc:324-341): calls
  `engine/resume-continuation` (442-450) with a VM-specific restore
  closure that calls `put-registers` -- direct analog of `semantic-restore`
  for the explicit resume-opcode path (not the wait-set path).
- Stream effects (`:stream-put`, `:stream-next`, etc., opcodes 13/15,
  semantic.cljc:376-402): private `run-effect` (175-192) calls
  `engine/handle-effect` (477-542) with `park-entry-fns` built by
  `call-park-entries` (157-172). `handle-effect` dispatches to
  `engine/handle-put`/`engine/handle-next`, which call
  `dao.stream/append!`/`next` directly (190, 233). On `:blocked`/`:full`,
  the park branch calls `handle-stream-block` (462-474), building a
  wait-set entry via the caller-supplied builder, conjing onto
  `(:wait-set state)`, setting `:blocked? true`.
- Later, between evaluations, `vm-hot` routes into `scheduler-round`
  (section 1), which calls `engine/check-wait-set` -> `dao.stream.waitset/
  check` (302-336, via `waitset-resolver` at 270-299, polling with
  `stream/next`/`stream/append!`), moving woken entries to `:ready-queue`
  via `make-woken-run-queue-entries`. `engine/resume-from-run-queue` pops
  one, checks `terminal-resume-outcome`, calls `semantic-restore` to write
  the woken value back into `:control/:value/:stack/:env/:k`.
- FFI (opcode 21, `:ffi-call`, semantic.cljc:411-461) is a third variant:
  calls `engine/park-continuation` directly, does its own
  `dao.stream.apply/put-request!`/`request`, on `:dao.stream/ok` or
  `:dao.stream/full` manually appends a `response-wait-entry`
  (105-114) to `:wait-set`, reusing the same generic wait-set/
  scheduler-round machinery to eventually wake and resume via
  `semantic-restore`.

Full path for stream backpressure: opcode -> `run-effect`/`handle-effect`
(engine) -> `dao.stream/append!` or `next` -> on block, entry pushed to
`:wait-set` -> later, `scheduler-round` (semantic.cljc) ->
`engine/check-wait-set` -> `dao.stream.waitset/check` ->
`dao.stream/append!`/`next` again (poll) -> woken entries -> `ready-queue`
-> `engine/resume-from-run-queue` -> `semantic-restore` (VM-specific)
writes registers back.

## 4. B3 stack VM (stack.cljc): none of this exists yet, by design

`src/cljc/yin/vm/debruijn/stack.cljc` implements no effects, no park/
resume, no scheduling:

- Namespace docstring (17-21): "Stream operations, primitives-as-effects,
  FFI, gensym, current-continuation, park, and resume are B4's phase; an
  opcode this namespace does not recognize fails loudly with a
  `:not-yet-implemented` ex-info rather than silently no-op-ing."
- `step1`'s `case` (132-237) has no arms for `:stream-*`, `:park`,
  `:resume`, `:current-continuation`, `:ffi-call`, or `:gensym`; default
  arm throws `(ex-info (str "Not yet implemented in B3: " op) {:rule
  :not-yet-implemented :op op})` (236-237).
- `vm/IVM` `blocked?` hardcoded `false` (262).
- `run` (251-253) is a plain unconditional loop/recur to `:halted` -- no
  wait-set polling, no scheduler round.

It shares one namespace already: `(:require [yin.vm :as vm] [yin.vm.engine
:as engine])` (30-31), but the only thing it calls from `engine` is
`engine/resolve-var` for `:load-free` (144-145). It does not call
`engine/handle-effect`, `check-wait-set`, `run-loop`, `park-continuation`,
`resume-continuation`, or `gensym`. Exactly one generic, non-scheduling
helper shared so far; every scheduling/effect/continuation-adjacent piece
of `engine.cljc` is unused by B3 so far -- consistent with "B4's phase,"
not started.

## 5. Design doc B4 section

`docs/design/yin.vm.debruijn.stack.md`:

- Status line: "B0, B1, B3 implemented and merged; B2 in progress; B4-B7
  not started."
- Section 4: "The VM is a new `yin.vm.debruijn.stack` namespace. It may
  implement existing VM protocols without adding methods, and it may
  reuse data-only engine helpers for name resolution, primitive
  descriptions, stream descriptors, store operations, gensym, and
  continuation records. It reimplements any helper that serializes the
  named register layout, including private response wait entries and
  named telemetry snapshots. No existing protocol or VM semantics
  change." The load-bearing sentence: B4 must reuse `engine`'s data-only
  helpers but must REIMPLEMENT anything baking in the named register
  layout -- it cannot reuse `semantic-restore`, `response-wait-entry`,
  `call-park-entries`, or `scheduler-round` itself, must write its own
  restore function and park-entry builders for its `{:segment :pc :frames
  :free-env :stack :continuation}` shape, then hand them to the same
  generic `engine/check-wait-set`/`resume-from-run-queue`/`run-loop`/
  `park-continuation`/`resume-continuation`.
- Section 4 on park/resume payload: "Park and resume carry the frame
  stack, free environment, operand stack, code identity, continuation
  chain, store view, parked records, gensym counter, primitives, modules,
  code aliases, and the semantic VM's other non-environment registers as
  explicit data. B4 supplies a frame-aware completion adapter or a lift
  through the descriptor morphism before exporting a continuation." --
  confirms B4 will write a B3-shaped restore/park-entry adapter, not
  borrow the named VM's.
- B4 phase box: `Existing source: src/cljc/yin/vm/debruijn/stack.cljc`
  (no new source file); `New: test/yin/vm/debruijn/stack_effects_test.cljc`;
  `Must not change: dao.stream protocols, lease, waitset, named effect
  rules, merged projection namespace`. "Implement stream operations,
  primitives, FFI, gensym, current-continuation, park, and resume.
  Completion requires parity for values, errors, effects, stores, stream
  outcomes, and blocked states." The "must not change" list including
  "waitset" and "named effect rules" signals B4 must plug into the
  EXISTING `dao.stream.waitset` contract and `yin.vm.engine`'s
  effect-handling rules rather than invent parallel stream-blocking
  semantics -- reuse at the `dao.stream`/`engine.handle-effect` layer is
  a design constraint, not merely permitted.
- No mention anywhere of a new `yin.vm.scheduler` or generalized
  `run-loop`-around-multiple-VM-shapes abstraction; the model is "each VM
  instance supplies its own restore-fn/park-entry-fns to the same generic
  engine functions," matching semantic.cljc today. No proposed
  `defmulti`/protocol for restore-fns across VMs -- that generalization is
  left implicit/undesigned.

## 6. architecture.md on continuations/scheduling

`docs/agents/architecture.md`'s AGENTS section (35-71): "Agents, functions,
closures, and continuations are a unified concept in Yin.VM" (37), "Agents
own state through their continuation structure: call stack, environment,
and stored values" (39), "Agent behavior is specified entirely through
stream effects" (41); later "Continuations are first-class, serializable,
and mobile across nodes. Functions, closures, and continuations are
unified as reified execution contexts" (115-116). A genuine, long-standing
architectural invariant -- continuations-as-data, effects-as-streams --
exactly what `yin.vm.engine`'s pure-data wait-set/ready-queue/parked-map
design implements.

Caveat: the same doc's "YIN.VM & UNIVERSAL AST" section (89-108) currently
states SemanticVM and the stack/register bytecode VMs "are deleted;
ASTWalkerVM is the only evaluator" -- stale relative to the actual repo
(both files exist and were edited after that doc's own last change). The
owner has already separately flagged this specific note as stale color
from an unrelated dao.stream v2 migration, not a live objection -- do not
re-raise it. The general continuation/effects-as-streams invariant
predates and is consistent with the concrete `yin.vm.engine`
implementation; it is the intended pattern, not a new idea.
</research>

## A real precedent: v1 already ran 3 VMs on this exact convention

The owner pointed out, after the research above, that an OLDER generation
of this codebase (v1, pre-dao.stream-v2, deleted in commit `d8b27a50`
"refactor(vm): delete experimental v1 VMs and the macro engine",
2026-09-10 -- the SAME deletion commit the owner separately confirmed was
an unrelated dao.stream v2 structural migration, not a rejection of
register VMs on their merits) already had THREE VMs sharing one
`yin.vm.engine`: `src/cljc/yin/vm/semantic.cljc` (1138 lines),
`src/cljc/yin/vm/stack.cljc` (1474 lines), and
`src/cljc/yin/vm/register.cljc` (1493 lines), all against a v1
`src/cljc/yin/vm/engine.cljc` (671 lines). Pull them with `git show
d8b27a50~1:src/cljc/yin/vm/engine.cljc` (and `register.cljc`,
`stack.cljc`, `semantic.cljc` at the same revision) to read them
yourself -- do not take this summary as a substitute for reading the
actual code.

Confirmed by grep across all three: each VM had its OWN hand-written
restore function (`semantic-vm-restore`, `stack-vm-restore`,
`register-vm-restore`), each calling the SAME generic
`engine/park-continuation`, `engine/resume-continuation`,
`engine/resume-from-run-queue`, `engine/run-loop`, `engine/handle-effect`
-- passed as a plain positional `restore-fn` function argument at each
call site (e.g. `register.cljc:981`:
`(engine/resume-from-run-queue state register-vm-restore)`; `stack.cljc:890`:
`(engine/resume-from-run-queue state stack-vm-restore)`). There is NO
`defprotocol`, NO `defmulti`, NO registration mechanism anywhere in v1's
`engine.cljc` for this -- confirmed by grepping all four files for
`defprotocol`/`defmulti`/`:restore` and finding only the `restore-fn`
parameter name repeated at call sites, nothing more formal. v1's
`engine.cljc`'s own top-level function list
(`run-loop`, `check-wait-set`, `resume-from-run-queue`,
`park-continuation`, `resume-continuation`, `handle-effect`,
`handle-make`/`handle-put`/`handle-cursor`/`handle-next`/`handle-close`,
plus the program-cache helpers) is close to identical in shape to today's
v2 `engine.cljc` -- the current file already inherited this design, not
by accident.

Treat this as real, load-bearing historical evidence directly answering
question 1 below: this exact convention (bare `restore-fn`/`park-entry-
fns` parameters, no protocol) already scaled to precisely the 3-VM case
(semantic, stack, register) this task is worried about, in this same
codebase, for real production code of substantial size, for some real
period of time, before being deleted for reasons unrelated to this
question. Weigh this concretely in your answer -- it is not a hypothetical
argument for restraint, it is a demonstrated outcome.

## What to design

Decide, and produce a design for, whether and how to formalize the
restore-fn/park-entry-fns contract that `yin.vm.engine`'s `run-loop`,
`check-wait-set`/`resume-from-run-queue`, and `park-continuation`/
`resume-continuation` already take as parameters. Specifically:

1. Is a formal protocol/multimethod/interface actually warranted with
   only ONE proven instance (`semantic.cljc`) and one about-to-exist
   instance (B3's B4 phase), or is it premature abstraction against this
   project's own "no premature abstraction, three similar lines beats a
   premature abstraction" discipline -- until there are genuinely two
   independent, already-written instances to generalize FROM (i.e. write
   B4 first per the existing plan, THEN extract the shared shape from
   the two real implementations, rather than design the abstraction
   before either B3's or a register VM's instance exists in code)? Give
   your own reasoned answer; do not just defer to the owner's framing of
   the question.
2. If you conclude formalization now is warranted: specify the exact
   interface (a protocol? a map-of-functions spec? a required-keys
   contract for the VM state map itself, e.g. a `:yin.vm.engine/restore`
   key holding the restore-fn, `:yin.vm.engine/park-entries` holding the
   builder?). Show what `semantic-restore`/`scheduler-round`/
   `call-park-entries` would look like conforming to it, and what B3's
   not-yet-written B4 equivalents would look like conforming to it, so
   the design is validated against both a real and a planned instance.
   State whether `scheduler-round` itself (currently a private 2-line
   semantic.cljc function) should move into `engine.cljc` as a shared
   `engine/scheduler-round` taking a restore-fn, eliminating that
   duplication specifically, even if you decide against a broader
   protocol.
3. If you conclude formalization now is premature: say so plainly, and
   instead specify the minimum concrete guidance B4's implementer needs
   so its hand-written glue is SHAPED for easy future extraction (e.g. a
   documented naming/shape convention: every VM's restore-fn takes the
   same argument shape and returns the same shape, every VM's park-entry
   builder follows the same key set) -- this still narrows duplication
   risk without committing to an abstraction the codebase does not yet
   need. Update the B4 phase box in `yin.vm.debruijn.stack.md` with this
   guidance either way (whether you formalize now or defer).
4. Note the implication for the gated, not-yet-authorized register VM
   (R4 in `yin.vm.debruijn.register.md`): if it is ever built, should its
   design already point at whatever interface/convention you specify here
   (a one-line pointer, not a rewrite of that document -- you may edit
   its section 5 only for this one-line pointer), or is that premature
   given R4 itself is gated behind R3's benchmark and may never be built?

Distinguish architectural defects/gaps from premature abstraction. This
project's stated preference (see the coordination rules any engineer here
works under) is real caution against unnecessary abstraction -- factor
that into your recommendation, do not default to "build the protocol"
just because the owner asked the question.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: your answer to (1) with reasoning; what you designed or deferred and
why; exactly which files you edited and what changed; anything needing the
owner's decision.
