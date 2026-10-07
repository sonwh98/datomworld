# Architect sign-off: DHT S4 (hardening, lookup repair, storage bound)

Role: Architect (docs/agents/roles/architect.md). This is a read-only review.
- Worktree: /Users/sto/workspace/datomworld-dht-s4, branch dht-s4, from dht-s3 072a1f0c. S4 is uncommitted; use `git diff`.
- The author is codex gpt-6-sol. It hit its usage limit before writing a report, so judge the diff directly against the brief.
- Brief: collab/1790782000000-stream-engineer-dht-s4-hardening.prompt.md.
- Contract: docs/design/dao.jing.dht.md §8 and §10 S4.
- The orchestrator's lanes on this tree: kondo clean; JVM 2471/0; Node 2382/0; CLJD all passed.

Verify adversarially, for every S4 acceptance bullet: is it implemented AND proven by a test that would fail if the property broke? In particular:
- The keyed MAC:
  - it comes from a vetted host primitive or library, not hand-rolled;
  - how the secret is created, rotated and stored;
  - the S2 stand-in is gone.
- The spoofed-source amplification bound across datagram sizes, chunks included, with zero allocation.
- A reply from an unexpected source is ignored, and a claimed address never enters the table.
- Lookup repair.
- The explicit refusal when storage reaches its bound.
- publish? false neither originates nor serves.
- How non-loopback exposure is gated.
- Earlier S1–S3 rulings still hold.
- Portability traps.
- The added Dart dependency (crypto 3.0.7 in pubspec) is acceptable.

The diff is ~200 lines; flag any acceptance bullet that looks unimplemented.

End with an explicit verdict: SIGN-OFF GRANTED or WITHHELD, with findings as a Severity | file:line | issue | fix table.
