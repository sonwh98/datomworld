# Slice 3 round 2 — ws-project + composition implemented; retirement deferred

## What was implemented

**NEW `src/cljc/dao/stream/ws_project.cljc`** (portable cljc; compiles and runs on all three lanes):
- `terminal-events` (`ws_project.cljc:34`) — the four channel-ending deposits per 3.1: `:ws/closed`, `:ws/ended`, `:ws/not-found`, `:ws/transport-error`.
- The projection (3.1's `ws-project` step): `projection` (`:43`) holds the reading cursor on the attachment's traffic medium plus the wired ring; `project!` (`:70`) keeps only events whose `:ws/attachment` names this channel, appends each `:ws/payload`'s `:ws/value` onto the ring (`contains?`-guarded, so nil rides), drops `:ws/error` diagnostics, and on any terminal event closes the ring (`:86`); `step!` (`:96`) reads the medium to blocked, adopts the gap recovery cursor, marks the projection ended on the medium's own `end`; `closed?`/`reading-cursor` accessors.
- The accepting peer: `make-acceptor` (`:140`), `adopt!` (`:180`, creates the per-connection media via the composition's `:make-media` and acknowledges the offer per `dao.stream.ws.md` Serving with `:ws/command :ws/accept` + `:ws/deposit` + `:ws/admission`), `accept-step!` (`:230`, transport step → offer adoption → second transport step so the accept frame is on the wire before anything is answered → per session projection then `remote/mirror-step` over the projected reader and the socket handle), `sessions` (`:258`), `session-end` (`:266`).
- The dialing peer: `dial` (`:288`), `dial-attach!` (`:336` — ws attach, projection, then `remote/attacher` over `{channel-descriptor {:reader ring :writer handle}}`; the link's drain then runs inside every reflection operation), `dial-step!` (`:362` — projection then this end's own mirror step, so the dialing end serves the other direction on the same connection), `channel` (`:377`).

**Deferral comments in `src/cljc/dao/stream/ws.cljc`** — comments only, zero behavior change (verified by diff): at `make-endpoint` (`ws.cljc:410-414`, the `:served` table) and at `accept-slot!` (`ws.cljc:554-556`, the wire `{:ws/frame :ws/accept}` send), both naming the slices-4/5 retirement and why it waits (the copy path's consumers).

## Ambiguities resolved (minimal readings)
1. The traffic medium's own `end` closes the projection but not the channel ring — 3.1 names only the four deposited events as ring-closers (unit-tested as such).
2. `:ws/payload` without `:ws/value` is dropped as malformed, never appended as nil.
3. A medium gap adopts the recovery cursor (mirrors `mirror-step`/drain gap handling); a gap is not channel loss.
4. One `dial` = one active attachment; reattachment composes a fresh dial with a fresh minted cursor (`dao.stream.ws.md`'s one-active-attachment client boundary).
5. `accept-step!` runs `endpoint-step` twice per tick so the mirror never answers a non-open ws handle (a `full` answer is dropped by `write-answer!`).
6. The toy's "complete history" source is a ring closed by its owner, which is what makes `next c1` answer the source's own `end`.

## Tests
- `test/dao/stream/ws_project_test.cljc` (portable; ran on all three lanes), 10 tests / 36 assertions: value forwarding in order incl. nil; `:ws/attachment` filtering incl. a foreign terminal event; `:ws/error` diagnostics dropped; cursor keeping across steps; payload-then-terminal in one step; `:ws/closed`/`:ws/ended` close the ring, later steps project nothing, the ring refuses appends; `:ws/not-found`/`:ws/transport-error` close the ring; opened/offer/valueless-payload ignored; gap recovery; medium end vs ring fate.
- `test/dao/stream/ws_project_jvm_test.clj` (JVM; **real loopback sockets** — http-kit listener + `java.net.http` client), 3 tests / 25 assertions: the section-5 toy end to end (probe confirmation's `:dao.stream.remote/surface #{:reader}` via the link's event writer; `cursor :oldest` → retry then `ok c0`; `next c0` → `ok "hello" c1`; `next c1` → `end`); both directions serving on one connection (B reflects `"str-2"` over the same accepted session and reads `"backward"` through A's dial-side mirror); a closed connection observed as the link's end (close → both projections close → both links answer `transport-error` `:dao.stream.remote/channel-gone`; `descriptor` still `ok`).

## Lane counts (sequential, solo; kondo 0/0 and cljstyle clean on all touched files)
- JVM `clj -M:test`: **2,268 tests / 183,303 assertions / 0 failures, 0 errors** (run twice; stable). Baseline at HEAD excluding my namespaces: 2,255/183,242 — test count matches your quoted 2,255 exactly; the quoted 183,224 is 18 assertions below HEAD (the slice-2 r2 fix assertions post-date that figure). My delta: +13/+61.
- Node `bb test:cljs`: **2,177 tests / 49,865 assertions / 0 failures, 0 errors** (baseline 2,167/49,829 → +10/+36 = my portable namespace).
- Dart `bb test:cljd`: **2,139 passed, all tests passed** (baseline 2,129 → +10).

## What the orchestrator must run
The true cross-host socket proofs, which need a remote-protocol peer program per host (the `dao.stream.slice-peer` pattern): compose these same `dao.stream.ws-project` acceptor/dial pieces with `dao.stream.ws.node` and `dao.stream.ws.dart` in a peer namespace, then run **clj→Node** (clj listener serves, Node dials and reads), **Node→clj** (Node's acceptor serves, clj's dial reflects back over the same connection), and **cljd→clj** (clj serves, the Dart peer dials). The namespace is portable and already compiles/runs on the Node and Dart lanes (unit tests above). The browser dials-and-reads lane is also yours: no browser harness exists in the three lanes; the composition is identical with `dao.stream.ws.browser/connect!` as the dial side.

Status: COMPLETE