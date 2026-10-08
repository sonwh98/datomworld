Created-GMT: 2026-09-14 14:06:00 GMT
Created-Local: 2026-09-14 21:06:00 +07
Coding-Agent: claude
Session-ID: fresh session (do not resume any prior thread)

# Task: Design the tuple representation of the semantic VM's code

Role: Architect — design document only. No implementation, no staging, no commits.

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-14 21:06:00 +07 | Status: active | Rationale: authored the UCF protocol draft this design extends; the reviewer will be gpt-6-astra (different family), independence preserved.

## Settled owner rulings — inputs, not open questions

These seven rulings were made by the owner on 2026-09-14 evening, live-verified by the orchestrator. Their *implementation* is fully in scope; re-litigating them is not.

1. **The Universal AST becomes flat tag-first positional tuples** (Erlang abstract format shape): `[:literal 10]`, `[:lambda ['x 'y] body]`. The walker's semantics are unchanged — every dispatch is on `(:type node)`, so tuple dispatch is mechanical. The motive is Unison-style content-hashing of the AST.
2. **Grammar discipline:** fixed arity per tag; `:application`/`:dao.stream.apply/call` carry an operand *vector*; `tail?` is a saturated trailing boolean (mirroring the segment grammar's `[:call argc tail?]`); loader-applied defaults are materialized in the tuple (saturation); no source positions and no `:macro?` in the canonical form — provenance and metadata are side tables keyed by AST address.
3. **`dao.space.query/q` performs Datalog over these tuples directly** though they are not `[e a v t m]` — verified live. The queryable shape is the flat per-node projection `[id tag & slots]` (child slots hold node ids); fixed arity per tag makes each tag's rows homogeneous so where-patterns partition by arity with no padding.
4. **The semantic VM is designed around this:** positional tuples are the canonical code form at both levels (AST nodes, segment instructions); loading tuples is the primary path; validation *is* the tuple grammar; the UCF §7.6.1 dependency fixed point recasts as Datalog over the same tuples; the hot loop never queries (it decodes tuples to the image at load).
5. **Code-on-stream is tuples end-to-end.**
6. **`t` and `m` are higher-level tuples over code addresses** — the hash chain IS the ledger. Level 0: code tuples, content-addressed, immutable, no t/m in identity. Level 1: ledger tuples `[t op address ref?]` where `m` becomes an open op vocabulary (`:assert`, `:retract`, `:derive`, `:expand`, `:publish`) and `t` is the ledger's clock. `:yin.code/derived-from`, the macro expansion ledger, and UCF §7.7's occurrence ledger are rows of this one relation. Ledgers interoperate by hash.
7. **Datoms are the reference layer:** `[e a v t m]` with `a` like `:yin/code` and `v` = the content address — git refs / Unison namespace entries (name → hash). Objects (tuple code under dao.jing by address) / refs (tracking datoms: naming, history, retract, custody, indexed) / projection (only where entity-shape over code internals is wanted).

## Verified facts you must build on

All verified 2026-09-14 evening by the orchestrator; re-verify before citing, then cite file:line.

- **Dispatch surface:** `src/cljc/yin/vm/ast_walker.cljc:359` and `:600` (cold + hot `case type`), `src/cljc/yin/vm/linearize.cljc:91` and `:234`, the codec's `case` at `src/cljc/yin/vm.cljc:413`. Frame continuations (`:eval-operator` etc.) are runtime state, not syntax — they stay maps.
- **The codec already saturates:** `(or (:prefix node) "id")` at `v2.cljc:444`, `(or (:buffer node) 1024)` at `:453`.
- **`ast->datoms` already throws on `:vm/store-update`** (absent from its `case`, `v2.cljc:413-478`) — that node carries a host `:fn` in syntax and is already outside the persistent form. The codec also has a `:stream/close` arm (`v2.cljc:465`) the walker lacks. The grammar table must resolve both boundaries (see Task §3).
- **The AST is already a DAG:** pre-assigned `:eid` sharing, emitted once and referenced (`v2.cljc:394-411`). Unison-style sharing-by-content-address replaces this: a shared subtree appears once, referring sites carry its address.
- **`dao.jing/segment-key` over tuple ASTs is deterministic and operand-order-sensitive** (live-verified: `(add 1 2)` ≠ `(add 2 1)`); `[[:literal [1 2]]]` and `[[:literal '(1 2)]]` hash identically — the transitional encoder's type-loss (`order-normalize` coerces lists→vectors, `src/cljc/dao/jing.cljc:45-64`). This is a **declared prerequisite inherited from dao.jing's open items — declare it, do not claim it closed.**
- **Query mechanics:** the general where-path is exact-arity positional unification (`eval-pattern-clause` → `unify-slots`, `src/cljc/dao/space/query.cljc:894` and `:799`; `tuple-shape-matches?` at `:439`); the 3-slot EAV fast path (`:899`) engages only for explicit `fact-relation` values. Live-demonstrated: per-tag queries, 3-clause joins across mixed arities (arity 5→5→3), pc-prepended segment rows `[pc tag & ops]`, DAG-sharing joins via a `:fns` predicate, ledger as-of queries (assert ≤ t < retract), and ref-datoms joined to code tuples through the address — the 3-slot ref pattern takes the indexed fast path while code tuples take the general path, in one query. Gotchas to encode as rules if you cite them: `match` (`query.cljc:520`) is constant/wildcard selection and does not bind `?vars`; `q`'s `:fns` option keys are quoted symbols.
- **The committed `yin.vm.semantic.md` §2.2** (commit `3325815`) already types `:yin.code/hash` as the segment address of the canonical instruction vector — ruling 7's shape, already in the doc family.

## Task

Create exactly one file: `docs/design/yin.vm.tuples.md` — the design document for code-as-tuples. It must contain, in an order you judge:

1. **Stance and layering** — objects / refs / projection (ruling 7), one section fixing the vocabulary. State what each layer is for and what never crosses between them.
2. **The AST tuple grammar** — a complete table over every node type the walker's `case` arms dispatch on (read `ast_walker.cljc`; the codec's `case` at `v2.cljc:413` is the current inventory to reconcile against). Fixed arity per tag, operand vectors, saturated `tail?`, saturation rules, and the exclusions from canonical form (source positions, `:macro?`, provenance → address-keyed side tables).
3. **The two boundary calls, made and stated** (no option menus): `:vm/store-update` (the orchestrator's recommendation, which you may adopt or overrule with reasons: restrict the `:fn` slot to a named primitive symbol resolved and profile-checked per UCF §7.5.2, or exclude the node from the canonical grammar) and `:stream/close` (one grammar truth — either it exists in both codec and walker or neither; state which).
4. **Addressing** — whole-tree `dao.jing/segment-key` as the merkle grain (all named payoffs — `:yin.code/derived-from` as content address, macro ledger `{addr-in → addr-out}`, module manifests pinning AST content, lowering as an address-to-address function — work at this grain); per-lambda subterm addressing declared a follow-up, not a blocker. Sharing-by-content-address replacing `:eid`.
5. **The segment level** — *reference* UCF §7.3.2 (`docs/design/yin.vm.universal-continuation-format.md`), do not duplicate it; state the hash chain (AST address → segment address) and lowering as a verified pure function between two content-addressed forms.
6. **Querying** — the flat per-node projection `[id tag & slots]`; segment rows `[pc tag & ops]`; `q` over both (cite the verified mechanics); refs as datoms joined by address; and what queries deliberately go to the datom projection instead (attribute-dimension queries with unbound attribute, `pull`/`entity-attrs` navigation, scale indexing under covered indexes).
7. **The VM around it** — load paths: direct tuple loading primary, datom-batch loading as the projection path, both-paths-same-image as the VM's own conformance test; validation = the tuple grammar (mnemonic/tag set, arity, operand kinds, saturations in place); dependency closure as Datalog over the same tuples (the recast of UCF §7.6.1); the hot loop never queries.
8. **The ledger and refs** — ruling 6 + ruling 7 as one design: tracking datoms `[e a v t m]` with `v` = address; the open op vocabulary; as-of queries as ledger joins; how `:yin.code/derived-from`, the macro expansion ledger, and UCF §7.7's occurrence ledger are rows of the one relation.
9. **Migration surface, honest** — the mechanical sweep (walker cold+hot cases, linearizer, tests constructing map ASTs); the observer/program-input lane accepting tuple collections (**shape unverified — flag it as an integration item to scope, not a solved detail**); what stays on datoms and why.
10. **Acceptance blockers / open items** — the dao.jing type-preserving encoding prerequisite (declared, inherited); anything else your design genuinely cannot close (say what and why; do not pad).

## Constraints

- Create exactly the one file above. Edit nothing else. Stage and commit nothing.
- Every sentence in the design is a rule of the proposed protocol/design; make calls and state them. No option menus, no "alternatives considered" padding. Where you must assume, say the assumption in one line.
- House doc style: a Status header (Proposed), a subordinate-docs paragraph naming `datom.world.md`, `yin.vm.semantic.md`, `yin.vm.universal-continuation-format.md`, `dao.jing.md`, `dao.space.query.md`; file:line citations for every load-bearing claim about current code; tables for grammars. Read the sources — do not trust this brief's line numbers blindly.
- The seven rulings are owner decisions; their implementation is in scope, the rulings are not.

## Deliverable

Begin your final response exactly with:

Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and named timezone>
Coding-Agent: claude
Session-ID: <your session id>

Then: the file path and line count; a one-paragraph summary; the section list; the boundary calls you made (§3) with your reasoning in two lines each; and a list of which load-bearing claims you verified against source yourself versus took from this brief.
