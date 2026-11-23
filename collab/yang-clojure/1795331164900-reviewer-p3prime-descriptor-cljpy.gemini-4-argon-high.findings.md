Created-GMT: 2026-11-22 06:26:04 GMT
Created-Local: 2026-11-22 13:26:04 +07 (Asia/Ho_Chi_Minh)
Completed-GMT: 2026-11-22 07:12:58 GMT
Completed-Local: 2026-11-22 14:12:58 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: ae995f44-d5a3-484c-82b4-695a353f4cd8

# Task: Step-7 review — yang.preludes P3′ (descriptor parameterization + clj.py + DEFAULT flip)

Role: Reviewer (independent, read-only)

Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-22 13:26 +07 | Status: active | Rationale: independent family from the codex/gpt-6.2-sol implementer; fresh conversation

## Findings

1. **NOTE | `src/cljc/yang/python/antlr/contract.cljc:240` | Optional `:kind` default in `frontend-contract` projection grants:**
   `yang.core.semantics/validate-descriptor` (`src/cljc/yang/core/semantics.cljc:249`) defaults an omitted projection `:kind` to `:cell-accessor` (`{:keys [module export kind] :or {kind :cell-accessor}}`) per `docs/design/yang.preludes.md` §6.3 ("Each entry defaults to `:kind :cell-accessor`"), whereas `contract/frontend-contract` (`src/cljc/yang/python/antlr/contract.cljc:240`) destructures `{:keys [module export kind]}` without `:or {kind :cell-accessor}`. All entries in `contract/clojure-projections` (`src/cljc/yang/python/antlr/contract.cljc:205-215`) specify `:kind :cell-accessor` explicitly, so no built-in or tested projection is affected; adding `:or {kind :cell-accessor}` in `contract/frontend-contract` is an optional future-proofing polish.

2. **NOTE | `src/cljc/yin/vm/module.cljc:157-162, 646`, `src/cljc/yin/vm/engine.cljc:135-136, 1537`, `src/cljc/yin/repl/query.cljc:176` | Child-namespace trie collision and falsy export resolution fixes:**
   Linking `clojure.core.impl` before `clojure.core` creates the intermediate trie path `['clojure 'core]` with `:children {'impl ...}` before `clojure.core` itself is installed. Switching `resolve-module` existence checks to `module-entry` (`module.cljc:646`, `engine.cljc:1537`, `query.cljc:176`) and adding `module/export-binding` (`module.cljc:157-162`, used by `engine/resolve-var` at `engine.cljc:135`) cleanly resolves both dependency-first linking of dotted parent/child module names and `:kind :value` exports whose values are `nil`, `false`, or `:clj/unbound` without touching `yin.vm.provenance-scope` or weakening P1′ provenance-door enforcement.

## Review Focus Verification Summary

1. **§6.4 validation authority (`src/cljc/yang/core/semantics.cljc`, `src/cljc/yang/python/antlr/contract.cljc`):**
   - `yang.core.semantics` is strictly language-agnostic (zero language-keyword switches or tag checks).
   - `closure` (`semantics.cljc:135-163`) and `verified-definitions` (`semantics.cljc:121-133`) compute the selected address-verified closure from `:yang.semantics/prelude-module`, verifying every manifest hash, semantic-bytecode row hash, row validity, acyclicity, and exact pin set match against `:yang.semantics/ambient-modules`.
   - `callable!` (`semantics.cljc:187-208`) and `validate-descriptor` (`semantics.cljc:211-267`) enforce namespace qualification (`:yang.semantics/unqualified-hook`) across all hooks and executable `:literals.*` `:overflow-op` fields, closure export membership (`:export`), `:lambda` definition shape (`:non-lambda`), parameter count + parameter list + permitted `:call-shapes` against the frontend contract's recorded `entry-lowering` (`:arity`), `:cell-accessor` projection target 0-arg `:lambda` + module 0-arg `init!` + 1-arg `export` (`:projection-signature`), `:value` projection `:literal` node (`:projection-value`), and the reserved-door ban (`cell/dsg__*`, `dsg__*`, `yin/def`, `*.rt/*`, `*.b/*`, `yang.safepoint/*`, `*.impl/*` → `:reserved-door`).
   - `authorize!` (`semantics.cljc:174-176`) checks the exact address-bound role tuple `[address target role]` against `(:roles frontend-contract)`. `descriptor-validation-test` (`test/yang/python/antlr/descriptor_profile_test.cljc:80-145`) exercises the failure path for substituting same-module, same-arity `'clj.py/sub` for `:op/add` (`:export-role`, line 88), `'clj.py/neg` for `:int :overflow-op` (`:export-role`, line 100), and `'clojure.core/dec` for the `"inc"` projection (`:export-role`, line 103), as well as tampered UAST `:non-lambda`, `:tree-address`, `:manifest-address`, and `:runtime-profile-pins`.

2. **Legacy `py-v1` behavior preservation (`src/cljc/yang/python/antlr/lower.cljc`, `src/cljc/yang/python/antlr/contract.cljc`, `src/cljc/yang/python/antlr/repl.cljc`):**
   - `contract/legacy-hooks` (`contract.cljc:145-151`) and `contract/legacy-literals` (`contract.cljc:187-193`) map `py-v1` 1:1 to `'py/*` exports and `:py/None`, `{:py/str ...}`, `{:py/float ...}`, `:py/unbound`, and 53-bit `py/int-lit`.
   - `contract/entry-lowering` (`contract.cljc:138-140`) keeps `py-v1` on the frozen 1-arg `py/run-main [body]` entry shape.
   - `lower.cljc` hunks preserve emission order, diagnostics, and import handling for `legacy?` (`(= contract/legacy-id (:yang.semantics/id descriptor))`), verified by `profile-program-root-goldens-test` (`descriptor_profile_test.cljc:389-391`) and the untouched CPython oracle corpus.

3. **`clj.py` per §7.3 (`src/cljc/yang/python/antlr/clj_runtime.cljc`, `src/cljc/yang/clojure/core_impl.cljc`, `src/cljc/yang/clojure/core_module.cljc`):**
   - `clj.py/make-fn` (`clj_runtime.cljc:196-206`) accepts the 5-arg Python lowering signature `[fname spec defaults kwdefaults code]` and delegates to `clj/make-callable`; `clj.py/call` (`clj_runtime.cljc:207`) delegates to `clj/invoke-callable`.
   - `clj.py` declares and exports zero provenance doors (`dsg__*`) and is excluded from `:trusted-prelude-modules` (`profiles.cljc:225`).
   - All numeric/float operations use boxed `yin.vm.float` and `integer/to-float-boxed`, `integer/true-div-boxed`, `integer/compare-float-boxed` (`clj_runtime.cljc:19-114`), with zero raw host doubles in guest UAST.
   - `clj.py/run-main` and `clj.py/run-session` (`clj_runtime.cljc:231-264`) take `[body-fn ambient-resolver]`, initialize `clj`, `clojure.core`, and `clj.py`, install `clj.py/_policy` as `:on-unhandled-policy` on `(clj/rt-ctx)`, restore the saved context via `try-finally` (`yrt.effect/restore!`), and return the body value verbatim (`descriptor_profile_test.cljc:344-354`).

4. **The DEFAULT flip (§6.5) (`src/cljc/yang/frontend.cljc`, `src/cljc/yin/repl/frontends.cljc`, `src/cljc/yin/repl.cljc`, `src/cljc/yang/python/antlr/profiles.cljc`):**
   - `yin.repl.frontends/standard` (`frontends.cljc:394-399`) installs both `"py-v1"` and `"clojure-core-v1"` revisions of `:yang/python` and sets `:yang.frontend/preferred [:yang/python "clojure-core-v1"]` on the immutable catalog.
   - `yang.frontend/select` (`frontend.cljc:216-227`) resolves ID-only requests via `:yang.frontend/preferred` when present and matching, while preserving explicit `[id revision]` pins and ambiguity refusals for unconfigured multi-revision frontends.
   - `profiles/admit` (`profiles.cljc:186-200`) reconciles descriptor and runtime profile pins by module symbol before lowering (`:defect :runtime-profile-pins` on mismatch), and `yin.repl` records the compilation derivation record (including `:yang.frontend/runtime-closure` and `:yang.frontend/ambient-resolver`) in `:frontend-derivations` (`repl.cljc:210, 244`; verified in `descriptor_profile_test.cljc:357-376`).

5. **§9 P3′ gates encoded in fixtures (`test/yang/python/antlr/descriptor_profile_test.cljc`):**
   - Cross-syntax `clj → clj.py → clj` unwind (`clojure-python-clojure-unwind-test`, lines 280-341): crosses real Clojure → Python (`clj.py`) → Clojure call boundaries across an unrelated `:effect` frame on `(clj/rt-ctx)` initialized with nonzero `:depth 4` and `:base 9`, running twice across all 6 `[:bundled :linked]` × `[:semantic :stack :register]` combinations and asserting exact-once `["clojure-inner" "python" "clojure-outer"]` cleanup per activation and `data/content=` context restoration.
   - Capability refusals (`capabilities-refuse-before-emission-test`, lines 159-177): verifies typed `except E:`, parenthesized and bare-call `genexpr`, `yield`, `class`, `with`, `del`, subscript assignment, augmented subscript assignment, keyword arguments, and parameter defaults all fail closed before emission with `:reason :yang.semantics/unsupported-capability`.
   - Four r8 default-profile regressions (`cross-carrier-keys-and-binding-presence-test`, lines 248-277; `persistent-profile-e2e-test`, lines 193-221): verifies (1) unqualified `:k` map key admission, (2) cross-carrier integer identity (`0` vs `(host-int/promote 0)`) on both insert and lookup, (3) `:clj/unbound` value distinguishability from absent local/global bindings via `{:value v}` vs `{:unbound :clj/unbound}` / `{}` envelopes, and (4) Jing encoded-byte canonical order (`0` before `nil` in iteration and `clojure.core/str`).

6. **Scope fences + portability:**
   - `py`, `js`, and `php` prelude modules are untouched; no JVM-only reflection is used; map/projection emission is deterministically sorted by `jing/canonical-compare`; and `clj ./bin/analyze-deps.clj` is clean.

VERDICT: APPROVED

---

## r2 Confirmation — REPL default-flip test pins & `clojure-core-v1` session error boundary

Completed-GMT: 2026-11-22 16:06:09 GMT
Completed-Local: 2026-11-22 23:06:09 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: ae995f44-d5a3-484c-82b4-695a353f4cd8
Base-Commit: fd148fd9

### Scope Reviewed

1. **`test/yin/repl/python_antlr_test.cljc` (lines 10–15, 138–150, 431–545):**
   - Added `(def ^:private native-python [:yang/python "py-v1"])`, updated `langs` to `[:yang.python/cst native-python]`, updated `session` to format `lang` with `(pr-str lang)` so vector selectors parse at the REPL command boundary while keyword selectors (`:yang.python/cst`, `:yang/python` in `compile-error-path-test` at line 575) behave identically, and updated the 9 direct `(session :yang/python)` calls in the multi-line probe and comprehension tests to `(session native-python)`.
   - Verified that **zero assertions were removed, altered, or weakened** across all 32 native-track fixtures (`print`, `range`, `ZeroDivisionError`/`NameError` formatting, `py-report` rollback, `py/run-session` preview, probe buffering).

2. **`src/cljc/yang/python/antlr/lower.cljc` (lines 2679–2721):**
   - Added `report-session-errors` wrapping the module body in `(clj/run-unit (fn [] body))` strictly when `repl-session?` (`(and session? (not legacy?))`) is true.
   - Without an enclosing `:handler` frame, an uncaught guest raise in a `clojure-core-v1` REPL session previously reached `yrt.effect/raise`'s `on-empty` fallback `(:clj/no-handler v)` (`src/cljc/yang/clojure/prelude.cljc:49`), causing an operational VM error (`"Cannot apply non-function"`) that aborted the round instead of reporting the guest condition.
   - Because `clj/run-unit` wraps every normal value `v` in `[:yin.k/ok v]` and every caught escape in `[:yin.k/error condition]`, `report-session-errors` unwraps `(data/nth outcome 1)` on `:yin.k/ok` without colliding with user vectors, and on `:yin.k/error` prints `"Error:" value` and returns `nil` normally through `clj.py/_run`'s `try-finally` (preserving `(clj/rt-ctx)` restoration and the `:primed` REPL session).
   - Non-session `lower-packet` calls (including all standalone program goldens) and `legacy?` (`py-v1`) lowering are completely unaffected.
   - Zero provenance doors (`cell/dsg__*` or `dsg__*`) are emitted into the lowered AST (`clj/run-unit` is a public export of trusted `clj`), and descriptor validation in `yang.core.semantics` is untouched.

3. **`test/yang/python/antlr/descriptor_profile_test.cljc` (lines 1–2, 379–395):**
   - Added `default-repl-reports-guest-errors-and-keeps-the-session-test`, exercising an unpinned `:yang/python` REPL session across an initial `:name-error` on `range` (verifying `"Error: "` output and `:primed` session retention), a valid comprehension assignment to `even_squares`, a `:zero-division` error on `1/0`, and a subsequent read of `even_squares` returning `[4 16 36 64 100]`.

VERDICT: CONFIRMED-CLOSED

---

## r3 Review — Commit `7060af65` (`dao.stream.ws.jvm` pre-101 receive-adapter installation)

Completed-GMT: 2026-11-23 04:02:04 GMT
Completed-Local: 2026-11-23 11:02:04 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: ae995f44-d5a3-484c-82b4-695a353f4cd8
Reviewed-Commit: 7060af658cf88389dbb0cb0438272ecf506ecce3 (parent 2e7ded4a)

### Verification of Commit `7060af65`

1. **Handshake-order fix correctness (`src/clj/dao/stream/ws/jvm.clj:280-345`):**
   - Replaces the `ring.websocket.protocols/Listener` wrapper (under which http-kit transmitted `101 Switching Protocols` before invoking `on-open`, leaving a window where early client frames hit `(when-let [a @adapter] ...)` with `@adapter == nil` and were silently dropped) with direct `AsyncChannel` upgrade handling in `upgrade!`.
   - `upgrade!` verifies `http/websocket-handshake-check`, invokes `(accept! target socket (clock))` to obtain the `:ws/handle` and its `ws/adapter`, and registers both `.setReceiveHandler` and `.setCloseRingHandler` on `channel` **before** calling `(.sendHandshake channel ...)`.
   - Atomic `lifecycle` coordination via `swap-vals!` handles all ordering edges cleanly:
     - Writes before `:open?` return `false`; writes after `:close` return `{:dao.stream/outcome :dao.stream/closed}`.
     - If `accept!` (e.g. path/capacity rejection or a thrown exception) invokes `(:close! socket)` before `.sendHandshake`, `:close [code reason]` is recorded in `lifecycle` and `(.serverClose channel (int code) reason)` is sent immediately after `.sendHandshake`; if `(:close! socket)` is called after `:open?` is set, `.serverClose` runs immediately and at most once.
     - Close notifications through `report-close!` are idempotent (`:reported?` in `lifecycle`), and a pre-registration channel disconnect (`(.isClosed channel)`) synthesizes `(report-close! 1006 "")`.

2. **Regression test fidelity (`test/dao/stream/ws/jvm_test.clj:484-526`):**
   - `first-frame-can-arrive-before-endpoint-acknowledgement` blocks `:accept!` on a `release` promise after signalling `accepting`, proves that the client's `.buildAsync` handshake cannot complete (`TimeoutException`) while `:accept!` is still installing the receive adapter, then releases `:accept!` while the client's `onOpen` immediately transmits `[:first :frame]` prior to `accept-and-ack-slot!`, and verifies the frame is queued and delivered without loss.

3. **Scope & compatibility check:**
   - Only `src/clj/dao/stream/ws/jvm.clj` and `test/dao/stream/ws/jvm_test.clj` are modified; no unrelated changes are present.
   - Well-ordered clients observe identical subprotocol negotiation, handshake headers, text/binary frame routing (`AsyncChannel` delivers `String` or `byte[]` directly to `message!`/`binary!`), and close semantics.

VERDICT: CONFIRMED-CLOSED
