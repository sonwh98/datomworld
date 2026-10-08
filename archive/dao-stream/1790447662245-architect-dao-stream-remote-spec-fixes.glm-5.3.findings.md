Completed-GMT: 2026-09-26 19:05:34 GMT
Completed-Local: 2026-09-27 02:05:34 +07
Coding-Agent: glm
Session-ID: 6d228259-1dbf-4494-8b1f-25b5c5c8e6d3

## 1. Findings

| Finding | Status | Where |
|---|---|---|
| MUST-FIX 1 protocol error surface | FIXED. Three errors (`not-found`, `no-surface`, `oversize`) as `:dao.stream.remote/error` on the one answer shape, never fabricated outcomes; fixed op-to-surface mapping (`cursor`/`next` need `:reader`, `append!` needs `:writer`, `descriptor` none); surface now travels in the `descriptor` answer under `:dao.stream.remote/surface`, so "learned from the attach probe" is now true; reflection translates errors to `transport-error` with reason keys; lease reclaim observation kept (gone, not disconnected) | remote.md 2.1, 2.3, 2.4, 6; dao.lease.md Carriage ("not-found protocol error") |
| MUST-FIX 2 WebSocket projection | FIXED. 3.1 specifies `ws-project` per channel end: unwraps `:ws/payload` `:ws/value`, correlates by `:ws/attachment`, drops `:ws/error`, closes the projected ring buffer on terminal lifecycle or failure resolution, which the link observes as `end` (channel-loss semantics in 2.4); reused-vs-new stated explicitly | remote.md 3.1, 2.4, 5 (toy) |
| MUST-FIX 3 anchor piggyback | FIXED by dropping it, cost stated. 2.3 now says steps 1-4 are the mirror's only contacts with the entry's handle, nothing calls a handle outside `apply-request`, and every anchor including `:oldest`/`:newest` costs one request | remote.md 2.3, 2.4 |
| MUST-FIX 4 prohibitions vs gate vs ShiBi | FIXED. Prohibitions amended: no draining, fold is an interpreter's work; gate redesigned to a bounded decision read (one `next`, gap-recovery makes two) on a capacity-1 decision medium written by a separately composed index interpreter; cursor/last-decision are wrap-held, advanced only inside a call; ShiBi fit restated with a stated limitation (latest decision per medium, not folded history); the prior "passes" claim is not carried forward | middleware.md (prohibitions, gate), shibi.md (plug-in list), remote.md 7 |
| MUST-FIX 5 encryption | FIXED by deferral with the exact caveat. Channel attachment point marked reserved/deferred; relay sees every envelope (identities, ops, cursors, ids, sizes, timing) even with value-level middleware; encode-then-encrypt-then-fragment named as requirements on the future wrapper, not a design | remote.md 1, 7 |
| MUST-FIX 6 OD-3 wording | FIXED. Stale cursor = `cursor-mismatch` at `next`; missing stream = `attach!`'s own `not-found`, a separate case taking no cursor; no other contract text touched | dao.stream.md, Cursors |
| MUST-FIX 7 CBOR framing | FIXED. Boundaries come from the channel codec (one application value per message; CBOR on the UDP channel; DHT keeps Transit-per-datagram), payload bytes are Base64 in the application value; siblings fixed in dao.jing.md and the plan's vocabulary | dao.jing.cbor.md (Objective, Memory and files, Remote and DHT), dao.jing.md |
| MUST-FIX 8 amendment count | FIXED. Section 1 and section 9 both enumerate the same five amendments; no "three decisions" claim remains | remote.md 1, 9 |
| SHOULD-FIX 1 UDP budget/keying | FIXED. Budget covers the whole encoded datagram, fragment envelope included; reassembly keyed by `[channel attachment identity, source-address, direction, id]` with the reason stated | remote.md 3.2 |
| SHOULD-FIX 2 NAT by behavior | FIXED. Mapping/filtering vocabulary, CGNAT named as address sharing not one behavior, outbound-path requirement and the impossibility stated | remote.md 1, 4 |
| SHOULD-FIX 3 length | PARTIAL. Moves done (dao.jing.content design, slices, companion inventory to the new plan; relay/reachability consolidated; concept/fate/lease/NAT/operation tables converted to lists). 725 to 675, not under 600: the eight MUST-FIX additions cost roughly 85 lines of new rules (without them the same moves and cuts land near 590), and cutting further means dropping rules, which the brief forbids. OWNER-VISIBLE arithmetic | remote.md; new plan |
| SHOULD-FIX 4 refused in source sets | FIXED as a slice (slice 0 names `src/cljc/dao/stream.cljc`); source untouched | plan, section 2 |
| GLM MUST 1 serving consumers | FIXED. Row names `yin.repl.serve` and `dao.jing.remote` (verified: remote.cljc:29, 981, 1023, 1038, 1046) | remote.md 8 |
| GLM MUST 2 bounded meeting work | FIXED. Board retention, active pairs, per-step fanout are composition bounds; exhaustion is refused by a gate and observed as `:dao.stream/refused`; the NAT-binding keep-alive cadence sentence restored | remote.md 4 |
| GLM SHOULD 1-6, 8, 10-12 | FIXED (1 = gate MUST 7; 2 dao.jing.md "today"+target; 3 accept-bytes! marked target; 4 btree row annotated; 5 slice 5 names rpc's fate; 6 driver consumers corrected to dao.space-via-coordinate and yin.repl.link, yin.vm.content qualified; 8 cut list applied; 10 key-hash name form only; 11 third-party renewal sentence; 12 one namespace spelling in plan 1) | as listed |
| GLM SHOULD 7 stale-forward docs | PARTIAL. Pointers added in dao.jing.hash-registry.md and dao.jing.call-site-classification.md (both under docs/design/); docs/dao.space.stigmergy.md is outside docs/design/ and outside my ownership: not edited, listed below | two files |
| GLM SHOULD 9 terminology | PARTIAL. UCF "facade" leftovers renamed to reflection (3 spots) and the acceptance row now cites the plan's slice 8; ws.md's residual server/client wording deferred: it tracks dao.stream.md's own Close-section wording, so re-wording it ahead of the code slices would desync transport spec from contract; recorded in plan 3 as a with-the-code item | UCF; plan 3 |

## 2. Design choices (rulings and owner statements touched)

- **Piggyback dropped, not routed** (MUST-FIX 3, my call per the brief): routing it would run a `cursor` per answer through middleware on the serving peer, a cost paid on every request to buy one saved round trip at attach; dropping is strictly simpler and fixes the surface bypass. Cost stated in the spec.
- **Protocol errors are a key on the answer, not a new frame** (MUST-FIX 1): the no-new-frames ruling holds; `channel-gone` is a reason value on the reflection's outcome, not a wire shape.
- **Surface in the descriptor answer** (MUST-FIX 1): the gate allowed descriptor answer or descriptor field; the answer key keeps the descriptor itself reachability-only, as 2.2 already ruled.
- **OWNER-VISIBLE, encryption**: remote channel confidentiality is deferred, not specified. A relay peer (pair channel, meeting peer) reads every request and answer envelope in the clear regardless of value-level middleware. Owner statement 6 is met for value middleware only.
- **OWNER-VISIBLE, gate/ShiBi**: the seam hosts a ShiBi built from index and query interpreters, with one stated limitation: the gate observes the index's latest published decision per medium, not the history it folded. Owner statement 8 is satisfied (the fold lives in an interpreter); the "four prohibitions" are amended as above, still within datom.world.md's six invariants (state is wrap-held, no callbacks, no hidden or shared global state, explicit chains).
- **OWNER-VISIBLE, length**: 675 against the under-600 target; arithmetic above. If the owner wants the number, the honest lever is relaxing "do not drop a rule", not further compression.
- Impossibility ruling kept and sharpened: both-unreachable peers need a third reachable peer; peers with no outbound path to a reachable WebSocket peer or relay cannot communicate at all.

## 3. Files

Created: `docs/design/dao.stream.remote.implementation-plan.md` (162). Rewritten: `dao.stream.remote.md` (725 to 675), `dao.stream.middleware.md` (153 to 169), `dao.shibi.md` (45 to 47). Edited: `dao.stream.md` (1003), `dao.jing.cbor.md` (691), `dao.jing.md` (629), `dao.lease.md` (304), `dao.data.btree.md` (1305), `yin.vm.universal-continuation-format.md` (1840), `yin.vm.linker.md` (2790), `dao.jing.hash-registry.md` (686), `dao.jing.call-site-classification.md` (273). The last seven are the "now false because of what I changed" set: framing sentences, the lease carriage word, the btree target row, UCF's retired term and moved slice citation, the linker vocabulary citation now pointing at plan section 1, and two stale-forward inventories.

## 4. New contradictions found, not fixed

- `docs/dao.space.stigmergy.md:241-246` still instructs `default-handlers`/`connect-content!` (outside docs/design/, outside ownership).
- `dao.stream.ws.md` keeps server/client wording in its mechanics sections, tracking `dao.stream.md` Close's own "server-side accepted-connection handle"; deferred to the code slices (plan 3).
- Task-header bookkeeping (GLM OTHER): the stub is `docs/design/dao.shibi.md`, not `shibi.md`.

## 5. Mechanical checks

Ran: non-ASCII scan (clean on new/rewritten files); em-dash scan (none introduced; pre-existing ones in other docs preserved verbatim); 170-column check (clean on my output; dao.stream.md/dao.jing.md long lines are pre-existing tables); server/client scan of the spec (owner quotes, the quoted "server" convention, and "stepped client"/"content client" module names only); piggyback and amendment-count greps; table-border closure on the plan's slice table; collab-reference scan; cross-reference sweep proving no design doc still cites the spec's moved sections 9/10 (UCF was the one, fixed). Verified against the tree before fixing: serving's second consumer, the coordinate opener at query.cljc:250, yin.vm/content.cljc's absence of a remote path, DHT's Transit-per-datagram, discovery.md's key-hash-only name form, the old serve draft's meeting bounds and NAT-lifetime wording. Not verified: no build or test run (per the brief); box-table column alignment not tool-checked (awk denied; by construction); pre-existing citations inside docs I did not fully open were not re-verified.

## 6. Ready for re-gate?

Yes. The two known soft spots a gate should press: the 675-line length against the under-600 target (arithmetic above), and the gate's decision-read contract in middleware.md, which is the newest machinery in this round.
