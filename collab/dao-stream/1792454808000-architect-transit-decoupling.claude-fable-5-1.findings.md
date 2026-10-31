Created-GMT: 2026-10-20 00:06:48 GMT
Created-Local: 2026-10-20 07:06:48 +07
Coding-Agent: claude
Session-ID: 8c79f881-a441-4e88-9098-a7d3b7a75d13
# Findings: redesign of the dependency on dao.stream.transit (slice T0)
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-20 07:06:48 +07 | Status: done | Rationale: resume the architect that ruled on the Transit point; owner redirected it

Design only. Edited: `docs/design/yin.repl-jolt-implementation.md` (change note; §4.2, §4.4; §9.4 rewritten as 9.4.1-9.4.4; §9.6; new slice T0 in §10.1; §10.2; §12; §13.3; §13.4 ruling 1 replaced; Appendix A W2; Appendix B). No src edits, nothing staged.

## Inventory (V)

Requirers in `src/`: `dao.stream.ws:17` (Transit as default codec in five places: `checked-codec:44`, one-arity `descriptor?:75`/`servable-descriptor?:92`, `make-endpoint:557`, no-offer arm of `accept-connection!:647`); `dao.stream.cbor:20` (`safe-number?` only); `dao.jing.stream:26` (Transit's subprotocol string only); `dao.data:11` (`portable-value?` only); the three host adapters `ws/jvm.clj:25`, `ws/node.cljs:36`, `ws/dart.cljd:22` (`default-codecs [transit/profile]` plus a `ws/subprotocol` fallback). The decisive find: `dao.stream.remote-channel` (the production composition under `yin.repl.serve:240` and `yin.repl.connect:302`) passes no codec to `make-endpoint:339`, `(:bind! host):385` or `make-attacher:823`, so the whole yin.repl network path rides on the Transit default today. 22 test namespaces require Transit explicitly, which is fine; ~14 rely on a default (listed in §9.4.3).

## The design (reading A, all lanes)

1. `dao.stream.portable` holds the codec-independent v2 domain (`max-safe-integer`, `safe-number?`, base `portable-value?`, `validate-portable`, `valid-descriptor?`). `dao.data`, `dao.stream.cbor`, `dao.stream.transit` require it. Transit keeps its own `portable-value?` wrapping the base with its cljd decoder-record rejection, so its profile predicate is unchanged.
2. Profiles stay plain data resolved by the composition; no registry (a second place to be wrong). `dao.stream.ws` drops the Transit require and every default: `:codec`/`:codecs` mandatory, two-arity descriptor gates only, a seam without `:ws/subprotocol` is `:ws/unsupported-subprotocol`.
3. Wire names become constants in `dao.stream.ws`; `dao.jing.stream` compares to them.
4. Host adapters drop `default-codecs` and fallbacks; `remote-channel` threads `:codecs`/`:codec` from its spec and refuses `::no-codec`; `yin.repl.serve`/`connect` pass a profile explicitly.
5. Which profile yin.repl selects is the owner's (flagged, §13.3). Until recorded, T0 passes the Transit profile, so behaviour is byte-identical to today. Recommendation only: CBOR.

Readings B and C: B (ws only) leaves cbor/jing.stream/data and the adapters coupled, so jolt still loads `cognitect.transit` via `dao.data`. C is the superseded ruling: hides the coupling on one host. Neither satisfies the owner's words.

## Consequences

Wire unchanged: every host listener already refuses a no-offer upgrade (V: `jvm.clj:413-436`, `node.cljs:274-278`, `dart.cljd:226-234`), so removing the in-process default changes direct compositions and tests only. `dao.stream.ws.md`'s "absent name is the transit-json default" sentence must be amended to "refused" as part of T0 (that doc was outside my edit scope). Behaviour change is composition-time only. Risk is breadth, not semantics; gate each step on unchanged lane counts. Jolt: the empty `:jolt` arm disappears entirely; `dao.stream.transit` is simply not in the jolt closure. J6 depends on T0; J0a-J5 do not.

## Slices

T0 in four steps, smallest first (§10.1): extract portable; strip ws defaults plus tests; adapters; remote-channel plus yin.repl. Gates: `grep dao.stream.transit src/` hits only the Transit namespace and its cljd port; three lanes green with recorded counts; cross-host peer suites unchanged; codec-less composition tests added.

## Reversal

An owner instruction that a shared default must exist (it would then be a named yin.repl composition value, never in `dao.stream.ws`), or evidence that a landed peer or store depends on the in-process default (none known; no deployed stores).

## Not verified

Nothing was run. The cljd nuance in `dao.data` (base predicate no longer rejects transit-decoder records) is R; per-lane test counts are for the T0 report. Node/Dart no-offer refusal is read from source, not exercised.
