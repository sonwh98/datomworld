Created-GMT: 2026-10-20 15:40:00 GMT
Created-Local: 2026-10-20 22:40:00 +07
Coding-Agent: claude
Model: claude-fable-5-1
Session-ID: 36134bec-9cbd-4852-b78c-4901af66fd02
Task: host-boundary design r4, fix N1-N6 within the unchanged scope (§2 plus H0-H4)
Role: Architect
Deliverable: docs/design/dao.stream.host-boundary.md edited in place (r4); no src edits, nothing staged.
Labels: V verified by reading, R computed or read, P proposed. No lane run. A non-Claude architect must sign off the final text.

# Rationale

Every finding was checked against the code before editing. N1: the ring
buffer stores the appended value itself and returns it on read
(`ringbuffer.cljc:124-131`, V), so r3's "a value that crosses a stream is
copied" was false; §2.4 now defines an exclusive mutation right held by the
allocator until publication, a transitive freeze on publication (any append,
nesting, seam or content handle), and four obligations enforceable in H0-H4
(P-entry argument immutability test, M-entry prevalidation test, writer
input-immutability test, an audited list of M-entry callers), stating what
is not enforceable here (readers mutating frozen values). N2: my r3 script
under-counted because it dropped requires inside `#?@` arms; the reconciled
by-file totals 142/140/141 with 133 shared match astra's traversal, the
"cannot under-include" claim is withdrawn, `yin/repl/host.cljc` is listed as
the JVM selector, `dao.pretty` is classified (internal pure, display only),
and H0 commits a `tools.namespace`-based tool plus tagged output. N3: §8.2
is a baseline list 1 by category with reasons, including `dao.data.btree`
(substantive atomic and weak-reference arms, retained by table C) and the
untouched codec files, with the final list stated. N4: `aset` keeps
Clojure's return value; `acopy` prevalidation on JS and Dart is a behaviour
change assigned to H2 with no-partial-write tests; strict `utf8-encode` is a
behaviour change (today's helpers substitute) assigned to H1 for the law and
H3 for the two consumers, with direct refusal tests; risk revised to medium
for H1 and H2. N5: greps are recursive and the gates distinguish exit status
1 from 2. N6: seven profile files and at least five test namespaces.

# Disposition

+-----+---------------+----------------------------------------------------------------------------------------------------------+
| Id  | Disposition   | Where in r4                                                                                              |
+=====+===============+==========================================================================================================+
| N1  | accepted      | §2.4 rewritten: exclusive mutation right, publication and freeze (transitive, readers), obligations      |
|     |               | (i)-(iv) with tests and the M-caller audit list; codec-copy assertion removed.                           |
+-----+---------------+----------------------------------------------------------------------------------------------------------+
| N2  | accepted      | §3.1 totals 142/140/141, 133 shared, by file, shadow resolution stated, under-include withdrawn;        |
|     |               | `dao.pretty` row; H0 commits `dev/host_boundary/closure.clj` and tagged outputs; §14.                    |
+-----+---------------+----------------------------------------------------------------------------------------------------------+
| N3  | accepted      | §8.2 baseline and final list 1 by category with reasons (btree, codec files, seams, deferred); H0 row.   |
+-----+---------------+----------------------------------------------------------------------------------------------------------+
| N4  | accepted      | §2.4 return values and prevalidation; H1/H2/H3 rows; table D no-partial-write and direct refusal tests; |
|     |               | §10 risk revised (H1, H2 medium).                                                                        |
+-----+---------------+----------------------------------------------------------------------------------------------------------+
| N5  | accepted      | H0, H2, H3 rows and §6: recursive scans; status 1 versus 2; zero-files read is a failure.                |
+-----+---------------+----------------------------------------------------------------------------------------------------------+
| N6  | accepted      | §10: seven profile files, at least five test namespaces.                                                 |
+-----+---------------+----------------------------------------------------------------------------------------------------------+

Not verified: no lane run; the library substitution behaviour on malformed
input is from library documentation (R); the closure totals remain R until
H0's committed tool reproduces them.
