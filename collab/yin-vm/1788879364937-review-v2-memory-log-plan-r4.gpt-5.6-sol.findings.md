I’m checking the r4 control flow end-to-end—population, cursor minting, replay, and close—to see whether any observed operation result can still bypass the manifest declaration. I’ll also assess whether the now-strict fixture values unnecessarily exclude a conforming transport.

Completed-GMT: 2026-09-08 14:56:19 GMT  
Completed-Local: 2026-09-08 21:56:19 +0700 (Asia/Bangkok)  
Coding-Agent: codex  
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0

# Architect Review — memory-log plan r4

## Verdict

**The append manifest gate is correct, but the generic retention law is still not ready.** The same omission remains for operations performed during cursor minting, replay, and closing.

Given four rounds and that only one complete-history transport exists, I recommend the fallback you proposed: implement `memory-log` with its transport-specific retention test and defer `run-retention-laws` until a second transport supplies a real generalization target.

## Findings

### P1 — Only append results are gated against the manifest

**Location:** `collab/1788879179820-architect-v2-memory-log-r4.claude-fable-5-1.findings.md:182-224,232-254`  
**Blocks the generic law:** Yes  
**Blocks the memory-log transport:** No

The new append gate fully closes the previous finding. It correctly rejects undeclared `full`, `invalid-value`, and `transport-error`, and distinguishes declared refusal from setup failure.

However, the law also directly invokes:

- `cursor` when minting the kept and fresh `:oldest` cursors;
- `next` throughout every replay;
- `close!` before post-close replay.

Those results are not described as being checked against their respective `:produces` sets before interpretation.

A concrete false-positive remains:

1. A complete writer’s manifest excludes `blocked` from `next`.
2. Its implementation nevertheless returns `blocked` at the open tail.
3. The law expects `blocked`, so its terminal check passes.
4. The law has passed while directly observing an excluded outcome.

Equivalent cases exist for:

- an undeclared `ok` from `cursor`;
- undeclared `ok`, `blocked`, or `end` during replay;
- reader-only `:terminal` naming an outcome excluded by `next`;
- undeclared `ok` from `close!`.

The prose at line 286 states the correct general rule, but the proposed law applies it only to append.

A genuinely generic law would need a shared observation gate for every operation it performs:

- verify the outcome belongs to that operation’s manifest `:produces`;
- verify the result satisfies `v2/valid-outcome?`;
- only then interpret it semantically.

### P2 — The population sketch treats `nil` as end-of-input

**Location:** `collab/1788879179820-architect-v2-memory-log-r4.claude-fable-5-1.findings.md:201-203`  
**Blocks the memory-log:** No  
**Blocks the sketch as a generic implementation:** Yes

The loop destructures `[v & more]` and terminates on `(nil? v)`. Consequently, a `nil` entry—valid for an unrestricted host-value transport—is mistaken for exhaustion, and values after it are never attempted.

Iterate based on whether the remaining sequence is empty, not whether its first value is `nil`, for example by testing `(seq remaining)` before destructuring.

## Confirmed correct

- The append manifest gate closes the reported P1 for population.
- Structured failure data is the right replacement for metadata.
- Declared `full` correctly stops population and replays the accepted prefix.
- Declared `closed` or `transport-error` on a fresh handle correctly fails setup.
- Requiring `:retention-values` to contain accepted values is sound. It does not exclude restricted domains; their manifest supplies values inside that domain.
- Treating `invalid-value` as `:retention-values-rejected` is therefore correct.
- Requiring non-empty reader-only `:expected` closes the vacuous-fixture issue.
- The five proposed falsification tests correctly cover the append-specific correction.

## Recommended implementer brief

Proceed with the contract amendment and `dao.stream.memory-log`, but defer the generic retention law and its synthetic falsification machinery.

The brief should require the transport-specific test to prove:

- an origin cursor is minted before any append;
- every test append returns `ok`;
- a substantial sequence, including `nil`, is retained exactly and in order;
- both the kept origin cursor and a fresh `:oldest` replay the entire history;
- neither replay observes `gap`;
- the open tail returns `blocked`;
- both replays still work after `close!` and terminate with `end`;
- the memory-log manifest’s exclusions remain supported by structural and transport-specific tests.

The exclusion-principle documentation is independently sound and may still land.

With the generic law removed or explicitly deferred, **the memory-log transport plan is ready to implement**.
