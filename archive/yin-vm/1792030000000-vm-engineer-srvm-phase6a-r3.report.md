Completed-GMT: 2026-10-11 16:51:10 GMT
Completed-Local: 2026-10-11 23:51:10 Asia/Bangkok


# Slice 6a round 3

Worktree `/Users/sto/workspace/datomworld-srvm-p6a`, branch `srvm-phase6a`, HEAD `bb4a6558`. Only `src/cljc/yin/vm/ucf/rows.cljc` and `test/yin/vm/ucf/rows_test.cljc` changed. `frame.cljc` is untouched. Nothing staged or committed. The completion timestamp uses the actual clock; it is earlier than the brief's Created-GMT marker.

## Changes and regression evidence

The four final named tests were run against the original source restored from the pre-edit backup, with a 256 MiB JVM heap and a 90-second subprocess timeout. Result: **4 tests, 27 assertions, 25 failures, 1 error**, exit 1 (`collab/6a-r3-pre-final-tests.log`). The fixed source was restored byte for byte. The same four tests then passed on final bytes: **4 tests, 27 assertions, 0 failures, 0 errors** (`collab/6a-r3-regressions-final.log`).

| Item | Change and evidence | Named regression, old-source result | Final result | Required mutant on final source |
|---|---|---|---|---|
| 1 MEDIUM | **SOURCE:** default `value-node` classifies guest values as literals and refuses marker-shaped maps regardless of payload validity. Explicit references come from the caller's encoder argument as `[:row node]` / `[:cell id]`. **TEST:** the well-formed `{:yin.k/row (rows/node :prim ['guest/f])}` window probe and keyword-cell variant both refuse; explicit classification still round-trips. Existing portable fixtures now supply their classifier explicitly, including captured frames. | `well-formed-guest-markers-are-refused`: 1 test / 3 assertions, 2 failures | 3 assertions pass in final regression run | `guest`: restore shape-based interpretation in the default classifier; **KILLED**, 1 test / 3 assertions, 2 failures, 0 errors |
| 2 MEDIUM | **SOURCE:** recursive marker refusal in every plain/literal slot during emission and wire decoding, using slot descriptors, including task results, store values, custody, nested collections, and symbol/collection metadata. Structural references and explicit value references are separate. **TEST:** both review maps `{:yin.k/row 1}` / `{:yin.k/cell :c}` are tried on encode; independently rehashed wire probes verify decode without depending on encode accepting the invalid literal. | `every-literal-context-refuses-markers`: 1 test / 20 assertions, 20 failures | 20 assertions pass in final regression run | `literal-both`: remove literal checking from emission and decoding; **KILLED**, 1 test / 20 assertions, 20 failures, 0 errors |
| 3 MEDIUM | **SOURCE:** decoded equality uses recursive tagged representations: literals, sequences, maps, sets, identities and opaque reference rows have disjoint forms. Guest collections are recursively tagged, so a cell cannot collide with a guest vector that spells its internal tag. Vector/list equality remains shared, preserving the existing genuine duplicate refusal. **TEST:** cell `:x` and the distinct literal `[:identity :yin.k/c-1]` coexist in one set; decode and decode/re-emit both succeed. | `cell-and-identity-shaped-literal-are-distinct`: 1 test / 2 assertions, 1 failure, 1 error (`:duplicate-key`) | 2 assertions pass in final regression run; existing duplicate-key tests also pass in G0 | `equality`: return untagged guest literals; **KILLED**, 1 test / 2 assertions, 1 failure, 1 error |
| 4 HIGH | **SOURCE:** add default `:label-bytes 1048576` and preflight intermediate encodings and unsigned byte comparison vectors before CBOR encoding or comparison-vector allocation. This covers initial labels, position encodings, signatures, individualization labels, unordered CE comparisons, class keys, and leaf comparison keys. The preflight counts existing byte payload lengths without copying and includes text/container/metadata overhead. Native byte-array length is used on all hosts. Refusal is status `:yin.k/unsupported`, kind `:resource-limit`, path/reason `[:label-bytes]`, consistent with existing local budget refusals. **TEST:** the equal-body seven-cell directed triangle/square fixture refuses under default budgets; its regression is run at `-J-Xmx256m`. | `intermediate-label-bytes-are-bounded`: 1 test / 2 assertions, 2 failures (caught Throwable yielded no ex-data; its class was not recorded by the helper) | 2 assertions pass; separate final bounded-heap run: 1 test / 2 assertions, 0 failures, 0 errors | `bytes`: remove preflight's size check; **KILLED**, 1 test / 2 assertions, 2 failures, 0 errors; 256 MiB heap and 90-second timeout |
| 5 LOW | No source change. Install-child emission still has no caller `budget` in scope: `emit-node` takes `[n numbering rows]`. Passing it requires threading through emission helpers/callers, rather than the conditional one-token change authorized by this item. | Not changed; no new test or mutant claimed | Existing child tests pass in final suites | Not applicable |

**Whether the seven-cell fixture should instead canonicalize remains an open Architect question. A hashed-label formulation would change the numbering; this round does not decide it.** The fix preserves the label formulation and accepted-fixture bytes, while refusing this resource-intensive fixture.

All three pinned roots remain unchanged and pass `canonical-roots-are-pinned` on JVM and Node:

- fixture: `:segment/blake3-be82d50b14804bb7a69103631b9c45759b49b67dbb3cebe75a1259fed6b4a937`
- worked frame: `:segment/blake3-c4a7c7e6de3b44400efec5930bc3d85d86b29153b4b2d548acba630bfbc3da7d`
- three-cell loop/two-cycle: `:segment/blake3-8899f95d330a174ee098e10f16ecf2d017d5aae67d4f50d8369222342e6d36e9`

The ten-cell census case also still succeeds and round-trips.

## Mutations and restoration

Before any edit, source bytes were copied to `collab/6a-r3-backups/pre/rows.cljc` and equality verified. Before mutations, fixed source bytes were copied to `collab/6a-r3-backups/rows.cljc` and equality verified. Later source corrections each had an already verified matching backup and additional snapshots (`rows-before-byte-length.cljc`, `rows-before-symbol-meta.cljc`); the final mutation backup was refreshed and verified before reapplying all four required mutants. Each mutation starts by checking source equality, restores from the verified backup in `finally`, and verifies exact byte equality after restoration. The old-source regression run uses the same restoration discipline.

Final-source mutation commands are literal, foreground subprocesses, each with `-J-Xmx256m` and a 90-second timeout:

```sh
clojure -J-Xmx256m -M:test -n yin.vm.ucf.rows-test -v yin.vm.ucf.rows-test/well-formed-guest-markers-are-refused
clojure -J-Xmx256m -M:test -n yin.vm.ucf.rows-test -v yin.vm.ucf.rows-test/every-literal-context-refuses-markers
clojure -J-Xmx256m -M:test -n yin.vm.ucf.rows-test -v yin.vm.ucf.rows-test/cell-and-identity-shaped-literal-are-distinct
clojure -J-Xmx256m -M:test -n yin.vm.ucf.rows-test -v yin.vm.ucf.rows-test/intermediate-label-bytes-are-bounded
```

Harness and final records: `collab/6a-r3-mutants.py`, `collab/6a-r3-mutants.json`; logs `6a-r3-guest.log`, `6a-r3-literal-both.log`, `6a-r3-equality.log`, `6a-r3-bytes.log`.

Additional mutation attempts, stated plainly:

- Dropping only emission-side literal checking was killed: 1 test / 16 assertions, 8 failures (`6a-r3-literal-encode.log`, before metadata assertions were added).
- Dropping only decoding-side checking **SURVIVED**: 1 test / 16 assertions, 0 failures/errors (`6a-r3-literal-decode.log`). Canonical re-emission applies the emission-side check, so the same refusal still occurs. The combined removal above is the required killed literal-context mutant. No claim that the decode-only mutant was killed.
- Initial new tests had two faulty decode probes that forged `[:lit nil]` instead of the intended marker. Those probes were corrected before the final old-source regression and final mutation campaign. Earlier scratch logs are not final verification.
- First Node run exposed my use of `count` on `Uint8Array`: 200 tests / 4773 assertions, 4 failures, 16 errors (`6a-r3-node.log`; build process exited 0 despite test failures). Replaced it with native array length and reran mutations and lanes; final results below are green.

Final source SHA-256 and backup SHA-256 both equal `eaad67ef85c01bcb151273fea8b053571a790fb15aac63938b24bc58d5995355`. Pre-edit source SHA-256 is `f5f5ee8525f91075ee4abbf68bf760ec35d2df20def6371816b22a28db7fd637`. Recorded in `collab/6a-r3-backups/verification.json`. `git diff --check` is clean; staged diff is empty.

## Final lanes (foreground; no slow tests)

| Lane | Exact counts / result | Log |
|---|---|---|
| Exact requested JVM frame + rows command | **30 tests, 549 assertions, 0 failures, 0 errors**, exit 0 | `collab/6a-r3-g0-final.log` |
| JVM 14 namespaces, round-1 set | **195 tests, 4769 assertions, 0 failures, 0 errors**, exit 0 | `collab/6a-r3-jvm-final.log` |
| JVM parity dependency | **5 tests, 60 assertions, 0 failures, 0 errors**, exit 0; unaffected by subsequent byte-length/metadata corrections | `collab/6a-r3-jvm-parity.log` |
| Node, same selection as round 2, includes frame-test and rows-test | **200 tests, 4828 assertions, 0 failures, 0 errors**; build exit 0, 0 warnings | `collab/6a-r3-node-final.log` |
| Final four regressions, 256 MiB heap | **4 tests, 27 assertions, 0 failures, 0 errors**, exit 0 | `collab/6a-r3-regressions-final.log` |
| Final seven-cell default-budget probe, 256 MiB heap | **1 test, 2 assertions, 0 failures, 0 errors**, exit 0 | `collab/6a-r3-byte-bound-final.log` |
| kondo, both changed files | **0 errors, 0 warnings**, exit 0 | `collab/6a-r3-kondo-final.log` |
| cljstyle, both changed files | Clean, exit 0; mise emitted a cache-write warning outside the workspace | `collab/6a-r3-cljstyle-final.log` |
| Dart | **NOT RUN by me**; orchestrator runs this lane | No Dart result claimed |

Commands:

```sh
clojure -M:test -n yin.vm.semantic-register.frame-test -n yin.vm.ucf.rows-test
clojure -M:test -n yin.vm.semantic-register.frame-test -n yin.vm.ucf.rows-test -n yin.vm.semantic-register.code-test -n yin.vm.semantic-register.analysis-test -n yin.vm.semantic-register.linearize-test -n yin.vm.semantic-register.vm-test -n yin.vm.semantic-register.effects-test -n yin.vm.semantic-register.parity-test -n yin.vm.semantic-register.link-test -n yin.vm.semantic-register.load-paths-test -n yin.vm.semantic-register.walker-trace-test -n yin.vm.ucf-test -n yin.vm.ucf.handoff-test -n yin.vm.ucf.safepoint-harness-test
clojure -M:test -n yin.vm.parity-test
clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^(yin\\.vm\\.semantic-register\\..*-test|yin\\.vm\\.ucf\\.(rows|handoff|safepoint-harness)-test|yin\\.vm\\.ucf-test)$"}'
clj -M:kondo --lint src/cljc/yin/vm/ucf/rows.cljc test/yin/vm/ucf/rows_test.cljc
mise exec -- cljstyle check src/cljc/yin/vm/ucf/rows.cljc test/yin/vm/ucf/rows_test.cljc
```

The bounded regression commands use the four literal named tests listed in the mutation section; the final regression run supplies all four `-v` arguments in one command. All final source and test bytes were stable during the final G0, 14-namespace JVM, Node, bounded regression, kondo and cljstyle runs.

ClojureDart inspection: searched every `assoc` in both touched files. All vector associations are single-key; remaining multi-key associations operate on established maps. No multi-key `assoc` on a vector or nil was introduced or found in these files. This inspection does not substitute for the orchestrator's Dart run.
