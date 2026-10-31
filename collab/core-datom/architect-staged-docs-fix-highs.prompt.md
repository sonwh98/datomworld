Created-GMT: 2026-09-01 10:04:39 GMT
Created-Local: 2026-09-01 17:04:39 Asia/Ho_Chi_Minh

# Task: apply the five High findings from the round 2 team review

Role: Lead System Architect

Implementers:
- Model: claude-fable-5 | Assigned: 2026-09-01 13:05:00 Asia/Ho_Chi_Minh | Status: active | Rationale: authored the round 2 convergence list; owns the contract's architecture

## Context

You are Reviewer B from a three-reviewer team review. Round 2 produced a joint
list all three reviewers signed. **The Medium and Low items have already been
applied** — do not redo them, and do not undo them. Your task is the five High
items only.

Read `collab/architect-staged-docs-r2.claude-fable-5.stdout.log` (your own
round 2 output) for the reasoning behind each, and the other two reviewers'
round 2 files in the same directory where you need their argument:
`collab/architect-staged-docs-r2.gpt-5.6-sol.stdout.log`,
`collab/architect-staged-docs-r2.glm-5.3.stdout.log`.

## Files you may edit — and only these

- docs/design/dao.stream.md
- docs/design/dao.stream.ws.md
- docs/design/dao.stream.implementation-plan.md

**Read no source code.** Do not edit `datom.world.md`, any ADR, or anything
under `collab/`.

## The five items

**H1 — Phase 4 is ordered before an interoperable wire contract exists.**
Add a decision gate at the Phase 3/4 boundary mirroring the existing descriptor
gate, covering handshake presentation, the authoritative disclaimer, the
application-range close code, the value codec, and malformed-input behaviour.
Do **not** invent the wire protocol itself — the gate is where it gets settled,
and the ws spec's Deferred list is where it stays until then. Make the plan's
4a handshake bullet consistent with that.

**H2 — the read side cannot report a runtime failure.**
Add `:dao.stream/transport-error` to the contract's Reading table. Decide
explicitly whether `cursor` carries it too, and say so either way. Add the
corresponding source branch to the plan's `forward!` totality list, which
currently enumerates only `cursor-mismatch` and `invalid-cursor`.

**H3 — flow control depends on a measurement nothing exposes.**
The team split on the fix and you must settle it. Sol prefers a standardized
optional `:dao.stream/lag` key on `next` results, arguing a transport-owned
operation makes 4d ring-buffer-specific. You and GLM prefer a transport-owned
lag operation. **Hard constraint: do not add an eighth operation to the
contract's public surface.** Pick one, implement it, and state in one sentence
why the other was rejected.

**H4 — `forward!` closing its destination on `end`.**
It cannot select the ws ended-stream close code, so the fast path degrades to
reattach-and-be-disclaimed. Make source `end` report a terminal state; the
serving composition performs the transport-specific close. Keep `forward!`
transport-agnostic.

**H5 — `forward!`'s self-rescheduling loop.**
Settle the step-versus-loop question rather than deferring it again. If you
choose the single-step form, say what a step consumes and returns, and make the
stop/start bullet and the flow-control deliverable consistent with it.

## Constraints

- **Minimal diff.** Change what these five items require and nothing else.
  Do not restructure, retitle, or improve prose you were not sent here to fix.
- Match the surrounding voice: declarative, reasons stated alongside rules,
  no bullet lists where a sentence works.
- Precedence is unchanged: contract wins over the ws spec and the plan.
- If applying an item would contradict something the Medium/Low pass already
  wrote, stop and report the conflict rather than resolving it silently.

## Report

Begin your final response with:

```
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh>
```

Then, per item: what you changed, in which file, and — for H3 and H5 — the
decision you took and why. End with anything you found while editing that the
review missed, or any place the five corrections interact badly.
