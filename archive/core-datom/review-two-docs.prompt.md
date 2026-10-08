Created-GMT: 2026-08-31 07:45:00 GMT
Created-Local: 2026-08-31 14:45:00 +07

# Role: Adversarial design reviewer — scoped clean-room review

## Scope, strictly

Read exactly two files:

- docs/design/dao.stream.md      — the contract (authority of the two)
- docs/design/dao.stream.ws.md   — the WebSocket transport spec, subordinate

**Do not read anything else.** Not the source tree, not the implementation
plan, not ADRs, not `datom.world.md`, not the `dao.space` documents, not
previous review logs. If a claim cannot be evaluated from these two documents,
say so and name what is missing — do not go find it.

The point of this constraint: earlier rounds pulled in the surrounding corpus
and the disagreements widened rather than converged. This round asks a
different question — **do these two documents hold together on their own
terms?** A specification a reader cannot evaluate without a corpus is itself a
finding.

## What to evaluate

1. **Internal coherence of the contract.** Does every section agree with every
   other? Look especially at: the nine invariants against the sections that
   claim to derive from them; the outcome tables against the prose that
   qualifies them; the IO Model's "no operation waits" against every operation;
   handle-relative semantics (Close says outcomes are handle-relative
   throughout — is that carried consistently?).

2. **Whether the subordinate spec conforms.** The ws spec may produce a subset
   of an operation's outcomes and must declare why. It may not produce an
   outcome the contract does not define, contradict the contract, or rely on
   something the contract never grants. Check both directions: the spec
   overreaching, and the spec depending on a contract guarantee that is not
   actually written.

3. **Completeness for an implementer.** Someone with only these two documents
   must be able to build the WebSocket transport and a conforming reader
   transport, and write a conformance suite. Name what they would have to
   invent.

## Three open questions, to be answered from these two documents alone

**Q1.** `attach!`'s `ok` row says "Attached to the existing logical stream."
The ws spec returns `ok` with "a handle on an attachment still being
established," and says how the connection resolved arrives later as data.
Is that conformant, is it a contradiction, or is the contract's row wrong?
If the row needs changing, give its exact replacement text. If instead a new
outcome is needed (for example a `pending`), say why the displaced-answer
mechanism the contract already describes does not suffice.

**Q2.** `append!`'s `ok` now reads "accepted at the next position in the
sequence this handle's writer surface is on," while Concurrency still says
"a successfully appended value occupies exactly one definite position in that
sequence" of the logical stream, and "whether an append landed before a close
is answered by the sequence itself." Are those consistent? Does the ws spec
ever state which sequence its writer surface is on, as the Writing section
requires? Give exact replacement text for whatever is wrong.

**Q3.** The ws spec mandates a deposited-event envelope
`{:dao.stream/attachment … :ws/event … :ws/value …}`. Judged only against the
contract: is a transport permitted to mandate the shape of values it appends
to a stream the host composed? Consider the contract's "Stream elements are
data. DaoStream assigns them no intrinsic meaning," its rule that result
keywords live under `:dao.stream/…`, and its treatment of transport-owned keys
in envelopes. Does the envelope conform, overreach, or fall outside what the
contract governs at all?

## Output

Findings ranked most severe first: severity | file:line | the claim | the
exact correction. Then your three answers. Then one section: **what an
implementer cannot determine from these two documents**, which is the part of
this review only this scoping can produce.

Be decisive. State plainly if you find nothing at a given severity.
Read-only. Do not edit files.
