Completed-GMT: 2026-09-26 00:09:37 GMT
Completed-Local: 2026-09-26 07:09:37 +07
Coding-Agent: claude
Session-ID: resume-of-a680556f-b767-4211-bafa-5e67257b844d

# Report: M3 fix round 1 (the small items from the DeepSeek gate)

Role: VM Runtime Engineer. Model: claude-opus-5-5.
Repository: /Users/sto/workspace/datomworld-ucf-phase2, branch ucf-phase2,
HEAD 19719d66. Nothing is committed, staged, reset, stashed, or merged.
No fetch deadline or abort hook was added.

## Lane counts

Each lane was run on its own, in order: JVM, then Node, then Dart (after
`rm -rf test/cljd-out`). Every lane has 0 failures and 0 errors.

| Lane | Previous M3 figure | Now                       |
|------|--------------------|---------------------------|
| JVM  | 2,098 / 181,640    | 2,102 tests / 181,666 asrt|
| Node | 2,011 / 48,483     | 2,015 tests / 48,507 asrt |
| Dart | 1,973 passed       | 1,977 passed              |

- The +4 tests on each lane are the four new step tests. All 13 step
  tests are listed by name in the Dart output.
- I ran Dart twice: the first run's filter dropped the count line. Both
  runs passed.
- kondo reports 0 errors and 0 warnings on all four changed Clojure
  files. cljstyle check is clean. `git diff HEAD --check` is clean.
- `git diff --check` does not cover the untracked step test, so I also
  scanned every added line, the step test included. All are ASCII, at
  most 80 columns, with no trailing whitespace and no em dashes.

## Item 1 (P2): the section 6.4 drive sentence

- **The fix:** `docs/design/yin.vm.linker.md` section 6.4 now says what
  the code does:
  - the drive advances whatever serves the content pair and returns the
    link state;
  - it does not step the linker; `fetch` calls `step` after each drive
    until the one link completes;
  - the reason is that `step` hands each completion out exactly once, so
    a drive that stepped the linker would drop the completion `fetch`
    waits for;
  - liveness is the drive's, stated as the current stance.
- **Other places checked:**
  - Section 6.3 describes only `step` and does not describe the drive.
  - Section 6.1 ("driven by whoever drives the server today") and the
    section 9 M3 paragraph ("the blocking driver over a link runtime")
    agree with the code.
  - No other sentence repeats the wrong description, and `fetch`'s own
    docstring was already accurate.
- **Left alone:** the 6.4 signature block still lists only the 3- and
  4-argument arities; the contract-carrying fifth argument is not shown.
  That is outside this item.

## Item 2: Base64 text cap before decoding

- **Test first:** `an-oversize-base64-reply-is-refused-before-it-is-decoded`
  (linker_step_test). Its first assertion failed on the old code with
  `:absent`, which proved the text was being decoded. It passes now.
  - The test's server answers with 400 characters that are not Base64 at
    all. Decoding them would fail closed as `:absent`.
  - With `:max-bytes 64` the reply is now `:parts-limit` with
    `:bound :max-bytes` and the address. Only a reply that was never
    decoded can be refused this way, so the text serves as the
    "throwing decoder" the brief allowed.
  - The same text under the default bounds is `:absent`, which pins the
    decode path.
  - A real reply at exactly its decoded length passes as `:ok`.
  - The same reply one byte under the limit is `:parts-limit` with the
    same evidence.
- **The change in `linker.cljc`:**
  - `answered-bytes` is now `answered-text`. It returns the Base64 reply
    text (a string) or `missing`, and decodes nothing.
  - `checked-part` now takes the text:
    1. no reply gives `:absent`;
    2. the text bound gives `:parts-limit`;
    3. text that is not strict Base64 gives `:absent`;
    4. the existing decoded-length cap gives `:parts-limit`;
    5. the M2 steps follow unchanged: address check, payload decode, row
       check.
  - The refusal shape is identical to the existing byte cap:
    `{:bound :max-bytes :address a}`.
- **The bound used is a lower bound, not the brief's ceiling.**
  - The text bound refuses only when `3*floor(n/4) - 2` exceeds the
    remaining budget. That is the fewest bytes strict padded Base64 of
    n characters can decode to.
  - The brief's `ceil(3n/4)` is an upper bound, and refusing on it would
    wrongly refuse a reply that is exactly at the limit. A 64-byte
    payload is 88 characters, and ceil(3*88/4) is 66.
  - With the lower bound, a reply the text bound lets through is decoded
    and meets the exact decoded-length cap. The at-limit assertion pins
    this.
- **How this orders against the strict-Base64 `:absent` path:** the text
  bound runs before the strict decode. So oversize text is
  `:parts-limit` whether or not it is valid Base64, and text within the
  bound that is not strict Base64 is `:absent`. Each reply therefore has
  exactly one refusal, and its text is never decoded when the text bound
  refuses it.

## Item 3 (P3): admission order

- **Test first:** `the-format-record-is-admitted-before-the-contract`.
  It failed on the old code, where a contract-less unknown format read
  `:invalid-request {:missing :contract}`. It passes now.
- **The change:** in `request-defect`, `:unsupported-format` now comes
  before the two contract checks. The docstring states the order.
- **The test also pins that the rest is unchanged:**
  - an unknown format with a wrong contract is `:unsupported-format`;
  - a known format without a contract is still
    `:invalid-request {:missing :contract}`;
  - a shape defect (`:exactly-one-of`) still precedes the format check.
- Every other admission check keeps its order: the closed key set,
  portability, the format key, exactly one of name or identity, then
  (now) the format record, then the contract, then contract equality.
  M2's own contract refusals are unchanged; the migrated M2 tests pass.

## Item 4 (P3): the two fail-closed paths

No production code was changed for this item; both paths could be
exercised as they are.
- **(a) `a-terminal-client-fails-every-owed-read-closed`**
  - A `:dao.stream.apply/ended` lifecycle value is appended to the
    response ring, so polling makes the rpc client terminal.
  - A read not yet issued reaches `issue-request`'s terminal branch and
    completes `:absent {:address a}`. Nothing is written to the request
    ring.
  - A read already on the wire is lost by the medium and also completes
    `:absent`.
- **(b) `a-full-writer-retries-the-one-envelope-it-owes`**
  - A small test-only stub writer answers `:dao.stream/full` twice, then
    passes appends to a ring.
  - After steps 1 and 2 the envelope is retained unsent, nothing reaches
    the medium, and the second link is not issued.
  - Step 3 sends the retried envelope, which keeps id 0, and the second
    link follows as id 1.
  - After the server runs, both links complete `:ok` under their own ids.
  - This test first failed because of my own error: it read the ring
    after all four steps had run. It now snapshots the ring after each
    step.

## Files changed in this round

- `src/cljc/yin/vm/linker.cljc`:
  - `answered-bytes` is replaced by `answered-text`;
  - new private helpers `least-decoded-length` and `text-bytes`;
  - `checked-part` takes the reply text and applies the text bound;
  - `receive-part` and `route-completion` are rewired to the text;
  - `request-defect` is reordered and its docstring updated.
- `test/yin/vm/linker_step_test.cljc`: four new tests and two private
  helpers (`replying`, `decoded-length`).
- `docs/design/yin.vm.linker.md`: the section 6.4 drive sentence.
- `linker_test.cljc` and `content_test.cljc` were not changed in this
  round.

## Left, and scratch removed

- Left: the section 6.4 signature block (see item 1). No fetch deadline
  or abort hook was added; that decision is the owner's.
- Scratch removed: `.m3fix/check.py`, my width/ASCII scanner inside the
  repo. No other scratch files exist; `git status` shows only the five
  M3 paths.

Status: COMPLETE
