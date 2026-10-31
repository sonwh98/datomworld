Created-GMT: 2026-10-20 20:35:00 GMT
Created-Local: 2026-10-21 03:35:00 +07
Coding-Agent: claude
Model: claude-fable-5-1
Session-ID: 36134bec-9cbd-4852-b78c-4901af66fd02
Task: host-boundary design r9, astra's review of r8 (R7-1 not resolved; R7-2 and R7-4 partial; H0-1 to H0-3)
Role: Architect
Deliverable: docs/design/dao.stream.host-boundary.md edited in place (r9); no src edits, nothing staged.
Labels: V verified by reading the cited lines. No lane run. A non-Claude reviewer must confirm r9 before H1 is dispatched.

# Rationale

**R7-1, resolved by extraction.** Astra is right on both counts: the
nearest-even kernel is the private `int-decimal->float`
(`integer.cljc:769`), the `:855` export is a guest-module symbol, and a
`dao` namespace calling into `yin` would break the layering and, after H2,
close a cycle. Reading the kernel shows every helper is written over the
host shim alone (`sign`, `bits`, `pow2`, `signed`, `ratio->float`,
`trunc-big`, `digits->big`, `pow10`, `scaled`, `float-ratio`,
`decimal-exponent`, V), so Table B now specifies a dependency-free
extraction: `dao.host.float` (over `dao.host.integer`, no `yin` require)
owns `decimal->float` (digit values, exponent, any carrier, to a double;
`+inf`/`0.0` past the range; never refuses), `float-digits`, `ratio->float`
and the helpers, with exact signatures and laws; `yin.vm.integer` keeps its
refusal preambles and delegates, so one implementation exists; `yang.php`
is the second consumer through the `decimal-text->float` composition.
Counting: none of it is in the 76 (no arm); it is one Table B item. Oracle:
H1 runs the existing float fixture rows directly against `dao.host.float`,
H2 runs the unchanged module suites through the rewiring. `code-points` is
confirmed callable from `dao.host` without `yin` (it uses only the two
text arms) and its surrogate cases exist.

**R7-2.** `yin/vm.cljc` is exempt from H2's removal pending OQ11: after H2
its one-arity is portable and the JVM two-arity keeps one arm, so it stays
on list 1 as `:migration-source` with the slice value `:pending` ("OQ11"),
a new value in the gate's `:clears` vocabulary (astra's H0-2: `"H2"` means
removal). The resolution paths are stated.

**R7-4.** The false "the gate asserts the mapping" sentence is replaced by
the precise category-to-class table and the stronger check H0's retag
implements: per-lane class sets, non-empty, never `:none`, intersecting
the category's permitted classes, never collapsed across lanes (H0-3).
Ring buffer: `:seam-driver`, `:stays` (H0-1).

Scope, owner decisions and structure are unchanged; §14 states what H1
must build beyond r7 (the extracted kernel, `code-points`,
`decimal-text->float`, their tests).

# Disposition

+-------+-----------+------------------------------------------------------------------------------------------------+
| Id    | Result    | Where in r9                                                                                    |
+=======+===========+================================================================================================+
| R7-1  | accepted  | Table B "float-text kernel, extracted" (owner, moved functions, signatures, laws, consumers,   |
|       |           | counting, oracle, slices); `code-points` dependency note; table D row; H2 cell; §14; header.   |
+-------+-----------+------------------------------------------------------------------------------------------------+
| R7-2  | accepted  | §8.1 "How H1 and H2 clear the tag": `yin/vm.cljc` exempt, `:pending` value, resolution paths.  |
+-------+-----------+------------------------------------------------------------------------------------------------+
| R7-4  | accepted  | §8.1 "The category-to-class mapping, stated": table and the strengthened per-lane check.       |
+-------+-----------+------------------------------------------------------------------------------------------------+
| H0-1  | accepted  | §8.1: ring buffer `:seam-driver`, `:stays`.                                                    |
+-------+-----------+------------------------------------------------------------------------------------------------+
| H0-2  | accepted  | §8.1: `:pending` slice value for conditionally retained arms.                                  |
+-------+-----------+------------------------------------------------------------------------------------------------+
| H0-3  | accepted  | §8.1: per-lane class sets, no collapse, `:none` rejected per lane.                             |
+-------+-----------+------------------------------------------------------------------------------------------------+
