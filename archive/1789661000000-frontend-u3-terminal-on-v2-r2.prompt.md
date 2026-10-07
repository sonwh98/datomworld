Created-GMT: 2026-09-17 13:56:40 GMT
Created-Local: 2026-09-17 20:56:40 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 9829afe4-c770-4cbf-af7b-418e6a80eab9
Role: Frontend & Graphics Implementer

# Task: Fix two P1 findings from adversarial review (r2)

Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-17 20:56:40 +07 | Status: active | Rationale: same session, follow-up correction on its own U3 diff

An adversarial review
(`collab/1789660000000-review-u3-u4-dao-stream-v1-retirement.gpt-6-astra.findings.md`)
of U3 found two P1 issues, both in your scope. Everything else in U3 was
confirmed clean (D4 waiter removal, option compatibility, artifact split).

## Finding 1 — the Flutter dao.gui Prototype loses its initial sample frame

`terminal.cljc`'s `bind` mints at `:dao.stream/newest` (correct per D4).
But in `src/cljd/datomworld/demo/dao_gui.cljd`, the picker's `LayoutBuilder`
(around `:353-365`) calls `render-built-in-surface!` (which emits the
sample frame) as part of the parent's build, before the child terminal
widget constructs and binds. Since the binding mints at `:newest`, a
frame appended before `bind` runs is invisible — the terminal opens on an
empty surface until a second frame arrives.

Fix: reorder so the sample frame is emitted *after* the terminal widget
has bound (e.g. have the terminal widget's own mount/`bind` trigger the
initial render, or have the widget expose a ready callback the picker
calls before rendering the sample) rather than the parent rendering
before the child exists. Verify both first mount and reopening the demo
(rebinding) still show the sample immediately, since D4's `:newest` rule
must hold for both.

## Finding 2 — terminal stream failures emit signals outside the canonical protocol

`terminal.cljc`'s `step` (around `:141-144`) sets `:error/kind` on the
`protocol-error-signal` to the raw v2 stream outcome keyword (e.g.
`:dao.stream/end`) on any non-ok, non-gap outcome. But
`docs/design/dao.postgraphics.md` states the `:error/kind` vocabulary is
exhaustively constrained to three tap/generation-ordering keywords
(`:future-frame-tap`, `:out-of-order-frame-events`, `:stale-generation-tap`)
— none of which describe a transport failure. Migrating the stream
underneath the terminal does not implicitly version the graphics
protocol's own signal vocabulary.

Reviewer's recommendation, which you should follow unless you find a
better alternative and can justify it: document a terminal-owned
extension, e.g. `:dao.terminal/transport-error`, and have `dao.postgraphics.md`
name it explicitly alongside the existing three (state which raw stream
outcomes map into it — at minimum `:dao.stream/end`, `:dao.stream/error`,
and any terminal outcome your `step` treats as non-recoverable). Also fix
the signal's `:frame-id` payload: the current call supplies the
submission counter as `:frame-id`, but submission and presented-frame
identities differ after a rejection or a skip, and a transport failure
can occur before any frame has ever been presented — decide what
`:frame-id` should be in that case (perhaps `nil`, perhaps the last
presented frame's id if tracked) and document it. Update the canonical
spec (`dao.postgraphics.md`), the terminal doc
(`dao.postgraphics.terminal.md`), the implementation, and the tests
together — this must land as one coherent change, not four inconsistent
ones.

## Task

1. Fix both findings.
2. Re-run `clj -M:test`, `bb test:cljs`, `bb test:cljd` (clear
   `test/cljd-out` first) — all must stay green.
3. Report exactly what changed, with line numbers, and your test output.

Leave changes unstaged for the orchestrator to review and commit. Do not
touch U4's files (`src/cljc/dao/gui/event.cljc`, `test/dao/gui/event/*`,
`docs/design/dao.gui.event.md`) — a separate follow-up is fixing its own
findings there.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
