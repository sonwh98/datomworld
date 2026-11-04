# H3-3 fixture family: the 20 unexercised arities (preparation)

Status: **preparation only**. This document designs the fixture family for
the 20 admitted-but-unexercised kernel function/arities and predicts its
A/B/C outcomes. It changes no harness, compiler, corpus, or evidence file.
The companion A-verification script is
[`h3_3_a_verify.clj`](h3_3_a_verify.clj). Harness integration (new rows in
`kernel_gate.edn`, registration of new integration IDs, evidence refresh)
is a later reviewed round. No trace-name registration is proposed in this
preparation (§7 withdraws the one an earlier draft sketched).

Upstream pin: `latte-kernel/latte-kernel` at
`86fca0d1d054af005cb37395eae04500d45e6279` (the `deps.edn` `:test` alias).
All line references below are to that pin: e.g. `norm.cljc:88` means
`src/latte_kernel/norm.cljc` line 88 at that sha.

One correction to the brief: `docs/design/hardening3-plan.md` does not
exist on this branch (or anywhere in this workspace); only the brief
references it. The dispositions below are therefore derived from the
landed corpus precedents in
[`kernel_gate.edn`](kernel_gate.edn), the harness rules in
[`latte_oracle_test.clj`](../../latte_oracle_test.clj), and the H3-2
pattern ([`h3_2_fixture_family.md`](h3_2_fixture_family.md)). If the plan
document lands with different dispositions, the refusal companions in §5
are the section to re-check first.

## 0. Verification ledger (read this first)

| Claim class | Status |
|---|---|
| Call graph, branch conditions, refusal messages/data (§3, §5) | Source-derived: read directly from pinned `defenv.cljc` / `nbe.cljc` / `norm.cljc` / `presyntax.cljc` / `syntax.cljc` / `unparser.cljc` in this round |
| Every predicted A outcome in §4–§5 | **A-verified**: [`h3_3_a_verify.clj`](h3_3_a_verify.clj) was run against pinned upstream `86fca0d1` in the preparation round and re-run unmodified in the credit-rule revision round; both runs `H3-3-A-VERIFY: PASS (43/43)`, exit 0 (see §8 for the exact commands actually run) |
| Canonical expected values (beta-reduction, norm/instantiate-def, mk-fresh) | Cross-checked against upstream's own tests at the same sha (`norm_test.cljc:test-beta-reduction`, `test-instantiate-def`, `syntax_test.cljc:test-mk-fresh`); the fixture terms are upstream's own test terms where they exist |
| Predicted B/C outcomes and C-lane trace records (§4, §7) | Source-derived from [`context.edn`](context.edn) and the harness's `kernel-rewrite` / `kernel-trace-form` / `kernel-executed-arity?` rules; **not executed** — executing B/C requires harness integration, which is the next round. Nothing here claims B/C agreement has been observed |
| Current credit state (§1) | Read from the checked-in [`slice9_evidence.edn`](slice9_evidence.edn) `:coverage :entries` and [`slice9_unexercised.edn`](slice9_unexercised.edn) on this branch in this round |

## 1. Baseline and what H3-3 must add

The kernel gate is honestly `:blocked` with exactly 20
admitted-but-unexercised function/arities (checked-in evidence:
`:counts {:pass 109, :recorded-upstream-failure 2,
:recorded-deviation 2}`, `:fixture-count 113`, `:exercised-count 145` of
165 admitted arities). The 20, with their namespaces:

| Namespace | Unexercised arities |
|---|---|
| `latte-kernel.defenv` | `axiom?/1`, `registered-definition?/1` |
| `latte-kernel.nbe` | `evaluation/1`, `instantiate-def/3` |
| `latte-kernel.norm` | `beta-reduction/1`, `beta-step/1`, `beta-step/2`, `beta-step-args/2`, `delta-step/4`, `delta-step-args/5`, `instantiate-def/3`, `redex?/1` |
| `latte-kernel.presyntax` | `parse/1` |
| `latte-kernel.syntax` | `binderify/3`, `mk-fresh/2`, `mk-fresh/3`, `readable-term/1`, `readable-term-/2`, `readable-term-/3` |
| `latte-kernel.unparser` | `unregister-unparser!/1` |

Design principles, following H3-2 and the residual fixtures already in
the corpus:

- **Direct first.** Where the fixture's own `:call` head can be the target
  function with the exact argument count, the fixture is a direct row and
  needs no registration. 16 of the 20 take this shape.
- **Named integration only where direct is impossible**, with the reason
  stated per case (§7): a private var (`readable-term-`) and a
  side-effecting sequence whose head is `let` (`unregister-unparser!`).
  One further arity (`registered-definition?/1`) can be credited by
  neither route under the harness as it stands; its fixture (F2) is an
  honest non-crediting pass and the gate stays `:blocked` on it (§7).
- **Refusal companions are `:negative?` rows, but negativity does not
  withhold credit** under the harness as it stands (§2, audit in §5.1).
  They exist so the integration round records honest negative rows
  rather than manufacturing pass shapes. Whether their predicted credit
  stands or the harness is tightened to exclude them is an integration
  decision, presented without a recommendation in §8.
- **One primary witness per arity** (§7). Incidental traces a fixture
  also produces are listed as redundant witnesses; they are predicted
  from the call graph and must be confirmed from both C-lane traces at
  integration, but no arity's credit may rest on an incidental trace
  alone in this design's accounting. This is the design's accounting,
  not a harness guarantee: under the current credit rule a passing
  refusal companion can also appear as a direct creditor for its head
  arity (§5.1), and integration must publish the actual creditor sets.

## 2. The executed-arity rule, and what each fixture must trace

Credit follows `kernel-exercise-entries` /
`kernel-executed-arity?` in
[`latte_oracle_test.clj`](../../latte_oracle_test.clj):

- Every selected guest function body appends `[guest-fn arity]` to the
  execution-trace stream at body entry (`kernel-trace-form`). A/B are
  uninstrumented.
- A passing fixture credits `[symbol n]` **directly** only when the
  fixture's `:call` head is `symbol` with exactly `n` arguments, and only
  when the exact trace record appears in **both** C lanes
  (`:c-walker` and `:c-semantic`).
- Otherwise credit is **named-integration** only: the fixture ID must be
  registered in `kernel-named-integrations`, the row must be `:pass`, and
  again the exact record must appear in both C lanes. Precisely: the
  harness computes the direct-creditor and integration-creditor lists
  INDEPENDENTLY — a registered fixture can satisfy both criteria and then
  appears in both lists — and the resulting `:exercise` classification
  gives the nonempty direct list precedence while the fixture ID is still
  retained in `:integration-fixture-ids`. A row's designated route and
  its classification can therefore differ (see §7's dual accounting).
- **`:negative?` is not consulted by the credit rule.** Neither branch
  of `kernel-exercise-entries` (`latte_oracle_test.clj:1931`) tests it:
  the direct branch tests `:pass` status, `kernel-executed-arity?`, and
  the fixture's `:call` head/argument count; the integration branch
  tests `:pass` status, named-integration membership, and
  `kernel-executed-arity?`. The flag is carried on each result row
  (`kernel-row`, `latte_oracle_test.clj:2247`) but unused there. A
  `:negative?` row whose A/B/C outcomes agree is `:pass` like any other
  (its A outcome must still be a genuine rejection — thrown, `false`,
  or `:ko`-headed — under the gate's separate negative check), and
  because traces are appended at body entry, a refusal that is entered
  and then throws has still recorded its head's trace. A passing
  negative direct fixture therefore **can** receive direct credit for
  its `:call` head at the exact arity, and a registered negative can
  contribute named-integration credit. This is not hypothetical: the
  checked-in [`slice9_evidence.edn`](slice9_evidence.edn) already lists
  a `:negative?` fixture among the credited fixture IDs of 51 admitted
  arities, two of them — `latte-kernel.norm/beta-eq? 2` (sole creditor
  `:beta-eq-false`) and `latte-kernel.typing/type-of 3` (sole creditor
  `:type-of-invalid`) — credited **solely** by a negative row. §5.1
  audits this family's refusal companions against this actual rule;
  §8 presents, without deciding, whether integration accepts the
  behavior or tightens the harness to exclude `:negative?` rows.
- Context-adapted functions trace under their bare context name with the
  explicit context argument counted: `kernel-context-integration-names`
  maps the upstream symbol to the bare name, and
  `kernel-context-extra-arguments` adds 1. Today that table already
  contains `unregister-unparser → 1`; it contains **no** defenv predicate
  entry, and none is proposed (§7: a global +1 offset there would not
  be arity-safe for a multi-arity predicate).

The trace record each H3-3 primary fixture must produce in both C lanes:

| Target arity | Guest trace record | Why that name |
|---|---|---|
| `defenv/axiom? 1` | `[gate-defenv-axiom? 1]` | context-adapter wrapper delegates to the bare context predicate of the same arity |
| `defenv/registered-definition? 1` | none — non-crediting (§7) | F2's guest call is predicted to trace `[registered-definition? 2]` and, by the context body's self-delegation, `[registered-definition? 3]` in both C lanes, but no harness mapping turns either record into credit for upstream `/1`, and none is proposed |
| `nbe/evaluation 1` | `[gate-nbe-evaluation 1]` | direct guest compilation of the upstream body |
| `nbe/instantiate-def 3` | `[gate-nbe-instantiate-def 3]` | direct |
| `norm/beta-reduction 1` | `[gate-norm-beta-reduction 1]` | direct |
| `norm/beta-step 1` | `[gate-norm-beta-step 1]` | direct |
| `norm/beta-step 2` | `[gate-norm-beta-step 2]` | direct |
| `norm/beta-step-args 2` | `[gate-norm-beta-step-args 2]` | direct |
| `norm/delta-step 4` | `[gate-norm-delta-step 4]` | direct |
| `norm/delta-step-args 5` | `[gate-norm-delta-step-args 5]` | direct |
| `norm/instantiate-def 3` | `[gate-norm-instantiate-def 3]` | direct |
| `norm/redex? 1` | `[gate-norm-redex? 1]` | direct |
| `presyntax/parse 1` | `[gate-presyntax-parse 1]` | direct; the body's ambient `empty-env` becomes the guest initial context |
| `syntax/binderify 3` | `[gate-syntax-binderify 3]` | direct |
| `syntax/mk-fresh 2` | `[gate-syntax-mk-fresh 2]` | direct |
| `syntax/mk-fresh 3` | `[gate-syntax-mk-fresh 3]` | direct |
| `syntax/readable-term 1` | `[gate-syntax-readable-term 1]` | direct |
| `syntax/readable-term- 2` | `[gate-syntax-readable-term- 2]` | named integration (private var, §4.6) |
| `syntax/readable-term- 3` | `[gate-syntax-readable-term- 3]` | named integration (private var, §4.6) |
| `unparser/unregister-unparser! 1` | `[unregister-unparser 2]` | bare context name + 1; mapping already in the harness tables |

## 3. Call graph the predictions rest on

From the pinned sources:

- `defenv/axiom?/1` (`defenv.cljc:177`) is `instance? Axiom`. The context
  adaptation replaces records with `:latte/kind` maps and the predicate
  with a kind check (`context.edn`), so a `->Axiom` construction agrees
  on A (record) and B/C (map) with outcome `true`; a `Definition` and a
  plain map both give `false`.
- `defenv/registered-definition?` (`defenv.cljc:99`) is multi-arity:
  `/1` means `(registered-definition? empty-env dname false)` — the
  ambient environment. The context adaptation
  (`context.edn:56`) has no ambient form; its arities are
  `[kernel dname]` and `[kernel dname local?]`. The `[kernel dname]`
  shape is identical for an explicit-environment upstream `/2` call and
  for upstream `/1` with its ambient environment made explicit — the
  bare trace therefore does not preserve which upstream arity or call
  origin produced it, and the `[kernel dname]` body self-delegates to
  the 3-arity, so one such call traces both `[registered-definition? 2]`
  and `[registered-definition? 3]` (§7). The gate wrapper the harness
  generates for `/1` therefore delegates `(registered-definition? p0)`
  to a context function that requires the context first — an arity
  error on B/C (§7). Upstream `/1` itself never throws for a symbol: an
  unregistered, unresolvable name fetches `[:ko …]` and yields `false`
  (`defenv.cljc:81`); a non-symbol name throws
  `"Cannot fetch definition (please report)" {:dname …}`
  (`defenv.cljc:96`).
- `nbe/evaluation` (`nbe.cljc:87`): `/1` delegates to the 4-arity with
  `{}` function environment, `empty-env`, `[]` context. On a binder the
  result **embeds a host Clojure function** (`nbe.cljc:102`), which is
  nonportable evidence (a host `fn` on A/B, a guest closure on C; the
  harness's `evidence-data` marks it `:nonportable-host-value`). Fixture
  terms must therefore fully apply, reducing to plain data: a redex
  `[(λ [a ✳] a) b]` evaluates to the symbol `b` and never returns a
  binder. A reference to a missing definition throws
  `"No such definition" {:term t :def-name name}` via `delta-reduction`
  (`nbe.cljc:44`).
- `nbe/instantiate-def/3` (`nbe.cljc:25`): `binderify`s the parameters
  over the body, then folds the arguments on as vector applications; it
  does **not** substitute. More arguments than parameters throws
  `"Not enough parameters (please report)" {:args args}` with the
  **original** argument vector (`nbe.cljc:29`). Contrast
  `norm/instantiate-def/3` (`norm.cljc:229`), which substitutes via
  `stx/subst`, re-abstracts unfilled parameters (renaming on collision —
  the partial case yields `t'` for `t`), and throws the same message
  with the **remaining** argument sequence (`norm.cljc:238`). The two
  functions share a name and a message; the fixtures must not conflate
  their expected data.
- `norm/redex?/1` (`norm.cljc:88`): application whose first element is a
  `λ`. `norm/beta-reduction/1` (`norm.cljc:94`) substitutes the argument
  for the bound variable and throws
  `"Cannot beta-reduce. Not a redex" {:term t}` otherwise.
- `norm/beta-step` (`norm.cljc:116`): `/1` is `(beta-step t 0)`; `/2`
  threads the reduction count. The application branch recurs through
  `beta-reduction` (hence every successful `beta-step` fixture also
  traces `beta-reduction/1`); the reference branch goes through
  `beta-step-args/2` (`norm.cljc:164`), which steps each argument with
  the threaded count. A term with no redex returns `[t 0]` — a value,
  not a refusal.
- `norm/delta-step` (`norm.cljc:321`): arities `/3` (already exercised),
  `/4` (`local?`, the target), `/5` (internal, already exercised). The
  reference branch calls `delta-reduction` (which calls
  `norm/instantiate-def/3` when unfolding) and recurs at `/5` while
  reductions happen; a non-reducible reference steps its arguments via
  `delta-step-args/5` (`norm.cljc:375`). Environment threading is the
  point of `/4`: `local? true` fetches with `local-only?` (a globally
  registered definition then throws `"No such definition"`), `local?
  false` also sees globals. Upstream fetch on A additionally falls back
  to namespace resolution; every fixture here registers its definition
  explicitly in the environment, so that fallback is never consulted
  and A/B/C cannot diverge on it (the guest has no namespace fallback —
  cf. the existing `:entry-delta-local-namespace-shadow` boundary).
- `presyntax/parse/1` (`presyntax.cljc:95`): `(parse empty-env t)`; a
  `[:ko …]` from `parse-term` is rethrown as ex-info `"Parse error"`
  carrying the parse-error map. The ambient `empty-env` is exactly what
  the harness's guest rewrite replaces with the initial context.
- `syntax/binderify/3` (`syntax.cljc:57`): folds bindings in reverse
  over the body; zero bindings return the body unchanged.
- `syntax/mk-fresh` (`syntax.cljc:262`): `/2` is `(mk-fresh base 0
  forbid)`; `/3` tries suffixes `""`, `'`, `''`, `'''`, then `-4`,
  `-5`, … by level. It is **pure**: it builds a symbol from the base
  name and the level; there is no gensym and no fresh-supply counter
  involved (contrast `typing/rebuild-type`, whose supply the harness
  threads explicitly). Repeated calls with equal arguments return equal
  symbols on every leg, so no freshness side condition is needed; the
  design corrects the brief's "gensym freshness" framing here.
- `syntax/readable-term/1` (`syntax.cljc:478`) computes the free
  variables and delegates to the private `readable-term-`
  (`syntax.cljc:449`): `/2` supplies an empty bound map, `/3` threads
  `free`/`bound`. A bound variable whose name occurs in `free` is
  renamed with `mk-fresh/2` (no metadata case: `(:name (meta x) x)`
  defaults to the symbol itself — corpus EDN cannot carry `with-meta`
  symbols, so the metadata-named cases in upstream's
  `test-readable-term` are not expressible as corpus rows and are out of
  scope; the collision renaming they exercise is reproduced with plain
  symbols). Two consequences: (a) `readable-term-` is a `defn-`, so the
  A leg cannot eval a direct qualified call to it (compile error:
  var is not public); the corpus `:call` must use the `(var …)` form,
  which also means the call head is not the function symbol and direct
  credit is impossible (§7); (b) the body calls `meta`, which the guest
  library must serve — precedent exists (`:minimal-with-meta`), flagged
  as the one library-dependency risk in §8.
- `unparser/unregister-unparser!/1` (`unparser.cljc:29`): mutates the
  ambient `+unparser-registry+` atom; throws `"No such unparser."
  {:ident ident}` when the ident is absent. The context adaptation
  threads an immutable registry through an explicit context
  (`context.edn:92`), and the harness already maps the trace name
  (`unregister-unparser`, +1 argument). The A leg isolates the atom via
  the existing `:unparser-install` mode, so no fixture here mutates
  shared upstream state beyond that isolation. **Conservative-upstream
  note (binding):** `unparser.cljc:35` rebuilds the `:user` list from
  the `:standard` list (`(u/vremove #(= % ident) (:standard registry))`)
  — unregistering a `:user` ident replaces `:user` with the filtered
  `:standard` list. The context source preserves this exactly
  (`context.edn:99`, "Preserve the upstream unregister's
  :user-from-:standard behavior exactly"). No fixture may expect the
  corrected `:user` list; the A-verification script pins the upstream
  registry shape for this case, and §4.7's fixture observes the effect
  only through `unparse`, which is insensitive to it for a single
  registered ident.

## 4. The family

Proposed corpus rows are shown in the corpus's EDN shape (A form; the
harness derives B/C, using `:guest-call` where present). New IDs are
marked †. Every predicted A outcome below is A-verified (§0); predicted
B/C outcomes are the same value unless stated, derived per §3.

### 4.1 defenv predicates

F1† `:h3-3-axiom-true` — primary witness for `axiom?/1` (direct):

```clojure
{:id :h3-3-axiom-true,
 :call (latte-kernel.defenv/axiom? (latte-kernel.defenv/->Axiom (quote a) [] 0 (quote T)))}
```

Expected A/B/C: `true`. Required trace: `[gate-defenv-axiom? 1]` in both
C lanes. The two false companions are refusal rows in §5 (R1); they trace
the same record and, negativity notwithstanding, would also credit
`axiom?/1` under the harness as it stands if they pass (§5.1). F1
remains the designated primary witness in this design's accounting.

F2† `:h3-3-registered-definition-ambient` — designed witness for
`registered-definition?/1`, **non-crediting** (§7):

```clojure
{:id :h3-3-registered-definition-ambient,
 :call (latte-kernel.defenv/registered-definition? (quote gate-no-such-definition)),
 :guest-call (registered-definition? env (quote gate-no-such-definition))}
```

Expected A: `false` — the name is in neither the ambient `empty-env`
nor any namespace the A leg can resolve (the name is chosen to resolve
nowhere; if a future namespace interns it, this prediction breaks and
the fixture must be renamed, never re-expected). Expected B/C: `false`
(context fetch on the initial context misses both maps). There is no
required trace for credit, because this row credits nothing: its guest
call is predicted to trace `[registered-definition? 2]` and
`[registered-definition? 3]` in both C lanes (§3), but
`kernel-executed-arity?` for upstream `/1` seeks
`[gate-defenv-registered-definition? 1]`, no bare-name mapping exists,
and §7 withdraws the +1 trace-table mapping an earlier draft proposed
(it is not arity-safe). The gate-wrapper `/1` the direct route would
otherwise use is arity-broken against the context signature (§3), so
no ordinary fixture credits this arity; the gate stays honestly
`:blocked` on it. The non-symbol refusal companion is R2 in §5. A `true` case for `/1` is deliberately **not**
designed: on A it could only be produced by namespace resolution (the
ambient env is empty by definition), which B/C expressly do not have —
such a row would be a manufactured mismatch, not a fixture.

### 4.2 nbe

F3† `:h3-3-nbe-evaluation-one` — primary witness for `evaluation/1`
(direct):

```clojure
{:id :h3-3-nbe-evaluation-one,
 :call (latte-kernel.nbe/evaluation (quote [(λ [a ✳] a) b]))}
```

Expected A/B/C: `b`. The term fully applies, so the host function the
binder branch builds is consumed internally and never appears in the
result (§3); a binder-returning term would embed a host `fn` and is
excluded by design. Required trace: `[gate-nbe-evaluation 1]`, plus
redundant `[gate-nbe-evaluation 4]`. Companion `:h3-3-nbe-evaluation-var`
(`(quote a)` → `a`, same traces) is a redundant second direct row; the
missing-reference refusal is R3.

F4† `:h3-3-nbe-instantiate-def` — primary witness for
`nbe/instantiate-def/3` (direct):

```clojure
{:id :h3-3-nbe-instantiate-def,
 :call (latte-kernel.nbe/instantiate-def (quote [[x ✳] [y ✳]]) (quote [x y]) (quote [a b]))}
```

Expected A/B/C: `[[(λ [x ✳] (λ [y ✳] [x y])) a] b]` — binderified, then
applied, **not** substituted (§3). Required trace:
`[gate-nbe-instantiate-def 3]`; redundant `[gate-syntax-binderify 3]`
(the internal `binderify` call). The too-many-arguments refusal is R4.

### 4.3 norm: beta family

F5† `:h3-3-redex-true` — primary witness for `redex?/1` (direct):

```clojure
{:id :h3-3-redex-true,
 :call (latte-kernel.norm/redex? (quote [(λ [x ✳] x) y]))}
```

Expected A/B/C: `true`. Required trace: `[gate-norm-redex? 1]`. The two
false companions (`[x y]`, `x`) are R5.

F6† `:h3-3-beta-reduction` — primary witness for `beta-reduction/1`
(direct), upstream's own `test-beta-reduction` term:

```clojure
{:id :h3-3-beta-reduction,
 :call (latte-kernel.norm/beta-reduction (quote [(λ [x ✳] [x x]) y]))}
```

Expected A/B/C: `[y y]`. Required trace: `[gate-norm-beta-reduction 1]`;
redundant `[gate-norm-redex? 1]` (the guard call) and
`[gate-syntax-subst 2]`. The non-redex refusal is R6.

F7† `:h3-3-beta-step-one` — primary witness for `beta-step/1` (direct):

```clojure
{:id :h3-3-beta-step-one,
 :call (latte-kernel.norm/beta-step (quote [(λ [x ✳] x) y]))}
```

Expected A/B/C: `[y 1]`. Required trace: `[gate-norm-beta-step 1]`;
redundant `[gate-norm-beta-step 2]` (the `/1` body delegates) and
`[gate-norm-beta-reduction 1]`. Companion F7b†
`:h3-3-beta-step-binder` — `(quote (λ [x ✳] [(λ [y ✳] y) x]))` →
`[(λ [x ✳] x) 1]`, the binder branch with the redex under the binder;
same traces, redundant for `/1`. Edge companion F7c†
`:h3-3-beta-step-no-redex` — `(quote x)` → `[x 0]`: the no-reduction
result is a value, recorded here so integration does not mistake it for
a refusal shape.

F8† `:h3-3-beta-step-two` — primary witness for `beta-step/2` (direct):

```clojure
{:id :h3-3-beta-step-two,
 :call (latte-kernel.norm/beta-step (quote [(λ [x ✳] x) y]) 5)}
```

Expected A/B/C: `[y 6]` — the threaded count is observable, which is
what distinguishes `/2` from `/1` beyond the trace. Required trace:
`[gate-norm-beta-step 2]`; redundant `[gate-norm-beta-reduction 1]`.

F9† `:h3-3-beta-step-args` — primary witness for `beta-step-args/2`
(direct):

```clojure
{:id :h3-3-beta-step-args,
 :call (latte-kernel.norm/beta-step-args (quote ([(λ [x ✳] x) y] z)) 0)}
```

Expected A/B/C: `[[y z] 1]` — one of the two parallel arguments
reduces; the count threads across both. Required trace:
`[gate-norm-beta-step-args 2]`; redundant `[gate-norm-beta-step 2]`,
`[gate-norm-beta-reduction 1]`.

### 4.4 norm: instantiate-def

F10† `:h3-3-norm-instantiate-def` — primary witness for
`norm/instantiate-def/3` (direct), upstream's own
`test-instantiate-def` full case:

```clojure
{:id :h3-3-norm-instantiate-def,
 :call (latte-kernel.norm/instantiate-def (quote [[x ✳] [y ✳] [z ✳]])
                                          (quote [[x y] [z x]])
                                          (quote ((λ [t ✳] t) t1 [t2 t3])))}
```

Expected A/B/C: `[[(λ [t ✳] t) t1] [[t2 t3] (λ [t ✳] t)]]`. Required
trace: `[gate-norm-instantiate-def 3]`; redundant
`[gate-syntax-subst 3]`/`[gate-syntax-subst 2]`. Companion F10b†
`:h3-3-norm-instantiate-def-partial` — four parameters, three arguments
→ `(λ [t' ✳] [[(λ [t ✳] t) t1] [[t2 t3] t']])`: the unfilled parameter
is re-abstracted and **renamed** `t`→`t'` by the substitution's fresh
renaming; this is upstream behavior to assert, not correct. Same
required trace, redundant for `/3`. The too-many-arguments refusal is
R7 — note its `:args` payload is the remaining sequence `(b)`, unlike
R4's original vector (§3).

### 4.5 norm: delta family (environment threading)

F11† `:h3-3-delta-step-four-local` — primary witness for
`delta-step/4` (direct), the `local? true` threading:

```clojure
{:id :h3-3-delta-step-four-local,
 :call (latte-kernel.norm/delta-step
         (latte-kernel.defenv/register-definition env (latte-kernel.defenv/->Definition (quote d) [] 0 (quote ✳) #{} (quote □) {}) true)
         [] (quote (d)) true)}
```

Expected A/B/C: `[✳ 1]`. The head is `delta-step` with four arguments,
so direct credit applies even though the environment argument is a
composed registration (precedent: `:entry-delta-local`). Required
trace: `[gate-norm-delta-step 4]`; redundant `[gate-norm-delta-step 5]`
(the post-reduction recur), `[gate-norm-delta-reduction 4]`,
`[gate-norm-instantiate-def 3]`.

F11b† `:h3-3-delta-step-four-global` — the `local? false` threading,
redundant for `/4`:

```clojure
{:id :h3-3-delta-step-four-global,
 :definitions [(latte-kernel.defenv/->Definition (quote d) [] 0 (quote ✳) #{} (quote □) {})],
 :call (latte-kernel.norm/delta-step env [] (quote (d)) false)}
```

Expected A/B/C: `[✳ 1]`, same traces as F11. Keeping both rows pins the
flag's two lookup scopes; a `local? true` call against a merely global
definition throws `"No such definition"` and is **not** given a row —
that divergence risk (A's namespace fallback) is already recorded by
`:entry-delta-local-namespace-shadow`, and duplicating it here would
add a boundary row, not coverage. The missing-definition refusal is R8.

F12† `:h3-3-delta-step-args` — primary witness for
`delta-step-args/5` (direct):

```clojure
{:id :h3-3-delta-step-args,
 :definitions [(latte-kernel.defenv/->Definition (quote d) [] 0 (quote ✳) #{} (quote □) {})],
 :call (latte-kernel.norm/delta-step-args env [] (quote ((d) [(λ [x ✳] x) y])) false 0)}
```

Expected A/B/C: `[[✳ [(λ [x ✳] x) y]] 1]` — the reference argument
delta-reduces (count 1), the application argument is stepped without
reducing (delta-step does not beta-reduce). Required trace:
`[gate-norm-delta-step-args 5]`; redundant `[gate-norm-delta-step 5]`,
`[gate-norm-delta-reduction 4]`, `[gate-norm-instantiate-def 3]`.

### 4.6 presyntax and syntax

F13† `:h3-3-parse-one` — primary witness for `parse/1` (direct):

```clojure
{:id :h3-3-parse-one,
 :call (latte-kernel.presyntax/parse (quote (lambda [x :type] x)))}
```

Expected A/B/C: `(λ [x ✳] x)`. Required trace:
`[gate-presyntax-parse 1]`; redundant `[gate-presyntax-parse 2]` (the
`/1` body delegates with the ambient environment, which the guest
rewrite supplies as its initial context). The two thrown refusals are
R9. Existing `:parse-binder`/`:parse-application` rows cover `/2`; this
row's only new content is the ambient-environment arity, which is
exactly the unexercised one.

F14† `:h3-3-binderify` — primary witness for `binderify/3` (direct):

```clojure
{:id :h3-3-binderify,
 :call (latte-kernel.syntax/binderify (quote λ) (quote [[x ✳] [y A]]) (quote [x y]))}
```

Expected A/B/C: `(λ [x ✳] (λ [y A] [x y]))`. Required trace:
`[gate-syntax-binderify 3]`, no redundant target traces. Companion
F14b† `:h3-3-binderify-empty` — `(quote Π) (quote []) (quote B)` → `B`:
zero bindings return the body; same trace, redundant for `/3`.

F15† `:h3-3-mk-fresh-two` — primary witness for `mk-fresh/2` (direct):

```clojure
{:id :h3-3-mk-fresh-two,
 :call (latte-kernel.syntax/mk-fresh (quote x) (quote #{x x'}))}
```

Expected A/B/C: `x''`. Pure and deterministic (§3) — no supply is
threaded and no leg can disagree on freshness. Required trace:
`[gate-syntax-mk-fresh 2]`; redundant `[gate-syntax-mk-fresh 3]` (the
`/2` body delegates at level 0). Companion F15b†
`:h3-3-mk-fresh-two-empty` — `(quote #{})` → `x`, the unforbidden base.

F16† `:h3-3-mk-fresh-three` — primary witness for `mk-fresh/3`
(direct), upstream's `test-mk-fresh` shape at an explicit level:

```clojure
{:id :h3-3-mk-fresh-three,
 :call (latte-kernel.syntax/mk-fresh (quote x) 3 (quote #{x x' x'' x'''}))}
```

Expected A/B/C: `x-4` — level 3's candidate `x'''` is forbidden, so the
search continues to the numeric suffix. Required trace:
`[gate-syntax-mk-fresh 3]` only. Companion F16b†
`:h3-3-mk-fresh-three-level-zero` — level `0`, forbid `#{x}` → `x'`;
same trace, redundant for `/3`. The `^long` hint on the upstream level
parameter is a host annotation; the guest receives the same integer
literal (precedent: numeric arguments in `:predicate-vectorn`).

F17† `:h3-3-readable-term` — primary witness for `readable-term/1`
(direct):

```clojure
{:id :h3-3-readable-term,
 :call (latte-kernel.syntax/readable-term (quote [(λ [x ✳] x) x]))}
```

Expected A/B/C: `[(λ [x' ✳] x') x]` — the bound `x` collides with the
free `x` and is renamed; the free occurrence is untouched. Required
trace: `[gate-syntax-readable-term 1]`; redundant
`[gate-syntax-readable-term- 2]`, `[gate-syntax-readable-term- 3]`,
`[gate-syntax-mk-fresh 2]`. Companion F17b† `:h3-3-readable-term-var` —
`(quote x)` → `x` (no binder, identity). The metadata-named upstream
cases are inexpressible in corpus EDN (§3) and are not attempted.

F18† `:h3-3-readable-term-2` — primary witness for `readable-term-/2`
(**named integration**, §7):

```clojure
{:id :h3-3-readable-term-2,
 :call ((var latte-kernel.syntax/readable-term-) (quote #{x}) (quote (λ [x ✳] x))),
 :guest-call (latte-kernel.syntax/readable-term- (quote #{x}) (quote (λ [x ✳] x)))}
```

Expected A/B/C: `(λ [x' ✳] x')`. The `:call` reaches the private var
through `(var …)` (a direct qualified call does not compile on the A
leg — verified in this round); the `:guest-call` is the ordinary guest
form, where the gate-compiled `readable-term-` is a plain definition.
Required trace: `[gate-syntax-readable-term- 2]` in both C lanes, plus
redundant `[gate-syntax-readable-term- 3]`, `[gate-syntax-mk-fresh 2]`.
Credit requires registering `:h3-3-readable-term-2` in
`kernel-named-integrations` at integration.

F19† `:h3-3-readable-term-3` — primary witness for `readable-term-/3`
(**named integration**):

```clojure
{:id :h3-3-readable-term-3,
 :call ((var latte-kernel.syntax/readable-term-) (quote #{x}) (quote {}) (quote (λ [x ✳] [x y]))),
 :guest-call (latte-kernel.syntax/readable-term- (quote #{x}) (quote {}) (quote (λ [x ✳] [x y])))}
```

Expected A/B/C: `(λ [x' ✳] [x' y])` — the explicit bound map is
threaded: the renamed binder is substituted for the bound occurrence,
the free `y` is untouched. Required trace:
`[gate-syntax-readable-term- 3]`; redundant `[gate-syntax-mk-fresh 2]`.
Credit requires registering `:h3-3-readable-term-3`, as F18.

### 4.7 unparser (side-effecting)

F20† `:h3-3-unregister-unparser` — primary witness for
`unregister-unparser!/1` (**named integration**; the trace-name mapping
already exists, §2):

```clojure
{:id :h3-3-unregister-unparser, :mode :unparser-install,
 :call (let [_ (latte-kernel.unparser/register-unparser! :gate (fn [t] (if (= t (quote x)) [(quote shown) true] [t false])))]
         [(latte-kernel.unparser/unparse (quote x))
          (do (latte-kernel.unparser/unregister-unparser! :gate)
              (latte-kernel.unparser/unparse (quote x)))]),
 :guest-call (let [k1 (register-unparser env :gate (fn [t] (if (= t (quote x)) [(quote shown) true] [t false])))]
               [(unparse k1 (quote x)) (unparse (unregister-unparser k1 :gate) (quote x))])}
```

Expected A/B/C: `[shown x]` — before removal the registered unparser
rewrites `x`; after removal it does not. The effect is observed only
through `unparse`, never through the registry value (which holds host
functions upstream and guest closures on C — incomparable evidence by
construction). The A leg runs under `:unparser-install`, so the ambient
atom it mutates is the harness's isolated one; B/C thread the immutable
registry through the returned context, and the *prior* context is never
reused after removal in this row (prior-context preservation for
registries is already asserted by the slice-8 registry sequences).
Required trace: `[unregister-unparser 2]` in both C lanes; redundant
`[register-unparser 3]`, `[unparse 2]`. Credit requires registering
`:h3-3-unregister-unparser` in `kernel-named-integrations`; the
conservative `:user`-from-`:standard` behavior (§3) is pinned on A by
the verification script and must not be "corrected" in any row. The
absent-ident refusal is R10.

## 5. Refusal and edge companions (all `:negative? true` when integrated)

Expected A outcomes are A-verified; B/C are predicted to agree (thrown
ex-info message+data, or the false value — the gate's negative check
accepts thrown, `false`, or `:ko`-headed A outcomes). Under the harness
as it stands these rows are **not** credit-free: §5.1 audits each one
against the actual credit rule of §2.

| ID | Fixture `:call` (guest form where it differs) | Expected A |
|---|---|---|
| R1† `:h3-3-axiom-definition` | `(latte-kernel.defenv/axiom? (latte-kernel.defenv/->Definition (quote d) [] 0 (quote body) #{} (quote T) {}))` | `false` |
| R1† `:h3-3-axiom-map` | `(latte-kernel.defenv/axiom? {})` | `false` |
| R2† `:h3-3-registered-definition-nonsymbol` | `(latte-kernel.defenv/registered-definition? 5)`; guest `(registered-definition? env 5)` | thrown `"Cannot fetch definition (please report)" {:dname 5}` |
| R3† `:h3-3-nbe-evaluation-missing` | `(latte-kernel.nbe/evaluation (quote (gate-missing)))` | thrown `"No such definition" {:term (gate-missing) :def-name gate-missing}` |
| R4† `:h3-3-nbe-instantiate-def-too-many` | `(latte-kernel.nbe/instantiate-def (quote [[x ✳]]) (quote x) (quote [a b]))` | thrown `"Not enough parameters (please report)" {:args [a b]}` |
| R5† `:h3-3-redex-application` | `(latte-kernel.norm/redex? (quote [x y]))` | `false` |
| R5† `:h3-3-redex-variable` | `(latte-kernel.norm/redex? (quote x))` | `false` |
| R6† `:h3-3-beta-reduction-nonredex` | `(latte-kernel.norm/beta-reduction (quote [x y]))` | thrown `"Cannot beta-reduce. Not a redex" {:term [x y]}` |
| R7† `:h3-3-norm-instantiate-def-too-many` | `(latte-kernel.norm/instantiate-def (quote [[x ✳]]) (quote x) (quote [a b]))` | thrown `"Not enough parameters (please report)" {:args (b)}` |
| R8† `:h3-3-delta-step-missing` | `(latte-kernel.norm/delta-step env [] (quote (gate-missing)) false)` | thrown `"No such definition" {:term (gate-missing) :def-name gate-missing}` |
| R9† `:h3-3-parse-one-empty` | `(latte-kernel.presyntax/parse (quote ()))` | thrown `"Parse error" {:msg "Compound term is empty" :term ()}` |
| R9† `:h3-3-parse-one-reserved` | `(latte-kernel.presyntax/parse (quote lambda))` | thrown `"Parse error" {:msg "Symbol is reserved" :term lambda}` |
| R10† `:h3-3-unregister-unparser-absent` | `:mode :unparser-install`, `(latte-kernel.unparser/unregister-unparser! :absent)`; guest `(unregister-unparser env :absent)` | thrown `"No such unparser." {:ident :absent}` |

R4 vs R7 is the pair most likely to be mis-transcribed at integration:
same message, different `:args` payload shape (original vector vs
remaining sequence, §3). Both are pinned exactly by the verification
script.

### 5.1 Refusal-companion credit audit (under the harness as it stands)

Each row below is judged by the actual rule of §2: direct credit iff
the row lands `:pass`, its `:call` head is the credited symbol with the
exact argument count, and the exact trace record appears in both C
lanes. Traces are appended at body entry, so a refusal entered and then
thrown has still recorded its head's trace. All traces are predicted
from §3's call graph, not executed (§0); every verdict is conditional
on the row passing with the predicted traces at integration.

| Row | `:call` head / argc | Predicted C-lane traces (both lanes) | Would it credit anything? |
|---|---|---|---|
| R1 `:h3-3-axiom-definition` | `latte-kernel.defenv/axiom?` / 1 | `[gate-defenv-axiom? 1]` | **Yes** — direct `defenv/axiom? 1` (F1's arity) |
| R1 `:h3-3-axiom-map` | `latte-kernel.defenv/axiom?` / 1 | `[gate-defenv-axiom? 1]` | **Yes** — direct `defenv/axiom? 1` (F1's arity) |
| R2 `:h3-3-registered-definition-nonsymbol` | `latte-kernel.defenv/registered-definition?` / 1 (guest: bare context call) | `[registered-definition? 2]`, `[registered-definition? 3]` | **No** — `kernel-executed-arity?` for `/1` seeks `[gate-defenv-registered-definition? 1]`; the guest call bypasses the gate wrapper and no bare-name mapping exists (§7) |
| R3 `:h3-3-nbe-evaluation-missing` | `latte-kernel.nbe/evaluation` / 1 | `[gate-nbe-evaluation 1]` (plus redundant `[gate-nbe-evaluation 4]`) | **Yes** — direct `nbe/evaluation 1` (F3's arity) |
| R4 `:h3-3-nbe-instantiate-def-too-many` | `latte-kernel.nbe/instantiate-def` / 3 | `[gate-nbe-instantiate-def 3]` (incidental `[gate-syntax-binderify 3]` has no credit path: head mismatch, ID unregistered) | **Yes** — direct `nbe/instantiate-def 3` (F4's arity) |
| R5 `:h3-3-redex-application` | `latte-kernel.norm/redex?` / 1 | `[gate-norm-redex? 1]` | **Yes** — direct `norm/redex? 1` (F5's arity) |
| R5 `:h3-3-redex-variable` | `latte-kernel.norm/redex?` / 1 | `[gate-norm-redex? 1]` | **Yes** — direct `norm/redex? 1` (F5's arity) |
| R6 `:h3-3-beta-reduction-nonredex` | `latte-kernel.norm/beta-reduction` / 1 | `[gate-norm-beta-reduction 1]` (incidental guard trace `[gate-norm-redex? 1]`, no credit path) | **Yes** — direct `norm/beta-reduction 1` (F6's arity) |
| R7 `:h3-3-norm-instantiate-def-too-many` | `latte-kernel.norm/instantiate-def` / 3 | `[gate-norm-instantiate-def 3]` (incidental `subst` traces, no credit path) | **Yes** — direct `norm/instantiate-def 3` (F10's arity) |
| R8 `:h3-3-delta-step-missing` | `latte-kernel.norm/delta-step` / 4 | `[gate-norm-delta-step 4]` | **Yes** — direct `norm/delta-step 4` (F11's arity) |
| R9 `:h3-3-parse-one-empty` | `latte-kernel.presyntax/parse` / 1 | `[gate-presyntax-parse 1]` (plus redundant `[gate-presyntax-parse 2]`) | **Yes** — direct `presyntax/parse 1` (F13's arity) |
| R9 `:h3-3-parse-one-reserved` | `latte-kernel.presyntax/parse` / 1 | `[gate-presyntax-parse 1]` (plus redundant `[gate-presyntax-parse 2]`) | **Yes** — direct `presyntax/parse 1` (F13's arity) |
| R10 `:h3-3-unregister-unparser-absent` | `latte-kernel.unparser/unregister-unparser!` / 1 (guest: bare context call) | `[unregister-unparser 2]` | **Yes** — direct `unparser/unregister-unparser! 1` (F20's arity), via the bare-name mapping already in the harness tables; unlike F20, this row's `:call` head is the symbol itself, so the route is direct, not named-integration |

Summary: 12 of the 13 §5 rows would receive direct credit for their
head arity under the current harness, covering 9 of the 20 target
arities (`axiom?/1`, `evaluation/1`, `nbe/instantiate-def/3`,
`redex?/1`, `beta-reduction/1`, `norm/instantiate-def/3`,
`delta-step/4`, `parse/1`, `unregister-unparser!/1`). Only R2 credits
nothing. No refusal row credits an arity outside §7's table, and none
credits `registered-definition?/1`. Every would-credit verdict
duplicates its primary fixture's arity: the §7 primaries remain the
designated witnesses, but the harness's `:direct-fixture-ids` would
list the refusal IDs alongside them, so an arity's exercised status
could in principle rest on a refusal row if its primary blocked or
mismatched (the gate would still block on the primary row's own
status). If integration instead adopts the tightening option of §8,
every verdict above becomes "no credit" and this table is the list of
rows whose behavior changes.

## 6. Conservative-upstream notes (binding for integration)

1. **Unregister rebuilds `:user` from `:standard`** (`unparser.cljc:35`,
   preserved at `context.edn:99`). Asserted on A by the verification
   script (after registering `:std`/`:usr` and unregistering `:usr`,
   the registry is `{:fundamental [] :standard [:std] :user [:std]
   :unparsers {:std …}}`). No row may expect `:user []` there.
2. **`norm/instantiate-def` renames unfilled parameters** (F10b: `t`
   becomes `t'`). The filtered/renamed form is upstream's; do not
   "fix" the substitution to preserve names.
3. **`mk-fresh` is not freshness.** It is a deterministic suffix search
   over an explicit forbidden set (§3). Any integration test that
   asserts two `mk-fresh` results differ must differ the *inputs*;
   asserting supply-style inequality on equal inputs would falsify the
   differential.
4. **`evaluation/1` must never return a binder.** The binder branch's
   host function is real upstream behavior; the family avoids it by
   fully applying. If integration wants binder coverage, that is a
   recorded-boundary discussion (host-value representation), not a
   fixture tweak.
5. **`registered-definition?/1` has no true case.** Ambient-environment
   truth on A is namespace resolution, which the guest boundary
   expressly refuses (cf. `slice9_gate.md` on namespace fallback). The
   family records the false case only.

## 7. Credit prediction and honest accounting

Exactly one **primary** credited fixture per arity, except
`registered-definition?/1`, which has none. "Credit kind" follows §2:
direct needs no registration; named-integration needs the ID added to
`kernel-named-integrations`; none means the designed fixture is an
honest non-crediting pass. Redundant witnesses trace the arity too
(predicted, §4) but no credit in this accounting rests on them. Under
the harness as it stands, passing §5 refusal companions would also be
listed as direct creditors for 9 of these arities (§5.1); the table
records the designated primaries, and integration must publish the
actual creditor sets (§8).

| Arity | Primary fixture | Credit kind | Required trace (both C lanes) |
|---|---|---|---|
| `defenv/axiom? 1` | F1 `:h3-3-axiom-true` | direct | `[gate-defenv-axiom? 1]` |
| `defenv/registered-definition? 1` | F2 `:h3-3-registered-definition-ambient` | **none (non-crediting)** | — (predicted `[registered-definition? 2]`/`[3]` map to no arity) |
| `nbe/evaluation 1` | F3 `:h3-3-nbe-evaluation-one` | direct | `[gate-nbe-evaluation 1]` |
| `nbe/instantiate-def 3` | F4 `:h3-3-nbe-instantiate-def` | direct | `[gate-nbe-instantiate-def 3]` |
| `norm/redex? 1` | F5 `:h3-3-redex-true` | direct | `[gate-norm-redex? 1]` |
| `norm/beta-reduction 1` | F6 `:h3-3-beta-reduction` | direct | `[gate-norm-beta-reduction 1]` |
| `norm/beta-step 1` | F7 `:h3-3-beta-step-one` | direct | `[gate-norm-beta-step 1]` |
| `norm/beta-step 2` | F8 `:h3-3-beta-step-two` | direct | `[gate-norm-beta-step 2]` |
| `norm/beta-step-args 2` | F9 `:h3-3-beta-step-args` | direct | `[gate-norm-beta-step-args 2]` |
| `norm/instantiate-def 3` | F10 `:h3-3-norm-instantiate-def` | direct | `[gate-norm-instantiate-def 3]` |
| `norm/delta-step 4` | F11 `:h3-3-delta-step-four-local` | direct | `[gate-norm-delta-step 4]` |
| `norm/delta-step-args 5` | F12 `:h3-3-delta-step-args` | direct | `[gate-norm-delta-step-args 5]` |
| `presyntax/parse 1` | F13 `:h3-3-parse-one` | direct | `[gate-presyntax-parse 1]` |
| `syntax/binderify 3` | F14 `:h3-3-binderify` | direct | `[gate-syntax-binderify 3]` |
| `syntax/mk-fresh 2` | F15 `:h3-3-mk-fresh-two` | direct | `[gate-syntax-mk-fresh 2]` |
| `syntax/mk-fresh 3` | F16 `:h3-3-mk-fresh-three` | direct | `[gate-syntax-mk-fresh 3]` |
| `syntax/readable-term 1` | F17 `:h3-3-readable-term` | direct | `[gate-syntax-readable-term 1]` |
| `syntax/readable-term- 2` | F18 `:h3-3-readable-term-2` | named-integration | `[gate-syntax-readable-term- 2]` |
| `syntax/readable-term- 3` | F19 `:h3-3-readable-term-3` | named-integration | `[gate-syntax-readable-term- 3]` |
| `unparser/unregister-unparser! 1` | F20 `:h3-3-unregister-unparser` | named-integration | `[unregister-unparser 2]` |

Honest accounting, by credit kind:

- **16 arities credit by ordinary direct fixtures** as the harness
  stands today (F1, F3–F17 minus the named ones): no registration, no
  harness change. If any one's trace is missing from a passing row at
  integration (e.g. the guest compiler refuses a shape), that arity
  stays unexercised and the plan's residual rule applies — add a
  minimal helper fixture, never weaken the trace rule.
- **3 arities credit as named integrations** (F18, F19, F20): the
  fixture shapes are corpus-supported today (`:guest-call`, `:mode`),
  but credit lands only when integration registers the three IDs in
  `kernel-named-integrations`. F20's trace-name mapping already exists;
  F18/F19 need only the ID registration.
- **1 arity cannot be credited by an ordinary fixture**:
  `defenv/registered-definition?/1`. Reason: upstream `/1` is the
  ambient-`empty-env` form; the context adaptation has no ambient form,
  so (a) the generated gate wrapper for `/1` delegates one argument to
  a context function whose first argument is the context — an arity
  error on B/C, hence `:blocked`, never `:pass`; and (b) the bare
  context traces `[registered-definition? 2]`/`[3]` that F2's
  `:guest-call` does produce are not mapped to this symbol in
  `kernel-context-integration-names` / `kernel-context-extra-arguments`
  as the harness stands. An earlier draft of this preparation proposed
  adding exactly that mapping (`registered-definition?` with a global
  +1 in `kernel-context-extra-arguments`, on the `unregister-unparser!`
  precedent). **That proposal is withdrawn**: a global +1 offset
  applies to every upstream arity, so a bare `[registered-definition? 2]`
  trace from an explicit-environment `/2` call would be misattributed
  to upstream `/1`, and `[registered-definition? 3]` is ambiguous for
  `/2` (F2's own call traces both records, §3). The
  `unregister-unparser!` precedent does not carry over: it is a
  single-arity function whose +1 shift is injective, and it establishes
  no identity preservation for a multi-arity mapping. The draft also
  missed that, once such a mapping exists, F2's `:call` head and
  argument count meet the **direct** criterion on their own — the
  credit would land as `:direct` without any named-integration
  registration of F2 ever being consulted. **F2 therefore stays an
  honest passing row that credits nothing, and the gate stays
  `:blocked` on this one arity** pending a separately reviewed
  treatment (§8). This is the only one of the 20 in that position.
- No arity is left without a designed fixture or an explicit note. The
  16 direct + 3 named-integration + 1 non-crediting = 20 is the accounting
  of **designated primary routes**. The predicted ACTUAL `:exercise`
  classification (per §2's precedence rule) differs on one arity: R10's
  passing row traces `unregister-unparser!` at the direct criterion, so
  that arity classifies `:direct` even though F20's named-integration
  route remains designated and its ID stays in `:integration-fixture-ids`.
  Predicted actual categories therefore: 17 :direct, 2 :named-integration,
  1 :unexercised — same 19 credited arities, one classification moved by
  the precedence rule, no double-counting.
- Preparation contributes **zero** coverage until reviewed and
  integrated: nothing in this round changes the checked-in evidence,
  and the gate remains `:blocked` with 20 unexercised arities.

Redundant (incidental) witnesses, predicted from §3's call graph —
useful if a primary trace is missing at integration, never counted
above: `binderify/3` also in F4; `beta-reduction/1`, `beta-step/2`,
`redex?/1` also across F6–F9; `norm/instantiate-def/3` also in F11/F12
(via `delta-reduction`); `mk-fresh/2`, `mk-fresh/3`,
`readable-term-/2`, `readable-term-/3` also across F15/F17/F18;
`parse/2` in F13 (already credited elsewhere).

## 8. Integration sketch and verification (next round, not this one)

A-verification, before any harness edit:

```sh
clj -M:test -e "(do (load-file \"test/yang/latte_oracle/h3_3_a_verify.clj\") (yang.latte-oracle.h3-3-a-verify/-main))"
```

Expected: `H3-3-A-VERIFY: PASS (43/43)`, exit 0. Any `FAIL` revises the
matching prediction above first (document + script together), never the
other way round. On this laptop the `:test` alias's test-runner main
also loads every test namespace and fails on the ungenerated ANTLR
parser classes (`yang.python.antlr.gen.Python3Lexer`) before reaching
the script — an environment gap, not a script failure — so the script
is run on an explicit classpath instead. The preparation round ran it
via `clj -Sdeps … -M -e "…"`; the credit-rule revision round re-ran it
via `java -cp` (classpath from `clj -Sdeps '{:paths ["test" "src/clj"
"src/cljc"] :deps {latte-kernel/latte-kernel {:git/url
"https://github.com/latte-central/latte-kernel" :git/sha
"86fca0d1d054af005cb37395eae04500d45e6279"}}}' -Spath`, then
`java -cp "$CP" clojure.main -e "(do (load-file
\"test/yang/latte_oracle/h3_3_a_verify.clj\")
(yang.latte-oracle.h3-3-a-verify/-main))"`). Observed output in the
revision round: 43 `PASS` lines and `H3-3-A-VERIFY: PASS (43/43)`,
exit 0. The A-side predictions are unchanged by the credit-rule
revision, and [`h3_3_a_verify.clj`](h3_3_a_verify.clj) is unmodified.

Integration (after design review; exact harness edits for the
integration engineer):

1. Add the §4/§5 rows to `kernel_gate.edn` (F1–F20 primaries and
   companions, then R1–R10 as `:negative? true` rows; one row per case
   where a group above lists two calls).
2. Register `:h3-3-readable-term-2`, `:h3-3-readable-term-3`, and
   `:h3-3-unregister-unparser` in `kernel-named-integrations`.
3. Land F2 as a non-crediting pass and keep the gate honestly
   `:blocked` on `registered-definition?/1` with the reason recorded
   in §7. Do **not** add any `registered-definition?` entry to
   `kernel-context-integration-names` or `kernel-context-extra-arguments`:
   the +1 trace-table mapping is withdrawn (§7), not deferred.
4. No declarations change is expected: no fixture here is a recorded
   deviation or upstream failure — every row is an ordinary `:pass` or
   `:negative?` row — so `slice9_declarations.edn` should carry forward
   unchanged unless integration discovers otherwise (if it does, that
   discovery is a finding, not a silent edit).
5. Run the focused gate test, refresh evidence with
   `write-kernel-gate-evidence!`, and audit that every §7 primary
   re-derives from both C-lane traces; publish credited/withdrawn/
   remaining sets with fixture IDs, including any §5 refusal IDs the
   harness lists as direct creditors (§5.1) and the integration gate's
   ruling on decision D1 below. Expected after state if all rows pass:
   164/165 exercised, the sole remainder
   `latte-kernel.defenv/registered-definition?/1`, and the gate still
   honestly `:blocked` on it — that claim belongs to the integration
   round's measured evidence, not to this document. (Under decision
   D1(b) the exercised count drops by the two solely-negative-credited
   arities named there unless completion witnesses are added for them.)

### Integration decisions this preparation does not make

**D1 — negative-row credit (from Finding 1).** The harness as it
stands credits passing `:negative?` rows (§2, §5.1). The integration
gate must choose; this preparation presents both options and takes no
position, and it changes no harness code either way.

- **(a) Accept the current behavior.** Consequences: the exercise is
  physically genuine — the target body is entered at the exact arity
  in both C lanes and the refusal outcomes agree on A/B/C — so a
  passing negative still traces the head at the exact arity it
  refuses. The behavior is already the de facto rule in the checked-in
  evidence (51 admitted arities list a negative creditor; 2 rest
  solely on one), so accepting it ratifies existing coverage without
  re-derivation. The costs are accounting costs: `:direct-fixture-ids`
  will list refusal IDs alongside (or, if a primary blocks, instead
  of) the §7 primaries for 9 of this family's arities (§5.1), so
  "exercised" no longer by itself proves a completion witness ran;
  integration must publish the full creditor sets and state whether a
  primary is required to be among them.
- **(b) Tighten `kernel-exercise-entries` to exclude `:negative?`
  rows** (a `(not (:negative? …))` condition in both the direct and
  the named-integration branches). Consequences: refusal companions
  become pure branch/refusal evidence, as this design originally
  assumed, and exercised status always rests on a completion or
  registered integration witness. The costs are corpus-wide: the two
  arities currently credited **solely** by a negative row
  (`latte-kernel.norm/beta-eq? 2` via `:beta-eq-false`,
  `latte-kernel.typing/type-of 3` via `:type-of-invalid`) revert to
  unexercised — 145 → 143 exercised on the current corpus, and
  162/165 after this family lands (remainders: those two plus
  `registered-definition?/1`) unless completion witnesses are added
  for them; the credited-ID lists of the other 49 arities shrink and
  all evidence must be re-derived and re-published; the
  acceptance-counting tests need explicit negative-row cases so the
  exclusion is itself tested. This is a harness change requiring its
  own review, separate from landing the fixtures.

**D2 — safe treatment for `registered-definition?/1` (requirement
sketch only).** The gate stays `:blocked` on this arity (step 3, §7).
Any future treatment that credits it must, at minimum: discriminate
by **arity and call origin**, distinguishing an
ambient-environment `/1` call made explicit from
explicit-environment `/2` and `/3` calls that produce identical bare
traces (§3); use **no global offset** — no blanket entry in
`kernel-context-extra-arguments` applied across a multi-arity
function; and be reviewed separately as a harness/trace-design
change, with the single-arity `unregister-unparser!` mapping treated
as no precedent for identity preservation. This sketch states
requirements only; it proposes no mechanism and authorizes no
implementation in the integration round.

Risks flagged for integration (predicted, not observed):

- F17–F19's guest path calls `meta` (in `readable-term-`). The guest
  library already serves `with-meta` (`:minimal-with-meta`), but if
  bare `meta` on a symbol is not served, those three rows block at
  admission/compile — the arity then stays unexercised and the
  residual rule applies.
- F2's A prediction depends on `gate-no-such-definition` resolving in
  no namespace visible to the A leg (§4.1). The integration round
  should keep that exact name and re-check if it ever fails.
- F11's composed registration call must survive `kernel-call` rewriting
  (precedent `:entry-delta-local` says it does); F12's quoted argument
  list `((d) [(λ [x ✳] x) y])` mixes a reference and an application —
  if the guest admission rejects either shape, split F12 into two rows
  stepping one argument each rather than weakening the call.

Open questions for the design reviewer:

- Decision D1 (§8): does the integration gate accept negative-row
  credit as the harness stands, or tighten `kernel-exercise-entries`
  to exclude `:negative?` rows? (Either ruling is consistent with
  this preparation; the §5.1 audit tabulates what changes.)
- Decision D2 (§8): is the arity- and call-origin-preserving
  requirement sketch the right basis for a separately reviewed
  treatment of `registered-definition?/1`, with the gate remaining
  honestly `:blocked` on that arity until such a review lands?
- Should F20's registry-shape consequence (§6.1) also be pinned as a
  B/C state assertion at integration (the slice-8 registry-replay
  already snapshots it), or is A-side pinning plus the `unparse`
  outcome sufficient?
- F7b/F7c, F10b, F11b, F14b, F15b, F16b, F17b and
  `:h3-3-nbe-evaluation-var` are redundant companions: integrate them
  all (branch/edge evidence), or only the primaries plus §5? This
  design recommends integrating them — witnesses that exist only in a
  document do not survive compiler changes — but they are severable
  without touching any primary credit.






