Created-GMT: 2026-09-09 12:25:38 GMT
Created-Local: 2026-09-09 19:25:38 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0
# Task: review Phase 2 of the transactor+index migration
Role: Routine Review

**Read-only. Print to stdout; write nothing.** Review `git diff`.

Phase 2 — the swap — is implemented by `glm-5.3`, uncommitted. 14 files,
+866/-542: `transactor.cljc` rewritten, `index.cljc` and `schema.cljc`
edited, five test files, seven docs including a new permanent
`docs/design/dao.space.transactor.md`.

Plan: `collab/1788950282826-architect-space-transactor-v2-plan-r5.claude-fable-5-1.findings.md` (Phase 2)
Implementer's report: `collab/1788953799216-storage-space-transactor-phase2.glm-5.3.findings.md`

## Structural checks I ran

- `ds/` residue: `transactor_test` **135 → 0**; `stigmergy_test` **6 → 3**
  (exactly `sources`, Phase 3's); `query_test` 2 → 0.
- `transactor.cljc` has **zero** occurrences of `DaoStreamLog`,
  `ds/defopen`, or the v1 require.
- `schema.cljc:1124` re-wraps to `{:result :ok …}` (the D10/F1 fix).
- `index.cljc:453-484` defines `checked` over `stream/validate-outcome` and
  calls it on **both** the mint and every read (the hang fix).

## Lanes I ran on this exact tree

- `bb test:clj` — 1433 tests, 165353 assertions, 0 failures, 0 errors. I ran
  it under `timeout 900`: exit 0, **not 124**, so the malformed-result path
  does not spin.
- `bb test:cljs` — 1343 tests, 34920 assertions, 0 failures, 1 pre-existing
  wasm error; `transactor-test` and `schema-test` present in Node output
- `compile demo` — 212 files, 0 warnings
- `bb test:cljd` — running; I will not report readiness without it

Do not rerun them.

## One thing the implementer found, worth your scrutiny

It fixed a **latent v1 bug** in `ThrowingAppendStream`: the parameter was
`_val` while the body called `(ds/append! inner val)`, so `val` resolved to
`clojure.core/val` and the retry appended a var object instead of the
record. Invisible because no assertion read that stream back. Confirm the fix
is right and that nothing else in the migrated doubles has the same shape.

## Judge

1. **D3's outcome discipline** — the watermark advances iff the local append
   answered `:dao.stream/ok`; non-ok returns as data; a non-outcome folds to
   `transport-error`. Is that right in every branch?
2. **D10/T19/T20** — schema installs `next-state` only on ok, and re-wraps.
   Are all 22 assertion sites still green by construction?
3. **The closedness bridge** — `SchemaWrapper.close!` keeps `{:woke []}`;
   `publish!`'s guard is under the wrapper lock. Any interleaving that breaks?
4. **Anything that only compiles because Phase 3 will land later.**
5. Anything owed and unnamed.

State plainly whether Phase 2 is ready to commit.
