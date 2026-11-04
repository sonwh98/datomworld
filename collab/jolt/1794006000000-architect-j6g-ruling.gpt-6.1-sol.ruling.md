Completed-GMT: filed by the orchestrator at the round's close
Coding-Agent: codex gpt-6.1-sol (architect-review, read-only; session eb3d92db)
Session-ID: eb3d92db-346b-43f6-a29d-77603374c3ca

**J6g ruling — Session-ID: eb3d92db-346b-43f6-a29d-77603374c3ca**

**Disposition:** J6g remains **FAILED**: 79 samples is below 600, and p99 ≈7.9 s exceeds 250 ms. Delivered-once and REPL advancement satisfy only their respective legs. The criterion stands. No code changes or remote runs were performed in this ruling round.

**1. Scheduling principle**

**A1’s transfer-count bounds do not bound an owner round’s synchronous interpretation or the elapsed interval between peer visits.** One read, one write, and correct descriptor rotation constrain operations per visit. They do not constrain compilation, expansion, indexing, publication, VM execution, message assembly, or callbacks executed between visits or within one visit.

The owner-thread contract shall state:

> A composition claiming bounded service intervals must bound the synchronous work along every path between service opportunities. Work that can exceed that budget must return an explicit successor for later owner advancement. Transfer-count or instruction-count bounds alone establish no wall-time guarantee.

Checking a deadline after an unbounded call returns does not enforce that deadline. Likewise, VM fuel cannot bound an unbounded host operation inside one instruction.

The source establishes a concrete violation of any history-independent interpretation bound: [yin.repl.index](/Users/sto/workspace/datomworld-orch/src/cljc/yin/repl/index.cljc:21) explicitly documents full-history publication and repeated watermark scanning, with quadratic total session cost. A finite five-iteration guest loop does not bound that surrounding work.

Descriptor rotation is not ruled defective. The filed diagnosis establishes the growing source-level cost; it explicitly does **not** identify the historical hot instruction from remote stack evidence. The trace must supply that attribution.

**2. Selected architecture and fix order**

**Select (a), incremental index materialization, as the primary remedy for the confirmed history-growth mechanism. Select bounded advancement as the scheduling requirement wherever residual synchronous work exceeds the service budget. Do not select (c) without a measured assembly hotspot.**

The implementation order is evidence-driven:

1. **Run the attribution trace before performance edits.** If code indexing dominates, proceed with (a). If another phase dominates, investigate that phase before treating index optimization as the sufficient J6g remedy.
2. **Implement (a) when indexing attribution confirms it.** Then trace again to identify residual monopolization.
3. **Apply (b) to the residual execution or publication work that requires yielding.** A VM-only change cannot repair synchronous publication before the VM runs.
4. **Consider (c) only when pump timing and stack evidence identify payload processing near A’s final bytes.** That evidence may justify prioritizing this branch; it is not a mandatory third optimization.

For **(a)**, carry the publication cursor, covered roots, and allocator successor as explicit composition-owned derived state. Consume the committed suffix rather than reopening and reconstructing the entire history each evaluation. Retaining an allocator successor must preserve transactor authority and derive/validate it from authoritative history on recovery; the shell must not invent transaction times.

This ruling supersedes acceptance of repeated full-history rebuilding as the necessary price of every-evaluation publication. It **preserves that publication cadence**. Every successful publication must cover the committed frontier, materialize its required rows and index content, validate the manifest, and complete the applicable HEAD procedure before reporting publication success. Preserve atomic transactions, provenance, recovery, retry, and failure reporting. No history truncation, hidden global cache, canonical tuple field, or periodic-publication substitution is selected.

Incrementality removes repeated reconstruction; it does **not** by itself prove a fixed service interval. Tree operations, validation, encoding, hashing, and storage work still require measurement and, where necessary, bounded advancement.

For **(b)**, retain pending execution or publication as an explicit successor advanced by the same owner. No worker thread, callback scheduler, or nested socket polling is selected.

Query visibility is ruled as follows:

- While committed code remains unpublished, ordinary session queries return an explicit index-unavailable/pending refusal. They must not silently substitute the previous manifest.
- Do not expose partially built indexes or combine published datoms with partially advanced AST relations.
- Preserve publication-before-evaluation ordering for successful indexing. Publication failure continues through the existing explicit failure policy; yielding must not turn pending work into success.
- Keep the previous verified manifest available for recovery and explicitly addressed snapshot use, without presenting it as the current session frontier.

This follows the existing [query snapshot contract](/Users/sto/workspace/datomworld-orch/src/cljc/yin/repl/query.cljc:405).

A yielded VM quantum is not evidence of a completed W evaluation. Preserve the registered submission and progress checks. If resumability requires changing what “every owner round” means in the harness, that requires a separate explicit workload ruling; it cannot silently certify this gate.

For **(c)**, a slow `:server-pump` phase alone is insufficient: it includes waiting, parsing, assembly, and callbacks. Require stack evidence in `take-payload`, `finish-frame`, `concat-chunks`, or their byte operations. A subsequent scoped change must preserve owned payloads, pure parser successors, message/control bounds, ordering, close behavior, and exactly-once complete-message delivery. Resumable assembly must withhold delivery until complete and expose runnable work through the existing deadline seam.

**No adapter/parser change is selected on the present evidence. A1–A7 remain binding.**

**3. Traced-run protocol and acceptance**

On the qualified remote checkout, record the commit, working diff, host, Jolt/native qualification, command, and configuration. Preserve stdout, stderr, and exit status.

First verify instrumentation presence. This local checkout has the older first-round `W-PHASE` markers, but lacks the filed `JOLT_WORKLOAD_W_TRACE` implementation and nested phase/age reporting. An environment variable alone cannot establish that the remote checkout contains it.

Run:

```sh
JOLT_WORKLOAD_W_TRACE=1 bb workload-w:jolt workload-w
```

The trace must provide:

- Flushed enter/leave records with round and monotonic timestamps for REPL work, A send, B send, server wait/pump, and client wait/pump; nested code-index and AST-index records.
- Phase counts, total/max durations, retained transaction growth, owner rounds, verified REPL results, B sent/completed/outstanding counts, and A sent/delivered counts.
- A’s first positive write and first delivery timestamps, with B samples restricted to requests sent and answered inside that interval.
- Independent supervisor reporting of active nested phase, round, and age every ten seconds and at the unchanged 300,000 ms whole-run cap.

Parent timings include child timings; do not add them as independent costs. Obtain CPU stack samples during sustained slow phases to distinguish scanning/building from hashing, GC, VM work, assembly, callbacks, or native polling. Missing B submissions indicate owner/producer cadence failure, not automatically lost network replies.

**The traced run is diagnostic and cannot PASS J6g.** Its intentional nonzero status must be distinguished from watchdog or worker failure.

After the selected fix lands, repeat attribution and then run the unchanged, untraced gate:

```sh
bb workload-w:jolt workload-w
```

J6g passes only when all registered conditions hold together:

- A’s 4 MiB message is delivered exactly once.
- At least **600** qualifying overlapping B round trips complete, with **p99 ≤250 ms** and no lost reply.
- The required REPL evaluation advances every owner round, verified through actual shell/result progress.
- The registered transfer pacing, duration, offered load, and outstanding-request limit are preserved.

Post-transfer replies cannot fill the sample target. The 500-sample validity floor is not a pass threshold. A watchdog timeout, incomplete evidence, or any unmet leg leaves J6g unpassed. The five-minute cap is a safety limit, not a replacement transfer window.