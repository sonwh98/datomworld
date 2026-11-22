Created-GMT: 2026-11-23 07:20:00 GMT
Created-Local: 2026-11-23 14:20:00 ICT (+07)
Coding-Agent: interactive (ZCode / glm-5.3-flash, orchestrator seat)
Session-ID: 01a203e5-cbc1-7352-a82a-c38258633577 (resume of your slice 1/2 thread)

# Task: store-swap follow-up — make the two dht-args tests host-free on Dart

Role: VM / Storage Engineer (fix round)

Implementers:
- Model: gpt-6.2-sol | Assigned: 2026-11-23 14:20 ICT | Status: active | Rationale: your slices are landed; this is the one red your slice exposed

Landing-gate finding: on the Dart lane, yin.repl.dht-args-test now COMPILES
(it could not compile on Dart before your slice — verified at base ea8e8e15
and master tip, both rc=1 cljd compile crashes) but two tests fail:

- join-after-global-flags-keeps-the-explicit-store
- startup-expands-the-supplied-reader-command-before-parsing

Failure: main/startup refuses with "no home directory on this host: give
--dir and --key explicitly" — because yin.shell.cli/home-dir* is
#?(:cljd nil ...) (Dart has no home directory BY DESIGN) and the tests call
main/startup without explicit --dir/--key while expecting :refusal nil.
The namespace's own docstring says "Subcommand expansion is host-free".

Fix: make both tests host-free — supply explicit --dir <tmp> and --key
<tmp-file> (or the equivalent options the startup parser accepts) so
home-dir is never consulted, on every host. Do NOT change src/. Do NOT
change the assertions' intent (the :peers/:follow/:refusal expectations stay).
Keep the tests meaningful on JVM/Node too (they must still pass there).

## Boundaries

- Work ONLY in /Users/sto/workspace/datomworld-store-swap (branch
  store-swap-p1 has your committed slices; add your fix as working-tree
  changes, do NOT commit — the orchestrator commits).
- Files you may change: test/yin/repl/dht_args_test.cljc ONLY.

## Verification (foreground, individual tests only)

- clojure -M:test -n yin.repl.dht-args-test  (JVM)
- Report exact commands and outcomes.

## Deliverable

Report to
collab/yin-repl/1795380000000-engineer-store-swap-dart-args-fix.gpt-6.2-sol.findings.md
in the worktree. Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: what changed, verification outcomes, findings path.
