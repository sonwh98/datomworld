
---
description: Mathematical notation as a versioned stream frontend producing explicit CIC term rows, with preserved source, reproducible interpretation, and a separate proof-checking boundary.
---

# yang.tao — Mathematical notation as an interpreted frontend

**Status:** Proposed design, 2026-10-28. No implementation or completed acceptance gate is claimed.

**Authority:** Subordinate to [Datom.World’s foundations](./datom.world.md), the [Yin.CIC implementation plan](./yang.cic.md), and the existing row, execution, and stream contracts.

## 1. Decision

Design `yang.tao` as a mathematical-notation frontend whose output participates in Yin’s universal representation.

Its pipeline is:

**Tao source → parser → form rows → elaboration observer → explicit logical term rows → CIC checker observer → checking result.**

The v1 elaborator is deliberately small: it resolves explicit names and binding structure and lowers a closed notation into fully explicit terms. It performs no implicit-argument synthesis, overload resolution, coercion search, tactic execution, or proof search.

Mathematical notation acquires no privileged status in this pipeline. It is data interpreted under a named, versioned configuration.

Two meanings of “produces UAST” must remain distinct:

- The frontend produces logical CIC terms represented as data in the universal row substrate.
- The parser, elaborator, checker, and any execution adapter are programs. When compiled for Yin, their executable UAST follows the existing VM projection and segment contracts.

A mathematical lambda does not become a Yin runtime closure merely because both representations use binding. A mathematical application does not authorize an arbitrary VM call.

This is a frontend undertaking of the same engineering class as `yang.typescript`: a specified grammar, parser integration, lowering, provenance, diagnostics, and conformance fixtures. It is not a claim of equal implementation cost, nor a proposal to reproduce Lean’s elaborator within that budget.

## 2. Derivation from the foundations

The axioms determine the interpretation boundaries. They do not uniquely determine a notation, parser algorithm, or user interface. Those are decisions constrained by the boundaries.

### 2.1 Interpretation is necessary, including for mathematics

The governing axiom states:

> **Interpretation Creates Semantics**: Data is syntax; semantics only emerge through interpretation (one truth, many perspectives).

Paper notation therefore has no exemption. A trained human reader supplies conventions about application, scope, precedence, omitted arguments, and mathematical context. A software frontend must supply an explicit interpreter for the conventions it accepts.

Consequently:

1. Source bytes alone do not identify the interpreted statement.
2. The grammar, name environment, and elaborator configuration participate in interpretation.
3. An interpretation result must name both its input and the interpreter that produced it.
4. Different frontends may interpret different surfaces into the same explicit term vocabulary.

The need for an interpretation identity follows from the axiom; the particular closed notation selected for v1 is a product decision.

### 2.2 Streams determine the composition boundary

The governing axiom states:

> **Everything is a Stream**: Portable I/O and system data flow through append-only streams; hosts may also install explicit local functions that perform direct I/O.

The elaborator is therefore an observer of a medium: form rows in, explicit term rows out. The checker observes candidate term rows and emits checking results. Neither requires a callback into its producer.

The associated invariant is exact:

> Do not introduce callbacks; every callback is an event on a stream.

Dependencies between stages are represented by input records and their addresses. Requests to another service, if introduced later, must have explicit request and response events. Internal function calls implementing a stage do not require a stream per function invocation.

This is an application of the existing composition doctrine, not a new orchestration framework.

### 2.3 Universal data makes inspection and interoperability possible

The governing axiom states:

> **Code and State are Datoms**: Code (Universal AST) and runtime execution state are structured datoms, enabling syntax independence and structural reflection.

Therefore notation, interpreted terms, interpreter state, and evidence must have explicit data representations. A frontend must not leave semantic decisions solely inside parser objects, host closures, or mutable process state.

The current content/ref/query distinction still applies. Immutable row content carries the syntax or term. Datoms and occurrence records describe its origin, history, and interpretation. Optional indexes expose views; they do not determine the meaning of the term or perform its proof check.

The invariant:

> Do not assume graphs: graphs must be constructed explicitly from tuples.

requires each consumer to validate and construct the relationships it uses. Receiving addresses is not evidence that children exist, scopes are valid, or a graph is admissible.

### 2.4 Continuations constrain state without requiring immediate migration support

The governing axiom states:

> **Everything is a Continuation**: Computation is modeled as serializable, portable continuations that can pause, travel across streams, and resume anywhere.

Interpreter progress must therefore be explicit: selected input, lexical context, environment snapshot, configuration, and pending work cannot be hidden in ambient state.

The related invariants are:

> Do not introduce hidden global state.

> Do not introduce shared mutable state.

> Do not introduce implicit control flow.

V1 may execute each bounded interpretation request to completion. General checkpoint/resume remains later work. A host-specific parser is explicitly local; its opaque host objects are not claimed to be portable continuations. A later portable checkpoint must use the established continuation contracts or restart from retained input under the same configuration.

### 2.5 Separation and retention follow directly

The decisive boundary invariant states:

> Do not collapse interpretation and execution into the same layer.

Tao consequently has no evaluator instruction that interprets mathematical notation on demand. The checker does not ask the frontend to resolve a name, fill a hole, or reinterpret an operator. Complete candidate terms cross the boundary before checking begins.

The retention invariant states:

> Do not lose information (unitarity): streams are append-only and no datom is destroyed, only reinterpreted. The Universal AST is never lost: it is always retrievable wherever code is lowered, by address or by a query over its datoms. Every derived form (semantic vector, native images, indexes) is an interpretation of it and may drop facts only because the AST remains.

Tao extends that retention obligation to written notation: original source bytes and source occurrences remain retrievable after parsing and elaboration. Lowering may omit parentheses, names, or comments from logical term content only because their source representation and provenance remain available.

A content hash is an identity, not a retention mechanism. Publication must retain the referenced artifacts, not merely their hashes.

## 3. The notation tradeoff: Lean, closed grammars, and s-expressions

The design tradeoff is:

**Mathematical surface / community-extensible syntax / closed effective grammar — pick two.**

This is an engineering constraint, not an impossibility theorem. “Community-extensible syntax” here means that ordinary libraries may introduce new concrete notation, fixity, binding forms, or interpretation procedures beyond a frozen profile. A fixed extension mechanism can support bounded combinations, but its effective notation environment still becomes an interpretation input.

| Choice | Benefit | Cost |
|---|---|---|
| Mathematical surface and community extensibility | Libraries can express domain conventions naturally | The effective grammar and interpretation environment evolve |
| Mathematical surface and a closed grammar | Parsing and lowering can be frozen and audited | New conventions require a new profile or frontend |
| A fixed s-expression reader and programmable extensions | Structural parsing stays simple | Users accept a surface farther from ordinary written mathematics |

Lean deliberately invests in the first choice. Its parser, macros, elaborators, and tactics are extensible, and much of the system is implemented in Lean itself. This gives community authors access to the same machinery used by built-in language features. That is a substantive usability and ecosystem advantage. [Lean 4 system description](https://lean-lang.org/papers/lean4.pdf), [Lean elaborator reference](https://lean-lang.org/doc/reference/latest/Notations-and-Macros/Elaborators/).

Datom.World’s contrary commitment concerns this integrated meta-language layer: Tao interpretation must remain an explicitly configured observer, with its decisions materialized before another stage consumes them. It does not follow that an interpreter must be written in a different implementation language. Self-hosting alone is not layer collapse; implicit re-entry, ambient extension, and blurred authority boundaries are the concerns.

Lean’s **kernel boundary agrees with the separation required here**. Its elaborator produces core terms that a small kernel checks; elaboration and tactics are outside that logical checking boundary. Tao must not claim stronger kernel separation merely because it uses streams. [Lean elaboration and kernel reference](https://lean-lang.org/doc/reference/latest/Elaboration-and-Compilation/).

ACL2 supplies the s-expression corner’s natural experiment: a Common Lisp-based system can support substantial verification work without adopting ordinary mathematical surface notation. Its industrial achievements must not be discounted. The concern about near-zero uptake among mainstream mathematicians is an adoption hypothesis for this design, not a measured census or proof that syntax caused the adoption pattern. [Kaufmann and Moore, ACL2 system description](https://www.cs.utexas.edu/~moore/publications/km97.pdf).

Tao v1 selects mathematical notation within a **closed, explicitly bounded grammar**. Community contributions may improve the implementation or propose a new grammar version. A source file cannot extend the active grammar while being interpreted.

Unicode is table stakes. Mathlib already uses mathematical Unicode extensively and documents its use. Tao’s hard work is fixity, precedence, binding structure, and eventually the conventions engine—not displaying Greek letters. [Mathlib style guidelines](https://leanprover-community.github.io/contribute/style.html).

## 4. Pipeline and authority

| Stage | Input | Output | Authority |
|---|---|---|---|
| Source admission | Source bytes and explicit request profile | Retained source artifact and request record | Validates encoding and request shape |
| Parsing | Source artifact and pinned grammar/parser | Form-row packet and occurrence mapping | Determines syntactic structure |
| Elaboration | Form rows, explicit context, pinned interpretation configuration | Fully explicit candidate term rows and interpretation evidence | Resolves the admitted notation |
| CIC checking | Candidate proposition/proof, checked environment, checking configuration | Accept, Reject, or Unknown | Judges terms under the frozen logical specification |
| Declaration admission | Candidate declaration and required checking inputs | New checked environment snapshot or refusal | Performs the kernel’s admission procedure |

Each emitted artifact and each derivation record is content-addressed. A transport cursor or retry identifier is an occurrence coordinate, not the content identity of a term.

A v1 check request explicitly selects the proposition root and proof root. These may originate in separate source units. Their roles cannot be inferred from arrival order or from whichever expression a producer most recently emitted.

Successful elaboration means only that a fully explicit candidate was produced. It is not proof acceptance. A producer-supplied “checked” flag has no authority.

The topology follows [row-native macro expansion](./yin.vm.macro.md): transformation between media, ordinary data at boundaries, provenance beside content, and no transformation machinery inside the evaluator. Tao does not automatically reuse the macro expander’s grammar, store, fixpoint rules, or event schema; those contracts address a different transformation.

Observers remain independently composable. An indexer, renderer, audit process, and checker may consume the same candidate medium without depending on one another.

## 5. UAST, logical terms, and executable segments

The [CIC plan](./yang.cic.md) distinguishes the checker implementation from its logical input. Tao preserves that distinction.

### 5.1 Form rows

Form rows represent Tao syntax: identifiers, grouping, binders, application, and the other admitted constructs. Their schema is versioned and passive. Receiving a form packet cannot execute it.

The concrete row schema is an implementation deliverable. Existing tuple and encoding conventions must be reused where applicable. An Tao syntax tag is not permission to add a new runtime UAST opcode.

### 5.2 Logical term rows

Elaboration emits the CIC vocabulary admitted by Gate S: bound variables, sorts, checked constants with explicit universe instantiations, application, typed lambdas, dependent products, and typed local definitions.

Binder names become occurrence information. Logical binding uses the binding representation fixed by the CIC specification, with the current plan selecting de Bruijn indices.

Term content is not normalized merely to improve hash sharing. Structural identity and logical definitional equality remain different relations. Any term canonicalization must be specified and tested independently of proof checking.

### 5.3 Executable UAST

When a frontend integration requires executable UAST, it may emit an ordinary adapter that constructs or submits the candidate data through established interfaces. This adapter uses existing runtime vocabulary. It does not execute the mathematical term as unrestricted guest code.

For executable programs, [the semantic VM contract](./yin.vm.semantic.md) remains authoritative: the canonical register-shaped segment address **A** is the executable code identity. AST row addresses, logical term roots, and source artifact addresses must not be relabeled as A.

No Tao-specific instruction, evaluator mode, or alternative segment identity is introduced.

## 6. V1 notation and elaboration contract

V1 is **term-mode, closed-notation, and explicit-binder**.

It accepts one term per source unit, under an explicitly supplied context and checked declaration environment. A surrounding request may associate units as a proposition and its proof. A document language, theorem-command language, or interactive proof script is not required.

### 6.1 Admitted forms

| Form | V1 interpretation |
|---|---|
| Parenthesized expression | Explicit grouping |
| Local identifier | Lexically resolved bound variable |
| Qualified global identifier | One declaration in the supplied checked environment |
| Sort with explicit level information | The sort vocabulary frozen by Gate S |
| Application | Explicit function and explicit arguments |
| Typed lambda, using λ | Lambda with a written binder type |
| Typed dependent product, using Π | Dependent function type |
| Arrow, using → | Nondependent product |
| Typed local definition | Explicit let binding with declared type, value, and body |

Lambda and product binder types are mandatory. Constant universe instantiations are explicit where the declaration requires them. There are no holes or unsolved metavariables in accepted elaboration output.

The first mathematical example is dependent identity: the proposition \(\Pi(P:\mathrm{Prop}).\,P\to P\), with the explicit proof \(\lambda(P:\mathrm{Prop}).\,\lambda(h:P).\,h\). Its validity comes from Gate S’s predicative theory, not from resemblance to Lean notation.

### 6.2 Precedence and binding

The binding decisions are:

- Application binds more tightly than the arrow and associates left.
- The arrow associates right.
- Lambda, product, and let bodies extend over the following expression within their enclosing delimiter.
- A binder enters scope in its body and subsequent nested binders, not in its own type annotation.
- A nonrecursive let binder is absent from its initializer and present in its body.
- Shadowing resolves to the nearest lexical binder and remains visible in the binding audit.
- Juxtaposition means application; it never guesses multiplication.
- An identifier containing a subscript character is an identifier, not implicit indexing.
- No type-directed reparsing changes the parse tree.

The notation profile must freeze token boundaries, punctuation, comments, qualified-name syntax, universe spelling, and any ASCII aliases before parser acceptance. These choices cannot remain host-library defaults.

### 6.3 Encoding and names

Source is UTF-8. Invalid encoding is rejected with a source-positioned diagnostic.

The notation profile pins Unicode handling and the identifier repertoire. Identifier normalization is explicit: v1 requires the selected normalized spelling and rejects nonconforming identifiers instead of silently changing them. Original bytes remain retained even on rejection.

Invisible or bidirectional formatting controls are not accepted as ordinary mathematical identifiers. Diagnostics must expose the relevant code points when a spelling is rejected or visually ambiguous.

Global resolution uses the supplied environment and explicit qualification. There is no ambient host namespace, filesystem search, automatic import, or fallback to a VM primitive with a matching name.

### 6.4 Explicitly deferred conventions

V1 does not interpret overloaded arithmetic, implicit arguments, coercions, type classes, inferred universes, metavariable unification, tactic blocks, simplifier automation, theorem search, omitted binder types, or library-defined fixity.

Integrals, summations, fractions, set-builder notation, and other domain notation enter only through separately specified mappings. A familiar glyph does not establish such a mapping.

These facilities belong in later elaboration programs. The architecture supplies their boundaries and evidence channels; it does not supply their algorithms.

## 7. Gate S and the CIC dependency

The [CIC plan’s section 10](./yang.cic.md) keeps elaboration, tactics, and metavariable unification outside the trusted checker. Tao does not reopen that boundary.

Gate S freezes the logical theory before typing implementation. The current plan chooses a minimal predicative core. Impredicative Prop, definitional proof irrelevance, eta, and other Lean-specific extensions cannot enter through notation.

At this design review, `docs/design/yin.cic.spec.md` is absent. Therefore:

- Source retention, notation design, parser fixtures, and form-row infrastructure may proceed.
- Term lowering must target a reviewed, versioned logical schema.
- Typing-rule implementation and claims that Tao proofs are checked remain gated by Gate S and the CIC milestones.
- A parser demonstration is not a completed theorem-checking vertical slice.

The grammar may give a pleasant spelling to an admitted constructor. It may not change that constructor’s typing or conversion rule.

The existing Yang→Yin conformance work remains a prerequisite wherever the elaborator or checker is implemented through `yang.clojure`. Tao neither bypasses compiler gaps with hidden host proof checking nor treats an earlier frontend sign-off as evidence that the CIC checker is complete.

## 8. Reproducible interpretation and checking configuration

The [CIC checking configuration](./yang.cic.md) already pins the kernel, runtime, linked modules, primitive implementations, initial bindings/store, host attestations, and logical specification.

An Tao checking configuration extends this with an **interpretation descriptor** containing:

- Frontend and notation-profile identity.
- Form and logical-term schema identities.
- Parser and elaborator implementation identities.
- Compiler/runtime identities for executed interpretation programs.
- Unicode, tokenization, precedence, and normalization policies.
- The complete name-resolution environment and declaration snapshot.
- The closed set of interpretation dependencies, primitives, and initial state.
- Explicit host attestations wherever implementation content addresses are unavailable.
- Deterministic resource limits and operational policy relevant to the recorded outcome.

A result binds the source artifact, selected source occurrences, form roots, explicit proposition/proof roots, interpretation descriptor, and CIC checking configuration.

Every stage records a derivation from named input artifacts to named outputs. Where no transformation exists, that fact is explicit; an invented intermediate “adapted source” is unnecessary.

Configurations are frozen for an interpretation/checking attempt. A grammar extension, imported environment change, primitive replacement, or altered interpretation policy creates a new configuration. Resumption must verify the stored configuration before proceeding.

Cache identity includes the inputs and configuration of the cached stage. An earlier source-level receipt cannot certify a new interpretation merely because the final term happens to share an address. Any reuse of a narrower kernel result must remain distinguishable from evidence connecting written notation to that term.

Addresses establish content identity. They do not prove that the implementation is correct, that a host attestation is trustworthy, or that artifacts remain available.

## 9. Unitarity and provenance

The retained evidence chain is:

**source occurrence → parsed form occurrence → interpretation event → logical term occurrence → checking result.**

The source artifact preserves exact bytes, including comments and whitespace. Source spans use a fixed coordinate convention, with byte offsets as the portable basis and display coordinates derived from retained text.

Occurrence identity includes origin, root, and structural path. A content address alone is insufficient: repeated identical subexpressions can have different source locations and binding contexts.

Interpretation evidence records:

- Resolved binding targets.
- Global declaration identities and universe arguments.
- Fixed syntactic expansions, including arrow-to-product lowering.
- The source origin of generated nodes.
- Input/output roots and the configuration responsible for the transformation.

These are side records. Source positions, histories, and audit annotations do not become runtime code slots or logical term fields solely to make them convenient to query.

Where a fact can be derived reliably from retained artifacts, derive it. Persist the nonrecoverable inputs and transformation decisions needed for explanation and replay.

An edit appends a new source and interpretation history. It does not overwrite the notation previously associated with a checking result.

## 10. Interpretation fidelity is a separate trust channel

Kernel soundness answers:

**Does this explicit proof establish this explicit proposition under this logical specification and checked environment?**

Interpretation fidelity answers:

**Does this explicit proposition represent the statement expressed by this source under the intended notation and context?**

A wrong elaborator can consistently turn a written statement into a different, provable statement. The kernel can correctly accept the resulting proof. Kernel acceptance therefore cannot certify interpretation fidelity.

V1 fences this channel in six ways:

1. **Closed interpretation.** No source-defined syntax extensions or executable notation hooks.
2. **Explicit inputs.** All binders, arguments, universes, and global resolution inputs are supplied.
3. **Inspectable output.** Present a fully explicit statement view with binding targets, declaration identities, universe arguments, and axiom dependencies.
4. **Independent comparison.** Compare lowering against independently constructed expected term rows. Parser/pretty-printer round trips alone are insufficient because both may share a bug.
5. **Source-bound evidence.** Reports distinguish the proposition actually checked from the source and interpreter that produced it.
6. **Independent kernel admission.** The checker validates the candidate regardless of producer claims, and declaration admission performs its own required checks.

The explicit statement renderer is itself an interpretation dependency for human review. Its identity must be recorded when its output is used as audit evidence. It must not hide arguments or restore ambiguous notation by default.

A fixed notation can still be misunderstood. V1 narrows the gap between text and explicit term; neither provenance nor a finite fixture suite proves that a human intended the resulting theorem.

## 11. Effects, failures, and publication

Parsing and v1 elaboration are deterministic over their declared inputs. They perform no ambient imports, time-dependent name generation, network-dependent search, or host callbacks.

Failures remain stage-specific:

- Source admission reports encoding and request defects.
- Parsing reports malformed or unsupported notation.
- Elaboration reports unresolved names, invalid binding structure, missing explicit information, or schema mismatch.
- Checking reports Accept, Reject, or Unknown according to the CIC contract.
- Resource exhaustion or unavailable dependencies never become proof acceptance.

An elaboration failure publishes diagnostic evidence and no successful candidate for that request. Valid prefix progress must not be mistaken for completion.

Only checked declaration admission can advance the checked environment. An elaborator may construct a candidate declaration, but it has no authority to install it as proved or silently add axioms.

Publishing outputs and provenance follows existing staged-flush and retry contracts. Reuse the macro observer’s operational lessons; do not claim atomicity across independent media unless the composition provides it.

## 12. Additive frontends and interoperability

A second frontend, such as `yang.tao2`, may adopt different notation and coexist with `yang.tao`.

Interoperability occurs at the explicit term-row boundary under compatible logical schemas and the same relevant theory/environment identities. It does not require translating one surface language into another.

Two frontends may produce identical term content and different source/provenance records. That is expected. They may also produce different terms from visually similar source; their separate interpretation identities make that difference inspectable.

Publishing a new frontend or notation profile does not mutate an existing profile. Reinterpreting old source under the new profile produces new evidence. A consumer explicitly chooses which interpretation it accepts.

This makes notation evolution additive without pretending that unrestricted syntax extension is free.

## 13. Implementation sequence and acceptance

| Gate | Deliverable | Acceptance |
|---|---|---|
| E0 — Notation freeze | Versioned supported syntax, precedence, binding, encoding, and refusal rules | Every advertised construct has positive and negative fixtures; no unresolved parsing convention |
| E1 — Source and form rows | Parser integration, retained source, validated form packets, occurrence mapping | Exact source recovery; deterministic parsing; malformed input never succeeds through silent recovery |
| E2 — Explicit lowering | Name resolution and structural elaboration against the approved CIC schema | Expected term rows match independent fixtures; capture, shadowing, application order, and binder depth are correct |
| E3 — Checking composition | Candidate medium connected to the CIC checker under pinned configurations | Dependent identity accepted and specified invalid variants rejected, after Gate S and the required CIC milestones |
| E4 — Reproducibility and interoperability | Replay, evidence verification, cross-host matrix, alternate-surface fixtures | Stable artifacts and outcomes under identical inputs/configurations; altered configurations cannot reuse source-level certification |

The corpus must cover:

- Application associativity and arrow precedence.
- Parentheses that change meaning and parentheses that merely disambiguate.
- Nested binders, shadowing, alpha-renaming, and escaping variables.
- Explicit universe arguments and environment mismatches.
- Unicode identifiers, token boundaries, invalid encoding, and normalization refusals.
- Unsupported overloads, omitted arguments, holes, and notation extensions.
- Repeated identical subterms with distinct occurrence provenance.
- Missing referenced rows, malformed graph structure, and forged producer verdicts.
- Resource exhaustion and interrupted publication.
- Different source spellings that intentionally elaborate to the same term.
- Identical source under different interpretation configurations.

Record exact parser, compiler, VM, schema, dependency, and theory identities with the evidence.

Follow the `yang.typescript` distinction between source-level and packet-level portability. If a parser is available only on one host, record that limitation. Form-row consumption, term lowering, and compiled execution must have their own JVM/Node/Dart matrix. A portable release requires the promised lanes to pass; receiving pre-parsed rows does not establish local parser support.

Lean may supply an external oracle only for the explicitly shared logical fragment. Its full Prop and conversion semantics are not the v1 CIC specification.

## 14. Binding decisions, implementation choices, and risks

### 14.1 Binding decisions

- Closed, versioned notation in v1.
- Explicit binders, arguments, universe information, and resolution environments.
- Separate parser, elaboration, and checking boundaries.
- Logical terms remain data; no mathematical notation introduces VM authority.
- Source and interpretation provenance remain retrievable.
- Interpretation identity accompanies checking identity.
- No implicit notation extension, proof search, or host proof-checking fallback.
- Gate S governs logical meaning.
- New frontends coexist through row-level interoperability.

### 14.2 Implementer choices

The implementer may choose a Pratt parser, generated parser, or another bounded parsing method, provided the frozen grammar and fixtures determine the same result.

Internal syntax objects, helper names, file layout, traversal strategy, and indexing structures are implementation choices. Their observable effects on addresses, binding, diagnostics, or provenance are not.

The notation profile must settle remaining concrete spellings before E0 closes. Wire schemas and event layouts require a focused review against existing row and stream contracts; this note does not add speculative slots to canonical VM structures.

General elaboration, tactics, domain notation, IDE assistance, and checkpoint migration remain separate designs.

### 14.3 Risk register

| Risk | Required control |
|---|---|
| A valid proof establishes the wrong interpreted statement | Independent lowering fixtures, explicit statement inspection, source-bound interpretation evidence |
| Binder capture or precedence mistakes silently change meaning | Frozen binding rules and adversarial structural fixtures |
| Familiar notation suggests unsupported logical rules | Gate S governs semantics; unsupported conventions are refused |
| Runtime code is confused with logical terms | Separate schemas, admission paths, and executable segment identities |
| Parser or elaborator state changes between runs | Closed configuration, pinned environments, replay and resume checks |
| Hashes survive while source artifacts disappear | Retention obligation for the complete evidence chain |
| A shared parser/renderer bug passes round-trip tests | Independent expected terms and separately audited rendering |
| A small frontend grows into an unbounded conventions engine | Explicit v1 refusals and separately reviewed elaboration milestones |
| Host-specific parsing is advertised as portable interpretation | Separate source, packet, and execution capability matrices |
| Multiple frontends silently disagree about the same notation | Distinct frontend identities and explicit interpretation selection |
