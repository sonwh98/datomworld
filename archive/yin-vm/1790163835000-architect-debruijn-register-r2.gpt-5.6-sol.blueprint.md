I’ll trace the R2 specification against the current R1 register compiler/descriptor and the B4 engine/stack implementation, then produce a concrete namespace-by-namespace blueprint with opcode tables, validation rules, lowering order, and test vectors. I’ll first read the governing architecture and all cited source sections.
The specification fixes the critical semantics: six liveness-bearing opcodes, sparse continuation pairs ordered exactly like `live`, and `:resume` as a terminator. I’m now reconciling those rules with the actual R1 function names/data shapes and the existing stack/engine helpers so the blueprint can name exact edit points rather than merely restating the design.
Two contract mismatches surfaced that the implementation blueprint must resolve explicitly:

- The normative §5.2.2 wire payload is `:regs`/`:dest` (plus `:live` and `:continuation`), while the task shorthand says `:registers`/`:destination`. I’ll preserve the normative wire keys and distinguish them from the runtime input keys.
- The current `ffi/response-wait-entry` removes only three writer keys, but §5.2.3 requires ten stale wake keys to be removed. That needs a small `yin.vm.ffi` prerequisite patch even though the nominal R2 file box omitted it; otherwise the new validator must reject the engine helper’s own output.

I’m checking the cross-host test commands and existing golden/contract conventions now, then I’ll deliver the full implementation handoff.
Completed-GMT: 2026-09-23 11:54:23 GMT
Completed-Local: 2026-09-23 18:54:23 Asia/Ho_Chi_Minh

# Register VM Phase R2 implementation blueprint

This is an implementation handoff for R2. It assumes the R1 files from `/Users/sto/workspace/worktree-register-r0` are merged into the implementation branch before work begins.

Two specification details require explicit interpretation:

1. Section 5.2.2 is authoritative for serialized field names. Runtime state supplies `:registers`; serialized continuations use `:regs`, `:live`, and `:dest`. Do not introduce alternate serialized keys `:registers` or `:destination`.
2. A “nonterminal `:resume`” is not representable: `:resume` has no fallthrough flag, and its CFG successor set is always empty. Do not require it to be the final physical instruction in a body; doing so would reject valid conditional branches and contradict §4.6. Wrong arity is `:arity`; liveness must prove that no successor is traversed.

There is also one necessary prerequisite outside the nominal R2 file box: `ffi/response-wait-entry` must remove every stale wake key listed in §5.2.3.

## 1. `yin.vm.debruijn-register-effects`

Create [debruijn_register_effects.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/debruijn_register_effects.cljc) with:

```clojure
(ns yin.vm.debruijn-register-effects
  (:require [dao.stream.apply :as apply2]
            [yin.vm :as vm]
            [yin.vm.debruijn-register-code :as rcode]))
```

Do not require `yin.vm.engine`, `yin.vm.ffi`, a stream implementation, or mutable state. This namespace constructs and validates data only.

Public constants/helpers may include:

```clojure
(def format-tag :yin.debruijn.register)

(def boundary-opcodes
  #{:call :stream-put :stream-next :ffi-call
    :current-continuation :park})
```

### 1.1 `effect-descriptor`

Precondition: `instruction` came from a validator-approved image and `registers` is the current body’s complete vector.

Exact behavior:

| Instruction | Result |
|---|---|
| `[:store-get rd key]` | `nil`; R4 reads `(:store runtime)` directly |
| `[:store-put rd key value]` | `{:effect :vm/store-put :key key :val value}` |
| `[:gensym rd prefix]` | `nil`; R4 calls `engine/gensym` directly |
| `[:stream-make rd capacity]` | `{:effect :stream/make :capacity capacity}` |
| `[:stream-put rd sr vr live]` | `{:effect :stream/put :stream (nth registers sr) :val (nth registers vr)}` |
| `[:stream-cursor rd sr]` | `{:effect :stream/cursor :stream (nth registers sr)}` |
| `[:stream-next rd cr live]` | `{:effect :stream/next :cursor (nth registers cr)}` |
| `[:stream-close rd sr]` | `{:effect :stream/close :stream (nth registers sr)}` |
| `[:ffi-call rd op args live]` | `nil`; FFI requires its dedicated two-stage path |
| `[:current-continuation rd live]` | `nil` |
| `[:park rd live]` | `nil` |
| `[:resume parked-id vr]` | `nil` |
| Any unrelated opcode | `nil` |

Do not normalize capacity, prefix, store values, stream references, or arguments here. The descriptor must preserve exact image/register values. In particular, do not apply `vm/default-stream-capacity`; the canonical R2 instruction already contains a nonnegative integer.

Dynamic primitive effects from `:call` are already effect maps returned by the primitive. R4 must pass those maps unchanged to `engine/handle-effect`; `effect-descriptor` does not invoke the primitive or wrap its result.

### 1.2 `continuation-payload`

Runtime input shape:

```clojure
{:segment validated-image
 :hash R
 :pc site-pc
 :frames outermost-first-lexical-frames
 :registers full-register-vector
 :continuation sparse-return-frame-vector}
```

Support all six boundary instructions:

| Opcode | Live slot | Destination | Resume mode |
|---|---:|---|---|
| `:call` | 5 | `rd`, unless tail | tail: `nil`; otherwise `rd` |
| `:stream-put` | 4 | `rd` | `:write-result` |
| `:stream-next` | 3 | `rd` | `:write-result` |
| `:ffi-call` | 4 | `rd` | `:write-result` |
| `:current-continuation` | 2 | `rd` | `:write-result` |
| `:park` | 2 | `rd` | `:write-result` |

A tail `:call` uses `:resume-mode :return-result`; all other cases use `:write-result`.

Construction is exactly:

```clojure
{:segment (:segment runtime)
 :site-pc (:pc runtime)
 :pc (inc (:pc runtime))
 :frames (:frames runtime)
 :regs (mapv (fn [r] [r (nth (:registers runtime) r)]) live)
 :live live
 :continuation (:continuation runtime)
 :dest destination
 :resume-mode resume-mode
 :format :yin.debruijn.register
 :hash (:hash runtime)}
```

Requirements:

- Copy `live` from the supplied instruction.
- Require the supplied instruction to equal the instruction at runtime `:pc`.
- Preserve ascending `live` order in `:regs`; never build a map.
- Do not include dead registers.
- Tail calls must produce `:regs []`, `:live []`, and `:dest nil`.
- Do not include `:store`, `:free-env`, primitives, modules, engine queues, stream handles, or allocator state.
- Reject a non-boundary instruction with `ex-info` carrying `{:rule :continuation-site :pc ...}`. This is a constructor precondition failure, not a serialized defect.

For `:current-continuation`, R4 wraps the result:

```clojure
(assoc payload :type :reified-continuation)
```

For `:park`, R4 gives the untagged payload to `engine/park-continuation`; the engine adds `:type` and `:id`.

### 1.3 `continuation-defect`

Return `nil` for a valid payload and the first deterministic defect map otherwise. Do not throw for malformed input.

Recommended rule order:

1. `:continuation-shape`
2. `:continuation-format`
3. `:continuation-segment`
4. `:continuation-hash`
5. `:continuation-pc`
6. `:continuation-live`
7. `:continuation-registers`
8. `:continuation-destination`
9. `:continuation-frames`
10. Return-frame validation

Checks:

- Payload is a map.
- `:format` is exactly `:yin.debruijn.register`.
- `:segment` passes `rcode/register-image-defect`.
- `:hash` equals `(rcode/register-hash (:segment payload))`.
- `:site-pc` is a nonnegative safe integer inside the image.
- The site instruction is one of the six boundary opcodes.
- `:pc` equals `(inc :site-pc)` and lies in the same declared body.
- `:resume-mode` equals the mode implied by the site instruction.
- `:live` is an ascending, duplicate-free vector of in-body register indices.
- `:live` exactly equals the instruction’s live operand.
- `:regs` is a vector of two-element vectors.
- `(mapv first :regs)` exactly equals `:live`.
- Every captured value is plain data.
- For `:write-result`, `:dest` exactly equals instruction `rd`, is in body bounds, and is absent from `:live`.
- For `:return-result`, `:dest` is nil and the site is a tail `:call`.
- `:frames` is a vector of lexical-frame vectors and is plain data.
- `:continuation` is a vector of valid sparse return frames.

Use defect details such as:

```clojure
{:rule :continuation-live
 :pc site-pc
 :expected instruction-live
 :actual payload-live}
```

```clojure
{:rule :continuation-hash
 :expected computed-r
 :actual (:hash payload)}
```

Each return frame must have:

```clojure
{:segment image
 :hash R
 :site-pc call-pc
 :return-pc (inc call-pc)
 :frames caller-frames
 :regs [[index value] ...]
 :live [index ...]
 :dest call-rd}
```

Return-frame validation must independently verify:

- Image and R.
- `site-pc` names a non-tail `:call`.
- `return-pc` is exactly the next pc and belongs to the same body.
- `dest` equals the call’s `rd` and is in bounds.
- `live` equals the call operand.
- Sparse pairs exactly match `live`.
- Frames and captured values are plain data.

Add `:frame-index` to a nested defect rather than losing the underlying rule.

With the mandated one-argument API, “foreign R” means that R does not identify the embedded segment. Comparing the entry with the currently loaded machine’s R is impossible here; future `register-restore` must additionally require:

```clojure
(= (:hash base) (:hash entry))
```

and raise `:continuation-format` before changing any VM registers.

### 1.4 `wait-entry-defect`

This validates newly parked wait-set entries, not already-woken ready entries.

Start by calling `continuation-defect`. Then classify the entry in this order:

1. `:request-sent true` → FFI writer.
2. Contains `:call-id` → FFI reader.
3. `:reason :put` → ordinary stream writer.
4. `:reason :next` → ordinary stream reader.
5. Otherwise `{:rule :wait-shape}`.

Canonical additions to the continuation payload are:

| Shape | Required transport fields |
|---|---|
| Stream writer | `{:reason :put :stream-id keyword :datom value}` |
| Stream reader | `{:reason :next :stream-id keyword :cursor-ref {:type :cursor-ref :id keyword}}` |
| FFI writer | `{:request-sent true :call-id keyword :op keyword :reason :put :stream-id vm/call-in-stream-key :datom request}` |
| FFI reader | `{:call-id keyword :reason :next :stream-id vm/call-out-stream-key :cursor-ref {:type :cursor-ref :id vm/call-out-cursor-key}}` |

For an FFI writer also require:

```clojure
(apply2/request? (:datom entry))
(= (:call-id entry) (apply2/request-id (:datom entry)))
(= (:op entry) (apply2/request-op (:datom entry)))
```

All request arguments must be plain data.

Use a fixed ordered vector for stale keys:

```clojure
[:value :status :cursor :store-updates :stream
 :datom :type :id :request-sent :op]
```

Application is shape-specific:

- FFI reader: all ten keys must be absent.
- Stream reader: all ten must be absent.
- Stream writer: `:datom` is required; the other nine must be absent.
- FFI writer: `:datom`, `:request-sent`, and `:op` are required; the other seven must be absent.

Reject present stale keys with:

```clojure
{:rule :wait-stale :keys [...]}
```

Reject missing or malformed resource identities with `:wait-resource`, and reason/shape disagreement with `:wait-shape`.

Use `vm/plain-data?` rather than host-specific exception tests. It rejects JVM exceptions, JavaScript errors, Dart exceptions, functions, stream handles, and arbitrary host objects portably.

An explicit `:resume` value is not part of a wait entry. Future `register-restore` must validate its separate `val` argument before mutation and raise `{:rule :resume-value}` when it is not plain data.

## 2. Register descriptor and validator

Update [debruijn_register_code.cljc](/Users/sto/workspace/worktree-register-r0/src/cljc/yin/vm/debruijn_register_code.cljc).

### 2.1 Descriptor

Set:

```clojure
(def contract-version 3)
```

The complete R2 additions are:

```clojure
:store-get
[[:yin.debruijn.register/rd :reg]
 [:yin.debruijn.register/key :data]]

:store-put
[[:yin.debruijn.register/rd :reg]
 [:yin.debruijn.register/key :data]
 [:yin.debruijn.register/value :data]]

:gensym
[[:yin.debruijn.register/rd :reg]
 [:yin.debruijn.register/prefix :str]]

:stream-make
[[:yin.debruijn.register/rd :reg]
 [:yin.debruijn.register/capacity :uint]]

:stream-put
[[:yin.debruijn.register/rd :reg]
 [:yin.debruijn.register/stream-reg :reg]
 [:yin.debruijn.register/value-reg :reg]
 [:yin.debruijn.register/live :data]]

:stream-cursor
[[:yin.debruijn.register/rd :reg]
 [:yin.debruijn.register/stream-reg :reg]]

:stream-next
[[:yin.debruijn.register/rd :reg]
 [:yin.debruijn.register/cursor-reg :reg]
 [:yin.debruijn.register/live :data]]

:stream-close
[[:yin.debruijn.register/rd :reg]
 [:yin.debruijn.register/stream-reg :reg]]

:ffi-call
[[:yin.debruijn.register/rd :reg]
 [:yin.debruijn.register/ffi-op :kw]
 [:yin.debruijn.register/arg-regs :regs]
 [:yin.debruijn.register/live :data]]

:current-continuation
[[:yin.debruijn.register/rd :reg]
 [:yin.debruijn.register/live :data]]

:park
[[:yin.debruijn.register/rd :reg]
 [:yin.debruijn.register/live :data]]

:resume
[[:yin.debruijn.register/parked-id :kw]
 [:yin.debruijn.register/value-reg :reg]]
```

The R1 forward declaration for `:store-put` is wrong and must be replaced, not extended. It currently lacks `rd` and incorrectly names a register operand. Store values are exact scalar data.

Operand checks:

```clojure
:reg   nonnegative-safe-integer?
:regs  vector of nonnegative-safe-integer?
:uint  nonnegative-safe-integer?
:str   string?
:kw    keyword?
:sym   symbol?
:data  vm/plain-data?
:bool  boolean?
```

Do not retain R1’s `(constantly true)` check for `:data`.

The full opcode table contains 22 mnemonics, so descriptor `:dim/arity` must derive to 22.

### 2.2 Liveness

Use/def must be exhaustive:

```text
:const rd _                        def rd
:load-bound rd _ _                 def rd
:load-free rd _                    def rd
:closure rd _ _                    def rd
:move rd rs                        use rs; def rd
:store-get rd _                    def rd
:store-put rd _ _                  def rd
:gensym rd _                       def rd
:stream-make rd _                  def rd
:stream-put rd s v _               use s,v; def rd
:stream-cursor rd s                use s; def rd
:stream-next rd c _                use c; def rd
:stream-close rd s                 use s; def rd
:ffi-call rd _ args _              use args; def rd
:current-continuation rd _         def rd
:park rd _                         def rd
:resume _ v                        use v
:call rd f args tail? _            use f,args; def rd unless tail
:branch-false c _                  use c
:return r                          use r
:halt r                            use r
:jump                              none
```

Successors:

```text
:jump t                 #{t}
:branch-false _ t       #{t, pc+1}
:return/:halt/:resume   #{}
tail :call              #{}
everything else         #{pc+1}
```

`body-liveness` must return entries for all six live-bearing opcodes, not only `:call`.

Derive the live operand position from `opcode-table` rather than duplicating six numeric positions in the compiler and validator. Export a small `live-slot-index` helper returning the tuple index of `:yin.debruijn.register/live`.

At each boundary:

```clojure
live = vec(live-out - def)
```

Because sets remain `sorted-set`s, the vector is ascending and deterministic.

### 2.3 Live validator rules

Run after body shape, jump scope, register bounds, and terminator validation:

- `:live-shape`: every live slot is an ascending, duplicate-free vector of nonnegative safe integers.
- `:live-bounds`: every index is below its owning body’s `:registers`.
- `:live-tail`: a tail `:call` has `[]`.
- `:live-exact`: compare with `body-liveness`; include `:expected` and `:actual`.

Apply the rules to every instruction with a live slot.

Add `:resume` to the control-flow terminator vocabulary. Do not require it to be the body’s final physical tuple. Existing body termination remains main `:halt`, lambda `:return`; unreachable instructions after `:resume` are permitted.

## 3. Register lowering

Update [debruijn_register_compile.cljc](/Users/sto/workspace/worktree-register-r0/src/cljc/yin/vm/debruijn_register_compile.cljc).

Remove `deferred-diagnostics` and `refuse-deferred!`. The default branch should now be only the existing unknown-node diagnostic.

Exact lowering and allocation:

| Resolved node | Actions |
|---|---|
| `:vm/store-get` | Emit `[:store-get rd key]`; no temporary |
| `:vm/store-put` | Emit `[:store-put rd key value]`; no temporary |
| `:vm/gensym` | Emit `[:gensym rd prefix]`; no temporary |
| `:stream/make` | Emit `[:stream-make rd buffer]`; no temporary |
| `:stream/put` | Allocate target temp; lower target; allocate value temp; lower value; emit `[:stream-put rd rt rv []]`; free target then value |
| `:stream/cursor` | Allocate source; lower; emit `[:stream-cursor rd rs]`; free source |
| `:stream/next` | Allocate source; lower; emit `[:stream-next rd rs []]`; free source |
| `:stream/close` | Allocate source; lower; emit `[:stream-close rd rs]`; free source |
| `:dao.stream.apply/call` | Allocate/lower operands left-to-right; emit `[:ffi-call rd op arg-regs []]`; free operands left-to-right |
| `:vm/current-continuation` | Emit `[:current-continuation rd []]` |
| `:vm/park` | Emit `[:park rd []]` |
| `:vm/resume` | Allocate value temp; lower value; emit `[:resume parked-id rv]`; free value temp |

For `:resume`, ignore the supplied `target-reg` only when emitting the instruction. Do not change the caller’s allocation discipline or attempt to reclaim the parent’s target early.

Replace `fill-live`’s hard-coded `:call`/index-5 handling with:

1. Compute `rcode/body-liveness` per body.
2. For every returned `[pc live]`, obtain the tuple’s live index through `rcode/live-slot-index`.
3. Replace that slot.
4. Assert the helper returned a slot for every pc.

### Lift extension

R2 must also extend the existing register-to-named lift or the R1 lift law will fail for every newly supported node.

Treat these as atomic producers:

```text
:store-get, :store-put, :gensym, :stream-make,
:current-continuation, :park
```

Strip `rd` and `live` while reconstructing their stack tuples.

Reduction forms:

- `:stream-put`: consume target and value productions; add `[:push]` after target only; append `[:stream-put]`.
- `:stream-cursor`, `:stream-next`, `:stream-close`: consume one production and append the corresponding tuple.
- `:ffi-call`: consume `count(arg-regs)` productions in order, finalizing each with `[:push]`, then append `[:ffi-call op argc]`.
- `:resume`: consume its value production without adding `[:push]`, then append `[:resume parked-id]`.

No R2 instruction needs a new diagnostic side-table entry. Continue emitting each instruction with its resolved source id so pc/source alignment remains intact.

## 4. FFI stale-wake prerequisite

Update [ffi.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi.cljc:65). `response-wait-entry` must become:

```clojure
(-> entry
    (dissoc :value :status :cursor :store-updates :stream
            :datom :type :id :request-sent :op)
    (assoc :call-id call-id
           :reason :next
           :cursor-ref {:type :cursor-ref
                        :id vm/call-out-cursor-key}
           :stream-id vm/call-out-stream-key))
```

This is not a new engine rule. It completes the already-specified conversion from a woken FFI writer into a fresh FFI reader and prevents `:store-updates` from being merged twice.

Extend [engine_test.cljc](/Users/sto/workspace/datomworld/test/yin/vm/engine_test.cljc:424) so its writer fixture contains all ten stale keys and asserts that every one is absent from the returned reader except fields deliberately reintroduced inside `:cursor-ref`.

## 5. Tests

Create [debruijn_register_effects_test.cljc](/Users/sto/workspace/datomworld/test/yin/vm/debruijn_register_effects_test.cljc).

### Effect descriptor tests

Use a table-driven test covering all twelve R2 instructions:

- Assert exact maps for store-put and all five stream operations.
- Assert register operand lookup, including nil as a legitimate register value.
- Assert nil for store-get, gensym, FFI, continuation, park, and resume.
- Assert stream-put argument order is target then value.
- Assert no defaulting or normalization occurs.

### Continuation tests

Use validated images produced by `register-compile/adapt`, then construct runtime maps with distinctive values in every register.

Cover:

- Only live registers appear.
- Pair order equals live order.
- Dead closures/collections are absent.
- `site-pc`, next `pc`, frames, continuation, format, and R survive exactly.
- Non-tail primitive effect gives `:write-result` and `dest`.
- Tail primitive effect gives `:return-result`, nil destination, and empty capture.
- Current-continuation and park use their own `rd`.
- Return frames can identify a different register image and R.
- A reified continuation is exactly `(assoc payload :type :reified-continuation)`.

### Defect mutation matrix

Starting from one valid payload, mutate one fact per assertion:

- Foreign format.
- One-bit/different R.
- Invalid embedded image.
- Negative, out-of-range, or cross-body pc.
- Incorrect next pc.
- Invalid resume mode.
- Destination mismatch or out of bounds.
- Unsorted, duplicate, extra, or omitted live index.
- Sparse pair index/order/count mismatch.
- Non-vector frames or continuation.
- Invalid nested return frame.
- Raw host object in a captured value.

Assert exact `:rule`, and `:expected`/`:actual` where specified.

### Wait-entry tests

Build one valid entry for each of the four canonical shapes. Test:

- Missing stream id.
- Missing or malformed cursor ref.
- Wrong reason.
- Reserved FFI stream mismatch.
- Request call-id/op mismatch.
- Foreign format and R.
- Destination and live defects delegated from `continuation-defect`.
- Every stale key injected individually into an FFI reader produces `:wait-stale`.
- Writer-required keys remain accepted only on writer shapes.
- Host exception/function/stream handle in retained data is refused.
- The output of `ffi/response-wait-entry` from a fully decorated woken writer passes `wait-entry-defect`.

### EDN round-trip

For payloads and all four wait forms:

```clojure
(= value (edn/read-string (pr-str value)))
```

Run this unchanged on JVM, Node/CLJS, and ClojureDart. Do not put functions, handles, cursors, exceptions, atoms, callbacks, or timers in valid fixtures.

### Engine seam equivalence

R2 must not instantiate a register VM.

Instead:

- Compare produced stream descriptors with the exact maps used by stack B4.
- Feed the descriptors into `engine/handle-effect`.
- Compare value, store transition, blocked flag, and engine-owned wait-entry projection.
- For blocked put/next, use a park-entry builder that merges a register continuation payload with the engine transport keys.
- Compare store-put with the engine `:vm/store-put` transition.
- Compare gensym through `engine/gensym`.
- Construct FFI requests with `apply2/request` and verify operand order, call id, and byte-for-byte retention across a full writer retry.
- Verify `ffi/call-result` alone classifies response errors; no error register is introduced.

## 6. Contract and compiler test updates

Update the R0 contract test:

- Change its frozen version directly to `3`.
- Expand its mnemonic set to all 22 instructions.
- Replace every deferred mapping with its exact R2 mapping.
- Add `live` to the application/call shape.
- Delete the “deferred to R2” assertions.
- Update descriptor validation claims to include live shape, bounds, tail, and exactness.
- Remove obsolete text saying R4 is benchmark-gated.

Update the R1 compiler test:

- Replace the expected version skew with equality at version 3.
- Delete deferred-refusal tests and deferred-corpus filtering.
- Change “move and store are never emitted” to “move is never emitted.”
- Add exact image tests for every R2 node.
- Add child allocation/release tests, especially stream-put and multi-argument FFI.
- Add live-set fixtures for stream-put, stream-next, FFI, current-continuation, and park.
- Add a resume CFG fixture proving liveness does not flow past `:resume`.
- Add validator tests for wrong string/keyword/data kinds, negative capacity, malformed register vectors, and every new live slot.
- Extend lift-law fixtures to all R2 forms.

## 7. Hash and golden re-pinning

Do this only after descriptor, encoder, lowering, and formatting are final.

Pin literal constants for:

1. `rcode/contract-version` → `3`.
2. `rcode/descriptor-hash`.
3. At least one pure R1 image vector and its new R.
4. Every effect-bearing golden image vector and R.
5. A nested-control-flow image containing multiple live boundaries.
6. FFI and resume images.

The pure image vectors should remain byte-identical to R1; their R values must change because descriptor hash is part of every R preimage. Never “pin” by computing expected values in the same assertion.

Generate the literals once on JVM, paste them into the `.cljc` tests, then require Node and ClojureDart to equal those same literals. Any host-specific expected hash is a failure.

## 8. Required verification

Run in this order:

```sh
clj -M:kondo --lint src/cljc/yin/vm/debruijn_register_code.cljc
clj -M:kondo --lint src/cljc/yin/vm/debruijn_register_compile.cljc
clj -M:kondo --lint src/cljc/yin/vm/debruijn_register_effects.cljc
clj -M:test -n yin.vm.debruijn-register-contract-test
clj -M:test -n yin.vm.debruijn-register-compile-test
clj -M:test -n yin.vm.debruijn-register-effects-test
bb test:clj
bb test:cljs
bb test:cljd
```

Finally run the repository formatter and the 80-column design/source check. R2 is complete only when the same literal descriptor hash, image vectors, R values, payloads, and EDN round trips pass on all three hosts.
