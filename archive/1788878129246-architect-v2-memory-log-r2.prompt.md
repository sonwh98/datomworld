Created-GMT: 2026-09-08 14:35:29 GMT
Created-Local: 2026-09-08 21:35:29 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 0dae45ea-4204-49d9-bf35-c56e24cce14e
# Task: revise the memory-log plan against review (r2)
Role: Lead System Architect

Review verdict: **request plan revision before implementation.** The transport
choice and state model are confirmed correct; six findings must be resolved.
Full review:
`collab/1788877651728-review-v2-memory-log-plan.gpt-5.6-sol.findings.md`

Emit the **whole revised plan** to stdout.

## P1-1 — you were right about `transport-error`; the contract is amended

The reviewer ruled in your favour and **I have already amended
`docs/design/dao.stream.md` in the working tree** (uncommitted) — read it
there. But it also improved the argument, and your §3 should adopt the better
one. Your "the heap is not the transport's medium" is debatable, since the
vector does live on that heap. The distinction that actually holds is between
**a failure the operation can observe and return from** and **fatal host
failure from which no operation result exists at all**. The latter is outside
the outcome algebra rather than inside it as an error. Rewrite §3 on that
footing. Phase A must now also name the contract amendment; it is no longer
"`dao.stream.md` untouched".

## P1-2 — `next` is not total

Your validation accepts every integer position, so a cursor with the right
identity and position `-1` reaches `nth` with a negative index and **throws**
on the JVM — violating `next`'s total outcome contract and differing across
hosts. A fabricated position past the tail is likewise not a cursor the stream
could have minted, yet `blocked` treats it as valid. Required:

- non-integer, negative, or `pos > tail` → `invalid-cursor`
- `0 <= pos < tail` → `ok`
- `pos = tail` → `blocked` while open, `end` after close

with tests for negative and beyond-tail positions on all three hosts.

## P1-3 — `run-retention-laws` can over-certify

Four required changes:

1. **Commit the falsification test.** Bolting `:retention :complete` onto the
   ring buffer's manifest in a `let` proves the harness once; it prevents no
   regression. Commit a test asserting the modified ring manifest fails with
   the expected retention-law violation, observed **through a kept
   pre-eviction cursor**.
2. **Do not require structural cursor equality in the shared law.** Cursor
   representation is transport-owned; two cursors denoting one position need
   not be structurally equal. Compare *observations*, not representations. A
   memory-log-specific test may assert equality.
3. **Include close.** Completeness lasts the logical stream's lifetime. Where
   `:closable` is declared: append, close, then prove both a kept origin
   cursor and a freshly minted owner `:oldest` still replay every value before
   ending.
4. **Reader-only complete histories.** A read-only immutable transport cannot
   be populated through `append!`. Either require a manifest retention fixture
   supplying a prepopulated handle plus expected values, or scope the law
   writer-only for now and say so. Do not let a reader-only manifest receive a
   vacuous certification.

Also: the law must require a reader surface for `:retention :complete`,
require `next` to exclude `gap`, and **reject unknown `:retention` values**
so a typo cannot silently disable it.

## P2-1 — the `full` rationale is wrong though the decision is right

The contract says `full` may be transient **or permanent**, and the writer
decides what to do with any non-`ok` outcome — so "a configured maximum makes
`full` permanent, and retry never comes" is not a valid objection. Keep the
decision, replace the reasoning: a finite complete log could legitimately
return permanent `full`; doing so here would change existing `dao.space`
semantics and demand a rollover or abort policy this migration does not have.

## P2-2 — rotation is not actually available

Your §3 offers "publish a checkpoint and start a fresh stream" as the place a
composition puts its policy. It is not available: a new transactor over an
empty log derives `t = 0` and collides with prior history; publishing only the
new log omits the old; and queries would need a snapshot chain or merge across
rotations. Say rotation is future work requiring a checkpoint that carries
causality, and remove the implication that it can be done today.

## P2-3 — the handoff sentence should preserve ownership

`dao.space` does not create its local stream; the host composition supplies
it. Use the reviewer's shape:

> The host composition supplies `dao.space` a local handle created by
> `dao.stream.memory-log/create!`. Its declared complete retention makes
> fresh `:oldest` cursors true origin cursors for `derive-next-t` and
> `publish-index!`; supplying an evicting transport is a host-assembly defect.

Also settle whether creation specs with **extra keys** are rejected or
ignored — "no transport-owned keys" does not answer it — and cover non-map,
missing-type, wrong-type and extra-key cases in the `invalid-spec` fixtures.

Everything the review confirmed correct stands: scope, manifest partitions,
absent `attach!`, the dense-vector retention model, anchors, close semantics,
linearization shape, and portability.
