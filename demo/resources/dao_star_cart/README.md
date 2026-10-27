# dao.star cart

A reproduction of the functionality of the lambdacart shop on dao.star Mode 1,
with dao.space as the database and `dao.space.query/q` for every query. The UI
is its own minimal dark theme; no lambdacart code is reused.

**Shop** (`/`): catalog grid, item detail, add/remove, cart with total, checkout
stub (no payment). The cart is a local of the session's yin.vm continuation and
lives as long as the lineage lease.

**Grid** (`/grid`): catalog editor. Edit name, price (minor units), description;
add/remove images (existing `/images/` files only) and tagories. Every mutation
goes through a host effects interpreter that validates it, transacts it, and
appends `{:saved ..}` / `{:rejected ..}` back to the session; the UI shows
`saving` until then. Each save rewrites `catalog.edn` (atomic rename).

## Read this

- **No authentication on the grid. Do not expose it.** The session token
  identifies a session; it is not authorization.
- **Coherence is stale-until-navigation.** A storefront view shows the catalog as
  of its last projection; a grid save is visible after the shopper navigates or
  reloads. There is no broadcast; the shop footer says so.
- **A subscription ends once per hour** (the exchange lease's `:dao.lease/max`)
  and the browser reconnects (the pages subscribe with `retry: 'always'`), which re-projects the current cart.
- **Saves go to `CATALOG_PATH`** (default `target/cart-catalog.edn`, created on first boot from the
  seed, parent directories included). If a path cannot be written the demo logs it and
  holds edits in memory only; with no path at all it says so at boot.
- **`public/js/datastar.js` must be vendored** at the pinned version
  (`dao.star-cart.view/datastar-version`, 1.0.0-RC.7) before the pages work in a
  browser: `sh resources/dao_star_cart/vendor-datastar.sh`.

## Provenance

`catalog.edn` was extracted from the live lambdacart Datomic on 2026-10-26 (see
the header comment in that file for the form): 249 items including two empty
placeholder records present in the source. Prices are minor units. Product images
(`public/images/`, 228 files, 68 MB) belong to the shop and are gitignored;
nothing here is to be published without the owner's say-so.
