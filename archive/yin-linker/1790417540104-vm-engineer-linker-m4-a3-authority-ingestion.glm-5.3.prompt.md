Created-GMT: 2026-09-26 10:12:20 GMT
Created-Local: 2026-09-26 17:12:20 +0700
Coding-Agent: glm
Session-ID: 5985a4e0-f658-4d44-b6c9-2d2f318994b8

# Task: yin.vm.linker M4 slice A3, authority-policy datom ingestion

Role: VM Runtime Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-26 17:12:20 +0700 | Status: active | Rationale: owner directive "use glm for S5 and A3"

Repository: the worktree /Users/sto/workspace/datomworld-m4-a3 (branch m4-a3, from master 9428c3d2, which holds
the authority policy slices A1 and A2 in src/cljc/yin/vm/linker/authority.cljc). Collab files:
/Users/sto/workspace/datomworld/collab/ (absolute paths; the worktree has no collab/). Do NOT touch any other
worktree. Parallel workers do S3 (Opus, engine.cljc and module.cljc), S4 (GLM, linker.cljc) and S5 (GLM, the UCF
doc). You may edit ONLY src/cljc/yin/vm/linker/authority.cljc, test/yin/vm/linker_authority_test.cljc (or a new
test file beside it), and at most ONE sentence in docs/design/yin.vm.linker.md section 8.2 if the spec needs it.

## Owner statement (verbatim quote)

"use glm for S5 and A3"

## What to build (slice A3)
docs/design/yin.vm.linker.md section 8.2 (the name environment and its authority; the passages on reading assertions
from a dao.space source and on snapshots, near lines 1680 to 1800). Slice A3 is datom ingestion: read the assertion
and proof datoms [ev :yin.module/envelope env] and [ev :yin.module/proof proof] from a dao.space source AT A SNAPSHOT
(a published index address or a cursor position) and assemble the envelope-and-proof input that
yin.vm.linker.authority/name-environment already consumes. Follow the existing pattern in src/cljc/yin/vm/linker.cljc:
index-from-datoms (near line 739) reads [identity attribute address] datoms; A3 does the same for authority datoms. It
must stay pure over the datoms it is handed (the composition supplies the source and the snapshot; the function never
performs an ambient read, never advances a snapshot itself), deterministic, and fail closed on malformed datoms (an
envelope datom with no proof datom yields the no-proof path the policy already handles; a proof datom with no
envelope is ignored or reported, decide from the spec and say which). Tests: end to end from transacted datoms
(transact assertion and proof datoms into an in-memory dao.space, read them through the dao.space query or match API at
a snapshot, run them through name-environment, assert the exact name environment and discard kinds), including a
snapshot advance (a datom transacted after the snapshot is invisible until the snapshot is rebuilt), a retraction
datom, and malformed datoms. Do NOT change name-environment's behavior or any A1 and A2 test.
Out of scope: the format records, fetch, require lowering, manifests, and any UCF doc.

## References
The DeepSeek gate review of A1 and A2 (you wrote that code): /Users/sto/workspace/datomworld/collab/1790381890403-architect-linker-authority-gate.deepseek-v4-pro.findings.md
(note its P3s were fixed). The scoping report, section A3: /Users/sto/workspace/datomworld/collab/1790355000000-architect-linker-m3-m5-scoping.glm-5.3.report.md
(unverified; check against the tree).

## Verification
Rules: TDD (failing test first, per docs/agents/build-n-test.md). mise for everything. JVM lane:
mise exec -- clojure -M:test. Baseline at master (record yours first): 2,138 tests / 181,898 assertions; report before and
after. The orchestrator runs Node and Dart. kondo (mise exec -- clojure -M:kondo --lint <files>) and cljstyle
(mise exec -- cljstyle check <files>) on every changed file. The code must be pure cljc that works on JVM, Node and Dart:
no reader-conditional trap (#?(:clj ...) does NOT exclude code from the cljd build; use #?(:cljd nil :clj ...) with :cljd
first), no cross-namespace #'private-var access. If the JVM lane shows one failure, rerun it and keep the FULL log: an
intermittent single failure has been seen under load.

Rules: pure ASCII and <= 80 columns on every line you add or edit (Markdown grid rows excepted), no em dashes. Do NOT commit, stage, checkout, reset, stash or merge. If the spec conflicts with the tree or is ambiguous, STOP and report BLOCKED with the exact conflict; do not improvise a design. GLM's weekly budget is small and resets 2026-09-27 01:26 +0700: be efficient, do not re-read what you can cite, keep the report tight. The orchestrator independently verifies your work and sends it to a non-GLM reviewer.

## Report
Write your final report to
/Users/sto/workspace/datomworld/collab/1790417540104-vm-engineer-linker-m4-a3-authority-ingestion.glm-5.3.report.md
(same header fields) and return it as your final response, with: the JVM counts before and after, files changed, tests
added, deviations (especially any decision the spec left open), and unrun checks.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED - <reason>
