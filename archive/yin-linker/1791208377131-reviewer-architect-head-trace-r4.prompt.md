Created-GMT: 2026-10-05 13:52:57 GMT
Created-Local: 2026-10-05 20:52:57 +07
Coding-Agent: codex
Session-ID: 01a10c19-e77f-7631-b9d5-f26df7a7ff2f

# Task: reviewer-architect-head-trace (round 4: confirm and sign off)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-05 19:46:00 +07 | Status: active | Rationale: same reviewer, resumed for the final confirmation of the core design

Read-only. The Architect revised
`docs/design/yin.vm.linker.dht.head.md` once more (now 1328 lines) in
/Users/sto/workspace/datomworld/.claude/worktrees/architect-head-trace to close
your round-3 findings. Do not edit anything. The Architect's report is untrusted:
`collab/1791208065576-architect-head-trace-r4.claude-fable-5-1.stdout.log`. Cite
repository evidence and document lines. This is the last automatic round: end with
a clear adopt-or-block verdict for the CORE (slices H0 to H3 as the document now
defines them).

## Claimed dispositions (check each in the TEXT, tests and slice criteria)

1. Identity-contract conflict: a board name is now lookup data only, resolved once
   to the ring's real descriptor; the reader then attaches normally (new C4, 5.1,
   8.2, 8.3, H2). The Architect chose this over amending `dao.stream.md` and over a
   dedicated single-entry endpoint, and says no owner decision is needed. It adds
   one named `descriptor` request shape to `dao.stream.remote`, a `resolve` on the
   link, and a name map on the ws acceptor and dial. It withdrew the earlier claims
   that the `yin.repl` fixed names are a convention to follow and that the issue
   "blocks relaying, not following".
2. Shared candidate ownership: an owner set `{address #{principal}}`, removal of a
   record only at the last release; release on replacement, rejection and
   installation (installation release lets a later manual `load-index` succeed).
3. Host refusal translation: both host load operations answer a DHT refusal as the
   call's error value, covering every `:dao.space.dht/refused` code (`yin/repl/
   query.cljc` `dht-answer` around line 636 calls `dht/load-index` and
   `linker.dht/load-module` without catching; the orchestrator confirmed).
4. Abandon and fetch-client cleanup: `abandon` retires the record's client interest
   using `content.step/abandon` (unsent request) or `content.step/retire`
   (outstanding id), both of which exist (`src/cljc/dao/jing/content/step.cljc`
   lines 708 and 736); `advance-loads` steps the client whenever it holds anything.
5. Replay and load-frequency: the document now claims safety with no progress
   guarantee, bounds the work an alternation causes, withdraws "loaded once per
   process", and adds alternating-replay tests and owner question 5.

## What to attack

- Is each of the five actually resolved in the text, the tests and the completion
  criteria, or only asserted? Cite lines.
- The `descriptor` request: is it a sound, minimal wire change to
  `dao.stream.remote`? Does it conflict with `dao.stream.apply` independence from
  rpc, with the logical-identity contract (`dao.stream.md` 277-285,
  `dao.stream.remote.md` 159-163), with `remote.cljc:509-519`, or with the mirror's
  existing behavior? Does it make the fixed `yin.repl/requests` and
  `yin.repl/answers` names a separate, out-of-scope defect, as the Architect says?
- The owner set and the abandon cleanup: any double release, leak, stale answer
  applied to the wrong candidate, or interaction with `forget` and `retry!`?
- Host refusal translation: does covering "every refused code on both operations"
  stay compatible with existing callers and tests?
- The named blocker: H2 amends `dao.stream.remote.md` and so needs Architect and
  independent-reviewer sign-off before it lands, while H0 and H1 do not depend on
  it. Is that independence real? Does H1 or H0 secretly need the name resolution?
- Does anything still block adopting the core as a design document? Do any of the
  five owner questions block it, or are they correctly scoped to later steps?
- The document is 1328 lines. Say whether that is justified for what it settles.

## Output

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a10c19-e77f-7631-b9d5-f26df7a7ff2f

For each of the five: resolved | partly | not resolved with the document line.
Then new findings as `P0-P3 | file:line | evidence | concrete fix`, or "No
actionable findings". End with one line: ready to adopt as the core design, or the
specific blockers.
