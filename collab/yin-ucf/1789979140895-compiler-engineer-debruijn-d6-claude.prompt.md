Created-GMT: 2026-09-21 08:25:40 GMT
Created-Local: 2026-09-21 15:25:40 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 69f82e15-85c9-4754-a273-a5f4ad68d932 (resumed — your epic-fix and D5-fix session)
# Task: debruijn-d6-claude — D6 evidence tests + two accepted P3 fixes
Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-21 14:19:09 +07 | Status: active | Rationale: same implementer; small closing round

Work only in /Users/sto/workspace/worktree-debruijn-impl. Two independent
reviews of your work are done and both say READY for commit:
- gpt-5.6-sol (architect): collab/1789978107952-architect-debruijn-epicfix-review.gpt-5.6-sol.findings.md
- glm-5.3 (adversarial): collab/1789976750630-reviewer-debruijn-epicfix-glm.glm-5.3.findings.md
Neither found a P1 or P2. This round closes what they named so that §7-D6 /
§8 of the design (the current copy:
collab/1789975149204-compiler-engineer-debruijn-epicfix-claude.ref-design-master-37dfbf54.md)
is fully evidenced. Verify each item against the code before applying it.

## Box

Edit ONLY: src/cljc/yin/vm/pipeline.cljc, test/yin/vm/pipeline_test.cljc,
test/yin/vm/debruijn_test.cljc, and src/cljc/yin/vm/debruijn.cljc ONLY if a
new test exposes a real defect (report it loudly if so). No staging,
committing, docs/, or collab/ edits.

## A. Two accepted glm P3 fixes in pipeline.cljc

- **glm P3-1** (pipeline.cljc ~110-120): `persist-projected!` builds the
  projected envelope INSIDE the store-failure `try`, so an envelope-build
  defect (`projected->datoms`' `:missing-record`) is reported as
  `:projected-write-failed` rather than `:internal-error` — contradicting the
  P3-4 classification you installed. Build the envelope in the FIRST `try`
  (classified via `exception-diagnostic`) and pass the value to
  `jing/materialize!`. Add a test, using the same `with-redefs` technique and
  `:cljd` skip as your internal-error test, that an envelope-build throw
  yields `:projected {:outcome :internal-error}` and NOT
  `:projected-write-failed`, with the named side still `:ok`.
- **glm P3-2** (pipeline.cljc ~87-91): `framing-defect`'s internal branch
  returns the whole internal diagnostic map, so a (hypothetical) internal
  throw from `frame-datoms` surfaces as `:named {:outcome :rejected :rule
  :internal-error}` — an internal defect dressed as caller rejection. Make it
  return `{:rule (:rule diagnostic)}` in both branches OR classify internal
  as its own outcome; pick the smaller diff and say which. Test only if it is
  reachable via `with-redefs` cheaply; otherwise state that it is untested
  by design.

## B. D6 matrix evidence gaps named by gpt-5.6-sol (§8 rows)

Add focused tests (debruijn_test.cljc unless noted), each with fixtures a
mutation would break:
1. **Zero-, one-, multi-parameter lambdas**: `(lam '[] ...)` vs `(lam '[x]
   ...)` vs `(lam '[x y] ...)` with the same body — all pairwise distinct
   fingerprints; and each alpha-equivalent to a renamed twin
   (`'[] `-lambda trivially, `[a]`≡`[x]`, `[a b]`≡`[x y]`).
2. **Parameter order**: `(lam '[x y] (v 'x))` vs `(lam '[y x] (v 'x))`
   distinct (bound positions [0 0] vs [0 1]); `(lam '[a b] (v 'a))` ≡
   `(lam '[x y] (v 'x))`; `(lam '[a b] (v 'b))` ≡ `(lam '[y x] (v 'x))`.
3. **Stream operations changing the fingerprint**: `:stream/cursor`,
   `:stream/next`, `:stream/close` over the same source pairwise distinct;
   `:stream/put` with target and value swapped distinct; `:stream/make`
   buffer already covered.
4. **Continuation markers**: `:vm/current-continuation` vs `:vm/park`
   distinct from each other and from a literal; each stable under repeated
   projection.
5. **Durable-storage breadth (pipeline_test.cljc)**: through the real file
   store (the same open/close/reopen pattern as your nil test), persist a
   small batch of programs that between them exercise every node type the
   emitter produces and the scalar classes the canonical table accepts
   (nil, booleans, string incl. a decomposed-NFC spelling, int, integral
   double, non-integral double, keyword, symbol, vector, list, set and map
   literals where the value table accepts them), reopen, run
   `datoms->projected`, and assert each program's projected records and
   fingerprint equal the original projection. One deftest with a loop is
   fine; keep it cheap enough for the CLJD lane.

If any of these exposes a real defect in debruijn.cljc, fix it minimally and
report it as a deviation — do not weaken the test.

## Verification

Same as before: focused JVM
`clojure -M:test -n yin.vm.debruijn-test -n yin.vm.pipeline-test` and the CLJS
lane (`bb test:cljs`, Java 21). kondo, cljstyle, env-block exports, the
Java-17 lane and CLJD are denied in your headless session — skip them and do
not retry; the orchestrator runs them. Report exact counts (baseline before
this round: focused 88 tests / 412 assertions; CLJS 1565 / 39054).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 69f82e15-85c9-4754-a273-a5f4ad68d932
