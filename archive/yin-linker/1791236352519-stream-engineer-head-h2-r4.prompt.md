Created-GMT: 2026-10-05 21:39:12 GMT
Created-Local: 2026-10-06 04:39:12 +07
Coding-Agent: claude
Session-ID: 820fb1cc-b82c-4ee6-96c7-7a170107243d

# Task: head-h2 (round 4: two Architect-ruled fixes)

Role: DaoStream and Network Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-06 04:05:48 +07 | Status: active | Rationale: same engineer, resumed to apply two Architect rulings on the reviewer's second pass

The reviewer's second pass
(`collab/1791236110071-reviewer-head-h2-r2.gpt-6.1-sol.findings.md`) closed its
three findings and found two new P2s; the Architect ruled on both
(`collab/1791236272936-architect-h2-contract-r3.claude-fable-5-1.stdout.log`).
Both reports are untrusted: check the citations. The orchestrator already applied
the Architect's earlier "until channel loss" edit to 2.4; ruling 1 below REPLACES
that sentence.

## 1. Drop the unsendable memory entirely (Architect ruling: option b)

`:unsendable` on the link (`src/cljc/dao/stream/remote.cljc` about 862-863 and
874-876) keeps every distinct refused name, whole, with no bound. Reasoning of the
ruling: derive, don't persist; the channel writer is the source of truth for
whether a value can be carried, and asking it again re-derives the same refusal.

- Code: remove `:unsendable`; the `:else r` branch then covers every refusal other
  than `full`. A `resolve` of an unsendable name leaves the link exactly as it
  found it apart from the id counter: nothing outstanding, nothing filed, nothing
  remembered. Update the `resolve-name` docstring to match.
- Contract, `docs/design/dao.stream.remote.md` 2.4: replace from "A send the
  channel writer refuses" through "until channel loss." with EXACTLY: "A send the
  channel writer refuses with `full` is retried by the next `resolve`; any other
  refusal is returned as the writer's own outcome, leaves nothing outstanding and
  is remembered nowhere, so each `resolve` of that name attempts the send again."
- Tests: rework `an-uncarryable-name-is-a-terminal-resolve` so that for
  `invalid-value` and for `closed`, three calls give the same writer outcome, THREE
  sends are attempted, and `:outstanding` and `:filed` stay empty. Add a many-name
  case: resolving 100 distinct refused names leaves the link holding none of them
  (no name in `:outstanding`, `:filed` or any other key of the link). Keep the
  `full` case as it is.

## 2. An answer with no identity completes no identity request

`well-formed-answer?` (about 93-97) now admits a named error with no identity, and
the identity branch of `absorb!` (about 513) compares a missing identity as `nil`,
so for a reflection whose identity is `nil` an unsolicited
`{id 0, name "unasked", error not-found}` consumes its probe and marks it gone
(before H2 it was dropped).

- Code: in the identity branch of `absorb!`, require
  `(contains? v :dao.stream/identity)` before the equality. That restores the
  pre-H2 behaviour exactly for identity requests. The named branch is unaffected.
- Contract, 2.4 Resolve: insert after "it echoes the name asked." EXACTLY: "An
  answer that carries no `:dao.stream/identity` completes no identity request."
- Tests: for a `nil`-identity reflection, an answer with an ABSENT identity is
  dropped and the probe stays outstanding, for both `not-found` and `oversize`; an
  answer with an explicit `nil` identity is tested separately (it behaves as before
  H2).
- Whether a `nil` identity should be legal for a reflection at all is a
  pre-existing question outside H2: do NOT change it; mention it in your report as
  a follow-up.

## Not in this round

The JVM free-port find-then-bind race stays as it is (Architect: H2 does not carry
a retry).

## Scope and process rules (unchanged)

Files: `src/cljc/dao/stream/remote.cljc`, `test/dao/stream/remote_test.cljc`,
`docs/design/dao.stream.remote.md`. Every existing test passes unchanged except the
one test this round reworks; an identity request is answered exactly as before. No
git commands, no formatter, no Node or Dart runs, no background processes, no
`clj -M:test -e`. Verify in the foreground with the same 21-namespace combined run
and `clj -M:kondo --lint` on the touched files, with assertion counts. Keep the
contract document ASCII and at most 80 columns (rewrap the paragraph if a
replacement pushes a line over).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 820fb1cc-b82c-4ee6-96c7-7a170107243d

Then report, per item: what changed and the tests that pin it; the exact commands and
outcomes with assertion counts; confirm both contract sentences are in verbatim;
anything unresolved.
