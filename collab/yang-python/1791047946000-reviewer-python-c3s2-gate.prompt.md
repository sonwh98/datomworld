Created-GMT: 2026-10-03 17:19:06 GMT
Created-Local: 2026-10-04 00:19:06 +07 (+0700)
Coding-Agent: glm (session c5a79e9a-8f28-46f1-a3ee-604e182857fc) and codex (gpt-6.1-sol, fresh thread, ID pending)
Session-ID: c5a79e9a-8f28-46f1-a3ee-604e182857fc (glm); pending (provider-generated) (codex)

# Task: Python C3-S2 (numeric dict/set keys, numeric-key deletion) independent gate

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: glm-5.3-flash | Assigned: 2026-10-04 00:19 +07 | Status: active | Rationale: routing-status 2026-10-03 (glm via CLI); different family from the Claude-family author; static pass
- Model: gpt-6.1-sol | Assigned: 2026-10-04 00:19 +07 | Status: active | Rationale: standing rule, gate reviews are gpt-6.1-sol fresh threads; independent second reviewer. Neither reviewer sees the other's report.

Perform a read-only review of the UNCOMMITTED C3-S2 change in /Users/sto/workspace/datomworld-py-c3key1 (branch yang-python-c3-s2, rebased
onto master eaf7d6f0). IMPORTANT: the index there holds unmerged entries left by a stash pop, so use `git diff HEAD` (not plain
`git diff`) to see the whole change: 9 files, +453/-62: docs/design/yang.antlr.md, src/cljc/yang/python/antlr/prelude.cljc (~+136),
src/cljc/yin/vm/data.cljc (-16), test/yang/python/antlr/{e2e_c2_test.clj,e2e_test.clj,float_address_test.cljc,prelude_parity_test.cljc,safepoint_test.cljc},
test/yin/vm/data_test.cljc. Governing text: docs/design/yang.antlr.md 8.5.4 (C3 integers; dict/set keys at ~2078-2096) and 8.5.5 (float addresses;
"C3-S2 replaces this numeric arm with ruling-6 decimal-string keys, pins one NaN-key behavior, and deletes `numeric-key`").

What the change claims to do:
- Numeric dict/set keys become ruling-6 reduced-rational keys `[:py.numeric/finite numerator-decimal denominator-decimal]` replacing C1's double
  normalization: `True`, `1`, `1.0` share 1/1; `False`, `0`, `+-0.0` share 0/1; `1.5` is 3/2; 2^53 and 2^53+1 stay distinct; infinities get signed keys;
  tuples normalize recursively; the non-numeric (identity) and unhashable arms are preserved; the insertion-order vector keeps the first original key.
- Float keys and `hash()` read the float's value through `data/float-value`, so the JS Float64 carrier (whose `valueOf` now throws :carrier-coercion) is never coerced.
- Every NaN shares ONE key, `[:py.numeric/nan]`. The architects are confirming this separately; judge only whether it is implemented
  consistently, tested, and documented, not whether it is the right policy.
- `data/numeric-key` (the float-fix interim) is deleted: its definition and export (data.cljc), its host-names entry (prelude.cljc), and its test block (data_test.cljc).
- Six content-address goldens in float_address_test.cljc were re-minted because the prelude changed; the hook-prelude golden is unchanged;
  `dict-keys-test`'s expected keys and canonical-bytes golden were re-pinned.

Check, with file:line evidence:
1. Key exactness: build the reduced-rational key by exact decomposition for ints, bools, finite floats (subnormals, +-0.0, huge floats, integral floats),
   bignums if reachable; reduced form, sign placement, decimal strings with no leading zeros; 2^53 vs 2^53+1 distinct; 1 / 1.0 / True one key; -0.0 vs 0.0
   one key; inf vs -inf distinct; NaN one key. Any case where two Python-equal numbers get different keys, or two Python-distinct numbers share one?
2. Cross-host exactness and the carrier: every float read goes through `data/float-value`; no implicit coercion of a carrier anywhere on the new paths (JS);
   no `(= x x)`-style NaN test that is host-dependent (the engineer notes `py/float-mod`/`py/float-divmod` still use `(= y y)`; that is pre-existing and out of scope, but flag
   any NEW use). ClojureDart traps: `:cljd` FIRST in any reader conditional, no unary minus on floats (use `(* -1.0 x)`), cljs keyword identity.
3. Completeness of the deletion: no remaining reference to `numeric-key` in src/, test/ or docs/ other than the one deliberate historical sentence in 8.5.5 (~2268); the export-set
   test in data_test.cljc matches the new exports; nothing else used it.
4. Tests: do the new e2e and parity rows pin the ruling's list (True/1/1.0, 0/-0.0, 1.5, 2^53 vs 2^53+1, inf/-inf, NaN, tuple recursion, the identity and unhashable arms,
   insertion order keeps the first key) on every VM and host? Can they fail? Any assertion weakened or removed versus master (compare the master tests)?
5. The merge: safepoint_test.cljc and prelude.cljc had rebase conflicts. Confirm master's `^:slow` tag and `dao.test-slow/guard` wrapper on tail-preservation-test are intact and the
   engineer's additions (integer-module require/registration) did not change that test's assertions. Confirm no conflict marker or duplicated hunk remains.
6. Docs: the three yang.antlr.md edits (NaN bullet 8.5.4, the 8.5.5 sentence ~2288, the C3 status bullet) are accurate to the code; flag any now-false statement.
7. Scope: nothing beyond the ruling.

Already verified by the orchestrator's tooling (untrusted by you, do not re-run): the engineer reports JVM 2906 tests/226619 assertions/0 failures, Node 2720/91647/0, Dart
2675 all passed, five Python slow tests green, kondo and cljstyle clean. The orchestrator re-runs the full lanes independently. Do NOT run suites.

Do not edit. Treat prior reports as untrusted. Complete in one turn.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix
State "No actionable findings" when appropriate. End with an explicit ready-to-commit verdict.
