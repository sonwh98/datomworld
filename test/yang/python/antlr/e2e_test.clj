(ns yang.python.antlr.e2e-test
  "End to end, as a stream topology: source events -> parser stage -> CST
   stream -> lowering stage -> program stream (source envelopes) -> an
   evaluator on each of the four VMs (the AST walker observes a program
   medium carrying the envelope's run member; the semantic, stack and
   register VMs are built from the same member).

   With hooks, the topology is longer: program stream -> encoder projection
   -> row stream -> `yang.safepoint` stage -> derived stream, and each VM
   runs the hook prelude followed by the derived program. `every-vm=` runs
   every program both ways: under no-op hooks the derived program must
   print what the naive one prints (safepoint slice 1, transparency).

   The composition installs the real `cell` module (cell slice 1), the
   real `data` module and the real `integer` module; nothing is stubbed.
   Printed output is compared as Python text rendered at the boundary
   (`yang.python.antlr.render`)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as stream]
    [yang.python.antlr.linked-harness :as linked]
    [yang.python.antlr.lower :as lower]
    [yang.python.antlr.parser :as parser]
    [yang.python.antlr.prelude :as prelude]
    [yang.python.antlr.render :as render]
    [yang.python.antlr.safepoint :as hooks]
    [yang.python.antlr.import-programs :as imports]
    [yang.python.antlr.safepoint-programs :as programs]
    [yang.safepoint :as safepoint]
    [yin.vm :as vm]
    [yin.vm.data :as data]
    [yin.vm.encoder :as encoder]
    [yin.vm.engine :as engine]
    [yin.vm.integer :as integer]
    [yin.vm.debruijn-linearize :as dl]
    [yin.vm.debruijn-register-compile :as rc]
    [yin.vm.debruijn.register :as rvm]
    [yin.vm.debruijn.stack :as dvm]
    [yin.vm.linearize :as linearize]
    [yin.vm.module :as module]
    [yin.vm.semantic :as semantic]
    [yin.vm.test-utils :as tu]))


;; =============================================================================
;; The host modules the lowering targets
;; =============================================================================

(def integer-limits
  "The `integer` limits the Python compositions here install: a float key
   or hash needs 1075 bits and 324 digits (see the prelude)."
  {::integer/max-bits 100000, ::integer/max-digits 4300})


(defn register-integer-module
  "Install the `integer` module under `integer-limits`, through the Python
   profile's registrar."
  [registry]
  (prelude/register-integer-module registry integer-limits))


(def data-limits
  "The `data` limit the Python compositions here install (C3 ruling 11)."
  {::data/max-items 1048576})


(defn register-data-module
  "Install the `data` module under `data-limits`."
  [registry]
  (data/register-data-module registry data-limits))


(def host-registrars
  "Registry steps the lowered programs need."
  '[yin.vm.module/register-cell-module
    yang.python.antlr.e2e-test/register-data-module
    yang.python.antlr.e2e-test/register-integer-module])


(defn host-registry
  "The registry with the cell, data and integer modules installed,
   admitted by the Python profile."
  []
  (prelude/admit (reduce (fn [reg sym] ((requiring-resolve sym) reg))
                         (module/empty-registry)
                         host-registrars)))


;; =============================================================================
;; The topology
;; =============================================================================

(def ^:private base-opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives})


(def ^:private load-semantic
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(defn- source-events
  [unit text]
  [{:yang.source/event :yang.source/chunk,
    :yang.source/unit unit,
    :yang.source/ordinal 0,
    :yang.source/text text}
   {:yang.source/event :yang.source/seal,
    :yang.source/unit unit,
    :yang.source/chunk-count 1}])


(defn- run-member
  "The run member of a source envelope: the map AST the lowering emitted."
  [{:yin/keys [batch root]}]
  (nth batch root))


(defn derive-program
  "The safepointed composition's stages after lowering: every envelope on
   `program` is projected by the encoder onto a row stream, the
   `yang.safepoint` stage rewrites it under `profile` onto a derived stream
   and writes its record to a ledger stream. Returns
   `{:envelope e' :record r}` for the one program."
  [program profile]
  (let [rows (tu/new-memory-log)
        derived (tu/new-memory-log)
        ledger (tu/new-memory-log)]
    (doseq [envelope (tu/drain program)] (encoder/load rows envelope))
    (safepoint/step-stage
      (safepoint/open-stage rows derived ledger profile :e2e/derived))
    {:envelope (first (tu/drain derived)), :record (first (tu/drain ledger))}))


(defn run-python
  "Every VM's value for `source`, as `{vm-key value}`; a throw becomes
   `[:thrown message]`. `registry` is the module registry the composition
   supplies.

   Options: `:hooks`, a hook prelude: the program is safepointed under
   `hooks/profile` and the VMs run the hook prelude, then the derived
   program. `:prep`, applied to each VM before it runs (a composition
   handing the task its streams). `:prelude :linked` lowers the linked
   program and runs it on the three vector VMs, `py` served from
   `yang.python.antlr.linked-harness`; the walker links it only after
   L-b."
  ([registry source] (run-python registry source {}))
  ([registry source {:keys [hooks prep prelude], :or {prep identity}}]
   (let [opts (assoc base-opts :modules registry)
         session (tu/make-observer-session
                   (prep (tu/create-vm {:modules registry})))
         program (tu/new-memory-log)
         src (tu/new-memory-log)
         cst (tu/new-memory-log)
         diagnostics (tu/new-memory-log)]
     (doseq [e (source-events [:e2e 0] source)] (stream/append! src e))
     (parser/step-stage (parser/open-stage src cst))
     (lower/step-stage (lower/open-stage cst program diagnostics
                                         {:medium lower/program-medium,
                                          :max-digits (::integer/max-digits
                                                        integer-limits),
                                          :prelude (or prelude :bundled)}))
     (let [problems (tu/drain diagnostics)]
       (cond
         (seq problems)
         {:diagnostics problems}

         (= :linked prelude)
         (let [ast (run-member (first (tu/drain program)))
               modules (module/register-effect-handler
                         registry :module/require module/require-handler)]
           (into {}
                 (map (fn [k]
                        [k (try (render/output
                                  (vm/value (linked/run-linked
                                              k ast {:modules modules})))
                                (catch Exception e [:thrown (ex-message e)]))]))
                 (keys linked/backends)))

         :else
         (let [ast (if hooks
                     (hooks/program hooks
                                    (vm/semantic-bytecode->ast
                                      (run-member
                                        (:envelope (derive-program program
                                                                   hooks/profile)))))
                     (run-member (first (tu/drain program))))
               datoms (vec (vm/ast->datoms ast))
               resolved #(tu/resolved-of ast)
               attempt (fn [f]
                         (try (render/output (f))
                              (catch Exception e [:thrown (ex-message e)])))]
           (tu/queue-ast! session ast)
           {:ast-walker (attempt #(vm/value (:consumer (tu/run-session session)))),
            :semantic (attempt #(vm/value (vm/run (load-semantic
                                                    (prep (semantic/create-vm opts))
                                                    datoms)))),
            :stack (attempt #(vm/value
                               (vm/run (prep (dvm/create-vm
                                               (:image (dl/lower-stack
                                                         (resolved)))
                                               (assoc opts
                                                      :contract vm/stack-contract)))))),
            :register (attempt #(vm/value
                                  (vm/run (prep (rvm/create-vm
                                                  (:image (rc/lower-register
                                                            (resolved)))
                                                  (assoc opts
                                                         :contract
                                                         vm/register-contract))))))}))))))


(defn- prints
  "The rendered value of a run that printed `lines` and raised nothing."
  [& lines]
  {:py/out (vec lines), :py/exception nil})


(defn- free-names
  "The names `ast` reads that no enclosing lambda binds."
  [ast]
  (letfn [(walk
            [n bound]
            (case (:type n)
              :variable (if (contains? bound (:name n)) #{} #{(:name n)})
              :lambda (walk (:body n) (into bound (:params n)))
              :application (reduce into
                                   (walk (:operator n) bound)
                                   (map #(walk % bound) (:operands n)))
              :if (into (walk (:test n) bound)
                        (into (walk (:consequent n) bound)
                              (walk (:alternate n) bound)))
              #{}))]
    (walk ast #{})))


(defn- definition-keys
  [ast]
  (set (keep (fn [n]
               (when (and (= :application (:type n))
                          (= 'yin/def (:name (:operator n))))
                 (:value (first (:operands n)))))
             (tree-seq map?
                       (fn [n] (filter map? (mapcat #(if (vector? %) % [%]) (vals n))))
                       ast))))


(def ^:private closed-names
  "What a canonical program may read: base-prelude definitions, the host
   names the prelude declares, primitives, and the definition operator."
  (into (conj (definition-keys prelude/uast) 'yin/def)
        (concat prelude/host-names (keys vm/primitives))))


(def ^:private linked-closed-names
  "What a linked program may read: the exports of `py` by their qualified
   names, the host names, and primitives (`require` among them); no
   definition operator and no runtime key."
  (into (set (map #(symbol "py" (name %)) prelude/module-exports))
        (concat prelude/host-names (keys vm/primitives))))


(defn- canonical-is-closed
  "The canonical program for `source` reads only `closed-names`, and no
   `py.sp/` name (safepoint ruling: the naive program never needs the hook
   prelude); the linked program reads only `linked-closed-names`."
  [source]
  (let [packet (assoc (parser/parse-source source)
                      :yang.python.antlr/max-digits
                      (::integer/max-digits integer-limits))
        free (free-names (lower/lower-packet packet))
        open (remove closed-names free)
        linked-open (remove linked-closed-names
                            (free-names (lower/lower-packet
                                          packet {:prelude :linked})))]
    (is (empty? open) (pr-str open))
    (is (not-any? #(= "py.sp" (namespace %)) free))
    (is (empty? linked-open) (pr-str linked-open))))


(defn every-vm=
  "`expected` from `source` on every VM, run naive, run safepointed under
   no-op hooks, and run linked; the canonical program is closed over its
   base prelude. The linked leg runs the three vector VMs: the walker's
   link of `py` is refused until L-b."
  [expected source]
  (canonical-is-closed source)
  (doseq [[label opts vms] [["naive" {} [:ast-walker :semantic :stack :register]]
                            ["no-op hooks" {:hooks hooks/noop-uast}
                             [:ast-walker :semantic :stack :register]]
                            ["linked" {:prelude :linked}
                             [:semantic :stack :register]]]]
    (let [results (run-python (host-registry) source opts)]
      (is (not (contains? results :diagnostics)) (pr-str results))
      (doseq [k vms]
        (is (= expected (get results k)) (str label " " k))))))


;; =============================================================================
;; Programs
;; =============================================================================

(deftest ^:slow arithmetic-and-print-test
  (every-vm= (prints "7 3.5" "-4")
             "print(1 + 2 * 3, 7 / 2)\nprint(-(2 + 2))\n"))


(deftest ^:slow floats-are-tagged-test
  (testing "int and float stay distinct values; 4/2 is 2.0 on every host"
    (every-vm= (prints "2.0 3.0 4.5 2 True False" "{1: 'b'} [1.0, 2]")
               (str "print(4 / 2, 1 + 2.0, 3 * 1.5, 1 + 1, 1 == 1.0, 2.5 < 2)\n"
                    "d = {1: 'a'}\n"
                    "d[1.0] = 'b'\n"
                    "print(d, [1.0, 2])\n"))))


(deftest ^:slow closures-and-nonlocal-test
  (every-vm= (prints "2 3")
             (str "def make():\n"
                  "    n = 0\n"
                  "    def inc():\n"
                  "        nonlocal n\n"
                  "        n = n + 1\n"
                  "        return n\n"
                  "    return inc\n"
                  "c = make()\n"
                  "c()\n"
                  "print(c(), c())\n")))


(deftest ^:slow global-declaration-test
  (every-vm= (prints "7")
             (str "total = 0\n"
                  "def add(k):\n"
                  "    global total\n"
                  "    total = total + k\n"
                  "add(3); add(4)\n"
                  "print(total)\n")))


(deftest ^:slow undefined-global-is-name-error-test
  (every-vm= {:py/out ["caught"],
              :py/exception {:type "NameError",
                             :args ["name 'nowhere' is not defined"]}}
             (str "try:\n"
                  "    print(missing)\n"
                  "except NameError:\n"
                  "    print('caught')\n"
                  "print(nowhere)\n")))


(deftest ^:slow builtin-fallback-before-module-binding-test
  (testing "a module-assigned builtin name reads the builtin until the module
            binds it, and the module binding after; a function sees the same"
    (every-vm= (prints "0" "1" "1")
               (str "print(len([]))\n"
                    "len = 1\n"
                    "print(len)\n"
                    "def f():\n"
                    "    return len\n"
                    "print(f())\n"))))


(deftest ^:slow shadow-then-delete-builtin-test
  (testing "the handler's exit unbinds the module name `len` (the lowering's
            only module-level unbinding; `del` is unsupported), and the next
            read finds the builtin again"
    (every-vm= (prints "2" "<ValueError object>" "3")
               (str "print(len([1, 2]))\n"
                    "try:\n"
                    "    raise ValueError\n"
                    "except ValueError as len:\n"
                    "    print(len)\n"
                    "print(len([1, 2, 3]))\n"))))


(deftest ^:slow builtin-lookup-order-test
  (testing "module dict, then builtins: a module binding wins once made"
    (every-vm= (prints "0" "1")
               "print(len([]))\nlen = 1\nprint(len)\n"))
  (testing "a class-body read the class namespace misses falls back to the
            builtins dict"
    (every-vm= (prints "1")
               "class C:\n    x = len\nprint(C.x([1]))\n")))


(deftest ^:slow class-body-reads-class-namespace-then-globals-test
  (testing "a class-body read before the body assigns the name falls back to
            the global; after, it reads the class namespace"
    (every-vm= (prints "1" "2" "1 2")
               (str "x = 1\n"
                    "class C:\n"
                    "    print(x)\n"
                    "    x = 2\n"
                    "    print(x)\n"
                    "print(x, C.x)\n"))))


(deftest ^:slow module-namespace-is-a-dict-test
  (testing "globals() is the namespace global reads and writes go through"
    (every-vm= (prints "3 1")
               (str "x = 1\n"
                    "g = globals()\n"
                    "g['y'] = 3\n"
                    "def f():\n"
                    "    return g['x']\n"
                    "print(y, f())\n"))))


(deftest ^:slow forward-capture-test
  (testing "a closure created before the local's only assignment sees it"
    (every-vm= (prints "5")
               (str "def outer():\n"
                    "    def f():\n"
                    "        return x\n"
                    "    x = 5\n"
                    "    return f()\n"
                    "print(outer())\n"))))


(deftest ^:slow recursion-test
  (every-vm= (prints "55")
             (str "def fib(n):\n"
                  "    if n < 2:\n"
                  "        return n\n"
                  "    return fib(n - 1) + fib(n - 2)\n"
                  "print(fib(10))\n")))


(deftest ^:slow function-objects-test
  (testing "defaults, *args, __name__, identity, arity as a Python TypeError,
            builtins as values"
    (every-vm= (prints "[1, 2, 0] [1, 5, 0] [1, 5, 2] f"
                       "arity"
                       "True False <lambda>"
                       "2 <function len>")
               (str "def f(a, b=2, *rest):\n"
                    "    return [a, b, len(rest)]\n"
                    "print(f(1), f(1, 5), f(1, 5, 6, 7), f.__name__)\n"
                    "try:\n"
                    "    f()\n"
                    "except TypeError:\n"
                    "    print('arity')\n"
                    "g = lambda: 1\n"
                    "h = lambda: 1\n"
                    "print(g is g, g is h, g.__name__)\n"
                    "L = len\n"
                    "print(L([1, 2]), L)\n"))))


(deftest ^:slow too-many-arguments-test
  (every-vm= {:py/out [],
              :py/exception
              {:type "TypeError",
               :args ["g() takes too many positional arguments"]}}
             "def g(a):\n    return a\ng(1, 2)\n"))


(deftest ^:slow while-break-continue-test
  (every-vm= (prints "[1, 2, 4, 5, 6]")
             (str "i = 0\n"
                  "acc = []\n"
                  "while True:\n"
                  "    i = i + 1\n"
                  "    if i > 6:\n"
                  "        break\n"
                  "    if i == 3:\n"
                  "        continue\n"
                  "    acc.append(i)\n"
                  "print(acc)\n")))


(deftest ^:slow for-range-list-else-test
  (every-vm= (prints "140")
             (str "s = 0\n"
                  "for k in range(5):\n"
                  "    s += k\n"
                  "else:\n"
                  "    s += 100\n"
                  "for x in [10, 20]:\n"
                  "    s = s + x\n"
                  "print(s)\n")))


(deftest ^:slow lambda-test
  (every-vm= (prints "5 [1, 4, 9]")
             (str "add = lambda a, b: a + b\n"
                  "sq = lambda x: x * x\n"
                  "out = []\n"
                  "for v in range(1, 4):\n"
                  "    out.append(sq(v))\n"
                  "print(add(2, 3), out)\n")))


(deftest ^:slow try-except-raise-test
  (testing "the handler catches a raise from a callee; the mutation before the
            raise survives"
    (every-vm= (prints "[1, 3] ('neg',)")
               (str "def f(x):\n"
                    "    if x < 0:\n"
                    "        raise ValueError('neg')\n"
                    "    return x\n"
                    "log = []\n"
                    "try:\n"
                    "    log.append(1)\n"
                    "    f(-1)\n"
                    "    log.append(2)\n"
                    "except TypeError:\n"
                    "    log.append(0)\n"
                    "except ValueError as e:\n"
                    "    log.append(3)\n"
                    "    print(log, e.args)\n"))))


(deftest ^:slow try-else-and-reraise-test
  (every-vm= {:py/out ["else" "inner"],
              :py/exception {:type "KeyError", :args ["k"]}}
             (str "try:\n"
                  "    pass\n"
                  "except Exception:\n"
                  "    print('no')\n"
                  "else:\n"
                  "    print('else')\n"
                  "try:\n"
                  "    raise KeyError('k')\n"
                  "except KeyError:\n"
                  "    print('inner')\n"
                  "    raise\n")))


(deftest ^:slow escape-restores-handlers-test
  (testing "return out of a try inside a loop leaves no stale handler"
    (every-vm= (prints "1" "caught")
               (str "def h():\n"
                    "    for i in range(3):\n"
                    "        try:\n"
                    "            if i == 1:\n"
                    "                return i\n"
                    "        except ValueError:\n"
                    "            pass\n"
                    "    return -1\n"
                    "print(h())\n"
                    "try:\n"
                    "    raise ValueError('after')\n"
                    "except ValueError:\n"
                    "    print('caught')\n"))))


(deftest ^:slow unbound-local-test
  (every-vm= (prints "unbound")
             (str "x = 1\n"
                  "def g():\n"
                  "    y = x\n"
                  "    x = 2\n"
                  "    return y\n"
                  "try:\n"
                  "    g()\n"
                  "except UnboundLocalError:\n"
                  "    print('unbound')\n")))


(deftest zero-division-test
  (every-vm= (prints "div")
             (str "try:\n"
                  "    1 / 0\n"
                  "except ZeroDivisionError:\n"
                  "    print('div')\n")))


(deftest uncaught-exception-test
  (every-vm= {:py/out ["1"], :py/exception {:type "ValueError", :args ["bad"]}}
             "print(1)\nraise ValueError('bad')\nprint(2)\n"))


(deftest ^:slow classes-test
  (every-vm= (prints "40 4 True False")
             (str "class A:\n"
                  "    def __init__(self, v):\n"
                  "        self.v = v\n"
                  "    def get(self):\n"
                  "        return self.v\n"
                  "class B(A):\n"
                  "    def get(self):\n"
                  "        return self.v * 10\n"
                  "b = B(4)\n"
                  "print(b.get(), A.get(b), isinstance(b, A),"
                  " isinstance(A(1), B))\n")))


(deftest class-attributes-and-identity-test
  (every-vm= (prints "2 True False")
             (str "class C:\n"
                  "    count = 1\n"
                  "    count = count + 1\n"
                  "a = C()\n"
                  "b = a\n"
                  "print(a.count, a is b, a is C())\n")))


(deftest lists-alias-and-index-test
  (every-vm= (prints "[1, 99, 3] 3 3 True")
             (str "a = [1, 2, 3]\n"
                  "b = a\n"
                  "b[1] = 99\n"
                  "print(a, len(a), a[-1], a == [1, 99, 3])\n")))


(deftest dict-order-and-key-normalization-test
  (testing "1, 1.0 and True are one key; insertion order is kept"
    (every-vm= (prints "{1: 'c', 'k': 2} 2 c")
               (str "d = {1: 'a', 'k': 2}\n"
                    "d[1.0] = 'b'\n"
                    "d[True] = 'c'\n"
                    "print(d, len(d), d[1])\n"))))


(deftest ^:slow exact-numeric-keys-test
  (testing "C3 ruling 6: one slot per numeric value, the first inserted key
            kept; signed zeros together; -1 and -2 apart; a function keys by
            its identity"
    (every-vm= (prints "{1: 'c'} 1" "{0.0: 'd'}" "{-1: 'a', -2: 'b'} 1")
               (str "print({1: 'a', 1.0: 'b', True: 'c'}, len({1: 'a', 1.0: 'b'}))\n"
                    "d = {0.0: 'a'}\n"
                    "d[-0.0] = 'b'\n"
                    "d[0] = 'c'\n"
                    "d[False] = 'd'\n"
                    "print(d)\n"
                    "def f():\n"
                    "    pass\n"
                    "e = {f: 1}\n"
                    "print({-1: 'a', -2: 'b'}, e[f])\n"))))


(deftest ^:slow numeric-hash-test
  (testing "C3 ruling 7: P = 2^61 - 1; equal numbers hash equal; -1 is -2"
    (every-vm= (prints "True True True" "-2 -2 1 12345"
                       "1152921504606846977 230584300921369408 1224995262755759164")
               (str "print(hash(0) == hash(False), hash(False) == hash(0.0),"
                    " hash(1) == hash(1.0))\n"
                    "print(hash(-1), hash(-2), hash(True), hash(12345))\n"
                    "print(hash(1.5), hash(0.1), hash(1e300))\n"))))


(deftest ^:slow unhashable-test
  (testing "hash() of a list, a function or a generator is a TypeError"
    (every-vm= (prints "3")
               (str "def g():\n"
                    "    yield 1\n"
                    "n = 0\n"
                    "for x in [[], g, g()]:\n"
                    "    try:\n"
                    "        hash(x)\n"
                    "    except TypeError:\n"
                    "        n += 1\n"
                    "print(n)\n"))))


(deftest ^:slow integer-limits-are-guest-exceptions-test
  (testing "C3 S3a: under a 60-bit composition the key of 5e-324 (2^1074)
            and every hash (P = 2^61 - 1) breach the bit limit: a
            MemoryError, an Exception, caught by except and finally, the
            dict and set unchanged; uncaught, it ends the run as a guest
            exception"
    (let [registry (-> (module/empty-registry)
                       module/register-cell-module
                       (data/register-data-module {::data/max-items 1048576})
                       (integer/register-integer-module
                         {::integer/max-bits 60, ::integer/max-digits 5})
                       prelude/admit)
          source (str "d = {1: 'a'}\n"
                      "s = {1, 2}\n"
                      "try:\n"
                      "    d[5e-324] = 'b'\n"
                      "except MemoryError:\n"
                      "    print('insert')\n"
                      "finally:\n"
                      "    print('finally')\n"
                      "try:\n"
                      "    print(d[5e-324])\n"
                      "except MemoryError as e:\n"
                      "    print('lookup', e.args)\n"
                      "try:\n"
                      "    s.add(5e-324)\n"
                      "except Exception as e:\n"
                      "    print('add', isinstance(e, MemoryError))\n"
                      "try:\n"
                      "    d[(1, 5e-324)] = 'c'\n"
                      "except MemoryError:\n"
                      "    print('tuple')\n"
                      "try:\n"
                      "    hash(1)\n"
                      "except MemoryError:\n"
                      "    print('hash')\n"
                      "print(d, s, d[1.0])\n"
                      "d[5e-324] = 'e'\n"
                      "print('unreached')\n")]
      (doseq [[label opts] [["naive" {}]
                            ["no-op hooks" {:hooks hooks/noop-uast}]]]
        (let [results (run-python registry source opts)]
          (is (not (contains? results :diagnostics)) (pr-str results))
          (doseq [k [:ast-walker :semantic :stack :register]]
            (is (= {:py/out ["insert" "finally" "lookup ()" "add True" "tuple"
                             "hash" "{1: 'a'} {1, 2} a"],
                    :py/exception {:type "MemoryError", :args []}}
                   (get results k))
                (str label " " k))))))))


(deftest ^:slow integer-is-test
  (testing "C3 ruling 8: `is` on integers is value-based; True is not 1"
    (every-vm= (prints "True True False")
               (str "x = 2 ** 52\n"
                    "y = 2 ** 52\n"
                    "print(x is y, x + 1 is y + 1, True is 1)\n"))))


(deftest strings-test
  (every-vm= (prints "abc 5 True")
             "print('ab' + \"c\", len('h\\u00e9llo'), 'x' == 'x')\n"))


(deftest boolean-operators-test
  (every-vm= (prints "x 0 True True False 2")
             "print(0 or 'x', 1 and 0, not [], 1 < 2 < 3, 3 < 2 < 1, 1 + True)\n"))


(deftest ^:slow long-loops-test
  (testing "loops are self-applied lambdas with tail-marked recursion; a few
            thousand iterations with continue and break stay bounded"
    (every-vm= (prints "3000 1500")
               (str "i = 0\n"
                    "while i < 3000:\n"
                    "    i += 1\n"
                    "n = 0\n"
                    "for k in range(100000):\n"
                    "    if k >= 3000:\n"
                    "        break\n"
                    "    if k < 1500:\n"
                    "        continue\n"
                    "    n += 1\n"
                    "print(i, n)\n"))))


(deftest ^:slow keyboard-interrupt-is-a-builtin-test
  (testing "with no hook prelude, KeyboardInterrupt is a BaseException that
            except Exception does not catch (naive, and under no-op hooks)"
    (every-vm= (prints "k True False")
               (str "try:\n"
                    "    raise KeyboardInterrupt\n"
                    "except Exception:\n"
                    "    print('e')\n"
                    "except KeyboardInterrupt as e:\n"
                    "    print('k', isinstance(e, BaseException),"
                    " isinstance(e, Exception))\n")))
  (testing "a module binding shadows it, as it does any builtin"
    (every-vm= (prints "1") "KeyboardInterrupt = 1\nprint(KeyboardInterrupt)\n")))


;; =============================================================================
;; Safepoint slice 1: signals, from Python source
;; =============================================================================

(defn- signals
  "A `prep` handing each VM its own signal stream holding `xs`."
  [xs]
  (fn [vm]
    (let [handle (tu/new-stream 8)]
      (doseq [x xs] (stream/append! handle x))
      (let [[ref vm] (engine/attach-resource vm handle)]
        (assoc-in vm [:store hooks/signals-key] ref)))))


(defn- every-vm-signalled=
  [expected source xs]
  (let [results (run-python (module/register-stream-module (host-registry))
                            source
                            {:hooks hooks/uast, :prep (signals xs)})]
    (is (not (contains? results :diagnostics)) (pr-str results))
    (doseq [k [:ast-walker :semantic :stack :register]]
      (is (= expected (get results k)) (str k)))))


(deftest ^:slow keyboard-interrupt-test
  (testing "one pre-appended signal stops an endless loop with
            KeyboardInterrupt"
    (every-vm-signalled= {:py/out [], :py/exception {:type "KeyboardInterrupt", :args []}}
                         "while True:\n    pass\n"
                         [2]))
  (testing "try/except KeyboardInterrupt runs the handler; except
            BaseException catches it too, and it is not an Exception"
    (every-vm-signalled= (prints "caught" "base True False")
                         (str "try:\n"
                              "    while True:\n"
                              "        pass\n"
                              "except KeyboardInterrupt:\n"
                              "    print('caught')\n"
                              "def spin():\n"
                              "    while True:\n"
                              "        pass\n"
                              "try:\n"
                              "    spin()\n"
                              "except Exception:\n"
                              "    print('exception')\n"
                              "except BaseException as e:\n"
                              "    print('base', isinstance(e, KeyboardInterrupt),"
                              " isinstance(e, Exception))\n")
                         [2 2]))
  (testing "an except KeyboardInterrupt clause tested before any safepoint
            ran still names the class"
    (every-vm-signalled= (prints "zero")
                         (str "try:\n"
                              "    1 / 0\n"
                              "except KeyboardInterrupt:\n"
                              "    print('kbi')\n"
                              "except ZeroDivisionError:\n"
                              "    print('zero')\n")
                         []))
  (testing "a module binding shadows the builtin under the real hooks too"
    (every-vm-signalled= (prints "1")
                         "KeyboardInterrupt = 1\nprint(KeyboardInterrupt)\n"
                         []))
  (testing "with no signal pending the program never blocks and prints what
            the naive run prints"
    (every-vm-signalled= (prints "300 150")
                         (str "i = 0\n"
                              "while i < 300:\n"
                              "    i += 1\n"
                              "n = 0\n"
                              "def bump(x):\n"
                              "    return x + 1\n"
                              "for k in range(300):\n"
                              "    if k < 150:\n"
                              "        continue\n"
                              "    n = bump(n)\n"
                              "print(i, n)\n")
                         [])))


;; =============================================================================
;; Safepoint slice 2: recursion
;; =============================================================================

(deftest recursion-error-is-a-builtin-test
  (testing "with no hook prelude, RecursionError is a RuntimeError, so an
            Exception (naive, and under no-op hooks)"
    (every-vm= (prints "rt True")
               (str "try:\n"
                    "    raise RecursionError('deep')\n"
                    "except RuntimeError as e:\n"
                    "    print('rt', isinstance(e, Exception))\n"))))


(deftest ^:slow recursion-under-real-hooks-test
  (testing "a to-the-limit countdown from parsed source ends in RecursionError
            on every VM; the corpus's ordinary calls are untouched"
    (every-vm-signalled= {:py/out ["3"],
                          :py/exception {:type "RecursionError",
                                         :args ["maximum recursion depth exceeded"]}}
                         (str "def f(n):\n"
                              "    return n if n == 0 else f(n - 1)\n"
                              "def g(x):\n"
                              "    return x + 1\n"
                              "print(g(g(g(0))))\n"
                              "f(1000)\n")
                         [])))


(deftest sys-is-refused-test
  (testing "sys.setrecursionlimit and sys.settrace are not reachable: the
            pre-seeded `sys` module resolves under the bundled profile too
            (I1: no linker delivery), but it exposes only sys.modules, so
            each read is an AttributeError and nothing runs silently"
    (doseq [[source attr] [["import sys\nsys.setrecursionlimit(50)\n"
                            "setrecursionlimit"]
                           ["import sys\nsys.settrace(None)\n" "settrace"]]]
      (let [results (run-python (host-registry) source)]
        (is (not (contains? results :diagnostics)) (pr-str results))
        (doseq [[k v] results]
          (is (= {:py/out [], :py/exception {:type "AttributeError",
                                             :args [attr]}}
                 v)
              (str k)))))))


(defn- node-shape
  [node]
  (select-keys node [:id :kind :type :text :rule :children]))


(deftest portable-packets-are-the-parsers-test
  (testing "each packet in safepoint-programs is what the parser makes of
            the source its docstring holds"
    (doseq [v [#'programs/recursion #'programs/unwind #'programs/generators
               #'programs/probe #'programs/admission #'programs/rebase
               #'programs/throwclose #'programs/nested-admission
               #'programs/delegation #'programs/while-true-pass
               #'programs/caught #'programs/def-and-while]]
      (is (= (map node-shape (:yang.cst/nodes (parser/parse-source (:doc (meta v)))))
             (map node-shape (:yang.cst/nodes @v)))
          (str v)))))


(deftest import-packets-are-the-parsers-test
  (testing "each packet in import-programs is what the parser makes of the
            source its docstring holds"
    (doseq [v [#'imports/m-packet #'imports/k-packet #'imports/t-packet
               #'imports/w-packet #'imports/e-packet #'imports/n-packet
               #'imports/o-packet #'imports/d-packet #'imports/c-packet
               #'imports/r-packet #'imports/x-packet #'imports/s-packet
               #'imports/q-packet #'imports/tt-packet #'imports/nn-packet
               #'imports/dotted-packet #'imports/aliased-packet
               #'imports/from-packet #'imports/del-packet]]
      (is (= (map node-shape (:yang.cst/nodes (parser/parse-source (:doc (meta v)))))
             (map node-shape (:yang.cst/nodes @v)))
          (str v)))))
