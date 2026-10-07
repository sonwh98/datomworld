Completed-GMT: 2026-10-05 09:36:10 GMT
Completed-Local: 2026-10-05 16:36:10 +07
Coding-Agent: claude (sonnet-5-5)

# D3 findings: the authenticated ledger reader

## Changed files
- New `src/cljc/yin/vm/ucf/holder/evidence.cljc`
- New `test/yin/vm/ucf/holder/evidence_test.cljc`

No landed namespace was edited. No git writes.

## API
`(evidence/read-evidence arbitration records l)`, where `records` is a sequence of `[author record]` in stream order from the origin. It is pure over data and holds no authority handle.

Only records authored by `arbitration` count. It folds them with `ledger/fold-record` from `ledger/empty-projection`. Answers:
- `{:yin.k/status :yin.k/ready :yin.k/binding b :yin.k/enrolled #{target-ids} :yin.k/prefix p}`. `b` is `custody/binding-evidence`, `p` is `input/inputs`, and `p` may be an empty prefix.
- `{:yin.k/status :yin.k/awaiting-grant :dao.lease/lease l}`. The history is whole but holds no grant of `l` yet. This includes no records, or only another author's records.
- `{:yin.k/status :yin.k/unsatisfied :yin.k/reason r}`. `r` is `:transport-error`, `:start-past-origin`, `:gap`, `:fold-defect`, or the binding's own no-evidence reason.

Unavailable answers carry neither `:yin.k/prefix` nor `:yin.k/enrolled`.

## Red and green
- **Red:** I wrote the test file first. The run failed to load, with `Could not locate yin/vm/ucf/holder/evidence.cljc on classpath`.
- **First green attempt:** 8 tests, 34 assertions, 1 failure. My own test expected `:fold-defect` for a record whose t jumped forward. That is correctly `:gap`. I changed the test to use a repeated record for the defect case. The implementation was unchanged.
- **Green:** `clojure -M:test -n yin.vm.ucf.holder.evidence-test` gave 8 tests, 34 assertions, 0 failures, 0 errors.
- **kondo:** `clj -M:kondo --lint` on both files gave 0 errors, 0 warnings. An unused `testing` referral was fixed.
- **Lanes:** JVM only, as instructed. Node and Dart lanes were not run (the orchestrator runs them at landing). The code is portable `.cljc`, but it is not verified on JS or Dart.
- **cljstyle:** not run, per the brief.

## Test coverage, by contract row
- **Equal to the authority's projection:**
  - The test builds the authority on the memory journal: offer, enroll, grant, two inputs, reclaim, regrant.
  - Binding, enrollment (against the authority's target keys) and prefix all equal the authority's own. The prefix has frontier 2, and `lease-1` gives an empty prefix of frontier 0.
  - The test reads `authority/projection` for comparison only. The runtime path never does.
- **Unavailable answers:**
  - A gap, a start past the origin, a transport error and a fold defect each answer unavailable with no prefix.
  - A repeated record is also a defect. A transport error alone with no other records is still unavailable.
- **Another author:** the same records attributed to "mallory" give `awaiting-grant` and no prefix. Interleaved mallory copies are ignored and give the same evidence as the clean read.
- **Reflection:** the evidence is read through a `dao.stream.remote` reflection, in the style of C5's test. It equals the direct read, and equals it again after a canonical-codec round trip. The same records attributed to another author prove nothing.

## Design notes and concerns
- A transport error is represented as data. A read that answered a stream outcome in place of a record takes the record's place, as `[author {:dao.stream/outcome ...}]`. The driver that accumulates reads must emit such an item on failure. This is an interface decision for D13 to confirm.
- `:start-past-origin` is reported only when the first record's t is above 0. A later forward jump is `:gap`. A t that is not an exact integer, or one that repeats or goes backward, is `:fold-defect`.
- I do not infer `:gap` from `fold-record`'s `:t-mismatch`, because `fold-record` returns the defect without the t. The density check runs before the fold instead.
- The namespace requires `authority.input` for `inputs`, which loads `yin.vm.ucf.authority` transitively. No authority value or handle is used. If you want the namespace free of the authority load, `input/inputs` would have to move, and that is a landed-code change I did not make.
- Cost is O(history) per call, as the plan's risk 3 says. There is no incremental fold API. A holder re-reading would re-fold from the origin.

## Incomplete work
None for D3's contract.
