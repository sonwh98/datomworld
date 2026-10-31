# Architectural ruling: Track A Phase C4 Slice P2

Date: 2026-10-08.
Architect: Codex, Lead System Architect.
Branch: `yang-python-c4-p2`; baseline: `f0ade63e`; reviewed commit: `6df4c12b`, plus the current working tree, including the untracked linked harness and acceptance namespace.

**Final verdict: REVISE, for incomplete acceptance execution evidence. P2-R1 is superseded and closed. No production correctness defect was established.**

## 1. Controlling authority and P2-R1 disposition

The [L-f sign-off, section 4](../archive/linker-l-f/1791407000000-architect-l-f-signoff.codex.findings.md#4-f3--serving-policy-deferred-liveness-and-p2-evidence-amendment) explicitly amends [P2 remediation section 6](1791405500000-architect-c4-p2-remediation.claude-fable-5-1.findings.md). It predates the [adversarial P2 review](1791408000000-reviewer-c4-p2.codex.findings.md) and governs this review.

That amendment removes `a-verifying-serve-links-py`, including verified-versus-trusted output parity, from P2's current acceptance gate. The test is deferred to the independent linker/REPL liveness remediation. P2-R1 therefore requests a test that was already formally deferred. Its assertion that local publication does not test receiving-task verifying service is technically correct, but that integration path is no longer required for P2 acceptance. The finding cannot block P2 and does not require an implementation revision.

This disposition preserves the distinction between evidence types. The omitted integration test has not passed. Large default-verifying serving remains limited by the bounded attempt driver's loss of progress across retries. The future liveness slice must restore that evidence without removing finite-work bounds or merely increasing the round cap. Trust belongs on the serving response; registry derivation assertions are separate. No registry `:trust` slot is required.

## 2. A1 verifying derivation evidence

`linked_prelude_test.cljc`'s `manifest-address-test` dereferences the harness publication produced by `publish/publish-module!`. It checks both `:status :ok` and `:trust :verified` for each of:

- `:yin.semantic/code`
- `:yin.debruijn.code`
- `:yin.debruijn.register`

It also checks the exact manifest golden and repeat-publication identity. The harness does not supply `:trusted` to publication. Its explicit trusted policy applies to the separate serving composition over the store it published itself.

**These assertions provide exactly the local verifying derivation evidence required by amended A1 when executed on each host.** A manifest address alone would not suffice. Shared delayed publication once per process is acceptable: every consumer uses the same publication, and A1 inspects its actual outcomes. Additional publications for repeat identity and the revised prelude remain intentional.

The implementation satisfies the evidence design. Completed execution on every required host is a separate obligation, addressed below.

## 3. Implementation and acceptance assessment

Reviewed the [original A1-A10 specification](1791403000000-architect-c4-p2-spec.claude-fable-5-1.findings.md), the A11/A12 remediation additions, the binding L-f amendment, production and test diffs against `f0ade63e`, and the working-tree linked tests. GitHub source references below identify repository paths on the review branch; uncommitted additions remain local review material until committed.

- **A1:** Correct local-verification assertions, exact manifest golden, repeat publication, and named walker refusal are present. Cross-host execution is not fully evidenced.
- **A2:** The JVM `every-vm=` helper adds the linked semantic, stack and register legs while preserving bundled and no-op-hook runs. Portable C3 parity runs every wide-profile corpus entry against its bundled result on the same three backends. The recorded JVM source run and Dart slow run pass; Node evidence is missing.
- **A3:** A second published specification appends and exports `probe`; the manifest changes while the fixed linked program retains its golden root. The program contains no prelude definition or internal runtime-key reference.
- **A4:** Two units continue the same task on all three backends, checking exception membership and class identity across repeated require/init. The Python-source module form remains assigned to I1.
- **A5:** The install child halts without blocking, has an empty heap, defines all exports with `py.rt/state` still uninitialized, and lifts exactly one store with no cells.
- **A6:** Equal, wrong, missing and linked-entry host profiles have direct discharge coverage. Receiving-task checks include equal and wrong profiles; the full linked prelude rejects the wrong `cell/new` profile by name.
- **A7:** An absent name environment produces `:absent` for `py`, with no bundled fallback or prelude keys in the task store.
- **A8:** Runtime placeholders derive from the initialization tables; qualified declarations equal the host-name set, bare declarations are primitives, exports are disjoint from primitives, and all exports are defined at module level. Excluding the separately defined ready flag from `runtime-keys` is correct.
- **A9:** `two-emitters-one-source-test` checks stripped export equality, qualified internal keys plus the ready flag, and the reserved-name invariant.
- **A10:** The walker is explicitly excluded from linked execution; its publication outcome asserts `:refused :undeclared-free`, naming `float?`. L-b owns the semantic change.
- **A11:** Design prose records publication at approximately 37-45 seconds and linked execution at 4.4-5.3 seconds versus 0.4-1.0 seconds bundled. The execution target of at most twice bundled is exceeded. This is accepted as a nonblocking performance finding under the remediation's explicit disposition, not permission to tune CBOR or the linker in P2. A complete final report should preserve per-backend measurements; the current ranges and decode attribution were not independently reproduced here.
- **A12:** The test derives row count and maximum occurrence depth from the emitted tree and compares both against half the default bounds. No hand-maintained size constant or enlarged bound substitutes for the budget.

The [prelude emitter](https://github.com/sonwh98/datomworld/blob/yang-python-c4-p2/src/cljc/yang/python/antlr/prelude.cljc) preserves one definition source, strips only `py/`, emits immutable placeholders before definitions, and uses the authorized wide application. `vconj` and `lnot` avoid primitive shadowing. The local free-name derivation constructs a publication specification; it does not replace the linker's scanners or their authority. `module-spec` refuses unsupplied names and derives host effects from the registry.

The [lowering](https://github.com/sonwh98/datomworld/blob/yang-python-c4-p2/src/cljc/yang/python/antlr/lower.cljc) keeps bundled as the default and emits explicit require, initialization and main execution for linked mode. Baseless classes call `py/object-class`; linked programs do not access private runtime keys. The options-map stage arity and safepoint rename are justified supporting changes.

The [host-profile helper](https://github.com/sonwh98/datomworld/blob/yang-python-c4-p2/src/cljc/yin/vm/module.cljc) accepts host-registered entries and excludes addressed linked entries, whose primitive declarations describe assumptions rather than exports. Both discharge sites use profile identity. P2 changes neither scanners, manifest/format schemas, publication machinery, install phases, lift nor receipt. The inherited L-f changes are not attributed to P2. Runtime allocation remains an explicit operation of the consuming task. No new production shared mutable state, host callback path or stream bypass was found.

## 4. Validation record and new finding P2-R2

**P2-R2: required execution record is incomplete (merge-blocking evidence gap).** Closing P2-R1 does not waive the remaining host lanes or corpus acceptance requirements.

Inspected existing local logs:

- `target/p2-cljd-linked.log`: 13 tests passed, including manifest, revised-root, install, identity and refusal cases with slow bodies executed.
- `target/p2-slow-cljd.log`: 336 tests passed; the portable C3 linked parity test appears in the executed run.
- `target/p2-e2e.log`: 41 tests, 697 assertions, zero failures or errors.
- `target/p2-parity.log`: 67 tests, 338 assertions, zero failures or errors.
- `target/p2-float.log`: 12 tests, 105 assertions, zero failures or errors.
- `target/p2-test-clj.log`: 3833 tests, 242065 assertions, zero failures and one error, the documented missing `test/resources/yin/vm/ucf/handoff-v2.txt` fixture.
- `target/p2-test-cljd.log`: 3649 passing tests and one failure, naming the same census baseline test.
- `target/p2-test-cljs.log`: startup text only, no completed summary. `target/p2-node-run.log` terminates with `MODULE_NOT_FOUND` for `@noble/hashes/blake3.js`. The dependency is also absent in the inspected workspace.
- `target/p2-slow-clj.log`: reaches `e2e-c1-test`, with no completed summary. The final engineer stdout file says that the slow set is running and the final report will follow; it is not a final acceptance report.

Existing logs are recorded evidence, not newly reproduced runs or proof of exact working-tree provenance. The independent reviewer separately reports 73 JVM tests and 755 assertions passing for module, linker and store-write audit coverage, while explicitly disclaiming completion of its P2 acceptance run.

This review additionally started `clojure -M:test -n yang.python.antlr.linked-prelude-test -n yin.vm.linker-manifest-test -n yin.vm.linker-require-test`. It reached the linked-prelude namespace but produced no completion summary before interruption (exit 130). Its log is `/tmp/p2-architect-tests.log`. No passing count, reproduced golden or completed discharge run is claimed from that attempt.

Required revision: complete and record the Node fast and guarded slow lanes, including A1's three verified publication outcomes, A3's root golden, and A2's C3 parity; complete the outstanding required JVM Python slow coverage; attach the final lane counts and per-backend A11 measurements to the engineer report. Reuse applicable completed evidence with its tested revision identified. Preserve named baseline exceptions. No `a-verifying-serve-links-py` test is required for this revision.

## 5. Format and final disposition

The reviewed added tracked lines and the new linked test files contain no em dashes or first-person narrative. `git diff --check f0ade63e` passes. This ruling uses GitHub links for production source references and relative links for local historical reports. Existing reports remain unchanged. Design prose correctly discloses L-b and the remaining L-a work; its prospective statement that P2 has landed becomes accurate only upon actual landing and should not be treated as execution evidence.

**P2-R1: closed as superseded by the binding L-f section 4 amendment. A1: correct verifying-evidence mechanism. Architecture: acceptable on inspection. Final merge sign-off: REVISE for P2-R2 until the required execution record is complete.** The performance overrun, walker support, I1 semantics and large verifying-serve liveness remain explicit separate dispositions, not newly imposed P2 implementation work.

Only this findings file is authored by this review. No implementation changes, staging, commit or merge are performed.
