Created-GMT: 2026-09-02 14:06:28 GMT
Created-Local: 2026-09-02 22:06:28 Asia/Shanghai

# Role: Adversarial reviewer of an implementation plan, against real code

## What this is

`docs/design/yin.repl.implementation-plan.md` is a new plan to build a second
Yin REPL — `yin.repl` — on the redesigned DaoStream contract, **beside** the
existing one. Nothing existing may be modified: `dao.stream`, `dao.stream.ws`,
`dao.stream.rpc.*`, `yin.repl` and their tests stay exactly as they are and keep
their consumers. The two implementations coexist.

Unlike the earlier document reviews in this project, **the source code is
evidence here.** This is a plan to change code that exists, so read it.

## Read

The plan and its authorities:

- `docs/design/yin.repl.implementation-plan.md`   — under review
- `docs/design/dao.stream.md`                        — the contract (authority)
- `docs/design/dao.stream.ws.md`                     — the ws spec (authority)
- `docs/design/dao.stream.implementation-plan.md` — sibling plan it depends on
- `docs/design/datom.world.md`                       — governing axioms and invariants

The code it plans to replace, and must not break:

- `src/cljc/yin/repl.cljc`                — the existing REPL (1085 lines)
- `src/cljc/dao/stream.cljc`              — v1 contract
- `src/cljc/dao/stream/ws.cljc`           — v1 ws transport
- `src/cljc/dao/stream/rpc/client.cljc`   — v1 RPC client
- `src/cljc/dao/stream/rpc/server.cljc`   — v1 RPC server
- `src/cljc/dao/stream/rpc/ws.cljc`       — v1 ws convenience
- `deps.edn`                              — the alias set

Precedence: `datom.world.md` > `dao.stream.md` > `dao.stream.ws.md` > the plans.

## What to judge, in this order

1. **Executability.** Could someone holding only these documents execute R1
   through R5 in order and finish? Name every place they would have to invent
   something, and say what they would have to invent. Be concrete.

2. **The v2 RPC shape.** The plan claims the v1 layer cannot be ported and must
   be rebuilt, because a v2 ws handle has no reader surface, v1 cursors are
   `(:position …)` arithmetic, and v1 returns a Promise on cljs and a Future on
   cljd. Its replacement: a client built from **two** handles (a writer for
   requests, a reader plus cursor for the response medium), and `request!` /
   `poll!` steps driven by the caller's own loop instead of a blocking `call!`.
   - Is that diagnosis right? Read `rpc/client.cljc` and check.
   - Is the two-handle, step-driven shape correct for a REPL's actual usage —
     an interactive read-eval-print loop that must stay responsive?
   - What does a caller have to write to use it, and is that acceptable? Sketch
     the loop if it helps you decide.
   - Does anything break when two requests are outstanding at once?

3. **The narrowing.** The plan deliberately excludes Phase 3 of the sibling
   plan, `forward-step` (4b), the serving composition (4c), Phase 5, the
   conformance harness, `retry`/`dedup`, a v2 UDP transport, remote telemetry,
   and the Flutter widget.
   - Is anything on that exclusion list actually required to make the REPL work?
     Being wrong here means discovering it mid-implementation.
   - Is anything *included* that the REPL does not need?

4. **cljd.** The plan makes clj, cljs (Node) and cljd all first class, and takes
   ownership of the cljd v2 ws transport over `dart:io` because the sibling plan
   defers it. Read the `#?(:cljd …)` sections of `src/cljc/dao/stream/ws.cljc`.
   - Is a `dart:io` WebSocket **server** available and usable the way the plan
     assumes, or is cljd client-only in practice?
   - Does the step-driven, no-Future design actually work on a Dart event loop,
     where the REPL's own read loop must yield for IO to progress? This is the
     question I am least sure of; be specific.
   - The plan cites a project trap: `#?(:clj …)` does not exclude code from the
     cljd build, so `#?(:cljd nil :clj …)` with `:cljd` first is required. Does
     the plan's design step on this anywhere?

5. **The duplication.** `yin.repl` cannot share code with `yin.repl`, because
   sharing would mean editing `yin.repl`. The plan copies roughly 60% of 1085
   lines verbatim and suggests putting the copied stream-independent half in
   `yin.repl.core`. Read `yin/repl.cljc` and judge: is 60% right, is the
   split line where the plan implies it is, and is copying the right call given
   the constraint?

6. **The two open decisions.** D2 (a reply travels on the connection's
   server-side handle, rather than via a per-conversation served stream) and D3
   (what the descriptor names, given the endpoint forwards nothing outbound).
   Are the recommendations right? D3 in particular: `dao.stream.ws.md` says a
   descriptor names a served stream and that the same descriptor reaches the
   same stream every time — what does `ws://host:port/repl` name here, and is
   the answer consistent with that spec?

## Output

Findings ranked most severe first: severity | file:line | the claim | the exact
correction. Cite line numbers, in the plan and in the source.

Then three short sections:

- **What would fail first**, if someone started R1 tomorrow.
- **What an implementer cannot determine from these documents.**
- **Whether the scope is right** — too narrow, too wide, or correct — and your
  reasoning. Say plainly if you think the plan should not be executed as written.

Read-only; do not edit files.
