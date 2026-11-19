# Landing-evidence mirror — jupiter gate run fixorder-g2-20261119-043834

Tested revision: branch fix/constructed-collection-order @ 39562f7c (pre-master-merge)
and, in master-redcheck.out / branch-mergecheck.out, origin/master @ 657f01ff
detached (the master-red comparison) and the merged branch (post-merge).

- summary.txt: per-suite rc's (bbtest-rc=1, daostream-rc=0)
- bbtest.out: the JVM attempt of `bb test` only (the task order runs
  test:clj first): 5,181 tests / 400,386 assertions / 11 failures, ending
  at "Error while executing task: test:clj". It establishes NO Node or
  Dart full-quick result — the run stopped at the failing JVM task, and
  this log carries no Node/Dart output. (r4 correction: an earlier
  revision of this README claimed Node+Dart green from this file.)
- daostream.out: the dao.stream precondition — all three lanes PASS
  (JVM 589 / Node 579 / Dart 509 tests, all exit 0); this file, not
  bbtest.out, is the only tri-host result in the mirror
- master-redcheck.out: the three flagged namespaces on detached origin/master 657f01ff — 2 failures (pre-existing master red)
- branch-mergecheck.out: the same namespaces on the branch after merging master — same 2 failures only; the 9 arm-violation failures cleared

Mirrored by the orchestrator so the evidence chain is locally reviewable (architect
sign-off finding: landing evidence could not be certified from the local chain).
