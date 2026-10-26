Created-GMT: 2026-10-26 14:55:22 GMT
Created-Local: 2026-10-26 21:55:22 +07
Coding-Agent: claude
Session-ID: C34FEBEA-30A9-4DC6-97C8-75FF6B67F0AD (resumed)
# Task: dao.star demo — amend the scoping for the catalog editor (grid)
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-26 21:55:22 +07 | Status: active | Rationale: your scoping needs re-ruling on two points after owner additions

Two owner additions arrived after your scoping, verbatim:
1. "lambdacart is just a reference for functionality. you do not have to use
   any of the code" — so: no ported CSS, no reproduced markup. The demo
   implements the functionality its own way with its own clean UI. Your
   rulings 4 (read grid.cljs/main.cljs, port css) are relaxed accordingly:
   the implementer builds its own minimal dark-themed UI; lambdacart is the
   functional spec only.
2. "don't forget grid.chp which allows users to edit the catalog" — the
   demo has a SECOND face: a catalog editor. In lambdacart, grid is the
   management UI (edit items: name/price/description/images, manage
   tagories, multi-tagory items). The demo must reproduce that
   functionality.

Fact deltas since your scoping (all done): catalog.edn extracted — 249
items (`:item/name :item/price :item/description :item/images :item/tagories
:item/slug`), store "TT Game Stock Online Store"; all 228 referenced
images copied (~68 MB) into
`resources/dao_star_cart/public/images/` (OUT of git — confirm); worktree
`.claude/worktrees/dao-star-demo` at 21649fd8.

Re-rule, read-only, with reasons and rejected alternatives:

1. **The store is now mutable.** Your in-memory `current (relation datoms)`
   ruling was for a read-only catalog. The editor transacts item edits.
   Options: (a) keep the relation-in-an-atom approach — every edit runs
   `transact/prepare-tx` against the current base datoms and the atom holds
   the accumulated datom vector; (b) use a real `dao.space.store` (file-
   backed) so edits persist across demo restarts; (c) relation + periodic
   snapshot to catalog.edn. Rule on what a demo with persistence-honest
   editing should do — consider that a demo whose edits vanish on restart
   looks broken, but so does one that drags in locking machinery.
2. **The editor session**: a second lineage kind (`/grid` path family in
   the router — note the router classifies `/`, `/s/`, `/k/`; how does a
   `/grid` begin path fit the classification table? New kind at the router,
   or a second driver/boundary pair, or begin-with-a-mode?). The editor
   program is its own continuation: list items, edit fields, save
   (transact), add/remove tagories. Ruling on the session/program shape and
   whether the editor's save goes through the same driver (program emits a
   model including the edit; the DRIVER performs the transact — host I/O
   stays out of the program, per Phase B's division).
3. **Cross-face coherence**: when the editor saves, shoppers' open
   subscriptions still show stale data until their next navigation
   (subscriptions re-project on session events, not catalog changes). Rule
   whether the demo accepts that honestly (re-navigation shows the edit;
   state it in the UI or docs), or whether a minimal broadcast (the save
   causes every live lineage's driver to re-project) is worth it. Prefer
   the honest, small option.
4. **Updated file ownership + test plan deltas** for the editor (grid
   renders items; edit save transacts and the storefront's next query sees
   it; tagory add/remove; auth — the owner said "users can edit", so no
   auth for the demo? state it).

Do not edit files. Begin the final response exactly with:
Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and timezone>
