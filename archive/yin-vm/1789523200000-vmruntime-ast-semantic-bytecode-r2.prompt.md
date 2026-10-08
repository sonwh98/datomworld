Created-GMT: 2026-09-15 21:26:40 GMT
Created-Local: 2026-09-16 04:26:40 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 206534e8-0432-4941-8631-0212c8f59132
# Task: ast->semantic-bytecode — fix 4 blocking findings from adversarial review
Role: VM Runtime
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-16 04:26:40 +07 | Status: active | Rationale: same implementer, resumed session, fixing own work

## Context

An independent adversarial review (gpt-6-astra,
`collab/1789522650000-review-ast-semantic-bytecode.gpt-6-astra.stdout.log`
— read the full report) verdict was **No, not ready for Architect
sign-off**. It reproduced 4 blocking defects with concrete probes, plus
one non-blocking coverage gap. All were verified real by the orchestrator
against the reviewer's report (not yet independently re-run by the
orchestrator — you should reproduce them yourself as part of fixing
them). Fix all four:

## Findings to fix

**1. Blocking — `:lambda :params` grammar-kind mismatch.**
`semantic-bytecode-grammar`'s `:lambda` entry declares `[:params :data]`;
the design doc now says `syms` (this was also just fixed in the doc,
commit `d48ed4f`, in case your session predates it — re-read
`docs/design/yin.vm.code-as-tuples.md` §2.2/§2.3 now, they define a
proper `syms` slot kind: "a vector of symbols, possibly empty"). Fix:
- Declare `:params` as `[:params :syms]` in the grammar map.
- Add an explicit `:syms` case to `slot-value` (projection): coerce to a
  vector and strip symbol metadata on each element — this replaces the
  current special-cased `(if (= [tag field] [:lambda :params]) ...)`
  branch with a general `:syms` kind handler (any future `syms`-kind slot
  gets the same treatment for free).
- Add validation in `semantic-bytecode->ast`'s reconstruction (`child` or
  a new case): a `:syms` slot must be a vector where every element is a
  symbol, else throw the `:slot-kind` defect (matching how `:nodes`
  already validates `vector?`). Currently a hashed `[:lambda nil
  child-id]` reconstructs successfully and then re-projects as `[]`,
  silently changing its own address — this must instead throw at
  reconstruction time.

**2. Blocking — structural sharing can silently overwrite retained
metadata.** Reproduced: `(with-meta 'x {:meaning 1})` and
`(with-meta 'x {:meaning 2})` used as two different `:literal` values (or
as `:variable`/`:global` names, or nested in `:data`-kind payloads) hash
to the same row id, because `dao.jing`'s scalar printing doesn't preserve
symbol/keyword metadata (a known, disclosed `dao.jing` residual — do NOT
try to fix `dao.jing.cljc`, that's out of scope and owned by a concurrent
unit). Right now your collision guard (`(when (and prior (not= prior
row)) (throw ...))`) can't detect this because plain `=` also ignores
metadata, so two rows that print identically but have different retained
metadata look "equal" to the guard and the wrong one silently wins.

Fix: make the collision guard metadata-aware. Compare not just `(not=
prior row)` but also whether any metadata-bearing value inside the two
row bodies differs in its metadata (a recursive check, or simpler:
compare `(pr-str {:row row :meta (deep-meta-of row)})` against the same
for `prior`, where `deep-meta-of` walks the body and collects
`(meta ...)` at every position that can carry it). This won't fix the
underlying dao.jing scalar-metadata gap, but it converts silent data
corruption into a loud, informative throw — consistent with how
`dao.jing.md` treats every other detected collision. Document in the
function's docstring that this class of collision (dao.jing's
undisclosed-at-encoder-level scalar metadata) is a known limitation
inherited from `dao.jing`, not fixed here, and will resolve when
`dao.jing`'s scalar-metadata handling is eventually closed.

**3. Blocking — reader provenance can remain inside a row body.**
Reproduced: a `:literal` whose value is `(with-meta 'x {:line 77 :column
9})` leaves that reader-position metadata on the symbol inside the row
body, because `:data`-kind slots pass values through unchanged and
`dao.jing`'s metadata stripping (`:line`/`:column`/`:end-line`/`:end-column`)
only applies at the top level of what's passed to `content-hash`, not
recursively inside opaque `data` payloads it doesn't introspect that
deeply for every nested scalar — actually check this precisely: does
`dao.jing/order-normalize` recurse into and strip reader-position
metadata from every nested scalar, or only from collection-level
metadata? Verify empirically. If `dao.jing` truly does not strip
reader-position metadata from a bare symbol nested inside a data
payload, this is the same underlying class as Finding 2 (an inherited
`dao.jing` scalar-metadata gap) — document it the same way, no code fix
possible here without touching `dao.jing.cljc` (out of scope). If instead
you find this IS something `ast->semantic-bytecode` itself could
reasonably strip (e.g. if the map-AST node's own `:value` field is where
reader metadata typically lands via the frontend, and you can strip it at
the node-processing level before it ever reaches `dao.jing`), do that
instead — but verify which case actually applies before choosing a fix.

**4. Blocking — accepted malformed rows change silently on
re-projection.** Reproduced: a correctly-hashed `:application` row with
`:tail? :not-a-bool` reconstructs without complaint, then re-projects
with `:tail? true` (via the `(boolean v)` coercion) — a different address
than what was loaded. Similarly a lambda with nil params reconstructs,
then re-projects with `[]` (Finding 1 covers the params case).

Fix: add slot-kind validation in reconstruction for the kinds that can
currently be silently coerced: `:bool` (must be literally `true` or
`false`, throw `:slot-kind` otherwise) and the new `:syms` case from
Finding 1. You do NOT need to implement the full §7.4 validator
(`:data`/`key` satisfying `plain-data?`, `:acyclic`, `:root-reachable`,
`:variable-scope` — note the design doc renamed this from
`:variable-bounds`, re-read the current doc) — the reviewer agreed a
separate full validator is a reasonable architectural boundary. Just
close the two concrete reproduced holes (`:bool`, `:syms`) so a
`build`/reconstruct call rejects them instead of silently normalizing
past the loaded content.

## Non-blocking (fix if cheap, otherwise just note in findings)

Finding 5: the reviewer counted 28 corpus entries, not 27 as your
findings claimed — just a documentation/report accuracy issue, verify and
correct your own count if you report it again.

## Contract

Re-run `test/yin/vm_test.cljc` and confirm 0 failures with the
existing 15+ tests still passing. Add new tests reproducing each of the
4 findings' failure scenarios and asserting they now throw/behave
correctly (the reviewer's exact reproductions are good starting points —
re-derive them from the report, don't guess).

## Boundaries

Only `src/cljc/yin/vm.cljc` and `test/yin/vm_test.cljc`. Do not
touch `dao.jing.cljc`, `dao.jing.md`, the code-as-tuples doc, or any
other file. Do not run `bb test:cljd` unless you've confirmed the CLJD
lane is free (check `ps` for any other running delegate process before
assuming so — if unsure, skip it and say so).

## Deliverable

Report exact diff per finding, reproduction-then-fix evidence for each,
and test results. Write findings to
`collab/1789523200000-vmruntime-ast-semantic-bytecode-r2.claude-opus-5.findings.md`
with the same header block as this prompt. Nothing staged or committed.
