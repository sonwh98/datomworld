Created-GMT: 2026-11-18 17:40:00 GMT (session clock at write)
Created-Local: 2026-11-19 00:40:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: yang-dedup-round2-batch1
Role: Engineer

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-19 00:40:00 +0700 | Status: active | Rationale: same implementer family as round 1 (continuity with the established pattern); independent argon+astra review already fixed the plan

Implement round-2 batch 1 of the yang.* compilers deduplication in this worktree (detached
at the current origin/master tip). You may edit files and run tests. **Do not commit, do not
push, do not touch collab/.** Leave the work uncommitted; scratch files (baseline capture
scripts) go in /tmp, never in the repo. Match surrounding code style.

## The three extractions (reviewer-amended; both review docs are in the worktree at
collab/exception-track/1795020669393-…audit…findings.md and 1795022027297-…conformance…findings.md
— read the C1/C2/C3 sections first)

### C1: complete the site-set deduplication
- Into `src/cljc/yang/core/siteset_store.cljc`: move `read-at`, `absent?`, `missing` from
  `yang.php.antlr.siteset` (its read-at is the canonical body; python's differs slightly —
  unify on the php body's semantics, note the python nil-vs-absent cond difference in the
  report). **Move the `::absent` sentinel comparison WITH the reader** — the auto-qualified
  keyword changes namespace (astra condition). REPLACE `load-tree`'s 4-arg signature with
  `[handle root]` (no dead arity; no test calls the 4-arg form — argon amendment). Store
  ends at exactly 5 publics: read-at, absent?, missing, load-tree, overlay.
- Into `src/cljc/yang/core/siteset_publication.cljc`: add `site-set-of` `[medium ast]`
  (returns `{:root …, :tree …, :set …}`) and `publish!` `[handle medium frontend-revision
  profile-address manifest ast]`. **Invariants:** the medium participates in occurrence
  selection and is NOT a record field; the returned `:tree` must NOT enter the serialized
  record; the record stays exactly the five `:yang.site/*` fields; set-before-record
  materialization order and signing inputs unchanged. Publication ends at exactly 5 publics.
- Consumers (`php/antlr/siteset.cljc`, `python/antlr/siteset.cljc`): thin wrappers/aliases;
  drop the `yin.vm.encoder` require from both (their out drops by 1). Verification,
  admission, discovery, derive-closure, deferred-module?, stage stay target-owned.

### C3: wire the 6 missed round-1 call sites
- `php/antlr/lower.cljc` module-packet + classmap-packet: inline module-tree →
  `core-module/module-tree`; `args-vector` → `core/build-vector 'php/vconj`; `app*` wrapper
  → `def ^:private app* core/app*` (matching py/ts).
- `python/antlr/lower.cljc` module-packet: same module-tree treatment (it adds a
  `yang.call` require-node — keep that operand).
- `python/antlr/prelude.cljc:~3684`: private `definitions->uast` →
  `(core/definitions->uast defs)` alias.
- `src/cljc/yang/core/call.cljc:~158`: the `uast` def →
  `(u/mark-tails (core/definitions->uast definitions))` — PRESERVE the linear tree and tail
  marking (astra: do NOT substitute the balanced emitter).

### C2 + the prelude split, ONE ATOMIC UNIT
- Extract the admission trio `host-declaration`, `module-spec`, `admit` (+ the private
  `refusal` helper) from `src/cljc/yang/core/prelude.cljc` into NEW
  `src/cljc/yang/core/prelude_admission.cljc` — 3 publics, requires exactly
  `[yin.vm :as vm] [yin.vm.module :as module]`. Its docstring: constructs
  declarations/specifications and checks registries; does not publish, link, or execute.
- `yang.core.module/declare-free-name` delegates to `prelude-admission/host-declaration`,
  but **keeps its covered-module check BEFORE delegation and its throw LOCAL** — the
  refusal key is the VALUE of `refusal-key`, not a literal `:refusal-key` (astra condition);
  exact ex-data: `{refusal-key refusal, :name n}`. `admit` keeps its own resolve-module
  checks and sorted missing-name report — do NOT rewrite it onto host-declaration.
- `yang.core.module/free-primitives` iterates `(prelude/free-name-vec ast)` — keep the
  sort-by-str order. Drop `yin.vm` + `yin.vm.module` from `core.module`'s requires.
- All 8 consumers of `module-spec`/`admit` (core.module, core.call, clojure/prelude,
  php/py/ts prelude, py/ts safepoint) gain the `prelude-admission` require; `core.prelude`
  drops `yin.vm` + `yin.vm.module` and the 6 caller-less publics across the codebase go
  private (`strip-form`, `placeholder` in core.prelude; `evidence-record`, `evidence-datom`
  in core.claims; `utf8-length`, `source-error` in yang.antlr.packet) — the prelude
  demotions are part of this atomic unit.
- Nothing else moves. No target namespace is required by any yang.core ns. Verify no cycles
  with the requires you add.

## Mandatory validation plan (astra's acceptance gates — capture BEFORE changing anything)

1. **Baseline capture (pre-change):** write a capture script in /tmp that, on the UNCHANGED
   worktree, prints for a fixed fixture: (a) canonical prelude row bytes + addresses from a
   linked publication, (b) site-set bytes and set-address, (c) full `:yang.site/*` record
   map + record-address, (d) refusal ex-data for one "undeclared name" admission, (e) an
   eval rejection envelope (sanitization at eval_coordinator.cljc:46 intact). Reuse the
   patterns in /tmp/dry-golden-cmp.clj (a golden capture from round 1; its key format is
   reusable). Fixed keys, deterministic outputs. Save to /tmp/r2-baseline.txt.
2. **Post-change:** re-run the same script; BYTE-IDENTICAL on (a)-(c), IDENTICAL ex-data on
   (d), IDENTICAL envelope on (e). Any divergence = your refactor changed behavior — fix
   the refactor, never the expectation.
3. **Focused test namespaces** (all must pass): yang.php.antlr.siteset-test,
   yang.python.antlr.siteset-test, yang.php.antlr.prelude-test,
   yang.php.antlr.safepoint-test, yang.python.antlr.prelude-parity-test,
   yang.python.antlr.safepoint-test, yang.typescript.antlr.prelude-parity-test,
   yang.typescript.antlr.safepoint-test, yang.python.antlr.lower-test,
   yang.typescript.antlr.lower-test, yang.clojure's test namespaces (discover via ls
   test/yang | grep clojure — the clojure prelude consumes module-spec/admit).
4. **Four-backend prelude parity SEPARATE from linked checks:** run the parity namespaces
   (php/py/ts prelude-parity + clojure prelude coverage) as their own invocation; then the
   linked 3-backend check via yang.php.antlr.include-test QUICK rows only (^:slow rows are
   out of scope for batch 1). Report each invocation separately with counts.
5. `git diff --check` clean.

If bb/ANTLR tooling is missing in the fresh worktree: `eval "$(mise env -s bash)"` then
`bb gen:antlr` first (documented scratch-clone gap).

## Report format (mandatory)

Begin exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then: per-cluster moved-where summary (file:line, before/after counts, the seam), baseline
vs post-change comparison outcomes verbatim, per-invocation test counts, git diff --stat,
anything noticed but not done. End with exactly one line:
VERDICT: DONE — <one line> | or | BLOCKED — <why>
