# DaoJing: Backend-Independent CBOR Storage

Status: implementation plan; not yet implemented. Architecture-reviewed
2026-09-17 (`collab/1789680000000-architect-review-dao-jing-cbor.*`): no
blocking findings, one medium-severity ambiguity (file-backend frame
ownership, corrected below), and one cross-cutting change (the
`dao.space.index`/`query` comparator changes) flagged as needing explicit
sign-off from `dao.space`'s owners before this plan is built.

Related: [DaoJing](dao.jing.md), [DaoJing DHT](dao.jing.dht.md),
[architectural foundations](datom.world.md).

## Objective and invariant

DaoJing is a backend-independent storage substrate, like Datomic storage.
It can store content in memory, a file, PostgreSQL, S3, or another backend.
**This invariant must be preserved. CBOR is the stored data format, not a
choice of storage backend.**

Replace Jing's handwritten hash encoding and EDN persistence with canonical
CBOR bytes. Jing owns the value-to-bytes contract, content addressing, and
integrity verification. Backends store and retrieve opaque bytes by address
and provide their own durability guarantees. They need no knowledge of CBOR,
Clojure values, datoms, indexes, or manifests. **One exception, narrower
than "backend" vs. "not a backend":** `dao.jing.file` is the only backend
that re-ingests its own output across process death — it must persist an
append-only log and later replay it with no external message boundary to
mark records, so it alone must invent a self-describing frame, which is
why that frame is itself a CBOR structure (a two-element
`[digest payload-bytes]` array; see *Memory and files*). `dao.jing.mem`
never persists, so it has no framing at all; `dao.jing.remote`/`dao.jing.dht`
get record boundaries for free from Transit's own message envelopes, so
they frame with Base64-inside-Transit, never a raw CBOR array. This is not
about `dao.jing.file` being more or less "Jing's own" than any other
backend in this source tree — all of `memory`/`file`/`remote`/`dht` are
equally in-repo; only PostgreSQL and S3 are genuinely third-party and not
yet built. What is third-party-implementable is the byte-store *contract*
itself, not any particular shipped implementation of it. The exception is
that `dao.jing.file` parses its own frame with Jing's shared codec, never a
divergent per-backend codec or value interpretation.

```text
write: value -> canonical CBOR bytes -> multihash address -> backend
read:  address -> backend bytes -> integrity verification -> decoded value

backend: memory | file | PostgreSQL | S3 | remote | DHT | ...
```

The multihash address is `:segment/<algorithm-id>-<lowercase-hex-digest>`,
per [`dao.jing.hash-registry.md`](dao.jing.hash-registry.md). BLAKE3 is the
default minting algorithm; SHA-256 remains an equally supported, explicitly
selectable peer. Both algorithms hash the same canonical CBOR bytes for a
given value; this document's byte contract is unaffected by which algorithm
names the resulting address.

PostgreSQL and S3 illustrate the required interchangeability; implementing
new PostgreSQL or S3 backends is outside this codec migration.

## Community precedent and integration choice

Reuse [Boring](https://github.com/replikativ/boring), the CBOR serializer built
for Datahike. Konserve integrates it as a pluggable JVM/JavaScript serializer
(id 3), but marks it BETA, "Not yet recommended for production stores";
Fressian remains its default (verified against
[Konserve serializers](https://github.com/replikativ/konserve/blob/main/src/konserve/serializers.cljc),
2026-09-16).
Jing will reuse serialization directly without adopting Konserve's storage
API, mutable document operations, or Datahike-specific type handlers.

Pin `org.replikativ/boring` to `0.1.30` on JVM and JavaScript, verified as the
latest release on 2026-09-16 against
[Clojars](https://clojars.org/org.replikativ/boring). Implement the same
Jing-supported representation on Dart. Boring's upstream
[compatibility policy](https://github.com/replikativ/boring/blob/main/doc/COMPATIBILITY.md)
preserves released tag meanings and older-output readability, but does not
promise identical encoding bytes across releases (verified against that
policy, 2026-09-16). Jing's pin and frozen byte/hash fixtures own byte
stability; Konserve's integration does not. Dependency upgrades must pass
those fixtures without regeneration.

Select `:canonical` and explicitly set encode options for stringref off,
shapes off, and no index frame. Verified against the
[Boring README](https://github.com/replikativ/boring/blob/main/README.md),
2026-09-16: `:clojure` defaults to string deduplication and preserved float
width, `:canonical` uses bytewise key ordering and shortest numeric forms,
and `:canonical-rfc7049` uses length-first ordering. Option precedence over
profile defaults is unverified; fixtures must prove the effective options,
rather than relying on profile selection alone.

## Layering and interfaces

- Introduce `dao.jing.cbor/encode` and `decode`, operating on host byte
  arrays. Share normalization and validation across hosts; isolate Dart's
  codec implementation behind the same interface.
- Preserve the public value-facing `materialize!`, `get`, `segment-key`,
  `content-hash`, and `close!` operations. Existing callers still write and
  read values.
- Introduce an explicit byte-store boundary below those operations:
  `{:put-bytes-fn f :get-bytes-fn g :close-fn c}`. Put takes an address and
  canonical payload bytes and returns `:inserted` or `:present`; get takes
  an address and caller-supplied not-found sentinel and returns bytes or
  that sentinel. A shared Jing wrapper exposes the existing content-store
  handle over this byte store.
- This contract is effect-payload-shaped (address, bytes → verdict), so it
  can become the deferred effect-stream write path in `dao.jing.md` without
  a contract break; the synchronous function handle is its present-day
  shape, not a commitment. The put half is effect-shaped as stated; the get
  half becomes effect-shaped once its outcome is the explicit
  `{:found? boolean, :bytes b}` envelope — `dao.jing.remote` already
  answers the `{:found? boolean, :value v}` shape this generalizes
  (`remote.cljc:55-60,84-85`; `:bytes` is this plan's future key, not
  `:value`, since the value here is a byte representation, not a decoded
  Clojure value) — with bytes in a portable byte representation — the
  caller-supplied not-found sentinel is a wrapper-level convenience over
  that envelope, not part of the contract's streamable shape.
- Encode once before a write, derive the address from those bytes, and pass
  those same bytes to the backend. On `:present`, read back and verify the
  bytes by hash and byte-for-byte equality with the proposed payload. Never
  overwrite conflicting content. Return an address only after
  the backend reports its applicable storage guarantee.
- The wrapper derives and verifies addresses; a backend re-verifies a put
  exactly when bytes arrive from an untrusted party, such as a remote/DHT
  server checking a client's claim.
- Verify every retrieved payload against its requested address before
  decoding exactly one supported value. Canonicality verification (decode,
  re-encode, byte-compare) occurs exactly once at each ingress of bytes Jing
  did not encode itself: remote/DHT receipt and file replay acceptance,
  before storage, caching, or exposure. Ordinary reads of accepted snapshots
  hash-verify and decode without re-encoding. Validation belongs to the
  shared codec/wrapper at those boundaries, not to backend value handlers;
  immutable local forwarding does not repeat ingress validation. Missing
  content remains distinct from stored nil, which has a CBOR representation.
- Store immutable snapshots. Memory backends copy byte arrays on insertion
  and retrieval so caller mutation cannot change previously addressed
  content. Other backends must provide the same observable isolation.
- Keep backend handles and composition explicit. Introduce no global
  backend registry, ambient codec options, or domain-specific interpretation.

## Encoding contract

Use Boring's `:canonical` profile with string references, shaped arrays, and
offset indexes disabled. Jing's normalization and named CBOR extensions are
fixed, not backend-configurable. The hash covers only canonical payload
bytes, never a backend coordinate, file frame, address envelope, or network
message.

Supported values are nil, booleans, strings, keywords, symbols, byte strings,
vectors, finite lists/sequences, maps, sets, metadata, integers,
floating-point numbers, big integers, decimals, and ratios. Reject arbitrary
records, functions, and unsupported host objects before storage. The numeric
carriers described below are explicitly supported exceptions to the record
restriction; arbitrary Boring records do not become Jing values. **This is
a narrower domain than today's transitional `pr-str`-based encoder, which
addresses anything printable/readable** — characters, `#inst`, `#uuid`, and
other tagged literals are addressable today and have no slot in this
supported-values list or in Jing's four named CBOR extensions; they become
rejected, unsupported values once this plan lands. No current producer
emits them, so nothing observed breaks, but "existing stores... must be
rebuilt together" (*Addressing and clean break*) presumes every stored
value is re-encodable under the new domain — an assumption this delta
should be checked against before rebuild, not discovered during it.

- Normalize sorted collections to ordinary maps and sets. Sort map keys
  and set elements lexicographically by unsigned canonical encoded bytes
  (a proper prefix sorts first), not length-first. Reject duplicate canonical
  keys or elements introduced by normalization instead of losing entries.
  Supported maps/sets must also survive portable decoded equality without
  collapsing entries: reject such collisions on encode and before decoded
  collection construction, including numerically equal keys of different
  kinds. Do not let the accepting host determine this boundary.
- Encode strings as UTF-8 without Unicode normalization. Reject unpaired
  high or low surrogates before encoding on every host, including in
  identifier components and metadata; normal astral text and noncharacters
  remain legal. Reject invalid UTF-8 on decode. Boring's own surrogate
  pre-validation is unverified; Jing owns this rule regardless.
- Lists and sequences share a representation distinct from vectors. Use
  CBOR tag 27 with the name `dao.jing/list` and an array argument; plain
  CBOR arrays represent vectors.
- Preserve collection and symbol metadata using Boring's `clojure/with-meta`
  mapping. Strip `:line`, `:column`, `:end-line`, and `:end-column`; omit empty
  metadata. Clear metadata on internally constructed Dart collections
  before attaching supplied metadata. Do not strip arbitrary user `:tag`
  metadata as a workaround for Dart constructor metadata. This clearing
  covers only collections Jing constructs at decode: a Dart producer
  building a payload with `list` must still clear ClojureDart's constructor
  metadata itself (`(with-meta (apply list xs) nil)`); that `dao.jing.md`
  open item carries forward, it is not retired by this plan.
- Encode **all** keywords and symbols as tag 27 with `dao.jing/keyword` or
  `dao.jing/symbol` and `[namespace name]`, where namespace is nil or a
  string and name is a string, taken directly from the identifier's fields.
  Uniform escaping removes a host-sensitive ordinary/escaped predicate.
  Never parse or print these components to reconstruct an identifier;
  preserve nil versus empty namespace and literal slashes, whitespace, and
  leading colons. Verified against
  [Boring's writer](https://github.com/replikativ/boring/blob/main/src/boring/writer.cljs),
  2026-09-16: its native tag 39 carries slash-joined text with a keyword
  colon prefix, so distinct namespace/name pairs or colon-leading symbols
  can collide. Jing has no native-identifier arm: reject tag 39, including
  otherwise ordinary names, and enforce that same boundary by canonical
  re-encoding at ingress.
- Hash byte strings by content, independent of host object identity.
- Reject malformed CBOR, unknown tags or named wrappers outside this
  profile, duplicate map keys or set elements, trailing data, and
  unsupported decoded values. Do not enable lossy encoder fallbacks.

Jing's four names use tag 27 followed by exactly `[name-string, payload]`:
`dao.jing/list` takes an array, `dao.jing/keyword` and `dao.jing/symbol` take
the two-component arrays above, and `dao.jing/float64` takes an eight-byte
byte string. Tag 27's object meaning is verified against the
[IANA registry](https://www.iana.org/assignments/cbor-tags/cbor-tags.xhtml);
the array-payload grammar and public mechanisms are verified against
[Boring extension documentation](https://github.com/replikativ/boring/blob/main/doc/EXTENDING.md)
and [records.cljc](https://github.com/replikativ/boring/blob/main/src/boring/records.cljc),
2026-09-16. Write these frames with `tagged-literal`; read them as
`boring.data/UnknownRecord`, then convert recursively through a closed Jing
name/shape dispatch before exposing values, preserving validated metadata
when converting a wrapper to its value. Use no ambient registry or
record/map constructor macros for these array payloads. Other UnknownRecords
are errors. This chosen post-decode path deliberately does not set
`:on-unknown-record :error`, which would reject Jing's own names; that
option alone would not close numeric tags anyway.

Explicitly reject inert `boring.data/TaggedValue` and every `SimpleValue`
outside Jing's nil/boolean domain: unknown numeric tags survive Boring
decoding as TaggedValue (verified against
[data.cljc](https://github.com/replikativ/boring/blob/main/src/boring/data.cljc),
2026-09-16). Validate tag/name/payload shapes before any lossy host
conversion; successful generic decoding is not profile acceptance. Native
CBOR floating-point items are forbidden even if a host decoder could turn
one into an integer. Frozen fixtures define the remaining Boring mappings
for sets and `clojure/with-meta`, whose payload is `[meta value]`; no other
Boring collection or record mappings are admitted. All lengths and integer
arguments use shortest definite encodings; integers fitting CBOR major
types 0/1 do not use bignum tags, and bignum magnitudes have no leading zero.

### Ingress limits and host-capability refusals

Ratified from the J0-J3 fixture corpus and its errata
(`test/resources/dao/jing/cbor-v1.errata.md`), not derived from the text
above: an implementation must enforce these explicitly rather than rely on
host defaults.

- Cap CBOR item nesting depth at 128 on both encode and decode, counting the
  top-level item as depth 1 and every array, map, and tag as one level
  deeper (a `dao.jing/list` frame therefore costs three levels). Refuse
  `:unsupported-value` on encode and `:malformed-cbor` on decode past the
  cap; this is a robustness rule against unbounded recursion, independent
  of the profile's other refusal classes.
- Constrain a decimal's exponent to `[-(2^31 - 1), 2^31]`, the JVM
  `BigDecimal` scale range, identically on every host. Refuse
  `:malformed-number` outside it. Without a shared window, one host can
  accept a canonical payload another host refuses, letting the accepting
  host determine the boundary the contract otherwise forbids.
- A host whose identifier equality is joined-name-based (ClojureScript
  keywords and symbols compare by namespace/name joined into one string)
  cannot hold two Jing values that are namespace/name-distinct but
  joined-name-equal (for example `(keyword nil "a/b")` and
  `(keyword "a" "b")`) in one decoded map or set without merging them. This
  is a host materialization limit, not a Jing acceptance rule: portable
  equality (`equiv`) keeps such values distinct, and the encoded bytes are
  canonical and unambiguous. Refuse with `:host-collapse`, a class outside
  the sixteen decode-refusal classes above, on a host that cannot hold the
  value. `:host-collapse` is a per-host capability refusal: J3's cross-host
  conformance requires identical outcomes across hosts except this named
  class, applied only on hosts whose identifier equality actually merges.

### Numeric identity

Preserve integer, floating-point, decimal, and rational kinds. This is an
explicit user choice: an integer `1` and floating-point `1.0` have different
addresses. Host integer width is not identity; small and big integers of the
same value share an address.

Carriers must implement portable `=`/`hash`/`compare` consistently across
hosts, including carrier-to-native-number comparisons in both operand
orders: compare numerically, never as disjoint record types. Finite values
compare by exact mathematical value (including a float's exact binary
value), without rounding through double; equal numeric values compare zero
and hash equally, independent of kind, decimal scale, and zero sign.
Order negative infinity below finite values, positive infinity above them,
and canonical NaN last; canonical NaNs are equal to each other for this
portable contract. Content identity remains stricter than numeric equality:
integer/float kind, decimal scale, and float zero sign still affect bytes.

**Owner ruling (2026-09-22): `dao.space.query`'s `=`/`not=` builtins and
Datalog unification match content addressing's full kind-strictness, not
just host `=`.** `(= 1 1.0)` must remain `false`; extended by a follow-up
ruling to cover decimal scale and float zero sign the same way content
addressing does, since these are one combined statement in *Numeric
identity* above: `(= 1.0M 1.00M)` and `(= 0.0 -0.0)` must also be `false`.
Host Clojure `=` cannot deliver this alone -- confirmed during step 3's
implementation, `(= 0.0 -0.0)` and `(= 1.0M 1.00M)` are both `true` on the
JVM, so host sets and maps merge values content addressing keeps distinct.
`dao.jing.cbor` therefore exports a new portable, kind-strict `content=`
(with a consistent `content-hash`) built the same way `equiv` is but with a
kind-strict numeric key in place of `num=`; `dao.space.query`'s `=`/`not=`
and Datalog unification bind to `content=`, not to host `=`. A datom value
containing a numeric carrier unifies only with a value of the same kind,
scale and sign, never across them. Portable ordering operations below feed
ordering and index comparators only, distinct from `content=`.

Pushing portable `compare` into `dao.space.index`'s datom comparators and
`dao.space.query`'s ordering builtins is architecturally sound (an
interpreter consuming a storage-adjacent utility, not the reverse) and, on
every axis that matters, a continuation of today's JVM behavior rather than
a change: `compare-vals` already dispatches to host `compare`, and
`(compare 1 1.0)` is already `0`; Clojure's native `< > <= >=` already
compare numbers by value across kinds (`(<= 1 1.0)` is already `true`).
This section's sign-off from whoever owns `dao.space` is about making
ordering correct and consistent for CBOR's decimal, rational, and
big-integer carriers, which today's host `compare`/`< >` do not know about,
and about wiring `content=` in place of host `=` where kind-strictness
must be exact.

**Owner-accepted limit (2026-09-22): a query's own returned result set can
still merge two content-distinct rows on the JVM and Dart.** Matching,
unification, ordering, and `min`/`max` are all `content=`-strict end to
end, confirmed during step 3's implementation. But the value `q` returns is
a plain host `#{...}`, and a host set decides membership with host `=`/
`hash` as it inserts each row, independent of how carefully the rows were
deduplicated beforehand: `[1.0M]` and `[1.00M]`, or `[0.0]` and `[-0.0]`,
still collapse to one row on the JVM and Dart, because host `=` merges them
regardless of insertion order (Node is unaffected, since its decoded
carriers already stay host-distinct). Preserving both rows in the final
output would require the return value to stop being a plain host set (for
example a content-key-ordered structure), a real interface change the
owner declined for this step. This is accepted as a narrower, separable
limitation, pinned by a test (`query_numeric_test.cljc`) so it fails loudly
if it silently changes rather than being rediscovered by surprise.

These operations must govern datom ordering in `dao.space.index` —
`compare-vals` and the EAVT/AEVT/AVET/VAET comparators; the generic
`dao.data.btree` comparator needs no change. `compare-vals`'s numeric arm
must use the portable compare directly, with no string-ordering fallback
for numbers. In `dao.space.query`, the ordering builtins (`< > <= >= min
max`) route through the portable compare above; `=` and `not=` bind to
`dao.jing.cbor/content=` per the ruling above, not host `=`. The
arithmetic builtins
(`+ - * / quot rem mod inc dec abs`) remain host-native and reject carrier
operands loudly rather than computing garbage — queries that combine
arithmetic builtins with floating, decimal, or rational datom values are
therefore not portable across hosts until arithmetic itself is made
portable, a recorded loud limitation consistent with VM arithmetic
remaining outside this migration. Where host-native dispatch cannot
satisfy the ordering contract, the consumer's numeric comparison boundary
must use explicit portable operations consistently. Reusing an upstream
record representation alone does not satisfy this requirement. One
intended consequence: covered-index membership and ordering are by numeric
value while content addressing and query equality are both kind-strict, so
`[e a 1 t m]` and `[e a 1.0 t m]` are one index entry with two distinct
content addresses that do not unify against each other in a query.

**Owner ruling (2026-09-22): `min`/`max` tie-break prefers finite
precision.** `min`/`max` return one of their two operands unchanged, never
a computed value; on a numeric tie (`compare` is `0` but the operands are
not content-identical, e.g. `(min 1 1.0)`), Clojure's own `min`/`max` keep
whichever operand a fold happens to see last, which depends on argument
order and is not a rule (`(min 1 1.0)` and `(min 1.0 1)` disagree on the
JVM today). The portable versions instead: (1) if exactly one operand is
`float64`, return the other one — integer, decimal, and rational are all
finite (exact) precision, and float64 is the one inexact IEEE-754 kind, so
it always loses a tie to an exact operand; (2) if both operands are
`float64`, or both are exact but distinct (different kind, or the same
kind at a different scale or sign), return whichever operand has the
shorter canonical CBOR encoding — the more compact representation, for
example decimal `1.0` (exponent `-1`, mantissa `10`, a one-byte shortest
head) over decimal `1.00` (exponent `-2`, mantissa `100`, a two-byte
shortest head); (3) if the two encodings are the same length too, return
whichever sorts first by canonical CBOR bytes, the same unsigned bytewise
order this profile already uses for map keys and set elements, so no new
ordering machinery is needed for the final, rarely-reached tiebreak. All
three cases are independent of argument order and of which host runs them:
`(min 1 1.0)` and `(min 1.0 1)` both return `1`.

- Provide a portable `float64` constructor/carrier. JavaScript callers use
  it for integral floating-point values such as `1.0`; ordinary integral
  JavaScript numbers remain integers, except native negative zero, which
  is floating-point content so its sign survives. Decoding floating-point content on
  JavaScript returns the carrier so re-encoding cannot lose its kind.
- Encode floating-point content on every host as tag 27,
  `dao.jing/float64`, with eight big-endian IEEE-754 bytes. This avoids
  Boring's native-number differences between JVM and JavaScript, verified
  against its compatibility policy above: integral JS numbers encode as
  integers while JVM `1.0` remains a float under `:canonical`.
  Widen native float32 values to float64, preserve signed zero and
  infinities, and canonicalize NaNs to quiet NaN bits `0x7ff8000000000000`.
  **The Jing profile emits no native CBOR floating-point whatsoever**;
  Boring's canonical float narrowing and NaN encoding never govern Jing
  floating-point bytes. The carrier is Jing-owned; upstream has no float64
  wrapper (verified against Boring `data.cljc`, 2026-09-16).
- Use CBOR integers and bignum tags 2/3 for exact integers. Never convert
  large integers through a floating-point intermediate. Reject unsafe
  integral JavaScript Number inputs; callers must supply BigInt for exact
  integers or the float64 carrier for floating-point content.
- Use decimal-fraction tag 4 with `[exponent mantissa]`; preserve decimal
  scale. Use rational tag 30 with a reduced numerator and positive,
  nonzero denominator. Retain rational kind even when the denominator is 1.
  Tags 2/3, 4, and 30 are registered meanings, verified against IANA above,
  2026-09-16; Jing allocates no custom numeric tag.
- Use native exact numeric types where they preserve the declared kind and
  representation; otherwise use immutable carriers. Reuse Boring's
  JavaScript decimal/rational representations, verified against Boring
  `data.cljc`, 2026-09-16: `Decimal [exponent mantissa]` and
  `Rational [numerator denominator]` exist on JVM and CLJS, with Rational
  re-encoding as tag 30. Wrap/adapt them as needed for the portable numeric
  operations above. Dart uses BigInt and explicit
  decimal/rational carriers. Add portable constructors and accessors;
  extending VM arithmetic is outside this migration.

## Backend changes

All existing backends move to the shared byte-store boundary. No backend
selects a different value codec or re-hashes a host-specific representation
— with one named exception, `dao.jing.file`'s own frame parsing (see the
Objective's carve-out, and *Memory and files* below).

### Memory and files

The memory backend stores byte snapshots under content addresses. The file
backend retains its append-only length framing, durability rules, duplicate
validation, and torn-tail recovery. Each frame contains a CBOR
`[digest payload-bytes]` record: digest is the raw digest byte string for
the address's carried algorithm (both initial registry algorithms are 32
bytes) and payload-bytes contains the exact canonical payload.
**`dao.jing.file` itself owns parsing and constructing this two-element
frame**, with Jing's own shared codec — the exception the Objective names
above, needed because `dao.jing.file`'s frame shape is itself a CBOR
structure, unlike `dao.jing.mem`'s unframed map or
`dao.jing.remote`/`dao.jing.dht`'s Base64-inside-Transit framing. The
wrapper owns keyword↔digest conversion, so file framing has no
identifier-codec dependency, and the algorithm identifier itself is not
carried in the frame — it round-trips through the address keyword the
digest is paired with at the wrapper boundary. Replay checks the digest
length against the address-carried algorithm's registry entry and compares
it byte-for-byte with the payload's digest under that same algorithm. The
outer record is backend framing and does not contribute to the content
hash. It is exactly one definite two-element CBOR array of byte strings,
with shortest lengths and no trailing data, inside the existing four-byte
big-endian signed length prefix; reject a frame exceeding that prefix's
positive range before append.

The byte-string payload is intentional: a file backend delegates payload
canonicality acceptance at replay to the shared Jing validator, then stores
and serves the original opaque bytes. It never replaces them with a decoded
and re-encoded value. Duplicate digests require identical payload bytes.
PostgreSQL byte columns and S3 objects obey the same ingress rule when opened
over externally supplied bytes.

### Remote and DHT

Carry canonical payload bytes as padded standard-alphabet Base64 strings
(no whitespace or URL-safe alphabet) inside the existing Transit
operation envelopes. Encode and decode this transport representation at
Jing's network boundaries; leave general DaoStream Transit unchanged.
Remote and DHT APIs above the byte-store wrapper remain value-facing.

Decode Base64 strictly, hash-verify received bytes against the claimed
address, and perform the one ingress canonicality check before accepting or
caching them. Preserve explicit presence indicators, idempotent writes,
correlated remote errors, and DHT refusal/next-candidate behavior. A found
nil travels as Base64 of CBOR nil; an absent envelope keeps its explicit
false indicator and nil placeholder, which is not Base64-decoded.

The only current Jing application-level message byte cap is the DHT UDP
budget of 1200 bytes (verified against `src/cljc/dao/jing/dht/node.cljc`,
`max-datagram`); the WebSocket path enforces no byte cap today (verified
against `src/cljc/dao/jing/remote.cljc`). For n payload bytes, Base64 occupies
`4 × ceil(n / 3)` bytes, roughly 33% overhead. With E bytes of actual
Transit envelope overhead, require `E + 4 × ceil(n / 3) ≤ 1200`, giving at
most `3 × floor((1200 - E) / 4)` payload bytes; E varies by request/reply
and coordinates, so check the final UTF-8 message size, not a fixed raw
payload allowance. Oversized sends are refused and oversized replies
dropped; DHT writes still acknowledge the local durable insert and degrade
to local-only storage, while remote fetch attempts time out. Decide to keep
WebSocket without a Jing byte cap in this migration: arbitrary payload
length is limited by host resources, not a new codec-domain limit. This
adds no UDP fragmentation or general-purpose transport protocol.

The clean break requires coordinated peer upgrades; no version negotiation
exists. A new-client put to an old server fails as an address mismatch
(the old server hashes the Base64 string as a value); an old-client put to
a new server fails at Base64 decoding for ordinary legacy values. A legacy
string that happens to be valid Base64 must still pass CBOR profile and
address verification and is rejected there, never silently reinterpreted.
These failures remain loud remote errors or explicit DHT refusal; there is
no legacy fallback.

### Other backends

A PostgreSQL implementation may store address-to-byte-column entries; an S3
implementation may store address-to-object-body entries. Their insertion,
concurrency, and durability mechanisms belong to the backend. The byte-store
contract requires that an existing address is never silently overwritten
with different bytes. Neither backend needs CBOR value handlers.

## Addressing and clean break

Hash canonical payload bytes directly with the selected registry algorithm,
per [`dao.jing.hash-registry.md`](dao.jing.hash-registry.md): BLAKE3 by
default, SHA-256 by explicit selection. Preserve the address shape
`:segment/<algorithm-id>-<64 lowercase hex>`, with canonical forms
`:segment/blake3-<64 lowercase hex>` and `:segment/sha256-<64 lowercase
hex>`. Keep the existing string-based `sha256` helper for its other callers
and add separate byte-hashing functions for each registered algorithm.

Every newly encoded value receives its CBOR-derived address. No legacy
reader, old-address alias, or graph migration is included. Reject old
complete EDN file records without rewriting them. Existing stores and
published references must be rebuilt together, including address-bearing
indexes, ASTs, and continuations. **This states the rebuild's precondition
explicitly: with no legacy reader, the only reconstruction path is
replaying original values from their intake streams, not reading them back
out of a rejected old store.** A value whose intake stream has since
evicted it (a `:dao.stream/gap`) and whose old store is rejected under this
plan has no remaining source — that content is unrecoverable, not merely
inconvenient to rebuild. Rebuild readiness should be checked against
retained intake history before this migration lands, not assumed.

**Owner ruling (2026-09-22): rebuild-readiness gate cleared.** This is a
development repository with no deployed store, no published index
manifest, no externally held Jing address, and no retained intake history
outside it. The orchestrator's repository-side audit found no committed
production store or manifest (only unrelated build artifacts), no
character/`#inst`/`#uuid` literal reaching a Jing-addressed value (the two
candidates, `random-uuid` and `js/Date.now` in `yin.vm.telemetry` and
`yin.repl`, are already stringified or numeric before use), and confirmed
`test/dao/data/psset_fixtures.cljc` as the one committed old-address
fixture already flagged for regeneration in step 5. With nothing deployed,
there is no unrecoverable content at risk and no rebuild to perform: the
clean break authorized by this document has no remaining precondition.
Both owner gates on step 3 (this one and the `dao.space` comparator gate
above) are now cleared.

Existing torn-tail recovery applies only
after the file is recognized as a valid new-format log; an old-format file
must not be treated as an empty or recoverable new store.
An empty file is a new log. Recognize a nonempty log only after its first
complete frame passes the new frame, digest, and payload checks. Validate
all complete frames before truncating an incomplete tail; any corrupt
complete frame or a nonempty file with no valid first frame fails without
mutation. This deliberately refuses an interrupted first write that cannot
be distinguished from foreign content without adding a format header. The
remedy for an interrupted first write is to delete or recreate the file: the
interrupted put was never acknowledged, so no acknowledged content is lost
and recreation is always safe.

Update the main Jing design, backend documentation, and downstream canonical
encoding references to describe the shared byte-store boundary and remove
the superseded transitional-printer limitations. Name exactly which
`dao.jing.md` open items this plan retires — the canonical-encoding item
with its three residuals (symbol metadata not address-significant,
pathological print collisions, ambient print-var leakage), byte-array
identity hashing, and metadata carriage for the file/remote/DHT codecs —
and which stay open: the intake transport's portable domain still carries
no metadata, byte strings, or rich numerics (`dao.stream` Transit), so
such payloads reach Jing only through direct `materialize!` calls and the
intake fail-closed rule carries forward unchanged. The ClojureDart `list`
producer obligation likewise carries forward, as *Encoding contract*
records.

## Implementation sequence and validation

1. Write encoding-contract tests and frozen CBOR hex/SHA-256 fixtures before
   implementation. Pin Boring and implement the JVM/JavaScript codec wrapper,
   normalization, named extensions, and numeric constructors.
2. Implement the matching Dart codec and carriers, either hand-rolled or
   over a Dart CBOR package, behind the shared interface — frozen fixtures
   own conformance either way. Require all three hosts
   to read each other's fixtures and reproduce identical canonical bytes.
3. Add byte hashing and the common value-facing wrapper; migrate memory and
   file backends to opaque bytes. Preserve public value-facing call sites.
   `dao.jing.cbor` exports the portable `=`/`hash`/`compare`; the numeric
   arm of `dao.space.index/compare-vals` (the EAVT/AEVT/AVET/VAET datom
   comparators) and `dao.space.query`'s ordering builtins (`< > <= >= min
   max`) route through them, with the arithmetic builtins left host-native
   per *Numeric identity* — the deepest of this migration's changes outside
   the `dao.jing*` namespaces, threading new runtime behavior into an
   existing consumer's ordering logic. Per the owner ruling in *Numeric
   identity*, `dao.space.query`'s `=`/`not=` builtins and Datalog
   unification bind to the new `dao.jing.cbor/content=`, kind-strict and
   not host `=` (step 5's `dao.data.btree.md` §5.2 change is
   shallower still: a pre-authorized default flip, not new code or a new
   runtime dependency).
4. Migrate remote and DHT byte transport, including validation, missing-value
   behavior, transport limits, and errors.
5. Update documentation and downstream address fixtures — including
   `dao.data.btree.md` §5.2, whose same-host-only verification default is
   **a default flip, not a code or format change**: §5.2 already
   pre-authorizes this exact trigger ("when the canonical byte encoding
   lands... the default flips to on and the same-host restriction
   disappears — the check itself needs no format change, only a stable
   encoding under it"). This CBOR migration is that stable encoding
   landing; `dao.data.btree.storage`'s rehash-and-compare check is
   unchanged code, now verified safe to default on. Then run the
   repository's JVM, Node, and Dart suites and applicable lint checks.

Required test scenarios:

- Map/set insertion order; mixed-type keys; sorted collections; list/vector
  distinction; collection and symbol metadata; empty maps, sets, vectors,
  and lists with no metadata versus empty or reader-position-only metadata;
  independence from ambient print settings.
- Unicode astral text and noncharacters; no normalization of composed versus
  decomposed text; reject `"a\uDC80b"` and an unpaired high surrogate before
  storage on JVM, Node, and Dart, including in metadata and identifiers.
- Pathological identifiers: `(symbol "42")` versus integer 42;
  `(keyword "a/b")` with ns nil/name "a/b" versus `(keyword "a" "b")`;
  the corresponding symbol pair; names containing whitespace or a leading
  colon; nil versus empty namespace. Construct exact component pairs when a
  host's one-argument constructor parses slash text. All identifiers use
  named frames; reject tag 39 even for ordinary names.
- Frozen read/write fixtures for each of `dao.jing/list`,
  `dao.jing/keyword`, `dao.jing/symbol`, and `dao.jing/float64`, including
  malformed payload shapes; closed UnknownRecord conversion and effective
  stringref/shapes/index disabling.
- Byte-array content identity and mutation isolation before and after reads.
- Integer versus integral float; signed zero; infinities and NaNs; integer
  boundaries and bignums; decimal scale; reduced ratios and denominator 1.
- Native float32 widening: a host float32 0.1 encodes the same eight bytes
  as its exact float64 widening, distinct from float64 0.1; Node and Dart
  reproduce the widened fixture, including signed-zero widening.
- Node/Dart decoded numeric carriers in index-ordered datom positions
  (`dao.space.index/compare-vals`, no string fallback) and
  `dao.space.query` matched positions: native/carrier operands both ways,
  mixed kinds, exact large values, ordering, signed zeros, infinities,
  NaN, and preserved kind on re-encoding. Also: the ordering builtins
  (`< > <= >= min max`) over decoded carriers; `=`/`not=` and Datalog
  unification bind to `dao.jing.cbor/content=`, kind-strict per the owner
  ruling in *Numeric identity*, so a collection value containing a carrier
  unifies only with a value of the same kind, scale and sign; the
  arithmetic builtins over carriers
  reject loudly; and `[e a 1 t m]` versus `[e a 1.0 t m]` collapse to one
  covered-index entry while keeping two distinct content addresses and not
  unifying against each other in a query.
- Malformed and noncanonical encodings; duplicate keys; unknown tags;
  duplicate set elements and decoded equality collapse; TaggedValue and
  unsupported SimpleValue; native CBOR floats; invalid UTF-8; trailing data;
  unsupported host values; address mismatches. Hash-valid noncanonical
  payloads fail at ingress; repeated local hydration hash-verifies every
  time without repeating canonical re-encoding.
- File reopen, duplicate insertion, durability failures, torn tails,
  complete corrupt frames, raw digest length/mismatch, and nondestructive
  rejection of old EDN stores (including those with torn tails) and
  unrecognizable first frames.
- Memory/file/remote/DHT agreement on addresses and decoded content, including
  metadata, binary values, rich numbers, stored nil, and missing content.
- Network corruption, malformed Base64, mixed-version peers in both
  directions (including Base64-shaped legacy strings), and peer failure.
  DHT final-envelope sizes at and beyond 1200 bytes for puts and replies,
  local-only oversized writes, and a WebSocket round trip beyond that
  budget with no new Jing cap.
- Existing Jing, published-index, AST-addressing, and continuation tests.
- An independent CBOR reader inspecting fixtures, with explicit handlers for
  Jing's named extensions and the correct deterministic ordering rule.

Acceptance requires identical payload bytes and addresses across hosts,
stable encode-decode-encode results for all supported values, and the same
content address regardless of the selected storage backend. It also requires
**injectivity**: pairwise-distinct supported content identities, including
the pathological identifier class, must have pairwise-distinct encodings and
fixture addresses. Identity here is after the explicitly declared
normalizations (integer width, sortedness, sequence realization, stripped or
empty metadata, reduced ratios, NaN payloads, and float32 widening); it
retains numeric kind,
decimal scale, signed zero, and retained metadata. Collision resistance of
the address-carried algorithm (BLAKE3 or SHA-256) is the addressing
assumption, not a mathematical injectivity claim about a finite digest.
Acceptance includes every scenario above,
specifically surrogate rejection, float32 widening, empty-metadata omission,
carrier equality/hash/ordering and query matching, and identifier separation.
No implementation is accepted on byte fixtures alone while these consumer
semantics fail. Memory-mapped navigation, compression, arbitrary record
handlers, new storage backends,
and a redesign of Jing's write-effect protocol remain outside this work.
