# Brief: DHT epic S3 — real sockets and chunks

Role: Engineer (docs/agents/roles/). Implementer: codex gpt-6-sol (GLM is paced per the owner). Sign-off: claude-fable-5-1.
- Worktree: /Users/sto/workspace/datomworld-dht-s3, branch dht-s3. It is master e3cf971b with dht-s1 (d0b734ae) and dht-s2 (ba672389) merged in.
- Do not stage or commit.

Contract (binding):
- docs/design/dao.jing.dht.md: §7 (wire), §8 (cookies), §10 S3 acceptance, and the Base64 seam paragraph.
- docs/design/dao.stream.datagram.md.
- Owner decisions: collab/1790762091000-orchestrator-dao-jing-dht-epic-owner-decisions.md.

Prior slice rulings to honour:
- S2 sign-offs: collab/1790766900000-architect-dht-s2-signoff*.findings.md. Ruling (b): S3 deletes the legacy IDhtNet, lookup and create-content-dht together with dao.jing.dht.node and node_test.
- S1 sign-offs: collab/1790770800000-architect-dht-s1-signoff*.findings.md.
  - Drop peer addresses of the wrong IP family BEFORE sending, rather than leaning on the seam's refusal, which costs a traffic-ring slot.
  - Dart cannot originate a zero-length datagram.

Build every S3 acceptance bullet in §10:
- Chunk records, and the move of the S2 core onto dao.stream.datagram sockets. Loopback only.
- Two and three peers exchange small queries and multi-datagram segments under injected loss and reordering.
- The largest legal `:find` reply fits one datagram.
- Oversize refuses before any send.
- Malformed, duplicate and inconsistent chunks stay within every bound.
- A chunked write counts a peer only when every chunk answered `ok`.
- One transport-neutral chunk split/absorb utility is shared by dao.stream.udp and the DHT, each keeping its own wire fields and keys. The declared fallback is allowed; the report must say which path was taken and why.
- Measure real covered-index nodes against `max-message-bytes`.
- JVM↔Node and JVM↔Dart pass the same wire tests.
- Update dao.jing.cbor.md (Remote and DHT) to this wire.
- Repoint dao.jing's Base64 functions onto dao.stream.base64, and prove both agree on every test vector.
- Delete the legacy surface and dao.jing.dht.node, per ruling (b).

Rules:
- Write tests first and show each one FAILS before its fix. Include at least one mutation per core property.
- Time advances only by appended ticks; no test sleeps, except where a real socket forces it.
- CLJC portability:
  - `:cljd` goes FIRST in mixed reader conditionals.
  - No array-map. No cross-namespace #'private.
  - Anchor regexes by hand, because CLJS re-matches is exec plus equality.
  - Refusal helpers return the error object; the call site applies ex-message.
- Invariants:
  - dao.stream is P2P, with no server/client and no privileged node.
  - dao.stream.apply stays independent of rpc.
  - No new keys in :dao.stream.apply/*.
- Run every check in the FOREGROUND and see each verdict:
  - kondo on the changed files
  - full `clj -M:test`
  - `bb test:cljs`
  - `bb test:cljd`
- The writable roots for the Flutter, pub and m2 caches are granted. If the sandbox still blocks a lane, name the lane and the error; the orchestrator runs it.
- Never report a result you did not see.

Report: collab/1790775900000-stream-engineer-dht-s3-sockets-chunks.gpt-6-sol.report.md. Map each acceptance bullet to its evidence.

## Fix round 1 (2026-09-30 23:45 +07, orchestrator): sign-off findings
fable was unavailable (529 overloaded), so gemini-3.1-pro-high reviewed instead. It WITHHELD its sign-off: collab/1790779000000-architect-dht-s3-signoff.gemini-3.1-pro-high.findings.md.
Rulings: gaps 1, 2 and 4 are acceptable for S3. Live cross-process and mixed-host loss/reorder testing is deferred to S5.
1. MUST FIX | test/dao/jing/dht_test.cljc | Tick-age eviction of partials has no direct test.
   - Add `chunk-partials-are-evicted-by-tick-age`: send one chunk part, step the state by `query-ticks` ticks, and assert the partial map (and `:partial-order`) is empty.
   - Show a mutation that disables age eviction and makes the test FAIL.
2. TIP | dht.cljc ~1093 | `step-ticks` rebuilds `:partial-order` and calls dissoc on every tick even when nothing expired. Wrap the updates in `(if (seq expired) ... state)`.
Keep the change confined to these items. Run kondo, full `clj -M:test`, `bb test:cljs` and `bb test:cljd` (delete test/cljd-out first if the compiled output is stale), and see each verdict.
Report: ...gpt-6-sol.report-r2.md
