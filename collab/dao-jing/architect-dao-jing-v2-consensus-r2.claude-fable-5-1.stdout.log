Completed-GMT: 2026-09-06 16:50:12 GMT
Completed-Local: 2026-09-06 23:50:12 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f

# Consensus round 2 — author seat

Converged in round 1, one line each:

- **C5** — agreed. One ordering detail both positions are consistent with, stated so an implementer has it: `step` is (1) if `:unsent` and not terminal, re-attempt through `rpc/request!`; (2) `rpc/poll!`; (3) if the returned state is terminal and still holds `:unsent`, `rpc/abandon-unsent` with the terminal reason; (4) take completions and diagnostics exactly once. `abandon state reason` is exposed for the composition's own disconnect-before-rebind, as `driver.cljc:220,258,293,305` does.
- **C6** — agreed. Any completion carrying a reason decodes to `{:lost reason}` with the qualified keyword unchanged; `allocator-error` is in the request outcome set and mints no id; examples are examples, not a closed vocabulary; astra's addition that reader `:dao.stream/end` and lifecycle `/ended` stay distinct in the passed-through reason is right and costs nothing.

---

**C1 | FINAL: Decision 2 (option b) stands; option (a) is not a like-for-like alternative and cannot be costed as one | MAINTAINED**

Astra's round-1 answer to the handle-map argument was "explicit backend functions and an underlying stream are compatible; the former does not exclude the latter." That is true and does not answer N-b. The question N-b poses is what a stream *beneath* a synchronous `:put-content-fn` changes about the emission. The answer is: nothing. Under v1 the log's `append!` is invoked inside `:put-content-fn` (`file.cljc:149`), portable code never receives a handle to it, no cursor is ever handed out, and `materialize!` returns the address synchronously (`jing.cljc:276-280`). The effect "never appears as an emission" (`datom.world.md:66-68`) in exactly the same way with the log stream present as with it absent. If Host Boundaries condemns anything here, it condemns the content-handle map as it exists today on v1, and a v2 append-log beneath it changes no clause of that judgement.

N-a then closes the other exit. A conforming v2 append-log cannot acknowledge durability synchronously; durability completion arrives later as data. But `dao.jing.file` acknowledges `:inserted` only after flush (`dao.jing.md:299-301`) and every production consumer — `dao.data.btree.storage` and `dao.space.index` — calls `jing/materialize!` as a value-returning operation. So option (a) does not "replace five calls with a transport"; it requires the content put to become an effect whose durability is observed later, which means redesigning `materialize!`'s acknowledgement and every consumer's write path. Astra conceded this in its own words ("a design prerequisite, not a mechanical five-call replacement"). That is not a cheaper or more conforming version of option (b). It is a different plan — the write-path redesign — mis-costed as conformance-suite work.

And that redesign, if and when it is undertaken, *lands where option (b) already puts the file IO.* `datom.world.md:57-59`: "A host interpreter consumes that stream, performs the operation, and appends the outcome." In the conforming future design, `dao.jing.file` is the host interpreter of `:jing/put` effects, and raw file calls inside a host interpreter are the sanctioned place for raw file calls. A v2 append-log there would be an interpreter reading a stream in order to append to another stream in order to write a file — a layer the conforming design does not need. Option (b) is therefore compatible with the future and not merely an interim.

What I concede to astra and Sol, and what the plan must say: (i) `dao.jing.md:297-303` currently commits to the log stream, so J4 is amending a design document, and Decision 2 must say so and route through the orchestrator as a design change, not slip it into an "Implemented surface" edit; (ii) the pre-existing question — whether DaoJing's synchronous content handle conforms to Host Boundaries at all — is real, is not created by this plan, is not curable by any transport, and goes into `dao.jing.md`'s open items by name as "the content write path as an effect stream", with N-a's consequence stated: durability arrives as data, so `materialize!`'s return value changes.

**Where the disagreement turns, stated cleanly for a ruling:** Is the write-path redesign — content put as an effect on a stream, durability observed later as data, `materialize!` no longer returning an address synchronously — in scope for this migration? If **no**, option (b) is the only honest choice: it worsens no coupling, uses no v1, and is where the future design's file IO lives. If **yes**, then neither option (a) nor (b) is the deliverable; the plan is blocked on a superior-document amendment and a redesign of every `materialize!` consumer, and should say "blocked" rather than pretend a transport resolves it. I hold that the answer is no, because the brief's boundary is `dao.jing*` and the redesign reaches `dao.data.btree.storage` and `dao.space.index`. Option (a) as written is the one answer I say is wrong under either ruling.

---

**C2 | FINAL: move the v1 observer out into `dao.jing.observer` in J1; seven one-line test repoints; fallback is astra's documentation route only if the orchestrator closes scope on the five `dao.space` test files | MAINTAINED, count REVISED per N-c**

Astra's remedy — distinguish direct from transitive throughout, remove at J5 — makes the tables accurate and leaves `dao.jing.v2*` unable to load without v1 for the whole coexistence. Astra's falsifier was "a documented requirement that J2 load or deploy without legacy namespaces". That requirement exists as the project's demonstrated standard, not as a sentence: the REPL plan built an entire second VM so that "`yin.repl` requiring no v1 namespace" would be "true rather than aspirational" (`yin.repl.implementation-plan.md:723-725`), and the runtime plan's end condition is "no namespace under `dao.*.v2` … requires `dao.runtime`" (`dao.runtime.implementation-plan.md:412`), enforced by construction. A v2 namespace that requires v1 transitively fails that standard by a footnote. Astra's second point — that the v2 observer "does not thereby operate on v1 readers" — is true and irrelevant to loading.

N-c corrects the cost: the v1 observer has no `src/` caller; the repoint touches seven test files, of which two (`mem_test.cljc:213`, `dht_test.cljc:402`) are `dao.jing`'s own and in scope, and five are `dao.space` tests. One require line and one alias each. Against that: nine `src/` requirers of `dao.jing` become v1-free at J1 rather than J5, the transitive claim becomes true and gate-able, and J5 becomes "delete `dao.jing.observer`" instead of surgery inside `dao.jing`.

**Clean question:** may J1 repoint five `test/dao/space/` requires? If yes, move-out. If no, astra's route is the only one available, and then the end condition must state plainly that `dao.jing.v2*` is transitively v1 until J5 — not as a footnote but as the bullet.

---

**C3 | FINAL: the stepped materializer is in this plan as its own phase, J3c, over the raw adapter of J3a; write-side readiness is claimed only when J3c passes | REVISED**

Astra is right that backend put and materializer are distinct layers — the local backend interface receives an address and returns a verdict (`jing.cljc:277`), and `default-handlers` already validates address-against-payload and calls raw put (`remote.cljc:57-63`). My round-1 fix folded the materializer into the adapter, which was the same layering error in the other direction. So: J3a keeps `request-put address payload` and `request-get address` as the backend primitives; J3c adds `request-materialize state payload` built over them — derive locally, put, on `:present` a second correlated get, complete with the address only after equality, else `:error` with `/integrity-failure` or `/present-but-absent` as data.

Where I do not move: astra's "defer to a named follow-on phase" reads as outside this plan's end condition. It cannot be. `dao.jing.md:39-46` defines DaoJing as knowing how to "insert idempotently"; a remote client that can put but cannot materialize is a raw RPC adapter to a KV, not a DaoJing backend, and the plan would then deliver less than its title claims. The materializer is about forty lines of per-id `{:payload :address :phase}` state over the J3a client and has no dependency J3a does not already have. Astra's own C3 plan-change specifies it precisely and `store-tree-async` is named as depending on it. Naming it a phase and putting it in the end condition is the only difference between our positions, and I hold it.

**Clean question, if the orchestrator wants one:** does this plan's end condition include stepped remote materialization? I say yes; the read-hydration claim needs only J3a, and the plan says so.

---

**C4 | FINAL: three namespaces — `dao.jing` (stream-free core), `dao.jing.observer`, `dao.jing.remote` — decided now; J5 executes it | MAINTAINED, and explicitly contingent on C2**

Astra's fold-back returns the observer into `dao.jing` and thereby makes `dao.jing` require `dao.stream`, so every one of the nine `src/` requirers of the address core — `dao.data.btree.storage`, `dao.space.schema`, the DHT — pulls in the stream namespace to mint a content address. C2 showed that coupling is what made the dependency table false; restoring it deliberately at J5 is choosing the shape that caused the defect. Three namespaces keep the address core stream-free permanently, which is the property both seats just agreed is worth having, and the observer's namespace is named after what it does to streams. The transient names are `dao.jing.v2.observer` and `dao.jing.v2.remote`; bare `dao.jing.v2` disappears from the plan.

The honest dependency: this answer follows from the C2 move-out. If the orchestrator refuses the move-out, there is no `dao.jing.observer` to rename into, and astra's fold-back is correct by default. C4 is therefore not a separate ruling; it is decided by the C2 ruling.

---

**N1 | FINAL: conditional, per N-d | REVISED on the fallback**

If a consumer plan **may** delete a v1 file: J1 deletes `src/cljc/dao/stream/log.cljc` and `test/dao/stream/log_test.cljc`, ports `torn-tail-test` into `file_test.cljc` with the adaptation the notes verified (one valid encoded record first, then the overlong-length tail; plus the sub-four-byte-tail variant; all three hosts), and *Boundary* records this as the one deletion outside `dao.jing*` with the reason: the orphaning is caused here, and per-transport retirement at the last consumer's migration is the stream plan's own rule at its natural granularity.

If it **may not**: astra's "legacy append-log retirement" as a named stream-owned dependency, **coordinated atomically with J1** — same change, two owners signing it — is correct, and I concede it over my round-1 fallback of "leave `log.cljc` standing". My objection to a hand-off was the dead window with an idle owner; astra's atomic coordination removes that window, and leaving the file standing does not. Under either branch the torn-tail evidence lands in `file_test.cljc`, because under Decision 2 the framing owner is `dao.jing.file`; astra's alternative destination applies only under the C1 route I hold to be wrong. Under either branch `dao.stream.file` (live-tail, `yin.io.file`) is untouched.

---

**Consensus exists** on C2's defect, C3's gap, C4's "decide now", C5, C6, and N1's retirement-with-replacement. **Consensus does not exist** on C1 (turns on whether the write-path redesign is in scope; option (a) is wrong under either answer), on the C2 remedy (turns on whether J1 may touch five `dao.space` test requires; C4 follows from that ruling), and on whether C3's materializer is inside this plan's end condition (I say yes, as J3c).
