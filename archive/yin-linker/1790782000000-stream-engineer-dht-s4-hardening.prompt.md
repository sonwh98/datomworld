# Brief: DHT epic S4 — hardening, lookup repair, storage bound

Role: Engineer. Implementer: codex gpt-6-sol (GLM is paced). Sign-off: claude-fable-5-1, with gemini-3.1-pro-high as fallback if fable is overloaded.
- Worktree: /Users/sto/workspace/datomworld-dht-s4, branch dht-s4, from dht-s3 072a1f0c (S1 + S2 + S3).
- Do not stage or commit.

Contract (binding):
- docs/design/dao.jing.dht.md: §8 (cookies: the keyed MAC, the silence rule, first-contact padding), §10 S4 acceptance, and any section S4 touches.
- Owner decisions: collab/1790762091000-orchestrator-dao-jing-dht-epic-owner-decisions.md.

Prior rulings (collab/*-architect-dht-s{1,2,3}-signoff*.findings.md) still bind:
- a need-cookie cookie is untrusted until a full reply;
- replies past their deadline are dropped;
- budget-bounded steps;
- wrong-family destinations are dropped before send.

Build every S4 acceptance bullet in §10:
- `cookie-for` becomes a keyed MAC; a cookie cannot be computed without the secret; the S2 stand-in is gone.
  - Use a vetted library or host primitive for HMAC on each host (JVM javax.crypto, Node crypto, Dart package:crypto or similar). Never hand-roll crypto (owner rule: below dao.stream, use libs).
  - Say how the secret is created, how it rotates across epochs, and where it lives.
- The chunk path runs under the cookie gate.
- A spoofed source elicits at most one reply, never larger than the datagram it sent, and silence when the reply would be larger. It allocates nothing. Test this across datagram sizes from 1 byte to the budget, chunks included.
- A reply from an unexpected source is ignored, and a claimed address never enters the routing table.
- Lookup repair: a live next-nearest peer is tried after dead peers, and dead peers leave the table.
- Inbound storage at its bound refuses explicitly.
- `publish?` false neither originates nor serves.
- Non-loopback exposure is permitted only after this slice. State in the report how exposure is gated now.

Rules:
- Write tests first and show each FAILS before its fix. Give at least one mutation per core property.
- Time advances only by ticks.
- CLJC portability:
  - `:cljd` goes FIRST in mixed reader conditionals.
  - No array-map. No cross-namespace #'private.
  - Anchor regexes by hand.
  - Refusal helpers return the error object.
- Invariants: dao.stream is P2P with no privileged node; apply stays independent of rpc.
- Run every check in the FOREGROUND and see each verdict: kondo on the changed files; full `clj -M:test`; `bb test:cljs`; `bb test:cljd` (delete test/cljd-out first if stale).
- If the sandbox blocks a lane, name the lane and the error. Never report an unseen result.

Report: collab/1790782000000-stream-engineer-dht-s4-hardening.gpt-6-sol.report.md. Map each acceptance bullet to its evidence.

## Fix round 1 (2026-10-01 01:00 +07, orchestrator): conditional grant from fable
codex is capped until 02:05, so this round goes to agy gemini-3.1-pro-high.
fable GRANTED its sign-off, conditional on F1 and F2: collab/1790786000000-architect-dht-s4-signoff.claude-fable-5-1.findings.md.
- F1 (condition) | docs/design/dao.jing.dht.md §8 | State secret creation and ownership: per node, at least 32 random bytes from the host CSPRNG, minted at composition, in memory only, never persisted or shared. Note that the tests share one secret for predictability.
- F2 (condition) | test/dao/jing/dht_test.cljc ~963 | Strengthen the storage-bound test with a bound of twice the store size:
  - store A → ok; A again → ok; B → refused;
  - local holds A only;
  - plus a store at exactly the bound → accepted.
  Show it FAILS with a per-message (non-cumulative) check mutation.
- F3 | Update the status lines in dao.jing.md:499 and dao.jing.cbor.md:493 (the stand-in is gone; exposure is gated by the secret and the bound).
- F4 | Add short-secret and missing-bound rows to the composition-defects test.
- F5 | In the chunk sweep, assert that an iteration where the reply fits produces exactly one reply.
Keep the change confined to these items. Run kondo on the changed files, full `clj -M:test`, `bb test:cljs` and `bb test:cljd` (delete test/cljd-out first), each in the FOREGROUND, and see each verdict.
Also write the S4 acceptance report the codex run never wrote: map each S4 bullet to its test, using the diff and the Architect's findings.
Report: collab/1790782000000-stream-engineer-dht-s4-hardening.gemini-3.1-pro-high.report.md
