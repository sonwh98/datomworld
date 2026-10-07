Created-GMT: 2026-09-25 08:05:00 GMT
Created-Local: 2026-09-25 15:05:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: UCF Blocker-Closure Acceptance Matrix (design the tests M3/M4 must pass)

Role: Lead System Architect

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-25 15:05:00 +0700 | Status: active |
  Rationale: the owner asked whether the UCF acceptance blockers can close
  now. Implementation belongs to the milestone pipeline (VM Runtime
  Engineer, ZCode subagents) once M3/M4 provide the runtime; what can be
  produced now is the acceptance contract itself, which is architecture
  authorship and yours.

Background: docs/design/yin.vm.universal-continuation-format.md section 7
is Proposed/Deferred behind five acceptance blockers (7.3 code identity,
7.4 safepoint reconstruction, 7.5 recursive portable encoding, 7.6
ownership arbitration, 7.7 dependency closure; 7.11 lists them). The
linker epic is the implementation phase: M3 delivers link-state/step over
stream exchanges; M4 delivers the lift driver, register-host-module,
require-handler, the install child, and the UCF table amendments. The v2
contract revision history is now published (dbae125b) and the :reasons
ruling (Option B, safepoint kinds) is recorded in
 docs/design/yin.vm.ucf-revisions.md.

Task: extend section 7.11 of
docs/design/yin.vm.universal-continuation-format.md with the blocker-
closure acceptance matrix. For each of the five blockers, specify:
1. The acceptance invariant, in one sentence, in terms of observable
   behavior (not implementation).
2. The concrete test contract: setup, action, expected outcome —
   including the refusal modes and, where relevant, the host matrix
   (JVM/CLJS/CLJD) and the safepoint kinds it must cover (explicit park,
   blocked stream read, effectful call).
3. The milestone that lands it (M3, M4, or post-M5 hardening) and any
   ordering constraint between blockers.
4. What is already satisfied by landed work (cite it: the v2 revision
   history dbae125b, the :reasons ruling, M2 format records).

Rules: this is design authorship of tests, not implementation. Write
ONLY to docs/design/yin.vm.universal-continuation-format.md (section
7.11 and, if needed, cross-reference lines in 7.3-7.7). ASCII, <= 80
columns on added/edited lines. Read sections 7.3-7.7, the linker spec
sections 9 (M3/M4) and 11, and docs/design/yin.vm.ucf-revisions.md
first; treat the current 7.11 list as your starting inventory.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Summarize the matrix: per blocker, the invariant and its landing
milestone, and anything the milestone briefs must not omit.

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
