Created-GMT: 2026-09-16 08:43:09 GMT
Created-Local: 2026-09-16 15:43:20 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: Independent review of the `dao.data` (`tag`/`summarize`) design proposal

Role: Adversarial Reviewer

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-16 15:43:20 +07 | Status: active | Rationale: cross-family independent review of a Claude-family (fable) design proposal, per team.md's reviewer-independence rule

## Context

Read, in this order:

1. `docs/design/datom.world.md` — master architecture: the "Everything is a
   Stream" / "Side effects must appear as stream emissions" axioms, and the
   Host Boundary section, which motivated this whole task.
2. `collab/1789566611000-architect-state-snapshot-abstraction.prompt.md` —
   the original task brief given to a different agent ("fable",
   claude-fable-5-1), which explains the origin: the owner observed that
   v1 VM telemetry (`src/cljc/yin/vm/telemetry.cljc`) is not a special
   subsystem, just a VM emitting plain data onto a `dao.stream` like any
   other observer — and asked fable to discover other places in the
   codebase needing the same "bound arbitrary internal state into safe
   plain data" pattern, and propose a minimal shared abstraction only if
   the evidence actually warranted one.
3. `collab/1789566611000-architect-state-snapshot-abstraction.claude-fable-5-1.stdout.log`
   — fable's own summary of what it found (duplicated classifiers, handle
   detectors, unnamed bounds, unbounded printing sites, one correct
   Dart-only instance, two v2-specific findings that reshaped the design:
   v2 cursors are plain maps needing no special predicate, and every v2
   handle implements the stream descriptor protocol so it can be named by
   identity alone) and what it proposed (two functions, `tag` and
   `summarize`, in a new namespace, explicitly rejecting an `emit` wrapper
   and the name `dao.observe`).
4. `docs/design/dao.data.md` — the CURRENT state of the design doc. This
   started as fable's proposal (originally drafted as `dao.summary.md`,
   proposing a standalone `dao.summary` namespace) and has since been
   revised interactively, in a separate design-review conversation, in
   three ways not authored by fable:
   - Denoised/tightened for clarity (no content change claimed).
   - The `:count` rule was made precise: `:count` is only ever reported
     when `(counted? x)` is true; for a non-`counted?` (unrealized, possibly
     infinite) lazy sequence, `summarize` never calls `count` and instead
     realizes at most `:items` + 1 elements to decide truncation, omitting
     `:count` entirely.
   - A `:chars` bound was added to `bounds` (now `{:depth n :items n :chars
     n}`), truncating `:string` values that were previously passed through
     with no length limit at all — added specifically because the
     "unbounded printing" finding (site `src/cljc/yin/repl/driver.cljc`
     line ~386, `(pr-str line)` on raw REPL input) would otherwise still be
     genuinely unbounded for a long string even after adopting `summarize`,
     since `:depth`/`:items` only bound containers, not string length.
   - The namespace itself was relocated from a standalone `dao.summary` to
     `dao.data` (`src/cljc/dao/data.cljc`), reasoning that it should be the
     parent namespace's own file sitting alongside the existing
     `dao.data.btree`/`dao.data.arrays` sub-namespaces (cross-cutting data
     utilities in the parent, specific data structures in sub-namespaces),
     since no other existing `dao.*` namespace was judged a genuine
     semantic fit (this reasoning itself was not adversarially reviewed —
     scrutinize it).

Also read for cross-reference, to verify claims made in the discovery
findings and in the reasoning above, rather than trusting them:
- `src/cljc/dao/pretty.cljc` (the pretty-printer discovery findings
  reference — note: this is a print-syntax renderer, not a value
  classifier; do not treat it as a duplicate to fold into `dao.data`)
- `src/cljc/yin/repl/driver.cljc`, `src/cljc/yin/repl/serve.cljc`,
  `src/cljc/yin/repl/connect.cljc` (the "unbounded printing" sites)
- `src/cljc/dao/data/btree.cljc`, `src/cljc/dao/data/arrays.cljc` (the
  existing `dao.data.*` sub-namespaces the doc now claims as siblings)
- `src/cljc/dao/stream.cljc` and/or `src/cljc/dao/stream.cljc` for
  `stream/descriptor?`'s actual contract, to verify the claim "every
  handle implements it, regardless of whether it's a reader, writer, or
  closable" is actually true today, not aspirational
- `src/cljc/yin/vm/telemetry.cljc` is the v1 telemetry implementation this
  work was originally motivated by, but v1 is deprecated — do not evaluate
  this design against v1's shape or flag v1-compatibility gaps; judge it
  purely against live/v2 code and the stated design goals

## Task

This is architectural review, not implementation. Evaluate:

1. **Fidelity to the evidence.** Does fable's stdout.log summary of its own
   findings actually hold up against what's in the repo (spot-check at
   least the pretty.cljc and driver.cljc/serve.cljc/connect.cljc claims)?
   Is anything in the findings summary overstated, understated, or wrong?
2. **Design soundness of `docs/design/dao.data.md` as it stands now.** Is
   `tag`'s classification complete and correctly ordered? Is `summarize`'s
   schema self-consistent (check every row of the table against every rule
   in "Rules")? Are the `counted?`, `:chars`, and namespace-relocation
   changes (all added after fable's original draft, described above)
   actually sound extensions, or do any of them introduce a defect,
   inconsistency, or unstated assumption fable's original design didn't
   have?
3. **Completeness against fable's own findings.** For each concrete
   duplicate/gap fable found, does the current design actually let that
   duplicate be replaced, or does a real gap remain? Be specific and cite
   file:line for any claim.
4. **The `dao.data` namespace placement.** Is treating `dao.data` as a
   general-purpose parent namespace (cross-cutting utilities) alongside
   `dao.data.btree`/`dao.data.arrays` (concrete structures) actually sound,
   or does it create a confusing/overloaded namespace, given
   `dao.data.arrays`'s own docstring frames it as specifically "for
   dao.data.btree" rather than a sibling utility? Would you have chosen
   differently, and why?
5. **Anything fable's brief asked for that the current doc silently drops**
   (re-read the original brief's "Task" and "Deliverable" sections) — e.g.
   did the brief require anything about portability verification, naming
   conventions, or citation of every duplicate site that the current
   `dao.data.md` no longer visibly satisfies?

Do not implement anything. Do not propose to implement anything as part of
this review — flag it as a recommendation for a future unit if genuinely
needed. This is read-only: do not edit `docs/design/dao.data.md` or any
other file.

## Deliverable

A findings list, most severe first, each with: what's wrong (or confirmed
correct, if you want to explicitly close out a question above), exact
file:line evidence, and severity (blocking design defect / real gap worth
fixing before implementation / minor-nonblocking / already correct as
designed). End with an overall verdict: is `docs/design/dao.data.md` ready
for implementation as written, or does it need another revision pass
first — and if so, exactly what should change.
