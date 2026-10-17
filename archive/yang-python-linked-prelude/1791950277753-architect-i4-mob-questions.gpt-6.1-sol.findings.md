Completed-GMT: 2026-10-14 03:59:46 GMT
Completed-Local: 2026-10-14 10:59:46 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a13890-169e-78b3-bde4-87b67897514f

My recommendations are **A for all three questions**, with two contract clarifications: strict I4 must disclose the publication-time absence gap, and Python must distinguish **name absence** from **content absence** when translating `:absent`.

**1. Unpinned optional imports: endorse Option A, strict I4 with an explicit known gap.**

Deferring an import changes **when a dependency is requested**. Allowing a nil pin changes **which dependency implementations the published module permits**. Those are separate architectural decisions.

The existing contract makes `:yin.module/requires` a map of names to exact manifest addresses. The reader resolves a name under its own authority environment; the publisher’s pin constrains that binding without becoming a name claim. This separation is explicit in [the DHT linker contract, §7.4](/Users/sto/workspace/datomworld/docs/design/yin.vm.linker.dht.md:1803).

Strict deferred dependencies preserve that separation:

- Publication identifies the dependency at address P.
- The reader may have no accepted binding for its name; the import statement receives an absence result and can take its fallback.
- If the reader resolves the name to Q, installation refuses unless Q equals P.

The proposed registry invariant is the right backstop: whenever a name becomes linked, its address must satisfy every concrete pin held by linked manifests in that registry. Absence can therefore be permitted without permitting substitution. Checks must cover inheritance, receipt of the installed module, and receipt of transferred dependencies—not just the halted install child’s registry.

Option B provides meaningful Python compatibility. Authors commonly publish packages whose optional dependencies are unavailable in the publishing environment. Strict I4 cannot support that case, even if the import sits inside a handler or an unreachable branch.

However, **ignoring nil pins makes nil a wildcard, not a declaration of absence**. A module published while `x` is unavailable could later bind any reader-authorized implementation of `x`, without changing the dependent’s manifest. Admission would still operate, and every fetched implementation could still be correctly content-addressed. The lost guarantee is the publisher’s exact dependency constraint.

That distinction matters: nil does not inherently break hashing or imply hidden global state. An explicitly specified open dependency could be coherent. But it broadens the executable package contract and weakens the current exact-binding rule in [linker verification, §4.2](/Users/sto/workspace/datomworld/docs/design/yin.vm.linker.md:429). It deserves a separate design covering publication, obligations, closure loading, registry conflicts, and replay under explicit resolution inputs. Updating `pin-conflict` alone is insufficient.

**Decision:** retain concrete pins for all declared dependencies in I4. Record publication-time-unavailable imports in `docs/agents/known-gaps.md`, including the fact that publication still refuses them.

Also amend the support profile and ruling 8’s “absent names included” wording to distinguish:

- **Supported:** published and pinned dependency absent from the reader’s resolution environment.
- **Deferred:** dependency unavailable and therefore unpinnable at publication.

Strict I4 is a defensible scoped delivery, but it is not complete support for Python’s optional-import idiom. A future design should choose explicitly between an open dependency and a pinned negative resolution; those have different behavior when the dependency later appears.

**2. Primitive surface versus host module: endorse Option A, bare `try-require`.**

`try-require` belongs beside `require` because it changes how a scheduler linking outcome reaches a continuation. It does not introduce a new external service.

The scheduler already owns registry hits, install waiters, cycle detection, correlated link responses, and continuation resumption. A dedicated host module would still need that scheduler behavior. It would add a declaration and composition dependency without removing the core machinery.

Neither option is inherently language-aware. Language-blindness depends on the protocol: names, addresses, profiles, and plain outcome data, with Python exception interpretation confined to lowering and the prelude. A bare primitive satisfies [the language-blindness contract, §7.5.10](/Users/sto/workspace/datomworld/docs/design/yin.vm.linker.dht.md:2268) directly.

Use the proposed surface:

```clojure
(try-require 'm)
;; emits :module/try-require {:module 'm}
```

Declare its profile under `:yin.module/primitives`, with the distinct effect `:module/try-require`. This lets effect profiling distinguish code that can inspect linking outcomes from code that uses ordinary `require`. It must remain effectful even when a registry hit answers immediately: its general behavior includes delivery, admission, and suspension.

The separate effect must reuse the existing linking and installation path. It must not become an alternative loader or authorization route. The important invariant is:

> `try-require` changes outcome delivery, while preserving every admission, verification, pin, origin, and store-ownership check.

The scoping design’s local waiter flag is appropriate. A plain `require` waiter and a `try-require` waiter can share one installation while receiving different delivery forms. The linker request envelope need not learn which form the guest selected.

Require a consistent outcome contract:

- Success, including a registry hit: `{:status :ok :module m}`.
- Link or install refusal: the structured refusal map, preserving evidence.
- Abandonment or transport loss: a distinct loss outcome.
- Pending admission: continued suspension, not a fabricated refusal or absence.

Refusal evidence must not be reduced to a boolean. That would discard information needed by later interpreters and by Python’s exception mapping.

**Decision:** add the bare primitive and its distinct effect profile. Update the design’s `module/try-require` wording to distinguish the guest primitive name from the effect namespace. A qualified library wrapper can be added later if useful; it does not justify a mandatory host module now.

**3. `ModuleNotFoundError` scope: endorse Option A, restricted to unresolved names.**

Python documents `ModuleNotFoundError` as an `ImportError` subclass for a module that cannot be located. Consequently, `except ImportError` also catches it. [Python 3.9 exception definitions](https://docs.python.org/3.9/library/exceptions.html#ModuleNotFoundError).

That supports this translation:

| Linking outcome | Python exception |
|---|---|
| No accepted name binding | `ModuleNotFoundError` |
| `:yin.admit/untrusted-principal` | `ImportError` |
| `:ambiguous-name` | `ImportError` |
| `:dependency-pin-mismatch` | `ImportError` |
| `:require-cycle` | `ImportError` |
| Verification or integrity refusal | `ImportError` |

In the admission case, resolution has already succeeded. The holder declined a specific address asserted by specific principals. Calling that “not found” collapses authority and admission, which [§7.5.9 explicitly separates](/Users/sto/workspace/datomworld/docs/design/yin.vm.linker.dht.md:2258).

This distinction improves fault isolation. A handler for `ModuleNotFoundError` can select a fallback for unavailable functionality without silently treating a policy rejection or dependency substitution as ordinary absence. A broader `except ImportError` remains available to applications that intentionally recover from either class.

There is a CPython nuance: its import hooks may deliberately raise `ModuleNotFoundError` to prevent an import. Thus Option B is possible as an explicit masking policy; it is not categorically incompatible with CPython. The normal search protocol nevertheless distinguishes failure to find a module from other exceptions, which propagate. [Python 3.9 import search and hooks](https://docs.python.org/3.9/reference/import.html#finders-and-loaders).

Security masking does not justify Option B here. Changing the exception class while retaining `:untrusted-principal`, address, and asserter evidence does not conceal existence. Actual concealment requires a separately specified disclosure policy governing all exposed evidence. The current contract deliberately delivers structured refusal data; preserve that transparency.

**Required clarification:** do not implement the mapping as simply:

```clojure
(= :absent (:reason refusal))
```

The DHT failure vocabulary uses `:absent` for both an unresolved **name** and missing **content at an address**. [DHT failure vocabulary, §9](/Users/sto/workspace/datomworld/docs/design/yin.vm.linker.dht.md:2554). A resolved module whose blobs cannot be retrieved should produce `ImportError`, preserving the content failure evidence. Translate to `ModuleNotFoundError` only when the outcome establishes name-resolution absence. Use structured evidence, never message text; make that distinction unambiguous in the contract.

Finally, this rule concerns **link-refusal translation**. Preserve exceptions raised during Python module-body execution, and retain CPython’s separate `sys.modules[name] = None` behavior. Failed initialization should remove the failing module’s cache entry while retaining successfully imported dependencies. [Python import cache](https://docs.python.org/3.9/reference/import.html#the-module-cache), [loading rules](https://docs.python.org/3.9/reference/import.html#loading).

The decisive I4 acceptance cases are reader-side name absence, content absence, admission refusal, late pin mismatch, and retry after failure. Run them across all four evaluators and three hosts, retaining ordinary `require` behavior and demonstrating that catching a refusal never exposes a rejected module.

This is a design evaluation; no files were changed and no test suites were run.
