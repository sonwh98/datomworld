Created-Local: 2026-10-14 +07
Reviewer: Fable (non-author; author codex)
Branch: srvm-phase7m, commit 6fc65722 (`git diff e6337294..HEAD`)
Scope: `src/cljc/yin/vm/ucf/rows.cljc`, `test/yin/vm/ucf/rows_test.cljc`, `test/yin/vm/ucf/rows_custody_test.cljc`, `test/yin/vm/semantic_register/scanner_goldens.cljc`

# Review: lint and scanner repair on the merged phase-7 branch

## 1. The four address-validation sites

**Site 1, `decode*` row hash (`rows.cljc:909-914`).** The hashes step now
parses the row id first; a registered non-BLAKE3 algorithm is refused
`:yin.k/profile-mismatch :row-algorithm`, then the digest is verified with
`jing/segment-matches?` and any mismatch (including a malformed id, which
`parse-segment-address` answers nil for and the `when-let` skips) stays
`:yin.k/hash-mismatch :row`. Order: the check sits at the head of step 4
(hashes), after gate, structure and graph (continuation §7.2; transport
tuples §8 steps 1-3); the parse is cheaper than the digest, so the
refusal precedes the hash as it should. For a carrier whose profile fixes
BLAKE3 this is the right policy: `emit` mints under the default and
`rows_test.cljc:110` pins `:blake3`, so no legitimate version-3 pack can
carry a SHA-256 row id; and a forged pack gains nothing, because the
digest is still checked under the algorithm the address names. The
refusal is also not the only guard: with it removed (mutant 5) the
SHA-256 root is still refused by canonical numbering
(`:yin.k/undecodable :noncanonical-cursor-cells`), so the new check
names the defect earlier and more precisely rather than closing a hole.

**But the status word diverges from the published UCF text (F1, below).**
`docs/design/yin.vm.ucf-transport-tuples.md:128-130` says a reader
"refuses an id whose algorithm is not the one version 3 mints under
(`:yin.k/undecodable`)", and step 4 (`:756-761`) folds the same case into
`:yin.k/hash-mismatch`. Before this commit the code answered
`:yin.k/hash-mismatch :row` (step 4's reading). The commit introduces a
third status, `:yin.k/profile-mismatch`, that neither passage names, and
the engineer report presents it as a design choice without citing the
spec. `:yin.k/status` is a dispatch contract for readers (profile-mismatch
is the gate's "unsupported version/profile" family, undecodable the
"this pack is not well formed" family). One of code or spec must move;
the spec's dedicated paragraph already decided `:yin.k/undecodable`.

**Site 2, `decode*` slot 11 carried code (`rows.cljc:917`).**
`segment-matches? address v` verifies a store address under its own
algorithm; `:yin.k/hash-mismatch :code` and the well-formedness `bad!`
follow unchanged. Correct for a STORE address (A is the identity; the
algorithm is self-described). Mutant 6 (BLAKE3-only) killed.

**Sites 3 and 4, `check-install!` (`rows.cljc:1850-1852`).** The manifest's
`:yin.semantic/code` address verifies the record under the algorithm that
address names; the record's `:yin.ledger/output` verifies the delivered
image under the algorithm that address names. The two are independent
conjuncts of one `and` together with the tree-equality conjunct, so a
record minted under one algorithm and an image under another both pass
(tested, three combinations) and dropping either conjunct is caught
(mutants 8a, 8b). No forged install passes on one conjunct alone.

**Lint.** `test/dao/jing/hash_registry_contract_test.cljc` is not in the
diff; `flags-equality-validation?`, `validation-target-call?` and the
sweep are unchanged. The four sites are satisfied by replacing the
equality with the registry's total predicate, not by hiding a
`segment-key` call behind an alias or a let (the lint walks nested forms
but not let-bound aliases, so that dodge was available and was not
taken). For every existing BLAKE3-addressed pack `segment-matches?`
computes the same digest the old `(= id (segment-key body))` did, so
default-algorithm equality semantics are preserved; the full rows suite
with its root pins is green (§2).

## 2. Verification (all foreground, JVM)

| Run | Result |
|---|---|
| `clojure -M:test -e :slow -n dao.jing.hash-registry-contract-test -n yin.vm.ucf.rows-test -n yin.vm.ucf.rows-custody-test -n yin.vm.ucf.rows-checkpoint-test -n yin.vm.semantic-register.scan-test -n yin.vm.semantic-register.layout-test -n yin.vm.semantic-register.safepoint-harness-test` | 121 tests, 10056 assertions, 0 failures, 0 errors (`target/p7m-review/lane.log`) |
| `clojure -M:test -v yin.vm.semantic-register.scan-test/goldens-match` (slow, every group) | 1 test, 67 assertions, 0 failures |
| `clj -M:kondo --lint` on the four changed files | 0 errors, 0 warnings |
| `git diff --quiet` after all mutants | clean; worktree carries only untracked `collab/` files |

No existing root id or pin moved: the rows suites that check the pins
are in the green lane above and the diff adds tests only.

## 3. Scanner golden regeneration

Independently recomputed through a throwaway test namespace (the direct
`clojure -Sdeps` form of the reproduction script needs approval in this
session; the probe ran the same `scan/old-answer` over
`(:ast (prelude/module-spec registry))`, rebuilt the fixture text exactly
as `collab/1792570000000-engineer-p7m-scan-golden.clj` does, and compared
with `=` against the committed file): the regenerated text is **byte for
byte identical** to `scanner_goldens.cljc`; the answer equals
`new-answer`; `clojure.data/diff` between the fixture's `[:c4 :py]` and
the recomputation is `[nil nil ...]`; every other group's recomputation
still equals the fixture (71 assertions, 0 failures). Probe deleted.

Attribution: `git log 58dc0964..e6337294 -- src/cljc/yang/python/antlr/prelude.cljc`
shows exactly `c63ba6d6` (C4 I2) and `544e99e1` (C4 I3), both ancestors of
the merge base. The diff's definition heads are: `getattr` split into
`lookup-attr` + `getattr` (explains every `-[attr-error true]` line and
the `getattr`/`lookup-attr` free-name additions), and twelve new
definitions `no-module import-sub dict-ref import-from star-name
star-item star-copy-all star-copy-public import-star import-fail
run-session lookup-attr`, which is the report's `:definitions-added` set
exactly. No definition was removed. Only `[:c4 :py]` changed in the
fixture (the header note plus that map; the slow goldens-match over all
groups is green). Not a scanner bug; not caused by the rows/derive work.

## 4. Regression tests are real

- `carrier-row-algorithm-is-part-of-the-profile`: honest SHA-256 root
  (digest verified with `segment-matches?`), refused with the named
  status and kind; killed mutant 5.
- `carried-code-verifies-the-address-algorithm`: honest SHA-256 carried
  code decodes to the same slot; corrupted content refuses
  `:yin.k/hash-mismatch`; killed mutant 6.
- `install-store-addresses-verify-under-their-own-algorithms`: three
  algorithm combinations lower and attach; killed mutant 7. Its negative
  case is weaker than it reads (F2): it corrupts `:yin.ledger/output`
  after the record address was minted, so the record-address conjunct,
  not the image conjunct, is what refuses. The image conjunct is
  nevertheless covered by the pre-existing test (`rows_custody_test.cljc:299,306`),
  which is what killed mutant 8b.

## 5. Mutants (cp backup; restore verified by `git diff --quiet -- src`)

| # | Mutant | Witness run | Result |
|---|---|---|---|
| 1 | row hash reverted to `(= id (segment-key body))` | lint sweep | killed (1 failure) |
| 2 | carried code reverted to `(= address (segment-key v))` | lint sweep | killed (1 failure) |
| 3 | manifest record reverted to `(= (segment-key record) addr)` | lint sweep | killed (1 failure) |
| 4 | install image reverted to `(= output (segment-key image))` | lint sweep | killed (1 failure) |
| 5 | row-algorithm refusal removed | rows-test | killed (2 failures; the SHA root then refuses as `:yin.k/undecodable :noncanonical-cursor-cells`, i.e. numbering, not acceptance) |
| 6 | carried code verified only under BLAKE3 | rows-test | killed (1 error: the SHA-256 address refused) |
| 7 | install image verified under the record address's algorithm | rows-custody-test | killed (4 failures, 2 errors; the two mixed combinations) |
| 8a | manifest-record conjunct dropped | rows-custody-test | killed (2 failures, by the existing "changed record with honest input and output" case) |
| 8b | image-output conjunct dropped | rows-custody-test | killed (4 failures, by the existing cases at :299 and :306; the new test's negative did NOT fire, see F2) |

No mutant survived.

## Findings

| id | severity | file:line | finding | fix |
|---|---|---|---|---|
| F1 | medium | `src/cljc/yin/vm/ucf/rows.cljc:912`; `docs/design/yin.vm.ucf-transport-tuples.md:128-130, 756-761` | A non-BLAKE3 carrier row id is refused `:yin.k/profile-mismatch :row-algorithm`, but the UCF transport-tuples text says such an id is `:yin.k/undecodable` (its row-id paragraph) or `:yin.k/hash-mismatch` (step 4, which is what the code answered before this commit). A third status is introduced without amending the spec; readers dispatch on `:yin.k/status`. | Owner's call, one line either way. Default: code follows the spec's dedicated paragraph, `(bad! :row-algorithm [id])` and the test's expected status becomes `:yin.k/undecodable`; or keep `profile-mismatch` and amend both spec passages (and the continuation §7.2 hashes row) in the same commit. |
| F2 | low | `test/yin/vm/ucf/rows_custody_test.cljc:324-327` | The new test's "wrong image" negative is refused by the record-address conjunct (the record changed after its address was minted), not by the image conjunct it means to exercise; mutant 8b passed this test. | Re-mint the record address after replacing `:yin.ledger/output` (and put it back in the manifest) so only the image conjunct can refuse; then the test kills 8b on its own. |
| F3 | info | `src/cljc/yin/vm/ucf/rows.cljc:909-912` | The explicit algorithm refusal is redundant for safety (numbering refuses the same pack) and exists to name the defect early; the comment says only "fixed by the profile". | Optional: extend the comment with the numbering fact so a later reader does not remove the check as dead or, conversely, believe numbering could be relaxed. |

Everything else is sound: the four sites are algorithm-aware in the way
the lint and `dao.jing.call-site-classification.md` intend, the lint was
satisfied without weakening the test, the golden regeneration is
mechanical and reproduces byte for byte with a fully attributed diff, the
lane is green and all nine mutants are killed.

VERDICT: CHANGES_REQUESTED
