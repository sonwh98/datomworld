Created-GMT: 2026-11-14 13:53:15 GMT
Created-Local: 2026-11-14 20:53:15 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / GLM seat — yang.typescript orchestrator)
Session-ID: pending (provider-generated; fresh thread — small subject)
# Task: T2 bare-global-write refusal — step-7 review (small)

Role: Lead System Architect

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-11-14 20:53 +07 | Status: active | Rationale: standing architect lane; GPT family independent of the Qwen implementer of the prior adjacent round (this round's emission swap was seat-authored — disclosed)

Read-only review of a SMALL round in the ts frontend: the T2 bare-global-write refusal.

## The defect and the fix (design-grounded; verify, don't trust)

Design yang.typescript.md §3.1.4 (strict mode always on; implicit globals refused) + §3.7
(script-goal globals are a SEPARATE slice — declared-global writes belong there). The old
`assign-name` emission (lower.cljc, first cond branch) threw an unconditional
`TypeError "Cannot assign to read only property 'nm' of object"` for ANY non-cell resolve —
mislabeled class, wrong situation, and a stray `js/global-get` read first.

The fix (single emission swap in the diff): the refusal is now
`js/throw-reference-error ("nm is not defined")` — the prelude's existing helper; the stray
read is kept (it is itself the ES-correct ReferenceError for the truly-absent case) with a
comment saying so; the docstring points declared-global writes to the §3.7 slice.

## Diff

`git -C /Users/sto/workspace/datomworld-t2 diff b134207f..HEAD` — wait, the branch base is
current origin/master: `git -C /Users/sto/workspace/datomworld-t2 log --oneline -1` shows
`50d31bc9` on top of `origin/master`. Single-commit diff; files: lower.cljc (one branch swap)
+ new `test/yang/typescript/antlr/global_write_test.cljc` (2 deftests / 12 assertions,
portable tri-backend).

## Execution evidence (orchestrator runs)

- Pin 1 (undeclared `undeclaredProbe = 7` inside guest try/catch): GREEN on all three
  backends — the reference-class throw is correct today via the LHS read.
- Pin 2 (declared `globalThis.saved = null` then `saved = 7`, guest try/catch): was RED on
  all three backends pre-fix (TypeError mislabel), GREEN post-fix (ReferenceError).
- Regressions: REG6 6/48, pins/manifest 18/90, lower suites 28/777, source-call 2/22 — all
  0F/0E at the fix commit.

## Review asks (small — three)

1. Is `throw-reference-error ("nm is not defined")` the design-correct refusal for BOTH
   sub-cases (truly-undeclared; declared-globalThis-property under module goal), per §3.1.4
   + §3.7's allocation of declared-global writes to the script-goal slice? Or does §3.7
   require declared-global writes to SUCCEED even in module-goal lowering (i.e. is the
   refusal itself wrong and §3.7's global-object write the required behavior)?
2. The kept `js/global-get` read: harmless preserve or should the emission skip the read
   (the refusal doesn't need the value)? Any guest-observable difference?
3. The two pins: correctly shaped to catch a regression of this swap (class AND the
   kept-read path), without over-pinning?

Distinguish architectural defects from gaps or deferred work (§3.7 script-goal writes are
a named follow-up slice). Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: per-ask answers + severity table if any + final verdict: SIGN-OFF or FIX-LIST.
