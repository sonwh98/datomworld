Completed-GMT: 2026-10-26 16:34:14 GMT
Completed-Local: 2026-10-26 23:34:14 +0700

I edited no files. The rulings below rest on these sources: the `dao.space.query` public surface (`relation`, `current`, `q`, `collect`), the signatures of `transact/prepare-tx` and `dao.star.driver`, `jvm.clj`'s function list, and `deps.edn`. I did not open the lambdacart cljs sources, any Dockerfile, the `store.cljc` internals, or the Phase B world fixture in `driver_test.clj`. Where a ruling depends on one of these, it carries a "verify" note, collected at the end of section 7.

## 1. dao.space store for the demo

**Ruling:** no file-backed store. The demo builds an in-memory datom vector once at boot and gives `q` a `current` view over a `relation`.

- Boot loads `catalog.edn`, which is plain `tx-data`.
- It runs `transact/prepare-tx {:base-datoms [] :tx-data tx}`. That answers `{:datoms [...] :tempids ...}` in canonical d5 shape `[e a v t m]`.
- The catalog db is then `(query/current (query/relation datoms))`.
- Queries are `(query/collect (query/q '[:find ... :in $ ... :where ...] db arg ...))`. The `$` input is the view value. `q` opens nothing and closes nothing, so there is no handle to own.

**Reasons:**
- The catalog is read-only for shoppers. The cart lives in a continuation, so nothing in the demo writes.
- The store layer (`store.cljc`, `store/fs.cljc`) is content-addressed file persistence with locks and a head/version file. That is durability machinery the demo does not need, and it adds a lock and a directory for the container to own.
- The fixtures for the Datomic conformance corpus feed `query/q` by this same route (relation values), so it is the intended in-process composition for tests.

**Schema and refs.** `prepare-tx` computes the set of `:db.type/ref` attributes from schema datoms in the base or the same tx. So the catalog starts with a schema block:
- `:item/images` and `:item/tagories` as `{:db/ident ... :db/valueType :db.type/ref :db/cardinality :db.cardinality/many}`;
- `:store/catalogs` or the minimal tenant link, if kept.

Verify the exact `:db/cardinality` handling against `transact_test.cljc` before the brief fixes it.

**Entity ids.** Do not carry Datomic entity ids. Use tempids in `tx-data` (or nested maps if `parse-tx-data` supports them, which I did not confirm). Items should carry a stable lookup key, `:item/slug` or a string id derived at extraction, so the URL token for item detail does not depend on allocated eids. Resolve refs to nested entity maps in the EDN, not to keywords. Keywords are only right for enum-like values, and tagory names are data, not idents.

**Rejected:**
- A file-backed `dao.space.store`. It adds locking and a head file for nothing.
- Keeping Datomic entity ids. They are meaningless outside that database and would collide with `datom/first-user-id` allocation.
- The compat layer. The owner excluded it.

## 2. Extraction plan

**Extract:**
- The store entity, with name only.
- One minimal tenant, if the schema requires the link. Otherwise drop it.
- All items with `:item/name`, `:item/price`, `:item/description`.
- Each item's `:item/images` (`:image/url`, `:image/alt`) and `:item/tagories` (`:tagory/name`).

Skip accounts, wallets, orders and addresses, as proposed. They are shopper-private and the cart is a continuation.

**Form:** run once, by the orchestrator or a bb task, on shiva:
`docker exec lambdacart-app-1 clj -M -e "<form>"` with the connection string you gave. The form does a Datomic `pull` over the item eids with the pattern above, converts entity maps to plain nested maps without `:db/id`, and `pr-str`s one EDN vector of item maps to stdout.

Two cautions:
- Redirect only stdout, because the JVM may print warnings on stderr.
- Do not pass the password beyond the already-public compose default. Do not commit connection strings.

**On disk:** `resources/dao_star_cart/catalog.edn` in the demo. It is a vector of item maps plus the store map, and boot turns it into `tx-data`. Check the EDN into the demo worktree: it is a few hundred small maps, plain data and reproducible from the extraction form. Record the extraction form and date in a header comment on the file.

**Images:** copy only the files the catalog's `:image/url` values name, from `/Users/sto/workspace/lambdacart/public/images`, into `resources/dao_star_cart/public/images/`. Fall back to the full directory only if it is small. Check the total size first. If it is large, mount at run time and keep the images out of git. Copy the css and font files the theme needs into `public/css/`.

**Rejected:**
- Extracting at app boot against live Datomic. The demo must not depend on the live stack.
- Hand-writing a catalog. That would not reproduce the live data.

## 3. Static assets vs the boundary

**Confirmed, with one tightening.** Host isolation is the correct reading. The boundary is application-agnostic and the composition's host edge owns dispatch.

The host edge is one http-kit handler. For each request:
- A path under `/images/` or `/css/` is served from disk and never enters the boundary.
- Every other path goes to `http/accept!`.

**Tightening:** the static branch must resolve the path against the asset root and refuse anything that normalizes outside it (`..`, encoded separators, absolute paths). Serve only regular files, set `Content-Type` from the extension, and answer 404 otherwise. This is the only new attack surface the demo adds, and it needs a test.

Do not route static requests through the boundary "for uniformity". Exchanges are leased and ingress is a bounded window, and image traffic would evict real requests.

## 4. The app

**Layout:** `src/cljc/dao/star/cart/` is wrong, because it implies the library layer owns the app. Following the repo, which has no existing `demo` source tree (`yin.demo` is a main namespace with a `bb demo` task), use:
- `src/clj/dao/star_cart/` for host code: `main.clj` (boot, composition, listener, static edge), `catalog.clj` (load and query).
- `src/cljc/dao/star_cart/` for pure code: `program.cljc` (the session program source), `view.cljc` (projection and HTML).
- `resources/dao_star_cart/` for data and assets.
- `bb.edn` task `cart` running `clj -M -m dao.star-cart.main` on port 3004, matching the `demo` task shape. Verify that namespace-to-path munging in the brief.

**The session program.** A yin.vm program run through `dao.await/run`, which the driver already steps. Per Phase B's ruling, the program is pure sequential logic. It reads inputs from its input medium with `<!`, holds `cart` as a local in its loop, and emits a model to its view medium with `>!`. The model is a plain map: the page (`:catalog`, `:item`, `:cart`, `:checkout`), the item key when on a detail page, and the cart (a vector of `{:item key :qty n}`).

Input kinds are fixed by what the signals carry: navigate, add, remove and checkout, each a small map decoded by `dao.star.signals/decode`. The cart is never a server table. It is a local binding in the continuation, so it survives exactly as long as the lineage lease does.

The program does no catalog queries. The driver-side view function does, because it is a pure projection: `(project-fn occurrence-id model)` calls `catalog/items` and `catalog/item` over the db value closed over at composition time. That keeps the program free of host values and keeps re-projection (subscribe and stale) valid without stepping.

**Projection.** `view.cljc` renders the SSE text using Datastar's `datastar-patch-elements` format. It has one function per page, plus the begin function for the complete HTML document at `/` (`datastar.js`, the stylesheet link, actions carrying the root id, `data-init="@get('/s/<token>')"`). Port the live site's CSS from `public/css` and take the grid structure from `src/cljs/lambdacart/grid.cljs` and `main.cljs`. I haven't read those; the implementer reads them first and reproduces markup structure, not behavior.

Serve `datastar.js` from the static edge. Vendor the file into `public/js/`, don't hotlink a CDN. Pin the version, and make sure the SSE event names match that version's vocabulary.

**Composition (per merged Mode 1):** boundary (`http/make` or the equivalent from Phase A), the exchange judge with `close-exchange` and `open-exchanges`, the lineage judge, the router over the session-facts medium and the lineage lease, and the driver. `session-facts` is a `memory-log` declared `:retention :complete`. Subscription exchanges carry the required `:dao.lease/max`. One tick loop drives the judges, router and driver at the cadence the lease sizing rules require.

Copy the wiring from the Phase B test world in `test/dao/star/driver_test.clj` rather than inventing it. That fixture is the proven composition. I did not read it, so verify the helper names in the brief.

**Rejected:**
- The cart as a dao.space table. It contradicts the demo's point.
- Catalog queries inside the program. They would put host state into a continuation.
- A new namespace under `dao.star.*`.

## 5. Deployment

**Image:** the demo image needs the repo's source and a trimmed dependency set. Two findings from `deps.edn`:
- The root `deps.edn` carries `com.datomic/peer`, `org.postgresql`, `stigmergy/chp 0.1.0-SNAPSHOT` and `io.github.rctcwyvrn/blake3`. `stigmergy/chp` exists under `~/.m2/repository/stigmergy`, which suggests a local install that a clean Docker build can't resolve. I did not confirm how it resolves elsewhere.
- Root `:paths` includes build output directories that don't exist in a clean checkout, such as `build/antlr/*/classes`.

**Ruling:** a demo-specific `deps.edn` at `docker/dao-star-cart/deps.edn` with only what the demo uses: `http-kit`, `core.async`, `data.json`, the blake3 dependency, and the `:paths` list `["src/clj" "src/cljc" "resources"]`. The Dockerfile copies `src/clj` and `src/cljc` from the repo plus the demo's resources. This avoids Datomic, Postgres and chp entirely. Verify by loading the demo's namespaces under that trimmed alias: if `dao.stream.*`, `dao.space.*` or `yin.vm.*` pull in a namespace that needs a dropped dependency, add that one dependency back. That check is the implementer's first task.

**Build and run:**
- Stage 1 (build): `clojure:temurin-21-tools-deps` or the image the lambdacart app already uses. Copy deps first and run `clj -P` to prime the cache.
- Final stage runs `clj -M -m dao.star-cart.main` with `PORT=3004` and the asset root as environment.
- No AOT or uberjar for the first cut. Source-loading start time is acceptable for a demo. An uberjar is a later optimization.
- Bake images and css into the image because they are small and make the image self-contained. If the image size from section 2 says otherwise, mount `./public` read-only instead.

**Port:** 3004 in the container, mapped `3004:3004` in a separate compose file or `docker run`. Do not edit the lambdacart compose project.

**Shiva contention:** the build runs plain `clj -P` and a JVM, which is light. It must not run `bb` tasks that touch the antlr or flutter lanes and must not run the repo's full test suites on shiva. Run the tests locally, build the image on shiva with `docker build` only, use `--memory`/`--cpus` limits on the container, and schedule the build when the orchestrator knows those lanes are quiet. Build with BuildKit and one parallel job.

## 6. Test plan (JVM only)

| Acceptance | Test | Medium |
|---|---|---|
| Catalog renders N real items with real image URLs from dao.space | Boot from `catalog.edn`. Count items by `q`, request `/`, then the subscribe, and assert N `<img src="/images/...">` elements, each URL equal to a catalog `:image/url`. | loopback socket |
| Image URLs resolve | For every URL in the first page, GET it from the static edge and assert 200, the right content type and non-empty bytes. | loopback |
| Add-to-cart parks and resumes with state intact | POST an add for item A, then one for B, and assert the cart view shows both with the right quantities. A third add of A shows qty 2, which proves state persists in the continuation across resumes. | loopback |
| Cart contents render | Navigate to the cart page and assert names, prices, quantities and a computed total. | in-memory driver world |
| Stale token is harmless | Replay the pre-add token and assert the cart does not change. | in-memory |
| Static path traversal refused | GET `/images/../deps.edn`, `/images/%2e%2e/...` and an absolute path, and assert 404 with no file content. | loopback |
| Catalog query path | A direct test that `catalog/items` goes through `dao.space.query/q` and `collect`, with a fixture of 3 items. | pure |
| Boot from the checked-in file | Parse `catalog.edn`, transact, and assert every item has a name, a price and at least one image. | pure |

Keep one full-page loopback test. Mark any test that needs real time `^:slow`. Everything else goes in the quick tier. Add no CLJS or CLJD lane work.

## 7. File ownership and risks

**The implementer owns (new files only):**
- `src/clj/dao/star_cart/*.clj`
- `src/cljc/dao/star_cart/*.cljc`
- `resources/dao_star_cart/**`
- `docker/dao-star-cart/**`
- `test/dao/star_cart/**`
- one `bb.edn` entry (`cart`)

**Not touched:** `dao.star.*`, `dao.stream.*`, `dao.space.*`, `dao.lease`, `yin.vm.*`, `dao.await`, and the root `deps.edn`. Changes there come back to the Architect.

**Risks, in order:**
1. **The Datastar contract.** Event names, the data prefix per line and the required blank line differ by version. Pin the version and test the exact bytes against the vendored `datastar.js`'s own expectations. A `text/event-stream` response with the wrong framing fails silently in the browser.
2. **Trimmed deps hiding a transitive require.** See section 5. The implementer finds out in minutes by loading the namespaces under the trimmed alias.
3. **Subscription max reconnects.** Every healthy subscription reconnects once per `:dao.lease/max` (Mode 1's cost). Pick a max long enough to be invisible in a demo, and test that the reconnect re-projects the current cart.
4. **The program/driver division of labor.** If the implementer lets the program touch the catalog or egress, the cart stops being a clean continuation and fact-before-UI ordering breaks. The brief says the program only reads inputs and emits a model.
5. **Image licensing and size.** Product photos are the shop's. The demo is on a private shiva port. The brief should say nothing is published or committed to a public remote without the owner's say-so. I'd keep `images/` out of git if the set is large.
6. **Extraction hygiene.** The Datomic password in the proposed connection string is the compose default. It must not land in a committed file.
7. **Price units.** `2400` looks like minor units, but I didn't verify. Check how `grid.cljs` formats it before rendering.
8. **Tick loop and the JVM.** One thread drives the judges, router and driver. Its cadence must satisfy the lease sizing relations, and the demo must set it from `lease/renewal-interval`, not by hand.

**To verify before the brief is final:** the `prepare-tx` shape for nested maps and cardinality-many refs (`transact_test.cljc`); the Phase B world fixture's helper names (`driver_test.clj`); the lambdacart `grid.cljs` and `main.cljs` markup; the size of `public/images`; and the trimmed-deps load.
