Completed-GMT: 2026-10-26 16:58:25 GMT
Completed-Local: 2026-10-26 23:58:25 +0700

I edited nothing. This amendment rests on `router.cljc` (`classify`, `make-router`), `driver.cljc` (`make-driver`), `jvm.clj` (`handler`, `listen!`), and `git status` in the demo worktree. I did not read `grid.chp`, the lambdacart editor code, `store.cljc`, or `transact_test.cljc`. The editor's feature list below comes from your prompt.

## Two facts that shape the rulings

1. **`router/classify` is closed.** It is hardwired to `/`, `/s/<t>` and `/k/<t>`, and any other path is `:unrouted`. `make-router` takes one `:begin` medium, and `make-driver` takes one `:program`, `:project-fn` and `:begin-fn`. A composition is therefore one app. The Mode 1 layer is merged and signed off, so it should not grow a "mode" for this demo.
2. **`jvm/handler` is private, and `listen!` binds one listener per boundary.** The demo's host edge needs to serve static files and dispatch to two boundaries on one port, so it needs the accept path. This is the one change I rule for a Phase A file; see ruling 2.

## 1. Mutable store

**Ruling:** option (a), plus one small persistence step. The store is an atom holding the accumulated datom vector. Each edit runs `transact/prepare-tx` against the current base datoms and swaps in the result. The storefront and the editor query `(query/current (query/relation @datoms))` at request time. On every successful save the demo also writes `catalog.edn` back, atomically (write a temp file beside it, then rename).

**Persisted form:** the file stays plain item maps, which is the format already extracted, not raw datoms. The save path derives the maps from the db by `q` or `pull` and writes the whole vector. Boot loads that file. Edits therefore survive a restart, and the file stays human-readable and re-extractable.

**Why:**
- The demo's honesty requirement is that edits don't vanish on restart. A snapshot file delivers that with about ten lines of host code.
- `dao.space.store` is content-addressed persistence with a lock and head file. A 249-item catalog gains nothing from it, and it would make the demo container own a lock directory.
- The cost is an O(n) rewrite of a 76K file per save. That is trivial here.

**Concurrency:** the atom serializes writes. A save is `swap!` with a function that calls `prepare-tx` on the current value, so two concurrent editors cannot lose each other's updates.

**Where the file lives:** the image bakes the seed `catalog.edn`. The save target must be a path outside the image's read-only layer, a mounted volume, set by an environment variable (`CATALOG_PATH`). On first boot with an empty volume the demo copies the seed there. State this in the Dockerfile notes. If the path is unwritable, the demo logs it and keeps editing in memory. It does not crash.

**Rejected:**
- (b) A file-backed `dao.space.store`: locking machinery for no gain.
- (c) Periodic snapshots: an edit made just before a crash is lost, and a timer adds a clock thread. Snapshot on save is simpler.
- Persisting raw datoms: it loses the human-readable seed and the re-extraction path.

## 2. The editor session

**Ruling:** a second, complete composition, not a new router kind.

- The demo defines one `assemble` function that wires boundary, exchange judge, lineage judge, router, driver, media and ticks. It calls it twice: once for the shop and once for the grid, each with its own program, `project-fn`, `begin-fn` and media.
- The shop composition is as scoped earlier.
- The grid composition has its own boundary instance. Its router and driver are unchanged `dao.star` code, so its own `/`, `/s/<t>` and `/k/<t>` are the grid's begin, subscribe and input.
- One tick loop drives both compositions.

**How `/grid` fits:** the host edge, which already owns dispatch, strips the prefix. A request for `/grid` or `/grid/...` is passed to the grid boundary with `:uri` rewritten (`/grid` becomes `/`, `/grid/s/x` becomes `/s/x`), and everything else goes to the shop boundary. The grid's `begin-fn` and `project-fn` emit URLs with the `/grid` prefix, so the browser's actions come back through the same edge. Both faces are one site on one port. Static paths are handled before either boundary, as ruled earlier.

**Edge handler.** The demo edge is its own http-kit handler. Because `jvm/handler` is private, make the smallest change: change `(defn- handler` to `(defn handler` in `src/clj/dao/stream/http/jvm.clj`, with a one-line docstring. The demo edge calls `(jvm/handler boundary)` for each boundary and dispatches to the right one. No logic changes. This is the only edit to a merged file. If the owner prefers zero touches to merged code, the alternative is a copy of the 25-line handler in the demo, which duplicates `read-body` and `socket`. I recommend the one-word change.

**Rejected:**
- A new `/grid` kind in `router/classify`. It would change a signed-off library for one demo, and the router has one `:begin` medium and the driver one program, so a kind would still need a second driver.
- Begin-with-a-mode. One program would have to be both apps, and the program-only-reads-inputs boundary would blur.
- A second port. The owner wants one site.

**The editor program.** It is a yin.vm continuation under `dao.await`, exactly like the cart program: it reads inputs from its input medium and emits a model to its view medium. Its state in the continuation is only UI state: the selected item key, the edit draft (field values typed so far), and a filter string. The catalog itself is not in the continuation.

Inputs: select item, set field (name, price, description), add image URL or remove image, add tagory or remove tagory, save, cancel. `signals.cljc` decodes the typed fields from the Datastar signals.

**Where the transact happens: the driver, as you proposed.** The program must not do host I/O. When the user hits save, the program emits a model that includes the pending edit `{:edit {:item key :changes {...}}}`. The `project-fn` is pure, so it cannot do the transact either. The side effect needs a defined home, because the driver is library code that I'm not changing.

**Ruling:** an `:apply-fn` the composition supplies. I have not verified `dao.star.driver` has such a hook. `make-driver` as read takes `:program :project-fn :begin-fn` and no effect hook. So there are two options:

- **(i) Intercept in the host before the driver (my recommendation).** Add one thin interpreter, `grid-effects`, in the demo: a stepped function reading the grid program's *view medium* with its own cursor, which is a peer observer of the same medium the driver reads. When it sees a model carrying `:edit`, it runs the transact and bumps a `:version` in a small `edits` atom. The `project-fn` reads the db fresh and so shows the saved result. This preserves the division: the program emits data, one host interpreter performs the I/O, and the driver is untouched.
- (ii) Add an effect hook to `dao.star.driver`. This changes the merged library and needs its own review.

Take (i). One consequence to pin: the transact must complete before the projection that reports "saved". That ordering is not given by the driver. To get it, the program does not claim success. It emits the edit and the projection renders "saving". The effects interpreter, after the transact, appends a confirmation input (`{:saved item-key}`) back to the session's input medium, and the program's next model shows "saved". This costs one extra round trip and keeps fact-before-UI intact. Validation errors (a negative price, an empty name) take the same path: the interpreter appends `{:rejected reason}` instead.

**Validation belongs in the effects interpreter, not in the program or the browser.** Signals are the client's unverified claim, so price must be an integer in range, name non-empty and bounded, and image URLs must be restricted to `/images/` paths already on disk or http(s) URLs per the owner's choice. State the bounds in the brief.

## 3. Cross-face coherence

**Ruling:** accept it honestly and say so; no broadcast.

- A storefront subscription renders the catalog as of its last projection. A grid save is visible to a shopper on their next navigation or reload, because each navigation runs a fresh `q`.
- A one-line note in the shop footer says "catalog as of last page load", and the demo README states it.
- The editor itself shows its own save immediately, since its own flow includes the confirmation round trip.

**Why:** a broadcast would need the save to cause every live lineage's driver to re-project. The driver re-projects on session events, not on catalog changes, and Mode 1 deliberately has no shared-state wakeups. Faking it with a ticker that injects synthetic inputs into every session would make `dao.star` look like a pub-sub system, which it is not. For a demo, the stale-until-navigation behavior is also a correct illustration of the design: the UI is a derivation, and derivations re-run when asked.

**If the owner later wants live update,** the honest route is that the shop's `project-fn` closes over a `version` counter, and a shop tick-driven interpreter appends a "refresh" input to sessions whose last projection used an older version. That is a follow-up, not part of this demo.

## 4. File ownership, tests, auth

**Ownership deltas** (new files, in addition to the earlier list):
- `src/clj/dao/star_cart/store.clj`: the mutable db atom, `transact-edit!`, snapshot-on-save with atomic rename, boot loading.
- `src/clj/dao/star_cart/edge.clj`: the http-kit dispatch (static, `/grid`, shop).
- `src/clj/dao/star_cart/assemble.clj`: the `assemble` function called twice.
- `src/clj/dao/star_cart/grid_effects.clj`: the effects interpreter.
- `src/cljc/dao/star_cart/grid_program.cljc` and `grid_view.cljc`: the editor program and its pure view.
- One edit to `src/clj/dao/stream/http/jvm.clj`: `defn-` becomes `defn` on `handler`. Architect-approved, by this document.

The UI is the implementer's own minimal dark theme (one small stylesheet in `public/css/`). Reproducing lambdacart's markup or css is out of scope, as the owner said. The earlier instruction to read `grid.cljs` and port css is withdrawn.

**Images stay out of git. Confirmed.** `git status` shows the 228 images untracked with no ignore rule covering them. Add a `.gitignore` line, or a `.git/info/exclude` entry, for `resources/dao_star_cart/public/images/`, so no one stages 68 MB by accident. The Dockerfile copies them from the build context, so the build context on shiva needs them. The orchestrator copies the directory there out of band. `catalog.edn` itself is checked in. It is the saved state, so tell the user that edits in a running container do not touch the repo copy.

**Auth: none for the demo.** The owner said users can edit. I rule no authentication, and the brief must say so plainly:
- The grid is open to anyone who can reach port 3004.
- Mitigations that cost nothing: bind the container to the shiva interface the owner already uses, keep it off any public route, and validate every field as in ruling 2.
- The token gate is not authorization. It identifies a session. ShiBi is the later answer, per the earlier rulings.
- The README states "no authentication; do not expose."

**Test plan deltas (JVM):**

| Acceptance | Test | Medium |
|---|---|---|
| Grid renders items | GET `/grid`, subscribe, and assert N item rows with names and prices from the db | loopback |
| Edit save transacts and the storefront's next query sees it | Edit item A's price through the grid, wait for the confirmation model, then request the shop catalog and assert the new price | loopback |
| Validation rejects bad input | Negative price, empty name, oversized description, and an image path outside the allowed set each yield `:rejected` and an unchanged db | in-memory |
| Tagory add and remove | Add a tagory to an item and assert a `q` on the tagory finds it, then remove it and assert it is gone. Include a multi-tagory item. | in-memory |
| Image add and remove | Same pattern on `:item/images` | in-memory |
| Persistence | Save an edit, build a fresh store from the written file, and assert the edit is present. Also assert the written file parses as the same item maps. | pure, temp dir |
| Atomic write | A save that fails mid-write (inject a failing writer) leaves the old file intact | pure |
| Concurrent saves | Two interleaved saves to different items both survive | in-memory |
| Path rewrite | `/grid/s/x` reaches the grid boundary as `/s/x`, and `/grid` does not reach the shop boundary | edge unit |
| Static traversal still refused | As before, now also under `/grid/images/..` | loopback |
| Stale shop view | After a grid save, an open shop subscription still shows the old price until navigation, then shows the new one. This pins the ruled behavior. | in-memory |

**Risks added:**
1. **Effects interpreter ordering.** The "saved" model must follow the transact. The brief pins the confirmation round trip and tests it.
2. **Two compositions on one tick loop.** A slow grid save must not starve the shop's judges. Keep the transact synchronous and small, which it is at this size, and run both compositions' steps in one loop.
3. **Duplicate wiring drift.** `assemble` must be one function with the program, projection and prefix as parameters. Copy-pasting the composition invites the two to diverge.
4. **Rewritten paths and URLs.** If the grid's projection forgets the `/grid` prefix on an action, the request lands on the shop boundary. A test that scans the rendered grid HTML for `@get`/`@post` URLs and asserts each starts with `/grid/` catches it.
5. **Catalog file write on a read-only mount.** Handled by logging and continuing in memory, with a test.

**To verify before the brief is final:** whether `prepare-tx` supports retracting and replacing a cardinality-many ref (tagory remove) the way the editor needs, which I did not check; how `dao.await` programs receive a late-appended input (the confirmation) while parked, which follows the Phase B pattern but I did not re-read it; and that the effects interpreter's extra cursor on the view medium is allowed by the driver's single-reader assumption. If the view medium is a ring buffer the driver also reads, a second reader with its own cursor is safe under the stream contract. If not, give the effects interpreter its own tee.
