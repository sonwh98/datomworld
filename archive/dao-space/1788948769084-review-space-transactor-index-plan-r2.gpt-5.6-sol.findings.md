I’m using the repository’s code-review checklist and will trace the revised three-phase edit closure against the current transactor, index, schema, and tests. I’ll pay particular attention to the deleted snapshot abstraction, the undetectable miswiring risk, and whether every removed test obligation has an explicit durable discharge.

The phase structure is mostly closed, and the decision not to promote a generic snapshot loop is sound. I did find a concrete Phase 2 failure: the proposed specialized loop branches on `:dao.stream/outcome` without first validating required result keys, so the migrated malformed-result test can loop forever instead of throwing. I’m finishing the deletion and documentation accounting around that blocker.

Completed-GMT: 2026-09-09 10:18:00 GMT  
Completed-Local: 2026-09-09 17:18:00 +0700 (Asia/Bangkok)  
Coding-Agent: codex  
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0

# Architect review — transactor + index migration plan r3

## Verdict

**Not ready to implement.** The transport correction and three-phase architecture are sound, but Phase 2 lacks required v2 result-shape validation and can hang on an existing malformed-reader case. Two smaller internal contradictions should also be corrected before briefing an implementer.

## Findings

### P1 — `snapshot-datoms` interprets v2 results before validating their shape

**Plan:** `collab/1788948293243-architect-space-transactor-v2-plan-r3.claude-fable-5-1.findings.md:220-245,476-478,670-672`  
**Blocks:** Yes

The proposed loop checks only `(:dao.stream/outcome r)`. On `ok`, it immediately consumes `:dao.stream/value` and recurs with `:dao.stream/cursor`.

That does not preserve the existing malformed-result contract. The current `MalformedResultStream` returns the configured result on every read. After migration, if it returns:

```clojure
{:dao.stream/outcome :dao.stream/ok
 :dao.stream/value valid-datom}
```

with no required cursor, the proposed loop recurs with `nil`, receives the same malformed `ok` again, and loops indefinitely instead of throwing. A stateful reader could instead return `blocked` next, causing malformed input to be silently accepted.

Concrete change:

- Validate the cursor result with `stream/validate-outcome :cursor` before extracting its cursor.
- Validate every read with `stream/validate-outcome :next` before the outcome `case`.
- Throw a malformed-result exception containing the raw result and validation defect.
- Only then interpret the three reachable outcomes `ok`, `blocked`, and `end`; retain the totality throw for other well-formed outcomes.

This does not require sharing `query/snapshot`. It shares only the contract’s existing result validator and preserves index’s narrower policy.

Add or preserve a v2 reader-double test whose repeated `ok` result lacks `:dao.stream/cursor`; it must terminate by throwing.

### P1 — Phase 3 contradicts its own P8 test disposition

**Plan:** lines `657` and `670-675`  
**Blocks:** Yes

The accounting table says `covered-indexes-returns-the-four-covered-sets` is **kept**, with only its adapter open moved. The residue section then says Phase 3 “removes the eight deftests above.”

Those instructions cannot both be followed. Removing all eight would delete the structural P8 cases that line 657 explicitly preserves.

Concrete change: say that seven adapter-oriented deftests leave `index_test`, while the `covered-indexes` deftest remains with its structural cases. Its opened-value assertion moves to `query_test`.

### P2 — Phase 1 is not actually behavior-neutral

**Plan:** lines `420-443`, invariant S8 at `401`  
**Blocks:** Recommended to correct before implementation

Changing from validate-and-flatten each element while reading to drain-all-elements-then-flatten changes observable failure ordering.

For example, if an early element is malformed and a later read returns a malformed signal:

- today, the malformed element throws immediately;
- after the proposed change, the later stream signal can win before element validation begins.

It also performs reads after the point where the old implementation stopped. Thus “no behaviour change” is false, and S8 is marked `[T✗]` without the promised reason for dropping it.

There is no need to incur this change. Expose and test `datoms-from-elements`, but keep `snapshot-datoms` calling `element-datoms` incrementally inside its read loop. The Phase 2 v2 loop can do the same.

Additionally, Phase 1 adds a public function while postponing its public-surface documentation until Phase 2. Either document it in Phase 1 or keep it private until Phase 2.

### P2 — “durable truth” contradicts the selected transport

**Plan:** lines `177-180`  
**Blocks:** No

`memory-log` explicitly does not survive process restart, so calling it “the only durable truth” is false. The underlying architectural point remains valid: the log is the authoritative truth for that process-lifetime logical stream, and the watermark is derived state.

Use “authoritative retained truth” or “sole source of truth for the logical stream’s lifetime.” Ensure the new permanent transactor documentation does not reintroduce the durability claim.

## Judgments requested

### Phase 2 deletion

**Sound.** `query/snapshot` and `index/snapshot-datoms` have materially different policies:

- query must preserve every stopping condition as data for an unknown reader;
- index consumes a composition-required complete-retention log and treats anything outside `ok`/`blocked`/`end` as a violated precondition.

A shared loop would either leak query’s status vocabulary into index or require policy injection larger than the duplicated mechanism. Ten or so lines do not justify that abstraction yet.

The implementation must still use the shared `stream/validate-outcome` primitive. Outcome-shape validation is contract machinery, not query policy.

### Wrong-transport residual risk

The plan states the risk accurately. A fresh ring-buffer `:oldest` cannot reveal prior eviction, and a runtime retention predicate is deliberately absent. Index cannot detect this misassembly internally.

Its proposed durable placement is sufficient:

- `dao.space.index.md`;
- `dao.space.md`’s write path;
- the permanent transactor design section;
- the ADR amendment’s pointer to complete retention.

Those documents should name the concrete required transport and state plainly that a reader/writer surface check does not establish retention.

### S5 and T4b deletion

The deletion is justified:

- Both tests deliberately construct the composition now classified as invalid.
- Their old gap behavior depended on v1 position-zero semantics.
- A fresh v2 `:oldest` cannot reproduce the claimed protection.
- `memory-log` excludes `gap` structurally and its own reviewed tests prove retained-origin replay.
- The surviving totality branch still rejects a well-formed unexpected outcome; after the P1 correction, malformed results also throw.

No replacement consumer-level gap fixture is owed.

## Cleared

- The capacity section and impossible regression test were correctly removed.
- The 28/27 site accounting is now correct.
- Phase 2’s edit set includes the necessary transactor tests, doubles, schema bridge, query helper, index helper, and other local-stream callers.
- T19 correctly prevents schema state from advancing on non-`ok`.
- `SchemaWrapper.close!` preserves `{:woke []}`.
- Moving schema publication’s guard under the existing lock removes the stale-predicate race; the stated index-build lock cost is honest.
- P5 now has a real failure-path ownership test.
- Phase 3’s eager schema opener correctly uses `try/finally` and closes the store after `read-datoms`.
- ADR 0003 should be amended, not superseded.
- The residue greps are appropriate phase-closure checks.
- No tests were run.

After adding v2 result validation and resolving the Phase 3 test contradiction, the plan is executable. The Phase 1 behavior/documentation and durability wording should be cleaned up in the same revision so no transient-plan ambiguity reaches the implementer.
