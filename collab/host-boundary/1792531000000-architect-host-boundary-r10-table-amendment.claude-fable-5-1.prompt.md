Created-GMT: 2026-10-21 02:35:00 GMT
Created-Local: 2026-10-21 09:35:00 +07
Coding-Agent: claude
Session-ID: fresh

# Task: Amend §8.1 Mapping Table in docs/design/dao.stream.host-boundary.md
Role: System Architect
Model: claude-fable-5-1

Governing Document: /Users/sto/workspace/datomworld/docs/design/dao.stream.host-boundary.md
Reviewer Finding: /Users/sto/workspace/datomworld/collab/1792530100000-reviewer-host-boundary-h0-r3.gpt-6-astra.findings.md

Codex Findings and Guidance on §8.1 Category/Class Discrepancy:
The reviewer notes that §8.1's mapping table and file rows have an internal tension for 8 bounded files:
1. `src/cljc/dao/space/transactor.cljc`, `src/cljc/yin/vm/ucf/authority.cljc`: category `:deferred-scope` ("locking"), but §3.1 class is `:runtime-prereq`.
2. `src/clj/dao/jing/content/driver.clj`: category `:seam-driver` ("lane-specific files"), but §3.1 class is `:deferred-timers`.
3. `src/{clj,cljs,cljd}/yang/antlr/cst.*`: category `:seam-driver`, but §3.1 class is `:deferred-stage`.
4. `src/cljd/dao/stream/{cbor,transit}/cljd.cljd`: category `:seam-driver`, but §3.1 class is `:codec-track`.

Codex Recommended Architect Disposition:
"Preserve the explicitly named baseline categories and the §3.1 closure classes. Amend §8.1 to state the permitted exceptions explicitly, as the eight bounded file/category/class entries above, then make the validator enforce that documented mapping."

Action:
1. Update `docs/design/dao.stream.host-boundary.md` §8.1 table and text to document these 8 bounded file/category/class entries explicitly.
2. In `.claude/worktrees/host-h0/test/dao/host/gate_test.clj`, update the table and validation so the documented mapping is directly enforced without any unapproved discrepancies.

Deliver findings report to:
/Users/sto/workspace/datomworld/collab/1792531000000-architect-host-boundary-r10-table-amendment.claude-fable-5-1.findings.md
