# D1 r11 — bounded fixture-gate integration and pre-declaration pilots

Status: **partial; no complete bounds declaration, bounded corpus verification, D1 companion or verification result**. Worktree mirror; no commit. The retained r9e store under `target/d1-r9e-retained-store` stays uncommitted and its retention location/owner remains pending. The r9g sequencing resolution authorizes labeled pre-declaration pilots and requires any verification run to follow a frozen declaration.

## Integrated fixture gate

`test/yang/correspondence_verification.clj` composes the independent `check-fixture!` static gate, a required `:site-declarations` refusal of a mutated declaration, each lane's per-step runtime checker and the independent scoped `attributed?` query for a nonempty supplied claim set. It preserves the trace data and measured runtime information and labels its output `:not-a-d1-verification-result true`: this is not a complete D1 verifier and does not establish recipe, image derivation, instruction provenance or full corpus credit coverage. It refuses missing completed lanes and missing trace-graph sizers; measured maximum exceedances are returned as `:failed-bound` with observed value and ceiling. A post-halt graph-size bound and fixture wall-time checks at phase boundaries are supported; the latter do not interrupt a long host operation mid-phase. The runtime checker monitors logical printed trace bytes and steps between guest steps, sampled JVM used heap and lane elapsed time; it does not enforce an OS/native-memory limit and a single step can temporarily exceed limits before detection. Reaching the step budget without halting is a refusal; do not misreport the unexecuted next step as a measured exceedance.

Row retrieval now accepts an explicit maximum rather than always using the 100,000-row pilot default. `bounded-tree-statistics!` checks rows, occurrence and application counts, depth and encoded application-path bytes as it walks, before invoking either lane. It does not independently cap the byte size of one row or initial row retrieval; a real driver must enforce those as well. The integrated gate has tests for real static declaration reconstruction using synthetic ASTs, simulated bounded lanes, scoped attribution, input mutation, missing lane, empty claims, a failed bound with preserved evidence, missing/oversized trace-graph measurement, tree limit refusal and fixture time failure. Passing these tests is not a corpus verification.

## Guard pilot and material timing finding

The first pre-declaration guard pilot used the r10 candidate limits, replayed retained programs, re-fetched both closures and queried all symbol-valued static callees at arities 0–4; it is an exploratory query set, not the required full upstream attribution/credit set. `1794731000000-engineer-d1-r11-guard-pilot.edn` records two passes, 1/5 and 100/700 exploratory queries credited. The timed variant (`1794731000000-engineer-d1-r11-guard-pilot-timed.edn`, `target/d1-r11-guard-pilot-timed.log`) additionally sized the halted trace graph. For `:check-assume-have-discharge`, walker/semantic steps were 2,544,482 / 1,398,649, lane wall 18,679 / 7,789 ms, trace graph 311,144 bytes each, 2,345 records / 67,500 printed bytes each, and the integrated static/mutation/lanes/query gate took **59,390 ms**. The 60,000 ms candidate from r10 would have only 610 ms (~1%) headroom and is not adequate to finalize as a headroom-supported ceiling. That gate timing excludes the initial store reopen, retained input fetch and replay compilation. Candidate values were not a declared run and no bounded-verification result is claimed.

A further pre-declaration two-fixture pilot `target/d1-r11-guard-pilot-complete.clj` completed with the newer tree checks and explicit graph sizer. Its outcome record is `1794731000000-engineer-d1-r11-guard-pilot-complete.edn`; the later retained-path pilot is described below. Its 180,000 ms fixture safety setting is a pilot setting, not a frozen declaration or permission to raise a declared bound.

## Remaining work

The r10 candidate table remains non-binding. A complete finite measured declaration still requires comparable full guarded-fixture and phase timings (including fetch/replay and query), defensible headroom including the 59,390 ms observation, byte/memory capacity enforcement and the required true B/S provenance production/persistence/loading/query and cold/warm comparisons. The prior B/S path-index benchmark is not that full experiment. The fixture gate still needs a real corpus driver with declaration binding, supported complete claims, all static and phase ceilings and failure persistence, as well as the design §4.2 recipe/image/derivation/instruction provenance steps. There is no complete D1 record or address to cite, so no companion or verification result has been fabricated. The entire oracle suite was not run.

## Retained-input fixture gate and latest pilot

`verify-retained-fixture!` now joins the r9e outcome to retained input,
snapshot, complete row closures, evidence and exact capture profile; it
recompiles the captured program/options and requires the recorded root, then
passes the reconstructed static input and caller-supplied claims into the
bounded fixture gate. It uses the original recorded admission coordinate,
not a newly minted one. It checks the requested row-fetch ceiling and maps a
fetch-limit refusal to structured `:failed-bound` evidence: `:observed` is
the lower bound on distinct required addresses encountered, explicitly
labeled `:distinct-required-addresses`; it does not claim the extra row was
successfully fetched. Fixture elapsed is checked at host phase boundaries;
per-step checks apply only within each lane. The progress writer now attempts
to fsync the directory after its atomic rename, best-effort on hosts that do
not support directory syncing.

The final two-fixture, **pre-declaration** retained-path pilot
`1794731000000-engineer-d1-r11-retained-pilot.edn` (SHA-256
`fce5f750ca1e46b4ee25850cf874a80d6d9e7e6ea0fd46d83787bc40fe2d3b76`)
recorded two passes: `:minimal-last` 3,245 ms and
`:check-assume-have-discharge` 68,932 ms including fetch, replay and fixture
gate but excluding opening the store. Its exploratory static-callee arities
0–4 produced 1/5 and 100/700 credits. It is not the full upstream claim set,
not a D1 verifier result and not a declared bounded run. Earlier complete
pilot measured the larger fixture at 69,876 ms; both exceed the old 60,000 ms
non-binding combined-fixture candidate. A possible 150,000 ms candidate is
not a finalized ceiling; the full corpus and store-open/phase measurements
under this final code are still absent.

The gate refuses empty claims and incomplete fixture, tree, lane or graph-size
bounds before running a lane. It also refuses a missing graph sizer and checks
post-halt reachable trace graph bytes against the supplied ceiling. Failed
bounds include the lane where relevant and preserve observed/ceiling values.
Per-step ring, printed-byte, elapsed and sampled-heap checks, and phase-boundary
fixture-time checks, are useful but do not form an operating-system memory
cap or interrupt a long host phase; real-process failure supervision and full
static serialized-object bounds remain unimplemented. The user-required D1
record/recipe, complete upstream carrier/claim reconstruction, image and
instruction derivation/provenance verification and publication remain open.
No complete declaration, corpus bounded verification, companion or result
exists; nothing here authorizes reliance.

Final focused correspondence tests passed **33 tests / 114 assertions**, zero failures or errors; clj-kondo on the eight correspondence support/test files reported **0 errors and 0 warnings**. `git diff --check` passed. No commit was made.
