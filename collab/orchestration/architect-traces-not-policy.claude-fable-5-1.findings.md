Warning: no stdin data received in 3s, proceeding without it. If piping from a slow command, redirect stdin explicitly: < /dev/null to skip, or wait longer.
Completed-GMT: 2026-09-07 12:15:55 GMT
Completed-Local: 2026-09-07 19:15:55 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f

# The core deposits traces; it interprets nothing

Short answer first: the reframing is right, with one sharpening that makes it structural rather than a preference. "Stop" is not a policy the core chooses; for an answer outside the contract there is no successor cursor, so the core *cannot* advance. What is left to decide is only where the record goes, and that is the stigmergic question. Today's four catch-alls do invent a meaning — they launder "I do not recognize this" into `transport-error`, a contract outcome that claims the transport *failed*, and then downstream readers cannot tell the two apart without digging for `:dao.stream/answer`. The fix is small, touches the P0 commit, and should be made now.

## 1. Is the reframing right?

Yes, and the reason is Axiom 2 applied to the interpreter's own failure. An answer outside the contract is still data — syntax with no semantics yet. `transport-error` is a semantics: "the transport failed to perform the read; nothing was observed" (`dao.stream.md`, Reading). A transport that answered `{:dao.stream/outcome :dao.stream/wholly-unexpected}` did not fail to perform a read; it performed one and reported it in a vocabulary this interpreter does not speak. Folding that into `transport-error` is the interpreter deciding what the unknown means, which is exactly what `datom.world.md`'s Host Boundaries forbids a depositing layer to do: "worth is not a property of the datum … filtering at the depositing layer destroys the perspectives that have not been taken."

Two things the reframing must keep distinct, or it overreaches:

- **An unrecognized *operation answer*** — the transport broke the contract (`:dao.stream/outcome` outside the closed set, a required key missing, or not a map at all). No successor exists. The step cannot advance. Deposit the trace; stop. **Not a policy.** The cursor law (an element's cursor advances exactly when its disposition is durably recorded) is satisfied vacuously: no element was observed, so there is nothing whose disposition is owed.
- **An unrecognized *value*** — a conforming `ok` read whose payload the interpreter cannot use. That is ordinary data (Axiom 2), and `rpc/poll-read` already handles it correctly: the effect classifies it as a diagnostic, answers `ok`, and the cursor advances. The trace is deposited *and* the element is consumed, because a total effect *did* record a disposition. This case is not what the four catch-alls are about, and must not be swept into "stop".

So the default for the first case is "trace and stop", for the second "trace and advance", and neither is a decision the core makes: the first follows from the missing successor, the second from the effect being total. The user's "log or alert" is the *reader's* semantics over the trace, not the core's default — which is the point.

## 2. What the trace contains, and must not

The trace is one plain-data map, qualified under the namespace that deposited it, carrying **facts only**:

```clojure
{:dao.stream/trace     :dao.stream/unrecognized-answer
 :dao.stream/operation :next            ; or :append! — which operation answered
 :dao.stream/answer    raw              ; exactly what came back, unmodified, however malformed
 :dao.stream/cursor    cursor           ; the cursor the operation was given — opaque, as data
 :dao.stream/identity  identity}        ; the logical-stream identity of the handle, via descriptor
```

`:dao.stream/identity` is included because `descriptor` is total by contract — it never fails and needs no surface — and it is the one fact that lets a reader correlate the trace with a stream without being told which interpreter deposited it. Everything else in the map is what the step already has in hand.

It must **not** contain: a contract outcome keyword standing in for the answer (that is the laundering being removed); a severity, category, or suggested action (that is a reader's interpretation — one reader's "fire alarm" is another's "test fixture did its job"); a host exception object or host handle (host types never cross onto a stream; the raw answer is retained as the value it was, and if the transport *threw*, that is a different trace whose `:dao.stream/answer` is the classified exception data, not the object); a timestamp or clock (the step has no clock — "now is passed in if the layer needs it at all" — the depositing composition adds time if it has it); and the effect function or the source handle themselves.

A trace built this way is readable by an alerting interpreter, an operator's dashboard, a conformance harness counting non-conforming transports, and a test asserting that a scripted defect surfaced — each with its own semantics, none needing the core to have chosen one.

## 3. Where it goes today

**The core deposits nothing itself, and that is correct.** `observe/step` is a pure function with no handle and no state. Handing it a writer would make the deposit destination the core's knowledge — the inversion Host Boundaries names ("which stream an adapter writes to is the caller's composition, never the adapter's knowledge") — and would give the core a second failure mode (the deposit answering `full` or `closed`). So the trace is **returned as data in the step's result**, and the composition above deposits it. For a pure step, returning the trace *is* the deposit into the only medium it has, the caller's state; the caller is a transform that carries it to the medium it was wired with. Today that means: `forward`'s trace reaches `serving`'s session state; the VM's reaches the REPL driver's state; DaoJing's reaches the observer's caller.

**On `rpc` and `apply`'s diagnostics vectors: yes, the same debt, and a worse form of it.** ADR-0003 rejected "one private ingress stream per endpoint, with its interpreter reading that stream" because "something must know both the producer and the consumer." A `:diagnostics` vector drained by `take-diagnostics` is that private ingress with one more defect: it is not a stream at all — no retention policy, no cursor, and the docstring's own warning that an undrained vector "would grow for the session's lifetime." The stream plan's ring-buffer exception at least deposits into a transport with declared retention and multiple cursors; the vectors deposit into an unbounded accumulator with exactly one reader. They are the same debt at one layer lower, and they close the same way: when `dao.space`'s writer face answers with data, the composition that today drains diagnostics to a console deposits them into the medium instead, and any reader that matches on `:dao.stream/trace` sees them.

The distinction that survives: the step's trace-as-return is **bounded by construction** — one trace per call, held in state the driver already threads — while the vectors accumulate across calls. The return value is not the debt; the vector's shape is.

## 4. Does "no policy parameter for the unknown case" survive the four interpreters?

It survives all four, and one of them already does it right.

- **`dao.runtime`** — already correct. `read-outcome->task`'s default branch resolves the task carrying the raw outcome as `:status` and `:value` (`v2.cljc:128-131`), and its test docstring states the principle. Only change: carry the whole raw answer, not `(:dao.stream/outcome result)`, which is `nil` for a non-map. No parameter.
- **`forward`** — `terminal-status`'s catch-all (`forward.cljc:42,47`) names an unrecognized answer `:transport-error`. It should name it what it is — `:source-unrecognized` / `:destination-unrecognized` — as terminal data carrying the trace. The cursor is kept, nothing is closed (its existing rule), and `serving` decides what to do with a forwarder that cannot continue, which is a disposition of the *connection*, not an interpretation of the answer. No parameter; its five tests pin no malformed case.
- **`yin.vm.stream-observer`** — already throws with the original answer preserved (`answered-outcome` unwraps `:dao.stream/answer`). It keeps throwing: that is the VM's policy for *all* defects, above the seam, and the throw carries the trace. No parameter.
- **`rpc/poll!` and `apply/serve-once!`** — the one place something genuinely *acts*: on an unrecognized read answer they go terminal and lose every outstanding request. That is not an interpretation of the answer; it is a consequence for their own state — whatever the answer means, this reader binding cannot continue, so requests awaiting it will never complete. What must change is the *reason*: today it is `:dao.stream/transport-error` (`rpc.cljc:124,420`), published into completions the driver reads as if the transport had said so. It should be the trace — reason `:dao.stream.rpc/unrecognized-answer` with the trace in the diagnostic — so the REPL reports "the transport answered something I do not recognize" rather than "transport error". No parameter.

So the testable consequence holds: no interpreter gains a `:on-unknown` option, and the four "human decides" tests the orchestrator proposed become unnecessary at the callers, because the step is the one place a new contract outcome arrives and the one place with a declaration-driven test that fails when `outcomes-next` grows. A new outcome reaches every caller as `:unrecognized` with its trace, simultaneously, until a human teaches the core what it means. Callers that dispatch on the step's status set — which is closed and tested — never see a raw contract keyword they were not written for. `forward`'s `terminal-status` and the VM's throw still dispatch on `:outcome` for the *known* defects, and that is fine: those keywords are the contract's, and the core guarantees `:outcome` is always one of them.

## 5. Where this belongs

The user's claim splits in two. "Interpreters do not differ in how they *observe*" is true and is already a consequence of the axioms — one contract, seven closed outcomes, one step — so it needs no new sentence; glm's finding and the P0 commit are its record. What the axioms do not yet say is what an interpreter does when observation hands it something outside its vocabulary, and that *is* axiom-level, because it is Axiom 2 turned on the interpreter itself and because the alternative — an `alert!` parameter, a severity, a default action — is direct coordination smuggled into the one layer that must not coordinate. I agree it belongs beside the six invariants. Draft, in their register:

> - Do not interpret what you cannot interpret: an interpreter that meets an answer or a value outside its vocabulary leaves it in the medium as a trace, exactly as it came, and takes no action on its behalf. What the trace means belongs to whoever reads it.

And one sentence in *Host Boundaries*, after "Host error types never cross the boundary. A transform function classifies them and emits a qualified value": *"Classifying is describing, not diagnosing: the qualified value says what was received and from where, never what it means or what should happen."* That closes the reading of "classifies" under which folding to `transport-error` looked compliant.

## 6. What it costs

**Change now** — small, and it corrects a landed commit while its author and reviewer are current:

- `dao.stream.observe`: `valid-or-transport-error` is deleted. A read answer that fails `valid-outcome?` returns `{:status :unrecognized :side :source :cursor c :read raw :trace {…}}`; an effect answer that fails it returns the same with `:side :effect` and `:effect raw`. The step's status set grows by one, and its declaration-driven test still pins that every contract outcome maps to exactly one *other* status. `observe_test`'s nine malformed-answer cases change their expected status; nothing else in the suite moves. The docstring paragraph currently uncommitted in the working tree stands and gains one sentence about the trace.
- `forward`: two new terminal statuses, `:source-unrecognized` and `:destination-unrecognized`, carrying `:trace`; the `:transport-error` catch-alls in `terminal-status` become dead and are removed. `serving` treats them as it treats every terminal status. Five tests unchanged.
- `yin.vm.stream-observer`: `answered-outcome` simplifies — the raw answer is `(:read observed)` directly. Seventeen tests unchanged.
- `rpc` and `apply`: `valid-operation-result` produces reason `:dao.stream.rpc/unrecognized-answer` (and `apply`'s terminal the analogous keyword) with the trace as the diagnostic; the outstanding requests are still lost. Their tests that script a malformed answer change one expected keyword; the REPL adapter maps the new reason where it mapped `transport-error`.
- `dao.runtime`: the default branch carries the raw answer. One expectation-table entry.
- `datom.world.md`: the invariant and the Host Boundaries sentence above, as a design-document change routed as such.
- The DaoJing plan's Decision 1: `:unrecognized` from the step becomes a fourth defect signal at the pool, reported with `:member` and `:result` like the other three — one row in the signal table.

**Do not change:**

- The core's signature. `step` takes a source, a cursor and an effect, and returns data. No deposit handle, no clock, no policy — the trace rides in the return value.
- The cursor law or E5. Unrecognized answers never advance; the effect-before-commit seam is untouched.
- ADR-0003's exception and its end conditions. Nothing here adds a medium or a reader; it only makes the traces that will one day be deposited *honest* before the medium exists.

**Wait:**

- Depositing traces into `dao.space` — until its writer face answers with data (ADR-0003's first end condition). Until then the composition carries the trace to whatever it has: the REPL prints it, `serving` records it in session state, DaoJing's caller decides. The trace's shape is fixed now so that nothing has to be re-encoded on that day.
- Converting `rpc`/`apply`'s diagnostics vectors into stream deposits — same gate. Recording that they are the ADR-0003 debt in a worse shape is enough for now; the drivers that drain them are the ones that will change.
- A shared trace vocabulary namespace — no. `:dao.stream/trace` on the map is the discriminator, and two keys of vocabulary do not justify a namespace; the moment a third depositor needs a *different* trace kind is the moment to look again.

One thing I would say against my own framing here, so it is on the record: this makes the step's status set eight-valued where the contract's outcome set is seven, and a reviewer could argue the step is now inventing vocabulary too. It is not — `:unrecognized` names the *absence* of a contract outcome, not a new one, and the raw answer travels with it unaltered — but the docstring should say that in one line, because the next reader will ask.
