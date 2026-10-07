Created-GMT: 2026-09-09 10:20:13 GMT
Created-Local: 2026-09-09 17:20:13 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 6378e1b0-1c5d-4e10-8ea0-61551c03c828
# Task: validate result shapes, and three corrections (r4)
Role: Lead System Architect

Review: `collab/1788948769084-review-space-transactor-index-plan-r2.gpt-5.6-sol.findings.md`
Your Phase 2 deletion, the residual-risk statement and its placement, and the
S5/T4b deletions are all **confirmed sound**. Four corrections. Emit the whole
revised plan to stdout.

## P1-1 — the new loop can hang, where the old one threw

`snapshot-datoms` as you wrote it checks `(:dao.stream/outcome r)` and, on
`ok`, immediately takes `:dao.stream/value` and recurs with
`:dao.stream/cursor`. I verified the consequence: `MalformedResultStream`
(`index_test.cljc:25`) returns its configured result on **every** read, so an
`ok` carrying no cursor makes the loop recur with `nil`, receive the same
result, and **spin forever**. `index_test:395` passes today and would hang the
suite. A stateful double answering `blocked` next would instead accept
malformed input silently.

Required — and note this does **not** reopen the Phase 2 deletion:

- validate the mint with `stream/validate-outcome :cursor` before taking its
  cursor;
- validate every read with `stream/validate-outcome :next` before the outcome
  `case`;
- throw a malformed-result exception carrying the raw result and the
  validation defect;
- only then interpret `ok`/`blocked`/`end`, keeping the totality throw for a
  well-formed but unexpected outcome.

`validate-outcome` is at `src/cljc/dao/stream.cljc:245`. It is **contract
machinery, not query policy** — sharing it is not sharing `query/snapshot`.
Require a v2 reader double whose repeated `ok` lacks `:dao.stream/cursor`,
which must terminate by throwing.

## P1-2 — the plan contradicts itself on P8

Line 657 keeps `covered-indexes-returns-the-four-covered-sets`, moving only
its adapter open. Lines 670-675 say Phase 3 "removes the eight deftests
above". Both cannot be followed. Say: **seven** adapter-oriented deftests
leave `index_test`; the `covered-indexes` deftest stays with its structural
cases, and its opened-value assertion moves to `query_test`.

## P2-1 — Phase 1 is not behaviour-neutral, so stop claiming it is

Draining all elements and then flattening changes observable failure
ordering: today a malformed *element* throws immediately; after the change a
later malformed *stream signal* can win first, and reads happen past the
point the old implementation stopped. You also mark S8 `[T✗]` without the
reason the method requires.

Do not incur the change. Expose and test `datoms-from-elements`, but keep
`snapshot-datoms` calling `element-datoms` **incrementally inside its read
loop**, and have the Phase 2 v2 loop do the same. Then Phase 1 genuinely is
behaviour-neutral and S8 need not be dropped at all.

Also: either document `datoms-from-elements` on the public surface in Phase 1,
or keep it private until Phase 2. Do not add a public function in one phase
and document it in another.

## P2-2 — "durable truth" is now false

`memory-log` does not survive process restart, so `derive-next-t`'s rationale
cannot call the log "the only durable truth". The architectural point stands:
it is the **authoritative retained truth for the logical stream's lifetime**,
and the watermark is derived state. Fix it wherever it appears, and make sure
the permanent transactor documentation you add does not reintroduce the
durability claim.

## One thing to strengthen while you are in there

The reviewer accepts your durable placement for the wrong-transport residual
risk — `dao.space.index.md`, `dao.space.md`'s write path, the permanent
transactor section, and the ADR amendment's pointer. It adds a requirement:
each must **name the concrete required transport** and state plainly that a
reader/writer surface check does not establish retention. Make sure all four
do that, not merely gesture at completeness.
