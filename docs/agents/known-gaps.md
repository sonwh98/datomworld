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
| absent is permitted; a host entry under a pinned name refuses with `:linked nil` | fable L-d review P3-2 / L-d-2 | linker | open | P3 |
| `semantic.scan-test` (then `semantic-register.scan-test`) C4 golden mismatch (JVM, Node) | slow tier at 355bd84b | yin.vm.semantic (the register-VM track) | closed 2026-10-14: the `[:c4 :py]` scanner golden regenerated mechanically after the other seat's linker relocation | P2 |
| JVM `hash-registry-contract-test` sweep flags `ucf/rows.cljc` `decode*` | quick tier | yin.vm.semantic (the register-VM track) | closed 2026-10-14: the address-validation lint in `rows.cljc` repaired | P2 |
| register design doc §2/§2.1 derivation prose still names the deleted resolve/lower-register stages | 8e Architect review P2-2 / 8g brief item 6 | yin.vm.semantic (the register-VM track) | closed in 8g: dated amendment in `yin.vm.debruijn.register.md` §2 (2026-10-15) | P3 |
| native binder refusal collapsed to the projection's `:slot-kind` rule (reduced defence-in-depth coverage) | 8e review P2-3 | yin.vm.semantic (the register-VM track) | open | P2 |
| `semantic/frame.cljc:141` compares a captured frame's free-environment bindings with the task's free-env by host `=`; not NaN-reflexive on JS/Dart (a NaN-valued free binding in a captured/parked/waiting frame would be `:free-env-mismatch` there; same class as the 8f-3 NaN fix) | 8f-3 NaN ruling §5 | yin.vm.semantic (the register-VM track) | open | P3 |
| dead fixture: `yin.vm.malformed-rows/malformed-vectors` (the v3 §7.5 vector rules) has no reader since the old vector validator died | 8g sweep | yin.vm.semantic (the register-VM track) | open (test-side deletion) | P3 |
| what died at phase 8, for the record: `yin.vm.completion` (its agreement facts survive in `semantic/scan_test`), and `yin.vm.ucf`'s v3 canonicalizer and stack-shaped safepoint halves (un-ported: completion in 8f-1, UCF halves in 8f-3; R2/R3; `yin.vm.ucf` is now only `code-address`) | 8f-1 ruling | yin.vm.semantic (the register-VM track) | recorded in 8g (`yin.vm.ucf-revisions.md` I-6, the dependency-completion banner, linker §5.2 note) | P3 |
| Historical citations of `docs/design/yin.vm.semantic.md` with v3 section numbers, now wrong after the rename and outside the nine-line allowance: `src/cljc/yin/vm.cljc:655` (§2), `src/cljc/datomworld/demo/continuation_handoff.cljc:67`, `src/cljs/datomworld/demo/continuation_stream.cljs:10` (§7), `src/cljd/yin/register_bench_cljd.cljd:6` (§8), `src/cljc/yin/vm/docs/jfr_benchmark_analysis.md:5,268` (§8), `test/bench/yin_vm_bench.cljc:3` (§8), `test/yin/repl_test.cljc:37` (§8), `test/yin/vm/semantic/linearize_test.cljc:286` (§2.7); in the renamed design §2 is the machine, §7 is "What A is", §8 is the derivations | 8g Architect review P2-1 | yin.vm.semantic (the register-VM track) | open: prose-only pass, retarget to the history block or the right new section | P3 |
| Historical source prose outside the nine citation-line allowance: the optimisation note mentions the deleted `vm/opcase`; `ucf/rows/checkpoint.cljc` says it replaces the now-deleted `yin.vm.ucf.checkpoint` | 8f-1 review D1 / P3-5 | yin.vm.semantic (the register-VM track) | recorded in 8g: `vm/opcase` died in 8f-3, the old inspector in 8f-1; prose-only follow-up if desired | P3 |
| Node `store-test` two-worker-threads flaky | quick and slow tiers | store | open | P3 |
| Dart `dao.stream.datagram.dart-test/refusals-and-lifecycle-events` (Linux) | quick tier | dao.stream | open | P3 |
| L-e (closure-origin and store-ownership verification) not implemented | docs 7.5 | linker | L-e | P1 |
| DHT 64 KiB blob cap: `py` cannot travel over DHT | docs 7.5 | DHT seat | chunked transport | P2 |
| CLI refusal for a bad `--dht-trust` value no longer names the flag (`main/parse-trust` could prefix it); shared parser docstring still says "A `--dht-trust` value" | fable AL-4-nits review P3-1 | yin.repl | next yin.repl slice | P3 |
| `(untrust <own>)` says "always trusted" on an `:external` session, where the external admitter decides | fable AL-4-nits review P3-2 | yin.repl | next yin.repl slice | P3 |
| `:try?` not carried by UCF pending entries; a lifted try waiter resumes under the `require` contract | fable L-d-1 architect review; `ucf/rows.cljc:1014`, `ucf/handoff.cljc:2036`, `ucf/remote.cljc:368` | UCF | L-d-1b, before I4-a | P1 |
| try joiner's `:link-id` is the install's parent id; drop `:link-id` from install waiters, loss datum carries `:module` | fable L-d-1 review P3-1 | linker | L-d-1b | P3 |
| `publish/requirements` pins only operator `require`; module-level `try-require` yields no pin | fable L-d-1 review P3-3 | linker | closed in L-d-3 | P3 |
| publication-time-unavailable optional imports cannot be represented without concrete segment pins | mob consensus I4 Q1 | yang.python / linker | open | P3 |
