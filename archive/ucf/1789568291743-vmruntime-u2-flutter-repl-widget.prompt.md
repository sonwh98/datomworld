Created-GMT: 2026-09-16 14:18:11 GMT
Created-Local: 2026-09-16 21:18:11 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: b32a407d-d148-4ae5-887b-1dfe5e4d4296

# Task: U2 — the v2 Flutter REPL widget

Role: VM Runtime

Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-16 21:18:11 +07 | Status: active | Rationale: multi-file composition across .cljc/.cljd with careful state-ownership discipline, matches complex agentic coding strength

## Context

`docs/design/yin.vm.v1-retirement.implementation-plan.md` — read section
"### D1 — the Flutter widget is a thin view over a Flutter-free `embed`
namespace" and "### U2 — the v2 Flutter REPL widget" in full before
touching anything. D1 has the exact composition and file dispositions;
read it precisely, it is the real spec.

**No other unit is running concurrently right now** — the CLJD lane
(`bb test:cljd`, `flutter`/`dart` tooling) is free for you to use; U1 (the
only other unit that needed it) is committed and done.

Read the actual current v1 implementation before porting anything:
`src/cljd/yin/repl/flutter.cljd` (what exists today), `src/cljc/yin/repl/serve.cljc`
(the `step`/`stop!`/`stopped?`/`summary` contract D1 composes),
`src/cljc/yin/repl/core.cljc` (`create-state`/`make-vm`/`make-session`/
`rebuild-session`, where the `:primitives` option needs to land),
`test/yin/repl_serve_test.cljc:25-30` (the injected host-function
pattern D1 says `v2_embed_test.cljc` should reuse), and
`src/cljd/datomworld/demo/dao_gui.cljd` /
`src/cljd/datomworld/demo/solar_system.cljd` (the two current consumers of
`flutter.cljd`, to know exactly what they call).

## Task

Per D1's disposition, exactly:

1. **`core.cljc`'s additive `:primitives` option.** `create-state` accepts
   `:primitives` (a map merged *over* the REPL primitives), stores it as
   `:extra-primitives`; `make-session`/`make-vm`/`rebuild-session` read it,
   so `(reset)` and `(vm :ast-walker)` keep the host-supplied functions.
   Add the one deftest D1 names in `test/yin/repl_core_test.cljc`: a
   state created with `{:primitives {'answer (fn [] 42)}}` evaluates
   `(answer)` to 42 before and after `(reset)` and after `(vm :ast-walker)`.
2. **`src/cljc/yin/repl/embed.cljc`** (new) — no Flutter, no Dart, no
   timer, runs on all three hosts: `start`, `step`, `status`/`status-text`,
   `stop`/`stopped?`, exactly per D1's four bullet points. `host` defaults
   to `(yin.repl.host/websocket)`; must accept an injected fake for
   testing (mirror `v2_serve_test.cljc:25-30`'s pattern).
3. **`src/cljd/yin/repl/flutter.cljd`** (new) — the notifiers,
   `default-port`, `load-device-ip!` (ported verbatim from v1's
   `flutter.cljd`), and `start-server!`/`stop-server!`/`info-card` exactly
   per D1's stated shape (device IP loaded first, advertised host from it
   or `"localhost"`, one `Timer.periodic` as the SOLE writer of the
   endpoint atom — this single-state-owner discipline is load-bearing,
   get it exactly right). `eval-input!` is deliberately NOT ported (D1
   explains why — a second evaluator outside the step owner is exactly
   the "second state owner" the driver forbids).
4. **`test/yin/repl_embed_test.cljc`** (new) — covering exactly what
   U2's criteria list: wildcard-bind + advertised-host → first `step`
   reports `:bind-succeeded`; `status-text` reads "listening" with zero
   clients; a request through an adopted session calling a `:primitives`-
   supplied primitive returns its value; `(reset)` through the same
   session keeps that primitive; `stop` then stepping reaches `stopped?`.
   Must run on `clj`, `cljs`, AND `cljd` — no reader-conditional traps
   (this project's known one: a `:clj`-only branch does not exclude under
   ClojureDart; use explicit `:cljd` branches where needed).
5. **Repoint the two demo consumers** (`dao_gui.cljd`, `solar_system.cljd`)
   and `dao_gui.md` from v1 `flutter.cljd`/`yin.repl` to the new v2
   `flutter.cljd`/`embed`, per D1's URL-form note (`daostream:ws://…`
   with `/repl`).
6. **Leave v1 `src/cljd/yin/repl/flutter.cljd` completely untouched** — it
   stays until U6 per D6's ordering. Do not delete or edit it.

## Verify

- `clj -M:kondo --lint` on every file you create/touch.
- `clj -M:test -n yin.repl.embed-test -n yin.repl.core-test` (JVM)
  — must pass.
- `bb test:cljs` (full suite) — `Testing yin.repl.embed-test` must
  appear in the output; report the full pass/fail count.
- `bb test:cljd` (full suite) — same namespace must appear and pass;
  **clear `test/cljd-out` first** (`rm -rf test/cljd-out`) before this run
  — a stale compiled artifact for a renamed/deleted namespace can
  silently mask a real failure (see `docs/agents/build-n-test.md`, just
  updated with this exact hazard tonight). Report the full pass/fail
  count for this host too, not just this one test namespace.
- The plan's own criteria name a **manual Flutter startup smoke test** as
  the only check that exercises `flutter.cljd` itself (`clj -M:cljd
  compile yin.repl.flutter datomworld.demo.dao-gui
  datomworld.demo.main`, then `flutter run`, selecting the demo, checking
  status reaches "listening", connecting from a desktop v2 REPL client,
  etc. — the full sequence is in D1/U2's text). You most likely cannot
  run an interactive Flutter app or a real device/emulator in this
  environment. If so, say explicitly that this specific manual check is
  unverified — do not skip it silently, and do not claim it passed.

Do not stage or commit. Do not touch any file outside what this task
names.

## Deliverable

Report back: the exact diff (new + changed files), the exact verification
commands and output for all three hosts, and an explicit statement of
whether the manual Flutter smoke test was run or is an accepted,
explicit gap (most likely the latter — say so plainly if so).
