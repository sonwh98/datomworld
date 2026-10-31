Created-GMT: 2026-10-18 14:45:00 GMT
Created-Local: 2026-10-18 21:45:00 +07 (+0700)
Coding-Agent: claude-sonnet-5-5

# Task: the (host) command — report which runtime the yin.repl runs on

WORK TREE: /Users/sto/workspace/datomworld-hostcmd (branch host-command, HEAD = master; the parser is
BUILT and node_modules are INSTALLED — the lanes can run). The owner asks, while testing the same
branch on three hosts, for a way to get information about the host from the REPL prompt. Today there
is none (the command set: vm, lang, compile, reset, help, repl-state, quit, telemetry, abandon,
connect, trust/untrust).

DO:
1. A `(host)` command in src/cljc/yin/repl.cljc: prints the host runtime and version — at minimum the
   platform keyword (JVM / Node / Dart) via the standard reader conditionals; add the runtime version
   where the host exposes it cheaply and portably (the JVM's java.version, the Node process.version;
   on Dart only what cljd exposes without new dependencies — if nothing cheap, the platform alone is
   honest). Reuse an existing host-constant if the codebase already carries one (survey first: grep
   for platform/host identity in src/clj*/... host.clj/cljs/cljd files) rather than inventing a
   parallel one.
2. Register it: command-heads, the help text (the (host) line), the dispatch branch. The message
   format: one honest line, e.g. "Host: JVM 21.0.5" / "Host: Node v25.6.1" / "Host: Dart".
3. Tests: a per-host test asserting the platform keyword (JVM asserts JVM, the Node lane asserts
   Node, the Dart lane asserts Dart) in whichever test files cover the command surface (repl_test +
   the shared packets pattern if the command is language-independent — it is a shell command, not a
   frontend program: it works regardless of the selected frontend).
4. Docs: the help text, src/cljc/yin/vm/docs/yin.repl.md (the command list / a line in the Starting
   section noting (host) identifies the runtime — useful when several REPLs are attached over
   (connect)).

VERIFY (lanes SEQUENTIALLY — the documented race): repl_test, main_test, frontend_catalog_test on
the JVM; the JVM quick tier; the Node lane (bb test:cljs) — its host test must assert Node; the Dart
lane (bb test:cljd) — its host test must assert Dart. kondo 0/0 + cljstyle on changed files.

CONSTRAINTS: single turn, no approvals. One commit to branch host-command. Do NOT push, do NOT touch
master. End with: the command's output per host (the actual captured lines), the test results per
host, and anything open.
