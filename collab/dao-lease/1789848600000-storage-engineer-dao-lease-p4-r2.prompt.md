Created-GMT: 2026-09-19 21:17:00 GMT
Created-Local: 2026-09-20 05:17:00 +07 (Indochina Time)
Session-ID: f69044f8-7920-435a-98be-f70b24d6181d (resumed — your Phase 4 session)
# Task: dao.lease Phase 4 — reconciliation round r2

The review found 1 P1 + 5 P2 (+ P3s). Read
`collab/1789848000000-reviewer-dao-lease-p4.claude-fable-5-1.findings.md`
(this tree's copy). All accepted. Scope: the same three files. Failing
test first for the P1 and each P2.

Dispositions (all ACCEPT):

- **P1 nil :self:** add `(check-assembly! (some? (get config :self)) …)`
  to `initial-judge` (make-judge inherits); add the matrix row and the
  nil-resolver-never-counts-as-grantor test.
- **P2 declaration does no work:** keep `:medium` on the wired entry AND
  add the two derived refusals the review names: refuse `:durable? true`
  with a `:host-values` medium, and refuse an `:evict-oldest` capacity
  below `:drain-budget`. Tests for both. Docstring states what
  compatibility does and does not mean.
- **P2 tolerance:** `make-judge` requires an explicit tolerance
  (`{:ms 0}` is the zero); `initial-judge` keeps nil-means-zero; add the
  row and test.
- **P2 holder outbound medium:** `make-holder` requires `:writer` (with
  declaration) and returns it; test added.
- **P2 end-to-end holder half:** add the composed both-halves cycle per
  the review: holder observes on its `:fact` medium, renews when due onto
  the judge's medium, stops; assert the `:release` lapse.
- **P2 sketches:** served-connection resolver reads `:ws/attachment` from
  an envelope declared `:envelope-key`; drive one real `serving` session
  under `#?(:cljd nil :clj …)` whose reclaim calls the real close path;
  replace the vacuous no-`:lapsed` assertion with a carriage step copying
  writer facts to the holder's medium, proving `:lapsed` is filtered out.
- **P3s:** swap `check-cadence!`/`check-units!` order; capacity required
  only for `:evict-oldest` plus a positive `:complete` case; docstring
  fix for "no stream exists" → "nothing is wired"; tick driver subtracts
  a creation-time baseline and `:cljd`-first conditional; `refusal-key`
  asserts an explicit `:refused` key you attach to each refusal. The
  `:scope`-label docstring softening: apply as reviewed.

After fixing: focused composition lane, full JVM lane, CLJS lane; report
exact counts. Single simple commands; no staging or commit.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: per-finding disposition, tests added, exact counts.
