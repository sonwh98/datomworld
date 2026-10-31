Created-GMT: 2026-09-26 10:12:20 GMT
Created-Local: 2026-09-26 17:12:20 +0700
Coding-Agent: glm
Session-ID: 07ad56e7-d862-4f2d-b838-d36933efd577
Completed-GMT: 2026-09-26 10:24:09 GMT
Completed-Local: 2026-09-26 17:24:09 +07

# Report: yin.vm.linker M4 slice S5, the UCF design amendments

Status: documentation only, one file edited, uncommitted as instructed.
`git diff` shows exactly one file: docs/design/yin.vm.universal-
continuation-format.md (200 insertions, 1 deletion; the deletion is the
rewrapped `:yin.k/reason` comment line). Nothing committed, staged, or
merged. Sources used: linker design section 9's M4 paragraph, its
section 10 file-box entry for the UCF doc, and section 7.3's decided
amendments (with section 7.2 for the wait states); completion criterion
12 was the checklist.

## Sections amended (final line numbers)

- 7.4.1 (lines 436-439, 449-479): the `:yin.k/reason` list gains
  `:link-request`, `:link-response`, `:install`; a new amendment block
  with an ASCII box table adds the three safepoints (`:module/require`
  miss, request in hand / awaiting the correlated response / install
  child running), stating the frame, resume pc, stack, and resume
  outcomes per the effectful-call row the require effect parks through.
- 7.4.3 (lines 626-679): the three pending variants in the section's
  code-block style (envelope verbatim from linker 7.2 step 3, including
  the `:yin.link/*` keys and `[:t0 7]` link id), plus the rules: link
  ids are `[origin counter]` scoped to the link pair; the response
  cursor is minted `:newest` before the request is appended; what each
  variant carries and the `full` retry (`:ffi-request` discipline);
  restore only on exact id correlation with duplicate/late/unknown
  skips as telemetry diagnostics; refusal raises as an `:ffi` `error`
  does; no restore until `linked`/`refused`; `gap` policy.
- 7.5.1 (lines 737-770): the closure marker gains `:yin.k/binding`
  (`:named` / `:positional`, both shapes verbatim from linker 7.3) and
  `:yin.k/store-of` (manifest address of the module store; travels once
  per slice); `:binding-mismatch` closed-world rule; what the segment
  names for the walker variant and for positional ones.
- 7.5.1 (lines 773-803): the `:yin.k/stream` and `:yin.k/cursor-ref`
  decode targets move to the private `:resources` table (fresh resource
  id, no store key ever created, program values keep the opaque
  reference ids), and the sealed-reference rule (task-scoped capability
  secret, seal verified at effect dispatch, lift authenticates before
  it encodes with `:yin.k/kind :forged-resource-reference`, lower
  re-seals under the receiver's secret).
- 7.5.3 (lines 926-936): cell lowering amended -- cells lower into the
  private `:resources` table, sharing preserved, no store key created.
- 7.6.2 (lines 1067-1079): the resource split amending the opening
  store-model list -- stream handles, cursor cells, and the FFI pair
  live in a private `:resources` table beside the store; only the
  engine's machinery reads it; a migration slice carries resource cells
  beside the program store.
- 7.6.2 (lines 1099-1118): isolated module stores -- one snapshot per
  module under its manifest address (dependencies' stores transitively,
  with the child's mutations), `:missing-module-store` lift refusal,
  each snapshot a `:yin.k/store` slice, lowered into per-task
  `:module-stores` one instance per module, first link wins, nothing
  into the ambient store ("no merge" at module grain), active-store
  isolation while a `:yin.k/store-of` closure runs.

Everything else is untouched, including the "Proposed / Deferred"
warning block, section 7.11 and its acceptance matrix, and the Rule R
"v3" contract text (verified in the diff hunks).

## Ambiguities found and how resolved

1. Linker 7.2 step 4 says the two pending variants are "the two UCF
   pending variants this flow adds (section 11, item 10)", but the
   amendments list is section 11 item 12 (item 10 is the require-flow
   test; every other amendment citation -- lines 1263, 1385, 1446,
   1459, 1515 -- says item 12, and item 12 names section 7.4.3's
   pending variants). Treated as a citation slip, not a design
   conflict: the decision itself (variants named `:link-request` and
   `:link-response`, in UCF 7.4.3) is stated once and unambiguously.
   Followed the decision text and item 12. Did not stop.
2. Presentation choice, not a linker ambiguity: the existing 7.4.1 and
   7.5.1 tables are markdown pipe tables, which the house format
   forbids for content I add, and the task said to preserve everything
   else exactly. The amendments are therefore new, clearly labelled
   blocks after the tables (one new ASCII box table for the three
   safepoints; prose that names the two amended decode cells), not
   edits to the existing pipe rows.
3. Scope note for the reviewer: the new refusal kinds
   `:forged-resource-reference` and `:missing-module-store` are named
   where the linker decides them (7.5.1 and 7.6.2 blocks). The closed
   kind set in 7.5.4 was not extended because 7.5.4 is outside the
   sections this task authorizes.

## Checks

No test lane applies (documentation only). Self-checks run and passing:
git diff shows only the one file; all 200 added lines are pure ASCII,
none over 80 columns except the 18 box-table rows (147 columns, pipes
verified aligned); no em dashes and no conflict markers in added lines;
only committed docs are cited (`yin.vm.linker.md`, `dao.stream.md`);
the linker design doc is committed (git ls-files). Unrun: none beyond
these; the orchestrator's independent verification and the non-GLM
review remain.
