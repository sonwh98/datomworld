Created-GMT: 2026-09-17 13:56:40 GMT
Created-Local: 2026-09-17 20:56:40 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 23e4c42e-7822-49da-9d68-5c8a231d1d0f
Role: Stream & Network Implementer

# Task: Fix two findings from adversarial review (r2)

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-17 20:56:40 +07 | Status: active | Rationale: same session, follow-up correction on its own U4 diff

An adversarial review
(`collab/1789660000000-review-u3-u4-dao-stream-v1-retirement.gpt-6-astra.findings.md`)
of U4 found one P1 and confirmed one P2 (a pre-existing plan ambiguity,
not your bug) in your scope. Everything else in U4 was confirmed clean
(D5 dispatch and parking, the scripted fixture's other behaviors).

## Finding 3 (P1) — a binding still re-reads after a terminal input outcome

`event.cljc`'s `advance` (around `:815-825`) returns
`{:binding binding', :status :transport-error}` on `cursor-mismatch`,
`invalid-cursor`, or `transport-error`, but `binding'` carries no
"stopped" marker — a later `advance` call on that same binding issues
another real `stream/next` read rather than short-circuiting. The
orchestrator independently confirmed this: `bind_test.cljc:259`'s "a
transport-error on the input is reported without a re-read" test calls
`advance` a second time and only asserts the returned `:status` matches
— it never re-checks `@reads` after that second call, so the test passes
even though a second real read happened. This meets "one read per call"
but violates the plan's actual "does not re-read" acceptance criterion
for the transport-error path.

Fix: once a binding observes a terminal input outcome
(`cursor-mismatch`/`invalid-cursor`/`transport-error`), it must retain
that terminal status so a later `advance` returns `:transport-error`
*without* calling `stream/next` again — the binding needs a persisted
"stopped" flag (or equivalent) that `advance` checks before reading.
Update `bind_test.cljc:259`'s test to assert `@reads` is still `1` after
the second `advance` call, so it actually certifies the no-re-read
behavior instead of merely tolerating it.

## Finding 4 (P2) — document the origin-cursor ambiguity, don't fix code

`event.cljc:783`'s "mint `:oldest` on first advance" (the origin-cursor
rule) follows D5 literally, but the reviewer found a real ambiguity in
what that guarantee actually promises: bind an empty capacity-1 stream,
append twice, then call the first `advance` — minting `:oldest` at that
point observes only the retained (most recent) value, silently missing
the first append, with no gap reported (nothing to compare against yet).
This is inherited from the plan's own wording, not a bug in your
implementation — do not change the minting logic. Instead:

1. Add a paragraph to `docs/design/dao.gui.event.md`'s *Binding Contract*
   section documenting this precisely: the origin-cursor guarantee only
   holds if the caller either supplies a cursor minted before production
   begins, or calls the first `advance` before production begins. A
   binding created after values have already been produced and evicted
   may silently observe a later position with no reported gap.
2. Add a test case in `bind_test.cljc` covering exactly this scenario
   (empty capacity-1 stream, two appends before the first advance,
   assert what actually gets observed) so the behavior is pinned down
   rather than merely documented in prose.

## Task

1. Fix finding 3 (real code + test fix).
2. Document + test-cover finding 4 (doc + test only, no logic change).
3. Re-run `clj -M:test`, `bb test:cljs`, `bb test:cljd` (clear
   `test/cljd-out` first) — all must stay green, and the corrected
   `bind_test.cljc:259` case must actually fail before your fix and pass
   after it (verify this yourself, don't just trust the assertion reads
   right).
4. Report exactly what changed, with line numbers, and your test output.

Leave changes unstaged for the orchestrator to review and commit. Do not
touch U3's files (`src/cljc/dao/postgraphics/terminal.cljc`,
`src/cljd/dao/postgraphics/flutter.cljd`, `src/cljs/dao/postgraphics/web.cljs`,
`test/dao/postgraphics/*`, `docs/design/dao.postgraphics.terminal.md`,
`src/cljd/datomworld/demo/dao_gui.cljd`) — a separate follow-up is fixing
its own findings there.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
