Created-GMT: 2026-11-18 16:50:27 GMT
Created-Local: 2026-11-18 23:50:27 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: pending (provider-generated; conversation_id captured at read-back)
# Task: yang-compilers-dedup-round2-audit
Role: Architect (read-only audit)

Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-18 23:50:27 +0700 | Status: active | Rationale: architectural-grade second-pass audit; independent of the codex implementer/reviewer family

Audit the yang.* target-language compilers for REMAINING deduplication opportunities.
This is round 2: a first audit (codex gpt-6.1-terra, Nov 15) identified five extractions
which have ALL landed on master (commit 1604aabe: `yang.core.siteset-store`,
`yang.core.siteset-publication`, shared emitters in `yang.core.prelude`, shared helpers in
`yang.core.lower`). Your job is to find what is LEFT, and to be honest about what should
stay duplicated.

Work read-only in the repo root (your cwd). Do not edit files; your final response is the
deliverable.

## Read first

- `src/cljc/yang/` — the compiler surface: `core/` (the shared substrate, now including the
  DRY modules), `php/antlr/`, `python/antlr/`, `typescript/antlr/`, `clojure.cljc` +
  `clojure/prelude.cljc`, plus the platform variants `src/clj/yang/antlr/cst.clj`,
  `src/cljs/yang/antlr/cst.cljs`, `src/cljd/yang/antlr/cst.cljd`, `src/jolt/yang/`.
- `docs/agents/malleability.md` — the governance criteria (bounded complexity 2-5 public
  entry points, coupling, extraction signals).
- `docs/design/datom.world.md` — architectural invariants.
- `collab/exception-track/1794725189567-engineer-yang-compilers-dedup-audit.gpt-6.1-terra.stdout.log`
  — round 1's audit (final agent_message holds the report): its "leave target-specific"
  rulings BIND unless you find they were wrong.
- `collab/exception-track/1794853997820-architect-dedup-malleability-review.gpt-6.1-sol.findings.md`
  — the architect's malleability verdict + the three P2 dispositions (esp. P2-1:
  core.prelude now at 16 public, core.lower at 12 — near the extraction trigger).

## Already ruled target-specific (do NOT re-litigate without new evidence)

- Guest prelude definition vectors (php/py/ts/clojure) — duplication IS the contract;
  content addresses depend on shapes. PHP's defquoted/ClojureDart EDN-read macro rationale.
- Float/digit rendering (py vs ts): thresholds, exponent format, negative-zero differ per
  language conformance.
- Scope walkers and wrap-escape: same names, different grammar semantics.
- Site admission/derivation (php admits empty sets, python rejects; different propagation).
- Discovery policy (php asserts principal vs python doesn't).
- Publication wrappers (already delegate to yang.core.publication).

## Known open thread (evaluate, don't auto-execute)

P2-1: core.prelude (16 public) and core.lower (12 public) exceeded the 2-5 bound; the
architect flagged a public/private audit and possible cohesive splits (prelude emission vs
admission; lowering diagnostics vs packet access/UAST construction) as SEPARATE future work.
Fold your judgment on this into the ranking.

## New areas round 1 did NOT cover (your primary ground)

- The cst triple: `src/clj/yang/antlr/cst.clj`, `src/cljs/yang/antlr/cst.cljs`,
  `src/cljd/yang/antlr/cst.cljd` (~221 lines each) — are these genuinely
  reader-conditional-necessary or partially dedupable into cljc?
- `src/jolt/yang/antlr/` — the jolt variant.
- The eval machinery: `php/antlr/eval_*.cljc` (eval_admission, eval_coordinator,
  eval_publication, eval_transport) — any internal duplication, and does any of it belong
  in yang.core (the eval-bridge is php-only today; would other targets repeat it)?
- `yang.antlr/packet.cljc`, `yang.antlr/` remaining contents vs the per-target antlr trees.
- The per-target `prelude_module`/`prelude_p9`-style extension files and `builtins`.
- Anything in `core/` that has grown beyond its name (the P2-1 lens).

## Deliverable

1. Inventory the current surface (post-1604aabe), with line counts.
2. For each candidate cluster: file:line refs, similarity evidence (diff or quote a
   representative pair — actually read the paired regions), the proposed seam, estimated
   savings, and risk.
3. Ranked top opportunities by (savings × soundness × malleability-benefit per
   malleability.md).
4. An explicit leave-alone list with reasons.
5. Judgement on P2-1: split or not, and into what.

## Report format (mandatory)

Begin exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

End with exactly one line:
AUDIT VERDICT: <top opportunity in one line; or NO ACTIONABLE REDUNDANCY>
