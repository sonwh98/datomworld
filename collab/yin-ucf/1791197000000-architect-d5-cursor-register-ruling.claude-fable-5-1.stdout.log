Completed-GMT: 2026-10-05 09:54:32 GMT
Completed-Local: 2026-10-05 16:54:32 +07

**Ruling: do not park gated `:stream/cursor` at all. Under a gate the engine creates the cursor cell unminted and returns the reference at once; the driver mints it as a recorded observation held on the cell. No kernel changes for cursor, no register format change, and D6's gate-completeness claim stands on all four kernels.**

Read-only; nothing edited, no suites run. I read `boundary-opcodes`, `live-slot-indices`, the liveness pass and `continuation-payload` in the register code, `run-effect` in the register kernel, the compiler's `:stream-cursor` emit, and the contract stamp. I did not read the register validator's `:live-*` rules line by line or `register-restore`.

## 1. `semantic.cljc` and `ast_walker.cljc`

The two additions the engineer describes are the correct mechanical fix if a gated cursor were to park: pass `(call-park-entries seg pc E St K)` at the semantic site, and add the `{:k (:next k) :env env}` builder plus a `blocked?` branch at the walker site. Neither changes a format.

They are not adopted. Under the ruling in section 2 a gated cursor never blocks, so neither site needs a builder or a `blocked?` branch. D5 must not make these two edits.

## 2. `debruijn/register.cljc`

**Adding `:stream-cursor` to `boundary-opcodes` changes the code format. Rejected.**

- A boundary opcode carries an in-band `:live` operand at a fixed tuple index (`live-slot-indices`). The compiler emits `[:stream-cursor target-reg src-reg]` today, a three-element tuple; as a boundary opcode it would be `[:stream-cursor rd src live]`.
- That changes the opcode table's arity for the mnemonic, what the validator admits, the liveness pass's output, the encoded image bytes, and so every register image's address.
- Existing register images would fail validation under the new table, and new ones would fail under the old. That is a new contract, `"r3"`, not `"r2"`.
- The repository is development-only, so nothing deployed would break. It is still a stamp change that the UCF amendment explicitly kept out of scope ("code stamps v3/b2/r2 … stay unchanged"), and it is not needed.

**The alternative, for all four kernels: an unminted cell.**

A cursor's position is not visible to the program until it reads through it. So creating the cursor is internal computation; only the mint is an observation, and the mint can be the driver's.

**Machine-facing contract.**

- **Creation.** Under any gate mode, `:stream/cursor` in `handle-effect` does what it does now except the mint: it verifies the stream reference, takes the id, issues the sealed cursor reference, and installs at that id an unminted cell:

  ```clojure
  {:stream-id id
   :yin.k/unminted {:origin :dao.stream/oldest
                    :seq n}}          ; creation order, from the id counter
  ```

  It makes no handle call and returns the cursor reference, not blocked. The kernels see an ordinary immediate result, exactly as in an ungated run.
- **Reads on an unminted cell.** Under a gate `:stream/next` parks as its ordinary `:next` entry and `:stream/poll` as its `:observe` entry (the D4 ruling), without touching the cell. The driver may not observe a read on a cell until that cell is minted.
- **The driver's mint.** The driver mints through the handle, records it as a `:cursor` input, and after the acknowledgment calls the apply. The four retained states of plan 1.4 are held on the cell, under `:yin.k/held` beside `:yin.k/unminted`.
- **Apply.** `engine/apply-mint [state cell-id position]` replaces the cell with `(vm/cursor-entry stream-id cursor)`, the cursor seeded from the portable position as the lower seeds one, with no handle call. It refuses a cell that is not unminted, and refuses in `:exporting` and `:ended`.
- **Source (residual 1 unchanged).** `{:yin.k/op :cursor, :yin.k/task path, :yin.k/stream identity, :yin.k/origin :dao.stream/oldest}`. The resulting position is only in the observation.
- **Selection.** Among unminted cells with an equal source, the lowest `:seq` first, live and in replay.
- **Replay.** The recorded position is applied with `apply-mint`; nothing is minted live.
- **Ungated tasks** never contain an unminted cell; their cursor path is unchanged and still mints once at creation.

**This replaces the cursor half of the D4 ruling.** The `:observe` entry remains for `:poll` only. `engine/apply-observation` has no `:cursor` arm.

**One semantic difference, stated.** Under custody the origin is resolved at the driver's next step after creation, not at the instant of creation. If the stream evicts in that window, a program that would have read `gap` first instead reads the then-oldest value. Both are valid observations of the stream, and the recorded one is what every replay reproduces.

**Stop condition for D5.** If any engine path other than `handle-next`, `handle-poll` and the sweep reads a cursor cell's cursor while a gate is set, stop and report it; it needs the same guard.

**Consequences.**

- **Gate completeness at D6:** unchanged and now uniform. Cursor creation makes zero handle calls on every kernel with no kernel edit, so no kernel is an exception.
- **Export:** a task with any unminted cell reachable from the root or a child is refused, `:yin.k/non-portable`, kind `:reason-mismatch`, at entering exporting (D8) and at lift (D9). A body's cell requires a position, and an unminted cell has none.
- **UCF 7.4.1 and 7.4.3 wording:** "A task is not at a liftable safepoint while it holds an `:observe` entry, an entry with an unapplied held observation, or a reachable unminted cursor cell. No pending variant is added."

## 3. D5 zero-call rows for cursor

On each of the four kernels (semantic, de Bruijn stack, de Bruijn register, AST walker), under gate mode `:running`:

1. `:stream/cursor` makes zero handle calls, is not blocked, and the task continues to its next instruction.
2. The cell at the returned reference is unminted, with origin `:dao.stream/oldest` and a `:seq`.
3. Two cursors created on one stream have increasing `:seq`.
4. A `:stream/next` through an unminted cell parks as an ordinary `:next` entry with zero handle calls.
5. `apply-mint` with a position, followed by applying a read outcome, yields the same machine value as the ungated run given the same mint and read outcomes.
6. `apply-mint` makes zero handle calls.
7. A second `apply-mint` on the same cell is refused.

Once, on any kernel:

8. In `:exporting` and in `:ended`, `apply-mint` is refused and the cell stays unminted.
9. Ungated, `:stream/cursor` still makes exactly one mint call and installs a minted cell.

## Plan delta, r5 (only these change from r4)

- **1.2:** cursor creation under a gate installs an unminted cell and does not park.
- **1.4:** the held state of a `:cursor` observation lives on the cell; selection among equal sources is by `:seq`; the `:observe` entry is for `:poll` only.
- **D4:** unchanged from what is in flight (no cursor).
- **D5:** adds the unminted cell and `engine/apply-mint` in `engine.cljc`, with the rows above. No kernel is edited for cursor; the four kernels are still edited for the FFI `put-request` change only.
- **D8, D9:** the export refusal also covers a reachable unminted cell.
- **D12:** the driver mints unminted cells before observing reads on them, in `:seq` order; replay applies the recorded position.
- **Document amendments:** the 7.4.1 and 7.4.3 sentence above; the engine state doc gains `:yin.k/unminted`; UCF 7.7.5 notes that under custody a cursor's origin is resolved at the driver's first step after creation.
