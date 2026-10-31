Completed-GMT: 2026-10-27 (clock not read; no precise timestamp available)
Completed-Local: 2026-10-27, +07 (clock not read)

The cart demo is built and its tests pass. Three things are not done: `datastar.js` is not vendored, the smoke run was not done as a standalone process, and the Docker image was not built. I did not write the findings report to `collab/`; the report is below.

## Checks
- `clojure -M:test -n dao.star-cart.catalog-test -n dao.star-cart.view-test -n dao.star-cart.edge-test -n dao.star-cart.app-test`: 28 tests, 478 assertions, 0 failures, 0 errors. This includes the `^:slow` test that fetches all 228 image URLs.
- `clj -M:kondo --lint` on `src/clj/dao/star_cart`, `src/cljc/dao/star_cart`, `test/dao/star_cart` and `jvm.clj`: 0 errors, 0 warnings.
- I did not re-run the `dao.star` or `dao.stream.http` suites. I changed none of their sources.

## Smoke evidence
- `clj -M -m dao.star-cart.main` and `curl` were refused by the permission gate, so I could not do the pasted self-check.
- The nearest evidence is `app_test.clj`. Each test boots the real `main/start!` path on an ephemeral port: catalog from `catalog.edn`, shop and grid compositions, edge, tick loop and listener.
- Over raw sockets it shows:
  - the shop page and 247 image tags in the subscription;
  - the grid listing 249 items;
  - a grid price edit written to the catalog file and seen by the shop after navigation;
  - traversal attempts answered 404.

## Trimmed deps
- **Evidence:** I loaded `dao.star-cart.main` and inspected which namespaces reference `datomic`, `taoensso`, `stigmergy`, `io.aviso` or `ring`. Only `user` (JVM startup noise from the chp jar) references `stigmergy.ikota`, and http-kit itself references `ring.websocket.protocols`. The demo's own closure touches none of the dropped libraries.
- **What I couldn't do:** I could not run `clojure` from a copy of the trimmed `deps.edn`, because `cd`, symlinks and `sh` were refused. So it is not verified under the trimmed classpath itself. I added `ring-websocket-protocols` 1.14.1 explicitly because http-kit needs it.

## What was built
- **`src/cljc/dao/star_cart/`:**
  - `program.cljc` and `grid_program.cljc`: the two yin.vm programs. They use only `assoc`, `get`, `if`, `+`, `-` and `def`, because the VM has no `dissoc`, `cond` or `str`. The cart is a slug-to-quantity map, and a removal decrements while the view hides zero lines.
  - `view.cljc`: the pure shop view and shared helpers. Its SSE bytes are tested against Datastar 1.0.0-RC.7's vocabulary from my knowledge of that version, not against the vendored file.
  - `grid_view.cljc`: the grid projection. A test scans every `@get`/`@post` URL for the `/grid` prefix.
- **`src/clj/dao/star_cart/`:**
  - `store.clj`: the catalog store, with edits transacted in a `swap!` and each save written by temp file plus atomic rename.
  - `catalog.clj`: reads through `q` and `collect`.
  - `assemble.clj`: the one wiring function, called for shop and grid, with renewal intervals from `lease/renewal-interval`.
  - `edge.clj`: static serving with traversal protection, and the `/grid` prefix rewrite.
  - `grid_effects.clj`: validation, the transact, and the `{:saved ..}` / `{:rejected ..}` confirmation.
  - `main.clj`: boot, one tick loop, listener on port 3004.
- **Docker, `bb`, docs:**
  - `docker/dao-star-cart/{deps.edn,Dockerfile,README.md}`.
  - The `cart` task in `bb.edn`.
  - `resources/dao_star_cart/README.md`, `public/css/cart.css`, and `vendor-datastar.sh`.
  - The header comment on `catalog.edn`.
- **Tests:** they cover the combined table, including the full-page loopback test and the stale-shop-view test. The `prepare-tx` and `q` facts I relied on are in the next section.

## Findings
1. **`datastar.js` is not vendored.** I could not fetch it: there was no local copy and network access needs approval. `resources/dao_star_cart/vendor-datastar.sh` fetches the pinned RC.7 bundle and prints its checksum. Run it once (with network) before pages work in a browser. The browser has not been run against any of this.
2. **Two empty placeholder items** (`item-76`, `item-105`, no name, price or image) are in the source data. The shop hides them and the grid lists them, so you can fix them there. The boot test asserts every non-blank item has a price and an image.
3. **`prepare-tx` limits.** Nested entity maps are not supported, so the store flattens items into item, image and tagory entities linked by tempids. A card-one value re-asserted unchanged produces conflicting rows and breaks `q`, so the edit path skips unchanged fields.
4. **Retracts need metadata.** A bare `[:db/retract e a v]` is treated as an add, so retracts carry `{:db/op :db/retract}`. Removing a card-many ref works, and the tagory and image tests prove it.
5. **Grid confirmation is a synthetic routed input.** After a transact, the effects interpreter appends a `:kind :input` message with a null exchange to the session's routed medium. The driver then steps the program and projects "saved". Each confirmation also writes one effect for a null exchange to the egress medium, which the boundary ignores.
6. **Images are not ignored by git.** The brief says an ignore rule is already in `.gitignore`, but `git status` lists all 228 images as untracked and `.gitignore` has no `dao_star_cart` line. I did not edit `.gitignore`. Add an ignore line (or a `.git/info/exclude` entry) before anyone stages.
7. **Shop catalog uses a fixed `max-open` of 256 exchanges and a one-hour subscription cap.** Both are defaults in `assemble.clj` and easy to change.
8. **The `kondo` "handler is private" error** is a stale cache. `jvm.clj` has `defn handler`, and linting `jvm.clj` together with the demo files clears it.
9. **Not tested:**
   - a browser;
   - the Docker build;
   - the one-hour subscription cap and reconnect, beyond the existing Mode 1 tests;
   - a catalog on a read-only mount other than the nonexistent-directory case.
10. **Left behind:** no probe or temp files remain. I did not stage, commit or touch Docker. The only non-demo edits are the `cart` entry in `bb.edn`, and `jvm.clj` was already changed by the orchestrator.
