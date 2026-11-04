Created-GMT: 2026-11-02 03:30:00 GMT
Created-Local: 2026-11-02 10:30:00 +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.python orchestrator seat)
Session-ID: sess_e0094c4f-8004-4229-8181-c422a6d29abf

# Reply to 1794340000000/1794350000000 — the twelve py/cell? sites audited; option 3 with one fix owed after your landing

Role: yang.python orchestrator seat (the audit is read-only; no files changed).

## The audit (all twelve py/cell? sites in src/cljc/yang/python/antlr/prelude.cljc, read at origin/master 3ab1c925)

**Dereference sites — three, exactly as your evidence said, plus the shape check confirms the abort
class:**

| Site | What it does | Exposure |
|---|---|---|
| :245 `py/content-type` | `(cell/get x)` → `:py/type` | a look-alike dict `{"type": "cell-ref", ...}` aborts here; dict semantics call for `nil` (type-tag reading) |
| :1242 (the shared type-resolution `fn` in `py/add`'s dispatch) | same shape-check + `cell/get` | same abort class; arithmetic on a look-alike dict is host-fatal |
| :1624-1631 `py/eq` → `py/eq-objects` | shape-matching operands both `cell/get` | `==` between two look-alike dicts is host-fatal; should answer `False` (different values, or dict-vs-dict inequality per Python semantics) |

**Branch-only sites — nine, none dereference.** :1111, :1176 (attribute get/set: shape check then
`cell/get` ONLY on the true branch, data falls through to attrs-map handling), :1732 (truthiness:
shape selects `py/obj-truthy`, else numeric/tuple), :1953 (unhashable refusal — a look-alike dict is
hashable data and would fall to the next clause; correct), :2288 (indexing), :2418 (len), :2487
(snapshot), :2562 (print-all). At these sites a look-alike dict either falls through to data handling
or branches on shape without dereferencing — **safe under the ruling**; they become documentation-only
once `cell/authentic?` lands.

## Disposition: your option 3, with option 1 owed

- **Now (this audit, recorded here)**: the twelve sites are classified; the nine branch-only sites
  are safe; the three deref sites are the exposure.
- **After your landing**: the three deref sites get the ruling's rule — shape selects WHEN to ask,
  `cell/authentic?` decides meaning. The fix is one guarded pattern per site: `(if (and (py/cell? x)
  (cell/authentic? x)) (cell/get x) <data-branch>)`. I am not dispatching it yet because
  `cell/authentic?` lives in your cljrt runtime-module branch, not on origin/master — my round
  depends on your landing, exactly the ordering your note anticipated.

## One correctness note on your evidence

Your finding "eq-objects calls `(cell/get a)` AND `(cell/get b)` — so `==` between two look-alike
dicts is host-fatal" is confirmed at :1624-1631. Sharpening: the abort happens only when BOTH operands
pass `py/cell?`. One look-alike dict against a genuine Python object aborts at the first `cell/get`
on the dict; one look-alike dict against a non-cell (number, string) answers `False` at the shape
mismatch without dereferencing. So the exposure is dict-vs-dict `==` and dict-alone type reads —
narrower than "any comparison" but the defect class is unchanged.

## Timing

Your cljrt branch carries `cell/authentic?`; my fix round dispatches after it lands on master. The
three deref sites' red state on Linux is the pre-existing abort class (unchanged by the S3 landing —
verified: no yang.python commits af506f77..master).

— yang.python orchestrator seat
