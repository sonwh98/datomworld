(ns yang.python.antlr.e2e-c2-test
  "Phase C2 slices S1, generators core, and S2, send, throw, close and the
   dynamic context, end to end on all four VMs over the
   real cell and data modules, through the same stream topology as
   `yang.python.antlr.e2e-test`. Expected output is CPython 3.9.6's for the
   same source. The continuation-length check reads the final VM's heap,
   so it is JVM-only."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.python.antlr.e2e-test :as e2e]
    [yang.python.antlr.lower :as lower]
    [yang.python.antlr.parser :as parser]
    [yin.vm :as vm]
    [yin.vm.data :as data]
    [yin.vm.debruijn-linearize :as dl]
    [yin.vm.debruijn-register-compile :as rc]
    [yin.vm.debruijn.register :as rvm]
    [yin.vm.debruijn.stack :as dvm]
    [yin.vm.linearize :as linearize]
    [yin.vm.module :as module]
    [yin.vm.semantic :as semantic]
    [yin.vm.test-utils :as tu]
    [yin.vm.values :as values]))


(defn- prints
  [& lines]
  {:py/out (vec lines), :py/exception nil})


(defn- every-vm=
  [expected source]
  (let [results (e2e/run-python (e2e/host-registry) source)]
    (is (not (contains? results :diagnostics)) (pr-str results))
    (doseq [k [:ast-walker :semantic :stack :register]]
      (is (= expected (get results k)) (str k)))))


(defn- lines
  [& ls]
  (apply str (map #(str % "\n") ls)))


;; =============================================================================
;; S1 acceptance
;; =============================================================================

(deftest generator-while-loop-test
  (testing "a generator function yielding 0..n-1 from a while loop, drained
            by list() and walked by for"
    (every-vm= (prints "[0, 1, 2]" "0" "1")
               (lines "def g(n):"
                      "    i = 0"
                      "    while i < n:"
                      "        yield i"
                      "        i += 1"
                      "print(list(g(3)))"
                      "for x in g(2):"
                      "    print(x)"))))


(deftest generator-body-is-lazy-test
  (testing "calling a generator function runs none of its body; each next
            runs it to the following yield; completion is StopIteration
            with value None"
    (every-vm= (prints "[]" "1 ['start']" "stop None ['start', 'end']")
               (lines "log = []"
                      "def g():"
                      "    log.append('start')"
                      "    yield 1"
                      "    log.append('end')"
                      "it = g()"
                      "print(log)"
                      "print(next(it), log)"
                      "try:"
                      "    next(it)"
                      "except StopIteration as e:"
                      "    print('stop', e.value, log)"))))


(deftest generator-return-value-test
  (testing "return 7 completes with StopIteration(7); a further next on the
            exhausted generator gives StopIteration() with value None"
    (every-vm= (prints "1" "7 (7,)" "None ()" "d")
               (lines "def g():"
                      "    yield 1"
                      "    return 7"
                      "it = g()"
                      "print(next(it))"
                      "try:"
                      "    next(it)"
                      "except StopIteration as e:"
                      "    print(e.value, e.args)"
                      "try:"
                      "    next(it)"
                      "except StopIteration as e:"
                      "    print(e.value, e.args)"
                      "print(next(it, 'd'))"))))


(deftest yield-expression-send-test
  (testing "in [a(), (yield 1), b()] the evaluated a() survives the
            suspension and b() runs after the resume; the list holds the
            value sent"
    (every-vm= (prints "1 ['a']" "2 ['a', 'b', ['A', 's', 'B']]")
               (lines "log = []"
                      "def a():"
                      "    log.append('a')"
                      "    return 'A'"
                      "def b():"
                      "    log.append('b')"
                      "    return 'B'"
                      "def g():"
                      "    x = [a(), (yield 1), b()]"
                      "    log.append(x)"
                      "    yield 2"
                      "it = g()"
                      "print(next(it), log)"
                      "print(it.send('s'), log)"))))


(deftest yield-in-nested-default-test
  (testing "a default is evaluated in the enclosing scope, so its yield is the
            enclosing generator's: the value sent becomes the default"
    (every-vm= (prints "1 d")
               (lines "def g():"
                      "    def h(x=(yield 1)):"
                      "        return x"
                      "    yield h()"
                      "it = g()"
                      "print(next(it), it.send('d'))"))))


(deftest long-generator-with-break-test
  (testing "3000 items from a while True generator, consumed by a for that
            breaks: no growth per item on any VM"
    (every-vm= (prints "2999 2999")
               (lines "def g():"
                      "    i = 0"
                      "    while True:"
                      "        yield i"
                      "        i += 1"
                      "n = 0"
                      "for x in g():"
                      "    if x == 2999:"
                      "        break"
                      "    n += 1"
                      "print(n, x)"))))


;; =============================================================================
;; S2 acceptance: send, throw, close and the dynamic context
;; =============================================================================

(deftest send-and-iterator-methods-test
  (testing "send of a non-None value to a just-started generator is a
            TypeError before any switch; send(None) starts it; completion
            through send is StopIteration; __iter__ is the generator itself
            and __next__ advances it"
    (every-vm= (prints (str "(\"can't send non-None value to a"
                            " just-started generator\",)")
                       "1" "got v" "2" "stop None" "True 1")
               (lines "def g():"
                      "    x = yield 1"
                      "    print('got', x)"
                      "    yield 2"
                      "it = g()"
                      "try:"
                      "    it.send(5)"
                      "except TypeError as e:"
                      "    print(e.args)"
                      "print(it.send(None))"
                      "print(it.send('v'))"
                      "try:"
                      "    it.send('w')"
                      "except StopIteration as e:"
                      "    print('stop', e.value)"
                      "it = g()"
                      "print(it.__iter__() is it, it.__next__())"))))


(deftest throw-caught-at-yield-site-test
  (testing "throw raises at the yield on the generator's own stack, so its
            try catches it and the next yield is throw's value; the class
            is called with the value, a tuple spread, an instance of it
            used as is; an instance with a value, a traceback, or a
            non-exception is a TypeError in the caller"
    (every-vm= (prints "1" "caught ('boom',)" "recovered"
                       "1" "caught ('again',)" "recovered"
                       "1" "caught ('a', 'b')" "recovered"
                       "1" "caught ('z',)" "recovered"
                       "('instance exception may not have a separate value',)"
                       "('throw() third argument must be a traceback object',)"
                       "not exc" "1")
               (lines "def g():"
                      "    while True:"
                      "        try:"
                      "            yield 1"
                      "        except ValueError as e:"
                      "            print('caught', e.args)"
                      "            yield 'recovered'"
                      "it = g()"
                      "print(next(it))"
                      "print(it.throw(ValueError, 'boom'))"
                      "print(next(it))"
                      "print(it.throw(ValueError('again')))"
                      "print(next(it))"
                      "print(it.throw(ValueError, ('a', 'b')))"
                      "print(next(it))"
                      "print(it.throw(ValueError, ValueError('z')))"
                      "try:"
                      "    it.throw(ValueError('x'), 'y')"
                      "except TypeError as e:"
                      "    print(e.args)"
                      "try:"
                      "    it.throw(ValueError, None, 5)"
                      "except TypeError as e:"
                      "    print(e.args)"
                      "try:"
                      "    it.throw(5)"
                      "except TypeError as e:"
                      "    print('not exc')"
                      "print(next(it))"))))


(deftest throw-non-exception-class-test
  (testing "a class not deriving from BaseException is a TypeError in the
            caller before it is called: its __init__ never runs and the
            generator stays suspended at its yield"
    (every-vm= (prints "1"
                       (str "('exceptions must be classes or instances"
                            " deriving from BaseException, not type',)")
                       "[]" "2")
               (lines "log = []"
                      "class C:"
                      "    def __init__(self, v):"
                      "        log.append(v)"
                      "def g():"
                      "    yield 1"
                      "    yield 2"
                      "it = g()"
                      "print(next(it))"
                      "try:"
                      "    it.throw(C, 7)"
                      "except TypeError as e:"
                      "    print(e.args)"
                      "print(log)"
                      "print(next(it))"))))


(deftest throw-uncaught-and-unstarted-test
  (testing "an uncaught throw runs the generator's finally, closes it and
            raises in the caller; a closed generator raises what is thrown;
            a just-started one closes without running its body"
    (every-vm= (prints "caller ('k',) ['ran', 'fin']" "done" "closed ('again',)"
                       "fresh ('x',) ['ran', 'fin']" "closed")
               (lines "log = []"
                      "def g():"
                      "    log.append('ran')"
                      "    try:"
                      "        yield 1"
                      "        yield 2"
                      "    finally:"
                      "        log.append('fin')"
                      "it = g()"
                      "next(it)"
                      "try:"
                      "    it.throw(KeyError, 'k')"
                      "except KeyError as e:"
                      "    print('caller', e.args, log)"
                      "print(next(it, 'done'))"
                      "try:"
                      "    it.throw(KeyError('again'))"
                      "except KeyError as e:"
                      "    print('closed', e.args)"
                      "it = g()"
                      "try:"
                      "    it.throw(ValueError('x'))"
                      "except ValueError as e:"
                      "    print('fresh', e.args, log)"
                      "print(next(it, 'closed'))"))))


(deftest close-runs-finally-test
  (testing "close runs the finally around the yield and answers None; it is
            idempotent; closing a just-started generator runs nothing;
            except Exception does not catch GeneratorExit"
    (every-vm= (prints "1" "None ['fin']" "None ['fin']" "closed"
                       "None ['fin']" "closed" "d fin" "None")
               (lines "log = []"
                      "def g():"
                      "    try:"
                      "        yield 1"
                      "        yield 2"
                      "    finally:"
                      "        log.append('fin')"
                      "it = g()"
                      "print(next(it))"
                      "print(it.close(), log)"
                      "print(it.close(), log)"
                      "print(next(it, 'closed'))"
                      "it2 = g()"
                      "print(it2.close(), log)"
                      "print(next(it2, 'closed'))"
                      "def d():"
                      "    try:"
                      "        yield 1"
                      "    except Exception:"
                      "        print('wrong')"
                      "    finally:"
                      "        print('d fin')"
                      "x = d()"
                      "next(x)"
                      "print(x.close())"))))


(deftest close-outcomes-test
  (testing "a return after GeneratorExit is None; another exception
            propagates; a yield is RuntimeError and the generator stays
            suspended, so it advances and closes later; GeneratorExit is a
            BaseException but not an Exception"
    (every-vm= (prints "a exit" "None" "propagated ('from close',)" "closed"
                       "0" "c ignores 1" "('generator ignored GeneratorExit',)"
                       "1" "c ignores 2" "None" "closed" "True False")
               (lines "def a():"
                      "    try:"
                      "        yield 1"
                      "    except GeneratorExit:"
                      "        print('a exit')"
                      "        return 5"
                      "def b():"
                      "    try:"
                      "        yield 1"
                      "    except GeneratorExit:"
                      "        raise ValueError('from close')"
                      "def c():"
                      "    n = 0"
                      "    while True:"
                      "        try:"
                      "            yield n"
                      "        except GeneratorExit:"
                      "            n += 1"
                      "            print('c ignores', n)"
                      "            if n == 2:"
                      "                return"
                      "x = a()"
                      "next(x)"
                      "print(x.close())"
                      "y = b()"
                      "next(y)"
                      "try:"
                      "    y.close()"
                      "except ValueError as e:"
                      "    print('propagated', e.args)"
                      "print(next(y, 'closed'))"
                      "z = c()"
                      "print(next(z))"
                      "try:"
                      "    z.close()"
                      "except RuntimeError as e:"
                      "    print(e.args)"
                      "print(next(z))"
                      "print(z.close())"
                      "print(next(z, 'closed'))"
                      "print(isinstance(GeneratorExit(), BaseException),"
                      "      isinstance(GeneratorExit(), Exception))"))))


(deftest generator-already-executing-test
  (testing "next, send, throw and close on a running generator are a
            ValueError in its own body before any switch; uncaught, it
            leaves the generator for the caller and closes it"
    (every-vm= (prints "('generator already executing',)"
                       "('generator already executing',)"
                       "('generator already executing',)"
                       "('generator already executing',)"
                       "1" "caller ('generator already executing',)" "closed")
               (lines "def g():"
                      "    for f in [lambda: next(me), lambda: me.send(1),"
                      "              lambda: me.throw(KeyError),"
                      "              lambda: me.close()]:"
                      "        try:"
                      "            f()"
                      "        except ValueError as e:"
                      "            print(e.args)"
                      "    yield 1"
                      "    me.send(None)"
                      "me = g()"
                      "print(next(me))"
                      "try:"
                      "    next(me)"
                      "except ValueError as e:"
                      "    print('caller', e.args)"
                      "print(next(me, 'closed'))"))))


(deftest pep-479-test
  (testing "a StopIteration escaping the body, raised, from an inner next,
            or thrown in, is RuntimeError; one caught inside is not"
    (every-vm= (prints "1" "('generator raised StopIteration',)" "closed"
                       "list ('generator raised StopIteration',)" "['ok']"
                       "thrown ('generator raised StopIteration',)")
               (lines "def g():"
                      "    yield 1"
                      "    raise StopIteration(9)"
                      "it = g()"
                      "print(next(it))"
                      "try:"
                      "    next(it)"
                      "except RuntimeError as e:"
                      "    print(e.args)"
                      "print(next(it, 'closed'))"
                      "def empty():"
                      "    return"
                      "    yield"
                      "def h():"
                      "    yield next(empty())"
                      "try:"
                      "    print(list(h()))"
                      "except RuntimeError as e:"
                      "    print('list', e.args)"
                      "def k():"
                      "    try:"
                      "        next(empty())"
                      "    except StopIteration:"
                      "        yield 'ok'"
                      "print(list(k()))"
                      "def t():"
                      "    yield 1"
                      "it = t()"
                      "next(it)"
                      "try:"
                      "    it.throw(StopIteration)"
                      "except RuntimeError as e:"
                      "    print('thrown', e.args)"))))


(deftest with-around-yield-test
  (testing "a with around a yield exits on close, on a thrown exception
            and on completion; an __exit__ that suppresses a thrown
            exception lets the generator run on to its next yield"
    (every-vm= (prints "enter a" "1" "exit a False" "None"
                       "enter a" "1" "exit a False" "caller ('k',)"
                       "enter a" "exit a True" "[1, 2]"
                       "quiet ('v',)" "after")
               (lines "class CM:"
                      "    def __init__(self, name):"
                      "        self.name = name"
                      "    def __enter__(self):"
                      "        print('enter', self.name)"
                      "        return self"
                      "    def __exit__(self, t, v, tb):"
                      "        print('exit', self.name, t is None)"
                      "        return False"
                      "class Quiet:"
                      "    def __enter__(self):"
                      "        return self"
                      "    def __exit__(self, t, v, tb):"
                      "        print('quiet', v.args)"
                      "        return True"
                      "def g():"
                      "    with CM('a'):"
                      "        yield 1"
                      "        yield 2"
                      "it = g()"
                      "print(next(it))"
                      "print(it.close())"
                      "it = g()"
                      "print(next(it))"
                      "try:"
                      "    it.throw(KeyError('k'))"
                      "except KeyError as e:"
                      "    print('caller', e.args)"
                      "print(list(g()))"
                      "def q():"
                      "    with Quiet():"
                      "        yield 1"
                      "    yield 'after'"
                      "it = q()"
                      "next(it)"
                      "print(it.throw(ValueError('v')))"))))


(deftest suspension-during-unwinding-test
  (testing "a nested generator closed from an outer one's handler, the throw
            driven from a different caller depth; a yield inside a finally
            that a throw or close is unwinding suspends the unwinding, and
            the next resume finishes it and re-raises in the caller"
    (every-vm= (prints "i1" "outer caught" "inner fin" "o3" "end"
                       "cleanup" "after cleanup" "rethrown ('v',)"
                       "('generator ignored GeneratorExit',)" "after cleanup"
                       "exit escaped" "closed")
               (lines "def inner():"
                      "    try:"
                      "        yield 'i1'"
                      "    finally:"
                      "        print('inner fin')"
                      "def outer():"
                      "    i = inner()"
                      "    try:"
                      "        yield next(i)"
                      "        yield 'o2'"
                      "    except KeyError:"
                      "        print('outer caught')"
                      "        i.close()"
                      "        yield 'o3'"
                      "def drive(it):"
                      "    return it.throw(KeyError)"
                      "o = outer()"
                      "print(next(o))"
                      "print(drive(o))"
                      "print(next(o, 'end'))"
                      "def u():"
                      "    try:"
                      "        yield 1"
                      "    finally:"
                      "        yield 'cleanup'"
                      "        print('after cleanup')"
                      "it = u()"
                      "next(it)"
                      "print(it.throw(ValueError('v')))"
                      "try:"
                      "    next(it)"
                      "except ValueError as e:"
                      "    print('rethrown', e.args)"
                      "it = u()"
                      "next(it)"
                      "try:"
                      "    it.close()"
                      "except RuntimeError as e:"
                      "    print(e.args)"
                      "try:"
                      "    next(it)"
                      "except GeneratorExit:"
                      "    print('exit escaped')"
                      "print(next(it, 'closed'))"))))


;; =============================================================================
;; Continuation length (JVM-only: reads the final VM's heap)
;; =============================================================================

(def ^:private opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :modules (-> (module/empty-registry)
                module/register-cell-module
                data/register-data-module)})


(def ^:private load-semantic
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(def ^:private final-vms
  "Each VM's final state for a lowered program."
  {:ast-walker (fn [ast _] (vm/eval (tu/create-vm opts) ast)),
   :semantic (fn [_ datoms] (vm/run (load-semantic (semantic/create-vm opts) datoms))),
   :stack (fn [_ datoms]
            (vm/run (dvm/create-vm (:image (dl/adapt datoms))
                                   (assoc opts :contract vm/stack-contract)))),
   :register (fn [_ datoms]
               (vm/run (rvm/create-vm (:image (rc/adapt datoms))
                                      (assoc opts :contract vm/register-contract))))})


(defn- size
  "Node count of a continuation payload; closures, continuations and cell
   refs held in it count one each, not their contents."
  [x]
  (cond
    (values/host-typed? x) 1
    (map? x) (reduce + 1 (map size (concat (keys x) (vals x))))
    (coll? x) (reduce + 1 (map size x))
    :else 1))


(defn- suspended-resume-size
  "The size of the one suspended generator's `:resume` after the module
   consumed `n` items from a global generator, on VM `k`."
  [k n]
  (let [ast (lower/lower-packet
              (parser/parse-source (parser/make-worker) [:u 1]
                                   (lines "def g():"
                                          "    i = 0"
                                          "    while True:"
                                          "        yield i"
                                          "        i += 1"
                                          "it = g()"
                                          "for x in it:"
                                          (str "    if x == " n ":")
                                          "        break")))
        final ((get final-vms k) ast (vm/ast->datoms ast))
        gens (keep (fn [[_ {:keys [value]}]]
                     (when (= :generator (:py/type value)) value))
                   (:heap final))]
    (is (= 1 (count gens)) (str k " holds one generator"))
    (is (= :suspended (:state (first gens))) (str k))
    (is (= #{:py/type :name :state :resume :ctx} (set (keys (first gens))))
        (str k " a suspended generator holds only its resume and context"))
    (size (values/payload (:resume (first gens))))))


(deftest resume-continuation-length-is-stable-test
  (testing "the suspended generator's :resume has the same size after 10 and
            after 1000 items: no per-yield growth, the stale base fixed"
    (doseq [k [:ast-walker :semantic :stack :register]]
      (testing (str k)
        (is (= (suspended-resume-size k 10) (suspended-resume-size k 1000)))))))
