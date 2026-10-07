Created-GMT: 2026-09-19 18:36:00 GMT
Created-Local: 2026-09-20 02:36:00 +07 (Indochina Time)
Session-ID: 0d520667-20b1-42f7-83c8-9b74753438cb (resumed — your consensus R1/R3 session)
# Task: revise dao.stream.waitset.implementation-plan.md per the closed consensus

The consensus you signed (13 items; Dispute A conceded; Dispute B resolved)
is now closed — gpt-5.6-sol ACCEPTED your probe compromise in R4, with three
qualifications. The authoritative record is:

- `collab/1789842300000-architect-dao-stream-waitset-consensus-report.md`
  (read first — the compiled consensus with gating)
- `collab/1789841259759-architect-dao-stream-waitset-consensus-r2.gpt-5.6-sol.findings.md`
  (the 13-item list with plan-line references)
- `collab/1789841522905-architect-dao-stream-waitset-consensus-r3.claude-fable-5-1.findings.md`
  (your own R3 caveats)
- `collab/1789842300000-architect-waitset-consensus-r4.gpt-5.6-sol.findings.md`
  (Dispute B acceptance + the three qualifications)
- `docs/design/dao.stream.waitset.implementation-plan.md` — the plan to revise

Revise the plan to resolve every item. Key shapes the consensus fixed:

- Item 1: keep `engine/check-wait-set` public as the VM integration; it
  invokes the extracted sweep, commits the returned store, constructs ready
  entries; delete only the two private helpers and the duplicated sweep
  body. Amend W2's deletion check accordingly.
- Item 2: the resolver is a synchronous two-function contract over WHOLE
  entries — `{:resolve (fn [store entry] -> {:stream :cursor :value …})
  :advance (fn [store entry cursor] -> store')}` — host-owned fields stay
  opaque; the library never adds resolved handles to returned entries; W1
  tests a non-VM store.
- Item 3: writer `:dao.stream/closed` stays its own terminal failure, never
  reader `:end`.
- Item 4 (W3): exclusive host ownership of interpreter state; the host loop
  calls `check`, consumes `:woken`, then calls a PURE cadence function
  `(cadence-step cadence-state moved?) -> {:cadence-state :sleep-ms}` that
  never calls `check`; host queue carries contentless wake tokens only;
  external parks are DELETED (control-stream + nudge pattern replaces
  them); no disposition callback retained by the library.
- Item 5: delete `:budget` — complete ordered sweep; bounded traversal
  deferred until its ordering semantics are specified.
- Item 6: `nudge!` is a contentless, coalescing, non-reentrant,
  composition-wired scheduling hint; polling remains authoritative; cover
  close and other relevant transitions; lost-nudge cost is the armed
  interval (possibly the backoff ceiling).
- Item 7: unresolved/unsupported entries return qualified waitset
  diagnostics (e.g. `:dao.stream.waitset/unresolved`), are removed from
  waiting, and the VM raises diagnostics BEFORE restoration
  (`engine.cljc:397` gap); rewrite the divergence-register row at plan:331
  as an intentional divergence; add the focused test.
- Item 8: `check` returns `{:waitset {:waiting …} :woken … :store …}`;
  per-call results only; no handle injection; repeated-check and
  serializable-fixture tests.
- Item 9: writer `:put` migration removed outright; the NON-ADVANCING
  ADVISORY PROBE is the reader handoff, with gpt's three R4 qualifications
  (retire on terminal; pending writes stay on host cadence; cursor-state —
  not just reference — isolation); every W4 site is enumerated from the
  committed W0 census with probe/host-cadence assignment.
- Items 10-11: the gap-recovery co-waiter test; per-operation literal
  classification maps over `outcomes-next` and `outcomes-append`
  separately.
- Item 12: W5 covers all obsolete `dao.runtime` references in
  `dao.await.md`; scope the stale-reference check honestly.
- Item 13: `check` is a synchronous, state-threaded interpreter step with
  explicit stream effects — never "pure"; replace the leftover draft
  sentence at the store seam.

Also fold in the W0 census's committed state: the adoption columns were
corrected under a separate sign-off (ws ack sweep deliberately unchanged
under the transport exclusion; one active waiter per REPL session) — W4
must not contradict them.

Add a "Revision — 2026-09-20 consensus reconciliation" section listing
each item's disposition. Keep W0's census table rows untouched.

Scope: ONLY `docs/design/dao.stream.waitset.implementation-plan.md`. No
staging, no commit. One single simple command per step if any.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: per-item disposition (1–13), any caveat you applied beyond the
letter of an item, and anything unresolved.
