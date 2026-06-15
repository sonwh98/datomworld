# WhatsApp gateway for dao.stream

This is plumbing **below** the dao.stream boundary. It drives a single real
WhatsApp account using the multi-device Web protocol (via
[Baileys](https://github.com/WhiskeySockets/Baileys), which handles the
Noise handshake, Signal end-to-end encryption and protobuf) and exposes it to
dao.stream over a local WebSocket. No Meta Cloud API, no Business API, no app
registration: just an ordinary WhatsApp account.

The gateway is written in ClojureScript and compiled to a Node script. The
source is `src/cljs/datomworld/whatsapp_gateway.cljs`; the shadow-cljs build is
`:whatsapp-gateway`. npm deps (`@whiskeysockets/baileys`, `ws`, `pino`,
`qrcode-terminal`) live in the project's root `package.json`.

## Run

From the project root:

```sh
npm install
npx shadow-cljs compile whatsapp-gateway
node target/whatsapp-gateway.js
```

(Or `npx shadow-cljs watch whatsapp-gateway` to recompile on change.)

On first run it prints a QR code. Scan it from the account's phone:
**WhatsApp → Settings → Linked Devices → Link a Device**. Device credentials are
persisted under `./.whatsapp-auth/` (override with `WHATSAPP_AUTH_DIR`), so later
starts reconnect without a QR. That directory is git-ignored and is account
access; treat it as a secret.

The WebSocket listens on `ws://localhost:3003` by default
(`WHATSAPP_GATEWAY_PORT` to change). Point dao.stream at it:

```clojure
(require '[dao.stream :as ds] '[dao.stream.whatsapp])
(def s (ds/open! {:type :whatsapp}))            ; default ws://localhost:3003
(ds/put! s {:to "15551234567" :text "hello from datom.world"})
(ds/->seq nil s)                                 ; inbound messages, acks, qr, status
```

## Protocol

One JSON object per WebSocket message.

Gateway → client (events):

| frame | meaning |
| --- | --- |
| `{"event":"qr","data":"<string>"}` | pairing QR (until linked) |
| `{"event":"connected"}` | account online |
| `{"event":"disconnected","data":{"reason":<code>}}` | link dropped |
| `{"event":"message","data":{from,id,timestamp,type,text,raw}}` | inbound message |
| `{"event":"ack","data":{id,status}}` | delivery/read receipt |

Client → gateway (commands):

| frame | meaning |
| --- | --- |
| `{"cmd":"send","to":"<jid>","text":"..."}` | send a text message |
| `{"cmd":"send","to":"<jid>","raw":{...}}` | send raw Baileys content (media, etc.) |

Inbound events are **broadcast** to every connected client, so a short-lived
sender and a long-lived listener can coexist. `to` should be a fully-qualified
JID (`<number>@s.whatsapp.net`); dao.stream's `outgoing->command` normalizes
bare numbers above the boundary, and the gateway tolerates them too.

## Notes

- Unofficial: this logs in as a linked device, the same as WhatsApp Web. It is
  not endorsed by WhatsApp and is subject to their terms; use a number you
  control and don't abuse it.
- Reconnect/backoff lives here. dao.stream sees only `connected` /
  `disconnected` events and never the protocol layer.
