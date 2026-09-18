# yin.vm.code-as-tuples — code as content-addressed tuples

Status: Proposed, revision 8 (after the owner's 2026-09-15 clarification
of ruling 1, accepted through an APPROVE-WITH-FINDINGS r7 review). This
document implements eight owner rulings — seven of 2026-09-14 and one of
2026-09-15: the map AST is the Universal AST's canonical representation that gets evaluated by the ast-walker (a LISP using maps, not a LISP using vectors instead of lists). However, the Universal AST is converted into flat per-node tuple rows (`[id tag & slots]`) that act like bytecode, preserving everything in the Universal AST so that the semantic VM can execute it quickly because of the linearization of the AST into tuples (the VM lowers these rows into an instruction vector for execution). These rows are what is content-hashed, stored, streamed, and named — there is no nested tuple tree (ruling 1 of 2026-09-14,
clarified 2026-09-15); the grammar is fixed-arity, saturated, and
provenance-free; `dao.space.query/q` runs over the rows directly; all `yin.vm` evaluators are `dao.stream` observers that load rows to evaluate code; concurrently, a dedicated AST indexer observes the same stream and maintains the **row relation** of §6.1 (the `$ast` relation `q` runs over) from the rows it observes. `dao.jing` is the content store the rows already live in; it is not the indexer's output; code on a stream is rows end to end; `t` and `m` live in a ledger of
records over code addresses; datoms `[e a v t m]` are the reference
layer; and the round-trip law — `map → rows → map` and `rows → map →
rows` are identities — is a first-class conformance obligation (ruling 8,
2026-09-15). The rulings are inputs. What follows is their
implementation as a protocol: every sentence is a rule of the design, and
each call it makes is stated once.

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
(§7.2). Line numbers were read from the working tree on 2026-09-14 and
re-verified on 2026-09-15.

---

## §1 Stance and layering

Code lives in one layer only, content; the other two layers never hold
code, only facts about it and views of it. Each has one job, and the
things that never cross between them are what make the design hold.

+----------------+--------------------------------+-------------------------------------+--------------------+--------------------------+--------------------------------+
| Layer          | Example                        | What it holds                       | Identity           | Where it lives           | Mutability                     |
+================+================================+=====================================+====================+==========================+================================+
| **Content**    | `{:type :lambda :params [x]…}`,| The Map AST code itself, stored as  | the content        | `dao.jing`, under its    | immutable; no `t`, no `m`, no  |
|                | the Map AST semantic root      | a linearized bytecode of flat       | address — a row's  | address                  | name, no occurrence, no        |
|                |                                | per-node tuple rows (§2) and        | id; a tree is named| (`src/cljc/dao/jing.cljc | predecessor address inside     |
|                |                                | instruction vectors (§5); and       | by its root row's  | :217-225`)               | code content                   |
|                |                                | also content-addressed values       | id (§4.1)          |                          |                                |
+----------------+--------------------------------+-------------------------------------+--------------------+--------------------------+--------------------------------+
| **Refs**       | [e :yin/code                   | Datoms `[e a v t m]` whose `v` is a | the datom itself,  | the ledger relation      | append-only; the hash chain is |
|                | :segment/sha256-…]             | code or record address: names,      | in a `dao.space`   | (§8), transacted by a    | realized by ledger records and |
|                |                                | history, retraction, derivation,    | ledger             | `dao.space` transactor   | the refs that publish them     |
|                |                                | expansion, publication, custody     |                    |                          | (§8.2)                         |
+----------------+--------------------------------+-------------------------------------+--------------------+--------------------------+--------------------------------+
| **Query**      | `[seg 12 :call 2 false]`, one  | Entity-shaped views of code         | derived; never an  | computed from content by | recomputed at will; discarding |
|                | segment-qualified row (§6.2)   | internals: the segment rows         | identity           | a pure function, kept by | a projection loses nothing     |
|                |                                | `[pc tag & ops]`, the datom         |                    | whoever queries (e.g.    |                                |
|                |                                | projection `[e a v t m]` of a tree, |                    | the AST indexer)         |                                |
|                |                                | and the occurrence relation (§6.1)  |                    |                          |                                |
+----------------+--------------------------------+-------------------------------------+--------------------+--------------------------+--------------------------------+

The layering is the git/Unison one: content is what git calls objects — blobs and
terms named by hash — refs are `name → hash` entries with history, and a projection is a
working tree or an index that can be regenerated from the content.

**Where the semantic layer lives.** The map AST — a tag plus named fields,
the walker's form — is the language's own representation, and it is a
presentation of the content, not a fourth layer: it is never
content-hashed, stored, or shipped; it exists on both sides of storage,
frontend-side before the boundary projection and machine-side after
load-time reconstruction, ephemeral and per-consumer, like an image
(§2.1).

The third layer is named for its job, querying; its members are the
projections — the segment rows (`§6.2`), the datom projection (`§6.5`),
and the occurrence relation (`§6.1`) — and wherever this document says
"a projection" it means one of those forms, never the layer. The flat
per-node rows are not among them: a row is the stored form itself, not a
view of something else (`§2.1`).

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
- **No datom-projection tempid is ever named by a ref, a ledger row, a
  closure, or a continuation.** The datom projection's `e` values are
  local; only addresses cross layers, and this is what retires the
  `:eid` sharing of `src/cljc/yin/vm.cljc:394-411` (§4.4).
- **Content identity is not occurrence identity.** An address says *what*
  a value is. Which source position, which batch, which call site, which
  expansion attempt produced it is occurrence information, and it is kept
  in side tables and events keyed by an origin (source batch or producing
  event), root address, and structural path (§2.5, §4.4, §8.4), never by
  content address alone.
- **The hot loop reads content only.** An evaluator builds its image at
  load — the walker reconstructs the map AST from rows, the semantic VM
  decodes the vector — and never consults a ref or a projection while
  stepping (§7.6).

---

## §2 The AST tuple grammar

### 2.1 Form

The map AST is the code's own representation — the **semantic layer** (the Universal AST). A
node is a map of its tag and its named fields:

```clojure
{:type :lambda,
 :params [x],
 :body {:type :application,
        :operator {:type :variable, :name +},
        :operands [{:type :variable, :name x} {:type :literal, :value 1}],
        :tail? true}}
```

The fields are what the walker dispatches on today (`src/cljc/yin/vm/ast_walker.cljc:333-470`); the `:lambda` arm already binds `params` by name, and the `:variable` arm already resolves through `resolve-var`'s env → store → primitives → modules fallthrough (`ast_walker.cljc:361-363`; the semantic VM's `:var` opcode does the same at `semantic.cljc:256-259`) — this design keeps that fallthrough rather than splitting free and bound references into separate node types (§4.5). Separately, closure application (`ast_walker.cljc:191`, and its hot-path copies at `:511`/`:553`; `semantic.cljc:200`) still binds arguments with `(zipmap params args)`, which leaves missing parameter names unbound rather than bound to `nil` — that binding change belongs to the application arm and the semantic VM's apply, not to `:lambda`.

The map AST is never hashed directly, never stored, never
shipped. It exists on both sides of storage — frontend-side, where
frontends emit it, and machine-side, where the loader reconstructs it
(§7.1) — and it is ephemeral and per-consumer, like an image: each
consumer's map is its own value, though conceptually they all represent the canonical Universal AST (the semantic representation).

The **canonical stored artifact** is the **flat per-node row projection** of the map
AST, `project : map-ast → rows`, one row per node (the bytecode linearization):

```clojure
[A :lambda [x] B]
[B :application C [D E] true]
[C :variable +]
[D :variable x]
[E :literal 1]          ; A..E are :segment/sha256-… row ids
```

A row's **body** is `[tag & slots]`: the slots are the node's field
values in the §2.3 order — a child slot holds the child row's id, a
`nodes` slot holds the ordered vector of the children's ids, and every
other slot holds its data unchanged. The row's id is
`(dao.jing/segment-key body)` (`src/cljc/dao/jing.cljc:217-225`). The stored payload in `dao.jing` is exactly the flat body `[tag & slots]`. When queried or streamed, the system reconstructs the full logical row by prepending the returned address, yielding `[id tag & slots]`. The id is an address envelope outside the hashed payload. The
id is therefore merkle: a parent's id commits to its children's ids
inline, and a tree's address is its root row's id (§4.1). These rows are
what `dao.jing` stores, what streams carry, what addresses name, and
what `q` queries. **There is no nested tuple tree anywhere in this
design**: the rows are not interleaved into a tree-shaped value, and no
sentence below may be read as one; the tree is the map AST the rows
reconstruct to. Identical subtrees project to the identical row, so a
stored set holds one row per distinct subtree and is the tree's DAG
(§4.4). Whether `dao.jing` stores rows as individual values (git-style,
shared subtrees shared) or packs them per tree is an **Open Question**,
not just an implementation note, because the choice determines the loader's interface (N fetches vs 1 fetch) and the validator's reachability rules. If packed, the pack requires its own address distinct from the root id.

The projection is total and lossless by the round-trip law (§7.2): for
every corpus program, `map → rows → map` is the identity on the
*canonical* map AST (post-strip and post-saturate, with frontend `:yang/*` keys and stray `tail?` marks gone), and `rows → map → rows` is the identity on the rows.
The §2.3 table is the dictionary of both directions — a tag's slot list,
in order, is the map's key set, so one table fixes the row's positions
and the map's fields together.

**Runtime `assoc` into a map node is legal.** The node a walker holds
under `:frame` is the machine's ephemeral image of the code, not a
canonical value, and nothing runtime-`assoc`ed is ever projected back to
rows: the cold `:eval-operator` arm `assoc`s `:operator-evaluated?` and
`:fn` into it (`ast_walker.cljc:213-217`), the cold `:eval-operand` arm
`assoc`s `:evaluated` (`ast_walker.cljc:226-237`), and the hot arms do
the same (`ast_walker.cljc:532-541`, `:576-580`); all of it stays inside
the machine. What the boundary requires is only that a projection's
input be a well-formed map AST (§2.4, §2.5); the reverse direction never
re-derives what evaluation wrote (§2.6).

### 2.2 Slot kinds

Every slot in the table has one kind. The kind is fixed by the tag and the
position; it is never inferred from the value. A literal whose value happens
to look like a node is a value, which is the rule `linearize/ast-children`
already states (`src/cljc/yin/vm/linearize.cljc:230-242`).

+---------+----------------------------------+---------------------------------------------------------------------------------------------------------------------------+
| Kind    | Admits                           | Notes                                                                                                                     |
+=========+==================================+===========================================================================================================================+
| `node`  | one child node                   | the child's row id in a row; the child's map in the semantic layer                                                        |
+---------+----------------------------------+---------------------------------------------------------------------------------------------------------------------------+
| `nodes` | a vector of child nodes,         | one slot, so arity stays fixed however many operands there are; the ordered ids in a row, the maps in order in the        |
|         | possibly empty                   | semantic layer                                                                                                            |
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
|         |                                  | (`src/cljc/yin/vm/semantic.cljc:577-579`), so symbols (`yin/def`), keywords (`test/yin/vm/parity_test.cljc:99`), and|
|         |                                  | numbers are all portable keys today. `key` is a named kind rather than `data` only so that the same definition is cited by|
|         |                                  | AST validation (§7.4), instruction validation (§7.5), dependency extraction (§7.7), and the store-slice encoding of UCF   |
|         |                                  | §7.6.2; it is the `data` domain and nothing narrower                                                                      |
+---------+----------------------------------+---------------------------------------------------------------------------------------------------------------------------+
| `sym`   | one symbol                       |                                                                                                                           |
+---------+----------------------------------+---------------------------------------------------------------------------------------------------------------------------+
| `syms`  | a vector of symbols,             | one slot, so arity stays fixed however many parameters there are; the                                                     |
|         | possibly empty                   | ordered symbols in a row, the symbols in order in the semantic layer                                                      |
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
inventory (`src/cljc/yin/vm.cljc:413-478`) under the two boundary calls
of §3. The `Body arity` column counts the body items (tag plus slots). A full row's count is its body arity plus one (for the ID). This table is the **projection and reconstruction
dictionary**: the slot list of a tag, in order, is exactly the map's key
set beyond `:type` — the arity ↔ key-set correspondence — so the one
table fixes the row's positions and the map's fields together. Position
`i` of a row body holds the value of the field the *Slots* column names
at `i`; projection reads the positions, reconstruction reads the names,
and neither side may disagree with the other.

+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+
| Tag                     | Body arity | Row body                             | Slots                    | Slot kinds       | Walker arm         | Codec arm       |
+=========================+============+======================================+==========================+==================+====================+=================+
| :literal                | 2          | [:literal value]                     | value                    | data             | ast_walker.cljc:360| v2.cljc:414-415 |
+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+
| :variable               | 2          | [:variable name]                     | name                     | sym              | unchanged: `ast_walker.cljc:361-363` | unchanged: `v2.cljc:417-418` |
+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+
| :lambda                 | 3          | [:lambda params body]                | params body              | syms, node       | unchanged: `ast_walker.cljc:364-370` | unchanged: `v2.cljc:419-424` |
+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+
| :application            | 4          | [:application operator operands      | operator operands tail?  | node, nodes, bool| :371-376           | :424-429        |
|                         |            | tail?]                               |                          |                  |                    |                 |
+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+
| :if                     | 4          | [:if test consequent alternate]      | test consequent alternate| node, node, node | :377-381           | :435-441        |
+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+
| :dao.stream.apply/call  | 3          | [:dao.stream.apply/call op operands] | op operands              | kw, nodes        | :382-394           | :430-434        |
+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+
| :vm/gensym              | 2          | [:vm/gensym prefix]                  | prefix                   | str              | :395-400           | :443-444        |
+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+
| :vm/store-get           | 2          | [:vm/store-get key]                  | key                      | key              | :401               | :445-446        |
+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+
| :vm/store-put           | 3          | [:vm/store-put key val]              | key val                  | key, data        | :402-409           | :447-449        |
+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+
| :vm/current-continuation| 1          | [:vm/current-continuation]           | —                        | —                | :422-427           | :474-475        |
+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+
| :vm/park                | 1          | [:vm/park]                           | —                        | —                | :428-430           | :469            |
+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+
| :vm/resume              | 3          | [:vm/resume parked-id val]           | parked-id val            | kw, node         | :431-438           | :470-473        |
+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+
| :stream/make            | 2          | [:stream/make buffer]                | buffer                   | int              | :439-447           | :451-453        |
+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+
| :stream/put             | 3          | [:stream/put target val]             | target val               | node, node       | :448-454           | :454-458        |
+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+
| :stream/cursor          | 2          | [:stream/cursor source]              | source                   | node             | :455-461           | :459-461        |
+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+
| :stream/next            | 2          | [:stream/next source]                | source                   | node             | :462-468           | :462-464        |
+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+
| :stream/close           | 2          | [:stream/close source]               | source                   | node             | added by §3.2      | :465-467        |
+-------------------------+------------+--------------------------------------+--------------------------+------------------+--------------------+-----------------+

**Named Variables:** A `:variable` row's `name` retains the original symbol from the AST. (De Bruijn computation is deferred to the Register VM phase).

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
and the codec faithfully persists every such mark (`v2.cljc:412`). The
boundary projection drops those marks: a mark no evaluator can
observe is not part of the program's syntax as the evaluators read it. The
mark on an `:if`'s branches is carried by the branch `:application` nodes
themselves, which is how `yin.vm.semantic.md` §5.3's "branches inherit it"
is realized.

### 2.4 Saturation

A row carries every default the loader would otherwise apply, so a
program written with a default omitted and one written with it stated are
one value with one address (UCF §7.3.2, *Saturation*). Saturation is a
rule of the projection boundary: the map→row direction materializes
defaults, the row→map direction never re-derives or re-defaults anything,
and a row with a nil in a saturated slot is invalid, not defaulted.

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

### 2.5 Exclusions, occurrences, and side tables

Exclusion is a rule of the projection boundary, the same rule §2.4 states
for saturation: these facts are stripped map→rows and never re-derived
rows→map. The map AST a frontend emits carries them; no row does:

- **Source positions** and any frontend metadata (`:yang/*` keys, Clojure
  reader metadata on symbols). Tracked instead in secondary side tables.
  **Parameter and variable names are not an exclusion**: the boundary
  projection retains them in the canonical tuples (`:lambda params`,
  `:variable name`), since this design preserves named variables across
  the boundary.
- **`:macro?`** and `:phase-policy`. `yang.clojure` sets them on defmacro
  lambdas (`clojure.cljc:436-438`) and the codec persists `:yin/macro?`
  (`v2.cljc:419-420`, schema `v2.cljc:312`). No evaluator reads them
  (`ast_walker.cljc:20-23`). The expander needs the fact that a particular
  definition is a macro; that fact travels beside the tree as an
  occurrence-bound declaration (§8.5), not as a slot.
- **`:eid`** (§4.4).
- **`:yin/root`.** A tree is its own root; the batch or the ref that names
  the tree names the root. No root fact exists in a row — the root row's
  id is the root fact (§4.1).
- **`t`, `m`, provenance.** Every provenance fact the codec or linearizer
  writes today (`:yin.code/source`, `:yin.code/derived-from`) becomes a
  ledger record (§8) or a side-table entry.

**Occurrences.** Content identity names *what*; three further things
name *where*, and each is a distinct coordinate:

- A **structural path** names a place inside one tree: a vector of slot
  indices from the root, with an index into a `nodes` slot written as a
  pair `[slot i]`. `[]` is the root; `[2 [3 0]]` is the first operand of
  the root's second slot. Slot indices are the row's positions, which the
  §2.3 dictionary fixes against the map's key order, so a path reads
  identically on both sides of the round trip. A path is a pure function
  of the tree, stable across media and hosts. `[root-address path]`
  separates two identical subtrees at two places in one tree, and nothing
  more.
- A **source occurrence** names one admission of one tree: `[medium batch
  j]`, the program medium's identity, the batch coordinate on it (the
  `:yin/source-batch` of `yin.vm.macro.md` §4.1), and the **member index**
  `j` of the tree within the batch (§8.5's ordered batch carries several
  trees). Two identical trees parsed from two files, admitted in two
  batches, or sitting at two indices of one batch have one root address
  and two source occurrences. A root address alone never identifies a
  source, and neither does a batch without its member index.
  Here `medium` is the program medium's `:dao.stream/identity` and `batch`
  is the admitting composition's fresh plain-data token, carried as
  `:yin/source-medium` and `:yin/batch-token` in the batch envelope. A retry
  of an unaccepted staged append keeps that token; a distinct admission
  mints another. Neither coordinate is inferred from cursor internals or
  content.
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

### 2.6 A walker-local note on runtime mutation

The frame re-scheme this section once carried is withdrawn as a design
requirement (owner ruling, 2026-09-15): the walker stays on map ASTs, and
runtime `assoc` into a map node is legal, because the held node is the
machine's ephemeral image of the code, not a canonical value (§2.1), and
nothing runtime-`assoc`ed is ever projected back to rows. The reasoning
worth keeping, as a note and not a rule: today's cold arms `assoc`
`:operator-evaluated?`/`:fn` (`ast_walker.cljc:213-217`) and `:evaluated`
(`:226-237`), and the hot arms the same (`:532-541`, `:576-580`);
`:operator-evaluated?` is redundant with the frame type, and a walker that
wanted the node read-only could keep every mutable field a sibling key of
the frame map — an implementation choice, available at any time, required
by nothing here. Frame continuations remain runtime state, not syntax, as
do closures (`ast_walker.cljc:364-370`) and reified continuations
(`ast_walker.cljc:422-427`). When continuation frames are themselves serialized or projected to datoms, the runtime keys the walker assoc'd into nodes must be stripped at that boundary.

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
`test/` for the node type on 2026-09-14 found none). Its one reachable
path is an in-process, hand-built map AST handed straight to the walker;
no row can carry it (§2.2, `data`), so it never crosses the boundary in
either direction.

The orchestrator's alternative, a `:fn` slot restricted to a named
primitive symbol resolved and profile-checked per UCF §7.5.2, is not
adopted: it would re-admit a node that no persistent form carries, for a
convenience no corpus program uses.

A program that needs the behaviour writes it as an application of the
`yin/def` primitive (`v2.cljc:128`) over a `:vm/store-get` operand:
`(yin/def k (f (vm/store-get k) args…))`. That rewrite is equivalent to
the arm **only under stated conditions**, and this document does not claim
it as a general semantics-preserving migration:

- `yin/def` and `f` resolve through `resolve-var`'s precedence, store
  → primitives → modules (`src/cljc/yin/vm/engine.cljc:46-58`), so the
  rewrite is equivalent only when neither `yin/def` nor `f` is shadowed by an enclosing `:lambda`'s params at the site (so both resolve as free names, through the fallthrough, rather than to a local binding);
- the arm stores whatever `(apply f current args)` returns, as data
  (`ast_walker.cljc:410-415`), while an application interprets an
  effect-shaped return (`ast_walker.cljc:184-188`,
  `semantic.cljc:206-220`). An effect descriptor is itself plain data:
  `module/effect?` recognizes any map carrying `:effect`
  (`src/cljc/yin/vm/module.cljc:77-79`), and
  `{:effect :vm/store-put :key :x :val 4}` passes `plain-data?`. So the
  rewrite is equivalent only when `f` returns plain data **that is not an
  effect descriptor under the applicable execution contract**; a result
  the contract would interpret is stored by the arm and executed by the
  rewrite.

### 3.2 `:stream/close` exists, in both codec and walker

One grammar truth: the instruction is real. The codec writes it
(`v2.cljc:465-467`) and reads it back (`v2.cljc:549-550`), the linearizer
lowers it (`linearize.cljc:135-136`), `yin.vm.code/mnemonics` admits
`:stream-close` (`src/cljc/yin/vm/code.cljc:12-16`), the semantic VM
decodes it (`semantic.cljc:585`) and executes it in its hot loop
(`semantic.cljc:397-399`), and the engine handles the effect
(`engine.cljc:480-482`). Only the walker lacks the arm
(`ast_walker.cljc:359-469`).

The walker gains it: evaluate `source`, then raise `{:effect :stream/close
:stream ref}` through `engine/handle-effect`, through an
`:eval-stream-close-source` frame shaped exactly as
`:eval-stream-cursor-source` (`ast_walker.cljc:319-326`). Removing the
instruction instead would delete working behaviour from the default
evaluator to match the non-default one; the parity suite is the reason the
two evaluators share one corpus.

---

## §4 Addressing

### 4.1 The grain

The address of a tree is the id of its root row. A row's id is
`(dao.jing/segment-key body)` (`jing.cljc:217-225`) over the row's body —
the tag, the data slots unchanged, the children as ids (§2.1) — so a
parent's id commits to its children's ids and every node is addressed by
construction; nothing coarser is hashed and no node needs a finer grain.
A root id is still a whole-tree address: following child ids from the
root row recovers the entire tree, and every named payoff below names
exactly this id.

+-----------------------------------------------+------------------------------------------------------------------------------------------------------------------------+
| Payoff                                        | How it works at the root-row grain                                                                                     |
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

### 4.2 The transitional encoder is inherited; ordinary identity use is unblocked

`segment-key` hashes an order-normalized, metadata-aware hand printer
(`jing.cljc`'s `order-normalize` / `canonical-print`). The three
conformance pairs this section used to name as blocking collisions are
now closed:

+--------------------------------------------------------+------------------------------------+--------------------------------------------------------------------------+
| Two different values                                   | Address today                       | Why it matters                                                           |
+========================================================+====================================+==========================================================================+
| `[:literal [1 2]]` and `[:literal (1 2)]`              | distinct                            | `conj` appends to one and prepends to the other                          |
+--------------------------------------------------------+------------------------------------+--------------------------------------------------------------------------+
| `[:literal [1 2]]` and `[:literal (seq [1 2])]`        | distinct                            | a seq is sequential but not a list, and both differ from the vector      |
+--------------------------------------------------------+------------------------------------+--------------------------------------------------------------------------+
| `[:literal ^{:a 1} [1 2]]` and `[:literal ^{:a 2} [1   | distinct                            | `data` admits plain metadata (`linearize.cljc:58`) and programs may read |
| 2]]`                                                   |                                      | it; collection metadata is now address-significant                      |
+--------------------------------------------------------+------------------------------------+--------------------------------------------------------------------------+

`dao.jing.md`'s Canonical encoding section records the encoder's current
contract precisely and its remaining residuals: scalar (symbol) metadata
is not address-significant, pathological symbols whose print mimics
another value's can still collide, byte arrays are hashed by identity
rather than content, and ambient print-var bindings still reach scalar
bytes. None of the three pairs above are affected by any of those
residuals — they were the structural (collection-level) gaps this design
depended on closing, and they are closed. **The identity and
deduplication block this section used to impose is lifted for ordinary
values** — rows, vectors, ledger-record maps, flat-projection row
collapse (§6.1), DAG sharing (§4.4), process-local address-keyed caches,
and `dao.jing` reads that resolve an address to a literal value may treat
two equal addresses as one value. The remaining residuals above are
pre-existing, scoped, and tracked as `dao.jing.md` Open Items rather than
as a design-wide block, but they are not uniformly latent: scalar
metadata and byte-array identity hashing need a future producer to emit
those specific value shapes before they matter, while ambient print-var
bindings (`*print-readably*` and similar) reach scalar bytes for ANY
scalar today — that residual is live now for any caller that hashes
inside such a binding, not conditional on a future producer.

The pinned canonical byte encoding itself — the target `dao.jing.md`
describes as its first open item, replacing this transitional hand
printer — remains open work; when it lands, every address minted under
this design changes with every other `dao.jing` address, the dependency
UCF §7.3.2 already accepts.

### 4.3 Every node is addressed by construction

Per-node addressing is not a projection consequence and no longer a
follow-up: a row's id is its node's address by construction (§2.1), so
every node of every tree is named, shareable, and queryable the moment it
is projected. The round-7 question — a `:lambda` whose body is
*materialized separately*, referred to by address from the canonical form
of the enclosing tree, pinned by manifests on its own — is the ordinary
shape of the stored row DAG, not a canonical-form extension: every child
slot already refers by address, and a manifest may name any row's id. What
remains of it is a lowering question — one segment per lambda rather than
out-of-line bodies in one segment (`linearize.cljc:151-158`) — which is a
§5.2.1 profile concern, changes no row address, and is not open here.

### 4.4 Sharing by content address replaces `:eid`; occurrences stay outside

Today a shared subtree is emitted once and referenced by a pre-assigned
entity id: `yang.clojure` allocates one for each defmacro lambda
(`clojure.cljc:418-437`) and the codec deduplicates on it
(`v2.cljc:394-411`). Its purpose is that the definition operand and each
call-site operator resolve to one entity so provenance can point at it.

Content addressing gives that for free and in every case, not only the one
`yang.clojure` remembers to mark: two occurrences of one subtree have one
address by construction and one row in the stored set, and a query "which
sites reference this lambda's content" is a join on its address (§6.4).
Because this design retains named variables in the canonical tuple (§2, §5.1),
this structural sharing requires exact identity, not alpha-equivalence:
two functions that are identical up to variable renaming project to
*different* rows, since the parameter and variable names are part of the
hashed content. Only subtrees identical in structure *and* in every retained
name collapse to one row. The row set holds each such distinct subtree once,
and parent slots point at it — while identity stays per row.
**This structural collapse depends on the §4.2 encoder being injective, which it now is for ordinary values under default print bindings.** The three conformance pairs (vector/list, vector/seq, collection metadata) that used to make the encoder non-injective are closed; structurally distinct ordinary values no longer hash equal under the default host print configuration. The disclosed residuals remain gaps: scalar metadata and byte-array identity hashing need a future producer to matter here, but ambient print-var bindings reaching scalar bytes are live now — a row whose slots include scalars hashed inside such a binding is not covered by this collapse guarantee — see §4.2.
`:eid` is dropped from the frontends and the codec.

What `:eid` also did, and content addresses do not, is name a *place*.
"This call site" is an occurrence key `[origin root-address path]` (§2.5),
not the site's content address; a query that needs distinct call sites
joins the occurrence relation, not subtree addresses, and a provenance
link that must survive a second identical site, a second identical file,
or a second identical expansion records the occurrence key. Content
addresses serve sharing and cross-media correspondence of content;
occurrence keys serve identity of places, sources, and attempts.

### 4.5 Free variables are queried, not tagged

An earlier revision of this design split variable references into two
node types: `:variable` for names bound by an enclosing `:lambda`'s
params, resolved against the lexical environment only, and `:global` for
free names, resolved through `resolve-var`'s store → primitives →
modules fallthrough. The motivation was static inspectability: knowing a
program's free-name dependencies (for UCF §7.6.1's `:yin.k/requires`
fixed point, and for capability checking before running untrusted
content) without evaluating it.

That motivation does not require a second tag. Whether a `:variable` row
is free or bound is exactly what a **query** over `$ast` (§6.1) can
compute: walk the row's ancestor chain (a `node`/`nodes` slot pointing at
a row's own id is a parent-child edge, so the ancestor relation is a
recursive Datalog rule over the row relation itself, needing no separate
occurrence side-table) and check whether the `:variable`'s `name` appears
in any enclosing `:lambda`'s `params`. If it does not, the name is free —
a `:global`-shaped fact, derived on demand, never persisted. `dao.space.query/q`
already supports recursive rules (`src/cljc/dao/space/query.cljc`'s
`eval-rule`), so this is not a capability gap; it is a query the design
never needed to avoid.

Splitting the tag instead of querying for the same fact bought nothing
the query could not already give, and cost real things: the name
collided with the project's own "no hidden global state" invariant — the
`store` a `:global` reference resolves into is genuinely mutable and
time-varying (it holds `yin/def` results alongside stream handles and
cursor entries, §7.7.2), so the naming confusion was a real signal, not
cosmetic; the no-fallthrough contract (`:variable`'s lexical-only
resolution, evaluating an unbound name to `nil` rather than falling
through to store/primitives/modules) replaced a forgiving runtime
fallthrough with a requirement that every frontend get free/bound
classification exactly right, which is precisely the hardest part of
writing a correctly hygienic macro expander; and the change depended on
frontend scope-analysis work (classifying free names as `:global` at
authoring time) that does not exist anywhere in this codebase. This is
now a decided case study for the broader principle in
[`docs/design/datom.world.md`](./datom.world.md)'s Design Principles:
before adding structure to a canonical, content-hashed representation,
check whether a query already gives you the fact for free.

See `test/dao/space/query_test.cljc` for a working demonstration: a
recursive rule (`edge`/`anc`/`depth`/`bound?`, verified against the real
`dao.space.query/q` engine, not sketched) computing the free-name set of
a row tree with no `:global` tag anywhere in it, including the
nearest-enclosing-binder case under shadowing. `edge`'s clauses are
hardcoded for the fixture's two tags (`:lambda`'s body slot,
`:application`'s operator/operands slots), not generic over §2.3's full
grammar — a tree using a tag with other node-valued slots (`:if`,
`:dao.stream.apply/call`, `:vm/resume`, the `:stream/*` family) has
edges this specific rule does not walk, so it can call a bound name free
(an overcount, not an undercount — still conservative-safe for §7.6.1,
just imprecise). A production rule needs an `edge` clause per node-valued
slot in the grammar, or a schema-driven walk over
`semantic-bytecode-grammar` instead of one clause per tag.

**The row-only query is unsound, not just imprecise, when a name is free
at one occurrence and bound at another — and this is not a permanent
cost of dropping `:global`, only of the simple query.** Because
structurally identical subtrees share one row (§4.4), a name that is
free at one occurrence and bound at another (e.g. `((fn [x] x) x)`, whose
operand `x` is free while the lambda body's `x` is bound) collapses to
one `:variable` row with multiple parent edges — one through the binding
lambda, one not. `bound?`'s row-level walk (`test/dao/space/query_test.cljc`)
finds a binding ancestor via *either* edge and calls the whole row bound,
which would silently drop the genuinely free occurrence from a computed
`:yin.k/requires` set. UCF §7.6.1 requires that set to be a **conservative**
fixed point — it must never undercount what a program depends on, since
undercounting a capability requirement is a security defect, not an
imprecision. The row-only query can undercount in this mixed case, so it
is not sufficient for `:yin.k/requires` on its own.

The fix is not to bring `:global` back. A name's free/bound status at a
*specific occurrence* is exactly a places-not-contents question, and
§6.1 already routes those through the occurrence relation
(`[origin root-address path ...]`, §2.5) rather than row identity: walk
each occurrence's own path to its ancestors, not the row's abstracted
parent edges. This is not merely argued — `test/dao/space/query_test.cljc`'s
`occurrence-aware-rules-resolve-mixed-free-and-bound-rows` proves it
against the real `q` engine, on the exact `((fn [x] x) x)` case above: a
hand-built occurrence relation of §2.5's shape (the production indexer
that would emit it does not exist yet, so the test builds it as fixture
data, same honest scoping as the row-level demo), a recursive rule
walking path-prefix ancestry rather than row edges, and — the point of
the exercise — a **contrast assertion that runs the row-only rule against
the same database and confirms it actually does undercount** (`#{}`
instead of `#{'x}`), so the failure mode §4.5 describes above is
demonstrated, not narrated. No new structure was needed; only the
richer, occurrence-joined query, exactly as this section claims.

**Not root-scoped as written — a real gap, not a hedge.** The test's
`occ-anc`/`occ-bound?` rules take only `?path` and `?name` as arguments;
they never thread a root through the recursion. Over a single tree this
is harmless, but a production occurrence relation holds every reachable
tree's occurrences together, and structural paths are not
root-qualified — two different trees can produce the identical literal
path (e.g. both have a lambda at path `[2]`). `occ-anc`/`occ-bound?` as
written would then find a binder in the WRONG tree and misclassify an
occurrence in one tree against a binder in another. Whoever wires up the
real `:yin.k/requires` computation (open item 5, "Conservative dependency
completion") must thread `?root` through every rule head
(`p-up`/`occ-anc`/`occ-bound?`) and constrain the `[$occ ...]` join to
one root-address, not lift the test's rule set unmodified. What the test
actually exercises is one single-root fixture using `:lambda` and
`:application` only — it does not test `:if` or any other node-valued
tag. What it establishes beyond that fixture is reasoning, not tested
coverage: the occurrence rule walks path prefixes generically rather
than one edge clause per tag, so — unlike the row-only rule's own
tag-coverage gap noted above — nothing about its approach depends on
which tag sits at a given path, which is why it should generalize across
the whole §2.3 grammar without the row-only rule's gap. That reasoning is
not itself tested here. What remains before production use is
root-scoping the rules and building the indexer that emits occurrence
tuples in the first place — both implementation work, not open design
questions.

---

## §5 The segment level

### 5.1 The instruction vector is UCF §7.3.2

The segment's canonical form is the positional instruction tuple vector of
UCF §7.3.2: one tuple per instruction, pc is the index, the
`yin.vm.semantic.md` §2.4 table fixes each mnemonic's arity and operand
kinds, defaults are saturated, refs are resolved pcs, and the header folds
away. Its address is `(dao.jing/segment-key vector)`, the value
`yin.vm.semantic.md` §2.2 already types `:yin.code/hash` as. The two
levels are uniform under this design: an AST row (§2.1) and an instruction
tuple are both flat positional tuples, canonical and content-addressed,
and the instruction vector was already this shape before the flat-row
ruling clarified the AST level. To perfectly preserve program semantics across boundaries, both the Universal Map AST and the Semantic Tuples strictly retain named variables. Therefore, `"v2"` everywhere in this document means the Named Variable grammar. This design otherwise adds
nothing to that form and restates none of it.

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
 :yin.ledger/function :yin.vm/lower
 :yin.ledger/profile  {:yin.lower/profile "ast-to-bytecode"          ; the lowering profile, §5.2.1
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
| Pinned by the profile                 | Reference value for `"ast-to-bytecode"`                                                                                                 |
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

1. **Content integrity.** The consumer verifies the claimed root ID against the root row and verifies each required row as `row.id == segment-key(row-body)`, recursively validating the reachable closure before reconstructing the semantic map. The instruction vector is verified similarly. Failure is
   `:yin.k/hash-mismatch` naming the value. This is UCF §7.3.4's check and
   says nothing about the derivation.
2. **Derivation.** If, and only if, the consumer implements the record's
   `:yin.lower/profile` exactly, it lowers the reconstructed map under that profile and verifies the resulting instruction-vector address against `:yin.ledger/output`. Equality
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

### 6.1 The row relation and the two directions around it

The flat rows are the canonical stored form (§2.1), and `q` runs over
them directly — ruling 3 of 2026-09-14 was verified live over exactly
this shape. A stored set holds one row per distinct subtree: identical
subtrees project to the identical row, so the set is the tree's DAG
(§4.4). Because a tag has one arity in the tree, it has one arity in the
relation (tag arity plus one for the id). Rows of one tag are
homogeneous, so a where-pattern for a tag has exactly that tag's arity
and no padding. Rows from many trees union into one relation without
collision, because ids are addresses. An occurrence count is a count
over parent slots or over the occurrence relation below, never over
rows.

**The Dedicated AST Indexer:** This row relation (called `$ast` in queries) is maintained by the dedicated AST indexer (§1). The indexer is a `dao.stream` observer peer to the evaluators (§7.1); its input is the row batches of §7.1; its output is this row relation plus the occurrence relation below; it is distinct from `dao.space.index`, which indexes datoms only (§6.5); it is a projection keeper per §1's Query layer, so discarding it loses nothing.

`map → rows` is the **codec boundary projection** (`yin.vm/ast->semantic-bytecode`): strip the §2.5
exclusions, saturate per §2.4, positionalize per the §2.3 dictionary,
merkle per §4.1. It is the standing contract of the Encoder Observer, not a
migration device (§9.1).

`rows → map` is **load-time reconstruction** (§7.1): validate the rows
(§7.4), then rebuild the map AST through the ids. The precedent is
`datoms->ast` (`src/cljc/yin/vm.cljc:494-559`), which already
rebuilds map ASTs from flat rows through entity ids — a `get-attr` per
field over an entity index, recursion through child ids; the loader is
its successor with content addresses in place of allocated ids.

Deduplication — treating two equal ids as one value — is an identity use;
for ordinary values it is no longer blocked (§4.2). The occurrence
relation is still what places, not contents, questions go through —
that requirement was never about the encoder block, and it stands
regardless (§4.5's mixed-occurrence case is exactly this: id equality is
sound, but a places question still needs the occurrence relation, not id
equality alone).

A second relation, `occurrences : tree → #{[root-address path
node-address]}`, one row per place, is the structural half of the join
between rows and the occurrence-keyed side tables of §2.5. It is a pure
function of the tree, computable from either side of the round trip, so
it carries no origin; a query fixes the origin from the composition's
side (which batch, which event) and joins on `[root-address path]`
within that origin. It is computed by the same walk that computes the
projection.

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

Direct row loading is primary. As `dao.stream` observers, evaluators receive batches natively from the stream. A batch on the program stream is the
canonical row set of one tree (AST medium) or one canonical instruction
vector (code medium), and the evaluator's loader takes it as is:

+----------------------+----------------------+--------------------------------------------------------------------------------------------------------------------------+
| Evaluator            | Loader input         | What loading does                                                                                                        |
+======================+======================+==========================================================================================================================+
| walker               | map AST              | natively reads map AST from stream topic without conversion — set `:program` and `:control` to the map.                    |
|                      |                      |                                                                                                                          |
|                      |                      |                                                                                                                          |
+----------------------+----------------------+--------------------------------------------------------------------------------------------------------------------------+
| semantic             | instruction vector   | validate (§7.5), decode positional operands into the image (the `case` of `semantic.cljc:563-593` reading `(nth tuple i)`|
|                      |                      | instead of attribute maps), store under `:code` with the address as the alias column UCF §7.3.4 requires                 |
+----------------------+----------------------+--------------------------------------------------------------------------------------------------------------------------+
| semantic,            | row set              | `lower` (§5.2) then the row above; composed by the composition exactly as `linearize/ast-loader` is today                |
| AST medium           |                      | (`linearize.cljc:269-286`, chosen in `src/cljc/yin/repl/core.cljc:63-68`)                                             |
+----------------------+----------------------+--------------------------------------------------------------------------------------------------------------------------+

Datom-batch loading is the projection path: a batch of `:yin/*` datoms is
projected to rows by the datom→row half of the §9.1 codec pair and then
loaded as above; a batch of `:yin.code/*` datoms is decoded to a vector
by UCF §7.3.4's projection rule and then loaded as above. The projection
path exists for media that carry datoms and for the datom projection of
§6.5; it is not a second loader and it runs the same validator (§7.2).

### 7.2 Conformance: both paths, one validator, one image

**This section supersedes the sentence in UCF §7.3.4 that names the
projection path as the reference path.** Under the owner's ruling the
direct path is primary and the reference; the projection path is derived
from it. Everything else in §7.3.4 stands.

Both paths invoke the same validator (§7.4 for rows, §7.5 for vectors)
before anything is registered, and the conformance obligation has four
parts:

1. **The round-trip law.** For every corpus program, `map → rows → map`
   is the identity on the canonical map AST, and `rows → map → rows` is
   the identity on the rows. This is the both-paths obligation restated
   under the flat-row ontology: the projection is total and lossless,
   and each direction is tested against the other over the corpus. The
   corpus already lives as map ASTs in the test tree, which is what makes
   this testable now.
2. **Valid corpus, same result.** For every corpus program, loading the
   rows directly and loading their datom projection yield the same walker
   `:program` value; loading the instruction vector directly and loading
   its datom projection yield the same image. Keyword store keys
   (`parity_test.cljc:99`) and numeric store keys are part of the corpus
   and must round-trip on both paths, so the `key` kind is exercised as
   the `data` domain it is (§2.2), not as the two types the corpus
   happened to use.
3. **Malformed input, same refusal.** For a published set of malformed
   vectors, each violating one rule of §7.5, and malformed row sets, each
   violating one rule of §7.4, both paths refuse with the same defect
   (rule and pc, or rule and path). A correctly hashed `[[:jump 9]]` is in
   the set. Image equality on valid input does not establish this; the
   malformed set does.
4. **Several segments loaded.** Loading two or more segments through each
   path yields the same `:code` map and the same address alias column, and
   a second segment claiming a live local id fails identically on both.

The obligation fails exactly when the dictionary and the datom schema
have drifted apart, which is the property that makes it the VM's own test
of this design.

### 7.3 Validation is the tuple grammar

A tuple loader validates before it decodes, and validation is the §2 table
plus the structural rules a segment needs, nothing else. Both checkers
mirror `code/well-formed?` (`code.cljc:155-180`): each returns nil or the
first defect, rules run in order, and each rule may assume the earlier ones
held.

### 7.4 Row rules

Defects name a path from the root row (§2.5), or the row's own id for a
row nothing reaches.

+-------------------+---------------------------------------------------------------------------------------------------------------------------------------------------+
| Rule              | Defect when                                                                                                                                       |
+===================+===================================================================================================================================================+
| `:tag`            | the element after the id is not a tag of §2.3                                                                                                     |
+-------------------+---------------------------------------------------------------------------------------------------------------------------------------------------+
| `:arity`          | the count differs from the tag's body arity plus one                                                                                              |
+-------------------+---------------------------------------------------------------------------------------------------------------------------------------------------+
| `:slot-kind`      | a slot's value is not of the slot's kind: a `node` slot that is not an address, a `nodes` slot that is not a vector of addresses, a `data` or     |
|                   | `key` slot failing `plain-data?`, a `bool` slot that is not `true`/`false`, a `sym` slot that is not a symbol, a `syms` slot that is not a vector |
|                   | of symbols                                                                                                                                        |
+-------------------+---------------------------------------------------------------------------------------------------------------------------------------------------+
| `:saturation`     | a saturated slot (§2.4) is nil                                                                                                                    |
+-------------------+---------------------------------------------------------------------------------------------------------------------------------------------------+
| `:id-resolves`    | a child id in a `node` or `nodes` slot names no row of the loaded set                                                                             |
+-------------------+---------------------------------------------------------------------------------------------------------------------------------------------------+
| `:acyclic`        | following child ids from a row revisits a row already on its own path                                                                             |
+-------------------+---------------------------------------------------------------------------------------------------------------------------------------------------+
| `:root-reachable` | a row of the loaded set is not reachable from the root row                                                                                        |
+-------------------+---------------------------------------------------------------------------------------------------------------------------------------------------+
The three reference rules are the row counterpart of §7.5's
`:target-bounds`: a row's child slots are references, and they must
resolve within the loaded set and close under the root, as a jump target
must land inside its segment. Acyclicity is also implied by the per-row
address check whenever it runs — a row's id commits to its children's
ids, so a cycle would break the hash (§4.1) — and the rule stands
regardless, so that a load taking ids as given still names the defect.

There is no encoder-domain rule: §4.2's residuals are disclosed value
shapes (scalar metadata, pathological symbols, byte arrays), not a
restricted admission domain, and ordinary identity uses are unblocked.

### 7.5 Vector rules

Defects name a pc. Rules 1–4 of `yin.vm.semantic.md` §2.6 (one segment,
dense pcs, sorted by pc, refs resolve to entities) are satisfied by the
vector form by construction and have no rule here; rules 5 and 6 and the
UCF §7.3.4 checks are translated as follows.

+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+
| Rule             | Defect when                                                                                                                                         |
+==================+=====================================================================================================================================================+
| `:nonempty`      | the vector has no instructions, or the outer sequence is not a `vector?` — the canonical positional form (UCF §7.3.2), never a list                  |
+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+
| `:mnemonic`      | the first element is not a mnemonic of `code/mnemonics` (`code.cljc:12-16`), or the element is not a `vector?` tuple — the same canonical-form rule  |
+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+
| `:arity`         | the tuple's count differs from the mnemonic's arity in `yin.vm.semantic.md` §2.4 as saturated by UCF §7.3.2                                         |
+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+
| `:operand-kind`  | an operand is not of its kind: `:const` and `:store-put` values or `:store-get`/`:store-put` keys fail `plain-data?` (the `key` kind of §2.2), a    |
|                  | a `:var` name is not a symbol, a `:closure` params is not a vector of symbols, a `:ffi-call` op or `:resume` parked id is not a keyword, a `:gensym`  |
|                  | prefix is not a string, a `:call` tail? is not exactly boolean, a `:stream-make` buffer is not a non-negative integer                                |
+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+
| `:saturation`    | a saturated operand (`:gensym` prefix, `:stream-make` buffer, `:call` tail?, `:ffi-call` argc) is nil                                               |
+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+
| `:target-bounds` | a `:jump`/`:branch-false` target or a `:closure` body is not an integer in `[0, length)`                                                            |
+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+
| `:terminator`    | the last instruction is not in `code/terminators` (`code.cljc:19-21`)                                                                               |
+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+
| `:argc`          | a `:call`/`:ffi-call` argc is not a non-negative integer                                                                                            |
+------------------+-----------------------------------------------------------------------------------------------------------------------------------------------------+

There is no vector-level binding rule for `:var`: nesting is a property of the AST. `resolve-var`'s retained fallthrough (env → store → primitives → modules, §4.5, §7.7.2) throws if nothing resolves (`engine.cljc:46-65`) — a `:var` name is validated only as a symbol here (§7.5's `:operand-kind`), never checked for boundedness at this level.

The address check (the vector hashes to the address it claims) is UCF
§7.3.4's and runs before these rules whenever an address is claimed; a
correct hash is never structural validation. The failure outcome of both
checkers on the UCF lowering path is `:yin.k/undecodable` naming the pc or
path, as §7.3.4 already specifies; on a local load it is a load error
naming the same.

### 7.6 The hot loop never queries

The walker dispatches on `(:type node)` and reads named fields of the map
AST it reconstructed at load; the semantic VM runs `(aget code pc)` over
an image decoded once at load (`yin.vm.semantic.md` §3.1). Neither touches
a relation, a ref, an address
index, or a projection while stepping. Projection and query are load-time
and tooling-time operations. This is the rule that keeps
`yin.vm.semantic.md` §6's cost model intact under this design: queryability
is paid once at load, or by whoever asks, never per step.

### 7.7 Dependency closure is Datalog over the tuples

UCF §7.6.1 computes `:yin.k/requires` by a conservative fixed point over
values, code, names, and modules. The **code part** of that fixed point is
the queries below, run over the flat rows of every reachable tree
and the segment-qualified rows (§6.2) of every reachable segment. The
value, name-satisfaction, and module parts remain the walk §7.6.1
specifies; these queries supply their inputs and do not replace them.

Extraction over trees (`$ast` is the union of flat rows of the reachable
trees):

```clojure
;; Free-name extraction is a query, not a tag match (§4.5): a :variable
;; occurrence's name is a free-name obligation unless it is bound by an
;; enclosing :lambda on ITS OWN occurrence path. This must be an
;; occurrence-relation query (§2.5's [origin root-address path]), not a
;; row-only one: content-addressed sharing means one :variable row can
;; have several occurrences, and a row-only rule that walks row parent
;; edges instead of per-occurrence paths can find a binder through one
;; occurrence's edge and wrongly call every occurrence of that row bound
;; — undercounting a real dependency, which UCF §7.6.1's conservative
;; fixed point must never do (§4.5). See `test/dao/space/query_test.cljc`'s
;; occurrence-aware rule set for the verified technique (not yet
;; root-scoped as written — §4.5 — a production query must additionally
;; constrain every rule to one root-address, since an unscoped rule set
;; can otherwise resolve a free name in one tree against a binder in a
;; different reachable tree); the shape here is illustrative, not the
;; exact syntax.
[:find ?name :in $ast $occ % ?root
 :where [$occ ?root ?path ?v] [?v :variable ?name] (not (bound? ?root ?path ?name))]

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
;; likewise over segment-qualified rows: derive free names from the
;; instruction vector's own scoping, no separate opcode needed. Unlike
;; AST rows, instructions are positionally addressed within one segment
;; (segment-address, pc), not content-shared sub-vector by sub-vector, so
;; the mixed-occurrence risk §4.5 documents for AST rows may not apply
;; here the same way — this is unverified. `linearize.cljc/lower` exists
;; today (`:162`) but takes `[e a v t m]` datoms, not the row sets this
;; design specifies (§9.1: lower must be adapted to read the row relation
;; instead); no test exercises this query against the row-set-based
;; lowering, so confirm this reasoning when that adaptation lands rather
;; than assuming it.
[:find ?name :in $code % :where [$code _ _ :var ?name] (not (bound? ?name))]
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
| `:literal`,   | `:const`, `:var`,      | `#{}` from syntax; | —                                                                                                        |
| `:variable`,  | `:closure`, `:push`,   | a call's effects   |                                                                                                          |
| `:lambda`,    | `:call`, `:return`,    | are its callee's   |                                                                                                          |
| `:application | `:jump`,               | profile effects    |                                                                                                          |
| `, `:if`      | `:branch-false`,       | (§7.7.2)           |                                                                                                          |
|               | `:halt`                |                    |                                                                                                          |
+---------------+------------------------+--------------------+----------------------------------------------------------------------------------------------------------+

Every tag and every mnemonic has a row, so "no external effect" is an
explicit `#{}`, never an absence. `:variable`/`:var`'s "Other requirement"
column is blank here because this table is unconditional per tag, and a
`:variable` row's contribution to the name obligation is conditional on
whether it is free (§4.5, §7.7.2) — that determination is a query over
`$ast`/`$code`, not a per-tag footprint fact, and is computed by the
extraction queries in §7.7 rather than this table. The conformance
obligation: for every corpus tree, the requirement set computed from the
tree and the one computed from its lowered segment are **equal** in every
field of `:yin.k/requires`. A tag or mnemonic outside the table is
`:yin.k/undecodable`, the same outcome the validators give it.

#### 7.7.2 Name obligations are resolved per store slice, and completion is conservative

A name extracted from code is an **obligation** when it is a **free**
`:variable` row's `name` — free per the query of §4.5, not per a
separate tag. This design preserves named variables across the Semantic
Tuple boundary, and keeps a single resolution path: the evaluator looks up
every `:variable` name through `resolve-var`'s existing precedence, env →
store → primitives → modules (`engine.cljc:46-58`, `ast_walker.cljc:361-363`,
`semantic.cljc:256-259`) — this is already what today's code does, and
this design does not change it. What changes is only how the obligation
set is computed: not by scanning for a distinct tag, but by the §4.5/§7.7
query over which `:variable` names are free.

**Under-arity calls leave missing parameters bound to `nil`, not unbound.**
An under-arity call leaves missing parameter names bound to `nil`; an
over-arity call drops extra arguments beyond the params list length. This
was a deliberate execution-contract change from the prior `zipmap`-based
binding, which left a missing parameter name absent from the extended
environment rather than explicitly `nil` — so it could fall through to
whatever the closure's own captured environment (or, transitively, the
store/primitives/modules chain) had under that name. Implemented as a
single shared helper, `yin.vm.engine/bind-params`, called from every
closure-application site in both v2 evaluators
(`yin/vm/ast_walker.cljc:192,513,555`, `yin/vm/semantic.cljc:200`);
the v1 ast-walker this rule originally also described no longer exists,
deleted by `yin.vm.v1-retirement.implementation-plan.md`. A missing
parameter is `nil` and shadows any such fallthrough within the closure's
body. This is independent of §4.5's free/bound decision: it is about
parameter binding at closure application, not about resolving a name to
a value.

Every retained obligation is a required primitive or module export, checked by
profile (UCF §7.5.2), and the **effects of a callable are read from its
profile** (`:yin.k/effects`), never inferred from the name: `yin/def`
contributes `:vm/store-put` and `require` contributes `:module/require`
because their profiles say so. Where a retained name has no profile at the
emitter, discovery is `:incomplete`; conservatively retaining every
possible primitive and module requirement is always admissible and never
makes a `:complete` result wrong.

This rule is the amendment UCF §7.6.1's *Names* paragraph needs: its
sentence that a name "is satisfied if it is bound in any environment the
value carries" is the unsound test, and this document states the
correct rule in its place; UCF must be amended to say the same
(§10.8).

#### 7.7.3 The fixed point converges over work items, not addresses

The unit of analysis is a **work item** `[code-address context]`, not an
address. Two closures sharing one segment address with different captured
environments are two work items, because each enters the segment with its
own environment (`semantic.cljc:199-205`) and so reaches different values (closures, streams, cursors, parked records), which
contribute different work items; the name obligations of the segment are the same for both. The **context** of a work item is a finite abstraction: the
closure's captured environment, and nothing else. This context
contributes values (such as closures or streams bound within it), not
name discharge. Because values are finite, the set of contexts is finite
and the iteration terminates.

- **Code once, contexts each.** Code is fetched, validated, and projected
  once per address; every newly discovered `[address context]` pair is
  analyzed, whether or not the address was seen before.
- **Parked ids.** Each extracted parked id must name a record in the
  carried `:parked` slice (UCF §7.6.3). A missing pid referenced as a
  code operand is `:yin.k/unsatisfied` naming the id (an unsupplied
  requirement). A missing pid referenced by an active value (e.g., a
  captured parked continuation) refuses the lift (`:yin.k/non-portable`,
  `:foreign-parked-ref`), as it represents an unresolvable graph cycle.
- **Values.** Closures, streams, and cursors reachable from the frame, the
  K frames, parked records, the store slice, and the pending wait
  contribute work items and stream identities as §7.6.1 specifies; a
  closure contributes `[its segment address, its captured-env context]`.
- **Modules.** A required module with an undeclared footprint sets
  `:yin.k/discovery :incomplete`, as §7.6.1 already rules.

**Convergence** is reached when one full pass adds nothing to any of:
the work-item set, the obligation set, the discovered
value set (closures, streams, cursors, parked records), the store-key
requirement, the FFI-op requirement, the effect set after normalization
(§7.7.1), and the callable and module footprints. A pass that adds a new
context, a new profile fact, or a new footprint continues the iteration
even when no new address appeared.

Discovery is `:complete` only when every reachable work item was
analyzed, every free-`:variable` obligation was discharged by the store slice by key or retained
as a profiled primitive or module requirement, every parked id resolved, and every module
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
 [:db/add ev :yin.ledger/function :yin.vm/lower]
 [:db/add ev :yin.ledger/profile  {:yin.lower/profile "ast-to-bytecode" :yin.code/contract "v2" :yin.k/version 0}]
 [:db/add ev :yin.ledger/record   record-addr]]     ; ev is a tempid; m defaults to :db/assert
```

The profile is carried as the structured map, exactly as it appears in the
record. `local-datom?` constrains `e`, `a`, `t`, and `m` and places no
restriction on `v` (`datom.cljc:42-58`), and today's schema already
carries a vector in `v`; no string
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
  (`src/cljc/dao/stream/memory_log.cljc:105-110`), so two constructions
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
  already mints its stream identity, `src/cljc/dao/stream/ringbuffer.cljc:27`).
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
{:yin/batch         [<rows-0> <rows-1> … <rows-n>]   ; canonical row sets, one per admitted root or disconnected subgraph
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
  appears in the batch's trees, connected or not. Initial harvest
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
  graph into the batch's trees produces several `[j path]` occurrences of
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
  `(yin/def <literal sym> <lambda>)`, i.e., its reconstructed semantic map must have an `:operator` that is a `:variable` node naming `yin/def` (free at that site, per §4.5), its first operand a `:literal` symbol node, and its second operand a `:lambda` node. A
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

### 9.1 The boundary, not a sweep

The walker stays on map ASTs exactly as built, retaining named variable evaluation. What changes is the
boundary around them — the projection in, the loader and the readers of
rows behind it. The semantics do not change; the boundary does.

+--------------------------------------------------+---------------------------------------------------------------------------------------------------------------------+
| Site                                             | Change                                                                                                              |
+==================================================+=====================================================================================================================+
| walker, `ast_walker.cljc`                        | Retains named variable evaluation (`:name`). `:lambda` arm binds by name. Does not use De Bruijn numbering. `:variable` arm (`:361-363`) is unchanged — still resolves through `resolve-var`'s env → store → primitives → modules fallthrough (§4.5). Closure application (`:191`, hot-path copies at `:511`/`:553`) changes from `(zipmap params args)` to nil-filling missing params. Adds `:stream/close` arm (§3.2).                                                                                                      |
+--------------------------------------------------+---------------------------------------------------------------------------------------------------------------------+
| loader, new                                      | validate rows (§7.4), reconstruct the map AST: `rows → map` (§6.1), the successor of `datoms->ast`                  |
|                                                  | (`v2.cljc:494-559`) with content addresses in place of allocated ids                                                |
+--------------------------------------------------+---------------------------------------------------------------------------------------------------------------------+
| linearizer,                                      | reads the row relation through the same `get-attr`-over-an-index shape it has today (`lower-node`'s `(get-attr e    |
| `linearize.cljc:87-148`,                         | :yin/type)` becomes a lookup by row id); `ast-children` becomes a table lookup of `node`/`nodes` slot positions;    |
| `:230-242`                                       | `lower` takes a row set and returns a vector plus the §5.3 provenance table; emits `:var name` and `:closure params body`; `lower-ast` (`:245-252`) goes away     |
+--------------------------------------------------+---------------------------------------------------------------------------------------------------------------------+
| codec, `v2.cljc:372-559`                         | becomes `yin.vm/ast->semantic-bytecode`, the projection pair of §6.5 mapping Universal AST to Canonical Rows/Datoms.            |
|                                                  | `:yin/root` and `:eid` removed; `:yin/address` added; retains `:yin/params` on lambda and `:yin/name` on variable |
+--------------------------------------------------+---------------------------------------------------------------------------------------------------------------------+
| `code/mnemonics`, `code/well-formed?`,           | `code/mnemonics` (`code.cljc:12-16`) is unchanged (§4.5). `well-formed?` keeps judging datom batches on the projection path before projection; the shared vector validator of §7.5 runs on   |
| `code.cljc:12-180`                               | both paths after it                                                                                                 |
+--------------------------------------------------+---------------------------------------------------------------------------------------------------------------------+
| `semantic/load-image`,                           | decodes from the vector directly on the primary path; frame binding for `:closure` matches arguments to `params` by position, binding each to its name (§7.7.2); apply (`semantic.cljc:200`) changes from `zipmap` to nil-filling missing params; `:var` opcode is unchanged, resolving env → store → primitives → modules (§4.5); decodes the new operand (`:closure params body`); both paths call the §7.5 validator   |
| `semantic.cljc:524-596`                          |                                                                                                                     |
+--------------------------------------------------+---------------------------------------------------------------------------------------------------------------------+
| frontends                                        | keep emitting map ASTs to the stream (as named universal ASTs). They do not perform the tuple projection. |
| `src/cljc/yang/clojure.cljc`,                    | All projection to Semantic Tuples and side-tables is deferred to the Encoder Observer (`yin.vm/ast->semantic-bytecode`).        |
| `python.cljc`, `php.cljc`                        |                                                                                                                     |
|                                                  | never survive the boundary. The r7 adapter's ordering rules become the standing contract: first reject any `:macro? |
|                                                  | true` lambda that is not the value operand of a `yin/def` as `:stray-macro-lambda`, then turn each admitted one into|
|                                                  | a declaration at its tree index and path, then strip the flag                                                       |
+--------------------------------------------------+---------------------------------------------------------------------------------------------------------------------+
| datom-batch adapter,                             | for media that carry datoms: derives `:yin/harvest` from entity order and entity identity while inlining, recording |
| standing                                         | every `[j path]` at which each definition entity is emitted (the `datoms->ast` recursion, `v2.cljc:494-559`,        |
|                                                  | extended to carry the path and the entity id, since today it tracks neither); one entity with several paths becomes |
|                                                  | one occurrence group; the adapter assigns no `:decl` values (§8.5)                                                  |
+--------------------------------------------------+---------------------------------------------------------------------------------------------------------------------+

The v1 lineage (`src/cljc/yin/vm.cljc`, `test/yin/vm/ast_walker_test.cljc`,
`test/yin/vm/ast_conversion_test.cljc`, `runtime_regression_test.cljc`) is
outside this design; it stays on its own map path until it is retired.

### 9.2 The observer lane: an integration item to scope, not a solved detail

Program input reaches an evaluator through
`dao.stream.observer/run-on-stream` (`src/cljc/dao/stream/observer.cljc:217-247`),
which is shape-agnostic: it hands each observed batch to a
composition-supplied `load`. The per-evaluator loaders are chosen in
`yin.repl.core/program-loaders` (`repl/v2/core.cljc:63-68`) and today
accept datom batches; the REPL's own eval path still converts a
semantic-VM AST through `ast->datoms` before it travels
(`repl/v2/core.cljc:444-449`). Two things about the row lane are
**unverified**:

- **The batch shape.** Whether one batch is one tree's row set, one
  `{:yin/code :yin/declarations}` value (§8.5), one vector, or a
  collection of them is
  a medium contract this design has not exercised end to end; §7.1 assumes
  one canonical value per batch. The shape must be fixed with the REPL
  shell and the `yin.vm.stream-observer` tests before the loaders are
  switched.
- **The program-input predicate.** `engine/executable-program-datom?`
  (`engine.cljc:511-513`) decides what counts as program input by testing
  the attribute namespace of an `[e a v t m]` row, and
  `append-program-datoms` (`engine.cljc:590-612`) builds on it. A row
  batch has no attribute slot. A repository search on 2026-09-14 found no
  caller of the v2 `append-program-datoms` beyond its own overload, so a
  row counterpart is not presumed necessary; whether any live
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

1. **The pinned canonical byte encoding in `dao.jing`** — declared and
   inherited, still open, but **no longer blocking ordinary identity and
   deduplication use** (§4.2). The three structural conformance pairs
   this item used to name as blocking (vector/list, vector/seq,
   collection metadata) are closed by `dao.jing`'s committed encoder fix
   — list and seq intentionally remain one address, since `=` calls them
   equal. What
   remains is the transitional hand printer's disclosed residuals
   (scalar metadata, pathological symbols, byte-array identity hashing,
   ambient print-var bindings on scalars — `dao.jing.md`'s Open Items)
   and the eventual pinned byte encoding itself, neither of which this
   design closes.
2. **The store-key domain is the `data` domain** (§2.2): every validator,
   extraction query, and store-slice encoding must use that one
   definition. This document now states it uniformly; the implementation
   phase must not reintroduce a narrower one, and the corpus must contain
   a numeric key beside the keyword one.
3. **Complete occurrence identity** (§2.5, §5.3, §8.4). Source origins
   `[:source medium batch j]` are supplied to lowering by the D4 envelope
   and recorded in instruction provenance: `medium` is the program stream's
   logical identity, `batch` is the composition-minted token, and `j` is the
   member index. Initial and
   nested expansion events must carry both the declared-ref parent link
   and the parent record address, and the record must be minted after
   the parent's. The expander incarnation (§8.4.1) is a composition-minted
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
   expander itself does not exist yet (`src/cljc/yin/vm/` has no
   `macro.cljc`) and is not a blocker for tuple evaluation without
   macros; §8.4's event shape is specified against `yin.vm.macro.md`, not
   against code.
5. **Conservative dependency completion** (§7.7.2, §7.7.3) — design
   work with stated mechanisms. Free-name discharge requires searching the
   store slice by key or resolving via primitive/module profiles (§7.7.2);
   convergence is over work items and all dependency facts, with the
   captured-environment context abstraction for values. Neither analysis exists in code;
   until they do, an emitter may only report `:incomplete`. Extracting the
   free-name set itself must use an occurrence-joined query, not a
   row-only one, to stay conservative when a name is free at one
   occurrence and bound at another (§4.5).
6. **Effect normalization** (§7.7.1): the footprint table must be
   published with the execution-contract stamp, and the tree/segment
   requirement-set equality must be tested over the corpus. FFI ops are a
   receiver capability requirement, never a store-slice requirement.
7. **The lowering profile** (§5.2.1) — **published 2026-09-18**. §5.2.1's
   table pins `"ast-to-bytecode"` exactly as `linearize/flatten-program`
   already behaves, per D1 of `yin.vm.code-as-tuples.implementation-plan.md`
   (published by copying, not by designing). A derivation record can now
   be verified in full, not only its content integrity (§5.2.2).
8. **UCF §7.6.1 needs two amendments** (§7.7.2, §7.7.3): its
   any-carried-environment satisfaction sentence is unsound and must be
   replaced by the store-slice-by-key / profile rule of §7.7.2 (an environment never
   discharges a name), and its address-per-segment fixed
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
    `yin.vm/primitives` (`v2.cljc:100-132`) is published with profiles,
    dependency closure reports names and `:incomplete`, not satisfiable
    bindings.
12. **Per-lambda subterm addressing** — dissolved by the flat-row form
    (§4.3): every node is addressed by construction, and the remaining
    one-segment-per-lambda lowering question is a §5.2.1 profile concern.
13. **The round-trip law** (§7.2, part 1) — a first-class conformance
    obligation, not a blocker: for every corpus program, `map → rows →
    map` is the identity on the canonical map AST and `rows → map →
    rows` is the identity on the rows, and both directions must be
    tested over the corpus before any row set is treated as canonical.
    The corpus already lives as map ASTs in the test tree, so the test
    is writable now.
14. **`yin.vm.semantic.md` Instruction Grammar Amendment** (§5.1) —
    **done 2026-09-18**, alongside item 7. `yin.vm.semantic.md` revision 1
    adds the argument-to-named-parameter binding rule of §7.7.2 to §4.1
    (correcting two stale `zipmap` references to the real
    `engine/bind-params` nil-fill behavior), and publishes the `"v2"`
    execution contract's revision history in §2.4 per
    `yin.vm.universal-continuation-format.md` §7.11's contract-revision-
    publication blocker. Along the way, four documentation drifts the
    2026-09-17 sweep found are corrected: §2.6 was missing the
    `:instruction-shape` well-formedness rule (`well-formed?` runs seven
    rules, the doc had six) and did not state the ref-required-ness of
    `:jump`/`:branch-false`/`:closure`; the decoder's `"id"` gensym-prefix
    and stream-make capacity defaults were undocumented; the
    `:current-continuation → :current-cont` opcode alias was missing from
    §2.4's mapping list; and the `:call` row's apply-rule citation pointed
    at §4.3 (commentary) instead of §4.2 (the actual transition equations).
