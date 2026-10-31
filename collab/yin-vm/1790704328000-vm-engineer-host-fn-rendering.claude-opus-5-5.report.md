Completed-GMT: 2026-09-29 18:58:24 GMT
Completed-Local: 2026-09-30 01:58:24 +07
Coding-Agent: claude
Session-ID: 10c592b5-4997-47d1-8055-8010792bb808

# Report: host functions render as a named, portable marker (local and served)

Worked in the main tree `/Users/sto/workspace/datomworld` (master 04378221), per the last WORK TREE section. Nothing is staged or committed. The only source file edited is `src/cljc/yin/repl.cljc`. `yin.vm/*`, `yin/repl/query.cljc` and `dao/data.cljc` are unchanged.

## Chosen form

```
{:type :host-fn, :name '+}
{:type :host-fn, :name 'dao.space.query/q}
{:type :host-fn}                      ; when no name is known
```

Why this form:
- It has the same shape as the existing `{:type :closure, :params ['i], ...}`, so a closure and a host function read side by side.
- The `:type` key tells the two apart.
- It prints through the same pretty printer and symbol quoting, so it comes out identical on CLJ, CLJS and CLJD.
- A tagged literal such as `#host-fn[+]` would be the only tagged form the REPL prints, and no host's reader could read it back.

## What changed (`src/cljc/yin/repl.cljc`, Rendering section)

- **`host-fn-namer`** (private): takes a VM and returns a function from host fn to name, or nil.
  - Primitives go through `yin.vm/name-of`, which looks up by identity and already exists.
  - Alias resolution uses the VM's `:primitive-canonical-names`, falling back to `yin.vm/primitive-canonical-names`. The de Bruijn stack and register VMs don't carry that key, so without the fallback `==` rendered nameless there.
  - Host-module exports come from `yin.vm.module/module-entries` over the VM's `:modules`. Each entry's `:bindings` or `:slice` is matched by identity and named `module/export`.
  - This is how `dao.space.query/q` gets its name after `(require ...)`: the query bridge installs the module into the VM's registry value, so the name comes from the registry side with no change to `query.cljc`.
  - No host reflection is used.
- **`host-fn-marker`** (private) builds the map.
- **`quote-symbols`** now takes the namer. A new `fn?` branch sits before the vector and map branches, so host fns inside collections render as markers too.
- **`format-value`** gains a `[value namer]` arity. The 1-arity form still works and renders host fns nameless; it is used by `prn`/`print`, the driver and the debug output.
- **`finalize-eval`** (the REPL's result site) passes `(host-fn-namer (:vm state''))`.
- The VM representation is unchanged: host fns stay opaque values in the environment.

## Served REPL

The served session needed no `dao.data` change. `yin.repl.serve/evaluate` answers with the text from `repl/eval-input` on the server, so what crosses the wire is already the rendered marker. A served session and a local one produce the same string. `dao.data`'s `:fn`/`:opaque` nodes are only used for diagnostics, not for eval results.

## Tests added

- `test/yin/repl_test.cljc`, `a-host-function-renders-as-a-named-portable-marker`: runs on every VM (`:ast-walker :semantic :stack :register`) and checks:
  - `+` renders as `{:type :host-fn, :name '+}`
  - `dao.space.query/q` after require renders with its qualified name
  - a host fn inside a collection built at runtime, `(conj [] + (assoc {} :f -))`, renders as markers
  - the alias `==` renders under its canonical name `=`
  - a `defn` closure still starts with `{:type :closure`
  - no output contains `#object[`
- `test/yin/repl_test.cljc`, `a-host-function-without-a-known-name-renders-nameless`: `(format-value inc)` and `[inc 1]` give the nameless marker.
- `test/yin/repl/serve_test.cljc`, `a-served-host-function-renders-as-the-local-marker`: sends `+`, the require and `dao.space.query/q` to a served endpoint. The answers must equal the local session's texts, the local texts must be the named markers, and no answer may contain `#object[`. This file now requires `[yin.repl :as repl]`.
- A literal `[+ {:f -}]` evaluates as quoted data (`['+ {:f '-}]`) in the REPL, which is why the collection case builds its vector and map at runtime.

## Mutation proofs (each applied on its own, then reverted from a backup copy)

| Mutation | Result |
|---|---|
| M1: remove the `fn?` branch | Failures show `#object[clojure.core$_PLUS_ 0x...]` and `#object[yin.repl.query$q ...]` in local, served and nameless cases |
| M2: namer ignores module exports | 5 failures (4 VMs + served): `'dao.space.query/q` became `{:type :host-fn}` |
| M3: result site calls `format-value` without a namer | 18 failures: every name gone |
| Canonical-table fallback absent (the first draft) | `==` rendered `{:type :host-fn}` on `:stack` and `:register`; fixed by the fallback |

After reverting, `git diff` touches only the three files above. The one-off `#object[` strings exist only in failed test output; no source or test contains `#object[` except the negative `str/includes?` assertions.

## Verification

- **kondo** (`clj -M:kondo --lint` on the 3 files): 0 errors, 0 warnings.
- **cljstyle check**: blocked. The permission layer refused the command, so it was not run. The one wrapped line in `quote-symbols` is formatted by hand in the house style.
- **Focused JVM** (yin.repl-test, yin.repl.serve-test, yin.repl.adapter-test, yin.repl.driver-test, yin.repl.main-test, dao.data-test, yin.repl.query-test): 121 tests, 788 assertions, 0 failures, 0 errors. Re-run after the final tidy-up with the same numbers.
- **Full `clj -M:test`**: 2374 tests, 184439 assertions, 0 failures, 0 errors.
- **`bb test:cljs`**: 2279 tests, 50915 assertions, 0 failures, 0 errors. Both `yin.repl-test` and `yin.repl.serve-test` showed "Testing" in node output, so rendering matches between CLJ and CLJS.
- Both full lanes ran before the final tidy-up, which only made `host-fn-namer` private and re-wrapped one line in `quote-symbols` (no behaviour change). Only the focused JVM suite and kondo were re-run after it.
- **`bb test:cljd`**: not run, per the brief; that is the orchestrator's lane.

## build/yin-repl-peer

Needs a rebuild. `test/yin/repl/slice_peer.cljc` requires `yin.repl.main`, `yin.repl.driver` and `yin.repl.serve`, which all pull in `yin.repl`, so the Dart peer binary carries the old rendering until `bb build:yin-repl-peer` runs. `bb test:cljd` already depends on that build.

## Open points for the orchestrator

- `prn`/`print` inside a program, e.g. `(prn +)`, render the nameless `{:type :host-fn}`. Those primitives are built before the VM exists and see only the output stream. Naming them would mean threading VM tables into `make-repl-primitives`; I left it out to keep the change small.
- The marker is plain data, so a user who types the literal map `{:type :host-fn :name '+}` gets the same text. The same is already true of `{:type :closure ...}`.
