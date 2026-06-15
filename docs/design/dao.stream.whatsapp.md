# Plan: `dao.stream.whatsapp` — read/write WhatsApp through a self-hosted gateway

> **Note on the current implementation.** Whatever `dao.stream.whatsapp` code
> already exists (the earlier Meta Cloud API attempt) can be **ignored**. Do not
> refactor it. Implement from scratch against this design; the old code is not a
> starting point and carries no constraints.

## Context

datom.world models all IO as DaoStreams: a descriptor passed to `ds/open!`
realizes a transport that satisfies the orthogonal `IDaoStreamReader` /
`IDaoStreamWriter` / `IDaoStreamBound` protocols. We already have an `:http`
transport (`dao.stream.http`), a bidirectional WebSocket transport
(`dao.stream.ws`), and a request/reply helper (`dao.stream.apply`).

We want a `:whatsapp` transport so agents can **send** messages (`put!`) and
**receive** inbound messages (`next`) as ordinary stream values, with no
callbacks leaking outside the stream boundary.

**No Meta Cloud API, no Business API, no app registration.** The only thing
required is a **valid WhatsApp account** (an ordinary phone number you control).
We talk to WhatsApp the same way WhatsApp Web / WhatsApp Desktop does: the
**multi-device protocol** — a Noise handshake, Signal-protocol end-to-end
encryption, and protobuf framing — established by scanning a QR code once and
persisting the resulting device credentials.

Per the dao.stream boundary rule (below dao.stream is swappable plumbing; use
libraries for crypto/protobuf, don't hand-roll), that protocol is **not**
reimplemented. It lives in a separate **WhatsApp gateway** process that drives a
mature library, and exposes WhatsApp to dao.world over a WebSocket. dao.stream
is the consumer; the gateway is plumbing.

Decisions:
- Transport: **multi-device Web protocol via a self-hosted gateway**, not Cloud API.
- Gateway library: **Baileys** (`@whiskeysockets/baileys`), a pure WebSocket
  Node implementation of the multi-device protocol — no headless browser, it
  already handles the Noise/Signal/protobuf layer. (whatsapp-web.js is the
  browser-automation alternative; rejected as heavier and flakier.)
- Gateway implementation: **ClojureScript** compiled to a Node script via
  shadow-cljs (`:whatsapp-gateway` → `target/whatsapp-gateway.js`), source at
  `src/cljs/datomworld/whatsapp_gateway.cljs`. Still a separate Node process; the
  npm deps (baileys, ws, pino, qrcode-terminal) live in the root `package.json`.
- Boundary: gateway ↔ dao.stream is a **WebSocket** carrying a small JSON
  command/event protocol. The gateway is the server; dao.stream connects as a
  client.
- Auth (QR pairing) happens **inside the gateway** and is surfaced to dao.stream
  as read-stream events (`:whatsapp/qr`, `:whatsapp/connected`), so even login
  appears as a stream emission rather than an out-of-band side channel.
- Acks are **fire-and-forget read events** (`:whatsapp/ack`): a `put!` does not
  block waiting for confirmation, and there is no send-id correlation. Delivery
  and read receipts simply appear on the inbound stream like any other event.
- The gateway **broadcasts** inbound events to **every** connected client. A
  short-lived sender and a long-lived listener can coexist; each independent
  `ds/open!` sees the full inbound stream from its connection onward.

```
  agent (Yin.VM)
        │  put! {:to .. :text ..}      next → {:whatsapp/from ..}
        ▼
  dao.stream.whatsapp  ──── WebSocket (JSON frames) ────▶  WhatsApp gateway
   (:whatsapp transport)                                   (CLJS→Node + Baileys)
        ▲                                                        │
        └──────────── dao.stream boundary ───────────┘          ▼
                                                         multi-device protocol
                                                         (Noise/Signal/protobuf)
                                                                 │
                                                                 ▼
                                                            WhatsApp servers
```

## The gateway (below the boundary, separate process)

A small CLJS→Node service (source `src/cljs/datomworld/whatsapp_gateway.cljs`,
built via shadow-cljs `:whatsapp-gateway`; docs in `gateway/whatsapp/`) that:
- Uses Baileys `makeWASocket` with `useMultiFileAuthState` to persist device
  credentials to disk, so QR pairing is a one-time step and the account
  reconnects on restart.
- Runs a **WebSocket server** (the `ws` package) on a local port (default
  `3003`). dao.stream connects to it. Accepts **multiple concurrent clients**
  and **broadcasts** every inbound event frame to all of them.
- **Event frames** (gateway → dao.stream), one JSON object per WS message:
  - `{"event":"qr","data":"<qr-string>"}` — emitted until paired.
  - `{"event":"connected"}` / `{"event":"disconnected","reason":...}`.
  - `{"event":"message","data":{from, id, timestamp, type, text, raw}}` —
    normalized from Baileys `messages.upsert`.
  - `{"event":"ack","data":{id, status}}` — delivery/read receipts.
- **Command frames** (dao.stream → gateway), one JSON object per WS message:
  - `{"cmd":"send","to":"<jid>","text":"..."}`.
  - `{"cmd":"send","to":"<jid>","raw":{...}}` — pass-through for media/templates.

  `to` is always a fully-qualified WhatsApp JID (`<number>@s.whatsapp.net`).
  The number→JID conversion is done **above** the boundary in
  `outgoing->command` (pure and testable), so the gateway receives JIDs ready
  for Baileys `sendMessage`.
- Reconnect/backoff and credential storage live entirely here. dao.stream never
  sees the Noise/Signal/protobuf layer.

The gateway is intentionally dumb: translate Baileys events ↔ JSON frames. All
interpretation (what a message means to an agent) stays above the boundary.

## dao.stream side (`:whatsapp` transport)

A `WhatsAppStream` record (mirroring `dao.stream.ws`'s record style) that holds
a raw WebSocket **client** connection to the gateway plus an inbound ringbuffer:

- **`put!`** — normalizes the value (`{:to .. :text ..}` or `{:raw ..}`) to a
  `send` command frame and writes it on the WS. Returns `{:result :ok :woke []}`.
- **`next`** — delegates to the internal inbound ringbuffer
  (`dao.stream.ringbuffer`) holding normalized inbound messages, acks, and
  connection events; cursor-based and non-destructive.
- **`close!` / `closed?`** — closes the WS connection and the inbound
  ringbuffer; status tracked in an atom.
- **`IDaoStreamWaitable` / `IDaoStreamDrainable`** — **delegated to the inner
  ringbuffer**, so blocking reads (`ds/take!!`, `ds/wait!`) and drains work
  directly on a `WhatsAppStream`. (`WebSocketStream` omits these because its
  sync protocol needs custom handling; here the ringbuffer already implements
  both, so plain delegation is correct.)

The WS client plumbing mirrors `dao.stream.ws/connect!` (Java 11 built-in
`java.net.http.WebSocket` on JVM; `js/WebSocket` on cljs; `dart:io` on cljd).
The **only** difference from `dao.stream.ws` is framing: instead of the
transit-encoded link/sync protocol used between two `WebSocketStream` peers,
each WS message is one JSON frame, and the on-message handler routes it through
`event->datom` onto the ringbuffer. Outbound goes through `command->frame`.

Registered with `(ds/defopen :whatsapp [descriptor] ...)`.

### Descriptor
```clojure
{:type :whatsapp
 :gateway-url "ws://localhost:3003"  ; optional, default; else env WHATSAPP_GATEWAY_URL
 :capacity nil                       ; inbound ringbuffer capacity / eviction
 :eviction-policy nil}
```
No access tokens, no phone-number-id, no verify-token: there is no Meta app.
The account identity lives in the gateway's persisted credentials. `:gateway-url`
follows the descriptor-wins / env-fallback reader-conditional pattern in
`agent.tzu/api-key` (`src/cljc/agent/tzu.cljc:15`).

### Pure core (cross-platform, the unit-testable surface)
- `outgoing->command` — `{:to .. :text ..}` → `{:cmd "send" :to <jid> :text ..}`;
  `{:raw ..}` passes through under `:raw`. Normalizes `:to` to a JID
  (`<number>@s.whatsapp.net`) if given a bare E.164 number; passes through a
  value that already looks like a JID.
- `event->datom` — a parsed gateway event frame → normalized stream value:
  - `message` → `{:whatsapp/from :whatsapp/id :whatsapp/timestamp
    :whatsapp/type :whatsapp/text :whatsapp/raw}`
  - `ack` → `{:whatsapp/ack {:id .. :status ..}}`
  - `qr` → `{:whatsapp/qr "<string>"}`
  - `connected`/`disconnected` → `{:whatsapp/status :connected|:disconnected}`
  - unknown event → `nil` (dropped).
- JSON via `clojure.data.json` reader-conditionally, exactly as
  `agent.tzu/json-encode|json-decode` (`src/cljc/agent/tzu.cljc:24-43`).

### Platform split
- Pure normalization (`outgoing->command`, `event->datom`) + the record: `.cljc`.
- Raw WS client wiring: per-platform `#?` branches in the **same `.cljc`** — no
  `.cljd` sibling. `java.net.http.WebSocket` (clj) / `js/WebSocket` (cljs) /
  `io/WebSocket.connect` (cljd). The CLJD code is inlined exactly as the old
  `whatsapp.cljc` inlined its `dart:io` webhook server (the ringbuffer/http
  convention), because the only platform-specific work is opening one socket and
  wiring its send/receive callbacks. (`dao.stream.ws` keeps a separate `ws.cljd`
  for historical reasons; whatsapp does not need to.)

### Connection lifecycle & backpressure (v1)
- When the local WS to the gateway drops, the stream emits
  `{:whatsapp/status :disconnected}` onto the inbound buffer, nulls its send-fn
  so subsequent `put!` calls silently no-op, and does **not** auto-reconnect.
  Re-establishing is done by opening a fresh `:whatsapp` stream.
- `put!` is fire-and-forget and applies **no backpressure**: it always returns
  `{:result :ok :woke []}` regardless of gateway/WhatsApp throughput. Acceptable
  for v1 given acks are themselves fire-and-forget read events.
- `connected?` (public helper, cf. `ws/connection-status`) reports whether the
  send-fn is currently wired, for callers/tests that need to await the socket.

## Files

- **Rewrite** `src/cljc/dao/stream/whatsapp.cljc` — this file already exists (the
  old Cloud API attempt); replace it wholesale with the record, pure core, raw
  WS client wiring, and `ds/defopen :whatsapp` described here. Do not preserve
  any of the old webhook/Bearer-token code.
- **Rewrite** `test/dao/stream/whatsapp_test.cljc` — also already exists; replace
  with the tests below.
- **Create** `src/cljs/datomworld/whatsapp_gateway.cljs` — the CLJS→Node gateway
  (`-main`): Baileys socket + `ws` server, broadcast fan-out, command handling.
- **Add** the `:whatsapp-gateway` `:node-script` build to `shadow-cljs.edn`
  (`:output-to "target/whatsapp-gateway.js"`).
- **Add** npm deps to the root `package.json`: `@whiskeysockets/baileys`,
  `pino`, `qrcode-terminal` (`ws` already present).
- **Create** `gateway/whatsapp/README.md` — protocol + one-time QR pairing,
  pointing at the shadow build. (No `package.json`/`index.js`: the gateway is
  CLJS, built from the project root.)
- **Check** `test/dart/runner.dart` — it references `dao.stream.whatsapp`; if it
  names removed Cloud-API symbols it needs a one-line adjustment, otherwise leave
  it.
- No `deps.edn` changes: `clojure.data.json` already present; the JVM WS client
  is built into Java 11.

Reference patterns to reuse (do not reinvent):
- `src/cljc/dao/stream/ws.cljc` — record + per-platform raw WS client (and the
  http-kit `as-channel` server in `listen!`, the model for the test stub gateway).
- `src/cljc/dao/stream.cljc` — protocols, `defopen`, `take!!`, `->seq`.
- `src/cljc/dao/stream/ringbuffer.cljc` — inbound buffer + cursor `next`; also the
  `IDaoStreamWaitable`/`IDaoStreamDrainable` impls the record delegates to.
- `src/cljc/agent/tzu.cljc` — env-key + JSON reader-conditional **style only**
  (the namespace itself is unused; not a real caller).
- `test/dao/stream/http_test.cljc` — inline http-kit server test harness pattern.

## Testing (TDD — write tests first, per CLAUDE.md)

`clj -M:test` (cross-platform core also compilable for cljs).

1. **Pure** (no network): `outgoing->command` text + `:raw` passthrough;
   `event->datom` over each event kind (`message`, `ack`, `qr`, `connected`,
   `disconnected`, unknown → nil).
2. **Stream contract** via ringbuffer: put normalized inbound values onto a
   `WhatsAppStream`, assert `next` reads them in order non-destructively;
   `close!`/`closed?`.
3. **Round-trip against a stub gateway** (JVM): **write** an inline http-kit WS
   **server** that plays the gateway role — model it on `ws.cljc/listen!`'s
   `as-channel` setup, not on `ws_test.cljc` (which uses mock send-fns, not a
   real socket). `ds/open!` a `:whatsapp` stream pointed at it; `put!` a message
   and assert the server received the expected `send` command JSON (with a JID
   `:to`); push a `message` event frame from the server and assert `next`
   surfaces the normalized `{:whatsapp/from ...}` datom; push a `qr` frame and
   assert `{:whatsapp/qr ...}` appears; assert `ds/take!!` (delegated to the
   ringbuffer) returns the next inbound value and blocks until one arrives.

The real Baileys gateway is exercised manually (Verification), not in unit
tests — it is swappable plumbing below the boundary.

## Verification (end-to-end)

- `clj -M:test -n dao.stream.whatsapp-test` green (the full `clj -M:test` is
  unrelated-ly blocked by `agent.tzu-test`, the dead namespace).
- Build & start the gateway (from the project root):
  `npm install && npx shadow-cljs compile whatsapp-gateway && node target/whatsapp-gateway.js`.
  On first run it prints a QR code; scan it from WhatsApp on the account's phone
  (Settings → Linked Devices → Link a Device). Credentials persist under
  `./.whatsapp-auth/`; subsequent starts reconnect without a QR.
- REPL smoke (send):
  `(def s (ds/open! {:type :whatsapp}))`,
  `(ds/put! s {:to "<test-number>" :text "hello from datom.world"})`,
  then drain a few events with repeated `(ds/take!! s)`. Because acks are
  fire-and-forget and interleaved with connection/inbound events, the ack is
  *one of* the values that appears (look for `{:whatsapp/ack ...}`), not
  guaranteed to be the first.
- Inbound: send a message **to** the linked account from another phone, and
  confirm it appears via `(ds/->seq nil s)` / `next` as a `{:whatsapp/from ..}`
  value.
