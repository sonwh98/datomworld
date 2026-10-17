Created-GMT: 2026-10-11 07:45:00 GMT
Created-Local: 2026-10-11 14:45:00 Asia/Bangkok

# Task: adversarial review of slice 6c-pre (the stack kernel's tail-completion seam)

Role: Adversarial Reviewer / Lead System Architect. Worktree `/Users/sto/workspace/datomworld-srvm-p6cpre`, branch `srvm-phase6cpre`, HEAD 7f1cf08e (diff `git diff master...HEAD`). READ-ONLY except your findings file; no git add/commit. Write each `-n ns` literally; foreground only. Before applying any mutant take a verified byte backup of `src/cljc/yin/vm/debruijn/stack.cljc` into `collab/` and restore from it (`cmp` and `git diff --quiet HEAD -- src/`).

Author: codex (gpt-6.1-sol); you are the non-author family. Read the brief `collab/1791920000000-vm-engineer-srvm-phase6cpre.prompt.md`, the report `collab/1791920000000-vm-engineer-srvm-phase6cpre.report.md`, and the governing design `docs/design/yin.vm.semantic-register-vm.continuation.md` §4.3 (stack layout and the tail-completion seam; cumulative stack bases) and §8.3 "6c-pre". Orchestrator verified on JVM: stack-tail-completion + stack + stack-effects + stack-parity + semantic-ffi + continuation-invoke: 87 tests / 821 assertions, 0 failures. Dart and the full regression lane are not run by you.

Do, adversarially:
1. The seam: a tail `:call` effect park is marked `:resume-mode :return-result`; restore delivers by the RETURN transition (pop innermost frame, truncate to its cumulative base, push the value; halt with `[value]` and clear store context when K is empty). Compare with `:return` at `stack.cljc` (~766-772) and the existing park/restore (~555-568, ~748-752): any divergence; any path where a marked entry still executes the layout syntax after the call; non-tail parks, captured continuations, synchronous results unchanged?
2. Retained-FFI re-park and response decoding order preserved before the final delivery (design §4.3)? Host-fn effects vs FFI parks both covered?
3. Mutants: apply at least ten yourself (marker omitted; marker on non-tail; frame not popped; base not truncated or truncated to the wrong base; value not pushed; empty-K halt value wrong; store context not cleared; delivered twice; retained re-park skipped; tail call in operand position still running the following layout instructions; plus your own). KILLED (which test) or SURVIVED. Compare with the author's claim that none survives.
4. Scope: only `stack.cljc` and the new test namespace changed; no existing test touched; no semantic-register or register-kernel edit; portability (cljd/Node) of the new code and test.
5. Anything the author resolved by reading the design differently; honest claims in the report.

Write `collab/1791930000000-reviewer-srvm-phase6cpre.claude-fable-5-1.findings.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Then findings (severity | file:line | evidence | correction), answers 1-5, the mutant table, and end with exactly one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED
Do not claim runs that did not occur.
