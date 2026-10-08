Completed-GMT: 2026-10-05 10:04:14 GMT
Completed-Local: 2026-10-05 17:04:14 +07

**Ruling: a gated `:stream/close` does not park. The engine queues the close as data on the machine value and returns `nil` at once, as the ungated effect does; the driver performs or drops it in issue order. No kernel is edited, no register opcode changes, and the close's value never depended on its outcome.**

Read-only; nothing edited, no suites run. I re-read `handle-close` and its arm in `handle-effect` (`engine.cljc` 583-591, 2131-2133). I did not open the kernels' close sites; the ruling needs nothing from them.

## The fact the ruling rests on

The brief says a close's "outcome is the continuation's value". In the landed engine it is not:

- `handle-close` calls `close!` and discards the answer. Its docstring says `close!` is total over `{ok}` and wakes nothing.
- `handle-effect` then returns `:value nil, :blocked? false`, whatever happened.

So the program receives `nil` always. Nothing about the close is observed by the task, and there is nothing to hold for it or to replay to it. That is why no park entry, no builder and no boundary opcode are needed.

## Machine-facing contract

### At the site, per gate mode

- **Ungated:** unchanged. One `close!` call, value `nil`.
- **`:running`:** the engine verifies the stream reference as now, then appends one record to a vector on that machine value and returns `nil`, not blocked. Zero handle calls.

  ```clojure
  :yin.k/closes [{:stream-id id :yin.k/issue n} ...]
  ```

- **`:exporting` and `:ended`:** the task does not run, so the site is not reached. The D4 behavior (refused, no handle call) stays as the guard.

### Issue order

A close must not overtake a write that the program issued before it, and a write issued after it must meet the close.

- Each machine value carries a counter, `:yin.k/issued`. It is separate from the id counter, so fresh names and traces do not shift.
- Under a gate, `handle-effect` stamps `:yin.k/issue n` on every `:put` wait entry it parks (at the place it already adds `:datom`) and on every close record, and increments the counter.
- The counter and the stamps are machine-only. They never travel: export is refused while a close is pending (below), and a lower stamps restored `:put` entries afresh in wait order.

FFI and link request entries are not stamped. They target the engine's own call-out and link resources, which a program holds no stream reference to and so cannot close.

### The driver

For each machine value in the task tree, per stream, in `:yin.k/issue` order across that stream's waiting `:put` entries and its close records:

- A `:put` is discharged as plan 1.3 and 1.11 say, before any later close.
- A close is resolved by the stream's protection class:
  - `:at-least-once`: the driver calls `close!`. It is idempotent, so a regrant repeating it is harmless.
  - `:enrolled`: the driver does not close. Closing a target is the authority's act. It appends one diagnostic to the composition's diagnostic stream and resolves the record. Later writes to the target are admitted as usual.
  - `:fail-stop`: the run ends (plan 1.6).
- After resolving, the driver calls `engine/apply-close [state stream-id issue]`, which removes that record and nothing else. It refuses in `:exporting` and `:ended`.
- Reads on the stream are observed after the closes pending on it in that step. A reader then sees `end` or data from the handle, as it would ungated.
- A write issued after a performed close reaches the handle through the driver, gets `closed`, and that outcome is applied. This is the ungated result.

A close is not an input and is not fenced. Its outcome is fixed, so there is no record, no `k`, and nothing in the replay prefix. On a regrant the re-executed close is queued and resolved again.

In `:ended`, pending closes are dropped with the rest of program IO.

### This replaces r1's sentence

r1 1.2 said a close on an enrolled target "answers the shaped refusal value". The engine cannot see enrollment and the effect's value is `nil` in every case, so there is no refusal value. The program gets `nil`; the refusal is the driver's diagnostic.

## Kernel edits

None, on any of the four kernels. The site returns an ordinary immediate result. `:stream-close` stays out of `boundary-opcodes`; the register contract stays `"r2"`.

## Cost

- One vector and one counter on a gated machine value, and one stamp on gated `:put` entries.
- One behavior that differs from an ungated run: on an enrolled target the close has no effect on the stream. That is the intended rule, and it is the same before and after a handoff.
- Between the program's close and the driver's step, the real stream is still open to other writers. Ungated, it would already be closed. Under custody every effect is deferred to the driver in this way.

## D5 zero-call rows for close

Under gate mode `:running`, on each of the four kernels:

1. `:stream/close` makes zero handle calls, is not blocked, returns `nil`, and the task continues.
2. `:yin.k/closes` gains one record with the verified stream id and an issue number.

Once, on any kernel:

3. A `:put` parked before a close has a lower issue number than the close; one parked after has a higher one.
4. `apply-close` removes exactly the named record and makes zero handle calls.
5. `apply-close` is refused in `:exporting` and in `:ended`.
6. A close with a forged or foreign stream reference fails as it does ungated, and queues nothing.
7. Ungated, `:stream/close` still makes exactly one `close!` call and leaves no `:yin.k/closes` key.

The driver rows (class behavior, ordering against writes and reads, the diagnostic on an enrolled target) belong to D11 and D12.

## Export consequences

- A pending close has no wire form. A task with a non-empty `:yin.k/closes` in the root or any child is refused, `:yin.k/non-portable`, kind `:reason-mismatch`, at entering exporting (D8) and at lift (D9).
- The 7.4.1 and 7.4.3 sentence becomes: "A task is not at a liftable safepoint while it holds an `:observe` entry, an entry with an unapplied held observation, a reachable unminted cursor cell, or a pending close. No pending variant is added."

## Plan delta, r6 (only these change from r5)

- **1.2:** a gated close queues a record and returns `nil`; `:yin.k/issued` and the issue stamp on gated `:put` entries.
- **1.11:** the per-class close rule above.
- **D5:** adds the close queue, the issue stamp and `engine/apply-close` in `engine.cljc`, with rows 1 to 7. D4's "`:running` close still calls `close!`" is replaced here.
- **D8, D9:** the export refusal also covers a pending close.
- **D10:** lower stamps restored `:put` entries in wait order.
- **D11:** the driver discharges writes and resolves closes per stream in issue order, with the diagnostic on an enrolled target.
- **D12:** reads on a stream follow the closes pending on it in the same step.
- **Document amendments:** the 7.4.1 and 7.4.3 sentence above; UCF 7.7.5 gains the close rule per protection class; the engine state doc gains `:yin.k/closes`, `:yin.k/issued` and `:yin.k/issue`.
