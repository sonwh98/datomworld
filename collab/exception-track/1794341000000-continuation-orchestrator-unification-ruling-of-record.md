Created-GMT: 2026-11-10 19:58:00 GMT
Created-Local: 2026-11-11 02:58:00 +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, continuation-semantics orchestrator seat)
Session-ID: not-applicable (interactive seat)
Status: OWNER-DECIDED 2026-11-11 02:5x +07 (in-session, verbatim below); seat-filed 03:0x +07
Owner decision, verbatim answer to the seat's structured question ("which bar must the track meet?"):
> "(A) now, (B) if R4 proof fails" — Sign (A) now; clojure moves to continuation lowering only if
> its R4 equivalence proof fails (the pre-authorized fallback). Decides later on evidence instead
> of paying (B)'s cost upfront.
Consequences as filed below stand, amended: the ts/php hold LIFTS on this decision (premise
"the unification track completes" is satisfied — the unification question is answered (A)); R4's
scope is confirmed per (1) with the (B) fallback now OWNER-AUTHORIZED, not merely pre-registered;
item (2)'s "dispatch remains parked until the owner's word" is superseded by this word.

# RULING OF RECORD (proposed) — the internal-exception-encoding unification question

## The question

Should the exception track go beyond ruling v2's boundary uniformity
(`collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md`) and converge the
INTERNAL exception encodings of the four frontends — and if so, on which encoding?

## The seat's finding: mob consensus 3/3 for (A) — keep v2 as-is

Three independent architect rulings, dispatched in parallel on identical briefs
(`179433838900{0,1,2}-architect-outcome-vs-continuation-unification.prompt.md`), each ruling from
its own file:line evidence:

1. **argon** (gemini-4-argon-high, agy, conversation `cdc97c9b-ce7e-438e-8044-87aeab3c725d`):
   **(A)**. Decisive mechanics: the `%capture`-based `try` makes its body structurally non-tail
   (`u/let1` lowers `(let [v (body)] (restore! saved) v)` — body is an operand, `tail? false`, one
   CESK frame per try execution), which destroys slice-5's constant-depth property; porting
   py/ts/php onto outcome tuples violates §8.10.7's normal-return invariant ("no completion packet
   around each result") across 14,100+ prelude lines that still need `%capture` for
   finally/break/continue/with/generators regardless. Cataloged FIVE real semantic differences
   among the three try families (try arity, frame width, restore! semantics, empty-stack policy,
   terminal escapes), rejecting premature dedup. Novel obligation: R4b must test cross-unit
   clojure callables (`clojure->clojure` across units, `clojure->python->clojure`) so
   `dsg__admitted?` and `:clj/outcomes?` cannot disagree across token segments.
   Log: `1794338389000-...gemini-4-argon-high.stdout.log`.

2. **astra** (gpt-6-astra, codex): **(A)**. "The code supports keeping v2." Clojure has explicit
   constant-depth regression tests (`test-outcome-common-call-depth`); outcome retention is
   conditional on R4's proof, not grandfathered.
   Log: `1794338389001-...gpt-6-astra.stdout.log`.

3. **deepseek-flash** (deepseek CLI, session `99b84f47-8b92-431e-b93f-9efc1cdcb63d`): **(A)**.
   Decisive observation: no internal convergence flips a single currently-red acceptance row —
   every red row is a boundary bridge/reception defect. The §8.10.7 boundary transfer is the only
   integration surface; flags that the ts/php hold relay's "unify exceptions as yin.vm
   continuations" is an orchestrator paraphrase, and only the owner can supersede v2 if the owner
   meant a forced internal encoding.
   Log: `1794338389002-...deepseek-4.1-flash.stdout.log` (provenance note in the prompt header:
   the literal name `deepseek-4.1-flash` was rejected by the endpoint with 400; the flash lane is
   `deepseek-flash`).

All three verdicts are mutually consistent and consistent with ruling v2 and the three R3-arc
adjudications (1794320926000, 1794332020000, 1794336505000).

## The ruling this seat files (per the mob, for the owner's signature)

**(A) — v2 stands.** Boundary uniformity only: the §8.10.7 condition
`{:yang.call/reason :yang.call/thrown, :yang.call/value v}` at every admitted guest-call boundary
is the entirety of the required uniformity. yang.clojure keeps its internal outcome encoding
CONDITIONAL on R4's equivalence proof at the §8.10.9 bar (not grandfathered); py/ts/php keep their
continuation-handler preludes. There is no "unify the internals" work item, and the
"unify exceptions as yin.vm continuations" phrase in the ts/php hold relay means the boundary
transfer, not a forced internal representation.

## Consequences this seat derives

1. **R4 scope** (unification bounds it): R4 proves the outcome channel EQUIVALENT to the
   raise-exit lane at the §8.10.9 bar (caught/uncaught/protocol-rethrow/binding/higher-order, the
   pre-registered `:clojure-private-public-equivalence` obligation) — plus argon's cross-unit
   callable scenarios. On failure, the pre-authorized fallback is unifying clojure onto the
   raise-exit lane (no new owner license needed).
2. **ts/php hold** (this seat's call, per the handoff): the hold's premise was "the unification
   track completes." Under (A), the unification question is now answered — the track's work IS
   v2's boundary sequencing. The hold is LIFTED once the owner signs this ruling; JS R1+R3
   resumes as v2's first round. Dispatch remains parked until the owner's word.
3. **Deferred (optional, post-R1/R2/R3)**: deduplication of the triplicated py/js/php try-family
   into one parameterized core prelude + one cross-frontend isomorphism test — a refactor round,
   NOT convergence; must parameterize argon's five documented differences and land after the
   boundary rounds (deepseek's sequencing caution).
4. **Not authorized by this ruling**: any change to internal encodings in any frontend; any
   shared-prelude work before R1-R3 land.

## Owner decision requested

Confirm or amend: (a) sign (A) as the ruling of record; (b) lift the ts/php hold per (2); (c)
confirm R4's scope per (1).
