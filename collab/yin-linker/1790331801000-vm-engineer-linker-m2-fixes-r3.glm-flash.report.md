# M2 gate-fix round 3 report (GLM-5.3-Flash subagent, verbatim extract)

Both round-3 P1 findings fixed, touching only the two permitted files.
All round-2 fixes and all existing tests preserved; no finding
conflicted with the spec (linker.md sections 4.1, 4.2).

Finding 1 — yin/def binding recorded at the entry path (was
linker.cljc:409). Fixed in tree-definition-occurrences at
src/cljc/yin/vm/linker.cljc:401-452: a yin/def application's binding is
now recorded at the invocation position — the application row's own
path extended one step past its two operands ((conj path [3 2]),
linker.cljc:432-434), the exact site tree-application-sites records for
a 2-operand application (linker.cljc:455-466) and the position where
the walker applies the operator only after the operands run
(ast_walker.cljc :eval-operand). A read inside the value operand
([... [3 1]]) now precedes the definition ([... [3 2]]) and is not
discharged. A :vm/store-put row keeps its own execution position;
:conditional? is computed over the extended path.

Finding 2 — every syntactic yin/def call counted as a store (was
linker.cljc:313). Fixed in the same scanner, within the existing
obligation/discharge data: each binding is marked by its source row tag
(:application vs :vm/store-put, linker.cljc:429-431); when the module's
own footprint binds yin/def — a :vm/store-put of the name or an
apparent yin/def binding of it — every yin/def-derived definition is
dropped, fail-closed (linker.cljc:444-450), and the obligations it
would have discharged are retained for step 5b (which already refuses a
receiver-shadowed operator as :shadowed-free). The M4 refinement —
proving the primitive binding against a verified module manifest — is
noted in the docstring (linker.cljc:421-425). No new manifest API; the
internal marker is stripped before records leave the scanner
(linker.cljc:450).

Tests (test/yin/vm/linker_test.cljc):
- New fixture yin-def-reads-its-own-name (:142) and new test
  a-yin-def-value-operand-read-precedes-the-definition (:1089):
  (yin/def 'x x) — the read stays an obligation, refused
  :use-before-definition; the definition sits at [root [[3 0] [3 2]]].
- New fixture yin-def-rebound-then-apparent-def (:150) and new test
  a-module-that-rebinds-yin-def-discharges-nothing-from-it (:1108): the
  scan keeps only the store-put binding; the read of x stays an
  obligation -> :unresolved-free 'x under the primitives-only receiver.
- Updated a-constant-key-yin-def-application-is-a-definition (:1069):
  the plain (yin/def 'x 1) case still discharges, now asserted at the
  invocation position; the stale entry-path assertion was corrected per
  the finding.

Verification (sequential, solo, all under mise; peers rebuilt first per
the bb test chain):
- JVM full suite: 2,057 tests / 180,912 assertions / 0 failures (r2
  baseline 2,055/180,908 + the 2 new tests).
- Node: 1,973 tests / 47,939 assertions / 0 failures.
- Dart: 1,935 passed, "All tests passed!" (baseline 1,933).

cljstyle check clean on both files (repo-wide check flags 15
pre-existing files, none touched here); clj-kondo 0 errors / 0 warnings;
pure ASCII, all lines <= 80 columns; no leftover diagnostics; no
commit/stage/checkout/reset/stash — git state unchanged (the same four
working-tree modifications as at task start).

Status: COMPLETE
