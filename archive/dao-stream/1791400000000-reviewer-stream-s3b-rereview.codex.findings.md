# Track B Slice S3b — Independent Adversarial Re-review

Reviewer: Codex (gpt-6.1-sol). Date: 2026-10-08.
Repository: `/Users/sto/workspace/datomworld-stream-s3a`.
Branch: `stream-crossmachine-s3b`; tracked working-tree diff against master base `802d9ee2`, plus `test/yin/repl/net_fixture.cljc`.

## Verdict: ACCEPT

R1, R2, R3 and R4 are resolved in the inspected working tree. The drain-gap/end teardown concern is also resolved and covered by passing regression tests. No remaining defect requiring revision was found in these remediations. Acceptance applies to this re-review of the requested remediations; the validation limits below are explicit.

## Method

Read the repository architecture and build/test rules, the prior REVISE findings, the Lead Architect specification, the engineer report and remediation appendix, and the relevant implementation, fixtures and tests. Inspected the working-tree changes against `802d9ee2`. Ran the affected JVM, Node and Dart tests, both boundary gates, changed-file formatting and lint, and `git diff --check`. No implementation or test source was changed by this review.

## Findings by revision

### R1 — Bind port versus advertised port: resolved

`yin.repl.serve/serve!` puts both `:bind-port bind-port` and advertised `:port advertised-port` in `:spec`. `remote-channel/serve` passes `(or (:bind-port spec) (:port spec))` to the host bind request. `descriptor-of` destructures the advertised `port` and places it in `:ws/port`; the channel address and REPL URL continue to name that advertised port.

`the-listener-binds-the-bind-port-while-the-url-names-the-advertised-port` asserts listener port 8080 and URL port 9090. `an-ephemeral-bind-with-an-advertised-port-binds-port-zero` asserts that 0 reaches the bind seam while the URL names 9090. Clojure truthiness preserves 0 through the fallback. The existing no-explicit-advertised-port test still refuses an advertised port of 0. The channel-level unequal-port test and existing absent-bind-port coverage also pass.

The design documents the advertised-port validation and unvalidated bind override. The port-0 regression proves propagation to the host seam, not OS ephemeral port allocation: the loopback fixture records port 0 literally. This is sufficient for the remediated composition contract, and the Node adapter tests separately exercise real ephemeral binding.

### R2 — Nil identity in multi-identity dial: resolved

`attach-identities` now terminates on `(empty? remaining)`, independently of an element's value. `valid-target?` requires `(every? some? identities)` as well as a non-empty vector and distinct identities. The validation happens before transport composition or attachment.

`dial-target-refusals` covers `[nil]`, first nil, middle nil and last nil; each returns `:refused` / `::invalid-target`, stays unchanged on step, and creates no connection. `an-identities-dial-attaches-exactly-the-requested-identities` asserts equality between the requested identity set and handle keys for three identities, including an absent final identity, with one shared connection. The two-reflection round-trip and absent-identity tests remain green. The original silent truncation is removed.

The optional prior suggestion to distinguish key presence from nil-valued `:name`/`:identities` remains unimplemented. It is not a remaining R2 blocker: nil identities are refused before connection, and the public docstring describes mutually exclusive meaningful target values. No new acceptance requirement is inferred from that optional suggestion.

### R3 — Boundary gate D10: resolved

Both requested scans print nothing:

```sh
grep -n ":ws/\|dao.stream.ws\|ws-project" src/cljc/yin/repl/serve.cljc src/cljc/yin/repl/connect.cljc
grep -ln ":ws/\|dao.stream.ws\b\|ws-project" test/yin/repl/*.clj* | grep -v "host/"
```

These commands return no-match status, not a test failure. `dht_head_test.cljc` now uses the shared `dao.stream.loopback-net` fixture. Its dial-address assertions are preserved through the fixture's `:dialed` recording, and its tests pass on JVM, Node and Dart.

The Node test moved to `test/yin/repl/host/node_test.cljs`, with namespace `yin.repl.host.node-test`. The focused Node output explicitly shows that namespace executing, and its tests pass. Under the host-seam scope stated in this re-review request, its concrete transport descriptors are correctly located below the boundary. D10's older prose saying only JVM host-adapter tests are exempt should be aligned with this accepted host-directory scope when the architect updates the specification; this is not a code revision blocker.

### R4 — Formatting: resolved

Ran installed cljstyle 0.17.642 directly with `check` over all 15 changed Clojure/ClojureScript source/test files, including the relocated Node test, plus the new `net_fixture.cljc`. Exit **0**, no output. The previously flagged `remote_channel_test.cljc` formatting passes in the current tree. No `fix` command was run by the reviewer. Changed-file clj-kondo also reports **0 errors, 0 warnings**. `git diff --check 802d9ee2` is clean.

### Drain-gap / lifecycle-end teardown: resolved

During `:stopping`, `lifecycle-lost` invokes `release-stop` only when `:stop :released` is absent. That path closes sessions and pending connections, records `:released now`, and asks the host to unbind. An accepted unbind then completes `::unconfirmed`; a refused or thrown unbind preserves `::unbind-failed`. Already-released stops retain their original release time. Terminal `serve-step` calls return the server unchanged, preventing repeated teardown.

The new tests `a-lifecycle-gap-under-a-drain-releases-once`, `a-lifecycle-end-under-a-drain-releases-once`, and `an-unbind-refused-under-a-drain-gap-is-unbind-failed` pass on all three hosts. They assert release time 101 after drain start 100, terminal outcome, closed sessions, one unbind call, and no further unbind on later steps. The gap test also checks exact terminal state stability. Ordinary grace completion, early session departure, no-session release and host-stopped behavior remain covered and passing.

As before, `:released` records local teardown and the unbind request; `::unbind-failed` cannot claim successful external listener release. The remediation preserves that distinction.

## Independent validation

| Check | Firsthand result |
|---|---|
| JVM: remote-channel, connect, serve, serve-connect-wire, dht-head | **92 tests, 667 assertions, 0 failures, 0 errors** |
| Node: remote-channel, connect, serve, dht-head, host.node | **92 tests, 662 assertions, 0 failures, 0 errors** |
| Dart: remote-channel, connect, serve, dht-head | **89 tests passed**, exit 0 |
| Production and test D10 gates | **0 matches** |
| cljstyle, all changed code/test files plus new fixture | **exit 0** |
| clj-kondo, same files | **0 errors, 0 warnings** |
| `git diff --check 802d9ee2` | **exit 0** |

JVM command:

```sh
clojure -M:test -n dao.stream.remote-channel-test -n yin.repl.connect-test -n yin.repl.serve-test -n yin.repl.serve-connect-wire-test -n yin.repl.dht-head-test
```

The first sandboxed attempt could not bind the wire test's loopback socket (`Operation not permitted`); rerunning with approved sandbox escalation produced the clean result above. This was an execution restriction, not an implementation failure.

Node used installed Java 21 and the build-level configuration merge:

```sh
clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^(dao.stream.remote-channel-test|yin.repl.(connect-test|serve-test|dht-head-test|host.node-test))$"}'
```

Unlike the earlier review's ineffective filter, this selected the five intended namespaces. Log: `/tmp/s3b-rereview-node.log`. The compiler reports one namespace/var clash warning in untouched `yin.vm.linker.head.board`; tests pass.

Dart used installed Babashka and Flutter with:

```sh
bb src/dev/cljd_agg.clj --only dao.stream.remote-channel-test,yin.repl.connect-test,yin.repl.serve-test,yin.repl.dht-head-test
```

Log: `/tmp/s3b-rereview-dart.log`. Compiler dynamic warnings do not prevent compilation or the passing run.

## Validation limits

This pass did not rerun the full repository lanes, all process-level main tests, or slow process tests. The engineer's reported full-lane missing `handoff-v2.txt` baseline failure and earlier slow-test success are reported evidence, not newly certified results. The focused runs independently establish that the remediations and their affected portable tests pass on JVM, Node and Dart, including the real-wire JVM test and renamed Node adapter test.

There are no required revisions from this re-review.
