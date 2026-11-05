Created-GMT: 2026-11-06 02:30:00 GMT
Coding-Agent: qwen3.8-max (ZCode subagent)
# Independent review of the attribution-as-query ruling DRAFT v2 (the input to v3)

## Independent architecture review — attribution-as-query ruling DRAFT v2

### 1. R1 provenance record — sufficient? minimal?

**Mostly right in shape, with three concrete gaps.**

- **Where the data lives is unstated, and the obvious home is closed.** R1 says "ordinary, address-linked data," but the module-manifest schema is version 1 with a **closed key set** — `manifest-keys` at linker.cljc:739-745, and any key outside the set is a `:manifest-shape` defect (:780-783). The draft correctly refuses to extend the linker's §8.1 derivation records, but never says the adaptation manifest lives *outside* the linker schema (out-of-band recorded datoms vs. a reviewed schema-2). An implementer could shoehorn it into schema 1 and defect. This must be stated.
- **Guest-arity→upstream-arity correspondence is the load-bearing join and is not named.** The existing mapping is the hardcoded offset table `kernel-context-extra-arguments` (latte_oracle_test.clj:1786-1791) consumed by `kernel-executed-arity?` (:1812-1820). R1's "call-shape correspondence" should explicitly subsume and retire this offset table — otherwise two parallel mapping mechanisms coexist and the query and the accounting test can disagree.
- **Adapter identity stability is unaddressed.** R1 says "adapter identity (its UAST address)," but the adapter is not a separately linked module: `kernel-compile` (:1823-1839) inlines `context.edn` + `context-upstream-form` rewrites (:872-904) into **each fixture's program**, so UAST addresses differ per fixture unless canonicalized. The draft needs one sentence on how the adapter address is made joinable across the 106 fixtures (canonical source hash + per-fixture derivation, presumably).
- Overengineering risk is modest but real: the `kernel-rewrite`/`context-upstream-form` transforms include per-form special cases (e.g. the `free-vars` let rewrite :1469-1472, the `elab-proof-step` rewrite :1500-1504). R1 should record these as *address-linked references to the rewrite definitions*, not re-encode each rewrite as data.

### 2. R2 refusal battery — right four cases? what slips?

The four cases (explicit-empty `/2` refuses `/1` credit; unexecuted `/1` beside executed `/2`; mismatched program/adaptation; missing lane) are the right *floor*, but two cases slip through:

- **Trusted-declaration slip (wrongly credits).** R3 attributes by "the fixture's *declared* ambient call shape." If a fixture *declares* ambient `/1` but actually passes an explicit empty environment, the declaration credits `/1` wrongly — and this is exactly the value-identical case the draft itself identifies (draft §Problem). The battery has a mismatched-*program* case but no mismatched-*declared-call-shape-vs-recorded-invocation* case. The linkage must verify the declared shape against recorded call-site/argument evidence, and the battery needs that fifth refusal.
- **Indirect-origin slip (wrongly refuses or wrongly credits).** The trace explicitly includes indirect calls (`kernel-trace-form` docstring, :1794-1796: "including indirect calls"), and the gate doc (slice9_gate.md:82-83) covers "higher-order calls through registries and extension closures" — e.g. `unfold-typed-implicit` applies `(:implicit-fn implicit-def)` (context.edn:117-118). R2 never says how call-origin linkage behaves when the entry originates through an indirect application. Default must be refusal unless linked; add an indirect-call battery case.

Also: **R2 specifies an obligation but no mechanism.** Nothing in the draft says what artifact constitutes the "verifiable connection" (call-site id in the trace? separate per-site streams? metadata?). Leaving it unspecified makes the battery the only check and invites the implementer to invent the weakest thing that passes. See Q3.

### 3. Simpler design the draft misses

Yes. The minimal sufficient mechanism is: **extend `kernel-trace-form` (:1794-1809) to emit `[fname arity call-site-id]`, where call-site-id is a compile-time-unique site tag, plus a fixture-declared site→upstream-arity table recorded in the evidence.** Since upstream `/1` (defenv.cljc:102, delegating `(registered-definition? empty-env dname false)`) and upstream `/2` (:103) collapse to the *same guest callable at the same guest arity* (context.edn:56-58), the only discriminator needed is *which source call site* entered the body — and that is available at instrumentation time for a one-line change. Disjointness by site id achieves the R3 guarantee without an address-linked adaptation manifest, and the independent audit can re-derive from the same two raw C traces it already uses (slice9_gate.md:145-147).

The draft's heavier R1 is justified **only if** the upstream→adapter correspondence must be reusable address-linked data for consumers beyond the gate audit (other frontends, future slices). The draft never makes that case. Amendment: either justify R1's full manifest on those grounds, or descope R1 to what the audit needs and keep the call-site-tag as the R2 mechanism. (Both can coexist: call-site id is the R2 evidence; R1 is the durable correspondence record.)

### 4. Broken interactions

- **F2 pin contradiction (the sharpest one).** R5 re-credits `/1` conditionally, but the current pins assert the opposite *as the accepted rule*: "F2 stays honestly non-crediting: no registration, no trace-table mapping" (:2355-2356), `not (kernel-executed-arity? f2 ... 1)` (:2395-2396), and `not (contains? kernel-named-integrations :h3-3-registered-definition-ambient)` (:2356). Acceptance says "No existing valid pin regresses" (draft :55). Crediting `/1` *necessarily* flips the F2 pins. The draft must explicitly authorize revising the F2/H3-3 pins as part of the re-credit, or define which pins count as "valid." As written, R5 and the acceptance criterion contradict each other.
- **Instrumentation budget.** slice9_gate.md:89-93 documents the 1,000,000→2,000,000 instruction bound *because* body-entry stream writes add guest instructions. R2's added origin linkage increases per-call trace volume again; the draft never restates a budget/capacity change (`stream/make 1000000` at :1827, drain at :1849-1854). Must be acknowledged or the harness can start truncating traces silently.
- **D2 token packets / `cell/authentic?`** — R6's "untouched" is fine; both exist and the query as described doesn't route through them. No conflict found.
- **Other frontends' prelude sweeps** (e.g. typescript prelude.cljc): the draft doesn't say whether R1/R2 obligations bind them now or set a pattern for later. One scoping sentence needed; not blocking.

### 5. Scoping inventory — anything material absent?

The table faithfully carries astra's inventory, but two rows are incomplete:

- **Manifest schema layer missing.** Given the closed `manifest-keys` (linker.cljc:739-745), "adaptation manifest" (Compilation row) needs a row/decision of its own: in-schema (requires a reviewed contract extension) or out-of-band datoms. This is the same hole as Q1.
- **Trace/budget layer missing.** The "Execution evidence" row lists linkage as missing but not the concomitant stream capacity / instruction budget revision (Q4).
- Minor: the inventory's "Gate accounting" row correctly names `dao.space.query/q`, which exists (dao/space/query.cljc), so the query target is real — good.

### 6. VERDICT

**Do not sign off yet — amend v2 with: (1) name the R2 linkage mechanism (call-site id is the minimal sufficient one) and add a fifth battery case for declared-shape-vs-recorded-evidence mismatch plus an indirect-origin case; (2) resolve the R5-vs-"no valid pin regresses" contradiction by explicitly authorizing the F2/H3-3 pin revisions; (3) state where R1's data lives relative to the closed schema-1 manifest key set; (4) state adapter-address stability and retire `kernel-context-extra-arguments` into the recorded correspondence; (5) restate the instrumentation budget change.** Otherwise the central principle, the R4 spec-target correction, and R5/R7 are sound and astra's four findings are genuinely incorporated.
