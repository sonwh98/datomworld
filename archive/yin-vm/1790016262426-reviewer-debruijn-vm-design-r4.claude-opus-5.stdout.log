Completed-GMT: 2026-09-21 18:46:08 GMT
Completed-Local: 2026-09-22 01:46:08 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: bbeae799-313d-4447-8264-714c09a5f1ca

**READY FOR OWNER DECISIONS**

- There is no P1. The architecture is settled, and every substantive r3 finding is fixed in the text.
- What remains is stale wording: sentences that contradict the revised §1 and §2, including two completion criteria that cannot be met as written.
- The design is ready to hand to the owner for the three B0 blockers: tempid identity, the comparison normalizer, and non-`:yin` attributes.
- It is ready to commit as a design document once the P2-A and P2-B edits below are made. These are about eight line edits, with no design change.

## Confirmation table

```
+-------+-----------------------------+--------------+-----------------------------------------------+
| ID    | Finding                     | Status       | Evidence                                      |
+-------+-----------------------------+--------------+-----------------------------------------------+
| P2-1  | image-only scope            | FIXED        | B2 "Scope reconstruction is a pure function of|
|       | reconstruction              |              | the image, never of :yin.code/source: each    |
|       |                             |              | body range is [entry, first :return], its     |
|       |                             |              | parent is the body containing the :closure,   |
|       |                             |              | and the name stack for resolve-name is built  |
|       |                             |              | innermost-first. This is opposite to the      |
|       |                             |              | runtime frame vector's outermost-to-innermost |
|       |                             |              | order and the conversion is explicit"; "A     |
|       |                             |              | failed layout-conforms? check is a validation |
|       |                             |              | defect."                                      |
| P2-2  | private framing helpers     | FIXED        | s2 "copies its small framing and length-prefix|
|       |                             |              | helpers and defines its own scalar tag table; |
|       |                             |              | it does not reach private projection vars";   |
|       |                             |              | consistent with B1 "Existing edits: none".    |
|       |                             |              | Surrogates: "Unpaired UTF-16 surrogates are   |
|       |                             |              | refused with :unsupported-value".             |
| P2-3  | per-host scalar classes     | PARTLY FIXED | s2 "Cross-host byte identity is required only |
|       |                             |              | for values present on all three hosts. B1     |
|       |                             |              | tests for 1 versus 1.0, ratios, and chars are |
|       |                             |              | JVM/Dart tests". B1's completion text still   |
|       |                             |              | says "cross-host bytes for every supported    |
|       |                             |              | scalar class": new P2-B.                      |
| P2-4  | exponential tree            | FIXED        | s1 "a lossless record DAG ... keyed by        |
|       |                             |              | [source-eid name-stack]"; B0 "A doubling-chain|
|       |                             |              | shared-eid fixture must remain linear in      |
|       |                             |              | record count".                                |
| P2-5  | which artifact is lossless  | PARTLY FIXED | s1 "executable, derived representation and is |
|       |                             |              | not itself invertible"; "up to a consistent   |
|       |                             |              | entity-id renaming"; s8 decision 1 reworded.  |
|       |                             |              | Still calls the image lossless or invertible: |
|       |                             |              | B2, B5, s7, s7.2, plus the stale s1 bullet.   |
|       |                             |              | New P2-A.                                     |
| P2-6  | hash over canonical vector  | FIXED        | s2 "the hash of the canonical positional      |
|       |                             |              | instruction vector: pc-indexed tuples, refs   |
|       |                             |              | resolved to pcs, no header ... Provenance is a|
|       |                             |              | diagnostic side table indexed by pc and is    |
|       |                             |              | outside the hash." No text hashes the raw     |
|       |                             |              | datom batch.                                  |
| P3-1  | 7.1 unverified claims       | FIXED        | "is reported to contain"; "UNVERIFIED here:   |
|       |                             |              | evaluation as a documented pipeline stage, the|
|       |                             |              | detailed component listing".                  |
| P3-2  | stale traversal/memo text   | FIXED        | s3 "The image inherits occurrence expansion,  |
|       |                             |              | fresh lambda labels, and body layout from ... |
|       |                             |              | lower; B2 does not repeat that traversal."    |
| P3-3  | "slot or side table"        | FIXED        | s2 "carried in a canonical side table indexed |
|       |                             |              | by instruction slot and is included in the    |
|       |                             |              | image hash".                                  |
| P2-E  | provenance in/out of        | NOT FIXED    | s3 still says "Those fields are excluded from |
|       | identity                    |              | code identity only when explicitly declared   |
|       |                             |              | diagnostic metadata", which contradicts s2    |
|       |                             |              | "Provenance ... is outside the hash." Part of |
|       |                             |              | P2-A.                                         |
| P2-7  | ex-data rule                | FIXED        | s1 "ex-data is compared after recursively     |
|       |                             |              | applying the value normalizer to every ex-data|
|       |                             |              | value."                                       |
| P3a   | 7.1 hedging                 | FIXED        | as P3-1; "do not establish that Unison        |
|       |                             |              | executes its exact stored identity form".     |
+-------+-----------------------------+--------------+-----------------------------------------------+
```

## New findings

### P1

None.

### P2

**P2-A. Stale sentences contradict §1's "not itself invertible" (B2, B5, §7, §7.2, §1, §3).**

Several sentences still treat the image as lossless or invertible:
- **B2:** "Completion requires … image decode round trips." The image is not invertible, so this criterion cannot be met.
- **B5:** "their lossless images may differ".
- **§7:** "alpha-equivalent equal projections with distinct lossless images".
- **§7.2:** "it consumes lossless executable images".
- **§1 bullet:** "the front-end `:yin/tail?` value where the named linearizer observes it". This contradicts line 43, "`:yin/tail?` on every node".
- **§3:** the provenance clause quoted in the P2-E row.

Failing scenario: a B2 implementer cannot satisfy the criterion. Decompiling `lower`'s output would need a decompiler that the design itself rules out.

Smallest fix:
- B2: replace the criterion with "B0 encode/decode round trip on the same inputs, and image encode/validate/load round trip".
- B5, §7 and §7.2: say "executable images".
- §1 bullet: "`:yin/tail?` on every node".
- §3: say "Debug node hashes and source references are always diagnostic metadata outside code identity."

**P2-B. B1's completion text contradicts §2's host rule.**
- Quoted from B1: "distinct image hashes for `1` and `1.0` … plus cross-host bytes for every supported scalar class."
- Quoted from §2: "Cross-host byte identity is required only for values present on all three hosts" and "B1 tests for `1` versus `1.0`, ratios, and chars are JVM/Dart tests".
- Failing scenario: on Node, `1.0` reads as `1`, so the `1`/`1.0` test fails. Ratios have no CLJS class, so "every supported scalar class" cannot give identical bytes on all three hosts.
- Smallest fix: B1 says "distinct hashes for `1`/`1.0`, ratio and char on JVM and Dart; identical bytes across hosts for the common scalar domain".

### P3

- **P3-A (§2).** "long, double, ratio, bigint, char, string, keyword, symbol, and other supported host values" does not list `nil`, booleans or collection literals. `:const` operands are any `vm/plain-data?` value: vectors, lists, maps and sets. Enumerate them, and state that maps and sets are ordered by encoded bytes and that vector and list are distinct classes, following the projection's value table.
- **P3-B (§7.1 paragraph 2).** "They remain possible later lowering passes" contradicts the owner ruling in paragraph 3, which says these are "separate AST-to-AST stream stages upstream of both lowerers". Replace it with "upstream AST-to-AST stages (below)".
- **P3-C (§7.1 paragraph 3).** An upstream lifting or ANF stage must write `:yin/tail?` on the nodes it creates, because both lowerers copy front-end tail flags and neither infers them (§3). Without that, tail calls are silently lost on both paths. Add one sentence.

## Checked and found clean

- **The lambda-lifting note** (§7.1 paragraph 3) contains all four required points:
  - "is optional";
  - "changes arity";
  - "Synthesized parameters need name-table entries";
  - "the invertibility invariant applies to whichever named AST the lowerer receives".
- It also adds "This VM neither depends on nor forbids those stages", which is consistent with B0-B6: no phase depends on lifting.
- **No Unison interoperability** is claimed or implied anywhere. §7.1 is framed as an analogy only, and §7.2 ends with "does not establish … any property of the Unison runtime".
- **The linker boundary is consistent** between §7.2 ("The boundary is `dao.stream`; local and remote resolution are the same transport-neutral mechanism, and their physical placement is not a linker decision") and §8 decision 6 ("The linker boundary and local/remote transport are already fixed as `dao.stream`"). Neither has a local-versus-remote branch.
- **§8** now ranks three B0 blockers (tempid identity, normalizer, non-`:yin` attributes), and they match §1's open points.
- **The normalizer, the park/resume restriction and the environment rule** are consistent across §1, §5, B3, B4 and B5.
- **B0-B6** each still have a file box, a must-not-change list and completion criteria, and no phase depends on a later one. The only criterion problems are the P2-A and P2-B texts above.
- **Form:** the status reads "design; not implemented"; sections are numbered; no line is over 80 columns; there are no em dashes, no pipe tables, and no routing text. The file is still untracked, so it has not been committed yet.

I did not edit any file.
