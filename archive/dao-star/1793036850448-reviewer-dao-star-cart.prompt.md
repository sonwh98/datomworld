Created-GMT: 2026-10-27 00:27:30 GMT
Created-Local: 2026-10-27 03:27:30 +07
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: dao.star cart demo — adversarial review of the demo implementation
Role: Reviewer (adversarial)
Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-27 03:27:30 +07 | Status: active | Rationale: independent family (GPT) from the Claude implementer; same reviewer that signed off the Mode 1 layer the demo composes

Read-only adversarial review of the uncommitted demo in
`/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-demo`
(branch dao-star-demo). Do not edit, stage, or commit; do not rerun suites
(the orchestrator's evidence is below); static analysis.

Read first:
- The two architect rulings, BINDING:
  `/Users/sto/workspace/datomworld/collab/1793032277025-architect-dao-star-demo-scope.claude-fable-5-1.findings.md`
  and `/Users/sto/workspace/datomworld/collab/1793033722326-architect-dao-star-demo-amend-grid.claude-fable-5-1.findings.md`
  (note its resolved verify-items and the architect-approved `jvm/handler`
  publicity, already applied).
- The implementer's report:
  `/Users/sto/workspace/datomworld/collab/1793034230245-stream-engineer-dao-star-cart-build.claude-opus-5-5.findings.md`
  — weigh its flagged limits (nested-map flattening, unchanged-field skips,
  retract metadata, the synthetic-routed-input confirmation, the two blank
  placeholder items, the not-run browser/Docker items).
- The demo code: `src/clj/dao/star_cart/*.clj`,
  `src/cljc/dao/star_cart/*.cljc`, `test/dao/star_cart/*`,
  `docker/dao-star-cart/*`, the `cart` bb task, and the one-word
  `jvm.clj` change (`defn-`→`defn` on `handler`).
- The underlying sources it composes: `src/cljc/dao/star/*`,
  `src/cljc/dao/stream/http.cljc`, `src/cljc/dao/space/{transact,query}.cljc`
  surfaces.

Orchestrator-executed evidence (not to be rerun): focused
`clojure -M:test` on the four demo namespaces → 28 tests / 478 assertions /
0 failures; kondo clean; local smoke run: shop begin page 200 with
`data-init="@get('/s/<uuid>')"` and vendored `datastar.js` (1.0.0-RC.7,
30732 bytes) served at `/js/`; grid begin 200; a real product image 200
image/png; traversal 404; deployed on shiva docker port 3004, answering 200
on localhost there (external reach blocked by firewall — an owner item,
not a code item).

Review focus:
1. **Architect conformance**: the mutable store ruling (atom + prepare-tx +
   snapshot-on-save with atomic rename + CATALOG_PATH semantics), the
   two-composition/one-edge design, the effects-interpreter pattern (no
   host I/O in programs; validation in the interpreter; the
   `{:saved}/:rejected` confirmation round trip), the honest
   stale-until-navigation stance.
2. **dao.star discipline**: does the demo abuse the Mode 1 layer anywhere —
   the synthetic routed input (a `:kind :input` with a null exchange), the
   second view-medium cursor, the fixed max-open 256 and one-hour
   subscription cap, fact-before-UI under the editor's save flow?
3. **Security surface**: the static edge's traversal protection (encoded
   separators, normalization, symlink-ish cases), the no-auth grid (is the
   README honest?), image URL restriction in validation, anything the edge
   leaks.
4. **Data handling**: the flattened item/image/tagory transacts, the
   unchanged-field skip, retract metadata `{:db/op :db/retract}` — do these
   match `transact.cljc`'s actual semantics, and can an edit sequence
   corrupt the catalog?
5. **Test honesty**: do the 28 tests actually pin the acceptance table, or
   would any pass on a broken implementation?
6. **Docker/deploy**: trimmed deps completeness, the run notes, anything
   that breaks on a fresh volume.

Deliverable: final response starting with the header fields, a verdict line
(SIGNED OFF / SIGNED OFF WITH CHANGES / NOT SIGNED OFF), findings as
`severity | file:line | evidence | recommended correction`, then the
requested properties that passed.
