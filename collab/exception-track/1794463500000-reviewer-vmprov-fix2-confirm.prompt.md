Created-GMT: 2026-11-11 18:20:00 GMT
Created-Local: 2026-11-12 01:20:00 +07 (+0700)
Coding-Agent: glm
Role: Reviewer (re-verification of the admission-boundary corrective commit)
Session-ID: a335ea82-4a69-4a3c-8f04-9901f9228d42
# Verify the corrected admission: security closure retained + legitimate macros restored

Context: the provenance primitive (branch `exception-vm-provenance`) had a review cycle. The
first corrective commit (566c5450) closed three admission findings but over-rejected: an
identity macro passing through compiler-generated symbols — `(id (let [[x] [42]] x))`,
`(id (fn [[x]] x))`, `(id (loop [x 0] x))` — failed with :reserved-prefix, breaking legitimate
macros. A prior reviewer also noted a deleted invocation-time regression test.

The second corrective commit is **50c9485a** (`git diff 566c5450..50c9485a`, read-only). The
engineer reports: macro lowering never mints reserved names (macro-internal temporaries use the
prelude gensym), so every reserved name in macro output is either retained compiler-minted
syntax (trusted, passes) or a forged quoted packet (rejected); admission consults that origin
distinction rather than the prefix. The deleted invocation-time test is restored verbatim; the
three passthrough probes are permanent tests asserting :ok; provenance + macro namespaces run
41 tests / 334 assertions, 0 failures.

## Your job (worktree /Users/sto/workspace/datomworld-vmprov, branch exception-vm-provenance)

1. Re-run the three identity-macro passthrough probes — each must be :ok at 50c9485a.
2. Re-run the admission rejections — guest-spelled reserved construction names through source,
   macro expansion, and indirect application must still reject.
3. Class coverage: sibling shapes (reserved lambda binders, store-get/store-put keys, nested
   references, the reader query door) through macro passthrough — legitimate occurrences must
   pass, forged constructions must reject.
4. Confirm the deleted regression test is restored, and the diff weakens no existing test.
5. Run `clojure -M:test -n yin.vm.provenance-test -n yin.vm.macro-test` and kondo on the changed
   files; report exact counts. Do NOT run yang.call.exception-contract-test (remote-only, owner
   ruling).

## Deliverable

A findings file at
`/Users/sto/workspace/datomworld/collab/exception-track/1794463500000-reviewer-vmprov-fix2-confirm.glm-5.3.findings.md`
with per-item results (evidence + counts), then a final line: **CONFIRMED** or **STILL OPEN:
<what>**. Modify nothing except creating that one findings file. Begin with
Completed-GMT/Completed-Local.
