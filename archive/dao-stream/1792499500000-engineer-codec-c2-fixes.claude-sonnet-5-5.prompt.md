Created-GMT: 2026-10-20 11:20:00 GMT
Created-Local: 2026-10-20 18:20:00 +07
Coding-Agent: claude
Session-ID: fresh

# Task: Implement Required Changes (R1, R2) for Slice C2
Role: Implementation Engineer
Model: claude-sonnet-5-5

Working Directory: /Users/sto/workspace/datomworld-codec-c2
Governing Document: /Users/sto/workspace/datomworld/collab/1792498000000-architect-c2-review.claude-fable-5-1.findings.md

Objectives:
1. Fix R1 (explicit subprotocol on raw sockets):
   - In `test/dao/stream/ws_test.cljc` around lines 311, 343, 434, 536: put `:ws/subprotocol seam` on each socket map (where `seam` is `(codec/codec-name transit/profile)` or already bound).
   - In `test/dao/stream/ws/dart_test.cljd` around line 110: assoc `:ws/subprotocol (codec/codec-name transit/profile)`.
2. Fix R2 (Design doc synchronization in `docs/design/dao.stream.codec.md`):
   - Record Owner Decision on OQ1 in §10: Resolved on 2026-10-20. `yin.repl` selects `dao.stream.cbor/profile` by default, configurable via CLI `--codec <cbor|transit>` and dynamically at the REPL prompt `(connect url :codec :transit|:cbor)`.
   - Update §6.4, §7.1 C2 row, and §11 to reflect that the `yin.repl` default moved to CBOR in C2 (with CLI/prompt configurability) rather than waiting for C5.
3. Address N1 (ws_project_peer unknown codec rejection):
   - In `test/dao/stream/ws_project_peer.cljc`, if `--codec` is supplied with an unknown value, reject it cleanly (print error and exit non-zero) instead of silently falling to nil/default.
4. Also update `src/cljc/yin/vm/docs/yin.repl.md` (N6) to document `--codec` flag and `(connect … :codec …)`.
5. Run tests:
   - `bb test:sub:clj dao.stream`
   - Re-verify frozen wire fixtures pass 100%.

Write findings report to:
/Users/sto/workspace/datomworld/collab/1792499500000-engineer-codec-c2-fixes.claude-sonnet-5-5.findings.md
