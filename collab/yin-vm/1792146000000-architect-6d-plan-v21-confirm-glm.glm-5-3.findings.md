Created-GMT: 2026-10-16 11:01:49 GMT
Created-Local: 2026-10-16 18:01:49 +07
Coding-Agent: glm
Session-ID: e33f308d-2ca8-4019-917d-4c85854673b4
# Task: confirmation of plan v2.1 — glm glm-5.3 verdict (resumed mob thread)
Role: Architect
Implementers:
- Model: glm-5.3 | Assigned: 2026-10-16 17:56 +07 | Status: completed | Rationale: non-author-family confirmation of codex-authored v2.1 per owner routing; final message promoted verbatim below (two CLI warning lines dropped); stdout at collab/1792146000000-architect-6d-plan-v21-confirm-glm.glm-5-3.stdout.log

V2.1-CONFIRMED

# Confirmation of plan v2.1 (codex amendment of Fable's 6d plan v2) — reviewer glm, resumed thread

Method: re-read v2.1, the governing consensus, and my base-plan review; re-verified every claim in v2.1's §4 citation table against the tree (all held — ledger at the end), plus the three P1 premises I failed to catch in round 1, now confirmed firsthand: `maybe-run` recurs `(run-fn vm)` after every handled bridge step (`ffi.cljc:342-349`), so a v2-style wrapped `run-fuel` restarts the budget unboundedly; `vm-hot` (`semantic.cljc:134-148`) has no gate check while 6d-a's carrier makes fenced `:running` restores constructible; and link/install completions are queued with their transport fields stripped (`ready` dissocs `:envelope :request :response :cursor`, `engine.cljc:1148-1153`) after the install entry is removed (`refuse-install` `engine.cljc:1344-1351`, link path `:1454-1455`), so v2's "ready wait judged in context `:wait` as a pending wait" dies in `pending-node`'s `:incomplete-install` arm (`rows.cljc:1559-1561`). My round-1 verdict did not survive these; the consensus was right to reconcile NOT-READY, and this confirmation is issued against the source, not the draft's claims.

## Amendment rulings

(The prompt says "P2 4-8"; the consensus numbers its P2 items 4–9 — all six ruled below.)

- **P1-1 (bound run-fuel through the bridge): OK** — §3.4's four-step contract (one raw budgeted action, ≤1 `bridge-step`, return immediately, no `maybe-run` wrap) implements the consensus's sanctioned "return after one bridge cycle" option; item 11 pins the FFI-loop, diagnostic-only-bridge, and repeated-invocation-to-reference cases; `bridge-step` verified one-operation (`ffi.cljc:278-291`).
- **P1-2 (gate guard to 6d-a): OK** — guard in 6d-a's change list, `run-fuel` step 1 and `vm-hot` entry (covering `vm/step` `:331-332` and `vm/run`→run-loop→`vm-hot` `:271-274`), item 12 exercises all four entries plus `:ended`; 6d-b change 5 and §3.8's sign-off check ensure no retained clause still assigns it to 6d-b.
- **P1-3 (completed-outcome grammar for link/install): OK** — the `:completion` row with all seven producer tags, Q1–Q5 decoupled from pending validation, "must not reconstruct a retired … envelope, response cursor or install", refusal/try semantics matching `refused-entry` exactly (`engine.cljc:1156-1165`), FFI call-ids gathered from ready descriptors so parked FFI records stay valid (extends `rows.cljc:1131-1135`); items 2 and 4 test it, including post-removal install completions and active-control-beside-completion.
- **P2-4 (ready cursor-cell encoding): OK** — glm's independent-body-under-shared-C mechanism with muse's shared-cursor interpretation ("the body is already the final post-sweep body" — consistent with the write-back-before-next-resolve sweep, `engine.cljc:2135-2139`), the census/update-row distinction grounded in the three verified blanket-skip sites (`rows.cljc:397`, `:468`, `:819`), muse's pinning case in item 5.
- **P2-5 (op-id shape vs custody): OK** — `check-op-id-shape!` factored (the shared function verified at `rows.cljc:1243-1259`), named slot-18 caller in `check-pendings!`, fork-completed histories require no root context, malformed-ID refusal pinned (item 6); default 6 rewritten accordingly.
- **P2-6 (drop the cardinality equality): OK** — §3.5 separates ordered region length/order/repeated-register agreement from the two set equations and states "no requirement that the length … equal `|L(p)|`"; item 9 carries it, including the explicit "do not compare".
- **P2-7 (ready `:V` in the oracle): OK** — implementation traversal (`value-prim` on `:V`, refusal values, restored descriptor fields) plus the independent oracle plus item 13's only-unsupported-primitive-nested-in-outcome case; §3.7.2 appends the portability sentence.
- **P2-8 (exception-state channel): OK** — stage-local `stage-result` outcomes carrying the latest completed state replace v2's inner-`assoc` (which genuinely cannot cross `step-active`'s catch — separate scopes verified, `driver.cljc:2117-2150` vs `:2255-2262`); journal-uncertain/inbox-refusal propagation preserved; exact retained-machine equality at every cut in the 6d-b tests.
- **P2-9 (UCF Quiesce step): OK** — the sentence exists at `UCF:1904` ("**Quiesce**: the ready-queue must be empty"); replaced with queue retention in §3.7.3, tracked in §3.8 and 6d-b change 8.
- **P3 hygiene — citation offsets: OK** — the "+48" rule withdrawn, symbol/quoted-text locators govern, `rg` re-run on the implementation branch (§3.8); my own drift measurements (−2…+59) support this.
- **P3 hygiene — blocked+ready coverage class: OK** — struck from the driver's classes, tested via the post-sweep/pre-resume `check-wait-set` seam (public, already used by the harness), driver and constructed-product coverage reported separately (§3.6, items 7–8). Verified the reasoning: a `run-fuel` round always returns running or still-blocked-with-empty-queue, never blocked-with-ready.
- **P3 hygiene — glm N2 (lower-defect expression): OK** — §3.2 resolves the first cref to the delivery-state row directly; correct adaptation to the new grammar where no intermediate wait row exists.
- **P3 hygiene — glm N4 (`:pc-body`): OK** — C3's diagnostic spelled correctly in §3.1, §3.8, and item 1.
- **P3 hygiene — glm N5 (halted+outstanding-wait): OK** — §3.1 sentence plus item 3's separate pin.
- **P3 hygiene — glm omissions (terminal-status ready case; inactive predicate): OK** — item 6's terminal-status case with raise-identically comparison and §3.6's outcome-comparison rule; §3.4's control-presence dispatch (verified: `active-continuation?` `engine.cljc:258-261` tests flags only) pinned in item 11.
- **P3 hygiene — codex #9 (holds' one-cycle promise): OK** — default 8 reworded with the backpressure grounding verified (`send-held` keeps the observation in `:observed` on `full`, `reader.cljc:484-496`); §7.1 step 1 and the §7.7.4 sentence drop the promise; 6d-b change 7 keeps the testing duty without converting it to a guarantee.
- **Default 6 rewording: OK** — policy unchanged, mechanism replaced by the factored shape check; consistent with §3.1's custody table and item 6.
- **Default 8 rewording: OK** — as above; defaults 1–5 and 7 restated unchanged, matching the consensus rulings.

Cross-cutting (b)/(c) checks: every v2 clause the amendments touch is explicitly superseded (v2 §3.1/§3.2/§3.4 replaced wholesale; §3.3 outcomes 2/4 and tests replaced; §3.5 equation, §3.6 coverage/oracle, §3.7.1–§3.7.4 affected clauses, §3.8 policy, §3.9 6d-a/6d-b lists, §3.10, §3.11 risk 2), and §3.8's pre-sign-off checklist institutionalizes the no-stale-clause audit. Acceptance renumbering is intact and lossless: v2's 6d-a items 1–10 all map into v2.1's 1–14 (1→1, 2→2, 3→3, 4→7, 5→8, 6→8, 7→9, 8→10, 9→11, 10→14; the `:ready-reason` refusal correctly disappears with P1-3), and each P1 is testable from the acceptance text (items 4, 11, 12).

## Residual-risk rulings

1. **Halted plus outstanding waits: handled acceptably.** The draft retains today's blocked normalization (which I verified is today's lift order, `rows.cljc:1688-1691`), pins it as its own acceptance case rather than under blanket law 1, states plainly that the source's `vm/value` can expose the dropped value before migration, and defers any observation-contract expansion to an explicit ruling. That is the honest treatment; the alternative (claiming result equality) would be false. One implied detail worth carrying into the brief: the harness's blanket result-equality comparator must exclude this constructed state — "pinned separately" already implies it.
2. **Act/run acceptance wording: handled; the intended reading is confirmed.** Item 1's "Delivery frames use `saved(pc, rd)`; running frames use `L(pc)`" is unambiguous and matches the kernel (`act-payload` selects `analysis/saved … rd`, excluding `rd`, `semantic.cljc:64-68`; the run frame uses `L(pc)`, which may contain `rd`). The cross-kind refusal is now well-defined: each frame judged under the other kind's window rule.
3. **Static vs dynamic evidence: handled.** §3.5's split (static: layout availability, set equations, injectivity; dynamic: executed ordered-region length, order, repeated-register agreement) with "neither is presented as a universal proof" is consistent with the plan's own "claims that are checks" doctrine and with what a layout-only test can establish.

## New defects introduced by the draft

- **P3 (nit, no change required) — the `:put` completion descriptor's `datom-V` is redundant with the ready row's `:V` on the success path** (plan §3.1 completion-table vs the same section's `:V` "delivered value"; the entry's datom and value coincide for a successful append per `engine.cljc:443-462`). It is defensible as pending-grammar symmetry and it preserves the original datom when `:V` is the shaped refusal map; if the owner wants the wire minimal, drop the slot or document the mirror — neither affects correctness, laws, or the acceptance gates.

Nothing else: every §4 citation verified in-tree (ffi 278/334; semantic 137/271/331; engine 258/1148/1156/1327/1627; rows 49/397/468/819/1131/1243/1388/2242; frame 162; driver 2117/2252; reader 484; UCF 1904 — all exact), the completion grammar covers every queue producer I could find (`check-wait-set` sweep, `poll-links`, `wake-installed`, `refused-entry`, driver-applied `apply-put`/`apply-ffi-*`), and §3.11 risk 2 correctly makes any undiscovered producer a pre-landing defect rather than a shipped `:ready-reason` refusal.

**Bottom line:** v2.1 faithfully implements the mob's amendment list (P1 1–3, P2 4–9, all P3 hygiene, defaults 6/8 reworded), contradicts nothing of v2 that remains in force, and keeps the acceptance gates consistent and testable. Confirmed for the owner; Fable's Tuesday diff-confirm remains the pre-merge gate.
