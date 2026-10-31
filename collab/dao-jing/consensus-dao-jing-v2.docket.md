# Consensus docket — dao.jing.v2 migration plan findings

Two independent reviews of the draft plan
(`collab/architect-dao-jing-v2-plan.claude-fable-5-1.findings.md`) are complete:

- `collab/reviewer-dao-jing-v2-plan.gpt-5.6-sol.findings.md` — routine review,
  4xP1 + 3xP2
- `collab/adversarial-dao-jing-v2-plan.glm-5.3.findings.md` — adversarial
  review, 3xP1 + 2xP2 + 1xP3

They converge on three defects and diverge on six items. This docket is the
divergence, plus one proposal neither reviewer saw.

## Verified by the orchestrator — settled fact, do not re-derive or dispute

Each checked against the tree:

- `dao.stream/outcomes-next` is exactly seven keywords (v2.cljc:78-85).
- `rpc/request!` is the sole path that retries an unsent envelope
  (rpc.cljc:243-244 -> `attempt-unsent`); `rpc/poll!` only reads the response
  medium (rpc.cljc:421-445). The plan's `busy` rule therefore cannot clear.
- `rpc/abandon-unsent` exists (rpc.cljc:267-283); `lose-outstanding` reduces
  over `:outstanding` only (rpc.cljc:308-315); `rebind` preserves `:unsent`
  (rpc.cljc:465-476).
- `rpc/allocator-error` exists (rpc.cljc:158,160).
- `jing/materialize!` derives the address itself and, on `:present`, reads the
  value back and throws on inequality (jing.cljc:249-296).
- `dao.jing.cljc:19` requires v1 `dao.stream`.
- `torn-tail-test` is at `test/dao/stream/log_test.cljc:100-135`.
- `:append-log` has exactly one non-test consumer, `dao/jing/file.cljc:190`.
  `dao.stream.log` is required only by `jing/file.cljc:17`, `log_test.cljc`,
  and `jing/file_test.cljc`. `log.cljc` is 252 lines; `log_test.cljc` is 170.
- `dao.stream.file` (`:file`, live-tail) is a DIFFERENT transport, a genuine
  stream with waiters and multiple readers, consumed by `yin.io.file`. It is
  not at issue anywhere in this plan.
- The full local suite is green on this revision (user-run).

## Agreed defects — not in dispute; confirm the fix shape only

- **A1.** The `busy`/`step` protocol deadlocks: `step` polls only, so
  `unsent?` never clears and every later request is `busy` forever. Both
  reviewers, P1.
- **A2.** `step`'s specified return `{:state :completions}` has no field for
  the diagnostics it claims to take exactly once. Both, P2.
- **A3.** Torn-tail/truncation recovery loses its only coverage. Both.

## Contested items — the debate

**C1. Decision 2 vs Axiom 1.** Sol (P1): removing `dao.stream` from
`dao.jing.file` contradicts "all IO and data flow through append-only
streams"; reader count does not determine whether IO is a stream;
`dao.jing.mem` performs no IO so it is not precedent; a subordinate migration
plan cannot create an exception to the superior architecture — amend
`datom.world.md` or build the v2 append-log. GLM: attacked it and concluded
the plan is right — the intake pool is DaoJing's stream boundary, the write
behind `:put-content-fn` is the backend effect beyond it, and `dao.jing.md`
already commits to "backend effects are explicit functions, not a protocol".

**C2. Transitive v1 dependency.** `dao.jing.v2` requires `dao.jing`, which
requires v1 `dao.stream`. The plan's Decision 4 table says "v1 dependency
after this plan: none" and its end condition says "no namespace under
`dao.jing.v2*` requires `dao.stream`". Sol (P1): false as written; extract a
stream-free shared core, make v1 `dao.jing` a composition over it, gate J2 on
transitive absence. GLM (P3): inherent to the fold-back strategy, ends at J5,
needs only a clarifying sentence.

**C3. `request-put` is not `materialize!`.** Sol (P1): the plan's client
takes an address and returns raw `:inserted`/`:present`, omitting the address
derivation and the read-back-and-verify that is DaoJing's integrity
behaviour, while calling the result "the async DaoJing backend" that
`dao.data.btree.md` 5.4 was waiting for. Either specify a stepped
materialization or drop the claim. GLM did not raise this.

**C4. J5 naming decision.** Sol (P2): left as a recommendation despite the
plan's own rule that decisions are settled before phases, and despite the
brief asking for it up front. GLM: sound — deferred with a settled
recommendation, mirroring the stream plan's own end-condition language.

**C5. Swallowed pending request at detach.** GLM (P1): on `/detached`,
`lose-outstanding` covers `:outstanding` only, so a request that returned
`pending-request` produces no completion at all; `rebind` then carries the
stale envelope onto the new writer. `abandon-unsent` exists for exactly this
and the plan never names it. Combined with A1, a caller holding that id has no
operation that retires it. Sol did not raise this.

**C6. Completion vocabulary not total.** GLM (P2): the `:lost` reasons omit
`cursor-mismatch` and `invalid-cursor` (produced by `poll-read`), and
`request-*`'s outcomes omit `allocator-error`. Sol did not raise this.

## N1 — proposal neither reviewer saw

After J1, `dao.stream.log` has no production consumer: 252 lines of three-host
file IO alive only for its own 170-line suite, until v1 is deleted wholesale.
The plan copies the framing out of it "by copy, never by require", which GLM
flagged as creating two owners of the same truncation logic with a drift
window lasting until v1 deletion.

**Proposal:** delete `dao.stream.log` and `log_test.cljc` in J1, and move
`torn-tail-test` into `file_test.cljc` in the same phase. One owner
immediately, no drift window, and A3's coverage travels with the code it
covers.

**Open sub-question — jurisdiction.** Deleting a v1 transport is arguably the
stream plan's business, not DaoJing's; the architect brief said not to
broaden scope beyond `dao.jing*`. But the orphaning is caused by this plan.
Does this plan claim the deletion, hand it to the stream plan with a named
dependency, or leave `log.cljc` standing until v1 dies?
