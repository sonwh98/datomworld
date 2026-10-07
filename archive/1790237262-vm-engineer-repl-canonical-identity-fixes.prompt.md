Created-GMT: 2026-09-24 08:07:42 GMT
Created-Local: 2026-09-24 15:07:42 +0700
Coding-Agent: claude
Session-ID: feb934cf-3962-4aed-b4bc-f7cbc2d1605d

# Task: Fix canonical image identity and doc-sync in yin.repl

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-24 15:07:42 +0700 | Status: active | Rationale: Fix P1 canonical image identity regression and P3 doc sync

The adversarial reviewer (gpt-6-sol) has identified one blocking P1 issue
and one minor P3 doc-sync issue from round 2 review:

## Findings to address

### 1. P1: Canonical Image Identity (core.cljc around line 106)

In addressing P2, `chain-hash` set the VM record's `:hash` to a chain hash
`sha256(previous || H(new-segment))` rather than canonical H / R over the
loaded `:segment`.
The governing design contracts (`yin.vm.debruijn.stack.md:204`,
`yin.vm.debruijn.register.md:631`) specify that the VM record's `:hash` and
reified continuation `:hash` MUST be the canonical H (for stack VM) or R
(for register VM) computed over the loaded `:segment`.
A differing hash breaks continuation validity and identity invariants.

Fix:
- Restore canonical H / R in the VM record's `:hash` field in both
  `append-stack-image` and `append-register-image`. Specifically:
  `(:hash (stack/load-image ...))` or `(stack-code/image-hash combined)` for
  stack, and `(rcode/register-hash combined)` for register.
- If a REPL chain identifier is useful for REPL diagnostics or tracking, it
  may be stored in the REPL session state (e.g. `:repl/chain-hash`), but the
  VM record itself MUST conform to the canonical specification:
  `(:hash vm)` is the canonical image hash of `(:segment vm)`.
- Update test `de-bruijn-loads-hash-only-their-new-segment` (and its name/
  assertions) in `test/yin/repl/core_test.cljc` to assert that after multiple
  incremental loads, the VM's `:hash` strictly equals canonical H (for stack)
  and canonical R (for register) over the combined segment.

### 2. P3: Doc-sync in `src/cljc/yin/vm/docs/yin.repl.md` (around line 72)

The section on datom-literal evaluation begins:
"Datom-literal evaluation runs on a VM-owned v2 ingress medium. A datom
program typed at the prompt is appended to the ast-walker's ingress ring
buffer (declared capacity 4096)..."

Update this text:
In v2 REPL with 4 VMs, the ingress and program media are owned by the REPL
session (`make-session`), not specifically by `ast-walker`. Any of the 4 VMs
receives its program input from the session-owned ingress medium.

## Acceptance Criteria

1. In `src/cljc/yin/repl/core.cljc`, `(:hash vm)` on `:stack` and `:register`
   is always the canonical image hash (H and R respectively) of the loaded
   `(:segment vm)`.
2. Tests in `test/yin/repl/core_test.cljc` verify that VM `:hash` matches
   canonical H / R across multiple incremental evaluations.
3. `src/cljc/yin/vm/docs/yin.repl.md` reflects that program media are owned by
   the REPL session across all 4 VMs.
4. `clj -M:kondo --lint src/cljc/yin/repl/core.cljc test/yin/repl_test.cljc test/yin/repl/core_test.cljc`:
   0 errors, 0 warnings.
5. `cljstyle check src/cljc/yin/repl/core.cljc test/yin/repl_test.cljc test/yin/repl/core_test.cljc`:
   clean.
6. All modified lines: <= 80 columns, pure ASCII.
7. JVM tests pass: `clj -M:test -n yin.repl-test -n yin.repl.core-test`.

Work in `/Users/sto/workspace/worktree-yin-repl-stream`. Do not commit.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes, and any unresolved concerns.
