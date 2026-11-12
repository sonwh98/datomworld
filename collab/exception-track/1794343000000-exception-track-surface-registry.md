Created-GMT: 2026-11-10 20:35:00 GMT
Created-Local: 2026-11-11 03:35:00 +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, continuation-semantics orchestrator seat)
Session-ID: not-applicable (interactive seat)
Status: ACTIVE — the ownership registry of record for the exception track

# Exception track — ceded-surfaces registry & coordination rules

Authority: owner ruling v2 (1794960000000), owner decision 2026-11-11 "(A) now, (B) if R4 proof
fails" (1794341000000). The ts/php dispatch hold is LIFTED. No track-level pause of any yang.*
seat: serialization is FILE-LEVEL only, on the surfaces below.

## Registry — surfaces owned exclusively by the exception track (until the round lands)

| Frontend | Ceded surface (files + regions) | Owning round | Status |
| --- | --- | --- | --- |
| typescript (js) | `typescript/antlr/prelude.cljc` entry bridge (`:1581` region) + protocol-condition paths (`:1627` region) + reception; kit test surfaces under `test/yang/call/` | JS R1+R3 (relay 1794970001000) | IN FLIGHT — branch `exception-js-r1r3` @ master 4cfa609d, worktree datomworld-jsr13, muse-spark-1.3-contributor session B1FBBBB5-52A0-42A2-BAFC-9742A463A51C (ts seat dispatch note 1794346200000) |
| php | `php/antlr/prelude.cljc` function entry (`:2264`), Closure entry (`:4217`), `php/call`/`call-with-binding-profile`/`call-named`, ForeignException reception, protocol rethrow (`:194-197`, `:2384`, `:4085` regions); kit test surfaces | PHP R1+R2+R3 (one coherent round) | STAGED — brief 1794342800000 filed; dispatch gated on JS R1+R3 landing (php seat ack 1794342949000) |
| python | `python/antlr/prelude.cljc` foreign reception (`:1034/:1045`), protocol loss (`:1046`), egress (`:912`); kit test surfaces | Python R2/R3+egress | IN FLIGHT — branch `exception-py-r2r3` @ master 4cfa609d, gpt-6.1-sol session 01a1c729-bca1-7fb2-be20-012a85e4b03a; EIGHT authorized flips enumerated in python ack 1794360000000 (the only python rows this round may change) |
| clojure | `clojure.cljc` escape/provenance region (`:1506-1629` bridges, `:dsg__condition` writers/readers, strippers) | Clojure R3 (fix round in flight) → R4 equivalence | IN FLIGHT — fix round deepseek-flash session d6e40695; astra re-confirm pending |

## Adjacent-surface acknowledgments (clojure seat reply 1794342913000 — accepted)

- **cc2** (branch `cc2-transform-session-c1`, worktree datomworld-cc2): touches
  `yang/safepoint.cljc`, `yin/vm/encoder.cljc`, `yin/vm/linker/publish.cljc`, `yin/repl.cljc` —
  NONE of these are on this registry; zero overlap with the clojure ceded surface. Acknowledged.
- **cc3 commitment of record**: the clojure seat commits to lowering-provenance work on the
  LINEARIZER side only (route (a): `semantic/linearize` + `derive`, no evaluator changes). Any
  evaluator-runtime provenance work (route (b): `ast_walker`/`semantic`/`stack`/`register`) is
  continuation-runtime surface and requires this seat's sign-off plus a coordination note BEFORE
  dispatch; impossibility on route (a) escalates to the owner with both seats' evidence.
- **(B)-fallback pre-claim obligation**: if R4's proof fails and the fallback touches
  `encoder.cljc`, `publish.cljc`, `linearize.cljc`, or `derive.cljc`, THIS seat must claim those
  files here before the fallback round starts; the clojure seat yields immediately.
  Pre-claimed as a conditional: none needed yet — the fallback scope is clojure.cljc-internal
  until its round brief is written.

Rule: a round from any other track that would edit one of these files' listed regions queues
behind the owning round (one-writer, per file+region). Everything else in every frontend
proceeds normally, in its own worktree. Any frontend round's G0/G1 verification includes the
kit namespaces (`yang.call.exception-contract-test`) — a flip in any row you did not author
stops the round for diagnosis.

## Branch registry (prevents the r3-style ambiguity)

- `exception-r0` — landed (master 0fa3ff7a). CLOSED.
- `exception-r3-impl` — owned by THIS seat (continuation-semantics orchestrator); worktree
  `/Users/sto/workspace/datomworld-r3-orch`. IN FLIGHT.
- `exception-r3` — EMPTY/stale, created by nobody current; do not use; delete only on owner word.
- Planned: `exception-js-r1r3` (ts/php seat), `exception-php-r1r2r3` (ts/php seat),
  `exception-py-r2r3` (python seat), `exception-r4` (this seat). Cut each from current master at
  round start; one writer per branch; never cut a round on another seat's branch.

## Conditional pause trigger

If R4's equivalence proof fails, the owner-authorized (B) fallback rewrites clojure.cljc broadly;
at that point ALL yang.clojure rounds pause until the fallback lands and its gates re-verify.
No other frontend pauses (it changes clojure's internal encoding only, not the boundary contract).

## AMENDMENT (2026-11-11 23:2x +07) — the VM provenance round (owner-authorized; steward takes round ownership)

Owner authorized (via the python seat's relay, handoff `1794450000000-crosslang-handoff-vm-provenance.md`)
a bounded VM change: **private per-sealed-entry provenance storage** — a host-only sibling field on
heap entries (proposed `:yang.call/provenance`), write-once at wrapper mint via a reserved-prefix
door, host-identity read via a reserved query, GC-with-entry, no guest-visible vocabulary beyond the
reserved doors (if public surface must change: full `cell/authentic?` rigor + architect second
opinion).

- **Round owner**: the continuation-semantics orchestrator (this seat). Branch: `exception-vm-provenance`,
  cut from current master, touching ONLY `src/cljc/yin/vm/engine.cljc` + `src/cljc/yin/vm/module.cljc`
  (+ their tests). **This CLAIMS the yin.vm heap-entry surface for that round** — the yin.vm seat is
  notified here per the queue rule; contention = coordinate before writing.
- **Acceptance contract** (committed RED): `exception-py-r2r3` @ `0326a044`,
  `test/yang/call/python_exception_test.clj` forgery pins — do not rewrite, they are the contract.
- **Adoption sequence**: python wires its wrapper mint/read onto the primitive (its seat, on
  `exception-py-r2r3`); php adopts at its round's start; **the clojure port consumes it for
  protocol-condition retention** (replacing the witness + its documented replay/mutation hole —
  the port brief is amended accordingly); **js is GRANDFATHERED** (its `js.rt` identity store is
  sound and shipped; migration optional, non-urgent).
- **Precondition**: the engineer/architect design consult (owner condition (a)) and the
  cross-frontend ruling (condition (b)) — dispatched as
  `1794451000000-consult-vm-provenance-design.prompt.md`.
- Note of record: the earlier NO on the VM seam declined the OUTCOME-encoding wrapper's compensation;
  this primitive is the uniform R3-contract mechanism for the continuation encoding's four frontends
  — different character, owner-authorized through the python seat's brief.
- Also amended: the clojure registry claim above widens to the whole `clojure.cljc` (+ `clojure/prelude.cljc`)
  for the convergence port (`exception-r3-impl` extends the R3 bridge work; not re-cut from master).

## AMENDMENT (2026-11-12 03:0x +07) — seat split: yang.typescript and yang.php are now SEPARATE orchestrator seats

The former joint "ts/php seat" is split. Per-seat ownership of record:

- **yang.php orchestrator**: owns the php surface — branch `exception-php-r1r2r3` (dispatched;
  round in flight), the php prelude provenance adoption (sol ruling: provenance on the Throwable
  identity cell via `cell/dsg__new-provenance`, adopted at round start), and the R1 bridge repair.
  Adoption spec: `1794471000000-to-yangphp-adoption-spec-and-dispatch-confirmed.md`.
- **yang.typescript orchestrator**: owns the js surface — CLOSED (JS R1+R3 merged as `3e42b034`;
  provenance grandfathered on the shipped `js.rt` identity store; DRY consolidation is a future
  consumer). Split ack + state: `1794471001000-to-yangtypescript-split-ack-state-closed.md`.
- Branch registry lineage note: `exception-js-r1r3` (@ `40284c15`) merged to master as `3e42b034`
  under the ts seat's architect authority — branch CLOSED.
- All future per-seat relays address the seats individually.

## AMENDMENT (2026-11-13 00:4x +07) — cc3 LANDED; the port is the sole clojure.cljc claim

- cc3-lowering-provenance MERGED to master (`2c80a421`, archive `fb493d52`). The yang.clojure
  seat's cc3 branch is CLOSED; their post-port oracle-binding re-verification obligation remains
  (triggered by the port's landing).
- The **port** (branch `exception-r3-impl`, worktree datomworld-r3-orch) is now the SOLE owner of
  `src/cljc/yang/clojure.cljc` + `clojure/prelude.cljc` — its rebase onto post-cc3 master and all
  subsequent port phases. No other seat writes clojure.cljc until the port lands.
- Sequencing note of record: the yin.repl shell refactor (`yin-repl-dht-composition`) waits for
  post-port landing order per its own clarification (1794546000000) — it rebases after cc3
  (done) and before its own landing; the port's rebase carries NEITHER the shell refactor nor
  anything else from that branch (it is not on master). Master namespaces (`yin.repl.query`)
  remain current for the port.
- Adopted: the yang.clojure seat's landing agreement — cc3 first (done), port rebases onto
  post-cc3 master, their post-port oracle-binding re-verification closes the loop.
