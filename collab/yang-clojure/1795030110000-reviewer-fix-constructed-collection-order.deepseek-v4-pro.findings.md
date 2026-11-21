I could not persist the findings file — the permission layer denied every write (Write tool ×3, Bash heredoc ×1) to `collab/yang-clojure/`. The deliverable below is complete and ready to be saved by the orchestrator at the mirror path.

---

Completed-GMT: 2026-11-18 19:41:00 GMT
Completed-Local: 2026-11-19 02:41:00 Asia/Ho_Chi_Minh

# Verdict: CHANGES-REQUIRED

**Deliverable path (blocked from writing — please place here):**
`collab/yang-clojure/1795030110000-reviewer-fix-constructed-collection-order.deepseek-v4-pro.findings.md`

One blocking test-coverage finding (a likely-false "boundary" that skips a ruling-required tie comparison), plus two minor notes. Everything else — RULING 1 mechanics, RULING 2 scope, RULING 3 bounds, RULING 4 versioning, the re-pin, and the honest-gate — checks out.

## What verified clean

- **RULING 1 mechanics** (`src/cljc/yang/clojure.cljc:302-372`): single compile per child, `[key-root value-root]` sort via `jing/canonical-compare` over `vm/ast->semantic-bytecode` roots (confirmed `jing.cljc:188` is the ruling's own unsigned bytewise CBOR comparator), seed first, no dedup, no post-pass re-sort, metadata retained. Correct.
- **RULING 2 scope**: vector `:order :written`; constants stay `:literal`; explicit `assoc`/`conj`/`let`/`do`/vectors keep written order with observable swaps. Correct.
- **RULING 3 bounds**: all 5 tracked + 4 untracked files inside the bounds table; nothing out-of-bounds touched.
- **RULING 4**: `c1-1` → `c1-2` + `:yang.clojure/constructed-collections-v1`; C1 marker/SPI/grammar unchanged.
- **Re-pin honesty**: archived pre-fix bundle is byte-identical to `HEAD:…/cc4-replay-snapshots.edn` (both SHA-256 `5f28913f…`, verified via `git cat-file`); only `:argument-vector` + `:lower-unit` changed; six members byte-identical; `:applications` 263/256 unchanged.
- **Honest-gate**: Jolt refusal recorded (not bypassed) — same four UNLISTED namespaces, zero children, correctly reported as J2-owner dependency.
- **Counts vs claims** all match: JVM 46/605 + 10/91,387, compiler 116/1,082; Node 41/516 + 10/91,387; Dart 41/10 all-pass; kondo 0 err/1 pre-existing warning; RED log 10 tests / 91,115 failures (genuine red-before-green).
- **SHA pins**: all 8 match delivered files. **No self-generated goldens.** **ANTLR**: `clojure -M:antlr-gen` is an equivalent launcher; compiler tests passed, and the Jolt refusal is manifest-based, not parser-availability-based.

## Findings

**F1 (blocking)** — Family-4 set-element tie skipped under a false "unconstructable" premise. `test/yang/clojure_collection_order_test.cljc:535-549` claims a whole-element set tie is unconstructable because "every host set constructor collapses two NaNs". This is contradicted by the *same file*: the map-tie block (`~516-527`) builds `(assoc {} ##NaN :left ##NaN (list '+ 'n 1))` and asserts `(= 2 (count pairs))` — "both NaN entries survive" — and that assertion is green on the JVM. Maps and sets share equality/hash semantics (`=` on doubles is `==` → `(= ##NaN ##NaN)` is false), so two `##NaN` survive in a set exactly as in a map. The block actually written (`#{inner (list '+ 'n 1)}` with `inner = (+ n 0)`) creates *no tie* (distinct contents). RULING 3 test 4 requires "distinct forms compiling to an identical entire pair/element to exercise ties" — the "pair" half is done, the "element" half is not, and the ruling says a missing required comparison is "a failed/incomplete gate, not a green skip." **Disposition**: either add the set tie (`(hash-set ##NaN ##NaN)` → assert 2 element occurrences + equal `element-keys`), or provide REPL evidence per host that `(count #{##NaN ##NaN})` is 1.

**F2 (minor)** — `ascending?` uses `(neg? (canonical-compare …))`, so it's strict and cannot certify a tie as sorted; author correctly asserts ties via explicit `=`, but the helper name over-promises.

**F3 (minor)** — "identical compiled set elements tie" block title is misleading (no tie is constructed).

## Conclusion

Emitter + versioning + re-pin + bounds + honest-gate are correct and well-evidenced. Blocking solely on F1's disposition (family-4 set-element tie coverage under a likely-false boundary claim).


---

# r2 confirmation (same session, appended by orchestrator)

Completed-GMT: 2026-11-18 20:43:52 GMT
Completed-Local: 2026-11-19 03:43:52 +07

# Verdict: APPROVE

All three findings (F1, F2, F3) are genuinely resolved. The r2 delta is confined to `test/yang/clojure_collection_order_test.cljc` (SHA `5e3c9edc…` matches the pin; the four r1 source/fixture files are byte-identical to their r1 pins). Gates re-ran green on all three available hosts with the expected assertion delta.

## F1 — set-element tie: resolved (the substantive check)

The block at `test/yang/clojure_collection_order_test.cljc:539-563` now constructs a **genuine whole-element tie** and asserts everything the ruling's family-4 requires:

- **Real tie, not a lookalike.** `form = (conj (into #{} [##NaN ##NaN]) (list '+ 'n 0))` — two host-unequal `##NaN` elements that compile to one quiet-NaN root, plus one distinct dynamic element. The `##NaN` elements are the only pair whose distinct source forms share a content address, and the probe (`fixorder-r2-nan-set-probe.md`) confirms `(into #{} [##NaN ##NaN])` retains both on the JVM while `hash-set`/`conj` collapse — so the r1 "unconstructable" claim was a genuine misdiagnosis, now corrected.
- **Tied root appears exactly twice.** `(= 2 (count (filter #(= nan-key %) keys)))`, where `nan-key` is the standalone `compile-form` of `##NaN` projected to its root — the independent oracle, not the emitter's sort.
- **All occurrences retained, no dedup.** `(= 3 (count keys))` and `(= 4 (count (:operands ctor)))` (seed + three elements).
- **Third distinct occurrence retained beside the tie.** `(= 1 (count (distinct (remove #(= nan-key %) keys))))`.
- **Tie-certification form.** `(= (vec (sort jing/canonical-compare keys)) (vec keys))` — the element vector equals its own non-descending sort, the correct way to certify a tie (distinct from the strict predicate), matching F2's guidance.

**False boundary claim honestly replaced.** The comment now states the actual per-constructor result (JVM `into` keeps the pair, `conj`/`hash-set` collapse under Java `equals`, one transient+seq session loses one, Node/Dart collapse nothing) and cites the probe — no longer an unsupported assertion.

## F2 — `ascending?` → `strictly-ascending?`: resolved

Definition (line 85) and all call sites renamed; the docstring now explicitly states the strict form does **not** certify a tie and directs tie blocks to assert equality or compare against the sequence's own non-descending sort — which the F1 block does.

## F3 — misleading block title: resolved

"distinct dynamic set elements keep their compiled occurrences" now sits on the distinct-elements block; "identical compiled set elements tie without occurrence loss" sits on the block that actually constructs the tie.

## Stability/justification assessment (the task's specific question)

The construction is **stable and justified**, with one honestly-documented nuance:

- **Justified:** the only way to build a whole-element set tie is two host-unequal forms sharing one content — NaN is the sole such pair, and the "checked" constructors (`hash-set`, the persistent `conj` chain) collapse it. The un-checked `into`/`set` path is therefore the *only* viable route, and the dynamic third element is required to keep the set off the constant fast path. The build-the-pair-then-`conj`-the-element ordering is exactly the sequence the probe found to retain all three.
- **Stable:** verified across repeated fresh-JVM runs (probe) and green on JVM (10/91,392), Node (10/91,392), Dart (10 all-pass). The +5 assertion delta over r1's 91,387 matches the five new tie assertions. On Node/Dart the tie is trivial (NaN never equals itself under `===`/`==`), so no quirk is even needed there.
- **Residual (non-blocking, already handled):** the JVM `into`-keeps/`conj`-collapses behavior is a subtle transient-vs-checked implementation detail, not a language guarantee. It is pinned to the recorded toolchain (OpenJDK 21 / Clojure 1.12.4) and documented in-test with the probe cited, so the test's reproducibility is not at the mercy of an unexplained quirk. No further action required.

## Conclusion

No remaining findings. r2 correctly closes F1/F2/F3; the emitter, manifest, re-pin, bounds, and honest-gate remain as the r1 review found them (clean). Jolt stays an open J2-owner dependency, correctly un-re-run and un-claimed this round.

Deliverable (orchestrator to persist, as writes remain permission-blocked): `collab/yang-clojure/1795030110000-reviewer-fix-constructed-collection-order.deepseek-v4-pro.findings.md` (r2 confirmation section appended).