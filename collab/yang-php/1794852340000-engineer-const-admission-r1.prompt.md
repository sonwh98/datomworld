Created-GMT: 2026-11-17 (dispatch time)
Coding-Agent: codex (gpt-6.1-terra, jupiter POOL 2 — RESUME of thread 01a1e3ef)
Session-ID: 01a1e3ef-d6d2-7f93-abc0-0846760901ce

# const-admission r1 — implement the astra constant ruling (RESUME of your dyn thread)

Worktree: ~/ci/dw-dynfix is now PUBLISHED at 831216f5 (your dyn work landed on
fix/dyn-continuation-producer and pushed). Continue IN THE SAME WORKTREE on a NEW branch from
master f32a4392: git switch -c fix/const-admission f32a4392 (do it; you have .git write? NO —
the seat pre-creates it; if checkout fails, report and stop). No commits (seat publishes).

## The ruling (READ FIRST — it corrects the dispatch's own errno-8 suggestion)
collab/yang-php/1794850396000-architect-constant-admission-ruling.findings.md — file it on
origin/master already. Key points:
- DELETE the bare-global rejection branch at lower.cljc:546; keep the exact prescribed shape
  (fallback arm php/const-get with namespaced fallback, builtins folding, else php/const-get-fq).
- Undefined constant = CATCHABLE Error via php/throw-error (php-src zend_throw_error); do NOT
  use php/guest-fatal, do NOT add errno-8 anything.
- define()/global const redeclaration = php/warn 2 + false (php/const-declare! is already
  correct — keep the handler-aware route).
- Correct the STALE refusal row at e2e_test.clj:235 (echo FOO; asserted unsupported) -> a
  runtime undefined-constant guard per the ruling.
- STRENGTHEN the fixture guards per the ruling's §4: the include-test fixture comparison does
  not assert fatal/status — add the guards (uncaught Error + status 255 for the unhandled row;
  normal completion nil-uncaught for the others).
- NON-WEAKENING: site-set admission, linker discharge, admission-before-execution, continuation
  validation, duplicate-class errno-64 fatal — all unchanged. Eval: caller's request constant
  table, no preflight/rollback.
- Do NOT implement warmup/runtime-composition changes (the subsystem-order mystery is the
  slow-tier selection gap hypothesis — record coverage separately, do not chase it as the fix).

## Acceptance (the ruling's list, before landing)
Fresh-process four-row results on every linked backend; caught/uncaught undefined-name guards;
redeclaration guards; builtin/namespace controls; unchanged admission and class-fatal
regressions; foundation gate (bb test:sub:clj dao.stream); then bb test:sub:clj yang.php once
(expect 0 failures). Record quick and slow coverage separately. Per-run log custody in
target/const-r1/. Findings file per convention.
