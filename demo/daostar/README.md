# dao.star cart — start here

A demo of [dao.star](../../docs/design/dao.star.md) Mode 1: a web shop whose
pages are projections of `yin.vm` continuations resumed over
[Datastar](https://data-star.dev/) SSE, with [dao.space](../../docs/design/dao.space.md)
as the database and `dao.space.query/q` for every query. It reproduces the
functionality of the owner's lambdacart shop (ttgamestock.com) with no
lambdacart code.

- **Shop** (`/`): catalog grid with search, item detail, cart, checkout stub.
  The cart is a local of the session's continuation — it lives exactly as long
  as the session.
- **Grid** (`/grid`): the catalog editor. Edit name/price/description, manage
  images and tagories, create items. Saves are validated and transacted by a
  host effects interpreter and persist across restarts.

## Operational notes

- **No authentication on the grid. Do not expose it.** The session token
  identifies a session; it is not authorization.
- **Coherence is stale-until-navigation.** A storefront view shows the catalog
  as of its last projection; a grid save is visible after the shopper navigates
  or reloads. There is no broadcast; the shop footer says so.
- **A subscription ends once per hour** (the exchange lease's `:dao.lease/max`)
  and the browser reconnects (the pages subscribe with `retry: 'always'`),
  which re-projects the current cart.
- **Saves go to `CATALOG_PATH`** (default `target/cart-catalog.edn`, created on
  first boot from the seed, parent directories included). If a path cannot be
  written the demo logs it and holds edits in memory only; with no path at all
  it says so at boot.

## Provenance

`resources/dao_star_cart/catalog.edn` was extracted from the live lambdacart
Datomic on 2026-10-26 (see the header comment in that file for the form): 249
items including two empty placeholder records present in the source. Prices
are minor units. Product images (`resources/dao_star_cart/public/images/`, 228
files, 68 MB) belong to the shop and are gitignored; nothing here is to be
published without the owner's say-so.

## Run it (local JVM)

Prerequisites: Java 21, Clojure CLI. The images under
`resources/dao_star_cart/public/images/` are gitignored — copy them from the
build host or ask the owner (they are also in the running demo container).

```sh
cd demo/daostar
sh bin/vendor-datastar.sh   (from the repo root)   # once: vendor datastar.js (network)
clj -M -m dao.star-cart.main                    # or: bb cart from the repo root
```

Then open <http://localhost:3004/> (shop) and <http://localhost:3004/grid>
(editor). Catalog saves go to `target/cart-catalog.edn` by default; point
`CATALOG_PATH` anywhere writable to keep them elsewhere. `PORT` changes the
port, `ASSET_ROOT` the image/css/js root.

## Run it (Docker)

From the **repository root** (the build context is the repo; the product
images must be present at `demo/daostar/resources/dao_star_cart/public/images/`):

```sh
docker build -f demo/daostar/docker/dao-star-cart/Dockerfile -t dao-star-cart .
docker run -d --name dao-star-cart -p 3004:3004 \
  -v star-cart-data:/data -e CATALOG_PATH=/data/catalog.edn dao-star-cart
```

The image sets `LANG=C.UTF-8` (the catalog has non-ASCII image filenames) and
runs from `/app/demo/daostar`, which is where its `deps.edn` expects to be.

## Test it

```sh
cd demo/daostar
clj -M:test        # 39 tests, ~550 assertions; JVM only
```

The suite boots the real compositions: catalog from the seed through
`dao.space.query/q`, shop and grid over loopback HTTP, search and item
creation, persistence and atomic writes, path-traversal and symlink refusal,
SSE byte framing.

## How it works (one paragraph)

Each browser session is a lineage: the HTTP boundary deposits requests on an
ingress stream, a router classifies them by token, and a driver steps a parked
`yin.vm` continuation — the shop and the grid are two independent
compositions of the same layer behind one host edge. The program is pure
sequential logic: it reads inputs and emits a *model*; the projection is a
pure function from model to Datastar SSE text; the driver appends the SSE
effects to the egress stream only after the session fact recording the new
occurrence is accepted. Catalog queries go through `dao.space.query/q` over an
accumulated datom vector; editor saves run `transact/prepare-tx` in the
effects interpreter, never in the continuation. See
`docs/design/dao.star.md` for the ratified design.
