Created-GMT: 2026-10-27 00:03:50 GMT
Created-Local: 2026-10-27 03:03:50 +07
Coding-Agent: claude
Session-ID: 59B70221-8A9E-4D0F-91EF-0A9609418224 (fresh task on this seat's session id; new session if unavailable)
# Task: dao.star demo — build the "dao.star cart" (lambdacart reproduction)
Role: DaoStream and Distributed Protocol Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-27 03:03:50 +07 | Status: active | Rationale: the dao.star Mode 1 implementer seat; the demo composes exactly the layer you built

Build the dao.star demo in the worktree
`/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-demo` (branch
`dao-star-demo` at 21649fd8). It reproduces the *functionality* of the
owner's lambdacart shop (live at ttgamestock.com) on the merged dao.star
Mode 1 layer, with dao.space as the database and `dao.space.query/q` for
all queries. lambdacart is a functional reference only — the owner said no
code reuse; the UI is your own clean minimal dark theme.

Read first:
- The two architect scoping rulings, which are BINDING:
  `/Users/sto/workspace/datomworld/collab/1793032277025-architect-dao-star-demo-scope.claude-fable-5-1.findings.md`
  and `/Users/sto/workspace/datomworld/collab/1793033722326-architect-dao-star-demo-amend-grid.claude-fable-5-1.findings.md`.
  Where they conflict, the amendment wins. The amendment's three
  "verify before the brief" items are RESOLVED: `:db/retract` ops exist in
  `transact.cljc`; a parked `dao.await` read wakes via `check-wait-set` on
  the next resume (the confirmation round trip works); a second cursor on a
  ring-buffer view medium is safe under the stream contract.
- `docs/design/dao.star.md` (Mode 1 as built) and the source under
  `src/cljc/dao/star/`, `src/cljc/dao/stream/http.cljc`,
  `src/clj/dao/stream/http/jvm.clj`.
- `src/cljc/dao/space/{transact,query}.cljc` (`prepare-tx`, `relation`,
  `current`, `q`, `collect`) and their tests, for the store pattern.
- The Phase B composition fixture: `test/dao/star/driver_test.clj` — copy
  its proven wiring via one shared `assemble` function, per the amendment.

## Already in the worktree (done by the orchestrator)

- `resources/dao_star_cart/catalog.edn` — 249 items extracted from the live
  Datomic (`:item/name :item/price :item/description :item/images
  :item/tagories :item/slug`) plus `:store/name`. Header-comment the
  extraction form and date per the scoping.
- `resources/dao_star_cart/public/images/` — all 228 referenced images
  (~68 MB), gitignored (the ignore rule is already in `.gitignore`).
- One architect-ruled edit already applied: `jvm/handler` is now `defn`
  (public) so your edge can dispatch to two boundaries. Do not edit any
  other merged file.

## What to build (per the rulings — the two findings carry the details)

1. **`src/clj/dao/star_cart/store.clj`** — the mutable catalog db: an atom
   holding the accumulated datom vector; boot loads `catalog.edn` →
   `transact/prepare-tx` (schema block first: `:item/images` and
   `:item/tagories` are `:db.type/ref`, `:db.cardinality/many`; verify the
   exact cardinality handling in `transact_test.cljc`); queries via
   `(query/collect (query/q ... (query/current (query/relation @datoms))))`;
   `transact-edit!` runs `prepare-tx` inside a `swap!`; snapshot-on-save
   rewrites the catalog file atomically (temp + rename) as plain item maps
   from a fresh `q`, to the path in `CATALOG_PATH` (seed copied there on
   first boot; unwritable path logs and continues in memory).
2. **`src/cljc/dao/star_cart/{program,view,grid_program,grid_view}.cljc`**
   — two yin.vm programs (pure sequential logic: `<!` inputs, local
   cart/editor state, `>!` models) and two pure projection functions.
   Shop: catalog grid → item detail → add/remove → cart page with total →
   checkout stub. Grid: item list → select → edit fields (name, price,
   description), add/remove images and tagories, save → emits
   `{:edit {:item key :changes {...}}}`, renders "saving" then "saved" on
   the `{:saved key}` confirmation input, `{:rejected reason}` shows the
   error. Datastar SSE framing (`datastar-patch-elements` etc.) with a
   pinned, vendored `datastar.js` in `public/js/` — test the exact SSE
   bytes against that version's vocabulary. One small stylesheet, your own.
   Every grid action URL must carry the `/grid` prefix (test scans for it).
3. **`src/clj/dao/star_cart/{catalog,assemble,edge,grid_effects,main}.clj`**
   — catalog query helpers (through `q`+`collect` only); ONE `assemble`
   function wiring boundary + judges + router + driver + media + ticks,
   called for shop and grid (memory-log session facts, `:retention
   :complete`; subscription exchanges carry the required `:dao.lease/max`;
   tick cadence from `lease/renewal-interval`); the host edge (own
   http-kit handler): static `/images/*` `/css/*` `/js/*` from disk with
   traversal protection (normalize against the asset root, regular files
   only, 404 otherwise — tested), `/grid...` prefix-stripped into the grid
   boundary (`(jvm/handler grid-boundary)` with `:uri` rewritten),
   everything else into the shop boundary; `grid_effects` — the stepped
   effects interpreter reading the grid's view medium with its own cursor,
   performing the transact on `:edit` models with VALIDATION (price
   non-negative integer in range; name non-empty, bounded; image URLs
   restricted to existing `/images/` paths; tagories bounded strings), then
   appending `{:saved key}` or `{:rejected reason}` to that session's input
   medium; `main` — boot, both compositions, one tick loop, listener on
   port 3004 (`PORT` env).
4. **`docker/dao-star-cart/`** — a trimmed `deps.edn` (http-kit,
   core.async, data.json, blake3, paths `["src/clj" "src/cljc" "resources"]`;
   FIRST TASK: load the demo namespaces under it and add back any dropped
   transitive dep), a two-stage Dockerfile (tools-deps, `clj -P` prime, run
   `clj -M -m dao.star-cart.main`), and a run note (port 3004, `CATALOG_PATH`
   on a volume, images mounted/baked from the build context). Do NOT run
   docker on shiva — the orchestrator deploys.
5. **Tests** — `test/dao/star_cart/`, the architect's combined table (the
   scoping's 8 + the amendment's 11): JVM only, loopback for the e2e
   faces, in-memory for the rest, `^:slow` per the 5 s rule, bounded waits.
   The full-page loopback test and the stale-shop-view test are required.
6. **`bb.edn`** — one `cart` task running the main namespace, matching the
   existing `demo` task shape.
7. **`README` note** for the demo (in `resources/dao_star_cart/README.md`):
   functionality reproduced, no auth on the grid ("do not expose"), stale-
   until-navigation coherence statement, extraction provenance.

## Acceptance criteria

- `clojure -M:test -n` over your test namespaces: 0 failures, 0 errors.
- `clj -M:kondo --lint` clean on all new files.
- The demo boots from `catalog.edn` and serves the shop and the grid on
  one port in a local smoke run (`bb cart` or `clj -M -m
  dao.star-cart.main` with a short self-check you can paste as evidence).
- No file outside your ownership list is modified (the `jvm.clj` edit is
  already done for you). Excluded: `dao.star.*`, `dao.stream.*`,
  `dao.space.*`, `dao.lease`, `yin.vm.*`, `dao.await`, root `deps.edn`,
  root `bb.edn` except the one `cart` entry, `.gitignore` except nothing
  further needed.
- The trimmed deps actually load (state the evidence).
- Do NOT stage, commit, docker-build on shiva, or publish anything. The
  orchestrator deploys.

Report to
`/Users/sto/workspace/datomworld/collab/1793034230245-stream-engineer-dao-star-cart-build.claude-opus-5-5.findings.md`
(a denied write goes in your final response). Begin the final response
exactly with:
Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and timezone>

Report changed files, exact test/check outcomes, the smoke-run evidence,
unresolved concerns, and any incomplete work. Do not claim edits or tests
that did not occur.
