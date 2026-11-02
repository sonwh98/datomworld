Created-GMT: 2026-10-29 15:35:00 GMT
Created-Local: 2026-10-29 22:35:00 +07
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: yang-slice9
Role: Compiler & AST Engineer

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-29 22:35:00 +07 | Status: active | Rationale: the program's close-out gate slice.

Work in /Users/sto/workspace/datomworld-yang-s9 (branch yang-slice9, off master@f74f2cec which contains slices 1-8). You may edit ONLY:
- test/yang/latte_oracle_test.clj
- test/yang/latte_oracle/ (end-to-end fixture additions)
Nothing under src/ is edited by this slice — it assembles and records the gate evidence.

THE PLAN slice 9 ("Close the kernel-level differential gate"):
/Users/sto/workspace/datomworld/collab/1793128583916-architect-yang-latte-gap-plan.gpt-6-astra.findings.md

DELIVERABLE — the end-to-end differential gate over the admitted corpus:
Assemble the admitted dependency graph and execute REAL parsing, substitution,
normalization, typing, and proof checking end to end, both lanes (walker + semantic),
comparing against upstream latte-kernel on the host (the A leg). Minimum acceptance
set per the plan:
- presyntax/parse: valid binders/applications and malformed inputs.
- syntax/subst: capture avoidance and shadowing.
- nbe/norm, norm/normalize, norm/beta-eq?: beta/delta reduction and opacity.
- typing/type-of-term, type-of, type-check?: valid and invalid terms.
- proof/compile-proof, elab-proof, check-proof: accepted proofs, rejected proofs,
  incomplete scripts, steps after qed.
- Unparser and implicit-extension fixtures using guest closures.
Every admitted function/arity exercised directly or through a named integration
fixture. Any unexplained mismatch, unsupported reachable dependency, or silently
skipped negative case blocks the gate: report them as blockers with the minimal
repro, do not work around them.

Also record: the admitted function/arity count (from the manifest) vs how many are
exercised (directly or transitively), listing any admitted-but-unexercised entries;
per-fixture A-outcome, C-walker-outcome, C-semantic-outcome; the exact commands and
counts. Where a fixture cannot run (an unsupported dependency reachable only through
it), classify it explicitly as blocked-by and name the dependency.

This slice EDITS ONLY the oracle test namespace + fixtures; if a compiler gap
surfaces, it is a BLOCKER to report, not something to work around — the gate's
honesty is the deliverable.

Verify: clj -M:test -n yang.latte-oracle-test -> 0 failures (plus the usual four
suites unchanged). Do not commit.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
