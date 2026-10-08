Created-GMT: 2026-09-16 14:11:12 GMT
Created-Local: 2026-09-16 21:11:12 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: pending (provider-generated)

# Task: Review U4 — browser REPL client on the v2 wire

Role: Adversarial Review

Implementers:
- Model: gemini-3.1-pro-high | Assigned: 2026-09-16 21:11:12 +07 | Status: active | Rationale: cross-family review; GPT capacity is nearly exhausted tonight, reserved for a smaller unit

## Context

`docs/design/yin.vm.v1-retirement.implementation-plan.md` — read section
"### D3 — the browser REPL demo is ported to the v2 wire before v1 goes"
and "### U4 — the browser REPL client on the v2 wire" in full.

New/changed files (all uncommitted): `src/cljs/dao/stream/ws/browser.cljs`
(new — DOM WebSocket `:connect!`-only adapter), `src/cljs/datomworld/demo/yin_repl.cljs`
(new — CodeMirror-based demo composing the adapter directly, not through
`yin.repl.host`), `test/dao/stream/ws/browser_test.cljs` (new — Node
test using a minimal `WebSocket` global shim), `src/cljs/datomworld/demo.cljs`
(one-line repoint of the "Yin REPL" card's require to the new demo). v1
`src/cljs/datomworld/demo/yin_repl.cljs` is untouched, staying until U6 per
the plan's D6 ordering.

Verified independently by the orchestrator: `clj -M:kondo --lint` on all
four files — 0 errors, the only 2 warnings confirmed pre-existing (verified
against `git show HEAD:src/cljs/datomworld/demo.cljs`). `bb test:cljs`
(full suite) → 1376 tests, 0 failures, `Testing dao.stream.ws.browser-test`
confirmed in output. `clj -M:cljs -m shadow.cljs.devtools.cli compile demo`
→ clean build. Per the plan's own criteria, one manual check
(`clj -M:clj-yin-repl --port 8080 --headless`, connecting from a real
browser, evaluating `(+ 1 2)`, observing detached-notice/reconnect
behavior) could not be run by either the implementer or the orchestrator —
no browser or long-running server process available in this environment.
Flag this explicitly as unverified, not silently accepted.

## Task

1. Read `src/cljs/dao/stream/ws/browser.cljs` against the actual
   contract `dao.stream.ws/make-attacher` and `host.cljc`'s
   `adapter?`/`binder?` distinction require (read those files directly).
   Does it correctly implement a `:connect!`-only adapter — synchronous
   return of `{:send! :close!}`, correct DOM event subscription (only
   `message`/`close`/`error`, never `open`, per the Node adapter's
   pattern), correct binary-vs-string message classification?
2. Read `src/cljs/datomworld/demo/yin_repl.cljs` against `yin.repl`'s
   Node-host composition pattern (the file this was meant to mirror,
   read it) — is the `driver/create-state`/`driver/submit-line!`/
   `driver/take-outbox` wiring correct, and is the `setInterval` genuinely
   the sole owner/writer of driver state (no second state owner)?
3. Confirm the reasoning for composing the adapter directly rather than
   through `yin.repl.host` is actually sound — read `yin.repl.host`'s
   real requires; does requiring it in the browser `:demo` build really
   risk pulling `js/require`/Node's `ws` package into the browser bundle,
   as claimed?
4. Confirm `browser_test.cljs`'s WebSocket shim genuinely exercises
   `connect!`'s real synchronous-return contract and the `:ws/opened`/
   `:ws/closed` boundary deposits, not just a superficial smoke test.
5. Confirm v1 `yin_repl.cljs` is genuinely untouched and the `demo.cljs`
   diff is exactly the one-line repoint claimed, nothing more.

Do not edit any file.

## Deliverable

A findings list, most severe first, and an explicit verdict: ready for
Architect sign-off (noting the one unverifiable manual check as an
accepted, explicit gap) or not.
