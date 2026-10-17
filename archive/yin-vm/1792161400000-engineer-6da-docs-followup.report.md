Completed-GMT: 2026-10-16 14:10:49 GMT
Completed-Local: 2026-10-16 21:10:49 +07
Coding-Agent: claude
Session-ID: dc3e4b88-02d8-428d-b938-541541f0fbe1
# Slice 6d-a docs micro-round: three items from the cmd review

Role: VM Runtime. Branch `srvm-6d-a`, worktree `/Users/sto/workspace/datomworld-srvm-6d-a`, still uncommitted.

There were no test edits, and the nit-1 `zero?` pin is untouched. No lanes were run beyond kondo/cljstyle on the touched files.

## The three edits

### 1. cmd P3-1: `docs/design/yin.vm.semantic.continuation.md` §5.7

The trailing plan-amendment line is deleted.

Before:
```
Terminal outcomes are compared as outcomes: … rather than both being required to return a guest value.

**Change:** Consensus P2-7; muse N4/glm O3; glm’s terminal-status acceptance omission.

```

After:
```
Terminal outcomes are compared as outcomes: … rather than both being required to return a guest value.

```

### 2. cmd P3-3: `docs/design/yin.vm.semantic.continuation.md` §7.3

The guard paragraph lives in §7.3. One sentence was appended to it.

Before:
```
Direct `vm-hot`, `vm/step`, `vm/run` and `run-fuel` refuse `:exporting` and `:ended` before guest, scheduler or bridge work. The guard is outside the instruction loop. A fenced running restore …
```

After:
```
Direct `vm-hot`, `vm/step`, `vm/run` and `run-fuel` refuse `:exporting` and `:ended` before guest, scheduler or bridge work. The guard is outside the instruction loop. The refusal is an ex-info whose data is `{:rule :gate-closed :yin.k/gate mode}` (`mode` the closed gate); 6d-b's holder paths reuse this name. A fenced running restore …
```

The shape matches `semantic.cljc` `check-gate!`: `(ex-info "Gate is closed" {:rule :gate-closed :yin.k/gate mode})`.

### 3. cmd P3-4: `src/cljc/yin/vm/ucf/rows.cljc` `completion-node`

`completion-node` coerces `try?` with `(boolean (:try? e))` on three lines: `:link-request`, `:link-response` and `:install`. One comment line above the first of them covers all three.

Before:
```clojure
    (:next :put) (let [pending (pending-node ctx e)] (node :completion (:slots pending)))
    :link-request (node :completion [:link-request (:link-id e) (:name e) (boolean (:try? e)) (:op-id e)])
```

After:
```clojure
    (:next :put) (let [pending (pending-node ctx e)] (node :completion (:slots pending)))
    ;; the engine is the trusted boolean producer of :try?; lower's Q2 check (:pending-try) carries the validation teeth
    :link-request (node :completion [:link-request (:link-id e) (:name e) (boolean (:try? e)) (:op-id e)])
```

## Sanity pass

- **kondo** on `rows.cljc`: 0 errors, 0 warnings.
- **cljstyle check** on `rows.cljc` reports two indentation-only defects. Neither was introduced by this round's single comment line, and neither was fixed, because the brief allows exactly three edits:
  - `completion-node`'s `(case …)` body sits one level shallow inside its `let`. My comment line sits inside that block.
  - `check-completions!`'s `let` sits one level deep.

  Both are whitespace only. `mise exec -- cljstyle fix src/cljc/yin/vm/ucf/rows.cljc` fixes them; whoever stages the landing commit can apply it.
- The `.md` files are not cljstyle-checked.

## STOPPED on

Nothing.

READY
