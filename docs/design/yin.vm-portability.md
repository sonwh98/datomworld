---
description: Hyper-portability across host platforms using Yin skeletons and Yang text-level macros.
---

# YIN.VM HYPER-PORTABILITY

To achieve universal execution across an arbitrary number of target platforms (C, JavaScript, Python, Rust, WASM, POSIX Shell), Yin.VM does not rely on writing bespoke compiler backends for each language. Instead, it leverages a text-level annotation and replacement system, conceptually similar to Ribbit.

This design cleanly enforces the Yin/Yang separation:
- **Yang (The Compiler):** Operates entirely on the Universal AST datom stream. At the edge, it acts as a smart text preprocessor.
- **Yin (The VM Skeletons):** Host-specific, minimal execution loops written in target languages, acting as templates.

## 1. YIN SKELETONS (HOST TEMPLATES)

For each target platform, a minimal Yin skeleton is written in the native host language (e.g., `src/host/c/yin.c`, `src/host/js/yin.js`).

A skeleton is responsible for only three things:
1. Instantiating a basic Moduli Space (a lightweight, in-memory datom index/cache).
2. Deserializing an initial datom stream.
3. Executing the continuation CESK loop.

Crucially, skeletons are not pure source code. They contain text-level macros disguised as comments that guide the Yang preprocessor.

```c
// Example: src/host/c/yin.c
#include <stdio.h>

// @@(replace "__DATOM_STREAM__" (encode-datoms))@@
char *input = "__DATOM_STREAM__";

void run_vm() {
    // @@(feature :stream/make
    init_io();
    // )@@

    while(has_datoms()) {
        process_datom();
    }
}

void call_primitive(int prim_id) {
    switch (prim_id) {
        // @@(primitives (gen "case " index ":" body))@@
    }
}
```

## 2. THE SKELETON CONTRACT

The portability claim lives or dies on the size of a skeleton. Ribbit ports
cheaply because its contract is tiny: six instructions, one data type. Yin's
equivalent contract is defined here. A host implementation is a Yin skeleton
if and only if it implements the four parts below, nothing more. Everything
outside this contract is Yang's job and happens before the datom stream is
serialized.

The normative vocabulary is the one exercised by
`src/cljc/yin/vm/prototype/cesk_space.cljc` and its test suite; this section
freezes it as contract version 0.

### 2.1 Value model

A skeleton must represent exactly these value kinds. This is the closed set a
datom cell (`e`, `v`, or a scalar `:cfg/val` / `:cell/value`) can hold; the
reference machine currently exercises only integer, boolean, and symbol as
flowing values, but a conformant store must carry all of them:

| Kind        | Notes                                                     |
|-------------|-----------------------------------------------------------|
| integer     | entity ids, `t`, and literals share this type             |
| boolean     |                                                           |
| nil         | absent / empty cell; `truthy?` treats nil and false as falsy |
| string      |                                                           |
| symbol      | interned; identity comparison must work                   |
| keyword     | interned; attribute names; identity comparison must work  |
| tuple       | fixed-size sequence of the above; the store's own datoms are 5-tuples. Not a flowing value: Linda tuples live as entities with `:tuple/*` attributes |
| ref         | an entity id tagged as a reference                        |

Closures and continuations are **not** host values. A closure is an entity id;
a continuation is an entity id. They travel in ref attributes (`:cell/ref`,
`:cfg/val-ref`, `:k/clo`), never as host function pointers. This is the single
rule that makes state migratable: no live value ever points into host memory.

### 2.2 The space (minimal Moduli Space)

The skeleton's datom store is an append-only log of `[e a v t m]` with three
operations:

1. `add(e, a, v)`: append, stamped with the machine's current step `t` and
   owner entity `m`. There is no retraction and no update.
2. `pull(e, attrs)`: latest assertion per attribute wins. Because the machine
   writes every cell exactly once, last-wins is exact, not a heuristic.
3. `match(template)`: Linda-style template matching over `[e a v]` with
   wildcards, for `out`/`rd` coordination and wait-set retry.

Plus eid allocation from a configured disjoint range (`eid-base`), so machines
sharing a space never collide.

An index (e.g. a by-entity hash map maintained on `add`) is permitted and
expected, but it is a cache of the log, never the source of truth. **No
Datalog engine, no schema validation, no retraction machinery is part of the
contract.** Full query lives outside the skeleton, in hosts that also run the
reference implementation.

### 2.3 The machine (CESK transition relation)

One function: `step(space, cfg) -> cfg' | halt(v) | blocked(wait)`, plus a
fuel-bounded driver loop. Alongside the space, the machine keeps a small
host-local cursor: the next eid, the step counter `t`, the owner eid, and the
current cfg eid. The cursor is the one piece of mutable state exempt from the
"everything is datoms" rule; it never migrates, because a resumed machine
rebuilds it from the log. A config entity carries:

    :cfg/mode      :eval | :apply
    :cfg/ctrl      ref to a :yin/* AST node
    :cfg/env       ref to a frame chain (:frame/parent, :bind/name, :bind/addr)
    :cfg/kont      ref to a continuation chain (:k/tag, :k/next, ...)
    :cfg/val       scalar result (or :cfg/val-ref for entity-valued results)

`:eval` mode dispatches on `:yin/type` over the closed node set:

    :literal :variable :lambda :application :if :let :letrec :out :rd

`:apply` mode dispatches on `:k/tag` over the closed frame set:

    :done :if :let :letrec :out-val :prim1 :prim2 :app-fn :app-arg

Primitive operators are the closed set `+ - * < =`; everything richer arrives
as a primitive via section 4 (cross-language macros), not as a new node type.

Two closed dispatch tables and three space operations: that is the whole
machine. Growing either table is a contract version bump, visible to every
host at once, which is the point. The pressure this creates ("I can't just
add a node type") is the restriction that keeps 25 skeletons in lockstep.

### 2.4 The effect boundary (host capabilities)

The only part of a skeleton that legitimately differs per host is the effect
handler table. Unlike 2.1-2.3, this vocabulary is not frozen from the
prototype: `cesk_space.cljc` exercises zero effects (its only coordination is
`out`/`rd`, which are part of the machine relation). The set below is
inherited from the pre-datomised `yin.vm` engine and becomes normative only
when the prototype grows an effect boundary; until then, contract version 0's
exercised effect set is empty and a v0 skeleton is a pure evaluator. The
contract effect set:

    :vm/store-put
    :stream/make  :stream/put  :stream/cursor  :stream/next  :stream/close
    :module/require              ;; planned; not yet an engine effect keyword
    :dao.stream.apply/call

Parking and resuming continuations is not on this list: blocking is part of
the machine relation itself (the `blocked(wait)` result and `:wait/*`
entities in 2.3), so it costs a host nothing.

Each effect name is also a **feature name**: this is the concrete mechanism
behind feature trimming (section 3). The features a program requires are the
union of effect names reachable in its datom stream; every
`@@(feature ...)@@` block in a skeleton is keyed by an effect name; Yang
strips blocks whose key is not in that union. A skeleton with zero stream
effects compiles to a pure evaluator.

### 2.5 What a skeleton does NOT contain

Explicit non-requirements, because they are where VM ports usually bloat:

- No reader or parser: the datom stream arrives pre-encoded (section 3).
- No macroexpansion, no name resolution: Yang finished those.
- No Datalog, no schema, no retractions.
- No garbage collection of the space: the log is append-only and bounded by
  fuel; compaction is a future host-independent transducer, not a host duty.
- No bytecode. (Hosts MAY add an opcode fast path later, but only if they can
  materialize the datomised E/S/K on demand; the CESK relation stays the
  conformance target.)

### 2.6 Conformance

The `.cljc` reference VM is the oracle. A skeleton is conformant when, for
every stream in the conformance suite, it produces a **bit-identical datom
log** (modulo `eid-base` offset), not just the same final value. Same
configs, same `t` stamps, same cell writes. This is the strongest possible
form of the determinism claim in section 5, and it makes differential testing
mechanical: run the reference on JVM or Node, run the skeleton, diff two
files. One qualifier: eids also appear in the `v` column, where refs share
the integer type with scalar literals, so the differ must know the closed set
of ref attributes (`:cell/ref`, `:cfg/val-ref`, `:cfg/ctrl`, `:cfg/env`,
`:cfg/kont`, `:k/clo`, `:k/next`, `:tuple/ref`, ...) and offset-normalize
only those values; a blind integer shift cannot tell a ref from a literal.

Estimated skeleton size under this contract: two dispatch tables (9 node
types, 9 frame tags), three space operations, and N effect handlers, a few
hundred lines per host. Larger than Ribbit's rib loop, because the contract
carries a database where Ribbit carries a cons cell, but bounded, mechanical,
and with no per-host design decisions beyond the effect handlers.

## 3. THE YANG PREPROCESSOR (AST-DATOMS->HOST-SOURCE)

> **Status: design proposal. Nothing in this section is implemented.**
> No `ast-datoms->host-source` transducer, no base-92 encoding, no
> `@@(replace ...)@@` / `@@(feature ...)@@` annotation syntax, and no
> `src/host/` skeletons exist yet. Section 2 (the contract) is grounded
> in the prototype; this section and section 4 describe how the contract
> will be consumed once Yang grows a host-target stage.

Yang's compilation pipeline models everything as a stream processor. When a user requests compilation to a specific host (e.g., C), Yang appends a new terminal transducer to the pipeline: `ast-datoms->host-source`.

This stage consumes the bounded, resolved stream of Universal AST datoms. It does not parse C code; it treats the C skeleton as an arbitrary text template.

### Process:
1. **Serialization:** Yang compresses the bounded datom stream into a dense string format. To optimize for size, datoms are encoded using a custom base-92 dictionary projection rather than raw EDN, ensuring that integers, keywords, and structural tuples take up minimal bytes.
2. **Injection:** Yang finds the `@@(replace ...)@@` annotations in the target skeleton and replaces the placeholder text with the compressed datom string.
3. **Dead Code Elimination (Feature Trimming):** Yang inspects the datom stream for required capabilities. Feature keys are effect names (section 2.4): if the program never emits a `:stream/put` intent, Yang strips the `@@(feature :stream/put ...)@@` blocks from the host skeleton entirely. This guarantees zero-overhead execution for unused features.

## 4. CROSS-LANGUAGE MACROS (DYNAMIC PRIMITIVES)

> **Status: design proposal. `yang/cond-expand` and `define-primitive`
> do not exist.** The existing macro system (`yin.vm.macro`) operates on
> the datom stream with `:yin/macro-expand` nodes and is language-agnostic
> (see `docs/cross-language-macro.md`). This section describes how
> host-specific code injection would layer on top of that system once
> the Yang preprocessor (section 3) is implemented.

Extensibility is achieved by allowing the host language to leak through the abstraction purely at compile time. 

If a user needs a highly specific, optimized piece of host logic (e.g., direct DOM manipulation in JS, or hardware registers in C), they define a primitive using Yang's `define-primitive` macro.

```clojure
(yang/cond-expand 
  ((host :js)
   (define-primitive (dom-alert text)
     "alert(read_string(pop()));"))
  ((host :c)
   (define-primitive (dom-alert text)
     "printf(\"Alert: %s\\n\", read_string(pop()));")))
```

Internally, Yang parses this into the Universal AST as a specialized datom:
`[-10 :yin.macro/host-code "alert(read_string(pop()));" 0 0]`

Negative eids mark compile-time-only datoms: they exist only inside Yang's
pipeline and never reach a skeleton, so they sit outside the positive
`eid-base` ranges that section 2.2 allocates to running machines.

When `ast-datoms->host-source` processes the stream, it intercepts these `:yin.macro/host-code` datoms and directly stamps their string values into the host skeleton's `@@(primitives)@@` block. 

## 5. ARCHITECTURAL PURITY

This mechanism maintains the non-negotiable invariants:
- **No collapse of interpretation and execution:** Yang generates datoms; Yin executes them. The host language compilation is just an extremely late binding of the execution environment.
- **Explicit Causality:** The generated `.c` or `.js` file is completely deterministic and traces perfectly back to the canonical datom stream.
- **Restrictions are a Feature:** By artificially restricting the compiler from "knowing" C or JavaScript, we force all semantic complexity into the Universal AST datoms, allowing the system to scale to 25+ languages trivially.
