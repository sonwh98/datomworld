Created-GMT: 2026-09-09 10:04:53 GMT
Created-Local: 2026-09-09 17:04:53 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 6378e1b0-1c5d-4e10-8ea0-61551c03c828
# Task: revise the transactor+index plan against the built transport (r3)
Role: Lead System Architect

**The blocker is cleared.** `dao.stream.memory-log` is built, reviewed and
committed (`ba90b3a`), together with three contract commits. Revise your
transactor+index plan against what actually exists. Emit the **whole revised
plan** to stdout.

## What changed under your plan

1. **The P0 your plan did not survive.** Reviewing it found that
   `:dao.stream/oldest` is the earliest *retained* position, not the origin:
   `v2/ringbuffer.cljc:62` mints at `(:first s)`, `:88` reports `gap` only
   when `pos < (:first s)`, `:125` advances `first'` on evicting append. So
   your "read from position zero" invariant was undeliverable, and
   `derive-next-t` would have derived transaction time from a truncated
   suffix silently. Read the committed contract, not your memory of it:
   `2e25b5c`, `ed30d7b`, `ff44107` — *Complete history* under *Retention and
   Gaps*, the two *Cursors* bullets, the retention-predicate absence, and
   `conformance.cljc`'s docstring.

2. **The transport now exists**, as built:
   - `dao.stream.memory-log`, `transport-type` = `:dao.stream/memory-log`
   - `(memory-log/create! {:dao.stream/type :dao.stream/memory-log})` →
     `{:dao.stream/outcome :dao.stream/ok :dao.stream/handle h
       :dao.stream/identity id}`. **No capacity key**; own-namespace,
     unqualified and non-keyword spec keys are `invalid-spec`.
   - Surfaces: reader, writer, closable. `attach!` **absent**, so a host
     dispatch table has nothing to resolve for this type.
   - Excludes `gap`, `full`, `transport-error`, and `invalid-value` on
     `append!`. `next` answers `invalid-cursor` for a position `> tail`,
     deliberately stricter than the ring buffer.
   - `:oldest` is position 0 for the life of the stream, before the first
     append and after close.

3. **The handoff sentence to quote**, which is now true rather than aspirational:

   > The host composition supplies `dao.space` a local handle created by
   > `dao.stream.memory-log/create!`. Its declared complete retention makes
   > fresh `:oldest` cursors true origin cursors for `derive-next-t` and
   > `publish-index!`; supplying an evicting transport is a host-assembly
   > defect.

## Required revisions

- **Delete the capacity-hazard section entirely**, including
  `retained-history-survives-reopen-at-capacity`. It existed to manage 26
  ring-buffer sites and pick a capacity "big enough"; that test would have
  pinned a defect as a feature. The answer is the right transport, not a
  number. Say what the 26 sites become instead: they are `dao.space` tests
  opening a local stream, and they now open a `memory-log`.
- **Restate the from-origin claims** on the transport's declared retention
  rather than on an anchor. `derive-next-t` and `publish-index!` are correct
  because the transport cannot evict, not because they mint `:oldest`.
- **`snapshot-datoms` becomes a v2 read.** Say exactly what it is now — it no
  longer needs a gap policy, because `gap` cannot occur on this transport.
  Whether its throw survives as defensive code is your call; say which and why.
  **Do not introduce `observe/drain` or any shared retention law**: the
  generic law is deferred until a second complete-history transport exists,
  and `snapshot` keeps its name if ever shared.
- **Re-check every phase** against the transport's actual surface, especially
  the transactor's `create!`-shaped constructor now that the local stream is a
  `memory-log` handle rather than an opened v1 descriptor.
- **Keep Phase 5 and the index close.** The owner took it: index ends v1-free
  in this sweep.

## The six findings from the plan's own review still stand

`collab/1788874517712-review-space-transactor-index-plan.gpt-5.6-sol.findings.md`
— the schema-state-advances-on-failed-append P1, the unclosed Phase 3 test
inventory, the stale closedness predicate in `schema/publish!`, the missing P5
failure-path test, the capacity accounting contradiction (now moot), and the
documentation closure including `docs/dao.space.stigmergy.md` and ADR 0003.
Address each; the review is authoritative where it differs from your r2.

## Constraints unchanged

Three lanes green at the end of every phase; `public/demo.html` verified by
compiling `:demo`; `dao.space.schema`'s own migration stays its own plan.
