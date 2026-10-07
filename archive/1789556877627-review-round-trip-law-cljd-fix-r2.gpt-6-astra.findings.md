Coding-Agent: codex
Model: gpt-6-astra (resumed conversation, thread 01a0a9e7-52d1-79f0-a1b7-abb39ecdf104)

# Review r2: confirmation of the Transit decoder follow-up fix

**Findings 1 and 2 are closed. The current diff is ready to proceed toward
Architect sign-off.**

1. **Closed — Transit metadata leak.** Both reconstruction sites in each
   decoder now clear newly minted wrapper metadata:
   `src/cljd/dao/stream/transit.cljd:270` and its explicit `~#list` branch
   at line 360; `src/cljd/dao/stream/transit/cljd.cljd:277` and its
   explicit branch at line 367. Element metadata remains intact; explicit
   `~#with-meta` decoding still restores transmitted metadata afterward.
   The new tests exercise actual decoding — `test/dao/stream/transit_test.cljc:60`
   checks both metadata and the pinned hash, closing the previously
   missing codec-to-hash coverage.

2. **Closed — inaccurate documentation.** `docs/design/dao.jing.md:423`
   now identifies both codec paths, describes their fixes, and accurately
   scopes the remaining risk to other Dart payload constructors.

3. **Still correct — root cause.** Unchanged from r1.

4. **Still correct — normalization fixes and metadata preservation.** Both
   original fixes remain unchanged and clear only newly constructed
   wrappers before restoring meaningful input metadata.

5. **Still correct — regression guards.** Original tests and pinned hash
   unchanged. Coverage now includes Transit decoding. CLJD execution
   remains essential; the new v2 test explicitly does no substantive
   checking on JVM/CLJS.

6. **Still correct — canonicalization and CLJS assessment.** List/seq
   canonical equivalence and meaningful metadata remain preserved.
   Additional production changes are Dart-only; no new CLJS regression
   apparent. CLJS runtime verification remains outstanding (untested by
   `bb test:cljd`).

Inspected the actual current diffs and surrounding code; did not rerun
suites (relied on orchestrator-reported verification).

**No further fix is required for findings 1–6.**
