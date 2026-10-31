Completed-GMT: 2026-09-19 18:53:50 GMT
Completed-Local: 2026-09-20 01:53:50 +07

# Adversarial review: dao.lease Phase 3 (the holder)

**The Phase 3 delta is not ready for commit.** I found one P1, five P2 and three P3 findings. I edited nothing and did not rerun suites. On the priority checks: the attribution gate is sound, and the bound arithmetic is correct at equality and with a cap present. The defects are all in the holder's state handling and assembly validation.

## P1

**P1 | `lease.cljc:1685-1690, 1704-1711` | The holder's bound is not terminal, so a renewal appended after the bound lets the holder act again on a lease the judge may already have reclaimed.**
- `due-to-renew?` does not consult the bound, so it stays true at and past it. `due-to-renew-schedule-test` (test:2052) pins this: `{:ms 11}` is due, and 11 is exactly grant 1 plus duration 10. Its comment reads "overdue stays due".
- `observe-renewal` advances `:last-renewal-at` on any `ok` append. An `ok` append says nothing about the judge's view of the lease.
- Scenario: grant observed at 1, duration 10, and the control flow stalls.
  - At reading 30, `at-bound?` is true.
  - `due-to-renew?` is also true, so the flow appends a renewal and gets `ok`.
  - The basis moves to 30, and `at-bound?` is false again until 40.
  - The judge lapsed the lease at about 12 and ignores the renewal, because the lease has left its ledger.
- The holder then acts for a further duration on a reclaimed resource. This breaks "Stop acting at the bound … whether or not anything has been heard."
- No test renews at or after the bound.
- Fix:
  - Make the bound terminal. In `observe-renewal`, advance only when `(not (at-bound? holder reading))`.
  - Make `due-to-renew?` false once `at-bound?` is true.
  - Latch a `:bound-reached? true` flag, so that a later stale or smaller reading cannot reopen the lease.
  - Add the scenario above as a test.

## P2

**P2 | `lease.cljc:1612-1618, 1665-1673` | The strict-below-half guarantee is never checked against the duration actually granted.**
- `renewal-interval` sizes the interval against the duration the composition expects.
- A proposal's duration is only an ask, and the grantor may grant a shorter one.
- `observe-grant` is the one place where the interval and the granted duration are known together, and it runs no check there.
- With interval 4 and a granted duration of 6, the holder keeps the lease on exact timing only. With a granted duration of 3, it reaches its bound before its first renewal is due, and nothing reports this.
- Fix: in `observe-grant`, compare `add-duration interval interval` with the granted duration. When the sum is not strictly below the duration, either refuse to hold the grant or record `:undersized? true` in the state. Add tests for both cases.

**P2 | `lease.cljc:1665-1672` | Keep-first holds the first grant naming this holder, whatever lease it is for.**
- There is no match on `:dao.lease/proposal` or `:dao.lease/subject`.
- A holder that proposed `:p1` for subject X will hold an earlier unsolicited or stale grant for subject Y, then ignore its real grant permanently.
- "One state per lease" cannot be achieved, because nothing binds a state to a particular lease.
- Fix: let `initial-holder` take optional `:proposal` and `:subject` expectations that `observe-grant` must match. Add a test with two grants to the same holder.

**P2 | `lease.cljc:1665, 1685, 1704, 1724` | No `reading` argument is validated.**
- Calling `observe-grant` with `nil`, before any tick has been drained, stores `:granted-at nil`. The next `due-to-renew?` or `at-bound?` call then fails inside `interval` with a null-pointer error.
- A reading in a unit outside the table, or past the per-unit bound, throws mid-flow in the same way.
- The judge refuses to act before its first tick. The holder has no equivalent rule.
- Fix: gate every reading with `duration?` plus the unit-table checks. Observe nothing, or throw an assembly-style error, when the reading is invalid.

**P2 | `lease.cljc:1580-1583` | Assembly validation is weaker than the judge's.**
- `:renewal-interval` is checked with `duration?` only. Unit membership and the per-unit `quot` bound are not checked.
- These are the same checks the judge's tolerance gained in N4 and R3.
- An interval of `{:hr 1}` passes `initial-holder`, then throws "unit absent" on the first `due-to-renew?` call.
- In `renewal-interval`, an over-bound coarse unit overflows on the JVM as a raw `ArithmeticException`, not an assembly `ex-info`.
- Fix: reuse the tolerance checks, and run them after `check-units!`.

**P2 | `lease.cljc:1747-1752` | `stop` is not idempotent.**
- Every call returns a fresh `:released` fact.
- A second call on a holder that is already released gives the control flow a second release to append. The contract calls a repeated `:released` for one lease a defect.
- The release tests (test:2131-2148) call `stop` once per holder, so nothing covers a second call.
- Fix: when `:released?` is already true, return `:release nil`. Document that a failed append is retried with the same fact.

## P3

**P3 | `lease.cljc:1693-1711` | Discrete ticks can push the actual renewal past half the duration even when the interval is sized correctly.**
- The holder renews at the first reading at or past the interval.
- With duration 10, interval 4 and ticks at 0, 3 and 6, the renewal happens at 6, which is past the half of 5.
- The sizing check cannot see the tick period.
- Fix: let `renewal-interval` take an optional tick period and require the interval plus that period to be strictly below half the duration. Otherwise state the relation in the docstring and in §2.5.

**P3 | `lease.cljc:1676-1684` | "The reading it renewed at" is ambiguous.**
- The reading should be the one drained before the append. A reading taken after the append moves the basis later than the evidence the judge can have.

**P3 | `lease.cljc:1714-1735` | No single "may I act?" predicate, and the cap basis is a contract question.**
- Every caller must compose `grant`, `(not released?)` and `(not at-bound?)` by hand. A `holding?` predicate would remove that risk.
- The holder measures the cap from the reading at which it observed the grant, which is later than the judge's tenure start. The holder can therefore act past the judge's `:cap` reclaim by the grant's flight time, and tolerance does not cover the cap. This follows the contract literally, so route it to the contract owner together with the earlier question about tolerance for an `unknown` lease.

## What holds

- **Attribution:** `observe-grant` uses the resolver bound to the source. A forged grant, a grant from a non-grantor, a grant naming another holder, a defective fact and a refusal all establish nothing.
- **Bounds:** `at-bound?` takes the earlier of the duration bound and the cap, and it is correct both with and without a cap.
- **Equality:** the bound fires at equality, which errs toward the holder stopping early.
- **Activity basis:** it is the later of the last renewal and the observed grant.
- **Renewal recording:** `observe-renewal` advances the basis only on an `ok` append and records nothing after `stop`.
- **Prohibitions:** the holder section has no clock, timer or mutable state, and no path writes `:lapsed`.
- **Assembly:** the resolver gate matches R1, and `check-units!` is reused.
