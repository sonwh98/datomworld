Created-GMT: 2026-09-19 18:15:31 GMT
Created-Local: 2026-09-20 01:15:31 +07 (Indochina Time)
Session-ID: f69044f8-7920-435a-98be-f70b24d6181d
# Task: dao.lease Phase 3 — the holder

Role: DaoSpace & DaoJing Storage Engineer (forward-step discipline)

You are on glm-5.3-flash within a bounded credit window: work tight, read
only what the task names. Phase 1+2 (vocabulary and judge) is committed on
this branch (`9b97d60a`); your unit adds Phase 3, the holder.

Read first, in this working tree:
- `docs/design/dao.lease.implementation-plan.md` — §4.3 Phase 3 (your build
  spec as amended), the invariants H1–H5 in §2.3, and the §6 rows that touch
  the holder
- `docs/design/dao.lease.md` — *The holder* section (lines ~182-196): the
  four rules your code implements
- `src/cljc/dao/lease.cljc` — the existing vocabulary and judge; your
  section joins this file and reuses its constructors and predicates
- `test/dao/lease_test.cljc` — the established test idioms (scripted
  ring-buffer ticks, envelope/source attribution, no clock anywhere)

Scope — exactly the same two files as Phase 1+2:
- `src/cljc/dao/lease.cljc` (add the holder section)
- `test/dao/lease_test.cljc` (add the holder deftests)

Build exactly what §4.3 names: `observe-grant` (H1: a holder holds nothing
until it has observed an author-valid grant — attribution via the same
`(resolver source fact)` seam the judge uses), the renewal-schedule
predicate (H2 as amended: renewal intervals strictly below half the
duration; equality is a violation, measured against the holder's own tick
cursor), `at-bound?` (H3: the earlier of duration since the later of the
last renewal and the observed grant, and the cap, when the grant carries
one), and `release` discipline (H4: the holder authors `:released`, stops
itself, and never reclaims or authors `:lapsed`). Each is a pure function
or constructor the holder's own control flow calls; none installs a timer,
renews on anyone's behalf, or reads a clock. §4.3's Prove list is your test
list, plus the H5 invariant wherever it bears on the holder.

Non-negotiables, as in Phases 1–2: facts are plain maps; defective facts
establish nothing; no host clock; no machinery renews for the holder; a
renewal is an append that must answer `:dao.stream/ok` to advance any
bound. Do not modify the judge or vocabulary sections' behavior; do not
build Phase 4. If a change outside the two files seems required, stop and
say so in the final report.

Verification, one single simple command per step (no chaining, no pipes):
1. `clojure -M:test` — full JVM lane green including your new deftests.
2. `clj -M:cljs -m shadow.cljs.devtools.cli compile slice-peer test` — green.
The orchestrator runs the CLJD lane. Do not stage or commit.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: changed sections, new deftests with counts, exact lane
outcomes, and anything unresolved.
