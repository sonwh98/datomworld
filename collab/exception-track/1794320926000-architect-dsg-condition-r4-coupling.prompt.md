Created-GMT: 2026-11-10 14:28 GMT
Created-Local: 2026-11-10 21:28 +0700
Coding-Agent: agy
Session-ID: pending (provider-generated)
# Task: architect adjudication — is `:dsg__condition` load-bearing for R4, or strippable at the escape boundary?
Role: Architect
Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-10 21:28 +0700 | Status: active | Rationale: argon is reserved for architecture review (routing-status 2026-11-09); this is an architecture adjudication, the intended use.

This is a NARROW adjudication, not a review. One question, two possible answers.

## Context

The exception track's Clojure R3 round (ruling v2 §R3:
`collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md` — read
§R3 and §R5) has an implementation on branch `exception-r3-impl` @ `98519957`
(diff: `git diff 8cd9fc92..98519957 -- src/cljc/yang/clojure.cljc`). It stops the
public escape's unconditional `:yang.call/thrown` reclassification so protocol
conditions forward unchanged. Verification found one consistent defect:

```
expected: {:yang.call/reason :yang.call/not-callable, :yang.call/data {:yang.call/callee 5}}
actual:   {:yang.call/reason :yang.call/not-callable, :yang.call/data {:yang.call/callee 5}, :dsg__condition true}
```

The forwarded condition carries an extra `:dsg__condition true` key. `dsg__` is the
compiler's reserved prefix (`clojure.cljc:410`) — the namespace guest source may never
bind. The marker also contaminates the ordinary not-callable path (a pre-existing test
fails with the same signature). R3's contract: "forwards UNCHANGED, every evidence
entry intact, payload identity kept."

## THE QUESTION

**Where does `:dsg__condition` come from, and is it load-bearing?**

Specifically, answer one of:

**(A) Strippable.** The marker is incidental to how the R3 diff builds the forwarded
packet (e.g. set during construction for a membership test, never read afterward).
The fix strips it at the escape boundary (or stops writing it) with no effect on any
other mechanism. Name the exact construction site and prove no reader exists.

**(B) Load-bearing for R4.** The marker IS the intra-unit token channel's
representation (ruling §R4: the outcome-token channel, clojure.cljc's
`:clj/escape-token` / `dsg__call_token` authentication), or is otherwise read by a
mechanism that must keep working (entry-body tail, private lane, admission scopes).
Then the fix must carry the distinction SOMEWHERE ELSE — and you must say where
(how does the compiler know, on a later capture/re-entry, that this map is a
condition it built, if not from this field?) and what the R3 round does vs defers
to R4.

## Where to look

- `git show 98519957 -- src/cljc/yang/clojure.cljc` — every `:dsg__condition` site.
- `git grep -n 'dsg__condition'` on the branch — ALL readers and writers, including
  tests.
- `yang.clojure-test` failure
  `test-value-invocation-refusals-are-terminating` (clojure_test.clj:2598) — proves
  the leak reaches the pre-R3 path; find the shared construction site.
- The R4 region: `condition->outcome` (1518–1530), `raise` lambda (1527–1531),
  packet auth (1548–1558, 1590–1602) on the branch.

## Deliverable

One ruling: **(A)** or **(B)**, with file:line evidence for every claim — the writer
site, every reader (or the proof none exists), and the minimal fix instruction for the
implementation round (which site to change, what the corrected map is, and — if (B) —
what R4 must own). Do NOT implement the fix. Do NOT review the rest of the diff (a
separate full review follows after the fix round). If you find the marker is
load-bearing in a way that changes R3's contract, say so explicitly — that is a
valid and important answer.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
