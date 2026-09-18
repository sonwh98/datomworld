# dao.lease — implementation plan

Status: implementation plan, subordinate to [`dao.lease.md`](./dao.lease.md)
(the operative contract) and [`dao.stream.md`](./dao.stream.md) (the stream
contract the facts ride on). Authored by the Architect. Nothing here is in
production, and nothing in this plan changes `dao.stream.md` or any existing
namespace: a lease "adds no operation to any contract and no key to any
DaoStream result map."

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

1. **Lease facts are plain maps, not `dao.space` datoms.** `dao.stream.md`'s
   Composition section says "a pause has to be a lease … whose facts are datoms
   on a medium (`dao.space`)". That is a cross-reference written before the
   lease contract existed. `dao.lease.md:14` is the operative text: "Facts are
   plain data on ordinary streams, classified by two dispatch keys." The
   implementation therefore builds facts as ordinary stream elements — maps
   carrying `:dao.lease/status` or `:dao.lease/event` — over the `dao.stream`
   surface, and composes against no `dao.space` namespace. `dao.space` (index,
   query, transactor) is a tuple-matching interpretation over `dao.jing`, not
   the carrier a lease needs; a lease fact is a single attributed map, not an
   `[e a v t m]` tuple. This plan states the ruling once rather than leaving it
   implicit.

2. **The tick stream is net-new.** `grep -r dao.lease src/` returns nothing, and
   no `:dao.lease/tick` producer exists. The design's Time section requires
   "a tick stream their composition wires" for the judge and one per holder, and
   the evidence table makes the tick's author the *adapter*. There is a rich
   *driver-cadence* vocabulary already (`serving/step! [serving now]`,
   `ws/endpoint-step [endpoint now]`, `yin.repl`'s `poll-loop!`, the daemon
   thread at `dao/jing/remote.cljc:981-988`) and a *time-as-data* convention
   (`now` threaded as an argument, never a clock read inside the step) — but no
   tick *stream*. The plan adds a reference tick source (§4.4) as host policy
   for tests and examples, and states plainly that the contract requires the
   composition to wire its own; the judge and holder consume an injected tick
   cursor, never a clock and never this plan's helper.

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
  and strict interval comparison. Pure functions; no stream, no clock, no state.
- **The judge** — an interpreter owning a private **ledger** of live leases,
  running the six-step **pass**, reclaiming through a composition-supplied
  idempotent procedure, and recording `:lapsed` after the act. A single step
  function the composition's driver calls at a declared cadence; it owns no
  scheduler, callback, or registry (the `forward-step` discipline,
  `dao/stream/forward.cljc:1-6`).
- **The holder** — renewal discipline: observe the grant before acting, renew at
  less than half the duration, stop at the bound, release when done. Helper
  functions a holder's own control flow calls; no machinery renews on a
  holder's behalf.
- **The composition helper** — a `make-*` constructor that wires the injected
  seams (attribution resolver, reclaim procedures, tick cursor, media) and
  **refuses at assembly** when a required seam is missing or incompatible, in
  the `make-serving` / `make-attacher` idiom
  (`serving.cljc:78-141`, `ws.cljc:375-405`).

The split is a single portable namespace, `src/cljc/dao/lease.cljc`, with three
clearly delineated sections (vocabulary, judge, holder) and one host-policy
section for the composition helper and reference tick source. The vocabulary is
deliberately *not* a separate file: it is the "small vocabulary" the rationale
scopes to, and a judge, holder, and composition are all readers of the same
facts; splitting the fact shapes from their interpreters would buy modularity at
the cost of a second namespace with no independent consumer.

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

### 2.1 Vocabulary and validity

| # | Invariant | |
|---|---|---|
| V1 | A fact carries exactly one of the two dispatch keys; a fact carrying neither is not a lease fact and is ignored by a reader; a fact carrying both is defective | `[D]` *Vocabulary* |
| V2 | A `:dao.lease/duration`, `:dao.lease/max`, and `:dao.lease/reading` are each a single-entry map from a unit keyword to a positive integer; a `:dao.lease/cause` is one of `:silence` `:release` `:cap` `:policy`; tolerance (a composition value, not a fact key) is the same shape and may be zero | `[D]` *Keys*, *Validity* |
| V3 | Unit keywords and their ratios are a composition-wide decision fixed once and shared by grantor, holders, and judge; comparison normalizes to the finer of two units and is strict — an interval exactly equal to its bound has not passed it | `[D]` *Units* |
| V4 | A fact is defective, and establishes nothing, when it: omits a key its status/event requires; carries a status/event outside the tables; carries a duration/cap/reading not of the required shape; carries a lease id on a proposal; answers one proposal with both `:accepted` and `:rejected`; repeats `:accepted`, `:released` or `:lapsed` for one lease; or carries a reading older than one already observed. A judge reading a defective fact has read no lease fact | `[D]` *Validity* |
| V5 | `defective?` and the validator are pure and host-neutral: given a fact they return a truth value / validation without consulting any stream, clock, or ambient state | `[T]` — pins purity, not the design's wording |

### 2.2 The judge — ledger and pass

| # | Invariant | |
|---|---|---|
| J1 | Only grantor-authored facts establish terms; a holder fact establishes no term, and a delayed holder renewal still counts as evidence | `[D]` *Authority* |
| J2 | A renewal counts only when its attributed author is the lease's holder; a renewal from any other author is a fact about that author and silence is measured as though it had not arrived | `[D]` *Authority* |
| J3 | At most one of `:accepted` or `:rejected` answers a proposal; a proposal creates no state; nothing exists until `:accepted` | `[D]` *Authority* |
| J4 | A lease **enters** the ledger when the grantor grants it, seeded from that act, and **leaves** once its reclaim has succeeded *and* its `:lapsed` record has been appended with `:dao.stream/ok`. Until both, it remains | `[D]` *The judge — Ledger* |
| J5 | The ledger records, per live lease: its terms (subject, holder, duration, cap), its tenure start (the reading the grant was seeded at, unchanged by renewal), its last relevant observation, its evidence state (`known`/`unknown` with resumed reading), and its reclaim state (`live`/`pending` with cause and success) | `[D]` *Ledger* |
| J6 | The pass classifies nothing until a first tick has been observed; at each pass, in order: (1) drain every wired tick cursor to `:dao.stream/blocked`, the newest reading (or the newest previously observed) is *now*; (2) drain every wired lease-fact cursor to `:dao.stream/blocked`, stamping each fact with *now*, applying renewals/releases/gaps; (3) answer and grant, seeded and stamped with *now*; (4) classify; (5) reclaim each due lease, marking it `pending` with its cause first; (6) record `:lapsed`, and on `:dao.stream/ok` the lease leaves the ledger | `[D]` *The pass* |
| J7 | A lease is due when, first-holding-in-order: already `pending` (keeps its cause); a valid `:released` observed, `:release`; tenure start further back than `:dao.lease/max`, `:cap`; the grantor's policy ends it, `:policy`; interval since last relevant observation exceeds duration + tolerance, `:silence`. A lease whose evidence is `unknown` is due for `:silence` only once a full duration has passed since its resumed reading | `[D]` *The pass* §4 |
| J8 | The reclaim procedure is idempotent and reports success; a reclaim that fails or does not report leaves the lease `pending` and unrecorded; the record follows the act and never precedes it; the absence of a `:lapsed` fact is never evidence of tenure | `[D]` *The pass* §5-6, *The judge* |
| J9 | A `:dao.stream/gap` on a lease-fact medium marks every lease on that medium `unknown` with *now* as its resumed reading; a lease recovered from a persisted ledger is `unknown`; conditions other than `:silence` apply to an `unknown` lease unchanged | `[D]` *Judging with incomplete evidence*, *Restart* |
| J10 | A drain ending in `:dao.stream/end` retires that cursor for later passes; a drain ending in `:dao.stream/transport-error` ends the pass before classification | `[D]` *The pass* |
| J11 | A grantor that has lost its ledger reclaims and re-grants: for each resource it still possesses it reclaims first, then grants afresh; the reclaim ends the prior tenure, a new grant alone does not | `[D]` *Restart* |

### 2.3 The holder

| # | Invariant | |
|---|---|---|
| H1 | A holder observes its grant before acting and establishes the grantor authored it by the composition's attribution; a holder that has not observed its grant holds nothing | `[D]` *The holder* |
| H2 | A holder renews at less than half the duration, measured against ticks on its own tick stream; a renewal is an append returning `:dao.stream/ok`, and any other outcome does not advance the holder's bound | `[D]` *The holder* |
| H3 | A holder stops acting at the bound — the earlier of duration since the later of its last renewal and its observed grant, and the cap the grant carries — whether or not anything has been heard | `[D]` *The holder* |
| H4 | A holder releases when done; the grantor still performs the reclaim and records it | `[D]` *The holder* |

### 2.4 Composition

| # | Invariant | |
|---|---|---|
| C1 | A composition that grants leases wires: a judging interpreter and a runtime driving it at a declared cadence; a tick stream for the judge and one per holder; a tolerance; an attribution resolver; a reclaim procedure per subject; a stream the grantor writes grants and `:lapsed` to, and the medium each recipient reads its facts from; media that retain or declare evict-oldest | `[D]` *Composition duties* |
| C2 | One handed no attribution resolver, or one incompatible with its medium, is refused at assembly — the `make-*` constructor throws before any stream exists | `[D]` *Composition duties*, `[T]` pins the throw |
| C3 | Media that neither retain nor declare evict-oldest are refused at assembly | `[D]` *Composition duties* |
| C4 | For durable resources, three things the vocabulary does not supply — a durable judge, a rule for which incarnation may reclaim, and fencing — are required; a composition that has not settled all three keeps its leases process-scoped | `[D]` *Composition duties* |
| C5 | No lease code reads a host clock, holds a callback, installs a timer, or consults a registry; time reaches lease code only as data on a tick cursor. A timer exists only as host policy outside the contract, in the driver | `[D]` *Prohibitions*, *Time*; `[T]` pins the clock/timer absence by grep (§7) |
| C6 | A lease does not extend retention, defer eviction, or gate history, and no `dao.stream` operation consults a lease | `[D]` *Prohibitions* — asserted by construction, since `dao.lease` imports `dao.stream` and never the reverse |

---

## 3. Decisions

### D1 — facts are plain maps on `dao.stream`, not `dao.space` datoms

Correction 1. The operative contract says "plain data on ordinary streams"; a
lease fact is a map with one dispatch key, read by an ordinary cursor, on the
`dao.stream` reader surface. `dao.space` (a Datalog/tuple interpretation over
`dao.jing`) is the wrong carrier for a per-attachment attributed map and adds a
medium the design does not require. This keeps the implementation's dependency
surface to `dao.stream` alone, matching the design's repeated claim that a
lease "leaves `dao.stream.md` unchanged by its existence."

### D2 — the judge is one pure step, driven by the composition's runtime

The pass is a single host-neutral step function `judge-step [judge] -> judge`
threading an immutable ledger, in the `forward-step` shape
(`forward.cljc:67-154`): it owns no scheduler, callback, registry, or mutable
state. *Now* is the newest reading drained from the tick cursor this step, never
a clock call. The composition's driver calls the step at its declared cadence,
exactly as `serving/step!` is driven by the daemon thread at
`dao/jing/remote.cljc:981-988`. This is the contract's "the judge is composed
inside the boundary that possesses the resource and reclaims by acting on what
it itself holds": the step *is* the possessing interpreter performing its own
act, and the step's output is the reclaimed-and-recorded ledger, not a verdict
for another layer to execute.

The ledger is **not rebuildable from any stream** (`dao.lease.md:120`): renewals
carry no time and observation times are judge-local, so the ledger is threaded
state the composition either persists (as a stream its owner writes and reads —
out of this plan's build scope, see §6) or recovers by the Restart rule.

### D3 — the tick stream is composition-supplied; a reference tick source is host policy only

Correction 2. The contract requires the composition to wire a tick stream and
binds the judge and holder to consume it, not to produce it. A tick's author is
the *adapter*. This plan therefore:

- binds the judge and holder to an **injected tick cursor** — they read
  `:dao.lease/reading` from it and never call a clock;
- provides a **reference tick source** as host-policy code under
  `#?(:clj)/(:cljs)/(:cljd)`, appending `{:dao.lease/event :dao.lease/tick
  :dao.lease/reading r}` to a caller-supplied writer at a caller-supplied
  cadence, for tests and examples only — it is *not* part of the contract and a
  real composition wires its own (or none, if its medium's deposits already
  carry comparable readings).

This keeps the host clock out of lease code (correction 2's point) while giving
the tests a scriptable, deterministic tick that needs no wall clock at all: the
test scripts the tick stream by hand and never runs the reference source.

### D4 — attribution resolver and reclaim procedure are injected functions, refused at assembly

`dao.stream` supplies no authorship (`dao.lease.rationale.md:224-245`). The
attribution resolver is an injected `(resolver fact) -> author`; the reclaim
procedure is an injected `(reclaim subject) -> success?` (idempotent, reporting
success), one per subject. Both are validated by the `make-*` constructor and
**throw `ex-info` at assembly** when missing or incompatible — the
`make-serving` / `make-attacher` idiom (`serving.cljc:104-129`,
`ws.cljc:375-405`). The resolver's inputs in this codebase are the three the
design names: an envelope key (`:ws/attachment`, `ws.cljc:162-169`), per-author
media identity (the `:sessions` key / `:traffic-reader`,
`serving.cljc:60-75`), or a transport's attachment identity
(`:dao.stream/attachment`, `ws.cljc:402`). The reclaim procedure for a served
connection is `close-session!`'s shape (`serving.cljc:179-183`); for a forwarder
pause it is the driver ceasing to call the forward step and closing the session;
for a shared-work claim it is a composition-supplied procedure following the
same `call-close!` idempotency pattern (`serving.cljc:38-43`).

### D5 — record-after-act rides on `observe/step`'s effect-before-commit law

`dao.stream.observe/step` advances its cursor exactly once, after its effect
answers `ok` (`observe.cljc:12-41`). The judge's step 6 (record `:lapsed`, then
leave the ledger on `:dao.stream/ok`) is the same law: the reclaim is the
effect, the `:lapsed` append is the commit, and a lease whose reclaim succeeded
but whose record append is non-`ok` stays `pending` and is reached again next
pass through the first clause of classification (J7). The step uses
`observe/step` for the per-lease reclaim/record pair so the ordering is enforced
by a tested primitive rather than restated.

### D6 — validity is a pure gate, and a defective fact is a no-op

V4's "a judge reading a defective fact has read no lease fact" is implemented as
a single pure `valid?` predicate applied before a fact touches the ledger: the
drain stamps and *drops* a defective fact, applies an eligible one, and the
gap/release/answer branches never see a fact that failed the gate. This keeps
the ledger's invariant (every entry was a valid fact) trivially, and makes
`defective?` independently testable (V5).

---

## 4. Phases

Each phase is behaviour-neutral to everything before it: nothing existing in
`dao.*` changes, and the three host lanes stay green by construction (the only
host-specific code is the reference tick source and driver loops, which are
new and additive).

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
  would produce a defective fact (V1, V2, V4).
- `defective?` and `valid?` — the pure gate (V4, V5): the full defect list from
  the contract, including the cross-fact rules (a lease id on a proposal, both
  `:accepted` and `:rejected` for one proposal, a reading older than one already
  observed — the last checked against an explicitly supplied prior reading,
  since validity is pure).

**Prove** — new `lease_test` deftests, unguarded, all hosts:

1. Constructors emit the exact map the table names; a constructor call that
   would omit a required key or carry a bad duration/cause throws.
2. `defective?` returns the full defect list on a corpus of malformed facts
   (missing key, out-of-table status, multi-entry duration, lease-id-on-proposal,
   both-answers, repeat `:accepted`, stale reading), and `false` on the
   well-formed corpus.
3. Unit arithmetic: `normalize` to the finer unit; strict comparison — an
   interval exactly equal to its bound reports "not yet passed".

Confirm `Testing dao.lease-test` appears in the Node (cljs) output.

### 4.2 Phase 2 — the judge

Same file, judge section; `lease_test` judge deftests. Pure step over threaded
state; scripted ticks and facts over `dao.stream.ringbuffer` cursors; no clock.

**Build**

- The ledger entry and `judge-step` implementing the six-step pass (J4–J11):
  `drain-ticks` (step 1), `drain-facts` with stamping and the `valid?` gate
  (step 2, D6), `answer-and-grant` (step 3), `classify` (step 4, the ordered
  cause list), `reclaim` via the injected procedure marking `pending` first
  (step 5), `record` via `observe/step`'s effect-before-commit (step 6, D5).
  Cursor retirement on `:dao.stream/end` and pass-abort on
  `:dao.stream/transport-error` (J10).
- `gap->unknown` and the `unknown`-silence rule (J9); `restart` (J11).

**Prove** — scripted, no host clock: the test hand-appends tick and fact maps to
ring buffers and hands the judge its cursors.

1. A never-renewed grant lapses on `:silence` (seed-from-grant, the design's
   "most likely form" of leak): grant seeded at reading 0, duration 10; ticks
   advance; a pass past 10+tolerance reclaims with `:silence` and records
   `:lapsed`.
2. A timely renewal advances the last relevant observation and defers `:silence`;
   a renewal from a non-holder author is ignored (J2) and silence measured as
   though it never arrived.
3. `:released` from the holder reclaims with `:release` before the duration
   elapses; `:cap` ends tenure past `:dao.lease/max` regardless of renewals;
   `:policy` ends it on the composition's own predicate.
4. A `:dao.stream/gap` marks the leases on that medium `unknown`; an `unknown`
   lease lapses for `:silence` only a full duration after its resumed reading,
   while `:cap` still applies unchanged.
5. Reclaim-before-record: a reclaim procedure returning false leaves the lease
   `pending` and unrecorded; a second pass with a succeeding procedure records
   `:lapsed` and removes the lease; the record is never observed before the act
   (J8).
6. `restart` reclaims-then-regrants: after the ledger is dropped, the
   composition-supplied inventory of still-possessed resources is reclaimed then
   re-granted with fresh identities (J11).

### 4.3 Phase 3 — the holder

Same file, holder section; `lease_test` holder deftests.

**Build**

- `observe-grant` (H1: gate on having observed an author-valid grant);
  `should-renew?` (H2: renew at < half the duration, measured on the holder's
  own tick cursor); `at-bound?` (H3: the earlier of duration-since-last-activity
  and the cap); `release` (H4). Each is a pure predicate/constructor the holder's
  own control flow calls; none installs a timer or renews on the holder's behalf.

**Prove** — scripted ticks and facts.

1. `should-renew?` is false until the grant is observed; true at < half the
   duration and false at >= half.
2. `at-bound?` fires at the cap even with fresh renewals, and at duration-since-
   last-activity when the cap is absent or larger.
3. A renewal append returning non-`ok` does not advance the holder's bound.

### 4.4 Phase 4 — composition and use-case sketches

Same file, composition section; `test/dao/lease_composition_test.cljc`.

**Build**

- `make-judge` and `make-holder` constructors: take a config map with the
  injected seams (tick cursor, fact cursor/medium, attribution resolver, reclaim
  procedure, tolerance, units) and **throw `ex-info` at assembly** on a missing
  or incompatible seam (C2, C3, D4). Return a step-bearing value the composition's
  driver calls at its cadence (C1).
- The **reference tick source**, host policy under `#?(:clj)/(:cljs)/(:cljd)`:
  a loop (`Thread/sleep`, `js/setInterval`, `async/Timer.periodic`) appending
  monotonic readings to a caller-supplied writer, stopped by the caller. Docstring
  states it is test/example policy, not contract (D3).
- Three use-case **sketches**, documented and lightly exercised, not built as
  products: the forwarder pause (the driver ceasing to advance a
  `serving` session's forward step, release = `close-session!`); the served
  connection lifetime (reclaim = `close-session!` on `:socket-handle`,
  attribution = `:ws/attachment`); the shared-work claim (a composition-supplied
  reclaim procedure following `call-close!`'s idempotency). These pin C1's
  wiring in a runnable form without claiming any of the three as a finished
  component.

**Prove**

1. `make-judge` throws on a missing resolver, a missing reclaim procedure, and an
   evict-arbitrary medium (C2, C3); each throw happens before any stream exists.
2. An end-to-end scripted grant→renew→lapse cycle through `make-judge`, with a
   hand-turned tick stream, reclaims and records exactly once.
3. The reference tick source (one host, the JVM lane) appends strictly
   non-decreasing readings and stops on demand.

---

## 5. Host matrix

| | clj | cljs (Node) | cljd |
|---|---|---|---|
| Vocabulary, judge, holder (Phases 1–3) | built, tested | built, tested | built, tested |
| `make-judge` / `make-holder` constructors | built, tested | built, tested | built, tested |
| Reference tick source + driver loop | `Thread`/sleep | `js/setInterval` | `async/Timer.periodic` |
| Use-case sketches (served connection, forwarder) | exercised | n/a (host-specific transport) | n/a |

The portable core is `.cljc` and has no host branch; only the reference tick
source and the served-connection/forwarder sketches touch a host. The three
hosts' tick sources already exist as patterns to mirror (`yin/repl.cljc:192-396`),
not to reuse.

---

## 6. Boundary — built here, and what is left owing

Built here: the `dao.lease` vocabulary, judge, holder, composition constructors,
their tests, and the three use-case sketches.

| owed | by | where recorded |
|---|---|---|
| A persisted ledger for a judge that must survive restart (a stream its owner writes and reads, or the Restart rule) | a composition that needs durability, not this plan | `dao.lease.md` *Restart*; this plan binds the interface, not a persistence transport |
| The three durable-resource prerequisites — a durable judge, an incarnation rule, fencing | the resource's own plan | `dao.lease.md` *Composition duties* |
| The wire form of a pause vocabulary between peers | `dao.stream.ws.md`'s close-code design gate | `dao.lease.md` *Carriage* ("distinguishing reclaim on the wire belongs to `dao.stream.ws.md`'s deferred close-code design") |
| The three use cases as finished components (forwarder pause, served-connection lifetime, shared-work claim) | their owners, as consumers of this vocabulary | `dao.lease.md` *Out of scope* ("this contract gates nothing, and the domain decides") |
| A generic unit table shared across compositions | the first composition that needs it | `dao.lease.md` *Units* ("a composition's interoperability decision") |

Explicitly not planned: `dao.space`, `dao.jing`, `yin.vm.*`, any transport, and
any change to `dao.stream` or `dao.stream.ws`.

---

## 7. End condition

- `dao.lease` requires only `dao.stream` (and `dao.stream.observe` /
  `dao.stream.ringbuffer` for the step and test media); no namespace requires
  `dao.lease`, and `dao.stream` is byte-for-byte unchanged (C6 holds by
  construction).
- Every rule of `dao.lease.md` — Vocabulary, Authority, Prohibitions, Time, the
  judge (ledger, pass, incomplete evidence, restart), the holder, Composition
  duties, Sizing, Limits, Carriage, Out of scope — is accounted for by an
  invariant, a decision, a phase, or an explicit deferral in §6.
- `bb test:clj`, `bb test:cljs` (confirm `Testing dao.lease-test` in the Node
  output), and `bb test:cljd` are green; the demo still compiles.
- Greps at zero: no `System/currentTimeMillis`, `js/Date.now`, `DateTime.now`,
  `setInterval`, `Timer.periodic`, `Thread/sleep`, or `addEventListener` inside
  the vocabulary/judge/holder sections (C5 — the clock and timer live only in
  the reference tick source and driver, marked host policy).
- Nothing is owed to a later plan except what §6 names, and nothing in this
  document changes the two contracts it is subordinate to.
