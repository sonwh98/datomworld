Completed-GMT: 2026-09-25 13:19:04 GMT
Completed-Local: 2026-09-25 20:19:04 Asia/Ho_Chi_Minh

Read-only design, no files edited. The recommendation is one closed rule, enforced in the evaluators and in the linker's validators, with no new AST tag. It makes every round 1-4 shadow guard deletable and leaves no residual shadow case.

## What is actually wrong today

The front end already treats `def` as a special form. In yang, `special-form?` dispatches on the head symbol before any local lookup (clojure.cljc:137), and it lowers `(def x 5)` to an application row whose operator is the variable `yin/def` (clojure.cljc:152). The evaluators then throw that decision away. `yin/def` is an ordinary registry entry (vm.cljc:194) that returns a store-put effect, and every executing engine resolves the operator through the one shared resolver (engine.cljc:54) in the order env, store, primitives, modules. The survey confirmed the walker, the semantic VM, the stack VM and the register VM all call that same function, and nothing anywhere refuses the name as a binder, a store key, or a value.

So the mismatch is between the front end and the evaluators. The fix is to make the evaluators agree with the front end, not to make the linker guess which resolution path a program took.

Two facts that shape the design:

- **Only primitives raise effects.** A program closure that returns an effect-shaped map just returns a value. Effect dispatch happens only for host functions (ast_walker.cljc:189). So once `yin/def` is not a value, the store-put effect can arise only from syntax.
- **Every other store key is already a literal.** The `:vm/store-put` node's key is a direct slot and its value is a datum, not a child (vm.cljc:807). The register `:store-put` writes an exact scalar. The `yin/def` application is the only store write whose key is evaluated. Fixing that makes every store key in a program syntactically visible.

## 1. Recommended mechanism

**Rule R: the symbol `yin/def` is syntax, never a name.** It may occur only as the operator of a two-operand application whose first operand is a literal symbol other than `yin/def`. Every other occurrence is refused with reason `:reserved-name`: a bare `:variable` in value position, a lambda parameter, a `:vm/store-put`, `:vm/store-get` or store-update key, a definition key, a macro name, or an entry in a composition-supplied primitive registry. Redefining `x` twice is still allowed; only `yin/def` itself is fixed. This is Clojure's `def` exactly: the special-form check precedes local and var lookup, `def` is not a value, and `(def def 7)` cannot disturb a later `(def x 5)`.

Enforcement has two halves that state the same rule:

- **Dynamic half, in every engine.** Before resolving an application's operator, check whether the operator row is `[:variable yin/def]`. If so, evaluate the value operand, then write the store through `engine/handle-effect` with a `:vm/store-put` effect, so the active-store routing of linker section 7.3 still applies. The name is never resolved. `yin/def` leaves the primitive registry.
- **Static half, in the format validators.** The linker's per-row and whole-value validators (steps 2 and 4) refuse any rule R violation for all four formats. Every image reaching any engine has passed this.

Against the alternatives:

- **A dedicated `:vm/define` AST node.** Rejected. The fact "this application is a definition" is already derived by pattern in the expander (macro.cljc:340), the encoder (encoder.cljc:168), and the linker. A new tag duplicates a derived fact, which is the case the "derive, don't persist" principle was written for, and forces migration of the harvest catalogue shape in macro spec 2.2, the encoder, code-as-tuples sections 1816 and 1831, and every content hash. Rule R gets the same guarantee with the grammar untouched.
- **Primitives-first resolution for reserved names, with `yin/def` still a value.** Sound for the linker but weaker. It keeps aliasing alive, keeps a computed key alive, keeps the reverse-lookup lift of a `yin/def` value alive, and keeps the M2 recognition merely sound rather than complete.
- **Refuse binders only.** Closes the lambda-parameter route but not the store write, the computed key, or the alias. That is the round 5 patch the owner declined.

## 2. Scope and who enforces

**`yin/def` only.** The soundness hole exists precisely because the linker and expander interpret this one name statically as a store write. `require` is dynamic. It evaluates to a `:module/require` effect that the engine dispatches to the linker, so shadowing it can only stop a require from happening, which is not a false discharge. Reserving every profiled primitive would outlaw ordinary Clojure idiom such as a parameter named `first`. Define the reserved set as a one-member contract table so `require` can join without redesign if it ever gains static discovery.

**Every engine and every host.** The four native backends on JVM, Node and Dart each implement the dynamic half; the shared resolver is untouched and simply never sees the name.

**Foreign UCF engines** are bound three ways. Rule R becomes a fact of the contract stamp in UCF section 7.3.3, alongside the instruction table. Every image is validated at link time regardless of which engine will run it, so a foreign engine never receives a non-definition `yin/def`. The dynamic half is a conformance corpus case: a definition form writes the store, and the store never contains the key `yin/def`. Section 7.4.2's stance that foreign engines publish static facts only fits this: reserved operators are a static fact.

## 3. What changes

**Engines and lowering.**

- ast_walker.cljc: in the `:application` case, recognize the shape and push a define frame; on operand return, call `handle-effect`. `:vm/store-put` with a reserved key throws.
- vm.cljc: remove `yin/def` from `primitives` and `primitive-profiles`; `create-vm` asserts a supplied registry holds no reserved name (next to the existing reverse-lookup assertion at line 1648); the semantic lowering emits a `:define key` instruction operating on the value register; the occurrence rules behind `free-names` exclude the reserved operator in definition position, so `yin/def` stops being a free name.
- Stack and register: the linearizers emit `[:define key]` (stack, pops the value) and `[:define rd key rs]` (register) instead of `:load-free yin/def` plus `:call`. `debruijn_resolve` refuses a reserved parameter. This bumps the stack and register contract revision, which the linker's format record already carries.

**Expander.** `yin/make-def` and the stdlib `defn` tree are unchanged. Harvest refuses a definition whose key is a reserved name, so `(defmacro yin/def ...)` can never install a macro that rewrites every definition. This closes a route the four gate rounds did not reach: harvest ignores lexical scope, and a stored macro named `yin/def` would be expanded at every call site.

**Linker, M2 worktree.** Delete: the round 3 filter that drops `yin/def`-derived definitions when the footprint binds `yin/def` (linker.cljc:474), the round 4 `computed-yin-def-write?` and `tree-yin-def-application-query` (linker.cljc:401 to 424), and the `:yin-def?` bookkeeping. Add: rule R checks in each format's row-defect and validate functions. Change: the vector definitions scanner reads `:define` operands beside `:store-put`, and the spec's "`:load-free` against the `yin/def` call sites" clause at linker.md line 309 goes. Keep: constant-key recognition from round 2 and the invocation position `[3 2]` from round 3. Those encode the fact that the value operand runs before the write, which is true of the special form too, and are not shadow guards.

**Spec text that now contradicts the rule, and which side changes.**

- UCF 7.5.2 and the 7.11 blocker require `yin/def` published as an effectful profiled primitive. The spec side changes: `yin/def` is not a primitive and has no profile; `require` remains the only effectful primitive.
- Code-as-tuples section 4.5 says the effects of a callable are read from its profile and `yin/def` contributes `:vm/store-put` because its profile says so. Changes to: a definition form contributes `:vm/store-put` syntactically, as a `:vm/store-put` row already does. The discovery result gets stronger, since every store key is now literal, which also retires part of the "discovery completeness" blocker.
- Linker.md 4.1 and 4.2 5b treat the `yin/def` operator as a free-name obligation discharged by primitive presence. It stops being an obligation.
- Linker.md 7.3 store-isolation clause at line 1352 stays as written; it is what makes the static half sound.

**Tests that change.** `linearize_test` swaps in its own `yin/def` primitive (line 418), `stack_effects_test` runs raw `:load-free yin/def` segments (lines 194 and 393), `completion_test` asserts `yin/def` in the required primitives (line 239), and `vm_test` reads the store at key `yin/def` (line 336). Each becomes a refusal or a `:define` form.

## 4. Is the linker's recognition then sound

**Sound and complete for constant keys.** In an admitted image, every application whose operator is the variable `yin/def` is a definition, always, on every engine. There is no alias because `yin/def` is not a value, no computed key because the key must be literal, no store or env rebinding because the name is never resolved and can be neither a binder nor a key. The receiver-side `:shadowed-free` check no longer has a `yin/def` case to make.

**Residual cases, none of them shadowing.** The ordering rules stay: a read inside the value operand precedes the write, and conditional or lambda-body definitions discharge under the existing dominance rules. Two deferred items, both over-retaining and admissible: `:vm/store-update` rows are not in the definition query, and the export rule stays advisory even though computed export keys can no longer exist.

**Defect versus deferred.** The round 4 alias finding is a defect of the current design and is closed by construction. The harvest route through `(defmacro yin/def ...)` is a defect nobody had filed. The two items above are deferred work.

## 5. Sequencing

**Recommendation: rule R lands before the M2 commit, as two commits in the phase 2 worktree.** First, the VM and validator halves with the spec amendments, gated on their own. Second, M2 with the round 3 and 4 guards deleted and the fixtures flipped to refusals, gated once. The r3/r4 guard is never committed, and no fifth patch is written.

Risk of this order: M2 stays uncommitted longer, and the VM change is larger than M2 itself, touching four engines. Risk of the other order: committing M2 as is puts a known unsound discharge (the alias case) on master with a denied gate on record.

**Fallback if the owner wants M2 unblocked first.** The static half alone is sound for linked images under today's resolver. A module store starts empty, only validated images write it, computed keys and reserved keys are refused, and a receiver whose env or store binds `yin/def` is still caught by 5b as long as `yin/def` remains an obligation. So M2 could ship with the validator checks in place of the guard and the VM half could follow. I do not prefer this, because it leaves REPL and direct-AST programs shadowable until the second change lands.

## 6. Completion criteria as tests, each on JVM, Node and Dart, across all four backends

1. A lambda with parameter `yin/def` is refused `:reserved-name` at validation and throws at evaluation.
2. A bare `yin/def` in value position, including `((fn [setter] (setter 'yin/def 0)) yin/def)`, is refused and throws.
3. `(yin/def 'yin/def 0)`, a `:vm/store-put` with key `yin/def`, and a `:vm/store-get` of that key are refused.
4. A non-literal key such as `(yin/def (id 'x) 1)` and a wrong arity are refused.
5. After `(yin/def 'x 1)` then `(yin/def 'x 2)`, reading `x` gives 2, and the store never holds the key `yin/def`.
6. `create-vm` with a supplied registry containing `yin/def` is refused; the standard registry has no such entry and no profile for it.
7. The same definition programs run through the walker, semantic, stack and register backends with identical stores, through the existing parity harness.
8. The three M2 fixtures for rebinding, direct store-put, and computed key refuse `:descriptor-defect` with rule `:reserved-name` instead of retaining obligations, and the alias fixture refuses the same way.
9. Obligations of a module that is only `(yin/def 'x 1)` are empty; `yin/def` is never a free name.
10. The expander refuses a harvest entry whose definition key is `yin/def`, including via `defmacro`.
11. A continuation parked inside a definition's value operand, for example a stream read as the value, lifts and lowers through the UCF round trip and completes the store write. Today that frame carries the primitive function object; it must carry a define marker instead.
12. Kondo clean, cljstyle clean, ASCII and 80 columns on every edited line.

Files read: engine.cljc, vm.cljc, ast_walker.cljc, macro.cljc, encoder.cljc, yang/clojure.cljc, the M2 worktree's linker.cljc and linker_test.cljc, linker.md sections 4, 7.3, 11 and 12, UCF 7.5.2 and 7.11, code-as-tuples 4.5, macro spec 2.2 to 2.4, the master design document, and the four gate findings plus the round 4 report. I did not run any test lane; nothing here depends on a run.
