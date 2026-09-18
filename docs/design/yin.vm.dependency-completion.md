# yin.vm dependency completion — the work-item fixed point (U14 design)

> **Status: Proposed (design round for U14).** Refines `yin.vm.code-as-tuples.md`
> §7.7.2–7.7.3 and UCF §7.6.1–7.6.3 (r3, as amended by U12) into data structures
> and a traversal. Supplies the `:yin.k/requires`, `:yin.k/store`, and
> `:yin.k/scheduler :yin.k/parked` inputs to the lift; it is not the lift.
> Implementation follows as U14 once U13 lands.

## 1. Scope and non-goals

- Computes, for one quiescent `SemanticVM`, the conservative closure UCF §7.6.1
  specifies: `:yin.k/requires` (every field), the reachable store slice (§7.6.2),
  the referenced parked slice (§7.6.3), and `:yin.k/discovery`.
- Does not encode values (§7.5), does not lift, does not lower, does not check
  satisfaction (§7.6.5). It hands those phases finished sets.
- Depends on U6 (`yin.vm/ast-requirements`, `segment-requirements`,
  `footprint-table`), U7 (`yin.vm/occurrences`, `free-names`), U12 (UCF text),
  and on U13 through the registry interface in §6 — nothing else.

## 2. Vocabulary

| Term | Meaning |
|---|---|
| address | a content address `:segment/sha256-…` (`dao.jing/segment-key`), of a segment vector or a tree root row |
| context | the finite abstraction of a captured environment, §4 |
| work item | `[address context]`, the unit of analysis (§7.7.3) |
| code facts | what one address contributes regardless of context (§5.1) |
| value facts | what one context contributes regardless of address (§5.2) |
| obligation | a free `:var`/`:variable` name from reachable code (§7.7.2) |
| discharge | the ruling on one obligation: by store key, by primitive profile, by module export, or none |
| slice | the sub-map of the emitter's store (or parked map) that travels |

## 3. The walk state

One persistent map, threaded through the algorithm, monotone in every field
(nothing is ever removed; that is what makes the fixed point a fixed point):

    {:contract    "v2"
     ;; code, fetched once per address (§7.7.3 "code once")
     :code        {address → {:kind   :segment | :tree
                              :facts  code-facts          ; §5.1, or nil when :missing
                              :status :loaded | :missing}}
     ;; work items
     :items       #{[address context]}     ; analyzed
     :frontier    queue of [address context]   ; discovered, not yet analyzed
     :contexts    {context → value-facts}  ; §5.2, memoized per context
     ;; discovered values (the §7.7.3 "discovered value set")
     :values      {:closures   #{[address entry context]}
                   :streams    #{store-key}
                   :cursors    #{store-key}
                   :parked     #{pid}
                   :primitives #{sym}}
     ;; obligations and their discharge
     :obligations {sym → :store | :primitive | :module | :undischarged}
     ;; the two slices, §7.6.2 / §7.6.3
     :store-slice  {store-key → value}     ; pulled from (:store vm), values not yet encoded
     :store-named  #{store-key}            ; keys code names that the store does not hold
     :parked-slice {pid → parked-record}   ; pulled from (:parked vm)
     ;; the requirement fields, accumulated
     :requires    {:yin.k/segments #{address}
                   :yin.k/primitives {sym → profile}      ; §6
                   :yin.k/modules {name → manifest-address}
                   :yin.k/effects #{effect-id}
                   :yin.k/ffi-ops #{op}
                   :yin.k/streams #{identity}
                   :yin.k/cursor-profiles #{transport}}
     ;; why discovery is not :complete
     :missing     {:segments   #{address}        ; fetch failed → :blocked
                   :parked     #{pid}            ; :resume operand not in (:parked vm)
                   :footprints #{module-name}    ; module without declared footprint → :incomplete
                   :profiles   #{sym}            ; retained primitive without a profile → :incomplete
                   :obligations #{sym}}          ; discharged by nothing → :incomplete
     :refusals    [{:kind :ambiguous-primitive | :unnamed-function | :host-state-primitive
                    | :ffi-pair-key | :unaddressed-segment, …}]}

Rules about the state:

- `:items` and `:frontier` are disjoint; an item enters `:frontier` only if it is in
  neither. This is the "every newly discovered `[address context]` pair is analyzed,
  whether or not the address was seen before" rule.
- `:code` is keyed by address, never by the VM's runtime segment id. A runtime
  segment id is translated through the loaded image's `:address` (`semantic.cljc`
  `load-vector` records it, `:code-aliases` inverts it). An image with no address is
  refused (`:unaddressed-segment`): there is nothing portable to name.
- `:store-slice` and `:parked-slice` are *pulled*, never copied wholesale (§7).
- Every field is a set, map, or queue whose growth is observable; the convergence
  test in §8 compares the state before and after a pass.

## 4. The context abstraction

The captured environment of a closure is the whole env map at closure creation
(`semantic.cljc:265`), inlined by value. Two facts from §7.7.2–7.7.3 shape the
abstraction:

1. an environment never discharges a name — so binding *names* are irrelevant;
2. an environment contributes reachable *values* — so only value-bearing bindings matter.

Therefore:

    context(E) = the set { abstract(v) | v ∈ vals(E), abstract(v) ≠ nil }

    abstract(v) =
      closure              → [:closure address(segment) entry context(env)]
      {:type :stream-ref}  → [:stream id]
      {:type :cursor-ref}  → [:cursor id]
      {:type :parked-continuation} → [:parked id]
      {:type :reified-continuation}
                           → [:k address(segment) pc context(env)
                                  (mapv abstract-frame k) (set (keep abstract stack))]
      host function object → [:primitive sym]     ; by reverse lookup, §6; ambiguity refuses
      vector / list / map / set of values
                           → the union of abstract over its elements (flattened into
                              the enclosing set; the collection shape itself is dropped)
      anything else (scalars, keywords, strings, nil) → nil

    abstract-frame({:type :return :segment :pc :env}) = [:frame address(segment) pc context(env)]

- The context is a plain persistent set; structural equality *is* context identity.
  No content hash is required. (A `:yin.k.ctx/sha256-…` may be derived for
  telemetry; it is not part of the design.)
- The definition is recursive but well-founded: env values are immutable and were
  created before the env that holds them, so the env-reachable graph is acyclic.
  Cycles in the VM only arise through the store (`def`-recursion) and the store
  is not part of any context — store values are walked by §7, once.
- Finiteness: `abstract` is total over the finite emitter state; the set of
  contexts is bounded by the set of distinct env values reachable at lift time.
  Together with the finite address set, `:items` is finite and the walk terminates.
- Coarseness is sound: dropping names and collection shape can only merge two
  environments that reach the same values, and the analysis of a work item
  depends on nothing else (§5).

## 5. What a work item contributes

Analysis of `[address context]` factorizes:

    facts([address context]) = code-facts(address) ∪ value-facts(context)

Both halves are memoized (`:code`, `:contexts`), so the second closure over an
already-walked address costs one map lookup for the code half. The work item
remains the unit in `:items` and in the convergence test, as §7.7.3 requires.

### 5.1 code-facts(address)

Computed once, when the address is first fetched.

    {:store-keys  #{key}        ; :store-get / :store-put operands
     :ffi-ops     #{op}
     :parked-ids  #{pid}        ; :resume operands
     :effects     #{effect-id}  ; after footprint-table normalization, §7.7.1
     :free-names  #{sym}        ; §5.1.1
     :closures    #{[entry]}}   ; :closure instructions — entry pcs only; the
                                ; context arrives with the value, not the code

The first four are exactly `yin.vm/segment-requirements` (segment) or
`yin.vm/ast-requirements` (tree). `:free-names` is §5.1.1. `:closures` is a
single query over `[$code ?addr _ :closure _ ?entry]` / `[$ast _ :lambda _ _]`.

Fetch is through a caller-supplied `fetch : address → canonical-vector | {:root :rows} | nil`.
The emitter's own fetch first answers from its loaded images (`:code`), then
`yin.vm.content/fetch-vector` / `load-rows`. A `nil` (or the content namespace's
"resolves to no payload" throw) records the address in `:missing :segments` and
marks `:code` `:missing`; the walk continues over everything else so the missing
list is as complete as one pass can make it.

### 5.1.1 Free names, on both sides of the derivation

Tree side (U7, done): `yin.vm/free-names` over `occurrences`, root-scoped.

Segment side (unwritten today, `vm.cljc:1214`): a `:var` at pc `p` is bound iff `p`
lies in the body of a `:closure` whose `params` contain the name, transitively
through enclosing bodies. Under the `"ast-to-bytecode"` layout
(`linearize.cljc:143-149, 313-321`: bodies appended after the main code,
contiguous, never interleaved, each closed by one `:return`) the body of the
closure at pc `c` with operand `body` is the pc range `[body, r]` where `r` is the
first `:return` at or after `body`. Emit a scope relation `$scope = #{[address pc c]}`
from that rule, then:

    [(seg-bound? ?addr ?pc ?name)
     [$scope ?addr ?pc ?c]
     [$code ?addr ?c :closure ?params _]
     [(member? ?params ?name)]]
    [(seg-bound? ?addr ?pc ?name)
     [$scope ?addr ?pc ?c]
     (seg-bound? ?addr ?c ?name)]

    [:find ?name :in $code $scope % ?addr
     :where [$code ?addr ?pc :var ?name] (not (seg-bound? ?addr ?pc ?name))]

Because segment instructions are positionally addressed (one pc, one row), the
mixed-occurrence problem of §4.5 does not arise here; the conformance test of
§7.7.1 (tree result = segment result for every corpus tree) is what proves the
range rule, and it is this unit's first test.

Precedence when both are available for one address: if the ledger (U9) holds a
`:derive` record from a tree to this segment, use the tree's `free-names`; else
the segment rule; if a segment carries a lowering profile other than
`"ast-to-bytecode"`, treat **every** `:var` as free. Over-approximating obligations
is always admissible (§7.7.2) and can only move `:complete` to `:incomplete`,
never make a `:complete` wrong.

### 5.2 value-facts(context)

Computed once per context by folding over its abstract values:

    [:closure addr entry ctx]  → work item [addr ctx]; :values :closures gains [addr entry ctx]
    [:stream id]               → :values :streams gains id; store key id is pulled (§7.1)
    [:cursor id]               → :values :cursors gains id; store key id is pulled, and the
                                  cursor-entry's :stream-id key is pulled
    [:parked id]               → :values :parked gains id; parked record pulled (§7.2)
    [:k addr pc ctx frames stack] → work item [addr ctx]; each frame [:frame a p c] → work
                                  item [a c]; stack abstract values folded recursively
    [:primitive sym]           → :values :primitives gains sym; discharge as :primitive (§6)

## 6. Obligations and discharge

For every `sym` in the union of `:free-names` over loaded `:code`, plus every
`:values :primitives` entry, rule once (re-ruling is idempotent):

    (contains? (:store vm) sym)          → :store     ; pull sym into :store-slice, §7.1
    registry has a profile for sym       → :primitive ; :requires :yin.k/primitives gains {sym profile};
                                                      ; :yin.k/effects gains (:yin.k/effects profile)
    (namespace sym) names a module the
    module registry holds                → :module    ; :yin.k/modules gains {name manifest-address};
                                                      ; :yin.k/effects gains the footprint's effects;
                                                      ; an undeclared footprint → :missing :footprints
    otherwise                            → :undischarged ; :missing :obligations gains sym

Order is `resolve-var`'s order minus env (`engine.cljc:53`): store, primitives,
modules. Env is skipped on purpose — UCF §7.6.1 *Names*.

Interface U13 must provide (the only thing U14 needs from it):

    (profile-of registry sym)  → profile-record | nil
         profile-record = {:yin.k/profile address :yin.k/class :pure|:effectful|:host
                           :yin.k/arities [n…] :yin.k/effects #{…} :yin.k/host-state :none}
    (name-of registry fn-object) → sym | ::ambiguous | nil

`::ambiguous` → refusal `:ambiguous-primitive`; `nil` for a function object →
`:unnamed-function`; `:host` class → `:host-state-primitive` (all UCF §7.5.2).
A primitive present in `(:primitives vm)` but absent from the registry is
`:missing :profiles` and `:incomplete`, never silently `:pure`.

A `:call`'s effects are never inferred from syntax (footprint row `#{}`); they
enter only through the profile / footprint union above. This is the §7.7.2 rule.

## 7. The slices during the walk

### 7.1 Store slice (UCF §7.6.2)

`:store-slice` is built by **pull**: a key enters when and only when

1. it is a `:store-keys` operand of loaded code and `(:store vm)` holds it;
2. it is an obligation discharged as `:store`;
3. it is the `:id` of a reachable `:stream-ref` / `:cursor-ref`;
4. it is the `:stream-id` of a pulled cursor-entry.

A key that is a `:store-keys` operand but absent from the store goes to
`:store-named` and does not travel (the isolated resumer store answers `nil`, as the
emitter's did). The FFI pair keys (`:yin/call-in`, `:yin/call-out`,
`:yin/call-out-cursor`, `vm.cljc:158-168`) are never pulled; a program that names
one as a `:store-get`/`:store-put` operand is refused (`:ffi-pair-key`, UCF §7.5.4 / §7.6.2).

Every pulled value is immediately passed through `abstract` (§4) and folded as in
§5.2 — a stored closure is a work item, a stored stream handle a stream identity.
This is the only place cycles through the store can be entered, and the
`:store-slice` key set is the memo that closes them.

The slice holds raw emitter values. Encoding (§7.5) happens after convergence,
by the lift, over exactly this map.

### 7.2 Parked slice (UCF §7.6.3)

`:parked-slice` is pulled by pid: every `:parked-ids` operand of loaded code and
every `[:parked id]` abstract value. A pid absent from `(:parked vm)` goes to
`:missing :parked`. A pulled record contributes work item
`[address(:segment) context(:env)]`, one work item per `:k` frame, and the
abstract values of its `:stack`.

Also pulled as roots (not by pid): every `:wait-set` entry, with the same shape
(`:segment :pc :env :stack :k` plus `:stream-id` / `:cursor-ref`); `:ready-queue`
must be empty (`:yin.k/not-quiescent` otherwise, UCF §7.4.1 — checked before the
walk, not by it). `:id-counter` is copied verbatim into the output scheduler map.

## 8. The traversal

    complete : {:vm :fetch :registry :modules :contract} → result

    1. Refuse early: :ready-queue non-empty; any loaded image without :address.
    2. Seed the frontier from the roots:
         [address(:control :segment) context(:env)]          ; the frame
         [address(f :segment) context(f :env)] for f in :k   ; K frames
         for each :wait-set entry: its frame, its K frames, its stack values,
           its :stream-id / :cursor-ref (as abstract values)
         abstract values of :stack and :value
    3. Loop while the frontier is non-empty:
         pop [addr ctx]; add to :items
         ensure code-facts(addr)      (fetch → :code; a miss → :missing :segments)
         ensure value-facts(ctx)      (→ :contexts; may push work items, pull keys)
         fold code-facts:  :store-keys → pull (§7.1); :parked-ids → pull (§7.2);
                           :ffi-ops, :effects → :requires; :free-names → discharge (§6)
         every push of a work item, every pull, every discharge appends to the state
    4. Verification pass (the literal §7.7.3 criterion): re-run discharge over all
       obligations and value-facts over all contexts with the frontier empty; assert
       the state is unchanged. A change is a bug in step 3, not a legitimate
       continuation.
    5. Status:
         :blocked     if (:missing :segments) non-empty
         :incomplete  else if any of :parked :footprints :profiles :obligations non-empty
         :complete    otherwise
       :blocked dominates :incomplete, which dominates :complete.
    6. Assemble the result (§9).

The worklist form is equivalent to §7.7.3's "one full pass adds nothing": every
fact is added only while analyzing an item or ruling a discharge, both of which
are driven by the frontier, so an empty frontier is a pass that added nothing.
Step 4 makes that equivalence a checked assertion rather than an argument.

Termination: §4 (finite contexts) × finite addresses; every fold is monotone and
memoized on `:items`, `:contexts`, `:code`, `:store-slice`, `:parked-slice`.

## 9. Output

    {:yin.k/requires  (:requires state) with
                      :yin.k/segments = (keys of :code with :status :loaded) ∪ (:missing :segments)
                      :yin.k/discovery = status
     :yin.k/store     (:store-slice state)                 ; raw values; the lift encodes
     :yin.k/scheduler {:yin.k/parked (:parked-slice state) ; raw records; the lift encodes
                       :yin.k/id-counter (:id-counter vm)}
     :yin.k/missing   (:missing state)                     ; §7.6.5 uses it verbatim
     :yin.k/refusals  (:refusals state)                    ; non-empty → the lift refuses
     ::work-items     (:items state)}                      ; diagnostics / tests only

`:yin.k/streams` is the union of `:values :streams` and the `:stream-id` of every
pulled cursor-entry and wait entry; `:yin.k/cursor-profiles` is the transport
profile of each pulled cursor-entry's `:cursor` (supplied by the caller's
`cursor-profile` fn, since the entry's cursor is opaque to the VM).

## 10. Namespace and surface (for U14 implementation, not written here)

- `src/cljc/yin/vm/completion.cljc`: `complete`, `context-of`, `abstract-value`,
  `segment-free-names`, `segment-scope`, `discharge`. Pure functions over the state map.
- Reuses `yin.vm/ast-requirements`, `segment-requirements`, `footprint-table`,
  `occurrences`, `free-names`; `yin.vm.code/project-segment-qualified`;
  `yin.vm.content/fetch-vector`, `load-rows`; `dao.space.query/q`, `relation`.
- Cross-host (`cljc`) — no host-only constructs; the abstraction uses only
  persistent collections and `dao.jing/segment-key`.

## 11. Acceptance tests (the ones U14 must land)

1. Conformance: for every corpus tree, `code-facts` from the tree and from its
   lowered segment are equal in every field, including `:free-names`.
2. Two closures, one segment, two envs (one env holds a stream, one does not):
   two work items; `:yin.k/streams` differs accordingly.
3. A stored closure reachable only through a `:store-get` operand is a work item;
   its free names are discharged.
4. `def`-recursion through the store terminates with one work item per
   `[address context]`.
5. A `:resume` naming a pid absent from `:parked` → `:incomplete` with the pid in
   `:yin.k/missing :parked`.
6. A segment whose address `fetch` cannot resolve → `:blocked`, the remainder
   still walked.
7. A free name bound in the env but not in the store, with no profile →
   `:incomplete`, `:missing :obligations` names it (env never discharges).
8. A `:store-get` of an FFI pair key → refusal `:ffi-pair-key`.
9. Step 4's verification pass is unchanged on every fixture above.

## 12. Open questions for the owner

1. **Missing parked id — refuse or report?** UCF §7.6.3 says the lift is refused
   (`:yin.k/non-portable`, `:foreign-parked-ref`); `yin.vm.code-as-tuples.md`
   §7.7.3 says `:yin.k/unsatisfied` naming the id. This design reports it under
   `:missing :parked` and `:incomplete`, leaving the refusal to the lift. One of
   the two documents should be amended to match.
2. **Context drops binding names and collection shape** (§4). Sound and coarser;
   confirm this is the intended "finite abstraction", or require name-keyed contexts.
3. **Segment-side scoping is layout-dependent** (§5.1.1). Confirm that binding
   the range rule to the `"ast-to-bytecode"` lowering profile, with all-free as the
   fallback for other profiles, is acceptable.
4. **U13 interface** (§6): `profile-of` / `name-of` are the two functions U14
   consumes; U13's registry format should be written against them.
5. **`:store-named` keys** (absent-from-store operands) do not travel; confirm.
