Created-GMT: 2026-09-07 11:28:22 GMT
Created-Local: 2026-09-07 18:28:22 +07 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac
# Task: can dao.stream.observe be the common core of every stream interpreter?
Role: Adversarial Code Reviewer and Security Auditor
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-07 18:28:22 +07 | Status: active | Rationale: resumed session; it reviewed the extraction it is now asked to judge the limits of

P0 landed as `b2bf609`: `dao.stream.observe/step` with `forward` and
`yin.vm.stream-observer` refactored onto it, per your review.

The user asks a larger question: **can `observe/step` be the common core of
*all* stream interpreters in datom.world?**

## The orchestrator's provisional answer, which you should attack

I looked at the two other interpreters in `dao.stream` and think the answer
is "no, and for a principled reason". Both advance the cursor **before** the
effect, deliberately:

- `dao.stream.rpc/poll-read`, on a read `ok`:
  ```clojure
  ;; Advance before decoding. A malformed or unsolicited element is thereby
  ;; consumed exactly once and cannot poison polling.
  (handle-event (assoc state :cursor (:dao.stream/cursor read-result))
                (decode-value state (:dao.stream/value read-result)))
  ```
- `dao.stream.apply/serve-once!`, on a request it cannot correlate:
  "A malformed element with no usable id cannot be correlated, but it still
  advances exactly once and becomes a local diagnostic."

So I claim two families, both correct:

| family | ordering | why | risk accepted |
|--------|----------|-----|---------------|
| at-least-once — `forward`, the VM observer, DaoJing | effect, then advance | a failed effect must be retried; nothing is lost | a permanently-failing element blocks forever |
| at-most-once — `rpc/poll!`, `apply/serve-once!` | advance, then effect | a poison element is consumed exactly once | an element whose processing fails is gone |

`observe/step` hardcodes the first, and that is the whole value: the cursor is
assigned in exactly one place. Parameterising the ordering would destroy the
property that makes E5 structural rather than remembered.

I also suggested a smaller thing all five share — read, fold a malformed
answer into the algebra, classify totally over the seven outcomes — currently
reimplemented as `malformed-result` (now in the core),
`rpc/valid-operation-result`, and `apply`'s own read dispatch. But I argued
against extracting it now, on the grounds that doing so with no fourth caller
would repeat the mistake revision 8 made: proposing a shared namespace on
resemblance rather than use.

## What to judge

1. **Is the two-family split real?** Check `rpc/poll-read` and
   `apply/serve-once!` yourself. Is the at-most-once ordering genuinely
   required by their domains, or is it an accident that a shared core would
   improve? If a poison element is the reason, does `observe/step` have an
   answer that preserves both properties, or is the tradeoff irreducible?
2. **Is my claim that parameterising the ordering destroys the value correct**,
   or is there a formulation where one core serves both families without the
   guarantee becoming conditional?
3. **The smaller shared layer.** Is read-and-classify worth extracting on its
   own evidence — three existing reimplementations — or is my
   wait-for-a-fourth-caller argument right? Note that I have now twice pushed
   an extraction that a reviewer had to correct, so treat my caution as
   possibly overcorrection.
4. **Anything else that reads a stream with a cursor** that I have missed —
   `dao.runtime`'s wait-set polling, `dao.space.index`'s `snapshot-datoms`,
   `yin.io.file`, the DHT — and whether it changes the answer.
5. **What, if anything, belongs in the plan as a recorded boundary**, so the
   next person who notices the resemblance finds the reasoning rather than
   re-deriving it.

Answer directly. If the two-family split is wrong, say so plainly and say what
the right decomposition is. If it is right but the boundary belongs somewhere
other than where I drew it, say where.

Do not edit any file. You have no authority to run tests. Produce the complete
response in this run.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac
