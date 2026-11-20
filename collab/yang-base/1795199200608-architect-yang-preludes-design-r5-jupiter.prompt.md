Created-GMT: 2026-11-20 18:26:40 GMT
Created-Local: 2026-11-21 01:26:40 +07 (+0700)
Coding-Agent: codex
Host: jupiter.stigmergy.systems (Codex Pool 2)
Session-ID: 01a1fa26-9e21-79a2-bb32-f1b87e22212c
Role: Lead System Architect
Model: gpt-6.2-astra

# Task: Architecture review r5 confirmation of docs/design/yang.preludes.md (all 9 r4 findings reconciled under Owner Clean-Slate Directive)

You are acting in the **Lead System Architect** role (`docs/agents/roles/architect.md`). This resumes your existing read-only Pool-2 session (`01a1fa26-9e21-79a2-bb32-f1b87e22212c`) inside `/home/sto/tmp/yang-preludes-r4-review`. Do NOT edit any files.

## Owner Context & Clean-Slate Directive

The project owner clarified directly in conversation:
- This is a single-user system with zero legacy users or deployments; clean rewrites and clean golden regeneration are cheap and strongly preferred over maintaining dual legacy runtimes or frozen-quirk backwards compatibility.
- Preludes should be cleanly composable and bidirectionally swappable across frontends (`yang.python` with `clojure.core` in P3, `yang.clojure` parameterized alongside Python/TS/PHP in P2, and symmetric cross-frontend profiles such as `:yang.clojure/py-v1` in P4).

## Summary of all 9 r4 reconciliations made in `docs/design/yang.preludes.md`

Please re-read the updated `docs/design/yang.preludes.md` (831 lines) against your r4 findings (`collab/yang-base/1795198164100-architect-yang-preludes-design-r4-jupiter.gpt-6.2-astra.findings.md`):

1. **HIGH (r4 #1 / r3 #7 — Provenance exclusion vs. transitive `:yin.module/footprint` & eliminating `clj.bridge` / legacy `clj` split):**
   - Eliminated `clj.bridge` completely and unified all Clojure-family §8.10.7 callable/provenance ownership directly into trusted Layer-2b **`clj`** (owning private `cell/dsg__new-provenance` / `cell/dsg__provenance` in both `uast` and `module-uast`, never exported; P3 migrates `yang.clojure` directly onto unified `clj` + `clojure.core` and retires legacy `dsg__call_token` / inline provenance ops).
   - Fixed §2.2 invariant 8 & §5.2 item 3 so non-trusted modules (`yrt.*`, `clj.py`, `clojure.core.impl`, `clojure.core`) are checked against their **direct** `:yin.module/primitives` and `:yin.module/exports` (matching `src/cljc/yin/vm/linker/publish.cljc:81`'s transitive `:yin.module/footprint` union, while governing transitive effects via `:yang.prelude/max-effects`).
2. **HIGH (r4 #2 / r3 #6 — Collision-free two-module `clojure.core.impl` + `clojure.core` using `clj/prim-get` / `clj/prim-assoc`):**
   - Split standard library into pure stateless **`clojure.core.impl`** (defining ONLY `impl-*` keys so zero bare primitive names like `get`, `assoc`, `conj`, `first`, `rest`, `vec`, `nil?`, `empty?`, `not` exist in its module store, resolving unambiguously to VM primitives under `src/cljc/yin/vm/engine.cljc:116`) and cell-free public facade **`clojure.core`** (requiring `clj` + `clojure.core.impl`).
   - Added collision-free **`clj/prim-get`** and **`clj/prim-assoc`** on `clj` (which has zero stripped exports named `get` or `assoc`); `clojure.core/init!` uses `clj/prim-assoc` and `clojure.core/export` uses `clj/prim-get`, eliminating self-recursion into `clojure.core/get` and exercising linked `(clojure.core/identity)` and `(clojure.core/get)` in P3 gates.
3. **HIGH (r4 #3 / r3 #5 — Two-step `owner` allocation & evaluated `assoc` condition evidence):**
   - Rewrote `clj/make-callable` (`§7.4` item 2) to allocate `(let [owner (cell/new meta) entry (fn [invocation] ...)] (do (cell/set! owner (assoc meta :yang.call/entry entry)) owner))`, mirroring `src/cljc/yang/python/antlr/prelude.cljc:944–960`.
   - Rewrote Steps B (`:yang.call/invalid-invocation`), C (`:yang.call/unsupported-binding`), and `clj/invoke-callable` (`:yang.call/not-callable`) condition construction using **evaluated `assoc` calls** (`(assoc {} :yang.call/callee owner)` and `(assoc (assoc {} :yang.call/callee owner) :yang.call/binding-profile (get invocation :yang.call/binding-profile))`), avoiding unevaluated map literals under `src/cljc/yang/core/uast.cljc:82`, plus P3 assertion on exact runtime `owner` and `:yang.call/binding-profile` at `raise-exit`.
4. **HIGH (r4 #4 / r3 #3 — Unambiguous identifier domain in `admit-key!` & field-based scalar identifier equality):**
   - Restricted keywords and symbols inside `admit-key!` (at any key/element nesting depth, across construction, update, lookup, membership, and comprehensions) to unambiguous identifiers: refusing empty namespace `""`, slash `"/"` in namespace or name (e.g., `(keyword nil "a/b")`, `(symbol nil "a/b")`, `[(keyword nil "a/b")]`), or leading colon `":"` in name (`dao.jing.cbor.md:266`).
   - Updated `clj.py/eq` & `clojure.core.impl/impl-not=` never to delegate `kind/keyword?` or `kind/symbol?` to host `=`, comparing strictly by kind + nullable namespace (`cmp-opt-str`) + name (`data/str-compare`), and gated both `(keyword nil "a/b") == (keyword "a" "b") -> false` and `:unsupported-key` refusals across all 4 hosts.
5. **HIGH (r4 #5 / r3 #9 — UAST-derived structural arity & role validation in `validate-descriptor`):**
   - Expanded `artifact-store` (`§5.2`, `§6.4`) to store verified `:yin.module/tree` canonical UAST alongside manifests.
   - Updated `validate-descriptor` (`§6.4` item 3) to inspect each selected manifest's verified `:yin.module/tree` (`wide-app` `(yin/def (quote sym) expr)` bindings per `src/cljc/yang/core/uast.cljc:35–56`) to check exact `:lambda` `:params` arity for every active hook and `:literals.*.overflow-op`, check 0-arg `:lambda` + `init!` (0-arg) + `export` (1-arg) for every `:kind :cell-accessor` ambient projection (including `clj.py/ex-info` noted in `§7.3`), and check non-lambda value shape for `:kind :value`, backed by conformance suites on that pinned address (plus a P2 test rejecting right-name/wrong-arity or wrong-role exports).
6. **HIGH (r4 #6 / r1 #10 — Independent `:trailing-requires`, `:finalize-c1? true`, & Clean-Slate golden discipline):**
   - Split recipe pins (`:yang.prelude/components`) from explicit executable trailing forms (`:trailing-requires`: `[]` for standardized base preludes `py`/`js`/`php`/`clj`/`yang.call`, `['py]`/`['js]` only for safepoint overlays) and uniform C1 finalization (`:finalize-c1? true`) in `§5.1`, `§5.2`, `§8.3`, and `§9` P0. Settled D4 & D6 per Owner Clean-Slate Directive (regenerate goldens cleanly per slice, gated by behavioral, 4-host, and oracle parity).
7. **HIGH (r4 #7 — Unit-bound `ambient-resolver` via 2-arg `:unit/run-main` & `clj.py/global-get`):**
   - Standardized `:unit/run-main` ABI across frontends to 2 args `[body-fn ambient-resolver]` (`§6.3`, `§6.4`, `§7.3`). Lowering synthesizes `ambient-resolver` from that unit's descriptor `:yang.semantics/ambient-projections`; `clj.py/run-main` stores `{:vars {}, :ambient ambient-resolver}` in the unit's `globals-cell`; `clj.py/global-get` checks `:vars` then `((get g :ambient) name)`, gated by two units sharing one `clj.py` address resolving different alias mappings in one task.
8. **MEDIUM (r4 #8 / r3 #8 — Cell-safe `coll?`/`map?`/`reduced?`/`sequential?`, complete `canonical-compare`, & `[NaN] == [NaN] -> false`):**
   - Added `impl-cell-ref?` check first inside `impl-coll?`, `impl-map?`, and `impl-reduced?` so authenticated callable cells answer `false` to `coll?`, `map?`, `set?`, `vector?`, `sequential?`, `seq?`, `list?`, and `reduced?`.
   - Added `cmp-opt-str` (nullable namespaces never passed to `data/str-compare`) and full within-kind lexicographic vector, map, and set comparison rules to `canonical-compare`, pinned explicit P3 ordered `keys` witness, and asserted **`[0.0] == [-0.0] → true`**, **`{"a": -0.0} == {"a": 0.0} → true`**, **`[NaN] == [NaN] → false`**, **`{"a": NaN} == {"a": NaN} → false`**.
9. **LOW (r4 #9 / r3 #13 — Accurate Python effect ceiling under `:trailing-requires []` vs. non-empty):**
   - Explained cleanly in `§5.1` line 286 that standardized linked `py` (`:trailing-requires []`) has exact 6-effect footprint `#{:cell/new :cell/get :cell/set! :cell/authentic? :cell/new-provenance :cell/provenance}`, while non-empty `:trailing-requires` (such as `py.sp`/`js.sp`) additionally includes `:module/require`, and gated both in P0.

## Review Output Format

1. Run `date -u` and `date` in your read-only shell so your output starts with accurate `Completed-GMT:` and `Completed-Local:` lines.
2. **Section 1 — Verdict:** State **`READY`** or **`REVISE`** with a concise assessment and explicit read-only verification scope statement (no files edited).
3. **Section 2 — r4 Finding-by-Finding Disposition:** A markdown table covering all 9 r4 findings (#1–#9), each marked `CONFIRMED-FIXED` or `OPEN` with `docs/design/yang.preludes.md` line and live-code evidence, plus confirmation of zero regressions on previously closed r1–r3 invariants.
4. **Section 3 — New or Remaining Findings (if any):** Any remaining issues (`Severity | file:line | Invariant / evidence | Recommended correction`).
5. **Section 4 — Owner Decisions (D1–D7):** Confirm D4/D6 Clean-Slate disposition and D1–D3/D5/D7 framing.
