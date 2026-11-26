Created-GMT: 2026-11-25 03:07:00 GMT
Created-Local: 2026-11-25 10:07:00 +0700
Role: ARCHITECT ruling lane (gemini-4-argon, effort high)
Task-ID: 1795660000000-architect-nil-condition-payload-ruling
Target-Gate: `yang.call.python-exception-test/python-malformed-condition-test` (`:register` / `:semantic` / `:stack`)

# ARCHITECT RULING: Malformed `nil` Condition Retains `nil` in Fresh `TypeError` Payload (`[nil]`)

## 1. Ruling: **RETAIN `nil` in `args` (`:payload [nil]`)**

When a non-map malformed condition (including `nil`) is delivered to Python reception (`dsg__condition-exception` at `src/cljc/yang/python/antlr/prelude.cljc:1064-1067`), the fresh `TypeError` MUST retain the malformed value in its `"args"` tuple (`:items [nil]`, observed as `:payload [nil]`).

This is a **production defect (category (c))**, introduced when commit `60cda9b6` repurposed host `nil` inside `py/make-exc` as `py/assert`'s private no-message sentinel six days after `7f10befa` landed.

### Grounding

#### (i) CPython Oracle Result & Guest/Host Representation Boundary

Executed on `shiva` (`python3`):

| Expression | `e.args` | `str(e)` | `repr(e)` |
| --- | --- | --- | --- |
| `TypeError()` | `()` | `''` | `'TypeError()'` |
| `TypeError(None)` | `(None,)` | `'None'` | `'TypeError(None)'` |
| `TypeError("msg")` | `('msg',)` | `'msg'` | `"TypeError('msg')"` |
| `assert False` | `()` | `''` | `'AssertionError()'` |
| `assert False, None` | `(None,)` | `'None'` | `'AssertionError(None)'` |

- In CPython, passing a single argument (even `None`) to `TypeError(x)` unconditionally constructs a 1-tuple `e.args == (x,)`. Only the 0-argument call `TypeError()` produces `e.args == ()`.
- In `yang.python.antlr.prelude`, `py/str-conv` (`prelude.cljc:3007-3025`) only converts scalars (raising `NotImplementedError` on exception instances), while host-facing exception projection `py/snapshot-exc` (`prelude.cljc:2584-2594`) snapshots `:args` by mapping `py/snapshot` (`prelude.cljc:2530-2547`) over `(get (get (get c :attrs) "args" (py/tuple [])) :items)`. Under `py/snapshot`, both guest `:py/None` and host `nil` project to `nil` (`:args [nil]`), whereas an empty `"args"` tuple projects to `:args []` (the 0-argument `TypeError()` shape).
- `dsg__condition-exception` (`prelude.cljc:1064-1067`) is a 1-argument constructor call `(py/make-exc py.b/TypeError condition)`, not a 0-argument `TypeError()` call.

#### (ii) Platform Exception-Parity Contract & Chronology Correction

- **Chronology correction to the QA attribution note:** `7f10befa` (`fix(python): reject malformed provenance conditions`, **2026-11-13 01:25:01 +0700**) **predates** the no-message sentinel in `60cda9b6` (`feat(yang.python.antlr): lower the assert statement`, **2026-11-19 23:45:49 +0700**).
- When `7f10befa` landed, `py/make-exc` was unconditionally:
  ```clojure
  (fn [cls msg]
    (let [e (py/make-instance cls)]
      (do (py/setattr e "args" (py/tuple (py/vconj [] msg))) e)))
  ```
  Every caller of `(py/make-exc cls msg)` received a 1-element `"args"` tuple `[msg]`.
- When `60cda9b6` (later refined by `392746bd` and `7692d347`) implemented `py/assert`, it needed a way for bare `assert test` to construct a 0-argument `AssertionError()` while `assert test, None` constructed a 1-argument `AssertionError(None)` (`args = (:py/None,)`). Because normal Python guest values represent `None` as `:py/None`, `60cda9b6` used raw host `nil` as an internal sentinel between `py/assert` (`:766`) and `py/make-exc` (`:748`), assuming—as its comment at `:742-744` states—that *"a nil message is an exception with no args, the bare `assert`'s; every other caller passes a real message"*.
- That assumption overlooked `dsg__condition-exception` (`:1067`), which passes the raw boundary `condition` directly to `(py/make-exc py.b/TypeError condition)`. When `condition` is host `nil`, `py/make-exc` mistook the malformed boundary condition for `py/assert`'s bare-assert sentinel and stripped it to `[]`.

#### (iii) Scope of Existing Precedent

- `docs/design/yang.antlr.md:3847-3866,3881-3883` specifies valid map conditions and provenance retention, leaving malformed non-map condition handling to the frontend reception guard.
- `collab/exception-track/1794483001000-to-yangphp-addendum-nil-guard-precedent-landed.md:9-15` references the landed Python P3 follow-up round (`fea3adb4` / `b134207f`), whose primary merge record `collab/yang-python/1794560000000-orchestrator-p3-followups-merged.md:10` states the exact F1 contract:
  > *"the mint's malformed-condition shape-check: nil / non-map conditions now produce a fresh Python `TypeError` carrying the malformed value; egress emits a plain `:yang.call/thrown` with NO retained provenance."*
- Retaining `nil` in `args` (`:payload [nil]`) is therefore directly required by the landed F1 precedent and treats all malformed non-map conditions (`nil`, `:garbage`, etc.) uniformly.

---

## 2. Exact Fix Surface (Losing Side: Production)

- **Owner / Seat:** Production repair in `src/cljc/yang/python/antlr/prelude.cljc:741-766` (routed per dispatch to the `yang.php` seat).
- **Exact change in `src/cljc/yang/python/antlr/prelude.cljc:741-766`:**
  Replace raw host `nil` as the private bare-`assert` no-message sentinel between `py/assert` and `py/make-exc` with `:py/missing` (the prelude's standard internal omitted-argument sentinel):
  1. In `py/make-exc` (`src/cljc/yang/python/antlr/prelude.cljc:741-751`):
     ```clojure
     [py/make-exc
      ;; a :py/missing message is an exception with no args, the bare `assert`'s;
      ;; every other caller passes a single argument (which may itself be the
      ;; guest's None or a raw malformed nil condition)
      (fn [cls msg]
        (let [e (py/make-instance cls)]
          (do (py/setattr e "args"
                          (if (= msg :py/missing)
                            (py/tuple [])
                            (py/tuple (py/vconj [] msg))))
              e)))]
     ```
  2. In `py/assert` (`src/cljc/yang/python/antlr/prelude.cljc:754-766`):
     ```clojure
     (fn [test msg]
       (if (py/truthy test)
         :py/None
         (py/raise (py/make-exc py.b/AssertionError
                                (if (= msg :py/None) :py/missing (msg))))))
     ```
- **Untouched surfaces:**
  - `dsg__condition-exception` (`src/cljc/yang/python/antlr/prelude.cljc:1064-1067`) remains unchanged.
  - `python-malformed-condition-test` (`test/yang/call/python_exception_test.clj:118-147`) remains unchanged.
- **Note on derived artifacts:** Because `src/cljc/yang/python/antlr/prelude.cljc` is modified, the implementing seat must verify whether any prelude-derived address or scanner golden moves and run both `yang.call.python-exception-test` and the `assert` test pins.

---

## 3. Non-Weakening Obligations

1. **`:garbage` and all other non-nil non-map malformed conditions unchanged:** `(dsg__condition-exception cls msg :garbage)` must continue to produce a fresh `TypeError` with `:payload [:garbage]` and `:provenance nil` across `:register`, `:semantic`, and `:stack`.
2. **Fresh `TypeError` and no-provenance requirements preserved:** Neither `nil` nor any other non-map condition may reach `cell/dsg__new-provenance`; the resulting exception must be a fresh `py.b/TypeError` instance whose `cell/dsg__provenance` is `nil` and which egresses as `{:yang.call/reason :yang.call/thrown, :yang.call/value <TypeError>}`.
3. **`assert` parity (`60cda9b6` / `7692d347`) preserved:**
   - Bare `assert False` must still produce `AssertionError` with empty `args` (`:items []`).
   - `assert False, None` must still produce `AssertionError` with 1-element `args` (`:items [:py/None]`).
   - `assert True, f()` must still never force the message thunk `f()`.
4. **No test weakening:** `test/yang/call/python_exception_test.clj:118-147` must remain at `:payload [malformed]` for `malformed` in `[nil :garbage]`.

---

## 4. One-Line CPython Probe Commands

```bash
python3 -c 'for expr in ["TypeError()", "TypeError(None)", "TypeError(\"msg\")"]: e = eval(expr); print(f"{expr}: args={e.args!r} len={len(e.args)} str={str(e)!r} repr={repr(e)!r}")'
```

```bash
python3 -c '
for stmt in ["assert False", "assert False, None", "assert False, \"msg\""]:
    try: exec(stmt)
    except AssertionError as e: print(f"{stmt}: args={e.args!r} len={len(e.args)} str={str(e)!r} repr={repr(e)!r}")
'
```
