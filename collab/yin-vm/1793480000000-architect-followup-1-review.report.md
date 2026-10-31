Completed-GMT: 2026-10-15 22:40:01 GMT
Completed-Local: 2026-10-16 05:40:01 +0700
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending (harness-assigned)

# Architect review: follow-up-1 (uncommitted, srvm-followup-1 @ 9ab9e792) + ruling on the stopped binder-refusal sub-part

Role: Architect. Read-only except this report.

Inputs read: the brief, the engineer report (`collab/1792103442788-engineer-followup-1.report.md`), gap-list G12/G13 and §4.1, frame-nan review P2-1, the full `git diff` (22 files, +135/-77) and `git diff -w` of `parity_test.cljc`, `dao.jing.cbor` (equiv, collapse?, content-key, members-wire, check-members, built), `ucf/rows.cljc` (emit, check-abstract!, decode*, lift-task*, check-dependencies!, the lower's `row` builder), `semantic/derive.cljc`, the two native opcode tables, `debruijn.cljc` `slot-kind-ok?`, the stage E process harness, the orchestrator log entries of 2026-10-14 14:30, 2026-10-15 01:08 and 2026-10-16 19:30.

Verification I ran (JVM only): `clojure -M:test -e :slow -n yin.vm.ucf.rows-test -n yin.vm.semantic.safepoint-harness-test -n yin.vm.semantic.parity-test` → 74 tests, 2215 assertions, 0 failures, 0 errors. Node and Dart not run (orchestrator). `cljstyle check` on `parity_test.cljc` needed an approval I did not have; not run.

## Verdict in one paragraph

Parts 2 and 3 are sound and may land as their own commits (two P3 doc pointer fixes below, non-blocking). Part 1(a) is CHANGES-REQUIRED: `cbor/content-key` is not the encoder's notion of map/set key distinctness, and the change opens a hole master did not have: a `:map`/`:set` row whose keys the host collection merges (0.0 and -0.0 on every host, 1 and 1.0 on Dart) now decodes and then loses an entry silently in the lower, the exact loss `dao.jing.cbor`'s `built` refuses as `:host-collapse`. The right rule is the encoder's `equiv` collapse (`:equality-collapse`), refused on every host in emit, decode and the abstract check, plus a `built`-style guard in the lower. Part 1(b) is RULED (i): close as not applicable; the invariant and its pins are stated below. The verdict for the whole diff is CHANGES-REQUIRED, scoped to part 1(a) and two ledger rows.

## P1 — Part 1(a): `decode*` key distinctness (P2, blocking)

### What the encoder's notion actually is

`dao.jing.cbor` has two equalities and uses them for different jobs:

- `content=` / `content-key` (cbor.cljc:882-913): kind-strict value identity ("the distinctions content addressing makes"; `(content= 1 1.0)` is false; 0.0 and -0.0 differ). Used for *value* identity: row order, NaN slots, the frame's `bindings=`, the 8e collision fix.
- `equiv` (cbor.cljc:794-823, `num=`, lists = vectors, identifiers by fields): "the equality whose collapses encode and decode refuse". This is the *membership* notion. `members-wire` (:1035-1050) refuses `:duplicate-key` on equal canonical bytes and then `:equality-collapse` when two keys are `equiv`; `check-members` (:1393-1401) does the same on decode; `built` (:1404-1412) refuses `:host-collapse` whenever building the host collection lost an entry ("losing an entry is never acceptable").

So for map/set keys the encoder's own notion is: canonical-bytes distinct AND no `equiv` collapse, identically on every host, and the host collection must hold every member. Under it, `{0.0 _ -0.0 _}` and `{1 _ 1.0 _}` are refused as `:equality-collapse` on the JVM, Node and Dart alike. `content-key` admits both.

### Why admitting them is a hole, not a fix

The row codec's `:map`/`:set` rows are flat slot lists, so they can express key pairs no host collection can hold. The lower rebuilds them as host collections (`rows.cljc:2123-2124`, `(into #{} …)` / `(into {} …)`) with no count check:

- 0.0 / -0.0: Clojure's `=` and `hasheq` equate them on the JVM (codex's own probe: host `distinct` count 1), JavaScript and Dart equate them too. A decoded `{0.0 x -0.0 y}` row lowers to a one-entry map on every host. On master the row was refused (by accident of host `distinct`, under the wrong name); on this branch it decodes, re-emits with the same root (the test asserts this) and then drops an entry at lower.
- 1 / 1.0: the JVM holds both; Dart merges them, JavaScript cannot even represent the pair without the `Float64` carrier. The old "Dart-only refusal" P2-1 named was host-dependent, which was the defect; the host-independent resolution is to refuse everywhere, not admit everywhere.
- The same task carrying the same map as a *literal* (`[:lit {1 :a 1.0 :b}]`, JVM-built) is refused today at emit by `cbor/encode` → `:equality-collapse`. After this change the row form of the same value is admitted while the literal form is refused: the codec disagrees with itself.

Answers to the brief's three questions: (1) no, `content-key` is content addressing's value identity, not the encoder's key notion; emit and decode now agree with each other but not with `cbor`'s literal path nor with the lower. (2) Inputs that change status, beyond the two target pairs: newly admitted on the JVM, `1.0M`/`1.00M` keys (host `=` merges, content-key separates; `equiv` would refuse them too, correctly); on JavaScript, slash-crossed keywords/symbols (`(keyword "a/b" "c")` vs `(keyword "a" "b/c")`: host `=` merges, both `content-key` and `equiv` separate) now decode and then merge at lower, which is why the lower needs the `built` guard regardless of the decode rule. Nothing that decoded before is newly refused: everything host `=` separates, `content-key` separates (metadata is ignored by both). (3) The expanded-key leaf change (`:783`, `[:literal (cbor/content-key x)]`) is sound in itself but carries the same wrong notion into `check-abstract!`'s `(distinct decoded)` (:811), which remains host-`=` and therefore still host-dependent for 1/1.0. (4) The test `collection-row-keys-use-content-equality` pins exactly what it says (decode accepts, root stable across re-emit, 12 assertions, wrappers keep the kinds apart on JavaScript, and the int/float mutant is Dart-only, as codex states) but it pins the wrong invariant: it never lowers, so it cannot see the entry loss.

### Required change (minimal; three code sites, one visibility change, one test)

1. `dao.jing.cbor`: make `collapse?` public (`defn-` → `defn`, docstring as is). No other cbor change; descriptors, fixtures and the conformance digest are untouched (no bytes change).
2. `rows.cljc` `decode*` :931-935: keep the canonical-bytes `:duplicate-key` clause; replace the `content-key` `distinct` clause with `(when (cbor/collapse? keys) (bad! :equality-collapse [id]))`. The rule name mirrors cbor's so a reader sees one policy.
3. `rows.cljc` `check-abstract!` :811: `(when (cbor/collapse? decoded) (bad! :equality-collapse path))` in place of the host-`distinct` check; revert :783 to `[:literal x]` (`equiv` already compares numbers by `num=`, identifiers by fields, lists as vectors, so the tagged structure needs no leaf normalisation).
4. `rows.cljc` lower, the `row` builder :2123-2124: after building a `:set` or `:map`, refuse when the host collection is shorter than the member count (mirror `cbor/built`; name it `:host-collapse` under the lower's existing refusal vocabulary at the row's path). This is the only line that protects JavaScript's identifier merge.
5. `test/yin/vm/malformed_rows.cljc`: add one `:equality-collapse` entry (a `:map` row with keys `1` and `(cbor/float64 1.0)`), so §7.2 part 3's same-refusal conformance covers the new rule like every other row rule.
6. Replace `collection-row-keys-use-content-equality` with `collection-row-keys-refuse-equality-collapse`: both pairs × `:map`/`:set` → decode refuses `:equality-collapse` at the row id on every host (keep the `float64-from-bits` / `float64` construction, it is right); plus the literal twin on the JVM only (`[:lit {1 :a (cbor/float64 1.0) :b}]` in a task → emit refuses with cbor's `:equality-collapse`; print a skip on JS/Dart where the host map cannot be built), pinning that row and literal agree. Mutant proof: reverting to host `distinct` fails on the JVM for the 1/1.0 pair (admitted) and on every host for the signed-zero pair by rule name (`:duplicate-key` ≠ `:equality-collapse`), so the mutant dies on all three hosts, which the current test cannot claim.
7. Ledger row 25 reopens with this wording: "closed in follow-up-1 (round 2): map/set row keys refuse `:equality-collapse` under `cbor/equiv` on every host (emit, decode, abstract check); lower refuses `:host-collapse` when the host collection merged members; JVM verified, Node/Dart assigned to orchestrator".

Not required: no descriptor, profile, golden or pinned A moves (codex confirmed none moved in round 1; the rework touches no encoding).

## P1 — Part 1(b): RULING on the stopped binder-refusal sub-part → (i), close as not applicable

Codex's finding is correct and complete. The ruling:

**Invariant.** Binder names exist in exactly two artefacts: the tree and A (the `:lambda` row's `:syms` slot). Every A enters the kernel through one validator, `debruijn.cljc` `slot-kind-ok?` (:527-538; `:syms` = vector of symbols; rule `:slot-kind`), whichever producer hands it over (`load-ast`, `load-vector`; the row-local rules at `linker.cljc:620-640`). Every native image is a function of an admitted A and carries no binder: a `:closure` is `[arity body-pc]` on the stack and `[rd arity body-pc]` on the register dimension, the arity being `(count params)`. A native image therefore has no binder slot, so "a hand-built native image with a non-symbol binder" is not expressible, and a native validator has nothing to refuse. The 8e review's P2-3 concern dates from when the lowerers took the AST; since 8e they take A, and the defence moved with the data it defends.

**Where it is pinned (existing, no new test):**
- the frozen opcode tables: `debruijn_code.cljc:55` (`:closure [[arity :uint] [body :pc]]`, lowering-contract-version 2) and `debruijn_register_code.cljc:83-85` (`[rd :reg] [arity :uint] [body-pc :pc]`, "r2"), with their descriptor tests; `debruijn_register_code_test.cljc:74` pins the shape string `[op rd arity body-pc]`;
- `derive_test.cljc:179` pins `[:closure rd (count x) body]` for every lambda of the corpus (the parameter vector becomes its count);
- `debruijn_code_test.cljc:566` pins a closure value as `{:type :closure :arity 1}`, "arity only, no params or body";
- `host_typed_values_test.cljc:242-247` pins that every bad binder is refused at the projection with `{:rule :slot-kind :path [1 1]}` for all four runners.

The binder-shaped rules a native validator *can* enforce already exist and are the real defence in depth on that side: `:closure-arity` / `:closure-target` (`debruijn_register_code.cljc:512-519`, a body's `:locals` must equal its closure's declared arity) and the stack dimension's closure body/arity consistency (`debruijn_code.cljc:780-820`).

**Consequence for the ledger.** Row 21 closes: "closed in follow-up-1, not applicable: native images carry no binders (closure = arity + body pc, derived from A; pins: opcode tables under contract 2/r2, `derive_test:179`, `debruijn_code_test:566`); the one binder validator is the projection's `:slot-kind` rule (`host_typed_values_test:242-247`); the native-side defence is the existing `:closure-arity` rule." No format, descriptor or validator change. If a future format ever carries names in a native image, this row reopens; under "A is the only code identity" it must not.

## P2 — Part 2: tests (accept)

- `park-beside-a-live-reader-wait` (safepoint_harness_test.cljc:2015-2034): an explicit park beside a reader that has consumed A and waits for B, lifted and lowered; the park resumes independently, the reader survives the resume, B wakes it to completion. Concrete outcomes, no reference comparison, so not a tautology; the "drop restored parked map" mutant is the right one. 
- `noise-before-the-correlated-ffi-response` (:2037-2058): the carried sent-FFI waiter ignores an unrelated response on the served stream, wakes on its own id, and the receiver's own request stream stays empty. The "route to the first waiter without comparing ids" mutant is the right one. Both exercise the 8d partial mappings the ledger names, not law-1 equality. Re-ran green here.
- `malformed-vectors` deletion: no reader in `src`/`test`/`docs` (the only hit is the unrelated `scan-test/malformed-vectors-throw` name); the namespace docstring now describes what remains. Safe.
- `parity_test.cljc`: `git diff -w` is the alias removal and the `:refer` additions only; every assertion form and expected value is unchanged. P3 (cosmetic): the continuation lines of `slice-4a-extra-rows` (:111-121) and the `definition-programs` entries keep the old `corpus/` column, so they are over-indented by seven columns; run `cljstyle fix` on the file before the commit (whitespace only, re-check `diff -w` empty).

## P3 — Part 3: docs (accept with two pointer fixes)

Verified against code:
- UCF §7.11 clause 10: "four JVM/Node host pairs on jupiter, 196 SIGKILLed lives" matches `stage_e_process_test.clj:42-45` (`[:jvm :jvm] [:jvm :node] [:node :jvm] [:node :node]`) and log :11248. P3 fix: the pointer "(2026-10-14 14:30 +07 handoff and 2026-10-15 continuation)" is half wrong: the 2026-10-15 01:08 entry records Node/Dart as *unrun* for srvm-phase8; the Node/Dart lane evidence is in the 14:30 handoff (phase 7 head: Node 153 tests, Dart +126) and in the 2026-10-16 19:30 +07 landing entry (Node fast green, Dart +3900). Cite those two.
- `yin.vm.linker.dht.md:3399-3401`: `assert-agreement` lives in `scan_test.cljc:129` and is applied to the b0, register and C4 corpora; wording accurate.
- `linker.cljc:657,675` and `layout.cljc:13`: checksum/identity wording correct.
- `scan.cljc` docstring: the namespace builds `query/relation` over `segment-rows` of the vector in hand (:94-95) and consults no index; the "Peer observers" sentence matches `datom.world.md:130`. Accurate.
- `vm.cljc:998-1001`: `yin.vm.code-as-tuples.md` §2.3 is "The table". Accurate.
- `:tree-unavailable`: `linker.md:28-33` and :240-247 now name the code's `:yin.k/unsatisfied` / `:yin.k/missing {:yin.k/trees …}`. P3 fix: ":243 says 'lift refuses'; the refusal is raised at `lift-task*` (`rows.cljc:1684-1686`, against the source index) and at the receiver's `check-dependencies!` (`rows.cljc:1908-1911`, step 9 of lower); the :243 context is the receiver's by-identity flow, so say 'the carrier refuses … (lift against the source index, lower at the dependency check)'. `yin.vm.semantic.derivations.md:1379` still prints `:tree-unavailable` inside the quoted amendment-text table; it is a record, leave it.
- The eight citations: the diff of every code file is comments, docstrings and one assertion message only (checked line by line). `#history` anchor exists (`yin.vm.semantic.md:19`); "§10 phase 8" exists. P3 fix: `linearize_test.cljc:286` retargets to "yin.vm.semantic.md §3.1 (canonical projection)", but the worked example `((fn [x] (+ x 1)) 10)` does not occur in that document; it is `yin.vm.semantic.derivations.md` §6.3 hand-derived goldens, row 6 (:764). Point there.
- `checkpoint.cljc` docstring: commit e1f6a580 (8f-1) deleted `ucf/checkpoint.cljc` and `ucf/handoff.cljc`; accurate. `bytecode-vm-optimization.md`: `vm/opcase` died in 8f-3 per the ledger; accurate.
- known-gaps: the new C4 scanner-pin row matches the brief (owner yang.python seat / muse, regenerate at the end of I5/I6 with Architect review). Row 25 (decode* keys) must be reopened per P1 and row 21 (binder) closed per the ruling, wording above. The other closures (22, 26, 28, 29) are correct.

## What lands, and how

Per the Q6 standing rule (scoped blocking): parts 2 and 3 are signed off as two separate commits (`test(yin.vm.semantic): …` and `docs(yin.vm): …`), each after the P3 pointer fixes above and the `cljstyle fix`; Node and Dart discovery proof ("Testing yin.vm.semantic.safepoint-harness-test", "Testing yin.vm.ucf.rows-test") is owed before the merge, one lane at a time. Part 1(a) goes back to the engineer as round 2 with the seven steps above and the ledger wording; one Architect read of the round-2 diff before its commit. Part 1(b): no code, ledger row 21 edit only (may ride on the docs commit).

CHANGES-REQUIRED
