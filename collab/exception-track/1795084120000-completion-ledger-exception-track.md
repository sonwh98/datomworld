Created-GMT: 2026-11-18 19:48:40 GMT
Created-Local: 2026-11-19 02:48:40 +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, continuation-semantics orchestrator seat)
Status: COMPLETION LEDGER — the exception track closes

# EXCEPTION TRACK — COMPLETION LEDGER

Directive: converge exception handling across the four yang.* compilers (clojure, python,
typescript, php) onto one uniform continuation semantics in yin.vm, such that an exception
raised by any frontend is delivered, retained, and rethrown identically regardless of which
frontend raised or caught it. COMPLETED — all four pillars measured on master and the
completion condition (the 12-direction pair matrix) is green.

## The four pillars

1. **One continuation encoding.** The clojure port landed the shared %capture/handler scheme
   on all four frontends; the outcome machinery (`[:yin.k/ok v]` / `[:yin.k/error data]`) was
   retired (net -418 lines). Architect ruling (A): boundary uniformity only, per-frontend
   encodings preserved.
2. **One boundary contract.** The exception kit green across all fixtures, finally legs
   first-executed. The `:clojure-finally-refusal` row was retired per owner ruling
   1794556000000 (the dialect surface derives from the source language); the proof obligation
   landed as try/finally JVM oracle fixtures (1798a795).
3. **One provenance mechanism.** The VM provenance primitive (8c10c5b7) with three consumers
   (js/python/php): foreign wrappers are authenticated by provenance, never shape (R5).
4. **One depth semantics.** The depth probe (3d271099): 144 constant-case cells green
   (D16=D64=D256, four fixture variants, four frontends, three lanes); recursive
   characterization recorded (clojure flat at 7; php 57→177→657; python 40→112→400;
   ts 28→76→268).

## The pair matrix — 12/12 green

All twelve directed pairs (donor→caller over {clojure,python,typescript,php}) across the
eight shared scenarios (catch-all, typed-catch, unchanged-rethrow, binding, finally-replacement,
retained-lifetime, round-trip, success-raise-success) — every leg green ×3 backends
(register/semantic/stack) on live JVM runs. The final leg, python→php :finally-replacement,
closed via the php seat's frontier round (2b6b5d93 → rebased 11c75a8a → merged dc184ffa);
steward acceptance 186 assertions/0 failures. The astra adjudication ruled the leg's red a
fixture-harness defect (run-main releases PHP globals before observation), repaired by staged
seeding through a live composition call — no expectation weakened, no VM surface touched.

## Findings handed forward (not track debt — owners' rows)

- **Measurement budget:** the probe's 1M→10M step-budget raise (53871f52) is a strict
  measurement correction; php's n=256 caught-failure case needs 6.78M steps. Budget
  exhaustion still reports as failure.
- **Recursive depth growth:** php/python/ts show polynomial recursive growth (recorded above)
  while clojure is flat; the linked-warm follow-up remains a named obligation — the unlinked
  probe does NOT prove arbitrary recursive exception unwinding.
- **Pending-obligation rows** (authored, `pending-frontend-rounds` in
  `yang.call.exception-contract`): suspension-and-recursion, host-vm-publication-parity
  (node/dart/bundled/task-isolation/custody-refusals), methods-and-callable-objects,
  foreign-formatting-and-class-name-forgery, and clojure-private-public-equivalence (r4).

## Seat roster (rounds merged to master)

clojure port · kit rounds 1+2 · php r1–r10 + frontier · python r0–r3 + adoption · ts callsite
+ foreign-wrapper adoption · depth probe (+ driver fix) · cljd dart fixes (a3f7ab8d, cd050ca2
idiom) · cc3 dart gate-shape fix · the corpus finally-oracle round. Verification: qwen3.8-max,
astra step-7 (escalated to owner, RETIRE ruled), sol consults and adjudications, terra
implementation reviews, glm engineer rounds, deepseek driver fix. Orchestrator seats:
continuation-semantics (steward), master-green (nightlies, the authoritative dart record),
DRY (consolidation, landed 3a2a9f4a).

## Instrument notes for the record

- Relay files vanished three times from the shared checkout mid-session; all recreated with
  -r2 suffixes and recorded in the session log.
- The steward's dartfix2 duplicate-lane violation (rm -rf cljd-out in the shared nightly
  clone) was confessed to master-green and stopped; the nightly is the authoritative dart
  record.
- The frontier acceptance for dc184ffa ran on the steward's local JVM lane (the same
  instrument as the 2026-11-16 baseline), not shiva; the nightlies own wider tip verification.

— the exception-track steward
