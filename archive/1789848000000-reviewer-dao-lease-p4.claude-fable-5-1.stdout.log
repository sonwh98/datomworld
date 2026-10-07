Completed-GMT: 2026-09-19 19:44:29 GMT
Completed-Local: 2026-09-20 02:44:29 +07

# Adversarial review: dao.lease Phase 4 (composition)

**Phase 4 is not ready for commit.** There is one P1 and there are five P2 findings. Two constructor refusals are missing, and the end-to-end test and the sketches prove less than the plan asked of them. I edited nothing and did not rerun suites.

## P1

**P1 | `src/cljc/dao/lease.cljc:699` (`initial-judge`), `2075-2110` (`make-judge`) | Neither constructor refuses a judge with no `:self`.**
- Authority is decided by `(= author (:self judge))` at 980, 989 and 1010.
- With `:self` nil, any fact whose resolver answers `nil` reads as authored by the grantor. A `nil` answer is the natural result for an unknown source or a missing envelope key.
- A forged `:accepted` from such a fact seeds tenure. That breaks "only grantor-authored facts establish terms" because of a wiring omission.
- The holder side already refuses this case (`some? self` at 1584). The refusal matrix has no `:self` case (composition test:205-235).
- Fix: add `(check-assembly! (some? (get config :self)) …)` to `initial-judge`, so `make-judge` inherits it. Add the matrix row. Add a test that a resolver returning `nil` never counts as the grantor.

## P2

**P2 | `lease.cljc:2002-2024` | The resolver-compatibility check compares two labels written by the same config author.**
- It catches a typo between `:resolver-bindings` and a medium's `:attribution`. It cannot catch a real mismatch between a resolver and a medium, so on priority check 2 the answer is that the check is mostly for show.
- `:retention`, `:capacity` and `:value-domain` are checked against their enums and then discarded. `wire-facts` never receives them. Nothing relates capacity to `:drain-budget` or `:cadence` (S3), and nothing relates `:host-values` to `:durable?`.
- The served-connection sketch shows the weakness.
  - It declares `:attachment-identity`.
  - Its resolver is `(fn [source _] source)`, identical to the per-author resolver.
  - `:ws/attachment` is an envelope key on deposited values (`ws.cljc:167-174`), so under D4 it is the `:envelope-key` kind.
  - The label is wrong and nothing notices.
- Fix: make the declaration do work. Two steps:
  - Keep `:medium` on the wired entry.
  - Add at least one derived refusal, for example refuse `:durable? true` with a `:host-values` medium, or an `:evict-oldest` capacity below `:drain-budget`.
- If no derived refusal is added, the docstring should say that compatibility here means only that the declarations agree with each other.

**P2 | `lease.cljc:2075-2110`; composition test:223 | A missing tolerance is accepted, and the plan says it must be refused.**
- Plan §4.4 item 1 lists "a missing or invalid tolerance" as a refusal.
- The contract lists "a tolerance supplied to the judge, possibly zero" among a composition's duties. That reads as an explicit value even when it is zero.
- The brief suggests a nil tolerance is something the contract permits. I read the composition duties as owing an explicit tolerance, possibly zero, so an omitted one is not supplied.
- `judge-config` carries no tolerance and still assembles. Only `{:hr 3}` is tested.
- Fix: have `make-judge` require an explicit tolerance. `{:ms 0}` is the zero, and `initial-judge` can keep treating nil as zero. Add the row. If this is not done, amend plan item 1.

**P2 | `lease.cljc:2143-2167` | `make-holder` validates no outbound medium.**
- The holder appends renewals and releases to a medium the judge reads. That wiring is the holder's half of C1.
- The config has no `:writer`, no declaration for one, and no check that one exists.
- A holder assembled with nowhere to renew constructs cleanly, and its lease lapses.
- Fix: require `:writer`, and return it on the value.

**P2 | composition test:294-326 | The end-to-end test never composes the holder half.**
- The renewal is appended by hand with `(fact! (lease/renewal :l1))`.
- Outside the two refusal tests, `make-holder`'s return value is only read for `:scope` (test:271, 280).
- These are never driven from a composed holder: `observe-grant`, `due-to-renew?`, `observe-renewal`, `holding?` and `stop`.
- What the test does cover:
  - It drives the composed `:step`.
  - It reclaims once and records once.
  - It shows no re-record on later passes.
- Fix: add one cycle that runs both halves.
  - The holder observes the grant carried onto its `:fact` medium.
  - It renews when due by appending to the judge's medium.
  - It then stops.
  - Assert a `:release` lapse.

**P2 | composition test:370-479 | The sketches do not exercise the seams they name, and one assertion cannot fail.**
- `serving/step!`, `close-session!` and `call-close!` appear only in comments. `:ws/attachment` does too, as the first P2 noted.
- Each sketch is "reclaim flips an atom, then a `:silence` lapse is recorded". Phase 2 already pins that. The plan's host matrix marks these sketches "exercised" on clj.
- In the served-connection sketch, `holder-facts` is wired to nothing. The test appends the grant itself and then asserts the buffer holds no `:lapsed` (435-439). No code path could put a `:lapsed` there, so the C8 assertion is vacuous.
- The place C8 could actually break is carriage from the grantor's stream to the holder's medium. No carriage helper or filter exists yet.
- Fix:
  - Have the served-connection resolver read `:ws/attachment` from an envelope, declared `:envelope-key`.
  - Under `#?(:cljd nil :clj …)`, drive one real `serving` session whose reclaim calls the real close path.
  - Replace the vacuous assertion with a carriage step that copies the writer's facts to the holder's medium, and prove that `:lapsed` is filtered out.

## P3

- **`lease.cljc:2079-2080`:** `check-cadence!` runs before `check-units!`. A unit table such as `{:ms 0}` reaches `(quot magnitude-limit 0)` and throws a raw `ArithmeticException`, not an assembly `ex-info`. Swap the order.
- **`lease.cljc:1966`:** `:capacity` is required even for `:complete` retention, where it means nothing. No `:complete` medium appears in any test. Require capacity only for `:evict-oldest`, and add a positive `:complete` case.
- **`lease.cljc:2027-2042`:**
  - The durable gate does throw when a prerequisite is missing. It checks presence only, so a bare `:dj` keyword passes.
  - `:scope` is a label that nothing consumes. The plan accepts that, but the docstring's claim that a process-scoped composition "cannot claim durable use" overstates it.
  - `make-holder` demands `:durable-judge` from a holder, which is an odd requirement.
- **`lease.cljc:2072-2074`:** "no stream exists in a refused composition" is false. The caller created the handles it passed in. "Nothing is wired" is the accurate claim.
- **`lease_test.cljc:2409`:** `System/nanoTime` has an arbitrary origin and can be negative.
  - While it is negative, `(max 1 …)` pins every reading at 1. Readings stay monotonic but time is frozen, so no lease lapses.
  - Subtract a baseline captured when the driver is created.
  - The reader conditional should also be `:cljd`-first, to match the house order.
- **composition test:126-133:** `refusal-key` takes `(first (keys (ex-data e)))`, which depends on map key order. Attach an explicit `:refused` key to each refusal and assert on that.

## What holds

- **Refusals that fire:** every one of these fires, each with its own ex-data key:
  - C2, C3 and C4;
  - cadence and ticks;
  - media and the medium enum;
  - resolver and resolver bindings;
  - writer and reclaim;
  - the three durable prerequisites.
- **Permitted configs:** a config with no `:answer` hook assembles.
- **Order of checks:** all checks run before `wire-tick` and `wire-facts`.
- **Tick driver:**
  - It is stepped and caller-driven, with no timer or callback.
  - Each driver holds its own atoms, so nothing leaks across hosts.
  - A clock running backwards is clamped to the last reading, and stop is honoured.
- **C5:** I saw no clock, timer or callback in the composition section of `lease.cljc`. The host clock reads live only in the test tree.
- **Pending path:** a non-`ok` `:lapsed` append is covered for `full`, `closed` and `transport-error`.
