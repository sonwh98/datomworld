Created-GMT: 2026-09-16 13:54:10 GMT
Created-Local: 2026-09-16 20:54:10 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 5d8abc05-1d58-4203-b9f5-0d952224f1b3

# Task: U4 — port the browser REPL demo to the v2 wire

Role: Stream & Network

Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-16 20:54:10 +07 | Status: active | Rationale: well-specified consumer port against an existing wire protocol and host adapter pattern

## Context

`docs/design/yin.vm.v1-retirement.implementation-plan.md` — read section
"### D3 — the browser REPL demo is ported to the v2 wire before v1 goes"
in full (has the exact adapter shape and file dispositions) and section
"### U4 — the browser REPL client on the v2 wire" (the acceptance
criteria) before starting.

**A concurrent, independent unit (U1: deleting `dao.await` v1) is running
right now in this same working tree.** Your file set is fully disjoint —
it touches `src/cljc/dao/await.cljc` and `test/dao/await_test.cljc` only.
No conflict expected. **Do not run `bb test:cljd` or any ClojureDart
compile/test command** — U1 (and later U2) own the CLJD lane tonight;
your verification is JVM/CLJS/Node only, which this unit's own criteria
don't require CLJD for anyway.

## Task

Per D3's disposition, exactly:

1. `src/cljs/dao/stream/ws/browser.cljs` — a `:connect!`-only adapter
   over the DOM `WebSocket`, the shape `dao.stream.ws/make-attacher`
   asks for (per `host.cljc`: start connecting, never wait, synchronously
   return `{:send! … :close! …}`), mirroring `node.cljs`'s `connect!` with
   the DOM event names. No `:bind!` — satisfies `host-common/adapter?`,
   not `binder?` (read `host.cljc`'s actual code for the exact contract,
   don't just take this summary's word for it — this is the real spec to
   implement against).
2. `src/cljs/datomworld/demo/yin_repl.cljs` — keeps the CodeMirror
   editor and history panel from the existing `yin_repl.cljs`; replaces
   the v1 `put-request!`/`poll-response` pair with
   `driver/create-state {:host browser-adapter}`, `driver/submit-line!`
   on Eval, and one non-overlapping `setInterval` of `tick-millis` that
   owns the driver state and drains `driver/take-outbox` into the
   history — mirror the Node host's composition
   (`src/cljc/yin/repl.cljc`'s Node-facing composition, read it) with
   the readline replaced by the editor. Its instruction line names
   `clj -M:clj-yin-repl --port 8080 --headless` and the
   `daostream:ws://…` URL.
3. Compose the browser adapter directly in `yin_repl.cljs` rather than
   through `yin.repl.host` — that namespace's cljs shadow selects
   Node's `ws` package per-*build*, not per-platform, and requiring it in
   the browser `:demo` build would pull `js/require` into the browser
   (read `yin.repl.host`'s actual requires to confirm this before
   relying on it).
4. Repoint `src/cljs/datomworld/demo.cljs`'s "Yin REPL" card to the new
   `yin_repl.cljs` demo. **Leave v1 `yin_repl.cljs` completely
   untouched** — it stays until U6 per D6's stated ordering; do not delete
   or edit it.
5. `test/.../browser_test.cljs` (find the right location matching this
   project's existing `dao/stream/ws/` test layout — check
   `test/dao/stream/` for the sibling `node_test.cljs` or similar to
   match convention exactly) — Node test using the `ws` package as the
   peer (Node has no DOM socket) or a minimal `WebSocket` global shim,
   proving the adapter returns `{:send! :close!}` synchronously, deposits
   `:ws/opened` and `:ws/closed` through the boundary, and never blocks.

## Verify

- `clj -M:kondo --lint` on every file you create/touch.
- The shadow `:test` build (Node) — `Testing dao.stream.ws.browser-test`
  must appear in the output. Use `bb test:cljs` or the equivalent scoped
  shadow-cljs invocation; report the exact command and output.
- Manual desktop check from the plan's own criteria — you likely cannot
  run a real browser session yourself; if so, say exactly what's
  unverified (which specific manual step) rather than skip it silently.
- Do NOT run `bb test:cljd` or any `clj -M:cljd` command.
- Do not stage or commit. Do not touch `src/cljc/dao/await.cljc`,
  `test/dao/await_test.cljc`, or any file outside what this task names.

## Deliverable

Report back: the exact diff (new + changed files), the exact verification
commands and output, and an explicit list of anything from the plan's
stated criteria you could not verify yourself (e.g. the manual browser
check) versus what you did verify.
