Created-GMT: 2026-10-18 09:40:00 GMT
Created-Local: 2026-10-18 16:40:00 +07 (+0700)
Coding-Agent: claude-sonnet-5-5

# Task: the C3 completion gate — produce ruling 14's evidence at the recorded head (verification only)

WORK TREE: /Users/sto/workspace/datomworld-c3gate, branch c3-completion, HEAD 79db800e (master =
b0b49c11 + docs). This is the C3 exact-integer phase's completion gate. THE AUTHORITY:
docs/design/yang.antlr.md section 8.5.4, the block introduced by "C3 is complete only with (ruling
14):" — read it and its surrounding subsections (the S7 detectors, c3_programs.cljc,
c3-corpus-v1.txt, the checked-in operand table, the C1 corpus) before running anything.

THIS IS VERIFICATION ONLY: no code changes, no golden rewrites, no detector edits. If anything fails,
that is a FINDING for the report — a failed detector or a moved golden is exactly what this gate
exists to surface.

PRODUCE, per ruling 14's own bullets:

1. THE TWELVE LANES: all four VMs on JVM, Node and Dart (Dart compiled to JS is not claimed).
   Source-level tests on the JVM; parserless forms on Node and Dart. Run the C1+C3 corpus suites on
   each lane and record tests/assertions/failures per lane. Invocation notes: the Node lane
   (mise exec -- bb test:cljs) and the Dart lane (mise exec -- bb test:cljd) take 10-30 minutes each —
   run them in the background and continue with the JVM work meanwhile; on "Patch does not apply"
   during a build, rm -rf build/antlr lib/antlr and rerun (the generator is not idempotent over
   already-patched trees — known).
2. GOLDEN BYTE FIXTURES on each host, in place of directed host-pair runs; confirm the Jing fixtures
   are unchanged against the committed pins (the byte-identity check per host).
3. THE FULL C1 CORPUS (not a sample) on its lanes.
4. THE GENERATED OPERANDS: the checked-in table with CPython-computed expectations — verify the
   corpus covers the boundaries around 2^53, 2^63, 2^64, promotion followed by cancellation, the
   divmod, shift, power, conversion, key, and hash(-1) detectors, and the C1 signed-zero cases.
   Confirm the expectations in the table are CPython-computed (spot-check several against CPython
   yourself if CPython is available; record the version).
5. MUTATION EVIDENCE, the seven named mutations — each shown failing its detector ONCE and recorded
   in your report: disabled promotion, skipped demotion, double-coerced keys, omitted floor
   adjustment, abs(n) for tag 3, host number? as the only scalar gate, and a prematurely narrowed
   shift count. Do this by TEMPORARILY mutating the source in your working tree (never committed),
   running the specific detector, capturing the failure, then RESTORING with git checkout. Record
   each mutation's diff-hunk summary + the failing assertion in the report.

COMMIT NOTHING (the branch stays at 79db800e; your report and any temporary artifacts live in
collab/, untracked). Do NOT push, do NOT touch master.

SINGLE-TURN COMPLETION with the lanes run to completion (the Node/Dart background waits included).
End with: the twelve-lane table, the golden-identity result per host, the C1 corpus result, the
operand-table spot-checks, the seven mutation evidences (each: the hunk, the detector, the failure),
and every finding (anything red, missing, or questionable).
