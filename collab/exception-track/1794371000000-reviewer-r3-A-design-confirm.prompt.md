Completed-GMT: 2026-11-11 00:55 GMT
Role: Reviewer + scope adjudicator (confirmation round on the mob's (A) design)
# Confirm the (A) design implementation AND adjudicate its VM scope expansion

## Background
Your round-3 probes (replay/mutation + the seven observation leaks) forced a mob. Verdict: 2/3 (A)
[glm-5.3, qwen3.8-max], 1/3 (B) [muse-wonder]. The (A) design: ordinary `:yang.call/thrown`
payloads delivered RAW (so `e` IS the payload, every leak closes by construction, the per-call-site
router is deleted); the wrapper survives ONLY for qualified protocol conditions; retention bound to
heap identity. muse-wonder — the (B) author — implemented it at **fbd8da0f** on `exception-r3-impl`
(`git diff c9c5533c..fbd8da0f`). Mob record:
collab/exception-track/1794369000000-mob-record-r3-retention-design.md.

## What the implementation did
- `src/cljc/yang/clojure.cljc`: raw ordinary payloads; protocol-only wrapper; router deleted.
- **`src/cljc/yin/vm/engine.cljc` (+52)**: a `:retained` mark written only by a new
  compiler-internal `:cell/new-retained` effect; `:cell/set!` refuses marked cells; a pure
  `retained-ref?`/`:cell/retained?` query; `heap-write` preserves the mark.
- **`src/cljc/yin/vm/module.cljc` (+30)**: the module surface for the retained mint/query.
- `test/yang/clojure_test.clj` (+205): the probe battery.

## Your job — two parts

**Part 1 — rerun the probe table (authored by astra, reproduced in your brief).** At fbd8da0f (worktree /Users/sto/workspace/datomworld-r3-orch), on both walker and semantic lanes, verify every probe
from your round-3 table now answers the payload: `(= e orig)`, `(= [e] [orig])`,
`(get {orig :found} e :missing)`, `(count e)`, `(assoc e :a 2)` then read, caught `nil` falsey,
`(vector? e)` AND `((fn [p] (p e)) vector?)`, and `(= e original)` with `original` = a `cell/new`
value. Then the forgery probes: `(throw (cell/new (cell/get e)))` and
`(cell/set! e (cell/get other))`. Report actual values, not claims.

**Part 2 — adjudicate the VM scope.** Is the `:retained` heap mark the right seam?
- Does it break any yin.vm invariant (heap-entry shape, serialization/lift, the `:semantic`/
  `:stack`/`:register` lanes, snapshot semantics)?
- Is `:cell/set!` refusing marked cells a safe general rule, or does it need to be scoped?
- Is the new module surface reachable by guest code (it must NOT be)?
- Is this acceptable within the track (the owner authorized the (B) fallback, not a VM change)?
  If it needs an owner ruling, say so precisely and say whether the clojure-side work can land
  WITHOUT it (i.e. is the VM change separable?).

## Deliverable
Part-1 table of actual results; Part-2 verdict (SAFE / NEEDS-OWNER / REJECT with evidence); final
line: **CONFIRMED** or **CHANGES STILL REQUIRED**. Read-only. Begin with Completed-GMT/Completed-Local.
