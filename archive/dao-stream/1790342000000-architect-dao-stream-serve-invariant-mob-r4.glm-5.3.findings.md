Completed-GMT: 2026-09-25 13:22:57 GMT
Completed-Local: 2026-09-25 20:22:57 +07

# dao.stream.serve invariant revision — round 4, sequenced final (GLM-5.3)

I re-verified codex's citations before answering, including the one that decides the attribution correction: `dao.stream.md:272-284` — attachment correlation is delivered to the composition through deposited events and result maps, and nothing in the contract inserts it into appended values. Codex caught two errors of mine and one insufficiency; I adopt all three on evidence, not to converge.

## 1. Q2 — same position in substance; adopt the wording with a two-word edit

Codex's option (b) — `:serve/hold` optional and additive, enabled per deployment, never a condition of meeting reachability or acceptance, proxy correct against an ignored flag, bounds and cancel-on-session-loss for honored holds — is my round-2 position stated more precisely: I said "defer… designed additive relief valve, enabled where a deployment class needs the coupling broken"; the operative content (additive, not a correctness dependency, bounded, session-scoped) is identical, and codex's "enabled per deployment" rightly replaces my vaguer enabling clause. Its replacement wording for edit 13 is better than mine and I **adopt it**, with one specific edit: insert "**off by default**" before "enabled per deployment" — the design's constructor-policy default is no holds (`findings.md:149`'s pattern: "the default is none, so nothing retries invisibly"), and the spec sentence should say the default, not just the permission.

## 2. The three corrections

**(i) Idempotence — ADOPT; my re-read discipline was an insufficient closure.** Codex is right that a missing announcement does not prove the append failed: the fact may not be written yet (delayed interpreter pass), may have been evicted by bounded announcement retention before the re-read, or the re-read itself may `gap` over the region — in each case my discipline either re-requests (doubling the pair) or waits ambiguously. Only the server side can close it: a stable request id, M-side deduplication across sessions for a stated retry horizon, one active pair per peer id, and a duplicate returning the same descriptors. This is OD-2's payload-correlation rule applied at the convention interpreter, and it mirrors the serve design's own last-append memoization (`findings.md:113-115`) lifted one scope wider. The stable id also becomes the lookup key for (iii). Keep my pre-append re-read as recovery guidance, exactly as codex's row 5 does.

**(ii) Privacy — ADOPT; my inbox-delivery alternative was privacy theater.** Codex's counter is correct and I verified it: the inbox descriptors are themselves announced as facts on the shared stream (revision:153, `:meet/inbox` carrying `:serve/in`/`:serve/out`), and a descriptor grants no authorization (`dao.stream.md:285-292`) — so anyone who can read the announcements can attach to the called peer's inbox and read the reflexive fact I proposed moving there. The readable set is unchanged; only the URL moved. The v1 text is the explicit public-address-disclosure statement; actual confidentiality requires access control or channel encryption, both correctly deferred with channel auth.

**(iii) Attribution — ADOPT; my item 3 contained a wrong mechanism detail.** Codex is right on the decisive point: serve *frames* carry `:serve/session` and the channel's medium carries the attachment identity, but the appended `{:meet/here <peer-id>}` value carries **neither** — `serve-step` appends the bare argument value, and `dao.stream.md:272-284` delivers correlation to the composition rather than inserting it into values. My phrase "both media carry it" was wrong; only one does. What survives of my item 3 is its normative core (explicit association; a bare peer-id claim supplies no address). Codex's mechanism is the right one: a serving-boundary observation recording, for each accepted meeting append, its value, actual serve session, and channel attachment — keyed by the stable request id from (i), so the interpreter reading the request stream can resolve each `{:meet/here}` to the attachment that delivered it before publishing `:meet/seen`.

## 3. Codex's final merged table, row by row

1. §3.1/§7.3 peer-id minting (own composition; ≥128-bit random or the key-hash form; neither authenticates until verification) — **AGREE** (matches my round-2 Q3 sentence).
2. §3.7/§7.3 optional-hold wording — **AGREE with the "off by default" insertion** (§1 above).
3. §5/§7.3 narrower served surfaces, M keeps complementary handles — **AGREE** (my item 9).
4. §6.2/§7.1 identity split plus "supply both meeting descriptors and both inbox-pair descriptors" — **AGREE**. I checked the addition against revision §7.1's candidate shape: it carries the announcement descriptor under `:serve/meet` but omits the **request-stream descriptor** a resolver needs in order to append `:meet/call` — a real omission, not gold-plating. "Bounded candidate resolution" matches the sequential-trial-plus-retry-budget policy (`findings.md:218, 194`).
5. §7.3 stable ids, M-side dedup, one active pair, duplicate returns the same descriptors, refusal/replacement for a conflicting active id, re-read as guidance — **AGREE** (adopted in (i)).
6. §7.3 serving-boundary observation (value + actual session + channel attachment, correlated by stable request id, before publishing `:meet/seen`) — **AGREE** (adopted in (iii)).
7. §7.3/§12 exposure statement, bounds on request work/inboxes/retention/fanout, observable refusal and gap recovery — **AGREE** (adopted in (ii); merges my retention-sizing and Q4 points).
8. §6.3/§7.4/criterion 6 same-socket punch, explicit-destination sends, nonce-bearing pong confirmation, restricted-cone test — **AGREE** (my items 1-2 plus codex's nonce hardening).
9. §6.4 outer gap as channel-frame loss, inner recovery without reporting final-source eviction, framing-aware UDP budget, "define fragmentation or an explicit portability limit for oversized inner values" — **AGREE**. The portability-limit clause subsumes my budget-accounting sentence and stays honest: it requires the limit to be *stated*, not fragmentation to be built (consistent with `findings.md:121, 193`).
10. §9/§13 pin M's inbox lifetime during migration acceptance; test dial-only pairs, bounded meeting work, announcement gaps, loss/reordering at both framing layers, fallback — **AGREE** (my items 11-12).

No disputes remain. Scorecard for the record: codex's round 3 improved my list in three places (idempotence closure, privacy honesty, attribution mechanism); my round-2 contributions that survive in the merged table are the punch-socket rule, the restricted-cone test requirement, the outer-gap/final-gap line co-authored with codex's sharper version, the identity-split hazard (cursor-mismatch), the retention-sizing and observable-refusal requirements, and the Q2 honest-costs sentence now expressed in codex's wording.

CONSENSUS: Q2, Q3, idempotence, privacy, attribution, table
OPEN: none
