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
Clojure values, datoms, indexes, or manifests. This "no CBOR knowledge"
promise is about *pluggable, third-party-implementable* backends
(PostgreSQL, S3, and the memory/remote/DHT byte-store implementations) —
`dao.jing.file` is Jing's own built-in durability implementation, not a
third-party backend, and it is explicitly allowed to own a thin CBOR
framing envelope around the byte-store boundary (see *Memory and files*):
that envelope is Jing's own shared codec applied to a two-element
`[digest payload-bytes]` record, never a divergent per-backend codec or
value interpretation.

```text
write: value -> canonical CBOR bytes -> SHA-256 address -> backend
read:  address -> backend bytes -> integrity verification -> decoded value

backend: memory | file | PostgreSQL | S3 | remote | DHT | ...
```

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
  `{:found? boolean, :bytes b}` envelope (the shape `dao.jing.remote`
  already answers) with bytes in a portable byte representation — the
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
restriction; arbitrary Boring records do not become Jing values.

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

**This is the plan's one change outside `dao.jing*`, and it is a real
boundary widening, not a violation.** Pushing portable `=`/`hash`/`compare`
into `dao.space.index`'s datom comparators and `dao.space.query`'s builtins
is architecturally sound (an interpreter consuming a storage-adjacent
utility, not the reverse), but it is the widest blast radius in this
plan and needs explicit sign-off from whoever owns `dao.space` before
implementation starts on this section, separate from Jing's own review.

These operations must govern datom ordering in `dao.space.index` —
`compare-vals` and the EAVT/AEVT/AVET/VAET comparators; the generic
`dao.data.btree` comparator needs no change — and `dao.space.query`
matching/Datalog unification, including hashed lookup. `compare-vals`'s
numeric arm must use the portable compare directly, with no string-ordering
fallback for numbers. Portable `=` and `hash` recurse through collections,
so a datom value containing a carrier unifies structurally. In
`dao.space.query`, the comparison builtins (`= not= < > <= >= min max`)
route through these portable operations; the arithmetic builtins
(`+ - * / quot rem mod inc dec abs`) remain host-native and reject carrier
operands loudly rather than computing garbage — queries that combine
arithmetic builtins with floating, decimal, or rational datom values are
therefore not portable across hosts until arithmetic itself is made
portable, a recorded loud limitation consistent with VM arithmetic
remaining outside this migration. Where host-native dispatch cannot satisfy
the contract, the consumer's numeric comparison/equality/hash boundary must
use explicit portable operations consistently. Reusing an upstream record
representation alone does not satisfy this requirement. One intended
consequence, uniform with today's JVM behavior: covered-index membership
and matching are by numeric value while content addressing is kind-strict,
so `[e a 1 t m]` and `[e a 1.0 t m]` are one index entry with two distinct
content addresses.

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
selects a different value codec or re-hashes a host-specific representation.

### Memory and files

The memory backend stores byte snapshots under content addresses. The file
backend retains its append-only length framing, durability rules, duplicate
validation, and torn-tail recovery. Each frame contains a CBOR
`[digest payload-bytes]` record: digest is a raw 32-byte SHA-256 byte string
and payload-bytes contains the exact canonical payload. **`dao.jing.file`
itself owns parsing and constructing this two-element frame** — it is Jing's
own code, using Jing's own shared codec, not a third-party backend
inventing a codec of its own; the "backends need no CBOR knowledge" promise
above is about the pluggable byte-store layer underneath this framing, not
about `dao.jing.file` as a whole. The wrapper owns keyword↔digest
conversion, so file framing has no identifier-codec
dependency. Replay checks the digest length and compares it byte-for-byte
with the payload's SHA-256. The outer record is backend framing and does
not contribute to the content hash. It is exactly one definite two-element
CBOR array of byte strings, with shortest lengths and no trailing data,
inside the existing four-byte big-endian signed length prefix; reject a
frame exceeding that prefix's positive range before append.

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

Hash canonical payload bytes directly with SHA-256. Preserve the address
shape `:segment/sha256-<64 lowercase hex>`. Keep the existing string-based
`sha256` helper for its other callers and add a separate byte-hashing function.

Every newly encoded value receives its CBOR-derived address. No legacy
reader, old-address alias, or graph migration is included. Reject old
complete EDN file records without rewriting them. Existing stores and
published references must be rebuilt together, including address-bearing
indexes, ASTs, and continuations. Existing torn-tail recovery applies only
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
no metadata, byte strings, or rich numerics (`dao.stream.v2` Transit), so
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
   comparators) and `dao.space.query`'s comparison builtins route through
   them, with the arithmetic builtins left host-native per *Numeric
   identity* — this migration's only change outside the `dao.jing*`
   namespaces.
4. Migrate remote and DHT byte transport, including validation, missing-value
   behavior, transport limits, and errors.
5. Update documentation and downstream address fixtures — including the
   `dao.data.btree.storage` adapter and `dao.data.btree.md` §5.2, whose
   verification must become a byte-hash check, is cross-host valid under
   this plan, and whose default-off rationale is superseded — then run the
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
  mixed kinds, exact large values, hash lookup, equality and ordering,
  signed zeros, infinities, NaN, and preserved kind on re-encoding. Also:
  collection values containing carriers unify structurally; the comparison
  builtins (`= not= < > <= >= min max`) over decoded carriers; the
  arithmetic builtins over carriers reject loudly; and `[e a 1 t m]`
  versus `[e a 1.0 t m]` collapse to one covered-index entry while keeping
  two distinct content addresses.
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
decimal scale, signed zero, and retained metadata. SHA-256 collision
resistance is the addressing assumption, not a mathematical injectivity
claim about a finite digest. Acceptance includes every scenario above,
specifically surrogate rejection, float32 widening, empty-metadata omission,
carrier equality/hash/ordering and query matching, and identifier separation.
No implementation is accepted on byte fixtures alone while these consumer
semantics fail. Memory-mapped navigation, compression, arbitrary record
handlers, new storage backends,
and a redesign of Jing's write-effect protocol remain outside this work.
