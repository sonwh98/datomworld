Completed-GMT: 2026-10-05 09:42:48 GMT
Completed-Local: 2026-10-05 16:42:48 +07

**Both stops were correct. Ruling 1: the FFI append is gated in D5 and the link append in D6, each by a one-line change at the existing call site, and the gate is declared complete only at D6. Ruling 2: a held poll or cursor is a machine-only wait entry with reason `:observe`, which the sweep always skips and export always refuses.**

Read-only; nothing edited, no suites run. I read the engineer's report (in the `datomworld-d4` worktree; the path in the brief does not exist in the main tree), `module/append-link-request` and `require-handler`, the `:ffi-call` sites in `semantic.cljc` and `ast_walker.cljc`, `handle-stream-block`, and `observed-wire`. I did not open the two de Bruijn kernels' `:ffi-call` sites or any kernel's `:stream/next` park-entry builder; ruling 2 has one narrow stop condition for that.

## Ruling 1: the FFI and link request appends

**Decision.** Do not widen D4. D4 keeps the five effects that live in `engine.cljc`. The FFI append moves to D5 and the link append to D6, which are already the FFI round and the link round. Each is gated where the append happens today, by answering `full` without touching the handle.

**Why this is small.** Every one of these sites already has a `:dao.stream/full` branch that builds the retained entry (`:ffi-request` or `:link-request`) with the request kept verbatim. That is exactly the parked state the gate needs. So the gate only has to produce `full`.

**Machine-facing contract.**

- **The predicate.** `yin.vm/gate-mode`, a function of a machine value returning `nil`, `:running`, `:exporting` or `:ended` from `:yin.k/gate`. It lives in `yin.vm` because `engine`, `module` and `ffi` all already require that namespace, so there is no cycle. D4 adds it.
- **FFI (D5).** A new `yin.vm.ffi/put-request [state call-in request]`:
  - gate mode nil: calls `apply2/put-request!` as today;
  - any gate mode: returns `{:dao.stream/outcome :dao.stream/full}` and makes no handle call.
  
  Each of the four kernels replaces its one `apply2/put-request!` call at the `:ffi-call` site with this function. Nothing else at the site changes: `require-call-pair!`, the park and the call id are internal and stay.
- **Link (D6).** `module/append-link-request` takes the state (or its gate mode) and, when gated, returns the entry unchanged, which is its existing `full` result.
- **The link response cursor (D6).** `require-handler` mints a `:dao.stream/newest` cursor on the response stream before it appends. That mint is an observation. Under a gate it is not performed: the `:link-request` entry is built without `:cursor`. The driver mints it before the first send, as a recorded `:cursor` observation with origin `:dao.stream/newest`, and installs it on the entry through the public apply. The cursor-before-append order is kept, by the driver.

**Reconciling with 1.2 and the zero-call lists.** An ungated append is an unfenced effect, so the gate is not usable until every path is covered:

- No code outside tests may set `:yin.k/gate` before D6 lands. D8 and D11 to D13, the first slices that set it, already depend on D6.
- D6's zero-call test is the completeness gate. It runs the whole list on all four kernels.
- 1.2's sentence "every path gated before it observes" is a claim about the tree after D6, and the plan now says so.

## Ruling 2: held `:stream/poll` and `:stream/cursor`

**Decision.** Neither of the engineer's two options. Refusing poll and cursor under `:running` would make ordinary programs fail under custody, and applying with no parked record leaves nowhere to hold an observation across recording retries. The observation is held on a wait-set entry with a reason that exists only in the machine value.

**The entry.** Under gate mode `:running`, `:stream/poll` and `:stream/cursor` park with:

```clojure
{:reason :observe
 :op     :poll | :cursor
 :stream-id id            ; the verified stream resource
 :cursor-ref ref          ; :poll only, the cell it reads at
 :origin :dao.stream/oldest   ; :cursor only, the requested origin
 :id     cursor-id        ; :cursor only, see below
 ...                      ; the continuation registers, as any entry
 :yin.k/held {...}}       ; absent until the driver observes
```

- **Building it.** The engine builds it with the kernel's existing `:stream/next` park-entry builder, which captures the same continuation (resume after the call with a value), then sets `:reason :observe` and `:op` and adds the fields above.
- **Stop condition, narrow.** If a kernel's `:stream/next` builder cannot be given a `:stream/cursor` effect, stop and report which field it needs; do not invent a builder.
- **Cursor identity.** For `:cursor`, the id and the sealed cursor reference are minted at park time, as `handle-effect` does now. That is internal and deterministic. No handle is touched and no resource is installed yet.
- **`:yin.k/held`** is the driver's data: the state (observed, requested, acknowledged), the input sequence, the source and the exact portable observation. It is the same key the four retained states of 1.4 use on ordinary `:next` entries.

**What the sweep skips.** An `:observe` entry is never polled by the engine in any mode. `check-wait-set` adds it to the engine-polled class beside link and FFI-response entries, and that class is excluded from the ordinary sweep. Such an entry cannot exist in an ungated task, because ungated poll and cursor run immediately as today.

**How apply installs it.** One public function, `engine/apply-observation [state entry outcome]`:

- **`:poll`:** applies exactly what `handle-poll` applies for that outcome. `ok` advances the cell to the recorded successor and resumes with the value; `blocked` resumes with `:dao.stream/blocked` and leaves the cell; the rest as `handle-next`.
- **`:cursor`:** installs `(vm/cursor-entry stream-id cursor)` at the entry's `:id`, where the cursor is built from the recorded portable position by the same seeding the lower uses, with no mint on the handle. It resumes with the cursor reference.
- The entry is removed and its continuation goes to the ready queue through the engine's existing woken-entry path.
- In `:exporting` and `:ended` it refuses, like every apply.

**Reconciling with residual 1.** The `:cursor` entry carries the requested origin. The driver builds the source from the task path, the stream identity and that origin. The resulting position exists only in `:yin.k/held` and in the input record. Replay calls `apply-observation` with the recorded observation and never mints.

**Export.** `:observe` is not a wire reason and gets no variant. `observed-wire` keeps returning nil for it. Two checks, both `:yin.k/non-portable`, kind `:reason-mismatch`:

1. Entering exporting (D8) refuses a task that has, in the root or any child, an `:observe` entry or any entry carrying `:yin.k/held`.
2. Lift (D9) refuses the same before it reaches `lift-pending!`, so the existing fall-through to `:yin.k/undecodable` is never reached for these.

The second check matters because a `:next` entry with `:yin.k/held` classifies as a plain `:next` in `observed-wire` and would otherwise look liftable.

**Reconciling with 7.4.1 and 7.4.3.** The amendment reads: a task holding an `:observe` entry, or any entry with an unapplied held observation, is not at a liftable safepoint. No pending variant is added and the closed reason set on the wire is unchanged.

## Plan delta, r4 (only these change from r3)

**1.2, the gate.**
- Add: "The gate is complete when D6 lands. Before then nothing outside tests sets `:yin.k/gate`."
- Add: `yin.vm/gate-mode` is the one predicate; `yin.vm.ffi/put-request` and the gated `module/append-link-request` answer `full` without a handle call.
- Add: under a gate, `require-handler` does not mint the link response cursor; the driver mints it as a recorded `:cursor` observation with origin `:dao.stream/newest`.

**1.4, sources.** A `:cursor` source's origin may be `:dao.stream/oldest` (program cursor creation) or `:dao.stream/newest` (the link response cursor). Add the `:observe` entry and `engine/apply-observation` as the representation of held immediates.

**Slices.**

| Slice | r3 | r4 |
|---|---|---|
| D4 | Seven immediate effects in `engine.cljc` | Five: put, next, poll, cursor, close. Adds `yin.vm/gate-mode`, the `:observe` entry, the sweep skip and `apply-observation`. Files: `yin/vm/engine.cljc`, `yin/vm.cljc`, tests. |
| D5 | Sweep and FFI routing, `engine.cljc` | Adds `yin.vm.ffi/put-request` and the one-line change in `semantic.cljc`, `debruijn/stack.cljc`, `debruijn/register.cljc`, `ast_walker.cljc`. Files widen to those four and `yin/vm/ffi.cljc`. |
| D6 | Links, children, direct resume, `engine.cljc` | Adds the gated `append-link-request` and the deferred cursor mint. Files widen to `yin/vm/module.cljc`. Its test is the gate-completeness test. |
| D8 | Exporting | Adds export check 1 above. |
| D9 | Lift | Adds export check 2 above. |
| D12 | Recorded reader | The driver observes `:observe` entries and the link response cursor, and applies through `apply-observation`. |

**Zero-call lists.**
- **D4:** put, next, poll, cursor, close, under each gate mode. Plus: an `:observe` entry is skipped by the sweep; `apply-observation` for poll (`ok`, `blocked`, `gap`) and for cursor produces the same machine value as the ungated effect given the same outcome; applying a cursor makes zero mint calls.
- **D5:** adds the first attempt of an FFI call on each of the four kernels (zero `put-request!` calls, a retained `:ffi-request` entry identical to the `full` branch's), the retained request's retry, and a shared response reader.
- **D6:** adds a `require` miss (zero appends and zero cursor mints, a `:link-request` entry with no cursor), link-response scanning, child creation and advance, and direct resume. Then the whole D4 to D6 list again on all four kernels.

**Section 5, document amendments, added.**
- UCF 7.4.1 and 7.4.3: the liftable-safepoint sentence above, naming `:observe` as a machine-only reason.
- UCF 7.7.5: the gate answers `full` at the FFI and link request sites; the link response cursor is a recorded observation.
- Linker 7.2 (the require flow): under custody the cursor-before-append order is the driver's.
- The engine state doc: the `:observe` entry and the `:yin.k/held` key.

Nothing else in r3 changes.
