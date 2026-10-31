# Track A Phase C4 Slice I1 — Static absolute imports (glm-5.3 findings)

Worktree: `.claude/worktrees/yang-python-c4-i1` (base `ea247169` == master at dispatch).
Brief: `collab/1791539186673-compiler-engineer-c4-i1.prompt.md` (copy: `collab-brief.md`).
Governing spec: `docs/design/yang.antlr.md` §8.5.6.

## Shape landed

**Runtime (`src/cljc/yang/python/antlr/prelude.cljc`, one definition list,
both emitters):**

- Module objects are heap cells `{:py/type :module :name s :dict d}`
  (§8.5.6); `py/module-of`/`py/module-dict`/`py/module-new` build them,
  `module-new` setting `__name__`, `__package__`, `__spec__`/`__loader__`
  (None); `__file__` is absent.
- `sys.modules` is the runtime dict `py.rt/modules`; `py/init!` pre-seeds
  the two runtime-synthesized module objects `sys` (its dict holding
  `py.rt/modules` under `modules`) and `builtins` (its dict IS the
  builtins namespace, now carrying the four standard dunders, CPython's
  own shape). `py/modules-ref`/`-put!`/`-del!` are the accessors.
- `py/import (name spec body)` implements §8.5.6's resolution order:
  `sys.modules` first — a hit is the module object, the body never
  re-executes; a miss with spec/body None (a runtime-synthesized name)
  raises the new `ImportError` builtin class; a miss instantiates via
  `py/module-run (name package body)`, `__package__` from the spec's
  package-ness (always None in I1).
- `py/module-run` creates the module object, inserts it into
  `sys.modules` BEFORE the body runs, runs `(body d globals-fn)` in the
  importing task, and on a raising body removes the entry and re-raises
  to the importer's handler. `py/globals-fn` factors the per-module
  `globals` builtin.
- `py/run-main` instantiates the SAME body image as `"__main__"` through
  `module-run` and snapshots the outcome (`{:py/out … :py/exception …}`);
  `py/run-module` keeps its test-runner role (fresh namespace, no
  sys.modules entry). One code image therefore sees `__main__` as main
  and its own name as an import.
- `py/getattr`/`py/setattr` gained `:module` branches (attribute
  read/write = dict operations on the module's own namespace, so the
  module's functions see them; `__dict__` returns the dict itself) and
  `py/delattr` is new (strict delete, AttributeError naming the missing
  attribute). Module objects snapshot as `:py/module` and render as
  `<module 'name'>`.

**Lowering (`src/cljc/yang/python/antlr/lower.cljc`):**

- `import m` (single undotted, `as`-free — I1's shape) lowers at the
  statement to `(py/import m pym.m/spec pym.m/body)` plus the binding of
  the module object in the scope the statement runs in; one
  `(require 'pym.m)` is hoisted per static import in the unit beside the
  wrapper's `py` require (ruling 4), scanned at any depth (a function
  body import still needs delivery). An import-free program's tree is
  byte-identical, so P2's program-root golden does not move.
- `import sys`/`import builtins` need no delivery: spec and body are
  `None` literals and no require is hoisted — the pre-seeded
  `sys.modules` entry answers.
- The bundled profile refuses any unit importing a compiled module
  (§8.5.6: imports require the linked prelude — a module closure's free
  reads never see the ambient store); the pre-seeded `sys`/`builtins`
  carry no delivery, so their import lowers under either profile. The
  pre-seeded `sys` exposes `modules` only — the interpreter hooks stay
  unreachable (`sys.setrecursionlimit` is an AttributeError, per the
  updated `e2e_test`). Dotted, aliased and `from` imports are refused
  naming the construct (I2's).
- `del_stmt` is lowered: `del m.y` is a strict delattr, `del x` reads
  first (NameError/UnboundLocalError when unbound) then unbinds; tuple
  targets delete each element; subscript deletes are refused as later
  slices'.
- The module emitter: `lower/module-packet` compiles one unit as the
  linker module `pym.<name>` — one wide application whose operands are
  `(require 'py)`, one `(require 'pym.<i>)` per import, then the
  `(yin/def spec …)` and `(yin/def body (fn [%globals %globals-fn] …))`
  definitions, exports `#{spec body}` (§8.5.6's module shape; the body
  is the same form the main wrapper runs). `lower/module-spec` builds
  `publish-module!`'s spec over a host registry: `py` and each import
  pinned by manifest address in `:requires`, a free name qualified by
  them covered by that requirement, bare names by their `vm/primitives`
  profile, other `ns/name`s by the registry's host-export profile —
  anything else refused `undeclared-free` before publication.
- `lower/module-symbol` maps `m` to `pym.m` (`pym` reserved, injective,
  nothing at the `pym` root).

**Scope (`src/cljc/yang/python/antlr/scope.cljc`):** `import_stmt` binds
the imported names where Python does (module level: a global; a function
body: a local cell); `import-bindings` follows CPython's rule (`import
a.b` binds `a`, `import a.b as x` binds `x`) so I2 needs no rework.

**Tests:**

- `test/yang/python/antlr/import_programs.cljc` (NEW): the modules and
  programs as CST packets, each generated from the JVM parser for its
  docstring source, so every host runs the real lowering.
- `test/yang/python/antlr/import_test.cljc` (NEW): fast lowering tests
  (hoisted requires, statement-time py/import call and binding, the
  bundled refusal, I2 refusals, scope binding, del lowering, module
  packet/spec shape and refusals) and the slow acceptance over the
  linked harness — all six §8.5.6 I1 rows on the semantic, stack and
  register VMs, plus `sys.modules` identity probes, the pre-seeded
  `sys`/`builtins` resolution, and a transitive `t`-imports-`k` run
  (the module's install child delivers its own imports).
- `test/yang/python/antlr/e2e_test.clj`: parser parity for every
  import-programs packet; the bundled `import sys` refusal message
  updated to the new linked-prelude refusal.
- `test/yang/python/antlr/prelude_parity_test.cljc`: `module-objects-test`
  runs the module object protocol over the bundled prelude on all four
  VMs (dunder read, attribute read/write/delete, `__dict__`, the
  sys.modules hit, the None-spec ImportError); `seeded-builtins-test`
  expects the four dunders the builtins module now carries.
- `test/yang/python/antlr/linked_harness.cljc`: `publish-guest-module!`
  publishes a guest module's spec beside `py`.

**Goldens moved once each** (the prelude definition list changed;
§8.5.6's I1 entry records them): `py` manifest →
`blake3-607cef06002c49a1cebdf836b751077f6d77c4618ebb317cc0533ef895d297a1`,
`pysp` manifest (via the requirement pin) →
`blake3-a842d2ad662f1d0a0dd7fb92922221343603472bac591073ac23ba82c9772d54`,
bundled prelude root →
`blake3-f4a40353e2be0a9f5c1074fb784da81c6542b8505577b8f6bdf80069203ad4e7`,
float program A →
`blake3-0fb95b9e3186be329d4127eec04d8e86143d4f83246e8c39c8177fe9acc37768`,
A' →
`blake3-42c5770081ef77a655db2aaedd0f6edc80269d80ae8885647883df7c1262dc81`,
record →
`blake3-c441eafa2d18d9969547ddc222d7652e23587653fb5bc9d986f8e562b0a0d9d4`,
prelude-id →
`blake3-bf98bc3f5fcd12e36151a1043afde9c7e14f74951176db8bd4a2a8fab28a02c6`;
`runtime-keys` 61 → 65. The hook prelude's own root did NOT move, and the
linked `x = 1` program root (`dbe72b29…`) did NOT move (import-free
wrappers are unchanged). Node computed the same bundled addresses
byte-for-byte.

## Traps hit (for the next slice)

- The prelude notation (`u/sexp->uast`) does not evaluate inside map
  literals: `{:py/str name}` in a definition embeds the SYMBOL — the
  module branch's dict lookups silently missed every key until the keys
  were built with `(assoc {} :py/str name)` calls. Symptom: setattr then
  getattr round-tripped (both used the broken key) while the module
  body's own bindings were invisible to `m.x`.
- `portable/packet` rules are KEYWORDS (`:file_input`); a string first
  element is a token. The generator emitted `(:rule n)` strings; the
  packets collapsed to single token nodes and the scope analysis saw a
  token as the root.
- `p/child-rules` is 3-arity `[pk node rule]`; scope's own helpers pass
  `pk` separately — easy to drop when copying lowering idioms.
- `del_stmt`'s single-target `exprlist` is not a tuple to
  `target-elements` (nil); take `(or (target-elements ctx xl) [xl])`.
- `tree-seq` over the then-chain wrapper visits requires in reverse
  source order (operator before operand) — assert module sets, not
  order.
- `clojure -M:test -e …` runs the whole suite first (main-opts); use
  `-n <ns>` or the `-v` var filter. `-A:test` and script execution are
  approval-gated in this sandbox; `bb -e` + `clojure.java.shell/sh` is
  the workaround for one-off commands.

## Verification

- clj-kondo: 0 errors, 0 warnings on every touched file (project-wide
  baseline unchanged: pre-existing .cljd and yin/repl/host.cljc
  findings).
- cljstyle: clean on every touched file.
- JVM fast, `yang.python.antlr.import-test` (`-e :slow`): 10 tests, 44
  assertions, 0 failures.
- JVM slow, `yang.python.antlr.import-test` (`-i :slow`): 8 tests, 30
  assertions, 0 failures — every acceptance row on the semantic, stack
  and register VMs.
- JVM, `yang.python.antlr.{import,e2e}-test` fast (after the e2e fix):
  21 tests, 221 assertions, 0 failures.
- JVM slow, `yang.python.antlr.{linked-prelude,linked-safepoint}-test`
  (`-i :slow`, final pins): 15 tests, 99 assertions, 0 failures — the
  `py` and `pysp` manifest goldens, repeated-publication identity,
  walker exclusion, cross-unit exception sharing, install-child
  isolation.
- Dart fast lane (`bb test:cljd`, final code): all tests passed (3778) —
  includes the new `import-test`/`import-programs` and the parity
  additions.
- Full JVM fast lane (`bb test:clj`, final code): 3947 tests, 244,056
  assertions, 0 failures, 0 errors.
- Node fast lane (`bb test:cljs`, final code): 3826 tests, 108,231
  assertions, 0 failures, 0 errors. Node computed the new bundled
  goldens byte-for-byte (the float-address cross-host identity the
  release gate asks for).
- `git diff --check`: clean.

## Open items / notes for the orchestrator

- The report file is `./findings.md` in the worktree root (the collab
  directory is outside this sandbox); please move it to
  `collab/1791539186673-compiler-engineer-c4-i1.glm-5-3.findings.md`.
- `collab-brief.md` (the staged brief copy) is untracked in the worktree
  root; keep or drop per protocol.
- I2's lowering groundwork is already in place: scope binds dotted/aliased
  imports per CPython, `module-symbol` mangles `a.b.c` to `pym.a$b$c`
  (one line when I2 lands), and `py/import`'s `__package__` derivation
  reads the spec's `:package?`.
