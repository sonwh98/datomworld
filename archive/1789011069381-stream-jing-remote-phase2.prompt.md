Created-GMT: 2026-09-10 03:31:09 GMT
Created-Local: 2026-09-10 10:31:09 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 26a798b9-cdc8-4a34-99b8-dbc27cd81708
# Task: dao.jing.remote Phase 2 — the swap
Role: Stream & Network Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-10 10:31:09 +0700 | Status: active | Rationale: Stream & Network fallback per team.md; implemented Phase 1 of this plan and every dao.space phase of this sweep

**Implementation task with write authority.** Repository
`/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`, clean at
`f1babc8`. This is a **new session**, not a resume: read the plan rather than
relying on Phase 1 memory, but your Phase 1 work is committed as `f57bfff`
and is the foundation you build on.

## Your specification

**`docs/design/dao.jing.remote.implementation-plan.md` §5 (Phase 2).**
Read §2 (invariants J1-J6, N1-N11, S1-S5, H1-H4), §3's D1, D2, D3, D5, D7,
and §0's corrections before touching code. §5.1-§5.5 are exact — where the
plan is specific, it is specific because a reviewer made it so across five
revision rounds.

**This is one phase, not two**: a v1 server cannot talk to a v2 client, so
`connect-content!` and the server constructor swap together with every
fixture that pairs them. When it lands, `dao.jing.remote` requires no v1
stream namespace.

## The five things most at risk

1. **N11 is not discharged by Phase 1.** You built `drain-outboxes` and
   `retire-call`; Phase 2 must wire **every exit of `call!`** through one
   private `settle!` that drains, `reset!`s the client's `:rpc`, and then
   returns or throws. §5.1 enumerates the exits in order —
   `request-undeliverable`, `invalid-request`, `allocator-error`,
   `terminal`, the deadline, `:done`, `:terminal`. **`settle!` must be the
   only way out of the function.** Test 9 asserts the stored state's
   `:completed`, `:diagnostics` and `:outstanding` are empty after every
   refusal, and that the three ex-data maps are equal apart from
   `:request-id`, which advances by one — the error information is unchanged
   by the leak fix.
2. **The cursor is minted at `:dao.stream/newest` BEFORE `attach!`.** Mint
   after and `/established` can land ahead of the position that would observe
   it, hanging the connect to its timeout. The docstring must say the order
   is the point and is observable, as `yin.repl.connect/open`'s does.
3. **N2's limit is stated, not fixed.** A close before the socket opens does
   **not** tear down the JDK's establishment. Test 6 asserts only that
   `connect-content!` throws `{:url :timeout-ms}` and that no handle escaped
   — **no cleanup claim, no EOF claim**. It closes its own accepted socket
   and `ServerSocket` in a `finally`; the client-side JDK connection it
   provokes cannot be closed and persists for the process. That is the leak
   N2 names, and closing it is **not yours** — it is owed by
   `dao.stream.ws.jvm` (§8), with `yin.repl` as first consumer.
4. **The cljd spellings are load-bearing.** `#?(:clj …)` **requires** reach
   the Dart compiler even though `#?(:clj …)` bodies do not. The JVM glue
   require is spliced `#?@(:cljd [] :clj [[…]])`; every JVM `defn` is
   `#?(:cljd nil :clj (defn …))` with `:cljd` **first**; every new JVM
   deftest is an **unconditional** `deftest` whose body is
   `#?(:clj … :cljd (is true …) :cljs (is true …))`, exactly as the six
   existing `network-*` tests are spelled. A `#?(:clj (deftest …))` is
   registered by the host pass and missing from the emitted Dart.
5. **The six `network-*` deftests are the neutrality proof.** They must pass
   with **only** the edits §5.2 names — the loopback literal and
   `network-file-restart-test`'s inlined fixture lines. **No assertion in any
   of the six may change.** If one must, the transport changed behaviour the
   contract tests do not see, and the phase is wrong: stop and report.

## Ownership

Write only:
- `src/cljc/dao/jing/remote.cljc` — §5.1 Build and Delete
- `test/dao/jing/remote_test.cljc` — §5.2
- `test/dao/space/stigmergy_test.clj` — §5.3, three lines
- `docs/design/dao.jing.md`, `docs/design/dao.stream.ws.md`,
  `docs/design/dao.stream.md` — §5.4 exactly

Touch nothing else: not `dao.stream.*` (Phase 0 is committed and closed),
not `coordinate.cljc`, not the plan, not `docs/orchestrator-log.md`.

## Verification you must run and report

- `clojure -M:test`, `bb test:cljs`, `bb test:cljd` — full, unfiltered, with
  **assertion counts**. Baseline at `f1babc8`: clj **1448 / 165465**, cljs
  **1350 / 35016**, cljd **+1304**, all zero failures. Confirm
  `Testing dao.jing.remote-test` in the Node output.
- `clj -M:cljs -m shadow.cljs.devtools.cli compile demo` — 212 files, 0
  warnings.
- `clj -M:kondo --lint src/cljc/dao/jing/remote.cljc` — baseline 0/0.
- **The closure greps**: `grep -n 'dao\.stream\.rpc\|dao\.stream\.ws\|rpc-ws/\|rpc-client/' src/cljc/dao/jing/remote.cljc test/dao/jing/remote_test.cljc`
  → **nothing**; `grep -rn '\[dao\.stream :as\|dao\.stream\.rpc' src/cljc/dao/jing/`
  → nothing for `remote.cljc`. Report actual numbers; **never edit toward a
  target**.
- `stigmergy_test`'s five scenarios pass unmodified beyond §5.3's three
  lines — that file is the end-to-end proof.

## Report

Write to `collab/1789011069381-stream-jing-remote-phase2.glm-5.3.findings.md`,
starting with Completed-GMT, Completed-Local, Coding-Agent: glm,
Session-ID: 26a798b9-cdc8-4a34-99b8-dbc27cd81708. State what you built and deleted, exact commands with
outcomes and counts, any invariant you could not honor and why, and anything
left owing. **Do not stage or commit.** If your budget runs out mid-phase,
leave the tree readable and say precisely what remains rather than leaving a
half-applied edit.
