Completed-GMT: 2026-11-01 09:03:29 GMT
Completed-Local: 2026-11-01 16:03:29 Asia/Ho_Chi_Minh
SIGN-OFF: confirmed. Scope: the four adoption commits ending at `b06dfd5f`. Conditions: final-tip Node verification and the required `bb test:sub dao.stream` gate; any red requires owning-seat diagnosis as pre-existing.

The rebased commits preserve the previously signed content. Both conflict resolutions are correct:

- Generated manifests pass independent `--check`: **156/154/155 files, 148 shared**, with `:profile-internal-pure`.
- Gate-test wording matches master; assertions are unchanged.

Independent JVM rerun: **143 tests / 1,486 assertions, zero failures or errors**. Oracle corpus unchanged. No tracked files modified.

Local `master` has advanced beyond rebase base `516f9594`; this confirms `b06dfd5f`, not a subsequent integration. Shiva Dart results remain user-reported; Node and the stream merge gate remain open.