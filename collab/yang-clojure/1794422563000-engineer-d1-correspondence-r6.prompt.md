Created-GMT: 2026-11-13 18:1x GMT
Created-Local: 2026-11-14 01:1x +0700
Coding-Agent: codex
Session-ID: (resume of thread 01a1d5bd-11ef-7513-9672-97c79d26f3bc)

# Task: D1 continuation r6 — the INDEPENDENT verifier (design §4.2's discipline, the heart of D1)

Role: VM Engineer (compiler provenance)

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-14 01:1x +0700 | Status: active | Rationale: continuity

## State resume (by state)

Worktree `d1-correspondence` @ `56cc5ca3` + UNCOMMITTED r1-r5. r5 landed the amended fidelity (profile `:fidelity-rule :fixed-jvm-integer-wrappers-v1`, new content address), the real fixture's capture passing, and the producer-side static replay+reopen pilot — whose stated crucial limit is that it SHARES `kernel-stamp-sites`, `cc3-oracle-binding`, and the oracle source-hash function with the producer, so it is NOT independent verification. Findings chain r1-r5 (mirrors + canonical copies). The original brief + r2/r3 briefs govern; design §4.2 is the authority for this round.

## The one item — build the INDEPENDENT verifier

Design §4.2's discipline: "a verifier must not call the producer's table derivation as its only 'independent' check." Your own r5 findings name the shared machinery: `kernel-stamp-sites`, `cc3-oracle-binding`, the oracle source-hash function. The verifier must:

1. **Re-derive the oracle site declarations from the compiled argument expressions** — its own traversal of the post-port compiled trees (the r5 findings state the shape: `:site`, `:callee`, `:arg-shapes`, `:def`, `:delegation?` per fixture), NOT calling `kernel-site-table`/`kernel-stamp-sites`/the producer's helpers. Where the derivation logic is genuinely shared mathematics (e.g. the arg-shape classification rules), the verifier implements its own copy from the design/spec text and the tests prove producer-vs-verifier agreement on the corpus — agreement between two implementations is the independence evidence; calling one function is not.
2. **Re-derive the canonical-source-hash** per its own implementation of the pinned algorithm (the r4 profile pins the printing/reading; the hash's traversal/gensym-normalization rules are in the oracle's source — implement the verifier's own version from the SPEC, prove equality on the corpus).
3. **Reconstruct and check the bindings**: for each fixture, the verifier's independently derived site set and the record's origin-bindings must agree with the producer's `cc3-oracle-binding` output — including the stamped-to-unstamped correspondence (2 applications per wrapper, the r5 accounting) and multiplicities.
4. **Execute the scoped attribution query** over both C lanes per design §4.2 step 6, from the record's own data.
5. **Publish the separate verification result** — the record's address, the verifier revision, inputs, per-fixture outcomes. The verifier's success is what authorizes reliance, per §4.2 step 7; the producer's own green tests never are.

Independence discipline: the verifier lives in its OWN namespace (e.g. `yang.correspondence-verifier` or similar — propose), shares NO code with the producer path beyond the language and already-landed public APIs it necessarily uses (`vm/occurrences`, `jing` addressing, the format contracts), and its tests include at least one negative: a mutated producer table that the verifier REJECTS (the design §4.2 step-4 rule).

## Bounds/invariants/deliverable

The corpus fixture scope is the pilot fixture set from r5 (per-fixture independence: one pass licenses nothing — keep the per-fixture gate). The verifier's re-derivations run on the POST-port compiled trees per the rebind note. Same bounded files + the new verifier namespace; forbidden set unchanged; v1 bytes frozen; no tagged run (the bounds declaration + tagged verification stay r7+). Red-first: the mutated-table rejection test is your red. Verification: verifier-vs-producer agreement on the corpus, the negative rejection test, the standing nets green, kondo 0 errors. Deliverable: findings r6 (mirror + say-so). Commit NOTHING.

If the verifier's independent derivation DISAGREES with the producer on any fixture: that is a DEFECT REPORT (producer or verifier bug — attribute it), not something to reconcile by hand. Stop at the first substantive disagreement, record it, and deliver the partial with the disagreement as the headline.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
