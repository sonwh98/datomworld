# dao.stream.codec: peer wire codecs for dao.stream

CBOR and Transit-JSON as peers behind one codec interface, selected by the
composition, with no codec requiring another and no shared namespace requiring
a particular codec; open to protocol buffers and Apache Arrow without designing
them.

Status: design only; nothing commissioned. Written 2026-10-20 by the Architect
(claude-fable-5-1; rationale in
`collab/1792455434000-architect-stream-codec-design.claude-fable-5-1.findings.md`,
made standalone the same day per
`collab/1792456214000-architect-stream-codec-standalone.claude-fable-5-1.findings.md`).
Revised the same day (r2) to address the independent review
`collab/1792456650000-architect-stream-codec-design-review.gpt-6-astra.findings.md`
(findings A1-A5; the revision's rationale is in
`collab/1792456810000-architect-stream-codec-design-r2.claude-fable-5-1.findings.md`),
and again (r3) to address the round-2 review
`collab/1792457440000-architect-stream-codec-design-r2-review.gpt-6-astra.findings.md`
(findings A1-r2 through A4-r2; rationale in
`collab/1792458220000-architect-stream-codec-design-r3.claude-fable-5-1.findings.md`).
This is an independent track: it depends on no other design document and no
other document's slices, and an orchestrator can run it from this document
alone (§10, §11). No code was changed; every claim about the tree is labelled.

Owner direction (verbatim, typos preserved): "redesign the system so that cbor
and transit are peer encoding. one can be swapped out for the other without
breaking client callsites. In addition, the design should be open to allow
future encodin like protocol buffers or apache arrow". Earlier the same day:
"we should rethink dependency on transit namespace. it should not require
transit".

Authorities:
- [datom.world.md](./datom.world.md) and
  [datom.world.abi.md](./datom.world.abi.md): `dao.stream` is the one
  abstraction boundary; implementations do not add contracts; no second ABI.
- [dao.stream.md](./dao.stream.md): the contract (operations, outcomes,
  portable descriptors).
- [dao.stream.ws.md](./dao.stream.ws.md): the WebSocket transport, the only
  consumer of codec profiles today, and its codec-profile text.
- [dao.stream.wasm.md](./dao.stream.wasm.md) §3: the wasm import surface uses
  Jing's canonical CBOR; no alternate framing.
- [dao.data.md](./dao.data.md): `tag`/`summarize` and their portable-domain
  check.
- [dao.jing.md](./dao.jing.md) and `dao.jing.stream` (the Jing/transport
  boundary adapter).

Labels: V = verified by reading this tree on 2026-10-20 (line cites); R = read
or inferred, not run; P = proposed, does not exist yet. Nothing was executed.

---

## 1. Problem

V: the two codecs are already shaped alike. Each exports a `profile` map with
the same five keys, `:ws/subprotocol`, `:ws/frame-kind`, `:ws/portable-value?`,
`:ws/encode`, `:ws/decode` (`src/cljc/dao/stream/transit.cljc:136-145`,
`src/cljc/dao/stream/cbor.cljc:111-119`), gated by `dao.stream.ws/codec-profile?`
(`src/cljc/dao/stream/ws.cljc:27-38`). The transport calls only those five
entries (`ws.cljc:283-286` encode on append; `:411-414` decode and domain check
on receive; `:448,459` frame kind). The docstrings say the codec is "a
composition choice carried as an explicit codec profile" (`ws.cljc:10-15`) and
"plain composition data; the transport never special-cases it"
(`transit.cljc:136-140`).

V: the code does not do what the docstrings say. Transit is privileged in
three ways:

+------------------------------+-----------------------------------------------------------------------------------------------------------------+
| Privilege                    | Where                                                                                                           |
+==============================+=================================================================================================================+
| Hard-coded default           | `ws.cljc:44` (`checked-codec`), `:75` and `:92` (one-arity `descriptor?`/`servable-descriptor?`), `:557`        |
|                              | (`make-endpoint`), `:647` (`accept-connection!` when the seam names no subprotocol); the three host adapters'   |
|                              | `default-codecs [transit/profile]` (`src/clj/dao/stream/ws/jvm.clj:36-38,423`; `src/cljs/dao/stream/ws/`        |
|                              | `node.cljs:48-50,249`; `src/cljd/dao/stream/ws/dart.cljd:26-28,225`) and their `ws/subprotocol` fallbacks       |
|                              | (`jvm.clj:333-334`, `node.cljs:210`, `dart.cljd:143`, `browser.cljs:108`).                                      |
+------------------------------+-----------------------------------------------------------------------------------------------------------------+
| Hosts the numeric domain     | `transit.cljc:15-43` defines `max-safe-integer`, `min-safe-integer`, `safe-number?`; `dao.stream.cbor` requires |
|                              | Transit for `safe-number?` alone (`cbor.cljc:20,64`), `dao.data` for `portable-value?`, which it applies only   |
|                              | to numbers (`src/cljc/dao/data.cljc:11,52-60`).                                                                 |
+------------------------------+-----------------------------------------------------------------------------------------------------------------+
| Named by a consumer          | `src/cljc/dao/jing/stream.cljc:26,32-39` tests a profile by comparing its subprotocol to `transit/profile`'s    |
|                              | and `cbor/profile`'s, and throws for any third codec (`:57-67`).                                                |
+------------------------------+-----------------------------------------------------------------------------------------------------------------+

V: the production composition chooses nothing. `dao.stream.remote-channel`
calls `ws/make-endpoint` without `:codecs` (`src/cljc/dao/stream/remote_channel.cljc:339-351`),
the host `:bind!` without `:codecs` (`:385-394`) and `ws/make-attacher` without
`:codec` (`:823-827`); `yin.repl.serve` (`src/cljc/yin/repl/serve.cljc:240-247`)
and `yin.repl.connect` (`src/cljc/yin/repl/connect.cljc:302-304`) pass a spec
with no codec. So every yin.repl network peer speaks Transit by accident of
the default, and any host without a Transit implementation (none of the three
lanes today; a future host lane that lacks `cognitect.transit` or a port of it)
inherits a default that cannot exist there.

Non-problem: the wire. Both profiles already run the same state machine,
envelope `{:ws/frame :ws/value :ws/value v}`, lifecycle and close codes
(`dao.stream.ws.md`, The Handshake; Elements and Serialization). This design
leaves the **wire contract unchanged**: the same subprotocol names, envelope,
state machine and close codes, so an old peer and a new peer interoperate in
both directions. For the CBOR profile that is additionally a byte-level
claim, since its wire is canonical; for Transit it is a semantic one, since
Transit text is not canonical and is not byte-compared. §7.3 says how each
is proved rather than asserted, and what is not proved.

## 2. Doctrine applied

- **`dao.stream` is the one boundary; implementations do not add contracts**
  (`datom.world.md:70`; `datom.world.abi.md` §1, §4). A codec is not a
  boundary and not a transport: it is how a transport implementation
  represents one portable value as one frame payload. The codec interface is
  therefore implementation-internal data of the stream layer, under
  `dao.stream`, and adds no operation, outcome or handle surface. A client
  never calls a codec; it calls `append!`, `attach!`, `cursor`, `next`.
- **No second ABI** (`datom.world.abi.md` §1: the wasm import surface's
  "canonical Jing CBOR representation does not define the abstraction
  itself"; `dao.stream.wasm.md` §3: requests and outcomes are exactly Jing's
  canonical CBOR, no alternate framing). The codec profile of this document
  is a transport wire codec for socket frames. It never reaches the wasm
  **import surface**, whose one representation is `dao.jing.cbor`'s canonical
  bytes. What happens inside an implementation is not constrained by either:
  `dao.stream.wasm.md:22-27` (V) permits a co-resident ring-buffer
  implementation to serve stream operations with zero host crossings and no
  serialization at all, and such in-memory representations stay
  implementation-local. `dao.stream.cbor` (the transport codec, Boring on
  JVM/CLJS) and `dao.jing.cbor` (the canonical content codec) stay two
  namespaces with two jobs; nothing here merges them or lets a profile select
  the canonical content encoding.
- **The portable value domain is the contract's, not a codec's.**
  `dao.stream.ws.md` (Elements and Serialization) states the v2 portable
  domain and then says the Transit profile "is exactly that domain" and the
  CBOR profile "is that domain widened". V: neither sentence matches the two
  predicates exactly (§4.4). This document therefore defines the **common
  swappable domain** once, in the contract, and each codec's own admission
  predicate is left as it is; the contract states how each relates to the
  common domain.
- **Custody** (`datom.world.abi.md` §3): what crosses is admissible plain
  data; a codec grants no additional portability. A codec that can carry host
  bytes carries them as bytes, never as a host object.

## 3. The interface

### 3.1 One contract namespace, two codec namespaces, zero defaults (P)

+---------------------------+-------------------------------------------------------------------------------------------------------------------+
| Namespace                 | Owns                                                                                                              |
+===========================+===================================================================================================================+
| `dao.stream.codec`        | The codec **contract**: the profile gate `profile?` and the key accessors (§3.2), the numeric domain              |
| (new, `src/cljc/dao/`     | (`max-safe-integer`, `min-safe-integer`, `safe-number?`, moved from `transit.cljc:15-43`), the common swappable   |
| `stream/codec.cljc`)      | domain predicate `portable-value?` and its `validate-portable` (§4.4, new, not moved), and the host-bytes         |
|                           | predicate `host-bytes?` (byte[] / Uint8Array / Uint8List; today `dao.stream.cbor/byte-payload?`,                  |
|                           | `cbor.cljc:80-86`, delegating to the host impl). Requires only `dao.stream`. Requires no codec.                   |
+---------------------------+-------------------------------------------------------------------------------------------------------------------+
| `dao.stream.transit`      | The Transit-JSON codec: `encode`, `decode`, `encode-descriptor`, `decode-descriptor`, `profile`, and its own      |
|                           | admission predicate `portable-value?` with `validate-portable` and `valid-descriptor?` (unchanged, `transit.cljc` |
|                           | `:46-93`). Requires `dao.stream.codec` (for `safe-number?`) and its host Transit implementation                   |
|                           | (`cognitect.transit` on clj/cljs, the 571-line `dao.stream.transit.cljd` port).                                   |
+---------------------------+-------------------------------------------------------------------------------------------------------------------+
| `dao.stream.cbor`         | The CBOR codec: as today, with its own admission predicate unchanged, requiring `dao.stream.codec` instead of     |
|                           | `dao.stream.transit`.                                                                                             |
+---------------------------+-------------------------------------------------------------------------------------------------------------------+
| `dao.stream.ws`           | Consumer. Requires `dao.stream.codec` for the gate and accessors. Requires neither codec. Has no default (after   |
|                           | the contract slice, §7.1).                                                                                        |
+---------------------------+-------------------------------------------------------------------------------------------------------------------+
| A future `dao.stream.pb`, | A codec: requires `dao.stream.codec` and its own library. Nothing in the tree requires it until a composition     |
| `dao.stream.arrow`        | selects it.                                                                                                       |
+---------------------------+-------------------------------------------------------------------------------------------------------------------+

The dependency graph after migration: `codec <- transit`, `codec <- cbor`,
`codec <- ws`, `codec <- data`, `codec <- jing.stream`, and
`{transit, cbor} <- (compositions and tests that select them)`. `grep
dao.stream.transit src/` hits only the Transit namespace and its cljd port;
`grep dao.stream.cbor src/` hits only the CBOR namespace, its impls and
compositions that select it.

Nothing moves out of `dao.stream.transit` except the three numeric
definitions, which no test references through Transit (V: `grep
transit/safe-number?` over `src/` and `test/` hits only `cbor.cljc:64`). Its
`portable-value?`, `validate-portable` and `valid-descriptor?` stay where they
are and keep their meaning; the earlier draft's "forwarding vars" are not
needed and are withdrawn.

### 3.2 The profile (P)

A codec profile is a plain map, constructed by the codec namespace (possibly
from parameters, §5) and passed by the composition to the transport. It is the
existing five-key map with two changes: the keys move from the WebSocket
transport's namespace to the codec contract's, because the profile is now the
contract for every transport that frames values, not a WebSocket detail; and
one capability is added because a consumer already needs it (§3.3).

+--------------------------------------+--------------------------------------------------------------------------------------------------------------+
| Key                                  | Meaning                                                                                                      |
+======================================+==============================================================================================================+
| `:dao.stream.codec/name`             | The codec's wire identity: a string that is a valid RFC 7230 token (no spaces, `;`, `=`, `/`), used verbatim |
|                                      | as the WebSocket subprotocol. Today `"dao.stream.transit-json"` and `"dao.stream.cbor"` (unchanged on the    |
|                                      | wire).                                                                                                       |
+--------------------------------------+--------------------------------------------------------------------------------------------------------------+
| `:dao.stream.codec/frame-kind`       | `:text` or `:binary`: which typed host frame carries one encoded value. Transit `:text`, CBOR `:binary`.     |
+--------------------------------------+--------------------------------------------------------------------------------------------------------------+
| `:dao.stream.codec/portable-value?`  | The codec's own admission predicate, unchanged from today's `:ws/portable-value?`. Law: it accepts every     |
|                                      | value of the common domain (§4.4). It may accept more, and the two existing ones do, differently.            |
+--------------------------------------+--------------------------------------------------------------------------------------------------------------+
| `:dao.stream.codec/encode`           | One value in the codec's domain to one frame payload (a string for `:text`, host bytes for `:binary`).       |
|                                      | Throws on a value outside the domain; the transport maps the throw to `:dao.stream/invalid-value`            |
|                                      | (`ws.cljc:284-290`, V).                                                                                      |
+--------------------------------------+--------------------------------------------------------------------------------------------------------------+
| `:dao.stream.codec/decode`           | One frame payload to one value; throws on malformed input or an out-of-domain value; the transport maps the  |
|                                      | throw to the protocol failure (`ws.cljc:411-415,439-440`, V).                                                |
+--------------------------------------+--------------------------------------------------------------------------------------------------------------+
| `:dao.stream.codec/bytes?`           | New. True when host bytes are values of the codec's domain and round-trip as bytes (CBOR: major type 2).     |
|                                      | Transit false, CBOR true. The one capability a present consumer needs (§3.3). Added in slice C0 (§7.1).      |
+--------------------------------------+--------------------------------------------------------------------------------------------------------------+

`profile?` checks all six: the name a token string, the frame kind one of
the two keywords, the three functions present, and `bytes?` an explicit
boolean (`true` or `false`, never absent or nil, so a profile cannot acquire
the capability by omission). It checks nothing about the name's **value**:
any well-formed profile under an unfamiliar name is a valid profile, and
every consumer dispatches on the capabilities and functions it carries,
never on the name (§4.1). Encode and decode stay **stateless per frame**:
no per-attachment codec state, no open/close hooks. §5 shows this holds for
the two stress tests under stated restrictions, without an extension.

**Accessors, and the two key sets during migration.** `dao.stream.codec`
exports one accessor per entry (`codec-name`, `frame-kind`, `portable-value-fn`,
`encode-fn`, `decode-fn`, `bytes?`). Consumers read a profile only through the
accessors, never by key. During the expand and migrate slices (§7.1) each
profile carries **both** key sets, the accessors look up the new key and fall
back to the old, and `profile?` accepts a map complete under either set:
complete means all six `:dao.stream.codec/*` entries with `bytes?` an
explicit boolean, or all five `:ws/*` entries (the legacy set has no
`bytes?`; the `bytes?` accessor answers `false` for a legacy-only map, so a
legacy profile is never granted a capability it did not declare). The
contract slice deletes the old keys, the fallback and the dual gate in one
edit, and at that point C0's dual-key gate tests are rewritten, not deleted
(§7.1 C4). This is what lets every intermediate slice leave all three lanes
green (review finding A2); the review's second pass (A2-r2) is right that
this makes the intermediate slices **feasible** under the stated acceptance,
not guaranteed green until each slice report records them so.

The socket **seam** key `:ws/subprotocol` (the subprotocol a host adapter
reports on the seam map, `ws.cljc:636-645`, `jvm.clj:359`, `node.cljs:192`,
`dart.cljd:108`, V) is a transport seam key, not a profile key, and is not
renamed.

Why move the keys. Keeping `:ws/*` would name the codec contract after one
transport and keep `dao.stream.codec` dependent on `dao.stream.ws`'s vocabulary;
a datagram or file transport that one day frames values with a profile would
then carry `:ws/` keys. With accessors the rename costs one contract-slice
edit under unchanged test counts, and the repo has no compatibility
constraint. The alternative (keep `:ws/` keys, move only the gate) is listed
in §9 as the smaller option the owner may prefer; it leaves the misnomer.

### 3.3 Why exactly one capability, and why this one

`dao.jing.stream` carries Jing's canonical bytes across a stream as the bytes
themselves on CBOR and as a vector of octets on Transit (V:
`stream.cljc:57-67,70-96`). That is codec knowledge expressed as a name test.
Declaring the fact on the profile (`bytes?`) lets the adapter decide by
capability: bytes when the codec carries them, octets otherwise. A third codec
needs no edit to `dao.jing.stream`. The capability is justified by a consumer
that exists today, not by protobuf or Arrow.

No other capability is added. Candidates rejected as speculative: a declared
domain label (`:base`, `:widened`), a version field, a per-codec maximum frame
size, a compression flag, a schema slot. None has a consumer in the tree; §5
shows the two stress tests do not need them either.

## 4. Identity, negotiation, resolution, domain

### 4.1 Identity

A codec is identified by `:dao.stream.codec/name`, a token string. Two
profiles with the same name must be wire-compatible; a codec whose wire form
depends on parameters (a schema, §5) puts the parameter's identity into the
name. Names are contract constants listed in `dao.stream.ws.md`; `dao.stream.ws`
and `dao.jing.stream` never compare names, they read capabilities and call
functions.

### 4.2 Negotiation (unchanged on the wire, V)

The WebSocket subprotocol mechanism is the negotiation: a client offers exactly
its selected profile's name; an endpoint serves exactly the profiles it was
composed with, in endpoint order; a mismatch is a refused upgrade, never a
downgrade (`jvm.clj:54-63,413-436`; `node.cljs:274-282`; `dart.cljd:209-238`;
`ws.cljc:636-652`). One change in process: a socket seam that names no
subprotocol is refused by `accept-connection!` (`:ws/unsupported-subprotocol`,
the existing branch at `ws.cljc:650-652`) instead of taking the Transit
default. Every host listener already refuses a no-offer upgrade before
`accept-connection!` runs (V, the three cites above), so this changes direct
compositions and tests, not peers. `dao.stream.ws.md`'s sentence "an absent
name is the `dao.stream.transit-json` default for sockets that negotiated
nothing" is amended to "is refused".

### 4.3 Resolution: data, not registry, not protocol

The composition passes the profile map itself: `:codec` to `make-attacher`,
`:codecs` (a vector, endpoint order) to `make-endpoint` and to the host
`:bind!`, `:codecs`/`:codec` in the `remote-channel` spec. Both are mandatory;
absence is a composition error at composition time (the existing assembly-defect
throws, `ws.cljc:45-47,561-564`, V; a new `::no-codec` refusal in
`remote-channel`).

Rejected: a **registry** (a global the transport reads is a second place to be
wrong, needs population order, and hides which codec a composition depends on;
it is exactly the ambient default this design removes). Rejected: a
**protocol or multimethod dispatched on the name** (dispatch needs the
implementation loaded anyway, so it hides the require instead of removing it,
and it makes the set of codecs a global again). Data is the simplest thing that
makes the dependency visible: the namespace that selects a codec requires it.

### 4.4 The portable value domain

**What the two predicates admit today (V).** The review (A1) is right that
neither existing predicate is a subset of the other, so neither can serve as
"the base domain":

+-------------------------------------------+---------------------------------------------+----------------------------------------------+
| Value                                     | `transit/portable-value?` (`:55-76`)        | `cbor/portable-value?` (`:25-70`)            |
+===========================================+=============================================+==============================================+
| `(keyword "a/b" "c")`, `(symbol "x" "")`, | admitted: every keyword and symbol passes   | rejected: `portable-identifier?` requires    |
| `(keyword ":lead")`, `(keyword "n" "")`   |                                             | non-empty parts, no leading `:`, no `/`      |
|                                           |                                             | (test `cbor_test.cljc:166-183`)              |
+-------------------------------------------+---------------------------------------------+----------------------------------------------+
| A collection whose metadata holds a host  | admitted: metadata is never inspected; the  | rejected: `portable-meta?` requires metadata |
| object                                    | Transit writer drops it on the wire (R)     | to be itself portable                        |
+-------------------------------------------+---------------------------------------------+----------------------------------------------+
| A collection with portable metadata, a    | admitted; metadata dropped on the wire (R); | admitted; metadata and list-hood preserved   |
| list                                      | lists admitted                              | on the wire (tag 27 frames, `ws.md:708-710`) |
+-------------------------------------------+---------------------------------------------+----------------------------------------------+
| A symbol whose metadata holds a host      | admitted: metadata is never inspected;      | admitted by the **predicate** (`:63` tests   |
| object, `(with-meta 'x {:k <host>})`,     | dropped on the wire (R)                     | the identifier only, never a symbol's        |
| direct or nested in a collection          |                                             | metadata), but both encoders encode symbol   |
|                                           |                                             | metadata recursively (`boring.cljc:99-102,   |
|                                           |                                             | 160-162`; `cljd.cljd:158-166`) and the Dart  |
|                                           |                                             | encoder throws on the host object            |
|                                           |                                             | (`cljd.cljd:186-187`). Predicate and encoder |
|                                           |                                             | disagree: a recorded gap, see below          |
+-------------------------------------------+---------------------------------------------+----------------------------------------------+
| A record                                  | admitted on clj/cljs (`map?` is true, no    | rejected (`(not (record? x))`, `:65`)        |
|                                           | record check); the cljd arm rejects its own |                                              |
|                                           | decoder records only (`:61-70`)             |                                              |
+-------------------------------------------+---------------------------------------------+----------------------------------------------+
| Host bytes                                | rejected                                    | admitted (byte string, major type 2)         |
+-------------------------------------------+---------------------------------------------+----------------------------------------------+

So `dao.stream.ws.md:701-704`'s "exactly that domain" and "widened" are both
approximate, and the test `transit-domain-stays-a-subset-of-the-cbor-domain`
(`cbor_test.cljc:203-210`, `cbor_test.cljd:186-190`) samples only values on
which the two agree; its name overstates what it checks.

**The common swappable domain D (P).** Defined once, in
`dao.stream.codec/portable-value?`, as a **new, third** predicate:

- nil, booleans, strings;
- safe numbers (`safe-number?`, the rule of `transit.cljc:34-43`, moved
  unchanged);
- portable identifiers: a keyword or symbol whose name is non-empty, does not
  start with `:` and contains no `/`, and whose namespace is nil or satisfies
  the same rule (CBOR's `portable-identifier?`, `cbor.cljc:25-36`); and, for
  a **symbol**, whose metadata, if any, is itself in D, checked recursively
  by the same metadata rule as collections (review A1-r2: without this clause
  `(with-meta 'x {:k <host-object>})` would be in D while the CBOR encoders
  encode symbol metadata and the Dart one throws on it);
- maps that are not records, vectors, lists and sets whose keys, values and
  elements are in D, and whose metadata, if any, is itself in D.

The metadata rule, shared by the symbol and collection clauses: on cljd the
compiler's call-site constructor metadata is stripped first, as
`cbor.cljc:42-49` does today for collections (that reader arm moves to the
contract and is applied to symbols too); what remains must be nil or a value
in D. Metadata on keywords does not exist in Clojure and is not a case.

D is a subset of both existing predicates. Transit's predicate admits D plus
unrestricted identifiers, records (on clj/cljs) and unrestricted metadata.
CBOR's predicate admits D plus host bytes at any depth, **and**, as a gap
between its predicate and its encoders (table above), symbols carrying any
metadata at all: `cbor.cljc:63` never inspects a symbol's metadata, while
`boring.cljc:99-102` and `cljd.cljd:158-166` encode it recursively, so a
symbol whose metadata holds a host object is admitted by the predicate and
then rejected by the Dart encoder (`cljd.cljd:186-187`; on Boring the host
object is handed to the library's own writer, whose outcome this document
does not assert). So "CBOR's domain is exactly D plus bytes" holds for the
encoders' behaviour on cljd and is **not** exactly true of the predicate;
the claim is stated here only with that qualification. The gap is recorded,
not fixed: this design changes neither legacy predicate (CD3), and whether
`cbor/portable-value?` should check symbol metadata is a one-line follow-up
for the CBOR namespace's owner, outside this track. Membership in both
legacy predicates therefore does not by itself establish the round-trip law;
only membership in D does, and D now excludes the counterexample. D is
**not** installed as either profile's predicate. Each profile's `:dao.stream.codec/portable-value?` remains its own
admission predicate, unchanged, so no existing value a Transit peer accepts
is refused after this design and no value a CBOR peer refuses is accepted
(review A1: no silent narrowing or widening under an existing name). D is used
by the law test below, by `dao.data` (which applies it to numbers only, where
D and Transit's predicate coincide; V `data.cljc:52-60`), and by nothing on the
send or receive path.

**Equality and projection rules.** Structural equality in the law is Clojure
`=` plus the contract's numeric rule (`dao.stream.ws.md:711-714`: an integral
finite double equals the equal safe integer). `=` is blind to metadata and to
the list/vector distinction, so the law promises nothing about either. What a
codec preserves **beyond** `=` is a codec property, declared in its own
namespace's docstring, not in the contract: CBOR preserves metadata and
list-hood; Transit drops metadata on the wire (R), and whether list-hood
survives its wire is a host-implementation fact this document does not assert.
A composition that needs metadata or list-hood to survive has chosen its
codec (§6.1 point 3).

**Law (P, enforced by a shared conformance test, slice C0):** for every value
`v` in D, `((portable-value-fn p) v)` is true for every profile `p`, and
`(decode-fn p)` of `(encode-fn p)` of `v` is equal to `v` under the rule
above, **except on the baseline exception paths of §7.3**, where the law
test asserts the frozen per-host outcome instead of the round trip (ruling
R2, Architect, 2026-10-20; see "Baseline exception paths" below). The law
test runs over both profiles on all three lanes with a corpus that includes,
as **adversarial identifier and metadata cases** (review A1):

- rejected by D, so outside the law and asserted rejected by D: the five
  identifiers of `cbor_test.cljc:171-175`, a vector whose metadata holds a
  host object, a record instance, and the **symbol-metadata cases** of
  review A1-r2: direct, `(with-meta 'x {:k <host-object>})`; nested in a
  vector, `[(with-meta 'x {:k <host-object>})]`; nested as a map value and
  as a map key; nested inside another symbol's metadata,
  `(with-meta 'y {:m (with-meta 'x {:k <host-object>})})`; and the same five
  shapes with the host object replaced by a record instance. For these the
  test asserts D's rejection only; it does **not** pin CBOR's answer, because
  its predicate and encoders disagree there (§4.4 gap), and pinning either
  would freeze a defect;
- inside D and asserted round-tripping on both profiles: `(with-meta [1]
  {:line 1})`, a symbol with portable metadata both direct and nested in a
  vector and in a map, and a symbol whose metadata holds a symbol with
  portable metadata (equal after the trip on both; metadata preservation
  additionally asserted for CBOR only, in CBOR's own test), a map whose keys
  carry metadata, nested collections mixing all four kinds, the numeric
  boundaries `9007199254740991`, `-9007199254740991`, `1.0` against `1`;
- inside D, asserted round-tripping on CBOR, and on Transit asserted to
  produce the **frozen per-host outcome** of §7.3's `exceptions.edn`: `'(1 2)`
  and a nested list such as `[1 '(2 #{3})]`. See "Baseline exception paths"
  below for why these are not round-tripped on Transit;
- asserted admitted by exactly one profile, to pin the documented asymmetry:
  `(keyword "a/b" "c")` Transit-only, host bytes CBOR-only, a vector with a
  host object in its metadata Transit-only.

**Baseline exception paths (ruling R2, Architect, 2026-10-20).** The C0
review (Codex, R2) found that the universal law as first stated contradicts
the F0 freeze: at `master@212c45dd` Transit's JVM decoder turns list text
into a non-list seq that its own post-decode gate (`ensure-portable!`,
`transit.cljc:96-100`, via `list?` at `:74`) rejects, so
`(decode (encode '(1 2)))` throws on the JVM while Node and Dart decode it
to a vector (equal to the list under `=`). F0 pinned exactly that per-host
behaviour in `test/fixtures/codec/transit/exceptions.edn` (entries `list`
and `nested-list`). The ruling:

1. **D is not narrowed.** Lists stay in D. The defect is in one host's
   Transit decoder, not in the domain: CBOR round-trips lists on all three
   lanes and Transit round-trips them on two. Removing lists from D would
   encode a JVM Transit bug into the shared contract and silently shrink
   what `dao.data` and future codecs may rely on.
2. **Transit is not fixed in C0.** C0's acceptance is "no behaviour change
   anywhere" (§7.1), and §7.3 says fixing an exception is a separate, named
   change that moves the entry from `exceptions.edn` to `envelopes.edn`
   and is reviewed as a behaviour change. A C0 that patched the decoder
   would fail the frozen fixture on purpose and bury a behaviour change in
   an expand slice.
3. **The law is stated with the exception.** "For every value in D over
   both profiles" is read as "for every value in D, on every path that is
   not a baseline exception path recorded in `exceptions.edn` at F0". The
   exception paths are, today, exactly the Transit list entries; the CBOR
   `1.0` entry is a decode-side divergence on a payload no encoder in this
   tree emits and does not touch the law's encode-then-decode shape.
4. **What the law test must do for the excepted values.** For each value
   on an exception path the test asserts, on CBOR, the full round trip
   under the equality rule, and, on Transit, the frozen per-host outcome:
   on the JVM the decode throws; on Node and Dart the decode is equal to
   the value. Asserting the outcome, rather than merely dropping the values
   from the corpus, is what keeps the law test from quietly passing after a
   future decoder change in either direction; the fixture test of §7.3
   pins the same fact from frozen text, and the two must agree. A test that
   round-trips lists on CBOR only and leaves Transit to the fixture test
   satisfies this ruling **provided** it names the exclusion and cites
   `exceptions.edn`, as the engineer's C0 draft does; asserting the Transit
   outcome in the law test as well is preferred and is accepted either way.
5. **Exit.** When the Transit owner fixes the JVM list decode (separate
   change, §7.3), the entries move to `envelopes.edn`, this paragraph's
   list of exception paths becomes empty, and the law test's Transit
   branch for lists becomes the plain round trip. Until then the law is
   universal over D on CBOR and universal over D minus the recorded
   exception paths on Transit, and the design claims nothing stronger.

The transport judges values with the attachment's codec predicate, as today
(`ws.cljc:283,414`).

## 5. Open to future encodings: protocol buffers and Apache Arrow as stress tests

Neither is designed here. The test is whether the six-key stateless profile
covers them or what minimal extension it would need. Both columns are
plausibility arguments, not demonstrated conformance (review A4); a real
codec would have to pass the §4.4 law test to be called a peer.

+-------------------------+-------------------------------------------------------------+-----------------------------------------------------------------------+
| Property                | Protocol buffers                                            | Apache Arrow                                                          |
+=========================+=============================================================+=======================================================================+
| Schema                  | Required on both ends; a message is meaningless without     | Required; an IPC stream begins with a schema message, batches follow; |
|                         | its descriptor.                                             | dictionary-encoded columns additionally need dictionary batches, and  |
|                         |                                                             | the IPC stream format lets later delta dictionary messages extend     |
|                         |                                                             | them in-band.                                                         |
+-------------------------+-------------------------------------------------------------+-----------------------------------------------------------------------+
| How the profile covers  | The codec namespace constructs a profile **from** a schema: | Stateless per frame **only under one of three restrictions**: (i)     |
| it, and under what      | `(pb/profile {:schema s})` closes `encode`/`decode` over    | dictionary-free batches; (ii) dictionaries fixed at composition       |
| restriction             | it. The schema's identity goes into the name                | time, immutable, carried into the profile beside the schema and into  |
|                         | (`"dao.stream.pb.<blake3-hex>"`, a valid token), so         | the name's hash, never sent in-band; (iii) each frame a self-         |
|                         | negotiation by subprotocol already refuses a peer with a    | contained IPC payload (schema, dictionaries, one batch). In-band      |
|                         | different schema. Out-of-band schema: composition data.     | delta dictionaries are per-attachment state and are **outside** the   |
|                         |                                                             | stateless profile; a codec wanting them needs the `open` hook of §8.  |
+-------------------------+-------------------------------------------------------------+-----------------------------------------------------------------------+
| Frame kind              | `:binary`.                                                  | `:binary`; one WebSocket message carries one self-contained codec     |
|                         |                                                             | payload, which under restriction (iii) contains **several** IPC       |
|                         |                                                             | messages (schema, any dictionary batches, one record batch), each a   |
|                         |                                                             | separate encapsulated message in Arrow's own stream format. One       |
|                         |                                                             | WebSocket message is still one stream value (review A4-r2).           |
+-------------------------+-------------------------------------------------------------+-----------------------------------------------------------------------+
| Envelope                | `encode` receives the frame map `{:ws/frame :ws/value       | Same in principle; representing the envelope and D in columnar form   |
|                         | :ws/value v}` and represents it however it likes (a         | is an unsolved obligation of the future codec, not shown here.        |
|                         | `oneof`); the transport never sees the representation.      |                                                                       |
+-------------------------+-------------------------------------------------------------+-----------------------------------------------------------------------+
| Domain and the §4.4 law | A protobuf codec's natural domain is its message type, not  | Same, more sharply: the natural domain is tabular (record batches).   |
|                         | D. To satisfy the law it must also carry D (a generic       | Carrying D and the envelope is the codec's cost; until a codec does   |
|                         | `Value` message, as protobuf's own `google.protobuf.Struct` | it and passes the law test, "peer" is not claimed for Arrow.          |
|                         | does). That is the codec's cost, not the interface's.       |                                                                       |
+-------------------------+-------------------------------------------------------------+-----------------------------------------------------------------------+
| `bytes?`                | True (`bytes` field).                                       | True (binary column) in principle; the codec decides.                 |
+-------------------------+-------------------------------------------------------------+-----------------------------------------------------------------------+
| Batching                | None needed; one value per frame.                           | A record batch **is** one value. Batching many stream values into     |
|                         |                                                             | one frame would change append semantics (one `append!`, one frame)    |
|                         |                                                             | and is a transport concern the contract does not permit a codec to    |
|                         |                                                             | decide. Not an extension point.                                       |
+-------------------------+-------------------------------------------------------------+-----------------------------------------------------------------------+

Conclusion, qualified. The six-key stateless profile plausibly covers
protobuf, and covers Arrow for dictionary-free batches, precomposed immutable
dictionaries, or self-contained per-frame IPC payloads. The two mechanisms
that make it so already exist: **parameterized profile construction** (a
codec namespace is a function from parameters to a profile; CBOR and Transit
take no parameters) and **schema identity in the name** (negotiation by name
already refuses a mismatch). Neither is new machinery. What would reopen this
section: a codec that cannot satisfy the §4.4 law (then "swappable" is false
for it by construction, and the honest answer is a declared
`:dao.stream.codec/domain` label so a composition can refuse the swap
statically; added then, not now), or a codec that genuinely needs
per-attachment state, of which Arrow's in-band delta dictionaries are now the
concrete example (then an `open` hook; no present codec needs it).

## 6. The callsites and the stability promise

### 6.1 What "swap without breaking client callsites" promises, precisely

1. **Client callsites are stream operations.** `append!`, `attach!`, `cursor`,
   `next`, `close!`, `descriptor` on handles, and the `remote-channel` and
   `yin.repl` serve/connect entry points. None of them names a codec; none
   changes when the codec changes. The codec appears exactly once per
   composition, as a profile value in that composition's spec.
2. **For values in D, every codec behaves identically**: same outcomes
   (`ok`, `full`, `invalid-value`, `closed`, `transport-error`), same
   deposited events, same structural equality after a round trip. This is
   the §4.4 law and the shared conformance test enforces it.
3. **For values outside D**, behaviour is codec-dependent by definition, and
   in both directions: host bytes are `ok` on CBOR and `invalid-value` on
   Transit; an identifier with `/` in its name or a collection carrying a
   host object in its metadata is `ok` on Transit and `invalid-value` on
   CBOR; a symbol carrying a host object in its metadata is `ok` on Transit
   and, on CBOR, passes the predicate but fails in the encoder (`invalid-
   value` on Dart, the §4.4 recorded gap); a value with portable metadata is
   `ok` on both but only CBOR delivers the metadata to the receiver (§4.4). A composition that relies
   on any of these has chosen its codec; `dao.jing.stream` is the one shared
   consumer that does, and it adapts by capability (§3.3) so the swap still
   works, at the cost of the octet-vector form on a codec without `bytes?`.
4. **Both ends swap together.** A codec is negotiated; swapping one end alone
   is a refused handshake, never a downgrade. "Swap" means the composition
   value changes on the peers that must interoperate.
5. **Nothing else is promised**: not wire size, not speed (never measured),
   not that every future codec can carry every value (§5).

### 6.2 Callsite table

+--------------------------------------+-------------------------------------------------------+--------------------------------------------------------------+
| Callsite                             | Today (V)                                             | After (P)                                                    |
+======================================+=======================================================+==============================================================+
| `dao.stream.ws`                      | Requires Transit; defaults to it in five places.      | Requires `dao.stream.codec`; `:codec`/`:codecs` mandatory;   |
|                                      |                                                       | reads profiles through the accessors; no-offer seam refused. |
+--------------------------------------+-------------------------------------------------------+--------------------------------------------------------------+
| `dao.stream.remote-channel`          | Composes endpoint, bind and attacher with no codec.   | `:codecs` (serve) and `:codec` (dial) in the spec, threaded  |
|                                      |                                                       | to all three; `{:status :refused :reason ::no-codec}` when   |
|                                      |                                                       | absent. Its own code names no codec.                         |
+--------------------------------------+-------------------------------------------------------+--------------------------------------------------------------+
| `yin.repl.serve`, `yin.repl.connect` | Pass a spec with no codec.                            | Pass the profile the yin.repl composition selected (§6.4).   |
|                                      |                                                       | The only place in production code where a codec namespace    |
|                                      |                                                       | is required.                                                 |
+--------------------------------------+-------------------------------------------------------+--------------------------------------------------------------+
| `dao.data`                           | `transit/portable-value?`, applied to numbers only    | `codec/portable-value?` (D). Identical answers for every     |
|                                      | (`data.cljc:52-60`).                                  | number, so no behaviour change. `dao.data.md`'s API block    |
|                                      |                                                       | updates its require.                                         |
+--------------------------------------+-------------------------------------------------------+--------------------------------------------------------------+
| `dao.jing.stream`                    | Name tests against both profiles; throws on a third.  | `(profile? p)` gate, then `bytes?`: bytes or octets. Tests   |
|                                      |                                                       | for nil, empty, unknown and both real profiles.              |
+--------------------------------------+-------------------------------------------------------+--------------------------------------------------------------+
| `ws/jvm.clj`, `ws/node.cljs`,        | Require Transit; `default-codecs`; `ws/subprotocol`   | Require neither codec; `:codecs` mandatory on `listen!`;     |
| `ws/dart.cljd`, `ws/browser.cljs`    | fallback when the adapter has no `:ws/codec`; read    | fallbacks deleted (the adapter always carries its codec      |
|                                      | the profile name by key (`jvm.clj:61,333,377,430` and | once `make-attacher` requires one); the name read through    |
|                                      | the node/dart/browser twins).                         | the `codec-name` accessor.                                   |
+--------------------------------------+-------------------------------------------------------+--------------------------------------------------------------+
| Tests (§7.2)                         | 22 namespaces require Transit; ~14 rely on a default. | Each names the profile it tests; codec-neutral suites run    |
|                                      |                                                       | over both profiles through one parameterized fixture.        |
+--------------------------------------+-------------------------------------------------------+--------------------------------------------------------------+

### 6.3 Lanes

- **clj**: transit-clj plus Boring; both codecs stay. Behaviour change is
  composition-time only (a codec-less composition fails at composition
  instead of speaking Transit).
- **cljs**: transit-cljs plus Boring; browser and Node adapters lose a dead
  fallback. Same.
- **cljd**: the 571-line Transit port (`src/cljd/dao/stream/transit/cljd.cljd`,
  V) and the 306-line CBOR impl (`src/cljd/dao/stream/cbor/cljd.cljd`, V)
  both stay: peers means both are available. Whether the Transit port is
  worth keeping once yin.repl defaults to CBOR is an **owner question**, not
  decided here; the design works with or without it. The cljd decoder-record
  rejection stays inside `dao.stream.transit` (§4.4).
- **A host without a Transit implementation** (generic; none exists today):
  after the contract slice its compositions pass a profile it does implement,
  and because nothing shared requires `dao.stream.transit`, that namespace
  and its host library are simply absent from the host's closure. The host
  needs a reader arm for the host number predicate
  (`dao.stream.codec/safe-number?` after C0, today `transit.cljc:19-31`, V),
  because every codec uses it, and an implementation of whichever codec it
  selects; nothing else in this design is host-specific. Any such host lane
  is its own track, depending on C0-C4 having landed and on nothing else here.

### 6.4 Which codec yin.repl selects by default: owner decision

After the migration no shared default exists and `yin.repl.serve`/`connect`
must pass a profile. Until the owner records a choice, they pass
`dao.stream.transit/profile`, which keeps today's wire behaviour exactly.

Architect's recommendation, marked as such: **`dao.stream.cbor/profile`**.
Reasons: its implementation is the smaller one to carry to a new host (a CBOR
impl is 212 lines on Boring and 306 on Dart against the 571-line Transit port,
V, line counts in Appendix A); it carries Jing's canonical bytes as bytes,
which is what `dao.jing.stream` and the signed-head/DHT paths move; it
preserves metadata and list-hood where Transit drops them (§4.4); there are
no deployed peers or stores to stay compatible with. Against: Transit-JSON
frames are readable in a browser devtools pane; Transit admits identifiers
and metadata CBOR refuses, so a composition that sends those today would
start seeing `invalid-value`; wire size and speed are unmeasured either way.
The decision changes one composition value and the cross-host peer suites'
configuration; it is recorded in §10 when made.

## 7. Migration

### 7.1 Slices: expand, migrate, contract

Review finding A2 showed the earlier sequence could not land green slice by
slice: removing defaults and renaming keys in one step while `remote-channel`,
the host adapters and `dao.jing.stream` still read the old keys would have
broken production compositions and turned Jing's name test into a nil-to-nil
comparison that selects the CBOR branch for Transit. The sequence is
restructured as expand (add, nothing removed), migrate (callers switch),
contract (remove). Each slice lands on master alone, under the three-lane
quick tier, with recorded test counts before and after and with the frozen
wire fixtures of §7.3 passing. "Counts unchanged" means the same number of
tests and assertions per lane, with any added test listed by name.

+-------+----------+-----------------------------------------------------------------------------+---------------------------------------------------------------------+
| Slice | Phase    | Change                                                                      | Falsifiable acceptance                                              |
+=======+==========+=============================================================================+=====================================================================+
| F0    | freeze   | Record the frozen wire fixtures (§7.3) from the tree **before** any other   | Fixture files committed; the fixture test passes on all three       |
|       |          | slice, at a tagged master commit. No `src/` change.                         | lanes against unchanged code; the tagged commit is named in the     |
|       |          |                                                                             | slice report as the "old peer" for §7.3's bidirectional check.      |
+-------+----------+-----------------------------------------------------------------------------+---------------------------------------------------------------------+
| C0    | expand   | Create `dao.stream.codec`: accessors with dual lookup, `profile?` accepting | Three lanes green; counts unchanged plus the named new tests in     |
|       |          | either complete key set, `safe-number?` and bounds moved from Transit, the  | `codec_test.cljc`: the §4.4 law corpus over both profiles **minus   |
|       |          | D predicate with `validate-portable`, `host-bytes?` (moved from             | the baseline exception paths of §7.3** (today: Transit lists, which |
|       |          | `cbor/byte-payload?`, which stays as a forwarding def). Both profiles gain  | round-trip on CBOR and are asserted to keep their frozen per-host   |
|       |          | the six `:dao.stream.codec/*` keys **beside** the five `:ws/*` keys.        | Transit outcome; §4.4 ruling R2), the adversarial identifier and    |
|       |          | `dao.stream.cbor`, `dao.stream.transit` and `dao.data` require the          | metadata cases (collection and symbol metadata, direct and nested), |
|       |          | contract; `cbor.cljc` drops its Transit require. No consumer changes.       | the dual-key gate. `transit-domain-stays-a-subset-of-the-cbor-      |
|       |          | Transit's decoder is **not** touched, even where it breaks the law (R2).    | domain` is replaced by the law test (its claim was false in         |
|       |          |                                                                             | general, §4.4). `grep dao.stream.transit src/` no longer hits       |
|       |          |                                                                             | `cbor.cljc` or `data.cljc`. Fixtures pass. No behaviour change      |
|       |          |                                                                             | anywhere.                                                           |
+-------+----------+-----------------------------------------------------------------------------+---------------------------------------------------------------------+
| C1    | expand   | Consumers read profiles only through the accessors, keeping every default:  | Three lanes green; counts unchanged plus: `jing/stream_test`        |
|       |          | `dao.stream.ws` (gate, `codec-index`, `distinct?` check, frame kind,        | cases for nil, `{}` and a **malformed** profile (a key missing,     |
|       |          | encode/decode calls), the four host adapters (negotiation and the           | `bytes?` absent or non-boolean, a non-token name), all rejected     |
|       |          | subprotocol they put on the socket), `dao.jing.stream` (`profile?` then     | before any lane branch; and, positively, a **complete profile       |
|       |          | `bytes?`; drops both codec requires). `remote-channel` **accepts**          | under an unfamiliar name** (a stub codec the test defines, never    |
|       |          | optional `:codecs`/`:codec` and threads them to endpoint, bind and          | Transit's or CBOR's name) run twice, with `bytes?` `true` and with  |
|       |          | attacher; absent, the `ws` default still applies.                           | `bytes?` `false`, asserting the bytes branch and the octets branch  |
|       |          |                                                                             | respectively, so dispatch is proved to be by capability and not by  |
|       |          |                                                                             | name (A2-r2); then the Transit and CBOR profiles as today. `grep    |
|       |          |                                                                             | ':ws/\(subprotocol\|frame-kind\|portable-value?\|encode\|decode\)'` |
|       |          |                                                                             | over `src/` hits only the two profiles, the seam key sites, and     |
|       |          |                                                                             | `codec.cljc`'s fallback. A `remote-channel` spec naming a codec     |
|       |          |                                                                             | is honoured end to end (one test per host). Fixtures pass.          |
+-------+----------+-----------------------------------------------------------------------------+---------------------------------------------------------------------+
| C2    | migrate  | Every caller supplies its profile explicitly: `yin.repl.serve`/`connect`    | Three lanes green; counts unchanged plus the fixture-driven         |
|       |          | pass `dao.stream.transit/profile`; every test in §7.2 names its profile;    | tests; `grep` for codec-less `make-attacher`/`make-endpoint`/       |
|       |          | one parameterized fixture runs codec-neutral suites over both profiles;     | `listen!`/serve/dial calls over `src/` and `test/` returns none.    |
|       |          | one-arity descriptor gates replaced by two-arity calls at every site.       | The §7.3 bidirectional old/new peer check runs here for both        |
|       |          | Defaults still exist but nothing reaches them.                              | profiles. Fixtures pass.                                            |
+-------+----------+-----------------------------------------------------------------------------+---------------------------------------------------------------------+
| C3    | contract | `dao.stream.ws` drops the Transit require, `checked-codec`'s default, the   | Three lanes green; counts unchanged plus: codec-less                |
|       |          | one-arity gates, the `make-endpoint` default and the no-offer default       | `make-attacher`, `make-endpoint`, `listen!`/`handle-request!`       |
|       |          | (refused). The four adapters drop `default-codecs`, the Transit require     | throw (one test per host); a seam without a name is refused;        |
|       |          | and the `ws/subprotocol` fallback. `remote-channel` refuses `::no-codec`.   | `remote-channel` returns `::no-codec`. `grep dao.stream.transit     |
|       |          |                                                                             | src/` hits only `transit*.clj*` and `yin/repl/{serve,connect}`.     |
|       |          |                                                                             | `dao.stream.ws.md` "absent name" sentence amended; its              |
|       |          |                                                                             | codec-profile text points here. Fixtures pass.                      |
+-------+----------+-----------------------------------------------------------------------------+---------------------------------------------------------------------+
| C4    | contract | Contract dual acceptance to one key set. Runs only after OQ3 is resolved    | Three lanes green; counts unchanged except the dual-key gate        |
|       |          | (§10), and which set survives is OQ3's answer. **If OQ3 moves the keys**    | tests of C0, which are **rewritten** (not deleted) in this slice:   |
|       |          | (CD5): both profiles drop the `:ws/*` keys, accessors become single lookup, | each test that asserted "a map complete under the other key set is  |
|       |          | `profile?` accepts the `:dao.stream.codec/*` set only. **If OQ3 keeps       | accepted" now asserts it is rejected, each test that asserted the   |
|       |          | `:ws/*`**: both profiles drop the `:dao.stream.codec/*` keys, the dual      | fallback lookup now asserts a single lookup, and the count of       |
|       |          | lookup is deleted, the accessors read `:ws/*`, `profile?` accepts the five  | gate tests before and after is equal and recorded. The grep gate    |
|       |          | `:ws/*` keys plus one added `:ws/bytes?` boolean (the capability of §3.3    | is **conditional on OQ3**: under the move, `grep ':ws/\(frame-kind  |
|       |          | must survive either answer).                                                | \|portable-value?\|encode\|decode\|bytes?\)'` over `src/` and       |
|       |          |                                                                             | `test/` is empty and `:ws/subprotocol` survives only on seam maps;  |
|       |          |                                                                             | under keep, `grep ':dao.stream.codec/'` over `src/` and `test/`     |
|       |          |                                                                             | hits only the namespace's own name, and no absence of `:ws/*` is    |
|       |          |                                                                             | asserted. Under both answers: fixtures pass; the §7.3 bidirectional |
|       |          |                                                                             | check runs again. Slow tier once.                                   |
+-------+----------+-----------------------------------------------------------------------------+---------------------------------------------------------------------+
| C5    | owner    | Owner-gated (§6.4): `yin.repl` selects `dao.stream.cbor/profile`;           | Same suites green over CBOR; `grep dao.stream.transit src/` hits    |
|       |          | cross-host peer suites configured for CBOR.                                 | only `transit*.clj*`. Not commissioned by this document.            |
+-------+----------+-----------------------------------------------------------------------------+---------------------------------------------------------------------+

Why each intermediate state is green. After C0 every consumer still reads
`:ws/*` keys that are still present. After C1 every consumer reads through
accessors that find either key set, and every default is still in place, so
a composition that names nothing behaves exactly as today. After C2 nothing
reaches a default, so C3 can delete the defaults without changing any
observable path; C3 is the only slice whose acceptance includes a behaviour
change (codec-less compositions fail), and that change is confined to direct
compositions and tests, which C2 already migrated. C4 deletes keys nobody
reads. The dependency is strictly linear: F0, C0, C1, C2, C3, C4.

F0 through C4 depend on nothing outside this document. Other tracks may depend
on them (any track that needs `dao.stream.ws` free of a codec default); such a
dependency is stated in the other track's document, never here.

### 7.2 Test impact (V, grep of `test/` on 2026-10-20)

Namespaces requiring `dao.stream.transit`: 22 by `grep -l dao.stream.transit
test/` (an earlier brief said 20; the grep count is authoritative):
`dao.stream.ws-test`,
`ws-codec-test` (`.cljc`, `.cljd`), `ws.jvm-test`, `ws.node-test`,
`ws.browser-test`, `ws-project-peer`, `ws-project-cross-jvm-test`,
`ws-project-cross-node-test`, `remote-route-test`, `loopback-net`,
`transit-test`, `cbor-test` (`.cljc`, `.cljd`), `dao.jing.stream-test`,
`yin.vm.linker.cross-host-transfer-test`, `head-board-test`,
`transfer-peer`, `yin.repl.main-test`, `yin.repl.slice-peer`. They keep
requiring the codec they exercise; that is composition.

Namespaces that rely on a default and change in C2: `ws-test`
(`make-attacher` without `:codec` at `:41,68,93,110,133,157,186`; `make-endpoint`
without `:codecs` at `:207,262,521`; seams without a name at
`:224-234,243,272,304,336,427,532-533,583`; one-arity gates at `:567-570`),
`ws-codec-test` (both), `ws.jvm-test`, `ws.node-test`, `ws.dart-test`,
`ws.browser-test`, `ws-project-test`, `ws-project-jvm-test`, `ws-project-peer`,
`ws-project-cross-jvm-test`, `ws-project-cross-node-test`,
`remote-channel-test` (`ws/descriptor?` one-arity at `:174`; serve/dial
without codec), `loopback-net` (no seam name; Transit at `:164`),
`yin.repl.host.jvm-test` (`:87-109`), and the yin.repl network tests through
serve/connect. `transit-test` keeps its predicate cases (`transit_test.cljc:15-57`)
unchanged, since Transit's predicate is unchanged; `codec-test` is new.
`cbor-test` loses `transit-domain-stays-a-subset-of-the-cbor-domain` (both
hosts) to the law test in C0 and keeps the rest; `journal-test`
(`cbor/validate-portable`, `:354`) is unaffected. Exact per-lane counts are
the slice reports' to record.

### 7.3 Wire proof: frozen fixtures and bidirectional peers

Review finding A3: the cross-host peer suites run new code against new code,
so both ends can drift together and "wire unchanged" would be asserted, not
proved. The proof has two parts, both mandatory.

**Frozen fixtures (F0).** Generated once from the pre-change tree at the
tagged F0 commit and committed under `test/` as data files the three lanes
all load (one directory per profile; CBOR payloads as hex, Transit payloads
as text, expected values as EDN). They cover, for each profile:

+---------------------+-------------------------------------------------------------------------------------------------------------------+
| Area                | Fixture content                                                                                                   |
+=====================+===================================================================================================================+
| Negotiation         | The exact subprotocol string each profile offers; one offer that must be refused; the no-offer case.              |
+---------------------+-------------------------------------------------------------------------------------------------------------------+
| Descriptors         | A served descriptor and a dialed descriptor (port 0 and a fixed port) encoded by each profile's descriptor path.  |
+---------------------+-------------------------------------------------------------------------------------------------------------------+
| Envelopes           | `{:ws/frame :ws/value :ws/value v}` for the §4.4 law corpus (every D case) plus each profile's own extra values   |
|                     | (CBOR: host bytes, metadata, a list; Transit: a `/`-bearing keyword), encoded by that profile.                    |
+---------------------+-------------------------------------------------------------------------------------------------------------------+
| Canonical rejection | The ten CBOR rejection payloads of `cbor_test.cljc:187-196`, and Transit text that decodes to an out-of-domain    |
|                     | value, each expected to be refused.                                                                               |
+---------------------+-------------------------------------------------------------------------------------------------------------------+
| Jing wrapping       | One canonical Jing byte string wrapped by `dao.jing.stream/wrap-bytes` for each lane and encoded by that profile; |
|                     | the unwrapped bytes as hex.                                                                                       |
+---------------------+-------------------------------------------------------------------------------------------------------------------+

The frozen suite has two parts per profile, kept in separate files so that
the strong claim and the weak claim are never asserted by the same test
(amendment recorded by the Architect on 2026-10-20 after the F0 review):

1. **Clean corpora** (`envelopes.edn`). Every entry must decode successfully
   on every lane to the expected value under the §4.4 equality rule, and
   CBOR entries must re-encode to the fixture bytes exactly, since the CBOR
   wire is canonical. Transit encoding is not byte-compared (its text is not
   specified canonical); its encode side is proved by the peer check below.
2. **Baseline exceptions** (`exceptions.edn`). Entries that already diverge
   across hosts, or fall outside the portable domain, in the pre-change tree
   at `master@212c45dd`, before any slice. Each entry records the exact
   per-host outcome (`:decodes` or `:throws`) observed at F0, and the test
   asserts that the running host still produces that recorded outcome,
   comparing successful decodes under the §4.4 equality rule. At F0 the
   exceptions are:
   - Transit list and nested list: the JVM Transit decoder throws on list
     text ("outside portable domain"), while Node and Dart decode it to a
     vector.
   - CBOR `1.0` (`f93c00`): the JVM and Dart decoders accept it; Node rejects
     `f93c00` as non-canonical (its own encoder emits `01` for the value 1).

   An exception entry pins the pre-existing behaviour so that no slice can
   silently change it, in either direction, without the fixture failing. It
   claims nothing about round trip: these paths were defective before F0,
   and "universal round trip" is asserted only for the clean corpora. Fixing
   an exception is a separate, named change that moves the entry from
   `exceptions.edn` to `envelopes.edn` and is reviewed as a behaviour change.

The fixture test, run on every slice from F0 on, covers both parts, and
additionally: each rejection payload is refused; each negotiation string is
what the profile offers.

**Bidirectional old/new peers (C2 and C4).** The F0 commit is built in a
sibling worktree as the "old peer". For each profile and for each of the jvm
and node lanes (dart where the test harness can host two builds; if it
cannot, the slice report records the gap by name and the Dart lane's
old/new coverage is reported as **absent**, never as covered by the jvm or
node runs): old serves, new dials; new serves, old dials. Each run attaches,
exchanges the envelope corpus in both directions, exchanges one Jing-wrapped
payload, and closes; the deposited values must satisfy the §4.4 equality
rule on both sides. A refused handshake, a protocol failure or an unequal
value fails the slice.

**What this does and does not claim (review A3-r2).** Three claims of
different strength, each with its own evidence:

+---------------------------+------------------------------------------------+-------------------------------------------------------------+
| Claim                     | Proved by                                      | Limit                                                       |
+===========================+================================================+=============================================================+
| Wire contract unchanged:  | Frozen negotiation fixtures (exact offer       | None within the covered lanes; a recorded Dart gap is a gap |
| names, negotiation,       | strings, one refusal, no-offer) and the        | in this claim's coverage, reported as such.                 |
| envelope shape, lifecycle | bidirectional old/new runs for both profiles.  |                                                             |
+---------------------------+------------------------------------------------+-------------------------------------------------------------+
| CBOR bytes unchanged      | Frozen CBOR fixtures: decode to the expected   | Byte-level, because the CBOR wire is canonical. Holds for   |
|                           | value **and** re-encode to the fixture bytes   | the fixture corpus; values outside it are inferred from the |
|                           | exactly, on every slice from F0 on.            | canonical rule, not measured.                               |
+---------------------------+------------------------------------------------+-------------------------------------------------------------+
| Transit interoperable     | Frozen Transit fixtures decode to the expected | **Semantic, not byte-level.** Transit text is not canonical |
|                           | value; old/new peers exchange the corpus in    | and is not byte-compared, so "the Transit encoder emits the |
|                           | both directions with equal deposits.           | same text as before" is not claimed. It would need a frozen |
|                           |                                                | Transit encoder comparison, which is not required because   |
|                           |                                                | no slice touches the Transit encoder (`transit.cljc`        |
|                           |                                                | `:103-155`, stays as it is); if a slice ever does, that     |
|                           |                                                | comparison is added to the fixture test for that slice.     |
+---------------------------+------------------------------------------------+-------------------------------------------------------------+

"This design changes no byte on the wire" is therefore stated only for
CBOR; for Transit the honest statement is that old and new peers
interoperate on the corpus. The wire proof says nothing about predicate or
API changes, which are documented separately: this design changes **no**
admission predicate (§4.4), moves `safe-number?` unchanged, and changes
`dao.data`'s require without changing any answer it gives (§6.2). The only
behaviour change is C3's, and it is at composition time, not on the wire.

## 8. What would reverse this design

- An owner instruction that a shared default codec must exist. Then the
  default is one named composition value in `yin.repl`, never in
  `dao.stream.ws`; the rest stands.
- Evidence that a codec needed in production cannot satisfy the §4.4 law.
  Then a declared domain label is added so compositions can refuse a swap
  statically (§5), and "peer" is qualified for that codec.
- A codec that genuinely needs per-attachment state (an in-band schema
  handshake that cannot be moved into the name; Arrow's in-band delta
  dictionaries, §5). Then an `open` hook is added for it alone. Neither
  stress test needs it under the restrictions stated in §5.
- Measured wire size or speed that makes one codec unfit for a path. That
  changes a composition value, not this interface.
- A consumer that needs D to be either existing predicate (so that a value
  outside D is promised to swap). Then D is not the contract's domain, the
  codecs are not peers for that value, and §6.1 point 3 is the honest answer.

## 9. Decision record

+------------+---------------------------------------------------------------------------------------------------------------+------------------------------+
| Id         | Decision                                                                                                      | Status                       |
+============+===============================================================================================================+==============================+
| CD1        | Codecs are peers behind the six-key stateless profile; the contract lives in `dao.stream.codec`; no codec     | Architect, 2026-10-20; owner |
|            | requires another; no shared namespace requires a codec.                                                       | may overrule                 |
+------------+---------------------------------------------------------------------------------------------------------------+------------------------------+
| CD2        | Resolution is data: the composition passes the profile; no registry, no protocol; absence is a                | Architect, 2026-10-20        |
|            | composition error at composition time.                                                                        |                              |
+------------+---------------------------------------------------------------------------------------------------------------+------------------------------+
| CD3        | One common swappable domain D in the contract, a new predicate that is a subset of both existing ones;        | Architect, 2026-10-20, r2    |
|            | neither existing admission predicate changes; the §4.4 law over D is a shared test with equality under        | (supersedes the r1 "base     |
|            | `=` plus the numeric rule.                                                                                    | domain moved from Transit")  |
+------------+---------------------------------------------------------------------------------------------------------------+------------------------------+
| CD4        | One capability, `bytes?`, for the consumer that exists; no other extension point until a consumer exists.     | Architect, 2026-10-20        |
+------------+---------------------------------------------------------------------------------------------------------------+------------------------------+
| CD5        | Profile keys move to `:dao.stream.codec/*` (§3.2), behind accessors, with both key sets present until the     | Architect, 2026-10-20; owner |
|            | contract slice. The owner may choose the alternative in OQ3 any time before C4.                               | may overrule via OQ3         |
+------------+---------------------------------------------------------------------------------------------------------------+------------------------------+
| CD6        | Migration is expand, migrate, contract (F0, C0-C4, §7.1); no slice removes what another slice still           | Architect, 2026-10-20, r2    |
|            | reads; the wire is proved by frozen fixtures and bidirectional old/new peers (§7.3), never by new/new suites. |                              |
+------------+---------------------------------------------------------------------------------------------------------------+------------------------------+
| CD7        | The §4.4 law excepts the baseline exception paths frozen at F0 (§7.3 `exceptions.edn`; today Transit lists);  | Architect, 2026-10-20,       |
|            | D is not narrowed and Transit's decoder is not touched in C0; the law test round-trips those values on CBOR   | ruling R2 on the C0 review   |
|            | and asserts Transit's frozen per-host outcome. Fixing the decoder is a separate, named behaviour change.      |                              |
+------------+---------------------------------------------------------------------------------------------------------------+------------------------------+

## 10. Open owner decisions

These are the owner's, not the Architect's. Each is recorded here when made,
with the date and the owner's words, and the touched sections are re-edited.

+------+------------------------------------------------------------------------------------------------------+----------------------------------------------------+
| Id   | Question                                                                                             | Architect's recommendation (not a decision)        |
+======+======================================================================================================+====================================================+
| OQ1  | Which profile `yin.repl.serve`/`connect` select by default once no shared default exists (§6.4).     | `dao.stream.cbor/profile` (slice C5). Until        |
|      |                                                                                                      | recorded, C2 passes `dao.stream.transit/profile`   |
|      |                                                                                                      | explicitly, so wire bytes are unchanged.           |
+------+------------------------------------------------------------------------------------------------------+----------------------------------------------------+
| OQ2  | Whether the 571-line cljd Transit port (`src/cljd/dao/stream/transit/cljd.cljd`) is kept once OQ1 is | Keep it while any composition or cross-host suite  |
|      | CBOR (§6.3). Peers means both are available; removing one is a scope decision.                       | selects Transit; revisit after C5, not before.     |
+------+------------------------------------------------------------------------------------------------------+----------------------------------------------------+
| OQ3  | Move the profile keys to `:dao.stream.codec/*` (CD5) or keep `:ws/*` and move only the gate and the  | Move them: the profile is the codec contract for   |
|      | domain. The review noted OQ3 is at once undecided and CD5's default. Resolution: CD5 is the          | any transport, and the repo has no compatibility   |
|      | **provisional** direction for C0-C3, which add the new keys beside the old and so are correct under  | constraint. The owner must record a choice before  |
|      | either answer; only C4 commits to one, and C4 does not run until the owner records the choice here.  | C4 is commissioned; nothing earlier waits on it.   |
|      | §7.1 C4 specifies both paths with their own grep gates; under "keep", `:ws/bytes?` is added so the   | Either way C4's acceptance never asserts the       |
|      | §3.3 capability survives.                                                                            | absence of `:ws/*` unless the keys were moved.     |
+------+------------------------------------------------------------------------------------------------------+----------------------------------------------------+

## 11. For a successor orchestrator

Decided (CD1-CD6): the contract namespace, data resolution, one common domain
D with a shared law test and unchanged codec predicates, the single `bytes?`
capability, the key move behind accessors (subject to OQ3), the
expand/migrate/contract sequence with the frozen-fixture wire proof. Not
decided: OQ1-OQ3 above; none of them blocks F0-C3, because C2 passes Transit
explicitly and C0-C3 are correct under either answer to OQ3. C4 waits for
OQ3; C5 waits for OQ1.

Start with **F0** (§7.3): it changes no source, and everything after it is
measured against what it records. Then **C0**: it adds a namespace, moves the
three numeric definitions, adds keys beside existing ones and changes no
behaviour; its acceptance is mechanical (three lanes green, counts unchanged
plus the named law and adversarial tests, fixtures passing, the `grep` gate
on `cbor.cljc` and `data.cljc`). Then C1, C2, C3 in order; each lands alone.
C4 after OQ3; C5 after OQ1.

Lanes and gates (`docs/agents/build-n-test.md`, Commands and Gate Ladder):
iterate on the JVM lane with `bb test:sub:clj dao.stream` (G0); at each slice
checkpoint run the three-lane subsystem test `bb test:sub dao.stream` (G1);
before landing run `bb test:changed` across all three lanes in the worktree
(G2), which for a foundation subsystem like `dao.stream` runs nearly the full
suite; `bb test` on master after merge is the mandatory gate (G3). The Dart
lane needs `flutter` on PATH; a fresh worktree needs `npm ci` before the Node
lane runs. The owner's test-tier cadence puts the slow tier (`bb test:slow`)
at phase boundaries: run it once after C4 lands, not per slice. Record the
per-lane test and assertion counts before and after every slice in the slice
report; "unchanged" in §7.1 is checked against those numbers. The §7.3
bidirectional peer check needs a second worktree at the F0 tag; the slice
report for C2 and C4 names that commit and the lanes it ran on.

Review requirement: this design and any implementation an orchestrator derives
from it under a Claude model are Claude-authored. Sign-off on each slice and on
the design itself must come from a non-Claude architect (the repo's standing
rule for Claude-authored work); a Claude reviewer may review but may not be the
sole sign-off. The reviewer checks, in this order: that no slice removes a key,
default or require that a later slice still reads (§7.1); the §4.4 law and
adversarial tests, including the symbol-metadata cases; the fixture test and,
at C2 and C4, the bidirectional peer check (§7.3); the grep gates in §7.1,
reading C4's under the OQ3 answer recorded in §10. Slice reports state the
wire result in the three-claim form of §7.3 (contract unchanged, CBOR bytes
unchanged, Transit interoperable) and name any lane whose old/new run did not
happen as a gap; a Dart gap is never reported as coverage.

## Appendix A. Citations opened for this document (2026-10-20, read only)

+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| Cite                                                   | Finding                                                                                                     |
+========================================================+=============================================================================================================+
| `src/cljc/dao/stream/ws.cljc:1-47,69-96,167-180,`      | Docstring "composition choice"; `codec-profile?` five keys; `checked-codec` default; one-arity gates;       |
| `277-294,340-357,402-462,504-542,554-588,625-652`      | `send-result`; teardown codes; `deliver!`/`receive!`/`receive-binary!` read `:ws/frame-kind`, call          |
|                                                        | `:ws/decode` and `:ws/portable-value?`; `make-attacher` "default: Transit"; `make-endpoint` default and     |
|                                                        | `:codec-index` by `:ws/subprotocol`; `accept-connection!` nil-offer default, seam `:ws/subprotocol`,        |
|                                                        | unsupported-subprotocol refusal.                                                                            |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/stream/transit.cljc:1-155`               | Host arms; numeric domain `:15-43`; predicate `:46-76` admits every keyword/symbol, every map incl.         |
|                                                        | records on clj/cljs, never inspects metadata; cljd decoder-record rejection `:61-70`; `valid-descriptor?`   |
|                                                        | `:86-93`; codec `:103-155`; `profile` docstring "plain composition data; the transport never special-cases  |
|                                                        | it".                                                                                                        |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/stream/cbor.cljc:1-119`                  | Requires Transit for `safe-number?` only; `portable-identifier?` `:25-36`; `portable-meta?` `:42-49` with   |
|                                                        | the cljd constructor-meta strip; `portable-value?` `:52-70` with `(not (record? x))`; `byte-payload?`       |
|                                                        | `:80-86`; `profile` `:111-119`.                                                                             |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| `test/dao/stream/cbor_test.cljc:161-210`,              | CBOR rejects the five malformed identifiers and non-portable metadata (`:166-183`); ten canonical-rejection |
| `test/dao/stream/cbor_test.cljd:186-190`               | payloads (`:186-200`); `transit-domain-stays-a-subset-of-the-cbor-domain` samples only agreeing values      |
|                                                        | (`:203-210`, `.cljd:186-190`).                                                                              |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/jing/stream.cljc:1-108`                  | Lane tests by `:ws/subprotocol` equality against both profiles; octets on Transit, bytes on CBOR; throws on |
|                                                        | any other profile.                                                                                          |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/data.cljc:1-60`                          | Requires Transit for `portable-value?`, applied in `number-node` to numbers only (`:52-60`).                |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| `src/clj/dao/stream/ws/jvm.clj:24-63,333-334,359,`     | Transit require; `default-codecs`; `negotiate` reads `:ws/subprotocol` of each codec; fallback; seam        |
| `377,410-436`                                          | `:ws/subprotocol`; `listen!` refuses no-offer upgrades.                                                     |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| `src/cljs/dao/stream/ws/node.cljs:34-70,192,210,`      | Same shape; `verifyClient` 400 `dao.stream/subprotocol-required`.                                           |
| `244-282`                                              |                                                                                                             |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| `src/cljd/dao/stream/ws/dart.cljd:18-28,108,143,`      | Same shape; `handle-request!` rejects a non-negotiated upgrade.                                             |
| `209-249`                                              |                                                                                                             |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| `src/cljs/dao/stream/ws/browser.cljs:84,101-108`       | Fallback only; DOM close-code substitution.                                                                 |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| `src/cljc/dao/stream/remote_channel.cljc:320-400,`     | No codec at endpoint, bind or attacher.                                                                     |
| `800-849`                                              |                                                                                                             |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| `src/cljc/yin/repl/serve.cljc:240-247`,                | Production serve/dial call sites; no codec.                                                                 |
| `src/cljc/yin/repl/connect.cljc:302-304`               |                                                                                                             |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| `src/cljd/dao/stream/transit/cljd.cljd` (571 lines),   | Line counts by `wc -l`.                                                                                     |
| `src/cljd/dao/stream/cbor/cljd.cljd` (306),            |                                                                                                             |
| `src/cljc/dao/stream/cbor/boring.cljc` (212)           |                                                                                                             |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| `docs/design/datom.world.md:70`,                       | One boundary; implementations do not add contracts; wasm surface's canonical CBOR "does not define the      |
| `datom.world.abi.md:25-73`, `dao.stream.wasm.md:22-27, | abstraction"; co-resident ring buffer may serve operations with no serialization; signatures specify the    |
| 96-105`                                                | import-surface implementation only; no TLV or alternate framing; the dissolved TLV service codec.           |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| `docs/design/dao.stream.ws.md:698-720` (Handshake;     | Two profiles, exact offer, no downgrade, absent-name default; portable domain text, "exactly that domain"   |
| Elements and Serialization)                            | and "widened" sentences (approximate, §4.4); numeric equality rule `:711-714`; canonical CBOR enforced on   |
|                                                        | receive.                                                                                                    |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| `docs/design/dao.data.md:87-96`                        | API block shows the Transit require.                                                                        |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| `docs/design/dao.stream.remote.md:453-469`             | A channel declares a portable value domain; transport swap preserves every handle semantics.                |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
| `test/` grep                                           | 22 namespaces require Transit; 13 call `make-attacher`/`make-endpoint` (59 sites); 7 name a seam            |
|                                                        | subprotocol; `loopback_net.cljc:164`; `transit_test.cljc:15-57`; `journal_test.cljc:6,354`; no test         |
|                                                        | references `transit/safe-number?` or the bounds.                                                            |
+--------------------------------------------------------+-------------------------------------------------------------------------------------------------------------+
