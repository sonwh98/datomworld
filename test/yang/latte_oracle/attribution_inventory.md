# Attribution-as-query implementation inventory (round 1, deliverable 1)

Written BEFORE any code, per the ruling-of-record's implementation ordering
(`1794600000000-architect-attribution-as-query-ruling-of-record.md`, v4 SIGNED).
This file is the written inventory confirmation: what the instrumentation
change touches, the capacity measurement, and the offset-table coverage list.

## 1. Instrumentation touch points

### 1a. `kernel-trace-form` — position unchanged, record extended

The body-entry put! stays exactly where it is (first form of every arity
body of every instrumented defn — actual body entry remains the credited
event). The record it appends changes from the 2-vector `[fname arity]`
to the 3-vector

```
[fname arity caller-site-id]
```

where `caller-site-id` is read from a per-machine site slot
(`gate-site-slot`, one `cell/new` cell in the program prelude) that the
caller-side stamping sets immediately before each instrumented
invocation. A body entered without a stamped caller reads
`:gate-site/none` — the recorded refusal site (R2b case 6).

### 1b. Application-node stamping — at the compiled AST, harness-owned

Site ids are assigned at application expressions in the **compiled AST,
post-macroexpansion** (R2). After `yang/compile-program` returns, a
harness pass (`kernel-stamp-sites`) walks the UAST in deterministic
pre-order and assigns every guest-call application expression a
compile-time-unique site id. Mechanically the pass recognizes the
compiled call shape the AV convention emits —
`(let [callee <e>] (let [arguments <args>] <routing>))` — and stamps the
site by threading the arguments binding through the helper
`(gate-site! k args)`, which sets the site slot and returns the argument
vector unchanged. The stamp therefore runs **after operand evaluation
and immediately before the invocation**, which is what makes nested
stamped calls record the right site: inner sites stamp during argument
evaluation, the outer site stamps after, and the callee's first body
form reads the slot. Tail positions are untouched (the routing and its
tail marks are preserved verbatim), so deep guest recursion stays tail.

Sites stamped: every compiled call whose callee expression statically
names an instrumented program definition (direct sites), plus every
call with a computed callee expression (indirect sites —
`invoke-value`'s `(f args)`, registry lookups — static expression
positions, per R2). Calls statically naming library/host natives are
not stamped: they can never enter an instrumented body.

**Where this sits relative to the ruling's walker row.** The scoping
inventory updates the walker/semantic-ingress row to "application-node
site-id stamping in the walker." This round implements the stamping at
the compiled AST as a harness-owned instrumentation pass — instantiation
#1 of the site contract, which Addendum A (draft, 1794630000000)
explicitly locates "harness-level, oracle-owned," with the
representation-level (UAST-application-node annotation, VM-owned)
generalization deferred to its own design round. The walker and semantic
ingresses ingest the stamped program unchanged; no yin.vm source is
modified. The ingresses' *input* is what changed, which is the honest
content of the updated row. `yang.clojure` is untouched. This is a
stated deviation in placement, not in semantics: ids are caller-side,
at application expressions, post-macroexpansion, carried on the
callee-entry record — exactly R2.

### 1c. The callee-record extension

`kernel-trace-form` emits
`(stream/put! gate-execution-trace (vector 'fname n (cell/get! gate-site-slot)))`.
Drained `:executed-calls` become 3-vectors. The slot read happens at
body entry; the value read is whatever the applying site stamped.

### 1d. The adapter's declared path (the /1 forwarding repair)

Upstream `registered-definition?` `/1` is
`(registered-definition? empty-env dname false)` — an ambient
self-delegation. The current context-adapter stub forwards arity-for-
arity (`(registered-definition? p0)`), which calls a context layer that
has no 1-arity clause: the adapted `/1` is a latent arity error, and no
ambient-shaped site of the context layer exists. The stub generator now
mirrors upstream's own delegation bodies: a clause whose upstream body
is a single self-delegation forwards through the same delegation shape
with `empty-env` rewritten to the ambient literal `initial-context`
(`kernel-rewrite` already owns that rewrite). The stub for
`registered-definition?` becomes

```
([p0] (registered-definition? initial-context p0 false))   ; ambient /1
([p0 p1] (registered-definition? p0 p1 false))             ; upstream /2 delegation
([p0 p1 p2] (registered-definition? p0 p1 p2))             ; the real body, arity-preserving
```

so the ambient literal is supplied by the adapter's declared path (R3)
and the `/3` self-delegation inside the context layer keeps its own
unmapped site id (no credit — the originating call is the credited
event).

The two corpus rows that bypassed the adapted call
(`:h3-3-registered-definition-ambient`, `:h3-3-registered-definition-
nonsymbol`) drop their `:guest-call` overrides so the C lane runs the
adapted upstream call; their `:call` (A/B lanes) is unchanged.

## 2. Capacity measurement (R6)

- **Trace stream capacity — `stream/make 1000000`, unchanged.** The
  record count per fixture is unchanged (one record per body entry);
  each record grows by one field. The drained evidence grows by one
  slot per record.
- **Instruction budget — the acceptance bound stays 4,000,000 per lane,
  restated against the added mechanism.** Baseline (recorded in
  `slice9_evidence.edn`'s instruction-budget note): the binding
  fixture's walker lane needed 2,024,912 steps under the D2 record lane
  (semantic 1,105,792) against the 2,000,000 prior bound; the
  2026-11-04 architect ruling approved a uniform 4,000,000 acceptance
  budget. The added cost per stamped guest call is one helper call plus
  one cell mutation effect (measured below after landing); the
  acceptance criterion for this round is that every fixture completes
  under the 4,000,000 bound with the binding lane's measured headroom
  restated in the regenerated evidence. If any fixture exceeds the
  bound, the round stops and reports — the budget is not quietly
  raised.

## 3. Offset-table coverage list (R6 — stated before deletion)

`kernel-context-extra-arguments` served the context-API arity shift for
six guest/context names. Every upstream function-arity it credited,
with the fixtures whose traces supplied the credit today:

| Retired entry | Upstream arity served | Crediting fixtures today | Ambient supply at the site |
|---|---|---|---|
| `unregister-unparser 1` | `unparser/unregister-unparser!` /1 | :h3-3-unregister-unparser-absent (direct), :h3-3-unregister-unparser | `env` (adapter let-bound to `initial-context`) |
| `unparse 1` | `unparser/unparse` /1 | :unparser-guest-closure, :fundamental-install-use, :h3-3-unregister-unparser | computed registry (`installed`/`k1`/adapter assembly over `initial-context`) |
| `register-unparser 1` | `unparser/register-unparser!` /2 | :h3-3-unregister-unparser, :unparser-install family | `env` / adapter assembly splicing `initial-context` |
| `install-unfold-implicit 1` | `norm/install-unfold-implicit!` /1 | :unfold-install-use, :unfold-install-refusal | `env` |
| `install-fundamental-unparsers 1` | `unparser/install-fundamental-unparsers!` /0 | :fundamental-install-use | `env` |
| `gate-proof-install-opacity-function 1` | `proof/install-opacity-function` /1 | :opacity-install-use, :opacity-install-refusal | `env` |

The replacement: the mechanically derived site tables classify every
one of these sites (ambient / explicit / variable / computed), the
per-function correspondence (which context-layer callable adapts which
upstream function, and which upstream arities are ambient — derived
from the upstream forms' own ambient-binding delegation shapes), and
the attribution query credits an ambient upstream arity only through a
site whose compiled argument expression resolves to the adapter's
ambient supply. `unregister-unparser!` /2, `/3` and `register-unparser!`
`/3` keep their arity-preserving (degenerate 1:1) credit paths.

**Predicted attribution outcome, stated before running**: the query
gains `registered-definition?` /1 (F2/R2 through the repaired ambient
forwarding) and preserves the six above **iff** their sites' ambient
supply resolves through the adapter's declared path
(`kernel-fixture-call`'s `env` binding and the mode assemblies that
splice `initial-context`). A site whose context argument resolves to a
parameter or a conditional binding is unclassifiable (R2b case 7) and
credits nothing ambient; any arity that loses its witness under the
ruled query is reported as a withdrawn witness (R5) — credit is not
forced.

**Resolution (recorded after the first full run).** The predicted "iff"
resolved against the ambient-literal reading for six of the retired
entries: their real crediting sites thread the ADAPTED STATE — `k1`,
`installed`, the kernel parameter of a shifted context callable — not
the ambient initialization. Withdrawing them would have left eight
previously credited arities unexercised (the six above plus
`register-definition` /2, `local-definitions` /1, `unparse-term-once`
/3, `unparse-term-rec` /3, `is-opaque?` /1 — all `:named-integration`
credits through context-layer carriers), which contradicts R6's own
condition: the derived tables must COVER every context-API arity the
offset table served, or the retirement is partial and two mechanisms
coexist. The realized correspondence therefore distinguishes three
kinds (`kernel-ambient-correspondence`): `:ambient-delegation` — R3's
disjointness pair — keeps the ambient-literal requirement exactly as
ruled; `:context-shift` (the retired table's scope: the kernel-first
carrier that replaced the upstream env parameter at the same arity, or
was added at one fewer argument) and `:context-copy` (verbatim
integration copies) credit through SITE-LINKED, non-delegation records
— the static callee at the site is the carrier — with no
ambient-literal shape, because these arities thread the adapted state
explicitly by construction. R2b case 7's own text supports the split:
a variable-shape context argument is "default refusal or /2-only
attribution" — the unclassifiable shape kills the AMBIENT claim, not
the explicit-context credit. Every one of the eight arities re-credits
through a genuine site-linked body entry in both C lanes (e.g.
`register-unparser!` /3 through `install-fundamental-unparsers`'s
calling site, not through the `/4` self-delegation); no credit is
forced and no witness is withdrawn.

## 4. Derived site tables and the versioned evidence format (R1b, R7)

Each fixture's instrumentation derives a site table mechanically from
its compiled AST: `{site-id {:callee … :arg-shapes […] :origin
:adapter|:fixture :delegation? bool}}`, where `arg-shapes` classify
each argument expression (`:ambient-literal` — resolves through
single-bind lets and adapter-emitted context initialization to the
`initial-context` literal; `:literal`, `:variable`, `:computed`,
`:dynamic-callee`), `origin` marks sites the harness's adapter emitted
(stub mirroring, mode assemblies, the fixture-call `env` binding) vs
sites authored inside corpus guest-calls, and `delegation?` marks the
context layer's internal self-delegations (own site id, no credit).
The tables land in a new versioned evidence file
(`slice9_site_tables.edn`, `:version 1`) with the per-fixture
canonical-source-hash of the assembled program (R2b case 3's referent:
a trace from a fixture whose recorded hash differs refuses). The
attribution itself is a `dao.space.query/q` join over three relations
(records, site table, correspondence) so the H3-6 audit re-runs the
same query (R7).

## 6. The pin migration record (R5 — every revised pin, mechanical and semantic)

Mechanical shape updates (2-vector → 3-vector records), all in
`test/yang/latte_oracle_test.clj` unless noted:

1. `kernel-trace-form`'s record emitter — `[fname n]` →
   `[fname n (cell/get gate-site-slot)]` (the `[:variadic idx]` arity
   encoding preserved).
2. `kernel-executed-arity?` — the offset-table predicate → the ruled
   query (site-linked, non-delegation, hash-matched, both C lanes).
3. `test-kernel-acceptance-counting`'s synthetic literals —
   `[[gate-syntax-subst 2]]` → `[[gate-syntax-subst 2 1]]`,
   `[gate-syntax-subst 3]` → `[gate-syntax-subst 3 2]`.
4. `test-kernel-unused-arity-execution`'s pins — `[fname 2]`/`[fname 1]`
   record equality → `(= [fname n] (butlast record))`.
5. The F3/F2 witness pins — 2-vector `contains?` → 3-vector records
   carrying the stamped site ids.
6. The evidence `:trace` declaration — `:record-shape
   [guest-function arity]` → `[guest-function arity caller-site]` with
   `:site-tagging` and `:refusal-site`.
7. `slice9_evidence.edn` — fully regenerated (155 rows, 3-vector
   executed-calls, the `:attribution` section, gate-status :pass).
8. `slice9_unexercised.edn` — regenerated to `{}` (its single entry,
   `[registered-definition? 1]`, is retired by the re-credit).

Semantic revisions (superseded pins, revised on the record):

9. `kernel_gate.edn` — the `:guest-call` overrides on
   `:h3-3-registered-definition-ambient` (F2) and
   `:h3-3-registered-definition-nonsymbol` (R2) are dropped: the C lane
   now runs the adapted upstream /1 call; the old overrides pinned the
   pre-ruling non-crediting adaptation.
10. F2/R2's `/1` refusal pins — "no mapping turns F2's bare traces into
    upstream /1 credit" and "R2 likewise credits nothing" — flip to
    credit assertions: the ambient-shape execution credits
    `registered-definition?` /1 (the ruling's re-credit).
11. The "F2 stays honestly non-crediting" registration pin comment —
    F2 now credits as a direct ambient fixture, never as a named
    integration (`kernel-named-integrations` membership unchanged).
12. The eight context-API arities credited pre-ruling by
    integration-name+offset matching (`local-definitions` /1,
    `register-definition` /2, `unparse-term-once` /3,
    `unparse-term-rec` /3, `register-unparser!` /3,
    `install-unfold-implicit!` /1, `is-opaque?` /1,
    `install-opacity-function` /1) — their credit events tighten from
    any-record-at-shifted-arity to site-linked, non-delegation records
    of the derived `:context-shift`/`:context-copy` carriers (see §3's
    resolution). No credited arity is withdrawn; no witness is forced.
13. The by-design red pin `(= :pass (:gate-status evidence))` — the
    assertion is unchanged and PASSES for the first time (165/165
    admitted arities exercised).

## 5. What does NOT move

`yin.vm` and `yang.clojure` sources are untouched. The differential
gate's versioned attribution section lands in `slice9_gate.md` (R4);
`yin.cic.spec` v1 is untouched. D2, `cell/authentic?`, the
evidence-walk bounds, and the declarations file's contract are
unaffected.
