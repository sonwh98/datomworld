**SIGNED OFF WITH CHANGES — §2 and H0–H4 only.** Apply the mechanical corrections below before commissioning; they require no further architect round. H5–H8 remain deferred. No files edited.

`H` = [dao.stream.host-boundary.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.host-boundary.md); source paths below are relative to `src/cljc/`.

- **N1 — Substantively resolved, wording correction required:** §2.4 supplies exclusive mutation rights, transitive freeze, reader restrictions and four assigned obligations. Verified `ringbuffer.cljc:131` stores the value directly. Fresh-return wording contradicts the intended ownership transfer; see M1.
- **N2 — Resolved as a design obligation:** §3.1/H0 require the committed `tools.namespace` tool, tagged outputs and independent agreement. My fresh reader-based traversal reproduced **142/140/141**, **133 shared**, including JVM `yin/repl/host.cljc`. Verified `dao.pretty` implementation and REPL display use.
- **N3 — Substantively resolved:** §8.2 explicitly retains B-tree atomics/references and codec files. Verified substantive `btree.cljc:102–119,212–245` arms. Baseline/final bookkeeping needs M2–M3.
- **N4 — Resolved:** §2.4, table D and H1–H3 assign `aset` return preservation, JS/Dart `acopy` prevalidation, strict UTF-8 and direct tests. Source confirms current array loops and UTF-8 library calls. Risk is revised.
- **N5 — Resolved:** H0/H2/H3 specify recursive scanning, zero-files failure and distinct no-match/error statuses.
- **N6 — Resolved:** §10 counts seven profile files and at least five test namespaces, matching slices. Table A independently totals **76**.

| ID | Severity | File:line | Evidence | Correction |
|---|---|---|---|---|
| M1 | Medium | H:223 | Returning fresh storage is called publication, which immediately freezes it, despite explicitly transferring mutation rights to the caller. This would prohibit subsequent `set!`/`aset`. | Separate fresh-result ownership transfer from freezing publication. Preserve the explicitly intended caller mutation right. |
| M2 | Medium | H:768 | Literal H2 grep also matches untouched `dao/data.cljc:54` documentation and out-of-scope `dao/postgraphics/lowering.cljc:587` (`rgba8unorm`). Therefore its asserted result is false. | Gate executable host references within the adopted closure; exclude comments/string false positives explicitly. Preserve scan-error handling and codec file ownership. |
| M3 | Low | H:744,754,766 | “Baseline” includes seven future files; the lane-specific category can retain the Dart Jing writer that H4 deletes. | Distinguish H0 baseline from planned additions; explicitly subtract both deleted Jing writers from the final list. |
| M4 | Low | H:877,887,1110 | §8.1 lists 16 existing files edited plus three deleted, not 20 edited; risk calls unchanged `aset` a behaviour change; appendix retains old closure totals. | Reconcile these counts/labels; update the stale r3 heading at line 13. |

**Could not verify:** the future committed closure tool/agreement report, compiler-resolved completeness, proposed tests or three-lane execution, exhaustive caller ownership compliance, and H4 performance. These remain implementation acceptance obligations.
