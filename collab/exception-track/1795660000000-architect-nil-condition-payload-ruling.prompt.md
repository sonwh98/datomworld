Created-GMT: 2026-11-25 04:3x GMT
Created-Local: 2026-11-25 11:3x +0700
Dispatched-By: master-green orchestrator seat (interactive; ruling needed to route a red)
Destination: agy CLI on shiva, model gemini-4-argon, effort high (ARCHITECT ruling lane)
Task-ID: 1795660000000-architect-nil-condition-payload-ruling

# ARCHITECT RULING: does a malformed nil thrown condition retain nil in the fresh TypeError payload?

Read-only ruling; findings only. This gates a 3-row red on master
(`yang.call.python-exception-test/python-malformed-condition-test`, :register/:semantic/:stack).

## The question

Guest code throws `nil` (or a non-map malformed condition) where a protocol condition is
expected. The runtime catches it and raises a FRESH TypeError. What are the fresh error's
`args`?

- The test expects `payload [nil]` — nil RETAINED as an argument
  (`test/yang/call/python_exception_test.clj:118-147`).
- The runtime's no-message sentinel (`src/cljc/yang/python/antlr/prelude.cljc:741-750`,
  predates the test) interprets host nil as "no message" and produces `[]`
  (the fallback the test was written against is at `:1064-1067`).

## Evidence on file (all in-repo; read, do not re-derive)

1. `docs/design/yang.antlr.md:3847-3866,3881-3883` — fresh thrown conditions; retention
   of valid protocol failures; SILENT on the args of the fresh error for invalid
   non-map conditions.
2. Landed precedent `collab/exception-track/1794483001000-to-yangphp-addendum-nil-guard-precedent-landed.md:9-15`
   — fresh TypeError + no provenance for non-map/nil; does not specify args.
3. The QA attribution (round 1795650000000, findings in the same collab dir): both `[]`
   and `[nil]` satisfy the narrow precedent; `:garbage` (a passing sibling case) must
   stay unchanged.
4. **CPython oracle (check on shiva: `python3 -c "import sys; e=TypeError(None); print(e.args)"`)**
   — if CPython retains `(None,)`, that is platform-parity evidence for RETENTION; the
   yang.python lane's stated goal is CPython-observable behavior for host-visible
   exception shapes. Also check `str(e)` on CPython vs what `py/make-exc` produces.

## Ruling deliverable

Findings `collab/exception-track/1795660000000-architect-nil-condition-payload-ruling.gemini-4-argon-high.findings.md`:

1. Rule: RETAIN nil in args (payload `[nil]`) or treat nil as no-message (`[]`)? Ground
   the rule in (i) the CPython oracle result, (ii) the platform's exception-parity
   contract, (iii) the existing precedent's scope.
2. Name the exact fix surface for the losing side: if RETAIN — the sentinel path in
   `prelude.cljc:741-750` (production; the yang.php seat implements); if no-message —
   authorize the expectation correction in `python_exception_test.clj:118-147` (the QA
   round implements).
3. State the non-weakening obligation either way: `:garbage` unchanged; fresh-TypeError
   and no-provenance requirements preserved; all other malformed-condition cases
   (non-nil non-map) unchanged.
4. One-line CPython probe commands for the orchestrator if you need more oracle data.

Read-only; no source edits; no test runs beyond the python3 oracle probes.

Begin the final response exactly with:
Completed-GMT / Completed-Local lines.
