Created-GMT: 2026-11-09 (real header in your report)
Coding-Agent: muse
Session-ID: 36b9d093-07fc-416d-ba54-5b1f44faa35e

# Task: root-cause the PHP engine regression — `<?php echo 1 + 2;` no longer matches its recorded transcript

Role: Engineer (diagnosis first; fix only if contained to the php surface)

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-09 | Status: active
- EXECUTION CONSTRAINT: your sandbox cannot ssh. Do NOT run slow-tier suites or
  anything long locally (owner ruling; a sibling round violated this). Static
  reading + git archaeology only. If a run is needed, STOP and report what to run.

## The evidence (measured, repeated, two seats, three hosts)

yin.repl.shell-session-test/local-shell-process-corpus, isolated on CLEAN MASTER:
- shiva (load-free): transcript mismatch on vm-corpus-{ast-walker,semantic,stack,
  register}, the failing line being literally `<?php echo 1 + 2;`
- jupiter: identical signature
- A sibling seat saw StackOverflowError on the same test from master on shiva.
- The e2e corpus run also shows recorded gaps now failing with
  "Call to undefined function gc_collect" (gc_023.phpt, gc_013.phpt).

## The question

`echo 1 + 2;` is the most basic PHP program. It no longer matches its recorded
transcript — this is NOT stale-record drift, it is the engine producing different
output (or failing) for a trivial program. The likely cause: a P8/P9 removal that
dropped something the recorded transcript depended on (a builtin, a function, a
print path) — cf. gc_collect being undefined, which looks like the same class.

## Your job (static)

1. Find the recorded transcript for that corpus line: grep the shell-session
   corpus fixtures for "echo 1 + 2".
2. Determine what the expected output is and what the current engine would produce:
   trace the php frontend/VM path for `echo` and integer addition — is `echo` a
   builtin, a lowering arm, a prelude function? Does `gc_collect` (and the other
   missing function) exist in the current prelude/builtins?
3. git archaeology: 0cc8dc1a (the P8 legacy retirement merge), 094ca161 (the
   post-P8 reconciliation), 359c0378 — which commit removed or renamed the
   builtin/arm/function the transcript needs? Show the diff hunk if you can find it.
4. Verdict: is the corpus expectation wrong (re-record) or is the engine missing
   something it should have (regression → fix)? Say which, with file:line evidence.
   If it is a real missing builtin, propose the minimal restoration and say
   whether it is contained to src/cljc/yang/php/ (then you may implement it in a
   scratch worktree on /tmp, no commits) or needs a design ruling (STOP and report).

Begin the final response exactly with Completed-GMT / Completed-Local.
