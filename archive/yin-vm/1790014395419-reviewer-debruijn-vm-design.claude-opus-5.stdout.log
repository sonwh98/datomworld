Completed-GMT: 2026-09-21 18:16:59 GMT
Completed-Local: 2026-09-22 01:16:59 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: bbeae799-313d-4447-8264-714c09a5f1ca

**NOT READY**

The direction is sound: a separate dimension, a sibling VM, and a differential backbone. The core problem is the equivalence contract in §1. As written, it cannot be met by any VM that runs the *projected records*. The projection deliberately erases three kinds of information the named path uses at run time: how a literal is spelled, the tail flags the front end chose, and parameter names. A fourth problem is that the named path's environment tier carries leftover state from earlier runs. Every one of the P1s below comes from one of those four.

## P1: the design is wrong, or the two VMs would diverge

**P1-1. Canonical spelling changes literal values (§1 item 1, §2 caching, §3.1).**
- Quoted: "Values and errors are equal under the existing semantic comparison" and "the projected code can be cached by the projection fingerprint".
- `mint-record` stores every scalar through `canonical-value` (`src/cljc/yin/vm/debruijn.cljc:1050`, `:955-997`):
  - An integral double becomes a `long` (`1.0` becomes `1`).
  - Strings, keyword parts and symbol parts are NFC-normalized.
  - The same applies to `:free` names, `:key`, `:prefix` and `:op`.
- The projection documents these as intended identity collisions. For execution they are wrong answers.
- Failing programs:
  - `(/ 1.0 2)`: named JVM gives `0.5`; de Bruijn gives `(/ 1 2)`, which is `1/2`.
  - `(count "e\u0301")`: named gives 2; de Bruijn gives 1.
  - A free symbol or store key spelled in non-NFC form: named resolves it; de Bruijn either fails with "Unable to resolve symbol" or reads a different store slot.
- Because `(f 1)` and `(f 1.0)` share one fingerprint, a cache keyed by fingerprint alone serves one image for two different programs. A literal side table cannot fix this.
- Separately, anything outside the canonical domain cannot be projected at all, but the named path runs it: ratios, bigints, chars, records, unsafe JS integers.
- Smallest fix:
  - Restrict the contract to programs whose literals, names and keys already equal their canonical spelling.
  - The lowerer rejects any other program up front (compare each named value to its canonical spelling) as a diagnostic.
  - Drop "cached by the projection fingerprint", or key the cache on something that separates spellings.
  - List this as an owner decision, because the alternative is changing the projection, which §9 forbids.

**P1-2. Recomputed tail position is not the named path's tail position (§1 items 3-4, §3.1).**
- Quoted: "Tail position is recomputed from syntax"; "Tail calls have the same termination and continuation behaviour"; park/resume "preserve the same local machine state".
- In the named path, `:tail?` is chosen by the front end. The linearizer only copies it (`src/cljc/yin/vm/linearize.cljc:99`).
- The front ends are more conservative than the syntactic rule:
  - `yang/clojure.cljc` `compile-let` (around line 247) gives the let-lambda body the *let's* tail flag, not `true`.
  - `yang/python.cljc` `compile-suite` (lines 355-360) does the same for statement sequencing.
  - Hand-built test ASTs usually carry no `:tail?` at all.
- Failing program: `(do (let [x 1] (g x)) 2)` where `g` executes `(park)`.
  - Named: `(g x)` is a non-tail call, so the parked `:k` holds two frames.
  - De Bruijn: `(g x)` is a lambda body, so it is a tail call and `:k` holds one frame.
  - The resumed value is the same, but the parked record, a `current-continuation` value, and the `yin.vm.completion` frame facts all differ.
- The projection hashes tail and non-tail uses as one node, so the flag cannot be recovered.
- Smallest fix: weaken items 3 and 4 to "the same result after resume, and bounded continuation growth wherever the named path is bounded". State plainly that the de Bruijn path applies proper tail calls at every syntactic tail position, and that the shape of `k` is excluded from comparison.

**P1-3. The named "environment tier" is the VM's live `:env` register, which leaks between runs (§1 item 6, §4.1).**
- Quoted: "Free names resolve in the existing order: environment, store, primitives, then module registry."
- In `semantic.cljc`, the `:return` on an empty continuation and the `:park` path both write the *callee's* merged environment into `:env` (lines 285-293 and 317; `put-registers`).
- `vm-load-program` does not reset `:env` (lines 679-690). Only `vm-eval`/`eval` restores it (`restore-initial-env`, line 779); `vm/run` does not, and neither does `ffi/maybe-run`.
- Failing sequence on one VM driven by `run` (the observer composition):
  1. Program 1 is `((fn [x] x) 5)`. The Clojure `compile-program` marks the root as a tail call, so after it halts, `:env` holds `x = 5`.
  2. Program 2 is `x`. Named returns 5; de Bruijn resolves through the initial free environment and then the store, and errors.
- The same leak happens after a park inside a closure.
- Smallest fix: define `free-env` as the VM's *initial* environment, fixed for the VM's lifetime. Record the named-path leak as a named-VM defect that must be fixed, or explicitly excluded, before B5 parity. Say whether a closure captures `free-env`. That matters if a host rebinds `:env` between programs, because named closures keep the environment from when they were created.

**P1-4. There is no "existing semantic comparison" that closures, continuations and parked values can pass (§1 item 1, §7).**
- The only comparison in the codebase is `parity-test/normalize` (`test/yin/vm/parity_test.cljc:121-133`). It compares closures by `:params`.
- De Bruijn closures have only an arity; the parameter names are gone.
- Named reified continuations and parked records embed name-keyed `:env`, `:segment` and `:pc` (`semantic.cljc:309-315` and `engine/park-continuation`). A park halts with that record as the VM's `:value`.
- Failing programs: `(fn [a] a)` returned as a result; `(park)` at the root. Neither can be equal under any existing comparison.
- Smallest fix: B0 defines a normalizer as part of the contract:
  - a closure compares as `{:type :closure :arity n}`;
  - continuations and parked records compare by type and id only;
  - stream and cursor refs compare by id;
  - the store is normalized so host handles do not appear;
  - telemetry is excluded.

**P1-5. Scope validation can be bypassed at the loader (§5 vs §2 and B1/B5).**
- Quoted: "Scope validation belongs at the lowering boundary"; "The loader repeats cheap operand-shape checks"; "never a host index exception".
- B1 defines a content-addressed image that can be loaded without B2, from a cache or the B5 persistence adapter.
- A shape-valid image containing `[:load-bound [5 0]]` in a body with one frame passes the loader, then either throws a host `nth` exception or reads `nil`.
- Smallest fix, either of:
  - The image validator checks scope statically. Each body carries its arity and its enclosing body (unique while occurrences are duplicated), and the validator walks that chain.
  - Or `:load-bound` checks the range at run time and raises `:invalid-scope`.
- B1's completion criteria must include a hand-built out-of-range image being rejected.

## P2: significant gaps, or false or unverifiable claims about existing code

1. **§4.1: "pushes a new frame made by `bind-params`"** is false. `engine/bind-params` (`engine.cljc:46-51`) takes parameter *names* and returns a name-keyed map. A projected lambda has only an arity. A new positional helper is needed: `(vec (take n (concat args (repeat nil))))`.
2. **§3.1: "the stream application forms that the named linearizer marks as tail calls"** is false. Only `:application` emits `:yin.code/tail?` (`linearize.cljc:97-100`). `:dao.stream.apply/call` lowers to `:ffi-call`, which has no tail operand (`linearize.cljc:110-116`; the `code.cljc:222` operand table). The matching owner decision in §8 has nothing to decide.
3. **§2: "the named linearizer's operations with the lexical operations made explicit"** is false.
   - The design renames and restructures the instructions:
     - `:const` becomes `:literal`;
     - `:branch-false target` becomes `:branch then else`;
     - `:push` and `:halt` are dropped;
     - gensym, store, stream, park and resume are folded into generic `:primitive`, `:store-op`, `:stream-op` and `:continuation`.
   - It adds a `:primitive name operands` that has no named counterpart; primitives are `:var` plus `:call`.
   - Without `:push`, the operand-stack discipline promised in §4.2 is unspecified.
   - Fix: mirror `code/vector-operand-table` exactly, replacing only `:var` with `:load-bound`/`:load-free` and dropping params from `:closure`.
4. **§7.1: "`yin.vm.completion` computes a conservative closure … supplies closure facts"** is true only for the *named* VM shape. `abstract-value` and `context-of` (`completion.cljc:74-125`) read `:segment`, `:entry`, name-keyed `:env` and `:k` frames, and `code-facts` reads `:yin.code` vectors. None of that applies to de Bruijn state without a new adapter. Say so.
5. **§7.1: "`load-vector` records a loaded vector's `:yin.code/hash` alias"** is imprecise. `load-vector` records `(jing/segment-key v)` in `:code-aliases` (`semantic.cljc:731-743`). `:yin.code/hash` is the datom-batch attribute that `load-image` checks.
6. **B5: "Run every existing semantic, engine, parity, linearize, and VM fixture through both executable paths"** cannot be done as stated.
   - Linearize and engine fixtures test lowering output and helper functions, not execution.
   - Most `semantic_test` fixtures build `:yin.code/*` batches directly, with no AST to project.
   - Fix: name the actual corpus: `parity-test/corpus` plus the every-tag corpus in `content_test` and `completion_test`. Name the pipeline too: `ast->datoms` → `project-datoms` → lowerer → VM, against `linearize/lower` → semantic VM.
7. **§1: "Values and errors are equal"** does not say how errors are compared. Error ex-data carries values that differ between the two paths: the `:fn` of "Cannot apply non-function" can be a closure, and a park record carries env maps. Compare the message plus a normalized ex-data.
8. **§4.2: "reuses `yin.vm.engine` … helpers"**: the reuse is not safe everywhere without checking.
   - Telemetry snapshots (`handle-effect`, `park-continuation`) serialize VM fields and will differ.
   - `response-wait-entry` and `call-park-entries` are private to `semantic.cljc` and have the named register shape.
   - Say which helpers are reused as-is and which are re-implemented.
9. **§3.2: "a generic shared code block would need a separately specified closure or parameter convention"** is partly wrong. De Bruijn code is position-independent with respect to the environment. A shared *lambda body* can be emitted once per hash, because the closure captures frames at run time, so no new convention is needed. Only inline, non-lambda sharing needs a jump-and-link convention. Duplicating occurrences stays safe. Its output size equals the named linearizer's, which also expands every occurrence (`linearize.cljc:79-150`), so there is no blow-up beyond the named path. Say that explicitly.
10. **§3.2 memo key `[node-hash, lexical-context, tail-context]`**: `lexical-context` is undefined here, because names are gone. It should be the vector of frame arities. Scope validity depends on those arities, and tail context changes the output, so both must be in the key. That is correct, but define it.

## P3: wording, form, minor

- §6 and §8 hold operational routing ("refreshed Claude pool", "Gemini or agy capacity", "GPT architect for signoff"). This will go stale in a design document; move it to the orchestrator log.
- §2 and B1 should state that the code-image encoder reuses `yin.vm.debruijn/encode-value`, the descriptor-hash pattern and `jing/sha256`, and does not write a second canonical encoder.
- `:closure arity body-ref` silently drops `:macro?`. State that the run-time closure ignores it, as the named `:closure` does.
- §4.2 should say how `vm/IVMState` `environment` answers: the free environment, or frames.
- §4.1 "Frame zero is the innermost frame" should say how frames are laid out in the vector (for example, innermost last and read from the end).
- B0 lists `docs/design/yin.vm.debruijn-vm.md` as "New", but the file already exists (untracked).
- §5: the projected reader already enforces the `[:tuple :int64 :int64]` shape (`slot-shape-ok?`). Say that B2 adds the non-negativity and range checks on top of it.

## What I checked and found clean

- **Duplicate parameters:** `(fn [x x] x)` under exact, under- and over-arity calls. The positional frame `[a nil]` read at `[0 1]` matches the rightmost-wins map from `bind-params`.
- **Nil versus absent:** a nil-filled parameter is present in the named map (`find` on `env`) and in range in the frame, so neither falls through to the store.
- **Store collisions:** a store key that equals a bound name is shadowed in both paths. `store-get`/`store-put` keys only meet variable resolution through free names, and do so in the same order.
- **Closure environments:** the named merged environment is exactly "outer bound names plus the initial environment", so for the same program, positional frames plus an initial `free-env` are equivalent (apart from P1-3).
- **Unexpanded macros** are rejected upstream by `check-unexpanded`.
- **gensym, park and stream ids** come from one shared `:id-counter`, so reusing the engine helpers keeps the ids the same.
- **The shared-DAG blow-up** is bounded by the named linearizer's own expansion (P2-9).
- **§7.1** is consistent with the axioms: the linker is a process between streams, holds no callbacks, and failure is a stream outcome. Nothing in B0-B5 rules it out, apart from the fingerprint-keyed cache in P1-1, which a linker must not inherit.
- **Referenced files** exist: `yin.vm.jit.md`, `content.cljc`, `completion.cljc`, and the UCF doc, whose status is "Proposed / Deferred" as the design says.
- **Form:** every line is 80 columns or less, there are no em dashes and no pipe tables, and the sections, invariants, phases, completion criteria and test matrix are all present.

I did not edit any file.
