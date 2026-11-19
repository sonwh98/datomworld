Created-GMT: 2026-11-19 04:45:51 GMT
Created-Local: 2026-11-19 11:45:51 ICT (+07)
Session-ID: 01a1f1f9-2d2b-71a2-b88a-1675b81dd975 (RESUME of your P1b-stage1 implementation thread)
# Task: P1b-stage1-r2 — apply the argon review's fixes

Role: VM Runtime Engineer (fix round; reviewer confirmation follows)

Your P1b-stage1 was reviewed by an independent reviewer (agy gemini-4-argon-high,
read-only, re-ran your whole gate matrix — all green). Verdict:
ACCEPT-WITH-FIXES. Full review:
/Users/sto/workspace/datomworld/collab/jolt/1795062317148-reviewer-p1b-stage1-argon.gemini-4-argon.findings.md
— read it first.

Apply, in priority order:

1. MANDATORY (P2 F1): host/gambit/host-ffi.ss:214 — joltffi-read-bytes calls
   unbound bytevector->u8-list; replace with u8vector->list, and add a
   non-UTF-8 read-bytes check to host/gambit/ffi/gambitffi-public.ss so the
   fixed path is actually exercised by a gate (state-derived, watchdogged).
2. OPTIONAL but do them (P3 F2-F4): nil-guard the string-naming args in
   host/gambit/host-ffi.ss; add "host-ffi.ss" to host/gambit/profiles.ss and the
   bridge symbols to host/gambit/unbound-allowlist.txt as the review prescribes;
   fix the libjoltffi-unloadable.dylib prerequisite list (Makefile:2126-2127).
3. After the fixes: rerun the full gate matrix (make gambitffi,
   gambitcheck/gambitkernel/gambiteval/gambitseedcheck/gambitgencheck/
   gambittwins/adaptercheck/gambitvars/gambitstatics + the new non-UTF-8 public
   check) — all green, assertive verdicts, watchdogged.

Commit on gambit-ffi-p1b (clear message, e.g. "p1b-stage1-r2: fix
joltffi-read-bytes u8vector->list + non-UTF-8 public check; apply argon P3
items"). Update the findings file inside p1b-stage1/ (or add a short r2
section). Never push; touch nothing outside the worktree
/Users/sto/workspace/jolt-ffi-p1b.

Return: Completed-GMT/Local/Coding-Agent/Session-ID header, per-fix
fixed/not-fixed with file:line, and the final gate summary line.
