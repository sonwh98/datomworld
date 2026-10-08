Completed-GMT: 2026-09-26 09:45:26 GMT
Completed-Local: 2026-09-26 16:45:26 +07

# Gate review — M4 slice S2: `register-host-module`, registry value shape

I read the diff (`git diff HEAD`), the five changed files directly, linker.md §8.3/§9/§10, UCF §7.5.2, `yin.vm/primitive-profile(s)`, and both (untrusted) GLM reports. I did not rerun the suites. Findings below; all are P3 (no P0–P2). Verdict follows.

## 1. SPEC CONFORMANCE (linker.md §8.3, UCF §7.5.2)

**Enforcement is correct and complete.** `profile-rule` (`module.cljc:96-116`) refuses, in order: a non-map / missing-key profile (`:malformed-profile`), a non-`:none` `:yin.k/host-state` (`:host-state`), and then by `:yin.k/class` — `:pure` passes, `:effectful` passes only with `(seq :yin.k/effects)`, and **any other class keyword falls through `case`'s default to `:host-class`** (`module.cljc:116`). This is the crucial bit: the UCF class `:host` is not a named `case` arm, so it correctly lands on `:host-class`, matching §8.3's "A `:host`-class function … is refused" (`linker.md:1847`). `check-binding!` (`module.cljc:119-127`) adds `:missing-profile` when `(get profiles sym)` is nil. The spec's three mandated refusals (`:host`-class, undeclared host state, no profile) map 1:1 to `:host-class` / `:host-state` / `:missing-profile`, all enforced before any registry write (the `doseq` over `sort-by str` keys at `module.cljc:150-151` throws before `assoc-in`).

**The two extra rules are sound completions, not scope creep.** §8.3 says a binding "must carry a profile in the shape of `yin.vm/primitive-profiles`" and an `:effectful` needs "a declared, non-empty `:yin.k/effects` set" (`linker.md:1842-1847`). `:malformed-profile` enforces the shape clause; `:undeclared-effects` enforces the non-empty clause. Neither invents new policy. The `profile-keys` set (`module.cljc:89-93`) is byte-identical to `vm/primitive-profile-keys` (`vm.cljc:393-395`), restated locally rather than reaching into a `:private` var — the correct cross-host choice.

**Registry value shape matches §8.3.** `register-host-module` (`module.cljc:152-164`) emits exactly `{name {:manifest :address :derivation :slice :stores}}` with `:manifest` `{:yin.module/name, :yin.module/derivations {}, :yin.module/primitives {sym → profile-address}}`, `:address nil`, `:derivation nil`, `:slice fns`, `:stores {}` — matching `linker.md:1887-1889` and the already-linked-manifest clause (`:yin.module/tree` absent, verified by the test at `module_test.cljc:98`). `resolve-module` keeps its `[registry sym]` signature (`module.cljc:52-57`), as §8.3 requires.

## 2. THE TRAP (every stream binding profiled)

**No regression.** `stream-profiles` (`module.cljc:223-235`) has exactly the five keys of `stream-module`, each `:effectful` with its single effect kind and arities matching the constructors (`make [0 1]`, `put! [2]`, `cursor [1]`, `next! [1]`, `close! [1]`). `await-profiles` (`await.cljc:80-86`) matches `await-bindings` 3-for-3. `register-stream-module` (`module.cljc:238-243`) rebuilds over `register-host-module` with the one-arg signature intact, so `repl.cljc:436`, `compilation_pipeline.cljs:87`, and `continuation_stream.cljs:83` need no edit — confirmed by reading them; they call only `register-stream-module`.

## 3. CALLERS AND CLEAN BREAK

`register-module` is **gone**: zero references in `src/` and `test/` (the only hits are `linker.md:941/1828`, which are the spec's own migration text, and `dao.stream.file.md:312` which names a different, pre-existing `register-module!` bang-variant — neither is an S2 straggler). `test/cljd-out/` is git-ignored (`.gitignore:31-32`), so the regenerated Dart file is not a straggler.

All four consumers pass the **full registry value** and resolve unchanged against the new shape: `resolve-var` (`engine.cljc:72-77`), `require-handler` (`module.cljc:258`, via `(:modules state)`), completion `module-of` (`completion.cljc:443-445`, via `(:modules (:vm env))`), and linker `resolvable?` (`linker.cljc:782-784`). `walk-path` (`module.cljc:37-49`) descends namespace segments as plain nested maps until a node carrying `:manifest`, then reads `:slice` — so it is backward-compatible with old-shape hand-built receivers `{name {sym fn}}` (no `:manifest` node means it keeps descending to the value). `semantic.cljc:266` and `ast_walker.cljc:86,660` pass `(:modules vm)` straight to `resolve-var`, which is the same full-registry path.

## 4. TESTS

Coverage is complete and each refusal can fail independently: acceptance (`module_test.cljc:43-51`), `:host-class` / `:host-state` / `:missing-profile` / `:undeclared-effects` / first-defective-binding-named (`:52-80`), entry shape + already-linked manifest (`:83-100`), stream-profiles parity and effect-union (`:135-145`). `register-stream-module` is actually exercised at `:147-149`, so the trap is proven not to throw. The only weakened assertion is `dotted-path-resolution-test` (`:109-111`), where the old `(= {'read …} (resolve-module r 'yin.io))` becomes `(some? …)` — justified, because a module-name lookup now returns the entry, not the bindings map, and the entry structure is re-covered more thoroughly by `host-module-entry-shape-test`. Binding the non-fn `42` under a `:pure [0]` profile (`engine_test.cljc:190-195`, `stack_effects_test.cljc:401-406`) is acceptable: the enforcement text does not require `fn?`, and value bindings were registerable before this change.

## 5. RULE R / SECURITY

`register-host-module` does not refuse a `yin/def`-keyed binding. This is **not a live vulnerability**: `resolve-var` (`engine.cljc:64-65`) and `check-store-key!` (`engine.cljc:87-88`) refuse the reserved name before the registry is ever consulted, so no program can reach a shadowing export. The Rule R registry check is assigned by UCF §7.5.2 to `empty-state`, not to `register-host-module` — so omitting it here is the correct slice boundary, not a defect. I flag the pre-existing gap that `empty-state`'s docstring ("A registry binding a reserved name (Rule R) is refused", `vm.cljc:1893`) is not yet implemented — `empty-state` checks `:primitive` (`:1900`) and `:primitive-profiles` (`:1903`) but passes `:modules` through unchecked (`:1981`). Pre-existing and outside S2, but the owning slice should close it.

## 6. CROSS-HOST + DOCSTRING ACCURACY

Cross-host-safe: no `letfn` (private `defn-`), no `#'` private cross-namespace access (`profile-keys` restated locally), no ungated `#?(:clj …)`, `stream-profiles`/`await-profiles` built by the existing pure `vm/primitive-profile` (same load-time pattern as `vm/primitive-profiles`), and `refusal-of` gates its catch on `:clj Exception / :cljs js/Error / :cljd Object` (`module_test.cljc:22`). The ns docstring example was correctly updated (`module.cljc:9`). The two em-dash→comma/colon edits (`module.cljc:190`, `:258`) are ASCII-cleanliness-only. `require-handler` still throws for absence — which is correct for S2; the §8.3 "never throws" behavior is S3's job and the test still asserts the throw (`module_test.cljc:165-170`).

## 7. Anything new

No new defects beyond the three P3s below; the rest is deferred S3/S4 work (require lowering, install child, manifest records, `:bindings` read in `resolve-module`).

## Findings

P3 | src/cljc/yin/vm.cljc:1893 vs 1981 | `empty-state`'s docstring promises "A registry binding a reserved name (Rule R) is refused" but the body checks only `:primitive` (1900) and `:primitive-profiles` (1903); `:modules` passes through unchecked. | Pre-existing (not S2's change) and guarded at resolution time by `resolve-var`/`check-store-key!`; the slice owning `empty-state` should add `(check-bindings! :registry (:modules opts))`. Not blocking S2.

P3 | src/cljc/yin/vm/module.cljc:163 | `:slice` holds the raw host functions rather than §8.3's "portable act-1 encoding, never lowered values" (`linker.md:1887-1889`). | Necessary deviation — host functions are opaque and cannot be UCF-encoded; the nil `:address`/`:derivation` are the "host is the boundary / no code fetched" warrant. Flag for S3/S4: consumers must treat nil `:address`+`:derivation` as "host module, exports are host-local". Not blocking.

P3 | test/yin/vm/engine_test.cljc:190-195, test/yin/vm/debruijn/stack_effects_test.cljc:401-406 | Non-fn value `42` registered under a `:pure [0]` profile. | The enforcement text never requires `fn?` and value bindings were registerable before; the `:pure` profile is a trusted composition warranty, not verified. Harmless, but note the profile is technically a lie about arity/effects for an integer. Optional: wrap as `(constantly 42)` and assert the call result if strictness is ever wanted.

Verdict: READY
Sign-off: GRANTED
