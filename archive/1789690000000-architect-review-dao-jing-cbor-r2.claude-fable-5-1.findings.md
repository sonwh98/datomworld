Coding-Agent: claude
Model: claude-fable-5-1
Role: Lead System Architect

# Review r2: confirmation of r1 corrections to dao.jing.cbor.md

Completed-GMT: 2026-09-18 03:15:00 GMT
Completed-Local: 2026-09-18 10:15:00 +07 (Asia/Ho_Chi_Minh)

## Verdict: ready for owner sign-off, with two non-blocking wording notes

Both r1 corrections substantively resolve what they were meant to resolve. Neither relocates a contradiction into a new blocking defect. Two precision issues remain in the wording itself — worth a follow-up touch-up, but not worth withholding sign-off over.

## Check 1: Finding-1 correction (file-backend CBOR ownership)

**Resolves the contradiction on its face** — there's now an explicit owner (`dao.jing.file`) and an explicit reason (it's Jing's own shared codec applied to its own framing, not a divergent per-backend codec).

**But the category it introduces doesn't fully hold up against how the rest of the doc uses "backend":**

- The architecture diagram (`:38`) still lists `file` as a bare peer of `memory | PostgreSQL | S3 | remote | DHT` under one undifferentiated `backend:` line — no textual marker that `file` is categorically different from the others there.
- `## Backend changes` (`:293-296`) opens with "All existing backends move to the shared byte-store boundary," and its first subsection is literally titled `### Memory and files` — file is still presented as an ordinary backend being migrated, not as a special built-in case, until the parenthetical two sentences later.
- The correction's own dichotomy — "pluggable, third-party-implementable" (PostgreSQL, S3, **and the memory/remote/DHT byte-store implementations**) vs. "Jing's own built-in" (file) — misclassifies `dao.jing.mem`, `dao.jing.remote`, and `dao.jing.dht`. Those are exactly as much Jing's own in-repo code as `dao.jing.file` is (same `dao.jing.*` namespace family, same source tree); PostgreSQL and S3 are the only backends that are actually third-party/not-yet-built. Grouping memory/remote/DHT with PostgreSQL/S3 to justify singling out file is not an accurate use of "third-party."

The real distinguishing property is narrower and more defensible than "built-in vs. third-party": only `dao.jing.file`'s own on-disk framing happens to be shaped as a CBOR array (`[digest payload-bytes]`) that must be parsed to find the frame boundary. `dao.jing.mem` has no frame at all (a flat address→bytes map); `dao.jing.remote`/`dao.jing.dht` frame with Base64-inside-Transit, not a raw CBOR array. That's why file alone needs an exception — not because it's "Jing's own" while the others aren't.

**Recommendation:** reword the exception around *"the only backend whose own frame format is itself a CBOR structure"* rather than *"built-in vs. pluggable third-party,"* and touch the diagram or the `Backend changes` heading with a one-clause pointer back to the Objective's carve-out so a reader hitting `:38` or `:295` first doesn't have to reconstruct the exception from memory.

## Check 2: Finding-4 correction (dao.space boundary-widening sign-off)

**Accurately placed** — it sits right after the reader first learns numeric identity will be portably enforced (`:209-226`) and right before the mechanism-level specifics naming `compare-vals`, the EAVT/AEVT/AVET/VAET comparators, and the query builtins (`:235-256`). That ordering (claim, then detail) is the right shape.

**Architecturally accurate on the substance** — "an interpreter consuming a storage-adjacent utility, not the reverse" correctly states why this isn't a violation of DaoJing's storage-ignorance invariant, and "needs explicit sign-off... separate from Jing's own review" is the right procedural ask.

**But it overstates scope by repeating an existing inaccuracy rather than fixing it.** The new paragraph asserts: *"This is the plan's one change outside `dao.jing*`"* (`:227`). That's not literally true against the plan's own text: Implementation-sequence step 5 (`:428-431`) also changes `dao.data.btree.storage`'s verification to a byte-hash check, which is outside `dao.jing*` too. (This same "only change" phrasing already existed pre-correction at step 3, `:424-425` — I didn't catch it in r1 either — but the correction had an opportunity to tighten it here and instead duplicated it into a second location.)

The btree-adapter change is a much shallower touch (swap what a rehash-and-compare check hashes against) than the `dao.space` threading (new runtime dependency on `dao.jing.cbor`'s portable operators in every comparator/builtin call), so the underlying "widest blast radius" claim is directionally right — but "one change outside `dao.jing*`" as literally worded is an overstatement the doc itself contradicts a page later.

**Recommendation:** narrow the claim, e.g. *"the only place this plan threads new runtime behavior into an existing consumer's comparison/equality logic outside `dao.jing*`"* — distinguishing it from the mechanical byte-hash-check swap in `dao.data.btree.storage`, which doesn't carry the same sign-off weight.

## Disposition

No new contradictions introduced, no blocking findings. Both fixes are correct in substance. The two wording notes above are real but minor — recommend folding them into the doc in the same pass before or shortly after `dao.space` sign-off, not as a gate on it.
