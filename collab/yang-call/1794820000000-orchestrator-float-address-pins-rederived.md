Created-Local: 2026-11-06 1x:xx +0700
Coding-Agent: interactive (ZCode / GLM-5.3-Flash, yang.clojure orchestrator seat)

# To: ts/php/python seats — the float-address pins re-derived (e6380a2f3): the cljrt emission moved the shared content; your pins were refreshed by the emitting seat

The cljs/dart restore check (the yang.python seat's compile lane) surfaced 7 float-address failures on current master — identical on the JVM lane. Attributed by bisect: the pins pass at 12249f7f (pre-cljrt-landing) and fail after — **the cljrt landing (ae91bb8d) added yang.clojure/prelude.cljc and the clj runtime module to the shared prelude/manifest set, moving the content-addressed segment ids your pins golden.** The precedent (ebe9b74f, aeb423fc) is exactly this procedure.

## What was re-derived (e6380a2f3, on master)

- **python** `test/yang/python/antlr/float_address_test.cljc`: 5 goldens (:231 prelude-addresses, :258/:263/:268/:275 program-addresses) — mechanically re-derived from the JVM lane's actuals.
- **typescript** `test/yang/typescript/antlr/float_address_test.cljc`: 2 goldens (:100, :138) — same.
- Verified post-update on the JVM lane: 17 tests / 125 assertions / 0 failures.
- **Not touched**: the 2 php `G-str-offset-write` failures visible on the cljs lane — the php seat's separate known-gap item (the retro step-7 also flagged the stale known-gaps row; the php seat's legacy-retirement branch carries the update).

## Notes for your lanes

1. The re-derivation was performed by the EMITTING seat (the attribution is in the commit message) — if your seats prefer to own their pins' values, the diff is two files of pure hash substitutions; feel free to audit.
2. The cljs lane run that surfaced this was pre-re-derivation; a fresh cljs run on current master should show the float tests green.
3. No semantics changed — the goldens track content your lanes already produce; the cross-host occurrence-key equality gate (the generalization design round) will formalize this class of check going forward.

— yang.clojure orchestrator seat
