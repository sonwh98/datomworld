# yin.cic.spec: the Gate S v1 specification of the Yin.CIC checker

**Status:** Gate S v1, frozen on review. Specification version string: `yin.cic.spec/v1`.
**Governs:** `docs/design/yang.cic.md` Phases 1 to 3 and 5. Phase 4 (inductives, iota) and Phase 7 (Lean extensions) each mint a new specification version.
**Updated:** 2026-10-29

This document is a specification, not a plan and not a verification. It fixes the logical theory the checker decides. Every rule carries a number. Every example states its expected outcome. A rule change, including a change to an exclusion, mints a new specification version, which changes the checking-configuration identity of the kernel plan §5 and invalidates earlier checking artifacts.

## 0. Reading conventions

- `E` is a global environment, `Γ` a local context, `t`, `u`, `A`, `B` terms, `ℓ` universe levels, `c` constant names, `p` universe parameter names, `i` natural numbers.
- `E; Γ ⊢ J` reads "judgment J holds in environment E and context Γ".
- A rule is written premises above the line, conclusion below. A rule with no premises is an axiom of the judgment.
- `t[u/0]` is substitution of `u` for the bound variable of index 0, with the standard de Bruijn shift. `↑ⁿ t` lifts every free index of `t` by `n`.
- "Reject (R-n)" names the rule whose premise fails; the checker reports that rule. "Reject (X-n)" names an explicit exclusion rule.
- Nothing in this document is Clojure. The kernel is a Yin program whose execution is governed by `yin.vm.semantic.md`; the terms it checks are data under this document. The VM's code identity A names the checker's code and is name-sensitive. Logical identity of a checked term is defined in §2.3 and ignores binder names. The two identities name different objects.

## 1. Universe levels

### 1.1 Grammar

```text
ℓ ::= zero            ; the base level
    | succ ℓ          ; successor
    | max ℓ ℓ         ; least upper bound
    | param p         ; a universe parameter of the enclosing declaration
```

**Rule L-1 (no imax).** `imax` is not a level constructor in v1. A level carrying any constructor outside this grammar is rejected with X-2 (§7). This removes the impredicative rule `imax ℓ zero = zero`; §3 R-PI uses plain `max`.

**Rule L-2 (well-formed level).** Under a declaration with parameter list `ps`:

```text
E; ps ⊢ zero level
E; ps ⊢ ℓ level  ⟹  E; ps ⊢ succ ℓ level
E; ps ⊢ ℓ₁ level, E; ps ⊢ ℓ₂ level  ⟹  E; ps ⊢ max ℓ₁ ℓ₂ level
p ∈ ps  ⟹  E; ps ⊢ param p level
```

A `param p` with `p ∉ ps` is Reject (L-2). Duplicate names in `ps` are Reject (D-1, §6).

### 1.2 Level equality

Levels denote natural numbers under an assignment of parameters to naturals. Two levels are equal when they denote the same natural under every assignment.

**Rule L-3 (normal form).** Every level normalizes to a finite non-empty set of atoms, each atom `(base, k)` with `base ∈ {zero} ∪ {param p}` and `k` a natural offset:

1. `zero` → `{(zero, 0)}`; `param p` → `{(param p, 0)}`.
2. `succ ℓ` → add 1 to the offset of every atom of `ℓ`.
3. `max ℓ₁ ℓ₂` → the union of the two atom sets.
4. Reduce: drop `(b, k)` when `(b, k')` with `k' ≥ k` is present; drop `(zero, k)` when any `(param p, k')` with `k' ≥ k` is present.

**Rule L-4 (decision).** `ℓ₁ ≡ ℓ₂` iff their reduced atom sets are equal. This decision is complete for the grammar of §1.1: an atom `(param p, k)` is bounded under every assignment only by an atom on the same parameter with a greater or equal offset, and `(zero, k)` is bounded by any atom with offset at least `k`.

v1 defines no level ordering `ℓ₁ ≤ ℓ₂` and no cumulativity (§7 X-4). Only equality is used.

**Example L-a.** `max (succ zero) (param u) ≡ max (param u) (succ zero)`: Accept. Both reduce to `{(zero,1), (param u,0)}`.
**Example L-b.** `succ (max zero (param u)) ≡ max (succ zero) (succ (param u))`: Accept. Both reduce to `{(zero,1), (param u,1)}`, then to `{(param u,1)}`.
**Example L-c.** `param u ≡ zero`: Reject (L-4). The assignment `u := 1` separates them.

## 2. Terms, contexts, environments

### 2.1 Term grammar

```text
t ::= bvar i                  ; bound variable, de Bruijn index, 0 innermost
    | sort ℓ                  ; the universe Sort ℓ
    | const c [ℓ₁ … ℓₙ]       ; a checked global constant at a universe instance
    | app t t                 ; application, binary
    | lam A t                 ; λ with explicit binder type A, body t under one binder
    | pi A B                  ; Π with domain A, codomain B under one binder
    | let A t t               ; let with annotation A, value, body under one binder
```

`Prop` is the notation `sort zero`. `Type` is the notation `sort (succ zero)`. Neither is a separate constructor.

**Rule T-1 (closed constructor set).** A term carrying any tag outside this grammar is Reject (X-6). This includes metavariables, free named variables, fixpoints, inductive types, constructors, recursors, projections, literals and quotients.

**Rule T-2 (binder names are metadata).** A `lam`, `pi` or `let` node may carry a source name for its binder. Names take no part in any judgment.

### 2.2 Contexts

```text
Γ ::= ·                       ; empty
    | Γ, A                    ; a variable assumption of type A
    | Γ, A := v               ; a local definition of type A with value v
```

`Γ(i)` is the i-th entry counting from the innermost, and `Γ(i).type` is its type lifted by `i + 1` so that it is well-scoped at the use site. Entries are typed in the context to their left.

### 2.3 Logical identity

**Rule T-3.** Two terms are identical iff they are structurally equal under §2.1 with levels compared by L-4. Binder names are ignored. This is alpha-equivalence by construction and the only syntactic equality the checker uses.

### 2.4 Environments

```text
E ::= ·                       ; empty
    | E, d                    ; a checked declaration appended
```

An environment is an ordered, immutable sequence of checked declarations. Declaration `d` sees exactly the declarations to its left. There is no forward reference and no mutual reference (§4.3 C-7).

## 3. Typing judgments

### 3.1 Judgment forms

| Form | Reads |
|---|---|
| `E ⊢ ok` | environment `E` is well-formed |
| `E; ps; Γ ⊢ ok` | context `Γ` is well-formed under parameters `ps` |
| `E; ps; Γ ⊢ t : T` | `t` has type `T` |
| `E; ps; Γ ⊢ t ⇒ T` | the checker infers `T` for `t` (the algorithmic form of the previous line) |
| `E; ps; Γ ⊢ T type` | `T` is a type, that is `T : sort ℓ` for some `ℓ` |
| `E; ps; Γ ⊢ t ≡ u` | `t` and `u` are definitionally equal (§4) |
| `E; ps; Γ ⊢ t ⇝ u` | `u` is the weak head normal form of `t` (§4) |

The parameter list `ps` is that of the declaration being checked and is constant through a check. It is written where it matters and elided elsewhere.

### 3.2 Well-formedness

**Rule W-1 (empty).** `· ⊢ ok`.
**Rule W-2 (extend environment).** `E ⊢ ok` and `E ⊢ d admissible` (§6) ⟹ `E, d ⊢ ok`.
**Rule W-3 (empty context).** `E; ps; · ⊢ ok`.
**Rule W-4 (assumption).** `E; ps; Γ ⊢ A type` ⟹ `E; ps; Γ, A ⊢ ok`.
**Rule W-5 (local definition).** `E; ps; Γ ⊢ A type` and `E; ps; Γ ⊢ v : A` ⟹ `E; ps; Γ, A := v ⊢ ok`.

### 3.3 Inference rules

These rules are syntax-directed on the term and define `⇒`. The declarative `t : T` holds iff `t ⇒ T'` and `T' ≡ T` (§3.4).

**Rule R-VAR.**
```text
i < |Γ|
────────────────────────────
E; Γ ⊢ bvar i ⇒ Γ(i).type
```
`i ≥ |Γ|` is Reject (R-VAR): a dangling index.

**Rule R-SORT.**
```text
E; ps ⊢ ℓ level
──────────────────────────────
E; Γ ⊢ sort ℓ ⇒ sort (succ ℓ)
```

**Rule R-CONST.**
```text
(c : ∀ qs, T) ∈ E        |qs| = n        E; ps ⊢ ℓⱼ level  for each j
───────────────────────────────────────────────────────────────────
E; Γ ⊢ const c [ℓ₁ … ℓₙ] ⇒ T[ℓ₁/q₁ … ℓₙ/qₙ]
```
`c ∉ E` is Reject (R-CONST, `:unknown-constant`). `|ℓs| ≠ |qs|` is Reject (R-CONST, `:universe-arity`). The substituted type is closed and carries no parameter outside `ps`.

**Rule R-PI (predicative).**
```text
E; Γ ⊢ A ⇒ S₁     S₁ ⇝ sort ℓ₁
E; Γ, A ⊢ B ⇒ S₂  S₂ ⇝ sort ℓ₂
──────────────────────────────────────
E; Γ ⊢ pi A B ⇒ sort (max ℓ₁ ℓ₂)
```
If `S₁` or `S₂` does not reduce to a sort, Reject (R-PI, `:domain-not-type` or `:codomain-not-type`). There is no case in which the codomain's sort alone decides the result. The sort of a Π is never smaller than the sort of its domain.

**Rule R-LAM.**
```text
E; Γ ⊢ A type        E; Γ, A ⊢ b ⇒ B
───────────────────────────────────────
E; Γ ⊢ lam A b ⇒ pi A B
```
`A` not a type is Reject (R-LAM, `:binder-not-type`).

**Rule R-APP.**
```text
E; Γ ⊢ f ⇒ F     F ⇝ pi A B     E; Γ ⊢ a ⇒ A'     E; Γ ⊢ A' ≡ A
──────────────────────────────────────────────────────────────
E; Γ ⊢ app f a ⇒ B[a/0]
```
`F` not reducing to a Π is Reject (R-APP, `:not-a-function`). `A' ≢ A` is Reject (R-APP, `:argument-mismatch`).

**Rule R-LET.**
```text
E; Γ ⊢ A type     E; Γ ⊢ v ⇒ A'     E; Γ ⊢ A' ≡ A     E; Γ, A := v ⊢ b ⇒ B
──────────────────────────────────────────────────────────────────────────
E; Γ ⊢ let A v b ⇒ B[v/0]
```

**Rule R-TYPE.** `E; Γ ⊢ T type` iff `E; Γ ⊢ T ⇒ S` and `S ⇝ sort ℓ` for some `ℓ`.

### 3.4 Checking

**Rule R-CHECK.** `E; Γ ⊢ t : T` iff `E; Γ ⊢ t ⇒ T'` and `E; Γ ⊢ T' ≡ T`. The checker never guesses a type for `t` from `T`; there is no bidirectional refinement in v1. Failure of the conversion is Reject (R-CHECK, `:type-mismatch`) and reports both `T'` and `T` in weak head normal form.

### 3.5 Worked derivation: the identity theorem

Let `T = pi (sort zero) (pi (bvar 0) (bvar 1))` and `t = lam (sort zero) (lam (bvar 0) (bvar 0))`.

Typing `t`:
1. R-SORT: `· ⊢ sort zero ⇒ sort (succ zero)`, so `sort zero` is a type (R-TYPE).
2. Under `Γ₁ = ·, sort zero`: R-VAR gives `bvar 0 ⇒ sort zero`, a type.
3. Under `Γ₂ = Γ₁, bvar 0`: R-VAR gives `bvar 0 ⇒ ↑¹(bvar 0) = bvar 1`.
4. R-LAM twice: `t ⇒ pi (sort zero) (pi (bvar 0) (bvar 1))`, which is `T` by T-3.

**Outcome: `· ⊢ t : T` Accept.**

Typing `T`:
1. Domain `sort zero ⇒ sort (succ zero)`, so `ℓ₁ = succ zero`.
2. Codomain under `Γ₁`: `pi (bvar 0) (bvar 1)`. Its domain `bvar 0 ⇒ sort zero`, `ℓ = zero`; its codomain under `Γ₂`: `bvar 1 ⇒ sort zero`, `ℓ = zero`. R-PI: `⇒ sort (max zero zero)`, which is `≡ sort zero`. So `ℓ₂ = zero`.
3. R-PI: `T ⇒ sort (max (succ zero) zero)`, and `max (succ zero) zero ≡ succ zero` by L-4.

**Outcome: `· ⊢ T : sort (succ zero)` Accept. `· ⊢ T : sort zero` Reject (R-CHECK: `succ zero ≢ zero`).** This pair is the boundary fixture B-1 of Annex A.

## 4. Conversion

### 4.1 Reductions admitted

**Rule C-1 (beta).** `app (lam A b) a ⇝ b[a/0]`.
**Rule C-2 (zeta).** `let A v b ⇝ b[v/0]`.
**Rule C-3 (zeta-local).** Under `Γ` with `Γ(i) = A := v`, `bvar i ⇝ ↑ⁱ⁺¹ v`.
**Rule C-4 (delta).** For `(definition c : ∀ qs, T := v) ∈ E`, `const c [ℓs] ⇝ v[ℓs/qs]`.

No other reduction exists in v1. In particular there is no eta (X-3), no iota (X-6), no proof irrelevance (X-5), and no reduction supplied by the executing VM.

### 4.2 Transparency policy, frozen

**Rule C-5.** A `definition` is transparent: C-4 applies to it unconditionally. A `theorem` is opaque: C-4 never applies to it; two occurrences of the same theorem constant are convertible only by C-9. An `axiom` has no value; C-4 never applies. Per-declaration transparency flags and reducibility hints are a spec extension, not v1.

### 4.3 Recursion and termination, frozen

**Rule C-6.** No term constructor denotes a fixpoint (T-1), and a declaration's value may reference only constants already in `E` (D-2, C-7). Therefore the unfolding relation on constants is well-founded, and v1 conversion is the conversion of a predicative λ-calculus with let and non-recursive constants. Weak head normal forms exist for every well-typed term and `≡` is decidable. Structural recursion, well-founded recursion and guarded recursion are not admitted in v1; a transparent recursive definition cannot be expressed. The admission of recursion is a spec extension that must arrive together with its termination policy.

**Rule C-7 (no self or forward reference).** While checking declaration `d`, the environment is the prefix of `E` strictly before `d`. A `const` naming `d` itself or any later declaration is Reject (R-CONST, `:unknown-constant`).

### 4.4 Weak head normal form

**Rule C-8.** `t ⇝ u` is the result of applying C-1 to C-4 at the head of `t` until no rule applies. The head positions are: the term itself, and the function position of an `app`. Reduction under `lam`, `pi`, `let` binders and in argument positions is not part of whnf. Delta (C-4) applies at the head only when no C-1 to C-3 step applies, and only when the constant is in head position of an application spine or is the term itself. The order of application does not affect the result because the system is confluent on well-typed terms; the implementation may choose any order.

### 4.5 Definitional equality

**Rule C-9.** `E; Γ ⊢ t ≡ u` is the least relation that is reflexive by T-3, symmetric, transitive, closed under C-1 to C-4 in any position, and a congruence for every constructor, with sorts compared by L-4 and constants compared by name and L-4 on their level lists.

The algorithm this document fixes as the reference decision procedure:

1. If `t` and `u` are identical by T-3, Accept.
2. Reduce both to whnf by C-8.
3. Compare heads: `sort` by L-4; `bvar` by index; `const` with the same name by L-4 on levels, then on mismatch unfold either side by C-4 if transparent and retry; `app` spines by head then pairwise arguments by C-9; `lam` and `pi` by domain then body under the extended context; `let` after zeta.
4. Anything else is Reject (C-9), reporting both whnfs.

Step 3 for `const` is the only place where the choice of which side to unfold matters for cost, never for the answer.

### 4.6 Conversion caching

**Rule C-10.** A cache may memoize `t ≡ u` results only for closed `t` and `u` (no free index, no local definition in scope), and only under a key that contains at least: the specification version string, the checking-configuration identity of the kernel plan §5, the canonical encodings of `t` and `u` by T-3, and the parameter list `ps`. The reduction policy is a function of the specification version in v1 and needs no separate key component. A cache keyed on less is a defect, and a cached entry may never turn an `Unknown` into `Accept`. Open-term caching is a spec extension; when it arrives its key must add the full context including local definitions.

### 4.7 Examples

**Example C-a.** `app (lam (sort zero) (bvar 0)) (const P []) ≡ const P []` where `P` is an axiom of type `sort zero`: Accept, by C-1 then T-3.
**Example C-b.** `let (sort zero) (const P []) (bvar 0) ≡ const P []`: Accept, by C-2.
**Example C-c.** With `definition id : pi (sort zero) (pi (bvar 0) (bvar 1)) := t` as in §3.5, `app (app (const id []) (const P [])) (const h [])  ≡ const h []` where `h : const P []` is an axiom: Accept, by C-4 then C-1 twice.
**Example C-d.** Same as C-c but `id` admitted as a `theorem`: Reject (C-9). The constant is opaque, both whnfs are the application spine itself, and `const h []` is not an application. This is boundary fixture B-5.
**Example C-e.** `lam A (app (↑¹ f) (bvar 0)) ≡ f` for a variable `f : pi A B`: Reject (C-9). There is no eta. Boundary fixture B-3.

## 5. Outcomes and resources

**Rule O-1.** A check returns exactly one of `Accept`, `Reject`, `Unknown`. `Reject` carries the rule number and the category keyword of the failing premise. `Unknown` carries a reason: `:budget` (reduction or conversion fuel exhausted), `:resource` (host limits), or `:interrupted` (a suspended check that did not complete).

**Rule O-2 (Unknown never Accept).** No rule, cache, external oracle, previous result or resumed continuation may convert `Unknown` into `Accept`. An `Unknown` is an execution outcome, not a third truth value.

**Rule O-3 (budget).** The checking configuration fixes a reduction-step budget and a conversion-depth budget. Exhaustion is `Unknown :budget`. Because C-6 guarantees termination, a budget exhaustion on a well-typed term is a resource fact about the configuration, never a logical fact about the term.

**Rule O-4 (no host contribution).** Nothing the executing VM does, no primitive result, no effect and no continuation operation contributes a step to §4. If the kernel's execution encounters a host failure, the outcome is a harness failure, reported outside O-1, never a `Reject`.

## 6. Declarations and admission

### 6.1 Shapes

```text
d ::= definition c [ps] T v      ; transparent, with value
    | theorem    c [ps] T v      ; opaque, with value
    | axiom      c [ps] T        ; no value, marked
```

`ps` is the ordered list of universe parameter names. `T` and `v` are closed terms (no free index) whose levels mention only `ps`.

### 6.2 Admission rules

**Rule D-1 (name and parameters).** `c ∉ E`; otherwise Reject (D-1, `:duplicate`). Names in `ps` are pairwise distinct; otherwise Reject (D-1, `:duplicate-parameter`).

**Rule D-2 (closure).** `T` and `v` contain no `bvar` escaping its binders and no `const` outside `E` (C-7). Every level in `T`, `v` and in every `const` instance satisfies L-2 under `ps`.

**Rule D-3 (definition).** `E; ps; · ⊢ T type` and `E; ps; · ⊢ v : T` ⟹ `E ⊢ definition c [ps] T v admissible`.

**Rule D-4 (theorem).** Identical premises to D-3 ⟹ `E ⊢ theorem c [ps] T v admissible`. **Decision for v1:** a theorem's type is not required to inhabit `sort zero`. The kind `theorem` fixes opacity (C-5) and marks the declaration as a proved statement for provenance; it carries no logical restriction. Under the predicative R-PI the identity statement inhabits `sort (succ zero)`, so a propositionhood requirement would reject the first vertical slice. The Lean restriction is a boundary, Annex A B-6.

**Rule D-5 (axiom).** `E; ps; · ⊢ T type` and `c` is in the admitted-axiom list of the checking configuration ⟹ `E ⊢ axiom c [ps] T admissible`. An axiom outside the admitted list is Reject (D-5, `:axiom-not-admitted`). An axiom is never marked proved, is never unfolded, and every `Accept` of a declaration reports the set of axioms in its transitive constant closure.

**Rule D-6 (no trusted fields).** A declaration submitted for admission carries no field the checker honours as evidence. A `:verified`, `:checked` or similar field is ignored and, if present, reported as Reject (D-6, `:forged-metadata`) so that a producer cannot rely on it silently.

**Rule D-7 (unsupported declaration).** Any other declaration shape, including inductive types, recursors, quotients, mutual blocks, unsafe or partial definitions, is Reject (X-6, `:unsupported-declaration`). It is not admitted as an axiom in its place.

### 6.3 Examples

**Example D-a.** `theorem identity [] T t` with `T`, `t` as in §3.5 into `·`: Accept. Axiom closure: empty.
**Example D-b.** `definition identity [] T t` into `·`: Accept. Same derivation; the kind only changes C-5.
**Example D-c.** `theorem bad [] T (lam (sort zero) (lam (bvar 0) (bvar 1)))` into `·`: Reject (R-CHECK, `:type-mismatch`). The body returns `P`, of type `sort zero`, where `bvar 0`, of type `P`, is required.
**Example D-d.** `definition loop [] (sort zero) (const loop [])` into `·`: Reject (R-CONST, `:unknown-constant`) by C-7.
**Example D-e.** `axiom em [] (pi (sort zero) ...)` with `em` not in the admitted list: Reject (D-5).
**Example D-f.** `definition twice [u] (pi (sort (param u)) (pi (bvar 0) (bvar 1))) ...` then `const twice [zero, zero]`: Reject (R-CONST, `:universe-arity`).

## 7. Exclusions, as rejection rules

Each exclusion is a rule the checker enforces, with a fixture. They are the v1 boundary and are expected to be lifted by versioned extensions, never by a change in the reading of v1.

**Rule X-1 (no impredicative Prop).** There is no Pi rule placing `pi A B` in `sort zero` when `A` inhabits a higher sort. Fixture: `· ⊢ T : sort zero` with `T` from §3.5: Reject (R-CHECK). Companion: `· ⊢ T : sort (succ zero)`: Accept.

**Rule X-2 (no imax).** A level node tagged `imax` is Reject (X-2, `:unsupported-level`). Fixture: `sort (imax (succ zero) zero)`: Reject (X-2).

**Rule X-3 (no eta).** Example C-e: Reject (C-9).

**Rule X-4 (no cumulativity).** `sort zero : sort (succ (succ zero))`: Reject (R-CHECK: `succ zero ≢ succ (succ zero)`). A term of type `sort zero` is not of type `sort (succ zero)`.

**Rule X-5 (no proof irrelevance).** With axioms `P : sort zero`, `h₁ : const P []`, `h₂ : const P []`: `const h₁ [] ≡ const h₂ []`: Reject (C-9). Different opaque constants are not convertible.

**Rule X-6 (closed constructor sets).** Any term tag outside §2.1, any level tag outside §1.1, any declaration shape outside §6.1: Reject (X-6) with the category `:unsupported-constructor`, `:unsupported-level` or `:unsupported-declaration`. Fixture: a term containing a metavariable node: Reject (X-6, `:unsupported-constructor`), never `Unknown`.

**Rule X-7 (no recursion).** Example D-d. A `fix` tag is X-6.

## 8. Consistency with the execution substrate

1. The checker is an ordinary Yin program. Its source identity is the semantic VM's A; this specification's version string is a separate component of the checking configuration. Changing either changes the configuration.
2. Logical terms are data in the universal datom vocabulary with the tags of §2.1. They are never Yin lambdas, and a Yin closure is never a logical term. Binding in logical terms is de Bruijn by §2.1; binding in the checker's own code is the VM's affair.
3. During a check the configuration is frozen (kernel plan §5). Any VM-level event that would alter it, a registry extension, a primitive redefinition or a store write outside the checker's own state, is a harness failure under O-4.
4. A suspended check resumed under a different configuration is refused before any judgment continues (kernel plan Phase 6). A completed judgment from before suspension is kept only if the configuration re-verifies.

## Annex A: comparison profile v1 against the Lean 4 oracle

The differential harness cites this annex. The oracle is a pinned Lean 4 release driven through its checked kernel admission path, as the oracle methodology specifies. Lean is non-cumulative, has an impredicative `Prop` through `imax`, has eta and definitional proof irrelevance, and requires a theorem's type to be a proposition. v1 differs on all four. The relation between the two checkers is therefore declared per judgment, never assumed.

### A.1 Translation

A v1 term translates to a Lean kernel expression constructor for constructor, `sort ℓ` to `Sort ℓ`, levels constructor for constructor, `bvar`, `app`, `lam`, `pi`, `let` to their Lean counterparts with binder names supplied from metadata or generated. `definition` translates to a Lean `defnDecl` with default reducibility, `theorem` to a `defnDecl` marked irreducible, not to `thmDecl`, `axiom` to `axiomDecl`. The translation is total on v1 and partial from Lean to v1: any Lean expression using `imax`, projections, literals, inductives, recursors, quotients or metavariables has no v1 image and is outside both profiles.

### A.2 Judgment-preserving fragment

On these judgments Yin `Accept` and `Reject` must match the oracle exactly:

| Judgment | Condition |
|---|---|
| `t ⇒ T` and `t : T` for terms without `pi` whose domain sort exceeds the codomain sort | the Pi rule agrees whenever `max ℓ₁ ℓ₂ = imax ℓ₁ ℓ₂`, which holds when `ℓ₁ ≡ zero`, or when every atom of the L-3 normal form of `ℓ₂` has offset at least 1 |
| `t ≡ u` by C-1, C-2, C-3, C-4 alone | no eta or proof-irrelevance step is required to decide the pair |
| level equality L-4 on `imax`-free levels | Lean's normalization agrees on this grammar |
| definition and axiom admission D-3, D-5 under the previous conditions | with Lean's axiom policy aligned to the configuration's admitted list |
| rejections R-VAR, R-CONST, R-APP `:not-a-function`, R-LAM, D-1, D-2 | structural errors both kernels report |

### A.3 Boundary fixtures

Each fixture records the expected pair of outcomes. A mismatch with the recorded pair blocks; a match with Lean is neither required nor expected.

| ID | Judgment | v1 | Lean |
|---|---|---|---|
| B-1 | `· ⊢ T : sort zero`, `T` the identity statement | Reject (R-CHECK) | Accept |
| B-2 | `· ⊢ T : sort (succ zero)` | Accept | Reject |
| B-3 | eta pair, Example C-e | Reject (C-9) | Accept |
| B-4 | `sort (imax (succ zero) zero)` as a term | Reject (X-2) | Accept, equals `Sort 0` |
| B-5 | unfolding of a `theorem` constant, Example C-d | Reject (C-9) | to be measured against the pinned oracle before the fixture is recorded; no prediction is made here |
| B-6 | `theorem identity : T := t` admitted as a Lean `thmDecl` | not applicable: v1 translates theorems to irreducible definitions | Accept: in Lean T : Sort 0, so the thmDecl propositionhood check passes |
| B-7 | proof-irrelevance pair, X-5 | Reject (C-9) | Accept |
| B-8 | `sort zero : sort (succ (succ zero))`, X-4 | Reject | Reject |

B-8 is a judgment-preserving row included to document that non-cumulativity is shared.

### A.4 One-way embedding

No universe-shifting embedding of v1 into Lean is claimed in this version. Where it is investigated later, agreement through it is reported separately and never as rechecking unchanged Lean exports.

### A.5 Outcome normalization

`Accept` and `Reject` compare as above. A Yin `Unknown` never matches any oracle outcome and blocks a fixture that claims completed checking. A Lean resource exception is recorded as the oracle's `Unknown`, distinct from a Lean type error. A broken driver, translator or crashed process is a harness failure, not a negative fixture.

## Annex B: fixture index for Phases 2 and 3

| ID | Rule exercised | Expected |
|---|---|---|
| F-1 | §3.5, `t : T` | Accept |
| F-2 | §3.5, `T : sort (succ zero)` | Accept |
| F-3 | R-VAR dangling: `· ⊢ bvar 0` | Reject (R-VAR) |
| F-4 | R-APP not a function: `app (sort zero) (sort zero)` | Reject (R-APP) |
| F-5 | R-APP mismatch: `app t (sort (succ zero))` with `t` from §3.5 | Reject (R-APP) |
| F-6 | R-LET annotation mismatch | Reject (R-LET) |
| F-7 | R-CONST arity, Example D-f | Reject (R-CONST) |
| F-8 | L-a, L-b, L-c | Accept, Accept, Reject |
| F-9 | C-a to C-d | Accept, Accept, Accept, Reject |
| F-10 | D-a to D-f | as listed in §6.3 |
| F-11 | X-1 to X-7 | as listed in §7 |
| F-12 | O-3 budget: C-c under a one-step budget | Unknown (`:budget`) |
| F-13 | C-10: cached and uncached F-9 agree; a cache keyed without the configuration identity is refused by the harness | Accept |

Every fixture runs on the direct Clojure execution of the kernel and on its compiled Yin execution on each verified host lane, and the outcomes must be identical. A lane that cannot run a fixture is a recorded blocker, not a passing lane.
