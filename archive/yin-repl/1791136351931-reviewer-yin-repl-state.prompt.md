Created-GMT: 2026-10-04 17:52:31 GMT
Created-Local: 2026-10-05 00:52:31 +07
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: yin-repl-state

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-05 00:52:31 +07 | Status: active | Rationale: independent family from the Claude author

Perform a read-only review of commit cfab3932 on branch worktree-yin-repl-state
(repository /Users/sto/workspace/datomworld/.claude/worktrees/yin-repl-state;
`git show cfab3932` is the whole diff). Do not edit anything.

## What was built

Owner request (verbatim): "the yin.repl should store its state in an edn file and
when it is started again from its last state. the state can be change with cmd
line params". Owner decisions: the state is the node's configuration (flags), not
the shell's definitions (a durable index store already recovers those); the file
is `~/.yin/<name>/state.edn` (the node directory; `--name`, default `node`;
`--dir` overrides); command-line flags override saved values AND are saved back;
`--reset` forgets, `--no-state` skips.

Files: src/cljc/yin/repl/state.cljc (new: split-args, flags->args, resolve-flags,
changed, load-flags, save!), src/cljc/yin/repl/main.cljc (startup 2-arity,
save-state!, expand-args extra map, plain-args, --vm), tests
test/yin/repl/state_test.cljc and main_test.cljc, docs
src/cljc/yin/vm/docs/yin.repl.md ("Saved state").

## What to check

1. Correctness of resolution: `saved < command line`, repeated flags replace
   whole, subcommand `:unset` (dht serve/join clear a saved --dht-publish), a
   valued flag with no value, flags given twice, --reset with nothing else.
2. Portability: the code is .cljc for JVM, Node (cljs) and ClojureDart. Known
   ClojureDart traps: its EDN reader throws on whitespace before a closer
   (`[1\n]`, `{:k 1 }`) — check `render`; `#?(:clj ...)` does NOT exclude code
   from the cljd build; no home directory on cljd (`home-dir*` returns nil).
3. Failure behaviour: unreadable/foreign state file must refuse (never be
   silently skipped); a write failure must warn and not stop the node; a
   refused startup must not save; a clash between saved flags and the command
   line must name the file and --reset.
4. Safety: the state file holds flag values only (key file PATH, never key
   material) — confirm nothing secret is written; `--dht-key` is a path.
   Check what happens when two processes share a node directory.
5. Test isolation: the 1-arity `startup` is stateless and only the three
   `-main`s pass {:persist? true}; test/yin/repl/dht_process_test.clj's spawn!
   passes --no-state. Look for any other path that could write to the real
   ~/.yin during tests.
6. Anything the tests do not cover that should be, and any claim in the docs
   that the code does not support.

## Already verified locally by the orchestrator (do not rerun)

- `clj -M:test -r "yin\.repl\..*"`: 248 tests, 2165 assertions, 0 failures
  (includes the process tests and the new state-test).
- Real processes on JVM, Node and Dart: `dht serve` saved a node; a bare
  restart (`--name n1`, or `--dir` on Dart) resumed it; `--dht-peer` override
  was saved and stuck; `--no-state` left the file alone; `--reset` cleared it.
- `clj -M:kondo` on the touched files: one pre-existing error in
  src/cljc/yin/repl/host.cljc (not in this diff).

Static analysis is what is wanted. Report findings ranked most severe first,
each with the file:line, the failing scenario, and a verdict (confirmed from the
code, or plausible). End with whether the change is ready to merge.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: <exact thread id>
