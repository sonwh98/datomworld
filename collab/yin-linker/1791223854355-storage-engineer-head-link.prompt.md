Created-GMT: 2026-10-05 18:10:54 GMT
Created-Local: 2026-10-06 01:10:54 +07
Coding-Agent: claude
Session-ID: 9d499fc0-d727-4628-a93c-78957a89f551

# Task: head-link

Role: DaoSpace and DaoJing Storage Engineer

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-06 01:10:54 +07 | Status: active | Rationale: owner directive 2026-10-06: route all Claude-side work to fable until the 04:00 reset; a different-family reviewer (gpt-6.1-sol) gates it

Implement one Architect-ruled fix in
/Users/sto/workspace/datomworld/.claude/worktrees/head-link (a git worktree stacked
on head-trace slices H0 and H1). It is the `link.cljc` part of slice H3 of
`docs/design/yin.vm.linker.dht.head.md`, landed ahead of the rest of H3 because it
is independent of the transport.

## The ruling (Architect claude-fable-5-1, 2026-10-05, ruling 3; verbatim)

"The real defect is older: `dht-attempt` is kind-blind. It also forgets a user's
failed index load made by hand, and waits on or tries to link records of other
kinds. D3 makes that wrong." Correction: "In `dht-attempt`, after the
`(nil? status)` branch, add a branch for
`(not= linker.dht/module-kind (:kind status))`. It answers
`{:status :refused :reason :dao.space.dht/kind-conflict :address address
:recorded (:kind status)}` and forgets nothing. H3 also adds that row to the
response rows of `yin.vm.linker.dht.md` section 9, and one test: a `require`
resolving to a failed candidate's address leaves the record in place and the
retry delay intact."

## Read first

- src/cljc/yin/repl/link.cljc (`dht-attempt`, about line 297; how its `:body`
  values and refusals are shaped and how callers consume them),
  src/cljc/yin/vm/linker/dht.cljc (`module-status`, `module-kind`, `load-module`,
  `load-refusal`), src/cljc/dao/space/dht.cljc (`load`, the kind-conflict refusal
  H1 added, `forget`), src/cljc/yin/vm/linker/head.cljc (`candidate-kind`, the
  follower's retry delay), docs/design/yin.vm.linker.dht.md section 9 (the
  response rows and the failure vocabulary; H1 added a kind-conflict row for the
  host load operations, keep the style), docs/design/yin.vm.linker.dht.head.md
  5.5 ("Kinds do not mix (D3)" and the Unloadable rule).
- test/yin/repl/require_test.cljc and test/yin/repl/dht_test.cljc (how a
  `require` against the DHT source is driven in tests),
  test/yin/vm/linker/head_follow_test.cljc (how a failed candidate record is
  made).

## What to build

- `src/cljc/yin/repl/link.cljc`: the kind check in `dht-attempt` as ruled. Make the
  refusal reach the caller in the SAME shape the function's other refusals use (the
  ruling gives the content; check how `:body` carries a refusal today and match
  it, and say in your report exactly what shape you produced and why). Records of
  the module kind behave exactly as before. A record of any other kind (index
  kind, candidate kind, anything else), in any status, is refused
  `:dao.space.dht/kind-conflict` with `:recorded` its kind, and is neither
  forgotten, waited on, nor linked. Note that `module-status` may itself hide
  non-module records (check; if it answers nil for another kind, the `nil?`
  branch would call `load-module` on it, which H1 now refuses with a kind-conflict
  THROWN from `load`: make sure a `require` never throws through the host
  interpreter, and report what you found).
- `docs/design/yin.vm.linker.dht.md` section 9: the response row for this refusal
  (ASCII, 80 columns, that document's style).
- Tests in `test/yin/repl/require_test.cljc` or `test/yin/repl/dht_test.cljc`
  (choose the one whose harness fits): (1) a `require` resolving to a FAILED
  candidate-kind record's address is refused as data with the kind-conflict, the
  record is still there, and the follower's retry delay is intact (the candidate
  is not restarted early); (2) a `require` resolving to a failed INDEX-kind record
  made by hand leaves it in place too; (3) a `require` resolving to a loaded or
  loading record of another kind is refused, not linked and not waited on; (4)
  module-kind behaviour is unchanged (the existing tests still pass, untouched).

## Scope and process rules

- Work only in `src/cljc/yin/repl/link.cljc`, `docs/design/yin.vm.linker.dht.md`
  and the one test file you choose. If a dependency needs another file, stop and
  ask. Do not edit `docs/design/yin.vm.linker.dht.head.md` (if a sentence there
  must change, say so in your report).
- **No git commands.** No formatter or cljstyle. Verify in the **foreground** with
  focused JVM runs (`clj -M:test -n yin.repl.require-test -n yin.repl.dht-test -n
  yin.vm.linker.dht-test -n yin.vm.linker.head-follow-test -n
  yin.vm.linker.dht-end-to-end-test`) and `clj -M:kondo --lint <files>`. No
  background processes. **No Node or Dart runs**; the orchestrator runs the lanes.
- Portable `.cljc`; ClojureDart traps: `#?(:cljd nil :clj ...)` with `:cljd` FIRST;
  no `0.0` literals; no duplicate `_` protocol parameters; no `#'ns/private`
  across namespaces; `for` over more than 32 elements can hand the body nil on
  Dart (use `keep` or `mapv`).
- Do not weaken or edit any existing test. If the ruling contradicts the code,
  STOP and say so with file:line.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 9d499fc0-d727-4628-a93c-78957a89f551

Then report: changed files; the exact commands and outcomes with assertion counts;
the refusal shape you produced; what `module-status` does for a non-module record
and how the nil branch is now safe; each of the four test cases and the test that
pins it; unresolved concerns; and any incomplete work.
