Created-GMT: 2026-09-13 13:38:02 GMT
Created-Local: 2026-09-13 20:38:02 +07
Coding-Agent: claude
Session-ID: 1d04718d-278f-483d-b3ff-a148660d953f

# Task: macro-source-call-split

Role: Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-13 20:38:02 +07 | Status: active | Rationale: Architect for docs-only amendments

## Context

`docs/design/dao.space.index.as-observer.md` §3.2 (committed `8113bcb`)
mandates a required amendment to `docs/design/yin.vm.macro.md` §4.1.

The mandate (index note §3.2, lines 383–396, Appendix A row A2):

> `:yin/source-call` is written as both an external coordinate (initial
> expansion: an eid in the source batch) and a log-local ref (nested
> expansion: a node in the log copy). One attribute cannot be both. It must
> split — an undeclared value attribute for the external coordinate,
> qualified by `:yin/source-batch` as it already is, and a declared-ref
> attribute for the log-local node — so that `event-schema` declares only
> the latter a ref and the index resolves it batch-locally while leaving
> the former as the value the join needs.

This amendment is required before Phase 2 of the index note can proceed (index note §7, Phase 2 gate).

## Your task

Produce the exact text changes to `docs/design/yin.vm.macro.md` to
implement this split. This is **documentation only** — no source edits.
Do not touch any `.cljc` or other source file.

### What must change

1. **§4.1 datom table (lines 675–676):** Replace the single `:yin/source-call`
   row with two rows:
   - `:yin/source-call`  — the **external coordinate**: the eid of the call
     node *as it appeared in the source batch*, qualified by
     `:yin/source-batch`. Present on a successful expansion of an initial
     (non-nested) call. Absent on nested expansions and admission failures.
     **Undeclared** in `event-schema` (it is a value, not a ref; the index
     leaves it untouched; the value-join is the composition's).
   - `:yin/source-node`  — the **log-local node**: the eid of the call node
     in the *log copy* of the enclosing expansion's output. Present on a
     successful nested expansion only. **Declared ref** in `event-schema`
     (the index resolves it batch-locally within the log batch).

2. **§4.1 prose after the table:** The current text (lines 694–696) reads:
   > A nested expansion's `:yin/source-call` names a node in the log copy
   > of the enclosing expansion's output, which is how the chain source →
   > event → root → event → root is walked for generated syntax.

   Replace with prose reflecting the split: a nested expansion's
   `:yin/source-node` names the log-local node; an initial expansion's
   `:yin/source-call` carries the value coordinate qualified by
   `:yin/source-batch`. Make clear that only `:yin/source-node` is a
   declared ref.

3. **§4.1 `event-schema` sentence (line 699):** Currently:
   > `yin.vm.macro/event-schema` declares these attributes
   > (`:yin/source-call`, `:yin/macro`, `:yin/expansion-root` as refs)

   Update the parenthetical: `:yin/source-call` is **not** declared a ref;
   only `:yin/source-node`, `:yin/macro`, and `:yin/expansion-root` are.

4. **§4.2 (lines 714–718):** The cross-medium paragraph references
   `:yin/source-call` in its value role. Update to name the correct attribute
   for each role: `:yin/source-call` for the external-coordinate value (stays
   a value, qualified by `:yin/source-batch`); `:yin/source-node` for the
   log-local ref. Keep the explanation of durable-id conditions for
   `:yin/macro`.

5. **Decision 0 list / any other incidental references** to `:yin/source-call`
   that currently conflate both roles: audit `grep -n source-call
   docs/design/yin.vm.macro.md` and fix each occurrence. For lines that
   already distinguish the value role, no change is needed; for lines that
   ambiguously say "eid in that batch, or in the log copy", split the reference.

6. **Phase 0 retired-attrs list (line 909):** Currently includes
   `:yin/source-call`. Confirm whether this is the attribute being split or an
   unrelated entry. If it refers to the old unified attribute, replace with
   the two new names (`:yin/source-call` undeclared value, `:yin/source-node`
   declared ref). If it is already correct, leave it.

### Naming rationale to include in prose

- `:yin/source-call` keeps the existing name in its narrowed (external
  coordinate, value) role so that existing log data is not invalidated by
  the split.
- `:yin/source-node` is the new name for the log-local ref. (Rationale:
  "node" is already the term used in the macro doc for AST nodes in the
  log copy; "call" would be ambiguous because nested expansions name the
  output node of the outer expansion, not a call site per se.)

### Constraints

- This is **read-only planning only** — produce the final proposed text as a
  diff or as an explicit "replace lines X–Y with: ..." list. Do not write to
  the file directly; the orchestrator will apply the diff after both original
  reviewers confirm.
- Do not add new sections. All changes go inside existing sections.
- Preserve all existing commentary and appendix entries; only the content
  touching `:yin/source-call` changes.

### Verification

After listing your changes, confirm by re-reading the changed sections and
checking:
- `:yin/source-call` appears only in its value/external-coordinate role.
- `:yin/source-node` appears only in its declared-ref/log-local role.
- `event-schema` lists `:yin/source-node` (not `:yin/source-call`) as a ref.
- No prose still says "eid in that batch, or in the log copy" about a single
  attribute.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Output: a numbered list of exact line-range replacements (line numbers from
the current file), each with the old text and the new text, ready for the
orchestrator to apply.
