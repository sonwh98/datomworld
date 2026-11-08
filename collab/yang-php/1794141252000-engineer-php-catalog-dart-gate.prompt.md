Created-GMT: 2026-11-08 02:5x GMT
Coding-Agent: muse
Session-ID: df736bff-e7d8-4593-986c-20385c15ee80

# Task: gate the two php-catalog dart assertions (nightly 20261108 dart lane triage)

Role: Engineer (same pattern as 9032649e; owner-approved host-gate approach)

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-08 | Status: active

## Facts (nightly 20261108-08 dart lane @ 001b8844, shiva, evidence target-ci/dart-shards-20261107-2036/all.log)

Two dart reds, both caused by the php frontend not being in dart's frontend
catalog (installed: :clojure :python only; php's ANTLR runtime is JS-only, not
ported to dart — owner-level design gap, recorded):

1. yin.repl-test/language-and-compile-commands-behave-as-they-do-in-v1:
   expects "Switched to :yang/php"; dart refuses unavailable-parser.
2. yin.repl.main-test/the-interactive-composition-holds-every-standard-language:
   asserts the unavailable-parser refusal lists :yang/php as INSTALLED.

Both assert a host-variable catalog state. The established pattern (9032649e,
merged as 80e2ad00) gates host-inapplicable assertions on :cljd.

## Your job

1. Work in a fresh scratch clone on jupiter (~/ci/ has dart clones; make your own
   from GitHub origin, checkout --detach origin/master = 80e2ad00; verify).
2. Read both tests fully. Gate the php-dependent assertions on :cljd — but
   PRESERVE what the tests verify on every host: where the test checks the
   refusal message's SHAPE ("a language keyword is not a selector: the refusal
   names what is installed, and the session keeps its frontend"), keep that
   check host-independent (assert the refusal happens and names the installed
   set generically, or gate only the exact catalog-content expectation). Do not
   delete assertions; gate the host-variable parts. Mirror how 9032649e gated
   its tests (read it: git show 9032649e).
3. Verify: dart scoped run of the two namespaces (bb src/dev/cljd_agg.clj --only
   yin.repl-test,yin.repl.main-test with DATOM_SLOW_TESTS=1 — NOTE main-test is
   slow, budget ~30 min) → green on dart; JVM scoped run of the same two
   namespaces (clojure -M:test -i :slow -n yin.repl-test / -n yin.repl.main-test)
   → still green (gates must not weaken JVM/node coverage). NOTE: JVM dht-test
   has ONE ledgered seam red (rounds-larger...) — if your runs touch it, report
   it as known-seam, not new.
4. No commits; report diff --stat + outputs verbatim.

Begin the final response exactly with Completed-GMT / Completed-Local.
