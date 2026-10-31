# Slice C3 re-review after fixes R1..R4

Date: 2026-10-21
Reviewer: Claude Fable 5.1, System Architect & Reviewer
Branch: codec-c3, worktree /Users/sto/workspace/datomworld-codec-c3
Reviewed HEAD: 32d1e3801f1f8272f5ea3b251d15b031f6e408aa plus the 14 uncommitted files listed by `git status` (two more than the first review: `dart_test.cljd` and `ws_test.cljc` grew).
Authority: docs/design/dao.stream.codec.md §§4.2, 4.3, 7.1 C3, 7.3.
Previous review: collab/1792534500000-architect-c3-review.gpt-6-astra.findings.md
Engineer fix report: collab/1792536200000-engineer-codec-c3-fixes.claude-sonnet-5-5.findings.md

**Verdict: SIGNED OFF WITH CHANGES.**

R1..R4 are fixed as reported and the fixes hold on all three lanes, which this review ran itself (the engineer had not run Dart). Per-lane before/after counts were obtained from a detached HEAD worktree and reconcile exactly to the named additions. Two low findings from the first review (R5 grep gate, R6 remote-channel docstrings) were outside the fix brief and remain open; both are documentation-level and are the "changes" of this verdict. Neither alters wire behaviour, and neither requires a further review round: the orchestrator may land C3 once R6 is applied and the R5 ruling below is recorded in the slice report.

## Fix verification

| Finding | Status | Evidence |
|---|---|---|
| R1 Node fixture consumed the removed default | **Fixed** | `node_test.cljs:336-356`: `server-fixture` binds `codecs (or codecs [transit/profile])` and passes it unconditionally to `make-endpoint` and, via the same binding, to `node/listen!`. `make-client` (`:404-417`) passes `:codec (or codec transit/profile)`. The seven zero-argument fixture callers now reach their intended behaviour; the Node lane proves it (506 pass). The default now lives in the test-owned composition, which is exactly where §4.3 puts it. |
| R2 `make-endpoint` accepted non-vector collections | **Fixed** | `ws.cljc:549-550`: `(and (vector? codecs) (seq codecs) (every? codec-profile? codecs))`. New test `make-endpoint-requires-a-vector-of-codec-profiles` (`ws_test.cljc:620-643`) accepts a vector and rejects a list and a set; it is `.cljc`, so it ran on all three lanes. |
| R3 Dart detected absent codecs only on request | **Fixed** | `dart.cljd:266-270`: `bind!` destructures `codecs` and throws on anything but a non-empty vector of valid profiles **before** `HttpServer.bind`, so no listener is allocated and `:deposit!` is never called. The request-side guard in `handle-request!` (`:218-219`) is retained as defence, matching the C3 table's named `handle-request!` throw. Dart's gate is stricter than JVM/Node `listen!` (which check only `(seq codecs)`); see N1. |
| R4 Node/Dart negative tests, three lanes, counts | **Fixed** | `a-codec-less-endpoint-or-listener-throws` (`node_test.cljs:1031-1046`) covers codec-less `make-endpoint` and codec-less `node/listen!`; `a-codec-less-bind-throws-before-allocating-a-listener` (`dart_test.cljd:119-126`) covers nil, `[]` and a list, and asserts no deposit. With `a-codec-less-listen-throws` (JVM) the C3 row's "one test per host" is met. Lanes and counts below. |

## Verification run

All commands run from the worktree under `MISE_TRUSTED_CONFIG_PATHS=<worktree> mise exec -- …`. Baselines were run on `HEAD` (32d1e380) in a detached worktree at /tmp/c3-baseline-wt with `node_modules` symlinked; it is removed after this report.

| Lane | Before (HEAD) | After (diff) | Δ tests | Δ assertions | Status |
|---|---|---|---|---|---|
| clj  | 499 tests / 3898 assertions | 503 / 3905 | +4 | +7 | pass, 0 failures |
| cljs | 502 tests / 4286 assertions | 506 / 4294 | +4 | +8 | pass, 0 failures |
| cljd | 431 tests | 435 tests ("All tests passed!") | +4 (431 → 435 = 3 shared `ws_test.cljc` tests + 1 `dart_test.cljd` test ✓) | n/a (Dart runner reports tests only) | pass, 0 failures |

Reconciliation of the deltas, per §7.1 "counts unchanged plus the named additions":

- Added on every lane (`ws_test.cljc`): `a-codec-less-attacher-or-endpoint-throws` (3 assertions), `a-seam-without-a-name-is-refused` (2), `make-endpoint-requires-a-vector-of-codec-profiles` (3).
- Added per host: `a-codec-less-listen-throws` (`ws_project_jvm_test.clj`, 1 assertion); `a-codec-less-endpoint-or-listener-throws` (`node_test.cljs`, 2); `a-codec-less-bind-throws-before-allocating-a-listener` (`dart_test.cljd`, 4).
- Intentional decrease: `codec_fixtures_test.cljc:190-197` drops the one chosen-codec assertion per profile for the frozen nil-offer row (−2 on every lane), the deviation the first review acknowledged and accepted.
- clj: 9 − 2 = +7 ✓. cljs: 10 − 2 = +8 ✓. Test counts +4 on both ✓.

Logs: /tmp/c3-rereview-{clj,cljs,cljd}.log (runner), target/profile/sub-{clj,cljs,cljd}.log (detail), /tmp/c3-base-{clj,cljs,cljd}.log (baselines). `git diff --check` is clean. The Dart compile step lists `dao.stream.ws.dart-test` and `dao.stream.ws-test` among compiled namespaces (sub-cljd.log:64-67); the Dart reporter overwrites per-test progress lines so individual ws test names are not grep-able in the log, and the +4 delta is the evidence that the new tests executed. The JVM suite still prints skipped Dart-peer checks because `build/ws-project-peer` is absent; unchanged from the first review and outside C3.

## Open findings carried from the first review

### R5 — Low: literal grep gate, Architect ruling

`grep -rn "dao.stream.transit" src/` still hits `dao/jing/stream.cljc:14,60` (docstring mentions of the subprotocol string `dao.stream.transit-json`) and `src/dev/host_boundary/closure.clj:158-159` (quoted namespace inventory for the H0 closure tool). Neither is a require or a dispatch; the dependency-shaped grep `grep -rnE "dao\.stream\.transit([^-]|$)" src/` hits only `transit*.clj*`, `yin/repl/connect.cljc:41` and the closure inventory.

Ruling: the §7.1 gate's purpose is "no consumer depends on Transit"; it is met. The slice report must state the gate as **passed under the dependency reading** and list the two non-dependency hit sites verbatim, so no reader can mistake a literal pass. Do not edit `dao.jing.stream`'s docstrings or the closure inventory to make the literal grep clean: the subprotocol string is a wire fact Jing legitimately documents, and the inventory is H0-frozen data. I recommend the design doc's C3 row gain a one-line footnote naming the dependency reading; that is a docs edit for the orchestrator, not a C3 blocker. (`yin/repl/serve` no longer hits at all, which is consistent with its C2 move to CBOR.)

### R6 — Low: `remote-channel` docstrings still promise the deleted default — **required change**

`remote_channel.cljc:294-296` says the optional `:codecs` falls back to "`dao.stream.ws`'s default (Transit)"; the refusal list at `:313-318` omits `::no-codec`; `dial`'s docstring (`:781-808`) does not name its required `:codec` or the new `::no-codec` refusal. The code is right (`:327-328`, `:826-828`); the prose contradicts it and §4.3. Fix the three sentences. No test or behaviour change; no re-review needed, a diff glance by the orchestrator suffices.

## New observations (non-blocking)

- **N1 Host gate asymmetry.** Dart `bind!` requires `(vector? …)` plus `codec-profile?`; JVM `listen!` (`jvm.clj:416`) and Node `listen!` (`node.cljs:242`) require only `(seq codecs)`. All three sit behind `make-endpoint`, which already enforces the vector in the `remote-channel` path, so no composition reaches a listener with a bad table. Acceptable for C3; if a later slice tightens the host gates, make them identical and test them identically.
- **N2 Fixture-test clarity.** The first review's optional suggestion (assert the frozen row's historical `:default-transit` tag before applying the C3 refusal override) was not taken. The comment at `codec_fixtures_test.cljc:190-191` explains the override; acceptable.
- **N3 Dart assertion counts.** The Dart runner reports tests only, so §7.1's "assertions per lane" cannot be recorded for cljd by anyone; the slice report should say so rather than omit the lane.

## Wire claims (§7.3, kept separate)

1. Contract unchanged on the network for explicitly composed peers: supported by source inspection and by all three lanes' frozen fixtures passing. Codec-less composition and nameless seams change intentionally (accepted in the first review).
2. CBOR bytes unchanged: frozen canonical byte comparisons pass on all three lanes.
3. Transit interoperable: frozen decode/exception checks pass on all three lanes; Transit's codec is untouched by this diff. No fresh bidirectional old/new peer run; that is mandated at C2/C4, not C3.

## Conditions of sign-off

1. Apply R6 (three docstring sentences in `remote_channel.cljc`).
2. Record in the slice report: the per-lane table above, the named additions and the −2 fixture assertions, the R5 dependency-reading grep with its two non-dependency hit sites, and that Dart reports no assertion count.
3. Commit per format.md as `feat(dao.stream): C3 …`; collab/ stays uncommitted.

Nothing in `src/` or `test/` was changed by this review.
