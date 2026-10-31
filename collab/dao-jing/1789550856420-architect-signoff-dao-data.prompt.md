Created-GMT: 2026-09-16 09:27:36 GMT
Created-Local: 2026-09-16 16:27:36 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 57378353-534b-441b-b8c8-e07e0187d84c

# Task: Architect sign-off on the `dao.data` implementation and its two consumer wirings

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-16 16:27:36 +07 | Status: active | Rationale: architecture sign-off gate before commit, per this branch's convention for every unit tonight

Perform a read-only architecture review of the current uncommitted
working-tree diff implementing `dao.data` and wiring it into two consumers.

Read first:
- `docs/design/datom.world.md`
- `docs/design/dao.data.md` (the frozen design spec — you (fable) wrote its
  first draft, under the name `dao.summary`, in
  `collab/1789566611000-architect-state-snapshot-abstraction.prompt.md` /
  `.claude-fable-5-1.stdout.log`; it has since been substantially revised in
  an interactive session and by a full adversarial review round — read the
  CURRENT doc, not your memory of the original draft)
- `src/cljc/dao/data.cljc` and `test/dao/data_test.cljc` (new files — read
  directly, not via `git diff`, since they're untracked)
- `git diff src/cljc/yin/vm/telemetry.cljc src/cljc/yin/vm/ffi.cljc
  src/cljc/yin/repl/driver.cljc src/cljc/yin/repl/serve.cljc
  src/cljc/yin/repl/connect.cljc`
- `collab/1789550577098-review-dao-data-implementation.gemini-3.1-pro-high.findings.md`
  (the independent adversarial review already completed on this diff —
  verdict: ready to proceed, no gaps found; treat this as a claim to verify,
  not authority, per this seat's own standing instructions)

## Context

Two independent implementation units, verified locally by the orchestrator
(not just trusted from delegate reports): `clj -M:kondo --lint` clean on all
seven touched/new files; `clj -M:test -n dao.data-test -n yin.vm.ffi-test
-n yin.vm.semantic-ffi-test` → 38 tests, 202 assertions, 0 failures; `clj
-M:test -n yin.repl.driver-test -n yin.repl.serve-test -n
yin.repl.connect-test` → 51 tests, 244 assertions, 0 failures (run after
both units combined). CLJD compiles scoped to the three new/changed
non-REPL namespaces individually; full-suite CLJD remains blocked by a
known, pre-existing, unrelated bench-file issue (untouched by this diff).

One real design/implementation contradiction surfaced during implementation
and was resolved, not silently accepted: `dao.data.md`'s stream rule
originally claimed the `:opaque` fallback defends a descriptor
implementation that's "malformed or throwing." The implementation only
defends the malformed case — defending "throwing" would need a
host-specific `catch` (confirmed against this project's own precedent,
`dao/stream/ws.cljc:89`, needing a three-way reader conditional),
directly conflicting with `dao.data`'s own no-reader-conditionals
constraint. Resolved by editing the doc to drop the "or throwing" claim and
state the actual limitation, keeping no-reader-conditionals as the harder,
load-bearing constraint — treating a broken third-party descriptor
implementation the same way the doc already treats an adversarial,
non-terminating lazy sequence (caller's responsibility to guard against).
Independently evaluate whether that resolution is architecturally sound.

Also evaluate specifically: the `yin/vm/telemetry.cljc` stub's own
docstring explicitly deferred "ordering the three surface protocols before
the `map?` branch" to "the real emit path, not a stub" — and this diff wires
in classification that now does that ordering, inside what is still
officially a stub (`enabled?`/`emit-snapshot` remain no-ops). Is completing
one specific piece of a stub's deferred behavior, while leaving the rest of
the stub unbuilt, architecturally coherent, or does it create a
partially-real stub that's harder to reason about than either a pure stub
or a real implementation?

Evaluate foundational invariants, ownership boundaries, explicit state and
control flow, concurrency and linearization, dynamic extension, host
isolation, CLJ/CLJS/CLJD portability, migration risk, completion criteria,
and design contradictions. Distinguish architectural defects from
implementation gaps or intentionally deferred work. Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: severity | file:line | invariant/evidence | recommended
correction. Also confirm the requested properties that passed review. End
with an explicit APPROVE / APPROVE-WITH-FINDINGS / REJECT verdict — this
governs whether the orchestrator is authorized to stage and commit this
diff, per the user's own standing instruction that staging/commit requires
this seat's sign-off.
