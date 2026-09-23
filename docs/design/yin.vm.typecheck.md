# yin.vm.typecheck -- Types as facts, the gate as an observer

Status: design, proposed; not implemented. Subordinate to
[`datom.world.md`](./datom.world.md), [`dao.stream.md`](./dao.stream.md),
[`yin.vm.macro.md`](./yin.vm.macro.md), and the canonical tuple grammar in
[`yin.vm.code-as-tuples.md`](./yin.vm.code-as-tuples.md).

> A type is a fact beside code. A type *check* is an interpretation of those
> facts. A type *gate* is that interpretation given write authority on the
> medium evaluators read. None of the three is an evaluator concern.

---

## 0. Decisions

1. **Evaluators know nothing about types.** No evaluator has a type
   transition, instruction, flag, option, or check. An evaluator observes
   program rows and rejects tags outside its vocabulary. This is decision 1
   of `yin.vm.macro.md` restated for types.
2. **Type facts are rows beside code, never slots in it.** A type claim is
   an occurrence fact keyed by root address and occurrence path. It is not a
   slot of any code row, so it never enters a content address, the de Bruijn
   fingerprint, H, or R. Annotating a program does not change its identity.
3. **Certainty is provenance.** A claim's certainty is not a scalar level on
   the claim; it is the `m` of the row that asserts it. "Declared",
   "inferred", "runtime", and "checked" name *who minted the row*, and the
   `m` entity says so. Nothing on a claim row ranks it.
4. **Checking is a process over coordinated media.** The typecheck observer
   reads code packets on `typecheck-in`, projects source claims onto
   post-expansion coordinates via `macro-log` when composed downstream of
   macro expansion, synchronizes on an explicit causal watermark (`claims-t`),
   evaluates one pure checker over the tree and its relevant claims, and
   appends verdict rows to `typecheck-events`.
5. **There is no mode; there is topology.** Whether the observer is absent,
   reports, or gates is decided by which medium the evaluators are composed
   to read (section 3). The observer has no `:mode` option.
6. **Authority is write authority on a medium.** Whoever can append claim
   rows may claim. Whoever holds `program-out` decides what runs. The gate is
   nothing more than the observer holding `program-out`.
7. **Refusal is silence plus a verdict.** A gated observer refuses a packet
   by not forwarding it and appending a `:refused` verdict. It never signals
   an evaluator. A refused program never lands on an evaluator's medium, so
   it never acquires an executable identity.
8. **The checker is a parameter, not the design.** The observer fixes the
   media, row shapes, and authority. The type language and inference
   algorithm are a pure function supplied at composition time (section 4).
   This document commits to no type system.
9. **Dynamic is a type, not an absence.** The checker's lattice has a top
   element for "unknown". Whether a packet whose check reaches top is
   forwarded is a checker parameter; the boundary where it happens is
   always a verdict row (section 5).
10. **Verdicts cite their evidence.** A verdict row names the exact claim
    datom references (`claim-refs`) it consulted. A later observer can
    recompute the verdict from the packet and those rows without trusting the
    observer.

---

## 1. Why this is not a VM feature

`datom.world.md` axiom 2: data is syntax; semantics emerge through
interpretation. A type row is data. Reading it as a description of a value,
as a constraint to enforce, or as noise is three interpretations of one row,
and the row does not know which it will receive. `yin.vm.macro.md` already
made this move for macros: a macro is an ordinary lambda whose *caller* is a
process on the syntax side of a medium boundary. A type check is an ordinary
analysis whose *reader* is a process on the syntax side of a medium boundary.
The evaluator never sees the analysis because it never lands on the
evaluator's medium.

The blog post `public/chp/blog/yin-vm-ast-chinese-characters.blog` states
the representational half of this: static and dynamic typing differ in the
certainty of type facts, not in the kind of code. This document supplies the
operational half it leaves implicit -- the "compiler enforces it" in its
certainty table is an observer with write authority, and this is that
observer.

### 1.1 Prior art: Unison

Unison's types and abilities are gates: an ill-typed term is never hashed
into the codebase, and a term whose effects are unhandled is refused. That
guarantee is the value of its static layer. Unison obtains it by making the
checker, the codebase, and the hash one system. This design obtains the same
guarantee -- a refused program never gets an H or R -- by topology, while
keeping the checker outside every evaluator and outside every hash preimage.
Exact Unison type-system compatibility is not a goal; see section 8.

Two things Unison cannot express fall out of the topology here. First, the
check can be absent or advisory per composition, which is gradual typing by
wiring rather than by a language dialect. Second, because runtime effects are
already stream emissions, a declared effect set and an observed effect set
are two fact sets over the same program, and their disagreement is a query
(section 6). Unison checks a term once at `add`; this design can keep
checking a running program.

---

## 2. Media and rows

The observer operates over coordinated input media and two output media:

    claims -------------------+ (causal watermark claims-t)
                              |
    macro-log ----------------+ (coordinate projection; downstream composition)
                              v
    typecheck-in -----------> [typecheck observer] ---> typecheck-out
                                      |
                                      +---> typecheck-events

Two primary compositions exist for the observer:
1. **Downstream of macro expansion (default):** `typecheck-in` is
   `expander-out`, carrying fully macro-expanded tree packets
   `[expanded-root rows]`. To interpret front-end source claims, the observer
   consumes `macro-log` (`yin.vm.macro.md` section 8.3) to project source
   occurrence coordinates onto expanded occurrence coordinates.
2. **Upstream of macro expansion:** `typecheck-in` is `program-in`,
   operating directly on unexpanded source batches. Occurrence coordinates
   match source claims directly, and `macro-log` is absent or identity.

When composed downstream, an unexpanded macro call site remaining in
`expander-out` is a terminal diagnostic.

### 2.1 Code rows

Unchanged from `yin.vm.macro.md` section 1.1: `[address tag & slots]`, tree
packets `[root-address rows]`. The observer allocates no node identities and
rewrites no code rows. A forwarded packet is byte-identical to its input.

### 2.2 Claim rows and causal stream coordination

A claim is one d5 datom whose entity is an occurrence:

    [occurrence :yin.sig/type <type-value> t m]

`occurrence` is the reified `[root-address path]` pair; a claim about a
definition names the occurrence of its binder. `<type-value>` is a plain
value in the checker's type language, opaque to this document. `m` references
the metadata entity that minted the claim, and is the only certainty a claim
has:

    m says                       the blog's column   who writes it
    -------------------------    -----------------   ------------------------
    yang from source ascription  Declared            the front end
    an inference observer        Inferred            a syntax-side process
    a trace observer of a run    Runtime             an observer of VM traces
    this observer's verdict      (checked)           the typecheck observer

A row with no recognised `m` is a claim by an unknown party. The checker
decides what an unknown party's claim is worth; this document does not.

Claim rows live on their own append-only medium, `claims`. They are not on
`typecheck-in`, because that medium is a program medium and must stay one.

#### Coordinate projection across macro expansion

Front-end claims cite pre-expansion source occurrences
`[input-root source-path]`. However, macro expansion rewrites subtrees, strips
source coordinates, and generates new content addresses
(`yin.vm.macro.md` section 2.4).

When the observer is composed downstream of the expander, it consumes
`macro-log` (`yin.vm.macro.md` section 8.3). For each macro expansion event
`[event-address attempt source-batch origin parent-event input-root call-path
macro-root output-root error]`, the log records which source subtrees were
retained, which call sites were expanded, and the replacement subtree roots.
The observer projects source occurrences to post-expansion occurrences:
- Nodes in unexpanded subtrees preserve their relative paths and content
  addresses.
- Macro-generated subtrees acquire their post-expansion paths under the
  expanded root.
- A front-end claim whose occurrence was entirely eliminated by macro
  expansion is safely ignored or recorded as dead evidence.

#### Explicit stream linearization and bounded buffering

Reading two asynchronous streams (`claims` and `typecheck-in`) without a
causal barrier invites race conditions (a packet arriving before its claims)
and threatens memory bounds (unbounded buffering of unindexed claims).

To preserve explicit causality and bounded memory:
1. **Causal watermark (`claims-t`):** Each code packet is associated with
   `claims-t`, the transaction timestamp or stream offset on `claims` up to
   which all claims for that packet were committed. Downstream of the expander,
   `claims-t` is supplied by `source-batch` from the corresponding `macro-log`
   entry or via the batch envelope.
2. **Waitset barrier:** The observer advances its cursor on `claims` up to
   `claims-t` before evaluating the packet. If claims have not yet arrived up
   to `claims-t`, the observer yields or awaits that barrier.
3. **Bounded index lifecycle:** The observer indexes into local working memory
   only those claim rows whose occurrence matches nodes in the active packet
   (or whose pre-expansion occurrence projects into it). Once the verdict is
   emitted on `typecheck-events`, the temporary packet claim index is
   immediately discarded. No unbounded cross-packet accumulation occurs, and
   `dao.space.index` is never consulted.

### 2.3 Verdict rows

One packet produces one verdict, on `typecheck-events`:

    [verdict :yin.typecheck/root      root-address]
    [verdict :yin.typecheck/checker   checker-hash]
    [verdict :yin.typecheck/outcome   :accepted | :refused | :dynamic]
    [verdict :yin.typecheck/evidence  #{claim-refs}]
    [verdict :yin.typecheck/derived   #{[path type-value]}]   ; optional
    [verdict :yin.typecheck/defects   [{:rule r :path p ...}]] ; on :refused

`checker-hash` is the content hash of the checker function's own program
rows when the checker is a `yin.vm` program, or a declared descriptor hash
otherwise; equal hashes imply the same judgement over the same evidence.
`:evidence` contains `claim-refs`: the set of exact claim datom references
`[occurrence :yin.sig/type <type-value> t m]` (or their canonical content
hashes) consulted during evaluation, uniquely identifying the facts that
justified the verdict.
`:derived` lets the observer publish the types it inferred as new claim rows
with its own `m` -- an inference observer and a checking observer may be the
same process -- without those rows ever touching the code.

A verdict's `m` is this observer. Verdict rows are how the blog's highest
certainty is minted: a claim cited as evidence by an `:accepted` verdict has
been checked, and a Datalog query can say so.

### 2.4 Forwarding

On `:accepted`, the observer appends the input packet unchanged to
`typecheck-out`. On `:refused`, it appends nothing there. On `:dynamic`, the
checker parameter `:dynamic` decides (section 5). In every case the source
cursor advances and the verdict is appended first, so a reader of
`typecheck-out` can always find the verdict that admitted a packet.

---

## 3. Topology, not mode

Three compositions of the same observer:

    absent    expander-out ---> evaluators
              (no observer composed; fully dynamic; today's topology)

    report    expander-out ---> evaluators
              expander-out ---> [observer] ---> typecheck-events
              (evaluators read the unchecked medium; verdicts are advice)

    gate      expander-out ---> [observer] ---> typecheck-out ---> evaluators
              (evaluators read only what the observer forwarded)

The observer's code is identical in `report` and `gate`. The difference is
which medium the evaluators' cursors are attached to, and that is the
composing agent's choice, made where it constructs the streams. Unison is
the `gate` row with the checker fixed; a dynamically typed language is the
`absent` row. `report` is the position neither offers: every verdict of a
strict checker, none of its authority.

A composition may chain gates -- a claims-consistency observer, then an
effect observer -- each holding the next medium. Order is composition data.

---

## 4. The checker contract

The observer is parameterised by one pure function:

    check : packet claims -> verdict-body

`packet` is one tree packet; `claims` is the set of claim rows whose
(projected) occurrence lies in that packet; `verdict-body` is the outcome,
evidence (`claim-refs`), derived claims, and defects of section 2.3. The
function reads no host clock, stream, registry, or mutable state, and it is
total: an unsupported tag or a malformed claim is a `:refused` verdict with a
named rule, never a throw. Throws are reserved for defects and terminal
stream outcomes, as in the expander.

Because `check` is pure and its inputs are rows, it may itself be a `yin.vm`
program run under a step budget in a child VM -- the one general facility
`yin.vm.macro.md` already requires for macros. Nothing here needs a second
sandbox.

This document fixes no type language. The first checker (section 9, T1) is
a claims-consistency checker: it derives nothing and only refuses a packet
whose declared claims contradict each other at a meeting point (an
application whose operator has a declared arity that its operand count
violates; a binder declared twice with unequal types). That checker is
enough to prove the topology and the media; it is not a type system, and it
must not be mistaken for one.

---

## 5. The dynamic boundary

The checker's lattice has a top element, written `:yin.sig/any`. A packet
whose check reaches top somewhere -- a value from `eval`, from a stream
whose payload no claim describes, from a free name with no claim -- yields
outcome `:dynamic`, with the reaching paths recorded in the verdict.

The observer's checker parameter `:dynamic` is one of:

    :refuse    treat :dynamic as :refused (Unison-strict)
    :admit     forward the packet; the verdict records every boundary path

This is the gradual-typing cast boundary, but it is a fact rather than an
inserted runtime cast: the evaluator runs the same program either way, and
"where does unknown-typed data enter a term with a declared type" is a
query over `typecheck-events`, not a place the VM does anything. The known
cost of gradual typing survives -- a query finds the boundary; only `:refuse`
prevents it -- and this design names that cost rather than hiding it.

---

## 6. Beyond admission: declared versus observed

Every effect a program performs is already a row on a stream (axiom 1, host
boundaries in `datom.world.md`). An effect declaration is therefore one
claim row, `[occurrence :yin.sig/effects #{...}]`, and an *observed* effect
set is a Datalog query over the run's streams. Their disagreement is a
third observer's verdict -- a conformance observer over runtime media -- not
this observer's. It is named here because it is the case where "certainty
is provenance" pays: the declared set is `m`=front-end, the observed set is
`m`=trace, and a packet that was `:accepted` at admission can still be found
in violation while it runs. This document does not specify that observer.

---

## 7. What the observer must not do

- It must not rewrite, reorder, or annotate code rows. Forwarded bytes equal
  input bytes. A design in which the check "enriches" the program has put a
  slot in the code row and changed every hash downstream.
- It must not write to `claims`. It writes derived types to
  `typecheck-events` as verdict rows; a composition that wants them treated
  as claims routes that medium into `claims`. The loop, if any, is topology.
- It must not consult an evaluator, a store, `dao.space.index`, or a prior
  verdict. Its inputs are the packet, the projected claim rows up to the
  causal watermark `claims-t`, and `macro-log` when composed downstream.
  Symmetric ignorance is load-bearing ([`dao.space.index.as-observer.md`]
  (./dao.space.index.as-observer.md)).
- It must not maintain an unbounded, stateful internal buffer of `claims`.
  Claim rows are drained strictly up to `claims-t`, indexed for the active
  packet, and freed when the verdict is emitted.
- It must not be reachable from a VM option. `:yin/typecheck` as an
  evaluator flag is the expander's withdrawn `:macro-eval` again, and is
  refused for the same reason: it duplicates authority that a medium
  already carries.

---

## 8. Non-goals

- A type system. The checker is supplied; T1's is deliberately trivial.
- Unison type or ability compatibility. Unison is prior art for the gate
  property, not a target.
- Type-directed compilation. H and R are functions of the resolved tuples
  and never read claim rows; a typed and an untyped copy of a program have
  equal executable identities by construction (decision 2).
- Runtime type checks in any evaluator. A value's type at runtime is a fact
  a trace observer may mint; no evaluator asserts, checks, or carries it.
- Trust in claim rows. Who may write to `claims` and whose `m` a checker
  believes is the B7 name-environment and authority work already deferred
  by the de Bruijn designs; this observer inherits that gap and states it in
  its verdicts (`:evidence` is exactly the rows it chose to believe).

---

## 9. Phases

Each phase has a file box, a must-not-change list, and JVM, CLJS, CLJD,
kondo, and cljstyle verification.

### T0: contract and fixtures

    New: test/yin/vm/typecheck_contract_test.cljc
    Existing edits: none
    Must not change: expander, evaluators, tuple grammar, dao.stream

Freeze the media interfaces, claim and verdict row shapes (including
`claim-refs`), the `claims-t` causal watermark waitset barrier, coordinate
projection over `macro-log`, the forwarding rule, and the topology fixtures of
section 3 with a stub checker that accepts everything. Completion: `report`
and `gate` compositions differ only in cursor attachment; a refused packet is
provably absent from `typecheck-out` and present, with its verdict, on
`typecheck-events`; coordinate projection maps source claims accurately;
forwarded bytes equal input bytes over the expander's fixture corpus.

### T1: observer and claims-consistency checker

    New: src/cljc/yin/vm/typecheck.cljc
    New: test/yin/vm/typecheck_test.cljc
    Existing edits: none
    Must not change: T0 shapes, expander, evaluators

The observer as a `dao.stream` process with the same drain and error
discipline as the expander, coordinate projection over `macro-log`, the
`claims-t` waitset barrier, and the section 4 consistency checker.
Completion: every fixture verdict cites exactly the `claim-refs` it used; the
`:dynamic` parameter is exercised both ways; a checker that throws is
reported as a defect, never as a verdict; memory consumption is strictly
bounded per packet.

### T2: derived claims and the boundary query

    Existing source: src/cljc/yin/vm/typecheck.cljc
    Existing edits: test/yin/vm/typecheck_test.cljc
    Must not change: claim row shape, code rows

Verdicts publish `:derived`; a fixture composition routes
`typecheck-events` back into `claims` and reaches a fixpoint; the section 5
boundary query is a pinned Datalog fixture over `dao.space.query/q`.

### Deferred

- Any real type language (the checker parameter's first non-trivial value).
- The conformance observer of section 6.
- Claim authority and provenance trust (B7).

---

## 10. End condition

A composition can put the same expanded program through no observer, an
advisory observer, or a gating observer by changing only where evaluators'
cursors attach; a refused program never acquires an H or R; every verdict is
recomputable from the packet and the `claim-refs` it cites; coordinates project
deterministically across macro expansion; and no evaluator, lowerer, or hash
function reads a claim row.
