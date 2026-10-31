Created-GMT: 2026-09-21 05:32:46 GMT
Created-Local: 2026-09-21 12:32:46 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: 923b8885-4549-4b46-ad11-0731ebb614ef (resumed — your D0-D5 session)
# Task: epic-audit fix round — F1-F3 (blocking) + F4, F6, F7 + the D6 gap-list tests
Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-21 12:32:46 +07 | Status: active | Rationale: same implementer; the audit's findings are in your namespace

The opus-5 epic audit returned NOT READY with three blocking findings, each
verified by running the committed code in a REPL. Apply in
src/cljc/yin/vm/debruijn.cljc, test/yin/vm/debruijn_test.cljc, and
test/yin/vm/pipeline_test.cljc (pipeline.cljc only if F9 demands it):

- **F1 (sets merge silently, ~982)**: canonicalizing a set whose elements
  merge — #{1 1.0}, or two spellings of é — must diagnose
  :unsupported-value on count shrink, exactly like the map rule at
  ~974-981. Fixture: a colliding set literal diagnosed.
- **F2 (reader checks hashes, not records)**: the reader must check each
  record's slots against its node type per the grammar (:key on a
  :variable diagnoses) and require canonically-spelled values (a stored
  1.0 where the int64 canonical is 1 diagnoses; a decomposed-NFC string
  diagnoses) — WITHOUT changing any minted hash. This makes the first
  architect awareness item's reader gate real. Fixtures: renamed-slot
  record and noncanonical-spelling record both diagnose.
- **F3 (exponential hashing on shared subgraphs)**: merkle-node walks every
  child before the preimage memo can hit, so a doubling shared chain costs
  0.8s → 2.0s → 7.0s (75/85/95 datoms). Fix: carry the computed hash
  through the resolver's [eid context] memo so a shared subtree is hashed
  once. Regression fixture: a deeply-shared doubling chain must project
  with a deterministic bound on node-hash invocations (instrument the seam
  however is honest — a counter argument, an atom-free injectable — NOT a
  wall-clock assertion) and equal the tree-built equivalent's fingerprint.
- **F4**: a known attribute on a node type whose grammar row doesn't list
  it should diagnose rather than be ignored (reader-side; the emitter never
  emits one).
- **F6**: locate any remaining path where an internal defect surfaces as
  :invalid-input and classify it :internal-error (the D4 rule).
- **F7**: a batch budget of 0 must not return :continue forever — treat it
  as invalid input.
- **F9 + D6 gap-list tests**: a nil-valued datom round-trips through real
  D5 storage without a hash mismatch (pipeline_test); the renamed-program
  pair's STORED datoms compare equal; destination :invalid-value and
  :transport-error outcome tests; terminal idempotence beyond :partial-frame.

Deferred, no action: F5 (local atoms in projected->datoms — contained, note
only); F8 (macro detection limit is §1's documented inherited limit).

Verification (exact counts): focused JVM for all three namespaces, kondo on
all touched files, cljstyle check, and the CLJS lane (F1-F3 change hashing —
cross-host agreement must hold). The orchestrator reruns everything incl.
CLJD. One simple command per step.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
