Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: P7-b part M — the FULL selection+registry corpus, all :oracle :pending. Build the evidence set; do NOT guess-adopt dispositions.

Role: Engineer. Branch p7b-ticks @ part L tip (pushed; 15/15 green incl include gate).
CONTEXT: the remaining selection gaps (do/while, for, foreach, switch statement selection;
general adjacent suppression; N=0 inside N>1 count preservation; membership change DURING
dispatch) cannot be honestly hand-derived — real Zend behavior is the judge, and the
acceptance bar is "tick counts equal PHP's". So THIS session produces the corpus; the
ORACLE pass adjudicates; a final session adopts per the actual records.

## Scope
1. Add fixtures (red-first where the current engine clearly deviates, otherwise
   record current behavior): every compound statement form above with ticks around and
   inside them; adjacent marked statements; nested declare(ticks=0) inside declare(ticks=2);
   register_tick_function fired DURING dispatch adding/removing entries mid-request; callback
   that unregisters itself; a generator yielding across a tick site inside a foreach.
2. Every new fixture: hand-derived expectation ONLY where the decision table already settles
   it (cite the row); otherwise set :expected from the CURRENT ENGINE output but keep
   :oracle :pending and mark it "oracle-adjudicate" in a table in your findings — that table
   is the shiva corpus list.
3. Do NOT change engine behavior this session unless a change is settled by the table
   (adjacent-suppression generalization IS settled — implement it; the rest wait).
4. Gates: existing 15/15 + include stay green; kondo/cljstyle; push per batch.
Findings: collab/yang-php/${TS}-engineer-p7b-ticks-m.gpt-6.1-terra.findings.md — MUST end with
the oracle-adjudicate table (fixture id -> question answered).
