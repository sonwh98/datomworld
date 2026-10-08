# Epic audit: the de Bruijn projection, D0–D4 (committed state `8ed66e3a`)

Task: collab/1789968388070-reviewer-debruijn-epic-audit.prompt.md. I read only the committed files through `git show debruijn-impl:…`. Line numbers below refer to `git show debruijn-impl:src/cljc/yin/vm/debruijn.cljc`. The P2 findings F1–F3 and P3 finding F4 were confirmed on the JVM. I did that by loading the committed file from /tmp into a REPL. I did not rerun any test suites.

## Findings

### P2

**F1. A set whose elements merge under canonicalisation collapses silently. The map rule diagnoses the same case.**
- `canonical-value` `:set` (line 982) is `(into (empty v) (map canonical-value) v)`, so elements that merge simply collapse.
- The `:map` branch (lines 974–981) and `encode-value` (lines 909–912) throw `:unsupported-value` on the same kind of merge.
- `merkle-node` hashes the pre-record after canonicalisation (lines 1050–1060), so the collapse reaches identity.
- Confirmed: `(lit #{1 1.0})` gets the same fingerprint as `(lit #{1})`. `(lit #{"é" "é"})` gets the same fingerprint as `(lit #{"é"})`.
- These programs differ in `count` and `seq`. That is a change in structure (cardinality), not the declared scalar 1≡1.0 or NFC limit. So a non-equivalent pair shares a fingerprint, against the D6 requirement that "non-equivalent fixtures remain distinct".
- Fix: in the `:set` branch, apply the map's count check (`(< (count canonical) (count v))` → throw `:unsupported-value`). Add one test next to `colliding-map-keys-diagnose` (JVM only for `1`/`1.0`, as that test already does).

**F2. The storage reader verifies the preimage, not the record.**
`datoms->projected` (lines 1227–1249) checks each slot's shape, then re-hashes the record exactly as stored. Two things are missing:
- **(a) The preimage does not record slot names.** `slot-encodings`/`encode-typed` (lines 1006–1014, 921–935) frame each slot by its type tag only. Within one node type, only the grammar keeps slots of the same type apart. The reader never checks which slots belong to which node type.
  - Confirmed: rewriting `[e :yin.debruijn/free foo]` to `[e :yin.debruijn/key foo]` on a `:variable` passes, with the same fingerprint. The reader returns the record `{:type :variable, :key foo}`, which no writer can produce.
  - The same trick works for `:arity`↔`:buffer` (both int64) and for `:value`↔`:free`/`:key` when the value is a symbol.
- **(b) The canonical-spelling gate is missing** (architect awareness item 1). Confirmed: a stored `:value 1.0` is accepted under the address of `:value 1`.

Together, (a) and (b) break the docstring's promise that "content addressing is verified at this boundary, never trusted" (lines 1189–1191). They also break `canonical-value`'s promise that "equal fingerprints imply equal :records maps" (line 955). Fingerprints stay correct, so dedupe is unaffected. What is wrong is the record content handed to any consumer of a store that has been tampered with or came from another writer. D5 is exactly the step that starts reading stored projected segments.
- Fix, with no digest re-pin:
  1. Derive a per-type projected slot set from `node-grammar` and reject records whose slots fall outside it. A `:variable` must have exactly one of `:bound`/`:free`, and a `:lambda` may optionally carry `:macro? true` only.
  2. Require every scalar to be its own canonical spelling. This must be exact spelling, not `=`: Clojure `=` would miss the case where `1` and `1.0` hash the same but read back differently.
  3. Add tests for the slot swap and the `1.0` spelling.

**F3. `merkle-node` takes time exponential in how deeply shared subgraphs nest (the D1/D2 seam).**
- `project-node`'s memo (line 743) returns the same resolved node object for a shared `[eid stack]`. `merkle-node` (lines 1035–1061) then recurses into every child before it looks up the hash-cons table, so it walks the fully unfolded tree.
- The emitter really does produce shared graphs: pre-assigned `:eid` sharing in `ast->datoms-with-root`, `src/cljc/yin/vm.cljc`.
- Confirmed with a doubling chain: 75/85/95 datoms took 0.8/2.0/7.0 s. At about 125 datoms it would take hours.
- Through `forward-step`, all of that happens inside one read "unit of work" (line 1421).
- §5's promise that "memoisation and sharing cannot change identity" still holds; sharing just stops paying off.
- Fix: thread the hash through the D1 memo so a shared occurrence reuses its hash. For example, have `project-node` cache `[node h]`, or fold `merkle-node` into the walk. Add a timing-free test that counts hash-cons misses against a doubling chain.

### P3

- **F4. Known attributes on the wrong node type are ignored, including dangling refs.** `known-attributes` (lines 357–364) is the union over every node type, so `check-attributes` (lines 472–483) accepts, for example, `:yin/body 999` on a `:literal`. Confirmed: it projects with no diagnostic. §2 says "unknown `:yin/*` attribute", which is ambiguous here, but a dangling ref slipping through undiagnosed conflicts with §1. Fix: a per-type allowed set.
- **F5. `projected->datoms` uses three local atoms** (lines 1118–1120). This holds to §1's literal "no namespace atom", but it is the only mutable state in the namespace. A `reduce` would make the whole file mutation-free. Not blocking.
- **F6. `exception-diagnostic` (lines 1331–1341) labels every `ex-info` as `:invalid-input`.** That includes the writer-internal defect at line 1130 ("record set does not contain a child hash"), which can only mean a projection bug. The narrow fix is to label `:rule` values that only internal code can produce as `:internal-error`.
- **F7. `forward-positive-budget` (lines 1294–1299) accepts a budget of 0** despite its name. The result is `:continue` with no progress, forever, under host cadence. Either require `pos-int?` or return a terminal status.
- **F8. `check-unexpanded` (lines 486–500) only catches an operator that is literally a `:lambda`.** A macro lambda reached through a bound variable (let-bound) also escapes. That limit is wider than the free-variable-name limit §1 documents; either say so in the doc or accept it.
- **F9. The projected writer emits nil-valued datoms**, e.g. `[e :yin.debruijn/value nil]` for `(lit nil)` (line 1138). The D2 round-trip covers this only in memory. If D5's store drops nil values, the reader will report `:hash-mismatch`. D5 must prove a nil literal round-trips through real storage.

## Answers

**1. The design's promise, end to end.**
- Trace of `(fn [count] (+ count 1))`:
  1. The emitter produces `-16 :lambda [count]`, body `-17 :application`, operator `-18 +`, operands `[-19 count, -20 1]`, then root.
  2. `index-frame` builds the index and `frame-root` picks `-16`.
  3. The lambda pushes `[[count]]`.
  4. `check-unexpanded` passes, since `-18` is a variable.
  5. `+` resolves to `{:free +}` and `count` to `{:bound [0 0]}`. The literal `1` is canonical int64.
  6. The lambda gets `:arity 1`.
  7. `merkle-node` hashes the leaves first, then the root: `095c83f7…`, pinned on all three hosts.
  8. With the binder renamed to `[n]`, or `1.0` in place of `1`, or the input shuffled, `:root` is identical and so are the bytes (`the-essays-fingerprint-is-stable`).
- **Collisions (equal fingerprint, different semantics):**
  - F1 set collapse. This is new and beyond the declared limits.
  - F2 lets a reader return different record content under one address.
  - Everything else found stays inside the declared limits: 1≡1.0, and NFC. An example of the NFC case is a free name spelled decomposed vs composed.
  - The resolver uses raw `=` while the encoder applies NFC. This is correct, not a seam: it matches how the VM binds symbols, and an NFC-aware resolver would create a real mismatch with the VM.
- **Divergences (same semantics, different fingerprints):** none found.
  - Defaults are filled in by the emitter before projection (gensym `"id"`, buffer 1024).
  - Explicit `macro? false` and an absent `macro?` project the same.
  - `:yin/tail?` and `:yin/macro-name` are dropped.
  - Duplicate binders are rightmost-wins, matching `bind-params`.
- **Seams between phases:**
  - D2 records vs D3 canonical spelling: F1, and F2(b).
  - D1 memo vs D2 hash-consing: F3.
  - D0 grammar vs D2 preimage vs D2 reader: F2(a).

**2. §1 invariants, whole file.**
- There is no namespace atom, callback, timer, registry, or clock.
- The only mutation is F5's local atoms, which nothing outside the function can observe.
- Blocked and full are `:retry` outcomes (lines 1397–1402, 1447–1452).
- Every throw on the read path becomes data inside the `try` (lines 1416–1434).
- Effects are only `stream/next` and `stream/append!`, both inside `forward-step`.
- Result: holds, with the F5 caveat.

**3. D6 checklist.**
- *D0–D5 criteria pass*: D0–D4 are committed and green. D5 has not landed, and it must also cover the F9 nil round-trip.
- *Alpha-equivalent programs give identical projected tuples and root hashes*: root hashes are proven (renamed binders at one and several levels, essay, shuffle, tree vs graph, tail flag). Tuple equality follows from record equality, because `projected->datoms` walks deterministically. But no test compares `projected->datoms` output for a renamed pair; the D6 check should add one assertion.
- *Non-equivalent fixtures remain distinct*: `content-changes-change-the-fingerprint` and `operand-order-changes-the-hash` cover this. F1 is a counterexample outside the fixtures; fix it and add the fixture.
- *All emitter types and stream outcomes are covered*: `every-type` covers the whole grammar. D4 covers blocked, the four source defects, destination closed, pending/full, partial frame, adjacent graphs, and invalid input. The destination `:invalid-value`/`:transport-error` outcomes are mapped but not exercised; D6 should add one table-driven test.
- *AST/walker/VM/linearizer/named storage/lease/waitset unchanged*: `git diff --stat 656e5c35..8ed66e3a` touches only `debruijn.cljc`, its test, and `pubspec.*`. D5 must keep it that way, and D6 should rerun that diff at the D5 head.

**4. The two architect awareness items.**
- *Reader canonical-spelling gate*: not benign in combination. On its own it only admits differences the declared limits already cover. Combined with the slot-name-free preimage (F2a), the reader accepts records no writer can produce, under valid addresses. D5 is where stored segments start being read, so this should be fixed before D5 relies on the reader.
- *§1 state-list wording*: benign. Index, scope, and memo are threaded explicitly inside the pure per-frame projection, and there is no output cursor (append-only). It is a documentation mismatch only. I recommend a one-line edit to the §1 list, e.g. "explicit in the forward-step state or threaded through the pure per-frame projection". It does not combine with anything else; F5's atoms are local to the writer, not step state.

**5. What the four phase reviews all missed:** F1, F2(a), F3, and F4. Each one only appears when you look across phases: the set rule against the D3 map ruling, the D0 grammar against the D2 reader, the D1 memo against the D2 hash-consing, and the D0 attribute set against the §1 dangling-ref rule.

## Recommended sequencing
F1–F3 all touch `debruijn.cljc`, which the D5 delegate is editing in the working tree. They should land as a separate fix round after D5 commits, or be handed to that delegate, rather than edited alongside its work. F4–F9 can go in the same round or be accepted as recorded limits.

## Verdict
EPIC NOT READY — blocking: F1 (set cardinality collapse under canonicalisation), F2 (the reader accepts slot-swapped and non-canonically spelled records under valid addresses), F3 (exponential Merkle pass over shared graphs the emitter produces).
