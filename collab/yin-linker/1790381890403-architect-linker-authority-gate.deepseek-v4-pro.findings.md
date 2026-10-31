Completed-GMT: 2026-09-26 02:05:00 GMT
Completed-Local: 2026-09-26 09:05:00 +0700

# Review: `yin.vm.linker` authority policy — M4 entry slices A1+A2

Adversarial review + security audit + architect gate of the uncommitted work (branch `ucf-authority`, base `0fc931fc`). Read-only; spec = `docs/design/yin.vm.linker.md` §8.2 (L1673–1799) and §9 M4 entry list (L1931–1941). Three files: NEW `src/cljc/yin/vm/linker/authority.cljc` (329), NEW `test/yin/vm/linker_authority_test.cljc` (340), EDIT to the spec doc.

## 1. Spec conformance

Implemented faithfully and completely. Mapping to §8.2:

- **Two proof kinds** — `proof-state` (`authority.cljc:66-101`): `:yin.module/signature` calls composition `:verify` over `jing/canonical-bytes` of the whole envelope (`:77-87`); `:yin.module/attested` checks proof `:yin.module/attested` == decl `:dao.stream/identity` AND event carrier `:dao.stream/identity` == decl (`:89-99`). Both exact.
- **Proven/unproven/undeclared** — unproven → `:no-proof`/`:bad-proof` (`:unauthenticated`, `:107-111`); undeclared → `:undeclared-principal` (`:257-261`).
- **Three per-principal passes** — `honor-passes` (`:130-177`): dedup by content id, then equivocation-on-equal-seq dropping that seq and everything later, then order-and-honor strictly past the floor. Matches L1719–1743 exactly.
- **Retraction binding** — by content id only, to a honored same-principal assertion (`:287-304`); `:dangling-retraction` otherwise (L1710–1713).
- **Equivocation / replay** (`:149-158` / `:160-168`), **floor** (`:160`, decl `:seq-floor`), **snapshot-as-rebuilt-state** (`:329` echoes, never dereferences; advance = fresh call), **`:ambiguous-name` naming every address+asserter** (`:210-213`), **provenance** (`:204-209`).

No §8.2 requirement is unimplemented.

## 2. The seven interpretive choices

1. **Key names** — RIGHT. Nearly all are named verbatim in §8.2 (`:yin.module/envelope`, `:yin.module/proof`, `:yin.module/op`, `:yin.module/manifest`, `:yin.module/asserted-by`, `:yin.module/seq`, `:yin.module/of`, `:yin.module/signature`, `:yin.module/attested`, `:dao.stream/identity`). Genuinely new: `:seq-floor` (decl key, `:224`) and result key `:honored-seq` (`:323-328`). **Record in spec.**
2. **Three extra diagnostic kinds** — DEFENSIBLE. `:malformed-envelope` is the A1 shape gate (outside §8.2's four kinds); `:undeclared-principal`/`:no-proof-kind` cover "ignored"/"cannot assert anything". **Record.**
3. **Name entries exist `:absent` for every shape-valid assert** — RIGHT (the "zero is `:absent`" fold, L1784–1786; malformed registers no name, `:253-255`). Tested.
4. **Retraction binds to honored same-principal assertion by id** — RIGHT (L1710–1713). Tested.
5. **Equivocation discards pair + later as `:equivocation`** — DEFENSIBLE (spec silent on later-envelope kind). **Record.**
6. **Provenance vectors** — RIGHT (necessary for the multi-asserter collapse). Note `:yin.link/proof-kind` uses `distinct` (`:207-208`).
7. **`:honored-seq` seeds next floor** — RIGHT and necessary (carries "highest sequence already honored" the composition must declare back). **Record.**

## 3. Fail-closed and security

No path honors or suppresses via a bare/forged assertion, a copied attested assertion, an undeclared principal, a replay, or an equivocating pair — signature covers the whole envelope; carrier identity gates attested proofs; dedup runs before equivocation so a genuine duplicate can never be misread (tested); retraction is id-scoped so a same-name sibling survives (tested).

Findings:

- **P2 | `authority.cljc:36-38` (used `:50`, `:54`) |** The private `segment-address?` only checks `(keyword? v)` + namespace `"segment"`, a weak re-implementation of the public `jing/segment-address?` (`dao/jing.cljc:349-353`), which validates algorithm-id, digest length, and hex digits. Consequence: `:segment/fake` (or `:segment/`) passes the A1 shape gate as a "well-formed" `:yin.module/manifest`/`:yin.module/of` instead of `:malformed-envelope`. Not a security hole (forged/undeclared still caught; a declared principal may sign any manifest; downstream fetch would reject a non-address), but a reuse violation + a fail-closed slack in the shape gate. **Fix:** drop the private defn; call `jing/segment-address?` at both sites.

- **Missing keys / non-map / non-map event / unknown op** — all fail-closed (`envelope-defect` `:41-59` → `:not-an-envelope`/`:bad-op`; nil-guarded diagnostics `:119-122`; non-map event guarded `:245-246`). No crash.
- **Huge input** — linear folds + a single `sort-by`; no `n²` path.
- **Determinism** — one answer per input: only `sort-by :seq` (ints) and the string sort `(str address " " asserter)` (`:191`); no host-dependent ordering affects any value.

## 4. Purity and cross-host

PURE. No atom/clock/random/host state; no reader conditionals; sole IO boundary is the composition-supplied `:verify`. Core fns (`int?`, `group-by`, `sort-by`, `distinct`, `if-some`, …) are used elsewhere in the cljc corpus (verified in `telemetry.cljc`, `waitset.cljc`, `jing/cbor.cljc`, etc.), so JVM/Node/Dart are safe. No `:cljd` reader trap (zero reader conditionals).

## 5. Tests

The 13 deftests map one-to-one onto the eleven §9 entry clauses + the A1 shape test; each asserts the exact discard kind/outcome so each can fail (implementer's red→green + mutation checks confirm):

1. signed-assertion-by-declared-principal-resolves (also provenance + `:honored-seq`)
2. bare-…without-proof-is-unauthenticated (`:no-proof`)
3. bad-signature-is-unauthenticated (`:bad-proof`, signs a different envelope)
4. attested-…copied-onto-another-stream-is-unauthenticated (both halves)
5. undeclared-principal-is-ignored (co-asserted name not made ambiguous)
6. signed-retraction-removes-only-the-assertion-it-names
7. dangling-retractions-are-discarded (no-assertion + other-principal)
8. exact-duplicate-envelope-is-honored-once
9. replayed-sequence-is-discarded (floor 5)
10. equivocating-pair-is-discarded (pair + later)
11. two-proven-assertions-refuse-ambiguous-name
12. snapshot-advance-rebuilds-never-rereads
13. malformed-envelope-is-discarded (A1)

Untested §8.2 edges (non-blocking):

- **P3 | `authority.cljc:101,110` |** `:no-proof-kind` ("a principal declared with neither kind cannot assert anything") has no test.
- **P3 | `authority.cljc:204-209` |** the multi-asserter single-address success (two principals, one name→one address → `:ok` with two asserters) has no test — the only path where `distinct` collapses same-kind proof kinds.

The signature stand-in (`fake-sign`/`fake-verify`, test `:25-36`) is faithful: it signs the same `canonical-bytes` the linker hands to `:verify`, so a signature valid for one envelope fails for any other.

## 6. Scope

Exactly three files; `linker.cljc` untouched (authority requires only `dao.jing`). Spec edit is minimal and accurate (one §8.2 sentence + one §10 file-box line). Slice A3 correctly out of scope.

## 7. Verdict

No P0/P1. Defects vs deferred work:

- **P2** (reuse/robustness): weak private `segment-address?` — trivial fix using existing public `jing/segment-address?`.
- **P3** (coverage): no `:no-proof-kind` test; no multi-asserter single-address test.
- **Deferred** (spec recording, not code): `:seq-floor`, `:honored-seq`, the three extra diagnostic kinds, and the equivocation-later-envelope kind are defensible owner/Architect decisions §8.2 only names conceptually — recommend one follow-up doc sentence each.

The §9 entry criterion — "the §8.2 assertion policy is implemented and its tests pass" — is met: every enumerated clause is implemented and tested, and the code is pure, deterministic, and cross-host clean. The P2 is worth fixing before A3 ingests real datoms (where malformed `:segment/…` addresses are more likely to appear), but it does not break the entry criterion.

Verdict: READY
Sign-off: GRANTED
