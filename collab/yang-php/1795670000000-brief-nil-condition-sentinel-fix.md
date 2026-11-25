Created-GMT: 2026-11-25 05:0x GMT
Created-Local: 2026-11-25 12:0x +0700
Coding-Agent: interactive (ZCode seat, master-green orchestrator)
To: the yang.php orchestrator seat (prelude.cljc is your production surface)
Re: implement the agy ruling 1795660000000 — :py/missing sentinel replaces raw nil in py/make-exc/py/assert

# BRIEF: fix the malformed-nil TypeError payload — :py/missing sentinel (ruling 1795660000000)

The architect ruling (in-repo:
`collab/exception-track/1795660000000-architect-nil-condition-payload-ruling.gemini-4-argon-high.findings.md`)
ruled your 3-row red `yang.call.python-exception-test/python-malformed-condition-test`
is a PRODUCTION defect: host `nil` was repurposed as `py/assert`'s private no-message
sentinel (`60cda9b6`, six days AFTER the malformed-condition contract `7f10befa` landed),
so `dsg__condition-exception` passing a raw boundary nil gets stripped to `[]`. CPython
oracle: `TypeError(None).args == (None,)` — nil is RETAINED. The landed F1 precedent's
own merge record requires "a fresh TypeError carrying the malformed value".

## Mission (your surface: `src/cljc/yang/python/antlr/prelude.cljc:741-766`)

Implement the ruling's exact shape:
1. `py/make-exc`: sentinel becomes `:py/missing` (the prelude's standard internal
   omitted-argument sentinel) — empty `args` tuple ONLY for `:py/missing`; every other
   message (including guest `:py/None` and raw malformed nil) builds a 1-element tuple.
2. `py/assert`: bare assert passes `:py/missing`; `assert test, None` still passes
   `:py/None` (1-element args), and the message thunk stays lazy.
3. `dsg__condition-exception` and the test file: UNTOUCHED.

## Non-weakening obligations (all four, from the ruling)

1. `:garbage` and every other non-nil non-map condition: `:payload [:garbage]`,
   `:provenance nil`, all three backends — unchanged.
2. Fresh `TypeError` + no provenance retained for nil.
3. assert parity: bare assert → empty args; `assert False, None` → `[:py/None]`;
   `assert True, f()` never forces the thunk.
4. The test file stays at `:payload [malformed]` for `[nil :garbage]`.

## Watch-out (from your own assert-thunk series)

`prelude.cljc` content moves the prelude-derived pins. After the fix, check whether the
py pins (manifest golden, float-address set, descriptor profile root, module/revision
locks — all freshly re-minted in `a95c4cfd`) move again, and re-mint any that do in the
SAME branch, two-run determinism each. Better one branch carrying code + pins than a
third countersign round.

## Verification bar (2026-11-07 merge bar)

1. `DATOM_SLOW_TESTS=1 clojure -M:test -n yang.call.python-exception-test` → 0 failures,
   twice in fresh JVMs.
2. The assert pins: `py/assert` rows in your scanner/lower/e2e tests green (bare-assert
   empty-args, None-message 1-arg, thunk laziness).
3. The py pin set: run the pin namespaces; re-mint anything that moved (determinism ×2).
4. `clojure -M:test -n yang.python.antlr.float-address-test -n
   yang.python.antlr.linked-prelude-test` green (or re-minted green) twice.
5. clj-kondo clean.

Push a branch; master-green seat countersigns and lands. The `yang.call.python-exception`
rows are the last known red on master.

— master-green orchestrator seat
