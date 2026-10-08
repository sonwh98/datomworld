Coding-Agent: agy
Model: gemini-3.1-pro-high

# Review: host-uniform division-by-zero fix

The fix addresses an issue where `(/ 1 0)` evaluated silently to `##Inf`
on JS/Dart due to IEEE-754 semantics, but threw an ArithmeticException on
JVM. The implementer changed the `/` primitive in `yin.vm` to a
`checked-divide` function that throws an error for any zero divisor on
any host.

### Findings

1. **Root-Cause Diagnosis — Already correct**
   Evidence: `src/cljc/yin/vm.cljc`'s `primitives` map. JVM throws for
   integer division by zero but yields `Infinity` for floats, whereas
   JS/Dart yield `Infinity` for both due to IEEE-754. The implementer
   correctly identified that `/` is the only exposed arithmetic operator
   with this divergence. Other operators that could theoretically diverge
   (`mod`, `rem`, `quot`) are not exposed in the VM primitives.

2. **Unifying to "Any Zero Divisor Throws" — Already correct**
   Evidence: `docs/design/yin.vm.divergence-register.md:319-322`.
   Enforcing "throw on any zero" is the only sustainable cross-host
   contract because JS and ClojureDart natively cannot differentiate `0`
   from `0.0`. The divergence register documents a REPL corpus check that
   expects the error text for "division by zero", confirming throwing an
   error (rather than yielding `##Inf`) is the original and intended
   behavior.

3. **`checked-divide` Variadic-Arity Handling — Already correct**
   Evidence: `src/cljc/yin/vm.cljc:114-119`. The variadic implementation
   `([x y & more] (reduce checked-divide (checked-divide x y) more))`
   perfectly replicates `clojure.core//`'s internal `reduce1` logic. The
   `(number? y)` check cleanly handles non-numeric denominators, ensuring
   they fall through to the native `/` to produce host-appropriate type
   cast errors without falsely triggering the zero-check exception.

4. **Test Coverage — Already correct**
   Evidence: `test/yin/vm_test.cljc:154-159`. The
   `division-by-zero-is-host-uniform` test correctly exercises both a
   standard division `(divide 1 0)` and a variadic reduction
   `(divide 24 2 0)`, asserting the exact JVM error text. Testing this
   directly against the `primitives` map isolates the logic properly from
   broader evaluator machinery.

5. **Branch Conflicts — Already correct**
   Evidence: uncommitted `git diff` vs `src/cljc/yin/vm.cljc` structure.
   The uncommitted `checked-divide` fix is physically located at the top
   of the file alongside the primitives mapping. It does not conflict or
   overlap with the `dao.data`/`dao.jing` AST schema changes that occur
   further down in the file.

### Overall Verdict

**Ready to proceed toward Architect sign-off as-is.** No modifications
are required.
