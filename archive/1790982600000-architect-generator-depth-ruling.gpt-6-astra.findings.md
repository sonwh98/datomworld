I’ll read the report and recursion/context code, then rule on generator depth, the regression contract, and the thread-context implications.


Completed-GMT: 2026-10-02 22:34:25 GMT
Completed-Local: 2026-10-03 05:34:25 +0700

**Require depth relative to the current resumer. Do not adopt first-resume depth as the completed support profile.** The engineer followed the earlier whole-record rule; this ruling amends that rule, rather than classifying the implementation as an unauthorized deviation.

First-resume depth retains historical caller depth after those calls have returned. Conversely, a generator first resumed shallowly can later undercount a deeper caller. That is not merely CPython’s version-dependent boundary offset: it makes recursion accounting depend on obsolete execution history.

**1. Context invariant and implementation direction**

Insert this normative sentence in §8.5.2:

> Effective recursion depth counts the currently active Python function frames: a resumed generator contributes its suspended local depth above the current resumer’s effective depth; intra-activation escapes restore captured local context without restoring an earlier resumption base, generator crossings restore the current caller’s complete context, and thread switches preserve the complete context of each thread.

Represent effective depth as `base + local-depth`. A generator’s saved handlers and escape snapshots record **local** depth; each resumption supplies a fresh base from the current caller. Ordinary function calls increment local depth, normal returns decrement it, and escapes restore the saved local depth.

Do not merely adjust the generator’s top-level saved `:depth`: `py/try`, `py/call-ec`, and finally frames also retain depth snapshots ([prelude.cljc:136](/Users/sto/workspace/datomworld-py-safepoint2/src/cljc/yang/python/antlr/prelude.cljc:136), [prelude.cljc:167](/Users/sto/workspace/datomworld-py-safepoint2/src/cljc/yang/python/antlr/prelude.cljc:167)). Those snapshots must not reinstall an old base.

Count the executing generator body as one Python function frame; creating a suspended generator leaves no active body frame. Check the effective depth when resuming, even if execution reaches another yield without an intervening function call. An admission failure must leave the suspended generator and caller context intact.

**2. Regression contract and slice 4**

Replace the historical-depth assertion in [generator-depth-test](/Users/sto/workspace/datomworld-py-safepoint2/test/yang/python/antlr/safepoint_test.cljc:558). With limit 100, its existing program should print:

```text
78
99
100
78
100
```

The deep call has 21 `down` frames plus one generator frame, leaving 78 probe frames. A top-level resume has only the generator frame, leaving 99. Each crossing restores the caller, whose top-level probe still reaches 100. These are this profile’s explicit frame-counting results, not assertions about CPython’s internal overhead.

Run on all four VMs and three hosts, and add:

- Shallow-first, deep-second resumption, detecting undercounting.
- A `try` captured before yield, then resumed at another depth and exited by exception or return, detecting stale-base restoration.
- Nested generators/delegation, counting each active generator once.
- Rejected over-limit resumption followed by successful shallow resumption.
- `throw`/`close` and finally execution at the current resumption base.

**Slice 4 keeps whole-context thread swapping.** The distinction is between intra-activation escape restoration and switching execution owners. A scheduled-out thread retains its current base, local depth, handlers, and frame state; restoring that thread restores them all. Scheduling must not rebase it onto the scheduler or another thread. A generator explicitly resumed by another thread uses that new resumer’s depth. No VM continuation inspection is needed.

**3. Adjacent choices**

- **Confirm `RecursionError` beneath `RuntimeError` in the base prelude.** Its existence must not depend on enabling instrumentation. This matches the exception hierarchy. [Python exceptions](https://docs.python.org/3/library/exceptions.html#RecursionError).
- **Confirm task-local default 1000 and the stated basic validation:** nonpositive integers raise `ValueError`; non-integers raise `TypeError`; failures preserve the old limit. Bool follows Python integer-subtype treatment.
- **Complete the setter contract:** a positive limit at or below the current effective depth must raise `RecursionError` without changing the limit. The present setter omits that check ([safepoint.cljc:96](/Users/sto/workspace/datomworld-py-safepoint2/src/cljc/yang/python/antlr/safepoint.cljc:96)). Python explicitly rejects limits too low for the current depth. [Recursion-limit contract](https://docs.python.org/3/library/sys.html#sys.setrecursionlimit). Keep the limit outside escape-restored context so valid changes persist.

Read-only inspection; no files changed or tests executed.

**Ruling:** Require current-resumer depth with activation-relative escape snapshots; replace the 79/79 regression, preserve complete thread-context swaps, and retain the exception hierarchy/default limit while adding the current-depth setter check.