Created-GMT: 2026-10-30 03:40:00 GMT
Created-Local: 2026-10-30 10:40:00 +07 (+0700)
Coding-Agent: codex (fresh session)

# Task: S3 pins — re-derive the moved addresses on the merged head

WORK CHECKOUT: /Users/sto/.codex/worktrees/yang-common-call/datomworld (branch python-s3-wiring,
tip e133e822 — the S3 wiring on top of master 64f2885a + the yang-call merge). CLEAN TREE expected.

THE FAILURE CENSUS (shiva, all three lanes):
- JVM quick: 18 failures — gate_test.clj:1003 independent-traversal-agrees-with-the-committed-closure
  (×3: the walked closure includes src/cljc/yang/core/call.cljc; the committed closure list does not)
  + float_address_test.cljc (~15: prelude-id and program addresses moved).
- Node (16 failures): program-addresses/prelude-addresses/program-address/prelude-address tests + a
  relative-imports and a preseeded-imports test.
- Dart (13+: "...and 9 more"): yang.typescript.antlr.float-address-test (prelude-address,
  program-address) + yang.python.antlr.float-address-test (prelude-addresses, program-addresses) +
  more.

THE WORK:
1. Re-derive EVERY moved pin mechanically from failing assertions at e133e822: the Python float
   addresses, the TYPESCRIPT float addresses (yang.typescript.antlr.float-address-test — NOTE: this
   is the TS/PHP orchestrator's ownership surface; the re-derivation is forced by the shared
   runtime's bundled-prelude emission which the convention requires; every re-derived value goes in
   the report for THEIR review, and the commit message flags it), and the closure ledger
   (gate_test's committed closure gains src/cljc/yang/core/call.cljc — check that file's ownership:
   if it is another seat's ledger, the addition is still forced by the emission; flag it in the
   report too).
2. The import-related reds (relative-imports, preseeded-imports): diagnose FIRST — if they are
   address pins, re-derive; if they are behavioral (the import path changed semantics), STOP and
   report — that is a finding, not a pin.
3. THE IN-SLICE GENERATOR RULE: rerun with deferred=#{:py} (or wider ONLY with a prediction); any
   additional movement beyond the already-known :py movement is a finding. Double-run
   byte-identical; restore #{:pysp}.

VERIFY ON SHIVA (the laptop-unload rule — a fresh clone ~/ci/datomworld-python-s3pins from
~/ci/datomworld-python-s3, checkout YOUR commit): the JVM quick tier MUST be 0 failures; bb
test:cljs and bb test:cljd MUST be green (or the exact residual reds reported with identities).
kondo 0/0 + cljstyle on changed files.

CONSTRAINTS: single turn; no approvals; commit to python-s3-wiring; do NOT push (the orchestrator
pushes after the gate); do NOT touch master. End with: the re-derived pin table (old → new per
host), the closure ledger change, the import-reds diagnosis, the generator finding, the shiva lane
results, and the ownership flags for the TS/PHP orchestrator.
