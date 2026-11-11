Created-GMT: 2026-11-09 07:4x GMT (real header below)
Coding-Agent: muse
Session-ID: c4bb7152-e89b-41a2-aa70-5ace46522b30

# Task: close P1-1 — eliminate ambiguous row-level lineage recovery (astra's confirmation round)

Role: VM Engineer (precise fix against a reviewer's repro)

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-09 | Status: active
- EXECUTION CONSTRAINT: no ssh, no local slow tests. Report commands instead.

## The verdict (read fully)

collab/yang-clojure/1794210000000-architect-cc1-p1-confirm.findings.md
— REQUEST CHANGES: P1-2 is MET (do not touch strip-tracking/construct again).
P1-1 remains open.

## The defect

macro.cljc:693 lineage-origin-for matches candidates by rows-VECTOR identity OR
ELEMENTWISE row identity. Distinct occurrences of identical content share row
objects (packet-of:110 retrieves from a shared index), so an earlier candidate's
elementwise match wins over the actual source packet's exact match — one
occurrence recovers ANOTHER's origin.

Repro (add as a pin; must fail before, pass after):

  (defmacro pick-second [a b]
    (conj [] (first b) (first (rest b))))
  (defmacro feed [x]
    (yin/application (yin/variable 'pick-second)
      [x (yin/application (yin/variable 'f) [(yin/literal 1)])]))
  (defn f [y] y)
  (feed (f 1))

Expected: reassembling b recovers the FEED-EXPANSION origin ([:retained
feed-expansion-origin]), never a's source admission.

## Required correction (astra's words)

"Prefer exact container lineage and eliminate first-match selection among
ambiguous row-level matches; propagate occurrence-specific lineage or
explicitly refuse ambiguity."

Implement it in src/cljc/yin/vm/macro.cljc. Keep P1-2's fix intact. Preserve all
existing pins.

## Worktree

Reuse /tmp/cc1-p1 (it holds the current patch over fa70516f). Keep changes
uncommitted. Report git diff --stat and the exact verification commands.

Begin the final response exactly with Completed-GMT / Completed-Local.
