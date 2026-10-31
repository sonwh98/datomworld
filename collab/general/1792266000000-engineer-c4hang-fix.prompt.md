Created-GMT: 2026-10-16 19:30:00 GMT
Created-Local: 2026-10-17 02:30:00 +07 (+0700)
Coding-Agent: claude
Session-ID: 44bbfa51-b7b2-4723-8dfd-b8a01e17d888

# Task: Fix the live-session comprehension hang (the linker undischarged spin) — the failing regression test is yours to make pass

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude opus (opus-5.5) | Assigned: 2026-10-17 02:30:00 +07 (+0700) | Status: active | Rationale: the owner named opus-5.5 for this fix; the continuation/undischarged machinery is your depth

WORK TREE: /Users/sto/workspace/datomworld-c4hang (branch c4hang-fix, based on origin/master 8b84437bb).
The worktree carries an UNCOMMITTED failing regression test:
test/yin/repl/dht_process_test.clj — `prompt-returns-after-filtered-comprehension-assignment-test`
(appended at the file's end, using the file's own spawn/type!/await-line helpers). Do not touch other
worktrees (datomworld-c4-i6 runs another round; datomworld-*-srvm-* belong to the srvm seat). Do not stage
or commit; the landing commit includes your fix plus this test going green.

THE BUG (owner-reported live, thread-dumped twice):
- At the JVM prompt, after `(lang :yang.python/antlr)`, the submission
  `even_squares = [x**2 for x in range(1, 11) if x % 2 == 0]` never returns to the prompt: the session
  spins at ~100% CPU indefinitely. The owner's live thread dump (on master fa91caa3 — the earlier cbor
  fix IS in their build): the spin is in `yin.vm.linker/undischarged`'s verdict fn — linker.cljc:1140,
  `(every? (fn [s] (precedes? (:at d) (:at s))) sites)` inside `(filter ... (unconditional defs))`,
  realizing a LAZY seq (LazySeq.force/realize frames), 281+ seconds of CPU. An earlier dump layer showed
  dao.jing.cbor/blen frames (that reflective site was fixed and landed as fa91caa3 — a different stall).
- The failing regression test reproduces the owner's observable: the spawned JVM REPL takes the lang
  switch and the two comprehension submissions, closes stdin, and NEVER EXITS within 90s (a control
  scenario, lang switch + `print(1+2)`, exits promptly — same run, same helpers).

YOUR JOB:
1. DIAGNOSE: why does the undischarged verdict's lazy seq realize without bound on this submission's
   publication/indexing path? (The comprehension's lowering, the auto-index publication, the occurrence
   seqs feeding the verdict — find where unbounded enters. The orchestrator has NO hypothesis this time;
   the earlier encoder guess was disproven.)
2. FIX at the real defect: the verdict's algorithm, its inputs, or the producer feeding it. Do not weaken
   the test; do not add timeouts in place of the fix; keep the landed I5/I6 semantics intact.
3. CLASSIFY (the namespace's other red, seen in a killed run):
   `a-reader-follows-the-publisher-s-head-across-processes` failed with "(require 'my.lib) never answered"
   and an earlier window showed a fetched-blob `:derivation-mismatch` → "Module link refused:
   descriptor-defect". Determine: pre-existing on master, caused by the srvm-module-pins merge (ce76c846),
   or caused by the I5/I6 slices. Report the classification with evidence; fix it ONLY if this round's
   changes caused it — otherwise report it for the owner/srvm seat with your evidence.
4. VERIFY (foreground, one at a time, waiting; no background runs, no watchers; complete the turn):
   - The failing regression test PASSES: clj -M:test -i :slow -n yin.repl.dht-process-test (the whole
     namespace — including your classification of the other red; the run takes a while, be patient).
   - bb gen:python-antlr and bb build:yin-repl-node first if builds are stale (they were run at worktree
     creation; re-run if in doubt).
   - The JVM quick tier: bb test:clj.
   - kondo + cljstyle on touched files.
   Report exact counts per lane and the full diagnosis with file:line.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
