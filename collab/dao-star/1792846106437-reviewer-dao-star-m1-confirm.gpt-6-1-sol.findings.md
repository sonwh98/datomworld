Completed-GMT: 2026-10-24 12:56:10 GMT
Completed-Local: 2026-10-24 19:56:10 +07

Static confirmation only. No files edited, staged, or committed; no suites rerun.

Paths below are relative to the worktree.

| Finding | Resolution | File:line evidence |
|---|---|---|
| 1. Duplicate terminal events | **Resolved** | `src/cljc/dao/stream/http.cljc:148` derives ownership from the committed prior state. Competing claims cannot both obtain the entry. |
| 2. Lost terminal event | **Resolved** | `http.cljc:133` and `:256` derive both sides of the handshake from committed transitions. Either accepting or finishing owns the terminal deposit. |
| 3. Unsupported dead-peer bound | **Resolved under revised ruling** | `src/cljc/dao/star/lease.cljc:274`, `:306`, `:329`, `:332` require and apply subscription caps, including inventory-recovered exchanges. `test/dao/star/r3_test.clj:89` checks renewal, reclaim, terminal deposit, and `:cap` lapse. |
| 4. Evicting session facts | **Resolved** | `lease.cljc:239`, `:245`, `:356`; `driver.cljc:62`; `router.cljc:61` enforce complete-history declarations without capacity. Tests use memory-log; `r3_test.clj:65` verifies retained origin history. |
| 5. Acting before grant / fail-open | **Partially resolved** | `driver.cljc:248`, `:336`, `:371` correctly stage initial responses and gate input handling. Subsequent patches and staged subscription flushing still bypass required tenure checks; details below. |
| 6. Pending publication bypasses bound | **Resolved** | `driver.cljc:279` drops pending work after bound/lapse; `:282` prevents publication while not holding. Tests cover bound, lapse, and gap. |
| 7. Consumption depends on emission | **Resolved** | `driver.cljc:313` stages a transition independently of model emission and retains the previous model when needed. `r2_test.clj:163` exercises inputs between emissions. |
| 8. Gap handling | **Partially resolved** | Tick gaps are now tracked at `lease.cljc:99`; lineage gaps call `forget!` at `driver.cljc:425`. But exchange-holder gaps remove only one subscription at `:412`, rather than forgetting every subscription. |
| 9. Grant skipped before first tick | **Partially resolved** | `lease.cljc:100` preserves grants while the reading is nil. A non-nil **invalid** first reading still advances the fact cursor without observing the grant. |
| 10. Failed release not retried | **Resolved** | `driver.cljc:427` drives pending releases on later passes; `r2_test.clj:255` verifies failed publication followed by recovery. |
| 11. Test adequacy | **Resolved for the original order/renewal concerns** | `r2_test.clj:293` would fail with facts-before-ingress ordering. `:314` chases tokens through individual passes. `:328` checks renewal spacing against the half-duration bound. |
| 12. CLJD silently decodes `{}` | **Resolved as an explicit support limitation** | `signals.cljc:11`, `:30`, `:37`, `:68` disclose missing seams and propagate their exceptions instead of silently discarding supplied signals. CLJD functionality remains unimplemented and unverified. |

Three blocking implementation gaps remain:

1. **HIGH | `src/cljc/dao/star/driver.cljc:138`, `:336`, `:428` | Projection paths do not consistently enforce tenure.**  
   `publish!` and `read-messages!` can emit subscription patches before `tick-subscriptions!` observes that pass’s exchange ticks and lapses. `patch!` checks only whether a subscription is staged. Advance the driver’s tick to an exchange’s cap while its lineage remains live, then process an input: a patch is appended before the exchange holder notices its bound.

   The newly introduced `flush-subscriptions!` also checks only the exchange holder. A subscription staged while the lineage was live can receive its first projection after the lineage reaches its bound, if its exchange grant arrives later.

   **Correction:** observe exchange-holder state before any subscription projection, and require both lineage and exchange tenure at every projection path. Add independently scheduled holder/judge tests.

2. **HIGH | `src/cljc/dao/star/driver.cljc:412` | Exchange-lease gaps still receive incomplete cleanup.**  
   The code drops only the exchange whose holder observed a gap. Another subscription with a newer cursor can remain and continue renewing, including renewing the lineage. The new gap test uses one subscription, so it cannot distinguish this behavior from forgetting all subscriptions.

   **Correction:** propagate an exchange-holder gap to session-wide `forget!` handling before further renewals. Test two subscriptions with differently positioned lease cursors.

3. **MEDIUM | `src/cljc/dao/star/lease.cljc:100` | Invalid first readings can permanently discard grants.**  
   The new guard distinguishes nil from non-nil, rather than unusable from usable readings. For example, `{:ms 0}` passes this guard; `observe-grant` rejects it, but the fact cursor advances. A subsequent valid tick cannot recover that grant.

   **Correction:** retain grants until a valid, in-table reading can observe them, or retain pending grants explicitly. Add invalid-first-reading followed by valid-reading coverage.

The remaining architectural qualifications are acceptable:

- **(a) Unreachable facts-gap handling:** harmless defensive code, provided it is not presented as recovery from a permitted complete-history gap.
- **(b) Requests remain unread without a tick:** correct fail-closed behavior. Supplying and driving holder tick streams is a composition duty.
- **(c) `:subscription-max` sizing:** a composition choice. A 200 ms cap with a 100 ms duration is valid. Actual reclaim follows a completed judge pass; cap sizing must allow for cadence and reclaim cost.
- **Untested CLJD throw:** honest enough for explicitly scoped JVM delivery; it does not establish CLJD support. The opening “total / nothing throws” docstring should be qualified to match the later scope exception. Green CLJD tests that never require these namespaces provide no additional assurance.

The renamed loopback test now accurately describes boundary reclaim and the chunked-response terminator. It does not claim real client-disconnect detection.

**NOT SIGNED OFF**