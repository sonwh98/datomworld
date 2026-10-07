Completed-GMT: 2026-09-25 12:06:45 GMT
Completed-Local: 2026-09-25 19:06:45 +07

# dao.stream.serve Q1–Q7 — independent round-1 answers (GLM-5.3)

Design under review: `collab/1790335900000-architect-dao-stream-serving-spec.claude-fable-5-1.findings.md` (cited below as "design §x / findings.md:line"). I reviewed it against the contract and the UCF without assuming Fable is right. Verdict up front: **6 of 7 recommendations stand (two with corrections); Q2's does not.**

---

**Q1 | position: keep the as-designed event-medium design, amend UCF §7.4.3 to resume `:put` on the observed source outcome (Fable's recommendation, strengthened) | confidence: high**

The alternative is not merely "a proxy-level convention" — it is contract-forbidden. The contract's Writing section: "The writer decides what to do about any non-`ok` outcome; DaoStream does not retry, buffer, or notify on its behalf" (`docs/design/dao.stream.md:578-579`). A proxy that answers `full` while holding the value is buffering on the writer's behalf after refusing it; and `full` is defined as "does not accept the value; **nothing was appended**" (`dao.stream.md:565`) — a held value is the opposite. This is exactly the misdescription OD-1 warns about ("`full` for throttled", `dao.stream.md:828-830`). The as-designed behavior is what the contract already blesses: the writer surface is the ordered outbound path and "what becomes of the value at the far end is reported there, not here… an answer from the far end cannot arrive before `append!` returns" (`dao.stream.md:570-576`; ws precedent `docs/design/dao.stream.ws.md:47-51`). Fable's UCF amendment is OD-2's resolution sentence 3 applied verbatim ("a transport whose acceptance is not its final answer… declares the channel on which that answer is deposited", `dao.stream.md:887-890`), and it generalizes without changing local-stream behavior (there the return IS the source outcome).

**Fable's recommendation should stand**, with one gap it must close: the amendment names only "resume when the source's outcome is observed." It must also name the case where no outcome ever arrives — session loss with an append in flight (`:serve/append-unknown`, findings.md:115). There the wait is not discharged: the engine either re-establishes it from the retained value after re-attach or refuses; it must not resume on outbound `ok` retroactively. One sentence in §7.4.3.

**Dependency: not independent — gated by Q5.** The amendment's legal basis is OD-2 (accepted), and `append-unknown` is OD-2's third state named honestly; without OD-2 the design's §3.4 is dishonest (findings.md:282).

---

**Q2 | position: named alternative — defer held reads out of v1; keep §3.7 as the additive design, send no `:serve/hold` in v1 | confidence: medium**

Fable's rationale conflates two cadences. A parked VM polls the **proxy's local window** — "`next` is a read of the proxy's window… a miss records a demand and answers `blocked`" (findings.md:137) — so the reader's polling is host-local and free. Network chatter is set by `proxy-step`'s retry policy (`:retry-after-ms`, findings.md:194), which the composition already owns. Holds don't remove the retry machinery either: on a best-effort channel the held answer itself can be lost, so the proxy re-sends until answered regardless. What holds buy is a better latency/bandwidth tradeoff, i.e. an optimization — and one whose costs are real: the only server behavior that is not a pure reaction to an inbound frame (a per-hold re-poll loop plus a hold budget — a second clock-domain policy beside retry/reconnect), per-session held state with its own session-loss semantics, and a wire feature to test on four hosts. The design's own completion criteria test none of it (findings.md:286-296), and both tolerance directions are already mandated ("a proxy must tolerate a server that ignores the flag", findings.md:131) — which is precisely the tolerance that makes later addition cheap. Nothing in the UCF requires it: §7.4.3's blocked read is a local cell poll, and the M4 facade acceptance has no latency condition (`yin.vm.universal-continuation-format.md:1451-1466`).

**Fable's recommendation (include) should not stand** as a v1 commitment. Independent of the other questions.

---

**Q3 | position: opaque composition-assigned strings now; self-certifying later (Fable's recommendation) | confidence: high**

"Self-certifying from the start" is incoherent as a half-measure on this channel: signing `:serve/register` alone authenticates registration while every later frame stays forgeable and readable on an unencrypted UDP channel ("UDP has none until a Noise-shaped channel exists", findings.md:284). Done properly it means per-frame auth/encryption — that is the Noise-shaped channel, already and correctly deferred. The relay routes by string equality either way (relay state `{:peers {peer-id → channel-writer}}`, findings.md:226), so kickoff-hash identities slot in as an additive rule on `:serve/register` (`docs/design/dao.stream.discovery.md:70-80`, findings.md:284). The contract also declines gating at this layer (`dao.stream.md:285-292`).

**Fable's recommendation should stand**, with a spec gap to close: duplicate or contested `:serve/register` (two peers claiming one id) is undefined in §7.3. Name it — a relay refuses or evicts per composition policy — otherwise peer-id squatting is undefined behavior rather than a declared policy. Independent of the other questions.

---

**Q4 | position: accept any `:serve/register`/`:serve/dial` in v1, postage as the intended later answer (Fable's recommendation) | confidence: high**

The contract has already answered the gating question for descriptors — "Nothing in this contract gates who may attach… it arrives as an addition" (`dao.stream.md:285-292`) — and the discovery doc has already accepted postage-in-principle ("Paying to deposit… **Accepted**… it needs a *cost*, not a *market*", `dao.stream.discovery.md:158-162`) while leaving its design unspecified (`dao.stream.discovery.md:231-233`), so postage cannot be v1 anyway. Dev-only repo, trusted networks for UDP (findings.md:284). Recording postage as the intended answer is the only posture consistent with both documents.

**Fable's recommendation should stand**, with one correction of emphasis: ungated must not mean unbounded. §7.3 gives links and peer-table entries no lifecycle — link GC on channel end, idle bounds, caps as composition data — so acceptance criterion 5 ("the relay's state never holds a session", findings.md:292) can pass while the relay leaks link entries forever under a dial flood. Bounded-state rules are not gating; the spec should carry them regardless. Independent of the other questions.

---

**Q5 | position: accept OD-1, OD-2, and OD-3 (a)+(2) now (Fable's recommendation, with the OD-1 rationale corrected) | confidence: high**

All three survive adversarial reading on their own merits, not just the design's convenience. OD-3(a) is how the code already behaves (`dao.stream.md:926-929`); (b) would commit every future transport now. OD-3(2) is required by the UCF independently of serve — blocker "Pending-state completeness… depends on a generated stream-over-network facade and a **portable cursor profile**" (`yin.vm.universal-continuation-format.md:1330-1334`); both existing transports already satisfy it (`dao.stream.md:942-943`). OD-2 is already half-true via ws (`dao.stream.md:858-861`) and is unavoidable once a proxy holds an append across a session.

Correction: Fable says "OD-1 is needed only for the `:establishing` answer." That understates it. The serve layer relays source outcome maps **verbatim** (findings.md:103), so a source on a newer contract will put outcomes into an older consumer, and today's consumers fault: "`yin.vm.engine/handle-put` and `handle-next` **throw**" (`dao.stream.md:818-821`). Over serve that is a remotely triggerable crash. OD-1's unrecognized-outcome rule (not the `:retry?` key, which the open-map rule `dao.stream.md:109-112` already permits) is the load-bearing fix; `:establishing` is a minor consumer. **Fable's recommendation should stand.**

**Dependency: this is the gate for everything else** — the design says so itself (findings.md:282), and Q1's amendment presumes OD-2. If the owner accepts only one thing from this round, it is Q5.

---

**Q6 | position: lease-governed as the decided rule, composition-retired as the v1 stopgap, lease integration as the immediate follow-on (Fable's direction, phasing amended) | confidence: high on direction, medium on phasing**

Composition-retired has no release signal: the exporter never learns the resumer is finished (a session close is not one — kept cursors resume through fresh attachments, `dao.stream.md:610-611`), so "until its own composition retires it" (findings.md:264) means serve-forever. The lease supplies exactly the missing signal: holder renews while it still needs the stream, releases when done, lapse unserves (`docs/design/dao.lease.md:159-176`, `213-214`). Two facts make this cheaper than Fable implies: `dao.lease` is **already implemented** (`src/cljc/dao/lease.cljc`: fact vocabulary, judge, holder disciplines, `make-judge`/`make-holder`), so this is composition, not design; and the model fits — the judge is "composed inside the boundary that possesses the resource" (`dao.lease.md:112-117`), which is the serving host; serve sessions carry an attachment identity usable by the attribution resolver (`dao.lease.md:237-241`); unserving gates attachment, not retention or history, so the prohibition at `dao.lease.md:94-95` is not touched; and `not-found` after unserving is contract-honest (`dao.stream.md:236-243`). Failure is honest data: a false lapse (`dao.lease.md:268-270`) surfaces as `:serve/disclaim` → `:yin.k/unsatisfied` naming the identity (`yin.vm.universal-continuation-format.md:536-538`).

**Fable's recommendation stands in direction; amend the phasing** so serve v1 is not gated on the lease composition: decide lease-governed now (so the serve doc never encodes "serve forever"), ship v1 with composition-retirement, land the judge-over-serve-table next. Either way, update `dao.lease.md:286-289` (reclaim observed as `:ws/closed`) to `:serve/close` with `:serve/reason` — the design already flags this (findings.md:328).

---

**Q7 | position: `dao.stream.serve` for the new layer, rename today's `dao.stream.serving` (Fable's recommendation) | confidence: high**

The word "serve" should belong to the attach-to-original model because the contract's own resolution sentence already uses it that way — descriptors resolve against "the streams a network endpoint serves because serving them is what it is" (`dao.stream.md:236-239`) — and today that sentence's only implementation is the copy model, which is precisely the confusion (ws doc prose "serving is what makes any stream remotely attachable", `dao.stream.ws.md:18-26`; contradiction C1, findings.md:318 — under OD-3(a) the copy is a different logical stream, `dao.stream.md:906-911`). `dao.stream.serving.copy` is an acceptable target (the prefix keeps the grep trail); note `dao.stream.forward` is the local single-destination copier (`src/cljc/dao/stream/forward.cljc`, `dao.stream.md:927-929`), so "copy" names a family, not a collision. **Fable's recommendation should stand.** Independent — but sequence it first: rename before `dao.stream.serve` lands, while the repo is still dev-only and no consumer grows on the ambiguous name.

---

## Dependency summary

- **Q5 gates Q1** (the UCF amendment is OD-2 sentence 3 applied; `append-unknown` is OD-2's third state) **and gates the whole design** (findings.md:282). Q1 is therefore not an independent choice.
- Q2, Q3, Q4 are independent of each other and of Q5.
- Q6 is independent of OD acceptance but consumes this design's `:serve/close` `:serve/reason` and obligates the `dao.lease.md:286-289` update.
- Q7 is cosmetic/independent; do it first.

Constraints honored throughout: six invariants (`docs/design/datom.world.md:23-28` — the design's §10 maps to them cleanly); transport-independent identity travels in the serve descriptor and every cursor is source-minted verbatim (findings.md:103); outcome sets stay within the contract's exhaustive sets (proxy tables, findings.md:139-145, all within `dao.stream.md:542-568`); kept cursors and gap are preserved (proxy "never produces a `gap` the source did not", findings.md:154); dev-only repo, so renames and OD acceptance cost no migration.

Read-only constraint honored: no repository file was edited. Working notes and citations are recorded in the plan file at `~/.claude-glm/plans/read-collab-1790337700000-architect-dao-serene-shannon.md`.
