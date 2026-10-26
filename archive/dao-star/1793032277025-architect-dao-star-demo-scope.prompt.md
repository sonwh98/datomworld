Created-GMT: 2026-10-26 14:11:17 GMT
Created-Local: 2026-10-26 21:11:17 +07
Coding-Agent: claude
Session-ID: C34FEBEA-30A9-4DC6-97C8-75FF6B67F0AD (resumed)
# Task: dao.star demo — scope the lambdacart reproduction ("dao.star cart")
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-26 21:11:17 +07 | Status: active | Rationale: you are the dao.star track's architect; the demo builds directly on the merged Mode 1 you ruled on

Read-only scoping. Owner instruction, verbatim: "let's create a dao.star
demo reproducing git@github.com:sonwh98/lambdacart.git. The app is actually
running on shiva docker container. You can extract data from datomic also
running on docker container and the image assets. You have access
git@github.com:sonwh98/lambdacart.git. You're the orchestrator for this
demo. Use dao.space instead of datomic for the database. For queries use
dao.space.query/q because datomic compatibility layer is being worked on
and not complete yet." Also relayed verbatim: "the live site is
https://www.ttgamestock.com/".

Worktree for the demo: `/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-demo`
(branch dao-star-demo at 21649fd8). The dao.star Mode 1 layer is merged to
master (boundary: `dao.stream.http` + `dao.stream.http.jvm`; layer:
`dao.star.facts/signals/lease/router/driver`; see `docs/design/dao.star.md`).
Your Phase B scoping findings and the r2 rulings are in
`archive/dao-star/` under collab numbers 1792832568086 (phase-b-scope) and
1792841840885 (reconcile-r2).

## Verified facts (orchestrator recon)

- **Live stack on shiva**: docker containers lambdacart-app-1 (port 3003),
  lambdacart-transactor-1 (4334-4336 → host 4344-4346), lambdacart-db-1
  (postgres, host 5433). The app container has java/clj/clojure and the
  full checkout at /app — a ready extraction vehicle:
  `docker exec lambdacart-app-1 clj -M -e "..."` against
  `datomic:sql://lambdacart?jdbc:postgresql://db:5432/datomic?user=datomic&password=datomic`.
- **Live data** (read-verified): items `{:item/name :item/price
  :item/description {:item/images [:image/url :image/alt]}
  {:item/tagories [:tagory/name]}}`, e.g. "P4-Game Controllers White",
  price 2400, image "/images/P-140 White.png". One store:
  "TT Game Stock Online Store". Schema (lambdacart resources/schema.edn):
  tenant→stores→catalogs→items, tagories, addresses, accounts→wallets,
  orders. Seed files: resources/tours.edn, resources/tt-cosmetics.edn.
- **Assets**: public/images (hash and name-named jpg/png, e.g.
  "/images/P-140 White.png"), public/css, public/chp templates. The local
  clone /Users/sto/workspace/lambdacart has them; shiva's compose mounts
  ./public/images.
- **UI ground truth**: the live site is a client-rendered cljs SPA; the
  markup lives in the repo's src/cljs/lambdacart/{grid,main}.cljs and
  public/css. Read them for the page structure the demo should reproduce
  (shopper-facing: product grid with images/names/prices, item detail,
  the dark gaming theme).
- **dao.space**: `src/cljc/dao/space/{store,transact,transactor,query}.cljc`
  and docs (dao.space.md is the storage boundary; dao.space.query/q takes
  Datomic-shaped queries with a `$` database input — a query value, a datom
  view over one, or an opened published index — and `collect` materializes).
  The Datomic compatibility layer is explicitly OUT: raw dao.space datoms +
  dao.space.query/q only.
- **Owner's constraint, verbatim**: "Use dao.space instead of datomic for
  the database. For queries use dao.space.query/q."

## Rule on (give a ruling with reasons and rejected alternatives; smallest
demo that honestly reproduces the shopper-facing site on dao.star):

1. **dao.space store for the demo**: which constructor a local
   single-process demo uses (file-backed or memory store — read
   src/cljc/dao/space/store.cljc and the tests for the intended composition),
   how the extracted data gets transacted (datom shape, whether a schema
   declaration step is needed, ref attributes — keep entity ids or resolve
   to keywords), and how `dao.space.query/q` binds its `$` input over that
   store in-process.
2. **Extraction plan**: what to pull from Datomic (we propose: items with
   images+tagories, the store, minimal tenant; skip accounts/wallets/orders
   unless they are one map away — the demo's cart is a dao.star continuation,
   not a Datomic order), the exact docker-exec extraction form, and the EDN
   shape on disk that the demo loads (resources/ of the demo? checked into
   the demo worktree? images copied from the lambdacart clone?).
3. **Static assets vs the boundary**: the HTTP boundary is
   application-agnostic and the composition's host edge owns dispatch. Rule
   the concrete split: dao.star handles `/` (begin), `/s/<token>`
   (subscribe), `/k/<token>` (input); the host edge answers `/images/*`,
   `/css/*` directly from disk (http-kit ring handler beside accept!), so
   unrouted paths never enter the boundary. Confirm this is the right
   reading of host isolation or correct it.
4. **The app**: the session program as a yin.vm continuation — Arc-style
   cart: begin page = catalog grid (items from dao.space.query/q), item
   detail, add-to-cart appends to a cart held in the continuation's own
   state (never a server table), checkout stub. Projection functions render
   Datastar SSE patches in the site's dark gaming theme (port the css).
   Namespace layout (proposal: `src/demo/dao_star_cart/` or similar — check
   how existing demos like yin.demo are organized and where a `bb` task
   fits), and what the composition wires (boundary + judge + router +
   driver per the merged Mode 1).
5. **Deployment**: a Dockerfile for the demo container on shiva (JVM, deps
   from the datomworld repo — how does a demo image get the repo's deps?
   deps.edn with a local root? uberjar via bb? read how the existing
   Dockerfile in datomworld's oracle work, if any, does it), port 3004
   (3003 is the live app), images baked or mounted. Note: shiva is BUSY
   with other seats' work — the build must not contend for the antlr or
   flutter lanes.
6. **Test plan**: the demo's acceptance — loopback e2e: catalog renders
   N real items with real image URLs from dao.space, an add-to-cart
   continuation parks and resumes with the cart state intact, cart
   contents render, static asset paths resolve. Keep it JVM-lane honest
   (no cljs lane work unless trivial).
7. **File ownership + risk list** for the implementer brief.

Deliverable: your final response (the orchestrator promotes it). Do not
edit files. Begin the final response exactly with:
Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and timezone>
