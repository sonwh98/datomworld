Created-GMT: 2026-11-20 15:04:07 GMT
Created-Local: 2026-11-20 22:04:07 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a1f878-9255-7591-a9d7-8c528b565999
# Task: Architecture review r2 confirmation of docs/design/yang.preludes.md (all 10 r1 findings reconciled)
Role: Lead System Architect
Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-20 21:57:24 +07 (+0700) | Status: completed | Rationale: Resume same OpenAI GPT Lead System Architect session (01a1f878-9255-7591-a9d7-8c528b565999) for r2 confirmation review of reconciled design

Completed-GMT: 2026-11-20 15:02:23 GMT
Completed-Local: 2026-11-20 22:02:23 ICT

**1. Verdict: REVISE**

Five r1 findings are **CONFIRMED-FIXED**; five remain **OPEN**. The architecture remains viable, but several revised contracts contradict live primitives or other requirements in the document.

I re-read the full revised design and the r1 report, and inspected supporting implementation contracts. This was a read-only review; no files were edited and no repository tests were run. Findings below concern specified behavior, not the absence of proposed implementations.

**2. r1 finding-by-finding disposition**

Line references in this table refer to `docs/design/yang.preludes.md`.

| # | r1 Finding | Status | Evidence / Notes |
|---|---|---|---|
| 1 | Portable numeric semantics | **OPEN** | Qualified adapters and integer kernels are the right correction. However, the classifier at line 522 rejects CLJS float carriers, and lines 527–530 violate or omit the newly documented helper preconditions. See F1. |
| 2 | Frontend-owned Hook ABI and structural lowering | **CONFIRMED-FIXED** | Lines 317–328 keep language-specific contracts outside core. Lines 539–551 specify compatible globals, function construction, control hooks, and unit-body arguments. Missing hooks and generator-expression gating are addressed. The separate function-entry defect is covered under finding 3. |
| 3 | Callable admission and exception bridging | **OPEN** | Authenticated cells and private provenance forwarding are appropriate, but the claimed behavior of `yang.call/eligible?` is incorrect. The entry boundary also loses normal return values and misclassifies binding failures. See F2–F3. |
| 4 | Portable `clojure.core` publication | **OPEN** | Export inventory, excluded host-dependent names, new helpers, primitive projections, task initialization, and sibling isolation are now explicit. The selected definitions still contain nonportable arithmetic, and dynamically returned functions lack a compatible construction contract. See F4–F5. |
| 5 | Generator frames and context migration | **CONFIRMED-FIXED** | Lines 140–151 preserve open non-`:finally` dispatch and distinguish frame count from activation depth. §4.5 and P4 now distinguish Python rebasing from PHP context restoration. |
| 6 | Hygienic hooks, authority validation, bundled helpers | **OPEN** | Qualification, lookup precedence, exact export validation, and reserved-door exclusions are corrected. However, the proposed bundled `get` binding cannot read vector frames when source code defines `get`. See F6. |
| 7 | Float rounding, domains, and capture safety | **OPEN** | Both exponentiation orders and their regression witness are preserved. Domain declarations improve substantially, but policy-hook signatures still pass raw doubles across the boundary prohibited by line 184. See F7. |
| 8 | Integer v4 refusal algebra | **CONFIRMED-FIXED** | Lines 210–219 correctly distinguish the three returned reasons from operational throws, preserve PHP-owned demotion, and replace implicit whitespace policy with `skip-while`. |
| 9 | Recipe/profile separation and verified closure | **CONFIRMED-FIXED** | Root-address pinning, immutable verified artifacts, qualified collision rules, and an optional effect ceiling resolve the architectural concerns. The empty-ceiling example needs a small correction; see F8. |
| 10 | Roadmap and acceptance gates | **CONFIRMED-FIXED** | P0 preserves existing emission details; P2 preserves default lowering bytes; P3 leaves default Clojure bundling unchanged. Independent P1 validation precedes coordinated activation. Gates are substantially more concrete, although they must incorporate the remaining corrections below. |

Confirmed sound at the design level: explicit caller-owned state in `yrt.*`; no core language registry; separation of parsing, lowering, admission, publication, and execution; private Layer-2b provenance handling; schema-1 preservation with call-site metadata outside manifests; deterministic emission; and unchanged default Clojure publication during P3. These are design confirmations, not execution-parity certifications.

**3. New or remaining findings**

**F1 — HIGH | [docs/design/yang.preludes.md:522](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:522) | Numeric classification and dispatch are not portable as specified | Define an executable kind test and complete guarded dispatch.**

The proposed classifier first requires `(data/number? x)`. The live [implementation](/Users/sto/workspace/datomworld/src/cljc/yin/vm/data.cljc:418) recognizes host numbers and exact-integer carriers. It does **not** recognize the CLJS [`Float64` object](/Users/sto/workspace/datomworld/src/cljc/dao/host/number.cljc:76). Consequently, a boxed float reaches the nonnumeric branch on Node. The subsequent `data/content=` test never runs. Copying the existing TypeScript expression does not establish its correctness.

There are additional dispatch contradictions:

- Line 527 converts both operands of negative integer exponentiation to floats, but `neg-pow` requires a **positive exact integer** exponent.
- Line 530 routes general float remainder to `fmod-pos`, whose domain excludes negative operands, zero divisors, and nonfinite values.
- Float exponentiation lacks the required exact-integral exponent check or explicit refusal of fractional exponents.
- Falling back to host-backed Yin `=` for collections leaves nested float equality host-dependent. CLJS `Float64` equality distinguishes signed zeros and equates NaNs; scalar special cases do not repair collection comparisons.

Specify portable carrier recognition, preserve exact exponent magnitudes, and define each sign/range/nonfinite branch or its explicit guest refusal. Add cross-host cases for boxed `1.0`, `2 ** -3`, negative remainder operands, fractional exponents, and collections containing signed zeros or NaNs.

**F2 — HIGH | [docs/design/yang.preludes.md:565](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:565) | The entry boundary loses results and violates binding-exception semantics | Use a value-returning boundary around binding and execution.**

Line 566 executes the function body through `try-orelse` with `identity` as `orelse`. By the document’s own line 163—and live [Python implementation](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/prelude.cljc:317)—normal execution discards the body result and calls `(orelse)` with **zero arguments**. This cannot preserve a function’s return value, including the promised result `20`.

Line 565 also specifies direct `:yang.call/arity` transfer for parameter mismatch. [§8.10.7](/Users/sto/workspace/datomworld/docs/design/yang.antlr.md:3861) requires callee binding failures to retain their local exception under `:yang.call/thrown`. The boundary must be installed **before binding**, as the existing [Clojure entry](/Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc:1218) does.

Use `yrt.escape/try-value` for function entries; retain `try-orelse` for source `try/except/else`. Put parameter binding inside the entry boundary and distinguish malformed protocol records from ordinary callee binding errors.

**F3 — HIGH | [docs/design/yang.preludes.md:563](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:563) | Heap authentication is incorrectly attributed to common callable admission | Specify source-level admission before structural invocation.**

`yang.call/eligible?` does not reject all unauthenticated maps. Its [descriptor projection](/Users/sto/workspace/datomworld/src/cljc/yang/core/call.cljc:29) dereferences an authenticated cell reference but otherwise retains the original map. A map with an entry and no eligibility predicate can therefore pass structural eligibility.

Clojure’s `dsg__admitted?` separately checks authenticated cells or its private immutable-descriptor witness. Those are different contracts.

Define the admission step performed by `clj.py/call` and source-facing callable predicates before invoking the shared helper. Preserve admissible foreign callables while refusing source data that merely contains entry-shaped keys. Do not change `yang.call` into a closed language registry or claim that structural eligibility establishes source authority.

**F4 — HIGH | [docs/design/yang.preludes.md:580](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:580) | The “portable subset” retains nonportable numeric definitions | Adapt or restrict numeric library exports.**

The selected library includes `inc`, `dec`, and `rem`. Live [`inc` and `dec`](/Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc:2608) use bare `+` and `-`; the [VM registry](/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc:404) binds these to host arithmetic. Cell-backed callable wrapping changes admission, not arithmetic.

Thus projected `inc(1.0)` still reaches the carrier-coercion problem that replacing Python operator hooks was intended to solve. Exact-integer arithmetic also remains outside the declared kernel/limit policy.

Specify portable implementations or explicit operand restrictions for these exports, including their equality behavior. Keep the correction confined to the new library profile so the existing Clojure default remains unchanged.

**F5 — MEDIUM | [docs/design/yang.preludes.md:586](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:586) | Initialization covers exported callables but not dynamically returned functions | Define callable construction throughout the new library.**

The library exports [`comp` and `partial`](/Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc:2887), which construct functions during invocation. Existing Clojure [function compilation](/Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc:1299) produces immutable descriptors with unit-local admission witnesses. Allocating top-level export cells in `init!` does not change those returned functions.

This is an unresolved publication contract, rather than a demand that the implementation already exist.

Specify how all escaping functions receive compatible admission at construction time. Preserve function identity across passage; avoid receiver-side replacement wrappers. If the new library uses a different compiler construction mode, reconcile its internal call path too: the existing intra-unit dispatcher reads an entry directly from a descriptor map.

Add a gate that returns `comp`/`partial` results and invokes them from independently compiled Clojure and other frontend units.

**F6 — HIGH | [docs/design/yang.preludes.md:172](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:172) | The existing `dsg__get` fallback cannot inspect escape frames | Supply capture-safe vector access.**

When source code defines `get`, the existing [`dsg__get` fallback](/Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc:2484) scans maps and returns `nil` for nonmaps. Escape frames are vectors, and the new runtime explicitly reads their indexed slots.

Binding shared escape-runtime `get` directly to that helper therefore breaks frame dispatch and traversal in this supported shadowing case. This is a concrete mismatch in the proposed reuse plan.

Extend the portable helper to cover the required vector semantics, or use a dedicated guest frame accessor built from `data/nth`. Test bundled/linked parity with source-defined `get` and active exception/finally frames.

**F7 — MEDIUM | [docs/design/yang.preludes.md:201](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:201) | Policy-hook signatures contradict the raw-double capture rule | Separate raw computation from callback invocation.**

Line 184 prohibits arbitrary guest calls while unwrapped doubles remain live. Nevertheless, lines 201–202 specify raw-double `x` and invoke `(on-underflow x n)` or `(on-range x)`.

Calling the location a “guard boundary” does not make that argument or the suspended environment portable.

Define the boundary concretely: raw helpers should return boxed results or safe status data, then a wrapper should invoke policy closures after leaving the raw arithmetic frame. Alternatively, provide an equally explicit capture-safe protocol. Cover exceptional hook paths in the P1 gate, not only successful arithmetic.

**F8 — LOW | [docs/design/yang.preludes.md:279](/Users/sto/workspace/datomworld/docs/design/yang.preludes.md:279) | The example effect ceiling rejects its own runtime | Omit the ceiling or provide an intentional allowed set.**

The Python profile example specifies `:yang.prelude/max-effects #{}`. Under line 296’s subset rule, this rejects a runtime using cell effects, including `cell/new`.

Use the documented absent/unbounded form for this example, or an explicit sufficient ceiling. This is an example defect; the revised ceiling architecture itself is sound.

**4. Owner-decision framing: D1–D7**

| Decision | Assessment |
|---|---|
| **D1 — Local mutation** | Concur with task-local cells and a separately named single-assignment variant. This fits current lowering and `nonlocal` requirements. |
| **D2 — `yrt.*` naming** | Concur. Linker names and implementation namespaces are clearly distinguished and align with the related designs. |
| **D3 — Python float tags** | Concur with retaining tags during P1. Representation migration should remain separate from runtime extraction. |
| **D4 — Batched activation** | Conditional concurrence remains appropriate: validate each extraction independently, then coordinate activation and refresh. Golden changes must follow semantic evidence. |
| **D5 — Curated projections** | Concur. Explicit aliases, lexical precedence, exact export checks, and reserved-door bans provide a bounded extension surface. |
| **D6 — Standalone `clojure.core`** | Concur with the constrained direction and unchanged default Clojure bundling. Implementation readiness remains conditional on F4–F5 and the callable-boundary corrections. |
| **D7 — Two exponentiation orders** | Concur with retaining both algorithms for P1. Unification remains a separate owner-approved semantic change with an oracle-departure record. |

These remain **owner decisions**, not approvals implied by “Architect concurred.” The revised framing is appropriate; the remaining work is to reconcile the concrete contracts and their acceptance tests.
