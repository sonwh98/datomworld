# Track B Slice S3b — Independent Adversarial Review

Reviewer: Codex. Date: 2026-10-08.
Repository: `/Users/sto/workspace/datomworld-stream-s3a`.
Branch: `stream-crossmachine-s3b`; reviewed tracked working-tree diff against `802d9ee2`, plus the new `test/yin/repl/net_fixture.cljc`.

## Verdict: REVISE

The migration largely follows the architecture and passes the focused JVM tests, including the real-wire round trip and reattachment. The two production namespaces satisfy the requested transport-token boundary. Nevertheless, there are two reproducible correctness defects, an unresolved explicit test-boundary requirement, and a changed-file formatting failure. Fix these before acceptance. The bind-port defect also exposes an omission in the architectural migration plan; following that plan literally does not preserve the existing serving API.

## Firsthand sources and method

Read `docs/design/datom.world.md`, the S3b architectural specification, the engineer completion report, the implementation and test changes against `802d9ee2`, the new network fixture, and relevant underlying descriptor, projection, attachment, and lifecycle machinery. Consulted the repository build/test guide and remote-channel design. Exercised focused JVM tests and additional adversarial probes. No implementation files were changed by this review.

## Required revisions

### R1 — P2: The migration silently replaces the requested bind port with the advertised port

Locations: `src/cljc/yin/repl/serve.cljc:192–197,232–239`; `src/cljc/dao/stream/remote_channel.cljc` host bind request (`:bind-port (:port spec)`).

`serve!` still accepts both `:bind-port` and `:advertised-port`, but puts only the advertised port into the portable specification. The channel composition then uses that advertised port as the actual listener port. On the base revision, the descriptor used the advertised port and the host bind request separately used `bind-port`.

Independent reproduction using `yin.repl.net-fixture/host`:

```clojure
(let [h (fixture/host)
      s (serve/serve! {:bind-port 8080 :advertised-port 9090
                      :host (:adapter h)})]
  [(:bind-port (first @(:bound h))) (serve/url s)])
;; Actual: [9090 "daostream:ws://127.0.0.1:9090/repl"]
;; Required: listener binds 8080; advertised URL names 9090.
```

This breaks port-forwarding/proxy compositions and can bind an unintended port or fail because that port is occupied. It also makes an explicit advertised port override an absent, invalid, or zero bind port instead of preserving the existing bind semantics. Current tests only separate bind and advertised *hosts*, so they miss the port regression.

Required: preserve bind-port as portable composition data below the boundary, analogous to `:bind-host`, with an explicit contract for its validation and fallback. Keep the descriptor and URL on the advertised port. Add a regression test with unequal ports and tests for any supported zero-port behavior. Update the architectural specification/design as necessary; do not restore direct WebSocket plumbing to `serve`.

### R2 — P2: A nil identity terminates the attachment loop and silently skips identities

Location: `src/cljc/dao/stream/remote_channel.cljc:685–688`.

`valid-target?` accepts a non-empty vector of distinct identities, including nil, but `attach-identities` uses `(nil? id)` as its exhaustion test. Nil is consequently treated as the end of the vector rather than as an input element. The generic descriptor validator checks presence of the identity key, not non-nil identity, and table validation does not prohibit nil table keys. Regardless of whether nil should ultimately be supported, reporting success while dropping requested entries is incorrect.

Independent reproduction at `:now 0`:

| `:identities` | Actual status | Handle keys | Connections created |
|---|---|---|---|
| `[nil]` | `:attached` | none | 0 |
| `[nil "ans"]` | `:attached` | none | 0 |
| `["req" nil "ans"]` | `:attached` | `("req")` | 1 |

For the first two cases there is no channel or probe to expire. Later `dial-step` calls therefore cannot provide the advertised attachment deadline, leaving a false successful dial indefinitely. For the third, the remaining requested reflections never exist.

Required: test sequence exhaustion independently of the identity value. Either attach every identity permitted by the descriptor/table contract, or explicitly refuse unsupported values before connecting. Add first/middle/last nil regression cases and prove a successful identities dial has precisely the requested handle keys. Consider explicit key-presence tests for D2's “both keys present” rule as well; current validation interprets nil-valued target keys as absent.

### R3 — P2 acceptance gate: D10's test boundary remains unsatisfied

The production check over `serve.cljc` and `connect.cljc` returns no `:ws/`, `dao.stream.ws`, or `ws-project` matches. This part passes.

The corresponding test-tree check still finds:

- `test/yin/repl/dht_head_test.cljc:22,93–112`: a direct ws require and descriptor/accept plumbing.
- `test/yin/repl/host_node_test.cljs:58–62`: concrete ws descriptors.

The architect's D10 explicitly says every test under `test/yin/repl/` except JVM host-adapter tests, and the definition of done requires an empty gate. Being unchanged from the base is not itself an exception to a tree-wide migration requirement. `dht_head_test` exercises another channel consumer, but it is still within the specified tree. The Node test is legitimately a host-adapter test, which makes a broader documented host-test exemption reasonable; the current specification does not grant it.

Required: migrate the DHT fixture to the shared transport fixture, or obtain an explicit architectural scope amendment. Document an intentional Node host-adapter exemption in both D10 and the actual check, if desired. Do not claim the current gate passes.

### R4 — P3 acceptance gate: Changed-file cljstyle check fails

Using installed cljstyle 0.17.642 directly, the check exits 2 and reports one incorrectly formatted file: `test/dao/stream/remote_channel_test.cljc`. It identifies indentation at the invalid-bound assertion, the inline `step!` fixture, and formatting in the newly added `read-only` reify. Some flagged formatting predates S3b, but the new reify is introduced by this slice.

Required: format the affected file and rerun the changed-file check. This is mechanical and does not require architectural permission.

## Decision-by-decision assessment

| Decision | Assessment |
|---|---|
| D1 — table surfaces | Correct: non-empty set, only reader/writer, each declared nature checked against the handle. Narrower surfaces and dual-nature entries work; refusal includes identity/surface. Validation precedes binding. |
| D2 — identities dialing | Correct for ordinary non-nil identity vectors: shared channel, immediate deferred-confirmation reflections, handles map, nil single-handle accessor, driver time recorded before probes. R2 prevents accepting the general implementation. |
| D3 — writable append semantics | Correctly documented and kept out of REPL transport code. The five requested test blocks are present. Correlated RPC answers remain evaluation confirmation. The specified blackhole test proves deadline recovery, but does not itself force a session writer to return `full`; a direct writer-refusal probe would strengthen coverage. |
| D4 — draining stop | Normal path conforms: stop initiation, continued answering, 500 ms REPL drain, immediate release with no sessions, early release after departure, ended close code, separate post-release completion grace. The host-stopped-during-drain path is covered. See lifecycle-gap coverage recommendation below. |
| D5 — detach versus close | Correct: detach closes the channel handle while leaving reflections and dial stepping available; full close terminates all reflections and the dial. REPL disconnect uses detach; reattachment disposes the old dial and makes a fresh one. |
| D6 — bind host | Correct for hosts; advertised host remains in the descriptor while bind host reaches the listener. The analogous existing bind-port capability regresses under R1. |
| D7 — diagnostics/accessor | Counter grows independently of the bounded diagnostic tail. Attachment is retained after loss and hidden after full close, as the stated accessor contract permits. |
| D8 — serving interpreter | Correct separation: channel stepping and status/notice derivation, shared serial REPL interpreter, no request advancement while stopping. Bind configuration preservation requires R1. |
| D9 — client and driver time | Portable URL spec, channel dial, value-returning step retained by driver, open/reattach time supplied from the current tick, RPC terminal as sole terminal authority. Focused wire and reconnect tests pass. |
| D10 — transport boundary | Production files pass; broader test-tree gate fails as described in R3. |

No new hidden global state, application callbacks, clock reads, or scheduling were found in the migrated layers. Existing handle operations and host seams remain the sanctioned boundaries. No transport keys were added to RPC request/answer envelopes.

## Engineer deviations 1–9

1. **Additional S3a assertion amendments — sound.** The added production bound and stop bookkeeping require updating shape/count assertions. Selecting the meaningful original fields and separately asserting release time preserves the old behavior checks.
2. **End observed within the drain rather than exactly the next tick — sound.** A previously filed `blocked` result must be consumed before the next ask can read end. A bounded poll sequence reflects the actual protocol and still proves end precedes closure.
3. **Failed synchronous bind remains failed after stop — sound.** The specification makes stop identity for a failed/inert endpoint; `stopped?` correctly indicates no shutdown work is owed. Unbind-failure notice wording follows the specified new vocabulary.
4. **Test renames and added wildcard coverage — sound.** Names now describe owned state; the host test adds meaningful coverage.
5. **Notice wording changes — sound within the specified migration.** Status/outcome-derived notices avoid reaching through the boundary for host internals. Diagnostics remain bounded. No material operator behavior regression was identified in these text changes.
6. **Release before completing a lifecycle gap/end during drain — safe and justified, but under-tested.** Completing immediately without release would leak open sessions/listener obligations. The added teardown addresses that. An independent gap probe reached stopped/unconfirmed and called unbind once. However, there is no dedicated new drain-gap/end regression test, and `:stop :released` stays nil despite teardown. Record release consistently if observability is meant to state actual release, or explicitly document this terminal exception. Add gap and lifecycle-end tests asserting session closure, one unbind, and no repeated teardown on later steps; also test an unbind refusal. A failed unbind cannot establish that the external listener is actually gone.
7. **Production 15000 ms deadline in connect tests — sound.** `connect/open` has no injected bounds surface. Testing the actual production deadline is valid and remains driver-paced without wall-clock waiting.
8. **Departure notices from live-session set differences — sound.** Closed sessions are announced even when terminal unbind failure prevents subsequent reaping; the seen-set update prevents repeats. Physical retained session maps and live-session observations are intentionally different, though summary consumers should understand that distinction.
9. **Leaving gate matches untouched — not conformant as submitted.** The rationale is understandable but cannot waive the explicit acceptance requirement. R3 describes the necessary migration or scope amendment.

The engineer's additional tooling deviation is not evidence of correctness. Missing checks must be completed or explicitly accounted for before landing.

## Independent validation and limits

- Focused JVM command: `clojure -M:test -n dao.stream.remote-channel-test -n yin.repl.connect-test -n yin.repl.serve-test -n yin.repl.serve-connect-wire-test` — **64 tests, 412 assertions, zero failures/errors**.
- Adversarial JVM probes independently reproduce R1 and R2.
- Production token scan — clean; test-tree token scan — R3 matches.
- Changed-file cljstyle — **exit 2**, one file flagged (R4); no formatting mutation performed.
- Changed-file clj-kondo (all changed Clojure source/test files and the new fixture) — **zero errors, zero warnings**.
- Node: first attempt failed before tests because Java 17 could not load a compiler requiring class version 65 (Java 21). Retried with installed Java 21 using `clj -M:cljs -m shadow.cljs.devtools.cli compile test` plus a requested namespace-regexp config merge. The command discovered a broader suite despite the requested filter. It executed `dao.stream.remote-channel-test`, then was interrupted during unrelated `yang.python.antlr.int-conv-test` after several minutes; `yin.repl.connect-test` and `yin.repl.serve-test` had not appeared. Log: `/tmp/s3b-node-review21.log`. **Incomplete, not a Node pass.**
- The missing `test/resources/yin/vm/ucf/handoff-v2.txt` is absent from the base revision's tracked tree too. The engineer's JVM/Dart full-lane fixture failure is plausibly pre-existing, not evidence of an S3b regression. This review does not independently certify the full Dart lane or slow process tests.

## Acceptance after revision

Fix R1 and R2 with regression tests; resolve R3 explicitly; clear R4. Preserve the current transport-free serve/connect boundary. Pin the drain-gap teardown deviation with targeted tests. Then rerun the affected portable namespaces on JVM, Node and Dart, the real-wire test, and the required process/slow checks, distinguishing the independently established missing-fixture baseline failure from new failures. The present passing JVM tests do not override the reproduced defects.
