Created-GMT: 2026-09-09 10:38:02 GMT
Created-Local: 2026-09-09 17:38:02 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 6378e1b0-1c5d-4e10-8ea0-61551c03c828
# Task: fold the adversarial findings and the durability relocation (r5)
Role: Lead System Architect

An **adversarial review by `deepseek-v4-pro`** — a third family, independent
of you and of the reviewer who has done every round so far — found four
issues, two blocking. Full findings:
`collab/1788949568142-adversarial-space-transactor-index-plan.deepseek-v4-pro.findings.md`

It confirmed sound: D6's validate-before-interpret loop, W38-W41 through
`PublishedSchemaRows`, the seven-vs-eight accounting, and Phase 2's compile
closure, with no interleaving bug beyond the documented T6.

Emit the whole revised plan to stdout.

## F1 (blocks) — you generalized the preservation rule from one instance

D3 changes the transactor receipt to
`{:dao.stream/outcome … :dao.space/t … :dao.space/datoms …}`, and
`schema/transact!` returns it **verbatim** (`schema.cljc:1112` — I verified).
You applied "do not silently change a v1 public result in schema" to
`close!` (D8, `{:woke []}`) but **not** to `transact!`, which is far more
asserted: I count **23** assertions in `schema_test` reaching into
`:result`, `:t` or `:datoms`. §4.6 #3 even specifies the non-`ok` return
without noticing the `ok` return changes shape. Three lanes are not green the
moment Phase 2 lands.

This is the fourth time in this chain that a rule has been generalized from a
single instance. Fix it the same way you fixed `close!`: re-wrap the receipt
schema-side to `{:result :ok :t t :datoms ds}`, or migrate the assertions —
choose, and say why. Then **state the rule generally** in the plan, so the
next reader applies it to every v1 public result schema still returns, not to
the two you happened to notice.

## F2 (blocks) — an eleventh published-adapter read path, unaccounted

`stigmergy_test.clj:176-178`'s `sources` helper does `(ds/open! source)` on
`index/published-index` coordinates from `published-source-pool`, then
`ds/strict-vec`. I verified it. **Every scenario in that file calls
`sources`**, and Phase 3 deletes the `defopen` it depends on. Your §10 cites
`stigmergy_test` as end-to-end proof but gives it no edit set and no residue
grep, and §4.4's single mention is wrong: `:106` is the **transactor** open,
not the agent local.

Give `stigmergy_test.clj` a full edit set with a residue grep like the other
files: `:103` local ringbuffer, `:106` → `transactor/create!`, `:118`
`ds/->seq`, and `:176-178` `sources`. Say which phase each moves in.

## F3 (fix) — you overclaim undetectability

§8/T18 says no runtime check will catch a wrongly wired transport. That is
false twice: `stream/descriptor` exposes `:dao.stream/type`, and `dao.space`
already does this class of check on `:dao.jing/type`; and the contract's
*Complete history* names the kept origin cursor as a detection mechanism.

Restate it as **detectable but deliberately not checked**, with the reason: a
type check couples `dao.space` to `memory-log` and would reject a future
correct transport, which is what "declared, never interrogated" exists to
prevent. That is a stronger argument than impossibility, and it is true.

## F4 (fix) — the totality throw has no test

§7 deletes the gap test, and §4.4's `MalformedResultStream` sub-cases cover
only *malformed* results. D6's throw for a **well-formed** unexpected outcome
— `gap`, `cursor-mismatch` — is now pinned by nothing. The exclusion
principle forbids an overflowing-ring-buffer *transport* fixture; it does not
forbid a stateful *double* returning a conforming `gap`. Add that sub-case.

## The owner's correction — durability is relocated, not softened

D4 now says "authoritative retained truth for the logical stream's lifetime",
which is better but still leaves a reader asking where durability lives. The
owner's point: an in-memory transport is entirely legitimate, and **durability
is `dao.jing`'s job, arriving at publication**. `dao.space.md:390` says "The
local stream is the durable record", which contradicts its own paragraph —
stage 1 explicitly touches no storage handle, stage 2 materializes into
content storage — and mirrors Datomic's memory-index, which is not durable
either.

So the fix names the right component: the local stream is authoritative for
its process lifetime; **the durable record is what publication puts in
`dao.jing`**, and un-published writes are not durable. That was equally true
of v1's ring buffers; the design simply claimed otherwise. Say so wherever the
claim appears, and add that a durable *stream* transport is not the answer —
it would duplicate the content store, which `dao.space.query.md`'s Decisions
already ruled out as `dao.stream`/`dao.jing` unification.
