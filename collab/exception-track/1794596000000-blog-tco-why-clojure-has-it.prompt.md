Created-GMT: 2026-11-13 23:00:00 GMT
Created-Local: 2026-11-14 06:00:00 +07 (+0700)
Dispatched-By: continuation-semantics orchestrator (exception-track steward, owner-directed)
Destination: codex exec -m gpt-6-astra (jupiter, POOL 2)
Session-ID: capture thread.started
# Task: fact-check, then write — why yang.clojure has TCO and the other yang compilers do not
Role: Architect + technical writer
Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-14 06:00 +0700 | Status: active | Rationale: the
  strongest architect available; the blog makes architecture claims that must be code-verified.

## Phase 1 — FACT-CHECK (read the code, verify or refute each claim)

The steward claims the following. Verify each against the actual source at the current master
tip (`origin/master`). Cite file:line for every verification.

### Claims to verify

**C1 — The UAST has no tail-call-specific tag.** The VM's semantic-bytecode-grammar
(`src/cljc/yin/vm.cljc:997-1018`) contains 17 tags: `:literal :variable :lambda :application
:dao.stream.apply/call :vm/gensym :vm/store-get :vm/store-put :vm/current-continuation
:vm/park :vm/resume :stream/make :stream/put :stream/cursor :stream/next :stream/close`, plus
`:tail?` as a BOOLEAN FIELD on `:application`, not a separate tag. TCO is the VM replacing the
frame when `:tail?` is true.

**C2 — yang.clojure gets TCO via the outcome model.** `src/cljc/yang/clojure.cljc`'s outcome
model (slice-5, `:outcomes true`) makes errors returned as VALUES (`[:yin.k/ok v]` /
`[:yin.k/error data]`), eliminating the need for a handler during intra-unit calls. The
intra-unit private tail lane (direct body application) is tail — the `:tail?` mark is safe.
Constant-depth through mutual tail chains is pinned by `test-outcome-common-call-depth`
(clojure_test.clj:1584-1618): depth 7 flat at 8/32/128.

**C3 — py/ts/php CANNOT get TCO via their dynamic-unwinding error model.** Rule v2 §R1 requires
every published entry to install the exception bridge BEFORE argument binding: handler active →
call body → cleanup → deliver. The body call is an OPERAND (non-tail by design): the handler
must be active during the call because a `throw` from any depth must reach any outer handler
(dynamic-unwinding semantics). The handler's active scope makes the body call non-tail.

**C4 — The depth probe MEASURED the trade.** `test/yang/call/depth_probe.edn` on branch
`exception-depth-probe` @ `545edc41`: clojure recursion flat at depth 7 (outcome model,
tail-optimized); php 57→177→657 (11.5× linear); python 40→112→400 (10× linear); ts 28→268
(9.6× linear). Post-port LaTTe oracle: 155/155 workloads, max ratio 1.20×, none exceeds 10×.

**C5 — The source languages determine the error model.** Python/JS/PHP promise dynamic
unwinding (`try/except` catches across arbitrary frames; a `raise` propagates through
non-cooperating frames). Real Clojure has `recur` because the JVM lacks TCO, but yin.vm HAS
native TCO, so yang.clojure doesn't need `recur` for TCO — it uses the outcome model for
error semantics instead, which happens to enable TCO.

### Also verify

- The `:provenance` primitive (`8c10c5b7` on master) does NOT introduce per-frontend VM changes
  (the owner's NO declined the `:retained` mark for the outcome channel; the approved
  `:provenance` field is shared infrastructure — verify it's frontend-agnostic).
- The shell refactor (`yin.shell.*`) is on master and does not break the TCO analysis.
- The `:clojure-finally-refusal` row was RETIRED per owner ruling (1794556000000) — verify the
  kit no longer asserts the refusal.

## Phase 2 — WRITE THE BLOG

After verification, write a blog post in a **`.blog` file** (EDN + Hiccup, per
`docs/agents/format.md`):

### Format requirements (STRICT)

- File: `public/chp/blog/tco-why-clojure-has-it.blog`
- EDN syntax with namespaced maps: `#:blog{:title "..." :date #inst "..." :abstract [...] :content [...]}`
- Hiccup vectors for markup: `[:p "text"]`, `[:strong "bold"]`, `[:a {:href "..."} "link"]`
- Include `:$blog-title` and `:$blog-date`
- **NEVER use markdown syntax** — no backticks, no asterisks, no pipes
- **NEVER use em dashes** — use commas, colons, periods, or parentheses instead
- Code samples: `[:pre [:code "..."]]`

### Blog structure (suggested, not mandated)

1. The VM is a CESK machine with native TCO (`:tail? true` replaces frames)
2. The four yang compilers compile to the same UAST — but TCO depends on the ERROR MODEL, not the UAST format
3. yang.clojure: the outcome model makes errors values → intra-unit tail lane → constant depth
4. yang.python/typescript/php: dynamic unwinding → handler at every entry → handler makes body non-tail → linear growth
5. The depth probe measured the trade: 7 flat vs 57→657 / 40→400 / 28→268
6. The architectural insight: TCO is a property of the ERROR MODEL, not the VM or the UAST format
7. The convergence directive: unify at the contract level, preserve the per-frontend error model
8. The measured evidence (depth probe + LaTTe oracle)

The blog should be accessible to a technical reader who understands compilers and VMs but is
new to the yang/yin.vm architecture. It should cite the actual code (file:line) and the
depth-probe measurements.

### Fact-check before writing

Every architectural claim in the blog must be verified in Phase 1. If any claim fails
verification, do NOT write the blog — report the failed claim instead. The blog makes
architecture claims; wrong claims damage the project's credibility.

### Commit

Write the `.blog` file, commit on `exception-kit-finally` branch (or a new `blog/` branch cut
from current master), push to origin. Include the fact-check results in the commit body.

End with Completed-GMT/Completed-Local and: **BLOG WRITTEN: <path> | fact-check: <all passed |
N failed: <which>>** or **FACT-CHECK FAILED: <which claims are wrong>**.
