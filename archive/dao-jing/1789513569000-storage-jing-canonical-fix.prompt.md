Created-GMT: 2026-09-15 20:06:09 GMT
Created-Local: 2026-09-16 03:06:09 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 541aa172-7582-4698-aee7-0ca0434052b3
# Task: dao.jing canonical encoder — close remaining P0 collisions
Role: Storage & Indexing
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-16 03:06:09 +07 | Status: active | Rationale: storage/content-addressing encoding work, flat-subscription implementer

## Context

`src/cljc/dao/jing.cljc` implements DaoJing's transitional content-addressing
encoder (`order-normalize` / `order-normalized-print` / `content-hash`,
lines 45-82 and 199-225). It is cross-host: `:clj`, `:cljs`, `:cljd` all
compile this file, and SHA-256 is already backed by per-host libraries — do
not touch that. The contract lives in `docs/design/dao.jing.md`, section
"Canonical encoding" (around line 173): equal supported values must produce
the same bytes on every platform, and distinct values must address
distinctly, both across types and within one type. This is a transitional
`pr-str`-based encoder, not the final pinned byte encoding — do not attempt
that larger migration here, only close the specific defects below within the
existing `pr-str`-based approach.

An earlier attempt at this fix (working tree diff currently on disk, unstaged
— do not assume it is correct, re-derive from a `git diff` yourself) added
`with-meta` around vector/list/seq branches of `order-normalize` but did not
finish the job. An independent review
(`collab/1789502626000-claude-review.findings.md`, "Fix 1" section) found it
only partially closes the P0:

1. **Metadata still collides on hash.** `order-normalize` now reattaches
   metadata via `with-meta`, but `order-normalized-print` calls plain
   `pr-str`, which never prints metadata unless `*print-meta*` is bound.
   `^{:a 1} [1 2]` and `^{:a 2} [1 2]` still hash identically. Map and set
   metadata is also lost during normalization itself (the map branch
   rebuilds a sorted map, the set branch emits a tagged list — neither
   carries the original metadata forward), so binding `*print-meta*` alone
   will not fix maps/sets.
2. **The set encoding collides with real data.** `(set? v) (list 'set
   (sort-by pr-str (map order-normalize v)))` puts the `'set` tag inside the
   normal value domain. A set `#{1 2}` and the literal list `(set (1 2))`
   (or a normalized-vector-of-two-elements shaped the same way) can hash
   identically. The tag must live outside the value domain a supported value
   could otherwise produce — e.g. a wrapper record/type dispatched
   specially at the print step, not a plain list literal.
3. **Records collapse to maps.** A record and an equal plain map currently
   share an address (the `map?` branch treats them the same). Distinguish
   them, or confirm with the Architect that records are explicitly out of
   scope for `dao.jing` (check `docs/design/dao.jing.md` for whether records
   are a supported value type at all — if they are not mentioned as
   supported, document that unsupported-type behavior explicitly rather than
   silently colliding).

## Task

Fix `order-normalize` / `order-normalized-print` in `src/cljc/dao/jing.cljc`
so that, within the existing transitional `pr-str`-based approach:

- Metadata differences on any of map, set, vector, list, seq produce
  different hashes (carried through normalization AND actually printed —
  verify with `binding [*print-meta* true]` or equivalent, not just
  `with-meta`).
- The set tag cannot collide with a real value shaped like the tagged
  form (vector, list, or otherwise). Do not put the tag inside the value
  domain.
- Records either address distinctly from equal plain maps, or — only if the
  Architect (`docs/design/dao.jing.md`) confirms records are unsupported —
  are explicitly documented as unsupported input with a clear failure mode
  (not silent collision).
- `materialize!` (lines 262-309) currently verifies a `:present` read-back
  with `=`, which ignores metadata. If metadata is now part of the address,
  decide whether `materialize!`'s equality check must change to catch a
  metadata-only mismatch as a collision, and fix it if so — but only if your
  encoder change makes metadata address-significant (which it should, since
  the collision fix requires it).

## Contract (acceptance criteria)

Add or extend `test/dao/jing_test.cljc` (cross-host: this file compiles under
`:clj`, `:cljs`, `:cljd` — do not introduce host-specific forms unless
guarded by reader conditionals already used elsewhere in this codebase) with
at minimum these collision-pair assertions, each asserting the two values'
`content-hash` (or `segment-key`) are NOT equal:

- `#{1 2}` vs `(list 'set '(1 2))` (or whatever literal shape order-normalize
  would otherwise produce for the set — construct the actual adversarial
  input, not a stand-in)
- `^{:a 1} [1 2]` vs `^{:a 2} [1 2]`
- `^{:a 1} [1 2]` vs `[1 2]` (metadata vs no metadata)
- a record vs an equal plain map with the same keys/values (or, if records
  are ruled unsupported, a test asserting the documented failure mode)
- existing passing assertions (vector vs list vs seq distinctness, e.g.
  `[1 2]` vs `'(1 2)` vs `(seq [1 2])`) must still pass — do not regress
  them

Also assert equal-content idempotency still holds: two structurally equal
values (same metadata too) still produce the same hash.

Run the existing test namespace on at least the JVM host and report the exact
command and output. If you can also run `:cljs`/`:cljd`, do so and report;
if you cannot, say so explicitly rather than claiming untested platforms
pass.

## Boundaries

- Only `src/cljc/dao/jing.cljc` and `test/dao/jing_test.cljc` are in scope.
  Do not touch `docs/design/yin.vm.code-as-tuples.md` (separate unrelated
  unstaged diff) or any other file.
- Do not attempt the full pinned canonical byte encoding migration
  (`docs/design/dao.jing.md`'s "Open items and current limitations" item 1)
  — that is future, out-of-scope work. Stay inside the transitional
  `pr-str`-based encoder.
- Do not stage or commit. Leave the fix as an uncommitted working-tree diff.
- Do not invoke any further subagents or delegate this work onward — you are
  the implementer for this task.

## Deliverable

Produce the complete deliverable without waiting for further input. When
done, report:
- exact diff summary of what changed and why, per defect above
- exact test command(s) run and their output/assertion counts
- which hosts you verified vs. could not
- any defect from the list above you could not close, and why

Report by writing a findings file at
`collab/1789513569000-storage-jing-canonical-fix.glm-5.3.findings.md` with
the same header block as this prompt (Coding-Agent/Session-ID/Task/Role/
Implementers) followed by your report.
