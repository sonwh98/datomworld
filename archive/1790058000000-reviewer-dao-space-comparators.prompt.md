Created-GMT: 2026-09-22 06:00:00 GMT
Created-Local: 2026-09-22 13:00:00 +07 (Indochina Time)
Coding-Agent: cmd (qwen/qwen3.8-max) | deepseek (shared brief; two independent reviewers)
Session-ID: pending (provider-generated) for cmd; caller-generated for deepseek
# Task: dao-space-comparators: independent adversarial review of step 3 (portable numeric wiring)
Role: Adversarial Review (Storage & Query Engine)
Implementers:
- Model: qwen/qwen3.8-max and deepseek-v4-pro | Assigned: 2026-09-22 13:00:00 +07 | Status: active | Rationale: the code was written by claude-opus-5 (Claude family); reviewers must differ; this phase is flagged in the design doc as "the widest blast radius in this plan"

Read-only. Work ONLY in /Users/sto/workspace/worktree-dao-space-comparators
(branch dao-space-comparators, HEAD f54a9888; the changes are UNCOMMITTED in
the working tree). Do NOT edit or create any file and do not run tests (the
orchestrator ran all lanes: kondo 0/0, cljstyle clean, full JVM Java 17 1743
tests / 174922 assertions, CLJS 1660 tests / 44798 assertions, CLJD 1622
tests all passed). You may read files and run read-only git commands. Give
the complete review now as your final response; do not wait for approval.

## What you are reviewing

`git diff` for: src/cljc/dao/jing/cbor.cljc (additive: `numeric-kind`,
`content-key`, `content=`, `content-hash`, `encoded-compare`), src/cljc/dao/
space/index.cljc (`type-rank`, `compare-numbers`, `compare-vals`), src/cljc/
dao/space/query.cljc (builtins, `unify`, `select-by-index`/`same-slot?`/
`range-scannable?`, `content-distinct`/`binding-key`, `relation-result`,
aggregates, `eval-or`/`eval-rule`). New test files: test/dao/jing/
cbor_content_equality_test.cljc, test/dao/space/index_numeric_test.cljc,
test/dao/space/query_numeric_test.cljc.

Read in full first: docs/design/dao.jing.cbor.md section "Numeric identity"
(both owner rulings: `content=` for query equality/unification with the
three-part min/max tie-break, and the accepted result-set-merge limit) and
the "Implementation sequence" step 3 paragraph and "Required test
scenarios" bullet on numeric carriers. The implementer's two reports:
collab/1790054338172-ref-implementer-report.md and
collab/1790056500000-ref-implementer-dedup-report.md (read both in full;
they document real bugs found by mutation testing -- verify the claims,
don't just trust them).

## What to judge (concrete; cite file:line and a specific value pair)

1. **`content-key`/`content=`/`content-hash` correctness.** Kind-strictness
   (integer/float64/decimal/rational never equal across kinds); within
   float64, does it correctly distinguish `0.0`/`-0.0` and treat every NaN
   bit pattern as one identity (is that the right call -- content addressing
   canonicalizes NaN on ENCODE, but content= operates on DECODED,
   possibly-never-encoded values; is treating all NaNs as content= to each
   other actually correct, or could a signaling NaN or non-canonical payload
   need to stay distinct)? Within decimal, does exponent+mantissa comparison
   correctly distinguish scale (`1.0M` vs `1.00M`)? Within rational, is a
   hand-built unreduced ratio (e.g. 2/4) correctly normalized to be content=
   its reduced form (1/2), consistent with what canonical encoding would
   produce? Host integer width: does `content-key` truly treat a JVM `Long`
   and `BigInteger`/`clojure.lang.BigInt` of the same value as content=?
   Hash consistency: for every case content= says true, does content-hash
   agree?
2. **`compare-vals`/`type-rank`/`compare-numbers` (index.cljc).** Does
   `cbor/numeric?` correctly bucket every carrier (JVM `Rational`; Node/Dart
   `Float64`/`Decimal`/`Rational`) alongside natives? Is the fast-path/
   slow-path split in `compare-numbers` (host `compare` for two Longs or two
   non-NaN doubles, `num-compare` otherwise) actually safe on every host --
   in particular the JVM Double fast path: does it correctly exclude NaN
   (the report says yes, verify), and is `(compare-vals 0.0 -0.0)` really
   `0` (ties, not a change, since only `=` changed, not ordering)? Try to
   construct a counterexample where the fast path and `num-compare` would
   disagree.
3. **`query.cljc`'s `=`/`not=`/ordering/min/max.** `content-eq`/
   `content-not-eq` variadic semantics correct? `numeric-chain`'s handling
   of `<=`/`>=` via `(complement pos?)`/`(complement neg?)` on
   `num-compare` -- correct for all three outcomes (-1/0/1)? `preferred-tie`
   and `portable-min`/`portable-max`: implements the exact three-part rule
   (content-identical -> same value; else exact beats float64; else shorter
   canonical encoding; else canonical byte order)? Does the binary fold via
   `variadic` truly agree with the aggregate reducer in `aggregate-fns` in
   every case, including argument-order independence? `host-arithmetic`'s
   carrier detection (`numeric? but not number?`) -- does it correctly
   reject on every host, and does it correctly ALLOW ordinary host
   BigDecimal/Ratio arithmetic to keep working (the report claims yes)?
4. **`select-by-index`'s range-scan fix.** This is the subtlest part of the
   diff: `same-slot?` (ties by `compare-vals`, not `=`) versus the final
   `content=` filter. Confirm the fix actually closes the gap the
   implementer's own mutation proved (reverting to host `=` returns `#{}`
   instead of `#{3 5}` for a mixed-kind scan) -- read that test and confirm
   it is a real, sound proof, not an artifact. Is `range-scannable?`'s list
   of scalar kinds complete and safe (nothing that should range-scan is
   excluded as unsafe, nothing unsafe is included)? For a NON-scannable
   bound value (a collection), confirm candidates still come from a full
   index scan and are filtered correctly, without a performance cliff that
   changes correctness (e.g. missing a `wildcard?` case).
5. **The result-set dedup fix and its accepted limit.** `content-distinct`/
   `binding-key`: correct that it deliberately does NOT walk `::dbs`/`'%`
   in a binding map (performance reasoning) -- does that skip ever cause an
   INCORRECT dedup (two bindings that differ only in dbs/rules being wrongly
   treated as duplicates, or vice versa)? The cycle-guard fix in `eval-rule`
   (keying active calls by `[rule-name (binding-key arg-vals)]`) -- the
   implementer says this was found by their own mutation (a recursive rule
   losing `:end`); confirm the fix is sound and doesn't reintroduce infinite
   recursion or under-detect real cycles. The accepted limit itself (a
   plain host `#{...}` can still merge `[1.0M]`/`[1.00M]` or `[0.0]`/`[-0.0]`
   on JVM/Dart): confirm this claim by reasoning about how Clojure's
   `PersistentHashSet`/`clojure.data` equivalent actually inserts, and
   confirm content-distinct upstream genuinely cannot prevent it (the report
   says so; verify the reasoning, don't just accept the conclusion).
6. **Scope discipline.** No edit outside the three named files plus the
   three new test files; the frozen J0-J3 CBOR files, `equiv`/`num=`/
   `num-hash`/`num-compare`/`equiv-hash`, and `dao.data.btree`'s comparator
   are genuinely untouched (confirm by reading the diff, not by trusting the
   report). The `not-required-vars`/`branch-free-vars`/rule-name `distinct`
   sites left as host `distinct` -- confirm they truly never carry a datom
   value (only query var symbols or rule name symbols).
7. **Tests.** Mutation-thinking: pick three of the implementer's own claimed
   bugs (the `type-rank`/`number?` gap, the zero-sign/decimal-scale merge,
   the index range-scan gap) and confirm the corresponding test would
   actually fail if the fix were reverted (the reports document doing this
   themselves for most sites; spot-check at least one independently by
   reading the test and reasoning through it, since you cannot run it here).

## Deliverable

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <cmd|deepseek>
Session-ID: <your session id if visible, else pending>
then a verdict: READY, READY WITH CHANGES, or NOT READY; findings as P1
(wrong bytes/wrong ordering/wrong equality, a correctness regression, scope
violation), P2 (significant gap), P3 (minor), each with file:line, the
design-doc sentence it violates or the concrete failing scenario, and the
smallest fix; what you checked and found clean. Findings only; edit no file.
