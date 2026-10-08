Completed-GMT: 2026-09-26 17:10:31 GMT
Completed-Local: 2026-09-27 00:10:31 +07
Coding-Agent: claude
Session-ID: fcb393fa-700d-41ae-b1a3-2e8152ac5b60

# Architect decision: dao.stream.serve section 15

## 1. Invariant feasibility

| Clause | Status | Grounds |
|---|---|---|
| Any dao.stream implementation mechanically exposable over WebSocket or UDP | Achievable with caveat | Derivation is the five-entry apply table over the declared surface, transport-blind (`dao.stream.serve.md:193-216`). Caveat: cursors and outcome maps must be plain data in the portable domain (`:257-262`); today the contract says cursor serialization is "transport-owned and TBD" (`dao.stream.md:523-525`), so this clause is true only once OD-3 (2) is accepted. Both existing transports qualify (`ringbuffer.cljc:63-64`). Over UDP a value over 1200 bytes is `:oversize` until fragmentation exists (`serve.md:589-593, 605-611`): exposable yes, readable only for values that fit. |
| P2P, no server, no client, no privileged node | Achievable at the protocol layer | No frame names a peer kind (`serve.md:162`), every peer runs both steps (`:50-55, 94`), roles are per session (`:122-126`), dialed channels are bidirectional (`:819-824`). WebSocket dialer/acceptor is an establishment fact (owner Q2). Browser can only dial (`:903-905`). |
| Traverse NAT | Conditionally achievable; see table below | Direct UDP punch works for cone NATs; symmetric NAT and CGNAT fall to the inbox relay (`:809-815`). WebSocket never punches: a NATed peer is reachable only on channels it dialed, and two NATed WebSocket peers always need a relay peer. |
| Toy: string wrapped as a stream, served over WebSocket | Achievable; see section 2 | One spec defect (local `cursor-mismatch`) and one missing implementation (no string-backed transport exists in `src/cljc/dao/stream/`). |

NAT matrix (A and B both behind the named NAT; "relay" means the inbox pair on a directly reachable peer M, `serve.md:664-680`):

| NAT at both ends | UDP direct | WebSocket direct | Falls to |
|---|---|---|---|
| Full cone | yes, after one outbound probe | no (nobody can accept) | relay for WS |
| Restricted / port-restricted cone | yes, with same-socket probe (`:586-590, 813-815`) | no | relay for WS |
| Symmetric | no (fresh port per destination) | no | relay |
| CGNAT | usually no (symmetric or port-pooled) | no | relay |
| UDP-blocking network | no UDP at all | no | relay over WS; peer must dial M |
| One side public | direct: NATed side dials | direct: NATed side dials | none |

Does a relay reintroduce a server? Structurally no: M runs only `serve-step` over two ring buffers, holds no state about the inner session (`:622-629, 1039-1043`), any peer may be M, and peers choose M (`:788-790`). What the owner must be told, plainly:

- Two peers that both lack a public address cannot reach each other by any mechanism unless some peer with a public address exists and carries traffic. That is a property of the Internet, not of this design, and no protocol removes it. `serve.md:684-687` already requires M to be directly reachable.
- Until channel encryption and peer authentication exist (deferred, `:969-978`), M reads every inner frame in plaintext and could forge them. "No privileged node" holds for authority in the protocol; it does not yet hold cryptographically. UDP serving is for trusted networks until then, as the doc says.
- Browser to browser P2P is impossible without either M or the deferred WebRTC channel (`:616-619`).

Nothing in the owner invariant is technically impossible under those three statements.

## 2. Toy example walkthrough

Peer A holds string `"hello"` wrapped as a reader-only handle with cursors `{:dao.stream.string/identity id :dao.stream.string/position n}`. Peer B reads it.

| Step | Mechanism | Works? |
|---|---|---|
| A adds `identity -> {handle, #{:reader}}` to its serve table | `serve.md:481, 494-498` | yes; no string special case |
| A's serve descriptor: type `:dao.stream/serve`, identity, `:serve/peer`, candidate = A's WebSocket channel descriptor | `:650-655` | yes |
| B `attach!`s it; host dispatch on `:dao.stream/serve` returns a proxy at once | contract `dao.stream.md:323, 355-361` | yes |
| proxy-step dials candidate 1 via `ws/make-attacher`; A's endpoint accepts the channel path and a `dao.stream.serve.ws` composition acknowledges the offer | `ws.cljc:390-405, 470-536, 548-562`; `serve.md:566-571` | yes, but that composition does not exist yet; `dao.stream.serving` is explicitly not reused (`:571-573`) |
| B sends `:serve/open [B 0]`; A answers `:serve/opened` with surface `#{:reader}`, oldest/newest cursors; for a source that mints no attachments the session attachment is session-scoped | `:138-142, 512-515` | yes |
| B's composition observes `:serve/opened`, then mints `:oldest` from the piggyback | `:334-336, 346-349` | yes; a `cursor` call before `:serve/opened` is `transport-error :retry? true` (needs OD-1) |
| `next` on proxy: window miss, `blocked`, demand issued as wire `next [cursor budget]` | `:383-388` | yes |
| A runs `next` repeatedly on the string handle, returns the ok vector ending with `end` (closed, complete-history) or `blocked` | `:205-209, 229-237` | yes |
| B installs outcomes keyed by the source's own cursors; reader sees the same positions A minted | `:224-226, 383-385` | yes |
| `gap` | string never evicts, excludes `gap`; proxy never synthesizes one | `:442-443` | yes |
| Proxy `next` answers `cursor-mismatch` "locally by identity" | `:399` | **no**: the identity key is transport-namespaced (`ringbuffer.cljc:63`, contract `dao.stream.md:904-906`) and the proxy "never constructs, parses or rewrites a cursor" (`serve.md:224-226`). The proxy cannot read the identity out of a foreign cursor. |

Conclusion: mechanically supported with no special case for "string". Two things do not work as written. The local `cursor-mismatch` row must become "relayed from the source; the proxy checks only structural equality against its window". And a string-backed transport must be written; UCF already demands one for M4 (`yin.vm.universal-continuation-format.md:1716-1725, 1765-1766`).

## 3. The eight decisions

| # | Decision | Why | Risk | Invariant-critical? |
|---|---|---|---|---|
| 1 Held reads | Accept: `:serve/hold` optional, off by default, ignorable by the serving side, never a condition of reachability. | Session state plus a step return satisfies "no operation waits" (`dao.stream.md:153-158`; `serve.md:355-361`). | Inbox-relayed peers pay poll latency on every read; if that dominates, v1 is slow, not wrong. | no |
| 2 OD-1/2/3 | Accept OD-3 (a) and (2), OD-2, OD-1 with the "effect unknown; no automatic retry" fallback, and the two 4.1 definitions; add one sentence to OD-3 (a): a serve descriptor is an endpoint of the owning transport's stream and the proxy is a handle on that stream, not a second transport. | Without OD-3 (2) clause A of the invariant is undefined. Without the added sentence, (a)'s narrowing to "different endpoints of the same owning transport" (`dao.stream.md:929-931`) reads as excluding a descriptor of type `:dao.stream/serve`, contradicting `serve.md:35-38`. | If (a) is read strictly, a served stream is legally a copy and UCF 7.4.3 is void. | yes |
| 3 Lifetime | Accept: lease-governed via `dao.lease.md`, with migration-acceptance pinning as the pre-lease stopgap. | The exporter and M possess the resource, so the judge sits where the lease contract requires (`dao.lease.md:112-114`); the serving-boundary observation supplies the attribution resolver (`serve.md:519-525`; `dao.lease.md:237-241`). | Pinning with no lease leaks served entries and inbox pairs when a resumer dies mid-acceptance. | no |
| 4 Rename | Rename `dao.stream.serving` to `dao.stream.broadcast`, docstring "client/server broadcast convention over the symmetric transport"; `dao.stream.serving.copy` is acceptable if the other architects prefer it. | `serving.cljc:2-8` describes itself as a WebSocket serving composition, which the new vocabulary reserves for the original-stream protocol; a name three letters away from `dao.stream.serve` invites confusion. | Churn in `remote.cljc:29` and its tests; low. | no |
| 5 Admission | Accept: composition policy of the door's peer, open in trusted deployments, section 7.3 bounds mandatory everywhere, postage or capability as an interpreter rule for public doors. | The contract gates nothing and reserves nothing for gating (`dao.stream.md:286-292`). | An open public meeting peer publishes reflexive addresses and fans out inboxes; without the bounds it is an amplifier. | no |
| 6 Peer id | Accept, adding: a peer id is a string in the portable domain, at least 128 random bits, public-key hash intended. | Attachment ids are already strings (`ws.cljc:113-115`; `serving.cljc:228`), and session identity `[peer n]` must cross the wire. | Unauthenticated ids permit impersonation on shared media until key-control verification exists (`serve.md:735-738`); accepted for trusted networks only. | no |
| 7 Append via proxy | Accept: outbound-path `ok` with the source outcome as `:serve/appended` on the event medium; UCF put-resume waits for that event. | The contract forbids a remote transport saying more at `append!` (`dao.stream.md:570-576`), and a proxy that retried an append would violate OD-2's "no automatic retry". | UCF 7.4.3 currently resumes a `:put` on `ok` (`ucf.md:626-628`); left unedited, migrated writers resume before the source has accepted. | no |
| 8 Retire remote transport half | Accept: retire the WebSocket descriptor, `connect-content!`, `serve-content!` and their composition after the service door lands; keep `dao.jing.remote.step`; unify the ingress check on the linker's order. | The three copies differ: `remote.cljc:35-51` hash-checks then CBOR-decodes and throws; `step.cljc:253-261` hash-checks only and returns nil; `linker.cljc:1292-1315` caps encoded text length, then bytes, then hash, then decode. The linker's is the strictest and matches `serve.md:886-890`. | The JVM blocking driver is what `dao.space.index` and `dao.data.btree.storage` call today (`dao.stream.md:1004-1010`); it must survive as host policy over the stepped client or nine call sites break. | no |

## 4. Cross-decision conflicts

- Decision 2 forces an edit in `dao.stream.md` beyond section 16: the "endpoint of the owning transport" sentence in OD-3 (a).
- The section 2 finding forces an edit in `dao.stream.serve.md` itself (the `cursor-mismatch` row at `:399`), which section 16 does not list because it only names other files.
- Decisions 4 and 8 touch the same require line in `remote.cljc:29`; do the rename in the same change as the retirement or accept two rounds of churn.
- Decisions 5 and 6 interact: a capability gate on a door cannot bind to a peer id while ids are unauthenticated, so any v1 capability must be a bearer value inside the request, not a peer-id property.
- Decisions 3 and 7 interact consistently: a lease reclaim with an append in flight yields `:serve/append-unknown` and an undischarged put wait (`serve.md:1002-1004`); the `dao.lease.md` carriage edit in section 16 must name `:serve/close :detached` as what the holder observes.
- Decision 7 requires the UCF edit that section 16 already lists; nothing beyond it.

## 5. Verdict

READY AFTER the section 16 edits are applied, plus two corrections not in section 16: relay `cursor-mismatch` from the source instead of computing it locally (`serve.md:399`), and add the OD-3 (a) endpoint sentence in `dao.stream.md`. The owner should be told the three NAT facts in section 1 as standing caveats, not blockers. Implementation additionally needs a string-backed transport and a `dao.stream.serve.ws` composition, both already implied by the spec and the UCF acceptance matrix.
