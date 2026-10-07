## What was implemented

Two NEW files only; `git status` confirms no tracked file was modified and nothing was staged/committed.

**`/Users/sto/workspace/datomworld/src/cljc/dao/stream/middleware.cljc`** (498 lines, implements `docs/design/dao.stream.middleware.md` exactly):

- `dispatch` (line 24): the one place an operation map becomes a protocol call (`stream/cursor`/`next`/`append!`); op outside the vocabulary throws (host-assembly defect, per dao.stream.md Result Convention).
- `IMiddlewareHandle` protocol (line 40) + `apply-request` (line 53): on a plain handle, bare dispatch; on a wrapped handle, the whole chain pass under the caller's ctx — the mirror step's entry point (dao.stream.remote.md 2.3).
- `run-outs` (line 69) / `operate` (line 85): `in` transforms outermost inward; an `in` answering an outcome map (`stream/outcome-map?`) short-circuits — inner handle unconsulted, only middlewares strictly outside it transform the outcome; normal dispatch runs every `out` innermost outward; nested wraps carry ctx inward.
- `wrap` (line 99): chain outermost first; reify per surface combination so the returned handle implements exactly the protocols the inner handle implements and no others; `descriptor`/`close!` delegate unchanged, no middleware sees them.
- gate (line 319): mints `cursor` `:oldest` once at construction; per operation retries the mint once when cursor-less, calls `next` on the medium once, exactly one recovery re-read after `gap` (ok/blocked/end/gap branches per spec), `end` ends the gate (no later reads), any other non-`ok` clears cursor+value for a fresh mint next operation; `verify` receives the last decision, or the none/ended markers (`:dao.stream.middleware/none|ended true`); nil passes through, a reason short-circuits `{:dao.stream/outcome :dao.stream/refused :dao.stream.middleware/reason r}`.
- `present` (line 413): `in` associates the credential under `:dao.stream.remote/credential`, `out` identity.
- Exemplars: `encryption` (line 431; in: ciphertext on `append!` else `:dao.stream/invalid-value`; out: plaintext or `{:dao.stream.middleware/undecodable true :dao.stream.middleware/raw v}` with outcome/cursor untouched), `metering` (line 465; appends `{:meter/identity i :meter/op op :meter/outcome kind}` to the composed sink, outcome unchanged), `channel-allow-list` (line 488; keyed on `:dao.stream.remote/channel` in ctx, ignoring credential and decision).

**`/Users/sto/workspace/datomworld/test/dao/stream/middleware_test.cljc`** (734 lines, 15 tests / 100 assertions), proof row covered: position rule under a value cipher over a ring buffer (`a-value-cipher-preserves-positions-cursors-and-outcomes`, line 276: cursors/outcomes structurally equal to the inner handle's, ciphertext-only inside, gap recovery cursor identical, plaintext on recovery); allow-list gate refuses with `:dao.stream/refused` + reason (line 406); latest decision through a capacity-1 medium after eviction (line 435; two appends between reads force gap->recovery->`:d3`); a filter cannot be expressed (line 361: out may only replace, positions/cursors invariant, declining is whole-operation refusal, nothing silently skipped); index-interpreter exemplar `index-fold` (line 676) publishing decisions end-to-end through the gate (line 700); plus short-circuit ordering (223), undecodable marker (322), invalid-value in (341), full decision-read lifecycle: construction mint, mint-fail none, blocked-keeps-value, end/no-later-reads, gap-ok, gap-gap no third read, re-mint after non-ok, nil pass-through, refusal short-circuit (466), metering emission (622), present credential association (645), apply-request dispatch (180), wrap ordering/delegation/surfaces (211/238/250).

## Ambiguities resolved (minimal readings)

1. Gate state: the `gate` constructor performs the initial mint and owns the cursor/value/ended state (a volatile, one gate value per wrapped handle, documented); `wrap` stays generic — observably identical lifecycle.
2. `verify` signature `(verify decision ctx req)` — follows dao.shibi.md's enumeration "one immutable query value, the context and the request".
3. `apply-request` runs a wrapped handle's chain under its ctx (required so the mirror's channel context reaches the gate, remote.md 2.3); protocol calls are the same pass with `{}`; unknown op throws.
4. Gate recovery read answering outside ok/blocked/end/gap: catch-all adopts the cursor if present, else clears (unspecified corner; minimal).
5. Decision emission is not built into `gate` (constructor names only verify/decision); composition composes it beside, per spec — metering demonstrates the emission shape.
6. Constructor arg keys unspecified by the spec chosen minimally: `:dao.stream.middleware/encipher|decipher`, `:meter/stream|:meter/identity`, reason `:dao.stream.middleware/channel-not-allowed`.
7. `defmacro-` avoided entirely — this repo's clj-kondo build cannot resolve it; the surface branches are plain reify (also removes cljd macro risk).

## Verification (sequential, solo, JVM tooling under mise)

- cljstyle check: both files clean. clj-kondo: 0 errors, 0 warnings. Pure ASCII, every line <= 80 columns.
- JVM (`mise exec -- clojure -M:test`): Ran 2,232 tests containing 183,003 assertions. 0 failures, 0 errors. (Baseline 2,217/182,889/0; +15/+114.)
- Node (`mise exec -- bb test:cljs`): Ran 2,144 tests containing 49,655 assertions. 0 failures, 0 errors. (Baseline 2,129/49,555/0; +15/+100.)
- Dart (`mise exec -- bb test:cljd`): All tests passed! — 2,106 passed. (Baseline 2,091; +15; compiled `test/cljd-out/dao/stream/middleware-test_test.dart` verified to contain the new tests.)

Status: COMPLETE