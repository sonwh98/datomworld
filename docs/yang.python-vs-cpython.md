### yang.python vs cpython: the register kernel benchmark (2026-11-12)

The same Python source, run by CPython 3.9.6 and by `yang.python` compiled
to a register image and executed by `yin.vm.debruijn.register`
(`src/cljc/yin/vm/debruijn/register.cljc`) on the JVM.

Harness: `test/bench/python_register_bench.clj`. The program is the
`recursion-test` fib of `test/yang/python/antlr/e2e_test.clj` — a
recursive `fib`, then one `print`. It goes through the real pipeline
(parser stage -> lowering stage with the bundled prelude -> linearization
-> `derive/register-image`), and every size passed the harness's own
correctness gate: `{py/out ["233"|"610"|"1597"], py/exception nil}`.

### How to reproduce

```sh
clj -Sdeps '{:aliases {:pybench {:extra-paths ["test"]}}}' \
  -M:pybench -m bench.python-register-bench <n> <runs>
```

`<n>` is the fib argument (default 15), `<runs>` the timed iterations per
measurement. One run takes ~7 s at n=13 on the laptop, so sizes 13-17 use
`runs=1` after a warm correctness run.

### Results (ms per program run)

Python calls = `2*F(n+1)-1`, the total `fib` invocations of the program.

| fib(n) | Python calls | yang reg-VM run¹ | ms/call | cpython in-proc² | ns/call | ratio |
|---|---|---|---|---|---|---|
| 13 | 753 | 6 559 | 8.7 | 0.063 | 84 | ~104 000x |
| 15 | 1 973 | 19 138 | 9.7 | 0.139 | 70 | ~138 000x |
| 17 | 5 167 | 44 932 | 8.7 | 0.348 | 67 | ~129 000x |

¹ `vm/run` + value/render on the loaded image, timed region mirrors
`test/bench/yin_vm_bench.cljc`: the compile stays outside.
² Mean of 50 `main()` (fib + print) calls inside a warm CPython process,
stdout discarded.

One-time costs, outside the timed region (yang side):

- parse + lower + project + `register-image`: ~1.8-3.4 s
- `create-vm` (register validator + `register-hash`): ~0.6-0.9 s, flat in
  `n` — dominated by the bundled prelude's image size

CPython full process (`python3 prog.py`, startup + compile + run included)
measured 29-33 ms at all three sizes. Wall-clock-vs-a-process ratios
therefore mostly measure those fixed costs, not the kernel: 235x at
n=13, 592x at n=15, 1559x at n=17. The in-process column is the
interpreter comparison.

### Findings

- The register kernel is linear in Python calls at ~8.7 ms/call: the
  three-point fit has intercept ~0, so the growing continuation costs
  nothing superlinear. fib(5)'s 287 ms run was warmup noise, not signal.
- ~10^5x off CPython per Python-level operation is the honest read.
  Arithmetic is pure host functions (the `integer` module), so the cost
  is the interpreted Python prelude wrapped around every visible
  operation — roughly 100-500 VM steps per Python statement at this
  stage — on a persistent-vector Clojure machine versus CPython's
  specialized C eval loop.
- Cross-check against `docs/benchmark.md`: the register VM runs the
  primitive-tier countdown at ~5.8 us/step (28.98 ms at n=5000). The
  ~8.7 ms per Python call is consistent with that per-step price times
  the prelude's step count per call, so the gap is prelude depth, not a
  kernel regression.

### Caveats

- Single timed sample per size (runs=1); sizes 13/15/17 agree to ~6% on
  ms/call, which is the practical error bar on this machine.
- JVM host, `-M` plain classpath (the `:test` alias' `-Xss64m` is not
  needed at these depths); CPython is the system 3.9.6.
- The bundled-prelude path only: `:linked` (`py` served as a module) was
  not benched here.
