Created-GMT: 2026-10-02 23:05:00 GMT
Created-Local: 2026-10-03 06:05:00 +07 (+0700)
Coding-Agent: claude
Session-ID: 5face18f-26de-4829-962a-42049a75c13f (resumed; float-fix round 2 — the converged sign-off additions)

# Task: Float-fix round 2 — the loud CLJS refusal (converged architect ruling)

Role: Yang Compiler and Universal AST Engineer

Both architects ruled on your two sign-off questions (fable:
/Users/sto/workspace/datomworld/collab/1790980500000-architect-floatfix-signoff.claude-fable-5-1.findings.md;
astra: collab/1790980800000-architect-floatfix-signoff-astra.gpt-6-astra.findings.md — both in the main tree;
the ruling summaries below are binding):
- Q1 CONVERGED: data/numeric-key signs off as the INTERIM key; this slice lands first; C3-S2 rebases onto it and
  replaces the numeric arm with ruling-6 decimal-string keys built from unwrapped doubles via exact decomposition
  (never containing a carrier or double), then deletes numeric-key, and pins one NaN-key behavior. Your action in
  THIS round: add a comment on data/numeric-key stating it is interim per the converged ruling and that C3-S2
  replaces it from exact typed inputs — nothing more.
- Q2 CONVERGED: the Python-scoped bridge is correct; universal unwrapping is REJECTED. But ONE addition before
  landing, in this round:
  1. In vm.cljc's pure wrapper (:cljs branch only), the arithmetic and ordering entries (+ - * / < > <= >=)
     refuse any cbor/float64? argument with a shaped error — the :yin.k/non-portable shape at engine.cljc:628-629
     is the nearest existing shape; a plain ex-info naming the primitive and the kind is acceptable. One guard,
     one place. Do NOT unwrap in pure (it would recreate the divergence inside generic yin). = == != stay host =
     by contract.
  2. Add one Node test: a generic yin row with a float literal, decoded from JVM-minted bytes (or built with
     cbor/float64 directly), applied to +, must REFUSE — not return a string.
  3. The UCF scalar-carrier note you already wrote covers the gap recording (a float64 carrier is a scalar for
     rows, images and hashing; generic yin primitives refuse it; only a profile with an explicit seam computes on
     floats portably) — keep it.
Everything else in your report needs no action (portable-scalar? left unchanged; the no-capture-in-the-seam
reliance accepted until C2 yields inside prelude bodies).

MECHANICS: foreground, one command at a time, waiting for each; no background runs, no watchers, complete the
turn: mise exec -- bb gen:python-antlr (skip if current), bb build:yin-repl-node (skip if current), bb test:clj,
bb test:cljs, bb test:cljd; kondo + cljstyle on touched files. Report exact counts.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
