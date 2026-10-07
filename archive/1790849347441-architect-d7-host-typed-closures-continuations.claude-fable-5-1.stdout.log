Completed-GMT: 2026-10-01 10:11:47 GMT
Completed-Local: 2026-10-01 17:11:47 +07
Coding-Agent: claude
Session-ID: f8eef849-bc12-4f36-87ee-4ae5da8aaa8c

# Architect design: D6/D7, host-typed closures and continuations

Read-only; no file edited. I executed seven programs on the AST walker (JVM, master `60b60898`) through `tu/compile-and-run`; claims about the other three VMs are from reading.

**What the run showed:**

| # | Program | Result | Meaning |
|---|---|---|---|
| 1 | Apply literal `{:type :closure … :body <data>}` | body ran | Forged closure still accepted. |
| 2 | Apply literal `{:type :reified-continuation :k nil}` to `5` | `5` | Forged continuation still redirects control. |
| 3 | `(get <real closure> :env)` | `{secret 42}` | A guest reads any closure's captured environment. New finding. |
| 4 | `(get <real continuation> :env)` | `{secret 43}` | Same for continuations. |
| 5 | Lambda with parameter `:yin.k/store-of`, applied to `:victim-module`, body `:vm/store-put` | accepted, returned `1` | A keyword parameter plants the module-store key in the environment with no forged value at all. Destination by reading (`engine.cljc:178-186`). New finding. |
| 6 | `=` on two closures from one lambda | `true` | Equality is structural today. |
| 7 | Apply `5` | "Cannot apply non-function" with `{:fn 5}` | Unqualified refusal that carries the value. |

Rows 3 and 5 change the design: host typing must also make the values opaque to guest primitives, and the store-of hole has a second entrance that host typing does not close.

## Recommended design

**Two shared host types, `Closure` and `Continuation`, each wrapping the kernel's existing payload map plus an owner tag, minted only by kernels, opaque to guest primitives, with structural equality.**

- **One namespace, `yin.vm.value`,** modelled on `yin.vm.effect` (`effect.cljc`): `deftype Closure [owner payload]`, `deftype Continuation [owner payload]`, trusted constructors, `closure?`/`continuation?` type tests, `payload` and `owner` accessors.
- **No `ILookup`.** This is the one deliberate difference from `Effect`. With it, the guest `get` primitive would still read `:env` (rows 3 and 4).
- **Payload unchanged.** Each kernel keeps its own map shape inside the wrapper, so lift, lower, completion and `gc-children` keep operating on the maps they already understand.
- **Owner tag:** a per-task value derived once from the capability secret and held in VM state. Application, invocation and lift check it.
- **`:parked-continuation` stays plain data.** Resume goes by id through the engine's `:parked` table, never through a guest-held value, so there is nothing to forge.
- **Cell, stream and cursor refs stay sealed plain data.** They name table entries, are already unforgeable, and must remain usable as dict keys. I withdraw my mob round-1 remark that cell refs should become host types.

## Q1. Representation per VM

**One deftype per kind, shared by all four VMs.**

| Site | Change |
|---|---|
| Construction (`ast_walker.cljc:149`, `:545`; `semantic.cljc:306`, `:357`; `stack.cljc:548`, `:702`; `register.cljc:561`, `:713`; the four `lower-closure`s) | Wrap the existing map: `(value/closure owner payload)`. |
| Application (`ast_walker.cljc:266`, `:634`, `:677`; `semantic.cljc:231`; `stack.cljc:574`; `register.cljc:585`) | `(value/closure? f)` then read fields from `(value/payload f)`. A type test replaces a keyword compare. |
| Invocation (`engine.cljc:60-62`) | `reified-continuation?` becomes a type test. |
| Park and resume | Unchanged; entries in `:parked`, `:wait-set`, `:ready-queue` are engine-private plain data holding wrapped values. |
| Encoder (`engine.cljc:656-690`) | Type tests come before the `map?` arm. A plain map with `:type :closure` is now a literal and is wrapped as one. |
| Completion (`completion.cljc:89-110`) | `typed?` on closures and continuations becomes type tests over the payload. |
| Heap trace (`engine.cljc:1852-1860`) | Unwrap, then ask the kernel's `gc-children` on the payload. See the two-mode rule below. |
| Printing (`repl.cljc:317-331`) | Render from the payload, never the owner tag. |

**Two-mode trace (closes gemini concern 1).** Kernel-shape interpretation applies only when the tracer arrives from a kernel root, an engine table, or a host-typed payload. Once it crosses into a value position (an environment value, an evaluated operand, a register, a cell's content), every map is walked whole as data. A guest map shaped like a walker frame is then never pruned.

**Gemini concern 2** stays a rule, not code: primitives return realized persistent data, never lazy sequences or host closures over guest values.

## Q2. Portability

- `deftype` plus `instance?` plus `(.-field ^Type x)` is proven on all three hosts by D4.
- Equality and hash need per-host protocol blocks: `Object`/`IHashEq` on CLJ, `IEquiv`/`IHash` on CLJS and CLJD. Order the reader conditional with `:cljd` first.
- Protocol methods must not repeat a parameter name (`[_ _x]`, not `[_ _]`), the defect the reclamation slice hit on CLJD.
- `get` on a non-lookup object must answer nil or fail identically on all hosts. This is unverified on CLJD; add it to the parity set.
- Neither type implements `IFn`; `fn?` must stay false so the primitive arm is not taken.

## Q3. Qualified refusals (D6)

One vocabulary, in the `:reason` shape the reference checks already use (`engine.cljc:314-326`). `:yin.k/status` stays for portability and effect-profile outcomes.

| Reason | When |
|---|---|
| `:not-applicable` | Operator is neither a host function, a `Closure`, nor a `Continuation`. Carries `:kind` (a coarse data kind), never the value. |
| `:foreign-value` | A host-typed value whose owner is not this task. Carries `:kind :closure` or `:continuation`. |
| `:foreign-format` | A continuation from another kernel format (today's register `check-format!`). |
| `:continuation-arity` | Existing arity refusal (`engine.cljc:65-74`), renamed into the vocabulary. |

One engine function classifies the operator; the four kernels call it. After D7 a malformed in-machine continuation cannot exist, so there is no structural validation to write, which is what I argued in the mob.

## Q4. Task ownership

- **Why it is needed even with unforgeable types:** a genuine `Closure` from task A can reach task B raw through an in-memory stream (`handle-put` appends the value as given, `engine.cljc:435-449`). Its program counters, segment ids and cell refs are A's coordinates.
- **Check:** owner compared at application and invocation, `identical?` first, `=` as fallback. One field read on the hot path.
- **Tasks without a secret** get owner `nil` and accept only owner-`nil` values.
- **Lift is the only crossing.** The encoder refuses a foreign-owned value as `:yin.k/non-portable`; `lower-closure` mints with the receiver's owner. Install children have their own secrets, hence their own tags.
- **Copy-on-lift (slice 2):** unaffected; the heap pull traces through payloads by the same seam.
- **In-task streams:** a closure passed through a stream inside one task still works; the reclamation pin logic must trace through wrappers.

## Q5. Equality and identity

**Structural, as today.** `=` on two host-typed values compares kind, owner and payload.

- Identity equality would make two runs of one program produce unequal VM states and would change after any lift and lower. The VM is a value; that property is worth more.
- A plain map never equals a `Closure`, so forged look-alikes do not compare equal.
- Guest function identity stays where the mutable-objects ruling put it: the cell that wraps the function object. `=` on cell refs is unchanged.
- Hash must agree with equality so closures can sit in sets and as map keys.

## Q6. The store-of hole

Three entrances, only the first closed by host types:

1. **Forged closure or continuation map carrying the key** (mob finding). Closed by D7.
2. **Keyword lambda parameter** (row 5). `check-params!` refuses only reserved names (`ast_walker.cljc:155-159`); `bind-params` then writes any key into the environment. Row validation does require symbols (`vm.cljc:1268`), but the map-AST path the test harness uses reached the walker without it. **Fix:** refuse a non-symbol parameter at the transition on every kernel, the way Rule R refuses a reserved binder.
3. **Wire marker naming another module's store** (by reading, not executed). `lower-closure` copies `:yin.k/store-of` from the marker (`semantic.cljc:1017`, `:1034`; `ast_walker.cljc:1191-1199`; `register.cljc:852-853`). A module's slice could name a store the receiver already holds, and "first link wins" keeps the live victim store. **Fix:** at lower, a closure may name store `m` only if its origin image belongs to manifest `m`. This is a linker check.

**Structural follow-up:** on the named kernels the store context lives in the lexical environment under a keyword. Moving it onto the closure and return frame, as the positional kernels already do (`engine.cljc:130-137`), removes entrance 2 by construction. I recommend the parameter refusal now and the move later.

## Q7. Slices, order, tests, spike impact

**Slice A (D6 + D7 core, one slice across four VMs):** `yin.vm.value`; construction, recognition and unpacking in each kernel; encoder, completion, trace, printing; owner tag; the four refusal reasons; non-symbol parameter refusal.

**Slice B (linker):** marker origin-versus-store check at lower.

**Slice C (later):** move store context off the lexical environment on the named kernels.

**Acceptance tests, four VMs × CLJ/CLJS/CLJD:**
- Rows 1 and 2 now refuse with `:not-applicable`.
- Rows 3 and 4 answer nil; `assoc` on a closure does not yield an applicable value.
- Row 5 refuses.
- A closure put on an in-memory stream by one task and applied by another refuses with `:foreign-value`; the same closure through lift and lower works.
- `=` on two closures from one lambda with equal environments is true; closure versus look-alike map is false; hash agrees.
- Two runs of one program yield equal VM states.
- Reclamation suite unchanged, plus a guest frame-shaped map holding a cell ref keeps that cell alive.
- The existing continuation-invoke and Python e2e suites pass unchanged.

**Spike prelude: one required change.** `py/numeric?` classifies by elimination and tests `(nil? (get x :type))` (`prelude.cljc:74-87`). After D7 a closure answers nil there and would be classified as a number. Add two pure predicates to the `data` module, `number?` and `callable?`, and rewrite `py/numeric?` on `data/number?`. `py/cell?` (`prelude.cljc:66`) keeps working because cell refs stay maps. The lowering does not change.

## Owner decisions

1. **Equality:** structural (recommended) or identity.
2. **Refs stay sealed data** (recommended), or become host types too.
3. **Named-kernel store context:** parameter refusal now and structural move later (recommended), or the move inside slice A.
4. **`data/number?` and `data/callable?`** as additions to the `data` module (recommended), or a single `data/kind`.

## Findings

| Severity | File:line | Evidence | Correction |
|---|---|---|---|
| high | `src/cljc/yin/vm/ast_walker.cljc:266`, `engine.cljc:60-62` | Rows 1 and 2: forged closure and continuation maps are applied. | Slice A. |
| high | `src/cljc/yin/vm/ast_walker.cljc:149`, `:545` | Rows 3 and 4: `get` reads `:env` out of any closure or continuation, exposing captured cell refs. | Host types without `ILookup`. |
| high | `src/cljc/yin/vm/ast_walker.cljc:155-159`, `engine.cljc:52-57` | Row 5: a keyword parameter binds `:yin.k/store-of` in the environment. | Refuse non-symbol parameters at the transition; later move the store context. |
| medium, by reading | `src/cljc/yin/vm/semantic.cljc:1017-1034`, `register.cljc:852-853` | `lower-closure` trusts the marker's store name. | Slice B origin check. |
| medium | `src/cljc/yang/python/antlr/prelude.cljc:74-87` | `py/numeric?` depends on closures answering `:type`. | `data/number?`. |
| medium | `src/cljc/yin/vm/engine.cljc:1852-1860` | Kernel-shape pruning applies to any map of that shape, including guest data. | Two-mode trace. |
| low | `src/cljc/yin/vm/ast_walker.cljc:278` | Refusal ex-data carries the applied value (`{:fn …}`). | Carry a kind, not the value. |
