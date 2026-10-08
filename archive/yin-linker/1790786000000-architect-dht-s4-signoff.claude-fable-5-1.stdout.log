**Verdict: SIGN-OFF GRANTED, conditional on F1 and F2 landing before the commit.** Both are small and touch no code in `dht.cljc`. I ran no lanes and relied on the orchestrator's green results. There is no author report, so I judged the diff directly.

**What holds**

- **Keyed MAC.** `cookie-for` is now exactly the §8 formula: HMAC of the epoch secret over the CBOR-encoded observed address, truncated to 16 bytes, with the epoch secret derived by HMAC from the root secret. All three hosts use vetted primitives (javax.crypto, Node crypto, Dart package:crypto). The stand-in is gone from code, and two tests fail if the secret stops mattering.
- **Spoofed-source bound.** Both the request and chunk paths send need-cookie only when it fits the inbound datagram, and an unproven chunk never reaches reassembly. The sweeps cover real chunk records to the budget and raw bytes from one byte up.
- **Unexpected replies and claimed peers.** Replies match on observed source plus query id, and find hints stay candidates. The new test fails if hints ever call prove.
- **Lookup repair.** No source change was needed; S2's expiry already forgets a peer at its try limit. The new test proves the 21st-nearest live peer is reached after twenty dead ones and that the dead peer leaves the table.
- **Storage bound and publish? false.** Refusal is an explicit `:ok false`, and the unpublished paths are the unchanged S2 code with their four tests.
- **Exposure gate.** A socket composition now cannot be built without a 32-byte secret and a bound. Bind host stays the caller's choice and defaults to loopback.
- **Prior rulings, portability, and the crypto 3.0.7 pin** all pass. Reader conditionals put `:cljd` first, and the Dart dependency was already transitive with the same hash.

**Findings**

| Severity | file:line | Issue | Fix |
|---|---|---|---|
| Medium (F1, condition) | `docs/design/dao.jing.dht.md:65`, §8 | Secret creation and ownership are stated nowhere. Every test shares one secret across nodes, which lets any node forge cookies for any other, and S5 will mint this with no rule to follow. | Add to §8: per node, at least 32 random bytes from the host CSPRNG, minted at composition, in-memory only, never persisted or shared. Note the tests share one for predictability. |
| Medium (F2, condition) | `test/dao/jing/dht_test.cljc:963` | The bound test uses one store against bound 1. A per-message check instead of a cumulative one passes it, and the already-counted-address path is untested. | Bound of twice the size: store A ok, A again ok, B refused, local holds A only, plus a store at exactly the bound accepted. |
| Low (F3) | `docs/design/dao.jing.md:499`, `docs/design/dao.jing.cbor.md:493` | Status lines still say the stand-in is in use and exposure waits for S4. | Update with the commit. |
| Low (F4) | `src/cljc/dao/jing/dht.cljc:1264` | Short-secret and missing-bound defects have no test. | Two rows in the composition-defects test. |
| Low (F5) | `test/dao/jing/dht_test.cljc:1097` | The chunk sweep never asserts a reply is sent when it fits, so an always-silent chunk gate passes. Impact is limited since requesters never send cookie-less chunks. | Assert one iteration produced exactly one reply. |
| Info | `src/cljc/dao/jing/dht.cljc:785` | Accounting uses the wire bytes length while the check uses verified bytes. Equal today. The counter also resets per process while the local store persists. | Use one length. Say in §9 that the bound is per process if that matters. |
| Info | `src/cljc/dao/jing/dht.cljc:192` | Each verification runs four HMACs. | Cache the epoch secret per epoch, follow-up only. |

**Conditions.** F1 and F2 land before the commit with F2 green on all three lanes. If confined to those, no further Architect round is needed. F3 through F5 may ride the same commit.

The full findings table is saved at `/Users/sto/.claude/plans/read-users-sto-workspace-datomworld-coll-wobbly-swan.md`. Plan mode blocked writing the usual collab findings file, so the orchestrator should copy it there.
