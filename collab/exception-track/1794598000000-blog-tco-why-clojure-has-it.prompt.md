Created-GMT: 2026-11-13 23:00:00 GMT
Created-Local: 2026-11-14 06:00:00 +07 (+0700)
Coding-Agent: codex
Session-ID: pending (capture thread.started)
# Task: fact-check + write a .blog post — why yang.clojure has TCO and the other yang compilers do not
Role: Architect + technical writer (fact-check every claim against the code before writing)
Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-14 06:00 +0700 | Status: active | Rationale: the
  strongest architect available; the blog makes architecture claims that must be code-verified.

## Phase 1 — FACT-CHECK (read the code, verify or refute each claim)

The steward claims the following about the yang/yin.vm architecture. Verify each against the
actual source. Cite file:line for every verification.

### Claims to verify

**C1 — The UAST has no tail-call-specific tag.** The VM's semantic-bytecode-grammar
(`src/cljc/yin/vm.cljc:997-1018`) contains ~17 tags with `:tail?` as a BOOLEAN FIELD on
`:application`, not a separate tag. TCO = the VM replacing the frame when `:tail?` is true.

**C2 — yang.clojure gets TCO via the outcome model.** `src/cljc/yang/clojure.cljc`'s outcome
model (slice-5, `:outcomes true`) makes errors returned as VALUES, eliminating the handler
during intra-unit calls. The intra-unit private tail lane is tail — `:tail?` is safe.
Constant-depth through mutual tail chains is pinned by `test-outcome-common-call-depth`.

**C3 — py/ts/php CANNOT get TCO via their dynamic-unwinding error model.** Ruling v2 §R1
requires every published entry to install the exception bridge BEFORE argument binding: handler
active → call body → cleanup → deliver. The body call is an OPERAND (non-tail by design).

**C4 — The depth probe MEASURED the trade.** `test/yang/call/depth_probe.edn` on branch
`exception-depth-probe` @ `545edc41`: clojure recursion flat at depth 7; php 57→177→657;
python 40→112→400; ts 28→268. Post-port LaTTe oracle: 155/155 workloads, max ratio 1.20×.

**C5 — The source languages determine the error model.** Python/JS/PHP promise dynamic
unwinding. Real Clojure has `recur` because the JVM lacks TCO, but yin.vm HAS native TCO.

**C6 — The provenance primitive (`8c10c5b7` on master) does NOT introduce per-frontend VM
changes; the `:provenance` field is shared infrastructure (frontend-agnostic).**

**C7 — The `:clojure-finally-refusal` row was RETIRED per owner ruling (1794556000000).**

## Phase 2 — WRITE THE BLOG

After verification, write a **`.blog` file** (EDN + Hiccup, per `docs/agents/format.md`):
- File: `public/chp/blog/tco-why-clojure-has-it.blog`
- EDN namespaced maps + Hiccup vectors
- Include `:$blog-title` and `:$blog-date`
- NEVER use markdown syntax; NEVER use em dashes
- Code samples: `[:pre [:code "..."]]`

### Blog structure

1. The VM is a CESK machine with native TCO (`:tail? true` replaces frames)
2. The four yang compilers compile to the same UAST — but TCO depends on the ERROR MODEL
3. yang.clojure: outcome model → errors as values → intra-unit tail lane → constant depth
4. yang.python/ts/php: dynamic unwinding → handler at every entry → linear growth
5. The depth probe measured the trade (cite actual numbers)
6. The architectural insight: TCO is a property of the ERROR MODEL
7. The convergence: unify at the contract, preserve the per-frontend error model

### Fact-check before writing

Every architectural claim verified in Phase 1. If any claim fails, do NOT write — report it.

### Commit

Write the `.blog` file, commit on a new `blog/tco` branch cut from current master, push to
origin. Include fact-check results in the commit body.

End with Completed-GMT/Completed-Local and: **BLOG WRITTEN: <path> | fact-check: <all passed |
N failed: <which>>** or **FACT-CHECK FAILED: <which claims are wrong>**.
