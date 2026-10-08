Created-GMT: 2026-09-15 22:02:58 GMT
Created-Local: 2026-09-16 05:02:58 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 3988f9a4-9143-4e58-8961-e92bece1ba64
# Task: Fix ClojureDart reader-conditional splicing bug blocking bb test:cljd
Role: Scoped / Subagent
Implementers:
- Model: glm-5.3-flash | Assigned: 2026-09-16 05:02:58 +07 | Status: active | Rationale: tiny, mechanical, bounded one-line fix

## Context

`test/bench/yin_vm_bench.cljc` (currently untracked, not part of this
repo's history yet — do not `git add` it, leave it exactly as untracked)
has, in its `ns` form:

```clojure
(:require #?(:cljd [] :clj [criterium.core :as criterium])
          [yin.vm :as vm]
          ...)
```

This is the classic ClojureDart reader-conditional trap: `#?(:cljd [] ...)`
(unsplicing `#?`, not `#?@`) inserts the literal value `[]` as ONE element
of the surrounding `:require` vector when read under `:cljd` — producing
`(:require [] [yin.vm :as vm] ...)`. An empty vector is not a valid
require-spec, and the ClojureDart compiler NPEs trying to read its
namespace name, aborting compilation of the entire `bb test:cljd` lane for
every namespace, not just this file.

## Task

Fix ONLY this one reader-conditional to splice instead of insert, so
under `:cljd` no `criterium` require appears at all (equivalent to simply
omitting it), and under `:clj` the `criterium.core` require is unchanged:

```clojure
(:require #?@(:cljd [] :clj [[criterium.core :as criterium]])
          [yin.vm :as vm]
          ...)
```

Note `#?@` (splicing) and the `:clj` branch is now `[[criterium.core :as
criterium]]` (a vector containing the require-spec vector, since splicing
unwraps one level).

## Verification

Run `bb test:cljd` and confirm it no longer fails at compile time with the
`Cannot invoke "clojure.lang.Named.getName()"` NPE on
`bench.yin-vm-bench`. It's fine if other namespaces have their own
unrelated issues — just confirm THIS specific compile error is gone and
that `bench.yin-vm-bench` itself compiles under `:cljd` (or, if
`criterium` isn't actually needed under cljd and the whole require list is
otherwise fine, that the namespace form is now valid ClojureDart).

## Boundaries

Only this one line in `test/bench/yin_vm_bench.cljc`. Do not touch
anything else in the file, do not `git add` it, do not touch any other
file.

## Deliverable

Report the exact one-line diff and the verification command/output. Write
findings to
`collab/1789525378000-scoped-fix-cljd-bench-require.glm-5.3-flash.findings.md`
with the same header block as this prompt.
