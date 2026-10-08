Completed-GMT: 2026-09-21 18:32:52 GMT
Completed-Local: 2026-09-22 01:32:52 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: bbeae799-313d-4447-8264-714c09a5f1ca

**NOT READY**

Architecture B fixes the real cause of my first review: lowering from the lossy projection. Most of the 22 findings are genuinely fixed in the text, not just marked "accepted". Three new problems block it:

- The code-image hash reuses the projection's canonicalizing encoder, so the old spelling collision comes back inside the cache key.
- A memo keyed on frame arities without binder names binds variables wrongly on a named graph.
- The invertibility invariant, which is B0's acceptance test, applies to an artifact that is never defined, and it cannot hold for the `:yin/tail?` facts the encoder deliberately drops.

## Confirmation of my 22 first-round findings

```
+-------+------------------------------+----------------+-----------------------------------------------+
| ID    | Finding                      | Status         | Evidence in the revised doc                   |
+-------+------------------------------+----------------+-----------------------------------------------+
| P1-1  | canonical spelling changes   | PARTLY FIXED   | s1/s2 "Exact named literal spelling is carried|
|       | literals; fingerprint cache  |                | ... not replaced by the projection's canonical|
|       |                              |                | value"; "it is not the executable cache key". |
|       |                              |                | But s2 "reuses yin.vm.debruijn/encode-value"  |
|       |                              |                | for the image hash: see new P1-A.             |
| P1-2  | syntactic tail recompute     | FIXED          | s3 "copies the front end's :yin/tail? exactly |
|       |                              |                | as the named linearizer does. It does not     |
|       |                              |                | infer tail position from syntax."             |
| P1-3  | live :env register leak      | PARTLY FIXED   | s1 "The free environment is the VM's initial  |
|       |                              |                | environment value, fixed for one VM instance";|
|       |                              |                | s5 excludes the leak. The "fresh initial      |
|       |                              |                | environments" escape cannot cover park/resume |
|       |                              |                | across two programs: see new P2-D.            |
| P1-4  | no existing comparison for   | PARTLY FIXED   | s1 "This normalizer is defined and tested in  |
|       | closures/continuations       |                | B0". But "Closures compare by arity and       |
|       |                              |                | normalized captured values" cannot match      |
|       |                              |                | (new P2-C), and "stable identity" of a reified|
|       |                              |                | continuation is undefined. Still owner        |
|       |                              |                | decision 2.                                   |
| P1-5  | loader scope bypass          | FIXED          | s3 item 2 "B1's image validator repeats the   |
|       |                              |                | check ... [:load-bound [5 0]] is rejected     |
|       |                              |                | before execution"; also in B1's completion.   |
| P2-1  | bind-params is name-keyed    | FIXED          | s4 "(vec (take arity (concat args (repeat     |
|       |                              |                | nil))))"; "named bind-params ... unchanged".  |
| P2-2  | stream-apply tail claim      | FIXED          | s3 ":dao.stream.apply/call lowers to :ffi-call|
|       |                              |                | and has no tail operand."                     |
| P2-3  | op table not the named one   | FIXED          | s2 "mirrors the existing yin.vm.code/vector-  |
|       |                              |                | operand-table; only lexical addressing        |
|       |                              |                | differs"; :const/:push/:halt/:branch-false    |
|       |                              |                | kept.                                         |
| P2-4  | completion is named-shaped   | FIXED          | s7.2 "the completion adapter is not yet a     |
|       |                              |                | de Bruijn adapter."                           |
| P2-5  | load-vector alias wording    | FIXED          | s7.2 "load-vector records the jing/segment-key|
|       |                              |                | in :code-aliases".                            |
| P2-6  | B5 corpus overclaim          | FIXED          | B0/B5 name the parity, content and completion |
|       |                              |                | corpora, "not helper-only tests".             |
| P2-7  | error comparison undefined   | PARTLY FIXED   | s1 "uses the message and normalized ex-data"; |
|       |                              |                | the ex-data normalization itself is still     |
|       |                              |                | unspecified (closure :fn values, park maps).  |
| P2-8  | engine helper reuse          | FIXED          | s4 "It reimplements any helper that serializes|
|       |                              |                | the named register layout".                   |
| P2-9  | shared lambda bodies         | FIXED          | s3 "B2 initially emits occurrence-local       |
|       | (narrowed)                   |                | bodies" (narrowing accepted).                 |
| P2-10 | memo key undefined           | REGRESSED      | s3 "The frame-arity vector is the lexical     |
|       |                              |                | context; names are not used as a hidden key." |
|       |                              |                | Arities were enough over projected records but|
|       |                              |                | are wrong over named datoms: new P1-B.        |
| P3-1  | routing text in the doc      | FIXED          | no model/pool routing text left.              |
| P3-2  | reuse encode-value           | FIXED AS       | done, but harmful under B: new P1-A.          |
|       |                              | WRITTEN        |                                               |
| P3-3  | :macro? dropped silently     | FIXED          | s4 "runtime closure application ignores it".  |
| P3-4  | IVMState environment         | FIXED          | s4 "its environment operation returns the     |
|       |                              |                | fixed free-env value."                        |
| P3-5  | frame vector layout          | FIXED          | s4 "ordered outermost to innermost; frame zero|
|       |                              |                | ... read from the end of the vector."         |
| P3-6  | B0 listed the doc as "New"   | FIXED          | B0 file box lists only the test file.         |
| P3-7  | reader tuple check vs scope  | FIXED          | s3 "tuple-shape check remains useful, but it  |
|       |                              |                | is not a scope check."                        |
+-------+------------------------------+----------------+-----------------------------------------------+
```

## New findings

### P1: the design is wrong, or the two VMs would diverge

**P1-A. The code-image hash reuses the canonicalizing encoder, which brings back the spelling collision (§2).**
- Quoted: "The encoder reuses `yin.vm.debruijn/encode-value` … Exact named literal spelling is carried in an executable spelling slot or side table" and "The code-image identity is the hash of the complete emitted image, descriptor hash, spelling/name table".
- What `encode-value` does (`debruijn.cljc:887-923`):
  - It encodes integral doubles as int64, so `1` and `1.0` get identical bytes.
  - It NFC-normalizes strings, keyword parts and symbol parts.
  - It throws on anything outside the canonical domain: ratios, bigints, chars, records, and unsafe JS integers.
- Failing scenarios:
  - `(/ 1.0 2)` and `(/ 1 2)` produce different images, and the named JVM gives `0.5` versus `1/2`. Hashed through `encode-value`, they get the same image hash, so a cache hit runs the wrong program.
  - `(+ 1/2 1)` runs on the named path, but the encoder throws, so the "lossless" encoding cannot represent it.
- Smallest fix: B1 defines an exact executable scalar encoding:
  - distinct tags for long and double (and for ratio, bigint and char, or an explicit refusal);
  - no NFC on hashed bytes.
- Reuse only the framing and length helpers. Add a B1 completion test that `1` and `1.0`, and composed and decomposed `é`, give distinct image hashes.

**P1-B. A memo keyed on frame arities resolves shared named nodes wrongly (§3).**
- Quoted: "Any memo key must include node identity, the vector of frame arities, and the tail context. The frame-arity vector is the lexical context; names are not used as a hidden key."
- On named input, how a name resolves depends on the binder *names* on the stack, not only the arities.
- Named AST graphs can share an eid: `ast->datoms` `seen-eids` emits a shared `:eid` once.
- Failing program: a variable node `{:eid 7 :type :variable :name x}` is shared under `((fn [x] #7) 1)` and `((fn [y] #7) 2)`.
  - Both occurrences have key `[7 [1] false]`.
  - The second occurrence reuses `[:load-bound [0 0]]` and returns `2`.
  - The named path resolves `x` as free there and errors, or reads the store.
- The projection gets this right: it memoizes on `[eid stack]` with the full vector of parameter names (`debruijn.cljc:742`).
- Smallest fix: the memo key is `[eid, name-stack, tail-context]`, where the name stack is the vector of parameter vectors, exactly as in the projection. Frame arities alone are enough only for the scope-validation check.

**P1-C. The invertibility invariant has no defined subject and fails on existing input (§1, B0, B2).**
- Quoted: "decode-db(encode-db(named-datoms)) = named-datoms modulo emitter-local tempids and row ordering", and "the front-end `:yin/tail?` value where the named linearizer observes it".
- **No subject.** The invariant is never tied to a defined artifact:
  - `encode-db` has no home. B0 adds only a test file; B1 is the image encoder; B2 is the lowerer.
  - It is unclear whether the thing being inverted is a tree-shaped record schema ("Freeze the lossless record schema") or the linear image ("image decode round trips"). Inverting a linear image means recovering `if` from branch-false/jump pairs, applications from push/call runs, and out-of-line bodies. That is a decompiler, and nothing specifies one.
- **Tail flags.** The named linearizer observes `:yin/tail?` only on `:application`, but front ends set it on other nodes too:
  - `compile-program` compiles the root with `tail?` true;
  - `compile-literal nil tail?`, `compile-if`, `compile-lambda` and Python `compile-stmt` all `assoc :tail? true`;
  - the emitter writes `[e :yin/tail? true]` for any node (`vm.cljc:564`).
- Failing program: the Clojure program `42` becomes a literal carrying `:yin/tail? true`. The encoder drops the flag, so decode lacks it and the equality fails.
- **Shared eids.** Per-occurrence expansion turns a named DAG into a tree. Unless the encoding keeps one source reference per occurrence and decode merges on it, decode produces more entities and the equality ("same … semantic references") fails.
- **Undecided fields.** `:yin/macro-name`, which is in the emitter schema at `vm.cljc:465`, and attributes from other namespaces are neither preserved nor declared as normalized.
- Smallest fix:
  - Add an `encode-db`/`decode-db` namespace to B0 (or B1).
  - State the invariant over tree-shaped lossless records, not the linear image.
  - Preserve `:yin/tail?` on every node type.
  - Carry one source eid per occurrence and re-merge on it.
  - Enumerate exactly what is preserved and what is normalized: tempids, `t`/`m`, non-`:yin` namespaces, and `:yin/macro-name`.

### P2: significant gaps, or false or unverifiable claims

**P2-A. The named path's input semantics depend on row order, but the invariant is "modulo row ordering" (§1).**
- `vm/index-datoms` (`vm.cljc:1528-1560`) behaves like this:
  - a repeated fact for the same entity and attribute keeps the *last* value;
  - `:yin/operands` concatenates across several datoms, in batch order;
  - the *last* `:yin/root` fact wins;
  - a batch with no root falls back to a structural heuristic.
- The projection's `index-frame` and `frame-root`, by contrast, throw on a duplicate fact and require exactly one root.
- So "modulo row ordering" is false for batches the named path accepts. And reusing the projection's framing would reject named-valid programs, which contradicts equivalence.
- Fix: restrict the input domain to one fact per entity and attribute, plus exactly one root, and state that. Reuse only `resolve-name`, not `index-frame`/`frame-root`.

**P2-B. "The resolver helpers are reused" is ambiguous, and most of D1 is lossy (§3).**
- Only `resolve-name` (`debruijn.cljc:606`) is public and lossless: it compares symbols with `=`, with no NFC.
- `build-node` and `project-node` are private. They run `validated-scalars` (canonical-domain rejection), `check-unexpanded`, and `mint-record`/`canonical-value`.
- Reusing any of those brings back the lossiness, and `check-unexpanded` would refuse `:macro?` applications that the named linearizer executes.
- Fix: name `resolve-name` as the only reused function. State whether unexpanded-macro rejection applies. If it does, the named path must be run on the same restricted domain for parity.

**P2-C. The closure normalizer cannot match (§1).**
- Quoted: "Closures compare by arity and normalized captured values".
- The named closure's `:env` is the merged name map: it includes the initial environment and drops shadowed bindings. De Bruijn frames keep shadowed slots.
- Failing program: `((fn [x] ((fn [x] (fn [] x)) 2)) 1)`.
  - Named captured values: `{x 2}` plus the initial environment.
  - De Bruijn captured values: frames `[[1] [2]]`.
- Fix: compare closures by `{:type :closure :arity n}` only. If more checking is wanted, compare behaviour by applying both closures to fixture arguments.
- Also define "stable identity" for a reified continuation, which has no id. For example, compare type only.

**P2-D. "Fresh initial environments" cannot test park/resume across two programs (§1, §5, B5).**
- Parking happens in one program and `:vm/resume :parked-N` in a later one, and both must run on the same VM. The named leak (a park inside a closure leaves `:env` holding the callee's environment) then affects any free name the second program reads before it resumes.
- Fix: B5 runs park/resume fixtures only on programs whose free names are also in the initial environment or the store, or it makes fixing the leak a B4 prerequisite. Say which.

**P2-E. Code identity: provenance is both in and out (§2 vs §3).**
- §2 says "The code-image identity is the hash of … provenance table".
- §3 says the node hash and source reference are "excluded from code identity only when explicitly declared diagnostic metadata".
- If provenance, and so source tempids, is in the hash, the same program emitted from different source positions or batches gets different images, which defeats caching for no semantic reason.
- Fix: identity covers opcodes, operands, exact scalars and the name table; provenance is always diagnostic metadata kept outside the hash.

**P2-F. The design duplicates the named linearizer with no justification and no guard against drift (§3, B2).**
- `debruijn_linearize.cljc` repeats the whole traversal in `linearize/flatten-program`: order, labels, out-of-line bodies, tail copying.
- A smaller design avoids that: take `linearize/lower`'s output and rewrite `:var` operands per body using `resolve-name`, and replace `:closure` params with arity. That gives the same order and tail flags by construction.
- If the duplicate is kept, B2 needs a structural test: the de Bruijn opcode sequence equals `lower`'s, apart from `:var` and `:closure` operands, for every corpus program. B5 compares only execution results, so it cannot catch a re-ordering that happens to give equal values.

**P2-G. §7.2 still consumes the lossy projection.**
- Quoted: "it consumes projected code and a value-held name environment".
- Under B, a linker that works on projected records brings back P1-1: canonical literals, and names only in canonical form.
- Fix: the linker consumes lossless images. The projection fingerprint is used only as a lookup index.

### P3: wording, form, minor

- **§7.1 claims I cannot confirm.** Read-only access, no network. The claims are:
  - "with the nearest bound variable at index zero";
  - "evaluation" as a pipeline stage;
  - the directory contents "ANF, MCode, Machine, Serialize, Decompile, and Canonicalizer".
- The owner's verified list covers let-rec minimization, lambda lifting, ANF, an IR with De Bruijn indices as stack positions, and decompilation. The extra claims should be marked UNVERIFIED or cited to a specific line.
- "Decompilation need not preserve exact source datoms, tail flags …" reads as a claim about Unison. Rephrase it as "this design does not assume Unison decompilation preserves …".
- The hedges are good as they stand: "They do not establish that Unison executes its exact stored identity form" and the UNVERIFIED line.
- **Owner decision 1** ("which stable source identity the inverse encoding must preserve") blocks P1-C. Say so explicitly: B0 cannot start until it is decided.

## Checked and found clean

- **Form:** the status line reads "design; not implemented"; sections are numbered; no line is over 80 columns; there are no em dashes, no pipe tables, and no routing text.
- **Scope validation:** placed at both entry points, lowering and the loader, with a hand-built `[5 0]` image in B1's completion criteria.
- **Front-end tail flags:** copied only where the named linearizer reads them, so execution parity on tail calls is achievable.
- **Semantics:** positional `bind-params`, nil fill, dropped extras and rightmost-wins duplicates match `engine/bind-params` plus `resolve-name`.
- **`resolve-name` itself:** it preserves `{:free name}` exactly and does not canonicalize.
- **Free environment:** fixed at the initial environment; `IVMState` `environment` defined; helpers that serialize the named register layout are reimplemented.
- **§7.2's stream framing:** a process between streams, no callbacks, failure as a stream outcome. It is consistent with the axioms apart from P2-G.
- **Phases:** B0-B6 each have a file box, a must-not-change list and completion criteria. The only gap is that `encode-db` has no home (P1-C).

I did not edit any file.
