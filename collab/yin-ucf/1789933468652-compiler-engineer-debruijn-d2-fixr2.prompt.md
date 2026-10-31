Created-GMT: 2026-09-20 19:44:28 GMT
Created-Local: 2026-09-21 02:44:28 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: 923b8885-4549-4b46-ad11-0731ebb614ef (resumed — your D2 session)
# Task: fix the D2 review round — one P1, two P2, two P3
Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-21 02:44:28 +07 | Status: active | Rationale: same implementer, warm session

# Task: fix the D2 review round — one P1, two P2, two P3

Review verdict was NOT READY (claude-fable-5-1). Apply these in
src/cljc/yin/vm/debruijn.cljc and test/yin/vm/debruijn_test.cljc only; the
box and environment rules are unchanged.

- **P1 (hash-consing memo, ~869-871, 904) — ORCHESTRATOR RULING: option (b)**.
  The memo keyed by Clojure `=` merges a list literal with a vector literal
  (`(= '(1 2) [1 2])` is true) and `0.0` with `-0.0` (reliably on
  CLJS), so the second occurrence takes the first's hash, non-equivalent
  programs share a fingerprint, and identity depends on traversal order.
  Fix: key the consing memo on the node's PREIMAGE — the concatenated
  encodings — which separates exactly what the hash separates. Keep the
  memo as an optimisation only; a preimage hit must imply identical hash and
  record. Add tests in ONE program: a list literal alongside a vector literal
  (different hashes, different fingerprint than the all-list program), and
  `0.0` alongside `-0.0` (different hashes).
- **P2 (keyword/symbol parts skip NFC, ~786-793)**: `ident-content` frames
  namespace and name without the seam. Route both parts through
  `normalize-nfc` — the value table declares `:keyword`/`:symbol` as
  `:nfc-utf-8` and datom.md requires NFC for keywords. Test: a free name
  containing a decomposed form hashes identically to its composed form.
- **P2 (reader never verifies content address, ~993-1063)**:
  `datoms->projected` must recompute each record's hash and raise a
  `:hash-mismatch` diagnostic on disagreement — content addressing is
  otherwise defeated at the storage boundary (precedent: yin.vm.content
  refuses content that does not hash to its address). Test: one tampered
  scalar in an otherwise valid record set.
- **P3 (provisional :int64 content is (str v), ~807)**: an integral double
  prints "1.0" on JVM and "1" on CLJS. Render integral doubles through
  `long` so the provisional rule agrees with the value table's
  1/1.0 collision now, not just after D3.
- **P3 (re-matches on non-string, ~1030)**: guard with `string?` so a
  non-string hash value raises `:malformed-hash` instead of a host
  exception.

Deferred (do NOT implement): unreachable-record diagnostics and scalar-type
revalidation on read (the hash check covers typing; the diagnostic question
revisits at D4/D5); the UTF-16 length-prefix note is D3's byte-rules scope.

Verification (report exact counts): focused JVM (clojure -M:test -n
yin.vm.debruijn-test), kondo, cljstyle check. The orchestrator reruns the
full lanes. One simple command per step.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
