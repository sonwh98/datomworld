Completed-GMT: 2026-09-19 19:25:42 GMT
Completed-Local: 2026-09-20 02:25:42 +07

# Adversarial review: dao.stream.waitset Phase W1

**W1 is close, but I would not commit it yet.** The sweep matches the plan and the engine's sweep, and I found no P0 or P1. Two P2 items should be settled first. Both are small, and one is a test-only change. I edited nothing and did not rerun suites.

## What holds

- **Sweep order (check 1):**
  - One ordered pass, every entry polled every call, no budget and no scan position (`waitset.cljc:186-223`).
  - A still-waiting entry is retained with `(conj waiting entry)`, the stored value itself.
  - `woken-is-per-call…` parks a blocked entry ahead of a ready one and the ready one still wakes (test:414-431).
- **Never-augment (check 2):**
  - `resolved` never touches `entry`, and every result carries `entry` verbatim.
  - `:advance` runs only when a poll returned a truthy cursor, before the next entry resolves (201-202).
  - The shared-cursor and gap tests prove the write-back order (test:248-328).
- **Classification (check 3):**
  - Writer `closed` falls to the default branch and keeps its own keyword. It never becomes `:end` (112-115).
  - An undeclared keyword is terminal under its own keyword, for both operations.
  - The two literal maps are compared for equality with `outcomes-next` and `outcomes-append` (test:174-175).
  - The branches match `engine.cljc:295-312` one for one.
  - The one divergence, an unknown `:reason` waking with a diagnostic, is in the register.
- **Diagnostics (check 4):** no stream operation, no `:advance`, store unchanged, entry intact, entry leaves `:waiting` (209-220; test:355-401).
- **Probe (check 5):** the identity `:advance` is called exactly once, the store comes back equal, and a re-park wakes on `:b`, not `:a` (test:451-487).
- **Boundary (check 6):**
  - Nothing under `src/cljc/dao/stream*` is modified.
  - The namespace requires only `dao.stream`.
  - It holds no atom, `defonce`, clock, timer or reader conditional.

## Findings

**P2 | `waitset.cljc:77-78, 111, 194` | An uninterpretable answer can still escape as an exception, or wake with `:status nil`.**
- The divergence register states the rule in its own words: "An answer the library cannot interpret is terminal data, never an exception and never a wait" (plan:607).
- A nil outcome:
  - `next` or `append!` may return a non-map, or a map with no `:dao.stream/outcome`.
  - The entry then wakes as `{:status nil :value nil}`, which no host branch can name.
  - The test for undeclared outcomes covers only a rogue keyword.
- A host dispatch error:
  - `:resolve` may return a truthy `:stream` that does not satisfy the protocol, for example a stale store record.
  - `stream/next` then throws a host dispatch error.
  - That is the prior form the register row retires, and it is fixed only for nil.
- A lost sweep:
  - A throw from a handle or resolver mid-sweep escapes `check`, and the return value is lost.
  - Earlier `:put` entries have already appended, so threading the old waitset into the next call appends them again.
  - The library's own rule is "no caller may drop a returned `:waitset` or `:store`", and here the library drops it.
  - The engine has the same behaviour, so this is faithful to it. A library that serves several consumers needs the behaviour stated either way.
- Fix:
  - Wrap each answer in `stream/valid-outcome?` and fold an invalid one into a qualified terminal status such as `:dao.stream.waitset/invalid-answer`.
  - Either catch per-entry throws into a qualified terminal diagnostic, or state in the `check` docstring that resolvers and handles must not throw and that a throw voids the sweep.
  - Add one register row and one test for whichever choice is made.

**P2 | `test/dao/stream/waitset_test.cljc:138-143, 176-186` | No classification test pins the `:advance` call count per outcome.**
- `check-one` passes a throwaway `(atom 0)` as the advance counter, so nobody reads it.
- A regression that called `:advance` with a nil cursor would pass the whole suite. The likely cases are `end`, `cursor-mismatch`, `transport-error`, or a rogue outcome.
- Priority check 2 ("`:advance` invoked only when a poll returned a cursor") is pinned for the writer (test:284) and for the diagnostics, but not for the reader outcomes.
- Fix: have `check-one` return the counter. In the `:next` loop assert 1 advance for `ok` and `gap`, and 0 for every other outcome. The same goes for the rogue-outcome test.

**P3 | `waitset.cljc:45-46` | `park` is total but depends on `:waiting` already being a vector.**
- On `{}` or `{:waiting nil}`, `conj` builds a list.
- Later parks then prepend, and the wake order among co-waiters silently reverses.
- Fix: `(update waitset :waiting (fnil conj []) entry)`, plus one assertion.

**P3 | `waitset.cljc:110-111` | A `:put` resolution with no `:value` key appends `nil`.**
- The engine reads `(:datom entry)` in the same loose way, so this is faithful.
- Consider treating a missing `:value` key as `unresolved`, or note in the docstring that the transport is the one that answers `invalid-value`.

**P3 | test:377-388 | The "resolve without a stream" case never asserts the advance counter.**
- Add `(is (zero? @advances))` so it matches its two sibling cases.
