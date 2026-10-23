**PART 1 — CONFIRMED WITH DEFECTS**

References: **H** = `docs/design/dao.stream.host-boundary.md`; **I** = `src/cljc/yin/vm/integer.cljc`; **G** = `.claude/worktrees/host-h0/test/dao/host/gate_test.clj`.

- **R7-1 — Dependency defect resolved; extraction specification needs tightening.** H:650–699 gives ownership to `dao.host.float`, depending only on `dao.host.integer`; Yin delegates in H2, leaving one implementation, and PHP consumes `decimal-text->float`. This removes the `dao → yin` require and proposed cycle. I:769 and :855 confirm the private-kernel/guest-export distinction. The named fixture suites exist and provide a useful regression oracle, though finite fixtures cannot prove universal equality. Before calling this a verbatim extraction, specify the complete helper/constants closure (`compare-scaled`, `chunk-digits`, `max-significant-digits`, power constants), exponent promotion formerly supplied by `big!`, and overflow handling: moving `::float-overflow` changes its namespace unless explicitly preserved or translated by Yin. Also make H:672’s grammar explicitly accept `"1."`, which PHP’s tokenizer accepts and H:695 names.
- **R7-2 — Resolved in r9.** H:1037–1045 expressly exempts `yin/vm.cljc` from H2 removal pending OQ11, retains the encoding arity, and specifies `:pending`. The implementation’s different keyword requires reconciliation below.
- **R7-4 — Partially resolved.** H:1047–1074 states the intended per-lane overlap check, but its mapping differs materially from the implementation and excludes some files that r9 itself assigns to those categories. Its “does not contain `:none`” law is stronger than the code.

**H1 commission: NO from r9 unchanged.** Reconcile these bounded specification/gate defects first. The intended H1 scope is otherwise sound: five `dao.host` files, both Table B compositions, extracted kernel and direct tests, strict UTF-8 encoding, and M-entry prevalidation laws/tests. OQ11 itself does not block H1.

**PART 2 — SIGNED OFF WITH CHANGES**

Verified base `9f6fc27d` and the five-file diff. H0-1 is resolved: ringbuffer is `:seam-driver`/`:stays`, backed by `:external-stream-seam`. Closure diffs change classifications/legends, not traversal.

| ID | Severity | File:line | Evidence | Correction |
|---|---|---|---|---|
| H0-2 | Medium | G:460,550 | `:conditional-retention-oq11` conflicts with H:1041’s `:pending`. Semantics agree. | **`:pending` wins** as the design vocabulary; update entry, allowed set, documentation and negative case. |
| H0-3a | Medium | G:509 | Equality with `#{:none}` allows `#{:none :profile-consumer}` to pass. | Check membership of `:none`; add a mixed-set negative case on one lane. |
| H0-3b | Medium | G:461–482; H:1058–1074 | Code omits migration `:consumer`, adds runtime `:deferred-timers`, deferred `:runtime-prereq`, and lane-specific seam exceptions. R9’s table would reject existing `transactor`, `authority`, and several lane files. | Reconcile both tables explicitly; retain justified lane exceptions and locking placements, and resolve the remaining class differences. |

Per-lane sets are never collapsed. All seven negative cases statically exercise real rejection paths, although none covers mixed `:none`.

The **293 → 73 aggregation does not weaken acceptance**: equality with the complete, realized problem vector fails for any recorded violation and retains file/lane/category diagnostics. That conclusion is separate from the predicate defects above.

**Could not verify:** independently rerun tests, closure/corpus checks, runtime lanes, or future extraction equivalence. The reported green results remain orchestrator evidence. No files were edited.
