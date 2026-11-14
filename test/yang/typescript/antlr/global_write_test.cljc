(ns yang.typescript.antlr.global-write-test
  "T2 — bare-global-write semantics (design yang.typescript.md §3.1.4 and §3.7).
   Strict mode is always on: an assignment to an UNDECLARED name must throw a
   catchable ReferenceError that names the variable (design: implicit globals
   are refused) — today the lowering emits an unconditional TypeError with a
   misleading 'read only property' message, which is the defect this file
   pins RED first (TDD) and greens after the assign-name repair.
   §3.7 script-goal declared-global writes (var at top level) are the
   design-specified follow-up slice and are NOT in this pin."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [yang.core.uast :as u]
    [yang.typescript.antlr.cst :as ts-cst]
    [yang.typescript.antlr.lower :as lower]
    [yang.typescript.antlr.prelude :as prelude]
    [yang.typescript.antlr.prelude-parity-test :as parity]
    [yang.typescript.antlr.render :as render]))

(def ^:private strict-undeclared-source
  "Real source through the real parser: the guest CATCHES the strict
   assignment failure and records the error's constructor name."
  (str "globalThis.probeClass = 'unset';\n"
       "try { undeclaredProbe = 7; } catch (e) { globalThis.probeClass = e.constructor.name; }\n"
       "console.log(globalThis.probeClass);\n"))

(def ^:private bundled
  "Prelude + the probe program, tails marked, on every backend."
  (u/mark-tails
    (u/seq-nodes
      [prelude/uast
       (u/app (u/v 'js/run-main)
              (lower/lower-unit (ts-cst/parse-source strict-undeclared-source)))])))

(deftest strict-undeclared-assignment-throws-reference-error-test
  (testing "strict undeclared assignment: the guest catches a ReferenceError
            naming the variable — not a mislabeled read-only TypeError"
    (doseq [[backend run] parity/runners]
      (let [{:keys [js/out js/uncaught]} (render/output (run bundled))
            line (str/trim (str (first out)))]
        (is (= "ReferenceError" line)
            (str backend " — the caught class must be ReferenceError; got: "
                 (pr-str line) " (uncaught: " (pr-str uncaught) ")"))
        (is (str/blank? (str uncaught))
            (str backend " — the probe catches the failure; nothing escapes"))))))

(deftest declared-globalthis-property-assignment-test
  (testing "assignment to a name that exists as a globalThis PROPERTY but is
            no binding (module goal, strict mode): the correct error is a
            ReferenceError naming the variable — today it is a mislabeled
            TypeError 'Cannot assign to read only property' (the T2 gap the
            donor fixture routed around)"
    (doseq [[backend run] parity/runners]
      (let [src (str "globalThis.saved = null;\n"
                     "try { saved = 7; } catch (e) { globalThis.probeClass = e.constructor.name; }\n"
                     "console.log(globalThis.probeClass);\n")
            {:keys [js/out js/uncaught]} (render/output (run (u/mark-tails (u/seq-nodes [prelude/uast (u/app (u/v 'js/run-main) (lower/lower-unit (ts-cst/parse-source src)))]))))
            line (str/trim (str (first out)))]
        (is (= "ReferenceError" line)
            (str backend " — strict module goal: the caught class must be
                         ReferenceError naming the binding-less name; got: "
                 (pr-str line) " (uncaught: " (pr-str uncaught) ")"))))))
