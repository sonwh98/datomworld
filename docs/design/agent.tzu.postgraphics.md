# agent.tzu → WebSocket → Flutter postgraphics canvas

## Context

`agent.tzu` already turns prompts into `dao.postgraphics` frame vectors
(`src/cljc/agent/tzu.cljc:304`) but those frames just sit in a returned
vector. We want the agent, running as a JVM REPL via `clj -M:atzu`, to push
frames across the wire into a Flutter app that hosts a WebSocket server and
renders them live. The user types `tzu>` prompts and watches a separate
Flutter window animate.

The transport already exists: `yin.repl` ↔ `yin.repl.flutter` runs over
`dao.stream.ws` today, with the JVM side connecting as a client via
`ws/connect!` (`src/cljc/yin/repl.cljc:350`) and the Flutter side hosting
via `(ds/open! {:type :websocket :mode :listen ...})` inside `repl/serve!`
(`src/cljc/yin/repl.cljc:876`). Wire format is transit-encoded link
envelopes — the postgraphics demo can ride exactly the same pipe;
`stream_write` of a frame op-vector goes through `link/local-put` →
`transit/encode` → text frame, and is delivered to the remote ringbuffer
on the other end. No new transport.

What is missing:

1. `IDaoStreamWaitable` on `WebSocketStream` (`src/cljc/dao/stream/ws.cljc:31`,
   `src/cljd/dao/stream/ws.cljd:10`). `postgraphics-widget` →
   `bind-stream!` parks readers via `ds/register-reader-waiter!` on
   `:blocked`; the record currently only implements Writer/Reader/Bound,
   so binding the widget directly would throw. The record's `next`
   already delegates to `(:remote-stream @link-state-atom)`, which is a
   ringbuffer that *does* implement waitable; delegating the waiter ops
   the same way closes the gap in ~5 lines per file. (yin.repl avoids
   this today by polling `:blocked` with `Thread/sleep` rather than
   parking — see `yin/repl.cljc:898`. The widget can't poll, so it needs
   the waitable surface.)
2. An LLM-callable tool (`ws_open`) that opens a `dao.stream.ws`
   connection from inside the agent loop and registers the resulting
   stream so the LLM can use the existing `stream_write` tool to push
   frame ops.
3. A Flutter demo that starts a `dao.stream.ws` server, displays the URL
   (mirroring `yin.repl.flutter/info-card`), and binds the incoming
   connection's stream to `postgraphics-widget`.

## Design

```
tzu REPL  ──ws_open──►  WebSocketStream (Java-11 HttpClient WebSocket)
   │                          │
   │     stream_write          │  transit/encode (link envelope) per text frame
   ▼                          ▼
[OPENAI_API_KEY]      ┌──────────────────────────────────┐
                      │  Flutter app (CLJD)              │
                      │  dao.stream.ws/listen!           │
                      │  on-message → remote-stream      │
                      │  postgraphics-widget renders     │
                      │  (URL shown via info-card UI)    │
                      └──────────────────────────────────┘
```

Same transport `yin.repl` uses; same `link/transit` semantics. The agent
calls `(ds/put! ws-stream frame-op-vector)`; the Flutter side reads the
remote ringbuffer via `postgraphics-widget`'s internal `bind-stream!`.

Tool shape: `ws_open(url, stream_id)` registers a persistent connection in
the agent's registry; the LLM uses the existing `stream_write` to push
individual frames. Single connection at a time on the Flutter side; a
newer connection replaces (closes) the older one.

## Files to change

### 1. `src/cljc/dao/stream/ws.cljc` and `src/cljd/dao/stream/ws.cljd` — IDaoStreamWaitable delegation

Add a fourth protocol impl to the `WebSocketStream` record (both files,
lines 31-64 and 10-43 respectively). The body delegates to the underlying
`:remote-stream` ringbuffer, which already implements
`IDaoStreamWaitable`:

```clojure
ds/IDaoStreamWaitable
(register-reader-waiter! [_ position entry]
  (ds/register-reader-waiter! (:remote-stream @link-state-atom)
                              position entry))
(register-writer-waiter! [_ _entry]
  ;; ws.put! never blocks — link buffers locally until the socket is
  ;; ready — so writer-waiter registration is a no-op.
  nil)
```

That's it. Existing `test/dao/stream/ws_test.cljc` keeps passing. Optional
small new test: open a listen/connect pair, register a reader-waiter on
the connect-side stream at position 0, `put!` from the listen side, and
assert the waiter entry's wake fn is invoked.

### 2. `src/cljc/agent/tools.cljc` — add `ws_open` tool

Three small edits:

- **Require:** add `[dao.stream.ws]` to the top-level `:require` block
  (line 9 area) so the `:websocket` opener is registered when the tool
  namespace loads. (It already may load transitively via other paths;
  add it explicitly so the tool is self-contained.)
- **Tool definition:** append to `stream-tools` (after `file_write` at
  line 108):

  ```clojure
  {"type" "function",
   "function"
   {"name" "ws_open",
    "description"
    "Open a WebSocket connection to the given URL and register it as a named stream in the registry. Subsequent stream_write calls on this stream send the value to the remote peer over dao.stream.ws (transit-encoded link envelope). Typical use: push dao.postgraphics frame op-vectors to a renderer.",
    "parameters"
    {"type" "object",
     "properties"
     {"url" {"type" "string",
             "description" "WebSocket URL, e.g. ws://localhost:8765"},
      "stream_id" {"type" "string",
                   "description"
                   "Id to register the connection under. Reuse it in subsequent stream_write calls."}},
     "required" ["url" "stream_id"]}}}
  ```

- **Dispatch:** add a `case` branch in `execute-tool-call` (between
  `http_fetch` at line 207 and the default at line 228). `ws_open` must
  *mutate* the registry so subsequent tool calls see the new stream;
  promote the registry to an atom at the call boundary (see change #3),
  then:

  ```clojure
  "ws_open"
  (let [url (get args "url")
        sid (get args "stream_id")
        stream (ds/open! {:type :websocket, :mode :connect,
                          :url url,
                          :capacity 64, :eviction-policy :evict-oldest})]
    (swap! stream-registry assoc sid stream)
    {"role" "tool", "tool_call_id" call-id,
     "content" (str "ok: registered '" sid "' for " url)})
  ```

  Update the other branches that currently read `(get stream-registry id)`
  to use `(get @stream-registry id)`.

### 3. `src/cljc/agent/tzu.cljc` — promote registry to atom

Three small edits:

- `run-agent` (line 314): document that callers pass a stream-registry
  atom (or coerce internally with
  `(let [reg-atom (if (instance? clojure.lang.IAtom stream-registry) stream-registry (atom stream-registry))] ...)`).
- `-main` (line 367): change `{"io" (ds/open! ...)}` to
  `(atom {"io" (ds/open! ...)})`. The same atom must persist across the
  outer REPL loop so connections opened by `ws_open` survive between user
  turns.
- Add `[dao.stream.ws]` to the `:require` block (line 12) so the
  `:websocket` opener is registered in the JVM REPL process.

### 4. `src/cljd/datomworld/demo/story.cljd` — new Flutter demo

Mirrors `src/cljd/yin/repl/flutter.cljd` very closely — yin.repl.flutter
already starts a `dao.stream.ws` server, populates `device-ip` /
`interface-addresses` / `server-status` `ValueNotifier`s from
`(io/NetworkInterface.list)`, and exposes a reusable `info-card` widget.
The story demo copies the same scaffolding and only differs in (a) the
canvas widget below the URL card, (b) what it does with the connected
stream.

Top-level state (idiomatic Flutter — `ValueNotifier` so the widget tree
rebuilds via `ValueListenableBuilder`):

```clojure
(def device-ip            (m/ValueNotifier "..."))
(def interface-addresses  (m/ValueNotifier []))
(def server-status        (m/ValueNotifier "starting"))   ; "starting" | "listening" | "connected"
(def current-stream       (m/ValueNotifier nil))          ; the active WebSocketStream
(def ^:private server*    (atom nil))                     ; the {:stop-fn ...} handle

(def default-port (if io/Platform.isIOS 8766 8765))       ; avoid yin.repl's 7777/7778
```

Lifecycle (mirrors `yin.repl.flutter/start-server!` / `stop-server!` /
`load-device-ip!`):

- `load-device-ip!` — copy verbatim from
  `src/cljd/yin/repl/flutter.cljd:21-41`. Populates `device-ip` and
  `interface-addresses` from `(io/NetworkInterface.list)`.
- `start!`:
  1. `(stop!)` to clear any previous server.
  2. `(reset! server* (ds/open! {:type :websocket, :mode :listen,
       :port default-port,
       :capacity 64, :eviction-policy :evict-oldest,
       :on-connect on-connect, :on-disconnect on-disconnect}))`.
  3. `(set! (.-value server-status) "listening")` and `(load-device-ip!)`.
- `on-connect [stream]`:
  - If `(.-value current-stream)` is non-nil, close it (single-connection
    semantics).
  - `(set! (.-value current-stream) stream)` and
    `(set! (.-value server-status) "connected")`.
- `on-disconnect [stream]`:
  - If `(= stream (.-value current-stream))`,
    `(set! (.-value current-stream) nil)` and
    `(set! (.-value server-status) "listening")`.
- `stop!`: call `(:stop-fn @server*)`, clear `server*` and
  `current-stream`, set status to "stopped".

`view` widget: `Scaffold` with `AppBar` "Story Demo"; body is a `Column`:

1. **URL card** — same shape as `yin.repl.flutter/info-card`
   (repl/flutter.cljd:93-138), retitled "Postgraphics Canvas":
   - `ws://0.0.0.0:<port>` mono line.
   - `ValueListenableBuilder` on `device-ip` showing
     `ws://<ip>:<port>` (this is the line the user reads off the
     screen to paste into the agent prompt).
   - `ValueListenableBuilder` on `interface-addresses` listing every
     interface.
   - `ValueListenableBuilder` on `server-status` showing
     `status: listening` / `status: connected`.
2. **Canvas** — `SizedBox` (720x540) hosting a `ValueListenableBuilder`
   on `current-stream` that renders `(pg/postgraphics-widget stream)`
   when the stream is non-nil and a "waiting for connection..."
   `Center`/`Text` placeholder otherwise.

The `postgraphics-widget` accepts the `WebSocketStream` directly. Its
internal `bind-stream!` reads frames via `ds/next` (delegates to
`:remote-stream`) and parks via the `IDaoStreamWaitable` delegation
added in change #1. `link/dispatch` writes incoming `:put` messages into
the remote ringbuffer; the widget wakes and renders each frame.

Required `:require`s (mirroring `yin.repl.flutter`):

```clojure
(:require ["dart:async" :as async]
          ["dart:io" :as io]
          ["package:flutter/material.dart" :as m]
          [clojure.string :as str]
          [dao.postgraphics.flutter :as pg]
          [dao.stream :as ds]
          [dao.stream.ws])
```

Possible follow-up (out of scope): the `device-ip` /
`interface-addresses` / `load-device-ip!` / `info-card` machinery is
generic and currently lives only in `yin.repl.flutter`. If a third
Flutter demo needs the same pattern, extract it into a shared
`datomworld.flutter.netinfo` ns. For now, copy-paste — one duplication
is not yet a pattern.

### 5. `src/cljd/datomworld/demo/main.cljd` — add demo button

Same pattern as the existing buttons (lines 31-112): add
`[datomworld.demo.story :as story-demo]` to the require block and a new
`SizedBox`/`ElevatedButton` in the `Column` children calling
`story-demo/start!` / `story-demo/view` / `story-demo/stop!`.

## Reused utilities (do not reimplement)

- `dao.stream.ws/listen!` / `connect!` — the transport. No edits beyond
  the `IDaoStreamWaitable` delegation in change #1.
- `yin.repl.flutter` — structural template for the Flutter demo: same
  `m/ValueNotifier` UI shape, same `info-card` widget, same
  `NetworkInterface.list` discovery.
- `agent.tools/execute-tool-call` dispatch table — add one `case` branch.
- `dao.postgraphics.flutter/postgraphics-widget` — already binds streams.
- `dao.postgraphics.terminal/bind-stream!` — drives the pump inside the
  widget; now able to park on a `WebSocketStream` once it implements
  waitable.

## Verification

1. **Unit (JVM):** at the REPL,
   ```clojure
   (require '[agent.tools :as t] '[dao.stream.ws])
   (def reg (atom {}))
   (t/execute-tool-call
     {"id" "x"
      "function" {"name" "ws_open"
                  "arguments" "{\"url\":\"ws://localhost:9999\",\"stream_id\":\"flutter\"}"}}
     reg)
   ```
   Confirm `(keys @reg)` contains `"flutter"` and the return content
   begins with `"ok: registered"`.

2. **Waitable delegation:** add a small unit test that opens a
   `connect!`/`listen!` pair on an ephemeral port, registers a
   reader-waiter on the client-side stream at position 0, `put!`s a
   value from the server side, and asserts the waiter fires. Run via
   `clj -M:test`; existing `test/dao/stream/ws_test.cljc` continues to
   pass.

3. **End-to-end:**
   - `clj -M:cljd compile`, run the Flutter app on desktop/simulator,
     tap **Story Demo**. Note the URL displayed (e.g.
     `ws://192.168.1.42:8765`).
   - In a second terminal: `OPENAI_API_KEY=… clj -M:atzu`.
   - At the `tzu>` prompt, ask the agent:
     ```
     connect to ws://192.168.1.42:8765 as "flutter", then send these frames one at a time using stream_write: a red filled circle at center on a 400x400 canvas, then move it right ten pixels per frame for ten frames
     ```
     Watch the Flutter canvas animate. The `server-status` notifier
     flips from "listening" to "connected".
   - Type `/exit` to leave the REPL; the connection closes; Flutter
     status returns to "listening".

## What is intentionally not in this design

- A new agent prompt for "story" generation. The user can have the agent
  call `prompt->frames` separately and then stream the result; richer
  story-mode generation is a follow-up.
- Beat-by-beat streaming generation, persisted frame logs, audio /
  captions / scene-title overlays.
- QR-coded URL display, mobile-friendly large text, network discovery
  beyond `NetworkInterface.list`'s first IPv4.
- Per-connection authentication or server-side frame validation. The
  postgraphics terminal already emits `:dao.terminal/rejection` signals
  for invalid ops; surfacing those in the UI is out of scope here.
- Multi-canvas / multi-connection fan-out (single-connection model only).
- A separate raw-EDN transport. `dao.stream.ws` (transit+link) is the
  one transport across the project; postgraphics rides it the same way
  yin.repl does.
