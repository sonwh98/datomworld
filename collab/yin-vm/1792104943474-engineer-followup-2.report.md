Completed-GMT: 2026-10-15 22:55:43 GMT
Completed-Local: 2026-10-16 05:55:43 +0700
Coding-Agent: codex
Session-ID: 01a141ba-e323-7d22-a10b-f410daef1c5e

# Engineer follow-up-1, round 2 (uncommitted)

Worktree: `/Users/sto/workspace/datomworld-srvm-followup`; branch `srvm-followup-1`; base `9ab9e792`. Read the round-2 prompt, the COMPLETE Architect review, master architecture and build/test guide. Preserved the round-1 work; no staging, commits, pushes, or collab commits.

## Required seven steps

1. `dao.jing.cbor/collapse?` is public, with its original docstring and implementation. This visibility change is the ONLY cbor diff. No encoding, descriptor, digest or pinned fixture changes.
2. `decode*` retains the canonical-byte duplicate check and its `:duplicate-key` refusal. Its second check now calls `cbor/collapse?` and refuses `:equality-collapse` at `[id]`.
3. `check-abstract!` calls `cbor/collapse?` on decoded keys and refuses `:equality-collapse` at the abstract path. Literal leaves are back to `[:literal x]`. Updated the existing expanded vector/list-key assertion to the new rule name.
4. The lower builds each map/set and checks the resulting count against its member count. A loss raises `:yin.k/undecodable`, kind `:host-collapse`, at `[row-id]` using the existing transport refusal vocabulary. The failure branch re-emits that decoded row (unchanged numbering) solely to recover its content address; it does not attach or execute anything. Added a JavaScript-only test for slash-crossed keyword fields, both map/set; JVM/Dart print explicit skips because their identifiers remain distinct.
5. Added the `:equality-collapse` map fixture (keys `1` and `(cbor/float64 1.0)`) in `malformed_rows.cljc`, with exact status/kind/row-id path and a consuming conformance test. The existing `malformed-row-sets` collection is the Universal AST grammar, consumed by VM/linearize/observer tests, not UCF; the new entry therefore lives in a separate `malformed-ucf-row-sets` collection in the requested file. Existing AST fixtures and their readers are unchanged.
6. Replaced `collection-row-keys-use-content-equality` with `collection-row-keys-refuse-equality-collapse`. Both signed-zero and integer/float pairs, each as map/set keys, must refuse at their row id on every host. Preserved `float64-from-bits` and explicit `float64` construction for JS. The JVM literal twin asserts `::cbor/refusal :equality-collapse`; JS/Dart print the requested skip. The replacement test has 13 JVM assertions; the new published-fixture test has 1. Low-level `emit`/`emit-numbered` still construct malformed carriers for these tests; literal emission goes through cbor. No additional validation was added to those construction helpers beyond the Architect's specified sites.
7. Reopened the round-1 decode-key closure and replaced it with the Architect's exact round-2 closure wording at ledger row 25. Closed binder row 21 with the exact ruling (i) wording. No native format or validator changes. Every other ledger row remains as round 1 left it.

## Parts 2 and 3

Preserved both successor tests, the dead `malformed-vectors` deletion, and parity alias cleanup. Ran `cljstyle fix` on ALL 17 changed Clojure-family files. It fixed the parity continuation columns and one pre-existing continuation indentation in `test/yin/repl_test.cljc`. Reconstructed round-1 parity text from HEAD plus its alias/refer changes and checked that its non-whitespace text equals the final file; assertions and expected values are unchanged.

Applied every P3 fix in the review: UCF §7.11 clause 10 now cites the 2026-10-14 14:30 +07 handoff and 2026-10-16 19:30 +07 landing; linker missing-tree prose now says the carrier refuses, identifying lift's source-index and lower's dependency check; `linearize_test.cljc` points to `yin.vm.semantic.derivations.md` §6.3, hand-derived goldens row 6. Kept the quoted historical `:tree-unavailable` amendment untouched.

## Validation

- Final required focused lane plus `-r '^dao\.jing\..*'`: **339 tests / 12,972 assertions / 0 failures / 0 errors**, exit 0. This includes every `dao.jing` test namespace (including conformance) discovered by the requested regex.
- Full unrestricted JVM quick tier, exact command `clojure -M:test -e :slow`: **4021 tests / 249,864 assertions / 0 failures / 0 errors**, exit 0.
- Final restored-source targeted run: **4 tests / 28 assertions / 0 failures / 0 errors**, exit 0.
- Kondo, all **17** changed Clojure-family files: JSON findings `[]`, **0 errors / 0 warnings / 0 infos**.
- `cljstyle fix` on changed files completed; final `cljstyle check` and `git diff --check` pass.
- Logs: `/tmp/followup2-focused-final.log`, `/tmp/followup2-quick.log`, `/tmp/followup2-restored-final.log`, `/tmp/followup2-kondo-restored.json`; mutation logs under `/tmp/followup2-mutant-*.log`.

Round-1 implementation against the replacement tests: red, 40 tests / 698 assertions / 11 failures / 0 errors. One of those failures was an initial test mistake using `:rule` rather than cbor's `::refusal`; corrected before green runs. An attempted primitive-alias lower probe was rejected by the receiver's existing `:ambiguous-primitive` invariant; removed it and used the real JS identifier case instead. No invalid receiver configuration remains.

The first expanded focused run in the sandbox had 338 tests / 12,971 assertions / 0 failures / 1 error: the loopback UDP socket test could not bind and its descriptor had a nil port. The unrestricted repeat passed (338 / 12,972 / 0 / 0). Final runs below include the added lower-guard test and run unrestricted as requested. No Node/Dart execution is claimed: those lanes and discovery evidence remain assigned to the orchestrator, one lane at a time, before merge. The round-2 diff still requires the Architect's reread before its commit.

## Mutation proof

All mutation edits were restored in `finally` blocks; final source is the same source tested by the full quick tier.

| Mutation | JVM executed result | Hosts where the tests would kill it |
|---|---|---|
| Revert BOTH wire and abstract checks to the original host `distinct` / `:duplicate-key` policy | 1 test / 13 assertions / **6 failures**, 0 errors | JVM, Node, Dart, by expected rule name. JVM admits integer/float rows and misnames signed-zero refusal; JS Float64 carriers keep the pairs host-distinct, so the mutant admits them and yields no `:equality-collapse`; Dart merges the numeric pairs and reports `:duplicate-key`. Node/Dart are source-based predictions, not runs. |
| Revert only wire check to host `distinct` / `:duplicate-key` | 2 tests / 14 assertions / **5 failures**, 0 errors | All three: JVM/Dart wrong rule and/or path; JS carriers can pass host distinct, but the remaining abstract check refuses at `[15]` rather than `[row-id]`, killing the exact-path assertion. |
| Revert only abstract check to host `distinct` / `:duplicate-key` | 1 test / 14 assertions / **1 failure**, 0 errors | All three: expanded vector/list-equivalent keys must name `:equality-collapse`, not `:duplicate-key`. |
| Remove lower count guard | Not executed | JavaScript: the new slash-crossed-identifier lower test would lose a member and fail. JVM/Dart explicitly skip this host-specific case; no claim of a kill there. |

The combined mutation is the report's full host-distinct regression. The wire-only mutation is distinguished because, with the corrected abstract check still present, JS can fail by path rather than rule name.

The already accepted Part-2 mutants were not rerun in this round. Round-1 report records JVM deaths for dropping the restored parked map and routing an FFI response without comparing ids. Both tests are portable and are expected to kill those mutants on JVM/Node/Dart; only the earlier JVM execution is evidence here.

## File-to-commit mapping (nothing staged)

Logical code commit A, Part 1(a):
- `src/cljc/dao/jing/cbor.cljc`
- `src/cljc/yin/vm/ucf/rows.cljc`
- `test/yin/vm/ucf/rows_test.cljc`
- `test/yin/vm/malformed_rows.cljc`: new cbor/UCF requires and `malformed-ucf-row-sets` addition only.
- `docs/agents/known-gaps.md`: row 25 only.

Remaining work B can be split into the Architect's separate Part-2 test and Part-3 documentation commits:
- Part 2: `test/yin/vm/semantic/safepoint_harness_test.cljc`, `test/yin/vm/semantic/parity_test.cljc`; `test/yin/vm/malformed_rows.cljc` dead `malformed-vectors` deletion and namespace-docstring update only.
- Part 3: the remaining `docs/agents/known-gaps.md` hunks (including binder row 21); `docs/design/yin.vm.linker.dht.md`, `docs/design/yin.vm.linker.md`, `docs/design/yin.vm.universal-continuation-format.md`; `src/cljc/datomworld/demo/continuation_handoff.cljc`, `src/cljc/yin/vm.cljc`, `src/cljc/yin/vm/docs/bytecode-vm-optimization.md`, `src/cljc/yin/vm/docs/jfr_benchmark_analysis.md`, `src/cljc/yin/vm/linker.cljc`, `src/cljc/yin/vm/semantic/layout.cljc`, `src/cljc/yin/vm/semantic/scan.cljc`, `src/cljc/yin/vm/ucf/rows/checkpoint.cljc`, `src/cljd/yin/register_bench_cljd.cljd`, `src/cljs/datomworld/demo/continuation_stream.cljs`, `test/bench/yin_vm_bench.cljc`, `test/yin/repl_test.cljc`, `test/yin/vm/semantic/linearize_test.cljc`.

The two shared files (`malformed_rows.cljc`, ledger) need hunk separation when staging. This report and all other collab artifacts are excluded from commits. No staging was performed to demonstrate the split.

Final tracked diff: 23 files. No `src/yang`, `test/yang`, scanner goldens, existing fixtures, pinned descriptors, or conformance digest changed. The only cbor change is public visibility. No index entries, commits, or pushes. All pre-existing collab inputs/logs were left alone; this report is the sole added deliverable.

Printed quick-tier skips (verbatim, where present):
```
SKIP jvm coll/set-signed-zero-collapse: host constructor merged the members (N/A; cbor-v1.errata.md E1)
SKIP jvm coll/set-decimal-scale-collapse: host constructor merged the members (N/A; cbor-v1.errata.md E1)
SKIP jvm coll/set-vector-list-collapse: host constructor merged the members (N/A; cbor-v1.errata.md E1)
SKIP jvm coll/map-integer-width-duplicate: host constructor merged the members (N/A; cbor-v1.errata.md E1)
SKIP jvm num/js-unsafe-integer: README Obligations: applies to Node only; N/A on JVM and Dart
;; SKIPPED a-dart-dialer-reads-a-jvm-server: build/ws-project-peer is absent -- compile it first: clojure -M:clojuredart:cljd compile dao.stream.ws-project-peer && dart compile exe lib/cljd-out/dao/stream/ws-project-peer.dart -o build/ws-project-peer
;; SKIPPED a-dart-client-attaches-to-this-jvm-server: build/yin-repl-peer is absent — run bb build:yin-repl-peer (or bb test) first
SKIP host-collapse: slash-crossed identifiers remain distinct on JVM
```

READY
