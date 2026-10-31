Coding-Agent: claude
Session-ID: e4154ec6-1ac3-4b9a-8441-0d572d865cd5
Model: claude-fable-5-1

Completed-GMT: 2026-09-30 10:28:33 GMT
Completed-Local: 2026-09-30 17:28:33 +07 (Asia/Ho_Chi_Minh)

All three findings are fixed in the docs; nothing in `src/` or `test/` was touched, and nothing is staged or committed.

## 1. HIGH — need-cookie reply could exceed the request

I took both options together, so the bound holds by rule rather than by a size estimate.

- **Silence rule** (`docs/design/dao.jing.dht.md:448`): the need-cookie reply is sent only if its encoded length is no larger than the datagram that caused it; otherwise the datagram is dropped in silence. There is no other reply to an unproven source.
- **Fixed reply shape** (`dao.jing.dht.md:444-447`): the need-cookie reply is exactly version, `:op :reply`, `:q`, `:id`, `:cookie`, `:need-cookie true`, and nothing else.
- **Padded first contact** (`dao.jing.dht.md:452`, wire field at `:369`): a request without a cookie carries `:pad` so the datagram is at least `first-contact-bytes`, which is 256 and a constant of wire v1. A cookie-less request is only ever a padded `:ping`; `:find`, `:store`, `:fetch` and chunks are never sent to a peer without a cookie.
- **Recovery** (`dao.jing.dht.md:460`): a need-cookie reply triggers one re-send that does not consume a try. After a timeout the requester discards that peer's cookie and sends a padded `:ping` before the next try, which covers a small datagram dropped in silence.
- **Chunks** (`dao.jing.dht.md:465`): an invalid request chunk falls under the same gate and size rule.

The 256 figure is my estimate of the reply's encoded size (roughly 130 bytes) with headroom; S2 acceptance proves it by test (`dao.jing.dht.md:544`).

## 2. MEDIUM — S2 needed cookies that were deferred to S4

I moved the whole cookie protocol into S2 and left only the keyed MAC for S4.

- **One mechanism, in S2** (`dao.jing.dht.md:425`): issue, echo, verify, the gate, the size rule, padding and freshness.
- **Only `cookie-for` differs between slices** (`dao.jing.dht.md:433`):
  - S2 stand-in: the first 16 bytes of SHA-256 over epoch, observed host and observed port. It is unkeyed and deterministic, so tests can predict it, and forgeable, so exposure stays loopback-only.
  - S4 replacement: HMAC-SHA-256 keyed by the epoch secret, from each host's library. The wire and state are unchanged.
- **Secret** (`dao.jing.dht.md:65`, `:73`): required with a socket from S4 only.
- **S2 scope and acceptance** (`dao.jing.dht.md:539-545`): an acknowledged write used the cookie each counted peer's fresh reply carried, and a store with a missing or wrong cookie causes no work.
- **S4 scope and acceptance** (`dao.jing.dht.md:579-585`): the stand-in is gone, a cookie cannot be computed without the secret, and the spoofed-source test runs across datagram sizes from 1 byte to the budget, chunks included.

## 3. MEDIUM — S1 owned a Base64 helper that S2 needed

I froze the existing codec as S2's seam, so S1 and S2 are independent again.

- **Base64 seam** (`dao.jing.dht.md:512`): S2 uses the existing `dao.jing/bytes->base64` and `dao.jing/base64->bytes`, which are already strict padded standard-alphabet. S1 creates `dao.stream.base64` in the same format and does not touch `dao.jing`. S3 repoints `dao.jing` onto the shared helper and proves both agree.
- **Slice order line** (`dao.jing.dht.md:508`): now states that neither S1 nor S2 consumes anything the other produces.
- **S3 acceptance** (`dao.jing.dht.md:577`): adds the repoint.
- **Datagram doc** (`docs/design/dao.stream.datagram.md:76-80`): no longer claims `dao.jing` uses the shared helper from S1; it points at the seam rule instead.
