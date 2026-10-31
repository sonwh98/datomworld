# Architect review: slice C3 (contract: defaults removed from dao.stream.ws, host adapters, remote-channel)

Reviewer: claude-fable-5-1 (Architect), 2026-10-21 09:40 +07
Worktree: /Users/sto/workspace/datomworld-codec-c3, branch codec-c3, uncommitted on top of 32d1e380
Governing: docs/design/dao.stream.codec.md §7.1 row C3, §7.3
Engineer report: collab/1792532500000-engineer-codec-c3.claude-sonnet-5-5.findings.md

## Verdict: SIGNED OFF WITH CHANGES

The contract step is done as specified: every default named in the C3 row is gone from `ws.cljc`, the four adapters and `remote-channel`, nothing in `src/` outside `transit*.clj*` and `yin/repl/{serve,connect}` requires Transit, and the JVM lane is green with the new refusal tests. Four changes are required before commit (C3-R1..R4); none alters the design, all are confined to this slice's files. Three items go to the known-gaps ledger.

## Checks per §7.1 C3

| Check | Result |
|---|---|
| `ws.cljc` drops `[dao.stream.transit :as transit]` | Yes. The `subprotocol` def went with it (unused after C2). |
| `checked-codec` has no default | Yes: `(codec-profile? x)` or throw; nil throws. |
| one-arity `descriptor?` / `servable-descriptor?` deleted | Yes; the only remaining one-arity `descriptor?` calls in `src/` are `udp.cljc` and `datagram.cljc`'s own local gates, unrelated. |
| `make-endpoint` requires `:codecs` | Yes: `(or codecs [transit/profile])` deleted; the existing non-empty/every-profile guard now throws on nil and `[]`. |
| no-offer / unknown offer refused | Yes: `accept-connection!` reads `(get (:codec-index endpoint) offered)` with no nil branch, so a nameless seam takes the `unsupported-subprotocol` close path. |
| adapters drop Transit require, `default-codecs`, `ws/subprotocol` fallback | Yes on all four. `browser.cljs` also dropped its now-unused `dao.stream.ws` require. `grep -rn 'ws/subprotocol' src/ test/` (excluding the `:ws/subprotocol` key) is empty. |
| `remote-channel` refuses `::no-codec` | Yes, both `serve` (empty `:codecs`) and `dial` (nil `:codec`), ordered after `::no-transport`, before the port check. The `(when (contains? config :codecs) …)` / `(when codec …)` conditional merges are gone. |
| grep gate `dao.stream.transit` over `src/` | Hits beyond the allowed set: `dao/jing/stream.cljc:14,60` (the subprotocol *string* in docstrings, not the namespace) and `src/dev/host_boundary/closure.clj:158` (the H0 closure tool's quoted namespace allowlist, not a require). Both are gate-text false positives; recorded in the ledger, not a defect. |
| `dao.stream.ws.md` amended | Yes: the "absent name" sentence now says refused, and points codec profiles at `dao.stream.codec.md`. No other "Transit default" text remains in that document. |
| kondo on the changed files | 0 errors. One new warning: `remote_channel.cljc:321` unused binding `config` (see C3-R4). The two `codec_fixtures_test` unused-private-var warnings predate C3 (present at HEAD). |

## Required changes

**C3-R1. Dart guard belongs in `bind!`, not `handle-request!`.** `dart.cljd` throws "needs :codecs" inside `handle-request!`, which runs per upgrade in the `.listen` callback *after* `:bind-succeeded` has been deposited. A codec-less Dart composition therefore binds fine and fails on its first connection as a listener error inside a Dart stream callback, which the composer never sees as a throw. JVM `listen!` and Node `listen!` throw synchronously at bind time. Move the `(when-not (seq codecs) (throw …))` to the top of `bind!` (before `io/HttpServer.bind`); the per-request guard may stay or go. The C3 row says "`listen!`/`handle-request!` throw", so this is a letter-vs-intent gap, and the intent is composition-time refusal.

**C3-R2. Per-host throw tests for Node and Dart.** The acceptance says "one test per host"; only `ws_project_jvm_test/a-codec-less-listen-throws` exists. Add one `(is (thrown? …))` for `node/listen!` without `:codecs` in `test/dao/stream/ws/node_test.cljs`, and one for `dart/bind!` in `test/dao/stream/ws/dart_test.cljd` once R1 lands. The engineer's report is explicit that the cljs/cljd guard code is unexecuted; these tests are what executes it.

**C3-R3. Fixture deviation ruling: amend `negotiation.edn`, drop the test's nil-offer special case.** Acknowledged. Ruling: the no-offer entry's `:expect :default-transit` is a frozen record of *behaviour*, not of wire bytes, and C3 is the one slice §7.1 names as changing exactly that behaviour. §7.3's own rule for `exceptions.edn` is that a named behaviour change moves the entry rather than leaving the data contradicting the test. Apply the same here: in both `negotiation.edn` files set the no-offer entry to `:expect :refused` with a comment line `;; C3 (dao.stream.codec.md §7.1): no default profile, a nameless offer is refused`, and in `codec_fixtures_test` restore `(if (= :refused expect) …)` with no `(nil? offer)` override. The fixture must say what the system does; a test that silently overrides a fixture entry is exactly the "buried behaviour change" §7.3 forbids. No other fixture file changes.

**C3-R4. `remote-channel` docstrings and the unused binding.** `serve`'s docstring (`remote_channel.cljc:296`) still says "omitted, `dao.stream.ws`'s default (Transit) applies", and neither `serve`'s nor `dial`'s refusal list names `::no-codec`. Amend both to "required; absent or empty is `::no-codec`". Drop the now-unused `:as config` on `serve` (kondo warning introduced by this slice).

## Known-gaps ledger (not blocking)

- G1. The `grep dao.stream.transit src/` gate as written also matches the subprotocol string and the host-boundary closure allowlist (`closure.clj:158`). The gate's intent is requires; the next doc touch of codec.md should say `grep -rn '\[dao.stream.transit' src/` or equivalent.
- G2. `test/dao/stream/ws/node_test.cljs:406` `make-client` docstring says "(default Transit)"; the code already passes a codec. Cosmetic; fix with R2 since the file is open.
- G3. Dart `handle-request!` would, if the guard stays there after R1, be unreachable-by-construction; either is fine, note it in the docstring.

## Verification

| Lane | Command | Result |
|---|---|---|
| JVM | `bb test:sub:clj dao.stream` | 35 namespaces, 502 tests, 0 failures, 10 s (matches engineer) |
| Node | `bb test:sub:cljs dao.stream` | NODE_RESULT |
| Dart | `bb test:sub:cljd dao.stream` | DART_RESULT |

Test deltas on JVM: +2 in `ws_test` (codec-less attacher/endpoint throw; nameless seam refused), +1 in `ws_project_jvm_test` (codec-less `listen!` throws), 1 rewritten in `remote_channel_test` (`::no-codec` for `serve` absent/empty and `dial` absent), 1 rewritten in `ws_codec_test` (no-offer refused replaces the Transit-default test). Counts "unchanged plus" the refusal tests, as the row requires.

## After the changes

Re-run the three `test:sub` lanes for `dao.stream`; on green, this review stands as sign-off without a second pass, provided the diff beyond the current one is limited to R1..R4 and G2. Anything wider returns for review.
