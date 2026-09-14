# yin.vm.code-as-tuples — code as content-addressed tuples

Status: Proposed, revision 7 (after the sixth 2026-09-14 review). This document
implements seven owner rulings of 2026-09-14: the Universal AST becomes
flat tag-first positional tuples; the grammar is fixed-arity, saturated,
and provenance-free; `dao.space.query/q` runs over the tuples directly; the
semantic VM loads tuples as its primary path; code on a stream is tuples
end to end; `t` and `m` live in a ledger of tuples over code addresses; and
datoms `[e a v t m]` are the reference layer. The rulings are inputs. What
follows is their implementation as a protocol: every sentence is a rule of
the design, and each call it makes is stated once.

This document is subordinate to
[`datom.world.md`](./datom.world.md) (axioms and invariants),
[`datom.md`](./datom.md) (the datom contract: stream-local `e`/`t`/`m`,
foreign references only in `v`, reserved markers),
[`yin.vm.semantic.md`](./yin.vm.semantic.md) (the segment, its opcode table
§2.4, well-formedness §2.6, lowering §5),
[`yin.vm.universal-continuation-format.md`](./yin.vm.universal-continuation-format.md)
(UCF: the canonical instruction vector §7.3.2, contract stamp §7.3.3,
primitive profiles §7.5.2, dependency closure §7.6.1, custody §7.7),
[`yin.vm.macro.md`](./yin.vm.macro.md) (admission, harvest, and the
expansion event), [`dao.jing.md`](./dao.jing.md) (content addressing and
its open canonical encoding), and
[`dao.space.query.md`](./dao.space.query.md) (the positional Datalog
surface and current-state resolution). It extends them; where it restates
one of their rules it cites the rule. Where it supersedes one, it says so
(§7.2). Line numbers were read from the working tree on 2026-09-14.

---

## §1 Stance and layering

Code lives in one layer only, content; the other two layers never hold
code, only facts about it and views of it. Each has one job, and the
things that never cross between them are what make the design hold.

+----------------+--------------------------------+-------------------------------------+--------------------+--------------------------+--------------------------------+
| Layer          | Example                        | What it holds                       | Identity           | Where it lives           | Mutability                     |
+================+================================+=====================================+====================+==========================+================================+
| **Content**    | `[:lambda [x] [:variable x]]`  | The code itself, in its canonical   | the content        | `dao.jing`, under its    | immutable; no `t`, no `m`, no  |
|                |                                | tuple form: AST trees (§2) and      | address            | address                  | name, no occurrence, no        |
|                |                                | instruction vectors (§5); and       | `(dao.jing/segment | (`src/cljc/dao/jing.cljc | predecessor address inside     |
|                |                                | ledger records (§8.2), which are    | -key value)`       | :217-225`)               | code content                   |
|                |                                | also content-addressed values       |                    |                          |                                |
+----------------+--------------------------------+-------------------------------------+--------------------+--------------------------+--------------------------------+
| **Refs**       | [e :yin/code                   | Datoms `[e a v t m]` whose `v` is a | the datom itself,  | the ledger relation      | append-only; the hash chain is |
|                | :segment/sha256-…]             | code or record address: names,      | in a `dao.space`   | (§8), transacted by a    | realized by ledger records and |
|                |                                | history, retraction, derivation,    | ledger             | `dao.space` transactor   | the refs that publish them     |
|                |                                | expansion, publication, custody     |                    |                          | (§8.2)                         |
+----------------+--------------------------------+-------------------------------------+--------------------+--------------------------+--------------------------------+
| **Query**      | `[A :application C [D E]       | Entity-shaped views of code         | derived; never an  | computed from content by | recomputed at will; discarding |
|                | true]`                         | internals: the flat per-node rows   | identity           | a pure function, kept by | a projection loses nothing     |
|                |                                | `[id tag & slots]`, the segment     |                    | whoever queries          |                                |
|                |                                | rows `[pc tag & ops]`, and the      |                    |                          |                                |
|                |                                | datom projection `[e a v t m]` of a |                    |                          |                                |
|                |                                | tree                                |                    |                          |                                |
+----------------+--------------------------------+-------------------------------------+--------------------+--------------------------+--------------------------------+

The layering is the git/Unison one: content is what git calls objects — blobs and
terms named by hash — refs are `name → hash` entries with history, and a projection is a
working tree or an index that can be regenerated from the content.

The third layer is named for its job, querying; its members are the
projections — the flat per-node projection (`§6.1`), the segment rows
(`§6.2`), and the datom projection (`§6.5`) — and wherever this document
says "a projection" it means one of those forms, never the layer.

**What the datom layer is for.** A datom never holds a node or an
instruction under this design; it holds a fact whose subject or value is an
address. Four jobs remain, and each is a thing an immutable value cannot do:

+------------------------+------------------------------------------------------------------------------------------------------------------------------------+
| Job                    | Why content cannot do it                                                                                                           |
+========================+====================================================================================================================================+
| naming                 | `[name :yin/code addr]` says what a name currently means; a name is mutable and retractable, content is not                        |
+------------------------+------------------------------------------------------------------------------------------------------------------------------------+
| time and history       | `t` and `m`, assert/retract, as-of views — an immutable value cannot be retracted, so the ledger rides on datoms                   |
+------------------------+------------------------------------------------------------------------------------------------------------------------------------+
| provenance and custody | derive, expand, and publish events, UCF's occurrence ledgers and leases: facts a medium possesses; a hash possesses nothing        |
+------------------------+------------------------------------------------------------------------------------------------------------------------------------+
| indexed storage        | the transactor, btree, and covered indexes, `pull`/entity navigation, attribute-dimension queries (`§6.5`): datom-native machinery |
+------------------------+------------------------------------------------------------------------------------------------------------------------------------+

In return, datoms no longer do three things: they are not identity (the
address is), not the load path (loaders take content directly, `§7.1`), and
not execution (the hot loop decodes once and never consults a datom,
`§7.6`). Code was the special case — the substrate's ordinary business
(traces, stream facts, stigmergy, schema, data) is datoms; this design only
pulled code out of them and made it properly immutable.

Said plainly: the datom layer is not a description of the code; it is the
only place the code's name, past, and ownership exist. Content says what
the program is; datoms say which program we mean, where it came from, and
who may run it.

What never crosses:

- **Nothing about time, naming, occurrence, or provenance enters code
  content.** `t`, `m`, entity ids, source positions, the macro flag, the
  expansion that produced a tree, the AST a segment came from, and the
  address itself are all outside the hashed value. UCF §7.3.2 already
  excludes these from the instruction vector; §2.5 applies the same
  exclusion to the AST. The consequence is stated, not hidden: code
  content does not know its predecessor, so the chain between values is
  held by ledger records, never by the values themselves (§8.2).
- **No code content enters a ref.** A ref carries an address and nothing
  else about the code; two ledgers that agree on `dao.jing` addresses
  interoperate without exchanging trees.
- **No projection id is ever named by a ref, a ledger row, a closure, or a
  continuation.** Projection ids are addresses (flat rows) or local tempids
  (datom projection). Only addresses cross layers; this is what retires the
  `:eid` sharing of `src/cljc/yin/vm/v2.cljc:394-411` (§4.4).
- **Content identity is not occurrence identity.** An address says *what*
  a value is. Which source position, which batch, which call site, which
  expansion attempt produced it is occurrence information, and it is kept
  in side tables and events keyed by an origin (source batch or producing
  event), root address, and structural path (§2.5, §4.4, §8.4), never by
  content address alone.
- **The hot loop reads content only.** An evaluator decodes tuple content
  into its image at load and never consults a ref or a projection while
  stepping (§7.6).

---

## §2 The AST tuple grammar

### 2.1 Form

The AST is the code, and the tuple tree is the AST itself in canonical
form, not a projection of it: there is no further representation behind
these vectors. The walker walks them, loaders load them, and the address
names them. The flat per-node rows and the datom batch are projections of
this form (§6), which is why they live in the query layer and this tree
lives here.

A node is a vector whose first element is its tag keyword and whose
remaining elements are its slots, in a fixed order with a fixed count per
tag. Child slots hold child nodes inline, so a tree is one nested value:

```clojure
[:application [:variable +] [[:literal 1] [:variable x]] true]
[:lambda [x] [:application [:variable +] [[:variable x] [:literal 1]] true]]
```

This is the Erlang abstract-format shape, and it is exactly what the
walker's `case` already dispatches on: every arm of the cold case
(`src/cljc/yin/vm/v2/ast_walker.cljc:359-469`) and the hot case
(`ast_walker.cljc:600-626`) keys on the node's type and reads a fixed set of
named fields. Position replaces name; nothing about the dispatch changes.

**The tuple is immutable under `:frame`; runtime bookkeeping lives beside
it.** Today the walker writes into the AST node it holds under `:frame`:
the cold `:eval-operator` arm `assoc`s `:operator-evaluated?` and `:fn`
into it (`ast_walker.cljc:213-217`), the cold `:eval-operand` arm `assoc`s
`:evaluated` (`ast_walker.cljc:226-237`), and the hot arms do the same
(`ast_walker.cljc:532-541`, `:576-580`). A keyword cannot be associated
into a vector, and a runtime slot in a canonical tuple would break fixed
arity and mix execution state into syntax. The continuation frames are
therefore re-schemed so that the node is read-only and every mutable field
is a sibling key of the frame map (§2.6). Frame continuations remain runtime
state, not syntax, as do closures (`ast_walker.cljc:364-370`) and reified
continuations (`ast_walker.cljc:422-427`).

### 2.2 Slot kinds

Every slot in the table has one kind. The kind is fixed by the tag and the
position; it is never inferred from the value. A literal whose value happens
to look like a node is a value, which is the rule `linearize/ast-children`
already states (`src/cljc/yin/vm/v2/linearize.cljc:230-242`).

+---------+----------------------------------+---------------------------------------------------------------------------------------------------------------------------+
| Kind    | Admits                           | Notes                                                                                                                     |
+=========+==================================+===========================================================================================================================+
| `node`  | one child tuple                  | inline in the canonical form (§4)                                                                                         |
+---------+----------------------------------+---------------------------------------------------------------------------------------------------------------------------+
| `nodes` | a vector of child tuples,        | one slot, so arity stays fixed however many operands there are                                                            |
|         | possibly empty                   |                                                                                                                           |
+---------+----------------------------------+---------------------------------------------------------------------------------------------------------------------------+
| `data`  | plain data as                    | never a host function or object                                                                                           |
|         | `linearize/plain-data?` defines  |                                                                                                                           |
|         | it (`linearize.cljc:45-58`):     |                                                                                                                           |
|         | nil, booleans, numbers, strings, |                                                                                                                           |
|         | keywords, symbols, and           |                                                                                                                           |
|         | collections of them, with plain  |                                                                                                                           |
|         | metadata                         |                                                                                                                           |
+---------+----------------------------------+---------------------------------------------------------------------------------------------------------------------------+
| `key`   | a store key: any value of the    | the store contract is a map lookup with no key restriction (`ast_walker.cljc:401`, `:402-404`); the linearizer admits any |
|         | `data` kind                      | `plain-data?` key (`linearize.cljc:79-85`, `:138-141`) and the loader carries it through unchanged                        |
|         |                                  | (`src/cljc/yin/vm/v2/semantic.cljc:577-579`), so symbols (`yin/def`), keywords (`test/yin/vm/v2/parity_test.cljc:99`),    |
|         |                                  | and numbers are all portable keys today. `key` is a named kind rather than `data` only so that the same definition is     |
|         |                                  | cited by AST validation (§7.4), instruction validation (§7.5), dependency extraction (§7.7), and the store-slice encoding |
|         |                                  | of UCF §7.6.2; it is the `data` domain and nothing narrower                                                               |
+---------+----------------------------------+---------------------------------------------------------------------------------------------------------------------------+
| `sym`   | one symbol                       |                                                                                                                           |
+---------+----------------------------------+---------------------------------------------------------------------------------------------------------------------------+
| `syms`  | a vector of symbols, possibly    |                                                                                                                           |
|         | empty                            |                                                                                                                           |
+---------+----------------------------------+---------------------------------------------------------------------------------------------------------------------------+
| `kw`    | one keyword                      |                                                                                                                           |
+---------+----------------------------------+---------------------------------------------------------------------------------------------------------------------------+
| `str`   | one string                       |                                                                                                                           |
+---------+----------------------------------+---------------------------------------------------------------------------------------------------------------------------+
| `int`   | one non-negative integer         |                                                                                                                           |
+---------+----------------------------------+---------------------------------------------------------------------------------------------------------------------------+
| `bool`  | exactly `true` or `false`        | never nil, never absent                                                                                                   |
+---------+----------------------------------+---------------------------------------------------------------------------------------------------------------------------+

### 2.3 The table

The tag set is the walker's dispatch set reconciled with the codec's
inventory (`src/cljc/yin/vm/v2.cljc:413-478`) under the two boundary calls
of §3. Arity counts the tag.

+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+
| Tag                        | Arity | Tuple                                    | Slot kinds        | Walker arm            | Codec arm         |
+============================+=======+==========================================+===================+=======================+===================+
| `:literal`                 | 2     | `[:literal value]`                       | data              | `ast_walker.cljc:360` | `v2.cljc:414-415` |
+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+
| `:variable`                | 2     | `[:variable name]`                       | sym               | `:361-363`            | `:416-417`        |
+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+
| `:lambda`                  | 3     | `[:lambda params body]`                  | syms, node        | `:364-370`            | `:418-423`        |
+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+
| `:application`             | 4     | `[:application operator operands tail?]` | node, nodes, bool | `:371-376`            | `:424-429`        |
+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+
| `:if`                      | 4     | `[:if test consequent alternate]`        | node, node, node  | `:377-381`            | `:435-441`        |
+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+
| `:dao.stream.apply/call`   | 3     | `[:dao.stream.apply/call op operands]`   | kw, nodes         | `:382-394`            | `:430-434`        |
+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+
| `:vm/gensym`               | 2     | `[:vm/gensym prefix]`                    | str               | `:395-400`            | `:443-444`        |
+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+
| `:vm/store-get`            | 2     | `[:vm/store-get key]`                    | key               | `:401`                | `:445-446`        |
+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+
| `:vm/store-put`            | 3     | `[:vm/store-put key value]`              | key, data         | `:402-409`            | `:447-449`        |
+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+
| `:vm/current-continuation` | 1     | `[:vm/current-continuation]`             | —                 | `:422-427`            | `:474-475`        |
+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+
| `:vm/park`                 | 1     | `[:vm/park]`                             | —                 | `:428-430`            | `:469`            |
+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+
| `:vm/resume`               | 3     | `[:vm/resume parked-id val]`             | kw, node          | `:431-438`            | `:470-473`        |
+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+
| `:stream/make`             | 2     | `[:stream/make buffer]`                  | int               | `:439-447`            | `:451-453`        |
+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+
| `:stream/put`              | 3     | `[:stream/put target val]`               | node, node        | `:448-454`            | `:454-458`        |
+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+
| `:stream/cursor`           | 2     | `[:stream/cursor source]`                | node              | `:455-461`            | `:459-461`        |
+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+
| `:stream/next`             | 2     | `[:stream/next source]`                  | node              | `:462-468`            | `:462-464`        |
+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+
| `:stream/close`            | 2     | `[:stream/close source]`                 | node              | added by §3.2         | `:465-467`        |
+----------------------------+-------+------------------------------------------+-------------------+-----------------------+-------------------+

`:vm/store-update` is not a tag (§3.1). `:yin/macro-expand` is not a tag:
the walker has no arm for it and the linearizer rejects it
(`linearize.cljc:19-22`); expansion happens before code reaches this
grammar (`yin.vm.macro.md`, decision 1).

The `:vm/store-put` value slot is data, not a node: the walker stores
`(:val node)` without evaluating it (`ast_walker.cljc:402-404`) and the
codec emits it as `:yin/value` (`v2.cljc:449`). The `:vm/resume` value slot
is a node: the walker evaluates it (`ast_walker.cljc:431-438`).

**`tail?` is a slot of `:application` only.** The one reader of the tail
mark in the whole v2 lineage is the linearizer's `:call` emission
(`linearize.cljc:107-108`), which reads it on `:application`; the walker
never reads it; `:ffi-call` has no tail operand (`yin.vm.semantic.md`
§2.4). The yang frontends mark `:tail? true` on literals, defs, and other
nodes (`src/cljc/yang/clojure.cljc:132`, `:164`; `src/cljc/yang/python.cljc:481`),
and the codec faithfully persists every such mark (`v2.cljc:412`). In the
tuple form those marks are dropped at the boundary: a mark no evaluator can
observe is not part of the program's syntax as the evaluators read it. The
mark on an `:if`'s branches is carried by the branch `:application` nodes
themselves, which is how `yin.vm.semantic.md` §5.3's "branches inherit it"
is realized.

### 2.4 Saturation

A tuple carries every default the loader would otherwise apply, so a
program written with a default omitted and one written with it stated are
one value with one address (UCF §7.3.2, *Saturation*). The map-to-tuple
boundary materializes defaults; a tuple with a nil in a saturated slot is
invalid, not defaulted.

+----------------------------------------------------+-------------------------------------+-----------------------------------------------------------------------------+
| Slot                                               | Default written                     | Source of the default today                                                 |
+====================================================+=====================================+=============================================================================+
| `:vm/gensym` prefix                                | `"id"`                              | walker `ast_walker.cljc:395`, codec `v2.cljc:444`, loader                   |
|                                                    |                                     | `semantic.cljc:576`                                                         |
+----------------------------------------------------+-------------------------------------+-----------------------------------------------------------------------------+
| `:stream/make` buffer                              | `vm/default-stream-capacity` = 1024 | walker `ast_walker.cljc:439-440`, codec `v2.cljc:453`, loader               |
|                                                    |                                     | `semantic.cljc:580-581`                                                     |
+----------------------------------------------------+-------------------------------------+-----------------------------------------------------------------------------+
| `:application` tail?                               | `false`                             | codec emits only `true` (`v2.cljc:412`); the linearizer reads `(boolean …)` |
|                                                    |                                     | (`linearize.cljc:107-108`)                                                  |
+----------------------------------------------------+-------------------------------------+-----------------------------------------------------------------------------+
| `:application` / `:dao.stream.apply/call` operands | `[]`                                | walker `ast_walker.cljc:383`                                                |
+----------------------------------------------------+-------------------------------------+-----------------------------------------------------------------------------+
| `:lambda` params                                   | `[]`                                | —                                                                           |
+----------------------------------------------------+-------------------------------------+-----------------------------------------------------------------------------+

### 2.5 Exclusions, occurrences, and side tables

The canonical form excludes, and a conforming encoder strips:

- **Source positions** and any frontend metadata (`:yang/*` keys, Clojure
  reader metadata on symbols).
- **`:macro?`** and `:phase-policy`. `yang.clojure` sets them on defmacro
  lambdas (`clojure.cljc:436-438`) and the codec persists `:yin/macro?`
  (`v2.cljc:419-420`, schema `v2.cljc:312`). No evaluator reads them
  (`ast_walker.cljc:20-23`). The expander needs the fact that a particular
  definition is a macro; that fact travels beside the tree as an
  occurrence-bound declaration (§8.5), not as a slot.
- **`:eid`** (§4.4).
- **`:yin/root`.** A tree is its own root; the batch or the ref that names
  the tree names the root. No root fact exists in the tuple form.
- **`t`, `m`, provenance.** Every provenance fact the codec or linearizer
  writes today (`:yin.code/source`, `:yin.code/derived-from`) becomes a
  ledger record (§8) or a side-table entry.

**Occurrences.** Content identity names *what*; three further things
name *where*, and each is a distinct coordinate:

- A **structural path** names a place inside one tree: a vector of slot
  indices from the root, with an index into a `nodes` slot written as a
  pair `[slot i]`. `[]` is the root; `[2 [1 0]]` is the first operand of
  the root's second slot. A path is a pure function of the tree, stable
  across media and hosts. `[root-address path]` separates two identical
  subtrees at two places in one tree, and nothing more.
- A **source occurrence** names one admission of one tree: `[medium batch
  j]`, the program medium's identity, the batch coordinate on it (the
  `:yin/source-batch` of `yin.vm.macro.md` §4.1), and the **member index**
  `j` of the tree within the batch (§8.5's ordered batch carries several
  trees). Two identical trees parsed from two files, admitted in two
  batches, or sitting at two indices of one batch have one root address
  and two source occurrences. A root address alone never identifies a
  source, and neither does a batch without its member index.
- A **producing event** names one generated tree: the `:expand` event
  (§8.4) whose output it is. Two expansion attempts with identical output
  have one output address and two producing events. An event has two
  identities, used in two places: a **log-local entity id**, which only a
  declared reference attribute on the same log may name, and the
  **address of its expansion record** (§8.2), which is portable.

An **occurrence key** is therefore `[origin root-address path]`, where
`origin` is `[:source medium batch j]` for admitted syntax and
`[:expansion record-address]` for generated syntax. Every side table and
every provenance link that must distinguish places, sources, or attempts
is keyed by an occurrence key, never by an address alone, and the member
index travels in the origin uniformly: declarations, source positions,
instruction provenance, and initial expansion events all carry the same
`[:source medium batch j]`. Joining a position table to a batch table on
root address is not a substitute: it yields every batch-position
combination, which is exactly the ambiguity the origin coordinate
removes. A bare log-local event number never appears inside an occurrence
key, because a key travels and the number does not (§8.4).

+------------------------+------------------------------------------------------------------------------------------------------------------------+----------------------+
| Side table             | Row                                                                                                                    | Keeper               |
+========================+========================================================================================================================+======================+
| source positions       | `[[:source medium batch j] root-address path file line col]`                                                           | the frontend that    |
|                        |                                                                                                                        | parsed the file      |
+------------------------+------------------------------------------------------------------------------------------------------------------------+----------------------+
| frontend metadata      | `[origin root-address path key value]`                                                                                 | the frontend         |
+------------------------+------------------------------------------------------------------------------------------------------------------------+----------------------+
| instruction provenance | `[segment-address pc origin root-address path]`                                                                        | the lowering (§5.3)  |
+------------------------+------------------------------------------------------------------------------------------------------------------------+----------------------+
| macro declarations     | `[[:source medium batch j] path]`: the definition at `path` of the batch's `j`-th tree is a macro definition; inside   | the frontend,        |
|                        | the batch envelope this is written `[j path :yin.macro/definition]` (§8.5)                                             | consumed by the      |
|                        |                                                                                                                        | expander             |
+------------------------+------------------------------------------------------------------------------------------------------------------------+----------------------+
| harvest catalogue      | `[[:source medium batch] h j path]`: occurrence `[j path]` belongs to the `h`-th **original definition** in the        | the frontend or the  |
|                        | batch's admission order, plain or macro; one original definition shared at several paths has several rows with one     | §9.1 adapter,        |
|                        | `h`. The macro-only declaration ordinal `k` is derived from this table and the declarations, not stored (§8.5)         | consumed by the      |
|                        |                                                                                                                        | expander             |
+------------------------+------------------------------------------------------------------------------------------------------------------------+----------------------+

### 2.6 Continuation frames over tuples

The frame schemas below replace the ones the walker builds today. In every
frame `:node` holds the canonical tuple unchanged; nothing is ever written
into it. Evaluation position is the count of evaluated operands, which is
how the walker already computes it (`ast_walker.cljc:231`, `:575`).

+---------------------------------------+------------------------------------+-------------------------------------------------------------------------------------------+
| Frame `:type`                         | Keys                               | Replaces                                                                                  |
+=======================================+====================================+===========================================================================================+
| `:eval-operator`                      | `:node` (the `:application`        | `{:frame node …}` at `ast_walker.cljc:375`, `:619`                                        |
|                                       | tuple), `:next`, `:env`            |                                                                                           |
+---------------------------------------+------------------------------------+-------------------------------------------------------------------------------------------+
| `:eval-operand`                       | `:node`, `:fn` (the evaluated      | the `assoc` of `:operator-evaluated?`/`:fn` into the node at `:213-217`, `:532-534`, and  |
|                                       | operator), `:evaluated` (vector),  | of `:evaluated` at `:226-237`, `:576-580`; `:operator-evaluated?` is redundant with the   |
|                                       | `:next`, `:env`                    | frame type and is dropped                                                                 |
+---------------------------------------+------------------------------------+-------------------------------------------------------------------------------------------+
| `:eval-test`                          | `:node` (the `:if` tuple),         | `{:frame node …}` at `:380`, `:624`; reads `(nth node 2)` / `(nth node 3)` instead of     |
|                                       | `:next`, `:env`                    | `(:consequent frame)` at `:243`, `:586-588`                                               |
+---------------------------------------+------------------------------------+-------------------------------------------------------------------------------------------+
| `:dao.stream.v2.apply/eval-operand`   | `:node` (the call tuple),          | the fresh frame at `:390`, which already keeps `:evaluated` beside the operands; `:op`    |
|                                       | `:evaluated`, `:next`, `:env`      | and `:operands` become `(nth node 1)` and `(nth node 2)`                                  |
+---------------------------------------+------------------------------------+-------------------------------------------------------------------------------------------+
| `:eval-stream-put-target`             | `:node` (the `:stream/put` tuple), | `{:frame node …}` at `:453`; reads `(nth node 2)` instead of `(:val frame)` at `:289`     |
|                                       | `:next`, `:env`                    |                                                                                           |
+---------------------------------------+------------------------------------+-------------------------------------------------------------------------------------------+
| `:eval-stream-put-val`                | `:stream-ref`, `:next`, `:env`     | unchanged (`:293-295`)                                                                    |
+---------------------------------------+------------------------------------+-------------------------------------------------------------------------------------------+
| `:eval-stream-cursor-source`,         | `:node`, `:next`, `:env`           | `:460`, `:467`, and the new §3.2 arm                                                      |
| `:eval-stream-next-cursor`,           |                                    |                                                                                           |
| `:eval-stream-close-source`           |                                    |                                                                                           |
+---------------------------------------+------------------------------------+-------------------------------------------------------------------------------------------+
| `:eval-resume-val`                    | `:parked-id`, `:next`, `:env`      | unchanged (`:434-437`)                                                                    |
+---------------------------------------+------------------------------------+-------------------------------------------------------------------------------------------+

The FFI frames (`:dao.stream.v2.apply/request-sent`, `eval-call`,
`ast_walker.cljc:159-163`, `:138`) hold no node and are unchanged. Wait
entries and parked records hold frames, so they inherit the schema without
change of their own.

---

## §3 The two boundary calls

### 3.1 `:vm/store-update` is excluded from the canonical grammar

The node carries a host function in its `:fn` field and applies it inside
the syntax (`ast_walker.cljc:410-415`). It is excluded on two grounds, and
they are sufficient: canonical content admits no host value (§2.2,
`data`), and the node is absent from every persistent and portable form
already, since the codec has no arm and throws on it (`v2.cljc:413-478`),
the linearizer lists it as unsupported (`linearize.cljc:19-22`), the
semantic VM has no opcode for it, and no test constructs one (a search of
`test/` for the node type on 2026-09-14 found none). Its one reachable path
is the walker's map-input arm, which survives only as long as that path
(§9.1).

The orchestrator's alternative, a `:fn` slot restricted to a named
primitive symbol resolved and profile-checked per UCF §7.5.2, is not
adopted: it would re-admit a node that no persistent form carries, for a
convenience no corpus program uses.

A program that needs the behaviour writes it as an application of the
`yin/def` primitive (`v2.cljc:128`) over a `:vm/store-get` operand:
`(yin/def k (f (vm/store-get k) args…))`. That rewrite is equivalent to
the arm **only under stated conditions**, and this document does not claim
it as a general semantics-preserving migration:

- `yin/def` and `f` resolve through `resolve-var`'s precedence, env → store
  → primitives → modules (`src/cljc/yin/vm/v2/engine.cljc:46-58`), so the
  rewrite is equivalent only when neither name is shadowed at the site;
- the arm stores whatever `(apply f current args)` returns, as data
  (`ast_walker.cljc:410-415`), while an application interprets an
  effect-shaped return (`ast_walker.cljc:184-188`,
  `semantic.cljc:206-220`). An effect descriptor is itself plain data:
  `module/effect?` recognizes any map carrying `:effect`
  (`src/cljc/yin/vm/v2/module.cljc:77-79`), and
  `{:effect :vm/store-put :key :x :val 4}` passes `plain-data?`. So the
  rewrite is equivalent only when `f` returns plain data **that is not an
  effect descriptor under the applicable execution contract**; a result
  the contract would interpret is stored by the arm and executed by the
  rewrite.

### 3.2 `:stream/close` exists, in both codec and walker

One grammar truth: the instruction is real. The codec writes it
(`v2.cljc:465-467`) and reads it back (`v2.cljc:549-550`), the linearizer
lowers it (`linearize.cljc:135-136`), `yin.vm.v2.code/mnemonics` admits
`:stream-close` (`src/cljc/yin/vm/v2/code.cljc:12-16`), the semantic VM
decodes it (`semantic.cljc:585`) and executes it in its hot loop
(`semantic.cljc:397-399`), and the engine handles the effect
(`engine.cljc:480-482`). Only the walker lacks the arm
(`ast_walker.cljc:359-469`).

The walker gains it: evaluate `source`, then raise `{:effect :stream/close
:stream ref}` through `engine/handle-effect`, through the
`:eval-stream-close-source` frame of §2.6, shaped exactly as
`:eval-stream-cursor-source` (`ast_walker.cljc:319-326`). Removing the
instruction instead would delete working behaviour from the default
evaluator to match the non-default one; the parity suite is the reason the
two evaluators share one corpus.

---

## §4 Addressing

### 4.1 The grain

The address of a tree is `(dao.jing/segment-key tree)` computed over the
whole canonical nested value (`jing.cljc:217-225`). A tree's address is the
merkle grain the protocol pins: every named payoff below works at this
grain and none needs a finer one.

+-----------------------------------------------+------------------------------------------------------------------------------------------------------------------------+
| Payoff                                        | How it works at the whole-tree grain                                                                                   |
+===============================================+========================================================================================================================+
| `:yin.code/derived-from` as a content address | a derivation record naming the tree address as input and the segment address as output (§8.2) replaces the entity ref  |
|                                               | of `v2.cljc:327` and `linearize.cljc:212`                                                                              |
+-----------------------------------------------+------------------------------------------------------------------------------------------------------------------------+
| the macro ledger                              | an expansion event naming input, output, and macro addresses (§8.4): expansion is a function from address to address,  |
|                                               | and each attempt is its own event                                                                                      |
+-----------------------------------------------+------------------------------------------------------------------------------------------------------------------------+
| module manifests pinning AST content          | a manifest names the tree addresses it exports; equal content is equal address across hosts                            |
+-----------------------------------------------+------------------------------------------------------------------------------------------------------------------------+
| lowering as an address-to-address function    | `lower : tree → instruction vector`, and each application of it is one derivation record (§5.2)                        |
+-----------------------------------------------+------------------------------------------------------------------------------------------------------------------------+

The address is deterministic and operand-order-sensitive: `(add 1 2)` and
`(add 2 1)` address differently, because `order-normalize` sorts maps and
sets but leaves vectors in place (`jing.cljc:45-64`). Literal maps and sets
inside a `data` slot hash order-insensitively, which is correct: they are
values.

### 4.2 The transitional encoder is inherited, and every identity use is blocked on it

`segment-key` hashes an order-normalized `pr-str` (`jing.cljc:67-71`,
`:203-214`). `order-normalize` coerces **every** sequential value to a
vector (`jing.cljc:63`), and `pr-str` prints no metadata. The encoder is
therefore not injective over the `data` domain, and the collisions are not
confined to one collection type:

+--------------------------------------------------------+------------------------------------+--------------------------------------------------------------------------+
| Two different values                                   | One address today                  | Why it matters                                                           |
+========================================================+====================================+==========================================================================+
| `[:literal [1 2]]` and `[:literal (1 2)]`              | lists become vectors               | `conj` appends to one and prepends to the other                          |
+--------------------------------------------------------+------------------------------------+--------------------------------------------------------------------------+
| `[:literal [1 2]]` and `[:literal (seq [1 2])]`        | a seq is sequential but not a list | the same, and a list reject-rule does not see it                         |
+--------------------------------------------------------+------------------------------------+--------------------------------------------------------------------------+
| `[:literal ^{:a 1} [1 2]]` and `[:literal ^{:a 2} [1   | metadata is dropped by `pr-str`    | `data` admits plain metadata (`linearize.cljc:58`) and programs may read |
| 2]]`                                                   |                                    | it                                                                       |
+--------------------------------------------------------+------------------------------------+--------------------------------------------------------------------------+

This is `dao.jing.md`'s first open item (*Canonical encoding*), inherited
unchanged, and it is an acceptance blocker of this design (§10.1).

**Every identity and deduplication use is blocked until the encoding is
fixed.** No restricted value domain is claimed, and none is admitted: a
reject-list over collection types does not make address-based identity
safe, because the admitted domain is open (`plain-data?` admits every
`coll?`, `linearize.cljc:56`) and the encoder's losses are structural. The
block applies uniformly to every addressed value and every use of an
address as identity: `data` slots of trees, `:const` and `:store-put`
operands of vectors, ledger-record maps (§8.2), flat-projection row
collapse (§6.1), DAG sharing (§4.4), process-local address-keyed caches,
`dao.jing` reads that resolve an address to a literal value, and every
ledger row that names an address. Process lifetime is irrelevant. Until
the block lifts, addresses may be *computed* and *carried* for the
conformance work of §7.2, but nothing may treat two equal addresses as
one value.

The three rows above are part of the **encoder conformance obligation**
that lifts the block: the pinned encoding must address each pair
distinctly, and the test that proves it is part of `dao.jing`'s
acceptance, not this document's. When it lands, every address minted under
this design changes with every other `dao.jing` address, the dependency
UCF §7.3.2 already accepts.

### 4.3 Per-node addresses and the per-lambda follow-up

The flat projection (§6.1) keys each node by the address of its own
inlined subtree, computed by the same `segment-key`. These per-node
addresses are a consequence of the hash function applied recursively; they
exist in the projection and nowhere in the canonical form, and nothing in
the protocol names them except queries.

**Per-lambda subterm addressing is a follow-up, not a blocker.** It means
something stronger than a per-node hash: a `:lambda` whose body is
*materialized separately*, referred to by address from the canonical form
of the enclosing tree, lowered to its own segment, and pinned by manifests
on its own. That changes the canonical form (a `node` slot may hold an
address) and the lowering (one segment per lambda rather than out-of-line
bodies in one segment, `linearize.cljc:151-158`). Nothing in this design
depends on it; when it is designed it adds a slot kind and a lowering rule
and invalidates no address of a tree that does not use it.

### 4.4 Sharing by content address replaces `:eid`; occurrences stay outside

Today a shared subtree is emitted once and referenced by a pre-assigned
entity id: `yang.clojure` allocates one for each defmacro lambda
(`clojure.cljc:418-437`) and the codec deduplicates on it
(`v2.cljc:394-411`). Its purpose is that the definition operand and each
call-site operator resolve to one entity so provenance can point at it.

Content addressing gives that for free and in every case, not only the one
`yang.clojure` remembers to mark: two occurrences of one subtree have one
address by construction, one row in the flat projection, and a query "which
sites reference this lambda's content" is a join on its address (§6.4). The
canonical form stays fully inlined; sharing is a property of the
projection and of storage, never of identity. `:eid` is dropped from the
frontends and the codec.

What `:eid` also did, and content addresses do not, is name a *place*.
"This call site" is an occurrence key `[origin root-address path]` (§2.5),
not the site's content address; a query that needs distinct call sites
joins the occurrence relation, not subtree addresses, and a provenance
link that must survive a second identical site, a second identical file,
or a second identical expansion records the occurrence key. Content
addresses serve sharing and cross-media correspondence of content;
occurrence keys serve identity of places, sources, and attempts.

---

## §5 The segment level

### 5.1 The instruction vector is UCF §7.3.2

The segment's canonical form is the positional instruction tuple vector of
UCF §7.3.2: one tuple per instruction, pc is the index, the
`yin.vm.semantic.md` §2.4 table fixes each mnemonic's arity and operand
kinds, defaults are saturated, refs are resolved pcs, and the header folds
away. Its address is `(dao.jing/segment-key vector)`, the value
`yin.vm.semantic.md` §2.2 already types `:yin.code/hash` as. This document
adds nothing to that form and restates none of it.

### 5.2 The hash chain is realized by derivation records

```
tree-address ──lower under lowering profile L, targeting execution contract C──▶ segment-address
```

Neither value carries the other's address: the tree is the input, the
vector excludes `:yin.code/derived-from` (UCF §7.3.2, *The header folds
away*). The link is a **derivation record**, itself content-addressed
(§8.2):

```clojure
{:yin.ledger/op       :derive
 :yin.ledger/input    <tree-address>
 :yin.ledger/output   <segment-address>
 :yin.ledger/function :yin.vm.v2/lower
 :yin.ledger/profile  {:yin.lower/profile "ast-v1"          ; the lowering profile, §5.2.1
                       :yin.code/contract "v2"              ; the UCF §7.3.3 execution contract it targets
                       :yin.k/version     0}}
```

Its address is published in the ledger (§8.3). The chain
`record → input, record → output` is what "the hash chain is the ledger"
means under this design: the chain lives in ledger records and refs, not
in the code content, which rightly excludes it.

#### 5.2.1 The lowering profile

UCF §7.3.3's execution-contract stamp versions the instruction grammar,
the transitions, resolution precedence, effect outcomes, and scheduling.
Its declared scope does not include the AST-to-instruction algorithm, and
two deterministic lowerers can target one execution contract and emit two
different, equally valid vectors. A derivation is therefore verifiable
only against a **lowering profile**, a published revision that pins:

+---------------------------------------+--------------------------------------------------------------------------------------------------------------------------------+
| Pinned by the profile                 | Reference value for `"ast-v1"`                                                                                                 |
+=======================================+================================================================================================================================+
| input grammar                         | the §2 tuple grammar, by revision of this document                                                                             |
+---------------------------------------+--------------------------------------------------------------------------------------------------------------------------------+
| normalization applied before emission | saturation (§2.4), tail marks on `:application` only (§2.3), nothing else                                                      |
+---------------------------------------+--------------------------------------------------------------------------------------------------------------------------------+
| emission rules                        | the §5.3 flattening table of `yin.vm.semantic.md`, in the walker's evaluation order (operator, then operands left to right)    |
+---------------------------------------+--------------------------------------------------------------------------------------------------------------------------------+
| label and body order                  | labels numbered in emission order (`linearize.cljc:76-77`); bodies emitted after the main sequence, in the order lambdas were  |
|                                       | encountered, including lambdas found inside bodies (`linearize.cljc:151-158`)                                                  |
+---------------------------------------+--------------------------------------------------------------------------------------------------------------------------------+
| terminators                           | main sequence ends in `:halt`, every body in `:return` (`linearize.cljc:150`, `:157`)                                          |
+---------------------------------------+--------------------------------------------------------------------------------------------------------------------------------+
| target execution contract             | the UCF §7.3.3 stamp the emitted grammar must satisfy                                                                          |
+---------------------------------------+--------------------------------------------------------------------------------------------------------------------------------+

The profile is a dedicated stamp, not an extension of the execution
contract: an execution contract may be targeted by several profiles, and a
profile is revised whenever any pinned rule changes, whether or not the
execution contract does. A lowering that introduces any host-dependent
choice violates its profile and is a defect. The profile is the structured
map above, carried **exactly** in the record and in the event projection
(§8.2); no string form of it is defined and none is used.

#### 5.2.2 Verification, in two separate steps

A consumer holding a tree, a vector, and a derivation record verifies two
different things and reports them separately:

1. **Content integrity.** Each value hashes to the address it is claimed
   under: `(segment-key tree)` equals `:yin.ledger/input`,
   `(segment-key vector)` equals `:yin.ledger/output`. Failure is
   `:yin.k/hash-mismatch` naming the value. This is UCF §7.3.4's check and
   says nothing about the derivation.
2. **Derivation.** If, and only if, the consumer implements the record's
   `:yin.lower/profile` exactly, it recomputes `(segment-key (lower tree))`
   under that profile and compares it with `:yin.ledger/output`. Equality
   verifies the asserted derivation; inequality is `:yin.k/derivation-
   mismatch`, a defective or dishonest lowering, never repaired silently. A
   consumer implementing a different profile does not recompute: it reports
   `:yin.k/profile-mismatch` naming both profiles, the same outcome UCF
   §7.3.3 gives a different execution contract, and it may still *load* the
   output vector if the vector's own execution contract is one it
   implements, because content integrity (step 1) is independent of who
   lowered it.

Two trees that lower to one vector under one profile yield two records
with one output; one tree lowered under two profiles yields two records
with two outputs, and neither is corruption.

### 5.3 Instruction provenance

`:yin.code/source` (`v2.cljc:326`, `linearize.cljc:225`) leaves the
instruction: UCF §7.3.2 excludes it from the vector, and the tuple form has
nowhere else to put it. It becomes the side table `[segment-address pc
origin root-address path]` of §2.5, emitted by the lowering beside the
vector and keyed by the full occurrence key, so that two identical call
sites, two identical files, two identical trees at two batch indices, or
two expansion attempts map to distinct instruction ranges. The `origin`,
including the member index `j` for admitted syntax, is an input to the
lowering, supplied by the composition that admitted or generated the
tree; the lowering never infers it. Rendering an instruction range back to
source is a join over that table and the source-position table on the
occurrence key, which is the query `yin.vm.semantic.md` §5.3 says it is;
for generated code the join goes through the producing event's record
address to its call occurrence (§8.4).

---

## §6 Querying

### 6.1 The flat per-node projection

`project : tree → #{[id tag & slots]}`, one row per distinct subtree:

- `id` is the subtree's address (§4.3);
- `tag` is the node's tag;
- a `node` slot holds the child's address; a `nodes` slot holds a vector of
  child addresses; every other slot holds its data unchanged.

Because a tag has one arity in the tree, it has one arity in the projection
(arity plus one for the id). Rows of one tag are homogeneous, so a
where-pattern for a tag has exactly that tag's arity and no padding. Rows
from many trees union into one relation without collision, because ids are
addresses. Identical subtrees collapse to one row; an occurrence count is
a count over parent slots or over the occurrence relation below, not over
rows. The collapse is an identity use and is blocked by §4.2 until the
encoding is fixed; until then the projection may be computed only with the
occurrence relation beside it, and no row may be dropped as a duplicate.

The §2.1 example projects to:

```clojure
#{[A :lambda [x] B]
  [B :application C [D E] true]
  [C :variable +]
  [D :variable x]
  [E :literal 1]}          ; A..E are :segment/sha256-… addresses
```

A second projection, `occurrences : tree → #{[root-address path
node-address]}`, one row per place, is the structural half of the join
between flat rows and the occurrence-keyed side tables of §2.5. It is a
pure function of the tree, so it carries no origin; a query fixes the
origin from the composition's side (which batch, which event) and joins
on `[root-address path]` within that origin. It is computed by the same
walk that computes `project`.

### 6.2 Segment rows

`project-segment : vector → [[pc tag & ops] …]`, the instruction vector with
pc prepended. This relation is per segment: pcs collide across segments,
and source scope belongs to the interpreter, never to a tuple slot
(`datom.world.md:34`). A query over several segments takes them as
separate sources (`:in $ $2 …`) or uses the **segment-qualified form**
`[segment-address pc tag & ops]`, which adds one to every tag's arity
uniformly and keeps per-tag arity fixed. Bare-pc rows from different
segments are never unioned into one relation.

### 6.3 `q` over both, and the mechanics it rests on

`q` accepts these relations through `query/relation`
(`src/cljc/dao/space/query.cljc:153-158`), whose contents are an arbitrary
mixed-dimensional tuple collection. A pattern clause is exact-arity
positional unification: `eval-pattern-clause` (`query.cljc:894-907`) parses
the pattern (`query.cljc:419-436`), and on the general path unifies every
row through `unify-slots` (`query.cljc:799-809`), which first requires
`tuple-shape-matches?` (`query.cljc:439-441`) and then unifies slot by
slot. A final `& ?tail` binds the remaining slots as a vector and `& _`
ignores them (`query.cljc:431-436`, `:807-808`). The 3-slot EAV fast path
(`query.cljc:899`) engages only when the source carries a fact index, which
`fact-relation` (`query.cljc:172-177`) and the datom views supply; the code
relations never do, so every code pattern takes the general path by
construction.

Verified shapes, each of which is a rule a query author may rely on:

- **Per-tag selection.** `[?id :variable ?name]` matches only arity-3 rows.
- **Joins across arities.** `[?site :application ?op _ _] [?op :variable
  ?f]` joins an arity-5 row to an arity-3 row on the address.
- **Operand membership** goes through a predicate: `[(member? ?operands
  ?x)]` with `member?` supplied under `:fns`; predicate arguments must
  already be bound.
- **Ledger as-of** (§8.6) and **ref-to-code joins** (§6.4) run in one
  query, the ref pattern on the fast path and the code pattern on the
  general path.

Two gotchas are rules:

- `match` (`query.cljc:520-528`) is constant-or-wildcard selection:
  `slots-match?` (`query.cljc:444-449`) treats only `_`, nil, and the
  internal free marker as wildcards (`query.cljc:409-411`), so a `?var`
  written in a `match` pattern is compared as a constant and binds nothing.
  Use `q` to bind.
- `:fns` keys are quoted symbols: `eval-fn-clause` looks the clause head up
  as a symbol (`query.cljc:1099`), and the builtins merge under the same
  keys (`query.cljc:1436-1438`). `{:fns {'member? f}}`, as
  `test/dao/space/stigmergy_test.clj:228` writes it.

### 6.4 Refs joined by address

A ref is a datom whose `v` is an address (§8). A name is an entity carrying
`:yin/name`; joining refs to code is a join on the address value:

```clojure
;; every call site, in the tree currently named `my.ns/f`, whose operator is `+`
[:find ?root ?path
 :in $refs $code $occ
 :where [$refs ?e :yin/name my.ns/f]          ; bind the name entity
        [$refs ?e :yin/code ?root]            ; 3-slot, fast path, current view
        [$code ?site :application ?op _ _]    ; general path
        [$code ?op :variable +]
        [$occ ?root ?path ?site]]             ; every place that content occurs in this tree
```

`$refs` is a `current` view (§8.6), so the name resolves to its current
address. The result names places, not contents: the occurrence relation
turns one shared site row into one result per place.

### 6.5 What goes to the datom projection instead

A tree also projects to datoms, `[e a v t m]` with tempids minted in
preorder and one extra fact `[e :yin/address addr]` per node so the
projection joins back to refs. This is the successor of `ast->datoms`
(`v2.cljc:372-482`) and is the *projection path* of §7.1. It exists for
three queries the flat rows deliberately do not serve:

+----------------------------------------------------------------------+-------------------------------------------------------------------------------------------------+
| Query                                                                | Why the datom projection                                                                        |
+======================================================================+=================================================================================================+
| attribute-dimension queries with the attribute unbound, `[?e ?a ?v]` | a flat row has no attribute slot; "every fact about this node" is an EAV question               |
+----------------------------------------------------------------------+-------------------------------------------------------------------------------------------------+
| `pull` and `entity-attrs` navigation (`query.cljc:715`, `:1481`)     | they navigate entity → attribute → value and reverse attrs                                      |
+----------------------------------------------------------------------+-------------------------------------------------------------------------------------------------+
| scale indexing under covered indexes (`dao.space.index`)             | the covered-index publisher and `open-published!` are EAVT/AEVT/AVET/VAET over datoms           |
|                                                                      | (`query.cljc:452-476`)                                                                          |
+----------------------------------------------------------------------+-------------------------------------------------------------------------------------------------+

The datom projection never becomes an identity: its `e` values are local,
and the only durable name in it is the `:yin/address` value.

---

## §7 The VM around it

### 7.1 Load paths

Direct tuple loading is primary. A batch on the program stream is one
canonical tree (AST medium) or one canonical instruction vector (code
medium), and the evaluator's loader takes it as is:

+----------------------+--------------------+----------------------------------------------------------------------------------------------------------------------------+
| Evaluator            | Loader input       | What loading does                                                                                                          |
+======================+====================+============================================================================================================================+
| walker               | tree               | validate (§7.4), set `:program` and `:control` to the tree; no conversion, the tuple is the node                           |
+----------------------+--------------------+----------------------------------------------------------------------------------------------------------------------------+
| semantic             | instruction vector | validate (§7.5), decode positional operands into the image (the `case` of `semantic.cljc:563-593` reading `(nth tuple i)`  |
|                      |                    | instead of attribute maps), store under `:code` with the address as the alias column UCF §7.3.4 requires                   |
+----------------------+--------------------+----------------------------------------------------------------------------------------------------------------------------+
| semantic, AST medium | tree               | `lower` (§5.2) then the row above; composed by the composition exactly as `linearize/ast-loader` is today                  |
|                      |                    | (`linearize.cljc:269-286`, chosen in `src/cljc/yin/repl/v2/core.cljc:63-68`)                                               |
+----------------------+--------------------+----------------------------------------------------------------------------------------------------------------------------+

Datom-batch loading is the projection path: a batch of `:yin/*` datoms is
decoded to a tree by the successor of `datoms->ast` (`v2.cljc:494-559`)
and then loaded as above; a batch of `:yin.code/*` datoms is decoded to a
vector by UCF §7.3.4's projection rule and then loaded as above. The
projection path exists for media that carry datoms and for the datom
projection of §6.5; it is not a second loader and it runs the same
validator (§7.2).

### 7.2 Conformance: both paths, one validator, one image

**This section supersedes the sentence in UCF §7.3.4 that names the
projection path as the reference path.** Under the owner's ruling the
direct path is primary and the reference; the projection path is derived
from it. Everything else in §7.3.4 stands.

Both paths invoke the same validator (§7.4 for trees, §7.5 for vectors)
before anything is registered, and the conformance obligation has three
parts:

1. **Valid corpus, same result.** For every corpus program, loading the
   tree directly and loading its datom projection yield the same walker
   `:program` value; loading the instruction vector directly and loading
   its datom projection yield the same image. Keyword store keys
   (`parity_test.cljc:99`) and numeric store keys are part of the corpus
   and must round-trip on both paths, so the `key` kind is exercised as
   the `data` domain it is (§2.2), not as the two types the corpus
   happened to use.
2. **Malformed input, same refusal.** For a published set of malformed
   vectors, each violating one rule of §7.5, and malformed trees, each
   violating one rule of §7.4, both paths refuse with the same defect
   (rule and pc, or rule and path). A correctly hashed `[[:jump 9]]` is in
   the set. Image equality on valid input does not establish this; the
   malformed set does.
3. **Several segments loaded.** Loading two or more segments through each
   path yields the same `:code` map and the same address alias column, and
   a second segment claiming a live local id fails identically on both.

The obligation fails exactly when the tuple grammar and the datom schema
have drifted apart, which is the property that makes it the VM's own test
of this design.

### 7.3 Validation is the tuple grammar

A tuple loader validates before it decodes, and validation is the §2 table
plus the structural rules a segment needs, nothing else. Both checkers
mirror `code/well-formed?` (`code.cljc:155-180`): each returns nil or the
first defect, rules run in order, and each rule may assume the earlier ones
held.

### 7.4 Tree rules

Defects name a path (§2.5).

+---------------+--------------------------------------------------------------------------------------------------------------------------------------------------------+
| Rule          | Defect when                                                                                                                                            |
+===============+========================================================================================================================================================+
| `:tag`        | the first element is not a tag of §2.3                                                                                                                 |
+---------------+--------------------------------------------------------------------------------------------------------------------------------------------------------+
| `:arity`      | the count differs from the tag's arity                                                                                                                 |
+---------------+--------------------------------------------------------------------------------------------------------------------------------------------------------+
| `:slot-kind`  | a slot's value is not of the slot's kind: a `node` slot that is not a vector with a tag, a `nodes` slot that is not a vector of such, a `data` or      |
|               | `key` slot failing `plain-data?`, a `bool` slot that is not `true`/`false`, a `syms` slot with a non-symbol                                            |
+---------------+--------------------------------------------------------------------------------------------------------------------------------------------------------+
| `:saturation` | a saturated slot (§2.4) is nil                                                                                                                         |
+---------------+--------------------------------------------------------------------------------------------------------------------------------------------------------+

There is no encoder-domain rule: §4.2 blocks identity uses outright rather
than admitting a domain.

### 7.5 Vector rules

Defects name a pc. Rules 1–4 of `yin.vm.semantic.md` §2.6 (one segment,
dense pcs, sorted by pc, refs resolve to entities) are satisfied by the
vector form by construction and have no rule here; rules 5 and 6 and the
UCF §7.3.4 checks are translated as follows.

+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+
| Rule             | Defect when                                                                                                                                         |
+==================+=====================================================================================================================================================+
| `:nonempty`      | the vector has no instructions                                                                                                                      |
+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+
| `:mnemonic`      | the first element is not a mnemonic of `code/mnemonics` (`code.cljc:12-16`)                                                                         |
+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+
| `:arity`         | the tuple's count differs from the mnemonic's arity in `yin.vm.semantic.md` §2.4 as saturated by UCF §7.3.2                                         |
+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+
| `:operand-kind`  | an operand is not of its kind: `:const` and `:store-put` values or `:store-get`/`:store-put` keys fail `plain-data?` (the `key` kind of §2.2), a    |
|                  | `:var` name is not a symbol, a `:closure` params is not a vector of symbols, a `:ffi-call` op or `:resume` parked id is not a keyword, a `:gensym`  |
|                  | prefix is not a string                                                                                                                              |
+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+
| `:saturation`    | a saturated operand (`:gensym` prefix, `:stream-make` buffer, `:call` tail?, `:ffi-call` argc) is nil                                               |
+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+
| `:target-bounds` | a `:jump`/`:branch-false` target or a `:closure` body is not an integer in `[0, length)`                                                            |
+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+
| `:terminator`    | the last instruction is not in `code/terminators` (`code.cljc:19-21`)                                                                               |
+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+
| `:argc`          | a `:call`/`:ffi-call` argc is not a non-negative integer                                                                                            |
+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+

The address check (the vector hashes to the address it claims) is UCF
§7.3.4's and runs before these rules whenever an address is claimed; a
correct hash is never structural validation. The failure outcome of both
checkers on the UCF lowering path is `:yin.k/undecodable` naming the pc or
path, as §7.3.4 already specifies; on a local load it is a load error
naming the same.

### 7.6 The hot loop never queries

The walker dispatches on `(nth node 0)` and reads slots by position; the
semantic VM runs `(aget code pc)` over an image decoded once at load
(`yin.vm.semantic.md` §3.1). Neither touches a relation, a ref, an address
index, or a projection while stepping. Projection and query are load-time
and tooling-time operations. This is the rule that keeps
`yin.vm.semantic.md` §6's cost model intact under this design: queryability
is paid once at load, or by whoever asks, never per step.

### 7.7 Dependency closure is Datalog over the tuples

UCF §7.6.1 computes `:yin.k/requires` by a conservative fixed point over
values, code, names, and modules. The **code part** of that fixed point is
the queries below, run over the flat projection of every reachable tree
and the segment-qualified rows (§6.2) of every reachable segment. The
value, name-satisfaction, and module parts remain the walk §7.6.1
specifies; these queries supply their inputs and do not replace them.

Extraction over trees (`$ast` is the union of flat rows of the reachable
trees):

```clojure
;; names, every one, not only free ones (UCF §7.6.1, Names)
[:find ?name :in $ast :where [$ast _ :variable ?name]]

;; store keys read or written by code
[:find ?key :in $ast :where (or [$ast _ :vm/store-get ?key]
                                [$ast _ :vm/store-put ?key _])]

;; FFI ops
[:find ?op :in $ast :where [$ast _ :dao.stream.apply/call ?op _]]

;; parked ids named by resume
[:find ?pid :in $ast :where [$ast _ :vm/resume ?pid _]]

;; syntax that raises an effect: the tag, to be normalized by the footprint table below
[:find ?tag :in $ast
 :where [$ast _ ?tag & _]
        [(contains? #{:stream/make :stream/put :stream/cursor :stream/next
                      :stream/close :dao.stream.apply/call} ?tag)]]
```

Extraction over segments (`$code` is the union of segment-qualified rows):

```clojure
[:find ?name :in $code :where [$code _ _ :var ?name]]
[:find ?key  :in $code :where (or [$code _ _ :store-get ?key]
                                  [$code _ _ :store-put ?key _])]
[:find ?op   :in $code :where [$code _ _ :ffi-call ?op _]]
[:find ?pid  :in $code :where [$code _ _ :resume ?pid]]
[:find ?mn   :in $code
 :where [$code _ _ ?mn & _]
        [(contains? #{:stream-make :stream-put :stream-cursor :stream-next
                      :stream-close :ffi-call} ?mn)]]
```

Store keys are extracted as values of the `key` kind (§2.2), whatever
their type, and are compared with the store slice's keys by value.

#### 7.7.1 Effect normalization: the footprint table

Syntax names effects by tag, segments by mnemonic, and profiles and
`:yin.k/requires` by **effect identifier** (`:stream/put`, UCF §7.6.1's
`:yin.k/effects`). The reference machine performs the mnemonic-to-effect
mapping in its hot loop (`semantic.cljc:360-403`: `:stream-make` raises
`{:effect :stream/make}`, `:stream-put` raises `{:effect :stream/put}`, and
so on; `:ffi-call` goes through the FFI pair, `semantic.cljc:405-420`).
That mapping is lifted out of the machine into a **footprint table**,
versioned with the execution contract stamp (UCF §7.3.3) because the
effect a mnemonic raises is part of that contract. Extraction applies the
table **before** any union with callable-profile effects, so the union is
over one vocabulary. The table for contract `"v2"`:

+---------------+------------------------+--------------------+----------------------------------------------------------------------------------------------------------+
| Syntax tag    | Mnemonic               | Effect identifiers | Other requirement contributed                                                                            |
|               |                        | contributed        |                                                                                                          |
+===============+========================+====================+==========================================================================================================+
| `:stream/make | `:stream-make`         | `#{:stream/make}`  | —                                                                                                        |
| `             |                        |                    |                                                                                                          |
+---------------+------------------------+--------------------+----------------------------------------------------------------------------------------------------------+
| `:stream/put` | `:stream-put`          | `#{:stream/put}`   | —                                                                                                        |
+---------------+------------------------+--------------------+----------------------------------------------------------------------------------------------------------+
| `:stream/curs | `:stream-cursor`       | `#{:stream/cursor} | —                                                                                                        |
| or`           |                        | `                  |                                                                                                          |
+---------------+------------------------+--------------------+----------------------------------------------------------------------------------------------------------+
| `:stream/next | `:stream-next`         | `#{:stream/next}`  | —                                                                                                        |
| `             |                        |                    |                                                                                                          |
+---------------+------------------------+--------------------+----------------------------------------------------------------------------------------------------------+
| `:stream/clos | `:stream-close`        | `#{:stream/close}` | —                                                                                                        |
| e`            |                        |                    |                                                                                                          |
+---------------+------------------------+--------------------+----------------------------------------------------------------------------------------------------------+
| `:dao.stream. | `:ffi-call op`         | `#{}`: an FFI call | `op` into `:yin.k/ffi-ops`, which is a **receiver capability requirement**: the resumer must hold its    |
| apply/call    |                        | is not an effect   | own FFI pair (`ffi/require-call-pair!` is checked before every call, `semantic.cljc:405-415`) and a      |
| op`           |                        | kind               | bridge handler for `op`. The pair's store keys (`v2.cljc:135-147`) are **never** a store-slice           |
|               |                        |                    | requirement: UCF §7.6.2 excludes the pair from the slice and the resumer installs its own; outstanding   |
|               |                        |                    | calls route per UCF §7.4.3                                                                               |
+---------------+------------------------+--------------------+----------------------------------------------------------------------------------------------------------+
| `:vm/park`    | `:park`                | `#{}`:             | —                                                                                                        |
|               |                        | scheduler-internal |                                                                                                          |
+---------------+------------------------+--------------------+----------------------------------------------------------------------------------------------------------+
| `:vm/resume   | `:resume pid`          | `#{}`              | `pid` into the parked-record obligation                                                                  |
| pid`          |                        |                    |                                                                                                          |
+---------------+------------------------+--------------------+----------------------------------------------------------------------------------------------------------+
| `:vm/gensym`  | `:gensym`              | `#{}`: the id      | —                                                                                                        |
|               |                        | counter is machine |                                                                                                          |
|               |                        | state              |                                                                                                          |
+---------------+------------------------+--------------------+----------------------------------------------------------------------------------------------------------+
| `:vm/store-ge | `:store-get k`,        | `#{}`              | `k` into the store-slice requirement                                                                     |
| t k`,         | `:store-put k`         |                    |                                                                                                          |
| `:vm/store-pu |                        |                    |                                                                                                          |
| t k`          |                        |                    |                                                                                                          |
+---------------+------------------------+--------------------+----------------------------------------------------------------------------------------------------------+
| `:vm/current- | `:current-continuation | `#{}`              | —                                                                                                        |
| continuation` | `                      |                    |                                                                                                          |
+---------------+------------------------+--------------------+----------------------------------------------------------------------------------------------------------+
| `:literal`,   | `:const`, `:var`,      | `#{}` from syntax; | `:var` names into the name obligations                                                                   |
| `:variable`,  | `:closure`, `:push`,   | a call's effects   |                                                                                                          |
| `:lambda`,    | `:call`, `:return`,    | are its callee's   |                                                                                                          |
| `:application | `:jump`,               | profile effects    |                                                                                                          |
| `, `:if`      | `:branch-false`,       | (§7.7.2)           |                                                                                                          |
|               | `:halt`                |                    |                                                                                                          |
+---------------+------------------------+--------------------+----------------------------------------------------------------------------------------------------------+

Every tag and every mnemonic has a row, so "no external effect" is an
explicit `#{}`, never an absence. The conformance obligation: for every
corpus tree, the requirement set computed from the tree and the one
computed from its lowered segment are **equal** in every field of
`:yin.k/requires`. A tag or mnemonic outside the table is
`:yin.k/undecodable`, the same outcome the validators give it.

#### 7.7.2 Name obligations are per context, and completion is conservative

A name extracted from code is an **obligation** of every activation that
can execute that code, and an obligation is discharged only by a binding
that activation would actually see under `resolve-var`'s precedence
(`engine.cljc:46-65`). Resolution is activation-specific: the reference
resolver returns the primitive for `x` under an empty environment and the
environment's binding under one that binds `x`. So a binding discharges
an obligation only in one of these contexts:

+-------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+
| Obligation from                                                         | Discharged by                                                                                |
+=========================================================================+==============================================================================================+
| a `:variable` inside a `:lambda` body naming one of that lambda's       | the parameter binding, **only when call analysis establishes that the binding exists**       |
| params, or an enclosing lambda's params on the structural path          | (below); otherwise the name is an ordinary obligation of the closure's context               |
+-------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+
| code the active control points into                                     | the active frame's environment, or the store slice by key                                    |
+-------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+
| a closure's body (the segment its `:entry` points into)                 | that closure's captured `:env`, or the store slice                                           |
+-------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+
| code a K frame, a parked record, or a wait entry resumes into           | that frame's or record's own `:env`, or the store slice                                      |
+-------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+
| code reachable only by address, with no activation associated           | nothing: the obligation is retained                                                          |
+-------------------------------------------------------------------------+----------------------------------------------------------------------------------------------+

**A parameter is bound only when an argument was supplied for it.** The
reference machine binds parameters with `(zipmap params args)` and checks
no arity (`semantic.cljc:199-205`; the walker does the same,
`ast_walker.cljc:190-191`), so an under-arity call leaves the omitted
parameters unbound and a `:variable` naming one resolves through the
captured environment, the store, the primitives, and the modules
(`engine.cljc:46-65`). A parameter obligation at index `i` is therefore
discharged for a lambda only when **every** call site of that lambda is
known and every one supplies at least `i + 1` arguments; call sites are
known only when the closure does not escape the analyzed code, that is,
when every `:application` whose operator can evaluate to it is in the
analyzed trees and the closure value does not appear in any carried
environment, store slice, stack, or parked record. Otherwise the name
stays an obligation of the closure's context: it is discharged by the
captured `:env` or the store slice, and if neither binds it, it is
retained as a primitive or module requirement. An unknown call context
retains or yields `:incomplete`; it never discharges. This design does
not introduce an exact-arity rule, which would be an execution-contract
amendment outside its scope.

**An unrelated carried binding never discharges an obligation.** A saved
continuation that binds `x` says nothing about the active activation's
`x`, which may resolve to a primitive with a profile the value needs. Every
retained obligation is a required primitive or module export, checked by
profile (UCF §7.5.2), and the **effects of a callable are read from its
profile** (`:yin.k/effects`), never inferred from the name: `yin/def`
contributes `:vm/store-put` and `require` contributes `:module/require`
because their profiles say so. Where the analysis cannot associate an
occurrence with a context, or a retained name has no profile at the
emitter, discovery is `:incomplete`; conservatively retaining every
possible primitive and module requirement is always admissible and never
makes a `:complete` result wrong.

This rule is the amendment UCF §7.6.1's *Names* paragraph needs: its
sentence that a name "is satisfied if it is bound in any environment the
value carries" is the unsound test, and this document states the
per-context rule in its place; UCF must be amended to say the same
(§10.8).

#### 7.7.3 The fixed point converges over work items, not addresses

The unit of analysis is a **work item** `[code-address context]`, not an
address. Two closures sharing one segment address with different captured
environments are two work items, because each enters the segment with its
own environment (`semantic.cljc:199-205`) and so carries different
obligations. The **context** of a work item is a finite abstraction: the
set of names bound in the activation's environment (captured `:env` keys
plus parameters established under §7.7.2's call analysis), and nothing
else. Names come from finite code and finite carried values, so the set
of contexts is finite and the iteration terminates.

- **Code once, contexts each.** Code is fetched, validated, and projected
  once per address; every newly discovered `[address context]` pair is
  analyzed, whether or not the address was seen before.
- **Parked ids.** Each extracted parked id must name a record in the
  carried `:parked` slice (UCF §7.6.3); a missing one is `:yin.k/unsatisfied`
  naming the id.
- **Values.** Closures, streams, and cursors reachable from the frame, the
  K frames, parked records, the store slice, and the pending wait
  contribute work items and stream identities as §7.6.1 specifies; a
  closure contributes `[its segment address, its captured-env context]`.
- **Modules.** A required module with an undeclared footprint sets
  `:yin.k/discovery :incomplete`, as §7.6.1 already rules.

**Convergence** is reached when one full pass adds nothing to any of:
the work-item set, the obligation set (name × context), the discovered
value set (closures, streams, cursors, parked records), the store-key
requirement, the FFI-op requirement, the effect set after normalization
(§7.7.1), and the callable and module footprints. A pass that adds a new
context, a new profile fact, or a new footprint continues the iteration
even when no new address appeared. If an implementation cannot compute
the bound-name set for some context, it reports `:incomplete` rather than
terminating on address stability.

Discovery is `:complete` only when every reachable work item was
analyzed, every obligation was discharged in its own context or retained
as a profiled requirement, every parked id resolved, and every module
footprint was declared. It is `:blocked` when an address cannot be
fetched and `:incomplete` otherwise. Recasting the walk as Datalog changes
where the facts come from, not the conservatism of the closure.

UCF §7.6.1 has the same defect in its own terms: "repeats until nothing
new is reachable" is stated over segments and values, and its walk of "a
newly fetched segment" is per address. It needs this same amendment
(§10.8).

Because the queries are over data, they answer for a tree that has never
run, which is the property §7.6.1 names.

---

## §8 The ledger and refs

### 8.1 Two notations, one relation

The ledger is one logical relation with rows `[t op address ref]`, ruling
6's shape: `t` is the ledger's clock, `op` is drawn from an open
vocabulary, `address` is a content address, and `ref` is the row's other
argument, a name entity or a second address. This document writes ledger
facts in that notation when it speaks of the relation.

The **persistent representation** of a row is datoms, and a datom is bound
by `datom.md`'s contract: `e`, `t`, and `m` are stream-local, `e` is a
non-negative integer, `m` is an integer, and `v` is the only slot that may
hold a foreign reference (`datom.md:140-180`). The transactor enforces
this: `local-datom?` (`src/cljc/dao/datom.cljc:42-58`) rejects a
non-integer `e` or `m`, and `pad-datom`
(`src/cljc/dao/space/transactor.cljc:74-90`) rejects a caller-supplied `t`
and stamps its own. Therefore a content address never appears in `e`, an
op keyword never appears in `m`, and `t` is never chosen by the writer.
The mapping between notations is:

+--------------+---------------------------------------------------------------------------------------------------------------------------------------------------------+
| Logical slot | Persistent carrier                                                                                                                                      |
+==============+=========================================================================================================================================================+
| `t`          | the datom `t` the transactor stamped                                                                                                                    |
+--------------+---------------------------------------------------------------------------------------------------------------------------------------------------------+
| `op`         | for naming rows: the reserved validity marker in `m` (`:db/assert` 1, `:db/retract` 0); for every other op: the `:yin.ledger/op` value of a **ledger    |
|              | event entity** (§8.2)                                                                                                                                   |
+--------------+---------------------------------------------------------------------------------------------------------------------------------------------------------+
| `address`    | a `v` slot                                                                                                                                              |
+--------------+---------------------------------------------------------------------------------------------------------------------------------------------------------+
| `ref`        | for naming rows: the local name entity in `e`; for address-to-address rows: a second `v` slot of the same event entity                                  |
+--------------+---------------------------------------------------------------------------------------------------------------------------------------------------------+

Anything written in the `[t op address ref]` notation below is a logical
row; anything written as `[e a v t m]` is transactor input, with `e` a
local integer or tempid, `t` nil at write time, and `m` a reserved marker.

### 8.2 Ledger records and ledger events

Every non-naming ledger fact is first a **ledger record**: a plain map,
content-addressed by `segment-key`, stored in `dao.jing` like any other content.
Record shapes:

+------------+-------------------------------------------------------+---------------------------------------------------------------------------------------------------+
| Op         | Record                                                | Meaning                                                                                           |
+============+=======================================================+===================================================================================================+
| `:derive`  | `{:yin.ledger/op :derive :yin.ledger/input tree-addr  | `output` was computed from `input` by `function` under the lowering profile (§5.2.1)              |
|            | :yin.ledger/output seg-addr :yin.ledger/function      |                                                                                                   |
|            | fn-name :yin.ledger/profile profile-map}`             |                                                                                                   |
+------------+-------------------------------------------------------+---------------------------------------------------------------------------------------------------+
| `:expand`  | `{:yin.ledger/op :expand :yin.ledger/input tree-in    | one expansion attempt; exactly one of `:yin.ledger/origin` (initial) and `:yin.ledger/parent`     |
|            | :yin.ledger/call-root tree-in :yin.ledger/call-path   | (nested) is present; failures carry `:yin.ledger/error` instead of `:yin.ledger/output` (§8.4).   |
|            | path :yin.ledger/origin [:source medium batch j]`     | `:yin.ledger/attempt` is the portable attempt identity of §8.4.1, scoped to the expander          |
|            | **or** `:yin.ledger/parent parent-record-addr`        | incarnation, so two expanders or two executions of one expander observing one call never mint one |
|            | `:yin.ledger/output tree-out :yin.ledger/macro        | record. The record holds no log-local id: a nested attempt names its parent by the parent's       |
|            | lambda-addr :yin.ledger/attempt [incarnation          | record **address**, so the record is minted only after the parent's record exists                 |
|            | counter]}`                                            |                                                                                                   |
+------------+-------------------------------------------------------+---------------------------------------------------------------------------------------------------+
| `:publish` | `{:yin.ledger/op :publish :yin.ledger/subject         | a checkpoint occurrence was published as the UCF value at `output` (UCF §7.3.4)                   |
|            | occurrence-id :yin.ledger/output ucf-addr}`           |                                                                                                   |
+------------+-------------------------------------------------------+---------------------------------------------------------------------------------------------------+

A record is a content-addressed value, so it is immutable, addressable, and verifiable,
and it is what "the hash chain" is made of: a record names its input and
output by address, and the record's own address is what the ledger
publishes. Walking the chain is following record addresses; code content
never has to know its predecessor.

A record enters the ledger as a **ledger event entity**, a local entity
(id ≥ `dao.datom/first-user-id`, `datom.cljc:27-40`) with the record's
fields as attributes and the record's address as one more:

```clojure
[[:db/add ev :yin.ledger/op       :derive]
 [:db/add ev :yin.ledger/input    tree-addr]
 [:db/add ev :yin.ledger/output   seg-addr]
 [:db/add ev :yin.ledger/function :yin.vm.v2/lower]
 [:db/add ev :yin.ledger/profile  {:yin.lower/profile "ast-v1" :yin.code/contract "v2" :yin.k/version 0}]
 [:db/add ev :yin.ledger/record   record-addr]]     ; ev is a tempid; m defaults to :db/assert
```

The profile is carried as the structured map, exactly as it appears in the
record. `local-datom?` constrains `e`, `a`, `t`, and `m` and places no
restriction on `v` (`datom.cljc:42-58`), and the existing schema already
carries a vector in `v` (`:yin/params`, `v2.cljc:302`); no string
rendering of the profile is defined.

Event operation and fact validity are separate by construction. The op is
a namespaced keyword in a `v` slot, globally meaningful without a
reserved id, which is `datom.md`'s own rule for built-ins that live in `a`
and `v` (`datom.md:214-217`); the datoms of the event carry the ordinary
`:db/assert` marker and fold as ordinary facts. No reserved `m` id is
allocated, because reserved ids have one global meaning everywhere
(`datom.md:200-212`) and a composition cannot mint one. Where a
composition wants an event to be the provenance of a naming fact, the
naming datom's `m` names the event entity through the established
metadata-entity mechanism, an id ≥ 16 carrying `:db/op` and provenance
(`datom.cljc:2-7`, `datom.md:172-180`; the transactor's map-`m` input at
`src/cljc/dao/space/transact.cljc:15-29`). A fact whose `m` names a
metadata entity is live under the current fold, which removes only
`:db/retract` markers (`query.cljc:76-103`); a validity op other than
assert or retract is expressed by the reserved marker, never by the
event, so the fold needs no knowledge of the open vocabulary. Interpreting
a metadata entity's `:db/op` for Datomic-visible validity is the
prerequisite `dao.space.schema.datomic.md` §4 names and is outside this
design.

The vocabulary is open: a composition that needs a new op declares a new
keyword and a record shape. A reader dispatches on the ops it understands
(`[?ev :yin.ledger/op :derive]`) and does not see the others, which is a
property of the query it writes, not of the fold.

### 8.3 Rows of the one relation

+-----------------------------------------------+------------------------------------------------------------------------------------------------------------------------+
| Logical row                                   | Persistent form                                                                                                        |
+===============================================+========================================================================================================================+
| `[t :assert tree-addr name]` — a name refers  | `[:db/add name :yin/code tree-addr]`                                                                                   |
| to a tree                                     |                                                                                                                        |
+-----------------------------------------------+------------------------------------------------------------------------------------------------------------------------+
| `[t :retract tree-addr name]` — the name no   | `[:db/retract name :yin/code tree-addr]`, followed by the new assertion                                                |
| longer does                                   |                                                                                                                        |
+-----------------------------------------------+------------------------------------------------------------------------------------------------------------------------+
| `[t :derive seg-addr tree-addr]` — a segment  | a `:derive` event entity (§8.2); direction: `input` is the tree, `output` is the segment                               |
| was lowered from a tree                       |                                                                                                                        |
+-----------------------------------------------+------------------------------------------------------------------------------------------------------------------------+
| `[t :assert seg-addr k-occurrence]` — a       | `[:db/add occ :yin.k/segment seg-addr]` on the local occurrence entity                                                 |
| segment is the address a continuation names   |                                                                                                                        |
+-----------------------------------------------+------------------------------------------------------------------------------------------------------------------------+
| `[t :expand tree-out tree-in]` — a tree is    | an `:expand` event entity (§8.4)                                                                                       |
| the expansion of another                      |                                                                                                                        |
+-----------------------------------------------+------------------------------------------------------------------------------------------------------------------------+
| `[t :assert lambda-addr name]` under          | not a ledger row at all: macro-ness is an occurrence-bound declaration beside the tree (§8.5); the ledger records only |
| `:yin.macro/definition`                       | the expander's harvest result, `[:db/add name :yin.macro/definition lambda-addr]`, for queries                         |
+-----------------------------------------------+------------------------------------------------------------------------------------------------------------------------+
| `[t :publish ucf-addr occurrence]` — a        | a `:publish` event entity; custody stays `dao.lease.md`'s vocabulary unchanged (UCF §7.7.2), keyed by the same local   |
| checkpoint occurrence was published           | occurrence entity                                                                                                      |
+-----------------------------------------------+------------------------------------------------------------------------------------------------------------------------+
| `[t :assert tree-addr manifest]` — a module   | `[:db/add manifest :yin.module/exports tree-addr]`                                                                     |
| manifest pins a tree                          |                                                                                                                        |
+-----------------------------------------------+------------------------------------------------------------------------------------------------------------------------+

`:yin.code/derived-from` therefore keeps its name as a query alias for
"the `:yin.ledger/input` of a `:derive` event whose output is this
address" and is no longer an attribute of the segment (`v2.cljc:327`,
`linearize.cljc:212`).

### 8.4 Expansion events keep their identity

`yin.vm.macro.md` §4.1 records one event per attempt, successful or not,
including repeated identical expansions, and links the event to its output
(`yin.vm.macro.md:666-709`). This design keeps that contract: the
`:expand` event entity **is** that event, one per attempt, and the address
rows are attributes of it, not a replacement for it:

```clojure
[[:db/add ev :yin.ledger/op         :expand]
 [:db/add ev :yin.ledger/input      tree-in-addr]        ; the tree containing the call
 [:db/add ev :yin.ledger/call-root  tree-in-addr]        ; the call occurrence, as values:
 [:db/add ev :yin.ledger/call-path  path]                ;   root address and structural path
 [:db/add ev :yin.ledger/origin     [:source medium batch j]]   ; INITIAL expansion only: a value
 [:db/add ev :yin.ledger/parent-event parent-ev]         ; NESTED expansion only: a declared ref, log-local
 [:db/add ev :yin.ledger/parent     parent-record-addr]  ; NESTED expansion only: the parent's record address, portable
 [:db/add ev :yin.ledger/output     tree-out-addr]       ; on success
 [:db/add ev :yin.ledger/macro      lambda-addr]         ; the macro's content
 [:db/add ev :yin.ledger/attempt    [incarnation n]]     ; portable attempt identity, §8.4.1
 [:db/add ev :yin.ledger/record     record-addr]         ; the addressed record of §8.2
 [:db/add ev :yin/source-batch      batch-id]            ; unchanged from §4.1
 [:db/add ev :yin/error             {...}]               ; on failure, unchanged
 [:db/add ev :yin/timestamp         ctx-t]]              ; unchanged
```

The split between local references and portable values follows
`yin.vm.macro.md` §4.1 exactly (`yin.vm.macro.md:692-708`), and it is
enforced by how the transactor resolves ids: `apply-tempid-map` replaces a
tempid in `v` **only when the whole value is a tempid on a declared
reference attribute** (`src/cljc/dao/space/transact.cljc:168-185`); a
tempid nested inside a vector is left as it is. Therefore:

- an **initial** expansion's call sits in admitted syntax, so it carries
  `:yin.ledger/origin`, a value `[:source medium batch j]` including the
  batch member index: the successor of `:yin/source-call` qualified by
  `:yin/source-batch`, which no transactor resolves, as today;
- a **nested** expansion's call sits in the output of the enclosing
  expansion, so it carries **two** links to the parent: `:yin.ledger/
  parent-event`, a declared reference attribute whose whole value is the
  parent event's id, the successor of `:yin/source-node` and resolved by
  the transactor within the log medium exactly as `:yin/source-node` is
  today; and `:yin.ledger/parent`, the parent's **record address**, which
  is a value and travels. The parent is complete before the child begins,
  because a nested expansion's input is the enclosing expansion's output
  (`yin.vm.macro.md:698-701`), so the parent's record address is known
  when the child's record is minted; no staging after allocation is
  needed, and the record (§8.2) never contains a log-local id, so it is
  hashed once, when built.

A successful event carries exactly one of `:yin.ledger/origin` and the
parent pair; an admission failure carries neither. The chain `source →
event → output → event → output` of §4.1 is walked locally through
`:yin.ledger/parent-event` and portably through `:yin.ledger/parent`, and
two attempts that produced identical output trees are two events with two
ids and two record addresses, because their `:yin.ledger/attempt` values
differ by construction (§8.4.1), so a child names its parent without
ambiguity in either walk. `:yin/expansion-root` and `:yin/macro` of §4.1 are carried by
`:yin.ledger/output` and `:yin.ledger/macro` as addresses; the other event
attributes are unchanged. Because the *content* values are addresses, the
cross-media identity problem of `yin.vm.macro.md` §4.2 does not arise for
content: the same tree has the same address on `program-in`,
`program-out`, and the log. The local event id remains log-local, as
`:yin/source-node` is today; the record address is what leaves the log.

#### 8.4.1 Attempt identity is scoped to an expander incarnation

A record's address is the hash of its value, so two attempts whose records
are value-equal are one record, whatever the encoder. A bare per-expander
counter does not prevent that: two expanders observing one medium, batch,
member, and call path, each at counter `0`, with one macro and one output,
would write one record; so would one expander restarted and replaying the
batch. The governing contract records every attempt separately
(`yin.vm.macro.md:668-669`), so the attempt identity must be unique per
attempt across expanders and across executions. It is:

```clojure
:yin.ledger/attempt [incarnation counter]
```

- **The incarnation** is a value held in the expander's process state
  `ctx` (`yin.vm.macro.md:216-223`), as a new field `:incarnation`,
  threaded through every call exactly as `:alloc` and `:t` are. It is
  **allocated**, never observed: a cursor read reserves nothing, since a
  cursor is a position minted by the stream for observation
  (`dao.stream.md:471-477`) and the memory log's `:dao.stream/newest`
  cursor is simply its current value count
  (`src/cljc/dao/stream/v2/memory_log.cljc:105-110`), so two constructions
  with no append between them read one position. Nor does committing a
  record on the log allocate anything: `prepare-tx` is a pure function of
  the history it is handed (`src/cljc/dao/space/transact.cljc:189-207`),
  so two preparations against one history assign one permanent id, and
  `transact!` allocates only `t` under its lock and appends datoms that
  are already resolved (`src/cljc/dao/space/transactor.cljc:231-254`);
  two transactor values over one stream are a documented single-writer
  hazard, not a coordination (`dao.space.transactor.md`, T6). **The log
  therefore plays no part in incarnation identity.** The incarnation is a
  **composition-minted token**, the one mechanism for every construction:

  ```clojure
  :incarnation {:yin.expander/token <token>}
  ```

  where `<token>` is a fresh random UUID minted by the host's library at
  the moment of construction (`random-uuid`, as the ring-buffer transport
  already mints its stream identity, `src/cljc/dao/stream/v2/ringbuffer.cljc:27`).
  The uniqueness warranty is the composition's, discharged by the token's
  122 random bits: it covers two expanders on one log, two expanders on
  two logs, and one expander constructed twice, with or without a
  restart, and it needs no state that survives a restart, which is why a
  random token and not a counter is the rule; a durable counter would
  need an allocate-and-persist boundary of its own, which is the problem
  being avoided. No id allocated by any transactor, and no position of
  any stream, enters the incarnation.
- **Allocation retry.** Construction is all-or-nothing, as it is for a VM
  (`v2.cljc:21-23`): minting the token is the first act of construction,
  and a construction that fails afterward, for any reason, leaves no
  expander. A construction that is retried mints a **new token** and
  obtains a new incarnation; it never reuses or reconstructs an earlier
  one, because an incarnation is reachable only through the `ctx` that
  holds it, and a `ctx` that was not retained is gone. A token under
  which no attempt was ever recorded is harmless.
- **The counter** is a per-incarnation attempt counter in `ctx`, advanced
  once per attempt, initial or nested, successful or failed, exactly as
  `:alloc` advances (`yin.vm.macro.md:360-365`); admission-failure events
  advance it too, since they are attempts under §4.1.
- **A staged retry reuses the identity; a distinct execution receives
  another.** The expander stages `expand-batch`'s output and log payloads
  and retries a failed flush from the staged data without re-running the
  expansion (`yin.vm.macro.md:364-365`, `:765-773`): the record, and the
  attempt identity inside it, are part of what was staged, so the retry
  writes the same record to the same address, which is the idempotent
  re-publication the ledger wants. A restart constructs a new expander,
  which mints a new incarnation, so replaying a batch after a restart
  mints new attempt identities and new records: that is a distinct
  execution and is recorded as one. What separates the two cases is
  whether `ctx` survived: a retry runs under the `ctx` that produced the
  staged payload; a restart does not have it, and allocates (above).

The parent-record chain of §8.4 is unchanged; it gains uniqueness from the
attempt identity inside every record.

### 8.5 Macro declarations travel beside the tree

The `:macro?` flag is outside the canonical tree (§2.5). What replaces it
is a **declaration fact bound to an occurrence**, produced by the frontend
and consumed by the expander at admission. The governing contract admits
and harvests **every entity in the batch, in batch order, including
definitions disconnected from the execution root**
(`yin.vm.macro.md:229-249`). A single rooted tree cannot carry a
disconnected definition, so the composition between frontend and expander
is an **ordered batch**, not a tree:

```clojure
{:yin/batch         [<tree-0> <tree-1> … <tree-n>]   ; canonical trees, one per admitted root or disconnected subgraph
 :yin/root          i                                ; index of the tree the evaluator runs
 :yin/declarations  #{[j path :yin.macro/definition] …}   ; the definition at path in tree j is a macro
 :yin/harvest       [#{[j path] …} #{[j' path'] …} …]}   ; one entry per ORIGINAL definition, in admission order;
                                                     ;   each entry is the set of [j path] occurrences of that definition
```

Rules:

- **Harvest order is an explicit catalogue, not a traversal.** The
  governing contract harvests every definition in the batch **in the
  order its entities appeared in the admitted datom batch**
  (`yin.vm.macro.md:242-249`), and it admits any acyclic, internally
  resolved batch; nothing requires entity order to equal structural
  preorder, and a root may contain definitions A then B whose entities
  arrived as B then A. Vector order plus preorder therefore does **not**
  preserve that contract, and this document does not claim it does. The
  catalogue `:yin/harvest` is the preserved order: a vector with **one
  entry per original definition**, in the original admission order, each
  entry the set of `[j path]` occurrences at which that definition
  appears in the canonical trees, connected or not. Initial harvest
  (`yin.vm.macro.md` §3.1 step 2) iterates the catalogue, last wins; it
  never iterates the trees. For a batch converted from datoms by the §9.1
  adapter the catalogue is derived from entity order, so the legacy
  contract is preserved exactly. For a batch a tuple-native
  frontend emits, the frontend chooses the order it writes; the reference
  frontends write source order, which is the **declaration order** of
  `yin.vm.macro.md` §3.2 (`yin.vm.macro.md:298-304`: operands before the
  body of an immediately applied lambda). That is a new contract for a
  new producer, not a narrowing of the legacy one, and the two are kept
  distinct: post-expansion declaration order is what the expander uses
  for definitions its own output forms (§3.1 step 5), and it stays
  separate from the admission catalogue.
- **One original definition, many occurrences, one ordinal.** A legacy
  batch may hold one definition entity reached through several
  references: the codec emits a pre-assigned entity once and lets every
  referring site name it (`v2.cljc:394-411`, `seen-eids`), and
  `yang.clojure` produces exactly this for a defmacro lambda used at its
  definition and at call sites (`clojure.cljc:418-437`). Inlining that
  graph into canonical trees produces several `[j path]` occurrences of
  one definition. The old harvest and declaration catalogue number the
  **entity**, giving it one declaration ordinal `k`, and a transformer
  may fabricate a stand-in that selects a catalogue entry by `k`
  (`yin.vm.macro.md:254-275`), so the ordinal is observable and
  duplicating it would be wrong. Therefore a catalogue entry is an
  **occurrence group**: the definition is harvested once, and the
  catalogue, not the trees, is the identity of the definition. The
  adapter derives groups from entity identity; a tuple-native frontend
  emits singleton groups, one per definition it writes, and if it writes
  one definition at several places it emits one group naming them all.
- **Two sequences: harvest order and declaration ordinals.** The
  governing contract numbers **only source macro definitions** into
  `:declared`, in harvest order, and replaces only those by stand-ins;
  plain definitions stay executable `yin/def` nodes and are forwarded
  untouched (`yin.vm.macro.md:254-278`). The harvest catalogue above
  contains every definition group, plain and macro alike, because harvest
  and redefinition need all of them in admission order. The **declaration
  catalogue** is a second, derived sequence: the macro-declared groups
  only (those whose occurrences carry `:yin.macro/definition`), in the
  relative order they have in the harvest catalogue. A macro group's
  declaration ordinal `k` is its index in the declaration catalogue, never
  its index in the harvest catalogue; every occurrence of that group is
  replaced by a stand-in carrying that same `:decl k` (§3.1 step 3), so a
  fabricated stand-in selects the same entry it would select under the
  legacy expander. A plain definition preceding macro `m` therefore leaves
  `m` at ordinal `0`, as today. Plain groups participate in harvest and
  redefinition (§3.1 step 2's last-wins over the harvest catalogue,
  including a plain redefinition removing a macro) and are **never**
  replaced by stand-ins; their occurrences remain ordinary `yin/def`
  syntax in the trees the evaluator receives.
- **The catalogue is validated at admission.** Every occurrence in every
  group must name a `(yin/def <literal sym> _)` node; every such node in
  the batch must appear in exactly one group; groups are non-empty and
  pairwise disjoint; every occurrence in a group must have the same
  `:literal` symbol and the same value-operand content address, since one
  original definition cannot have two bodies; every `:yin/declarations`
  entry must be a member of some group, and a group is declared as a whole
  (all of its occurrences carry the declaration or none do, which is
  automatic for a legacy entity with one flag). The declaration catalogue
  is derived from the validated harvest catalogue and the declarations,
  never carried separately, so it cannot disagree with either. A
  violation is an admission failure `{:kind :malformed-input :reason
  :harvest-catalogue}` naming the entry.
- **Binding.** A declaration `[j path :yin.macro/definition]` names the
  `:application` at `path` in tree `j`, and through the catalogue the
  original definition whose group contains `[j path]`; that node must be
  `(yin/def <literal sym> <lambda>)`, i.e. operator `[:variable yin/def]`,
  first operand a `:literal` symbol, second operand a `:lambda`. A
  declaration whose coordinates do not resolve to such a node is an
  admission failure, `{:kind :malformed-input :reason
  :stray-macro-declaration}`, the successor of §3.1 step 1's
  `:stray-macro-lambda`.
- **Marks are validated before they are stripped.** A frontend or the
  §9.1 adapter that converts a map AST carrying `:macro? true` must reject
  a flagged lambda anywhere but the value operand of a `yin/def` as
  `:stray-macro-lambda` **at conversion**, before the flag is removed;
  it may never strip such a flag and emit a plain lambda. A flag in the
  admitted position becomes the declaration at that tree and path. The
  quiet case `yin.vm.macro.md` decision 5 exists to prevent is therefore
  prevented at the same point it is today.
- **Ordering of harvest.** The catalogue's order; last wins, as §3.1
  step 2 rules. Vector order and preorder play no part in harvest.
- **Missing fact.** A `(yin/def sym v)` with no declaration at its
  coordinates is a plain definition. If `sym` is currently a macro, the
  plain definition removes it, exactly as §3.1 step 2 rules.
- **Scope.** Harvest is syntactic and whole-batch, as today; a declaration
  in an unexecuted branch or in a tree the root never reaches is in force
  for the batch. Declarations are per batch and never persist beyond the
  harvest that consumed them; the harvested store is the expander's state.
- **Redefinition.** Two batches that carry identical trees differ in
  behaviour only through their declarations, which is the intended
  separation: content is what the program is; being a macro is what this
  occurrence of a definition is *for*.
- **Evaluators see the root only.** The composition hands an evaluator
  `(nth batch root)` after expansion; a tree outside the root is expander
  input, never program.

This replacement protocol, not the expander itself, is the blocker
(§10.5): tuple evaluation without macros needs none of it, and the v2
expander does not exist yet.

### 8.6 As-of queries are ledger views

Current value of a name uses the implemented current-state interpreter,
which resolves the greatest `t` per `[e a v]` and then removes retractions
(`query/current`, `query.cljc:204-212`; the fold `current-state-seq`,
`query.cljc:76-103`):

```clojure
(q '[:find ?addr . :in $ ?name :where [?e :yin/name ?name] [?e :yin/code ?addr]]
   (query/current refs) 'my.ns/f)
```

Value of a name as of `?t` is the same view with its as-of argument, which
bounds visible datoms to `t ≤ ?t` before folding
(`query/current` two-arity, `query.cljc:210-212`; `bound-datoms`,
`query.cljc:68-73`):

```clojure
(q '[:find ?addr . :in $ ?name :where [?e :yin/name ?name] [?e :yin/code ?addr]]
   (query/current refs t) 'my.ns/f)
```

A hand-written negation over raw d5 rows is not the contract and is not
used: "any retraction before `?t`" wrongly removes an address that was
retracted and later reasserted, whereas the fold's latest-event-wins per
`[e a v]` keeps it. A query that must inspect raw rows selects the latest
event per `[e a v]` with `max ?t` first and interprets that row's `m`
second.

Provenance walks are joins over event entities:

```clojure
;; what was this segment lowered from, and what did that expand from
[:find ?src
 :in $ ?seg
 :where [?d :yin.ledger/op :derive] [?d :yin.ledger/output ?seg] [?d :yin.ledger/input ?tree]
        [?x :yin.ledger/op :expand] [?x :yin.ledger/output ?tree] [?x :yin.ledger/input ?src]]
```

---

## §9 Migration surface

### 9.1 The mechanical sweep

Every site that constructs or reads a map node changes to the positional
form, and every site that writes into a held node changes to the frame
schema of §2.6. The semantics do not change; the reads and the frames do.

+-------------------------------------------------------------------+----------------------------------------------------------------------------------------------------+
| Site                                                              | Change                                                                                             |
+===================================================================+====================================================================================================+
| walker cold syntax arms, `ast_walker.cljc:359-469`                | `(:type node)` → `(nth node 0)`; field reads → slot reads; add the `:stream/close` arm (§3.2); the |
|                                                                   | `:vm/store-update` arm goes with the map path (§3.1)                                               |
+-------------------------------------------------------------------+----------------------------------------------------------------------------------------------------+
| walker cold continuation arms, `ast_walker.cljc:205-358`          | `:eval-operator` (`:206-222`), `:eval-operand` (`:223-238`), `:eval-test` (`:239-244`), the FFI    |
|                                                                   | operand arm (`:245-260`), `:eval-stream-put-target` (`:287-296`): read `:node` and keep            |
|                                                                   | `:fn`/`:evaluated` in the frame per §2.6; no `assoc` into the node                                 |
+-------------------------------------------------------------------+----------------------------------------------------------------------------------------------------+
| walker hot syntax arms, `ast_walker.cljc:600-626`                 | same as cold, five arms                                                                            |
+-------------------------------------------------------------------+----------------------------------------------------------------------------------------------------+
| walker hot continuation arms, `ast_walker.cljc:502-599`           | `:eval-operator` (`:503-541`), `:eval-operand` (`:542-582`), `:eval-test` (`:583-589`): same frame |
|                                                                   | change as the cold arms                                                                            |
+-------------------------------------------------------------------+----------------------------------------------------------------------------------------------------+
| linearizer, `linearize.cljc:87-148` and `:230-242`                | `lower-node` reads slots from the tree instead of `get-attr` over a datom index; `ast-children`    |
|                                                                   | becomes a table lookup of `node`/`nodes` slot positions; `lower` takes a tree and returns a vector |
|                                                                   | plus the §5.3 provenance table, and `lower-ast` goes away                                          |
+-------------------------------------------------------------------+----------------------------------------------------------------------------------------------------+
| codec, `v2.cljc:372-559`                                          | becomes the §6.5 projection pair `tree->datoms` / `datoms->tree`; `:yin/tail?` emitted only from   |
|                                                                   | `:application`; `:yin/macro?`, `:yin/root`, `:eid` handling removed; `:yin/address` added          |
+-------------------------------------------------------------------+----------------------------------------------------------------------------------------------------+
| `code/well-formed?`, `code.cljc:155-180`                          | keeps judging datom batches on the projection path before projection; the shared vector validator  |
|                                                                   | of §7.5 runs on both paths after it                                                                |
+-------------------------------------------------------------------+----------------------------------------------------------------------------------------------------+
| `semantic/load-image`, `semantic.cljc:524-596`                    | decodes from the vector directly on the primary path; the datom path projects first; both call the |
|                                                                   | §7.5 validator                                                                                     |
+-------------------------------------------------------------------+----------------------------------------------------------------------------------------------------+
| frontends `src/cljc/yang/clojure.cljc`, `python.cljc`, `php.cljc` | emit the ordered batch `{:yin/batch :yin/root :yin/declarations :yin/harvest}` (§8.5); drop        |
|                                                                   | `:eid`, `:phase-policy`, and non-`:application` tail marks at the boundary. Until each frontend is |
|                                                                   | swept, one map-to-tuple adapter at the frontend boundary saturates, converts, and handles          |
|                                                                   | `:macro?` in this order: first reject any `:macro? true` lambda that is not the value operand of a |
|                                                                   | `yin/def` as `:stray-macro-lambda`, then turn each admitted one into a declaration at its tree     |
|                                                                   | index and path, then strip the flag. An adapter fed a datom batch derives `:yin/harvest` from      |
|                                                                   | entity order and entity identity: while inlining it records every `[j path]` at which each         |
|                                                                   | definition entity is emitted (the recursion of `datoms->ast`, `v2.cljc:494-559`, must be extended  |
|                                                                   | to carry the path and the entity id, since today it tracks neither), and one entity with several   |
|                                                                   | paths becomes one occurrence group. The adapter emits the harvest catalogue only; the macro-only   |
|                                                                   | declaration ordinals are derived by the expander at admission (§8.5), so the adapter assigns no    |
|                                                                   | `:decl` values. The evaluators see only tuples from the first day, and no flagged lambda ever      |
|                                                                   | becomes a plain one                                                                                |
+-------------------------------------------------------------------+----------------------------------------------------------------------------------------------------+

Tests that construct map ASTs, counted by occurrences of a node-type key on
2026-09-14 (`grep -cE ':type :(application|lambda|literal|variable|if)'`;
the counts are matching lines, not nodes or edits):

+----------------------------------------------------------------------+-------------+
| File                                                                 | Occurrences |
+======================================================================+=============+
| `test/datomworld/demo/continuation_handoff_v2_test.cljc`             | 64          |
+----------------------------------------------------------------------+-------------+
| `test/yin/vm/v2/ast_walker_test.cljc`                                | 49          |
+----------------------------------------------------------------------+-------------+
| `test/yin/vm/v2/parity_test.cljc`                                    | 36          |
+----------------------------------------------------------------------+-------------+
| `test/yang/clojure_test.clj`                                         | 36          |
+----------------------------------------------------------------------+-------------+
| `src/clj/yin/demo_v2.clj`                                            | 29          |
+----------------------------------------------------------------------+-------------+
| `test/yang/python_test.clj`                                          | 19          |
+----------------------------------------------------------------------+-------------+
| `src/cljd/yin/register_bench_cljd_v2.cljd`                           | 19          |
+----------------------------------------------------------------------+-------------+
| `test/bench/yin_vm_v2_bench.cljc`                                    | 16          |
+----------------------------------------------------------------------+-------------+
| `test/yang/php_test.clj`                                             | 14          |
+----------------------------------------------------------------------+-------------+
| `test/yin/vm/v2/semantic_stream_observer_test.cljc`                  | 12          |
+----------------------------------------------------------------------+-------------+
| `test/yin/vm/v2_test.cljc`                                           | 7           |
+----------------------------------------------------------------------+-------------+
| `test/yin/vm/v2/linearize_test.cljc`                                 | 5           |
+----------------------------------------------------------------------+-------------+
| `test/yin/vm/v2/semantic_ffi_test.cljc`, `semantic_engine_test.cljc` | 4 each      |
+----------------------------------------------------------------------+-------------+
| `test/yin/vm/v2/ffi_test.cljc`, `engine_test.cljc`                   | 1 each      |
+----------------------------------------------------------------------+-------------+

Tests that inspect continuation frames (`continuation_handoff_v2_test`,
`ast_walker_test`) also change with §2.6; the count above understates
those.

The v1 lineage (`src/cljc/yin/vm.cljc`, `test/yin/vm/ast_walker_test.cljc`,
`test/yin/vm/ast_conversion_test.cljc`, `runtime_regression_test.cljc`) is
outside this sweep; it stays on maps until it is retired.

### 9.2 The observer lane: an integration item to scope, not a solved detail

Program input reaches an evaluator through
`dao.stream.v2.observer/run-on-stream` (`src/cljc/dao/stream/v2/observer.cljc:217-247`),
which is shape-agnostic: it hands each observed batch to a
composition-supplied `load`. The per-evaluator loaders are chosen in
`yin.repl.v2.core/program-loaders` (`repl/v2/core.cljc:63-68`) and today
accept datom batches; the REPL's own eval path still converts a
semantic-VM AST through `ast->datoms` before it travels
(`repl/v2/core.cljc:444-449`). Two things about the tuple lane are
**unverified**:

- **The batch shape.** Whether one batch is one tree, one `{:yin/code
  :yin/declarations}` value (§8.5), one vector, or a collection of them is
  a medium contract this design has not exercised end to end; §7.1 assumes
  one canonical value per batch. The shape must be fixed with the REPL
  shell and the `yin.vm.v2.stream-observer` tests before the loaders are
  switched.
- **The program-input predicate.** `engine/executable-program-datom?`
  (`engine.cljc:511-513`) decides what counts as program input by testing
  the attribute namespace of an `[e a v t m]` row, and
  `append-program-datoms` (`engine.cljc:590-612`) builds on it. A tuple
  batch has no attribute slot. A repository search on 2026-09-14 found no
  caller of the v2 `append-program-datoms` beyond its own overload, so a
  tuple counterpart is not presumed necessary; whether any live
  composition reaches the predicate is what the implementation phase
  establishes before deciding.

Both are integration items to scope in the implementation phase.

### 9.3 What stays on datoms, and why

This is §1's datom-layer jobs
seen from the migration side:

- **The ledger, every ref, and every ledger event** (§8): mutability,
  history, provenance, and custody are datom properties, and the
  transactor is what possesses them.
- **`dao.space.index` and published covered indexes**: they index datoms,
  and the scale path of §6.5 goes through them.
- **The macro expander's event log** (§8.4): events are entities with
  attributes, one per attempt; only their values changed kind.
- **The datom projection of code** (§6.5): kept for the three query classes
  it alone serves, regenerable from the content at any time.
- **Execution traces** (`yin.vm.semantic.md` §3.6): state over time is a
  datom stream by design and is untouched.

---

## §10 Acceptance blockers and open items

1. **Type-preserving canonical encoding in `dao.jing`** — declared,
   inherited, and **blocking every identity and deduplication use**
   (§4.2). `order-normalize` (`jing.cljc:45-64`) coerces every sequential
   value to a vector and `pr-str` drops metadata, so lists, seqs, and
   metadata-bearing vectors collide with plain vectors. No restricted
   value domain is claimed; the round-2 list-rejection rule is withdrawn
   as unsound. Trees, vectors, ledger-record maps, flat-projection
   collapse, DAG sharing, process-local caches, content resolution by
   address, and ledger rows are all blocked alike until `dao.jing`'s
   encoding passes the conformance pairs of §4.2. This design does not
   close the item.
2. **The store-key domain is the `data` domain** (§2.2): every validator,
   extraction query, and store-slice encoding must use that one
   definition. This document now states it uniformly; the implementation
   phase must not reintroduce a narrower one, and the corpus must contain
   a numeric key beside the keyword one.
3. **Complete occurrence identity** (§2.5, §5.3, §8.4) — design work
   with stated mechanisms, not yet implementable. Source origins
   `[:source medium batch j]` must be supplied to the lowering and
   recorded in instruction provenance and initial expansion events;
   nested expansion events must carry both the declared-ref parent link
   and the parent record address, and the record must be minted after
   the parent's. The medium identity coordinate is whatever the
   composition names its program medium by; this document does not fix
   its shape, and that shape must be fixed before an origin can be
   written. The expander incarnation (§8.4.1) is a composition-minted
   random token, never a log-allocated id or a stream position; its
   uniqueness is the composition's warranty, discharged by the token's
   randomness, and no transactor is claimed to provide it. The
   `:incarnation` field of `ctx` and the token-minting step at
   construction are additions to `yin.vm.macro.md`'s `ctx` and expander
   construction that this document cannot make there.
4. **Macro batch and admission preservation** (§8.5) — design work with
   stated mechanisms. The ordered batch, the admission-order harvest
   catalogue of occurrence groups, the derived macro-only declaration
   catalogue, its admission validation, the validate-before-strip rule,
   and the adapter's path-and-entity tracking during inlining must exist
   before any tuple program containing a macro definition can be
   admitted. The legacy datom-batch contract is
   preserved only through the adapter-derived catalogue; tuple-native
   frontends operate
   under the separate declaration-order contract stated there. The v2
   expander itself does not exist yet (`src/cljc/yin/vm/v2/` has no
   `macro.cljc`) and is not a blocker for tuple evaluation without
   macros; §8.4's event shape is specified against `yin.vm.macro.md`, not
   against code.
5. **Conservative dependency completion** (§7.7.2, §7.7.3) — design
   work with stated mechanisms. Parameter discharge requires the call
   analysis of §7.7.2 (every call site known and supplying the argument);
   convergence is over work items and all dependency facts, with the
   bound-name-set context abstraction. Neither analysis exists in code;
   until they do, an emitter may only report `:incomplete`.
6. **Effect normalization** (§7.7.1): the footprint table must be
   published with the execution-contract stamp, and the tree/segment
   requirement-set equality must be tested over the corpus. FFI ops are a
   receiver capability requirement, never a store-slice requirement.
7. **The lowering profile** (§5.2.1): `"ast-v1"` must be published as a
   pinned document before any derivation record is written; until then
   no derivation can be verified, only content integrity (§5.2.2).
8. **UCF §7.6.1 needs two amendments** (§7.7.2, §7.7.3): its
   any-carried-environment satisfaction sentence is unsound and must be
   replaced by the per-context rule, and its address-per-segment fixed
   point must become the work-item fixed point. This document cannot edit
   UCF.
9. **The observer-lane batch shape and the program-input predicate**
   (§9.2) — integration items with no design answer in this document. The
   §8.5 ordered batch is a candidate shape for the AST medium, not a
   verified one.
10. **Ledger event representation on the transactor** (§8.2): the
    event-entity form is legal under `local-datom?` as written, but the
    map-`m` provenance path (`transact.cljc:15-29`) and the metadata-op
    interpreter `dao.space.schema.datomic.md` §4 names are not exercised by
    this design's tests yet. The naming rows need neither; the provenance
    link from a naming fact to its event does.
11. **The primitive-profile registry** — inherited from UCF §7.11. Every
    retained name obligation is checked by profile under UCF §7.5.2, and
    §7.7.2 reads callable effects from profiles; until
    `yin.vm.v2/primitives` (`v2.cljc:100-132`) is published with profiles,
    dependency closure reports names and `:incomplete`, not satisfiable
    bindings.
12. **Per-lambda subterm addressing** — a follow-up (§4.3), not a blocker.
