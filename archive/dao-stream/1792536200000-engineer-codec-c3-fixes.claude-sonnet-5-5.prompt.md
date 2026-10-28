Created-GMT: 2026-10-21 03:05:00 GMT
Created-Local: 2026-10-21 10:05:00 +07
Coding-Agent: claude
Session-ID: fresh

# Task: Address Codex Architectural Review Findings on Slice C3
Role: Implementation Engineer
Model: claude-sonnet-5-5

Working Directory: /Users/sto/workspace/datomworld-codec-c3
Branch: codec-c3
Governing Document: /Users/sto/workspace/datomworld/collab/1792534500000-architect-c3-review.gpt-6-astra.findings.md

Codex Review Findings to Fix:

## 1. R1 (High): Node test compositions still consume removed default
- In `test/dao/stream/ws/node_test.cljs:335-355` (`server-fixture`):
  Currently when called with `{}`, it leaves `:codecs` nil, causing `ws/make-endpoint` to throw.
  Update the fixture so that when `:codecs` is not supplied, it explicitly passes `[transit/profile]`.
  Ensure `node/listen!` also gets `[transit/profile]`.
  Run the Node tests (`bb test:sub:cljs dao.stream`).

## 2. R2 (Medium): `make-endpoint` must strictly require `vector?`
- In `src/cljc/dao/stream/ws.cljc:549-550`:
  Enforce `(vector? codecs)` in addition to `(seq codecs)` and `(every? codec-profile? codecs)`.
  Non-empty lists or sets must be rejected (throw/refuse).
  Add test in `test/dao/stream/ws_test.cljc` verifying rejection of lists and sets of codecs, and acceptance of vectors.

## 3. R3 (Medium): Dart bind! must validate codecs before binding
- In `src/cljd/dao/stream/ws/dart.cljd:266-286` (`bind!`):
  Validate that `:codecs` is a non-empty vector of valid profiles BEFORE calling `HttpServer.bind`.
  Do not allocate a listener or report `:bind-succeeded` if codecs are missing or invalid.

## 4. R4 (Medium): Add missing negative host tests
- Add absent-codec throw/rejection tests for:
  - Node host adapter in `test/dao/stream/ws/node_test.cljs`
  - Dart host adapter in `test/dao/stream/ws/dart_test.cljd`
  (mirroring `test/dao/stream/ws_project_jvm_test.clj:423`).

## 5. Verification:
- Run `bb test:sub:clj dao.stream`
- Run `bb test:sub:cljs dao.stream`

Write report to:
/Users/sto/workspace/datomworld/collab/1792536200000-engineer-codec-c3-fixes.claude-sonnet-5-5.findings.md
