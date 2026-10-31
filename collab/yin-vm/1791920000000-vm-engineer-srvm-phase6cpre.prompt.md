Created-GMT: 2026-10-11 07:30:00 GMT
Created-Local: 2026-10-11 14:30:00 Asia/Bangkok

# Task: semantic-register-vm phase 6, slice 6c-pre (the stack kernel's tail-completion seam)

Role: VM Engineer (yin.vm)

Implementers:
- Model: gpt-6.1-sol (codex), fresh thread. Review and sign-off go to claude-fable-5-1 (non-author family), never to codex.

Work ONLY in the git worktree `/Users/sto/workspace/datomworld-srvm-p6cpre`, branch `srvm-phase6cpre` (cut from master f11771e9, which contains the merged 4a/4b, the phase-5 and phase-6 designs and all amendments). Do not `cd` to another checkout. `mise trust` and `npm ci` are done.

## Governing design (merged to master; do not amend it)

`docs/design/yin.vm.semantic-register-vm.continuation.md`: §0 (rulings, binding), the sections your slice names in §8.3 "6c-pre" (the seam) with §4.3, §9 (verification honesty), §12 (portability). The amended frozen design (`yin.vm.semantic-register-vm.md` §4.1-4.3, §6, §10 phase 6), the amended UCF (§7.3.4, §7.4, §7.11) and the amended carrier draft (`yin.vm.ucf-transport-tuples.md`) are references. Landed code to build on and mirror: `src/cljc/yin/vm/semantic_register.cljc` and its tests, `src/cljc/yin/vm/semantic_register/{code,linearize,analysis}.cljc`, `yin.vm.handoff` / `yin.vm.ucf` and the existing safepoint harness tests. `docs/agents/build-n-test.md` defines the lanes. The design's numbered acceptance list for your slice IS your definition of done.

## Owner rulings that bind you (do not re-litigate)

- A is the only code identity; new identities use Jing's default digest (BLAKE3). No legacy: no version bump, no compat shims, one body version, no support for older bodies. The AST is always retrievable (unitarity). The vector-to-datoms mapping is retired. dao.stream is the complexity boundary: do not design transport mechanics.
- Do not change the design: on an ambiguity or conflict record it in the report with the evidence and the smallest reading you took. Verify every hand-derived number on the FIRST run; report a wrong one plainly.

## Rules

- **Tests that can fail.** Before reporting, for at least eight behaviours you consider hardest, apply a mutation to the source temporarily, confirm a NAMED test fails, restore byte for byte (take a verified backup FIRST into `/Users/sto/workspace/datomworld-srvm-p6cpre/collab/` and restore from that backup; check with `cmp` and `git diff --quiet HEAD -- <path>`). Put the table in the report. A surviving mutant is a finding.
- Portability traps (Dart/Node): `#?(:cljd nil :clj ...)` with `:cljd` FIRST; no private-var access across namespaces; `(- x)` on a float; `for` over a >32-element seq (use keep/mapv); `(assoc nil :a 1 :b 2)`; distinct protocol param names; cljd EDN reader whitespace before closers; maps may carry nil-valued keys (preserve them).
- **zsh does not word-split an unquoted variable.** Write each `-n namespace` literally. Foreground only; never `&`.
- Sandbox honesty: if a command cannot run (the Dart lane), record the exact command and error and say NOT RUN; the orchestrator re-runs every lane.
- Do NOT `git add` or `git commit`; do not stage `collab/`.

## Scope

Exactly the slice named in design §8.3 "6c-pre" (the seam) with §4.3. Edit `src/cljc/yin/vm/debruijn/stack.cljc` ONLY for the seam (park-entry builders mark a tail-call park `:resume-mode :return-result`; `stack-restore` delivers such an entry by the return transition) and create `test/yin/vm/debruijn/stack_tail_completion_test.cljc`. Non-tail parks and every existing stack test must stay green and unchanged: run the full stack-kernel test group (`yin.vm.debruijn.stack-test`, `stack-effects-test`, `stack-parity-test`, `debruijn-test`, `debruijn-code-test`, the parity and contract suites, the semantic-ffi/continuation suites that run the stack kernel) and report counts. The register kernel and the semantic-register files are not edited.

## Verification (record exact counts per lane)

G0 `clojure -M:test -n <ns>` per namespace while iterating. Before reporting: the new and changed namespaces plus the semantic-register namespaces and the old suites the slice touches, on JVM and Node (shadow-cljs compile with an `:ns-regexp` config-merge, confirming "Testing <ns>" per new namespace); Dart attempt `bb src/dev/cljd_agg.clj --only ns1,ns2,...` (NOT RUN with the exact error if blocked); `clj -M:kondo --lint` and `cljstyle check` on changed paths; trailing-whitespace scan. Do not run the full `bb test`.

## Report

Write `collab/1791920000000-vm-engineer-srvm-phase6cpre.report.md`. Begin it exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: changed files (new versus modified); exact lane outcomes; the numbered acceptance list with PASS/FAIL and evidence; the mutation table; every ambiguity or finding and what you did; unresolved concerns; incomplete work. Do not claim edits or runs that did not occur.
