Created-GMT: 2026-11-20 14:57:24 GMT
Created-Local: 2026-11-20 21:57:24 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a1f878-9255-7591-a9d7-8c528b565999
# Task: Architecture review r2 confirmation of docs/design/yang.preludes.md (all 10 r1 findings reconciled)
Role: Lead System Architect
Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-20 21:57:24 +07 (+0700) | Status: active | Rationale: Resume same OpenAI GPT Lead System Architect session (01a1f878-9255-7591-a9d7-8c528b565999) for r2 confirmation review of reconciled design

Perform a read-only r2 confirmation review of the updated design document:
- `docs/design/yang.preludes.md`

Reference your r1 findings in:
- `collab/yang-base/1795169993265-architect-yang-preludes-design.gpt-6-astra.findings.md`

Re-read `docs/design/yang.preludes.md` in full (read-only; do not edit any files). All 10 r1 findings (6 HIGH, 4 MEDIUM) have been reconciled:

1. **HIGH (Numeric portability in `:yang.python/clojure-core-v1`, §6.3, §7.1–§7.2):** Removed bare `+`/`-`/`*`/`/`/`<`/`<=`/`>`/`>=` hooks; routed all arithmetic/ordering/equality through `clj.py/*` adapters backed by `yin.vm.integer`, `yrt.float`, and `yrt.int` (`:min-bits 53`), with explicit Int×Int, Float×Float, Mixed Int×Float, no-capture unwrapped arithmetic, non-numeric boolean handling, and `data/content=` kind-strict equality.
2. **HIGH (Frontend-owned Hook ABI & structural lowering rules, §6.2–§6.3, §7.3):** Separated language-agnostic `yang.core.semantics` from frontend-owned `yang.python.antlr.contract`; documented exact Python Hook ABI compatibility in `clj.py` (`global-get`/`global-set` per-module isolation, 3-arg `clj.py/try` over `yrt.escape/try-orelse`, 5-arg `clj.py/make-fn`, `clj.py/run-main` preserving `[:yin.k/ok ...]`, missing hooks `:op/mod`, `:op/floordiv`, `:op/pow`, `:ctrl/for-each`, and explicit capabilities including generator-expression gating under `:control/generators`).
3. **HIGH (Callable admission & §8.10.7 exception bridging, §7.4):** Switched `clj.py/make-fn` to authenticated task-heap cells (`cell/authentic?`) for cross-unit admission across `yang.clojure`/`clojure.core`/frontends, added binding-before-body checks, explicit `clj.py/call` raise-exit unwrapping `:yang.call/thrown` vs. private `cell/dsg__new-provenance` retention, and `cell/dsg__provenance` forwarding in entry wrappers.
4. **HIGH (Portable `clojure.core` library contract, §7.5):** Published an explicit portable subset of `yang.clojure/library` excluding `var?`/`resolve`, authored pure helpers (`assoc-in`, `update-in`, `get-in`, `hash-map`, `set`) + primitive callable projections (`nil?`, `empty?`, `not`, `first`, `conj`, `assoc`, `get`, `vec`), per-task instantiation under `clojure.core/init!`, sibling lexical isolation, and explicit `:ambient-projections` opt-in while leaving `yang.clojure` default `library-for` unchanged in P3.
5. **HIGH (`yrt.escape` generator frames & 4→5-slot migration, §4.2, §4.5):** Opened `yrt.escape/raise` dispatch to all non-`:finally` boundary frames (preserving `:generator` frames in P1), detailed the 4→5-slot migration and `n` vs. `depth` distinction for `js`/`clj`, and corrected §4.5 (`php` restores `(get s :ctx)` directly vs. `py` `:base` rebasing, parameterized via `on-start-ctx`/`on-resume-ctx`).
6. **HIGH (Hygienic hooks, precedence, exact export/door validation, bundled `clj` helpers, §4.2, §6.3–§6.4):** Required namespace qualification for every `:hooks` entry (`:yang.semantics/unqualified-hook` refusal), defined strict precedence (`lexical > module globals > :ambient-projections`), required exact export checks over the verified artifact closure + explicit ban on `cell/dsg__*` / `dsg__*` / `yin/def` / `*.rt/*` / `*.b/*` / `yang.safepoint/*`, and aligned bundled `clj` `yrt.escape` capture-safety accurately to live `yang.clojure:2470–2517` (4 existing `dsg__*` helpers, `conj` binder refusal, adding portable guest `dsg__add`/`dsg__lte`).
7. **MEDIUM (`yrt.float` rounding, domains, no-capture rule, §4.3, §10 D7):** Documented `1.1 ** 6` divergence and exported both `fpow-half` (`py`/`js`/`clj.py`) and `fpow-base` (`php`), stated domain preconditions (`fmod-pos`, `float-parts`, `neg-pow` `[x n]` hook), and enforced no-capture/no-safepoint around unwrapped doubles.
8. **MEDIUM (`yrt.int` v4 refusal algebra, §4.4):** Replaced `unwrap-result` with `check-limit` handling only the 3 returned keyword reasons (`:bit-limit`, `:digit-limit`, `:float-overflow`) without catching operational throws, kept `php/int-result` language-owned, and generalized `skip-ws` to `skip-while`.
9. **MEDIUM (Recipe vs. resolved profile, verified artifact closure, `:max-effects`, §5):** Split `:yang.prelude/recipe` from `:yang.prelude/profile` (`:yang.prelude/root-address`), required an immutable verified artifact closure for validation, allowed bare export names across distinct modules while blocking qualified/projected collisions, and replaced `:effects` with optional `:yang.prelude/max-effects` ceiling.
10. **MEDIUM (Roadmap & gate reconciliation, §6.4, §8.3, §9, §10):** Fixed `P0`→`P2` reference in §6.4, clarified P0 preserves all existing emission invariants and P3 leaves default `yang.clojure` unchanged, incorporated all per-phase acceptance gate clarifications, and added D7.

Verify whether each r1 finding is `CONFIRMED-FIXED` or remains `OPEN`, and report any newly introduced architectural or contract defects. Do not edit any files. Produce the complete r2 review report directly in your final response.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report:
1. Verdict: `READY` | `REVISE` | `REJECT`
2. r1 Finding-by-Finding Disposition Table (`# | r1 Finding | Status (CONFIRMED-FIXED / OPEN) | Evidence / Notes`)
3. Any new or remaining findings (`severity | file:line | invariant/evidence | recommended correction`)
4. Confirmation of owner-decision framing (D1–D7)
