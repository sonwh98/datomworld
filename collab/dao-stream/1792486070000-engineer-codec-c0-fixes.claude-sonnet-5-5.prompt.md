Created-GMT: 2026-10-20 08:47:50 GMT
Created-Local: 2026-10-20 15:47:50 +07
Coding-Agent: claude
Session-ID: fresh

# Task: Address Review Findings R1, R3, R4 for Slice C0
Role: Implementation Engineer
Implementers:
- Model: claude-sonnet-5-5 | Assigned: 2026-10-20 15:47:50 +07 | Status: active | Rationale: Fix review findings in isolated worktree.

Working Directory: /Users/sto/workspace/datomworld-codec-c0
Reviewer Report: /Users/sto/workspace/datomworld/collab/1792485635000-reviewer-codec-c0.gpt-6-astra.findings.md
Architect Ruling Report: /Users/sto/workspace/datomworld/collab/1792485815000-architect-codec-c0-ruling.claude-fable-5-1.findings.md

Tasks to execute:
1. R1 (Token validation in profile name):
   - In `src/cljc/dao/stream/codec.cljc`, enforce RFC 7230 token syntax on `:dao.stream.codec/name` and legacy `:ws/subprotocol` name validation.
   - RFC 7230 token: 1+ chars matching visible US-ASCII except separators/delimiters (no spaces, control chars, or `"(),/:;<=>?@[\]{}`). Reject `""`, `"bad name"`, `"a/b"`, `"a;b"`, `"a=b"`.
   - In `test/dao/stream/codec_test.cljc`, add tests asserting rejection of these malformed names for modern, legacy, and dual profiles.
2. R2 (Test comment update):
   - In `test/dao/stream/codec_test.cljc`, update the comment above `lists-in-d-round-trip-on-cbor` (around line 84) to cite Architect Ruling R2 / Decision CD7 in `docs/design/dao.stream.codec.md`.
3. R3 (Missing corpus cases in D & profile asymmetries):
   - In `test/dao/stream/codec_test.cljc`:
     - Add `(symbol "" "n")` (unportable empty-prefix identifier) to rejected identifiers.
     - Add record-bearing symbol metadata test cases: as map key, map value, and nested inside another symbol's metadata, verifying rejection.
     - Add the three explicit profile-asymmetry assertions from §4.4:
       * Slash-bearing keyword `(keyword "a/b" "c")`: rejected by D, admitted by Transit, rejected by CBOR.
       * Host bytes (`host-bytes?` / byte array): rejected by D, rejected by Transit, admitted by CBOR.
       * Collection whose metadata holds a host object: rejected by D, admitted by Transit, rejected by CBOR.
4. R4 (Clean docstring grep):
   - In `src/cljc/dao/stream/cbor.cljc:2`, reword the docstring to remove the literal string "dao.stream.transit" so that `grep -rn "dao.stream.transit" src/` hits only `transit.cljc`, `transit/cljd.cljd`, `ws.cljc`, `ws/*.clj*`, `jing/stream.cljc`.

Verification:
- Run `bb test:sub dao.stream` across all 3 lanes (clj, cljs, cljd) and confirm all pass with 0 failures.
- Check `grep -rn "dao.stream.transit" src/` to verify R4.

Write findings to /Users/sto/workspace/datomworld/collab/1792486070000-engineer-codec-c0-fixes.claude-sonnet-5-5.findings.md.
