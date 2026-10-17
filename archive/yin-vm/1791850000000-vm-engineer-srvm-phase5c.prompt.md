Created-GMT: 2026-10-11 05:00:00 GMT
Created-Local: 2026-10-11 12:00:00 Asia/Bangkok

# Task: semantic-register-vm phase 5, slice 5c (scanners and the production linker format record)

Role: VM Engineer (yin.vm)

Implementers:
- Model: gpt-6.1-sol (codex), fresh thread. Review and sign-off go to claude-fable-5-1 (non-author family), never to codex.

Work ONLY in the git worktree `/Users/sto/workspace/datomworld-srvm-p5c`, branch `srvm-phase5c` (cut from master 993d5411, which contains the merged 4a and 4b). Do not `cd` to another checkout. `mise trust` and `npm ci` are done.

## Governing design (merged to master; do not amend it)

`docs/design/yin.vm.semantic-register-vm.derivations.md`, in this order: §0 (owner rulings, binding), §1-§3 and §4 as they apply to your slice, §6 (verification), §8 (regeneration), §10 (§10.3 (slice 5c; items 1-11) and §7: your files, acceptance list and ordering), §12 (portability). The frozen design `docs/design/yin.vm.semantic-register-vm.md` (§3, §8) and the landed code (`src/cljc/yin/vm/semantic_register/{code,linearize,analysis}.cljc`, `semantic_register.cljc`, `debruijn_register_code.cljc`, `debruijn_code.cljc`, `debruijn_register_compile.cljc` and `debruijn_linearize.cljc` as the old oracle) are your references. `docs/agents/build-n-test.md` defines the lanes. The design's numbered acceptance list for your slice IS your definition of done.

## Owner rulings that bind you (do not re-litigate)

- R and H are lowered FROM A alone, never from the tree and never through the old lowerers. No adapter. No version bump. R and H need NOT equal the old bytes or each other; the old compilers are an EXECUTION oracle only.
- A is the only code identity; new identities use Jing's default digest (BLAKE3). The only SHA-256 use is the existing descriptor-hash function.
- Do not change the design: on an ambiguity or conflict record it in the report with the evidence and the smallest reading you took.
- Verify every hand-derived number (the design marks them) on the FIRST run; report a wrong one plainly, never adjust silently.

## Rules

- **Tests that can fail.** Before reporting, for each behaviour you consider hardest (at least eight for this slice), apply a mutation to the source temporarily, confirm a NAMED test fails, and restore the file byte for byte (take a verified backup FIRST into `/Users/sto/workspace/datomworld-srvm-p5c/collab/`, and restore from it; check with `git diff --quiet HEAD -- <path>`). Put the table in the report. A surviving mutant is a finding.
- Portability traps (Dart/Node): use `#?(:cljd nil :clj ...)` with `:cljd` FIRST; no private-var access across namespaces; `(- x)` on a float; `for` over a >32-element seq (use keep/mapv); `(assoc nil :a 1 :b 2)` (start from `{}`); protocol methods need distinct param names; cljd EDN reader whitespace before closers; maps may carry nil-valued keys.
- **zsh does not word-split an unquoted variable.** Never put a namespace list in a variable; write each `-n namespace` literally.
- Foreground only; never `&`; never background a lane.
- Sandbox honesty: if a command cannot run in your sandbox (the Dart lane), record the exact command and error and say that lane was NOT RUN. The orchestrator re-runs every lane.
- Do NOT `git add` or `git commit`; do not stage `collab/`.

## Scope

Exactly the slice named in design §10.3 (slice 5c; items 1-11) and §7: its files to create/change, and nothing else. Slice 5c needs phase 3 only and runs concurrently with 5a; do not touch 5a's files. No production pipeline switches in 5c. The register backend's empty-obligations fixture in `linker_require_test.cljc` and the one test exclusion stay as they are until the orchestrator rules; you may add the two-line follow-up of design §10.4 ONLY IF all of 5c's gates pass, and report it separately.

## Verification (record exact counts per lane)

G0 `clojure -M:test -n <ns>` per namespace while iterating. Before reporting: the new and changed namespaces plus the semantic-register namespaces (code, linearize, analysis, load-paths, vm, walker-trace, parity, effects, link) and the old suites the slice touches, on JVM and Node (shadow-cljs compile with an `:ns-regexp` config-merge, confirming "Testing <ns>" per new namespace). Dart: attempt `bb src/dev/cljd_agg.clj --only ns1,ns2,...`; if the sandbox blocks it, say NOT RUN. `clj -M:kondo --lint` and `cljstyle check` on changed paths; trailing-whitespace scan. Do not run the full `bb test`; the orchestrator runs G2/G3. Slow tests the design says must actually run: run them and quote the counts.

## Report

Write `collab/1791850000000-vm-engineer-srvm-phase5c.report.md`. Begin it exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: changed files (new versus modified); exact lane outcomes; the numbered acceptance list with PASS/FAIL and evidence per item; the mutation table; every ambiguity or finding and what you did; unresolved concerns; incomplete work. Do not claim edits or runs that did not occur.
