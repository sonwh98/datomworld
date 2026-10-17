Completed-GMT: 2026-10-10 16:10:36 GMT
Completed-Local: 2026-10-10 23:10:36 Asia/Bangkok

(The host clock read earlier than the prompt's Created stamp when this
report was written; the times above are the clock's.)

# Report: phase-5 derivations design, revision round 2

Edited: `docs/design/yin.vm.semantic-register-vm.derivations.md` only
(now 1730 lines). Frozen design, source, tests and the C4 track
untouched; nothing staged or committed.

## Per item

1. **Raw C4 oracle excludes the definition operator** (§6.3 corpus 3,
   5c item 7). The raw equality is now
   `(= (scan/free-names v4) (disj (set (prelude/free-names ast)) vm/definition-operator))`,
   with the reason stated, and the acceptance item carries the same
   qualification. **Verified** by reading `prelude.cljc:3395-3414`: the
   walk's default branch is `(reduce … (vals node))`, so for an
   `:application` it visits the operator map `{:type :variable :name
   yin/def}`, whose `:variable` branch adds the name when it is not a
   parameter; `yin/def` is never a parameter (Rule R), so it is reported.
2. **Allocation lemmas withdrawn and replaced** (§3.2). The root-result
   and call-destination lemmas are removed. **Verified** the
   counterexample by deriving `((f))` myself under §8.2's rules: A is
   `[:var 2 f] [:call 1 2 [] false] [:call 0 1 [] false] [:halt 0]`;
   intervals `v2:[0,1] v1:[1,2] v0:[2,3]`; at pc 2 `v2` has expired
   (`end 1 < 2`), so the outer result takes `T0`, which is R1's `T0` as
   well. The text now says explicitly that a child's result is not its
   first instruction and makes one narrow claim only, the
   atomic-operator lemma: a call whose operator is a single atom at the
   body's first pc has its §8.2 destination above `T0` (the operator's
   interval `[0, call-pc]` holds `T0` at the call) and its R1 destination
   below the operator's slot (allocated before it). B0 is covered row by
   row by that lemma (fourteen call roots with a `:var`/`:closure`
   operator at pc 0, two `if` rows via the `<` test call); the register
   corpus rows it covers are listed individually by inspection of the
   goldens, and twelve rows are explicitly left to the gate with no
   claim.
3. **Export gate permits both address readers** (5a item 4). The test
   now forbids functions that assign an executable identity (names
   containing `hash`, `key`, `identity`, `digest`, or named
   `address`/`code-address`) and permits `addresses` and
   `resolved-addresses` by name, asserting each returns a vector of
   `[:bound d q]`/`[:free sym]` tuples, never a string or keyword.
4. **F11 replacement aligned with §6.3** (§8 F11). The frozen-text
   replacement now states the three obligations: (i) unordered
   agreement on sets and the `[name in-body?]` / `[name conditional?]`
   multisets plus the requirements sets, (ii) ordered agreement against
   the emission-order tree oracle (main then FIFO bodies, definition
   visits only its value; sites exclude definitions and include FFI),
   (iii) join agreement through `linker/verify` on the complete
   normalised outcome, success with retained obligations or refusal with
   reason and name, `:use-before-definition` exercised. It also records
   that the C4 gate is read-only and compares declarations against the
   discharged set.
5. **Runtime corpus described correctly** (5a item 8). The register-corpus
   part of the runtime corpus is now twelve selected rows (the ten
   value rows plus the two closure-valued rows `:body-queue-order` and
   `:closure-in-arm-in-body`, which normalise to `:closure`), and the
   twenty exclusions are enumerated by reason: seventeen read an unbound
   name, two resume an unknown parked id, one needs an FFI bridge.
   **Verified** against `corpus.cljc:54-118` row by row, including that
   `:if-in-tail` (n=3 reaches `loop`) and `:all-tail-arms` (0 is truthy,
   reaching `f`) throw on an unbound name, and that `:body-queue-order`
   and `:closure-in-arm-in-body` reference no free name.

## Unverified

- The `((f))` derivation and the atomic-operator lemma are hand
  derivations under the two allocators' stated rules; no §8.2
  implementation exists to run. R1's side of the lemma rests on
  `lower-node!`'s allocation order (`debruijn_register_compile.cljc:188-197`)
  as read.
- The per-row register-corpus coverage list in §3.2 is by inspection of
  the goldens' first instruction and root call, not by running an
  allocator.
- Whether the two closure-valued rows halt on both native kernels under
  the stated composition was not run; byte comparison over all 32 rows
  does not depend on it.
- No tests were run in this round; verification was by reading
  (`grep`, `sed`, `date`).
