# DaoStream Remote Implementation Plan

Status: plan, not rules. The rules are
[`dao.stream.remote.md`](./dao.stream.remote.md); this file carries the
migration design, the completion criteria and slices, and the companion-edit
inventory moved out of that spec to keep it short. Subordinate to the spec
on any disagreement.

## 1. What replaces `dao.jing.remote`: `dao.jing.content`

Content lookup is a request and response service over remote streams
(`dao.stream.remote.md`, section 5), in one module family, `dao.jing.content`:
the namespaces `dao.jing.content` (`serve-step`), `dao.jing.content.step`
(`step`), `dao.jing.content.driver` (`driver`, clj only) and
`dao.jing.content.async` (`async`).

- **Vocabulary**, a payload convention: `{:jing/request r :jing/get
  address}` answered by `{:jing/request r :jing/found? b :jing/bytes b64}`;
  `{:jing/request r :jing/put address :jing/bytes b64}` answered by
  `{:jing/request r :jing/result :inserted | :present}`. `r` is a
  self-minted random value. Bytes travel as padded standard Base64 text in
  the application value; message boundaries are the channel codec's, one
  application value per message, so the vocabulary rides Transit-JSON text
  or canonical CBOR alike (`dao.jing.cbor.md`, Remote and DHT).
- **The interpreter** (`dao.jing.content/serve-step`): a pure step over a
  local `requests` reader and `answers` writer against a `dao.jing` handle.
  It runs the one ingress canonicality check on every put.
- **The ingress check**, one shared function `dao.jing/accept-bytes!`,
  moved from today's private `dao.jing.remote/accept-bytes!` and used by
  the interpreter, by the stepped client on every `get` answer and
  `:present` verify, and by `yin.vm.linker`. The three copies become one
  (today: `dao/jing/remote.cljc`, `dao/jing/remote/step.cljc`, and the
  linker's own; only the first includes the canonical CBOR decode).
- **The stepped client** (`dao.jing.content.step`): the `request-put`,
  `request-get`, `request-materialize`, `step`, `abandon` shape of
  `dao.jing.remote.step`, over a `requests` writer and an `answers` reader
  that are reflections or local handles. This is the portable interface,
  per `dao.stream.md` OD-5.
- **The blocking driver** (`dao.jing.content.driver`, clj only): host
  policy that steps the client and sleeps, returning a synchronous
  `dao.jing` handle. It replaces `connect-content!`; its consumers are the
  coordinate opener and, through it, `dao.space` (the published-index
  coordinate opens it at `src/cljc/dao/space/query.cljc`) and `yin.repl.link`
  on the JVM. `yin.vm.content` has no remote path today (its handle is an
  argument), so it gains one only by being handed a coordinate-built handle.
- **The async facade** (`dao.jing.content.async`): replaces
  `dao.jing.remote.async` over the new stepped client, same consumer API;
  `dao.data.btree.storage` is the consumer (`hydrate-async`,
  `store-tree-async`).
- **The coordinate**: `{:dao.jing/type :dao.jing/remote :dao.jing/requests
  <remote descriptor> :dao.jing/answers <remote descriptor>}` on every
  host, resolved by `dao.jing.coordinate/open!` to the driver on clj and to
  the stepped client elsewhere. The `:url` form is gone; its consumers are
  the tests and the coordinate itself.

## 2. Contract boundary

The `dao.stream.md` changes are the only changes to that contract: OD-1,
OD-2 and OD-3; the `:dao.stream/refused` rows on `cursor`, `next` and
`append!`; and the composed-handle sentence in Surfaces. They add no network
or capability concept. Section 5 holds the fate of every network path;
section 4 lists companion-document edits.

## 3. Completion criteria and implementation slices

Ordered. Each names files, host lanes and its proof. Verification commands
are in `docs/agents/build-n-test.md`.

+----+------------------------------------------------------------+--------------------------------------------------------------+---------------+
| #  | Slice and files                                            | Proves                                                       | Lanes         |
+====+============================================================+==============================================================+===============+
| 0  | Contract and source alignment: the `dao.stream.md`         | Existing consumers pass; `dao.stream.observe/step` and the   | clj cljs cljd |
|    | amendments of the plan's section 2 are accepted;           | engine treat an unrecognized outcome as refused, not a       |               |
|    | `:dao.stream/refused` added to the closed outcome sets     | throw; the outcome sets admit the refusal.                   |               |
|    | for `cursor`, `next` and `append!` in                      |                                                              |               |
|    | `src/cljc/dao/stream.cljc`; `dao.stream.observe`,          |                                                              |               |
|    | `yin.vm.engine` follow the unrecognized-outcome rule.      |                                                              |               |
+----+------------------------------------------------------------+--------------------------------------------------------------+---------------+
| 1  | `dao.stream.middleware`: `apply-request`, `wrap`, `gate`,  | Position rule under a value cipher over a ring buffer; a     | clj cljs cljd |
|    | `present`, a metering exemplar, an index-interpreter        | gate with the channel allow-list refuses with                |               |
|    | exemplar publishing decisions. `src/cljc/dao/stream/        | `:dao.stream/refused`; a gate reads the latest decision      |               |
|    | middleware.cljc`, tests.                                   | through a capacity-1 medium after eviction; a filter cannot  |               |
|    |                                                            | be expressed.                                                |               |
+----+------------------------------------------------------------+--------------------------------------------------------------+---------------+
| 2  | `dao.stream.remote` core: `mirror-step`, link, reflection, | The toy over two in-process ring buffers as the channel;     | clj cljs cljd |
|    | remote descriptor dispatch, the protocol errors.           | `gap` from an evicting source crosses verbatim with the      |               |
|    | `src/cljc/dao/stream/remote.cljc`, tests.                  | source's cursor; `blocked` then `ok` on re-ask; `not-found`  |               |
|    |                                                            | marks gone; `no-surface` does not; the descriptor answer     |               |
|    |                                                            | carries the surface; a kept cursor from a reflection is      |               |
|    |                                                            | accepted by the source handle.                               |               |
+----+------------------------------------------------------------+--------------------------------------------------------------+---------------+
| 3  | WebSocket channel composition: `ws-project` at each end;   | The toy across a socket on clj to Node, Node to clj, cljd    | clj cljs cljd |
|    | mirror steps over accepted and dialed attachments;         | to clj; both directions serve on one channel; browser        |               |
|    | retire `:ws/accept`, disclaim and the served-path table    | dials and reads; a closed connection is observed as the      |               |
|    | in `dao.stream.ws`.                                        | link's `end`.                                                |               |
+----+------------------------------------------------------------+--------------------------------------------------------------+---------------+
| 4  | Content service: the `dao.jing.content` family (section 1) | `dao.jing.remote` tests ported and passing over the new      | clj cljs cljd |
|    | with `serve-step`, `step`, `driver` (clj), `async`;         | module; `dao.space.index`, btree hydration, the linker M3    |               |
|    | `dao.jing/accept-bytes!`; `dao.jing.coordinate` new         | and M4 tests unchanged in outcome; no require of             |               |
|    | coordinate; delete `src/cljc/dao/jing/remote.cljc`,        | `dao.jing.remote` remains.                                   |               |
|    | `remote/step.cljc`, `remote/async.cljc` and their tests.   |                                                              |               |
+----+------------------------------------------------------------+--------------------------------------------------------------+---------------+
| 5  | REPL as a service: `yin.repl.serve` and `connect` over     | `(connect "daostream:ws://...")` resolves to a remote        | clj cljs cljd |
|    | `requests` and `answers` reflections; delete               | descriptor pair; the demo REPLs work; `yin.repl.link` serves |               |
|    | `dao.stream.serving`, `dao.stream.rpc.ws`, the apply wire  | content through slice 4; `dao.stream.rpc` is reworked over   |               |
|    | envelope from `dao.stream.apply`; rework or retire         | reflections or retired in the same slice, because the apply  |               |
|    | `dao.stream.rpc`.                                          | envelope it requires is deleted here.                        |               |
+----+------------------------------------------------------------+--------------------------------------------------------------+---------------+
| 6  | UDP channel: `dao.stream.udp` with fragmentation keyed by  | The toy over UDP with a 48 KiB value (inside the default     | clj cljs cljd |
|    | attachment, address, direction and id; host seams          | 64 KiB maximum); loss injection recovers by resend;          |               |
|    | `dao.stream.datagram.jvm`, `.node`, and `.dart`.           | oversize beyond the maximum answers `transport-error`        |               |
|    |                                                            | with reason `oversize`.                                      |               |
+----+------------------------------------------------------------+--------------------------------------------------------------+---------------+
| 7  | Pair channel and the meeting and relay conventions with    | Two peers behind a simulated restricted NAT punch; behind    | clj           |
|    | leases: `dao.stream.remote.pair`, `dao.stream.remote.meet`;| a simulated symmetric NAT they relay; a pair whose holder    |               |
|    | a meeting gate refuses past the pair bound.                | stops renewing answers `not-found` after duration plus       |               |
|    |                                                            | tolerance; a reconnect within it finds the pair intact; a    |               |
|    |                                                            | meeting peer past its bound refuses, not silences.          |               |
+----+------------------------------------------------------------+--------------------------------------------------------------+---------------+
| 8  | UCF facade: `yin.vm.universal-continuation-format.md`      | The acceptance row: a string-backed stream migrates with a   | clj cljs cljd |
|    | 7.4.3 and 7.5.3 lift and lower through remote descriptors  | kept cursor and resumes; forced eviction yields `gap` with   |               |
|    | with the `:dao.stream.remote/v1` cursor profile.           | the source's cursor.                                         |               |
+----+------------------------------------------------------------+--------------------------------------------------------------+---------------+

Slice 7 also proves the pair rule of the spec's 3.3: a `gap` on a pair's
`in` ends that link's channel, outstanding appends are reported
`append-unknown`, and `attach!` on the same pair descriptor resumes.

Deferred, in order of expected arrival: authentication and authorization
(ShiBi), key distribution, channel-level encryption, a WebRTC data channel,
browser inbound. None changes a shape in the spec's section 2.

## 4. Companion-edit inventory

The edits the unification requires in other design documents, beyond the
spec's own sections. Those not yet made are slices; those made in the
authoring and fix rounds are listed as done.

- `dao.stream.md` (done): OD-1, OD-2, OD-3 accepted and moved into the
  contract body; the `:dao.stream/refused` row on `cursor`, `next` and
  `append!`; the composed-handle sentence in Surfaces; the OD-3 wording
  corrected so `attach!`'s `not-found` and a stale cursor are separate
  cases.
- `dao.lease.md` (done): served entries and relay pairs named as lease
  subjects; the carriage note names the `not-found` protocol error.
- `dao.stream.ws.md` (done): the stream belongs to the peer that holds it;
  the copy path versus attachment to the original; dialer-side and
  acceptor-side; deferred liveness, resumption and RPC items point to the
  spec.
- `yin.vm.universal-continuation-format.md` (done): 7.4.3 and 7.5.3 cite
  the spec; the put resume rule waits on the event writer's source outcome;
  `:yin.k/cursor-profiles` names `:dao.stream.remote/v1`; the acceptance
  row cites slice 8 here.
- `dao.jing.md` (done): the coordinate shape and its today/target split;
  the deprecated backend bullet; `dao.jing/accept-bytes!` marked as the
  target; the Base64 carriage named as channel-codec message boundaries.
- `yin.vm.linker.md` (done): the content pair vocabulary cites section 1
  here; the 6.1 table row, 6.3 shape, M3, deps and failure-policy timing.
- `dao.data.btree.md` (done): the 5.4 backend row annotated as the target
  name for `dao.jing.remote`; the Phase 4 landing note.
- `dao.jing.dht.md` (done): the NAT limitation.
- `daostream-udp-design.md` (done): superseded status line.
- `dao.jing.cbor.md` (done): the framing claims corrected to channel-codec
  message boundaries with Base64 in the application value.
- `yin.vm.debruijn.linker.md`, `dao.space.query.md`,
  `yin.vm.debruijn.stack.md`, `dao.jing.remote.implementation-plan.md`
  (done): one status sentence each naming the successor.
- `dao.jing.hash-registry.md`, `dao.jing.call-site-classification.md`
  (done): one-line pointers that the `dao.jing.remote` sites describe
  today's code and the successor keeps the checks and classifications.
- `docs/dao.space.stigmergy.md` (done): the minimum viable stack's content
  step marked historical, pointing at `dao.jing.content`.
- Remaining, with the code: the `dao.stream.ws.md` residual dialer/acceptor
  vocabulary beside its older deposit-model wording, and `dao.stream.md`'s
  own Close-section "server-side accepted-connection handle", track the
  contract's wording and are re-worded with the code slices, not before.

## 5. Unification: the fate of every existing path

Moved from the spec's section 8, which keeps only the normative summary.
Every existing network path, verified against the tree, and its fate:

- `dao.stream.ws` and the jvm, node, browser, dart adapters: **subsumed**.
  Already a channel: writer plus deposit medium plus the spec's 3.1
  projection. Its accept and disclaim frames and served-path table become
  unnecessary once `not-found` is an answer per identity; retiring them is
  slice 3.
- `dao.stream.serving`: **retired**. Forwards a source into every accepted
  socket and flattens a source `gap` into a detach: the copy model. Its
  consumers are `yin.repl.serve` and `dao.jing.remote` (which requires and
  drives it); both are retired by slices 4 and 5.
- `dao.stream.forward`: **unrelated**. A local copy step; replication by
  convention, not exposure.
- `dao.stream.rpc`: **convention-over**. Correlation by id over two streams
  is the spec's section 5 service; its state machine may be kept as one
  such vocabulary over reflections, or retired in slice 5.
- `dao.stream.rpc.ws`: **retired**. Its only job is translating `:ws/`
  envelopes for rpc; the 3.1 projection is the channel binding now.
- `dao.stream.apply` as a wire envelope: **retired**. Its ok-or-error
  wrapper duplicates the outcome map. The `:dao.stream.apply/call` AST node
  and the VM's FFI bridge keep the name and are unrelated to the network.
- `yin.repl.serve`, `connect`, `driver`, `adapter`, the `daostream:ws://`
  URL: **convention-over**. An eval service: `requests` and `answers`
  streams, `:op/eval` as payload; the URL becomes a remote descriptor for
  that pair. The shared-shell privilege is its interpreter's policy.
- `dao.jing.remote`, `remote.step`, `remote.async`, the `:dao.jing/remote`
  coordinate in `dao.jing.coordinate`: **retired**. Deprecated whole;
  replaced by `dao.jing.content` (section 1).
- `yin.repl.link`, `yin.vm.linker` M3 and M4: **convention-over**. The
  content pair is a request and response service over reflections or local
  ring buffers; the linker's stepped core is unchanged.
- `dao.data.btree.storage/hydrate-async`, `store-tree-async`:
  **convention-over**. Consume the content client of section 1; the
  callback facade is host policy, per `dao.stream.md` OD-5.
- `dao.jing.dht.node` UDP DHT: **unrelated**. Raw JVM datagram RPC with its
  own Kademlia envelope; its socket rules and 1200-byte budget are the UDP
  channel's template.
- `dao.stream.transit`, `dao.stream.cbor`: **subsumed**. The channel
  codecs, unchanged.
