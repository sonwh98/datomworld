# Slice C4 architectural review (claude-fable-5-1)

Created-GMT: 2026-10-21 04:20:00 GMT
Worktree: `/Users/sto/workspace/datomworld-codec-c4`, branch `codec-c4`, uncommitted (9 files, +48/-76 on `6b21521c`)
Governing: `docs/design/dao.stream.codec.md` §3.2, §3.3, §7.1 C4, §7.3, §9 CD5, §10 OQ3
Engineer report: `collab/1792541000000-engineer-codec-c4.claude-sonnet-5-5.findings.md`

## Verdict: SIGNED OFF WITH CHANGES

The code is correct and does exactly what §7.1 C4 specifies under the key
move. The required changes are three stale docstrings (R1–R3), the §10
OQ3 record that the design doc itself says must exist before C4 lands
(R4), and two acceptance runs that §7.1 C4 names and nobody has run yet
(R5, R6). None of them touches the shape of the change. R1–R4 are edits;
R5–R6 are runs whose results go in the slice report. Commit after R1–R4
are applied and R5–R6 are recorded green; no second Architect pass is
needed unless R5 or R6 fails.

## What I verified myself

| Check | Result |
|---|---|
| `bb test:sub:clj dao.stream` in the worktree | 35 namespaces, 503 tests, 0 failures, 15 s (matches engineer and brief) |
| `clj -M:kondo` over the 8 changed files | 0 errors, 4 warnings, all four pre-existing on `HEAD` (verified by linting `git show HEAD:` content): `codec.cljc:70` unused `m`, `codec_fixtures_test.cljc:109,127` unused private vars, `ws/jvm_test.clj:501` httpkit ns |
| Grep gate `':ws/(frame-kind\|portable-value\?\|encode\|decode\|bytes\?)'` over `src/ test/` | 12 hits, every one the event-reason keyword `:ws/decode-failure` (the regex matches it as a prefix of `:ws/decode`). No profile key survives. The brief's "completely clean" overstates; the engineer's "empty except `:ws/decode-failure`" is the accurate statement and the slice report should use it |
| `:ws/subprotocol` in `src/` | Only the four seam sites: `ws.cljc:624,632`, `jvm.clj:353`, `node.cljs:187`, `dart.cljd:103`. Exactly what §3.2 and §7.1 C4 permit |
| `:ws/subprotocol` in `test/` | Seam maps in the ws tests and `codec_fixtures_test.cljc:187`; three deliberately non-profile maps used as rejection probes (`codec_test.cljc` legacy fixture, `ws_codec_test.cljc:235`, `jing/stream_test.cljc:94`). All legitimate: a bare `{:ws/subprotocol ...}` is now the canonical "not a profile" value and the gate rejects it |
| Gate tests rewritten, not deleted | 4 gate deftests before and after (`profile-gate-accepts-*`, `profile-gate-rejects-malformed`, `profile-gate-rejects-non-token-names`, `accessors-read-*`), 25 `is` assertions before and 25 after in that region. The engineer's report says "5"; the count that matters, assertions, is equal either way. Record "4 deftests / 25 assertions" in the slice report |
| Lane counts vs C3 | JVM 503 = C3 503; Node 506 = C3 506 (from the C3 fixes report). Dart 435 has no C3 baseline in the collab reports I could find; the brief reports it green for every codec/cbor/transit/ws namespace with the one known Linux datagram gap |
| Every consumer reads through accessors | `ws.cljc`, the four adapters and `dao.jing.stream` call `codec/codec-name`, `codec/frame-kind`, `codec/profile?`, `codec/bytes?`; no consumer reads a profile by key. Deleting the fallback therefore changes no consumer path, as §7.1 says ("C4 deletes keys nobody reads") |

## Compliance with §7.1 C4 under the CD5 move

| Requirement | Status |
|---|---|
| Both profiles drop the `:ws/*` keys | Done. `cbor.cljc:109-114`, `transit.cljc:109-114` carry the six `:dao.stream.codec/*` entries only |
| Accessors become single lookup | Done. `lookup` deleted; five accessors are one keyword get each; `bytes?` unchanged (`false` when absent, so a non-profile still gains no capability) |
| `profile?` accepts the `:dao.stream.codec/*` set only | Done. Six-key gate per §3.2: token name, frame kind in `#{:text :binary}`, three fns, explicit boolean `bytes?` |
| Gate tests rewritten: "other set accepted" becomes "rejected", fallback becomes single lookup, counts equal and recorded | Done in substance (see table above). Recording the count in the slice report is R7 (trivial) |
| Grep gate empty | Met, with the `:ws/decode-failure` caveat above |
| `:ws/subprotocol` only on seam maps | Met |
| Fixtures pass | Met on JVM (in the 35 namespaces I ran) and, per the brief, on Node and Dart |
| §7.3 bidirectional old/new check runs again | **Not run.** The engineer's report says so explicitly; the brief does not claim it. R5 |
| Slow tier once | **Not run.** Neither report mentions it. R6 |
| OQ3 "resolved and recorded in §10" | **Not recorded.** §10 OQ3 still reads as open ("The owner must record a choice before C4 is commissioned"). The engineer brief states "OQ3 moves the profile keys (CD5 confirmed)", so the decision exists; the doc does not carry it. R4 |

## Required changes

R1. `src/cljc/dao/stream/codec.cljc:1-5` ns docstring still says the accessors
"read the `:dao.stream.codec/*` keys and fall back to the legacy `:ws/*`
keys". There is no fallback. Replace with: "profile accessors that read the
`:dao.stream.codec/*` keys".

R2. `src/cljc/dao/stream/codec.cljc:143` `profile?` docstring says "seven
`:dao.stream.codec/*` keys". There are six (§3.2 table; the gate checks
six). The "seven" comes from the engineer brief, which listed a
`:dao.stream.codec/subprotocol` key that does not exist and was rightly
not implemented. Write "six".

R3. `test/dao/stream/codec_test.cljc:3` ns docstring says "the dual-key
profile gate". Write "the single-key profile gate" (or "the profile gate").

R4. Design doc, before commit (Architect-owned text, orchestrator to apply
with the owner's words):
  - §10 OQ3 recommendation cell: prepend `RESOLVED 2026-10-21 (owner): move
    the keys to `:dao.stream.codec/*` (CD5 stands); C4 implemented under the
    move.` and keep the rest as history, as OQ1's row does.
  - §9 CD5 status cell: `Architect, 2026-10-20; owner confirmed via OQ3
    2026-10-21`.
  - §3.2 "Accessors, and the two key sets during migration" paragraph:
    add one leading sentence `(Historical: this describes C0–C3; C4 removed
    the second key set, the fallback and the dual gate on 2026-10-21.)`.
    Do not rewrite the paragraph; it is the record of why the slices were
    green.
  - §11 line 964/972 "C4 waits for OQ3" sentences: append "(resolved, C4
    landed)" or equivalent. One-line edits.

R5. Run the §7.3 bidirectional old/new check and record it in the slice
report, exactly as C2 did (`collab/1792495500000-engineer-codec-c2...findings.md`
§"7.3 bidirectional old/new peers"): old peer = tag `f0-old-peer`
(`47f43405`) in a sibling worktree; JVM↔JVM both directions for both
profiles via the peer driver; Node↔JVM via
`DAO_STREAM_PEER_DIR=<old-worktree> bb test:sub:cljs dao.stream` with the
negative control (`DAO_STREAM_PEER_DIR=/nonexistent` must print SKIPPED for
the four cross-node proofs); Dart old/new coverage reported **absent** by
name if the peer binary is not built, never as covered. This is the one
place a key rename could have reached the wire (the subprotocol string an
adapter puts on the socket now comes only from `:dao.stream.codec/name`),
and §7.1 C4 requires the run under both OQ3 answers.

R6. Run the slow tier once for the subsystem (`bb test:sub dao.stream
--slow`, or `bb test:slow` if the orchestrator prefers the global form) and
record counts per lane. §7.1 C4: "Slow tier once." §7.2 test-tier cadence
also requires it at a phase boundary, and C4 closes the codec track.

R7. Slice report: record "gate tests 4 deftests / 25 assertions before and
after" and the lane counts per lane (JVM 503, Node 506, Dart 435) beside
the C3 counts (503, 506, Dart baseline to be named).

## Non-blocking notes (P3, apply or not with R1–R3)

N1. `cbor.cljc:106` and `transit.cljc:106` profile docstrings open with "The
`dao.stream.ws` codec profile". After CD5 the profile is the codec
contract for any framing transport (§3.2 "Why move the keys"). "The
`dao.stream.codec` profile" is the accurate phrase. Cosmetic.

N2. `codec_test.cljc:52` `(assoc legacy :ws/subprotocol n)` is now a
tautology: the legacy fixture is rejected whatever `n` is, so the
assertion no longer tests token validation. It is harmless and preserves
the assertion count the slice is required to keep. If the engineer wants
it to mean something again, `(assoc (modern false) :ws/subprotocol n)` is
still accepted (the stray key is ignored), which is a more useful
single-lookup proof. Not required.

N3. `ws.cljc:26-32` `codec-profile?` docstring describes the profile in
WebSocket terms. It is `dao.stream.ws`'s own view of the gate and
delegates to `codec/profile?`; acceptable as is.

N4. Engineer brief drift. The brief named `ws_codec_test.cljc`, `ws.cljc`
accessors, `:ws/encode-fn`, `:dao.stream.codec/subprotocol`, and a
seven-key profile. None exists; the engineer followed the repo and the
design doc and said so. Correct call. Future briefs for this track should
be cut from §3.2 and §7.1 directly, not retyped.

## What this slice does not change

No admission predicate, no encoder, no decoder, no wire byte. The CBOR
fixture re-encode check and the Transit decode check pass in the fixture
test on all three lanes. The only observable difference is that a map
complete under the old `:ws/*` set is now refused by `profile?`, which is
the slice's purpose and has no caller in the tree (every composition was
migrated in C2 and every profile in the tree is a modern one).

## Known-gaps ledger

Nothing new. The Dart Linux datagram timeout (`known-gaps.md:31`) is
unrelated to this slice. Dart old/new peer coverage remains absent, as it
was at C2; it is a §7.3-permitted recorded gap, not a C4 regression.
