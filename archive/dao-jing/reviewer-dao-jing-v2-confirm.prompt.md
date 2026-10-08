Created-GMT: 2026-09-06 17:04:47 GMT
Created-Local: 2026-09-07 00:04:47 +07 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0776c-dbcf-7343-a6de-ef18f705fec7
# Task: dao.jing.v2 migration plan — confirmation round
Role: Routine Reviewer (correctness, invariants, portability)
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-07 00:04:47 +07 | Status: active | Rationale: resumed original review session to confirm its own findings

## What this is

A confirmation round on your own review. The plan you reviewed has been
revised. Verify that each of **your** findings was actually addressed in
substance — not paraphrased, not partially applied, not answered by a sentence
that leaves the defect standing — and flag anything the revision introduced.

The revised plan is now a repository file:

  docs/design/dao.jing.v2.implementation-plan.md   (900 lines)

Your original findings are at the path you wrote them to, in `collab/`. Also
on disk, if useful: the other reviewer's findings, and a two-round consensus
between the plan's author (`claude-fable-5-1`) and an independent architect
(`gpt-6-astra`) — `collab/*consensus*`.

## What happened to the findings

Accepted and incorporated: the `busy`/`step` deadlock (now: re-attempt unsent
through `rpc/request!`, poll, abandon on terminal, publish — with `step` named
as the only path that clears `busy`); `:diagnostics` in the step result; the
swallowed unsent request at detach (now `rpc/abandon-unsent`, plus an exposed
`abandon`); completion decoding total by pass-through with `allocator-error`
in the request outcome set; torn-tail coverage ported with a fixture
adaptation; the transitive v1 dependency (the v1 observer moves out to
`dao.jing.observer` so `dao.jing` is stream-free from J1); the naming decision
taken now (three namespaces); and stepped remote materialization added as J3c
because `request-put` was the backend primitive, not `materialize!`.

Rejected, once: **the finding that Decision 2 violates Axiom 1.** Both
architects concluded independently that a v2 append-log beneath a synchronous
`:put-content-fn` changes nothing about the emission — `datom.world.md:66-68`
condemns that adapter shape today, on v1, and no transport cures it — and that
`dao.stream.md:153-158`'s no-wait rule means a conforming v2 append-log could
not acknowledge durability synchronously, so option (a) is a redesign of when
a content put is acknowledged, reaching `dao.data.btree.storage` and
`dao.space.index`, not a transport substitution. The write-path redesign is
recorded as a named open item instead. **If you think that reasoning is wrong,
say so plainly and say what it misses** — a rejected finding you still hold is
a legitimate output of this round, not a failure of it.

## Verified facts — settled, do not re-derive

`outcomes-next` is seven keywords; `request!` is the sole unsent retry path
and `poll!` only reads; `abandon-unsent` exists and `lose-outstanding` covers
`:outstanding` only; `allocator-error` exists; `materialize!` derives the
address and verifies read-back on `:present`; `dao.jing.cljc:19` requires v1
`dao.stream`; the v1 observer has no `src/` caller and seven test files call
it; `log_test.cljc:104` writes a non-decodable fixture; `:append-log` has one
non-test consumer; `dao.stream.file` is a different, genuinely stream-shaped
transport and is untouched. The suite is green on this revision (user-run);
you have no authority to run tests.

## Produce

For each of your own findings:

  <your finding, one line> | ADDRESSED / PARTIALLY ADDRESSED / NOT ADDRESSED /
  REJECTED-AND-I-ACCEPT / REJECTED-AND-I-DISSENT | <evidence: the plan section
  or line that settles it, or what is still missing>

Then, separately: **defects introduced by the revision.** It grew from 630 to
900 lines; new material carries new risk. Report as
`P0-P3 | section | evidence | concrete fix`, or "none found".

Do not edit any file. Do not re-review what you already passed as sound.
Produce the complete response in this run.

Your findings were: P1 Decision 2 vs Axiom 1 (the one rejected); P1 the
stream-free shared core does not exist / transitive dependency; P1 the
`busy` progress defect; P1 `request-put` is not `materialize!`; P2 `step`
drops diagnostics; P2 golden-record compatibility gap; P2 J5 naming unsettled.

Note that your transitive-dependency finding was upheld but your *remedy* was
not: rather than extracting a shared core, the revision moves the v1 observer
out, because it has no `src/` caller and the extraction would have perturbed
nine production namespaces to relocate already stream-free code. Judge whether
that achieves what your finding required.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0776c-dbcf-7343-a6de-ef18f705fec7
