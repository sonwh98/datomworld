Completed-GMT: 2026-10-05 09:50:00 GMT
Completed-Local: 2026-10-05 16:50:00 +0700
Coding-Agent: claude (sonnet-5-5)

# D2: front `:yin.k/renewal` request

D2 is implemented and green on the JVM. The front now carries `{:yin.k/request :yin.k/renewal :yin.k/request-id r :dao.lease/lease l}` to the holder's lease-fact stream the same way it carries a release.

## Changed files
- `src/cljc/yin/vm/ucf/authority/front.cljc`:
  - `:yin.k/renewal` added to `required` with `[:dao.lease/lease]`, so it joins the closed `requests` set.
  - One `answer` case: `(carry! f author (lease/renewal (get r :dao.lease/lease)))`.
  - Docstring updated in three places: the request list, the `:carried` sentence, and the suspended sentence.
- `test/yin/vm/ucf/authority/front_test.cljc`.

Only these two files are edited. The six existing request kinds and the version-0 wire are untouched. `carry!`, the diagnostic family, attribution, the reply shape and the DaoStream outcome maps are reused unchanged, so there are no new keys. The request id stays opaque to the front.

## Tests
- **Carried and counted by the judge** (`a-renewal-is-carried-and-counted-by-the-judge`):
  - It answers `{:yin.k/status :carried}`.
  - The renewal fact `(lease/renewal l)` is the last record on the holder's lease medium.
  - After `grant/step!`, the judge's ledger entry for the lease has `:evidence :known`.
- **Unresolved author** (`an-unresolved-renewal-author-is-wrong-author`): the diagnostic is `:wrong-author`, with nothing carried and no reply. The claimed request is `{:yin.k/request :yin.k/renewal :yin.k/request-id "r-renew"}`.
- **Poisoned authority**: the renewal is added to the existing every-request-suspended test, which also asserts that nothing reaches the medium.
- **Malformed renewal**: a renewal with no lease is added to the existing `defective` list.
- **Closed set**: `the-request-set-is-closed` now includes `:yin.k/renewal`.

## Evidence
- **Red:** before the implementation, `clojure -M:test -n yin.vm.ucf.authority.front-test` reported 4 failures in 3 tests: `an-unresolved-renewal-author-is-wrong-author`, `the-request-set-is-closed`, and `a-renewal-is-carried-and-counted-by-the-judge` (two assertions). The output was piped through `tail -40`, and I did not check whether the poison and malformed additions also failed.
- **Green:** after the implementation, the same command reports 30 tests, 216 assertions, 0 failures, 0 errors.
- **kondo:** `clj -M:kondo --lint` on both files reports 0 errors and 0 warnings.
- **Not run:** the Node and Dart lanes and cljstyle, which are the orchestrator's.

## Concerns
- No `dao.lease` change was needed. The judge already applies a holder's renewal fact through its renewal handler: it refreshes `:last-observation` and sets `:evidence :known`. My "judge counts it" assertion checks `:evidence :known`. That evidence is already `:known` right after the grant, so on its own it does not prove the renewal moved the ledger. The carried-fact assertion is the stronger check that the renewal reached the judge's medium.
- I dropped a draft "not known before" assertion for the same reason. Say if you want a stricter ledger check, for example `:last-observation` advancing after a tick.

No git writes were made.
