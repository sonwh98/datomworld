Completed-GMT: 2026-10-14 07:23:47 GMT
Completed-Local: 2026-10-14 14:23:47 +07
Coding-Agent: claude
Session-ID: pending (provider-generated)

# L-d-3 adversarial review (read-only)

Reviewed: `git diff` on `linker-ld3` vs `master@739227db` (7 files, +228/-67) plus the untracked `src/cljc/yin/vm/linker/requirements.cljc`, against the scoping design §2.1/§2.4/§3 row L-d-3, `yin.vm.linker.dht.md` §4.2/§7.4, and the engineer report. No files edited, no commands with side effects run (plan mode); the engineer's test and lint results are quoted, not reproduced by me.

## Verdict: APPROVE-WITH-NITS

No P0/P1. No P2 in claimed acceptance or newly introduced behavior. Three P3 nits, none blocking under the Q6 rule.

## Checklist outcome

1. `publish/requirements` and `yin.vm.linker.requirements` — OK.
   - `requirements/eager` is a pure worklist (`loop` with vector `queue`, `seen` set); every reference enqueued once; only `:application` nodes are followed, plus the body of an immediately applied `:lambda` operator. Matches the old private recursive scan semantics exactly (same `:variable`/`require`, one `:literal` symbol operand, lambda bodies not followed, `:if` not followed).
   - Cycle avoided: `publish` → `closure` → `requirements`; `requirements` has no requires; `closure` now also requires `yin.vm`, which does not require any `yin.vm.linker.*` ns (grep clean). `publish/requirements` kept as the public surface the design names.
   - Walker-side projection (`closure.cljc` walk-requires) rebuilds `{:type tag field value ...}` from the row body `[tag & slots]` cached in `:seen` by step 2 using `vm/semantic-bytecode-grammar`. Verified the blob stored at a row address is the body (`materialize-tree!` writes `(subvec row 1)`; `body-child-ids` reads `(first body)` as tag), so the destructure is right. `:operands` slot is `:nodes` (vector of addresses), `:operator` and `:body` are `:node` addresses, `:literal :value` is `:data` kept as a symbol, so `count`, `node-at`, and `symbol?` behave identically to the AST path. Unknown address → `{:type nil}` → ignored; harmless.
2. Closure walker — OK. `:eager?` on every listed pin; `(not eager?) st` short-circuits before the visited/depth/descent branches, so deferred pins are never read (e2e test asserts `base` stays absent in the reader's local store after the dependent links). Eager absent → `:missing` unchanged; max-depth refusal unchanged for eager pins.
3. `dependency-bindings` — OK. `(filter :eager? (:requires w))` preserves walk order; entry shape `{:module :name :pinned :binding ...}` unchanged (no `:eager?` leaks into entries), so the exact-equality assertions in `test/yin/repl/dht_process_test.clj:825-842` still hold (that fixture already hoists `(require 'my.lib)`).
4. Acceptance tests — present and match §3 row L-d-3: `deferred-pins-are-listed-without-reading-their-content` (complete with deferred absent, `:missing` with eager absent, bounds variant), `requirements-derive-only-unconditional-module-level-requires` (lambda body, `:if` branch, `try-require` all deferred), `deferred-dependency-bindings-never-refuse-early` (empty bindings under absent, matching, and mismatching name-envs; all four formats link), e2e `a-deferred-import-is-absent-as-data-after-the-dependent-links` across all four VMs and both claim modes with fold diagnostics compared to `ld/resolve-name`. Eager fixtures were correctly made explicit (`eager` helper wraps the body in an immediately applied lambda after `(require 'base)`, with the `require` primitive profiled).
5. Invariants/portability — OK. No Python words in linker code or docs (new words: `:eager?` only). Manifest unchanged; eagerness derived from rows already read (derive-don't-persist). No `with-redefs`; maps start from literal `{...}`/`{}`; no reader conditionals added; no `for` over chunked seqs; `peek`/`pop`/`into`/destructuring are portable. Docs §4.2 (shape, prose, table row 5) and §7.4 updated consistently with §2.4.

## Findings

P3 | src/cljc/yin/vm/linker/requirements.cljc:26 | The eager test is `(symbol? (:value arg))`, but the `require` primitive (`src/cljc/yin/vm.cljc:410-414`) accepts a vector spec `[ns :as alias]`, and REPL programs use that form (`test/yin/repl/store_test.cljc:489`). An unconditional module-level `(require '[base :as b])` is therefore classified deferred: no prefetch, no early binding check, and a plain `require` raises at install instead of being refused as data up front. Inherited from the old private scan (and from 5.3's `requiring`), and §2.1 declares eager-read-as-deferred safe, so not blocking. | Fix: accept `(or (symbol? v) (and (vector? v) (symbol? (first v))))` and take `(first v)`, in `eager` only (one place; `publish/requirements` inherits it). Add one assertion to `requirements-derive-only-unconditional-module-level-requires`.

P3 | src/cljc/yin/vm/linker/closure.cljc:197 | `requirements/eager` walks the entire cached tree for every visited module, including modules with an empty `:yin.module/requires`. Bounded by `:max-parts` so not a DoS vector, but it is wasted work on the hot load path for the common dependency-free module. | Fix: `(when (seq (:yin.module/requires manifest)) ...)` around the eager-set computation, defaulting to `#{}`.

P3 | collab/1791961569446-engineer-ld3-dht-deferred.findings.md (process) | Verification ran `bb test:sub:clj yin.vm` only; `target/profile/sub-clj.log` lists no `yin.repl.*` namespace. The slice changes the shape of walker output and the semantics of `dependency-bindings`, both consumed via `yin.vm.linker.interpreter` by `test/yin/repl/dht_process_test.clj` and `test/yin/repl/dht_test.cljc` (`publish-at-the-prompt…`, P2/reader scenarios around line 2500). I read those fixtures and expect them green (they hoist a real `(require …)` program), but they are unrun. | Fix: before landing, run the quick tier or at least `bb test:sub:clj yin.repl` (slow flag for the process test), per the test-tier cadence.

## Non-findings worth recording (no action)

- `(if (require 'x) …)` puts a require in test position, which is unconditional, yet is classified deferred (`:if` is not followed). Design §2.1 calls this safe and the old scan behaved the same; left as is.
- A publisher can shadow `require` with a lambda parameter and be classified eager (over-eager = one prefetch) or alias it and be classified deferred (on-demand load). Both directions are the accepted misclassifications of §2.1; the pin invariant (L-d-2) still guards receive.
- `footprint` still unions effects from all pins, eager or not. Conservative and correct.
- Scoping §4 "Contradiction to amend" names a 7.5.3 sentence about deferred requirements asked on the importing task's request; the brief scopes L-d-3 docs to §4.2/§7.4 and that sentence belongs to L-d-2's amendment, so not charged to this slice.

## Known-gaps ledger candidates

- Vector-spec `require` is never eager (P3 above).
- Deferred pins are not descended into even when local (design v1; scoping §4 risk 3 already lists prefetch as follow-up).
