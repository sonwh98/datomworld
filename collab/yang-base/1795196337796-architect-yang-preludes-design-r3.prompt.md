Created-GMT: 2026-11-20 17:38:57 GMT
Created-Local: 2026-11-21 00:38:57 +07 (+0700)
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id)
# Task: Architecture review r3 confirmation of docs/design/yang.preludes.md (F1–F8 reconciled; gpt-6.2-astra)
Role: Lead System Architect
Implementers:
- Model: gpt-6.2-astra | Assigned: 2026-11-21 00:38:57 +07 (+0700) | Status: active | Rationale: Independent OpenAI GPT-6.2 flagship Lead System Architect review per docs/agents/team.md and user request (fresh session on model upgrade from gpt-6-astra r1/r2)

Perform a read-only r3 architecture confirmation review of `docs/design/yang.preludes.md`.

Read first:
- docs/design/datom.world.md
- docs/agents/malleability.md
- docs/design/yang.preludes.md
- collab/yang-base/1795169993265-architect-yang-preludes-design.gpt-6-astra.findings.md (r1)
- collab/yang-base/1795186644731-architect-yang-preludes-design-r2.gpt-6-astra.findings.md (r2)
- docs/design/yang.antlr.md (sections 3, 8.5.5, 8.10.2, 8.10.7, 9)
- src/cljc/yin/vm/data.cljc
- src/cljc/dao/jing/cbor.cljc
- src/cljc/dao/host/number.cljc
- src/cljc/yin/vm/integer.cljc
- src/cljc/yin/vm/module.cljc
- src/cljc/yang/core/call.cljc
- src/cljc/yang/clojure.cljc
- src/cljc/yang/python/antlr/lower.cljc
- src/cljc/yang/python/antlr/prelude.cljc
- src/cljc/yang/php/antlr/prelude.cljc

Summary of r2 → r3 reconciliations in `docs/design/yang.preludes.md`:

- **F1 (HIGH — Numeric classifier, guarded dispatch, collection equality, §7.2 & §9):** Replaced `(data/number? x)` with explicit `:pure` prerequisite `data/float64?` (backed by existing `dao.jing.cbor/float64?` at `src/cljc/dao/jing/cbor.cljc:212`) + `integer/integer?`; preserved exact positive integer `(integer/neg e)` for `neg-pow-result`; guarded `%` (`clj.py/float-mod`) and `//` (`clj.py/float-floordiv`) against non-finite/zero/negative inputs before `fmod-pos`; added exact integral vs. explicit fractional-exponent refusal (`:pow-fractional`) and infinite-exponent refusal (`:pow-infinite`) for `**`; replaced host-dependent collection `=` fallback with recursive `clj.py/eq` normalizing `-0.0` keys/elements to `+0.0` and keeping `NaN != NaN` inside collections.
- **F2 (HIGH — Function entry boundary, §7.4):** Switched `:yang.call/entry` inside `clj.py/make-fn` from `try-orelse` to `yrt.escape/try-value` (preserving normal return values like `20`), and moved parameter-binding / named-arg checks inside the `try-value` boundary so callee binding errors are delivered under `:yang.call/thrown` per `yang.antlr.md` §8.10.7 while malformed protocol records transfer `:yang.call/invalid-invocation` outside it.
- **F3 (HIGH — Source-level callable admission, §7.4):** Defined `clj.py/admitted-callable?` checking authenticated cell refs (`(= (get x :type) :cell-ref)` + `cell/authentic?` + `data/callable?` entry + `yang.call/eligible?`) **before** `yang.call/invoke` and source callable predicates, refusing forged plain maps without altering `yang.call` or adding receiver-side wrappers.
- **F4 (HIGH — Portable standalone `clojure.core` numerics & equality, §7.5):** Rewrote standalone `clojure.core`'s `inc`, `dec`, `rem`, `not=`, `int?`, `nth`, `fn?` onto the §7.2 portable numeric/equality/admission contract (requiring `yrt.float`, `yrt.int`, `integer`, `data/float64?`), leaving default `yang.clojure/library` untouched.
- **F5 (MEDIUM — Dynamic closure construction & reconciled call path in standalone `clojure.core`, §7.5):** Specified uniform cell-backed callable construction (`cell/new` + `try-value` §8.10.7 bridge) for both `init!`-allocated exports AND dynamically returned closures (`comp`, `partial`), keeping static sibling calls internal and routing dynamic higher-order calls through cell-authenticated `yang.call/invoke` (never `yang.clojure`'s map-only shortcut).
- **F6 (HIGH — Capture-safe vector frame access in `yrt.escape` / bundled `clj`, §4.2):** Added `data/nth` to `yrt.escape`'s host dependencies so all 5-slot frame reads use `data/nth` directly (immune to `(defn get ...)` shadowing), reserving `get` strictly for context maps (and noting vector-indexing defense-in-depth for bundled `dsg__get`).
- **F7 (MEDIUM — Strict raw-double / callback separation in `yrt.float`, §4.3):** Removed all guest callback parameters (`on-underflow`, `on-range`) from raw-double functions; switched `neg-pow-result` and `floor-int-result`/`float-floor-result` to boxed `data/float64` inputs returning either boxed results or pure status keywords (`:yrt.float/underflow`, `:yrt.float/out-of-range`) outside raw-double leaf frames.
- **F8 (LOW — §5.1 Python profile example `:max-effects`):** Changed `:yang.prelude/max-effects #{}` to `nil` (noting a sufficient set if a ceiling is set).

Verify in read-only mode whether every r2 finding (`F1`–`F8`) is `CONFIRMED-FIXED` or `OPEN`, confirm no regressions to the 5 findings closed in r2 (`#2`, `#5`, `#8`, `#9`, `#10`), and check for any remaining or newly introduced architectural/contract defects. Do not edit any files. Produce the complete r3 review report directly in your final response.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report:
1. Verdict: `READY` | `REVISE` | `REJECT`
2. r2 Finding-by-Finding Disposition Table (`ID | r2 Finding | Status (CONFIRMED-FIXED / OPEN) | Evidence / Notes`)
3. Any new or remaining findings (`severity | file:line | invariant/evidence | recommended correction`)
4. Final confirmation of owner-decision framing (D1–D7)
