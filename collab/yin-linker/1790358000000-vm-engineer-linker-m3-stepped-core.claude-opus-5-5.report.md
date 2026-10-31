Completed-GMT: 2026-09-25 23:37:04 GMT
Completed-Local: 2026-09-26 06:37:04 +07
Coding-Agent: claude
Session-ID: a680556f-b767-4211-bafa-5e67257b844d

Note: these times are the host clock's. They are earlier than the brief's
Created stamp (2026-09-26 04:20 GMT), so the host clock or the brief's
stamp is off.

# Report: yin.vm.linker Milestone M3, stepped core and link runtime

Role: VM Runtime Engineer. Model: claude-opus-5-5.
Repository: /Users/sto/workspace/datomworld-ucf-phase2, branch ucf-phase2,
HEAD 19719d66. Nothing is committed, staged, or stashed. The earlier
stash entry was not touched.

## Lane counts

| Lane | Baseline (19719d66)        | Final                      |
|------|----------------------------|----------------------------|
| JVM  | 2,089 tests / 181,424 asrt | 2,098 tests / 181,640 asrt |
| Node | 2,002 tests / 48,286 asrt  | 2,011 tests / 48,483 asrt  |
| Dart | 1,964 passed               | 1,973 passed               |

Every lane has 0 failures and 0 errors. The +9 on each lane is the new
namespace. All nine step tests are listed by name in the Dart output, and
`Testing yin.vm.linker-step-test` appears in the Node output. I ran Dart
after `rm -rf test/cljd-out`, with no other lane running. kondo reports
0 errors and 0 warnings on all four files. cljstyle check is clean on all
four. Every added line is ASCII and at most 80 columns.

## Files changed

- `src/cljc/yin/vm/linker.cljc`
  - **New:** `link-state`, `request-link`, `step`, `abandon`, `verify`.
  - **Renamed:** `free-name-defect` is now `discharge`, the section 10
    export name. Its behaviour is unchanged.
  - **Rewritten:** `fetch` is now the blocking driver over a runtime.
    `trusted-fallback` and `verifying-fallback` now take a runtime
    instead of a handle plus H index.
  - **Removed:** `read-address`, `fetch-one` (now `checked-part`, which
    works on bytes) and `fetch-parts`. The worklist is now per-link state
    inside `step`.
  - `:unsupported-format` is added to `refusal-reasons`.
  - The namespace now requires `dao.stream.apply` and `dao.stream.rpc`.
- `test/yin/vm/linker_step_test.cljc` (NEW, 9 tests, per the file box).
- `test/yin/vm/linker_test.cljc`
  - Every `fetch` call now goes through a ring-buffer runtime, using new
    public helpers: `ring-handle`, `serve-all`, `local-runtime`,
    `fetch-local`, and `ws-runtime` (JVM only).
  - The fallback, transfer and WebSocket tests are migrated.
  - One test is re-specified; see deviation 3.
  - The fixtures the step test uses are made public.
- `test/yin/vm/content_test.cljc`: the seven `fetch` calls now use
  `lt/fetch-local`.

Nothing on the must-not-change list was touched: `image-hash`,
`register-hash`, `segment-key`, the opcode tables, `dao.stream` and
`dao.jing`.

## Design as built

- **The content pair is spoken over `dao.stream.rpc` directly.**
  `:jing/get-content` answers `{:found? b :value base64}`.
  - I did not use `dao.jing.remote.step`, because its completion decode
    hashes and decodes the payload itself. That would break the M2
    order: byte cap, then address check, then decode, then row check.
  - The linker takes the raw Base64 bytes and runs M2's `checked-part`
    on them in M2's order.
  - Section 6.2 already names `:rpc` as a `dao.stream.rpc` client, and
    section 9 allows `dao.stream.rpc` as the alternative.
- **State and admission.**
  - The state holds the rpc client, `:formats {kw record}`,
    `:indexes {kw index}` and bounds (defaults via `default-bounds`),
    plus per-link bookkeeping. It holds no content handle.
  - Admission order is: closed key set and portability, then a
    well-formed `[origin counter]` id, then `:unsupported-format`, then
    `:contract-mismatch`.
  - `request-link` sends nothing. It runs admission and index lookup and
    puts any refusal in the outbox for the next `step`.
- **Each link reads its parts in order, one content request at a time.**
  So the traffic, the get counts and the first-refusal order match M2
  exactly. Different links run in parallel on one pair and are
  correlated by rpc id.
- **`step` runs in the order section 6.3 gives:**
  1. retry the unsent envelope;
  2. poll up to `budget` elements;
  3. if the client is terminal, abandon the unsent envelope;
  4. route each response to its link and run step 2's checks, then steps
     3 to 5a as soon as the worklist empties;
  5. issue each link's next request (depth bound checked first);
  6. return the completions exactly once.
- **Transport failures fail closed as `:absent`,** as M2's failing
  handle did. That covers a lost request, an error response, a malformed
  envelope, bad Base64 and a terminal client.
- **Contract admission is unchanged.**
  - `fetch` never assigns a contract. With the contract omitted the
    result is `{:missing :contract}` `:invalid-request`; with another
    contract it is `:contract-mismatch` with M2's `:expected`/`:actual`
    evidence.
  - In both cases no content request is sent. The tests check this by
    counting gets and by reading the request ring.

## Tests added (linker_step_test.cljc)

1. `the-refusal-matrix-holds-through-step`
   - Covers all four formats, driven by `step` over ring buffers:
     `:ok`, `:invalid-request`, `:unsupported-format`,
     `:contract-mismatch`, `:absent` (step 1 and step 2),
     `:address-mismatch`, `:parts-limit`, `:hash-mismatch`,
     `:descriptor-defect` and `:use-before-definition`.
   - It also runs `discharge` (5b) on the completion's obligations:
     discharged against the full receiver, `:unresolved-free` against an
     empty one, `:shadowed-free` when the free env binds `+`.
2. `a-link-stays-pending-while-the-server-answers-one-part-per-step`
   - An AST tree; the server is advanced once per step.
   - No completion arrives for n steps, one more request appears each
     step, and the link completes at step n+1.
3. `a-request-carrying-a-function-or-a-handle-is-invalid-request`
   - Cases: a function identity, a handle identity, a function contract,
     an extra `:yin.link/index` key, and an extra `:yin.link/receiver`
     key. The refusal carries neither a function nor a handle.
   - Also: both name and identity, neither, and no format.
   - Also: a non-map request, a bad id and a function inside the id,
     each completing under nil.
   - Also: a duplicate id still in flight is refused under nil.
   - No refused request reaches the pair.
4. `a-request-by-name-has-no-name-environment-yet`: returns
   `:absent {:name ...}`. The name environment is M4 work.
5. `a-local-fetch-is-exactly-the-stepped-traffic-over-a-handle-free-state`
   (section 6.4), for all four formats:
   - the request and response rings are equal element for element;
   - the get-counting server handles show equal counts, one read per
     part;
   - no map in the `fetch` runtime's state carries `:get-bytes-fn`.
6. `the-dht-handle-behind-the-served-boundary-answers-a-link`
   - Four formats; a DHT handle sits behind `default-handlers` on ring
     buffers, and the test drives the server.
   - A forged peer gives `:absent`; a later honest peer gives `:ok`.
7. `abandon-completes-a-link-lost-exactly-once`
   - The link completes `:lost` once; the late answer completes
     nothing.
   - Abandoning an unknown id leaves the state unchanged.
8. `two-links-on-one-pair-each-complete-under-their-own-id`: a stack
   link and an AST link.
9. `an-addressed-instruction-stream-runs-under-its-stamp-or-is-refused`
   - A semantic vector linked by `step` loads under the record's "v3"
     contract and runs, returning 11.
   - Stamps "v2", "b2" and "r2" are `:contract-mismatch`, with an empty
     request ring.

The JVM WebSocket path (B6 criterion 1) is
`linker_test/images-transfer-over-the-websocket-transport`, migrated.
- `ws-runtime` takes the established rpc client of `connect-content!`,
  and `linker/fetch` steps it itself. The `serve-content!` thread serves
  the pair.
- The drive only yields, and throws after 10 s as a stall guard.
- It passes for all four formats.

## Acceptance-matrix rows covered

UCF section 7.11 currently has **no rows tagged M3**. I grepped the
section and the whole document; neither contains an M3 tag or a
code-identity row. I covered the brief's stated code-identity row
("the addressed instruction stream runs under its stamped contract or is
refused before load") with test 9. Linker section 11 criteria exercised:
- 1: one `step` and one `fetch` for four formats, with no `:format`
  branch;
- 2: no handle or function in a request, refusal or state;
- 5 and 6 through both `fetch` and `step`;
- 7 and 19: contract refused before any content request or validator;
- 8;
- 9: traffic equality, and no handle in the state;
- the step 0 part of 24: old stamps and an omitted contract.

## Deviations (for the reviewer to judge)

1. **`fetch` has a contract arity; `format` is a keyword.**
   - Section 6.4 lists `(fetch runtime format identity [receiver])`.
     M2's required contract needs somewhere to go, so there is a fifth
     argument `opts {:contract c}`.
   - The two spec arities pass no contract and so are `:invalid-request`.
     That is M2's own arity pattern, and section 6.3 says omission is
     invalid "as it already is for fetch".
   - `format` is the format keyword, the same as the request's
     `:yin.link/format`; the record comes from the state. The bounds live
     in the state (section 6.2), no longer in `fetch`'s opts.
2. **`:drive` advances the composition; `fetch` itself calls `step`.**
   - Section 6.4 says the drive "steps the linker's client side and
     whatever serves the content pair" and returns `state'`.
   - `step` returns its completions once and does not keep them in the
     state. So a drive that stepped the linker and returned only a state
     would drop the completion `fetch` is waiting for.
   - I kept the `(fn [state] state')` signature. The drive serves the
     pair (or, for WebSocket, yields); `fetch` then calls `step` and
     looks for its link's completion.
   - The alternative is a drive that returns the step result. That
     changes the stated signature, so I did not take it.
3. **A corrupted RPC reply is now `:address-mismatch`, not `:absent`.**
   - M2's test `corrupt-rpc-response-is-classified-absent` depended on
     `content-client` rejecting the reply before the linker saw it.
   - The linker is now the pair's client, so its own step-2 check sees
     the bytes and names the mismatch, the same as store-level
     corruption (I3: location is not identity).
   - The test is renamed `corrupt-rpc-response-is-an-address-mismatch`
     and now also asserts the address and that the bytes are not
     decoded. Store-level `:address-mismatch` is unchanged.
4. **`free-name-defect` is renamed to `discharge`,** with the same
   signature and a nil-or-refusal result. Section 10's export list names
   `discharge` and not the old name. One test call site is updated.
5. **A runtime serves one `fetch`.** `fetch` returns the outcome, not
   the successor state. The docstring says so. Callers that link again
   build a fresh runtime (the local one is cheap) or use `step`
   directly. The WebSocket test opens one connection per format for
   this reason.
6. **Shared test helpers.**
   - `linker_test.cljc` exports the runtime helpers, and the fixtures
     the step test needs are public. `linker_step_test` and
     `content_test` require `yin.vm.linker-test`.
   - There is precedent: `linker_test` already requires
     `yin.vm.debruijn-vm-contract-test`.
   - No new test-support file was added, which would have gone beyond
     the file box.
7. **Not strictly test-first.**
   - I migrated the M2 suite onto the runtime and wrote the stepped core
     before `linker_step_test`.
   - The step tests went red once, and the failure was in my test: an
     assertion that no traffic occurred sat after the duplicate-id case,
     which sends the admitted link's request. They then went green. I
     did not check them red against a missing implementation.
8. **Scope held back to M4** (the spec assigns these there): a
   `:yin.link/name` request answers `:absent` because there is no name
   environment yet. `:name-env`, `:authority`, `:derivation` and
   `:fallback` are not stored in `link-state`.

## Unrun or open checks

- No independent review has been done yet; the orchestrator plans one.
- The terminal-client path has no dedicated test: `issue-request` on a
  terminal rpc client, and `step` abandoning the unsent envelope.
  Neither is exercised directly. The `:pending-request` retry (a full
  writer) is also untested, because ring buffers evict and never answer
  full.
- In `fetch`, liveness is up to the drive. A drive whose server never
  answers loops forever unless the drive abandons the link or throws.
  This is documented in the docstring; there is no built-in deadline,
  because section 6.3 leaves deadlines to the composition.

Status: COMPLETE
