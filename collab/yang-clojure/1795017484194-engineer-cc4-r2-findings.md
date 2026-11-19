Created-GMT: 2026-11-18 15:58:04 GMT
Created-Local: 2026-11-18 22:58:04 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a1ea51-a87d-7062-ae12-e6c1c169b48c
Role: VM Engineer (compiler provenance)
Implementers:
- Model: gpt-6.1-sol | Status: stopped on compiler divergence | Rationale: same-session r2 continuation

# cc4 r2 — STOPPED on reproduced Dart replay divergence

Role: VM Engineer (compiler provenance)
Worktree: `/Users/sto/workspace/datomworld-cc4`
Branch/base: `cc4-fourhost-equality` / `2687ab0cf03ebaa232be49a2333347d12a71d9a3`.
No commits. No compiler/source implementation changed. The r1 findings remain
unchanged. This report and evidence are written in the authorized worktree;
the orchestrator can mirror them to its checkout.

## Verdict and required next round

**FAIL: four-host replay equality.** Dart produces different canonical code
for the argument-vector and lower-unit cohort members; C1 keys and instruction
provenance therefore also differ. The focused failing test reproduces this.
Per the brief's binding STOP rule, cc4 does not fix the compiler, re-pin Dart,
normalize generated code, or continue budget/acceptance execution after this
failure. A compiler determinism repair needs a separately authorized round.
The acceptance bundle remains blocked. D1 is dissolved per the owner's dispatch;
no durable-correspondence verification or D1 consumption is claimed.

A second, independent blocker is J2's inherited manifest: four existing test
namespaces are unlisted. The cc4 callsite inclusion is implemented, but the
lane refuses before launching a Jolt child. This is a failed required gate,
not an accepted reader limitation or a green skip.

## Concrete compiler defect and diverging artifacts

All eight inputs are the SAME `.cljc` source/form literals and options. The
source fixtures include float `1.5`, `^:replay` metadata, destructuring-generated
temporaries, repeated `(identity a)` subtrees, a macro constructing a twin call,
wrapper/library/prelude surfaces, and separate supplied admission tokens.
No host-specific source conditional occurs in the cohort itself.

| Entry | Application occurrences | JVM and Node canonical root | Dart canonical root |
|---|---:|---|---|
| argument-vector | 263 on both | `:segment/blake3-a0f9c217122ce675d1b3cd5f899740d604ec35f801eac9a4960153cbcd91d699` | `:segment/blake3-02eac587af541bc180db4109b3accd5e3981a92eb5d1d00e63d6f12d74b484b0` |
| lower-unit | 256 on both | `:segment/blake3-44c06492b31348905205b1e1130c27aa1c769790010291ca223d3ab61bfa2775` | `:segment/blake3-9fe835e99e3afc3c2dc7639e7f26d1bcb8ab08445ded6b08aa8d3c78d01546fa` |

For argument-vector, the JVM C1 digest is
`82385d11b83bd1a6bd540cc80011ec88f23cf2e9a959fecfd604eff029a1ba2a`, Dart is
`a7f9b710743fa0572fc24817d41abfdab16f14ddeef266d31e783fc9dcc59c69`.
For lower-unit, JVM is
`654c6a31726250f58550db88ce905fda869f3040b350e298da0aa73995b581ed`, Dart is
`73663cae54144b2ea36899e0f134fda95004a66e47812f44a517309a21970514`.
Full key-vector and instruction-provenance digests, expected/actual, are retained
in `test/resources/yang/clojure/cc4-replay-dart-divergences.edn`.

Read-only comparison of the saved row maps isolates a bottommost difference:
`assoc` constructing the generated arity error map. Its arguments are:

- JVM/Node: `{} :yin.vm.data/reason :arity :yin.k/argc (data/count dsg__av) :yin.k/fn 'replay`
- Dart: `{} :yin.vm.data/reason :arity :yin.k/fn 'replay :yin.k/argc (data/count dsg__av)`

The common operator row is `:variable assoc`. All seven child addresses exist
unchanged on both hosts; only the order of the final two key/value pairs differs.
The resulting application addresses are respectively
`:segment/blake3-8598820bee9b622d827c7879729f60dad19244f9f9869ee693c1eaca58c1bb94`
and
`:segment/blake3-9a8f3a641f0e49b7381eccc74a1373b0d6bf97e954964587a449fcc2ee177e9b`.
Argument-vector has 494 unique rows on each host, 32 exclusive rows on each;
lower-unit has 499 each, 45 exclusive rows on each.

Likely cause (inference from saved rows and read-only source inspection):
`arity-error-form` at `src/cljc/yang/clojure.cljc:1087` constructs a map with
`cond->`/`assoc`; `compile-constructed-collection` at `:302–323` emits
`(interleave (keys form) (vals form))` without a stable ordering rule. The map's
host iteration order therefore enters canonical application operands. No fix
was attempted; choosing the ordering/evaluation policy is outside cc4.
The runtime meaning may be equivalent for this error map, but C1 replay requires
identical code addresses, and this gate correctly fails it.

## Pinned cohort and artifact checklist

`replay-expectations` is literal recorded JVM data, not generated expectations
on each host. `replay-snapshot` hashes the **entire** canonical-byte-sorted
occurrence-key vector and C1 relation using Jing BLAKE3; it also pins all three
formats' reconstructed call-provenance together. There is no origin/name/path
normalization. The macro entry additionally pins its entire producing log.
`jing/canonical-compare` supplies ordering, avoiding host printer order.
The original r1 pin keeps a directly asserted complete eight-key vector too.

| Producer / artifact | Pinned surfaces | A | Equality result |
|---|---|---:|---|
| native `compile-program` | root, full C1/keys, semantic/stack/register provenance | 14 | JVM/Node/Dart match |
| `compile-program` with `true yang/unit-opts` | same, including generated arity helper | 263 | JVM/Node match; Dart diverges |
| `lower-unit` | wrappers + bundled `library-for`, all C1/keys/provenance | 256 | JVM/Node match; Dart diverges |
| `clj-prelude/module-uast` | root and complete static relations | 114 | JVM/Node/Dart match |
| production macro expansion | constructed/retained twin origins, full producing log | 5 | JVM/Node/Dart match |
| second supplied admission token | same native root, different full origins/keys | 14 | JVM/Node/Dart match |
| fresh `yang.call` publication | root, full C1/keys, table/provenance/record/manifest addresses | 100 | JVM/Node/Dart match |
| fresh `clj` publication | same complete side artifacts and dependency address | 114 | JVM/Node/Dart match |

Total A = 880, including duplicate admissions and shared prelude content.
This is an occurrence count, not a storage-deduplicated count. The negative fixture
uses supplied `"admission-a"` and `"admission-b"` on `:cc4/replay`, member 0;
roots match and origin sets differ. Macro coordinates are supplied by the existing
production-style helper: seed token/medium `:callsite-test/standard-forms`,
program token `"callsite-test"`, medium `:program-in`, initial t=0. Expansion
records and their producing chain are included verbatim in the log digest.
Publication uses the publisher's recorded deterministic default specification
admission; its real origins are included in the complete C1/key digests.
`:origin` is omitted only from the redundant address-summary map, because its
printed keyword is not EDN-reader-safe; no persisted origin is changed.

Native uses default opts; the argument-vector and linked unit use the dispatch
revision's `yang/unit-opts`: convention `:arg-vector`, `:outcomes true`,
`:linked-runtime true`, `library-adapters`, and `vm/primitive-profiles`.
Those historical option names remain as implemented after the continuation port.
The compiler and test file SHA-256 pins below fix all profile/naming inputs.

Recorded artifacts:

- `test/resources/yang/clojure/cc4-replay-snapshots.edn`: readable JVM digest/address expectations, matching the literal test data.
- `test/resources/yang/clojure/cc4-replay-jvm.cbor`: the full eight-member JVM trees, C1 relations, sorted key vectors, expansion log, publication payloads and provenance. Binary canonical CBOR retains even origins whose printed keyword cannot be re-read as EDN.
- `test/resources/yang/clojure/cc4-replay-jvm-affected.edn`: complete JVM trees/C1/sorted keys for the two affected members.
- `test/resources/yang/clojure/cc4-replay-dart-divergences.edn`: expected/actual digest snapshots and complete actual Dart trees/C1 rows for those same members.
- `collab/yang-clojure/1795017275897-cc4-r2-evidence/`: immutable raw test/refusal logs, JVM capture recipe and row-difference evidence. The focused Dart failure prints the actual producer artifacts before failing.

## Host matrix: exact commands and counts

All executed runtime gates here ran on Darwin 25.6.0 arm64, host `darkstar`.
Tool selection: `export PATH="$HOME/.local/share/mise/shims:$PATH"`.
OpenJDK 21.0.2+13-58; Clojure CLI 1.12.4.1602; Node v25.6.1;
Flutter 3.47.8. JVM tests use the repository's `-Xss64m` policy.
ANTLR prerequisite: `bb gen:antlr` — input/output verified cache hit.
Single-namespace G0 runs comply with the laptop exception; no subsystem/full
lane set was launched locally.

| Runtime | Command | Result |
|---|---|---|
| JVM battery | `clojure -M:test -n yang.clojure-callsite-test` | 45 tests, 597 assertions, 0 failures/errors |
| JVM final diagnostic/source verification | `clojure -M:test -n yang.clojure-callsite-test -v yang.clojure-callsite-test/portable-replay-cohort-test` | 1 test, 11 assertions, 0 failures/errors |
| Node initial and final | `clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yang\\.clojure-callsite-test$"}'` | 40 tests, 508 assertions, 0 failures/errors; all eight snapshots match |
| Dart battery | `bb src/dev/cljd_agg.clj --only yang.clojure-callsite-test` | 40 registered tests: 39 pass, 1 fails (cohort); Flutter does not report an assertion tally |
| Dart isolated reproduction | `bb src/dev/cljd_agg.clj --only yang.clojure-callsite-test/portable-replay-cohort-test` | 1 registered test, fails reproducibly; actual artifacts captured |
| Jolt/J2 | `bb src/dev/jolt_j2_lane.clj core --only '^yang.clojure-callsite-test$'` | exit 1 at manifest; 0 Jolt child tests executed, no digests produced |

The JVM battery preceded the additional diagnostic-only artifact fields and B/S
preparation arity; the final focused JVM check and final Node battery include
those changes. The isolated Dart reproduction includes the diagnostic capture.
The original Dart attempt was a toolchain sandbox refusal (Flutter SDK cache
writes denied), not a test result. The retry with cache access compiled and ran,
and then exposed the real divergence. No full-suite claim is made.

TDD evidence: the initial empty snapshot expectation fails on JVM (1 failed
assertion out of 11); subsequent recorded expectations pass. Adding per-format
provenance caused another empty-expectation red before the final recording.
The J2 inclusion assertion initially fails because cc4 is absent; inclusion is
now present in `included` and in `check-manifest!`'s bare inventory. The four
other namespace omissions are independently reproduced using HEAD's original
`included` and original `check-manifest!` definitions (saved baseline script).

## Legacy behavior and refusal inventory

The existing battery passed `published-instruction-provenance-test` on JVM,
Node and Dart: an unannotated legacy module publishes with an address and no
instruction-provenance record. `unclaimed-batch-test` and
`unclaimed-after-claimed-test` preserve absence of a C1 claim. These are the
existing publication/load boundaries exercised by this battery; no new D1 or
universal migration claim is inferred from them.

The existing five JVM-only deftest families remain reader-gated on Node/Dart:
`repl-packet-provenance-survives-failure-test`,
`repl-packet-provenance-survives-link-resume-failure-test`,
`repl-packet-provenance-durable-test`,
`repl-fabricated-retention-evidence-is-ignored-on-pending-completion-test`, and
`repl-fabricated-retention-evidence-is-ignored-from-late-formatting-on-pending-completion-test`.
Their `:cljd nil` and JVM interop restrictions remain intact. Additionally,
`repl-query-link-forwarding-test` is registered on Dart but its `with-redefs`
spy body is explicitly `:cljd nil`; its green registration is **not evidence
that the forwarding spy executed on Dart**. This is the documented refusal.

J2's global applicability refusal names existing unlisted suites:
`yin.repl.php-parser-test`, `yin.vm.code-entry-test`,
`yin.vm.machine-data-test`, `yin.vm.provenance-test`. No exclusions were added,
removed or relaxed. The gate's manifest owner needs to reconcile these before
Jolt can execute cc4. The optional namespace selector cannot bypass this check.

The battery's invalid versions, missing/ambiguous origins, incomplete coverage,
wrapper-provenance tampering, fabricated retention evidence, and missing-record
negative cases remain tested under their existing applicability gates. The newly
observed Dart divergence is a compiler defect, not an accepted host refusal.

## Budget declaration and stop status

`1795016601236-cc4-r2-budget-declaration-v1.md` was written **before budget
measurements**. It declares finite input, storage, retained heap, peak heap/RSS,
cold/warm pipeline, stage and S−B ceilings, derived by scaling cc3 v2's reference
ceilings with explicit headroom. These are declarations, not measured passes.
The unexecuted preparation harness is preserved as
`1795017203479-cc4-r2-budget-runner.cljc.draft`; it is not a validated runner and
is deliberately outside test discovery. Its B/S preparation arity is retained
in the cohort helper for a later authorized budget round.

| Required experiment | Storage | Retained/peak | Pipeline | Verdict |
|---|---|---|---|---|
| pre-port B and S, JVM | not run | not run | not run | blocked by mandatory STOP |
| post-port B and S, JVM | not run | not run | not run | blocked by mandatory STOP |
| cross-check host B and S | not run | not run | not run | blocked by mandatory STOP |
| remaining runtime measurements | not run | not run | not run | blocked by mandatory STOP |
| S−B and pre/post shifts | unavailable | unavailable | unavailable | no compliance/attribution result |

The correct pre-port pin is merge `309fe842`'s first parent:
`8afd82f5e99d0f2b2d38a5f53b292105fdead688`. The historical depth table's
`fbd8da0f` baseline is not substituted for this required pin. A source-only
historical copy and a minimal common corpus were prepared under ignored
`target/cc4-preport` and `target/cc4-postport`; **no budget run was launched**.
Future measured pre/post shifts must be attributed to Phase 2 `d60038ef`, with
other intervening base changes documented as confounders. The declaration
states the B limitation explicitly: compiling annotations is common work, so
stripping them before downstream B operations only isolates downstream S−B.
No zero compile overhead, measured memory, budget pass or failed bound is claimed.

## Remote approval review and scope

Read-only load checks reached both designated hosts, both idle relative to core
count; no Dart lane was running. A request to create
`~/ci/datomworld-cc4-r2-node` on jupiter from the repository already there was
rejected by automatic approval review. Its stated reason: copying the entire
private repository and mutating the remote host required explicit authorization
for that specific operation, beyond host testing. The clone creation did not
execute. An asynchronous approval question is pending. Local focused gates
continued as the permitted alternative; no transfer workaround was attempted.
Now that a compiler divergence has triggered STOP, no remote acceptance or
budget work will proceed in this round regardless of that pending approval.

Only the two authorized tracked test/manifest files changed. All other new
artifacts are in the permitted fixture and collab locations. The compiler source
hash is recorded below solely as an input pin; it is unchanged from HEAD.
`git diff --check` passes. Final kondo reports **0 errors, 1 pre-existing unused
`yin.repl.query` require warning**, exit 2 due to warnings. No commit/stage/push.

## SHA-256 pins at delivery

| File | SHA-256 |
|---|---|
| `test/yang/clojure_callsite_test.cljc` | `feead0c05783e09e725c6786c2b3d8dd6cc32eca875ccc9e2c5fc2ff466ff607` |
| `src/dev/jolt_j2_lane.clj` | `e71dced27962666119f2a9c7ed70664ffcac820161b3ef4b5b654d9ae301bbca` |
| `test/resources/yang/clojure/cc4-replay-snapshots.edn` | `5f28913f56b1b001aa11d4ba8ef6216df334fcadaf7ba096b2992681007a959f` |
| `test/resources/yang/clojure/cc4-replay-jvm.cbor` | `4200ab187d04833f425de5dfd30685113841ffd58e62d1aacb11946123ababc2` |
| `test/resources/yang/clojure/cc4-replay-jvm-affected.edn` | `0b7ebab6bb5e3062876020bce10161c4e807786786fe806eb49e6bd924553f1c` |
| `test/resources/yang/clojure/cc4-replay-dart-divergences.edn` | `3e16f12a2e16f85bf36c58c230cd40df7b5aa3e64cd0c0f0b74265e3978578c2` |
| `src/cljc/yang/clojure.cljc` | `fc15a56c10ed2d59a842e0f677b3bcaa23ae1331112fdd3aaff13895b3d0a12b` |

Acceptance status: work item 1's JVM recording is implemented; work item 2 fails
on Dart and is independently blocked on J2; work items 3 and 4 cannot close
under the mandated compiler-defect STOP. The next authorized action is a
compiler determinism round, followed by replaying cc4 unchanged and then the
already-declared budget work. The failing Dart expectations must stay pinned.
