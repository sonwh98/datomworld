Created-GMT: 2026-10-20 00:17:14 GMT
Created-Local: 2026-10-20 07:17:14 +07
Coding-Agent: claude
Session-ID: 8c79f881-a441-4e88-9098-a7d3b7a75d13
# Findings: peer codecs for dao.stream (design of dao.stream.codec.md)
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-20 07:17:14 +07 | Status: done | Rationale: resume the architect of the Transit-decoupling ruling; owner widened the requirement

Design only. Written: `docs/design/dao.stream.codec.md` (new, status "design only; nothing commissioned"). Edited: `docs/design/yin.repl-jolt-implementation.md` (third change note; §4.2, §4.4; §9.4 reduced to a pointer plus jolt consequences; §6.4 and ruling 4 per A5; §9.5, §9.6, J6 row per the Autobahn point; §9.6 matrix and ruling 3 per A4; slice table row C0-C4 replaces T0; §10.2; §12; §13.3 items 1-2; ruling 1 now a pointer; appendices). No src edits, nothing staged.

## The design in one paragraph

The two codecs are already shaped alike (one five-key profile map, V). What makes them unequal is three privileges Transit holds: a hard-coded default in five places of `dao.stream.ws` and in all three host adapters; hosting the portable domain that `dao.stream.cbor` and `dao.data` require; and being named by `dao.jing.stream`. The design removes the privileges rather than inventing a framework: a `dao.stream.codec` contract namespace holds the profile gate, the key vocabulary and the base portable domain; each codec requires only that; `dao.stream.ws` requires only that and has no default; the composition passes the profile as data (`:codec`/`:codecs`, mandatory); `dao.jing.stream` decides by one declared capability (`bytes?`) instead of by name. The profile stays six keys and stateless per frame. The wire does not change.

## Decisions worth the owner's eye

- **Resolution is data, not registry or protocol** (CD2). A registry is the ambient default under another name; a multimethod on the name hides the require instead of removing it.
- **One base domain in the contract, superset predicates per codec, a law tested across all codecs** (CD3): for every base value, every codec accepts it and round-trips it. That is the precise content of "swap without breaking callsites": callsites are stream operations and never name a codec; behaviour is identical for base-domain values; for widened values (CBOR metadata, bytes) a composition has chosen its codec; both ends swap together.
- **One capability only** (CD4): `bytes?`, because `dao.jing.stream` needs it today. No domain label, version, schema slot or batching hook: no consumer exists.
- **Profile keys move to `:dao.stream.codec/*`** (CD5). Reason: the profile is now the codec contract for any transport, and naming it after WebSocket is a misnomer. This is the one place I chose the larger diff; the smaller alternative (keep `:ws/*`) is recorded for the owner.
- **Protobuf and Arrow fit without extension** (§5): a codec namespace is a function from parameters to a profile (CBOR and Transit take none), and schema identity goes into the name, where subprotocol negotiation already refuses a mismatch. Their real cost is the §4.4 law (they must also carry base values). Arrow batching is not an extension point: one record batch is one value; batching stream values into frames would change append semantics.
- **Doctrine**: the profile is implementation data under `dao.stream`, adds no operation or outcome, and never reaches the wasm import surface, which keeps Jing's canonical CBOR as its one representation (no second ABI).

## Owner decisions, flagged not made

- OQ1: yin.repl's default profile. Recommendation CBOR; until recorded, C4 passes Transit explicitly so bytes on the wire are unchanged.
- OQ2: whether the 571-line cljd Transit port stays once OQ1 is CBOR.
- CD5 alternative (keep `:ws/*` keys).

## Slices (dao.stream.codec.md §7)

C0 contract namespace and base domain, Transit keeps forwarding vars (astra A2), law test over both profiles. C1 `dao.stream.ws` without Transit require or defaults, key rename, parameterized codec fixture, `dao.stream.ws.md` sentence amended. C2 `dao.jing.stream` by capability, with nil/empty/unknown/valid tests (astra A3). C3 host adapters. C4 `remote-channel` and yin.repl name their codec. C5 owner-gated CBOR default. Each: three lanes green, counts recorded, cross-host peer suites unchanged. J6 depends on C0-C4; nothing else in the jolt plan does.

## Astra corrections folded

A4: the detector has the diagnostic and rejected payload; the peer has only the sender's wire behaviour and one terminal event. My "identical observables at both ends" is withdrawn (§9.6, ruling 3). A5: "targets the newest release tag" was my addition; §6.4 and ruling 4 now say what Q5 says. Autobahn: §9.5 pins release, case list and per-case accepted verdict or listed exception before J6; a run-time reason is a report, not acceptance. A1-A3 addressed the superseded shape; A2 and A3 survive as C0/C2 acceptance.

## Not verified

Nothing was run. Whether Transit encode throws on a record today is R. Node and Dart no-offer refusal is read from source. Per-lane counts belong to the slice reports. The brief's "20 test files" is 22 namespaces by my grep.
