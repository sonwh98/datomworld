Created-GMT: 2026-11-22 21:17:15 GMT
Created-Local: 2026-11-23 04:17:15 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: zcode (orchestrator seat, yang-preludes P1′–P4′)
Session-ID: not-applicable (interactive seat)

# QA handoff: consolidated pre-existing master reds (NOT the yang-preludes track's failures)

Role: Coordination artifact from the Lead Engineering Orchestrator

Per the owner ruling ("you are only responsible for fixing failures in your track. i'll
have a qa engineer fix the other failures"), the failures below were excluded from the
yang-preludes landing gates P1′→P4′-a. Every entry was verified as pre-existing (present
at the then-current master, or master-identical) by this seat before exclusion; the
evidence command is listed. **Start from current master** — the yang.python seat's
bytes/assert series and other seats' landings have moved pins since some of these were
first triaged, so re-baseline each on the tip before fixing.

| # | Failure | Evidence of pre-existence | Notes / leads |
|---|---|---|---|
| 1 | `dao.host.gate-test` — 29 failures (incl. `effect-calls-escape-neither-body-vectors-nor-other-scopes` ×7-8, `an-injected-host-arm-outside-list-*`, `independent-traversal-agrees-with-the-committed-closure` ×6, `a-scan-failure-is-not-a-pass` ×3, `gate-passes-on-the-current-tree` ×4, `library-mode-finds-no-host-library-outside-the-profile` ×3) | `clj -M:test -n dao.host.gate-test` → 29 failures IDENTICAL on master `b1eb3e40` and on every P2′/P3′ branch tip (verified 2026-11-22, laptop) | The gate self-test suite disagrees with the current tree state; likely needs its committed-closure fixture refreshed after recent landings |
| 2 | `yang.python.antlr.float-address-test` — 5 failures (`prelude-addresses-test` :229, `program-addresses-test` :258/:263/:268/:275) | `clj -M:test -n yang.python.antlr.float-address-test` → 5 failures IDENTICAL on master `f23728d3` and P1′ tip (verified 2026-11-21, laptop) | Float-address golden drift predating the preludes work |
| 3 | `yang.python.antlr.linked-prelude-test` — count mismatch (`module-spec-declares-every-free-name-test` :191) | Relayed by the DRY seat as the php seat's known red (collab/yang-php/1795211000000-…md); seen failing in every gate since | NOTE: the python seat's bytes slice re-minted the py manifest (runtime keys 71→79, commit b3208aec); re-derive whether this specific red still exists at current master or was absorbed/moved by that re-mint |
| 4 | cljd compile failure — `yin.vm.data-test` `portable-key-and-equality-test`, `-map-lit` macro crash on `{nil :nil, 0 :zero}` (PersistentVector→IPersistentMap cast) | Compiled that namespace at origin/master on jupiter → identical failure (verified 2026-11-21, jupiter clone) | cljd fixture bug (map literal with nil key), not a VM issue; affects the cljd lane of every `bb test:sub` |
| 5 | `d2-wedge-probe2` — guest instruction budget exhaustion at 2,000,000 steps | IDENTICAL failure on master `f23728d3` and P1′ tip (verified 2026-11-21, laptop; both throw, P3′ gets slightly further: closure value vs nil) | Distinct from `d2-wedge-min-repro`, which P3′ FIXED (1,735,235 steps); probe2 may be a harder wedge or an obsolete probe |
| 6 | `yang.python.antlr.descriptor-profile-test` — 3 errors in `py-v1` pin fixtures | `clj -M:test -n yang.python.antlr.descriptor-profile-test` at CLEAN master `0c36043d` → 12 tests/119 assertions/**3 errors** (verified 2026-11-23, laptop, with the P4′-a diff stashed) | NEW since P3′: introduced by the python seat's bytes/assert landings moving `py-v1` pins the descriptor profile test also pins; coordinate with the yang.python seat — their pin re-mint and this fixture's pins must agree |
| 7 | dao.stream cljs `ws_project_cross_node_test` — occasional 2-4 failures ("cursor ask never settled") | FLAKY, not a persistent red: passed 579/0 on multiple reruns at the same tips (2026-11-22/23); ROOT CAUSE of one occurrence cluster was fixed anyway by 0950f140 (JVM ws receive-adapter race, pre-existing since 71a1dc5c) | Listed for completeness: if it recurs, check for orphaned JVM/Node processes on the host before diagnosing |

Excluded-by-design (not failures): Track B (legacy prelude re-architecture) and its r6
gates — deferred on owner demand; C7 (`core.literals`) and the P2-1 residual
(prelude/lower public-surface audit) — other seats' deferred items.

Contact: the orchestrator seat's log
(`orchestrator-logs/1795254481-zcode-glm-5.3-flash-yang-preludes-p1onward.md`) holds the
full triage history; each landing gate's accounting entries list these same reds with the
gate-specific counts.

## UPDATE 2026-11-23 23:00 +07 (orchestrator seat, after checking collab/)

- **Item 4 (cljd `-map-lit`): FIXED by the qa engineer** — `d33d3826` (zipmap construction replaces the raw nil-key map literal); dart lane compile-all rc=0 at the landed tip.
- **Items 2/3/6 share a root mechanism**: the qa round 1795430000000 + master-green seat's relay established that the `dht-import-v1` golden generator is **environment-sensitive — a fresh `bb gen:antlr` mints different addresses** (three distinct values observed for one source tree). The pin "reds" in this family are mint-state mismatches, not stale expectations. A countersign protocol (union-tree verification, fresh-generation canonical state) is ACTIVE between the master-green orchestrator seat and the yang.python seat — see `collab/yang-php/1795460000000-to-yangphp-remint-countersign-reconcile-note.md` and `collab/yang-php/1795440000000-to-yangphp-dht-import-generator-nondeterminism.md`. Do not fix these pins independently; join the countersign protocol instead.
