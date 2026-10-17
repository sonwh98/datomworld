# Phase 6a discovery-order numbering review

Reviewed branch `srvm-phase6a`, HEAD `c8d4dca2075a05abe8e1ad7cd98c48b71d4c19ed`, implementation `19f8a1d2`, against numbering-note §§1–6, the reference probe, the engineer report and review r4. Non-author review; no agents. Commands ran as foreground processes. Only this findings file remains edited. Temporary scripts, logs, patched document copies and source backup are under `/tmp/review6a-*`.

**No blocking correctness or specification finding.** The independent 5,000-state corpus reproduced 4,110 accepted and 890 refused states, with zero root or refusal-verdict/path mismatches over two independently renamed/shuffled copies per state. Every accepted original decoded and re-emitted successfully. All eight requested source mutants were caught.

| Severity | File:line | Finding |
|---|---|---|
| None | `src/cljc/yin/vm/ucf/rows.cljc:237` | Representation estimation is iterative, stops against remaining work, and charges before comparison encoding. The prior r4 resource finding is resolved. |
| None | `src/cljc/yin/vm/ucf/rows.cljc:643` | Census count precedes depth scan, which precedes ranks, occurrence counts and recursive walks. The prior r4 depth finding is resolved. |
| None | `src/cljc/yin/vm/ucf/rows.cljc:921` | The whole root/child recomputation is translated before root comparison and frame validation. Resource refusals propagate unchanged. The prior r4 decoder-order finding is resolved. |
| Verification limit | `src/cljc/yin/vm/ucf/rows.cljc:257` | Node and Dart received the requested static portability review, not execution. This verdict does not certify their runtime test lanes. |

## Implementation versus note and probe

Function-by-function comparison of the procedure found these correspondences:

| Production functions | Probe counterpart and assessment |
|---|---|
| `descriptors`, `elem-item`, `value-slot?`, `child-task?` | Synthetic `:entry [:V :V]`, ordered key/value pair, value slots and task-scope boundary agree. `:entry` does not enter the wire schema. |
| `work!`, `charge-representation!`, `key-bytes!`, `sort-by-key!` | Probe `spend!`, `charge-rep-bound!`, encoding and sorting agree. Production retains bytes-input `bytes-key`, the portable payload-length conditional, and records the attempted over-limit work charge; probe diagnostics are omitted. |
| `depth-scan!` | Same explicit stack, slot/member/map-entry/data/metadata depth accounting and per-position work charge. Install children are skipped for their own runs. |
| `walk-cells`, `item-cells`, `body-ranks` | Same census/child exclusions, memoization and distinct-label byte ordering. Probe `occurrence-counts` is inlined into `discovery-numbering`; counts still include every occurrence outside census/children. |
| `new-run`, `meet!`, `enter-slot!`, `enter-node!` | Same W1/W2 and root/local tags, pre-order table traversal, keyed structural sorting, numeric window order and child exclusions. Probe's unused `meet!` boolean is omitted. |
| `unordered!`, `fixpoint!`, `unnumbered-cells` | Same one-numbering-per-keying snapshot, unique-key entry, queue order and re-keying until a pass numbers no cell. Map entries retain alias information across key/value. |
| `tie-failure` | Probe `group-failure`, with `reduce` instead of `for` and membership checks instead of set intersection. All three clauses remain: complete key, disjoint member cells, confinement across surviving groups. |
| `resolve!` | Same passing-group entry and root refusal/key residue. No probe permissive mode or trace remains. First failing queue group's path and union of failing groups' unnumbered cells agree. |
| `key-walk` | Probe `local-run`; memo key includes item, its already-numbered cells and enclosing local counter. Nested local indices are not reused. |
| `spell-cell`, `spell-slot`, `spell-node` | Root `[:n k]`, local `[:u k r m]`, literal references, sorted spelling vectors and repeated tie residue agree. Production always includes occurrence counts. |
| `discovery-numbering`, `canonical-numbering` | Same root procedure and nonempty-census totality check, without diagnostic fields. The explicitly retained empty-census contract returns `{}` after its count gate. |
| `unresolved!`, `child-numberer`, emission and decoding | Same qualified refusal and root-prefix translation; production improves the probe by carrying the supplied budget and preserving nested install path prefixes. |

Default `:work` is exactly `4194304`; `:label-bytes` and `:search` are absent. Totality tests exercise missing and extra declared cells, require an exception without `:yin.k/status`, and prevent partial emission for nonempty censuses.

The private decoder is exercised through public `decode`, which invokes the real `decode*`; the independent script does not access private Vars. A separate nested-child witness produced lift path `[12 a 12 b 15]`, decode path `[<root> 12 a 12 b 15]`, and carried cells `[:yin.k/cc-1 :yin.k/cc-2]`. A separately decoded valid carrier under `{:work 1}` produced exactly `:yin.k/unsupported :resource-limit [:work]`.

## Independent corpus and test runs

Adapted the earlier script from `/Users/sto/workspace/datomworld-srvm-numbering/collab/numbering-probe/codex-gen.clj`. Kept `java.util.Random` seed `179221`, generator shapes, renamer/shuffler and two copies per state. Replaced probe helpers with ordinary schema traversal and replaced the execution path with real `canonical-numbering`, `emit-numbered`, and `decode`/`decode*`. No probe discovery code was loaded. Extended decode checking from the first 100 states to every accepted original, comparing the decoded task's re-emitted root too. The 250 install-child states remain in the corpus; reproduction of the exact 4,110/890 split confirms that adaptation did not perturb its random sequence.

| Run | Result |
|---|---|
| `clojure -M:test -n yin.vm.ucf.rows-test -n yin.vm.semantic-register.frame-test` | 40 tests, 691 assertions, 0 failures/errors; includes the slow generator. |
| `clojure -M:test -e :slow -r 'yin\.vm\.ucf\..*'` | 665 tests, 9,313 assertions, 0 failures/errors. |
| Independent `/tmp/review6a-gen.clj` | 5,000 originals, 10,000 renamed/shuffled copies; 4,110 accepted, 890 refused, zero mismatches, 4,110 successful decode/re-emissions. |
| Supplemental nested-child and resource checks | All assertions passed. |

The broader test counts differ from the engineer's earlier branch snapshot; these are the results on the reviewed HEAD. Resource exhaustion is never treated as an accepted canonicality result. The independent run encountered only acceptance or the intended structural refusal.

## Independent mutations

Each mutant was a source edit from a `cp`-equivalent byte copy (`shutil.copyfile`) of the original. Each ran rows/frame tests with `-e :slow`: baseline scope 39 tests, 683 assertions, including the quick generator. After every mutant the original was copied back, `diff -q` succeeded, and `git diff --quiet` succeeded. The final restoration was checked again. No mutant survived.

| Mutant | Failures / errors | Main detector |
|---|---:|---|
| Drop occurrence count from `[:u k r m]` | 17 / 6 | Discovery maps, count-separation fixtures, alias fixtures and pins. |
| Drop first-occurrence index, spelling `[:u r m]` | 4 / 0 | Balanced-alias witness, pinned root and refusal assertions. |
| Skip confinement clause (iii) only | 10 / 0 | Shared-across-groups refusal fixtures and quick incidence generator. |
| Skip representation charge in `key-bytes!` | 3 / 0 | Large-set literal work refusals and estimator-step assertions. |
| Skip the iterative depth scan entirely | 5 / 0 | Deep literal gate and large-literal counter/early-estimator assertions. |
| Move frame validation before numbering | 3 / 0 | Both halves of `numbering-precedes-frame-defect`: wrong numbering and unresolved numbering beat reserved-name defects. |
| Reverse ordered child/slot visits in `enter-node!` | 37 / 0 | Hand-derived discovery maps, alias witnesses, frame fixture maps and pins. |
| Key walks ignore enclosing already-numbered cells (`num {}`) | 2 / 0 | Quick generator finds accepted root invariance failures. |

The first frame-order experiment omitted the task-root guard and also produced unrelated non-task-root errors. I discarded that version as evidence and reran a precise mutant that moved the original guarded validation block before recomputation; the table reports that corrected run.

Reversing a *certified passing tie group's host order* would be an equivalent mutant under the adopted proof, unlike reversing ordered children; no failure is expected for that different mutation.

## Portability and public helper scope

The new procedure has no `for` over long sequences, reflection/private-Var access, JVM-only collection APIs, or multi-key `assoc` on nil. It uses portable atoms, `reduce`, `mapv`, sequence traversal and explicit-stack gates. Recursive cell scans, spelling, memo hashing and encodings follow the depth gate for nonempty censuses. Integer counters/ranks/indices under default bounds remain far below JavaScript's exact-integer limit. The byte-payload branch keeps Dart `.-length` and the existing default `alength`; catches retain `#?(:cljd Object :clj Throwable :cljs :default)`. These are static checks, not compiler/runtime evidence.

Production call-site search found no use of the injected child numberer or supplied counter outside this codec. Ordinary `emit` computes root numbering with its own counter and builds its private canonical child numberer from the merged caller budget. Decode recomputes through that `emit`, so neither new helper arity can bypass production validation. `emit-numbered` remains an explicitly unchecked probe constructor, as before; its new callback is useful for deliberately invalid child carriers and is not selected by an input wire value. Public `decode` removes caller `:check-numbering`; `canonical-under` retains its existing test-only exception but still runs recomputation.

## Engineer deviations 1–12

| # | Decision | Reason |
|---|---|---|
| 1 | Accept; resolved | The branch now includes the master merge (`810fb3c8`). The engineer's earlier inability to merge is no remaining integration defect. |
| 2 | Accept; resolved | Docs patch is now landed (`23c233ed`), and the adopted note's status is updated (`c8d4dca2`). |
| 3 | Accept | Carrier paragraph names preserve normative references; dropping obsolete implementation line numbers and measured figures from carrier prose is appropriate. |
| 4 | Accept | Optional child-numberer only expands the existing explicit-numbering probe constructor; production `emit` supplies its own canonical implementation. |
| 5 | Accept | Counter arity supports meaningful early-abort assertions without changing refusal data or ordinary production calls. |
| 6 | Accept | Caller budgets apply to each child's numbering; nested install prefixes are retained and independently verified. This is not a shared aggregate work counter across all tasks. |
| 7 | Accept | Empty-census `{}` is explicitly preserved by note §4 and the old contract. It is an exception to running the full depth/totality procedure; malformed abstract inputs still belong to decoder schema/census checking. |
| 8 | Accept | Sorting local diagnostic cell ids by `str` is portable and preserves the keyword-id order used here. Canonical roots and verdict/path invariance do not depend on these source-name diagnostics. |
| 9 | Accept | Explicit numbering is appropriate when constructing malformed carriers to test decoder defects now rejected earlier by canonical lift. Decoder assertions remain intact. |
| 10 | Accept | Heavy work-counter assertions are present in the large-literal test; duplicating them in another test adds cost without coverage. |
| 11 | Accept | Incidence families add relevant independent generator coverage; confinement and numbered-cell-key mutants are caught by the quick generator. |
| 12 | Accept | `canonical-under` still recomputes with numbering comparison disabled. Public decode strips that switch, and translated tie refusal remains available. |

## A1–A9 document checks

Applied `collab/1792250000000-docs-amendments-against-master.patch` with `patch -p1` to temporary copies obtained with `git show master:<path>`; it applied cleanly. Both outputs are byte-identical to the landed carrier and continuation documents and the engineer's amended copies.

Spot checks covered A1's key/walk/tie/resource/totality rules; A2's invariant receiver acceptance and tie-refusal wording; A3's distinction between member comparison keys and final wire-row ordering; A4's root/child decoder translation, validator order and added-kind counts; A5's replacement of refinement/search budgets and its accepted refusal choice; A6's definition of the n-th encountered cell; A7's continuation numbering description; A8's body-rank spelling and distinction between canonical traversal and host enumeration; and A9's acceptance items 12–22. They agree with the implementation. Historical baseline line references in the companion note are explicitly references to `d77da9f0`, not current source locations. The earlier stale incomplete-key example and termination measure have also been repaired in the adopted note.

VERDICT: READY_TO_MERGE
