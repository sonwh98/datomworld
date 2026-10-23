Created-GMT: 2026-10-20 20:05:00 GMT
Created-Local: 2026-10-21 03:05:00 +07
Coding-Agent: claude
Model: claude-fable-5-1
Session-ID: 36134bec-9cbd-4852-b78c-4901af66fd02
Task: host-boundary design r8, astra's review of r7 (R7-1 to R7-5)
Role: Architect
Deliverable: docs/design/dao.stream.host-boundary.md edited in place (r8); no src edits, nothing staged.
Labels: V verified by reading the cited lines. No lane run. A non-Claude reviewer must confirm r8 before H1 is dispatched.

# Rationale

**R7-1.** No new arm is needed for PHP floats: `yin.vm.integer/decimal->float`
exists (V `integer.cljc:769-776`, exported at `:855`, CPython `float(str)`,
nearest-even) and `yang` already requires `yin.vm.integer`. Table B gains
`dao.host.float/decimal-text->float` (text to digits plus base-10 exponent,
then that algorithm; law, overflow and refusal stated; oracle: the existing
float-text cases plus five named texts) and `dao.host.text/code-points`
(the private `data.cljc:143-159` function made public; lone surrogates are
their own code point, which matches the three host calls the packet uses).
Both are built in H1 and are stated as not counted in the 76, so Table A,
§4.3, §10 and HD2 keep 76 unchanged. §14 says precisely what H1 must build
beyond r7.

**R7-2.** Four behaviour changes are now named in §10 with table D tests:
`acopy` prevalidation; strict `utf8-encode`; `bytes->str` strict UTF-8 on
every host (the JVM today substitutes U+FFFD for malformed input, V, so
malformed bytes change on all three and non-ASCII bytes on JS and Dart);
PHP integers above 2^53 exact on JS. H2's cell lists the consumer tests
(non-ASCII and malformed bytes, `9007199254740993`, 400-digit texts,
carrier law). The public encoding arity is preserved pending **OQ11**,
recorded as open with the evidence (no repository caller, macro prelude
excludes it, JVM-only arity) and the recommendation to make it portable
with UTF-8 the only accepted name.

**R7-3.** The index comparator keeps Dart's exclusion by construction: the
native path is taken for exact integers only, every float goes through
`cbor/num-compare` on every host; signed-zero, NaN, infinity and `1`
versus `1.0` tests are required in H2.

**R7-4.** "No tool change" is replaced: closure classes (the `class-rows`
data in `closure.clj:112`) and gate categories (`gate_test.clj:444`) are
distinguished, classification-data edits and regeneration are permitted,
and the traversal, totals, hashes and negative-test semantics are fixed.

**R7-5.** Header is r8 with the chronology; §2.2 points to §4.2; the 13
prints are 8 `println`, 1 `print`, 4 `js/console.log`, `flush` excluded;
the ring buffer's `:168` arm has one name, implementation-private under the
seams row and the `:external-stream-seam` class.

# Disposition

+-------+-----------+------------------------------------------------------------------------------------------------+
| Id    | Result    | Where in r8                                                                                    |
+=======+===========+================================================================================================+
| R7-1  | accepted  | Table B (two functions, laws, oracles, H1); Table A float/text unchanged at 76; §3.1 row; §14. |
+-------+-----------+------------------------------------------------------------------------------------------------+
| R7-2  | accepted  | §10 four changes; table D H2 consumer-test row; H2 cell; §6 and §14 "four"; OQ11 in §13.      |
+-------+-----------+------------------------------------------------------------------------------------------------+
| R7-3  | accepted  | §3.1 row (canonical comparison, exclusion preserved); table D signed-zero tests; §14 check.    |
+-------+-----------+------------------------------------------------------------------------------------------------+
| R7-4  | accepted  | §8.1 reconciliation paragraph: classes versus categories; what H0 must and must not change.   |
+-------+-----------+------------------------------------------------------------------------------------------------+
| R7-5  | accepted  | Header; §2.2 reference; §3.2 print breakdown; §8.1 seams row (ring buffer), dialect row.       |
+-------+-----------+------------------------------------------------------------------------------------------------+

**OQ11 framing.** Question: remove, keep JVM-only, or make portable the
public two-arity `bytes->str`. Not decided; H2 preserves it behind a
retained arm. Recommendation: portable, UTF-8 only, qualified refusal
otherwise.
