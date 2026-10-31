**PART 1 — NOT CONFIRMED**

References below use **H** = `docs/design/dao.stream.host-boundary.md`.

- **R7-1 — Not resolved.** The nearest-even algorithm exists, but `integer.cljc:769` defines **private** `int-decimal->float`; `:855` exports a guest-module symbol, not a callable `yin.vm.integer/decimal->float` var. H:640’s proposed call also violates H:678’s prohibition on `dao` requiring `yin`. Reaching it through `integer-module` introduces VM dependencies and, after migration, a cycle through `yin.vm → dao.jing.cbor → dao.host.float`. Specify a dependency-free extraction of the kernel and its helpers. The private `data/code-points` implementation and surrogate tests exist. **76 is honest for Table A’s primitive entries**; portable Table B compositions need not increase it to 78. That count does not excuse the dependency defect.
- **R7-2 — Partially resolved.** All four changes and consumer acceptance cases are named, including malformed/non-ASCII bytes, exact `9007199254740993`, overflow and canonical carriers. OQ11 correctly remains an **OWNER decision**, preserving the JVM encoding arity. However, H:974–977 still requires all nine consumers to leave list 1 in H2, contradicting OQ11’s retained arm. Explicitly exempt `yin/vm.cljc` pending that decision.
- **R7-3 — Resolved.** H:460–464 requires canonical comparison for floats on every host; Table D requires signed-zero ties, NaN-last, infinity ordering and `1` versus `1.0`. This preserves the protection documented at `index.cljc:70–85`.
- **R7-4 — Partially resolved.** Classification edits and regeneration are correctly distinguished from traversal changes. However, H:964’s assertion that the gate checks the category/class mapping is false: it checks category membership, allowed slice and a weak `:none` condition.
- **R7-5 — Resolved in the design.** Revision chronology, §4.2 reference and exclusion of `flush` from the 13 counted prints are corrected. Ringbuffer now has a consistent seams placement in r8, which the retag has not adopted.

**H1 commission: NO, pending the decimal-kernel dependency correction and reconciliation above.** H1 must build the five `dao.host.{integer,float,number,bytes,text}` files, both Table B functions (`code-points`, `decimal-text->float`) and their direct tests, plus strict UTF-8 encoding and mutation-prevalidation laws and tests.

**PART 2 — SIGNED OFF WITH CHANGES**

Verified base `9f6fc27d` and the five-file diff. The 16 entries carry r7’s **9 migration/H2, 6 dialect/stays, 1 deferred/stays** assignments. Their source sites support the migration/deferred distinctions; I found no evidence of categories invented merely to hide arms. R8 changes the required ringbuffer placement.

Here **G** = `.claude/worktrees/host-h0/test/dao/host/gate_test.clj`.

| ID | Severity | File:line | Evidence | Correction |
|---|---|---|---|---|
| H0-1 | Medium | G:514 | Ringbuffer’s atom test is labelled dialect despite that category’s display/EDN definition and r8’s seams row. | Use `:seam-driver`, retaining `:stays`. |
| H0-2 | Medium | G:487 | `"H2"` means removal, but OQ11 requires a surviving JVM encoding arm. | Record conditional retention pending OWNER resolution; reconcile the design’s removal rule. |
| H0-3 | Medium | G:708–714 | Unknown categories and row-disallowed slices fail the test suite. Classes are collapsed across lanes, so a later lane can mask `#{:none}`; category/class compatibility is never checked. | Validate each lane’s nonempty class set, reject `:none`, and enforce the documented mapping. |

Waitset consolidation and ringbuffer classification are legitimate. Removing `lane-rows` slightly changes classification code, **not traversal**. Closure diffs change tags/legends only. Both injection tests and the scan-failure test retain their original semantics.

**Could not verify:** I did not independently rerun the reported 293 assertions, closure check, corpus hashes or runtime lanes. No files were edited.
