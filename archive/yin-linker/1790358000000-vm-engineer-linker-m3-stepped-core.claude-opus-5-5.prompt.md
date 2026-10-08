Created-GMT: 2026-09-26 04:20:00 GMT
Created-Local: 2026-09-26 11:20:00 +0700
Coding-Agent: claude
Session-ID: a680556f-b767-4211-bafa-5e67257b844d

# Task: yin.vm.linker Milestone M3, stepped core and link runtime

Role: VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-26 11:20 +0700 | Status: active | Rationale: owner directives "use claude cli for m3" and "for 4, use opus"; supersedes the earlier staged brief collab/1790314000000-vm-engineer-linker-m3-stepped-core.prompt.md (ZCode GLM, pre-Rule-R)

Repository: /Users/sto/workspace/datomworld-ucf-phase2 (branch ucf-phase2). HEAD is
19719d66, the M2 commit, on top of Rule R commit 0fc931fc. The tree is clean. Collab
files: /Users/sto/workspace/datomworld/collab/ (absolute paths; this worktree has no
collab/). A git stash entry from earlier work exists; do NOT drop or apply it. Do NOT
touch /Users/sto/workspace/datomworld or the other worktrees.

## Owner statements (verbatim quotes)

"use claude cli for m3"
"for 4, use opus"
"But i like Rule R: yin/def is syntax never a name"

## Specification (authoritative)
Implement Milestone M3 exactly as docs/design/yin.vm.linker.md section 9 states (the
M3 paragraph near line 1880 and its test list), reading first: section 6 (linking is a
stream exchange, including the 6.4 local-fetch traffic test), section 4 (the fetch
pipeline and the four format records as landed in M2), section 2 (invariants), section
10 (file box: NEW test/yin/vm/linker_step_test.cljc; the must-not-change list), section
11 (completion criteria, incl. criterion 24 Rule R holds on every format), and the
acceptance matrix rows tagged M3 in
docs/design/yin.vm.universal-continuation-format.md section 7.11.
M3 contract: implement link-state, request-link, step and abandon over
dao.jing.remote.step (or, for a purely local composition, over dao.stream.rpc on a
ring-buffer pair served by dao.jing.remote/serve-content!'s handlers); reimplement
fetch as the blocking driver over a link runtime; export verify and discharge. Tests,
per section 9: the full refusal matrix through step over ring buffers on all three
hosts; the JVM WebSocket path from B6 completion criterion 1; a :pending sequence
where the content server answers one part per step; a request carrying a function or a
handle is :invalid-request; the section 6.4 local-fetch traffic test with a
get-counting server handle and a handle-free linker state; the DHT handle behind
serve-content! answering a link over ring buffers with the server driven by the test;
and the M3-tagged acceptance-matrix rows (the code-identity row: the addressed
instruction stream runs under its stamped contract or is refused before load).

## What changed since the old brief (you must honour these)
- Rule R is landed. yin/def is syntax, never a name; the reserved set is only yin/def
  (require stays an ordinary function). A refusal is :descriptor-defect with rule
  :reserved-name. Do not add any guard for shadowing yin/def.
- The four records read their contract from the vm/*-contract constants (v3 AST and
  semantic, b2 stack, r2 register). fetch REQUIRES a contract: omitted is
  :invalid-request before any read, a differing one is :contract-mismatch. The stepped
  core must preserve exactly this admission and never assign a contract stamp to
  external input; the caller supplies the record's contract.
- The stepped core must preserve the M2 pipeline semantics exactly: the same admission
  checks, the same refusal vocabulary, the byte cap before hashing and decoding,
  :max-parts, the invocation-position and dominance logic in step 5a, and no branch on
  :format outside the format records.
- A read-only scoping report exists that lists what M3 consumes from M2 by name
  (section C): /Users/sto/workspace/datomworld/collab/1790355000000-architect-linker-m3-m5-scoping.glm-5.3.report.md.
  Its claims are UNVERIFIED; check them against the tree before relying on them.

## How to work
TDD (failing test first, per docs/agents/build-n-test.md). mise for everything:
JVM mise exec -- clojure -M:test; Node mise exec -- clj -M:cljs -m
shadow.cljs.devtools.cli compile slice-peer test; Dart rm -rf test/cljd-out && mise
exec -- bb test:cljd (one process only, solo, sequential); lint mise exec -- clojure
-M:kondo --lint <files>; mise exec -- cljstyle check <files>. FIRST record a baseline
(the M2 commit): JVM 2,089 tests / 181,424 assertions; Node 2,002 / 48,286; Dart 1,964
passed; report yours before changing anything. Pure ASCII, <= 80 columns on every line
you add or edit (Markdown grid rows excepted), no em dashes. Cross-host traps:
#?(:clj ...) does NOT exclude code from the cljd build, use #?(:cljd nil :clj ...)
with :cljd FIRST; #'ns/private-var cross-namespace reflection fails on CLJD, make
helpers public; cljd ExceptionInfo, dart:core alias, cljs keyword identity and
private mutable fields differ across hosts. Do NOT commit, stage, checkout, reset,
stash or merge. If the spec's M3 text conflicts with the landed M2 shapes or is
ambiguous, STOP and report BLOCKED with the exact conflict; do not improvise.
The orchestrator reruns every lane independently and sends the diff to an independent
reviewer (codex is down; DeepSeek is the fallback).

## Report
Write your final report to
/Users/sto/workspace/datomworld/collab/1790358000000-vm-engineer-linker-m3-stepped-core.claude-opus-5-5.report.md
(same header fields) and return it as your final response, with: baseline and final lane
counts, files changed, tests added, the acceptance-matrix rows covered, deviations, and
unrun checks.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED - <reason>
