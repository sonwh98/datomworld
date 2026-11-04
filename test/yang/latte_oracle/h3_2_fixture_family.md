# H3-2 fixture family: proof elaboration (preparation)

Status: **preparation only**. This document designs the fixture family and
predicts its A outcomes. It changes no harness, compiler, or evidence file.
The companion A-verification script is
[`h3_2_a_verify.clj`](h3_2_a_verify.clj) (seat-run; see §6). Harness
integration (new rows in `kernel_gate.edn`, registration of new integration
IDs, evidence refresh) is a later round and needs kimi's design review first.

Upstream pin: `latte-kernel/latte-kernel` at
`86fca0d1d054af005cb37395eae04500d45e6279` (the `deps.edn` `:test` alias).
All line references below are to that pin: `proof.cljc` means
`src/latte_kernel/proof.cljc`, and "upstream proof test" means
`test/latte_kernel/proof_test.cljc` at the same sha.

## 0. Verification ledger (read this first)

| Claim class | Status |
|---|---|
| Call graph, branch conditions, refusal messages/data (§2, §4) | Source-derived: read directly from pinned `proof.cljc` / `typing.cljc` / `defenv.cljc` in this round |
| F1/F3 expected proof terms, types, and intermediate states | Source-derived from upstream's own asserted expectations (proof test `test-proof`, `test-elab-proof`, `test-elab-have`, `test-elab-discharge`, `test-update-var-deps`, `test-gen-local-calls`); upstream asserts them, this round did not re-run them |
| F2, R1–R3 expected outcomes | Source-derived predictions from `proof.cljc` control flow; no upstream test asserts these exact scripts |
| Any statement "A returns X" for F2/R1–R3 | **A-verified pending seat run** of [`h3_2_a_verify.clj`](h3_2_a_verify.clj) (§6). Nothing in this round was executed: this seat is headless/file-only by brief |
| Current credit state on this branch (§1) | Read from the checked-in [`slice9_evidence.edn`](slice9_evidence.edn) on `hardening3-h3-1`; not re-derived in this round |

No prediction below is machine-verified by this round. The seat run of the
verification script is the A-verification step; a `FAIL` line falsifies the
named prediction and the fixture must be revised before integration.

## 1. Baseline and what H3-2 must add

The eleven H3-2 targets (function/arity, from the approved hardening-3 plan,
slice H3-2; arities agree with the manifest inventory):

`elab-have/8`, `elab-have-impl/8`, `elab-declare/7`, `elab-discharge/6`,
`elab-discharge-impl/6`, `abstract-local-def/4`, `abstract-local-calls/4`,
`gen-local-calls/3`, `update-def-uses/3`, `update-var-deps/3`,
`ref-uses-in-term/1` — all in `latte-kernel.proof`.

Measured state on this branch (`hardening3-h3-1`, checked-in evidence,
`:counts {:pass 102, :recorded-upstream-failure 2, :recorded-deviation 2}`,
`:exercised-count 145`, 20 unexercised): since the H3-1 repair, the existing
`:check-assume-have-discharge` fixture passes, and the evidence already
credits **all eleven** targets as `:named-integration` through that one
fixture (see the `:coverage :entries` rows for `latte-kernel.proof/*` in
[`slice9_evidence.edn`](slice9_evidence.edn)). At the plan's baseline
(`d5d56ca3`, 107/165) the same fixture was blocked and credited none of them.

Consequence for this design, per the plan ("begin with the repaired
`check-assume-have-discharge`… measure what it actually credits before
adding fixtures"):

- The family does **not** need eleven new witnesses. It needs (a) the
  canonical witness stated and kept (F1 = the repaired fixture's script),
  (b) complementary branch/state coverage the single witness does not
  provide — inferred-type and anonymous `have` (F2), step-level discharge
  state assertions (F3) — and (c) refusal companions (R1–R3), which the
  passing witness cannot supply.
- Credit predictions in §5 are therefore stated twice: the credit the
  fixture *would* confer if it were the (or an additional) passing named
  integration, and the marginal credit over the current branch state
  (zero for the eleven, by design — their value is redundancy, branch
  coverage, and refusal evidence, not new arity counts). If integration
  lands on a tree where `:check-assume-have-discharge` is again blocked,
  F1/F3 restore the same eleven credits; F2 alone restores only the
  `have`/`declare` subset (§3).

## 2. Call graph the predictions rest on

From pinned `proof.cljc`:

- `check-proof/5` (`proof.cljc:507`) → `compile-proof` (assume expansion,
  `proof.cljc:463`), `elab-proof/3`, `typing/rebuild-type`, `norm/beta-eq?`.
- `elab-proof/3` (`proof.cljc:445`) loops `elab-proof-step/6`
  (`proof.cljc:346`): `:declare` → `elab-declare/7`; `:have` →
  `elab-have/8`; `:discharge` → `elab-discharge/6`; `:qed` → `elab-qed/4`.
  A `[:ko …]` step result ends the proof as `[:ko …]`; thrown `ex-info`s
  propagate uncaught.
- `elab-declare/7` (`proof.cljc:37`) → `typing/rebuild-type/3`; failure is
  `[:ko ty']`, success conses `[v ty']` onto `ctx` and `[v #{}]` onto
  `var-deps`.
- `elab-have/8` (`proof.cljc:55`) delegates to `elab-have-impl/8`
  (`proof.cljc:60`): `typing/type-of-term/3`; if the stated type is not
  `_`, `typing/rebuild-type/3` + `norm/beta-eq?/4`; if the name is `_`,
  returns the state **unchanged** (no registration, no dependency updates,
  `proof.cljc:91`); if the name is already a registered local definition,
  `[:ko …already registered…]` (`proof.cljc:93`); otherwise registers a
  `Definition` with `stx/free-vars` and updates dependencies:
  `update-var-deps/3` on the recorded type only (the term call is commented
  out, `proof.cljc:100`), `update-def-uses/3` on term and type, then
  `(assoc name #{})` (`proof.cljc:100`).
- `update-var-deps/3` (`proof.cljc:109`) is **conservative**: it adds the
  new name to *every* `var-deps` entry; the occurrence-filtered version is
  commented out (`proof.cljc:114`). §4 (refusals) and the state assertions
  depend on this; the slice must not "correct" it.
- `update-def-uses/3` (`proof.cljc:124`) → `ref-uses-in-term/1`
  (`proof.cljc:121`, `stx/term-reduce` with `{:ref conj}`): only names
  actually referenced in the term/type gain the new user.
- `elab-discharge/6` (`proof.cljc:148`): empty `ctx` throws
  `"Context is empty (please report)."`; otherwise delegates to
  `elab-discharge-impl/6` (`proof.cljc:154`), which throws
  `"Incorrect discharge name."` when the discharged name is not the head
  of `ctx` (or of `var-deps`), then reduces `abstract-local-def/4` and
  `abstract-local-calls/4` over the head entry's dependency set `xdeps`.
  **If `xdeps` is empty, neither abstract function is entered** — discharge
  credit requires a preceding named `have` (which, by the conservative
  update above, makes every live `var-deps` entry nonempty).
- `abstract-local-def/4` (`proof.cljc:172`): fetches the local definition,
  conses `[x ty]` onto its `:params`, increments `:arity`, re-registers.
- `abstract-local-calls/4` (`proof.cljc:185`): rewrites the definition's
  `:parsed-term` with `gen-local-calls/3` (`proof.cljc:194`), which inserts
  the discharged variable as first argument at every reference to an
  abstracted definition (`abs-defs`), recursing over refs, binders,
  applications, and ascriptions.

Entry conditions per target (what a fixture must actually do):

| Target | Entered iff |
|---|---|
| `elab-declare/7` | a `:declare` (or `:assume`-generated declare) step elaborates |
| `elab-have/8`, `elab-have-impl/8` | a `:have` step elaborates (any name/type form) |
| `update-var-deps/3`, `update-def-uses/3`, `ref-uses-in-term/1` | a **named** `have` succeeds (anonymous `have` returns before them) |
| `elab-discharge/6` | a `:discharge` step is attempted (impl entered only with nonempty `ctx`) |
| `elab-discharge-impl/6` | `:discharge` with nonempty `ctx` |
| `abstract-local-def/4`, `abstract-local-calls/4` | discharged head's dependency set is nonempty |
| `gen-local-calls/3` | `abstract-local-calls` runs (same condition), once per abstracted definition |

## 3. The family

Three positive named integrations and three refusal companions. Scripts use
the harness's script shapes: F1 enters through `check-proof` (the
`:assume` surface), F2/F3 through `elab-proof` (raw steps, as in upstream's
`test-elab-proof`). Proposed new IDs are marked †; F1 reuses the existing
registered ID. All expected outcomes are A predictions (pending seat run,
§0); F1/F3 values are upstream's own asserted values.

### F1 — `:check-assume-have-discharge` (existing ID, canonical witness)

Entry: `latte-kernel.proof/check-proof` on `empty-env`, theorem name
`composition`, theorem type `(parse '(forall [A B :type] (==> (==> A B) (==> A A B))))`.

```clojure
[[:assume {:line 1}
  [A :type B :type f (==> A B) x A]
  [:have <a> (==> A B) f {:line 2}]
  [:have <b> B (<a> x) {:line 3}]]
 [:qed <a> {:line 4}]]
```

This is the existing `kernel_gate.edn` fixture (already in
`kernel-named-integrations`) and upstream's `test-proof` script.
`compile-proof` expands it to declares `A B f x`, the two haves, discharges
`x f B A`, and the qed.

Expected A (upstream `test-proof`, proof test lines 342–352):

```clojure
[:ok [(<a>) (Π [A ✳] (Π [B ✳] (Π [f (Π [⇧ A] B)] (Π [x A] (Π [⇧ A] B)))))]]
```

Coverage: named `have` with explicit types; `<b>` references `<a>`
(local-definition-referenced-by-local); four discharges each with nonempty
dependency sets (after the two haves every `var-deps` entry is
`#{<a> <b>}`); completion through `check-proof` (hence also `:qed`).
Predicted arities: **all eleven** (§5), each entered multiple times.

### F2† — `:h3-2-elab-inferred-anonymous`

Entry: `latte-kernel.proof/elab-proof` on `empty-env`, initial `ctx` `[]`.

```clojure
[[:declare A :type {}]
 [:declare x A {}]
 [:have <a> _ x {}]
 [:have _ A x {}]
 [:qed <a> {}]]
```

Purpose: the two `have` branches F1 never takes — inferred type (`_`,
`proof.cljc:71`: recorded type is the synthesized type, `rebuild-type`
skipped for the stated type) and anonymous `have` (`_`,
`proof.cljc:91`: type-checked, then state returned unchanged).

Expected A (predicted; the verification script checks exactly this):

- result `[:ok [def-env (<a>) A]]` (harness-projected: `[:ok ((<a>) A)]`);
- local definitions of `def-env` are exactly `#{<a>}` — the anonymous have
  registers nothing;
- `<a>` is `{:name <a> :params [] :arity 0 :parsed-term x
  :term-free-vars #{x} :type A}` — type `A` was inferred from `x`.

Predicted arities: `elab-declare/7`, `elab-have/8`, `elab-have-impl/8`,
`update-var-deps/3`, `update-def-uses/3`, `ref-uses-in-term/1` (all via the
named inferred have; the anonymous have re-enters only
`elab-have/8`/`elab-have-impl/8`). No discharge arities: F2 has no
`:discharge` step, deliberately — discharge credit is F1/F3's job, and this
separation is what makes a future trace audit able to attribute discharge
credit to the right fixture.

### F3† — `:h3-2-elab-discharge-states`

Entry: `latte-kernel.proof/elab-proof` on `empty-env` (upstream
`test-elab-proof` script, proof test lines 271–283):

```clojure
[[:declare A :type {}] [:declare B :type {}] [:declare f (==> A B) {}]
 [:declare x A {}]
 [:have <a> (==> A B) f {}] [:have <b> B (<a> x) {}]
 [:discharge x {}] [:discharge f {}] [:discharge B {}] [:discharge A {}]
 [:qed <a> {}]]
```

Expected A end-to-end (upstream `test-elab-proof`): projected
`[:ok ((<a>) (Π [A ✳] (Π [B ✳] (Π [f (Π [⇧ A] B)] (Π [x A] (Π [⇧ A] B))))))]`,
with `<a>`/`<b>` fully abstracted (params
`[[A ✳] [B ✳] [f (Π [⇧ A] B)] [x A]]`, arity 4; `<b>`'s term rewritten to
`[(<a> A B f x) x]`).

State assertions (the acceptance-relevant states F1's projected result
cannot show; upstream `test-elab-have`/`test-elab-discharge` values,
checked step-by-step by the verification script):

- after declares: `ctx` is `([x A] [f (Π [⇧ A] B)] [B ✳] [A ✳])`,
  `var-deps` is `([x #{}] [f #{}] [B #{}] [A #{}])`;
- after `<a>`: `var-deps` is `[[x #{<a>}] [f #{<a>}] [B #{<a>}] [A #{<a>}]]`
  (conservative all-entries update, §4), `def-uses` is `{<a> #{}}`, `<a>` is
  `f` at type `(Π [⇧ A] B)` with no params;
- after `<b>`: `var-deps` entries are all `#{<a> <b>}`, `def-uses` is
  `{<a> #{<b>} <b> #{}}` (only `<b>` references `<a>`), `<b>`'s term is
  `[(<a>) x]`;
- after `[:discharge x]`: `<a>` gains param `[x A]` (arity 1) although its
  term `f` never mentions `x` — the conservative-dependency consequence,
  asserted as upstream behavior, not corrected; `<b>` gains `[x A]` and
  its term is rewritten to `[(<a> x) x]`;
- after discharging `x f B A`: `ctx` and `var-deps` are empty, both
  definitions have arity 4, and `elab-qed` on `(<a>)` yields the F1 type.

Predicted arities: **all eleven** (same path as F1, but with authored —
not compiler-generated — `:discharge` steps, and with the intermediate
states pinned).

Why keep both F1 and F3 if each predicts all eleven? They enter through
different public surfaces (`check-proof`+`compile-proof` assume expansion
vs. raw `elab-proof`), and only F3 pins the discharge state transitions
the H3-2 acceptance line requires ("concrete expected proof/type outcomes
and relevant state assertions"). Either one alone would satisfy the arity
list; together they make the credit robust to a regression in either entry
surface. This is the smallest family that covers the plan's four required
shapes plus both completion surfaces; dropping F2 loses the inferred/
anonymous branches, dropping F3 loses authored-discharge state evidence,
dropping F1 loses the `check-proof` completion the plan names first.

### Refusal companions (all `negative? true` when integrated)

R1† `:h3-2-have-duplicate` — duplicate local definition
(`proof.cljc:93`, after successful type synthesis and type match):

```clojure
[[:declare A :type {}] [:declare x A {}]
 [:have <a> A x {}] [:have <a> A x {}]]   ; via elab-proof
```

Expected A: `[:ko {:msg "Have step elaboration failed: local definition already registered" :have-name <a> :meta {}}]`
(a returned `[:ko …]`, not a throw — the harness's negative check accepts
`:ko`-headed values).

R2† `:h3-2-declare-invalid` — invalid declared types, two cases:
(a) unparseable declare type (`elab-proof-step` `:declare` parse branch,
`proof.cljc:352`): script `[[:declare y (==> A) {}]]` → expected A
`[:ko {:msg "Proof failed at declare step: cannot parse type expression." :var y :type-expr (==> A) :meta {} :cause <parse-ko>}]`
(only `:msg`/`:var` are pinned in the verification script; `:cause` is the
parser's own `[:ko …]` payload and should be pinned at integration time
from the seat-run output). This is the least certain prediction in the
family: if upstream's parser throws on `(==> A)` instead of returning
`[:ko …]`, the seat run shows it and R2a must be re-expected as thrown.
(b) mismatched have type (`proof.cljc:80`): script
`[[:declare A :type {}] [:declare x A {}] [:have <a> :type x {}]]` →
expected A `[:ko {:msg "Have step elaboration failed: synthetized term type and expected type do not match" :have-name <a> :expected-type ✳ :synthetized-type A :meta {}}]`.

R3† `:h3-2-discharge-incorrect` — incorrect discharge, two cases, both
thrown `ex-info` (they propagate through `elab-proof`; A records them as
thrown, B/C as the equivalent error outcome):
(a) wrong name (`proof.cljc:157`): `[[:declare A :type {}] [:declare x A {}] [:discharge y {}]]`
→ `"Incorrect discharge name."` with `{:discharge-name y :var x :meta {}}`;
(b) empty context (`proof.cljc:149`): `[[:discharge x {}]]`
→ `"Context is empty (please report)."` with `{:discharge-name x :meta {}}`.

Refusals confer no arity credit (only `:pass` rows contribute); their
predicted trace value is diagnostic only — e.g. R1 still enters
`elab-have-impl/8` and R3a enters `elab-discharge-impl/6`, but neither may
be counted if integrated as negatives, and §5 does not count them.

## 4. Conservative-upstream note (binding for integration)

Pinned `update-var-deps/3` (`proof.cljc:109`) adds each new local
definition's name to **every** `var-deps` entry; the occurrence-filtered
body is present only as a comment (`proof.cljc:114`). Observable
consequences the fixtures assert rather than repair:

- after one named have, *all* live variables list that have as a
  dependency (F3 state assertions above; upstream
  `test-update-var-deps` asserts the same shape);
- discharging a variable therefore abstracts *every* live local definition
  over it, including definitions whose terms never mention it (`<a>` gains
  `[x A]` at F3's first discharge), and rewrites their local calls
  accordingly (`gen-local-calls` inserts the variable at references to
  abstracted definitions only — non-references and non-abstracted
  references are untouched, `proof.cljc:198`).

H3-2 preserves this behavior exactly. No fixture may expect the filtered
dependency sets, and no A/B/C disagreement arising from this
over-approximation is a defect to fix in this slice; "correcting" LaTTe
here would falsify the differential.

## 5. Credit prediction table

Credit kinds follow the harness rule (`kernel-exercise-entries`): *direct*
only when the fixture's own call head is the target function with the exact
argument count; otherwise *named-integration*, and only for IDs registered
in `kernel-named-integrations`, only on `:pass`, and only when the exact
`[guest-fn arity]` body-entry record appears in **both** C lanes. Every
fixture here enters through `check-proof`/`elab-proof`, so **no H3-2 credit
is direct**; all of it is named-integration. F1's ID is already registered;
F2†/F3† confer nothing until the integration round adds their IDs to
`kernel-named-integrations` (and their rows to `kernel_gate.edn`, §6).

| Fixture | Predicted arities (named-integration) | H3-2 acceptance line |
|---|---|---|
| F1 `:check-assume-have-discharge` | all 11 | named have (explicit), local-refers-to-local, nonempty-deps discharge, `check-proof` completion |
| F2† `:h3-2-elab-inferred-anonymous` | `elab-declare/7`, `elab-have/8`, `elab-have-impl/8`, `update-var-deps/3`, `update-def-uses/3`, `ref-uses-in-term/1` | named have with inferred type; anonymous have; `qed` completion (via `elab-proof`) |
| F3† `:h3-2-elab-discharge-states` | all 11 | authored discharge chain with pinned intermediate states; `qed` completion (via `elab-proof`) |
| R1†–R3† (negatives) | none (never credited) | source-backed refusal companions: duplicate definition, invalid declared type, incorrect discharge |

Per-arity witnesses (predicted; each must be confirmed from both C-lane
traces at integration, not from this table):

| Arity | Witnesses | Entered via |
|---|---|---|
| `elab-have/8` | F1, F2, F3 | `:have` steps (elab-have delegates to impl) |
| `elab-have-impl/8` | F1, F2, F3 | every `elab-have` call |
| `elab-declare/7` | F1, F2, F3 | F1: assume-generated declares; F2/F3: authored declares |
| `elab-discharge/6` | F1, F3 | F1: compile-generated discharges; F3: authored discharges |
| `elab-discharge-impl/6` | F1, F3 | discharges on nonempty `ctx` |
| `abstract-local-def/4` | F1, F3 | discharges whose head dependency set is `#{<a> <b>}` (nonempty) |
| `abstract-local-calls/4` | F1, F3 | same discharges, second reduction pass |
| `gen-local-calls/3` | F1, F3 | inside each `abstract-local-calls` (rewrites `<b>`'s term) |
| `update-def-uses/3` | F1, F2, F3 | each named have, twice (term and recorded type) |
| `update-var-deps/3` | F1, F2, F3 | each named have, once (recorded type; conservative) |
| `ref-uses-in-term/1` | F1, F2, F3 | inside each `update-def-uses` |

Marginal credit over the current branch state (§1) is zero for all eleven
while `:check-assume-have-discharge` passes — the family's contribution is
independent witnesses, branch coverage (inferred/anonymous have), pinned
discharge states, and refusals. If any target's trace is missing from a
passing F1/F3 at integration (e.g. a discharge whose dependency set is
empty on the guest leg), the plan's residual rule applies: add a minimal
corpus-derived helper fixture for that arity rather than weakening the
trace rule. The helper probes in the verification script
(`update-var-deps`, `ref-uses-in-term`, `gen-local-calls` direct calls,
mirroring upstream's own unit tests) are the ready-made starting points
for such residuals; they are diagnostics in this round and confer no
credit as designed.

Also preserved, per the plan: the existing fresh-supply and
immutable-prior-context regressions (`test-kernel-proof-fresh-supply`)
are untouched by this design — no fixture here mutates a prior context,
and F2's anonymous-have assertion (locals stay `#{<a>}`) is the
family-internal analogue.

## 6. Integration sketch and seat verification (next round, not this one)

Seat A-verification, before any harness edit:

```sh
clj -M:test -e "(do (load-file \"test/yang/latte_oracle/h3_2_a_verify.clj\") (yang.latte-oracle.h3-2-a-verify/-main))"
```

Expected: `H3-2-A-VERIFY: PASS`, exit 0. Any `FAIL` revises the matching
prediction above first (document + script together), never the other way
round.

Integration (after kimi's design review; exact harness edits for the
integration engineer):

1. Add to `kernel_gate.edn` (A form shown; the harness derives B/C):
   F2 `{:id :h3-2-elab-inferred-anonymous :project-proof? true :call (latte-kernel.proof/elab-proof env [] (quote [[:declare A :type {}] [:declare x A {}] [:have <a> _ x {}] [:have _ A x {}] [:qed <a> {}]]))}`,
   F3 `{:id :h3-2-elab-discharge-states :project-proof? true :call (latte-kernel.proof/elab-proof env [] (quote [[:declare A :type {}] [:declare B :type {}] [:declare f (==> A B) {}] [:declare x A {}] [:have <a> (==> A B) f {}] [:have <b> B (<a> x) {}] [:discharge x {}] [:discharge f {}] [:discharge B {}] [:discharge A {}] [:qed <a> {}]]))}`,
   R1–R3 as `:negative? true` rows with the §3 scripts (R2/R3 carry one row
   per case if the harness's one-call-per-row shape requires it).
   Note `:project-proof?` projects `[:ok [def-env term type]]` to
   `[:ok (term type)]`; F3's *state* assertions cannot live in that
   projection — they belong in a focused harness test beside the fixture
   (step-level `elab-declare`/`elab-have`/`elab-discharge` chain, as the
   verification script does on A), which is also where the B/C state
   comparison boundary should be proposed for review if the projected
   outcome proves insufficient.
2. Register `:h3-2-elab-inferred-anonymous` and
   `:h3-2-elab-discharge-states` in `kernel-named-integrations`.
3. Run the focused oracle, refresh evidence with
   `write-kernel-gate-evidence!`, and audit that every §5 witness
   re-derives from both C-lane traces (the gate test already enforces
   this); publish credited/withdrawn/remaining sets with fixture IDs.
   The gate stays `:blocked` on the remaining non-proof arities; no
   H3-2 result changes that.

Open questions for kimi's design review:

- R2a's rejection shape (returned `[:ko …]` vs thrown parse failure) is
  the one prediction the seat run may falsify; is pinning only
  `:msg`/`:var` at integration acceptable, or should `:cause` be pinned
  exactly once observed?
- Are F3's step-level state assertions wanted as a harness test (B/C
  threaded states), or is A-side state pinning plus the projected
  end-to-end outcome sufficient for the acceptance line "relevant state
  assertions"?
- Given §1 (the eleven are already credited on this branch), should F2/F3
  still be integrated as named integrations, or held as preparation until
  a credit withdrawal makes them load-bearing? This design recommends
  integrating them: witnesses that exist only in a document do not
  survive compiler changes.
