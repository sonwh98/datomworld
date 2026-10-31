Completed-GMT: 2026-09-02 19:29:48 GMT
Completed-Local: 2026-09-03 02:29:48 Asia/Ho_Chi_Minh

WITHDRAWN.

HIGH | docs/design/yin.repl.implementation-plan.md:438-442,532-542 | The prerequisites say handoff slots are “per connection,” and R4 specifies each medium’s capacity but not the fixed pool’s size. `dao.stream.ws.md:205-214,250-253` requires a fixed, non-empty collection whose exhaustion provides bounded admission control. Dynamically allocating slots per connection would defeat that bound; otherwise the implementer must invent the pool size. | Define a concrete positive slot count, or a required/configured count with a stated default, and replace “slots per connection” with “a fixed pool of slots, at most one pending connection per slot.”

No other delta finding: identity propagation, offer/ack ordering, writer retention, pre-ack delivery prohibition, exhaustion behavior, wire-contract status, and remaining gates match the amended authority.
