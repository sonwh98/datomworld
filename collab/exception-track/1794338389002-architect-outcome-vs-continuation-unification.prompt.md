Created-GMT: 2026-11-10 19:20:00 GMT
Created-Local: 2026-11-11 02:20:00 +07 (+0700)
Coding-Agent: deepseek
Session-ID: 99b84f47-8b92-431e-b93f-9efc1cdcb63d
# Task: architect adjudication — should the exception track unify the INTERNAL exception encodings, and on which one?
Role: Architect (independent mob ruling — three architects are answering this in parallel; rule on your own evidence, do not attempt to see the others' verdicts)
Implementers:
- Model: deepseek-flash (requested name "deepseek-4.1-flash" rejected by endpoint 400; flash lane is deepseek-flash) | Assigned: 2026-11-11 02:20 +0700 | Status: active | Rationale: deepseek-flash is the flash architect lane; independent mob ruling on an architecture-level encoding decision.

## Context

An independent audit (orchestrator lane, 2026-11-10) established the current state:

- The yin.vm UAST has NO :throw/:try/:catch/:finally/:yield tags. The canonical continuation
  vocabulary is `:vm/current-continuation` (`%capture`, zero children), `:vm/park`, `:vm/resume`,
  plus the `:tail?` boolean on `:application` (`src/cljc/yin/vm.cljc:997-1018`,
  `src/cljc/yang/core/uast.cljc:50-51`; CESK semantics in `src/cljc/yin/vm/ast_walker.cljc:289-295, 556-564` —
  invoking a continuation REPLACES the machine's k, undelimited).
- yang.python, yang.typescript, yang.php lower exceptions identically in STRUCTURE: a per-frontend
  prelude family (`py/try`/`js/try`/`php/try`, `*-try-finally`, `*-raise`/`*-throw`, `*-call-ec`)
  over `%capture`, with the handler being a captured continuation stored on a handler stack in a
  mutable runtime cell (`py.rt/ctx` python/antlr/prelude.cljc:315-328; `js.rt/ctx`
  typescript/antlr/prelude.cljc:1725-1735; `php.rt/ctx` php/antlr/prelude.cljc:258-271). The three
  preludes are hand-written near-copies, not shared code.
- yang.clojure is the outlier: the slice-5 outcome model — every guest function returns
  `[:yin.k/ok v]` / `[:yin.k/error data]`; `throw` lowers to `[error-tag v]` and `try` to an
  `:if` over `(data/content= (data/nth outcome 0) :yin.k/error)` (`src/cljc/yang/clojure.cljc:840-864,
  866-868, 1125-1143`). Documented motivation (853-856): error propagation as a value return keeps
  named/mutual tail chains at constant CESK depth. Consequences: portable try restricted to one
  Exception/ExceptionInfo catch, no finally, no raise-from. It still emits `u/capture`
  (`:vm/current-continuation`) twice as the §8.10.7 raise-exit bridge (`clojure.cljc:1562, 1610`).
- RULING OF RECORD v2 (owner-approved, `collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md`)
  chose BOUNDARY uniformity: the same §8.10.7 condition
  `{:yang.call/reason :yang.call/thrown, :yang.call/value v}` at every admitted guest-call boundary,
  with internal lowering explicitly NOT required to be identical (scope bounds; R4 allows the
  outcome-token channel to remain "an optimization only with equivalence proof"). Read v2 fully.

## THE QUESTION

Should the exception track go beyond v2's boundary uniformity and converge the INTERNAL exception
encodings across the four frontends — and if so, on which encoding?

Rule on exactly one of:

**(A) Keep v2 as-is.** Boundary uniformity only; outcomes remain a clojure-internal encoding
(pending R4's equivalence proof). If you rule (A), say what NEW work (if any) is still justified —
e.g. factoring the triplicated py/js/php try-family into one shared core prelude, or a
cross-language throw-across-boundary golden test over the 12 directed pairs.

**(B) Unify internally on the continuation-handler encoding.** Port clojure off outcomes onto a
`%capture`-based try/try-finally/call-ec prelude family like the other three. Cost: loses the
constant-depth tail property that motivated slice 5, reopens architect rulings A1-A5 and LaTTe/
crosshost verification. Say whether the tail-depth cost is acceptable and why.

**(C) Unify internally on outcome tuples.** Port py/ts/php onto outcome returns. Cost: every guest
function's return protocol widens (the `[:yin.k/ok v]` wrapper on every call), higher-order
combinators must thread outcomes, and the three conformant preludes + golden tests are rewritten.
Say whether these costs are acceptable and why.

**(D) Dual-mode / other.** A different convergence (e.g. shared core prelude with per-frontend
parameterization, or an owner-level decision to accept two documented encodings with a pinned
boundary contract). Specify it concretely.

## Where to look

- `collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md` — ruling of record (read fully)
- `src/cljc/yang/core/uast.cljc`, `src/cljc/yang/tails.cljc` — canonical node vocabulary
- `src/cljc/yang/python/antlr/prelude.cljc`, `src/cljc/yang/typescript/antlr/prelude.cljc`,
  `src/cljc/yang/php/antlr/prelude.cljc` — the three continuation preludes (compare their try families)
- `src/cljc/yang/clojure.cljc` — the outcome model (840-864, 1125-1143) and the capture bridge (1506-1629)
- `src/cljc/yin/vm/ast_walker.cljc`, `src/cljc/yin/vm/semantic.cljc` — how the machine interprets continuations
- `docs/design/yang.antlr.md` §8.10.7 (3838-3922) — the raise-exit convention
- `collab/exception-track/1794320926000-architect-dsg-condition-r4-coupling.gemini-4-argon-high.findings.md`
  and `1794332020000-architect-value-path-refusal-shape.*` — prior adjudications in this track

## Deliverable

One ruling: **(A)**, **(B)**, **(C)**, or **(D)**, with file:line evidence for every factual claim.
State the dominant tradeoff in one sentence (tail depth vs return-protocol tax vs rewrite cost),
and list the concrete follow-up work your ruling implies for the track (rounds, tests, owner
decisions). Do NOT implement anything. If you believe the question is not yours to rule (e.g. it
needs an owner product call), say so explicitly and rule provisionally anyway.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
