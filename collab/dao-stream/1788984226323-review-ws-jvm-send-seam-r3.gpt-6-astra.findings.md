Completed-GMT: 2026-09-09 20:04:40 GMT
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574
Role: Routine Review | Model: gpt-6-astra

I’ll check the revised observer placement and return values, then inspect the restored close test and new inline-failure test. I’ll keep this read-only and won’t rerun the suite.

**Clean and ready to commit. All findings from the previous rounds are discharged.**

- **Observer placement is correct.** `whenComplete` runs even when registered after exceptional completion, so the installation-to-registration window does not lose failure. Another submitter’s observer may claim the failure first; that is correct because the transition belongs to the connection, not a particular caller.
- **Concurrency remains sound.** Tail construction and installation share the failure-claim lock. A submission either chains before failure is claimed or receives `closed` afterward. Successors of a failed future cannot issue their sends, and only one observer reports the failure.
- **Reporting occurs outside the submission monitor**, including the already-exceptional case. The state is marked failed before adapter reentry, preventing recursive teardown.
- **`closed` composes correctly.** `send-result` preserves the outcome map, covering an append that passed the outer handle’s open-phase check before failure. Before-open `false` and satisfied-close `nil` retain their distinct meanings.
- **J1–J4 are adequately pinned.** The seven tests cover incomplete-future acceptance, send ordering, queued failure propagation, terminal refusal, healthy close chaining, inline notification, reentrant teardown, and before-open behavior. `Thread/holdsLock` is appropriate for the explicitly required monitor property; the remaining assertions observe host calls and notifications rather than private future structure.
- **No completion wait remains.** Send, unsent retry, and close do not join or wait for their futures. The public `client-socket` extraction remains a reasonable test seam.

No additional finding. Read-only static review; no files written or suites rerun.
