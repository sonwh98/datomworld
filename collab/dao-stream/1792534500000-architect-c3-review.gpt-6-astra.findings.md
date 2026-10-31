# Slice C3 architectural and code review

Date: 2026-10-21
Reviewer: Codex, System Architect & Reviewer
Branch: codec-c3
Reviewed HEAD: 32d1e3801f1f8272f5ea3b251d15b031f6e408aa, plus the 12 uncommitted files listed by git diff.
Authority: /Users/sto/workspace/datomworld/docs/design/dao.stream.codec.md, especially §§4.2, 4.3, 7.1 C3 and 7.3; repository architectural foundations.
Engineer report: /Users/sto/workspace/datomworld/collab/1792532500000-engineer-codec-c3.claude-sonnet-5-5.findings.md.

**Verdict: NOT SIGNED OFF.**

The central default removal is correct, and the requested JVM verification passes. However, a remaining Node test composition still relies on the deleted default, the endpoint does not enforce the specified vector shape, and mandatory host acceptance evidence is incomplete. The literal grep gate also fails. This is a review only; no implementation or test files were changed.

## Findings, in priority order

### R1 — High: Node compositions still consume the removed default

`test/dao/stream/ws/node_test.cljs:335-355` defines zero-argument `server-fixture` by passing `{}` to its map arity. That arity supplies `:codecs` to `ws/make-endpoint` only when the incoming value is truthy. It also passes that same nil to `node/listen!` at lines 377-382.

Seven existing tests invoke the zero-argument fixture (lines 541, 562, 632, 649, 690, 846 and 1002). These now throw at endpoint construction, before the listener or intended test behavior is reached. For example, `a-reflection-round-trips-append-and-next-through-a-real-node-listener` and `a-cbor-client-is-refused-by-a-transit-only-endpoint` both use it.

This is a statically established failing execution path, not a claimed Node test run. It violates §7.1's first review requirement: no default may be removed while a caller still reads it. The omission predates the C3 diff, but C3 activates the regression.

Required change: make the test-owned composition explicitly select `[transit/profile]` for its Transit fixture and pass the same vector to endpoint and listener; retain explicit dual-profile selection. Audit remaining callers and run the Node subsystem lane.

### R2 — Medium: make-endpoint accepts collections other than the required vector

`src/cljc/dao/stream/ws.cljc:549-550` checks only `(seq codecs)` and `(every? codec-profile? codecs)`. Thus a non-empty list or set of valid profiles passes. The accepted collection is retained at line 571; it is not normalized or restricted.

§4.3 specifies an ordered vector, and the review brief explicitly requires an explicit `:codecs` vector. Accepting a set defeats that shape and order guarantee. This permissive gate is inherited, but remains an unmet C3 acceptance requirement.

Required change: require `vector?` along with non-empty and valid profiles. Add focused cases for list/set rejection and vector acceptance.

### R3 — Medium: Dart detects absent codecs after binding rather than at composition time

`src/cljd/dao/stream/ws/dart.cljd:218-219` adds the missing-codecs throw inside `handle-request!`. Public `bind!` at lines 266-286 still calls `HttpServer.bind`, installs the request listener and emits `:bind-succeeded` without checking codecs. A direct codec-less Dart host composition therefore allocates a listener and reports success; the defect surfaces only when a request arrives.

The C3 table explicitly names a throwing `handle-request!`, which the patch implements, but §4.3 also requires absence to be a composition error at composition time. `remote-channel/serve` protects its own path; it does not protect direct host composition.

Required change: validate the required codec configuration before `HttpServer.bind`, retaining request-side defense if desired. Cover the public bind path and the C3-specified request guard without leaving a listener allocated.

### R4 — Medium: mandatory per-host tests and three-lane evidence are missing

The added negative listener test covers JVM only (`test/dao/stream/ws_project_jvm_test.clj:423`). The Node and Dart adapter test files contain no corresponding added absent-codec test. The engineer explicitly reports both missing tests and unexecuted lanes. §7.1 C3 requires one test per host and three lanes green, with before/after test and assertion counts. The report gives only an after JVM test count, with no assertion or baseline accounting.

Required change: add the Node/Dart negative tests, repair R1, run all three subsystem lanes, and record counts and named additions/rewrites. The review's JVM result below does not discharge those gates.

### R5 — Low: the literal grep gate is not clean

Executed exactly `grep -rn "dao.stream.transit" src/`. In addition to permitted Transit implementation and `yin/repl/connect` hits, it returns:

- `src/cljc/dao/jing/stream.cljc:14,60`: subprotocol names in docstrings.
- `src/dev/host_boundary/closure.clj:158,159`: quoted namespace inventory data.

The engineer mentions the docstrings but omits the development inventory hits. These are not runtime requires or codec dispatch, so they do not reintroduce Transit coupling. Nevertheless, the literal §7.1 gate fails; it cannot be reported as passing.

Required resolution: reconcile the gate with these nondependency references explicitly (an approved narrow exclusion or appropriately revised descriptive text/data). Do not remove valid inventory data merely to conceal the hits. No waiver is silently granted by this report.

### R6 — Low: remote-channel documentation still promises the deleted default

`src/cljc/dao/stream/remote_channel.cljc:295-299` says `:codecs` is optional and omission selects Transit. The refusal documentation at lines 313-318 omits `::no-codec`; the `dial` documentation similarly omits its required codec and new refusal.

Required change: document mandatory composition-supplied profiles and `::no-codec` on both serving and dialing paths.

## Objective-by-objective assessment

| Check | Result |
|---|---|
| ws Transit require removed | Pass. No codec implementation require remains in ws. |
| checked-codec validates explicit profile | Pass, ws.cljc:35-40; make-attacher calls it at line 512. |
| One-arity descriptor gates deleted | Pass. Both descriptor predicates require `[x codec]`. |
| make-endpoint requires explicit vector | Partial. Missing/empty codecs fail, valid profiles and distinct names are checked; vector shape is not enforced (R2). |
| No-offer/unrecognized subprotocol refused | Pass. Index lookup at lines 632-637 closes with protocol code 4002 and returns `:ws/unsupported-subprotocol` for both. |
| Four adapters remove Transit/default-codecs/fallback | Pass by source inspection. Browser already had no Transit require/default-codecs; its fallback and now-unused ws require are removed. Other adapters remove their definitions/requires and read the selected codec through the accessor. |
| remote-channel missing codecs | Pass for valid direct WS compositions. serve returns `::no-codec` for nil/empty codecs at lines 327-328; dial returns it for nil codec at lines 826-828. Earlier invalid-target/transport/table checks retain precedence; route dispatch remains independent. |
| WS contract document amended | Pass. Absent name is refused and codec-profile text links to dao.stream.codec.md. |
| Frozen fixtures | Pass on JVM with the acknowledged C3 exception below. Fixture data files are unchanged. |
| Grep gate | Literal fail; semantic dependency removal passes (R5). |
| Required JVM verification | Pass: 502 tests, 3902 assertions, zero failures/errors. |

## Architectural acknowledgement of the nil-offer deviation

**Acknowledged and accepted for C3.** Both frozen `negotiation.edn` files retain the historical `{:offer nil :expect :default-transit}` entry. `test/dao/stream/codec_fixtures_test.cljc:190-197` instead asserts refusal for nil offers.

This is the exact intentional seam behavior change authorized by §4.2 and §7.1 C3. It is not permission to rewrite codec wire fixtures, relax baseline decoder exceptions, or change recognized negotiation outcomes. Host listeners already reject no-offer upgrades; the old frozen row exercises the direct in-process seam rather than an accepted network handshake. Keeping the baseline data intact and documenting the narrow interpretation change is appropriate.

For clearer diagnostics, the test could explicitly assert the historical row's expected tag before applying the C3 override, and label the effective expectation as refusal rather than `:default-transit`. This is a clarity improvement, not a blocker to acknowledging the deviation. Its conversion removes one chosen-codec assertion per profile, so count reconciliation should name that intentional assertion decrease.

## Verification and limits

Executed the requested `bb test:sub:clj dao.stream` using the repository's mise toolchain. Bare `bb` was initially absent from PATH; mise required trust for this worktree, supplied through the invocation-scoped `MISE_TRUSTED_CONFIG_PATHS`. The first sandboxed run encountered nil datagram bind addresses and was interrupted. It is not counted as a C3 failure or a pass.

Reran with approved unrestricted local socket access:

- Command: `MISE_TRUSTED_CONFIG_PATHS=/Users/sto/workspace/datomworld-codec-c3 mise exec -- bb test:sub:clj dao.stream`
- Exit: 0; 35 JVM namespaces; 502 tests; 3902 assertions; 0 failures; 0 errors.
- Runner reports 10 seconds for the JVM lane.
- Runner log: `/tmp/codec-c3-review-clj-unrestricted.log`.
- Detailed test log: `/Users/sto/workspace/datomworld-codec-c3/target/profile/sub-clj.log`.
- The suite prints skipped Dart-peer checks because `build/ws-project-peer` is absent. Those checks are not covered by the green count.
- `git diff --check` passes.
- Node and Dart lanes were not run in this review; neither is reported green. No before-C3 baseline run was performed.

Wire claims, kept separate as required by §7.3:

1. **Contract unchanged on the network:** source inspection supports unchanged profile names, envelopes and close behavior for explicitly composed peers. Direct codec-less composition and nameless seams intentionally change. Full cross-host verification is incomplete.
2. **CBOR bytes unchanged:** the JVM frozen-fixture tests pass, including canonical byte comparisons. No new Node/Dart fixture result is claimed.
3. **Transit interoperable:** JVM frozen decode/exception checks pass and this diff does not alter its codec. No fresh bidirectional old/new interoperability run was performed; those runs are mandated at C2/C4, not newly at C3. JVM fixture success alone is not an encode-side interoperability proof.

Return for sign-off after the Node migration defect and contract gaps are corrected and C3's host tests, three-lane counts and grep resolution are supplied. No need to regenerate frozen wire data.
