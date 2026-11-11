Created-GMT: 2026-11-10 (real header below)
Coding-Agent: muse
Session-ID: 83426f4f-aeaa-437f-b4b5-25496ba681de

# Task: fix the macro-build event-record regression on master (1 test, JVM)

Role: VM Engineer (precise fix; the nightly evidence is exact)

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-10 | Status: active

## The failure (nightly 20261110-0805, jupiter JVM, master 8cd9fc92)

test/yang/clojure/macro_build_test.clj:144 — a-build-expands-between-file-backed-media:
  expected: (= [nil :arity nil] (mapv (fn* [p1#] (:kind (peek p1#))) events))
  actual:   [nil nil nil]
i.e. the SECOND event should be recorded with kind :arity and is now missing.

## Prime suspect (verify, do not assume)

cc1's provenance work (fa70516f..ba2deb73, landed b1b9abae) rewrote the
expansion-record/event path in src/cljc/yin/vm/macro.cljc — the P1-1 fix's
ambiguity-refusal (lineage-origin-for returns nil on ambiguity) may be
suppressing the :arity event row in this build path, OR the metadata strip
(P1-2) removed something the event peek needs. Read the test first, then the
macro.cljc event-record code (final-call-site-metadata / the events vector /
the arity-event emission), and decide.

## Rules

- Fix against THIS failing test only; re-run it green.
- Do not weaken the assertion. If the test is right and the record is wrong,
  the fix restores the :arity event. If the record semantics legitimately
  changed (explain precisely how U1/provenance requires it), report back with
  the reasoning BEFORE changing the test — STOP and report instead of editing.
- Keep P1-1..P1-4 fixes intact (they are architect-approved).

## Where

Scratch worktree on the laptop: reuse /tmp/cc1-p1 (it is on callsite-contract-
clojure + uncommitted fixes; git fetch and merge origin/master first, resolve
any conflict against this task). Uncommitted; report git diff --stat.

## Verify (commands for the orchestrator)

JVM: clojure -M:test -n yin.vm.macro-build-test (the failing namespace),
then yang.clojure-callsite-test + yin.vm.encoder-test (cc1's own battery, to
prove no regression). Node: the same battery selector. Report the exact
commands. Begin with Completed-GMT / Completed-Local.
