Created-GMT: 2026-11-09 08:2x GMT (real header below)
Coding-Agent: muse
Session-ID: 1dccb4ae-0261-4e80-88ca-2a5b98bc4d8b

# Task: close P1-4 — stale tracking metadata survives packet content replacement

Role: VM Engineer (fourth provenance edge in this family; a targeted fix)

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-09 | Status: active
- EXECUTION CONSTRAINT: no ssh, no local slow tests; report the commands.

## The verdict (read fully)

collab/yang-clojure/1794214000000-codex-cc1-regate.findings.md

## The defect

macro.cljc:909 p-assoc replaces packet CONTENTS while retaining the original
vector's metadata; recover-packet:887 then trusts that ::origin-of and bypasses
lineage recovery. Executed repro (JVM, returns :ok but records wrong history):

  (defmacro pick-second [a b]
    (assoc a 0 (first b) 1 (first (rest b))))
  (defmacro feed [x]
    (yin/application (yin/variable 'pick-second)
      [x (yin/application (yin/variable 'g) [(yin/literal 2)])]))
  (defn f [y] y)
  (defn g [y] y)
  (feed (f 1))

Wrong: {[] [:retained [:source :program-in 0 0]]} (a's source)
Right: the FEED expansion's origin.

## Required correction (codex's words)

"Invalidate stale tracking metadata when packet contents change, then recover
provenance from the resulting packet's actual lineage - or refuse
missing/ambiguous provenance. Add this case as a regression."

## Design consideration (address it in your report)

This is the FOURTH edge in one family (fabricated :constructed on reassembly;
:origin-of breaking literal encoding; ambiguous row-level first-match; now stale
metadata surviving content replacement). Each prior fix patched one operation.
If there is a structurally sound invariant that makes every such edge impossible
— e.g. tracking metadata is only valid for the exact vector object it was
attached to and MUST be dropped by any operation that changes contents (assoc/
conj/subvec on a packet), enforced at ONE choke point — implement that instead of
operation-by-operation patches. State which you chose and why.

## Where

Worktree /tmp/cc1-p1 over f440a2fb (git fetch; reset to origin/callsite-
contract-clojure first — that tip now CONTAINS the previous fix). Keep
uncommitted; report git diff --stat + the exact verification commands.

Begin the final response exactly with Completed-GMT / Completed-Local.
