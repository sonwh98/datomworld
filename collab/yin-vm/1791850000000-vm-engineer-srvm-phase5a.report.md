Completed-GMT: 2026-10-11 06:17:30 GMT
Completed-Local: 2026-10-11 13:17:30 Asia/Bangkok

Implementation report for slice 5a, VM Engineer (yin.vm). Worktree `/Users/sto/workspace/datomworld-srvm-p5a`, branch `srvm-phase5a`. Implementation only: no independent review or sign-off performed. No files staged or committed.

Changed files (new versus modified)

- NEW: `src/cljc/yin/vm/semantic_register/derive.cljc`: lexical resolution from A's closure-owner chain; pc-ordered address readers; stackification of A's parsed expressions; FIFO closure bodies; absolute labels; diagnostic side table; `register-image` throws exactly `{:reason :not-in-slice-5a}`.
- NEW: `test/yin/vm/semantic_register/derive_test.cljc`: 94 structural corpus rows (26 B0 + 32 register + 15 respelled old stack + 4 public address-law extras + 5 respelled live fixtures + 12 respelled R2 fixtures), ten literal H goldens covering §6.3 rows 1–9 (row 7 has two), literal existing image checksums, normalization, execution runners and acceptance tests.
- MODIFIED existing source/tests/designs: none. Scope remains the two files of §10.1.
- NEW report, logs, verified byte backup, and verification scripts under `collab/`. Existing prompt/stdout log were already untracked on arrival. Nothing under `collab/` staged.

Exact lane outcomes

| Lane | Result | Tests | Assertions | Failures | Errors | Evidence |
|---|---|---:|---:|---:|---:|---|
| JVM final selected namespaces | PASS, exit 0 | 357 | 7,112 | 0 | 0 | `1791850000000-5a-jvm-final.log` |
| Node final selected namespaces and required test dependencies | PASS, exit 0; 170 files, 3 compiled, 0 compiler warnings | 356 | 7,067 | 0 | 0 | `1791850000000-5a-node-final.log` |
| JVM focused, restored source | PASS | 10 | 2,946 | 0 | 0 | `1791850000000-5a-restored-g0.log` |
| Dart | NOT RUN: compile failed, exit 1 | 0 executed | 0 executed | — | — | `1791850000000-5a-dart.log` |
| kondo changed paths | PASS, 0 errors, 0 warnings | — | — | — | — | `1791850000000-5a-kondo-final.log` |
| cljstyle check changed paths | PASS, exit 0 | — | — | — | — | `1791850000000-5a-cljstyle-final.log` |
| trailing whitespace scan | PASS, 0 lines | — | — | — | — | `1791850000000-5a-whitespace.log` |

Node explicitly printed `Testing yin.vm.semantic-register.derive-test` and all nine requested existing semantic-register namespaces. Its test dependencies additionally selected `yin.vm-test` and `yin.vm.debruijn-vm-contract-test`; the final JVM command includes those too. Host-specific tests account for the count difference.

Final JVM command (each namespace literal; no zsh namespace variable):

```sh
clojure -M:test -n yin.vm.semantic-register.derive-test -n yin.vm.semantic-register.code-test -n yin.vm.semantic-register.linearize-test -n yin.vm.semantic-register.analysis-test -n yin.vm.semantic-register.load-paths-test -n yin.vm.semantic-register.vm-test -n yin.vm.semantic-register.walker-trace-test -n yin.vm.semantic-register.parity-test -n yin.vm.semantic-register.effects-test -n yin.vm.semantic-register.link-test -n yin.vm.debruijn-test -n yin.vm.debruijn-code-test -n yin.vm.debruijn-resolve-test -n yin.vm.debruijn-linearize-test -n yin.vm.debruijn-register-contract-test -n yin.vm.debruijn.stack-test -n yin.vm.debruijn.stack-effects-test -n yin.vm.debruijn.stack-parity-test -n yin.vm.debruijn-vm-contract-test -n yin.vm-test -n yin.vm.parity-test > collab/1791850000000-5a-jvm-final.log 2>&1
```

Final Node command:

```sh
clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin\\.vm\\.(semantic-register\\.(derive|code|linearize|analysis|load-paths|vm|walker-trace|parity|effects|link)-test|debruijn(-code|-resolve|-linearize|-register-contract)?-test|debruijn\\.(stack|stack-effects|stack-parity)-test|parity-test)$"}' > collab/1791850000000-5a-node-final.log 2>&1
```

Focused restore check:

```sh
clojure -M:test -n yin.vm.semantic-register.derive-test > collab/1791850000000-5a-restored-g0.log 2>&1
```

Dart command attempted, without escalation:

```sh
bb src/dev/cljd_agg.clj --only yin.vm.semantic-register.derive-test,yin.vm.semantic-register.code-test,yin.vm.semantic-register.linearize-test,yin.vm.semantic-register.analysis-test,yin.vm.semantic-register.load-paths-test,yin.vm.semantic-register.vm-test,yin.vm.semantic-register.walker-trace-test,yin.vm.semantic-register.parity-test,yin.vm.semantic-register.effects-test,yin.vm.semantic-register.link-test,yin.vm.debruijn-test,yin.vm.debruijn-code-test,yin.vm.debruijn-resolve-test,yin.vm.debruijn-linearize-test,yin.vm.debruijn-register-contract-test,yin.vm.debruijn.stack-test,yin.vm.debruijn.stack-effects-test,yin.vm.debruijn.stack-parity-test,yin.vm.parity-test > collab/1791850000000-5a-dart.log 2>&1
```

Exact blocking errors include:

```text
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 71: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.stamp.tmp.38860: Operation not permitted
/Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/internal/update_engine_version.sh: line 78: /Users/sto/.local/share/mise/installs/flutter/3.47.6-stable/bin/cache/engine.realm: Operation not permitted
Execution error at cljd.compiler/mk-live-analyzer-info (compiler.cljc:214).
EOF while reading
Exception in thread "Thread-0" java.lang.RuntimeException: Operation not permitted (sysctl failed)
cljd compile failed, exit 1
```

The log also contains repeated Flutter failures with subsequent temporary-file suffixes. No Dart test executed; no Dart success is claimed. This was a sandbox failure, not an automatic approval-review rejection (no escalation requested).

Lint/style commands:

```sh
clj -M:kondo --lint src/cljc/yin/vm/semantic_register/derive.cljc test/yin/vm/semantic_register/derive_test.cljc
cljstyle check src/cljc/yin/vm/semantic_register/derive.cljc test/yin/vm/semantic_register/derive_test.cljc
```

Earlier runs, retained honestly: two G0 compile failures due to test-harness contract names (`dc/contract`, then `vm/debruijn-contract`); corrected to `vm/stack-contract`. First executable numeric run: 10 tests, 2,452 assertions, 4 failures, 0 errors: three missing primitives in the composition and the old resolved-address reader's stream value attribute. All numeric assertions passed. Corrected stream reader to `:yin/val-node` and supplied the three primitives as described below; next G0: 10 tests, 2,452 assertions, 0 failures/errors. After checksums and stronger body-layout assertions: 10 tests, 2,946 assertions. Earlier selected JVM: 268 tests/5,416 assertions and separate stack effects/parity: 46 tests/675 assertions, both green. Earlier correctly selected Node: 356 tests/6,583 assertions, green.

The initial Node command incorrectly nested `:ns-regexp` under `:builds :test`; shadow expects the direct build configuration. That run selected extra namespaces, was interrupted (exit 130) without a completed count, and is not a verification claim (`1791850000000-5a-node.log`). The corrected commands above completed.

No `bb test`, `bb test:changed`, or complete `bb test:sub yin.vm` run was made. The user requested the targeted pre-report lanes and reserved G2/G3 for the orchestrator. No slow test is introduced by 5a. The mandatory linked C4 slow oracle belongs explicitly to 5b item 9, and the scanner slow gate to 5c item 7; neither is claimed as run here (0 C4 roots and 0 C4 module runs).

Numbered acceptance list (§10.1)

1. PASS — `resolve-rewrites-only-vars-and-closures`, all 94 structural rows: length/tuple preservation, destination preservation, exact closure arity, exact bound pc/name domain. Mutation M01 and M04 fail this named test.
2. PASS — `resolution-is-resolve-names`: duplicate binder `[0 1]`; nested duplicate outer binder `a [1 1]`, `b [0 0]`; nested lambdas `a [1 0]`, `b [0 0]`; free `y`. M02 and M03 fail this test.
3. PASS on executed hosts — `resolve-is-a-stage-value`: JVM public-name audit finds no executable-identity function; portable `pr-str`/EDN round-trip on every row; both sequence readers return vectors. The production namespace introduces no digest or identity API. Reflection is `#?(:cljd nil :clj ...)`, with cljd first.
4. PASS — `stack-goldens`: ten H vectors from §6.3 pinned literally, absolute labels 4/5, 6/7 and body 6; ten checksum strings computed from those printed pinned vectors through unchanged `dc/image-hash`. No old-image byte comparison or `dl/adapt` call in this test. M05–M11 fail it.
5. PASS on executed hosts — `stack-images-validate`, all 94 rows: A and H validators, closure arities/params, body counts/discovery order/terminators/source body association, and independent lexical chain bounds and exact name resolution. M13/M14 fail the strengthened test.
6. PASS — `stack-determinism`: vectors, encoded bytes and existing checksums on each row, plus equal-A/equal-address/equal-H pairs for macro metadata, sharing eid, and a literal's tail flag. Provenance-bearing maps are not compared.
7. PASS — `stack-execution-oracle`: all 26 B0 rows equal old/new and pinned normalized expected values; 12 successful register rows; 19 equal thrown messages; exactly 1 successful explicit zero-argument `:op/ping` bridge, `[true false :pong]`. Counts 26/12/19/1 are asserted. Both pipelines compare normalized stores as well as halted/blocked/value observables. Composition clarification below. M12 fails this test.
8. PASS — `lexical-address-law-stack`: A, resolved A, H and old occurrence resolver equal per structural corpus row; nested depth 1, duplicate position 1, and no free `yin/def` non-vacuity checks. Old definitions walk only their value child.
9. PASS — `register-image-is-not-in-5a`: exact exception data, no A → R implementation in production.
10. FAIL / incomplete tri-host gate — kondo/style/whitespace clean, requested JVM/Node selections green and Node new-namespace confirmation present. Dart NOT RUN due to sandbox; complete `bb test:sub yin.vm` remains the orchestrator's gate. No tri-host sign-off claimed.

Mutation checks (tests that can fail)

Before any mutant, copied production source to `1791850000000-5a-derive.cljc.backup` inside this worktree's `collab/`, then verified with `cmp`. Every mutant changes that source file, reloads it, runs the named existing test with `clojure.test/test-vars`, then restores via byte stream copy in `finally`. Every restoration verifies both `cmp` and the requested `git diff --quiet HEAD -- src/cljc/yin/vm/semantic_register/derive.cljc`. Because the new file is untracked, git diff alone is vacuous; `cmp` is the actual byte-for-byte restoration proof. The test file is never mutated.

Final runner: `clojure -M:cljs collab/1791850000000-5a-mutations.clj > collab/1791850000000-5a-mutations-verified.log 2>&1`, exit 0. Source restored and a fresh focused G0 passed afterward. All fourteen mutants trigger assertions; none relies merely on compiler failure.

| Mutant | Named test (`derive-test/…`) | Failed assertions | Errors | Final result |
|---|---|---:|---:|---|
| M01 bound names omitted | `resolve-rewrites-only-vars-and-closures` | 62 | 0 | KILLED |
| M02 outer frame lost | `resolution-is-resolve-names` | 2 | 0 | KILLED |
| M03 binder position off by one | `resolution-is-resolve-names` | 4 | 0 | KILLED |
| M04 closure arity off by one | `resolve-rewrites-only-vars-and-closures` | 36 | 0 | KILLED |
| M05 call argc off by one | `stack-goldens` | 8 | 0 | KILLED |
| M06 tail flag discarded | `stack-goldens` | 2 | 0 | KILLED |
| M07 conditional labels off by one | `stack-goldens` | 2 | 0 | KILLED |
| M08 closure body label off by one | `stack-goldens` | 1 | 0 | KILLED |
| M09 definition key lost | `stack-goldens` | 2 | 0 | KILLED |
| M10 resume id lost | `stack-goldens` | 1 | 0 | KILLED |
| M11 call pushes omitted | `stack-goldens` | 8 | 0 | KILLED |
| M12 FFI argc off by one | `stack-execution-oracle` | 2 | 0 | KILLED |
| M14 sibling bodies reordered | `stack-images-validate` | 28 | 0 | KILLED |
| M13 lambda terminator becomes halt | `stack-images-validate` | 36 | 0 | KILLED |

Initial surviving mutant is a finding, not omitted: M13 (lambda terminator `:return` → `:halt`) survived the original `stack-images-validate` (543 passing assertions, 0 failures/errors) in `1791850000000-5a-mutations.log`. `dc/image-defect` permits that image. Added independent FIFO body partition, source-body association and terminator checks to the same named test; M13 now produces 36 failed assertions, M14 reordered sibling bodies produces 28. No existing validator was changed. The intermediate mutation run completed its tests/restorations but waited on the Java agent pool used by shell checks and was interrupted (exit 130); the final runner adds `shutdown-agents` and exits 0.

Hand-derived number verification

The first executable numeric run compared all H instructions and absolute labels literally and all explicitly stated L(p) sets, including the row 7 terminal-arm layout note. None of those numbers was wrong. All H pinned images pass the existing validator. No golden was silently adjusted.

A separate verification-only script, `1791850000000-5a-check-hand-numbers.clj`, respells §6.3's ten literal R instruction vectors directly from the frozen companion text, reconstructs intervals from A's syntactic definitions plus L, runs the specified expiry/allocation rule, checks every register-bearing slot against the pinned R tuples, and runs `register-image-defect` on each pinned image. It does not implement or export `register-image` in production. Command: `clojure -M:cljs collab/1791850000000-5a-check-hand-numbers.clj`; successful numeric run exit 0, evidence `1791850000000-5a-hand-numbers.log`. Its initial compile attempt referred to nonexistent `rcode/rd`/`uses`; corrected to reading the public opcode table before any R numbers executed. Printed intervals, slot assignments and peaks agree with the design; the peaks in image order are 4,4,2,1,4,(3 main/4 lambda),2,4,3,4. Structural slot is T3 in the terminal operand, four total registers. All ten R validators return nil, checking live operands as well. The note's points {4,5,6} is asserted in `hand-derived-liveness`.

The only new hash-string pins are existing H checksums generated from the literal vectors, saved in `1791850000000-5a-golden-hashes.log`; no executable identity is assigned to the resolved stage. A identity remains the existing BLAKE3 `linearize/project` address.

Ambiguities, findings, and smallest readings taken

- Composition inconsistency, evidence `src/cljc/yin/vm.cljc:375` and first numeric log: §6.1.3 specifies exactly `vm/primitives`, but the required successful `:gensym`, `:store-ops`, `:define-then-call` rows use `list`, `vector`, `inc`, which that registry does not contain. Both pipelines originally threw the same unbound-name errors for these rows. Smallest test-only reading to satisfy the specified 12 value rows: extend both identical compositions with `(fn [& xs] (apply list xs))`, `(fn [& xs] (vec xs))`, and `inc`. Existing source registry/corpus/design are untouched; runtime count remains exactly 12/19/1. This clarification needs reviewer attention.
- Checksum wording: the owner says A alone is code identity and only the existing descriptor hash uses SHA-256, while §10.1 item 4 explicitly requires `dc/image-hash` strings and §3.1 forbids changing that format function. Existing `debruijn_code.cljc:551` implements `image-hash` with SHA-256. Smallest reading: production derivation adds no hash call or new identity; tests pin the mandated unchanged native checksum through the existing function. No SHA-256 implementation or identity API was added. If the owner intends that existing checksum function to change, that conflicts with this slice's untouched-files box and is unresolved outside 5a.
- Reader input underspecification: §5 names `resolved-addresses` for the old-resolver leg but gives no signature. It accepts the old resolver's public result map, walks occurrence tuples itself with FIFO bodies, and skips a definition's operator/key. `addresses` accepts A, the resolved vector or native instruction vector (also a `:vector`/`:instructions` map); it reads loads by tuple length. Neither assigns identity or is called by the emitter for the old-resolver path.
- Corpus/gate scope: §6.1.1 labels linked C4 as part of C, but §10.1's runtime acceptance lists only 26/12/19/1 and §10.2 item 9 explicitly assigns the linked C4 oracle to 5b. Smallest reading for 5a is the 94 non-C4 structural rows; linked roots/modules are deferred to that explicitly named gate, not claimed covered here.
- Frozen §8.3's adapter and byte-oracle text is superseded by companion §0 owner rulings. Production requires only semantic-register code and `debruijn/resolve-name`; it has no old lowerer/resolver/kernel imports. Old lowerers are used only in the test execution oracle. No design amendment or version bump.
- A-omitted facts cannot affect H: only A enters the emitter. Macro metadata, entity sharing and irrelevant literal tail metadata are checked through equal-A/equal-H tests. Diagnostic source columns and provenance are outside native checksum and A; the source side table here names A pcs, so equal A gives equal diagnostics too.
- The existing validator's permissive lambda terminator check is the M13 finding above. New assertions close the derivation-test gap while the existing format stays untouched.

Unresolved concerns and incomplete work

- Dart test execution is unverified and must be rerun outside this sandbox; acceptance item 10 is not satisfied on three hosts.
- Full subsystem G1 and orchestrator G2/G3 were not run; only the explicit selected pre-report lanes were completed.
- Independent non-author review/sign-off by claude-fable-5-1 remains outstanding. This report is implementation evidence, not self-review sign-off.
- The composition, existing checksum wording and C4 scope readings above need that review. No design changes were made.
- A → R is deliberately incomplete and still throws the specified exception; 5b is not implemented. No scanner, pipeline cutover, publisher, format or old-lowerer changes.
