NOTE (fresh session on a different model): you are NOT the model that wrote the existing work; the previous implementer (codex) ran out of credits. You have no memory of it. Everything it produced is on disk in this worktree (committed HEAD plus any uncommitted edits shown by `git status` / `git diff`) and in `collab/` (its report, its logs, the reviewer findings). Read them first, verify what is already done against the instructions below, and finish the rest. You have a shell and edit rights in this worktree: use them. Never commit or stage. If a command is refused, record the exact command and error and say so in the report.

Created-GMT: 2026-10-11 05:00:00 GMT
Created-Local: 2026-10-11 12:00:00 Asia/Bangkok

# Task: semantic-register-vm phase 5, slice 5b (A -> R)

Role: VM Engineer (yin.vm)

Implementers:
- Model: gpt-6.1-sol (codex), fresh thread. Review and sign-off go to claude-fable-5-1 (non-author family), never to codex.

Work ONLY in the git worktree `/Users/sto/workspace/datomworld-srvm-p5b`, branch `srvm-phase5b` (cut from master 71b4f4d7, which contains the merged 4a, 4b and 5a). Do not `cd` to another checkout. `mise trust` and `npm ci` are done.

## Governing design (merged to master; do not amend it)

`docs/design/yin.vm.semantic-register-vm.derivations.md`, in this order: §0 (owner rulings, binding), §1-§3 and §4 as they apply to your slice, §6 (verification), §8 (regeneration), §10 (§10.2 (slice 5b; items 1-11): your files, acceptance list and ordering), §12 (portability). The frozen design `docs/design/yin.vm.semantic-register-vm.md` (§3, §8) and the landed code (`src/cljc/yin/vm/semantic_register/{code,linearize,analysis}.cljc`, `semantic_register.cljc`, `debruijn_register_code.cljc`, `debruijn_code.cljc`, `debruijn_register_compile.cljc` and `debruijn_linearize.cljc` as the old oracle) are your references. `docs/agents/build-n-test.md` defines the lanes. The design's numbered acceptance list for your slice IS your definition of done.

## Owner rulings that bind you (do not re-litigate)

- R and H are lowered FROM A alone, never from the tree and never through the old lowerers. No adapter. No version bump. R and H need NOT equal the old bytes or each other; the old compilers are an EXECUTION oracle only.
- A is the only code identity; new identities use Jing's default digest (BLAKE3). The only SHA-256 use is the existing descriptor-hash function.
- Do not change the design: on an ambiguity or conflict record it in the report with the evidence and the smallest reading you took.
- Verify every hand-derived number (the design marks them) on the FIRST run; report a wrong one plainly, never adjust silently.

## Rules

- **Tests that can fail.** Before reporting, for each behaviour you consider hardest (at least eight for this slice), apply a mutation to the source temporarily, confirm a NAMED test fails, and restore the file byte for byte (take a verified backup FIRST into `/Users/sto/workspace/datomworld-srvm-p5b/collab/`, and restore from it; check with `git diff --quiet HEAD -- <path>`). Put the table in the report. A surviving mutant is a finding.
- Portability traps (Dart/Node): use `#?(:cljd nil :clj ...)` with `:cljd` FIRST; no private-var access across namespaces; `(- x)` on a float; `for` over a >32-element seq (use keep/mapv); `(assoc nil :a 1 :b 2)` (start from `{}`); protocol methods need distinct param names; cljd EDN reader whitespace before closers; maps may carry nil-valued keys.
- **zsh does not word-split an unquoted variable.** Never put a namespace list in a variable; write each `-n namespace` literally.
- Foreground only; never `&`; never background a lane.
- Sandbox honesty: if a command cannot run in your sandbox (the Dart lane), record the exact command and error and say that lane was NOT RUN. The orchestrator re-runs every lane.
- Do NOT `git add` or `git commit`; do not stage `collab/`.

## Scope

Exactly the slice named in design §10.2 (slice 5b; items 1-11): its files to create/change, and nothing else. Implement `register-image` in `src/cljc/yin/vm/semantic_register/derive.cljc` per design §2 (5a landed the resolved vector, `addresses`, `stack-image` and the oracle runners in `derive_test.cljc`: reuse them, do not re-derive), extend `derive_test.cljc`, and create `test/yin/vm/debruijn_register_code_test.cljc` (item 10). Nothing else; `debruijn_register_code.cljc` and the register kernel are untouched. LESSONS from the 5a review (Fable): (1) every emitter rule needs a golden or a runtime row that can fail: include R goldens and runtime rows reaching a value on BOTH pipelines for `:ffi-call` (explicit `{:op/echo (fn [a b] [a b])}` bridge) and `:stream/put` (onto a made stream) so mutants dropping operands or swapping them die; (2) a validator that permits a malformed image does not kill a mutant: assert independent structural checks; (3) the oracle composition needs `list`, `vector`, `inc` as 5a's already does; (4) item 9 (the slow C4 execution oracle) MUST actually run on JVM and Node, quote both counts; if Node cannot finish it, say so and give the exact stall point.

## Verification (record exact counts per lane)

G0 `clojure -M:test -n <ns>` per namespace while iterating. Before reporting: the new and changed namespaces plus the semantic-register namespaces (code, linearize, analysis, load-paths, vm, walker-trace, parity, effects, link) and the old suites the slice touches, on JVM and Node (shadow-cljs compile with an `:ns-regexp` config-merge, confirming "Testing <ns>" per new namespace). Dart: attempt `bb src/dev/cljd_agg.clj --only ns1,ns2,...`; if the sandbox blocks it, say NOT RUN. `clj -M:kondo --lint` and `cljstyle check` on changed paths; trailing-whitespace scan. Do not run the full `bb test`; the orchestrator runs G2/G3. Slow tests the design says must actually run: run them and quote the counts.

## Report

Write `collab/1791950000000-vm-engineer-srvm-phase5b.report.md`. Begin it exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: changed files (new versus modified); exact lane outcomes; the numbered acceptance list with PASS/FAIL and evidence per item; the mutation table; every ambiguity or finding and what you did; unresolved concerns; incomplete work. Do not claim edits or runs that did not occur.
