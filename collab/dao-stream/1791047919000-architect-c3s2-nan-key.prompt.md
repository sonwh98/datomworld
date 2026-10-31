Created-GMT: 2026-10-03 17:18:39 GMT
Created-Local: 2026-10-04 00:18:39 +07 (+0700)
Coding-Agent: claude (fable-5-1, session b01ce68c-6db7-4975-9f3e-c00e23ae8437) and codex (gpt-6-astra, resume of thread 01a0f878-281b-7253-ac44-ff2402583d35)
Session-ID: b01ce68c-6db7-4975-9f3e-c00e23ae8437 (fable); 01a0f878-281b-7253-ac44-ff2402583d35 (astra)

# Task: C3-S2 — confirm or change the NaN dict/set key behavior

Role: Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-04 00:18 +07 | Status: active | Rationale: standing mob (owner-decision questions go to the architect pair)
- Model: gpt-6-astra | Assigned: 2026-10-04 00:18 +07 | Status: active | Rationale: standing mob partner; independent opinion on the same brief

Read-only. Answer independently; do not edit, do not run suites.

## What the ruling said, and what the engineer did

docs/design/yang.antlr.md 8.5.4 (the C3 + cross-ruling text you wrote, ~2078-2096) says dict/set keys become ruling-6
reduced-rational keys, `[:py.numeric/finite numerator-decimal denominator-decimal]`, and:

> Infinities get their own signed keys; NaN keeps today's behavior. Jing's private `exact-key` has this shape but collapses NaN
> and is not reused.

and 8.5.5 (float addresses) says C3-S2 "pins one NaN-key behavior, and deletes `numeric-key`".

The C3-S2 engineer pinned this (uncommitted in /Users/sto/workspace/datomworld-py-c3key1; see `git diff` of
docs/design/yang.antlr.md, src/cljc/yang/python/antlr/prelude.cljc `py/key`, and the new tests):

> Every NaN shares one key, `[:py.numeric/nan]`. A float here has no object identity to tell two NaNs apart, and Jing float64 content
> already makes every NaN one value, so a per-object NaN key cannot survive addressing; keying by the host double is not deterministic
> either (on the JVM host `=` answers true for one boxed NaN and false for two). This departs from CPython, where distinct NaN
> objects are distinct keys.

"Today's behavior" (the C1 key was the host double) is what the ruling said to keep; the engineer found it is not deterministic
across hosts and pinned one shared key instead. Observable effect in Python terms:
`x = float('nan'); d = {x: 1}; d[x]` works in CPython (same object) and here; `d[float('nan')]` is a KeyError in CPython
(a different NaN object) but returns 1 here; `len({nan, nan2})` is 2 in CPython and 1 here.

## Questions (give a recommendation, not a survey; one-line verdict first)

1. Is "every NaN is one key" acceptable as the pinned behavior? Alternatives to weigh against the datom.world invariants (one
   representation, same address on every host, no value identity for floats, derive don't persist):
   (a) one shared NaN key (the engineer's choice);
   (b) NaN can never be found again: each insertion makes a fresh unreachable entry, lookups of any NaN miss (this
       matches `nan != nan` but breaks CPython's `d[x]` for the same object, and needs an identity we do not have);
   (c) refuse NaN as a key with a TypeError-like guest exception;
   (d) something else.
2. Does the decision need a documented note in 8.5.4 beyond what the engineer wrote (it is the only recorded departure from
   CPython here)? Should the cross-host test pin all three observable cases above (same object, distinct objects, set length),
   and is the current test set enough?
3. Does the ruling-6 key for infinities and for 2^53 / 2^53+1 (two distinct keys) hold in the diff as written? Read
   `py/key` and the float-key helpers once for exactness holes (negative zero sharing 0/1, bool, huge floats, subnormals).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
