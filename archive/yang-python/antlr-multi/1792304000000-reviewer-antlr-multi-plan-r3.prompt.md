Created-GMT: 2026-10-17 10:15:00 GMT
Created-Local: 2026-10-17 17:15:00 +07 (+0700)
Coding-Agent: codex
Session-ID: pending (capture thread.started; seat-assigned 96e4ab4e-8a2c-435d-87a0-e9ede52272c5)

# Task: Re-review round 3 — the two P1 corrections in the antlr-multi plan

Role: Lead System Architect (the same reviewer; the author's reconcile r2 routes back per protocol)

THE V3 PLAN (the delta from v2): /Users/sto/workspace/datomworld/collab/1792302000000-architect-antlr-multi-reconcile-r2.claude-cloud-fable.findings.md
— the revised sections addressing your two P1s (the v2 for context:
collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings-v2.md).

VERIFY against your two repro-backed P1s:
1. The synthetic-token justification: the :synthetic-policy map keyed on grammar role (NEWLINE :at-end,
   DEDENT :always, EOF always, other types never); the oracle keys on type; the exporter keeps char-index
   evidence; the goldens prove the two agree; the canonical synthetic text (empty, "<EOF>") removes the
   runtime getText fallback; the one-edit change to the retained JVM exporter; the twelve-row mutation
   table + three acceptance fixtures on every host. Your DEDENT {:text "" :span [16 16]} accepted; a
   collapsed NAME rejected by type.
2. The error coordinates: the span (source coordinates from char indexes with a boundary invariant) vs
   ANTLR's line/column captured verbatim with bounds only — explicitly not required to agree; the cause
   documented from the patched helper; the conformance corpus gains helper-NEWLINE/DEDENT error fixtures
   pinned with their disagreeing coordinates as expected values. Your {:line 3 :column 0 :span [13 14]}
   case handled.
3. The original P1-class properties preserved (the §1.3.6 rewrite names which rule establishes each);
   the ledger rows 10/11; nothing else regressed.

Verdict: end with exactly one line "Verdict: READY (sign-off granted)" or "Verdict: REQUEST CHANGES" with
findings. Begin the final response exactly with:
Completed-GMT / Completed-Local
