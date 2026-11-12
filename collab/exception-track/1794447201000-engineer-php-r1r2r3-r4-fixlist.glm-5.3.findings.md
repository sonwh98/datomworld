Created-GMT: 2026-11-12 06:3x:00 GMT
Created-Local: 2026-11-12 13:3x:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm (glm-5.3)
Session-ID: 6e2967a3-3d6a-4604-b3f0-2a61a74bb22f (r4 fix-list; resumes the r3 session)
Round: PHP r4 — sol's step-7 FIX-LIST (two blockers) — branch exception-php-r1r2r3, on tip
4939a3ae (r1 1757aae7, r2 e4f271ff, r3 4939a3ae)
Status: both blockers repaired, each with a red-first regression that is green now; all
r2/r3 pins and the disclosed lane-blocked signature unchanged; committed, NOT pushed

# Findings — r4: the exported-mint bypass (P1) and handle-candidate authentication (P2)

## Disclosure first

The step-7 findings file the brief cites
(`collab/exception-track/1794446270000-architect-php-r1r2r3-step7.gpt-6.1-sol.findings.md`)
is NOT in this worktree, not on origin/master (0e666a3f), and not in this repository's
history — the relay presumably lives only in the reviewer's/orchestrator's checkout. I
worked from the r4 prompt's own defect text (whose line anchors — prelude.cljc :2851,
:2876/:2879/:4891, php_exception_test.clj :372 — match this tip exactly) and reconstructed
both attacks firsthand; every reproduction below is my own.

## P1 — the exported transitive mint: REPAIRED

**The defect, reproduced**: `php/receive-condition` was a `php/`-prefixed definition, and
core/exports takes every `php/` key, so the condition-accepting receiver was a MODULE
EXPORT — a composed unit's boundary form could call it directly with a forged condition.
Reproduced red on all three backends before the fix: the call mints a GENUINE
provenance-carrying ForeignException whose retained condition is the attacker's map
(`:caught-a-wrapper true`), and the unchanged rethrow forwards it through the entry bridge
as if it were a real retained condition (`:forged-forwarded true` ×3) — provenance forgery
through the exported helper, exactly the bypass class the r3 spec's point 5 named.

**The repair** (prelude.cljc :2860-2896, :2891, :2894, :4906): the receiver is renamed
`php.rt/receive-condition` — the `php.rt/` namespace core/exports never takes, the same
compiler-private rule as mint-retained/raise-foreign/raise-protocol; exact-symbol
resolution is preserved in bundled and linked forms by the same strip rule that already
governs the other php.rt/* names (the module emitter leaves them qualified everywhere).
The three call helpers reference it unchanged in role — the raise exit they hand to
yang.call/invoke, where every condition the receiver sees is bridge- or validation-built,
never a guest argument. The docstring records why the name is private.

**The audit** (`php-mint-reachability-test`, RED-first — the offender list was exactly
`(php/receive-condition)`): a static walk over the definition graph asserting NO module
export DIRECTLY mentions any of `php.rt/mint-retained`, `php.rt/raise-foreign`,
`php.rt/raise-protocol`, `php.rt/receive-condition`. One hop is the discriminating depth: a
direct mention is a guest-reachable name whose own parameter can flow to the mint as a
condition; deeper reachability runs through the call helpers' callback wiring where
conditions are machinery-built (my first, transitive version of this audit flagged ~every
export through the php/call hub and was wrong for exactly that reason — documented here so
the shape is not re-broken). The three call helpers are the explicit, commented whitelist.
The adoption pin is extended per the brief: `receive-condition` asserted not-exported by
its stripped name (the php.rt/-prefix assertion could not catch it).

**The executed regression** (`php-exported-mint-forgery-test`, RED-first ×3, now green):
calls the receiver through the composed-unit surface with a forged not-callable condition,
catches the minted wrapper, rethrows it, and asserts the forged condition NEVER forwards.
After the repair the forged call cannot even resolve — every backend answers the
resolution refusal (`Unable to resolve symbol: php/receive-condition`), the surface is
closed, no green run delivers the forged condition.

## P2 — authenticate handle candidates before local classification: REPAIRED

**The defect, reproduced**: `php/obj-val?` accepted any non-nil `:php/obj` when
`:php/type` was absent, and `php/throwable?`'s instance-of then read the class out of the
"cell" with an unauthenticated `cell/get` (`check-ref!`) and lower-cased it with `php/lc`
(`data/str->code-points`). Ordinary legal foreign data shaped like a handle refused at the
host primitive instead of becoming the ForeignException: `{:php/obj 42}` → "Dead or forged
cell reference (:id nil)"; a forged `{:php/obj {:type :cell-ref :id 99999 :seal ...}}` →
the same at :id 99999; `{:php/type :obj :class 42}` → the string-op refusal. All three
reproduced red ×3 backends.

**The repair** (prelude.cljc :2836-2859): `php/obj-val?` is now a total authenticated
local-object test —
- handle form: the `:php/obj` entry must pass `cell/authentic?`, the VM's own heap+seal
  predicate (total boolean: a non-reference, a forged or dead reference all answer false,
  never a refusal — engine.cljc `authentic-ref?`), so `cell/get` only ever runs on a live
  cell this task issued;
- typed-map form: `:php/type :obj` AND a `data/string?` `:class` — a name php's class
  tables can be safely asked about (a memberless lookup still answers nil and class-is?
  stays false, so only the refusal is new).

Legitimate representations are preserved: real handles (authentic cells) and real map
errors (string :class) classify exactly as before; an unchanged local Throwable keeps its
identity path; no broad host catch anywhere — the fix is total predicates before the
classified read, per the brief.

**The regression** (`php-handle-shaped-foreign-payload-test`, RED-first ×3 on all three
shapes, now green): each handle-shaped foreign payload — `{:php/obj 42}`,
`{:php/obj {:type :cell-ref :id 99999 :seal "forged"}}`, `{:php/type :obj :class 42
:props {}}` — is delivered as a thrown condition and must come back `{:retained true
:local-foreign-exception true :condition-identical true :payload-identical true}` on every
backend: original reason, complete evidence, payload identity, the §8.10.7 rule-3
reception.

## Gate evidence (all firsthand this round, JVM, laptop lane; contract test NEVER local)

| Gate | Result |
| --- | --- |
| php-mint-reachability-test (NEW, RED-first) | 2 assertions, 0F/0E (pre-fix offender list: `(php/receive-condition)`) |
| php-exported-mint-forgery-test (NEW, RED-first) | 4 assertions, 0F/0E (pre-fix: forged-forwarded true ×3) |
| php-handle-shaped-foreign-payload-test (NEW, RED-first) | 9 assertions, 0F/0E ×3 payloads ×3 backends (pre-fix: dead-or-forged-reference refusals ×3) |
| php-provenance-adoption-test (extended) | 5 assertions, 0F/0E |
| php-kit-parity-test | 1 test / 4 assertions, 0F/0E — `{:reason :yang.call/not-callable :condition-identical true}` ×3 |
| php-copied-wrapper-forgery-test / php-mutated-wrapper-forwarding-test | 3 assertions each, 0F/0E |
| php-fresh-egress-directed-test / php-local-reception-directed-test | 3 assertions each, 0F/0E |
| php-retention-store-surface-test | 4 assertions, 0F/0E |
| php-kit-batch-parity-test | 53 assertions, 9 failures = the SAME 3 lane-blocked fixtures × 3 backends, failure signature BYTE-IDENTICAL to the r2/r3 disclosure (:payload-identical only; everything else in those rows correct); the other 12 fixtures green |
| directed legs (×3 backends each) | [:python :php :typed-catch] (green on retry; first attempt hit the r2-documented cold-publication dao.jing stall, same leg as r2/r3), [:python :php :unchanged-rethrow], [:python :php :retained-lifetime], [:clojure :php :catch-all], [:php :typescript :catch-all] — all GREEN |
| regressions | exceptions 6/25, prelude 33/373, class-model 8/587, objects 3/538, generators 3/76, builtin-surfaces 4/225, e2e fast 21/1116, exception-source 3/113, conformance 3/18 — all 0F/0E |
| clj-kondo / cljstyle (both touched files) | 0/0 / clean |

## Residuals / carry-overs

Unchanged from r3's findings: nil-provenance behavior split (door refuses, documented);
the §3a instrument/lane conflict (three fixtures, byte-identical signature, steward's);
the §3b dao.jing laptop-lane stall (one retry needed, shiva is the acceptance lane);
suspension probe; per-call result cell; the remote lanes (contract test, test:changed,
12-pair matrix, Node/Dart harnesses) — the orchestrator's.

## Process disclosures

- The cited step-7 findings file is absent from every surface I can reach (disclosed
  above); the two blockers were reconstructed from the r4 brief's own text and verified
  red-first before repair.
- The first version of the reachability audit (transitive closure) was wrong — it flagged
  the intended call-helper wiring through the php/call hub; it was tightened to one-hop
  with the commented whitelist BEFORE any implementation, and the pre-fix red run named
  exactly `php/receive-condition` as the sole offender.
- Temporary scaffolding namespaces (leg runner, lint runner) were deleted before commit
  and never committed. No assertion weakened at any point; the 18 disclosed lane-blocked
  assertions keep their kit-authored reds, signature unchanged.
