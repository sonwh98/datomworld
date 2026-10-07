Completed-GMT: 2026-09-06 17:04:47 GMT
Completed-Local: 2026-09-07 00:04:47 +07 (Asia/Bangkok)
Coding-Agent: interactive
Session-ID: not-applicable (interactive seat)

# Task: dao.jing.v2 migration plan — orchestrator log

Role: Lead Engineering Orchestrator
Seat: Claude Code interactive, model claude-opus-5
Repository: /Users/sto/workspace/datomworld, branch `dao.stream-redesign-v2`,
base f87cb37, tree clean at start.

## Scope and authority granted

The user authorized, in sequence and each explicitly: (1) brief the Architect
on a `dao.jing.v2` migration plan; (2) route the draft to `gpt-5.6-sol` and
`glm-5.3` for review; (3) a two-round consensus between `gpt-6-astra` and
`claude-fable-5-1`; (4) a revision by the author seat; (5) write the result to
`docs/design/`; (6) this confirmation round. No authority to stage or commit
was given or taken. Consent was requested separately for each external
destination; none was carried over.

## Delegations, with session IDs

| task | role | model | CLI / mode | Session-ID |
|------|------|-------|-----------|------------|
| plan draft | Architect | claude-fable-5-1 | claude, plan, Read-only | e425d8bd-ad4c-44f7-aaed-54cb3196fd0f |
| routine review | Reviewer | gpt-5.6-sol | codex exec, read-only | 01a0776c-dbcf-7343-a6de-ef18f705fec7 |
| adversarial review | Adversarial Reviewer | glm-5.3 | glm, plan, Read-only | 50d48a71-9ff9-44b7-8dc0-b334e5f42aac |
| consensus r1+r2 | Architect (independent) | gpt-6-astra | codex exec, read-only | 01a0779a-7a9d-79e0-930a-ac19af7164b4 |
| consensus r1+r2, revision | Architect (author) | claude-fable-5-1 | claude --resume | e425d8bd-ad4c-44f7-aaed-54cb3196fd0f |

`gpt-6-astra` was probed with a throwaway call before briefing
(thread 01a07798-ef37-7bc2-a947-aff4892bc41e) to confirm the model exists.
Reviewer independence held throughout: the author is Claude, both reviewers
and the second consensus seat are non-Claude families.

## Claims independently verified by this seat

Delegated claims are untrusted. Each of the following was checked against the
tree before being relied on, with the result recorded:

- `dao.stream/outcomes-next` is exactly seven keywords (v2.cljc:78-85). HOLDS.
- `apply/dispatch-request` (apply.cljc:187), `apply/serve-once!` (301),
  `rpc/serve-once!` (106), `rpc/request!` (232), `rpc/unsent?` (259),
  `rpc/poll!` (421), `rpc/rebind` (465), `rpc.ws/rebind` (ws.cljc:152). ALL EXIST.
- `dao.stream.log` uses a 4-byte big-endian length prefix over opaque byte
  arrays (log.cljc:3); `jing.file/encode-record` supplies the `pr-str` of
  `[address payload]` (file.cljc:40-43). HOLDS — byte-compatibility claim sound.
- `rpc/request!` is the sole retry path for an unsent envelope
  (rpc.cljc:243-244 -> `attempt-unsent`); `rpc/poll!` only reads the response
  medium (421-445). HOLDS — the round-1 `busy` rule was a deadlock.
- `abandon-unsent` (rpc.cljc:267-283); `lose-outstanding` reduces over
  `:outstanding` only (308-315); `rebind` preserves `:unsent` (465-476).
  HOLDS — an unsent request was silently swallowed at detach.
- `allocator-error` (rpc.cljc:158,160). HOLDS.
- `jing/materialize!` derives the address and verifies read-back on `:present`
  (jing.cljc:249-296). HOLDS — `request-put` was not a materializer.
- `dao.jing.cljc:19` requires v1 `dao.stream`. HOLDS — the round-1 "no v1
  dependency" claim was false transitively.
- The v1 observer has NO `src/` caller; `observer-state`/`observe-step!`
  appear only in tests, across SEVEN files: five `test/dao/space/`
  (transactor, stigmergy, index, schema, query) plus `jing/mem_test.cljc:213`
  and `jing/dht_test.cljc:402`. Author's round-1 count of five was CORRECTED.
- `torn-tail-test` at `test/dao/stream/log_test.cljc:100-135`; its fixture
  writes `(->bytes [11 22])` at :104, not a decodable record. HOLDS — the
  port needs an adaptation.
- `:append-log` has exactly one non-test consumer, `jing/file.cljc:190`.
  `dao.stream.log` is required only by `jing/file.cljc:17`, `log_test.cljc`,
  `jing/file_test.cljc`. HOLDS — orphaned after J1.
- `dao.jing.md:297-299` describes the file backend as "backed by an
  append-only log stream". HOLDS — Decision 2 amends a design document.
- `datom.world.md:66-68` condemns "an adapter that exposes a function for
  portable code to call". HOLDS — and it condemns `:put-content-fn` today,
  on v1, which is why no transport cures it.
- `dao.stream.md:153-158` states the no-wait rule generally; `dao.jing.md:299-301`
  acknowledges a put only after flush. HOLDS — a conforming v2 append-log
  would require redesigning acknowledgement. This seat contributed this fact;
  neither reviewer had it.
- `yin.repl.driver` calls `rpc/abandon-unsent` at driver.cljc:220,258,293,305.
  HOLDS.
- `dao.stream.file` (`:file`, live-tail, consumer `yin.io.file`) is a distinct
  transport, untouched by this plan. HOLDS.
- Author's round-1 citation "jing_test.cljc lines 322-561": file is 560 lines.
  MINOR SLIP, corrected in revision.

## Tests

NOT run by this seat. The user reported `bb test` green on this revision and
instructed that it not be re-run. Recorded as user-run evidence without the
exact command output or tested revision captured, which is weaker than the
standard this role normally applies. No phase of this plan has been
implemented, so no test outcome is claimed for it.

## Rulings made by this seat

- The write-path redesign (content put as an effect on a stream, durability
  observed later as data) is OUT OF SCOPE for this plan; the Host-Boundaries
  question about the synchronous content handle is recorded as a named open
  item instead. Both consensus seats reached this independently.
- The two remaining scope questions — five `test/dao/space/` repoints, and
  deleting `dao.stream.log` from a consumer plan — were NOT ruled. They are
  carried in the document as scope-contingent with named alternatives,
  decided when implementation is authorized. The user chose this over ruling
  in the abstract.
- Round 2 of the consensus was run with both seats in parallel. Consequence:
  each answered the other's round 1, and both closing summaries assert
  disagreements that their own bodies do not support. This was a seat error in
  round design, not a defect in either agent's reasoning. The convergence is
  real; the residual disagreement is an artifact.

## Outcome

Consensus reached on all seven contested items. Six real defects accepted and
incorporated; Sol's C1 finding (Decision 2 violates Axiom 1) was overruled by
both architects and is the one finding rejected. Revision written to
`docs/design/dao.jing.v2.implementation-plan.md` (900 lines, new file).

One edit was made by this seat to the author's output: its Status line cited
`collab/consensus-dao-jing-v2*` as provenance. `collab/` and `archive/` are
gitignored, so a committed design document referencing them would dangle. The
parenthetical path was removed; the sentence and all other content stand
verbatim.

## Not done

- Nothing staged or committed. `git status` shows the new design document
  untracked alongside 26 uncommitted `collab/` artifacts.
- No phase implemented. This is a plan, not a diff.
- Confirmation round with `gpt-5.6-sol` and `glm-5.3` — in progress at the
  time of writing; findings will be recorded as `*-confirm.*.findings.md`.
- `collab/` artifacts not archived: archiving is gated on the work being
  committed, and it is not.

---

## Correction, appended 2026-09-07 00:14 +07 (append-only; the entry above stands as written)

**The seven-test-file count recorded above under "Claims independently
verified" is WRONG. The correct count is nine.** `observer-state` /
`observe-step!` are called from: `jing_test.cljc`, `jing/mem_test.cljc`,
`jing/dht_test.cljc`, **`jing/file_test.cljc:113-135`**, and the five
`test/dao/space/` files. This seat listed `file_test.cljc:123-125` in its own
grep output and then omitted it when correcting the author's count, and did not
count `jing_test.cljc`'s observer section as an affected file.

The error propagated: it was written into `collab/consensus-dao-jing-v2-r2.notes.md`
as N-c, presented to both consensus seats as settled orchestrator-verified
fact, and both built the C2 cost argument on it. `glm-5.3` caught it in the
confirmation round. The conclusion it supported — that the move-out is cheaper
than the extraction — survives, because nine test files is still bounded and
no `src/` file changes. But the plan's stated cost is understated, and the
scope-contingent ruling rests on that cost.

Also confirmed from `glm-5.3`: `dao.jing.file`'s handle exposes the log under
`:log` (`file.cljc:197`) and `file_test.cljc:131` reads it, so the revised
plan's "portable code never receives a handle to that stream" is an
overstatement in a contested decision's rationale.

**Second correction.** N-a as this seat wrote it said a conforming v2
append-log "could not simply wrap synchronous file writes". `gpt-5.6-sol`'s
dissent shows the sharper truth: such a transport IS constructible, because
`append!`'s `:dao.stream/ok` "implies neither local readability nor remote
delivery" (`dao.stream.md`, Writing). What it cannot do is support
`materialize!`'s `:inserted` meaning "durably stored now". The cost lands on
`materialize!`'s contract and its synchronous consumers, not on the
transport's constructibility. `glm-5.3` independently reached the same
sharpening and accepted the rejection on it.
