Created-GMT: 2026-10-20 19:30:00 GMT
Created-Local: 2026-10-21 02:30:00 +07
Coding-Agent: claude
Model: claude-fable-5-1
Session-ID: 36134bec-9cbd-4852-b78c-4901af66fd02
Task: host-boundary design r7, reconcile the design with what H0 measured (F1-F4)
Role: Architect
Deliverable: docs/design/dao.stream.host-boundary.md edited in place (r7); no src edits, nothing staged.
Labels: V verified by reading the cited lines and H0's closure edn files. No lane run. A non-Claude reviewer must confirm r7 before H1 is dispatched.

# Rationale

Each measurement was checked against the code; the design yields to it.

**F1.** The 16 `:h0-unlisted` files split three ways. Nine are profile
consumers the manifest covers and H2 migrates: byte length in
`repl/query:460`, `linker/closure:32`, `ucf/rows:282`; `bytes->str` at
`vm.cljc:404` (a real divergence: JS and Dart decode byte-as-char-code, the
JVM decodes UTF-8, so strict UTF-8 is a named behaviour change); PHP number
parsing at `php.cljc:95,98` (`js/parseInt` above 2^53 is a second); the two
`isSafeInteger` checks; the index number fast path; code points in the
packet IR (the portable `code-points` moves to table B). Six are retained
dialect arms: three runtime-type diagnostics, two EDN reader aliases, the
ring buffer's own atom test. One (`rpc.cljc:114`) is deferred entropy. The
six names H0 could not find are dropped (four outside the closure, two
exempt-only). H0 must retag the 16 as data; totals, tool, tests and hashes
stand. §10's "index untouched" was false; cost is 24 edited plus 3 deleted.

**F2.** Three scopes: 27 lines in `src/cljc`, 26 under OQ1's directory
reading, 21 in the transitive closure. The gate uses the closure: it is
what a composition can reach, it is committed and regenerated, and an
unrequired file cannot affect a host until required, when it enters the
closure. The five `dao.await` and `remote_serve` sites go to the deferred
design's inputs with the scope question stated.

**F3.** §3.1 no longer names `dao.stream.udp`, the browser adapter,
`content.async` or `space.schema`; the waitset drivers and the ws seams'
clock reads sit in the seams row; the `random-uuid` count reads 21.

**F4.** `main.cljc` has 13 resolved print calls (r3 counted text lines).
`antlr/repl.cljc:71` is a `println` inside a quoted yin program, withdrawn
from §3.1, §3.2 and §9.3. §2.4 (iv) states what the gate sees (only
`dao.data.btree`, 33 calls), that `chunks`, `cbor` and `sign` mutate host
arrays inside arms until H2, that the freeze rule is never gate-checkable,
and the four steps H2's audit takes.

# Disposition

+-----+-----------+------------------------------------------------------------------------------------------------+
| Id  | Result    | Where in r7                                                                                    |
+=====+===========+================================================================================================+
| F1  | accepted  | §3.1 two new rows; §8.1 first, codec, dialect-arms, deferred rows and reconciliation paragraph |
|     |           | (what H0 must and need not change; how H2 clears the tag); §8.3 file list; §10 cost and risk.  |
+-----+-----------+------------------------------------------------------------------------------------------------+
| F2  | accepted  | §3.2 "three scopes" paragraph; §9.1 new required input.                                        |
+-----+-----------+------------------------------------------------------------------------------------------------+
| F3  | accepted  | §3.1 seams, entropy, timers, locking rows; §1 and §8.1 out-of-closure notes.                   |
+-----+-----------+------------------------------------------------------------------------------------------------+
| F4  | accepted  | §3.2 counts; §3.1 stage row; §9.3; §2.4 (iv).                                                  |
+-----+-----------+------------------------------------------------------------------------------------------------+

Tool path read as `src/dev/host_boundary/closure.clj` (H0's justified
deviation) in §3.1, the H0 row and §14. Header carries the r7 line.
