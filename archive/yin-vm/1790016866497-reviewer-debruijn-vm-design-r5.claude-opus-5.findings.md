The final review is written up below: it confirms the r4 items and spot-checks the edits made after fable's review.

Completed-GMT: 2026-09-21 18:55:27 GMT
Completed-Local: 2026-09-22 01:55:27 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: bbeae799-313d-4447-8264-714c09a5f1ca

**READY FOR OWNER DECISIONS**

- The design can go to the owner for the decisions ranked in §8. Only #2, the normalizer, still blocks B0.
- It can be committed as a design document after two P2 text edits:
  - B2 still requires round trips from the lossless view that the revision just made optional.
  - The `lift(adapt(lower x)) = lower x` test named in the brief is not in the document.
- Several P3 leftovers keep coming back. They are worth folding into the same edit.

## Confirmation of the r4 items

```
+-------+---------------------------+--------------+---------------------------------------------+
| ID    | Item                      | Status       | Evidence                                    |
+-------+---------------------------+--------------+---------------------------------------------+
| P2-A  | image called lossless /   | PARTLY FIXED | Fixed: s1 bullet "`:yin/tail?` on every     |
|       | invertible; stale         |              | node"; B5 "their executable images may      |
|       | provenance clause         |              | differ"; s7 "distinct executable images";   |
|       |                           |              | s7.2 "consumes executable images". Not      |
|       |                           |              | fixed (3rd pass): s3 "Those fields are      |
|       |                           |              | excluded from code identity only when       |
|       |                           |              | explicitly declared diagnostic metadata".   |
|       |                           |              | B2 introduces a new contradiction: P2-1.    |
| P2-B  | B1 vs s2 host rule        | PARTLY FIXED | B1 now adds "distinct hashes for 1/1.0,     |
|       |                           |              | ratios, and chars on JVM and Dart, and      |
|       |                           |              | identical bytes across hosts for the common |
|       |                           |              | scalar domain", but still also says,        |
|       |                           |              | unqualified, "distinct image hashes for 1   |
|       |                           |              | and 1.0". Delete that earlier clause.       |
| P3-A  | enumerate the scalar      | NOT FIXED    | s2 still says "... symbol, and other        |
|       | domain                    |              | supported host values" (no nil, bool, or    |
|       |                           |              | collection literals).                       |
| P3-B  | "later lowering passes"   | NOT FIXED    | s7.1 "They remain possible later lowering   |
|       |                           |              | passes", which contradicts paragraph 3's    |
|       |                           |              | "AST-to-AST stream stages upstream".        |
| P3-C  | upstream stage must write | NOT FIXED    | s7.1 paragraph 3 is unchanged; no           |
|       | :yin/tail?                |              | :yin/tail? sentence.                        |
+-------+---------------------------+--------------+---------------------------------------------+
```

## New findings

### P1

None.

### P2

**P2-1. B2 still depends on the now-optional lossless view (B2 vs §1, §7.3, B0).**
- Quoted from B2: "Completion requires … B0 encode/decode round trips on the same inputs".
- B0 no longer defines `encode-db`/`decode-db`, and §7.3 says the view "is not read by B2, B5, or the VM".
- Failing scenario: if the owner drops the view (§8 decision 1 allows that), B2 can never be completed.
- Smallest fix: remove that clause from B2, and put the lift test in its place (P2-2).

**P2-2. The lift test is missing, and the lift is not a function of the image alone (§2, B2).**
- Quoted from §2: "one lift morphism from `:yin.debruijn.code/*` to `:yin.code/*`; the lift uses diagnostic binder names or synthesized names."
- B2's completion text does not contain `lift(adapt(lower x)) = lower x`. It has only the opcode-by-opcode comparison.
- Binder names sit in the side table, outside the hash. So `(fn [x] x)` and `(fn [y] y)` share one image hash, which is correct, but lift them to different named code. The lift is therefore a function of the image *plus* its side table.
- With synthesized names, equality holds only up to alpha-renaming.
- Smallest fix: B2's completion includes `lift(adapt(lower x), side-table) = canonical-vector(lower x)`. Compare against the canonical positional vector, not the datom batch, which carries tempids and `:yin.code/source`. Add a second test: with synthesized names, the lift is alpha-equivalent to `lower x`, and that includes duplicate parameters `(fn [x x] x)`.

### P3

- **B0 heading and §7 matrix.** "B0: contract, inverse, and normalizer" and the matrix item "inverse encoding" still present the inverse as core work. Retitle B0, and mark the matrix row "(only if §7.3 is commissioned)".
- **§4.** "`:macro?` is retained in the lossless encoding for decode and diagnostics": `lower` drops `:macro?`, so it now survives only in the optional view. Say "in the optional source view (§7.3), if retained".
- **B4 file box.** B4 must "lift frames through the descriptor morphism or run the frame-aware completion adapter", but its box lists only a test file, and "Existing edits: none" forbids changing `completion.cljc`. Name where the adapter lives, for example `debruijn_vm.cljc` from B3.
- **§1 and §8 #8.** "retire one VM" includes retiring the semantic VM, which §9 lists as unchanged and has many consumers. That would surprise the owner. Say that retiring the semantic VM needs its own design, and that the benchmark gate only *reports* numbers for B3 completion rather than requiring a win.

## Checked and found clean

- **Binder names are consistently out of the hash** in §1, §2, §3, B1 and §7.2. §2 says "Binder names and provenance are carried in a diagnostic side table … outside the image hash", and code identity is "canonical positional instruction vector … descriptor hash, and exact scalar bytes". No sentence still hashes a name table.
  - `(fn [x] x)` and `(fn [y] y)` both adapt to `[:closure 1 b] … [:load-bound [0 0]]`, so they share a hash.
  - `(f 1)` and `(f 1.0)` differ in their `:const` bytes on JVM and Dart; §2 already says they cannot be distinguished on CLJS.
  - Free names stay hashed as `:load-free` operands.
- **The descriptor** declares arity, slots, encoding and a lift. The exact-spelling slots are typed `Bytes`, which is coherent with the no-NFC rule.
- **Optional DAG:** apart from P2-1 and the P3 wording, nothing in B1 or B3-B5 reads it. §8 decisions 1 and 3 correctly stop blocking B0; #2 (the normalizer) still does.
- **Continuations** are declared "not … interchangeable between the two VMs" (§1). The park state now includes parked records, the gensym counter, modules and code aliases.
- **"never invokes a loader; absence is a park plus a request emission"** (§7.2) is consistent with §4.
- **`environment`** is deliberately left unimplemented. `yin.vm.telemetry` reads `:env` directly (`telemetry.cljc:263`) and never calls the protocol method, so nothing breaks.
- **B6** is defined only in §7.2 now, and every mention in §2, §7 and §8 is consistent with that.
- **The benchmark reference** exists: `yin.vm.semantic.md` "§8. Phase 4 Benchmarks".
- **Form:** the status reads "design; not implemented"; no line is over 80 columns; there are no em dashes, no pipe tables, and no routing text.

I did not edit any file.
