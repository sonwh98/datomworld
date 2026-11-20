Created-GMT: 2026-11-20 10:19:53 GMT
Created-Local: 2026-11-20 17:19:53 +07 (+0700)
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id)
# Task: Architecture review of docs/design/yang.preludes.md (configurable and composable Yang preludes)
Role: Lead System Architect
Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-20 17:19:53 +07 (+0700) | Status: active | Rationale: Independent different-family Lead System Architect review (OpenAI GPT reviewing Gemini-authored design) per docs/agents/team.md and docs/agents/roles/architect.md

Perform a read-only architecture review of `docs/design/yang.preludes.md` (a proposed design for configurable and composable preludes across `yang.*` compilers, covering both modular Layer-2a `yrt.*` shared runtime modules including math/numbers, and treating preludes as swappable semantics/standard libraries — exemplified by configuring `yang.python` with a `clojure.core` prelude).

Read first:
- docs/design/datom.world.md
- docs/agents/malleability.md
- docs/design/yang.preludes.md
- docs/design/yang.antlr.md (sections 3, 8.10, and 9)
- docs/design/yang.typescript.md (sections 2.2, 17, and 19 A12.5)
- docs/design/yang.php.md (section 9)
- src/cljc/yang/core/prelude.cljc
- src/cljc/yang/core/prelude_admission.cljc
- src/cljc/yang/core/call.cljc
- src/cljc/yang/core/module.cljc
- src/cljc/yang/core/publication.cljc
- src/cljc/yang/frontend.cljc
- src/cljc/yang/python/antlr/lower.cljc
- src/cljc/yang/clojure/prelude.cljc
- src/cljc/yin/vm/docs/state.md

Also inspect relevant sections of `src/cljc/yang/clojure.cljc` (e.g., `library`, `compile-program`, `lower-unit`), `src/cljc/yang/python/antlr/prelude.cljc`, `src/cljc/yang/typescript/antlr/prelude.cljc`, and `src/cljc/yang/php/antlr/prelude.cljc` as needed to check real code facts and invariants.

Evaluate:
1. **Foundational axioms and non-negotiable invariants** (`docs/design/datom.world.md`): no hidden global state, no implicit control flow, no raw callbacks, no shared mutable state, no layer collapsing, no assumed graphs, unitarity / derive-don't-persist.
2. **Ownership & security boundaries:**
   - Are `yrt.*` modules genuinely stateless (explicit `ctx-cell` passing, cross-language task isolation across `py`/`js`/`php`/`clj`)?
   - Does the boundary around reserved VM provenance doors (`cell/dsg__new-provenance` and `cell/dsg__provenance` in `yin.vm.docs/state.md` and `yin.vm.module`) hold cleanly without leaking mint/read privileges across shared modules?
   - Are `yang.clojure` bundled capture-safety (`dsg__*` wrapper) and linked isolation handled accurately without breaking existing contracts?
3. **Open frontend SPI & dynamic extension:** Does the design preserve `yang.antlr.md` §3 ("no closed language registry in Yang core")? Does `:yang.semantics/descriptor` + `:yang.prelude/profile` cleanly fit `yang.frontend` and `yang.core.*` without collapsing parsing, lowering, composition/admission, publication, and evaluation?
4. **Correctness & semantic edge cases of `yrt.*` and `:yang.python/clojure-core-v1`:**
   - Check `yrt.escape` (4-slot vs 5-slot frames, `:base` rebasing, `orelse` vs value-return, `abandon-to`, `on-empty`, `normal-arg`) against the actual implementations in `py`, `js`, `php`, and `clj`.
   - Check `yrt.float` and `yrt.int` (especially `yang.antlr.md` §8.5.5 float-address rules, `-0.0`, NaN handling, `yin.vm.integer` v4 refusals) against `py`, `js`, and `php`.
   - Check `:yang.python/clojure-core-v1` against real `yang.python.antlr.lower` and `yang.clojure` mechanics (e.g., `dsg__*` vs `clj/global-get`/`clj/define-global!`, `yang.call/invoke` arguments vs `yang.clojure` calling conventions, `clojure.core` publication from `yang.clojure/library`, scope/cell binding, identifier aliasing, fail-closed capability enforcement, and byte-identical default lowering).
5. **CLJ/CLJS/CLJD/Jolt portability, content addressing, and migration risk:** Are `defquoted`, canonical ordering, schema-1 manifest integrity (`:yang/call-site` outside manifest), and the golden-refresh plan sound?
6. **Malleability & bounded complexity** (`docs/agents/malleability.md`): Dependency layering, public API surface, and avoided over-abstraction.
7. **Design contradictions & completion criteria:** Any contradictions with `yang.antlr.md`, `yang.typescript.md`, `yang.php.md`, or live code; whether P0–P4 acceptance gates are concrete and testable.

Distinguish architectural defects from implementation gaps or intentionally deferred work. Do not edit any files. Produce the complete review report directly in your final response without waiting for human input.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report:
1. Verdict: `READY` | `REVISE` | `REJECT`
2. Findings: `severity | file:line | invariant/evidence | recommended correction`
3. Confirmed passing: the requested architectural properties verified sound
4. Assessment of the open owner decisions (D1–D6)
