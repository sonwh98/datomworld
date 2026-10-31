Completed-GMT: 2026-10-30 20:12:43 GMT
Completed-Local: 2026-10-31 03:12:43 Asia/Ho_Chi_Minh

SIGN-OFF: denied. Scope: `63674368..12feb7d1` on `yang-common-call`. One required change remains.

**P2 — The invocation admission gate skips argument evaluation on refusal.** In [clojure.cljc:1305](/Users/sto/workspace/datomworld-yang-common-call/src/cljc/yang/clojure.cljc:1305), positional, named, and options operands are evaluated only in the admitted branch. This violates §8.10.4’s source evaluation requirements.

Using the existing profile-raise harness, I independently probed:

```clojure
(yang.call/invoke
  5 (probe/note [21]) (probe/note []) profile-raise)

(yang.call/invoke-with
  5 (probe/note [21]) (probe/note []) profile-raise
  (probe/note {}))
```

At parent `abbfb998`, these record `[21]`, `[]`, and—where supplied—`{}` before the refusal. At `12feb7d1`, **none of those argument effects occur**; only the refusal is recorded. Confirmed on both walker and semantic lanes.

Required fix: evaluate and bind all operands exactly once, left to right, before the witness check; use those bindings in both branches. Add regressions covering refused callees through both operators, including options evaluation.

The prior admission and manifest findings are otherwise addressed. The reported source-spelled raise limitation remains non-blocking under the existing ruling.

After correction, refresh verification at the final commit. Node/Dart completion and the repository’s required `dao.stream` gate remain merge conditions. No tracked files were changed during this review.