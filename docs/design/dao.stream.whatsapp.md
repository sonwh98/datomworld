# Plan: `dao.stream.whatsapp` — read/write WhatsApp over the Meta Cloud API

## Context

datom.world models all IO as DaoStreams: a descriptor passed to `ds/open!`
realizes a transport that satisfies the orthogonal `IDaoStreamReader` /
`IDaoStreamWriter` / `IDaoStreamBound` protocols. We already have an `:http`
transport (`dao.stream.http`), a bidirectional record transport
(`dao.stream.ws`), and a request/reply helper (`dao.stream.apply`).

We want a new `:whatsapp` transport so agents can **send** WhatsApp messages
(`put!`) and **receive** inbound messages (`next`) as ordinary stream values,
with no callbacks leaking outside the stream boundary. Target is the **Meta
WhatsApp Business Cloud API** (`graph.facebook.com`): send via
`POST /{phone-number-id}/messages` with a Bearer token; receive via webhooks.

Decisions:
- API: **Meta Cloud API**.
- Scope: **send + receive** (inbound via a JVM webhook server).
- Send results: surfaced **as read-stream events** (`:whatsapp/ack` /
  `:whatsapp/error`), so every side effect appears as a stream emission.

## Design

A `WhatsAppStream` record (mirroring `dao.stream.ws`'s record style) that:
- **`put!`** — normalizes the value to a Cloud API JSON payload and fires an
  outbound send by opening an `:http` stream (`dao.stream.http`, reused, not
  reimplemented). A JVM forwarder drains the single HTTP response and emits
  `{:whatsapp/ack {:id ...}}` or `{:whatsapp/error {...}}` onto the **inbound**
  ringbuffer. Returns `{:result :ok :woke []}`.
- **`next`** — delegates to an internal inbound ringbuffer
  (`dao.stream.ringbuffer`) holding both received messages and send acks;
  cursor-based and non-destructive.
- **`close!` / `closed?`** — stops the webhook server (if running) and closes
  the inbound ringbuffer; status tracked in an atom.

Registered with `(ds/defopen :whatsapp [descriptor] ...)`.

### Descriptor
```clojure
{:type :whatsapp
 :api-url "https://graph.facebook.com/v21.0"  ; optional, default
 :phone-number-id "..."   ; for send; else env WHATSAPP_PHONE_NUMBER_ID
 :access-token "..."      ; for send; else env WHATSAPP_ACCESS_TOKEN
 :capacity nil            ; inbound ringbuffer capacity / eviction
 :eviction-policy nil
 :webhook {:port 3003 :path "/webhook" :verify-token "..."}}  ; inbound; verify-token else env WHATSAPP_VERIFY_TOKEN
```
Credentials: descriptor wins, env fallback — follow the reader-conditional
pattern in `agent.tzu/api-key` (`src/cljc/agent/tzu.cljc:15`). No secrets in
`config.clj`.

### Pure core (cross-platform, the unit-testable surface)
- `outgoing->payload` — `{:to .. :text ..}` → Cloud API JSON map
  `{:messaging_product "whatsapp" :recipient_type "individual" :to .. :type "text" :text {:body ..}}`;
  pass `:raw` through untouched for templates/media.
- `webhook->messages` — parsed webhook envelope →
  vector of normalized `{:whatsapp/from :whatsapp/id :whatsapp/timestamp
  :whatsapp/type :whatsapp/text :whatsapp/raw}` (walks
  `entry[].changes[].value.messages[]`; optionally maps `value.statuses[]` to
  `{:whatsapp/status ...}`).
- `send-url` — `[api-url phone-number-id]` → string.
- `verify-response` — `[params verify-token]` → 200 + `hub.challenge` on match,
  else 403.

JSON via `clojure.data.json` reader-conditionally, exactly as
`agent.tzu/json-encode|json-decode` (`src/cljc/agent/tzu.cljc:24-43`).

### Inbound webhook server (JVM-only, gated like ws.cljc's http-kit server)
- `http-kit` `run-server` on `:port` (already a dep).
- `GET :path` → `verify-response` (Meta subscription handshake).
- `POST :path` → parse JSON body, `webhook->messages`, `ds/put!` each onto the
  inbound ringbuffer, return `200` fast (Cloud API requires a prompt 2xx).
- The webhook handler putting onto the ringbuffer **is** the explicit stream
  representation of the inbound callback (no hidden callbacks).

### Platform split
- Outbound send + all pure normalization: `.cljc`, works clj/cljs/cljd through
  `dao.stream.http`.
- Webhook server + ack-forwarder (`future` + `ds/take!!`, like
  `agent.tzu/chat-completion`): `#?(:clj ...)` only, mirroring how
  `dao.stream.ws` gates its `org.httpkit.server` code.

## Files

- **Create** `src/cljc/dao/stream/whatsapp.cljc` — record, pure core, webhook
  server, `ds/defopen :whatsapp`.
- **Create** `test/dao/stream/whatsapp_test.cljc` — see Testing.
- No `deps.edn` changes: `http-kit`, `clojure.data.json`, `ring` already present.

Reference patterns to reuse (do not reinvent):
- `src/cljc/dao/stream/http.cljc` — `:http` transport (used by `put!`).
- `src/cljc/dao/stream.cljc` — protocols, `defopen`, `take!!`, `->seq`.
- `src/cljc/dao/stream/ws.cljc` — record + platform-gated server pattern.
- `src/cljc/dao/stream/ringbuffer.cljc` — inbound buffer.
- `src/cljc/agent/tzu.cljc` — env-key + JSON + http-over-stream call style.
- `test/dao/stream/http_test.cljc` — inline JVM http-kit test server pattern.

## Testing (TDD — write tests first, per CLAUDE.md)

`clj -M:test` (cross-platform core also compilable for cljs).

1. **Pure** (no network): `outgoing->payload` text + `:raw` passthrough;
   `webhook->messages` over a realistic text-message envelope, statuses, and
   empty payload; `verify-response` match → challenge, mismatch → 403.
2. **Stream contract** via ringbuffer: put normalized inbound values onto a
   `WhatsAppStream`, assert `next` reads them in order non-destructively;
   `close!`/`closed?`.
3. **Send path** with an inline JVM http-kit stub server (copy the harness from
   `test/dao/stream/http_test.cljc`): point `:api-url` at it, `put!` a message,
   assert the server saw the expected JSON body + `Authorization: Bearer`
   header, and that a `:whatsapp/ack` appears via `next`.
4. **Webhook integration** (JVM): start the server on an ephemeral port, `POST`
   a sample webhook JSON, assert `next` surfaces the normalized message; `GET`
   handshake returns the challenge.

## Verification (end-to-end)

- `clj -M:test` green.
- REPL smoke: `(def s (ds/open! {:type :whatsapp :phone-number-id ... :access-token ...}))`,
  `(ds/put! s {:to "<test-number>" :text "hello from datom.world"})`, then read
  the ack with `(ds/take!! s)`.
- Inbound: expose the webhook port via a tunnel (e.g. ngrok), register the URL +
  `verify-token` in the Meta App dashboard, send a message to the business
  number, and confirm it appears via `(ds/->seq nil s)` / `next`.
