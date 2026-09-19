# dao.lease — implementation plan

Status: implementation plan, subordinate to [`dao.lease.md`](./dao.lease.md)
(the operative contract) and [`dao.stream.md`](./dao.stream.md) (the stream
contract the facts ride on). Authored by the Architect, revised once against an
independent adversarial review (see *Revision — 2026-09-19 reconciliation*).
Nothing here is in production, and nothing in this plan changes `dao.stream.md`
or any existing namespace: a lease "adds no operation to any contract and no key
to any DaoStream result map."

Method as in the sibling plans: the invariants list (§2) is the contract,
grouped by the test that exercises each and marked `[D]` stated in the design,
`[T]` pinned only by a test, `[T→D]` test-pinned and promoted to the design.
Where this plan adds a clause the design does not state, it is marked `[D]` *
new*. The implementation and its tests have no authority beyond the invariants
they pin. This document is transient: it is deleted when nothing in it is still
owed, and §6 names where each thing it carries must be written first.

---

## 0. Corrections to the brief

The brief is the two design documents. Three sharpenings, each of which changes
what the plan says rather than what the contract means:

1. **The two operative contracts still contradict each other, and this plan does
   not resolve that on its own authority.** `dao.stream.md:752-755` says a
   pause's "semantics live in `dao.lease.md`, whose facts are datoms on a medium
   (`dao.space`)"; `dao.lease.md:14` says "Facts are plain data on ordinary
   streams, classified by two dispatch keys." Datoms *are* plain data, so the
   latter does not logically repeal the former, and `dao.lease.md:8-10` says a
   lease "leaves `dao.stream.md` unchanged by its existence." A subordinate
   implementation plan has no authority to choose between two operative
   contracts. This plan therefore records the contradiction, **builds plain maps
   on `dao.stream`** (the only carrier `dao.lease.md` names as operative), and
   hands the amendment to the orchestrator. Proposed one-sentence amendment to
   `dao.stream.md`'s Composition section: change "Those semantics live in
   `dao.lease.md`, whose facts are datoms on a medium (`dao.space`)" to read
   "Those semantics live in `dao.lease.md`, whose facts are plain data on
   ordinary streams." Until that amendment is applied, this plan's plain-map
   choice is flagged **unresolved-contract** in §6, not asserted as settled.

2. **The tick stream is net-new, and its producer must not be a `dao.lease`
   timer.** `grep -r dao.lease src/` returns nothing, and no `:dao.lease/tick`
   producer exists. The design's Time section requires "a tick stream their
   composition wires" for the judge and one per holder, and the evidence table
   makes the tick's author the *adapter*. There is a rich *driver-cadence*
   vocabulary already (`serving/step! [serving now]`, `ws/endpoint-step
   [endpoint now]`, `yin.repl`'s `poll-loop!`, the daemon thread at
   `dao/jing/remote.cljc:981-988`) and a *time-as-data* convention (`now`
   threaded as an argument, never a clock read inside the step) — but no tick
   *stream*. The contract's Prohibitions rule out a `dao.lease` timer ("No timer
   holds a callback", `dao.lease.md:82`), so the reference tick producer is a
   **step/deposit function a host driver calls** — it installs no timer and holds
   no callback, and the timer lives in the host driver, which is outside
   `dao.lease.cljc` (see D3, §4.4).

3. **The JIT precedent is design-only.** `docs/design/yin.vm.jit.md` is a
   contract with no implementation: `src/cljc/yin/vm/` contains no trace or jit
   namespace, `telemetry.cljc` is an explicit no-op stub, and `yin/vm.cljc`
   defines only the baseline `IVM`/`IVMState` protocols. `dao.lease` inherits
   the JIT's *shape* — advisory evidence, an explicit `status` carried as
   queryable data, a guard checked only at possessor-scheduled judgment points,
   fallback on guard failure — not its code. The concrete building blocks
   `dao.lease` composes against are the `dao.stream` protocol and outcomes
   (`dao.stream.cljc:54-118,156-183`) and the status-as-data idiom, not any JIT
   implementation.

---

## 1. What the namespace is

`dao.lease` is a small, host-neutral vocabulary and two interpreters over it,
all portable (`.cljc`). It provides:

- **The vocabulary** — constructors and validators for the five status facts
  and two evidence events, duration arithmetic in a single-unit representation,
  and strict interval comparison. Structural validity is a pure function of a
  fact alone; history-dependent admissibility is a judge-local gate (see V4/V7).
- **The judge** — an interpreter owning a private **ledger** of live leases,
  running the six-step **pass**, reclaiming through a composition-supplied
  idempotent procedure, and recording `:lapsed` after the act. A single
  **effectful, state-threaded** step the composition's driver calls at a declared
  cadence; it owns no scheduler, callback, or registry (the `forward-step`
  discipline, `dao/stream/forward.cljc:1-6`), but it is *not* pure — it reads
  transport handles, invokes the reclaim effect, and appends `:lapsed` (D2).
- **The holder** — renewal discipline: observe the grant before acting, renew at
  strictly less than half the duration, stop at the bound, release when done.
  Helper functions a holder's own control flow calls; no machinery renews on a
  holder's behalf.
- **The composition helper** — a `make-*` constructor that wires the injected
  seams (attribution resolver bound to a source, reclaim procedures, tick cursor,
  an explicit medium declaration) and **refuses at assembly** when a required
  seam is missing or incompatible, in the `make-serving` / `make-attacher` idiom
  (`serving.cljc:78-141`, `ws.cljc:375-405`).

The split is a single portable namespace, `src/cljc/dao/lease.cljc`, with three
clearly delineated sections (vocabulary, judge, holder) and one composition
section. The vocabulary is deliberately *not* a separate file: it is the "small
vocabulary" the rationale scopes to, and a judge, holder, and composition are all
readers of the same facts. The reference tick producer is **not** in this
namespace (D3); it lives in the test tree as host driver code.

### The facts

The two dispatch keys and the fact table are the contract's, reproduced here
because every invariant below cites them:

| Fact     | `:dao.lease/status`   | Author  | Identity carried                                      | Also required                                                                             |
|----------|-----------------------|---------|--------------------------------------------------------|--------------------------------------------------------------------------------------------|
| Proposal | `:dao.lease/proposed` | holder  | `:dao.lease/proposal`                                  | `:dao.lease/subject`; `:dao.lease/duration` optional, an ask                              |
| Grant    | `:dao.lease/accepted` | grantor | `:dao.lease/lease`, plus `:dao.lease/proposal` if answering | `:dao.lease/subject`, `:dao.lease/holder`, `:dao.lease/duration`; `:dao.lease/max` optional |
| Refusal  | `:dao.lease/rejected` | grantor | `:dao.lease/proposal`                                  | —                                                                                          |
| Release  | `:dao.lease/released` | holder  | `:dao.lease/lease`                                     | —                                                                                          |
| Reclaim  | `:dao.lease/lapsed`   | grantor | `:dao.lease/lease`                                     | `:dao.lease/cause` — `:silence`, `:release`, `:cap`, or `:policy`                          |

| Evidence | `:dao.lease/event`  | Author  | Identity carried     | Also required     |
|----------|---------------------|---------|----------------------|-------------------|
| Renewal  | `:dao.lease/renewal`| holder  | `:dao.lease/lease`   | —                 |
| Tick     | `:dao.lease/tick`   | adapter | —                    | `:dao.lease/reading` |

Keys: `:dao.lease/lease`, `:dao.lease/proposal`, `:dao.lease/subject`,
`:dao.lease/holder`, `:dao.lease/duration`, `:dao.lease/max`,
`:dao.lease/cause`, `:dao.lease/reading`.

---

## 2. The invariants

Grouped by the test file section that exercises each. Tests are `lease_test`
deftests (Phase 1–3) and `composition_test` deftests (Phase 4), named inline.

### 2.1 Vocabulary, validity, and identity

| # | Invariant | |
|---|---|---|
| V1 | A fact carries exactly one of the two dispatch keys; a fact carrying neither is not a lease fact and is ignored by a reader; a fact carrying both is defective | `[D]` *Vocabulary* |
| V2 | A `:dao.lease/duration`, `:dao.lease/max`, and `:dao.lease/reading` are each a single-entry map from a unit keyword to a positive integer; a `:dao.lease/cause` is one of `:silence` `:release` `:cap` `:policy`; tolerance (a composition value, not a fact key) is the same shape and may be zero | `[D]` *Keys*, *Validity* |
| V3 | Unit keywords and their ratios are a composition-wide decision fixed once and shared by grantor, holders, and judge; comparison normalizes to the finer of two units and is strict — an interval exactly equal to its bound has not passed it | `[D]` *Units* |
| V4 | **Structural** defect, decided from a fact alone: a fact is defective when it omits a key its status/event requires, carries a status/event outside the tables, carries a duration/cap/reading not of the required shape, or carries a lease id on a proposal. A structurally defective fact establishes nothing | `[D]` *Validity* |
| V5 | `defective?` is pure and host-neutral: given a fact alone it returns a truth value without consulting any stream, clock, or ambient state. Purity is pinned by a test that passes facts through a validator wired to a global atom and a clock and asserts neither is read | `[T]` — pins purity |
| V6 | **Identity minting**: `:dao.lease/lease` is minted by the grantor and never reused within that grantor; `:dao.lease/proposal` is minted by the holder and echoed by the grant or refusal answering it; a proposal carries no lease id | `[D]` *Keys* |
| V7 | **Admissibility** is history-dependent and judge-local, not a property of a fact alone: a fact is inadmissible when it answers one proposal with both `:accepted` and `:rejected`, repeats `:accepted`/`:released`/`:lapsed` for one lease, or carries a reading older than one already observed. The judge's drain decides admissibility against its ledger; `defective?` (V4) does not | `[D]` *Validity*, `[D]` *new* — pins *where* each check runs |

### 2.2 The judge — ledger and pass

| # | Invariant | |
|---|---|---|
| J1 | Only grantor-authored facts establish terms; a holder fact establishes no term, and a delayed holder renewal still counts as evidence | `[D]` *Authority* |
| J2 | A renewal counts only when its attributed author is the lease's holder; a renewal from any other author is a fact about that author and silence is measured as though it had not arrived | `[D]` *Authority* |
| J3 | At most one of `:accepted` or `:rejected` answers a proposal; a proposal creates no state; nothing exists until `:accepted` | `[D]` *Authority* |
| J4 | A lease **enters** the ledger when the grantor grants it, seeded from that act, and **leaves** once its reclaim has succeeded *and* its `:lapsed` record has been appended with `:dao.stream/ok`. Until both, it remains | `[D]` *The judge — Ledger* |
| J5 | The ledger records, per live lease: its terms (subject, holder, duration, cap), its tenure start (the reading the grant was seeded at, unchanged by renewal), its last relevant observation, its evidence state (`known`/`unknown` with resumed reading), and its reclaim state (`live`/`pending` with cause and success) | `[D]` *Ledger* |
| J6 | The pass classifies nothing until a first tick has been observed; at each pass, in order: (1) drain every wired tick cursor, the newest reading (or the newest previously observed) is *now*; (2) drain every wired lease-fact cursor, stamping each fact with *now*, applying renewals/releases/gaps; (3) answer and grant, seeded and stamped with *now*; (4) classify; (5) reclaim each due lease, marking it `pending` with its cause first; (6) record `:lapsed`, and on `:dao.stream/ok` the lease leaves the ledger | `[D]` *The pass* |
| J7 | Eligibility precedes policy. A renewal is counted (and the lease's last relevant observation advanced) whether or not any other condition ends the lease in the same pass; a `:policy` cause may not be implemented by declining to count evidence received. Then a lease is due when, first-holding-in-order: already `pending` (keeps its cause); a valid `:released` observed, `:release`; tenure start further back than `:dao.lease/max`, `:cap`; the grantor's policy ends it, `:policy`; interval since last relevant observation exceeds duration + tolerance, `:silence`. A lease whose evidence is `unknown` is due for `:silence` only once a full duration has passed since its resumed reading | `[D]` *Authority*, *The pass* §4 |
| J8 | The reclaim procedure is idempotent and reports success. The record follows the act and never precedes it: mark `pending` with the cause, perform the reclaim, append `:lapsed`, and remove the lease from the ledger only on `:dao.stream/ok`; a reclaim that fails or does not report leaves the lease `pending` and unrecorded, and a non-`ok` `:lapsed` append leaves it `pending` for the next pass (reached through the first clause of classification). The absence of a `:lapsed` fact is never evidence of tenure | `[D]` *The pass* §5-6, *The judge* |
| J9 | Incomplete evidence: absence is evidence only over a window the judge observed; a `:dao.stream/gap` on a lease-fact medium marks every lease on that medium `unknown` with *now* as its resumed reading; a surviving older renewal is **not** inferred to be the newest; a lease recovered from a persisted ledger is `unknown`; conditions other than `:silence` apply to an `unknown` lease unchanged. A **tick-cursor gap is not a lease-evidence gap**: a late pass is not a wrong pass, and it does not mark leases `unknown` | `[D]` *Judging with incomplete evidence*, *Time* |
| J10 | A drain ending in `:dao.stream/end` retires that cursor for later passes; a drain ending in `:dao.stream/transport-error` ends the pass before classification. `:dao.stream/cursor-mismatch` and `:dao.stream/invalid-cursor` are **pass-aborting defects** — they end the pass before classification, and are never treated as a completed drain (that would manufacture absence evidence) | `[D]` *The pass*, `[D]` *new* — pins the unclassified reader outcomes |
| J11 | A grantor that has lost its ledger reclaims and re-grants: for each resource it still possesses it reclaims first, then grants afresh; the reclaim ends the prior tenure, a new grant alone does not | `[D]` *Restart* |
| J12 | A grantor owes no answer to a proposal; a holder's wait on an unanswered proposal is bounded by nothing in this vocabulary, and no lease state is created by the wait | `[D]` *Authority* |
| J13 | A grantor may grant unsolicited, with no proposal; the holder is the party the grant is delivered to, and the grant carries `:dao.lease/subject` and `:dao.lease/holder` outright so a reader of the grant alone knows what was granted to whom | `[D]` *Authority* |
| J14 | A composition persisting a ledger across a restart keeps its readings comparable across it: the tick stream's readings never decrease, and a recovered ledger resumes with the same unit table and reading basis | `[D]` *Time* |
| J15 | `:dao.lease/max` binds within one ledger lifetime: tenure start is set once at the grant and no gap or renewal moves it, so the cap bounds tenure even across gaps and restarts of evidence | `[D]` *Limits* |
| J16 | The pass is bounded: each cursor is drained up to a composition-declared `:drain-budget` of elements per pass, and a cursor still yielding `:dao.stream/ok` after the budget leaves the remainder to the next pass. "Late, not wrong" is unconditional for ticks; a fact cursor truncated this pass suppresses `:silence` for its medium's leases (and unregistered leases while any medium is truncated) until a completed drain — suppression never touches `:release`, `:cap`, `:policy` or pending retries. This resolves the contract's demand to "drain to blocked" against DaoStream's lack of a snapshot tail | `[D]` *new* — the quiescence resolution, D7; suppression added by review round r2 |

### 2.3 The holder

| # | Invariant | |
|---|---|---|
| H1 | A holder observes its grant before acting and establishes the grantor authored it by the composition's attribution; a holder that has not observed a grantor-authored grant holds nothing, and a forged/non-grantor `:accepted` establishes nothing | `[D]` *The holder* |
| H2 | A holder renews at **strictly less than half** the duration, measured against ticks on its own tick stream; a renewal is an append returning `:dao.stream/ok`, and any other outcome does not advance the holder's bound. The holder's renewal interval is a composition value strictly below half the duration, so a successful next renewal is guaranteed before half the duration elapses; an interval at or above half already violates the sizing relation | `[D]` *The holder*, *Sizing* |
| H3 | A holder stops acting at the bound — the earlier of duration since the later of its last renewal and its observed grant, and the cap the grant carries — whether or not anything has been heard | `[D]` *The holder* |
| H4 | A holder releases when done — appending `:released` — and stops its own activity; it does not reclaim and does not author `:lapsed`. The grantor still performs the reclaim and records it | `[D]` *The holder* |
| H5 | The holder's bound bounds attention, not access: a holder needing exclusion obtains it from the resource, not from this vocabulary | `[D]` *The holder* |

### 2.4 Composition

| # | Invariant | |
|---|---|---|
| C1 | A composition that grants leases wires: a judging interpreter and a runtime driving it at a declared cadence (a maximum interval between *completed* passes); a tick stream for the judge and one per holder; a tolerance; an attribution resolver bound to a source; a reclaim procedure per subject; a stream the grantor writes grants and `:lapsed` to, and the medium each recipient reads its facts from; media that retain or declare evict-oldest | `[D]` *Composition duties* |
| C2 | `make-*` validates an **explicit medium declaration** in its config — `{:retention :evict-oldest|:complete, :capacity n, :value-domain :portable-values|:host-values}` — not the handle (a handle exposes surfaces, not retention). One handed no resolver, a resolver incompatible with the declared medium, or a medium declared neither retain nor evict-oldest is refused at assembly: the constructor throws before any stream exists. `fn?` establishes presence; the declaration establishes compatibility | `[D]` *Composition duties*, `[T]` pins the throw |
| C3 | Media declared neither retaining nor evict-oldest are refused at assembly | `[D]` *Composition duties* |
| C4 | For durable resources, three things the vocabulary does not supply — a durable judge, a rule for which incarnation may reclaim, and fencing — are required. `make-*` takes `:durable?` (default false) plus the three prerequisites; a `:durable?` config missing any of the three throws at assembly, and a non-durable config is declared process-scoped on the returned value — an unsettled composition *cannot* claim durable use | `[D]` *Composition duties*, `[D]` *new* — pins the process-scoped fallback |
| C5 | No code in `src/cljc/dao/lease.cljc` reads a host clock, holds a callback, installs a timer, or consults a registry; time reaches lease code only as data on a tick cursor. A timer, if any, lives in the host driver outside the namespace, and the reference tick producer is a step/deposit function, not a timer | `[D]` *Prohibitions*, *Time*; `[T]` pins the absence by grep over the whole file (§7) |
| C6 | A lease does not extend retention, defer eviction, or gate history, and no `dao.stream` operation consults a lease | `[D]` *Prohibitions* — asserted by construction, since `dao.lease` imports `dao.stream` and never the reverse |
| C7 | No notification marks a lapse; no absolute time appears in any fact; a reclaim frees the resource, never the record; a holder renews from its own control flow and no machinery renews on its behalf | `[D]` *Prohibitions* |
| C8 | Carriage is delivery, not a second authoring. `:lapsed` does not cross a boundary — it is the grantor's record on the grantor's stream — and a remote holder observes a reclaim as the resource event (e.g. an ordinary `:ws/closed`), not as a `:lapsed` fact | `[D]` *Carriage* |

### 2.5 Sizing and limits (recorded guidance)

These bind compositions, not the code; they are recorded here so §7's accounting
is true, and their values belong to compositions.

| # | Relation / limit | |
|---|---|---|
| S1 | The granted duration exceeds twice the holder's renewal interval | `[D]` *Sizing* |
| S2 | The judge's tolerance covers expected flight time and the rate skew between its tick stream and the holder's | `[D]` *Sizing* |
| S3 | A medium's retention window exceeds the judge's lag (cadence plus drain time) | `[D]` *Sizing* |
| S4 | A false lapse is possible, and reclamation is bounded by duration + tolerance + cadence + the reclaim's own cost; a medium that gaps more often than one duration never completes an observation window and leaks continuously, and per-attachment media are the isolation | `[D]` *Limits* |

---

## 3. Decisions

### D1 — facts are plain maps on `dao.stream`, with the contradiction flagged, not settled

Correction 1. This plan builds plain maps on the `dao.stream` reader surface —
the only carrier `dao.lease.md` names as operative — and records the unresolved
`dao.stream.md` contradiction in §6 together with the one-sentence amendment.
It does **not** edit `dao.stream.md`, and it does not assert the plain-map choice
as a settled resolution of two operative contracts.

### D2 — the judge is one effectful, state-threaded step, driven by the composition's runtime

The pass is a single host-neutral step function `judge-step [judge] -> judge`
threading the ledger, in the `forward-step` shape (`forward.cljc:67-154`): it
owns no scheduler, callback, registry, or *mutable* state of its own. It is
**not pure** — it reads transport handles via `stream/next`, invokes the
resource-reclaim effect, and appends `:lapsed`. *Now* is the newest reading
drained from the tick cursor this step, never a clock call. The composition's
driver calls the step at its declared cadence, exactly as `serving/step!` is
driven by the daemon thread at `dao/jing/remote.cljc:981-988`. This is the
contract's "the judge is composed inside the boundary that possesses the resource
and reclaims by acting on what it itself holds": the step *is* the possessing
interpreter performing its own act, and the step's output is the
reclaimed-and-recorded ledger, not a verdict for another layer to execute.

The ledger is **not rebuildable from any stream** (`dao.lease.md:120`): renewals
carry no time and observation times are judge-local, so the ledger is threaded
state the composition either persists (as a stream its owner writes and reads —
out of this plan's build scope, see §6) or recovers by the Restart rule.

### D3 — the tick stream is composition-supplied; the reference producer is a step function, not a timer

Correction 2. The contract requires the composition to wire a tick stream and
binds the judge and holder to consume it, not to produce it. A tick's author is
the *adapter*. This plan therefore:

- binds the judge and holder to an **injected tick cursor** — they read
  `:dao.lease/reading` from it and never call a clock;
- provides the reference producer as a **step/deposit function** — a pure
  `(tick-fact reading)` constructor in `dao.lease` (the vocabulary's `tick`), plus
  a host **driver** in the test tree that computes a monotonic reading and
  `append!`s it at a cadence. The driver (and its timer, on hosts that use one)
  is outside `dao.lease.cljc` and holds no callback *inside* the contract
  namespace. It is test/example policy, not contract.

This keeps the host clock and every timer out of `dao.lease` (correction 2's
point, and the Prohibitions) while giving the tests a scriptable, deterministic
tick that needs no wall clock at all: the test scripts the tick stream by hand.

### D4 — the attribution resolver is bound to a source; the reclaim procedure is injected and idempotent

`dao.stream` supplies no authorship (`dao.lease.rationale.md:224-245`), and
`stream/next` returns only value and successor cursor — attachment identity is
returned at attach time, not embedded in each value. A plain fact alone cannot
reveal which reader or attachment supplied it. The resolver is therefore
**`(resolver source fact) -> author`**, where `source` is the cursor/medium
binding the fact was read from — the composition binds the per-author medium,
envelope key, or attachment identity to that source. The reclaim procedure is an
injected `(reclaim subject) -> success?` (idempotent, reporting success), one per
subject. Both are validated by the `make-*` constructor and **throw `ex-info` at
assembly** when missing or incompatible — the `make-serving` / `make-attacher`
idiom (`serving.cljc:104-129`, `ws.cljc:375-405`). The source bindings in this
codebase are the three the design names: an envelope key (`:ws/attachment`,
`ws.cljc:162-169`), per-author media identity (the `:sessions` key /
`:traffic-reader`, `serving.cljc:60-75`), or a transport's attachment identity
(`:dao.stream/attachment`, `ws.cljc:402`). The reclaim procedure for a served
connection is `close-session!`'s shape (`serving.cljc:179-183`); for a forwarder
pause it is the driver ceasing to call the forward step and closing the session;
for a shared-work claim it is a composition-supplied procedure following the same
`call-close!` idempotency pattern (`serving.cljc:38-43`).

### D5 — record-after-act is explicit sequencing in `judge-step`, not `observe/step`

`observe/step` performs one source read and one writer-shaped effect whose effect
must answer a valid `append!` outcome (`observe.cljc:58-105`); a boolean
`(reclaim subject)` is not such an outcome, and `observe/step` has no operation
for iterating the ledger, storing `pending` before reclaim, or sequencing a
reclaim followed by a separate append. The record-after-act law is realized by
**explicit sequencing in `judge-step` step 6**: mark the lease `pending` with its
classified cause, invoke the injected reclaim and record its success, append
`:lapsed`, and remove the lease from the ledger only on `:dao.stream/ok`; a
failed or unreported reclaim, or a non-`ok` `:lapsed` append, leaves the lease
`pending` and unrecorded for the next pass (J8). The law stands; the mechanism is
direct append-outcome handling, not `observe/step`.

### D6 — structural validity is pure; admissibility is a judge-local gate

`defective?` (V4) decides only what a fact alone can decide — missing keys,
out-of-table status, malformed duration/cap/reading, a lease id on a proposal —
and is pure (V5). The history-dependent rules — both answers, repeated
`:accepted`/`:released`/`:lapsed`, a stale reading — are `admissible?`, decided
by the judge's drain against its ledger and seen readings (V7). The drain stamps
a fact, drops a structurally defective one, and applies an admissible one; the
gap/release/answer branches never see a fact that failed either gate. This keeps
the ledger's invariant (every entry was valid and admissible) and makes each gate
independently testable.

### D7 — the pass drains to a budget, not to `blocked`

Finding 8's hazard is real: `stream/next` is non-blocking but a concurrently
written stream may keep answering `ok`, and DaoStream exposes no snapshot tail.
`forward-step` bounds this with a batch budget (`forward.cljc:67-89,102-121`);
the judge adopts the same discipline. `judge-step` drains each cursor up to a
composition-declared `:drain-budget` (default, say, 256) per pass, and a cursor
still yielding `ok` after the budget leaves the remainder to the next pass.
"Late, not wrong" holds unconditionally for tick cursors (ticks are monotonic)
but only conditionally for fact cursors: a budget-truncated fact drain has not
completed its observation window, so **a pass in which any fact cursor was
truncated suppresses the `:silence` classification for the leases registered on
that truncated medium — and for leases registered on no medium while any medium
is truncated — until a later pass drains it to `blocked`, `end`, or retirement.**
`:release`, `:cap`, `:policy` and pending retries are never suppressed. This
amends the reconciliation's J16 wording (review round r2, finding P1-2): the
drain budget without suppression would manufacture absence evidence, which the
contract forbids. Stated as an invariant (J16) and tests, not left as an
unbounded loop hidden in the step.

---

## 4. Phases

Each phase is behaviour-neutral to everything before it: nothing existing in
`dao.*` changes, and the three host lanes stay green by construction. The only
host-specific code is the tick *driver* in the test tree, which is new and
additive.

### 4.1 Phase 1 — the vocabulary

`src/cljc/dao/lease.cljc`, `test/dao/lease_test.cljc`. Pure, all three hosts, no
stream, no clock.

**Build**

- The unit keywords and ratios as a single shared map (a placeholder default, e.g.
  `{:ms 1 :s 1000}`, but the *value* is composition-owned — the contract says
  units are "a composition's interoperability decision"); `normalize` to the
  finer unit; `compare-durations` strict (`=` is "not yet passed"); `add-duration`
  and `exceeds?`.
- `duration`, `cap`, `reading`, `tolerance`, `cause` predicates (single-entry
  `{unit positive-int}`, cause in the four-value set; tolerance may be zero).
- Fact constructors `proposal`, `grant`, `refusal`, `release`, `lapsed`,
  `renewal`, `tick` — each returns the map the vocabulary table names, and each
  rejects (throws, as a host-assembly defect, not a stream outcome) a call that
  would produce a structurally defective fact (V1, V2, V4).
- `defective?` (V4, structural only) and `admissible?`'s **pure half** (the
  stale-reading predicate against an explicitly supplied prior reading; the
  both-answers and repeat rules run against the judge's ledger in Phase 2, V7).

**Prove** — new `lease_test` deftests, unguarded, all hosts:

1. Constructors emit the exact map the table names; a constructor call that would
   omit a required key, carry a bad duration/cause/cap/reading, or put a lease id
   on a proposal throws.
2. `defective?` returns the full *structural* defect list on a corpus (missing
   key, out-of-table status, multi-entry duration, non-positive cap/reading, a
   unit not in the composition's fixed table, lease-id-on-proposal) and `false`
   on the well-formed corpus. The two dispatch-key rules (V1) are pinned in both
   directions: a fact with neither key is "not a lease fact", a fact with both is
   defective.
3. `defective?` purity (V5) is pinned adversarially: the validator is built with
   access to a global atom and a clock-read fn, and the test asserts neither is
   consulted for the whole corpus — a validator that reads either fails.
4. Unit arithmetic: `normalize` to the finer unit; strict comparison — an
   interval exactly equal to its bound reports "not yet passed"; agreement that a
   composition's single unit table is shared (S1's `duration > 2 × renewal`
   relation, stated not enforced).
5. `tolerance` permits zero and rejects a non-`{unit positive-int}` shape (V2).

Confirm `Testing dao.lease-test` appears in the Node (cljs) output.

### 4.2 Phase 2 — the judge

Same file, judge section; `lease_test` judge deftests. Effectful step over
threaded state; scripted ticks and facts over `dao.stream.ringbuffer` cursors and
a scripted fake reader for the defect outcomes; no clock.

**Build**

- The ledger entry and `judge-step` implementing the six-step pass (J4–J16):
  `drain-ticks` (step 1, bounded by `:drain-budget`, D7), `drain-facts` with
  stamping and the `defective?`/`admissible?` gates (step 2, D6, V7), `answer-
  and-grant` (step 3), `classify` with eligibility-before-policy (step 4, J7),
  `reclaim` marking `pending` first then the injected procedure (step 5), `record`
  with explicit sequencing (step 6, D5). Cursor retirement on `:dao.stream/end`,
  pass-abort on `:dao.stream/transport-error`/`cursor-mismatch`/`invalid-cursor`
  (J10).
- `gap->unknown` and the `unknown`-silence rule (J9); `restart` (J11).

**Prove** — scripted, no host clock; the test hand-appends tick and fact maps and
hands the judge its cursors; a scripted fake reader yields `transport-error`,
`cursor-mismatch`, and `invalid-cursor` for J10 (the ring buffer cannot produce
them).

1. **Seed-from-grant, entered through the real grant path** (J4, J6, finding 13):
   a grant authored by the grantor and drained this pass is stamped with the
   pass-wide *now*, and the test asserts both **tenure start** and **last relevant
   observation** equal that single stamp — not a pre-seeded reading. Then ticks
   advance and a pass past duration+tolerance reclaims `:silence` and records
   `:lapsed` (the "never-renewed grant" leak).
2. **Eligibility before policy** (J7, finding 6): a pass in which a valid renewal
   arrives *and* the policy predicate ends the lease records the renewal (last
   relevant observation advanced) even though `:policy` wins the same pass.
3. **Attribution** (J1, J2): a non-grantor `:accepted` establishes nothing; a
   delayed eligible renewal still counts; a renewal from a non-holder author is
   ignored and silence measured as though it never arrived.
4. **Proposal state** (J3, J12, J13): a proposal creates no ledger entry; an
   unanswered proposal leaves no state and no bound; an unsolicited grant (no
   proposal) seeds a lease whose subject and holder come from the grant alone.
5. **Cause precedence** (J7, finding 20): when `release`, `cap`, `policy`, and
   `silence` coincide, the first-holding cause wins; a `pending` lease keeps its
   original cause across retries (not reclassified).
6. **`:released`**, **`:cap`**, **`:policy`** each reclaim with their cause
   (J7); `:cap` ends tenure past `:dao.lease/max` regardless of renewals (J15).
7. **Incomplete evidence** (J9, finding 7): a `:dao.stream/gap` marks the leases
   on *that* medium `unknown` (not every medium); an `unknown` lease lapses for
   `:silence` only a full duration after its resumed reading while `:cap` and
   `:release` still apply; a surviving older renewal is not inferred to be the
   newest; a tick-cursor gap does **not** mark leases `unknown`.
8. **Reclaim-before-record** (J8, finding 20): a reclaim returning false leaves
   the lease `pending` and unrecorded; a second pass with a succeeding procedure
   appends `:lapsed` and removes the lease; the record is never observed before
   the act; the cause is not reclassified on retry.
9. **Cursor outcomes** (J10, finding 19): `:dao.stream/end` retires only that
   cursor (later passes do not call it again); `:dao.stream/transport-error`,
   `:dao.stream/cursor-mismatch`, and `:dao.stream/invalid-cursor` each abort the
   pass *before* classification, and leases that would have been reclaimed in
   that pass are **not** (no partial reclaim during an aborted pass).
10. **Drain budget** (J16, finding 8): a fact cursor that yields `ok` for
    `:drain-budget` elements without `blocked` leaves the remainder for the next
    pass, and the pass still completes with the newest reading drained so far.
11. `restart` reclaims-then-regrants with fresh identities (J11).

### 4.3 Phase 3 — the holder

Same file, holder section; `lease_test` holder deftests.

**Build**

- `observe-grant` (H1: gate on having observed a *grantor-authored* grant);
  `renewal-interval` (H2: a composition value strictly below half the duration)
  and `due-to-renew?` (H2: true when the interval since the last
  renewal/observation reaches `renewal-interval`); `at-bound?` (H3: the earlier
  of duration-since-last-activity and the cap); `release` (H4). Each is a pure
  predicate/constructor the holder's own control flow calls; none installs a
  timer or renews on the holder's behalf.

**Prove** — scripted ticks and facts.

1. **Attribution gate** (H1, finding 15): a forged/non-grantor `:accepted`
   establishes nothing — `observe-grant` reports "no grant observed"; a genuine
   grantor-authored grant satisfies it.
2. **Strict renewal** (H2, finding 14): `renewal-interval` is strictly below half
   the duration; `due-to-renew?` is false before the grant is observed, becomes
   true at `renewal-interval` (guaranteeing a successful next renewal before half
   the duration), and an interval at or above half is treated as already
   violating the sizing relation — the holder does not wait until the deadline
   to renew.
3. `at-bound?` fires at the cap even with fresh renewals, and at
   duration-since-last-activity when the cap is absent or larger (H3).
4. A renewal append returning non-`ok` does not advance the holder's bound
   (H2, finding 20).
5. **Release** (H4, finding 15): a holder that emits `:released` stops its own
   activity, authors no `:lapsed`, and performs no reclaim; the grantor still
   reclaims and records.

### 4.4 Phase 4 — composition and use-case sketches

`src/cljc/dao/lease.cljc` (composition section) and
`test/dao/lease_composition_test.cljc`.

**Build**

- `make-judge` and `make-holder` constructors: take a config map with the
  injected seams — tick cursor(s), fact cursor(s) and their **explicit medium
  declarations**, the source-bound attribution resolver, reclaim procedures,
  tolerance, units, `:drain-budget`, `:durable?` and the three durable
  prerequisites — and **throw `ex-info` at assembly** on any missing or
  incompatible seam (C2, C3, C4, D4). Return a step-bearing value whose
  process-scoped/durable status is declared on it (C4), driven by the composition
  at its cadence (C1).
- The reference tick producer, **not** in this namespace: the vocabulary's `tick`
  constructor plus a host driver in the test tree (`test/dao/lease_test.clj` for
  the JVM lane) that computes a monotonic reading and `append!`s a tick fact at a
  cadence; the driver holds the timer, and nothing in `dao.lease.cljc` does (D3).
- Three use-case **sketches**, documented and lightly exercised, not built as
  products: the forwarder pause (the driver ceasing to advance a `serving`
  session's forward step, release = `close-session!`); the served connection
  lifetime (reclaim = `close-session!` on `:socket-handle`, attribution bound to
  `:ws/attachment`); the shared-work claim (a composition-supplied reclaim
  procedure following `call-close!`'s idempotency). These pin C1's wiring in a
  runnable form without claiming any of the three as a finished component.

**Prove**

1. **Refusal matrix** (C2, C3, C4, D4, finding 16): `make-judge` throws at
   assembly — before any stream exists — on each of: a missing runtime/cadence
   declaration; a missing judge or holder tick stream; a missing or invalid
   tolerance; a missing holder-side resolver; an *incompatible* resolver (not
   merely absent); a missing grant/`:lapsed` writer; a missing recipient fact
   medium; a medium declared neither retain nor evict-oldest; and a `:durable?`
   config missing any of the three durable prerequisites.
2. **Process-scoped fallback** (C4, finding 17): a non-durable config returns a
   value whose declared scope is process-scoped, and a `:durable?` config without
   all three prerequisites does not construct at all.
3. An end-to-end scripted grant→renew→lapse cycle through `make-judge`, with a
   hand-turned tick stream, reclaims and records exactly once, and a `:lapsed`
   append answering `full`/`closed`/`transport-error` leaves the lease `pending`
   (J8, finding 20).
4. The reference tick driver (JVM lane) appends strictly non-decreasing readings
   and stops on demand; asserting the `dao.lease` namespace itself contains no
   timer/clock call is the §7 grep, not a runtime test.

---

## 5. Host matrix

State as of the Phase 1+2 build (r4): **Phases 1 and 2 are built and tested;
Phases 3–5 items below are owed and listed in their target state.**

| | clj | cljs (Node) | cljd |
|---|---|---|---|
| Vocabulary and judge (Phases 1–2) | **built, tested** | **built, tested** | **built, tested** |
| Holder (Phase 3) | owed — unbuilt | owed — unbuilt | owed — unbuilt |
| `make-judge` / `make-holder` constructors (Phase 4) | owed — unbuilt | owed — unbuilt | owed — unbuilt |
| Reference tick **driver** (test-tree host policy, Phase 4) | owed — `Thread`/sleep | owed — `js/setInterval` | owed — `async/Timer.periodic` |
| Use-case sketches (served connection, forwarder; Phase 4) | owed — unbuilt | n/a (host-specific transport) | n/a |

The portable core is `.cljc` and has no host branch; only the tick *driver* (in
the test tree, not `dao.lease.cljc`) and the served-connection/forwarder sketches
touch a host. The three hosts' tick sources already exist as patterns to mirror
(`yin/repl.cljc:192-396`), not to reuse.

---

## 6. Boundary — built here, and what is left owing

Built here (Phase 1+2): the `dao.lease` vocabulary, judge, and their tests.
Owed (Phases 3–4, unbuilt): the holder, the composition constructors and
their tests, the reference tick driver, and the three use-case sketches.

| owed | by | where recorded |
|---|---|---|
| **Unresolved contract**: the `dao.stream.md:752-755` / `dao.lease.md:14` fact-carrier contradiction, and the one-sentence amendment in §0.1 | the orchestrator (this plan must not edit `dao.stream.md`) | §0.1 |
| A persisted ledger for a judge that must survive restart (a stream its owner writes and reads, or the Restart rule) | a composition that needs durability, not this plan | `dao.lease.md` *Restart*; this plan binds the interface, not a persistence transport |
| The three durable-resource prerequisites — a durable judge, an incarnation rule, fencing | the resource's own plan | `dao.lease.md` *Composition duties* |
| The wire form of a pause vocabulary between peers | `dao.stream.ws.md`'s close-code design gate | `dao.lease.md` *Carriage* ("distinguishing reclaim on the wire belongs to `dao.stream.ws.md`'s deferred close-code design") |
| The three use cases as finished components (forwarder pause, served-connection lifetime, shared-work claim) | their owners, as consumers of this vocabulary | `dao.lease.md` *Out of scope* ("this contract gates nothing, and the domain decides") |
| A generic unit table shared across compositions | the first composition that needs it | `dao.lease.md` *Units* ("a composition's interoperability decision") |
| Legitimate-use growth of the judge's `:seen`/`:answered` maps in a long-lived judge | the composition that persists the ledger | `dao.lease.md` *Ledger* ("the ledger is not rebuildable from any stream"); pruned only with a persistence design |
| A recovery helper that restores `unknown` entries (with `:resumed` readings) from a persisted ledger | the composition that persists the ledger | `dao.lease.md` *Judging with incomplete evidence*, *Restart* |
| The fact-magnitude bound: a fact's raw magnitude ≤ 2⁵² and, against a unit table, ≤ `quot 2⁵² unit-magnitude` (division-checked), so every product and sum stays exact on all three hosts and no reading stream ever ages out of validity | each composition's unit table; enforced structurally and at `initial-judge` assembly | `dao.lease.md` *Units*; supersedes the r2 round's 10⁶ bound, which gave the judge a finite lifetime (review round r2 confirmation, N1) |
| Whether the `unknown`-evidence silence rule should include tolerance (a gapped lease can currently be reclaimed earlier than an uninterrupted one) | the contract owner | `dao.lease.md` *The pass* §4 (literal text implemented; question raised by review round r2) |
| Any truncated or gapped medium affects every never-renewed lease (suppression or fresh `unknown`), including an attacker's own medium; bounded because `:cap` still applies and it errs toward the holder | the Phase 4 composition constructors ("the grant declares its medium") | review round r2 confirmation, N6 |
| A renewal dropped because the attribution resolver throws counts against the holder — a transient resolver failure can become a false lapse. Phase 4 should consider suppressing `:silence` for that medium in that pass, the same way a truncated drain does | the Phase 4 composition constructors | review round r3 gate, R5 |
| Phase 1+2's `initial-judge` defers assembly validation of medium declarations to `make-*` (Phase 4); until those constructors exist, a composition is validated only by review | the Phase 4 constructors | `dao.lease.md` *Composition duties* |
| Transfer of a lease between holders, and delegated renewal — both denied by the contract | never (recorded, not deferred) | `dao.lease.md` *Out of scope* |

Explicitly not planned: `dao.space`, `dao.jing`, `yin.vm.*`, any transport, and
any change to `dao.stream` or `dao.stream.ws`.

---

## 7. End condition

- `dao.lease` requires only `dao.stream` (and `dao.stream.ringbuffer` for test
  media); no namespace requires `dao.lease`, and `dao.stream` is byte-for-byte
  unchanged (C6 holds by construction).
- Every rule of `dao.lease.md` — Vocabulary, Authority, Prohibitions, Time, the
  judge (ledger, pass, incomplete evidence, restart), the holder, Composition
  duties, Sizing, Limits, Carriage, Out of scope — is accounted for by an
  invariant (V1–V7, J1–J16, H1–H5, C1–C8, S1–S4), a decision (D1–D7), a phase, or
  an explicit deferral/denial in §6.
- `bb test:clj`, `bb test:cljs` (confirm `Testing dao.lease-test` in the Node
  output), and `bb test:cljd` are green; the demo still compiles.
- Greps at zero over the whole of `src/cljc/dao/lease.cljc` (not a scoped subset):
  no `System/currentTimeMillis`, `js/Date.now`, `DateTime.now`, `setInterval`,
  `Timer.periodic`, `Thread/sleep`, `addEventListener`, or any timer/callback
  construct (C5 — the timer lives only in the host driver in the test tree).
- Nothing is owed to a later plan except what §6 names, and nothing in this
  document changes the two contracts it is subordinate to.

---

## Revision — 2026-09-19 reconciliation

Disposition of the twenty findings of the independent review (gpt-5.6-sol,
verdict "unsound"), applied against the contract text:

| # | Finding | Disposition |
|---|---|---|
| 1 | D1 contract contradiction | Applied — §0.1 states the contradiction, proposes the one-sentence `dao.stream.md` amendment, builds plain maps, flags unresolved-contract in §6 |
| 2 | Reference tick source installs a timer | Applied — the producer is a `tick` constructor + host driver in the test tree; no timer/callback in `dao.lease.cljc`; C5 grep scope widened to the whole file |
| 3 | `judge-step` is not pure | Applied — D2/§1/§4.2 reworded to "effectful, state-threaded interpreter step" |
| 4 | `observe/step` cannot do reclaim→record | Applied — D5 rewritten to explicit `judge-step` sequencing; `observe/step` dropped from the mechanism |
| 5 | §7 accounting incomplete | Applied — added V6–V7, J12–J16, H5, C7–C8, S1–S4; §6 records the out-of-scope and unresolved items |
| 6 | J7 policy can suppress evidence | Applied — J7 reordered to eligibility-before-policy, with a test (4.2 #2) |
| 7 | J9 incomplete-evidence semantics | Applied — J9 completes absence-over-window, no-inference-of-newest, and tick-gap≠fact-gap; tests 4.2 #7 |
| 8 | Drain-to-blocked unbounded | Applied — D7 + J16 add a `:drain-budget`; test 4.2 #10 |
| 9 | Resolver signature lacks source | Applied — D4 widens to `(resolver source fact)`; C2 binds the source |
| 10 | Assembly compatibility uninspectable | Applied — C2 requires an explicit, validated medium declaration in config, not handle introspection |
| 11 | Phase 1 tests incomplete | Applied — corpus extended (neither/both keys, non-positive cap/reading, invalid tolerance incl. zero, foreign unit, unit-table agreement) + adversarial purity test |
| 12 | Phase 2 leaves J invariants untested | Applied — 4.2 enumerates attribution, proposal-state, precedence, all cursor outcomes, seed-through-grant, budget, restart; a scripted fake reader supplies the defect outcomes |
| 13 | Seed-from-grant too weak | Applied — 4.2 #1 enters through the real grant path and asserts tenure start and last-observation equal the pass-wide stamp |
| 14 | Renewal rule inverted | Applied — H2 + 4.3 #2 make the interval strictly below half and treat equality as a violation |
| 15 | H1/H4 unproven | Applied — 4.3 #1 (forged grant) and #5 (release emits `:released`, stops, no reclaim) |
| 16 | Phase 4 covers a fraction of C1–C5 | Applied — 4.4 #1 enumerates the full refusal matrix incl. incompatible resolver and durable-without-prerequisites |
| 17 | C4 process-scoped fallback unenforced | Applied — C4 + 4.4 #2 make the fallback a declared, constructor-enforced property |
| 18 | Repeated-fact validity is stateful | Applied — V4 (structural, `defective?`) split from V7 (admissibility, `admissible?` in the drain); D6 states where each runs |
| 19 | Unclassified reader outcomes | Applied — J10 classifies `cursor-mismatch`/`invalid-cursor` as pass-aborting defects; test 4.2 #9 |
| 20 | Tests pass while invariant false | Applied — 4.2/4.3/4.4 harden the named cases (cause precedence, per-medium gap, no reclassification, pass-wide `now`, no partial reclaim on abort, non-`ok` `:lapsed` after `full`/`closed`/`transport-error`) |

No finding is rebutted: the four load-bearing source citations were spot-checked
by the orchestrator and are accurate, and each finding's required change is
consistent with the contract text, so each is applied rather than argued against.
Nothing could not be resolved except the one item this plan is forbidden to
resolve on its own authority — the `dao.stream.md` fact-carrier wording, which is
handed to the orchestrator as §0.1's amendment and remains flagged
unresolved-contract until applied.

## Revision 2 — 2026-09-19 reconciliation with the code review

The Phase 1+2 implementation was adversarially reviewed (claude-fable-5-1,
independent family; 5 P1 / 8 P2 / 5 P3) after this plan's first revision. The
orchestrator verified every P1 and P2 against the source and accepted them;
the implementer fixed all of them in the same session (r2), with
failing-tests-first for the review's prescribed tests. Plan-facing outcomes,
amended here by the orchestrator: J16/D7 gain the truncated-drain silence
suppression (facts, unlike ticks, are not "late, not wrong" when the window is
incomplete); D4's `(resolver source fact)` signature is confirmed as
implemented; §6 gains owed rows for `:seen`/`:answered` growth, a persisted
ledger's recovery helper, the 10⁶ magnitude bound, and the
unknown-silence-tolerance question, which belongs to the contract owner.
