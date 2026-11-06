Created-GMT: 2026-11-06 1x:xx GMT
Created-Local: 2026-11-06 1x:xx +07 (+0700)
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id)

# Task: READ-ONLY audit — is exception handling uniform across the four frontends, and does every frontend compile exceptions to the same continuation?

Role: Yang Compiler Reviewer (read-only; you edit NOTHING — the deliverable is your final agent message)

WORKTREE: /Users/sto/workspace/datomworld-f12 (at e9a0b430 + master merges; read-only).

## The owner's question, verbatim

"exception handling should be implemented uniformly across all languages. exceptions should compile down to the same continuation"

Audit whether this is TRUE of the current code, for all four admitted frontends:
yang.clojure, yang.typescript (js), yang.php, yang.python.

## The designed contract (docs/design/yang.antlr.md)

- **§8.10.7** (:3838): an escaping source exception is delivered to the CALLER's supplied raise exit as `{:yang.call/reason :yang.call/thrown, :yang.call/value original-thrown-value}` — an exceptional continuation transfer, NOT an ordinary return. Returning an identical-looking map is ordinary data. Protocol failures use `:yang.call/not-callable` / `:invalid-invocation` / `:unsupported-binding` with plain evidence. Reception rules: payload valid in the receiving runtime keeps its local behavior; JS may deliver the raw payload; Python/PHP wrap a foreign payload in their local `ForeignException` (catch-all catches it; typed catches match the LOCAL hierarchy).
- **§8.10.11** (:4057): the exception evidence row (binding failures, arbitrary foreign thrown data, catch-all/typed, unchanged rethrow, finally replacement, protocol-error rethrow, condition-shaped thrown maps, A→B→A propagation, ForeignException identity, rethrow-from-another-activation).

## What to audit, per frontend (yang/clojure.cljc, yang/typescript/antlr/{lower,prelude}.cljc, yang/php/antlr/{lower,prelude}.cljc, yang/python/antlr/{lower,prelude}.cljc)

1. **The lowering path**: trace how a source try/catch/except/throw/finally lowers. Does the escape go through the §8.10.7 condition shape (`:yang.call/thrown` + value) and the caller's supplied raise exit / exceptional continuation — or does any frontend take a private path (a host-native try/catch around the body, a special instruction, a different map shape)? File:line for each frontend's raise-exit hookup.
2. **The call boundary**: how each frontend's `run-main`/callee activation receives a foreign condition and maps it back (the §8.10.7 reception rules 1-3). Where does `ForeignException` wrapping happen for python and php; does JS really deliver raw; does clojure deliver as data?
3. **Uniformity verdicts**: for each frontend, UNIFORM / DIVERGENT / UNPROVEN, with the exact evidence. A divergence is a different continuation shape, a synthesized engine value, shape-routing on the condition map, or a guessed foreign class name — flag any of these hard.
4. **Local coverage inventory** (brief): which local exception behaviors each frontend's own tests pin (throw, catch-all, typed, finally, rethrow), with test file:line — depth, not exhaustive.
5. **Cross-language evidence**: confirm or refute that NO pair currently has §8.10.7 propagation evidence (the S11 round reported the conformance kit has zero exception legs; verify).

## Known constraints

- Read-only: no file edits, no test runs longer than a focused namespace load if needed (prefer static reading; the hosts are busy with the owner's test runs — do NOT run slow tests).
- This is an audit, not a design review: report what IS, not what should be, except where you flag a divergence.

## Deliverable

One final agent message: the per-frontend lowering trace (file:line), the boundary behavior per frontend, the four verdicts, the coverage inventory, and a ranked list of divergences if any (each with evidence). End with a one-line overall verdict: UNIFORM / DIVERGENT / UNPROVEN-EVERYWHERE for the "same continuation" claim.
