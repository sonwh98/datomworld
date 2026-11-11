Created-GMT: 2026-11-10 (real header below)
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: approve a test-side slot-order update (your U1 change moved it)

Role: Lead System Architect (final confirmation; one decision)

## The situation

Master 8cd9fc92's JVM nightly: ONE failing test —
test/yang/clojure/macro_build_test.clj:144, a-build-expands-between-file-backed-media:
  expected: (= [nil :arity nil] (mapv #(:kind (peek %)) events))
  actual:   [nil nil nil]

An engineer round (STOP-and-report, correct behavior — it was an assertion
change) traced it WITHOUT touching code: commit 847aa885 (the signed U1
implementation) appended provenance slots to the event-row shape
(macro.cljc:1290). The old shape ended [... output-root error], so  read
the last slot (:kind was reachable). The new shape pins the correspondence at
(nth event 11) — confirmed by two green approved tests
(clojure_callsite_test.cljc:523 and macro_test) that already use the new
positions. The :arity event IS recorded; only the test's accessor is stale.

## The proposed fix (one line, assertion strength unchanged)

test/yang/clojure/macro_build_test.cljc:144:
  (mapv #(:kind (peek %)) events)  ->  (mapv #(:kind (nth % 10)) events)

## Your decision (yes/no with reasons)

Is the proposed assertion the correct reading of the post-U1 event-row shape —
i.e., is slot 10 (nth 10) where :kind now lives for these build events — or is
there a better accessor (e.g. the field name the new rows use)? Confirm and
the orchestrator lands the one-liner and re-runs the failing test + the cc1
battery. Reject with the correct accessor if slot 10 is wrong.

Constraints: read-only. Begin with Completed-GMT / Completed-Local.
