Created-GMT: 2026-09-20 19:53:20 GMT
Created-Local: 2026-09-21 02:53:20 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 8fafe5c6-77cd-4436-8231-ff0f220ce947 (resumed — your de Bruijn lineage)
# Task: confirmation round — D2 fix verification
Role: reviewer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-21 01:18:50 +07 | Status: active | Rationale: same implementer, warm session

# Task: adversarial code review — the de Bruijn projection (canonical code form)

Your D2 verdict was NOT READY with one P1 (hash-consing memo keyed by `=`
merging list with vector and `0.0` with `-0.0`) plus two P2 (keyword/symbol
parts bypassing the NFC seam; the reader not verifying content addresses). The
implementer applied your fixes. Verify each is actually resolved in this
worktree's uncommitted state:

1. P1: the memo is keyed on the node's preimage (the concatenated encodings)
   per the orchestrator's ruling of your option (b), so a preimage hit implies
   identical hash and record. New tests put a list literal beside a vector
   literal in one program (different hashes; fingerprint differs from the
   all-list program) and `0.0` beside `-0.0` (different hashes).
2. P2: ident namespace/name parts route through `normalize-nfc`; a
   decomposed vs composed free name hashes identically.
3. P2: `datoms->projected` recomputes each record's hash and raises
   `:hash-mismatch`; a tampered-scalar test exists; non-string hashes raise
   `:malformed-hash` (string? guard, your P3).
4. P3 accepted: integral doubles render through `long` in the provisional
   int64 content, so 1 ≡ 1.0 holds on every host now (test exists).
5. Deferred per orchestrator: unreachable-record diagnostics (D4/D5); UTF-16
   length prefix (D3 byte rules). Flag only if deferral is unsound.

Already verified by the orchestrator in this exact worktree — do not rerun
suites: focused JVM 47/159/0; kondo 0/0; JVM full 1605/168945/0; CLJS full
(including signed-zero and 1/1.0 fixtures on the JS host) 0 failures; CLJD
full 1487 passed.

Deliver a per-finding resolution table and exactly one verdict line: READY
for architect sign-off, or NOT READY with the blocking list.
