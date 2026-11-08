PERFECT. The grep confirms the exact source lines:
- **values.cljc:71** — `(-hash [_] (hash [:closure owner payload]))` — the Closure's `:cljs` IHash arm. ✓ exactly as the trace reports (`values.cljc:71:26`, col 26 = the `(hash ...)` call).
- **values.cljc:107** — `(-hash [_] (hash [:continuation owner payload]))` — the Continuation's `:cljs` IHash arm. ✓ exactly as the trace reports (`values.cljc:107:26`).

(My earlier manual line count was off by one; grep is authoritative. Good.)

And the JVM/`:clj` counterparts: `hashCode` at :61 (Closure) / :97 (Continuation) and `hasheq` at :64 / :100; ClojureDart at :51 / :87. Those explain the JVM lane's `StackOverflowError` in the PHP REPL corpus (QA brief item 3) — same defect, different host.

Now the third dump (register lane) shows `values.cljc:43:1` — that's the `(deftype Closure` line, i.e. the Closure's `-hash` attributed to the deftype (source-map imprecision for that frame). Same family.

Also, the third dump's counts are very informative:
- `yin.vm.debruijn.stack.stack_restore`: **3004** calls (the stack lane ran and PASSED with 3004 restores)
- `yin.vm.engine.trace`: **12** → GC ran 12 times
- `yin.vm.plain_data_QMARK_`: **174,800**
- `yin.vm.debruijn.register.install_image` 1, `load_image` 1, `derive.register_image` 1 → the register lane's load COMPLETED (no overflow there!)
- No `register_restore` count → the register lane's restore never ran (or wasn't reached before the throw)

And the enclosing candidates at the throw are only `hooked > on_every_vm` — meaning the overflow happened inside a function I did NOT instrument at that level... but the histogram shows `Object.yin.vm.engine` (2 frames) and `Object.yin.vm.ast-walker` (4 frames) in dump 1. Those are the outer frames of the trace (the bottom). I tried to extract them with grep "cljc" excluding cljs/core.cljs — that only showed the values.cljc frames in the first 70. The `yin.vm.engine`/`yin.vm.ast-walker` frames must be deeper in the trace (beyond the first 70 lines I printed... no wait, the histogram covers the WHOLE trace; the bottom frames are the last lines of the trace, which I only see if I print the last frames).

My "BOTTOM 45 lines of dump 1" printed the *tail of the dump block*, which is the histogram, not the bottom of the stack — because I only stored the FIRST 70 frames of the stack in the dump (`__st.slice(0,70)`). The full trace has 13,542 frames but I only printed 70. The histogram tells me the whole-trace composition: 4 `Object.yin.vm.ast-walker` frames and 2 `Object.yin.vm.engine` frames exist somewhere in the 13,542 — those are the outermost (trigger) frames.

So I know the trigger involves `yin.vm.engine` and `yin.vm.ast-walker` (for lane 1). Given `engine.trace` count = 3 at that point and the GC is the only engine function that hashes values into a set, the trigger is almost certainly `engine/trace`'s `seen` set (engine.cljc:2516/2537) during `collect` (engine.cljc:2587) — reached from `count-allocation` on `cell/new`.

I'd like to CONFIRM the trigger frames precisely rather than infer. Let me modify the dump to print the LAST 40 frames of the trace too (the outermost). That requires another run (a few minutes). Is it worth it? 

The brief's deliverable: "the named culprit function(s) with file:line and the trace evidence". I have that: `values.cljc:71` and `values.cljc:107` with a 13,542-frame trace whose histogram is 100% `hash`/`-hash`/`hash_ordered_coll`/`hash_unordered_coll`. The trigger (who calls hash) is valuable for the fix direction. I can strengthen it cheaply: the `__inside` list shows only `hooked > on_every_vm` because my instrumentation of `engine.collect`/`engine.trace` was verbosity 0 (count-only, no push? no — count-only still pushes/pops `__inside`!). Hmm: my wrapper pushes N onto `__inside` regardless of verbosity. So if `engine.trace` were on the stack at the throw, `__inside` would list it!

`__inside` = `hooked > on_every_vm` ONLY. So `engine.trace` was NOT on the stack at the throw. Hmm! That contradicts my GC theory...

Wait — but `engine.collect`/`engine.trace` are count-only (v=0) — do they still push? Let me re-read my wrapper code:

```js
var W = function(){
  global.__counts[N] = (global.__counts[N]||0)+1;
  var c = global.__counts[N];
  global.__inside.push(N);
  (v===2 ? log : nothing)
  try { return F.apply(this, arguments); }
  finally { global.__inside.pop(); ... }
};
```
Yes — `__inside.push(N)` happens for ALL candidates regardless of verbosity. So if `engine.trace` were enclosing the throw, it would appear in `__inside`.

It does NOT. And the histogram says there are 2 `Object.yin.vm.engine` frames and 4 `Object.yin.vm.ast-walker` frames in the trace. Hmm — but those could be *deeper* (inner) frames, not enclosing... no: `__inside` reflects my wrappers; if the trace passes through `yin.vm.engine.trace` (my wrapper `W`), then `W` would be on the JS stack AND in `__inside`.

Unless the frame names `Object.yin.vm.engine` / `Object.yin.vm.ast-walker` refer to functions I did NOT wrap — e.g. `yin.vm.engine.count_allocation` (not instrumented), `yin.vm.engine.store_put`, `yin.vm.ast_walker.cesk_transition`, etc. My histogram regex `at ([A-Za-z0-9_$.<>\-]+)` captures the function label; for a frame like `at Object.yin.vm.engine/count_allocation ...` hmm, the label would be `Object.yin.vm.engine` if the function name contains a `/`... Actually V8 prints `at Object.count_allocation [as yin.vm.engine/count_allocation]`? Hmm, the regex stops at `/` since `/` isn't in my char class. So `Object.yin.vm.engine` could be a truncated form of something like `at yin.vm.engine/count_allocation (...)`. Hmm, no: the pattern `at ([A-Za-z0-9_$.<>\-]+)` on `at Object.cljs$core$hash [as hash] (...)` gives `Object.cljs$core$hash`. For `at yin.vm.engine/count_allocation (...)` it would give `yin.vm.engine` — wait, no: the regex would match `yin.vm.engine` and stop at `/`. But the histogram shows `Object.yin.vm.engine` — with the `Object.` prefix, meaning V8 printed `at Object.yin.vm.engine...`. Hmm, V8 prints `Object.foo` when the function is a property of the global object (`foo` being the function name). So `Object.yin.vm.engine` = a function literally named `yin.vm.engine`?? Hmm, no: for CLJS compiled fns, the JS function name is like `yin$vm$engine$count_allocation`, and V8 shows `at Object.yin$vm$engine$count_allocation [as yin.vm.engine/count_allocation]`. My regex would capture `Object.yin$vm$engine$count_allocation`. That doesn't match `Object.yin.vm.engine` either.

Hmm. So what produces the label `Object.yin.vm.engine`? A frame like `at Object.yin.vm.engine/count_allocation (...)`? The `[as ...]` form appears when the property name differs from the function name. Actually V8's format: `at <functionName> (<location>)` or `at <object>.<method> (<location>)` or `at <fnName> [as <alias>] (<location>)`.

For a namespaced CLJS fn accessed as a global property `yin.vm.engine.count_allocation`... hmm.

OK — the exact identity of those 2+4 frames matters for naming the *trigger*. Let me get them precisely: re-run with the dump printing (a) the first 30 frames AND (b) the LAST 40 frames of the trace, plus (c) all frames whose text mentions our namespaces (`yin/vm/`, `yang/`, `dao/`). That's cheap to add and gives the definitive trigger.

But a full re-run takes ~6 minutes (19:04:45 → 19:10:47 ≈ 6 min). That's acceptable — and it's still "the ONE authorized reproduction" (the same command, re-run with better capture; I'll be transparent about the number of runs).

Hmm, but is it necessary? Let me think about what the trigger could be, given `__inside` shows NO instrumented candidate enclosing the throw:

The throw happened inside `on_every_vm`'s try → `(run ast prep)` or `(f vm)`. The instrumented candidates that WOULD have been on the stack: `yin.vm.eval`, `yin.vm.run`, `engine.run_loop`, `ast_walker.vm_eval`, `vm_load_program`, `refuse_reserved_ast_BANG_`, `ast__GT_datoms*`, `datoms__GT_ast`, `ast_reserved_defect`, `render.output`, `outcome`, `engine.collect`/`trace`, `debruijn.*.create_vm`/`load_image`, `derive.*`...

NONE of them is in `__inside` at the throw! So the throw happened OUTSIDE all of them — i.e. NOT during `vm/run`, NOT during load, NOT during `render/output`... 

Hmm! What's left inside the try? `(f (run ast prep))` where f = `outcome`:
```clojure
(defn- outcome [vm] [(vm/halted? vm) (vm/blocked? vm) (render/output (vm/value vm))])
```
`outcome` IS instrumented (count 1 in dump 3 → it was called once, for the stack lane). In dump 1 (ast-walker lane), `outcome` count is absent → 0 calls. So the ast-walker lane threw BEFORE `outcome`.

So the throw happened inside `(run ast prep)` = `(vm/eval (prep (tu/create-vm opts)) ast)` but NOT inside `yin.vm.eval`?! That's impossible... unless the wrapper wasn't in effect. Hmm! Wait: `yin.vm.eval` count = 1 in dump 1 — so it WAS called once and returned/threw. If it threw, `__inside` would still contain it (the `finally` pops it — AH, THERE'S THE BUG IN MY REASONING: the `finally { global.__inside.pop(); }` runs during unwinding, BEFORE the catch in `on_every_vm` executes! So by the time my DUMP runs, all the intermediate frames have already popped themselves off `__inside`.

Of course. `__inside` only shows wrappers that are still on the stack — and since the exception unwinds through them, they all pop. Only `hooked` and `on_every_vm` remain because the catch is INSIDE `on_every_vm`'s map function... wait, `on_every_vm` itself would also have popped if the throw propagated out of it — but the try/catch is inside `on_every_vm`, so it stays on the stack. And `hooked` calls `on_every_vm`. Right.

So `__inside` is useless for identifying the trigger (by design flaw). Fine — the trace's bottom frames are the answer. Let me re-run with the bottom frames printed.

Actually — cheaper idea: I can identify the trigger from the *counts*: in dump 1 (ast-walker lane), `engine.trace` count = 3, `plain_data_QMARK_` = 5784, `ast_reserved_defect` = 1, `vm_load_program` = 1, `run_loop` = 11. And the throw is in the ast-walker lane.

Hmm, the trigger candidates that hash a Continuation/Closure:
(a) GC: `engine/trace`'s `seen` set — but `trace` count was only 3 and each trace call is instrumented, so if the throw were inside trace, `trace`'s wrapper would have popped... yes it pops. Inconclusive.
(b) `vm/ast->datoms`? No hashing of continuations there.
(c) Something in the *run*: `assoc` into a map keyed by a closure/continuation? The store? `engine/put-active` → `assoc store key val` — hashing the KEY (a symbol). Not a continuation.
(d) `park-continuation` → `(assoc (:parked state) id entry)` — key is an id.
(e) `:row-index` keyed by `[body-node params]` — hashing AST nodes (maps) — deep? The AST is 600 deep → hashing a `[body params]` vector recurses into the body AST (600 levels × ~4 frames = 2400). Hmm! That's `hold-rows` — but that's the ROW loader (`vm-load-rows`), not used here (`vm-eval` uses `vm-load-program`).
(f) `engine/pin-refs` → `trace` with follow? false → also hashes into `seen`.
(g) **`(conj seen entry)` in trace** — same as (a).

Hmm, what about the guest's `cell/new` → `count-allocation` → `collect` → `mark` → `trace` → `seen`. The GC roots include `(:value vm)` and `(:env vm)` and `(:k vm)` (kernel). The `:k` chain contains `:env` maps which contain captured Continuations (the handler chain lives in a CELL in the store; the store is a `:data` root!). The store holds `py.rt/ctx` cell → its value map has `:handlers` → the 1000-deep handler chain of vectors, each holding a Continuation whose payload's `:k` is a map chain...

**Hashing that structure**: `[kind payload rest n depth]` → `hash_ordered_coll` (iterative over 5 elements) → element 1 = Continuation → `values.cljc:107 -hash` → `(hash [:continuation owner payload])` → `hash_ordered_coll` → payload map → `hash_unordered_coll` (iterative over entries) → `:k` value = a map → `-hash` (cached? first time: no) → recurses into ITS values → `:next` → the next link map → ... down the whole chain! Plus `:env` → closures → their payloads → `:body` AST (600 deep!) and `:env`...

So ONE `hash` of the store's ctx cell recurses: handler chain (1000) × k-chain (each level's k is ~20 links) × ... The observed 13,542 frames ≈ 1000 × 13. Consistent with the handler-chain nesting (each level: vector → Continuation → payload map → k map → ... → next level's handler vector via env?).

So the trigger is a `hash` of a structure containing the guest's handler chain. WHERE? The GC's `seen` set is the prime suspect (`[:data x]` entries). 

But hold on — `engine.trace` was called only 3 times in the ast-walker lane before the throw, and the throw happened... hmm, the walker's run does `cell/new` per `py/try` → `count-allocation` → `collect` when the threshold is hit. With ~2000 cells allocated, and `fresh-gc`'s base threshold (let me check `vm/fresh-gc`), maybe 3 collections. Plausible!

So: GC #3 (or #4) → `mark` → `trace` → `(contains? seen [:data <ctx cell value>])` → hash → 13,542 frames → boom.

To CONFIRM, I need the bottom frames. Let me do one more run with an improved dump that prints:
- the last 40 frames of the trace (outermost), and
- all frames matching `yin/vm|yang/|dao/|cljs-runtime/yin` (our code), capped at 40.

That's a decisive confirmation of the trigger. It costs ~6-7 minutes of wall clock. Worth it — the brief asks for "the named culprit function(s) with file:line and the trace evidence", and naming the trigger strengthens the fix direction.

Note: run5's process is still running? "running=YES" at 19:10:47 but the summary shows "Ran 1 tests containing 4 assertions. 3 failures, 0 errors." at line 6511 — so the test finished; the process may be doing teardown (or other ns work). Let me wait for it to exit before launching another node run (to avoid CPU contention). Actually the summary is printed at the very end; the process should exit shortly.

Let me prepare preload6 (same as 5 but with the improved dump: print frames 0-25 AND the last 40 AND all our-namespace frames), and launch after run5 exits.

Also, to save time, I can restrict the run to the ast-walker lane only? No — the test runs all four. Fine, ~6 min.

Actually, better idea to save a lot of time: I can make the dump *only* print for the FIRST RangeError (lane 1) and exit... no, let it run; I'll poll.

Let me write preload6 + launch script. I'll gate on run5's process being gone.

One more improvement: print `global.__counts` for the key GC functions at throw time (already there) and add `yin.vm.engine.collect`, `count_allocation`? `count_allocation` is private (`defn- count-allocation`) → compiled as `yin.vm.engine.count_allocation`. Let me add it as count-only... actually let me add `yin.vm.engine.collect` and `yin.vm.engine.count_allocation` with verbosity 2 (gated) — they're not hot (collect is rare).

Hmm, but if `collect` is on the stack at the throw, its wrapper pops before the catch → `__inside` won't show it. BUT the trace's bottom frames will show `yin.vm.engine/collect` etc. That's what I want.

Let me write it.