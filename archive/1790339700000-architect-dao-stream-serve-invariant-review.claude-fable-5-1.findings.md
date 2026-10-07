Completed-GMT: 2026-09-25 12:45:11 GMT
Completed-Local: 2026-09-25 19:45:11 +07

# dao.stream.serve — conformance review against the P2P invariant

Verdict up front: the design passes on its protocol core (sessions, mechanical derivation, proxy, idempotent reads) and **fails on reachability**. Relay and rendezvous were written as privileged node kinds with their own frames and step functions. They can be dissolved entirely into ordinary peers serving ordinary streams under a convention, and the result is simpler than what I wrote. The rest is vocabulary. Revised text for every changed section is in part 6.

## 1. The reading I took of the invariant

- **"No concept of a server or client"** forbids node *kinds* and protocol *frames* that only some kind of node handles. It does not forbid per-session asymmetry: in any one session one peer holds the stream and the other attaches to it. That asymmetry is the contract's own (a handle is on the stream or on an attachment) and is the "client/server by convention" the owner allows.
- **"No privileged node"** means every peer runs the same protocol with the same two steps, and any peer may run any convention on top. A peer that happens to run a directory convention is not privileged, because the protocol has no frame for it and any other peer may run it too.
- **"Traverse NAT in a P2P use case"** I read as: direct where the network allows (hole punching), and otherwise through some third peer, without that third peer being a special kind.
- **"Mechanically exposed"** I read as attachable *as itself*, with the source's cursors, not copied. Unchanged from the design.

Owner questions the reading raises: (a) confirm that a convention some peers run (a meeting stream, below) is not a "privileged node" in the intended sense; (b) confirm that dialer versus acceptor on a WebSocket is acceptable as a per-channel establishment fact given that browsers cannot accept.

## 2. Inventory of server, client and privileged-node concepts

Line numbers refer to `collab/1790335900000-architect-dao-stream-serving-spec.claude-fable-5-1.findings.md`.

| Severity | Design line | Why it conflicts, or does not | Peer-symmetric restatement |
|---|---|---|---|
| high | 80-84, §3.2 frames `:serve/register`, `:serve/dial`, `:serve/introduce`, `:serve/via`, `:serve/punch` | **Real.** Five protocol frames exist only for relay and rendezvous nodes; a frame only one kind handles is a privileged kind written into the wire. | Delete all five. Registration, dialing and introduction become values on ordinary streams under a convention (§7 revised). `:serve/punch` is `:serve/ping`. |
| high | 224-228, §7.3 `relay-step`, `{:peers :links}` | **Real.** A node kind with its own step and its own state that no other peer runs. | A peer that serves two ordinary streams per visitor (an inbox pair) and runs the ordinary serve-step. No relay step exists. |
| high | 230-234, §7.4 rendezvous S, `:serve/introduce`, reflexive address as a protocol fact | **Real.** Same defect; S is a kind. | Reflexive addresses are attachment identities the UDP channel already deposits; a peer that runs the meeting convention announces them on its own single-writer stream. |
| high | 199-201, §6.4 `:serve/via` channel-over-channel | **Real**, and unnecessary. It exists only because a relay was a forwarder. | A channel is any writer handle plus any reader handle (§6.1 revised). Two proxied streams served by a third peer *are* a channel; no wrapping frame is needed. |
| high | 205-218, §7.1 descriptor: `:serve/peer` "the serving peer", candidates with `:serve/via :relay|:punch`, identities `"relay-R"` | **Real** in the candidates; vocabulary in `:serve/peer`. | A candidate is either a channel descriptor or a serve descriptor for a stream some peer serves that carries my inbox. Punching is a resolution rule against a meeting stream, not a candidate kind. |
| medium | 49-53, §3.1 "session id minted by the opening side, unique per channel" | **Real but latent.** On a symmetric channel both ends mint; on a shared inbox many peers mint. Collisions. | Session identity is `[opener-peer-id n]`. |
| medium | 156-164, §5 title "The server"; 91, 93, 114, 131 "the server computes/keeps/honors" | Vocabulary, but the title enshrines a kind. | Retitle: "The serving side: serve-step and the serve table (every peer has one)". Replace "the server" with "the serving side of the session". |
| medium | 244-256, §8 rows "relay/rendezvous steps", "relayed channel (§6.4)", "serves and proxies through a relay" | Follows from the above. | Rows removed; browser row reads "through a peer that serves its inbox". |
| medium | 286-296, §13 criteria 5 and 6 ("the relay's state never holds a session"; "through a rendezvous") | Follows from the above; criterion 5 is now inverted. | See revised §13. |
| low | 179-186, §6.2 "serve endpoint serves one path", "client only", "server via the existing endpoint seam", channel identity "the serving peer id" | **Establishment fact, stays.** TCP has a listener and a dialer; browsers cannot listen. This is a channel property and confers nothing after establishment. | Say so explicitly: the ws descriptor's host and port are the *acceptor's*; once established, both ends attach the channel to both steps. Channel identity is the acceptor's peer id. |
| low | 220-222, §7.2 "The serving host listens" | Establishment fact, but the sentence names a host kind. | "A peer that has an acceptor channel (a listening socket) can be reached directly." |
| low | 9-21, §1 "serving", "the exporter", "the resumer" (§9) | Vocabulary. Serve and attach are per-session verbs; exporter and resumer are per-migration roles under the UCF, which the owner's convention clause covers. | Keep; add one sentence in §2 stating every peer has both steps. |
| low | 267-274, §10 "Relay and rendezvous interpret nothing" | Follows. | Line removed. |
| low | 304-312, Q3, Q4 as written | Both presume a relay authority. | Revised in part 4. |
| none | 55-78, `:serve/open`, `:serve/opened`, `:serve/disclaim` | Per-session asymmetry: one side holds the stream. Permitted. | Unchanged; note it as a session fact. |
| none | 236-238, §7.5 | Already symmetric. | Unchanged. |

What stays as a per-channel establishment fact and is never an authority: which end listened and which dialed (WebSocket); whose socket address is written in a channel descriptor; that a browser can only dial. After establishment, every channel is attached to both `serve-step` and `proxy-step` on both ends.

## 3. Can relay and rendezvous be ordinary peers? Yes, completely

The mechanism. Any peer M may choose to serve a **meeting stream pair** under identities it announces however it likes (a descriptor in a directory stream, a rendezvous topic in `dao.stream.discovery`, a URL on a wall). The pair is exactly the `dao.stream.ws.md` request-medium pattern, so nothing new is specified:

- **`meet-requests`**: writer surface only. Any peer opens a session and appends requests. This is M's request medium; many writers, and the meaning of their order is M's interpreter's.
- **`meet-announcements`**: reader surface only, single-writer (M). M's interpreter reads its request medium and appends facts.

Three request kinds and three fact kinds, all convention vocabulary under `:meet/…`, none of them serve frames:

- `{:meet/here <peer-id>}` → M creates two ring buffers, serves them, and announces `{:meet/inbox <peer-id> :dao.stream/identity <in> …}` with the serve descriptors of the pair. Any peer that reads the announcement stream now knows how to reach that peer through M.
- On every request M's interpreter also announces `{:meet/seen <peer-id> :meet/reflexive {:host … :port …}}`, taken from the request's `:udp/attachment` in M's traffic medium. That is the whole of STUN: the reflexive address is a channel fact M already deposits, republished as a value.
- `{:meet/call <peer-id> :meet/from <my-peer-id>}` → M announces it. Both parties read the announcement stream anyway, so both learn the other's reflexive address and that a call is in progress. That is the whole of the introduction.

**Relayed channel.** A channel is a writer handle plus a reader handle (§6.1 revised). Peer A reaching B through M uses a proxy to B's inbox as its writer and a proxy to its own inbox as its reader. The serve protocol runs unchanged over it: sessions inside, `:serve/open` on B's serve table, cursors verbatim. M runs the ordinary serve-step over ring buffers and never sees a session it did not itself serve. Because the inner protocol already tolerates loss (idempotent reads, deduplicated appends), an inbox that evicts under pressure produces an inner `gap` on the outer proxy, which the inner retry policy absorbs.

**Hole punching.** After a call is announced, both peers `attach!` a UDP channel to the other's reflexive address and send `:serve/ping`. The first inbound datagram from that address is deposited by the UDP adapter with that attachment identity; the composition matches it to the pending candidate and the direct channel exists. If no pong arrives in the punch budget, the inbox-through-M channel is the next candidate. No frame beyond `:serve/ping` and `:serve/pong` is involved.

**What remains privileged:** nothing at the protocol level. Two facts remain, neither a privilege: M must have a reachable address (a network fact, and exactly why some peer has to be reachable for any two NATed peers to meet), and M must have chosen to run the meeting convention (a composition choice any peer can make). Under the reading in part 1 this satisfies the invariant. It is also stigmergy in the literal sense: peers coordinate through traces left on a shared medium, which is the project's founding model, and the meeting stream is the same object as the discovery document's directory stream.

Costs, stated honestly: a relayed message is framed twice (outer session to M, inner session to the peer) and a peer must poll its inbox on M. The second cost is what changes Q2.

## 4. Effect of the invariant on the Q1–Q7 consensus

| Q | Consensus position | Effect | Revised position |
|---|---|---|---|
| Q1 | event-medium append; UCF `:put` resumes on observed source outcome; append-unknown leaves the wait undischarged | none; no role is involved | unchanged |
| Q2 | defer held reads | **changes** | Include `:serve/hold` in v1, or at the latest in the same milestone as the meeting convention. The consensus deferred it when a relay was a forwarder that pushed frames. With no relay, a peer's reachability through M *is* polling its inbox proxy; held reads are what keep that from being a busy loop across the network. Deferring them now makes the only NAT fallback a chatty one. |
| Q3 | opaque composition-assigned ids; duplicate `:serve/register` is relay policy; self-certifying later; public relay needs channel auth | **changes** | There is no assigner. A peer id is **minted by the peer itself**, from the start: a 128-bit random value at minimum, and in the self-certifying form (hash of the peer's public key, `dao.stream.discovery` mechanism 1) as the intended format, with signature verification an additive rule later. "Composition-assigned" must not mean assigned by a relay. Duplicate `:meet/here` for one id is an ordinary fact on M's announcement stream; what M's interpreter does with it is M's convention, and which meeting stream a reader trusts is the reader's choice, exactly as with directories. The "public relay needs channel auth" tripwire becomes "a peer serving a public request stream gates it by an interpreter rule", which the contract already permits. |
| Q4 | relay admission is composition policy; lifecycle and caps required; postage future | **moves, substance survives** | There is no relay to admit. Inbox creation, retention and idle unserving are M's serve-table policy over its own request medium, identical to any served writer surface. Caps and lifecycle are required exactly as the consensus said, restated as "M bounds the number and retention of inboxes it serves". Postage stays the intended answer for a public meeting stream. |
| Q5 | accept OD-2, OD-3, corrected OD-1; amend blocked and anchors | none | unchanged |
| Q6 | lease-governed lifetime; retirement as stopgap | none; exporter and resumer are per-migration roles | unchanged |
| Q7 | `dao.stream.serve`; rename `dao.stream.serving`; fix ws prose | strengthens | unchanged, plus: retitle §5, delete the relay and rendezvous sections, and rename the copy composition to say "copy" or "broadcast" so it reads as a convention. |

The consensus is not wrong where it stands; it was reached against a design that had a relay in it. Q2 and Q3 are the two places where removing the relay flips the answer.

## 5. Contradictions with existing docs under the invariant

| Severity | File:line | Evidence | Correction |
|---|---|---|---|
| high | `docs/design/dao.stream.ws.md:9-16` | "the stream's identity and lifecycle belong to the server, not to the wire"; "server-hosted stream" | Identity and lifecycle belong to the peer that holds the stream. Acceptor versus dialer is a channel establishment fact. Reword; the state machine already permits either end to hold the served stream. |
| high | `docs/design/dao.stream.ws.md:18-26` | "serving is what makes any stream remotely attachable" | Still the copy-model claim flagged before; under the invariant add that the copy composition is one convention, not the transport's meaning of serving. |
| medium | `src/cljc/dao/stream/serving.cljc:1-11, 234-237` | "Host-owned WebSocket serving composition"; forwards the source from `:forward-anchor` into every accepted socket | Rename to a copy or broadcast name; its docstring should say it implements a client/server broadcast convention over the symmetric transport. |
| medium | `docs/design/dao.stream.ws.md:32-45, 336-371` | "client handle", "server-side accepted-connection handle", "the serving host closes… the client" | Vocabulary: dialer-side and acceptor-side handles; the holder of the stream closes it. |
| low | `docs/design/dao.stream.md:594-600` | "a server-side accepted-connection handle is an attachment and does not gain ownership merely by being on the serving host" | Already the right principle. Reword "server-side" and "serving host" to "acceptor-side" and "the host holding the stream" so the contract does not lend the words authority. |
| low | `docs/design/dao.stream.discovery.md:120-125` | rendezvous topics: "the neighborhood stores but never authors" | Not a contradiction; a dependency. The meeting convention is a single-writer announcement stream plus a request medium, and a rendezvous topic is where its descriptor is found. Cite the serve spec from here and adopt mechanism 1 for peer ids (Q3). |
| low | `docs/design/dao.jing.dht.md:165-167` | "hole-punching, relay/TURN, and bootstrap discovery" | "relay/TURN" becomes "an inbox pair served by any reachable peer"; a DHT node is a natural peer to run the meeting convention. |
| low | `src/cljc/dao/stream/rpc.cljc:100-110`, `docs/design/dao.stream.apply.md` | `server-state`, `serve-once!` | Per-session RPC roles, not node kinds. No change needed; note it. |

## 6. Revised draft spec sections (ready text)

Only the sections that change. Section numbers match the design.

### §2 Layering (replace the diagram's last two rows and add one rule)

```
channel        a writer handle and a reader handle the composition supplies:
               a ws or udp attachment with its deposited medium, or two proxied
               streams served by a third peer, or two ring buffers in one process
reachability   how a channel is established: direct, punched, or through a peer
               that serves an inbox pair under the meeting convention (§7)
```

Add to the rules: **Every peer runs both steps.** `serve-step` answers sessions on the streams in its serve table; `proxy-step` drives the sessions it opened. Every channel, whoever dialed it, is attached to both steps at both ends. There is no server node and no client node; "serving" and "attaching" are what a peer does in one session.

### §3.1 Sessions (replace the identity sentence)

A session is identified by `[opener-peer-id n]`, where `n` is unique among the sessions that peer has opened on this channel. Both ends of a channel may open sessions, and a channel may be a shared medium with many opening peers, so the peer id is part of the identity.

### §3.2 Frames (delete five, rename none)

Delete `:serve/register`, `:serve/dial`, `:serve/introduce`, `:serve/via` and `:serve/punch`. The reachability control block is removed entirely. The frame set is: `:serve/open`, `:serve/opened`, `:serve/disclaim`, `:serve/close`, `:serve/request`, `:serve/response`, `:serve/ping`, `:serve/pong`. Nothing in the protocol names a peer kind.

### §5 The serving side: serve-step and the serve table (retitle; body unchanged except wording)

Replace "the server" with "the serving side of a session" throughout, and open the section with: every peer has a serve table and runs `serve-step`; a peer whose table is empty answers every `:serve/open` with `:serve/disclaim` and is otherwise indistinguishable from any other.

### §6.1 Channel contract (replace items 1 and 2)

1. A **writer handle** whose `append!` places one value on this channel's outbound path.
2. A **reader handle** with the contract's reader surface, positioned on this channel's inbound values. A ws or udp attachment supplies it as the medium its adapter deposits into; a proxied stream served by another peer supplies it directly, because a proxy has a reader surface; two ring buffers supply it in process.

A channel declares ordering and reliability from its parts: a deposited medium is ordered and best-effort under eviction; a proxied inbox is ordered and best-effort under the inbox's retention; UDP is unordered and best-effort.

### §6.2 WebSocket channel (replace the Direction paragraph and host line)

**Direction is establishment, not authority.** A WebSocket has a dialer and an acceptor because TCP does; a browser can only dial. The channel descriptor's host, port and path are the acceptor's, and its `:dao.stream/identity` is the acceptor's peer id. Once established, the channel is attached to both steps at both ends and nothing in this layer remembers who dialed. Hosts: clj, cljs node and cljd may accept or dial; cljs browser may dial. `dao.stream.serve.ws` wires an attachment, from either side, to `serve-step` and `proxy-step`.

### §6.4 A channel over served streams (replaces "A channel over a channel")

Two proxied streams are a channel: peer A's writer is a proxy to a stream B reads, and A's reader is a proxy to a stream B writes. When a third peer M serves both streams, A and B have a channel without reaching each other, and M runs nothing but the ordinary serve-step over two ring buffers. Serve frames inside such a channel are values in M's streams and M never interprets them. This is streams as values sent through streams, and it is the whole of relaying.

### §7 Reachability (replace §7.1 through §7.4)

**7.1 The serve descriptor.** As before, with `:serve/peer` the holding peer's self-minted id, and `:serve/candidates` an ordered vector where each element is one of:

- a **channel descriptor** (ws or udp) naming an acceptor address of the holding peer;
- a **serve descriptor** for a stream some peer M serves that is the holding peer's inbox, together with the announcement-stream descriptor where M publishes the matching outbox: `{:dao.stream/type :dao.stream/serve :dao.stream/identity <inbox> :serve/peer M :serve/meet <M's announcement-stream descriptor> :serve/candidates [M's channels]}`;
- a **meeting resolution** `{:serve/meet <descriptor> :serve/peer <holding-peer-id>}`, meaning: attach to that announcement stream, append `:meet/call` to its request stream, read the holding peer's reflexive address, and attach a UDP channel to it while the holding peer does the same toward mine.

The proxy tries candidates in order; each attempt's resolution is deposited on the proxy's event medium. The descriptor carries no authorization.

**7.2 Direct.** A peer with an acceptor channel, a listening ws endpoint or a bound UDP socket, is reached by a channel descriptor. This is establishment: the acceptor holds no authority the dialer lacks.

**7.3 The meeting convention.** Any peer M may serve a **request stream** (writer surface; M's request medium, many writers) and an **announcement stream** (reader surface; single writer, M). An interpreter of M's composition reads the request medium and appends to the announcement stream. The vocabulary is a convention, not protocol:

```clojure
;; requests, appended by any peer through an ordinary session
{:meet/here <peer-id>}                                   ; make me reachable through you
{:meet/call <peer-id> :meet/from <peer-id>}              ; I want a channel to that peer

;; facts, appended by M alone
{:meet/inbox <peer-id> :serve/in <serve descriptor> :serve/out <serve descriptor>}
{:meet/seen  <peer-id> :meet/reflexive {:host … :port …}}   ; from :udp/attachment on the request
{:meet/call  <peer-id> :meet/from <peer-id>}                ; the call, republished
```

On `:meet/here`, M creates two ring buffers, adds them to its serve table, and announces their descriptors; the named peer reads `in` and any peer may write it; the reverse for `out`. The pair, proxied, is a channel (§6.4). Retention, the number of pairs M keeps, and when an idle pair is unserved are M's composition policy, the same policy any served writer surface has. M is not a relay; it is a peer serving ring buffers, and a peer that reads M's announcements decides whether to trust them exactly as it decides whose directory to fold in `dao.stream.discovery`.

**7.4 Punching.** After a call is announced, each party has the other's reflexive address from `:meet/seen`. Each `attach!`es a UDP channel to it and sends `:serve/ping` on the schedule its driver's `now` allows; the first inbound datagram from that address is deposited with that attachment identity, the composition matches it to the pending candidate, and a direct channel exists. No pong within the punch budget means the next candidate, normally the inbox pair on the same M. A symmetric NAT defeats punching and takes that path; the protocol reports a failed candidate, not a failure.

**7.5 P2P symmetry** and **7.6 Liveness** unchanged, except that `:serve/ping` is also the punch probe.

### §8 Host isolation (replace three rows)

| Piece | clj | cljs browser | cljs node | cljd |
|---|---|---|---|---|
| serve frames, proxy-step, serve-step, meeting interpreter | ✓ | ✓ | ✓ | ✓ |
| ws channel, dial | ✓ | ✓ | ✓ | ✓ |
| ws channel, accept | ✓ | — | ✓ | ✓ |
| channel over served streams (§6.4) | ✓ | ✓ | ✓ | ✓ |

A browser peer is a full peer: it serves and attaches over any channel it can dial, and is reachable by any peer through an inbox pair on a peer it has dialed.

### §12 Deferred (replace the authorization item)

Gating of a public meeting request stream (postage or capability) is an interpreter rule on M's own medium; the contract gates nothing and this layer adds nothing.

### §13 Completion criteria (replace items 5 and 6)

5. **Reachability without acceptance.** A peer that only dials serves a stream to a peer that only dials, through an inbox pair on a third peer that runs nothing but `serve-step` and the meeting interpreter; the third peer's serve table holds two ring buffers and no other state about the session.
6. **Punch and fallback.** Two UDP peers behind a NAT simulator obtain a direct channel through a meeting stream's `:meet/seen` facts and `:serve/ping`; under a symmetric-NAT simulator they fall to the inbox pair, reported as a failed candidate on the proxy's event medium.

Add: 10. **Symmetry proof.** The same peer composition, with no configuration difference beyond which channels it has, plays the accepted-side, dialed-side, inbox-holding and inbox-using positions in items 5 and 6.

No repository file was edited; this review is read-only as instructed.
