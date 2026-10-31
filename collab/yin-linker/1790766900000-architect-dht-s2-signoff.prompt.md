# Architect sign-off: DHT S2 (caller-stepped DHT core on in-memory sockets)

Role: Architect (docs/agents/roles/architect.md). This is a read-only review.
- Worktree: /Users/sto/workspace/datomworld-dht-s2, branch dht-s2. The change is uncommitted; use `git diff` and `git status` to see it.
- Contract: docs/design/dao.jing.dht.md (committed dac64b41), especially §4.3, §7, §8, §10 S2 acceptance.
- Owner decisions: collab/1790762091000-orchestrator-dao-jing-dht-epic-owner-decisions.md.
- Brief: collab/1790764302000-storage-engineer-dht-s2-core.prompt.md.
- Implementer report: collab/1790764302000-storage-engineer-dht-s2-core.claude-opus-5-5.report.md.
The author is claude-opus-5-5.

Tasks:
1. Review adversarially against every S2 acceptance bullet in §10 and the owner decisions. Check in particular:
   - The ack means a local insert plus a send to at least `ack-peers` (floor 2) distinct peers, recorded as a `/sent` fact.
   - Fetch-only when there is no publish flag.
   - Solo mode never acks.
   - Oversize is refused before the local insert.
   - The cookie silence rule and the 256-byte padded first contact.
   - Time advances by ticks only.
   - The facade has a single step owner.
2. Would the tests fail if each property broke? Check portability traps: reader-conditional order, array-map, cross-namespace #'private.
3. **RULE on the contract contradiction.** §10 says S2 deletes `IDhtNet`, `lookup` and `create-content-dht`, but `dao.jing.dht.node` (deleted in S3) and node_test still use them. Choose (a), (b) or (c):
   - (a) delete node and node_test in S2;
   - (b) keep the legacy code until S3 and correct the §10 table;
   - (c) move the old protocol into node now.
   Give a reason.
4. Rule on each of the ten open choices listed in the report. Include the two-round-trip first contact: does a need-cookie reply make the peer proven or fresh?

End with an explicit verdict: SIGN-OFF GRANTED or WITHHELD. Give findings as a Severity | file:line | issue | fix table, then the rulings from 3 and 4.
