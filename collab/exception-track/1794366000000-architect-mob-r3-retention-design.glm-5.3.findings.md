Completed-GMT: 2026-11-10 23:09 GMT
Completed-Local: 2026-11-11 06:09 +07 (+0700)
Role: Architect (mob seat 3 of 3) — read-only ruling on `exception-r3-impl` at `c9c5533c`
(worktree `/Users/sto/workspace/datomworld-r3-orch`), evidence
`1794365000000-reviewer-r3-identity-confirm.gpt-6-astra.stdout.log` (read in full),
`1794363200000-reviewer-r3-fix-confirm.gemini-4-argon-high.stdout.log`, ruling v2
(`1794960000000-owner-ruling-exception-uniformity-v2.md`), the R4 brief
(`1794362000000-engineer-r4-equivalence-proof.prompt.md`), and the code. No files edited.

# RULING: how clojure retains a condition without exposing it to guest observation

## Verdict on the two findings

| Finding | Verdict | Why |
|---|---|---|
| (a) content replay / mutation | REAL, P1, but confined to the protocol-condition form | the witness authenticates the `[visible condition]` pair, not the association "this cell is the retained carrier" — `(throw (cell/new (cell/get e)))` and `(cell/set! e (cell/get other))` both defeat it |
| (b) observation leaks | REAL, P1, and STRUCTURAL for anything the catch binds as a wrapper | the leak list (`(= [e] [orig])`, map-key `get`, `count`, `assoc`, caught-`nil` truthiness, aliased predicates) is not an incomplete routing table; it is the open-ended observation surface of a first-class guest value |

Both are defects of ONE design decision: the wrapper cell sits in the **caught-value
position** (error-outcome slot 1, bound directly by the `try` lowering at
`clojure.cljc:1246-1250` from the `condition->outcome` `:yang.call/thrown` arm at
`clojure.cljc:1656-1664`). Neither is a defect of the outcome-token channel itself.

## The structural diagnosis

**(b) cannot be patched by more routing.** The unwinding is syntactic
(`dsg__visible_of` inserted at known heads: `=`/`==` all operands at
`clojure.cljc:1815-1818`, collection operand of `get`/predicates at `:1821-1832`,
keyword calls at `:1850-1852`, the `visible-predicate-heads` list at `:931-935` routed
at `:1279-1286`). Guest observation of a value is not a closed set of spellings: the
value can be nested in a literal, stored in a cell, passed through a variable-headed
call, handed to another unit or another language, and observed there. A wrapper in
guest hands can impersonate the payload only under observations the compiler
enumerated in advance. Indistinguishability under *arbitrary* observation is achieved
exactly when the caught value **is** the payload — nothing else.

**(a) cannot be closed by contents authentication alone.** The witness comparison
(`clojure.cljc:972-974`, re-deriving `dsg__condition_witness` and comparing by
closure equality — owner + payload + captured env, `values.cljc:43-76`,
`data.cljc:484-493`) proves the triple is compiler-authentic. It cannot prove this
*cell* is the carrier the compiler minted: `cell/new`/`cell/get`/`cell/set!` are
admitted guest exports (`engine.cljc:2773-2790`), and `authentic-ref?`
(`engine.cljc:332-352`) certifies only "the heap issued this id with this seal" —
nothing about who allocated it or whether its contents changed. This is precisely the
defect class §8.10.12 names: *"the distinguishing fact (did the heap issue this?) is
not in the value. It is in the heap. Only the issuer can answer."* For retention the
distinguishing fact is stronger — *did the issuer mint this cell as a retained-condition
carrier, unchanged?* — and today the heap holds no such fact. It must be put there.

**The decisive precedent is master.** At `887cc273` (pre-R3) `throw` lowers to
`[error-tag v]` with the payload raw, the catch binds slot 1 as-is, and Round 0's
`[:clojure :python :round-trip]` was green on all three lanes: the channel delivers
payload identity fine **when nothing wraps it**. The wrapper was introduced for R3
protocol-condition retention and over-applied to ordinary throws. §R2(1) regressed,
it was not discovered impossible.

## (A) The patch design — three commitments

### 1. Ordinary `:yang.call/thrown` conditions deliver the payload RAW (restore master)

`condition->outcome`'s `:thrown` arm (`clojure.cljc:1656-1664`) and its
unqualified-value arm (`:1679-1682`) stop minting a wrapper; the error-outcome slot
carries `(:yang.call/value condition)` directly. The catch binder
(`clojure.cljc:1246-1250`) and the `entry-body` ordinary path (`:1435-1436`, fresh
`yang.call/thrown` at the public boundary) then behave exactly as master did.

This closes **every** leak in (b)'s table *by construction*, not by routing: `e` is
the payload, so `(= [e] [orig])`, `(get {orig :found} e :missing)`, `(count e)`,
`(assoc e :a 2)`, caught `nil`, `((fn [p] (p e)) vector?)`, and passage into other
units/languages all answer as the payload with zero compiler knowledge. The round-trip
row's `(= e original)` (`exception_legs.clj:44`) holds without the `=` routing.

### 2. The wrapper survives ONLY as the documented local form for qualified protocol conditions

For `:yang.call/not-callable`, `:invalid-invocation`, `:unsupported-binding`
(`clojure.cljc:1665-1676`, refusal arms `:1699-1702`, `:1719-1722`, `:2801-2807`) the
wrapper stays — this is what R3 actually needed and what §8.10.7 requires: a
"documented local exception form retaining the complete original condition," opaque
with accessor semantics, exactly python/php's `ForeignException` stance. §R2(1) has no
antecedent here: the guest never held the condition map as a value before the catch
(the condition was minted by the callee's bridge or the protocol check), so there is
no "original identity" to restore — the generated form's observation semantics are the
local form's own, served by the existing routing (`ex-message`/`ex-data`
`clojure.cljc:989-1002`, keyword/`get`/`=` routing) which now only ever sees wrappers.

The `saved-protocol-*` rows (`exception_contract.clj:123-128`) — `retain` saves, `later`
rethrows from a new activation — are why the carrier must remain a first-class value:
provenance has to travel with the saved value across activations and units.

### 3. Identity-bound retention: an issuer-side mark on the wrapper's heap entry

Close (a) with the §8.10.12 pattern — the issuer answers, never the shape:

- **Mint**: the wrapper cell is allocated by a compiler-internal, *unpublished* mint
  (the `dsg__*` reserved-prefix regime, like the `dsg__call_token` machinery at
  `clojure.cljc:2840`) writing the triple plus an engine-side `:retained` mark on the
  heap entry. Guest `cell/new` (`engine.cljc:2773-2778`) cannot produce the mark.
- **Freeze**: `:cell/set!` (`engine.cljc:2785-2790`) refuses marked entries, with the
  module's existing strict-refusal convention (as `check-ref!` refuses non-cells).
  `(cell/set! e (cell/get other))` refuses; contents are frozen for the wrapper's
  entire lifetime — §8.10.7's "identity-owned association... survives storing or
  returning the wrapper."
- **Query**: a pure `cell/retained?`-family query (same vocabulary family and
  discipline as `cell/authentic?`, `engine.cljc:2796-2799`) so the guest-lowered
  `dsg__condition_retained` requires mark + frozen contents in addition to the
  existing shape pre-filter. Replay `(throw (cell/new (cell/get e)))` decodes as an
  unmarked cell → **ordinary thrown payload** → a fresh `:yang.call/thrown`, which is
  exactly the R5 `:forged-retention` expected semantics.

No ambient registry: the association is one heap-entry flag whose lifetime is the
cell's, task-sealed like every other heap fact. With mark + freeze the witness closure
becomes redundant and may be **retired** — the triple is issuer-guaranteed — so the
error path mints strictly less than today: nothing at all for ordinary throws, one
marked cell for protocol conditions. Slice-5's purity bar (constant-depth tails, no
mandatory `%capture`, closures cell-free) is *better* satisfied than at `c9c5533c`,
not worse.

**How `throw e` still forwards the condition.** Ordinary: `(throw e)` is a fresh
`:yang.call/thrown` with `e` = payload — byte-identical to the original mint for
source-thrown values (the mint *is* `{reason :thrown, value v}`), payload-identical
for foreign-arrived ones, which is what the rows assert (`:reason` +
`:payload-identical`; `exception_legs.clj:167-190` — no ordinary row asserts
full-map condition identity). Protocol: `e` **is** the marked wrapper; the boundary
decode at `entry-body` (`clojure.cljc:1437-1438`) and `strip-top-outcome`
(`:2599-2625`) forwards the stored condition byte-for-byte — P1-3's no-field-deletion
semantics unchanged.

## What survives of R3; what the round must do

Survives: P1-1's mechanism (superseded and strengthened by the mark), P1-3 byte-for-
byte storage, P2-4's strengthened assertions, the accessor routing, `ex-message`/
`ex-data`. Changes: the two ordinary arms of `condition->outcome`; the engine
extension (heap-entry mark + one refusal arm + one query); retire-or-keep the witness
(keeping it costs nothing new). New tests, straight from astra's probe table, on both
walker and semantic lanes: nested `(= [e] [orig])`, map-key `get`, `count`, `assoc`,
caught-`nil` truthiness, aliased predicate, content replay, `set!`-swap — plus the
existing round-trip and saved-protocol rows re-run. The engine mark needs Node/Dart
parity and a four-seat doc note in §8.10.7/§8.10.12 (vocabulary addition, per the
§8.10.12 cross-seat process); it is a bounded `yin/vm` change and so sits outside the
R4 brief's clojure.cljc-only scope — it warrants its own small round or an explicitly
widened brief.

**R4 proceeds as staged.** The astra finding is lane-*uniform*: all three lanes share
`condition->outcome` and the catch binder, so this was never a private-vs-public
equivalence divergence. After the patch the error slot's guest-visible meaning no
longer depends on the carrying lane, which is what makes the §8.10.9 equivalence
argument tractable. The proof should be ordered *after* this patch; proving
equivalence of a design that violates §R2(1) on all three lanes alike would prove the
wrong thing.

## Why not (B)

The finding does not constitute the R4 equivalence failure. (B)'s premise — "the
outcome channel cannot deliver payload identity to ordinary guest code" — is false:
master delivered it (Round 0 green), and the patch restores it in one arm. The
continuation lane binds the *same* payload with the *same* fresh-`thrown` rethrow
semantics, and it **still needs commitment 3**: the `saved-protocol-*` rows are
lane-independent, and no lane gives clojure values identity — only the issuer-side
mark does, on either lane. So the mark is not a cost of choosing (A); it is on the
critical path of every compliant design. The port would re-lower every call site
across three lanes and the cross-unit admission surface, forfeit the owner-preferred
optimization, and buy no contract compliance the patch lacks.

## Why not (C)

In clojure every value is a valid thrown payload, so §8.10.7's reception rule 1
("a payload valid as an exception in its own runtime") covers ordinary payloads
*without remainder* — there is no narrower class to carve out. Accessor-only
semantics would bless the regression of a row Round 0 proved green and re-write the
ruling to fit one implementation. (C) is the fallback of last resort for a design
vacuum; a compliant, small design exists, so narrowing is unjustified — and the
`[:clojure :python :round-trip]` row should keep asserting exactly what it asserts
today: `home` returns true, i.e. `(= e original)` and exactly-once cleanup, on both
lanes.

---

**(A)** — the caught value must BE the payload (ordinary throws delivered raw, master's proven semantics), with the wrapper demoted to the opaque local form for qualified protocol conditions whose retention is bound to a task-issued, frozen heap identity; nothing about the wrapper's failure is an outcome-channel equivalence defeat, so the owner-authorized (B) fallback is not triggered.
