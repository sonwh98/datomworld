# dao.star cart: run note

- Port **3004** in the container; map `3004:3004` (a separate compose file or
  `docker run`; do not touch the lambdacart compose project).
- `CATALOG_PATH` (default `/data/catalog.edn`) must be on a **volume**. On first
  boot with an empty volume the baked seed `catalog.edn` is copied there; every
  grid save rewrites it atomically. If the path is unwritable the demo logs it
  and keeps editing in memory. Edits in a running container never touch the repo
  copy of `catalog.edn`.
- Images are baked from the build context (`resources/dao_star_cart/public/images/`,
  68 MB, gitignored). Alternatively mount that directory read-only over
  `/app/resources/dao_star_cart/public/images` and set `ASSET_ROOT` if the layout differs.
- `deps.edn` here is trimmed (no datomic, postgres, antlr, jsoup, stigmergy/chp):
  the demo's namespace closure was checked to load none of them.
- Run limits on shiva: `docker run --memory 1g --cpus 1`; build with BuildKit and
  `docker build` only. Do not run `bb` lanes or the repo test suites there.
- **No authentication on `/grid`: do not expose this port beyond the private
  interface.**
