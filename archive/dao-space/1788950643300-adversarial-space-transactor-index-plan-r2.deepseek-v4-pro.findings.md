I re-read the sources against r5 and checked each fix, D10's completeness specifically, and hunted for new breakage. Verdict and evidence below.

## Verdict: ready to brief an implementer — with one trivial nit (D10's supporting count, not its rule).

### 1. Each fix closes its finding — none moves the problem

- **F1 (D10 re-wrap).** Closed. `schema/transact!` re-wraps ok → `{:result :ok :t t :datoms ds}` inside the branch T19 already forces, so every `(:result)`/`(:t)`/`(:datoms)` assertion stays green. I traced the re-wrap against `schema.cljc:1112-1114`: the inner receipt is `{:dao.stream/outcome :dao.stream/ok :dao.space/t t :dao.space/datoms ds}`, so the mapping `:result :ok`, `:t (:dao.space/t …)`, `:datoms (:dao.space/datoms …)` reproduces the v1 shape exactly (map equality is order-independent). The "non-ok returns are *new*, not changed" note is correct — v1 threw, `schema.cljc:1069` still throws on the wrapper's own closed check, and no src or test caller inspects a non-ok return.
- **F2 (`stigmergy_test`).** Closed. I verified the "6 → 3 → 0" count against the file: the six `ds/` call sites are exactly `:103, :106, :118, :176, :177, :178` (the `[dao.stream :as ds]` require at `:31` contains no `ds/`, correctly outside the grep). Phase 2 clears three, Phase 3 clears three, and the `ds` require is deleted in Phase 3 (§5.2) only after the last `ds/` site is gone — so each phase compiles and runs. The `sources` rewrite (`query/open-published!` → `query/rows` → `query/close-published!`) is functionally equivalent: `query/rows` on a published value forces `index/read-datoms`' EAVT vector, which is the same input `ds/strict-vec` produced from `PublishedIndexStream`'s `(delay (vec (bt/seq (:eavt indexes))))` (the two agree, pinned by `lazy-eager-parity-per-order`).
- **F3 (§8 restated).** Closed, and the reasoning is now honest rather than overclaimed. Both routes are named, and the two declines are both sound: a type check would couple `dao.space` to `memory-log` by name and reject future correct complete-retention transports; the kept-origin-cursor is for consumers *on* evicting transports, and the transactor neither creates the local stream nor could mint at origin. Both are true of the tree, not restatements of my finding.
- **F4 (S10 + conforming doubles).** Closed. A `{:dao.stream/outcome :dao.stream/gap :dao.stream/cursor {…}}` and a `{:dao.stream/outcome :dao.stream/cursor-mismatch}` are both *conforming* (`outcome-required-keys` demands `:dao.stream/cursor` only for `:next` gap, nothing for `cursor-mismatch`), so `checked` passes them and they hit the totality `case`. The double answers a conforming `ok` cursor from `cursor` first (per §4.4) and the configured outcome from every `next` — sound.

### 2. D10 — the enumeration is actually complete; the *supporting* count is not exact

I enumerated every public def in `schema.cljc` and every site that touches `tx/` or `index/`:

- **Forward a callee value (3):** `transact!` (→ re-wrap), `SchemaWrapper.close!` (→ `{:woke []}`), `publish!` (→ `{:manifest-address … :manifest …}`, checked-unchanged).
- **Touch the transactor but don't forward (2):** `SchemaWrapper.closed?` (reads own flag), `transactor` (returns a `SchemaWrapper`).
- **Touch neither `tx/` nor `index/`:** `current`, `published`, `extract-schema`, `resolve-props`, `bootstrap`, plus the data defs `axioms`/`type-pred`. `published` builds its own map and does **not** call `index/published-index` (its docstring says "validates like", and it doesn't). `current` returns a query value, not a callee value. `PublishedSchemaRows.close!` → `{:woke []}` is correctly covered "per D10".

That is every public surface. There is no fifth forwarding instance, and no sixth.

The one crack: **"20 assertion sites" is an undercount — it is 22.** The plan's list omits `schema_test.cljc:1657` and `:1789`, both `(is (= :ok (:result (schema/transact! …))))`. (The plan's own note says "the review counted 23; line-vs-assertion counting" — but this is not a line-vs-assertion artifact, it is two whole `(:result …)` sites missing.) It is **immaterial**, because the fix is re-wrap-not-migrate: keeping every site green does not depend on enumerating them, so the two omitted sites stay green regardless. I flag it only because D10's whole point is that the enumeration is closed, and "closed" is being claimed on a list that is literally two short.

### 3. Nothing the fixes newly broke

- Re-wrap is three lines in the T19 branch; the mixed ok(v1)/non-ok(v2) return is unreachable-vs-untested on the non-ok side and safe on the ok side.
- The `sources` migration opens/forces/closes the store in the same lifecycle order as today (store closed once via `:close-guard`).
- The durability relocation (D4) is docs-only; restart-resets-`t` is pre-existing (v1 ring buffers were in-memory), so no regression and no new invariant tension.
- Phase 2 leaves `sources`' three `ds/` sites valid because the `:dao.space.index/published` defopen survives until Phase 3 — each phase stays green.

### 4. Ready?

Yes. The four findings are closed without moving any problem; D10's rule is complete; no new breakage. The only thing I'd correct before briefing is the assertion-site figure — say **22** (add `:1657`, `:1789`), or drop the count and say "every `:result`/`:t`/`:datoms` assertion on a `schema/transact!` return stays green under re-wrap, by construction." One trivial, optional cleanup: `[dao.stream.ringbuffer]` in `stigmergy_test.clj:32` becomes an unused require after Phase 2 and can go with the `:103` migration; the `ds/` grep won't catch it, but it's lint-only.

Otherwise: brief the implementer.
