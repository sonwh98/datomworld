Created-GMT: 2026-11-13 00:30 GMT
Created-Local: 2026-11-13 07:30 +0700
Dispatched-By: master-green orchestrator seat (interactive)
Destination: codex exec resume -m gpt-6.1-terra (thread 01a1d1d4-c1b4-7512-a786-80fe6ea0df86)
Task-ID: 1794525000000-engineer-python-golden-remint-r3-riders

# R3: DOCS-CLASS rider correction on top of master (no value changes, no test runs)

The independent review (REVIEW (qwen3.8-max) section in your findings file) found every VALUE
of r1/r2 correct, but the ATTRIBUTION text wrong. It explicitly recommends: "land the values
(already done, 588ce3b5 == origin/master), then a single docs-class commit fixing items 1–3 in
the four test files, with a body carrying the cause set, the 78-byte accounting and the
verification matrix. No re-mint, no re-run."

## Do — exactly this, on a fresh worktree from origin/master (now `588ce3b5`+)

1. In all four test files (`float_address_test.cljc`, `linked_prelude_test.cljc`,
   `linked_safepoint_test.cljc`, `prelude_parity_test.cljc`): replace every "P9 math builtins"
   rider with the true cause — the Python exception-track/provenance series `04e58031` +
   `2ab72b31` + `9c97fae3` + `00ca2d1c` + `7f10befa` (merged `8018005d`). Provenance footprint
   entries (`:cell/provenance`, `:cell/new-provenance`) grow every unit's hashed footprint by
   78 CBOR bytes; the corpus delta is exactly that (10 addresses rotate, all else byte-identical).
2. Add the missing rider at `linked_prelude_test.cljc:190`: the 70→71 is the provenance series
   adding one runtime key; say which kind (read `runtime-keys` + the merge's diff to name it
   honestly — likely the provenance egress/mint helper key; DO NOT guess a name, quote the
   actual symbol you can verify).
3. `prelude_parity_test.cljc`: explain WHY `"type"` enters `builtin-function-names` and 25→26
   (the R2/R3 reception series added the `type` builtin to the prelude — verify against
   `prelude/builtin-functions` at these commits before writing), with a rider naming `04e58031`
   or the exact commit that introduced it.
4. Commit ONE docs-class commit: subject
   `docs(yang.python): correct the golden-pin riders — provenance series, not P9` + body with
   the cause set, the 78-byte accounting, and the verification matrix from the review.
5. Checks instead of test runs (docs-only per the routing table): `git diff --check` clean AND
   `clj -M:kondo --lint` on the four files (catches any docstring you accidentally unbalanced).
   Nothing else.

Deliverables: push branch `python-golden-remint-r3`, append a dated section to the same findings
file with the diffstat, the naming you verified for the extra runtime key, and kondo rc.
