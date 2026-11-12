Created-GMT: 2026-11-11 19:05:00 GMT
Completed-GMT: 2026-11-11 19:47:15 GMT
Completed-Local: 2026-11-12 02:47:15 +07 (+0700)
Role: Engineer (python adoption round; branch owner: continuation-semantics orchestrator)
Coding-Agent: deepseek-v4-pro

# Findings — python provenance adoption (exception-py-r2r3)

Landed the five-point adoption on `exception-py-r2r3` (rebased onto master @
0f0ab852; the primitive 8c10c5b7 in ancestry). Two commits: 00ca2d1c (prelude)
+ 428bbdd7 (goldens). Pushed to origin.

## The five points

1. **Mint** — `py/condition-exception` -> `dsg__condition-exception`, now
   `(cell/dsg__new-provenance (cell/get (py/make-exc cls msg)) condition)`
   (prelude.cljc:1053): the complete original condition rides the private
   `:provenance` heap sibling, installed atomically at allocation; nil
   provenance is refused by the door.
2. **Read** — the :917 egress authenticates through `cell/dsg__provenance`
   (prelude.cljc:918); nil -> the plain fresh `:yang.call/thrown` path. Never
   fatal, never mutating.
3. **`:py/retained-condition` dies** — the key write (old :1051) and the slot
   read (old `py/retained-condition`) are removed; no `:py/retained-condition`
   remains in the prelude. Guest-visible wrapper writes stay legal and cannot
   touch provenance (`heap-write` preserves the sibling).
4. **Transitive-helper closure** — `dsg__condition-exception` is a bare
   (non-`py/`) `dsg__`-prefixed name: not in `module-exports` (which keeps only
   `py/` keys), not projected, not aliased, and unspellable by guest source
   (reserved prefix). `py/retained-condition` was removed entirely. No guest
   can mint arbitrary provenance through an exported constructor.
5. **Acceptance** — the three forgery contract pins flip GREEN; the ledger
   fixtures + parity pins stay green; the shared `source-call-test` stays
   green. Details below.

## Rewiring sites (file:line)

- prelude.cljc:205  — `host-names` gains `cell/dsg__new-provenance`
  `cell/dsg__provenance`.
- prelude.cljc:918  — egress reads `cell/dsg__provenance e`, `nil?` sentinel.
- prelude.cljc:1044 — `dsg__condition-exception` definition (mint).
- prelude.cljc:1053 — the mint body (`cell/dsg__new-provenance`).
- prelude.cljc:1074,1078 — the two mint call sites (ForeignException,
  not-callable TypeError).

## Pins green evidence

`clojure -M:test -n yang.call.python-exception-test` — **Ran 8 tests, 117
assertions, 0 failures, 0 errors.** The three forgery pins all green on
semantic/stack/register:
- `:forge`  -> `:yang.call/thrown`, original-forwarded false, payload-identical true
- `:clone`  -> `:yang.call/thrown`, original-forwarded false, payload-identical true
- `:overwrite` -> `:yang.call/not-callable`, original-forwarded true, replacement-forwarded false

`clojure -M:test -n yang.python.antlr.source-call-test` — **Ran 4 tests, 4
assertions, 0 failures, 0 errors.**

## Goldens evidence

Regenerated `test/yin/vm/semantic/scanner_goldens.cljc` (`deferred #{:py}`,
the run's single mover): `condition-exception` -> `dsg__condition-exception`,
`retained-condition` dropped, `cell/dsg__new-provenance` and
`cell/dsg__provenance` join the free-name set; `:application-count` 4046 ->
4039. Three-run byte-identical (sha256 33c9da34…); the untouched groups
verified in place by the abort-gate (no disagreement raised).

## kondo

Not run: the clj-kondo binary is absent on this host, and the `clojure -M:kondo`
alias (and `java -cp`) are denied by the sandbox here. The changes are confined
to quoted-data contexts (`defquoted` bodies and the quoted `host-names` set),
identical to the pre-existing `cell/new`/`cell/get`/`cell/set!` usages, so no
unresolved-symbol or arity surface is introduced. Flag for the steward if a
kondo 0/0 run is a hard gate.

ADOPTION LANDED: 428bbdd7123d7ef09f8916e1ea5fa8a466f42fc4
