Completed-GMT: 2026-10-04 17:21:08 GMT
Completed-Local: 2026-10-05 00:21:08 +07

# Gate review, M-next C slice C5: offers, grants, bindings

**VERDICT: APPROVE-WITH-NITS.** Every guarantee on the attack list holds by inspection; the tests are meaningful and the seven mutations the engineer ran are consistent with what the code enforces. No correctness or safety defect found. The nits are one grammar-parity amendment to C4's inspector (ruling (a), should land before stage D), the UCF/plan doc sentences C5 now forces, and two optional hardenings. Nothing blocks commit of C5 itself.

## Mutation pass by inspection (attack item 1)

| Guarantee | Verdict | Evidence |
|---|---|---|
| Store before ledger reference | holds | `offer!` (grant.cljc:104-113) pre-decides purely under `authority/locked`, puts the body, then `transition!` re-enters the same lock and re-runs the same pure `decide` against the same projection — no other transition can interleave on any host (JVM lock held; Node/Dart single isolate), so the store precedes the commit unconditionally |
| Uninspectable body commits and stores nothing | holds | grant.cljc:100-103 refuses before `locked`, before any store; tested with three defect classes |
| Duplicate offer idempotent by occurrence AND address | holds | offer-decision grant.cljc:59-60 answers `:replayed` with empty facts → no store, no commit; fold's `:duplicate-offer` (ledger.cljc:210) is the second wall; idempotent across carriers and after reopen (test) |
| Variant / different-origin refused | holds | `:variant-conflict` (baseline compare), `:occurrence-conflict` (origin compare), both with zero writes and zero stores |
| One grant for two candidates | holds | hook takes first proposer per unheld occurrence (grant.cljc:189-217); the writer, not the hook, enforces the invariant — `grant-decision` grant.cljc:139-140 answers `invalid` for a held occurrence or recorded lease, and the fold's `:held-occurrence`/`:duplicate-lease` (ledger.cljc:223-226) refuse the rest; 8-thread race test agrees |
| Grant and bound in ONE record; fold refuses grant without binding | holds | writer commits `[g (custody/bound …)]` as one facts vector = one transaction = one journal frame; fold threads `::unbound` per record and defects `:unbound-grant` at record end (ledger.cljc:340-342) — a binding in a later record also fails (`:unbound-grant` on the grant's record), and a lone second binding fails `:duplicate-binding` |
| Epoch 0 first grant | holds | occurrence created at `:yin.k/epoch 0` (ledger.cljc:295); `bound-defect`'s `:epoch-mismatch` forces binding-epoch = occurrence-epoch (ledger.cljc:241-244) |
| Binding-evidence negatives | holds | other author (filter custody.cljc:155), other record (`:not-in-grant-transaction`), duplicate in one or two records (`:duplicate-binding`), float/negative/2^52/string/nil epoch (`:inexact-epoch` via kind-strict `exact?`, custody.cljc:29-36; `cbor/numeric-kind` distinguishes `:integer` from `:float64`, so JS 1-vs-1.0 is decided by kind, not equality) |
| Evidence survives canonical CBOR and the remote reflection | holds | tested through `cbor/decode (cbor/encode …)` and a two-ring-buffer `dao.stream.remote` reflection whose descriptor identity equals the arbitration; mallory-attributed copies prove nothing |
| Fold arms fail closed | holds | every new arm defects (`:malformed-fact`, `:foreign-arbitration`, `:unknown-occurrence`, `:unknown-lease`, `:binding-mismatch`, `:epoch-mismatch`, `:duplicate-*`, `:unbound-grant`); `entities` re-checks published attribute order on the read side; 23 negative assertions |
| Memory durability shape equals the file backend's | holds | journal.cljc:99-106 uses the same four `:dao.stream.journal/`-qualified keys as file.cljc:29-38; values `:memory`/`:none`/`:none`/`#{}` are honest |
| Authoring guard throws before any write | holds | `fact-datoms`/`facts->datoms` (ledger.cljc:130-151) throw on `:unknown-kind` and `:unpublished-attribute`; `transition!` builds datoms (authority.cljc:215) before `commit!`, so nothing is written and nothing poisoned; the writer additionally converts the same class to `invalid-value` (grant.cljc:151-157) |

## Judge wiring (attack item 2)

- **Writer adapter** (grant.cljc:164-178): answers only `:dao.stream/outcome`; `transition!`'s `:suspended`/`:closed` status maps become `transport-error`, a refused/inadmissible fact becomes `invalid-value`. Matches `deliver-authored`'s contract: non-ok leaves the fact queued or lost-to-pass, never tenure-establishing (lease.cljc:1217-1238).
- **Readiness-only reclaim**: `(fn [_subject] (ready? a))` where `ready?` = projection non-nil (grant.cljc:181-186). It writes nothing, revokes nothing; tested before/after close.
- **step! holds the lock across the whole pass** — correct by reading: `authority/locked` is JVM `locking` (reentrant, so the writer's `transition!` re-enters), and there is no lock-order inversion — the transactor's internal lock is only ever acquired inside the authority lock (`transition!` → `commit!` → `transact!`), never the reverse. On Node/Dart the pass is synchronous in one isolate. It is **testable without a product hook**: `judge-config` answers a plain map, so a JVM-only test can `assoc` a `:writer` that delegates after a promise, run `step!` on a background thread, and assert a concurrent `enroll!` blocks until the step completes. Untested here — see finding 4.
- **Stale `:accepted`/proposal re-answer**: a stale `:accepted` from a medium attributed to another author establishes nothing (lease.cljc:987-993); attributed to self, `:seen`'s repeated-status gate and the ledger fold's `:duplicate-lease` block any second commit. A re-drained proposal whose grant already landed gets no second grant twice over: the occurrence is held in the projection (the hook's ground truth) and `deliver-authored`'s `:answered` gate blocks `[holder proposal-id]` re-answering. A proposal whose grant append failed remains unanswered by design — correct retry. No C5 path re-answers.

## Scope, determinism, guards (attack items 3–5)

- **No scope creep**: no epoch increment (epoch is set 0 at offer and only read by the binding), no lapse kind (the writer refuses `:lapsed`, tested), no `admit!` (the seam docstring still says it does not exist), no completion/input/front code. Only UCF namespaces are required; no engine namespace touched.
- **Cross-host determinism**: datoms follow published attribute order in decision order; map values (the baseline, op-id keys) go through canonical CBOR with sorted keys; sets never leak iteration order into bytes; every counter is bounded at 2^52-1 with kind-strict checks. `(str (random-uuid))` lowercase on all three hosts is pinned by `the-occurrence-form` running on the Dart lane.
- **Repo guards**: `test/resources/dao/jing/cbor-v1.json` and `test/resources/yin/vm/ucf/checkpoint-v1.txt` untouched; nothing in the diff writes any resource; the fixtures namespace's "nothing here may write the resource" discipline is respected — grant_test re-encodes bodies in memory and recomputes addresses.

## Findings

1. **The inspector does not check the occurrence form** — `src/cljc/yin/vm/ucf/checkpoint.cljc:191` (also 157-169, 249-269). Severity: minor (grammar parity, not safety — the authority refuses `:malformed-occurrence` and the fold refuses `:malformed-fact`, so no bad occurrence can be admitted; but D's `validate-body` will inherit the looser gate and agree with a body the authority refuses). Fix: the exact amendment in ruling (a) below; both fixture occurrences already conform, so only new test mutations are needed.
2. **UCF/plan doc sentences C5 now forces are not yet written** — `docs/design/yin.vm.universal-continuation-format.md` 7.2.1, 7.7.2, 7.7.8; plan 1.8. Severity: minor (docs). (i) 7.2.1: the concrete occurrence form; (ii) 7.7.2: the authority's *admitted-offer* fact with `:yin.k/baseline`, authored by the authority, distinct from the emitter's offer evidence; (iii) 7.7.8: the view for "the only binding for L" (Q5); (iv) 7.7.8: the authorship rule is identity equality (Q6); (v) plan 1.8: the memory declaration values `:none`. Plan section 5 already forecast these; land them before D.
3. **Grant replay match ignores duration and proposal** — `src/cljc/yin/vm/ucf/authority/grant.cljc:135-138`. Severity: nit. Unreachable in composition (only the judge appends grants, retrying an identical fact); a hand-crafted re-append with the same (lease, occurrence, holder) but different duration would answer ok without recording. Fix opportunistically in C6, when reopen reconstruction must make recorded terms win (store a digest of the grant, or its duration, in `:leases`).
4. **No test pins the whole-step lock** — `src/cljc/yin/vm/ucf/authority/grant.cljc:235-239`. Severity: nit (coverage). Correct by reading (see above); a JVM-only blocking test via a test-authored config needs no product hook. Add in C6 or C12 beside C3's "two decisions" JVM threads test.
5. **Evidence does not structurally gate the grant** — `src/cljc/yin/vm/ucf/custody.cljc:164-178`. Severity: nit. It checks lease, holder, subject but not `lease/defective?`. The fold's `grant-defect` already refuses defective grants, so within attribution this is unreachable; a one-line `:grant-mismatch` hardening is optional.
6. **Observation (no severity)**: once ticks pass a duration, a due lease loops pending — readiness true → `:lapsed` append → `invalid-value` → still pending, once per step. Documented in the namespace docstring; compositions must not wire reclaim-driven policy before C6.
7. **Observation (no severity)**: the fold trusts the offer fact's baseline (no re-inspection at reopen; a hand-crafted journal could offer one address under two occurrences). The journal is authority-owned and not an adversarial boundary; consistent with plan 1.1. No action.

## Rulings on the engineer's eleven questions

1. **Memory durability values — accept.** Shape equals the file backend key-for-key; `:none`/`:none`/`#{}` are the honest minima. Add them to plan 1.8's enumeration in the doc pass (finding 2v).
2. **Occurrence form and C4 — the inspector changes.** See ruling (a).
3. **Refusals in the ledger — C6 may add it; C5 must not.** See ruling (b).
4. **Grant replay match — accept for C5.** See finding 3.
5. **"The only binding for L" — accept; wording needed.** Amend 7.7.8 to: the uniqueness that counts is *in the named authority's transaction history*; a reader holding a partial view can refute (a second binding) but never establish; in this composition the reader's records are the authority's complete-retention journal read from its oldest anchor, so the check over the given records is completeness-backed, and the fold's `:duplicate-binding` is the enforcement behind it.
6. **The author model — accept; identity equality is the rule.** The author value for records read from a stream whose `:dao.stream/identity` equals the body's arbitration identity is that identity — the reflection test pins exactly this (grant_test.cljc:471-482). Descriptors are transport-relative and must never be compared. Put the sentence in 7.7.8 (finding 2iv).
7. **A different park with equal origin and baseline — accept.** Equal occurrence + origin + baseline is indistinguishable from a snapshot variant, and admitting it is safe: every custody-relevant comparison (baseline ids and intents, origin, occurrence) sees identical data, and op-ids/dedup key on the same occurrence either way. The emitter-side invariant (distinct parks mint distinct 122-bit ids) is the guard. Add one clarifying sentence to 7.2.1: a reuse indistinguishable from a variant is admitted as one.
8. **The carrier recorded once — accept.** dht 14.2.2 (line 3177) makes duplicate evidence idempotent "by occurrence and snapshot address"; the medium is not in the key, and no C6–C11 slice reads `:yin.k/medium`.
9. **Evidence's partial grant check — accept as-is.** The fold gates what the authority commits; attribution is the reader's trust boundary. The `lease/defective?` line is optional hardening (finding 5).
10. **No cross-host byte fixture for the new kinds — accept.** C12 owns the checked-in ledger file (plan slice table); the unchanged C3 digest fixture shows no byte drift in existing kinds.
11. **"Unavailable comparison suspends variant admission" — accept.** Unreachable while the baseline lives in the projection; revisit only if C6/C12 makes the projection lazy.

## The three special rulings

**(a) Occurrence form: C4's inspector changes; the authority keeps its check.** The authority fixing the form (lowercase UUID string, custody.cljc:49-55) is exactly UCF 7.2.1's "the concrete form is fixed by M-next C" — and the inspector is the shared grammar gate D's `validate-body` will call, so the form belongs there; otherwise C and D disagree over a body the authority will refuse. The authority's `:malformed-occurrence` and the fold's `:malformed-fact` stay as defense-in-depth. Exact amendment, in `src/cljc/yin/vm/ucf/checkpoint.cljc`:

- require `[yin.vm.ucf.custody :as custody]` (custody requires only `dao.jing.cbor`, so the graph stays acyclic; `ledger.cljc` already depends on it the same way);
- in `check-root-header!` (line 191), keep the nil check and add directly after:
  ```clojure
  (when-not (custody/occurrence? (get body :yin.k/occurrence))
    (undecodable! (conj path :yin.k/occurrence)
                  {:yin.k/kind :malformed-occurrence}))
  ```
- in `check-origin!` (lines 161-164), add `(custody/occurrence? (get origin :yin.k/occurrence))` to the shape conjunction (its refusal names `[… :yin.k/origin :yin.k/occurrence]` with the same kind);
- in `check-op-id!` (lines 254-257), replace `(some? (get op-id :yin.k/occurrence))` with `(custody/occurrence? (get op-id :yin.k/occurrence))`;
- fixtures need no rewrite (both fixture occurrences are already lowercase UUIDs); add mutation rows to pin it, e.g. `["occurrence-not-uuid" "successor" (set-in [:yin.k/occurrence] "O")]` and an op-id twin, mapped to `:malformed-occurrence` in checkpoint_test's kind table;
- UCF 7.2.1 gains: *"The concrete form (M-next C, slice C5): a UUID string in lowercase hex, one spelling, so canonical-byte equality is string equality."*

**(b) The refusal fact kind: C6 may add it; C5 must not.** Nothing in C5 authors refusals — the hook's no-refusal deviation is sound, because a losing candidate needs no answer: the occurrence is held, no further grant can issue for it, and the loser's proof is `binding-evidence`. Adding an unused kind now would be speculative schema. One concrete requirement to record for C6: `dao.lease`'s refusal fact carries only `:dao.lease/proposal`, but plan 1.5 step 5 rebuilds `:answered` "from every recorded grant and refusal **with its proposer** and proposal id" — so C6's ledger refusal fact must carry the proposer as an attribute (or use a dedicated answered kind keyed `[proposer proposal-id]`), or reconstruction cannot key the map. That schema note belongs in C6's slice; plan 1.5's wording already presumes it.

**(c) Offer ordering: accept the deviation and amend the plan text.** The executed order — inspect; under the authority lock decide purely; store only what the decision will commit; commit — is strictly stronger than plan C5's "inspect, store, then commit": refused, replayed and uninspectable offers store nothing (the plan's literal order would store variant-conflict bodies), the store-before-reference invariant is preserved, and a store whose commit then fails (bound, poison) leaves a harmless orphan blob per plan 1.1. There is no TOCTOU window: `decide` is pure and re-run inside `transition!` under the same lock against the same projection, and its two runs cannot diverge. Amend plan C5's sentence to the executed order so the composition contract and the code say one thing.

---

Read-only review; no repository file edited and no suite run. The one permitted write was the reviewer plan file at `/Users/sto/.claude-glm/plans/read-users-sto-workspace-datomworld-c5gr-abundant-ritchie.md`, which only records this review's method.
