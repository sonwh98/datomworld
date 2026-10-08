# Architect sign-off: DHT S3 (real sockets and chunks)

Role: Architect (docs/agents/roles/architect.md). This is a read-only review.
- Worktree: /Users/sto/workspace/datomworld-dht-s3, branch dht-s3. It is master e3cf971b with dht-s1 and dht-s2 merged in; the S3 work is uncommitted (`git diff`, `git status`).
- The author is codex gpt-6-sol.
- Brief: collab/1790775900000-stream-engineer-dht-s3-sockets-chunks.prompt.md.
- Report: collab/1790775900000-stream-engineer-dht-s3-sockets-chunks.gpt-6-sol.report.md.
- Contract: docs/design/dao.jing.dht.md §7, §8, §10 S3; docs/design/dao.stream.datagram.md.
- Prior rulings: collab/1790766900000-architect-dht-s2-signoff*.findings.md and collab/1790770800000-architect-dht-s1-signoff*.findings.md.

Verify adversarially:
- Every S3 acceptance bullet.
- The chunk bounds (partial count, per-source share, bytes, tick age) under hostile input.
- The cookie or pending check happens BEFORE any allocation.
- A peer counts only after every chunk answered ok.
- Oversize is refused before any send.
- The shared dao.stream.chunks utility keeps UDP's and the DHT's identities separate.
- The deleted legacy surface leaves no dangling users.
- Base64 repointing.
- The owner invariants: dao.stream is P2P with no privileged node; apply stays independent of rpc.
- Portability traps.

The author discloses gaps; RULE on each (acceptable for S3, or must fix before commit):
1. Live cross-process JVM↔Node and JVM↔Dart sockets were not tested. Only a shared canonical wire vector runs on every host.
2. Loss and reordering were exercised only in the in-memory mesh, not over live mixed hosts.
3. Tick-age eviction of partials has no direct test.
4. Test-first was only partly followed for the socket and wire-vector tests.

End with an explicit verdict: SIGN-OFF GRANTED or WITHHELD. Give findings as a Severity | file:line | issue | fix table, then the rulings.
