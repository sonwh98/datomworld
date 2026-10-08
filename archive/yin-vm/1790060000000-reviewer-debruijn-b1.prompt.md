Created-GMT: 2026-09-22 07:00:00 GMT
Created-Local: 2026-09-22 14:00:00 +07 (Indochina Time)
Coding-Agent: cmd (qwen/qwen3.8-max)
Session-ID: pending (provider-generated)
# Task: debruijn-b1: independent review of the de Bruijn VM's executable dimension and validator
Role: Adversarial Review (Compiler / Bytecode)
Implementers:
- Model: qwen/qwen3.8-max | Assigned: 2026-09-22 14:00:00 +07 | Status: active | Rationale: the code was written by claude-sonnet-5 (Claude family); reviewer must differ; this is compiler lowering/opcode-table/AST-analysis work, team.md's specialty for this model

Read-only. Work ONLY in /Users/sto/workspace/worktree-debruijn-b1 (branch
debruijn-b1, HEAD 03eeffef; the B1 files are COMMITTED at this HEAD, not
uncommitted -- read them via the working tree or `git show`). Do NOT edit
or create any file and do not run tests (the orchestrator verified: kondo
0/0, cljstyle clean, full JVM Java 17 1723 tests / 172674 assertions, CLJS
1640 tests / 42497 assertions, CLJD 1602 tests all passed, focused
namespace 12 tests / 124 assertions). You may read files and run read-only
git commands. Give the complete review now as your final response; do not
wait for approval.

## What you are reviewing

src/cljc/yin/vm/debruijn_code.cljc (about 760 lines) and
test/yin/vm/debruijn_code_test.cljc (about 290 lines), both new, both at
HEAD 03eeffef (`git show 03eeffef --stat`, `git show 03eeffef -- <path>`).

Read in full first: docs/design/yin.vm.debruijn-vm.md section 1
(Architecture and invariants) and section 2 (Instruction dimension and
code identity) in full, plus the "B1: executable dimension and validator"
phase box. The design doc's own S2/B1 host-classification wording was
corrected mid-implementation by two real findings (both already folded
into the committed design doc, verify them against the code): ClojureDart
has no ratio type and no project-local equivalent (bigint, ratio both
JVM-only, refused elsewhere with `:unsupported-value`), and ClojureDart's
`char?` does not reliably distinguish a genuine char from a one-codepoint
string (char is also JVM-only now). Also read
yin.vm.code/vector-operand-table (the source this dimension's opcode
table must derive from) to compare shapes directly rather than trust the
new file's own claims about it.

## What to judge (concrete; cite file:line)

1. **Opcode table derivation.** Is every carried mnemonic's operand shape
   (stack ops, `:const`, `:push`, `:halt`, `:branch-false`, stream ops,
   store ops, park, resume, gensym, FFI) genuinely DERIVED from
   `yin.vm.code/vector-operand-table`, not hand-copied with a silent
   drift risk? Are `:load-bound`/`:load-free` (replacing `:var`) and the
   new `:closure` shape (arity + body pc, replacing a parameter vector)
   correctly and completely specified? Any mnemonic missed or
   mis-derived?
2. **Scalar encoder.** The 14-class domain (nil, bool, long, double,
   ratio, bigint, char, string, keyword, symbol, vector, list, map, set):
   exact scalar bytes preserved (no NFC on identifier/string content, no
   integral-double-to-long folding); map/set entries ordered by encoded
   bytes; unpaired UTF-16 surrogates refused with `:unsupported-value`;
   values outside the domain refused with a qualified diagnostic, never
   silently canonicalized. Does the file reach into `yin.vm.debruijn`'s
   PRIVATE vars anywhere (it must not; framing/length-prefix helpers are
   meant to be copied, not imported) -- check the `:require` list and
   every qualified call.
3. **Host classification, the corrected parts especially.** `host-char?`,
   `host-ratio?`, `host-bigint?`, `host-long?`, `host-double?`: correct on
   every host per the corrected design? Reader-conditional ORDER: this
   codebase has a documented ClojureDart trap (`#?(:clj ...)` without an
   explicit, FIRST-listed `:cljd` branch can silently compile the `:clj`
   branch for Dart too) -- check every `#?()` in the file for this hazard,
   not just the ones already fixed; is `:cljd` explicit and never relying
   on an ambiguous `:default` fallthrough where a `:clj` branch is also
   present? Is the CLJS integral-double-is-outside-the-common-domain rule
   correctly implemented on the DECODE/receive side, not conflated with
   encode?
4. **`image-hash`.** Hashes the descriptor hash (incl. lowering-contract
   version) plus the canonical positional instruction vector; provenance
   (binder names, diagnostics) is in a side table OUTSIDE the hash --
   confirm nothing diagnostic leaks into the hashed bytes. Confirm H is
   the only identity computed anywhere in this file (no second
   fingerprint).
5. **The validator.** Walks each body's enclosing arity chain; rejects a
   `:load-bound` operand like `[5 0]` pointing past a body's declared
   arity/enclosing chain. Try to construct a DIFFERENT out-of-range or
   malformed case the validator might miss (a negative depth, a position
   equal to but not less than arity, a body reference to a nonexistent
   pc, a `:closure` arity mismatched with its body's actual parameter
   count if that's even checkable here).
6. **Tests and golden fixtures.** Composed vs decomposed e-acute hashing
   distinctly (proves no NFC); `1`/`1.0` distinct on JVM/Dart, not tested
   on CLJS; ratio and char distinct-hash tests are JVM-only (confirm this
   is what the code file's actual behavior requires, not stricter or
   looser than the design allows); the golden-fixture test that must fail
   on a layout perturbation -- is it a REAL proof (would catch a real
   regression) or does it only assert something too narrow to catch
   drift?

## Deliverable

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: cmd
Session-ID: <your session id if visible, else pending>
then a verdict: READY, READY WITH CHANGES, or NOT READY; findings as P1
(wrong bytes, wrong hash, a validator gap that lets a malformed image
through, a Dart/CLJS reader-conditional hazard that silently miscompiles),
P2 (significant gap), P3 (minor), each with file:line, the design-doc
sentence it violates or the concrete failing scenario, and the smallest
fix; what you checked and found clean. Findings only; edit no file.
