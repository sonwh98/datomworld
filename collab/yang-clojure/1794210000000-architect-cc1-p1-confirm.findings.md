Completed-GMT / Completed-Local: 2026-11-09 07:37 GMT / 2026-11-09 14:37 +0700

**REQUEST CHANGES — P1-1 remains open; P1-2 is corrected.**

1. **P1-1: recovery can attach another occurrence’s origin.** At [macro.cljc:693](/tmp/cc1-p1/src/cljc/yin/vm/macro.cljc:693), `lineage-origin-for` returns the first candidate matching either rows-vector identity **or elementwise row identity**. Distinct occurrences of identical content share row objects because [packet-of:110](/tmp/cc1-p1/src/cljc/yin/vm/macro.cljc:110) retrieves them from the same index. An earlier candidate’s elementwise match therefore wins before the actual source packet’s exact match.

   Static reproduction:

   ```clojure
   (defmacro pick-second [a b]
     (conj [] (first b) (first (rest b))))
   (defmacro feed [x]
     (yin/application (yin/variable 'pick-second)
       [x (yin/application (yin/variable 'f) [(yin/literal 1)])]))
   (defn f [y] y)
   (feed (f 1))
   ```

   `pick-second` receives equal-content operands with different histories: `a` retains the source admission; `b` belongs to the `feed` expansion. Both contain identical row objects. Reassembling `b` recovers **`a`’s source origin**, so its correspondence incorrectly records `[:retained source-admission]` instead of `[:retained feed-expansion-origin]`. Child insertion propagates that same incorrect recovery.

   The original single-operand pins are addressed, but the minimum correction requires preserving the **correct occurrence’s** provenance. Prefer exact container lineage and eliminate first-match selection among ambiguous row-level matches; propagate occurrence-specific lineage or explicitly refuse ambiguity. Add this nested, equal-content/different-origin case.

2. **P1-2: minimum correction met.** [strip-tracking:635](/tmp/cc1-p1/src/cljc/yin/vm/macro.cljc:635) recursively removes only `:yin.vm.macro/origin-of` from supported payload collections and metadata, preserving legitimate metadata entries. [construct:760](/tmp/cc1-p1/src/cljc/yin/vm/macro.cljc:760) applies it to `:data`/`:key` slots before hashing. The `packet-data` pin covers the original encoding failure.

Reviewed the worktree patch over `fa70516f`, read-only. No tests were run; the reported Node/JVM 28 tests and 257 assertions do not cover the remaining collision above.