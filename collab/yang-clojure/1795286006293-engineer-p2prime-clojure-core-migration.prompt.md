Created-GMT: 2026-11-22 02:13:26 GMT
Created-Local: 2026-11-22 09:13:26 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: yang.preludes P2′ — unified clojure.core + yang.clojure migration

Role: Engineer

Implementers:
- Model: gpt-6.2-sol | Assigned: 2026-11-22 09:13 +07 | Status: active | Rationale: same proven model and session continuity with the P1′ lineage; fresh session for a new slice

## Working tree

Work EXCLUSIVELY in `/Users/sto/workspace/dw-p2prime` (branch `feat/p2prime-clojure-core`, based on master `f51c3a0d`, which contains the landed P1′ at merge `3749514d`). `git add -N` every NEW file (including tests). Do NOT touch `/Users/sto/workspace/datomworld`.

## Prior slice context (landed — build on it, do not redo)

P1′ (§4.3/§4.4/§7.4, review-closed) delivered: `src/cljc/yang/rt/effect.cljc` (`yrt.effect`), `src/cljc/yang/rt/int.cljc` (`yrt.int`), unified trusted `clj` in `src/cljc/yang/clojure/prelude.cljc` (two-step `make-callable`, `invoke-callable`, `prim-get`/`prim-assoc`, private provenance doors, bundled-mode content-address enforcement via `src/cljc/yin/vm/provenance_scope.cljc`), `dsg__call_token`/`dsg__intra_call` retired, guest try/catch delivering through 5-slot `:handler` frames, and `dsg__add`/`dsg__lte` for bundled `clj`. Lesson from that slice: RUN THE FULL NAMESPACES (`clj -M:test -n yang.clojure-test` etc.), not `-v` selections — partial selections let a 38-failure breakage through.

## Governing design

`docs/design/yang.preludes.md` r8 — read §7.5 (the two-module `clojure.core` contract — your core spec), §9 roadmap row "P2′" (your acceptance gates), §6.2 (core vs frontend ownership), §8.1 (portability), and the reviewer-binding conditions already folded into the doc. Key §7.5 requirements:

1. **`clojure.core.impl` — pure, stateless implementation module:**
   - Banned from `:ambient-projections`; zero direct provenance doors; ZERO state cells.
   - Requires `yang.call`, `clj` (the unified trusted bridge), `yrt.int`. Guest policy tags (`:coll/unsupported-key`, `:math/*`, `:int/*`) live in the handler contract installed by the owning entry (plain `yang.clojure`'s own cold-error policy AT P2′ — you install it here; `clj.py/run-main` only at P3′); only underlying VM effects are declared through the linker footprint (`yin/vm/linker/publish.cljc`).
   - Every definition prefixed `impl-*` (`impl-identity`, `impl-get`, `impl-assoc`, `impl-conj`, …) so internal calls to `(get m k)`, `(assoc m k v)`, `(conj xs x)`, `(first xs)`, `(rest xs)`, `(vec xs)`, `(nil? x)`, `(empty? xs)`, `(not b)` resolve directly to VM primitives — zero bare primitive store collisions.
   - `impl-cell-ref?`: authenticated callable cells answer FALSE to `coll?`, `map?`, `set?`, `vector?`, `sequential?`, `seq?`, `list?`, `reduced?`; `impl-fn?` matches `clj/admitted-callable?` 1-to-1.
   - Host-backed operations per §7.5's list: `impl-inc`/`impl-dec`/`impl-rem`/`impl-int?` via `integer/*` + `yrt.int/check-limit` and `float/add`/`float/sub`; `impl-not=` via `data/equiv`; `impl-str` deterministic renderer (ints → `integer/format`, floats → `float/format`, keywords/symbols → `kind/namespace`+`kind/name`, maps/sets → `data/canonical-entries`, cells → `"#<fn>"`); seq/collection ops normalizing maps/sets via `data/canonical-entries`; mutators/lookups enforcing `data/admit-key?`. Excluded: `var?`, `resolve`, `invoke-value`.
2. **Public cell-free facade `clojure.core`** (`src/cljc/yang/clojure/core_module.cljc` is the design's suggested home — follow repo naming conventions if they differ): requires `clj` and `clojure.core.impl`; exports only cell-free pure functions at install time — idempotent 0-arg `init!` building `{sym cell}` via `clj/prim-assoc` + `clj/make-callable`, 1-arg `export` via `clj/prim-get`, and 0-arg cell-accessor exports `(fn [] (clojure.core/export (quote <name>)))`. Dynamic closures (`impl-comp`, `impl-partial`) construct cells via `clj/make-callable`; all dynamic callbacks invoke via `clj/invoke-callable`.
3. **Migrate `yang.clojure` onto unified `clj` + `clojure.core`**: retire its inline `library-for` duplication and unit-local paths so there is ONE unified Clojure runtime.

## Acceptance gates (from §9 P2′ row — all must hold)

- **The locally-bound-map `:k` → 9 fixture** (the §9 H7 repair): native collection invocation through the new standard library — including the landed fixture where a locally bound map invoked with `:k` returns 9, which requires the native data-call dispatch to admit locally bound maps.
- **Bundled primitive shadowing**: bare `get`/`assoc` resolve through the source store before VM primitives (bundled `yang.clojure` suite must prove shadowing through the new standard library).
- Unified `yang.clojure` suite green — FULL namespaces, not selections.
- Goldens: refresh `yang.clojure` goldens cleanly; py/js/php goldens byte-identical (verify with git diff; their preludes remain untouched).
- `clj ./bin/analyze-deps.clj` clean (no cycles, no multi-node SCCs; P1′ baseline has no self-loops).
- The real cross-syntax `clj → clj.py → clj` unwind gates ONLY at P3′ — not yours.

## Out of scope

- py/js/php preludes and goldens; the python lowerer and descriptors (P3′, php-seat-coordinated surface); `clj.py` adapters; Track B.
- Known pre-existing master reds (excluded from your nets, do NOT fix): `yang.python.antlr.linked-prelude-test` 71-vs-72 (php seat's); `yang.python.antlr.float-address-test` 5 failures; the cljd `-map-lit` compile failure in `yin.vm.data-test` on jupiter; `d2-wedge-probe2` budget exhaustion.

## Tooling notes

`bb` resolves via mise: `export MISE_TRUSTED_CONFIG_PATHS="$PWD"; eval "$(mise env)"`. Match surrounding code style; deterministic canonical ordering (§8.1, byte-identical CBOR across hosts); no JVM-only reflection; zero raw host doubles in guest UAST (boxed `yin.vm.float` only).

## Deliverable

Implementation on `feat/p2prime-clojure-core`, UNCOMMITTED (you have NO commit authority). Final report: files added/changed; each gate with the exact command and pass/fail counts; full namespaces run (list them); goldens refreshed vs untouched confirmation; any design ambiguity resolved (with the §/quote); any gate you could not run and why.
