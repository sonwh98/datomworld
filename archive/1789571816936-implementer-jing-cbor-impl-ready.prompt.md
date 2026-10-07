Created-GMT: 2026-09-16 15:16:56 GMT
Created-Local: 2026-09-16 22:16:56 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: Make the dao.jing.cbor design implementation-ready

Role: Implementer (design documentation only)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-16 22:16:56 +07 | Status: active | Rationale: user-directed; deep-reasoning revision of a storage design document

## Authorization and hard bounds

You are authorized to edit EXACTLY ONE file: `docs/design/dao.jing.cbor.md`.
Do not create, edit, or delete any other file. Do not touch `src/`, `test/`,
dependency manifests, or any other design document — if you believe another
document must change, say so in your final report instead of editing it.
This effort is design documentation only; no code changes exist in this work.

## Objective

Revise `docs/design/dao.jing.cbor.md` (currently "Status: implementation
plan; not yet implemented") until it is implementation-ready: every upstream
claim verified or marked, every previously-open decision decided inside the
document, and acceptance criteria complete enough to start implementation
without another design round. Preserve the document's current altitude and
structure — it is a design contract, not a unit-by-unit implementation plan.
Do not restructure it into units. Preserve the user's explicit design
choices: rich numeric support and the integer-versus-floating-point address
distinction are requirements, not defects to optimize away.

## Required reading (in order)

1. `docs/design/dao.jing.cbor.md` — the document you are revising
2. `docs/design/dao.jing.md` — current Jing design; its "Canonical encoding"
   and "Open items and current limitations" sections define what this
   migration supersedes
3. `docs/design/dao.jing.dht.md` — DHT transport semantics and operational
   limits
4. `AGENTS.md` and `docs/design/datom.world.md` — foundations and invariants
5. `docs/design/adr/0001-dao-space-as-storage-boundary.md`
6. `src/cljc/dao/jing*.cljc`, `src/cljc/dao/jing/dht/node.cljc` (read-only)
   — the implementation the design describes

## Input findings to incorporate

An adversarial review (glm-5.3, verdict: ready with corrections) and
orchestrator-verified upstream evidence follow. The orchestrator has
independently verified every upstream fact in the "Evidence pack" against
primary sources; treat those facts as settled. If you introduce NEW claims
about upstream libraries that are not in the evidence pack, label them
"unverified" in the document or the report.

### Review findings — all accepted; incorporate each

P2-1. Numeric carriers lack an `=`/`hash`/`compare` contract, and decoded
carriers flow into ordered consumers (dao.data.btree folds datoms by
`compare`; Datalog unification compares decoded values). Fix in the doc:
add to *Numeric identity* a paragraph requiring carriers to implement
portable `hash`/`=`/`compare` consistently across hosts, with
carrier-to-native-number comparison defined numerically (not type-disjoint);
name the ordered consumers (btree datom ordering, dao.space.query matching)
and add a Node/Dart test scenario exercising decoded numerics in ordered and
matched positions. This also resolves review "unresolved decision 1".

P2-2. Unpaired surrogates diverge across hosts: JVM UTF-8 encoding
substitutes `?` (0x3F); JS TextEncoder and Dart utf8.encode substitute U+FFFD
(EF BF BD). `(materialize! h "a\uDC80b")` would mint different addresses per
host AND decode to a different string than stored while passing
canonicality. Fix: the encoding contract rejects strings containing unpaired
surrogates before encoding, on every host (normal astral text and
noncharacters stay legal); add the fixture to the Unicode scenario. Whether
boring itself pre-validates is an unverified upstream behavior — Jing states
its own rule regardless.

P2-3. Completion-criteria gaps. The required-test-scenario list and the
acceptance paragraph must add: (a) the surrogate fixture; (b) a host float32
widening scenario (the "widen native float32" rule currently has none);
(c) empty collections with and without empty metadata (the "omit empty
metadata" rule is untested); (d) carrier equality/ordering smoke (P2-1);
(e) pathological identifier fixtures named explicitly: `(symbol "42")` vs
integer 42, `(keyword "a/b")` (ns nil, name "a/b") vs `(keyword "a" "b")`,
names containing whitespace or a leading colon; (f) an INJECTIVITY
acceptance criterion — pairwise-distinct supported values (including the
pathological identifier class) must address pairwise distinctly — alongside
the existing byte-identity and encode-decode-encode criteria.

P3-4. File frame: carry the raw 32-byte digest byte string instead of the
`:segment/sha256-...` keyword in the `[digest payload-bytes]` frame record.
The wrapper owns keyword↔digest conversion; replay validation becomes a byte
compare; the file backend stops depending on identifier encoding entirely.

P3-5. Transport limits: name the actual limits (DHT UDP datagram budget of
1200 bytes in `node.cljc` — the only app-level cap; none enforced on the WS
path today), state the effective-budget arithmetic after Base64 (+~33%)
inside a Transit envelope, state the existing local-only degradation for
oversized content on DHT, and decide explicitly whether the WS path gains a
byte cap now that payloads are arbitrary-length byte strings.

P3-6. Canonical re-encode on every read is redundant on the hottest path
(B-tree hydration pays double codec cost per node for nothing once the hash
verifies). Scope the rule: hash-verify every retrieved payload;
canonicality-verify (decode + re-encode byte-compare) exactly once at
ingress of bytes Jing did not encode itself — remote/DHT receipt and file
replay acceptance. State this as the resolved decision (review "unresolved
decision 3").

P3-7. Byte-store put re-verification assignment: the wrapper derives and
verifies addresses; a backend re-verifies exactly when bytes arrive from an
untrusted party (remote/DHT server verifying a client claim). One sentence.

P3-8. Mixed-version peers: record in *Remote and DHT* that new-client to
old-server fails as an address mismatch and old-client to new-server fails
as a Base64 decode error — both loud, never silently misinterpreted — and
that the clean break therefore implies coordinated peer upgrades (no version
negotiation exists).

P3-9. Konserve precedent: qualify it — boring is integrated by Konserve as a
pluggable serializer but currently marked BETA there ("Not yet recommended
for production stores"); Jing's pin plus frozen fixtures, not Konserve's
integration, is what owns byte stability.

### Review "unresolved decision 2" — identifier strategy; resolve it with
the evidence pack

The current text says "Use Boring's identifier mappings where they round-trip
exactly." The evidence pack now pins the native arm precisely (tag 39,
colon-prefixed slash-joined text) and its concrete ambiguity classes. Replace
the vague rule with a decidable per-value predicate defined in the document,
or switch to escaping ALL identifiers into the `dao.jing/keyword` /
`dao.jing/symbol` frames for uniform bytes — choose one, justify it in one
sentence, and specify the other side of the rule (decoding must enforce the
same ordinary-versus-escaped boundary through canonical re-encoding). Either
choice must be decidable identically on JVM, JS, and Dart without ambient
state.

### Architect addition

One sentence in *Layering and interfaces*: the byte-store contract is
effect-payload-shaped (address, bytes → verdict) and therefore convertible
into the deferred effect-stream write path (dao.jing.md "Open items") without
a contract break; the synchronous function handle is present-day shape, not
a commitment.

## Evidence pack — orchestrator-verified upstream facts (settled; cite as
"verified against <source>" in the document where relevant)

All verified 2026-09-16 against: github.com/replikativ/boring (README,
doc/COMPATIBILITY.md, doc/EXTENDING.md, doc/IANA-REGISTRATION.md,
src/boring/writer.cljs, src/boring/records.cljc, src/boring/data.cljc
at main), github.com/replikativ/konserve (src/konserve/serializers.cljc),
clojars.org/org.replikativ/boring, iana.org/assignments/cbor-tags.

1. `org.replikativ/boring` version 0.1.30 exists and is the latest release
   (pushed 2026-09-06; 27 releases total). The design's pin is valid.
2. boring's compatibility policy: released tag meanings are append-only and
   newer readers must decode older output; the policy "does not require a
   new encoder to produce byte-identical output to an older encoder."
   Jing owning frozen fixtures is the correct mitigation — keep it.
3. Profiles: `:clojure` (default, string deduplication, preserves float
   width), `:interop`, `:archival`, `:canonical` (bytewise key order,
   shortest numeric representation), `:canonical-rfc7049` (length-first).
   The README does NOT state whether an explicit option overrides a profile
   default, so the document must instruct Jing to set explicit encode
   options (stringref off, shapes off, no index frame) rather than relying
   on profile selection alone.
4. Under :canonical the float policy is shortest (NaN → f9 7e00, float
   narrowing). Irrelevant to Jing floats because Jing emits NO native CBOR
   floats — all floating-point content rides the opaque 8-byte carrier. The
   document should state that invariant explicitly: the Jing profile emits
   no native CBOR floating-point whatsoever.
5. Identifiers (verified from src/boring/writer.cljs): keywords and symbols
   encode as CBOR tag 39 (TAG-IDENTIFIER) over a text string — keyword
   `:foo/bar` → tag 39, text `":foo/bar"`; symbol `foo/bar` → tag 39, text
   `"foo/bar"` (slash-joined fqn, colon prefix distinguishes keywords).
   Verified ambiguity classes that do NOT round-trip distinctly: symbol
   `(symbol "a/b")` (ns nil) and symbol `(symbol "a" "b")` both print
   `a/b`; a symbol whose str begins with `":"` collides with a keyword;
   `(symbol "42")`-style prints collide with other scalar texts in the
   transitional encoder, and the tag-39 text space shares strings'
   ambiguity for whitespace-bearing names. These classes justify the
   design's `[namespace name]` escape — the document's predicate must be
   defined over exactly this kind of case. Identifier strings participate
   in stringref when enabled (Jing disables stringref, so they do not).
6. Tag 27 (IANA-registered: "Serialised language-independent object with
   type name and constructor arguments", cbor.schmorp.de/generic-object).
   In boring every named frame is tag 27 + 2-element array
   `[name-string, payload]`, and the payload is NOT always a map:
   clojure/queue and clojure/sorted-set write arrays; clojure/with-meta
   writes a 2-element array [meta value]; clojure/ex-info writes a 3-element
   array; records write field maps. So the design's array-argument named
   extensions match boring's own wire grammar.
7. Public API surface for Jing's four named extensions: on WRITE, arbitrary
   tag-27 frames with any payload shape are producible through public
   values — `tagged-literal` with the name symbol and the payload form, or
   UnknownRecord passthrough (non-map fields re-encode byte-identically).
   On READ, the registry maps wire names to constructors; unregistered
   names decode to `boring.data/UnknownRecord` which preserves name and
   payload; decode option `{:on-unknown-record :error}` throws
   `:boring/unregistered-record`. Registration macros in records.cljc are
   record/map-oriented (`map->` constructors), so the document must PIN the
   exact mechanism Jing uses for array-payload names (tagged-literal on
   write + registered constructor or post-decode UnknownRecord conversion
   on read) and carry a fixture for each of the four names.
8. Unknown NUMERIC tags decode to inert `boring.data/TaggedValue`
   (passthrough), NOT an error. Jing's closed decoding therefore needs an
   explicit post-decode rejection of TaggedValue (and any SimpleValue it
   does not define), in addition to `:on-unknown-record :error`. The
   document's rejection rules must name this.
9. Numeric carriers (verified from src/boring/data.cljc):
   `boring.data/Decimal [exponent mantissa]` and
   `boring.data/Rational [numerator denominator]` are defrecords defined on
   BOTH JVM and CLJS (CLJS stand-ins for BigDecimal and Ratio; Rational
   re-encodes as tag 30). There is NO Float64-like wrapper upstream —
   Jing's `dao.jing/float64` carrier is necessarily Jing-owned. The design's
   "reuse Boring's JavaScript decimal/rational carriers" claim is verified.
10. Numeric divergence across hosts (verified from doc/COMPATIBILITY.md):
    "an integral JS number encodes as an integer, while a JVM 1.0 stays a
    float even under :canonical; re-encoding across platforms can therefore
    change signed bytes" — this is boring's own documentation, validating
    the design's float64 carrier motivation.
11. Tags 2/3 (bignum), 4 (decimal fraction), 30 (rational) are
    IANA-registered (RFC 8949 §3.4 / peteroupc CBOR rationals). Tag 256 is
    the stringref marker (Jing's profile disables stringref). Boring's
    shaped-array tag 39649 is provisional and unregistered — shapes stay
    off in Jing. Numeric custom tags are recommended from the IANA FCFS
    range (≥ 32768) and the writer refuses only tags 25/256 — not needed
    if Jing stays with tag-27 names.
12. Konserve integrates boring as serializer id 3 (`BoringSerializer`,
    portable clj+cljs), marked BETA; Fressian remains the default there.
    Serialization is pluggable and decoupled from storage backends — the
    design's "separate serializer" claim is accurate with the beta caveat.

## Editing guidance

- Keep the existing section skeleton; extend rather than reorganize. The
  document may grow (roughly 1.5–2x is acceptable) but stays a design
  contract with the same voice: declarative, terse, no code blocks beyond
  the existing contract shapes.
- Where the evidence pack contradicts the current text (e.g. "Reuse
  Boring's JavaScript decimal/rational carriers" — keep, now verified; the
  identifier rule — replace with the decided predicate), make the document
  state the settled fact and its source in one clause, not a research note.
- The "Community precedent" section should carry the beta qualifier (P3-9)
  and the explicit-options instruction (evidence 3).
- Update "Required test scenarios" and the acceptance paragraph per P2-3.
- Do not add a version header, migration aliasing, or any compatibility
  bridge — the clean break is decided.
- Status line stays "implementation plan; not yet implemented" — readiness
  is the goal of this revision, not a claim that code exists.

## Report

Begin the final response exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07 (Asia/Ho_Chi_Minh)>

Then: a summary of every change made by section; how each numbered finding
(P2-1..P3-9, the two decisions, the architect addition) was incorporated or
why not; any new unverified claims you introduced; and any changes you
believe other documents need (listed, not made).
