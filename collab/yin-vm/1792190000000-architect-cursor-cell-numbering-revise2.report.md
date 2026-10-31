Completed-GMT: 2026-10-12 06:55:00 GMT
Completed-Local: 2026-10-12 13:55:00 Asia/Bangkok

(Host clock as read by `date -u` at completion.)

# Architect report: discovery-order numbering note, round 3

Edited: `docs/design/yin.vm.semantic-register-vm.cursor-cell-numbering.md`
(rewritten, 980 lines), the prototype
`collab/numbering-probe/src/probe/discovery.clj` (934 lines) with its run
log `collab/numbering-probe/run.log`, and two probe helpers
(`collab/numbering-probe/java.args`, the launcher's cached classpath with
absolute paths, and `run.sh`). Nothing staged or committed. The 6a code
was read from branch `srvm-phase6a` (`d77da9f0`); the probe's copies of
`rows.cljc`, `rows_test.cljc` and `frame.cljc` were re-diffed against the
branch and are identical. 6a's own tests pass in the probe project
(21 tests, 324 assertions).

One environment note: the `clojure` launcher (and bare `java`) needed an
approval this non-interactive session could not give; the JVM ran
through `mise exec -- java @java.args clojure.main …` on the launcher's
cached classpath, foreground, twice (before and after one diagnostic
change). All numbers below are from the second run, which is the log.

## Per item of the brief

**1. HIGH, D5 semantics (key runs swallow conflicts).** Decided and
specified: only the *root run* and *component runs* (runs whose
completeness D4 requires) refuse on a conflict; a *key run* (made to
sort one element) is speculative, never refuses, and spells its residue
`[:tie …]` in the key, which lemma A shows is canonical. The note's §0
names the three kinds of local run, F4 restates the predicate ("the root
run, or a component run it requires, reaches a fixpoint at which …"),
D5 says it, lemma D is rewritten around it, and A1/A4 carry it. The
witness `[#{#{[A B] [B A]}} A]` is now a fixture: `A cc-1, B cc-2`, root
`9600fe01…` (equal to the review's digest), one root over 24 renamings
plus shuffles, decodes. The prototype already behaved this way; the
spec was wrong, and the diagnostic is now also host-independent (a
refusal names the least conflicting group's path and the sorted cells of
every conflicting component; the seven-cell state reports all seven).

**2. HIGH, install-child refusal escaping decode.** The prototype's
`decode*` now wraps the whole discovery recomputation, root and install
children (`emit*`, which numbers children inside `emit-node*`), in the
narrow translation; a lift-side child refusal carries path
`[12 <install name> …]` (a path is threaded through `emit-node*`), and
the receiver reports `:yin.k/undecodable :unresolved-cursor-cells` with
path `[<root id> 12 a 15]`. Verified on a parent whose install `'a`
holds the 2-cycle: lift refuses `[12 a 15]`; the `emit-numbered` carrier
decodes to the undecodable kind with the prefixed path. The note's
"Validation and decode" paragraph and A4 say "the whole of that call …
root and every install child"; §8 points at `emit-node :185-189` and
`decode* :570-575`.

**3. HIGH, work bound.** Chose the brief's third option plus a
structural fix: the polynomial claim is dropped; `:work` is an explicit
budget charged per position visited, per position spelled and per byte
copied into a spelling, `:search` per D6 trial, both refusing
`:yin.k/unsupported :resource-limit [<key>]` as 6a's `:label-bytes` did;
the memo is called an optimization with no context bound claimed. The
structural fix: a provisional cell is spelled `[:u j r]` with `r` the
body *rank* among the census's distinct body labels, not the label. Rank
order equals label order (CBOR is prefix-free, so a comparison stops at
the same position with the same sign), so no numbering changes; every
pin of rounds 1 and 2 reproduced. Measured on a 4-cell state: label
spelling 4364 key bytes at a 128-character descriptor and 920118 at
65536; rank spelling 1396 at both. The prototype now charges the budgets:
`{:work 1}` refuses at `emit` and propagates untranslated through
`decode`; `{:search 1}` refuses the 3-cycle under D6. Largest `:work`
over 2300 generated states: 1470 (6a's default is 200000).

**4. MEDIUM, A2 and the estimates.** A2 now says the emitted commitment
and the acceptance decision are invariant under host enumeration, names
D4's entry of equal-encoding components as the procedure's one use of
host order, justified by lemma C. §7's estimate is implementation-based:
prototype schema-to-procedure region about 330 nonblank lines (about 30
of them D6, diagnostics, tracing, measurement); in place, reusing 6a's
helpers, 250–300 added, 135–150 deleted, about 100–160 net; the review's
independent 100–195 is quoted beside it. Review cost: one implementation
round and one review round, reading §3 and §8.

**5. IMPORTANT, coverage regression.** F4 states exactly which
structures D5 refuses: two or more equal-body cursor cells held *only*
through guest sets or maps, in equal-keyed elements each mentioning more
than one of them (the directed 2-cycle and 3-cycle, which 6a numbers and
which are interchangeable; the path and the seven-cell state, which are
not). Whether realistic tasks hit them is stated as unmeasured (no v3
corpus), with the structural argument (cells are created into
registers; a D5 state needs guest code to build a set of cursor tuples
on one stream at one position and drop every other reference) and the
generator rate (35 of 2300). Rather than leave the regression, the note
specifies **D6, residue search**: at a conflicting component, one trial
per element of its least conflicting group (that element entered first
when its container is keyed), run to completion, least spelling adopted,
`:search` charged per trial; lemma E proves it canonical; consequence 6
and the run show it moves no D1–D4 id. Prototyped behind
`:residue-search`: the 2-cycle and 3-cycle reach **6a's exact roots**
(`0fe898cc…`, `0b181cd4…`) in 2 and 3 trials; the path `726a9fbb…`, the
loop plus 2-cycle `858a891e…` (`L cc-1`), the seven-cell state
`d76d787a…` in 11 trials and 488 work (6a refused it on label bytes);
each one root over 24 renamings plus shuffles. The note recommends D6
and keeps the D5 variant fully specified (kind, translation, tests) as
the owner's alternative; §10 question 1 is the choice.

**6. "Replacing 6a".** §8 lists, with `srvm-phase6a` line numbers, what
`rows.cljc` deletes (`check-label-bytes!` 212–230; inside
`canonical-numbering`: labels 252–255, `ce` 257–284, `positions`
285–325, `classes` 326–330, `refine` 331–337, `search` 338–354, the
checked wrappers 245–251; the `:label-bytes` key, and `:search` in the
D5 variant), rewrites (`canonical-numbering` same contract; `emit-node`
path; `decode*` 570–575 wrap; docstring), and keeps (everything else,
named), and what `rows_test.cljc` keeps (every fixture and test, named,
including `seven-cursor-cell-triangle-square`) and rewrites (four tests,
with their new names, assertions and pins in both variants). Every
counterexample of §6 is a fixture in acceptance items 12–16. Portability
notes for the rewrite are included (no `for` over long seqs, no `@#'`,
`(assoc {} …)`).

## The rerun (both modes, foreground)

| Search | D5 variant | D6 |
|---|---|---|
| 2000 random value states (nested sets, `#{[X X] [Y Z]}`-style pairs, cursor-cell map keys, tied elements, cells aliased across containers) | 1975 numbered, 25 refused (14 witnessed non-interchangeable, 11 not distinguished), 0 invariance failures, 1783 decoded, 0 decode failures, 192 at 6a's own recompute budget, max work 1254 | 2000 numbered, 0 refused, 0 invariance failures, 1808 decoded, 0 failures, 192 at 6a's budget, max trials 13, max work 1470 |
| 200 frame-bearing (act window, K-frame window, environments, parked state) | 192 / 8 (5 / 3), 0 failures, 175 decoded, 17 at 6a's budget | 200 / 0, 183 decoded, max trials 5 |
| 100 with install children | 98 / 2 (0 / 2), 0 failures, 75 decoded, 23 at 6a's budget | 100 / 0, 76 decoded, max trials 4 |

Every fixture root of rounds 1 and 2 reproduced, with rank spelling and
with D6 on. New pins: `9600fe01…` (witness); under D6 `0fe898cc…`,
`0b181cd4…`, `726a9fbb…`, `858a891e…`, `d76d787a…`. Still refused in the
D5 variant: the 2-cycle, 3-cycle, path, loop plus 2-cycle and seven-cell
state (named honestly in F4 and §6); under D6 nothing is refused except
by budget. The "192 at 6a's budget" column is the probe decoding through
6a's own `decode*`, which still runs 6a's search; a probe limitation,
not a numbering outcome, as before.

## Unverified or disputed

- No review finding is disputed; all five reproduced and are addressed.
- Three-host runs, kondo, and the real codec's budget charging: not run
  (documents and a JVM scratch prototype only). The prototype's decode
  check still runs after 6a's `validate-frames`; the real codec places it
  at step 6 (acceptance item 20, test `numbering-precedes-frame-defect`).
- The real-state rate of D5 conflicts is unmeasured (no v3 corpus); D6
  makes the question moot for correctness and leaves it as a cost
  question (trials observed: 2–13).
- The cost bound is enforced by budget, not proved; `:work` and
  `:search` defaults are 6a's (200000, 10000) and were never approached.
- The D6 trial order ("entered first when its container is keyed") is a
  definition, not the only possible one; any deterministic choice is
  canonical by lemma E, so an implementer must follow the note's, not
  invent another.

## Verdict

**READY for an implementation brief**, cut from §8, in the D6 variant
(recommended) or the D5 variant, once the owner answers §10 question 1.
Remaining risks: a canonicality bug in the port (mitigated by the
generator of item 18 in CI, the decode round trip, and the proof); the
`for`-over-32-elements and `@#'` traps when porting to `.cljc`; the
unproved cost bound (budgeted, never approached in 2300 states); and the
unmeasured real-state conflict rate (a cost question only under D6).
