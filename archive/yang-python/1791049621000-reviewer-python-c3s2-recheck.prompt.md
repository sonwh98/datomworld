Created-GMT: 2026-10-03 17:47:01 GMT
Created-Local: 2026-10-04 00:47:01 +07 (+0700)
Coding-Agent: glm (resume c5a79e9a-8f28-46f1-a3ee-604e182857fc) and codex (gpt-6.1-sol, resume 01a102c7-32e7-7b22-961c-001610af6066)
Session-ID: c5a79e9a-8f28-46f1-a3ee-604e182857fc (glm); 01a102c7-32e7-7b22-961c-001610af6066 (sol)

# Task: Python C3-S2 gate re-check (after round 5)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: glm-5.3-flash | Assigned: 2026-10-04 00:47 +07 | Status: active | Rationale: resume of its static gate to confirm the fixes
- Model: gpt-6.1-sol | Assigned: 2026-10-04 00:47 +07 | Status: active | Rationale: resume of its gate thread to confirm the fixes

Resume your C3-S2 gate for the CURRENT tree in /Users/sto/workspace/datomworld-py-c3key1 (branch yang-python-c3-s2, now REBASED onto master a932bb55, which contains the
landed range fast path). The change is STAGED: use `git diff --cached` (9 files). Since your reports, the engineer applied round 5, and the architects (fable-5.1 and
gpt-6-astra, both) APPROVED the one shared NaN key `[:py.numeric/nan]` and amended the earlier ruling (their text: collab/1791047919000-architect-c3s2-nan-key.*).

The orchestrator independently checked your original findings:
- sol P2 (the "unhashable" test went through py/hash, not py/key; key arms had no portable coverage): AGREE; the engineer added a key-arms test through `py/key` (identity objects stay
  distinct, list/dict/set keys and tuples containing them raise TypeError, set dedup keeps the first original key). Verify it.
- sol P3 and glm P2 (docs still claimed three data exports including `numeric-key`): AGREE; rewritten to two exports. Verify no other stale mention.
- glm P3 (py/hash comment omitted the tuple arm): AGREE; comment now names it.
- glm P3 (a plan doc mentions a vanished fixture): NO ACTION, historical.
- The architects' additions were applied: the NaN equivalence-class doc sentence, the three observable cases, the ~2130 sentence, the known-limits bullet (integer digit limit; 1075-bit
  denominator), NaN lookup/set/tuple tests including a decoded canonical NaN, boundary-key fixtures (max finite, negative min subnormal, smallest normal, largest subnormal), and the
  carried-over range-guard pin rows (index 2^26 fast path; 2^26+1 enters `py/range-elem`) in prelude_parity_test.cljc.

New code to review, not previously seen by you:
1. `py/int-canon` `(if (= n 0) 0 n)` used in `py/key`'s integer branch and `py/hash`: the engineer found that on JS `(* -1 0)` is `-0`, which the integer module rejects. Is this the right
   place and form (note it avoided `(+ n 0)` because `+` throws on a JS/Dart BigInt)? Any path with an integer that still bypasses it? Any host where `(= n 0)` misbehaves for a big carrier?
2. The engineer found that its own round-3 tuple test compared two quoted vector literals (prelude vectors are data, contents never evaluated) and fixed it by building vectors with `py/conj`.
   Check ALL new and old tests in the diff for the same mistake: any test that builds a vector/tuple literal in prelude notation and passes it where evaluation of the elements is needed.
3. It changed the sentinel in the stub test `range-fast-path-on-every-host-test` (a landed test) from a keyword to the number -12345, because `range-at` compares the stub's result with `stop`
   and a keyword throws on the JVM but not on JS. Does the stub test still prove what it must (a broken or never-taken fast path makes it FAIL on all four VMs)? Is the new sentinel safe on JVM, Node and Dart?
4. Re-minted goldens (prelude root, A, A', record-address, prelude-id; the hook-prelude golden and the dict-keys canonical bytes unchanged in this round): confirm only those lines changed relative to master's
   file apart from the intended `dict-keys-test` re-pin and the integer-module registration.
5. Optional C5 (a small-limit refusal test) was NOT done, because the guest-exception mapping for integer-module refusals is not implemented yet; the docs now say today such a refusal fails the run. Agree?

Re-read only relevant design, source and test lines. Challenge these conclusions. Do not repeat resolved findings unless the fix is incomplete. Do not edit files. Do NOT run suites (the orchestrator is
running the full JVM, Node and Dart lanes on this exact tree now).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Return: finding | final disposition | evidence | remaining action. Report new defects as P0-P3 | file:line | evidence | concrete fix.
Explicitly state whether the change is ready to commit.
