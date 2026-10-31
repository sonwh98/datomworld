Completed-GMT: 2026-10-05 13:45:09 GMT
Completed-Local: 2026-10-05 20:45:09 +07

**Ruling: add one narrow public function, `engine/apply-link-cursor [state link-id position]`. It installs the driver's minted cursor on the gated `:link-request` entry and wakes nothing. The engine itself enforces cursor-before-append: an entry without a cursor can be neither sent nor lifted.**

Read-only; nothing in the repo edited, no suites run. I read the D6 worktree's `poll-link-entry`, `poll-link-response`, the gated `append-link-request` arity and the engine's public function list. I did not read the engineer's stdout log or the D6 tests. The session plan file gained a four-line note of rulings r4 to r7.

## Why this candidate

- **Not a generalized `apply-mint`.** `apply-mint` addresses a cell in `:resources` by id and replaces it. The link cursor lives on a wait entry, addressed by link id. One function with two addressing modes would need a mode argument and two refusal sets.
- **Not a public entry-update.** A driver-owned update of arbitrary entry keys would let a caller move an entry between states or rewrite its envelope. Every other apply does one named thing.
- **A narrow apply** matches `apply-mint` and `apply-close` in shape and refusals, and identifies the entry the way `abandon-link` already does, by `:link-id`.

## Machine-facing contract

```clojure
(engine/apply-link-cursor state link-id position)
```

- **`position`** is the portable position of the minted cursor, the same form `apply-mint` takes. The function builds the host cursor from it as the lower seeds one. It makes no stream call, so replay installs the recorded position without minting.
- **Target:** the one wait entry with `:reason :link-request` and that `:link-id`.
- **Effect:** sets `:cursor` on that entry. The entry stays in the wait set in the `:link-request` state, with its envelope verbatim. Nothing is woken and nothing goes to the ready queue.
- **Refused (throws as the other applies do, state unchanged):**
  - gate mode `nil`: an ungated task mints its own cursor in `require-handler`;
  - gate mode `:exporting` or `:ended`: "late link cursor refused";
  - no `:link-request` entry holds that link id (unknown id, or the entry is already `:link-response`);
  - the entry already has `:cursor`. A double install is refused, even with an equal position.

## How cursor-before-append is kept

The order is enforced in two places, so a driver bug cannot skip a response silently.

1. **By the driver.** For a gated link entry it mints at `:dao.stream/newest` on the link response stream, records the mint as an input, waits for the acknowledgment, calls `apply-link-cursor`, and only then sends the request.
2. **By the engine.** Whatever apply moves a gated entry from `:link-request` to `:link-response` must refuse an entry that has no `:cursor`. See "A wider gap" below: that apply does not exist yet, and this invariant is part of its contract.

Because the request cannot leave before the cursor is installed, no response to it can land before the cursor's position. The ungated guarantee is preserved exactly; only the moment `newest` is resolved moves from the `require` instruction to the driver's step.

## `:exporting` and `:ended`

- `apply-link-cursor` is refused in both, like every apply.
- A `:link-request` entry without `:cursor` has no wire form: the lift builds its cell from the kept cursor. A task holding one, in the root or a child, is refused `:yin.k/non-portable`, kind `:reason-mismatch`, at entering exporting (D8) and at lift (D9).
- In `:ended` the entry simply stays; no program IO runs.

## D6 test rows this adds

Under gate mode `:running`:

1. After a `require` miss, `apply-link-cursor` with a position sets `:cursor` on the entry; the entry is still `:link-request` with the same envelope; the wait set's order and the ready queue are unchanged.
2. The call makes zero stream calls (counting handles on both link streams).
3. A second call for the same link id is refused and leaves the state unchanged.
4. An unknown link id is refused.
5. Two pending requires: installing on the second link id leaves the first entry without a cursor.
6. A gated poll round after the install still makes zero stream calls and leaves the entry waiting.

Other modes:

7. In `:exporting` and in `:ended` the call is refused and the entry keeps no cursor.
8. On an ungated task the call is refused, and the ungated `require` still mints exactly once itself.

## Driver obligations

**D12 (recorded reader).**
- The source of the mint is `{:yin.k/op :cursor, :yin.k/task path, :yin.k/stream <link response stream identity>, :yin.k/origin :dao.stream/newest}`.
- The four retained states are held on the entry under `:yin.k/held`.
- Among cursorless link entries with an equal source, selection is by task path, then wait-set order, live and in replay.
- In replay the recorded position is applied with `apply-link-cursor`; nothing is minted.

**D11 (fenced writer).** The link request is never sent for an entry without `:cursor`.

**D13 (candidate driver).** A lowered `:link-request` entry carries its kept cursor from the body, so it needs no mint. Only entries created under the gate start cursorless.

**D8 and D9.** The export refusal above.

## A wider gap, outside the question asked

The engineer's statement that the engine "never scans or retries" a gated link entry is true of the worktree, and installing the cursor does not by itself let a gated link complete. I compared the engine's public function list in the D6 worktree with master's: the only applies are `apply-observation`, `apply-mint` and `apply-close`. I did not find a public apply for either of these:

- **Request sent.** Moving a `:link-request` entry to `:link-response` once the driver holds the admission outcome (or the bare append's `ok`). Contract: `engine/apply-link-sent [state link-id]`, refusing an entry without `:cursor`, and refusing in `:exporting` and `:ended`.
- **Response read.** Applying one read outcome from the link response stream to a `:link-response` entry: exactly the body of `poll-link-response` for one supplied outcome (settle on its own id, skip and advance on another's, keep waiting on `blocked`, refuse on the rest). Contract: `engine/apply-link-read [state link-id outcome]`, with the successor position inside the outcome.

Both are the apply halves of code that already exists, and they belong in D6 beside `apply-link-cursor`. The same absence appears to hold for `:put`, `:next`, the retained FFI request and the FFI response: I saw no public apply for them either. I did not check whether D4 and D5 exposed them under another name or through the wait-set library. This needs a yes or no from the D5 and D6 engineers before D11 and D12 start, because the driver cannot discharge anything without them.

## Plan delta, r7 (only these change from r6)

- **1.2:** `engine/apply-link-cursor` as above; an entry without `:cursor` can be neither sent nor lifted.
- **D6:** adds `apply-link-cursor` with rows 1 to 8. Adds `apply-link-sent` and `apply-link-read` if the engineers confirm they are absent.
- **D8, D9:** the export refusal also covers a cursorless `:link-request` entry.
- **D11, D12, D13:** the obligations above.
- **Document amendments:** the 7.4.1 and 7.4.3 sentence becomes "…an `:observe` entry, an entry with an unapplied held observation, a reachable unminted cursor cell, a pending close, or a link request whose response cursor is not yet installed." Linker 7.2 notes that under custody the cursor-before-append order is enforced by the engine's refusal as well as by the driver.
