# Known-gaps ledger and the standing review rule (Q6)

Adopted by the owner 2026-10-14 (mob consolidation Q6).

## Review rule
- Stop on satisfied gates, not a round count.
- The reviewer is a different model family from the author.
- Reviewer prompts quote the slice row and its acceptance lines.
- Blocking is P0 or P1, or a P2 in claimed acceptance, inside the slice's scope.
- Fix rounds may not add public entry points, catalog ids or frontends.
- A finding in code the slice should not have written means revert, not fix.
- A disputed in-scope P1 goes to a third-family Architect, not a third engineer round.

## Ledger
| gap | evidence | owner seat | closing slice | severity |
|---|---|---|---|---|
| a host entry under a pinned name refuses with `:linked nil`, same as absent | fable L-d review P3-2 | linker | open | P3 |
| `semantic-register.scan-test` C4 golden mismatch (JVM, Node) | slow tier at 355bd84b | semantic-register-vm | golden regeneration | P2 |
| JVM `hash-registry-contract-test` sweep flags `ucf/rows.cljc` `decode*` | quick tier | semantic-register-vm | open | P2 |
| Node `store-test` two-worker-threads flaky | quick and slow tiers | store | open | P3 |
| Dart `dao.stream.datagram.dart-test/refusals-and-lifecycle-events` (Linux) | quick tier | dao.stream | open | P3 |
| L-e (closure-origin and store-ownership verification) not implemented | docs 7.5 | linker | L-e | P1 |
| DHT 64 KiB blob cap: `py` cannot travel over DHT | docs 7.5 | DHT seat | chunked transport | P2 |
| CLI refusal for a bad `--dht-trust` value no longer names the flag (`main/parse-trust` could prefix it); shared parser docstring still says "A `--dht-trust` value" | fable AL-4-nits review P3-1 | yin.repl | next yin.repl slice | P3 |
| `(untrust <own>)` says "always trusted" on an `:external` session, where the external admitter decides | fable AL-4-nits review P3-2 | yin.repl | next yin.repl slice | P3 |
