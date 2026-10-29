# Yin.CIC — Implementation Plan

**Architecture:** Clojure/CLJC source → `yang.clojure` → Yin Universal AST (UAST) → Yin.VM → CIC proof checking  
**Status:** Proposed plan, not a claim of existing implementation  
**Updated:** 2026-10-28

## 1. Decision

Implement the CIC kernel in **a deliberately restricted subset of Clojure**, preferably portable `.cljc`, and compile it with `yang.clojure` to Yin's UAST. Run the resulting content-addressed program on Yin.VM. This should be the canonical deployable checker. Do not hand-code the checker in UAST or hard-wire CIC into the VM.

This is aligned with Datom.World's documented division of responsibilities:

- **Yang** compiles Clojure expressions into UAST.
- **Yin** interprets/execut es programs with its continuation-oriented CESK machinery.
- **DaoSpace** may persist and query program structure, proof terms, declarations, dependencies, and checking state.

**Two distinct uses of the universal representation must remain separate:**

1. **Kernel implementation:** Clojure source compiled by Yang into executable Yin UAST/code rows.
2. **Kernel input:** Fully explicit CIC terms, types, declarations, and proof objects encoded as *data* in the universal datom/AST vocabulary and checked by the kernel.

The same underlying graph/tuple substrate can represent both, but a Clojure `fn` is not automatically a logically valid CIC lambda term. A checker must interpret the typed logical vocabulary according to a fixed formal specification — one frozen by the specification gate before Phase 1 (Gate S, section 7).

## 2. System layout

```text
               Clojure / CLJC source
                  (yin.cic library)
                         |
                         v
                    yang.clojure
                         |
                         v
              Yin UAST / code rows
                         |
                         v
                     Yin.VM
                (CESK, closures,
                 environments,
                 continuations)
                         |
                         v
                  CIC checker code
                         ^
                         |
  Explicit CIC terms, proofs, declarations, universes
      (datom/UAST objects; CHECKED as data)
                         |
                         v
               Immutable environment
                         |
                         v
             Accept / Reject / Unknown
```

`Unknown` is for incomplete checking or resource exhaustion; it must never be treated as `Accept`.

Yang's role is to compile **the checker**; it must not be relied upon as a proof validator. Elaborators, tactics, compilers, LLMs, and other proof producers supply candidate terms. Only the CIC kernel authorizes checked declarations.

## 3. Design principles

1. **Yin-native runtime, not a peer VM.** The kernel is an ordinary Yin application compiled from Clojure.
2. **Small logical core.** Begin with sorts, variables, application, lambda, Pi, let, constants, and conversion. Inductives arrive later.
3. **Use Yin's unified functions, closures, environments, and continuations.** Share machinery where it preserves the required logical semantics.
4. **Separate VM execution from logical reduction.** Yin executes the *checker*. CIC definitional equality obeys only CIC's admitted beta/delta/zeta/iota/etc. rules.
5. **No arbitrary host effects in conversion.** Do not allow FFI, mutable host callbacks, runtime code rewriting, general recursion, or continuation control operations to count as logical computation.
6. **Immutable identity.** Pin the full *checking configuration* for each checking result: the checked declaration environment, term graph, compiled kernel artifact, VM/compiler/schema versions, and the runtime trust identity of the closed executable composition the checker runs under (section 5).
7. **Explicit trust accounting.** Because the checker is compiled, confidence in a Yin-checked proof depends on the checker source **and** Yang's semantics preservation, Yin.VM, and any trusted runtime primitives.
8. **Cross-check rather than self-certify.** Maintain a directly runnable Clojure reference execution and use Lean 4 as an external oracle on the common supported fragment.

## 4. The critical first engineering question

Can `yang.clojure` compile and Yin.VM execute enough Clojure to implement the checker **without undocumented host-Clojure escapes**?

Current public documentation lists literals, variable lookup, `fn`, application, `if`, `let` (desugared), and `do` as supported by the compiler. Treat richer Clojure constructs (`case`, `loop/recur`, records, multimethods, macros, recursive definitions, persistent-map operations) as **features to audit**, not assumed capabilities.

Create a compatibility matrix. Every row carries a per-host status — JVM, Node, and Dart — matching the tri-host verification contract of `docs/agents/build-n-test.md`; a facility counts as supported only on the lanes that pass, and a Dart gap is recorded as a named blocker in the matrix rather than left implicit:

| Required facility | Target support | Fallback if absent |
|---|---|---|
| Closures + lexical binding | Must work | Fix Yang/Yin |
| Recursive functions | Must work | Named self-application or explicit continuation machinery |
| Immutable sequence/vector/map access | Must work | Define a small Yin collection API |
| Tagged sum-like term values | Must work | Use vectors/tuples with stable tags |
| Arbitrary-precision natural numbers or symbolic levels | Needed for universes | Pure logical representation, not host integer shortcuts |
| Deterministic structural comparison | Must work | Implement verified comparison over canonical nodes |
| Hash/identity of canonical terms | Important for performance | Sound structural comparison before trusting cache shortcuts |
| Suspend/resume | Later | First establish uninterrupted equivalence |

**Gate 0:** A simple Clojure lambda/closure application, a recursive AST walk, and an immutable environment lookup compile and run equivalently on Yin and the reference Clojure host, on every host lane the matrix marks green; a lane that cannot run them is a recorded blocker, not an unmentioned gap.

## 5. Trusted computing base

A major consequence of the proposed architecture: **Yang and the Yin runtime become part of the trusted checking path**. This differs from checking Lean proof terms with its native kernel, for which ordinary elaboration and compilation are outside the logical TCB.

Risk controls:

- Pin the compiler, VM, compiled kernel artifact, and data schema versions.
- Retain source-to-UAST mappings and stable code IDs.
- Make the approved kernel artifact immutable during checking.
- Reject any runtime macro/rewrite that mutates the trusted checker or checked environment mid-verification.
- Test Yang semantics preservation with reference interpretation and targeted adversarial programs.
- Keep host services used by the checker few, pure, explicit, and audited.
- Reject unsupported declaration constructors rather than interpreting them loosely.
- Require all proof objects to be rechecked before entering a trusted environment.

#### Checking configuration and runtime trust identity

A checking result must not be identified by its listed ingredients alone. At run time the checker resolves every name through a fixed lookup chain — lexical bindings, program store, primitives, then the composition-supplied module registry (`resolve-var` in `src/cljc/yin/vm/engine.cljc`) — and a host module is a declared boundary, not a content-addressed one: registration checks the profile, not the code, and a host module's address is nil because the host is where the code already is (`src/cljc/yin/vm/module.cljc`). Two runs can therefore present identical listed result identifiers while executing different trusted operation implementations.

Every checking result therefore records a **checking configuration**:

- The compiled kernel artifact, compiler, VM, and data schema versions — content hashes where they exist.
- The closed set of linked modules and their identities: content addresses for fetched code, and for host modules an explicit **host attestation** — module name, exported symbols, profile records, and the host implementation version the publishing composition answers for. Where content addressing is unavailable, the attestation and version are the identity.
- The allowed primitive implementations and the checker's initial bindings and store snapshot: every layer of the `resolve-var` chain, pinned.
- The frozen specification version (Gate S).

Rules:

- The configuration is part of result and cache identity; results and cache entries are shared only between runs with the same configuration.
- During a check, the configuration is frozen: no registry extension, primitive redefinition, or outside store mutation. A change attempt is refused, and any dynamic extension afterwards mints a *new* configuration whose results do not mix with the old one's.
- On resume (Phase 6), the stored configuration is re-verified before checking continues; a mismatch is a refusal, not a merge.

**Important subtlety:** The kernel can be written in Clojure without its logic being *Clojure's* semantics. Clojure supplies the implementation; a formal CIC specification supplies the type checking and equality rules — the one fixed by Gate S before Phase 1.

## 6. Kernel data model

Define tagged logical constructors, projected onto the universal graph/tuple model:

```text
:cic/bvar   ; locally bound variable; de Bruijn index
:cic/sort   ; Sort u, where u is a universe level
:cic/const  ; checked global declaration and universe instantiation
:cic/app    ; function application
:cic/lam    ; lambda with explicit binder type
:cic/pi     ; dependent function type
:cic/let    ; local definition
```

These are **logical term tags** and may be represented as datom attributes/relationships in the existing UAST. Do not confuse them with Yin's runtime instruction opcodes, even when both use the same data substrate. A translation/projection can use common interned nodes when semantics permit.

Universe levels need a separate immutable grammar (e.g. zero, successor, max, imax, parameter). Proof checking contexts and global declaration environments must also be immutable.

Use de Bruijn indices or an equivalent rigorously defined binding scheme. Source-level names are metadata; alpha-equivalent terms must not be distinguished merely because binder names differ.

## 7. Implementation phases

### Phase 0 — Yang/Yin viability spike

**Deliverables**

- Source-to-UAST-to-Yin working pipeline for a small recursive Clojure program.
- A conformance suite comparing direct Clojure execution with compiled Yin execution.
- Inventory of runtime primitives, including closed lexical environments, continuations, collection operations, and host access.
- Profile of closure invocation and tree traversal.
- The section 4 capability matrix, filled in for all three hosts (JVM, Node, Dart).

**Acceptance:** The same source program has the same outputs on the reference Clojure host and Yin under documented deterministic semantics, on all three host lanes (JVM, Node, Dart). A lane that cannot run the spike is recorded in the capability matrix as a blocker with an owner; Phase 0 does not count as proving portability until it clears.

### Phase 0.5 — Specification gate (Gate S)

Phase 1 and Phase 2 may not begin until the v1 logical specification exists as a written, reviewed document (to be created as `docs/design/yin.cic.spec.md`; until it exists, no typing-rule code may land). The gate freezes, for v1:

- **Sorts and universes:** the admitted sorts, the universe level grammar, and their constraints.
- **Typing judgments:** variable, sort, Pi formation, lambda, application, and let.
- **Conversion judgments:** exactly which reductions v1 admits — beta, zeta, and delta over transparent checked constants. Iota joins the specification only at Phase 4; eta and definitional proof irrelevance are not in v1.
- **Declaration admissibility:** the shapes a global declaration may take (definition, theorem, axiom) and the internal validation `add-checked-declaration` performs on each.
- **Axiom policy:** which axioms are admissible, how they are marked in the environment, and the rule that no axiom is ever silently imported or treated as proved.

**v1 theory decision (recorded 2026-10-28):** the minimal predicative core — `Prop` is the base sort of a predicative universe hierarchy, with no impredicative quantification into `Prop`. A minimal predicative theory and Lean-like `Prop` semantics do not have interchangeable sort, Pi, or conversion rules, so one must be fixed before any typing milestone can mean anything; this choice makes the first vertical slice (`(P : Prop) → P → P`) well-defined without committing to Lean's full semantics. Impredicative `Prop`, `imax`, definitional proof irrelevance, and eta are **spec extensions**: each is versioned, and each enters only where Phase 7 Lean corpora demand it.

A specification change mints a new spec version: it changes the checking-configuration identity (section 5) and invalidates previously issued checking artifacts.

**Gate S is a specification gate, not formal verification.** It freezes a human-reviewed document before typing rules are implemented. Mechanizing the judgments against a model, minimizing trusted primitives, and proving metatheoretic properties remain the long-term confidence work of section 9; nothing in this gate claims them.

### Phase 1 — Terms, universes, and environments

**Implement** `yin.cic.term`, `yin.cic.level`, `yin.cic.context`, and `yin.cic.environment`.

- Tagged term constructors and projections.
- Structural validation and canonicalization.
- De Bruijn shifting/substitution as a reference implementation.
- Immutable local typing contexts.
- Trusted global environment with checked declarations only.
- Universe level normalization/comparison.

**Acceptance:** Terms round-trip through the datom/UAST projection, preserve binding, and cannot reference invalid environment versions.

### Phase 2 — Minimal dependent typing

**Implement** `infer`, `check`, and the sort/Pi/lambda/application typing rules.

Conceptual API (not existing public API):

```clojure
(cic/infer checked-env local-ctx term)
(cic/check checked-env local-ctx term expected-type)
(cic/check-declaration checked-env declaration)
```

First vertical slice: check the fully explicit proof term corresponding to:

```lean
theorem identity : (P : Prop) → P → P :=
  fun P h => h
```

Also reject ill-typed variants and terms containing unsolved metavariables.

**Acceptance:** The dependent identity proof is accepted and malformed variants are rejected — each judged against exactly the judgments the frozen Gate S specification defines, with rejections citing the judgment violated. This is not yet a complete CIC kernel; acceptance covers the v1 spec fragment and nothing beyond it.

### Phase 3 — Definitional equality with Yin closure reuse

**Implement** `whnf`, `defeq?`, and later readback/normalization.

- Beta reduction of closed and open terms.
- Zeta reduction for let bindings.
- Delta unfolding of appropriately transparent *checked* definitions.
- Neutral values for variables and stuck applications.
- Optional normalization by evaluation, using Yin's closure/environment machinery where feasible.
- Sound memoization keyed to **every judgment input**: the compared terms, the local typing context including its local definitions, the semantic substitution environment for open terms, the global environment identity, and the reduction policy (transparency and admitted unfoldings). Identical de Bruijn syntax under different local definitions can reduce differently, so term syntax plus global environment alone is not a sound key. In v1, conversion caching is restricted to closed terms under a pinned global environment and reduction policy; caching open-term results additionally requires the full local context and semantic environment in the key.

**Crucial qualification:** An ordinary Yin runtime closure is not automatically an adequate CIC semantic value, especially for open terms. CIC conversion may require *neutral terms, quoting/readback, universe reasoning, and controlled reduction*. Reusing the VM's closure machinery is a goal; implementing the minimal logical conversion layer is unavoidable.

Continuation suspension can optimize checking workloads, but arbitrary continuation jumps do not become new definitional equalities.

**Acceptance:** Beta/let/transparent constant equality matches the logical specification and the supported Lean reference fragment, including negative cases.

### Phase 4 — Inductive declarations and recursors

**Implement**:

- Checks for well-formed constructor types and strict positivity.
- Universe restrictions and safe eliminators.
- Primitive recursor generation or validation.
- Iota reduction under definitional equality.

Progression: `False`, `True`, `Nat`, `Eq`, `List`, then indexed inductives. Defer mutual/nested inductives and full Lean special cases.

**Acceptance:** The checker validates natural-number recursion and induction proofs while rejecting negative occurrences and invalid recursors.

### Phase 5 — Native Yin.CIC v1

Add environment admission, theorem metadata, dependencies, and stable proof results:

```clojure
(cic/check-proof checked-env proposition proof-term)
(cic/check-declaration checked-env declaration)
(cic/add-checked-declaration checked-env declaration)
(cic/dependencies checked-env theorem-id)
```

`add-checked-declaration` must internally validate its declaration and must not trust `:verified true` fields supplied by users.

**Acceptance:** A small, axiom-controlled theorem library can be checked from a fixed snapshot entirely by the compiled Yin kernel.

### Phase 6 — Yin-native operational features

After soundness-oriented core testing:

- Checkpoint/resume proof checking using persistent continuations.
- Versioned proof dependencies and queryable provenance in DaoSpace.
- Cache checks by checking-configuration identity (section 5) with sound collision/identity handling.
- Distribute independently checkable declarations.
- Verify cross-host deterministic results and semantic equivalence after migration.

**Acceptance:** Interrupted and uninterrupted checking agree, and a resumed checker cannot silently change its trusted environment, code image, or any other element of its checking configuration — linked modules, primitives, host attestations, or specification version.

### Phase 7 — Lean 4 compatibility

- Import Lean expressions, levels, and declarations as fully explicit CIC-like datom objects.
- Implement the exact kernel features required by each target corpus: universe rules, proof irrelevance, inductive computation, quotients, projections, literals, transparency, etc.
- Differential-test acceptance/rejection against Lean 4 and independent checkers.
- Add performance regression benchmarks based on real exported proofs.

**Acceptance:** Publish a precisely bounded subset of Lean exports that Yin.CIC independently rechecks with matching outcomes. Full Lean compatibility is not assumed.

## 8. Suggested source layout

```text
src/cljc/yin/cic/
  term.cljc          ; logical terms and projections
  level.cljc         ; universe levels and comparison
  context.cljc       ; local dependent contexts
  environment.cljc   ; immutable checked declarations
  reduction.cljc     ; controlled logical reduction
  conversion.cljc    ; definitional equality
  typecheck.cljc     ; infer / check
  inductive.cljc     ; positivity / recursors
  proof.cljc         ; public kernel API
  checkpoint.cljc    ; suspension (later)
  lean_import.cljc   ; interoperability (later)

test/yin/cic/
  yang_conformance_test.cljc
  typing_test.cljc
  conversion_test.cljc
  inductive_test.cljc
  adversarial_test.cljc
  differential_test.cljc
```

The modules should be valid **Clojure source** and also compile into Yin programs. Where Yang supports only a Clojure subset, write against that subset or add the missing Yang lowering rather than bypassing Yin with host callbacks.

## 9. Acceptance and security tests

### Validity

- Identity theorem and Pi/application typing.
- Correct universe constraints.
- Valid inductive declarations and recursor computation.
- Beta, delta, zeta, iota and any admitted eta/proof-irrelevance behavior.
- Environment/version consistency.

### Rejection and failure cases

- Ill-typed terms and dangling de Bruijn indices.
- Unsolved metavariables in trusted proof objects.
- Invalid universe constraints or impredicative/type paradoxes.
- Unchecked axioms and unchecked imported declarations.
- Negative/non-strictly-positive inductive definitions.
- Unrestricted fixed points masquerading as total logical terms.
- Arbitrary host effects or continuation manipulation influencing the conversion relation.
- Resource exhaustion misreported as valid proof.
- Cache poisoning, stale proof dependencies, inconsistent code hashes.

### Equivalence and external checking

- Direct Clojure source execution vs Yang→Yin execution.
- Tri-host agreement — JVM, Node, and Dart — as `docs/agents/build-n-test.md` requires at every gate from G1 up. Portable release acceptance requires all three lanes green; an open Dart blocker is recorded in the Phase 0 capability matrix, and until it clears, portability claims are limited to the hosts actually verified.
- Suspend/resume vs uninterrupted checking.
- Positive and negative exported Lean proof cases on the shared fragment.

No finite test suite proves consistency. Gate S freezes the specification before typing is implemented; that is a specification gate, not a proof. Later, and separately, long-term confidence work includes mechanizing the typing/conversion judgments against a model, minimizing trusted primitives, and independent implementations. Until that work exists, the checker's authority rests on the frozen spec, the audited checking configuration of section 5, and the cross-checks above.

## 10. What to defer

Do not build tactics, an elaborator, general metavariable unification, theorem search, an LLM proof generator, IDE tooling, or unrestricted host FFI into v1 of the trusted checker. Those can be ordinary Yin programs that produce *candidate* CIC terms.

Distinguish **Yin's unification of functions/closures/continuations** (runtime computation) from **unification of metavariables** (elaboration). The latter belongs outside the kernel.

## 11. First sprint / vertical slice

1. Pick a tiny source subset supported by `yang.clojure`: `fn`, application, `if`, `let`, and deterministic data projections.
2. Compile and execute a recursive AST walk on Yin.
3. Define a minimal immutable CIC term schema (bvar, sort, pi, lam, app).
4. Implement Pi type checking with a simple, correct reference substitution function.
5. Check `(P : Prop) → P → P` using `fun P h => h`.
6. Add five invalid proof terms and require precise rejection.
7. Run the *same Clojure implementation* both directly and after Yang compilation.
8. Record the full checking configuration (section 5) in the test result: compiler/runtime versions, code hash, environment snapshot, linked modules and host attestations, and specification version.

**Definition of done:** The first theorem is checked by CIC logic written in Clojure and executed as compiled Yin code, with no host-side fallback that secretly performs the proof check — judged against the frozen Gate S specification.

## 12. Decisions requiring explicit specification

- Which subset of Clojure is reliably compilable today?
- Is the canonical executable representation UAST datoms, an interned code-row lowering, or both with a defined mapping? (Treat executable code rows vs ledger datoms precisely.)
- How exactly does Yin's unified function/closure/continuation representation bind environments and carry open terms?
- Which logical primitive types are admissible and what are their trusted runtime implementations?
- Universe theory for v1 — resolved 2026-10-28: the minimal predicative core, fixed by the Gate S specification (section 7); Lean-like `Prop` semantics (impredicativity, proof irrelevance, eta, `imax`) are versioned spec extensions at Phase 7.
- What counts as transparent for delta-reduction, and how is recursion admitted?
- How will compiler/VM semantic changes invalidate previous checking artifacts? The mechanism is checking-configuration identity (section 5); the residual decision is the reissue policy for results issued under an older configuration.
- Does a proof result certify only a CIC proposition, or additionally establish a theorem about Yin program execution? (The latter requires a proven correspondence.)

## 13. Source references

- Datom.World, **Yang Compiler**: https://datom.world/yang.chp
- Datom.World, **Yin VM**: https://datom.world/yin.chp
- Datom.World, **FAQ**: https://datom.world/faq.chp
- Datom.World repository: https://github.com/sonwh98/datomworld
- Lean reference, **Elaboration and Compilation / Kernel**: https://lean-lang.org/doc/reference/latest/Elaboration-and-Compilation/
- Lean reference, **Type System**: https://lean-lang.org/doc/reference/latest/The-Type-System/
- Type Checking in Lean 4, **Trust**: https://ammkrn.github.io/type_checking_in_lean4/trust/trust.html

---

**Bottom line:** Write the checker in Clojure, compile it using Yang, and run it as a Yin program. Keep the logical checker and the proof term semantics explicit; freeze the trusted code image and environment; audit the Yang/Yin trust boundary; and validate the design with a minimal dependent identity proof before tackling Lean compatibility.
