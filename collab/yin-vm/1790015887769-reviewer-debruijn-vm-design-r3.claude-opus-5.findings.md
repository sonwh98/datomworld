Completed-GMT: 2026-09-21 18:39:27 GMT
Completed-Local: 2026-09-22 01:39:27 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: bbeae799-313d-4447-8264-714c09a5f1ca

**READY FOR OWNER DECISIONS**, with no P1 open. Six new P2s should be folded into the text before B0 or B1 starts.

- Every r2 P1 is fixed in the text itself, not just in the disposition table.
- The adapter architecture (P2-F) is sound: body scope can be rebuilt from `lower`'s image alone, and `yin.vm.completion` already does exactly that.
- What remains is under-specification plus one internal contradiction, not an unsound design:
  - the image hash is not tied to a canonical form;
  - the reused helpers are private;
  - host scalar classes do not match across hosts;
  - the tree encoding can blow up exponentially;
  - two sentences in §1 are stale.

## Confirmation table

```
+-------+-------------------------------+---------------+---------------------------------------------+
| ID    | Finding                       | Status        | Evidence                                    |
+-------+-------------------------------+---------------+---------------------------------------------+
| P1-A  | image hash used canonical     | FIXED         | s2 "reuses only framing and length-prefix   |
|       | encode-value                  |               | helpers ... no NFC or integral-double       |
|       |                               |               | folding occurs in hashed bytes ...          |
|       |                               |               | deliberately distinct from encode-value";   |
|       |                               |               | B1 "distinct image hashes for 1 and 1.0".   |
|       |                               |               | Residue: new P2-2, P2-3.                    |
| P1-B  | arity-only memo key           | FIXED         | s3 "Any memo key must include node identity,|
|       |                               |               | the full vector of parameter-name vectors,  |
|       |                               |               | and the tail context". (Now largely moot:   |
|       |                               |               | B2 rewrites lower output, no own traversal.)|
| P1-C  | invertibility has no subject; | FIXED         | s1 "The encoded artifact is a tree-shaped   |
|       | tail?/macro-name dropped      |               | lossless record set, not the linear         |
|       |                               |               | instruction image ... :yin/tail? on every   |
|       |                               |               | node, :yin/macro-name"; B0 adds             |
|       |                               |               | debruijn_encoding.cljc. Residue: P2-4, P2-5.|
| P2-A  | named row-order fallback      | FIXED         | s1 "one fact per entity and attribute and   |
|       | rules                         |               | exactly one :yin/root marker ... a          |
|       |                               |               | diagnostic". The ast->datoms-with-root      |
|       |                               |               | output fits this domain (checked).          |
| P2-B  | which D1 helpers are reused   | FIXED         | s3 "reuses only the public                  |
|       |                               |               | yin.vm.debruijn/resolve-name ...            |
|       |                               |               | index-frame, build-node, and project-node   |
|       |                               |               | are not reused".                            |
| P2-C  | closure normalizer could not  | FIXED         | s1 "Closures compare as {:type :closure     |
|       | match                         |               | :arity n} only; continuation and parked     |
|       |                               |               | values compare by type only".               |
| P2-D  | fresh VM vs cross-program     | FIXED         | s1 "Park/resume fixtures that cross program |
|       | park/resume                   |               | boundaries are restricted to free names     |
|       |                               |               | supplied by the initial environment or      |
|       |                               |               | store"; repeated in B4.                     |
| P2-E  | provenance in and out of the  | PARTLY FIXED  | s2 "Provenance is always diagnostic metadata|
|       | image identity                |               | outside the hash", but s3 still says debug  |
|       |                               |               | hash/source ref "are excluded from code     |
|       |                               |               | identity only when explicitly declared      |
|       |                               |               | diagnostic metadata". Delete the s3 clause. |
| P2-F  | duplicate linearizer          | FIXED         | B2 "Run the existing yin.vm.linearize/lower |
|       | traversal                     |               | ... rewrite each :var ... replace each      |
|       |                               |               | :closure parameter vector with its arity",  |
|       |                               |               | plus an opcode-by-opcode test. Residue:     |
|       |                               |               | P2-1, P2-6.                                 |
| P2-G  | 7.2 consumed the lossy        | FIXED         | s7.2 "consumes lossless executable images   |
|       | projection                    |               | ... uses the projection fingerprint only as |
|       |                               |               | a lookup index".                            |
| P3a   | 7.1 overclaims                | PARTLY FIXED  | "nearest bound variable at index zero" is   |
|       |                               |               | gone; the decompilation sentence is now     |
|       |                               |               | "not assumed here". Still beyond the owner's|
|       |                               |               | verified list: "evaluation" as a pipeline   |
|       |                               |               | stage, and the component list "ANF, MCode,  |
|       |                               |               | Machine, Serialize, Decompile, and          |
|       |                               |               | Canonicalizer". See P3-1.                   |
| P3b   | owner decision 1 blocks B0    | FIXED         | s8 item 1 "B0 blocker: whether tempids ...".|
| P1-1  | (r1) canonical spelling       | FIXED         | via P1-A; s2 "Exact named literal spelling  |
|       |                               |               | ... is included in the image hash".         |
| P1-3  | (r1) :env leak                | FIXED         | s1 free-env paragraph plus s5, B4, and owner|
|       |                               |               | decision 5.                                 |
| P1-4  | (r1) no normalizer            | FIXED         | s1 normalizer paragraph; "defined and tested|
|       |                               |               | in B0".                                     |
| P2-7  | (r1) error ex-data            | PARTLY FIXED  | s1 "Error comparison uses the message and   |
|       |                               |               | normalized ex-data": the rule for ex-data   |
|       |                               |               | is still not stated. Fix: "ex-data is       |
|       |                               |               | compared after applying the value normalizer|
|       |                               |               | recursively to its values".                 |
| P2-10 | (r1) memo key                 | FIXED         | as P1-B.                                    |
+-------+-------------------------------+---------------+---------------------------------------------+
```

## New findings

### P1

None.

### P2

**P2-1. The adapter's scope reconstruction works, but the design does not say how (B2).**
- Quoted: "rewrite each `:var` operand using `resolve-name` in the source body context".
- Rebuilding scope from `lower`'s output *is* possible, and needs neither `:yin.code/source` nor the AST index:
  - `flatten-program` gives every `:lambda` occurrence a fresh label and a freshly lowered body (`linearize.cljc:86-90` and `:145-150`). So each body is the target of exactly one `:closure`, including shared-eid and `:macro?` lambdas; `lower` ignores `:macro?`.
  - Bodies are appended after the main code. Each body is contiguous and ends at its first `:return`, because `:return` is emitted only at a body's end.
  - The enclosing body of a closure at pc `c` is the body whose range contains `c`. Code outside every body is the top level, with an empty name stack.
- `yin.vm.completion` already implements this: `closure-ranges`, `layout-conforms?`, `segment-scope` and the `seg-bound?` rules (`completion.cljc:132-212`).
- "Source body context" hints at `:yin.code/source`. That would be wrong: a shared-eid lambda's two body copies carry the same source eid, so the relation would be ambiguous.
- Smallest fix: specify the reconstruction as a pure function of the image:
  - the body range is `[entry, first :return]`;
  - the parent is the body that contains the `:closure`;
  - the name stack is built innermost-first for `resolve-name`, whose depth 0 is `(nth stack 0)`, the opposite of §4's runtime frame order.
- Require `layout-conforms?`, a validation defect otherwise, and cite completion as the precedent. `segment-scope` is public; `closure-ranges` and `layout-conforms?` are private, which is the same visibility issue as P2-2.

**P2-2. The "reused" framing helpers are private (§2 vs the B1 file box).**
- Quoted: "The encoder reuses only framing and length-prefix helpers plus `jing/sha256`" and "Existing edits: none … Must not change: … projection encoder".
- `framed`, `to-hex`, `utf8-byte-length`, `int64-le-hex`, `double-le-hex` and `slot-tag` are all `defn-`/`^:private` (`debruijn.cljc:775-872`). Reusing them means editing `yin.vm.debruijn` or reaching in through var-quotes.
- There is also a trap: `framed` looks up its tag in a `slot-tag` table built from the projection's classes. A new class such as `:ratio` or `:char` gets `nil`, and `(str nil)` is `""`, so the tag byte silently disappears from the hashed text.
- Smallest fix, either of:
  - copy these small helpers into `debruijn_code.cljc` with its own tag table, which is about 40 lines;
  - or list "make the framing helpers public" as an existing edit in B1 and drop "none".
- Either way, B1 must also refuse unpaired surrogates, as the projection's `well-formed-utf16?` does. Otherwise `utf8-byte-length` and the host encoders disagree.

**P2-3. The executable scalar classes do not exist on every host (§2, B1).**
- Quoted: "distinct tags represent long, double, ratio, bigint, char …" and "cross-host bytes for every supported scalar class".
- Where the classes differ:
  - On CLJS, `1.0` reads as the JS number `1`.
  - `\a` is the string `"a"` on CLJS.
  - Ratios are JVM-only; bigints differ by host.
- So "distinct image hashes for 1 and 1.0" cannot hold on Node, and a Clojure source containing `1.0` legitimately hashes differently on JVM and Node.
- Smallest fix:
  - Declare a per-host classification: a JS number is long when it is a safe integer and double otherwise.
  - Restrict cross-host byte identity to *values* that exist on all hosts.
  - Mark the `1`/`1.0`, ratio and char tests as JVM and Dart only.

**P2-4. The tree-shaped encoding is exponential on shared named DAGs (§1, B0).**
- Quoted: "a tree-shaped lossless record set … one source eid for each occurrence".
- `ast->datoms` deduplicates shared `:eid`s (`seen-eids`), so a named graph that doubles at each level is linear in datoms but 2^n in occurrences. That is the bug class the projection's memo fixed.
- The executable image is also expanded, because `lower` expands, but the *lossless source* artifact has no reason to be.
- Smallest fix: make the record set a DAG keyed by `[source-eid, name-stack]`, exactly the projection's memo key, so a variable's resolution can still differ per lexical context. Decode re-merges by source eid. B0 needs a doubling-chain size test.

**P2-5. §1 contradicts itself on which artifact is lossless.**
- Line 15: "The executable de Bruijn image is a third, lossless representation of the named datoms", and "the front-end `:yin/tail?` value where the named linearizer observes it".
- Line 40: "The encoded artifact is a tree-shaped lossless record set, not the linear instruction image … `:yin/tail?` on every node".
- The image drops non-application tail flags, `:macro?`, `:macro-name`, and the structure of `if` and application, so it is not lossless.
- Also, "source eid for each occurrence" together with "normalizes … emitter-local tempids" needs to say that equality is modulo a consistent renaming of entity ids. Emitter source eids *are* tempids.
- Owner decision 1 still asks "whether tempids have an owner-approved normalization", which §1 has already declared.
- Smallest fix: call the image "executable, derived, not invertible"; update the bullets to "every node"; say "equal up to a consistent entity renaming"; reword decision 1 as "which stable identity, if any, replaces tempids".

**P2-6. The image hash is not tied to a canonical form (§2, B2).**
- Quoted: "The code-image identity is the hash of the complete emitted image".
- `lower` produces a datom batch whose entity ids depend on `:id-start`, plus `:yin.code/source` and `:derived-from` tempids.
- `ast-loader` gives each batch a different `:id-start` (`linearize.cljc:426-429`). Hashing the rewritten batch would therefore give the same program two different images.
- Smallest fix: B2 rewrites and B1 hashes the canonical positional vector (pc-indexed tuples, refs as pcs, no header), as `load-image`'s `canonical` and `jing/segment-key` already do. Provenance is kept as a side table indexed by pc.

### P3

- **P3-1 (§7.1).** "evaluation" and the component list are still not in the owner-verified set: let-rec minimization, lambda lifting, ANF, an IR with De Bruijn indices as stack positions, decompilation, and the identity documents. Either cite them as owner-verified from the directory listing, or move them under the UNVERIFIED line. The rest of §7.1 is now properly hedged: "do not establish that Unison executes its exact stored identity form", "not assumed here", and the UNVERIFIED list.
- **P3-2 (§3).** The memo paragraph and "The named datom graph is walked per occurrence" describe a traversal B2 no longer performs. Recast them as properties inherited from `lower`.
- **P3-3 (§2).** "carried in an executable spelling slot or side table": pick one before B1 freezes the descriptor.

## Checked and found clean

- **Structural parity is a real invariant.** `:var`→`:load-bound`/`:load-free` and params→arity are one-for-one rewrites that keep the operand count. Program counters, labels, `:jump`/`:branch-false` targets and closure body pcs are unchanged. Only the mirrored operand table changes: the two new opcodes are added, and `:closure` operand 1 goes from `:syms` to `:uint`.
- **The structural test is enough to stop drift.** The image is derived from `lower` by construction, so drift can only enter through the rewriter, and B2's opcode-by-opcode test covers that.
- **No canonicalization leaks back in through the adapter.** `resolve-name` compares with `=` and never calls NFC. Free names go to `:load-free name` exactly as spelled.
- **The B0 input domain fits the emitter.** `ast->datoms-with-root` always emits exactly one `:yin/root` as the last datom (`vm.cljc:633`). It emits each attribute once per entity, including one vector datom for `:yin/operands`, and emits a shared eid once. Ordinary emitted programs are not rejected; only hand-merged or duplicated batches are, and they get a diagnostic.
- **The normalizer, P2-D and the provenance rule** are consistent across §1, §5, B0, B3, B4 and B5, apart from the §3 clause noted in P2-E.
- **§7.2** stays consistent with the axioms after the change: a process between streams, no callbacks, failures as stream outcomes, the fingerprint used only as a lookup.
- **Form:** the status reads "design; not implemented"; sections are numbered; no line is over 80 columns; there are no em dashes, no pipe tables, and no routing text. The file is still untracked.

I did not edit any file.
