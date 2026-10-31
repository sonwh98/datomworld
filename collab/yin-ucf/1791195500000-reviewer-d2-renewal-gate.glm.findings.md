Completed-GMT: 2026-10-05 09:49:40 GMT
Completed-Local: 2026-10-05 16:49:40 +07

# Gate review, M-next D2 — the front's `:yin.k/renewal` request

**Verdict: NOT READY** — one required change: the test that claims "the judge counts the carried renewal" does not pin it (finding 1), plus a trivial line-length reflow (finding 2). The production diff itself is correct as far as static reading can verify: the carriage path mirrors release exactly, the scope is exactly the two contracted files, and nothing existing changed shape. Fix finding 1 (and 2) and this lands.

Preliminary note: the engineer's report named in the gate prompt (`collab/1791193500000-compiler-engineer-ucf-d2-renewal.findings.md`) **does not exist** — only `...prompt.md` is on disk. There is therefore no engineer red/green evidence to check; I reviewed the diff directly (`git diff` on `ucf-d2-renewal`, two files: `src/cljc/yin/vm/ucf/authority/front.cljc`, `test/yin/vm/ucf/authority/front_test.cljc`), read-only, no suite run.

## Findings

**1. (Blocker) The judge-counting assertion is vacuous — the contract's fourth behavior is unpinned.**
`test/yin/vm/ucf/authority/front_test.cljc:385-387` asserts `(= :known (get-in j2 [:ledger l :evidence]))` after the second `grant/step!`. But the grant itself already sets that: the hook's grant in pass 1 (front_test.cljc:380) reaches `apply-authored` → `seed-lease` (`src/cljc/dao/lease.cljc:1192-1204`, `:evidence :known` at `dao/lease.cljc:958`). So `(get-in j [:ledger l :evidence])` is already `:known` **before** the renewal is sent. The engineer's own flagged concern is confirmed at the source level, and it is decisive:

- Deleting the `:dao.lease/renewal` case from `apply-valid-fact` (`dao/lease.cljc:1023-1031`) leaves this test green.
- A second pass that never drains the medium leaves it green (no gap fires on the memory-log medium, so nothing flips evidence to `:unknown`; with the single tick `{:s 1}` wired by the `judge` helper, `:now` does not move between passes — `dao/lease.cljc:1440-1441` — so `:last-observation` is also identical either way; and at now `{:s 1}` against duration `{:s 30}` the silence clause reclaims nothing, so the lease stays in the ledger regardless).
- The middle assertion (front_test.cljc:384, the medium's last fact equals `(lease/renewal l)`) pins only the front's carriage half — which the `:carried` reply assertion already covers. Nothing in the test distinguishes "judge counted it" from "judge ignored it".

Ruling on the question posed: **the carried-fact assertion does not carry the weight; a stricter assertion is required before landing.** The judge core's counting is already pinned non-vacuously in `dao.lease`'s own suite (`test/dao/lease_test.cljc:630-646`, via a moved tick and `:last-observation`), so what D2 must pin is only the link *front-carried fact → judge pass over the holder's medium counts it*. Two concrete forms, either suffices:

- **Registration (smallest):** after the counted renewal, `l` rides the medium's fact entry — `(is (some #(contains? (:leases %) l) (:facts j2)))`. This is produced *only* by a holder-authoritative counted fact (`process-fact-value`, `dao/lease.cljc:1100-1102`): the grant seeds no medium and the drained proposal registers nothing, so after pass 1 `l` is on no medium — the assertion is false exactly when the renewal was not counted. Optionally add the contrast `(is (not-any? #(contains? (:leases %) l) (:facts j)))` first.
- **Moved tick (the dao.lease house pattern):** expose the tick log from the `judge` helper (front_test.cljc:233-242 currently hides it), append `(lease/tick {:s 2})` before the second `grant/step!`, and assert `(= {:s 2} (get-in j2 [:ledger l :last-observation]))` — the seed stamped `{:s 1}`, so only a counted renewal produces `{:s 2}`.

The strictest form (gap → `:unknown` → counted renewal → `:known`) pins the evidence semantics too, but that is `dao.lease`'s contract, already covered there; not required for D2.

**2. (Must-fix, trivial) One line exceeds 80 columns.**
`src/cljc/yin/vm/ucf/authority/front.cljc:80` is 86 columns — "…lease-fact stream (a renewal likewise).  An admit that admission" was not reflowed after the insertion. It is the only >80 line in either file (both committed versions have none). Reflow the sentence.

## What was attacked and found clean

3. **Carriage mirrors proposal/release exactly.** `front.cljc:118` adds `:yin.k/renewal [:dao.lease/lease]` to `required` in the same shape as release; `front.cljc:246-247` dispatches to the *same* `carry!` with `(lease/renewal (get r :dao.lease/lease))` — byte-for-byte the release arm's shape. Resolver attribution is unchanged (`carry!` resolves the medium by the inbound-resolved author, `front.cljc:209-225`); the reply is the same `{:yin.k/status :carried}`; the suspended/refused/uncarried algebra is the untouched `carry!`; no new keys on any DaoStream outcome map. `reply-evidence`/`outcome?` (`front.cljc:322-327`) are generic over `requests`, so renewal replies are authority-attributable outcomes with zero code change — correctly not touched. The ns docstring is updated in all three places (request table `front.cljc:37-40`, replies paragraph, suspended sentence).
4. **Closed set, malformed list, poisoned, scope.** `requests` derives from `required` keys (`front.cljc:123-125`), so the set is closed with the new kind, and `the-request-set-is-closed` is updated (front_test.cljc:887-890). The malformed vector gains the missing-lease renewal (front_test.cljc:416) exercising `well-formed?` through `required`; the poisoned `doseq` gains the suspended case (front_test.cljc:727) with "nothing carried" still asserted (front_test.cljc:734). The unresolved-author test (front_test.cljc:390-397) mirrors the family: nothing carried, no reply, `:wrong-author`, and `:yin.k/claimed` exactly `select-keys` of request + request-id. `git diff` scope is exactly the two contracted files; no other file in the repo references `front/requests` or `:yin.k/renewal`.
5. **Version-0 wire and the six existing kinds untouched.** Every existing hunk in the diff is context-identical; `required`'s six existing entries and `answer`'s six existing arms are unchanged; no codec, envelope or version change. Adding a kind a version-0 driver never sends is additive on the authority's side only.
6. **No dao.lease edit needed or made.** `lease/renewal` (dao.lease.cljc:566-574) and the judge's renewal case pre-exist; the engineer's stop-and-report clause was correctly not triggered. `docs/design/dao.lease.md` *Carriage* already lists renewals among carried payload (line 293), so the doc is not made stale by this slice (the D-plan amendment to that section remains future work, as planned).
7. **Portability and style.** Both tests are pure-data `.cljc` with no reader conditionals (none needed), no floats or host-number traps (tick/duration maps are integers), ASCII only, and follow the file's existing helper conventions (`renewal-req` matches `release-req`; the new section carries the ruler header). The only style defect is finding 2.

## Required before landing

1. Replace the vacuous assertion at front_test.cljc:386 with a strict one (registration on the medium entry, or a moved tick plus `:last-observation`), per finding 1.
2. Reflow `front.cljc:80` to ≤80 columns.

Everything else may land as is.
