I’m using the repository’s code-review checklist and will inspect the implementation, manifest, and assertions directly. I’ll focus especially on manifest exclusions, `swap!` retry behavior, cursor validation across instances, and whether the tests assert—not merely execute—the retention properties.

The core state machine and retry behavior are sound: the `volatile!` records the branch taken by the successful `swap!` attempt, so append/close races linearize correctly. I found one envelope-validation edge outside the transport’s internal state machine and am checking it against the contract wording before assigning severity.

Completed-GMT: 2026-09-08 15:24:00 GMT  
Completed-Local: 2026-09-08 22:24:00 +0700 (Asia/Bangkok)  
Coding-Agent: codex  
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0

# Routine Review — memory-log implementation

## Verdict

**One contract-validation defect blocks the commit.** The transport state machine, manifest exclusions, concurrency behavior, retention tests, and portability otherwise check out.

### P1 — Malformed creation-spec keys are silently accepted

**File:** [memory_log.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/memory_log.cljc:47)  
**Blocks commit:** Yes

`valid-spec?` rejects keys in `dao.stream.memory-log` but accepts every other key, including unqualified keywords and non-keyword keys:

```clojure
(log/create! (assoc spec :capacity  multiline))
(log/create! (assoc spec "capacity" 3))
```

Both currently return `ok`.

The contract says every non-`:dao.stream/type` creation-spec key is qualified, and the open-map rule only requires consumers to ignore unknown **qualified** keys. An unqualified or non-keyword entry is therefore a malformed specification, not a foreign extension.

Concrete change:

- Permit `:dao.stream/type`.
- Reject every other key that is not a qualified keyword.
- Reject qualified keys whose namespace is `dao.stream.memory-log`.
- Continue ignoring other qualified keys.
- Add tests for an unqualified keyword and a non-keyword key returning `invalid-spec`.

## Confirmed correct

- **Manifest exclusions:** `gap`, `full`, `transport-error`, and `invalid-value` have no result-producing path. `append!` either adds the supplied host value, returns `closed`, or fails to return at the host-runtime level.
- **Complete retention:** state contains one vector and no operation removes, truncates, replaces, or rebases retained values. `:oldest` always denotes position zero.
- **`next` totality:** validation precedes `nth`; malformed positions cannot reach indexed access. A cursor from another memory-log instance returns `cursor-mismatch`.
- **Snapshot consistency:** `next` derives identity, tail, closedness, and the indexed value from one immutable atom snapshot.
- **Linearization:** the append linearization point is the successful `swap!` CAS. If its function retries, the volatile is overwritten by the final successful attempt:
  - append wins before close → `ok`;
  - close wins first → retry observes closed and reports `closed`.
  A later close occurring before `append!` returns does not invalidate `ok`; the append already linearized first.
- **Single-threaded hosts:** the same implementation is valid on CLJS and CLJD; retries are unnecessary there but do not change the outcome logic.
- **Reader conditionals:** the JVM concurrency block uses `#?(:cljd nil :clj …)`, avoiding the CLJD `:clj`-feature trap. Transport source has no reader conditionals.
- **Retention assertions:** the origin cursor is bound before the append expression; all 1,000 append outcomes are asserted `ok`; the exact 1,000-element sequence contains 500 interleaved `nil`s; kept and fresh-origin replays are checked before and after close; terminals and absence of `gap` are asserted.
- **Totality tests:** cover non-maps, missing fields, non-integers, negative and beyond-tail positions, foreign identity, open/closed tail behavior, and post-close cursor minting.
- **Conformance documentation:** the exclusion-principle addition is accurate and introduces no executable behavior.
- **Contract amendment:** matches the previously approved distinction between reportable exhaustion and fatal host failure.
- **No generic retention machinery:** its absence matches the final approved scope; the transport-specific test supplies the intended evidence.

The implementer report overstates the tested append-value variety slightly—the retention vector contains integers and `nil`, while other fixtures append keywords, not strings. This does not affect correctness because the absence of value validation or encoding is structural.

After tightening creation-spec key validation and adding those two assertions, the code is statically ready. Final commit readiness also remains contingent on the owner’s still-running CLJD suite completing successfully.
