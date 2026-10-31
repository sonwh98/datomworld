Created-GMT: 2026-10-24 11:37:20 GMT
Created-Local: 2026-10-24 18:37:20 +07
Coding-Agent: claude
Session-ID: 59B70221-8A9E-4D0F-91EF-0A9609418224 (resumed)
# Task: dao.star Mode 1 — fix round r2: ten review findings
Role: DaoStream and Distributed Protocol Engineer
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-24 18:37:20 +07 | Status: active | Rationale: same seat (session resumed); you wrote the code under review

The codex adversarial review of Phase A+B returned NOT SIGNED OFF with 12
findings. Read the full findings first:
`/Users/sto/workspace/datomworld/collab/1792840752497-reviewer-dao-star-m1-phase-ab.gpt-6-1-sol.findings.md`

Your scope in this round: findings **1, 2, 5, 6, 7, 8, 9, 10, 11, 12** and
the loopback-comment wording correction. Findings **3 and 4 are excluded** —
they challenge architect rulings and are being re-ruled in parallel; do not
touch the dead-peer/tick-renewal mechanism or the session-facts medium's
transport kind beyond what the findings below strictly require (finding 8's
gap propagation must not change which transport backs any medium).

Work in the same worktree `/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-m1`.
TDD: for each finding, write the failing test that pins the defect first
(where the defect is a missing test — findings 11 — strengthen the existing
test), then fix.

**1 (HIGH, `http.cljc:148` claim! duplicate terminal events).** The `swap!`
fn sets the `claimed` volatile but never resets it on the absent-entry
branch of a retry, so two competing closers can both capture the entry and
both close it. Derive ownership from the committed transition: the swap!
fn must answer distinguishable state (or reset the volatile at fn entry)
so a losing retry answers nil. Add a deterministic competing-claims test
(two threads or an injected conflicting swap).

**2 (HIGH, `http.cljc:133` finish!/awaiting lost terminal event).**
`pending?` is set inside a swap! fn that may retry; a retry that finds the
`:awaiting` slot removed (accept! observed nil and removed it) still leaves
`pending?` true, so neither side deposits the terminal event. Base the
deposit decision on the successful atomic transition, not on a speculative
in-fn write. Test the exact interleaving, including the
request-then-terminal ordering.

**5 (HIGH, `driver.cljc` acting-before-grant and fail-open checks).**
`begin!` answers the begin HTML immediately after the root fact, before the
lineage grant is observed; `subscribe!` projects before its exchange
holder's grant is observed; `handle!` gates on `granted?`/`past-bound?`
which can answer fail-open on an invalid reading. Fix: stage the begin
response and the subscribe projection until the relevant grant is observed
(the same `:wait`-style unread-message mechanism `handle!` already has);
gate leased actions fail-closed (an invalid or missing holder reading must
not permit consumption or projection), with explicit treatment of gaps.

**6 (HIGH, `driver.cljc:356` pending publication bypasses the bound).**
`session-step!` calls `publish!` unconditionally when `:unpublished` is
staged. Recheck the holder's tenure before publishing pending work: past
bound or lapsed, the pending transition must not publish or project.
Define what happens to the staged transition then (it is dropped and the
exchange is left to lapse; the input's requester sees silence) and test
recovery after bound, lapse, and gap.

**7 (HIGH, `driver.cljc:263` consumption tied to emission).** A program
that reads an input and parks again without emitting a model currently
leaves the token current and appends no consumption fact, so replaying the
token re-executes a step. Record every consumed input independently of
emission: the successor fact and latest-model retention happen whether or
not the view medium emitted. Test a program that consumes two inputs
between emissions.

**8 (HIGH, `lease.cljc:99`/`driver.cljc:338` gap handling per medium).**
Propagate gap outcomes from every cursor the driver and holders read —
tick stream, lineage-lease medium, exchange-lease medium, view medium,
begin medium — into the same forget-and-stop-renewing behavior the routed
session medium already has. A lineage-lease gap must clear the session's
subscriptions before any further renewal. Extend the gap tests to cover
each medium.

**9 (MEDIUM, `lease.cljc:103` grants skipped before the first tick).** The
holder's fact cursor advances even when its reading is nil, so a grant
published between ticks is skipped forever. Retain pending grants or defer
cursor advancement until a reading exists that can observe them. Test
independently scheduled judge and holder ticks.

**10 (MEDIUM, `lease.cljc:177`/`driver.cljc:249` failed release never
retried).** A `release!` that fails to append is stored but never retried.
Drive pending releases on subsequent passes. Test one failed release
followed by recovery.

**11 (MEDIUM, test adequacy).** Strengthen the observation-order tests so
they fail if the drain order is reversed (publish a fact and deposit its
request inside the same controlled pass, not one round ahead); tighten the
renewal-bound test to assert positions against tick readings and the
strict half-duration bound rather than "some renewal exists".

**12 (MEDIUM, `signals.cljc:18` CLJD decode silently {}).** Do not
implement a CLJD JSON seam in this round. Make the scoping loud instead:
`decode` answers `{}` only for genuinely absent or malformed input, and on
a host where the parser seam is unimplemented it must throw `ex-info`
naming the seam rather than silently answer `{}`. State the JVM-only scope
in the namespace docstring.

**Loopback comment**: `test/dao/star/loopback_test.clj:141` says the client
sees EOF; it actually asserts the chunked-response terminator. Correct the
comment and any misleading test name.

Acceptance: all six dao.star test namespaces plus `dao.stream.http-test`
green (`clojure -M:test -n ...`), `bb test:sub:clj dao.stream` 0 failures,
kondo clean on every file you touch. Do not rerun the full three-lane gate
(the orchestrator runs G2 after reconciliation). File ownership unchanged
from your Phase B brief, plus `src/cljc/dao/stream/http.cljc` and
`test/dao/stream/http_test.clj` for findings 1-2 only (they are your Phase
A files). Still excluded: `jvm.clj`, docs, deps.edn, the tick-renewal
mechanism and transport-kind changes reserved for the architect's r2
rulings.

Report to
`/Users/sto/workspace/datomworld/collab/1792841840885-stream-engineer-dao-star-m1-fixes-r2.claude-opus-5-5.findings.md`
(a denied write goes in your final response; the orchestrator promotes it).
Do not stage or commit. Begin the final response exactly with:
Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and timezone>
