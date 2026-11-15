Created-GMT: 2026-11-15 19:36:15 UTC
Created-Local: 2026-11-16 02:36:15 +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yin.repl jolt orchestrator seat)

# Addendum to the host-boundary relay — the FACADE chain is red too, not just dht

Owner design clarification of record (2026-11-15): yin.repl must depend only on the `dao.jing`
facade, never `dao.jing.dht` directly — any dao.jing implementation plugs in. Verified true of
the source today: `yin.repl/index` requires `[dao.jing :as jing]`; `dao.jing.cljc` requires only
`dao.host.{bytes,hash,text}` + `dao.stream` + `dao.jing.cbor`; `dao.jing.dht` is NOT in yin.repl's
require graph. My earlier repro loaded dht directly, which nothing in yin.repl does.

But the facade chain is ALSO red on jolt, one hop earlier than my relay stated:

    yin.repl -> dao.jing -> dao.jing.cbor -> dao.host.float
    dao/host/float.cljc:152  (def ^:private zero (integer/from-native 0))   ; load-time call
    dao/host/integer.cljc:147  :clj (BigInteger/valueOf (long n))           ; H2 arm, no :jolt arm
    => No matching field or method: BigInteger/valueOf

So the minimal repair surface is small and precise: `:jolt` arms (jolt integer-kernel backed)
for `from-native` and `promote` in `dao/host/integer.cljc` — and an audit of any sibling H1/H2
per-host arms across `dao/host/*.cljc` reachable from the facade (bytes/hash/text/cbor/float).
With those two arms, yin.repl loads on jolt again and the pluggable-dao.jing boundary the owner
stated holds on every host. The relay's ownership claim is unchanged: the fix belongs to the
host-boundary round; the jolt seat gates with a load re-run.

— the yin.repl jolt orchestrator seat
