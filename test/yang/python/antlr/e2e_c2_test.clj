(ns yang.python.antlr.e2e-c2-test
  "Phase C2 slices S1, generators core, S2, send, throw, close and the
   dynamic context, S3, yield from, iter and sequence iterators, and S4,
   generator expressions, end to end on all four VMs over the
   real cell and data modules, through the same stream topology as
   `yang.python.antlr.e2e-test`. Expected output is CPython 3.9.6's for the
   same source. The continuation-length check reads the final VM's heap,
   so it is JVM-only."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.python.antlr.e2e-test :as e2e]
    [yang.python.antlr.lower :as lower]
    [yang.python.antlr.parser :as parser]
    [yang.python.antlr.prelude :as prelude]
    [yin.vm :as vm]
    [yin.vm.data :as data]
    [yin.vm.debruijn-linearize :as dl]
    [yin.vm.debruijn-register-compile :as rc]
    [yin.vm.debruijn.register :as rvm]
    [yin.vm.debruijn.stack :as dvm]
    [yin.vm.engine :as engine]
    [yin.vm.integer :as integer]
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


(deftest ^:slow long-generator-with-break-test
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
;; S3 acceptance: yield from, iter and sequence iterators
;; =============================================================================

(deftest yield-from-send-through-test
  (testing "yield from passes sends to the inner generator, its return value
            is the expression's value, and a list delegates through a
            sequence iterator"
    (every-vm= (prints "1" "got s" "v r" "10" "20" "end")
               (lines "def inner():"
                      "    x = yield 1"
                      "    print('got', x)"
                      "    return 'r'"
                      "def outer():"
                      "    v = yield from inner()"
                      "    print('v', v)"
                      "    yield from [10, 20]"
                      "o = outer()"
                      "print(next(o))"
                      "print(o.send('s'))"
                      "print(next(o))"
                      "print(next(o, 'end'))"))))


(deftest yield-from-throw-through-test
  (testing "a throw at the outer reaches the inner's handler and its next
            yield is throw's value; uncaught by the inner, it closes both and
            raises in the caller; a sequence delegate has no throw, so it is
            raised in the outer, and no send"
    (every-vm= (prints "1" "inner caught ('x',)" "handled" "v ret" "after"
                       "caller ('k',)" "closed"
                       "1" "outer got it" "k" "no send")
               (lines "def inner():"
                      "    try:"
                      "        yield 1"
                      "    except ValueError as e:"
                      "        print('inner caught', e.args)"
                      "        yield 'handled'"
                      "    return 'ret'"
                      "def outer():"
                      "    v = yield from inner()"
                      "    print('v', v)"
                      "    yield 'after'"
                      "o = outer()"
                      "print(next(o))"
                      "print(o.throw(ValueError('x')))"
                      "print(next(o))"
                      "def bare():"
                      "    yield 1"
                      "def through():"
                      "    yield from bare()"
                      "t = through()"
                      "next(t)"
                      "try:"
                      "    t.throw(KeyError('k'))"
                      "except KeyError as e:"
                      "    print('caller', e.args)"
                      "print(next(t, 'closed'))"
                      "def seq():"
                      "    try:"
                      "        yield from [1, 2]"
                      "    except KeyError:"
                      "        print('outer got it')"
                      "        yield 'k'"
                      "s = seq()"
                      "print(next(s))"
                      "print(s.throw(KeyError))"
                      "s = seq()"
                      "next(s)"
                      "try:"
                      "    s.send('x')"
                      "except AttributeError:"
                      "    print('no send')"))))


(deftest yield-from-close-order-test
  (testing "closing the outer closes the inner first, so the inner's finally
            runs before the outer's; an inner that ignores GeneratorExit
            makes the outer's close a RuntimeError after the outer's finally"
    (every-vm= (prints "1" "inner fin" "outer fin" "None" "closed"
                       "wrap fin" "('generator ignored GeneratorExit',)")
               (lines "def inner():"
                      "    try:"
                      "        yield 1"
                      "    finally:"
                      "        print('inner fin')"
                      "def outer():"
                      "    try:"
                      "        yield from inner()"
                      "    finally:"
                      "        print('outer fin')"
                      "o = outer()"
                      "print(next(o))"
                      "print(o.close())"
                      "print(next(o, 'closed'))"
                      "def stubborn():"
                      "    try:"
                      "        yield 1"
                      "    except GeneratorExit:"
                      "        yield 2"
                      "def wrap():"
                      "    try:"
                      "        yield from stubborn()"
                      "    finally:"
                      "        print('wrap fin')"
                      "w = wrap()"
                      "next(w)"
                      "try:"
                      "    w.close()"
                      "except RuntimeError as e:"
                      "    print(e.args)"))))


(deftest yield-from-thrown-stop-iteration-test
  (testing "StopIteration thrown through a closed generator delegate completes
            the delegation with its value (a subclass's first argument, a
            class None), as CPython's _gen_throw does; one raised in the
            delegate's body, or thrown at a sequence delegate, is PEP 479's
            RuntimeError"
    (every-vm= (prints "v 7" "after" "v 8" "after" "v None" "after"
                       "pep479 ('generator raised StopIteration',)"
                       "seq ('generator raised StopIteration',)")
               (lines "def inner():"
                      "    yield 1"
                      "    yield 2"
                      "def outer(i):"
                      "    v = yield from i"
                      "    print('v', v)"
                      "    yield 'after'"
                      "class MyStop(StopIteration):"
                      "    pass"
                      "for exc in [StopIteration(7), MyStop(8, 9), StopIteration]:"
                      "    i = inner()"
                      "    o = outer(i)"
                      "    next(o)"
                      "    i.close()"
                      "    print(o.throw(exc))"
                      "def body():"
                      "    yield 1"
                      "    raise StopIteration(5)"
                      "o = outer(body())"
                      "next(o)"
                      "try:"
                      "    next(o)"
                      "except RuntimeError as e:"
                      "    print('pep479', e.args)"
                      "def lst():"
                      "    v = yield from [1, 2]"
                      "    yield v"
                      "o = lst()"
                      "next(o)"
                      "try:"
                      "    o.throw(StopIteration(3))"
                      "except RuntimeError as e:"
                      "    print('seq', e.args)"))))


(deftest yield-from-chain-with-changing-callers-test
  (testing "a three-deep chain resumed from the top level, a function, another
            generator and a delegating generator; a throw through three
            levels from a function caller"
    (every-vm= (prints "c1" "c2" "b-list" "a-end" "done"
                       (str "[('c got', 'sent'), 'c fin', ('b got', 'c-ret'),"
                            " ('a got', 'b-ret')]")
                       "c caught" "end")
               (lines "log = []"
                      "def c():"
                      "    x = yield 'c1'"
                      "    log.append(('c got', x))"
                      "    try:"
                      "        yield 'c2'"
                      "    finally:"
                      "        log.append('c fin')"
                      "    return 'c-ret'"
                      "def b():"
                      "    r = yield from c()"
                      "    log.append(('b got', r))"
                      "    yield from ['b-list']"
                      "    return 'b-ret'"
                      "def a():"
                      "    r = yield from b()"
                      "    log.append(('a got', r))"
                      "    yield 'a-end'"
                      "g = a()"
                      "print(next(g))"
                      "def via_function(it, v):"
                      "    return it.send(v)"
                      "print(via_function(g, 'sent'))"
                      "def via_generator(it):"
                      "    yield next(it)"
                      "w = via_generator(g)"
                      "print(next(w))"
                      "def deleg():"
                      "    yield from g"
                      "d = deleg()"
                      "print(next(d))"
                      "print(next(d, 'done'))"
                      "print(log)"
                      "def c3():"
                      "    try:"
                      "        yield 1"
                      "    except KeyError:"
                      "        yield 'c caught'"
                      "def b3():"
                      "    yield from c3()"
                      "def a3():"
                      "    yield from b3()"
                      "g = a3()"
                      "next(g)"
                      "def thrower(it):"
                      "    return it.throw(KeyError)"
                      "print(thrower(g))"
                      "print(next(g, 'end'))"))))


(deftest yield-from-suspension-during-unwinding-test
  (testing "a yield in the inner's finally while a thrown exception unwinds
            it, then in the outer's except and finally; an exception that
            unwinds through both finallys suspends at each and reaches the
            caller after them"
    (every-vm= (prints "1" "inner cleanup" "outer handler" "outer cleanup" "end"
                       "inner cleanup" "outer2 cleanup" "caller ('v',)")
               (lines "def inner():"
                      "    try:"
                      "        yield 1"
                      "    finally:"
                      "        yield 'inner cleanup'"
                      "def outer():"
                      "    try:"
                      "        yield from inner()"
                      "    except KeyError:"
                      "        yield 'outer handler'"
                      "    finally:"
                      "        yield 'outer cleanup'"
                      "o = outer()"
                      "print(next(o))"
                      "print(o.throw(KeyError))"
                      "print(next(o))"
                      "print(next(o))"
                      "print(next(o, 'end'))"
                      "def outer2():"
                      "    try:"
                      "        yield from inner()"
                      "    finally:"
                      "        yield 'outer2 cleanup'"
                      "o = outer2()"
                      "next(o)"
                      "print(o.throw(ValueError('v')))"
                      "print(next(o))"
                      "try:"
                      "    next(o)"
                      "except ValueError as e:"
                      "    print('caller', e.args)"))))


(deftest ^:slow yield-from-long-range-test
  (testing "yield from range(3000), directly and through a second level of
            delegation, summed by a for; the range's return value is None"
    (every-vm= (prints "v None" "4498500" "v None" "4498500")
               (lines "def g():"
                      "    v = yield from range(3000)"
                      "    print('v', v)"
                      "def h():"
                      "    yield from g()"
                      "s = 0"
                      "for x in g():"
                      "    s += x"
                      "print(s)"
                      "n = 0"
                      "for x in h():"
                      "    n += x"
                      "print(n)"))))


(deftest iter-protocol-test
  (testing "iter of a sequence is a stateful iterator, its own iter; a
            generator is its own iter; a list iterator walks the list live
            and stays exhausted; a dict iterator sees the size change; a
            non-iterable is a TypeError at iter, a non-iterator at next"
    (every-vm= (prints "1 2 d" "True" "True" "not iterable" "not an iterator"
                       "0 True" "1" "2" "['a', 'b'] []" "1 2" "1 2 d"
                       "stays exhausted"
                       "('dictionary changed size during iteration',)"
                       "stop ()")
               (lines "it = iter([1, 2])"
                      "print(next(it), next(it), next(it, 'd'))"
                      "print(iter(it) is it)"
                      "def gen():"
                      "    yield 1"
                      "x = gen()"
                      "print(iter(x) is x)"
                      "try:"
                      "    iter(5)"
                      "except TypeError:"
                      "    print('not iterable')"
                      "try:"
                      "    next([1])"
                      "except TypeError:"
                      "    print('not an iterator')"
                      "it = iter(range(3))"
                      "print(it.__next__(), it.__iter__() is it)"
                      "for y in it:"
                      "    print(y)"
                      "it = iter('ab')"
                      "print(list(it), list(it))"
                      "a, b = iter((1, 2))"
                      "print(a, b)"
                      "l = [1]"
                      "it = iter(l)"
                      "l.append(2)"
                      "print(next(it), next(it), next(it, 'd'))"
                      "l.append(3)"
                      "print(next(it, 'stays exhausted'))"
                      "d = {'a': 1}"
                      "it = iter(d)"
                      "d['b'] = 2"
                      "try:"
                      "    next(it)"
                      "except RuntimeError as e:"
                      "    print(e.args)"
                      "try:"
                      "    next(iter([]))"
                      "except StopIteration as e:"
                      "    print('stop', e.args)"))))


;; =============================================================================
;; S4 acceptance: generator expressions are anonymous generators
;; =============================================================================

(deftest genexp-laziness-test
  (testing "any and all stop at the first decisive element and never
            produce later ones; sum and set stop at the element that fails;
            next on a generator expression runs one element at a time"
    (every-vm= (prints "True [1, 2, 3]" "False [1, 2]" "6 [1, 2, 3]" "[]"
                       "10 [1]" "20 [1, 2]" "[30] [1, 2, 3]" "done"
                       "sum stopped [1, 'a']" "set stopped [1, [2]]"
                       "True False [0, 1, 3, 0]")
               (lines "log = []"
                      "def gen():"
                      "    for i in [1, 2, 3, 4, 5]:"
                      "        log.append(i)"
                      "        yield i"
                      "print(any(x > 2 for x in gen()), log)"
                      "log = []"
                      "print(all(x < 2 for x in gen()), log)"
                      "log = []"
                      "def f(x):"
                      "    log.append(x)"
                      "    return x"
                      "print(sum(f(x) for x in [1, 2, 3]), log)"
                      "log = []"
                      "g = (f(x) * 10 for x in [1, 2, 3])"
                      "print(log)"
                      "print(next(g), log)"
                      "print(next(g), log)"
                      "print(list(g), log)"
                      "print(next(g, 'done'))"
                      "log = []"
                      "try:"
                      "    sum(f(x) for x in [1, 'a', 3])"
                      "except TypeError:"
                      "    print('sum stopped', log)"
                      "log = []"
                      "try:"
                      "    set(f(x) for x in [1, [2], 3])"
                      "except TypeError:"
                      "    print('set stopped', log)"
                      "log = []"
                      "print(any(f(x) for x in [0, 1, 2]), all(f(x) for x in [3, 0, 4]), log)"))))


(deftest genexp-outermost-iterable-at-creation-test
  (testing "the outermost iterable is evaluated and iter()-checked when the
            generator expression is created; every later for and if clause
            runs lazily, as the generator is resumed"
    (every-vm= (prints "['src']" "1 ['src', ('if', 1), ('inner', 1)]"
                       (str "[10, 2, 20] ['src', ('if', 1), ('inner', 1), "
                            "('if', 2), ('inner', 2)]")
                       "created TypeError" "created" "next TypeError"
                       "created" "next AttributeError")
               (lines "log = []"
                      "def src():"
                      "    log.append('src')"
                      "    return [1, 2]"
                      "def inner(x):"
                      "    log.append(('inner', x))"
                      "    return [x, x * 10]"
                      "g = (y for x in src() if log.append(('if', x)) is None for y in inner(x))"
                      "print(log)"
                      "print(next(g), log)"
                      "print(list(g), log)"
                      "try:"
                      "    g2 = (x for x in 5)"
                      "except TypeError:"
                      "    print('created TypeError')"
                      "g3 = (x for x in [1] for y in 5)"
                      "print('created')"
                      "try:"
                      "    next(g3)"
                      "except TypeError:"
                      "    print('next TypeError')"
                      "g4 = (x for x in [1] if x.missing)"
                      "print('created')"
                      "try:"
                      "    next(g4)"
                      "except AttributeError:"
                      "    print('next AttributeError')"))))


(deftest genexp-scopes-test
  (testing "the outermost iterable is read in the enclosing scope at
            creation; free variables of the other clauses bind late, in the
            generator expression's own scope; a class body's names are not
            visible inside; the target does not leak"
    (every-vm= (prints "[21, 22]" "[0, 5, 10]" "[1, 2]" "[1, 2]" "NameError"
                       "[1, 2] outer")
               (lines "xs = [1, 2]"
                      "n = 10"
                      "g = (x + n for x in xs)"
                      "xs = [100]"
                      "n = 20"
                      "print(list(g))"
                      "def make():"
                      "    k = 1"
                      "    g = (k * x for x in range(3))"
                      "    k = 5"
                      "    return g"
                      "print(list(make()))"
                      "def outer():"
                      "    ys = [1, 2]"
                      "    g = (y for y in ys)"
                      "    ys = [3]"
                      "    return list(g)"
                      "print(outer())"
                      "class C:"
                      "    items = [1, 2]"
                      "    gen = (i for i in items)"
                      "print(list(C.gen))"
                      "class D:"
                      "    v = 3"
                      "    try:"
                      "        r = list(v * i for i in [1])"
                      "    except NameError:"
                      "        r = 'NameError'"
                      "print(D.r)"
                      "x = 'outer'"
                      "g = (x for x in [1, 2])"
                      "print(list(g), x)"))))


(deftest genexp-nesting-and-call-arguments-test
  (testing "nested generator expressions; a generator expression as the
            sole call argument; a consumer name rebound locally, at module
            level and through globals() receives the generator: nothing is
            inlined"
    (every-vm= (prints "[(1, 0), (2, 0), (2, 2), (3, 0), (3, 3), (3, 6)]"
                       "[0, 0, 1, 3, 6]" "4 [6]" "[2, 4, 'f']"
                       "['a', 'b'] (1, 2) {1}" "7" "[1, 2, 'mine']"
                       "[0, 1, 'rebound']")
               (lines "g = ((x, y) for x in range(4) for y in (x * k for k in range(x)))"
                      "print(list(g))"
                      "nested = (sum(y for y in range(x)) for x in range(5))"
                      "print(list(nested))"
                      "gg = (x for x in (y * 2 for y in [1, 2, 3]) if x > 2)"
                      "print(next(gg), list(gg))"
                      "def f(it):"
                      "    return list(it) + ['f']"
                      "print(f(x * 2 for x in [1, 2]))"
                      "print(list(x for x in 'ab'), tuple(x for x in [1, 2]), set(x for x in [1, 1]))"
                      "def h():"
                      "    any = lambda g: next(g)"
                      "    return any(x for x in [7, 8])"
                      "print(h())"
                      "sum = lambda g: list(g) + ['mine']"
                      "print(sum(x for x in [1, 2]))"
                      "globals()['all'] = lambda g: list(g) + ['rebound']"
                      "print(all(x for x in [0, 1]))"))))


(deftest genexp-pep-479-test
  (testing "a StopIteration escaping a generator expression's body, from an
            inner next, an exhausted iterator, a call, or thrown in, is
            RuntimeError, and the generator expression is then closed"
    (every-vm= (prints "('generator raised StopIteration',)" "closed"
                       "iter ('generator raised StopIteration',)"
                       "any ('generator raised StopIteration',)"
                       "thrown ('generator raised StopIteration',)")
               (lines "def empty():"
                      "    return"
                      "    yield"
                      "g = (next(empty()) for x in [1])"
                      "try:"
                      "    list(g)"
                      "except RuntimeError as e:"
                      "    print(e.args)"
                      "print(next(g, 'closed'))"
                      "it = iter([])"
                      "g = (next(it) for x in [1, 2])"
                      "try:"
                      "    next(g)"
                      "except RuntimeError as e:"
                      "    print('iter', e.args)"
                      "def stop():"
                      "    raise StopIteration(4)"
                      "try:"
                      "    print(any(stop() for x in [1]))"
                      "except RuntimeError as e:"
                      "    print('any', e.args)"
                      "g = (x for x in [1, 2])"
                      "next(g)"
                      "try:"
                      "    g.throw(StopIteration)"
                      "except RuntimeError as e:"
                      "    print('thrown', e.args)"))))


(deftest genexp-generator-protocol-test
  (testing "send, throw, close, __next__ and __iter__ on a generator
            expression behave as on any generator (S2): a sent value is
            ignored, a non-None first send is a TypeError, a throw is raised
            at the yield and is not forwarded to the iterated generator, a
            throw into an unstarted one closes it, close is idempotent; a
            generator expression delegates under yield from"
    (every-vm= (prints "10" "20" "30" "stop ()"
                       "(\"can't send non-None value to a just-started generator\",)"
                       "1" "thrown" "closed" "unstarted thrown" "closed" "1"
                       "None closed None" "closed" "True 1 True" "1"
                       "genexp does not forward throw" "[1, 2]")
               (lines "g = (x * 10 for x in [1, 2, 3])"
                      "print(next(g))"
                      "print(g.send(None))"
                      "print(g.send(5))"
                      "try:"
                      "    g.send(1)"
                      "except StopIteration as e:"
                      "    print('stop', e.args)"
                      "g = (x for x in [1, 2])"
                      "try:"
                      "    g.send(3)"
                      "except TypeError as e:"
                      "    print(e.args)"
                      "print(next(g))"
                      "try:"
                      "    g.throw(ValueError)"
                      "except ValueError:"
                      "    print('thrown')"
                      "print(next(g, 'closed'))"
                      "g = (x for x in [1, 2])"
                      "try:"
                      "    g.throw(KeyError)"
                      "except KeyError:"
                      "    print('unstarted thrown')"
                      "print(next(g, 'closed'))"
                      "g = (x for x in [1, 2])"
                      "print(next(g))"
                      "print(g.close(), next(g, 'closed'), g.close())"
                      "g = (x for x in [1])"
                      "g.close()"
                      "print(next(g, 'closed'))"
                      "g = (x for x in [1, 2])"
                      "print(iter(g) is g, g.__next__(), g.__iter__() is g)"
                      "def inner():"
                      "    try:"
                      "        yield 1"
                      "    except ValueError:"
                      "        yield 'caught'"
                      "g = (x for x in inner())"
                      "print(next(g))"
                      "try:"
                      "    g.throw(ValueError)"
                      "except ValueError:"
                      "    print('genexp does not forward throw')"
                      "def user():"
                      "    g = (x for x in [1, 2])"
                      "    yield next(g)"
                      "    yield from g"
                      "print(list(user()))"))))


;; =============================================================================
;; Continuation length (JVM-only: reads the final VM's heap)
;; =============================================================================

(def ^:private opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives,
   :modules (-> (module/empty-registry)
                module/register-cell-module
                (data/register-data-module {::data/max-items 1048576})
                (integer/register-integer-module
                  {::integer/max-bits 100000, ::integer/max-digits 4300})
                prelude/admit)})


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


(defn- suspended-resume-sizes
  "Generator name -> the size of its `:resume`, for the `m` generators
   suspended after `source` ran on VM `k`."
  [k m source]
  (let [ast (lower/lower-packet
              (parser/parse-source (parser/make-worker) [:u 1] source))
        final ((get final-vms k) ast (vm/ast->datoms ast))
        gens (keep (fn [[_ {:keys [value]}]]
                     (when (= :generator (:py/type value)) value))
                   (:heap final))]
    (is (= m (count gens)) (str k " holds " m " generators"))
    (doseq [g gens]
      (is (= :suspended (:state g)) (str k))
      (is (= #{:py/type :name :state :resume :ctx} (set (keys g)))
          (str k " a suspended generator holds only its resume and context")))
    (into {} (map (fn [g] [(:name g) (size (values/payload (:resume g)))])) gens)))


(defn- counter-source
  "A global generator consumed for `n` items; `delegate?` puts a
   `yield from` level between the loop and the counting generator."
  [n delegate?]
  (lines "def g():"
         "    i = 0"
         "    while True:"
         "        yield i"
         "        i += 1"
         "def h():"
         "    yield from g()"
         (if delegate? "it = h()" "it = g()")
         "for x in it:"
         (str "    if x == " n ":")
         "        break"))


(deftest ^:slow resume-continuation-length-is-stable-test
  (testing "the suspended generator's :resume has the same size after 10 and
            after 1000 items: no per-yield growth, the stale base fixed"
    (doseq [k [:ast-walker :semantic :stack :register]]
      (testing (str k)
        (is (= (suspended-resume-sizes k 1 (counter-source 10 false))
               (suspended-resume-sizes k 1 (counter-source 1000 false)))))))
  (testing "under yield from, neither the delegating nor the inner
            generator's :resume grows per item"
    (doseq [k [:ast-walker :semantic :stack :register]]
      (testing (str k)
        (is (= (suspended-resume-sizes k 2 (counter-source 10 true))
               (suspended-resume-sizes k 2 (counter-source 1000 true))))))))


(defn- deep-size
  "Node count of `x` with continuation and closure payloads followed; a
   cell ref counts as itself (its cell is counted once, as a heap entry)."
  [x]
  (cond
    (values/host-typed? x) (+ 1 (deep-size (values/payload x)))
    (map? x) (reduce + 1 (map deep-size (concat (keys x) (vals x))))
    (coll? x) (reduce + 1 (map deep-size x))
    :else 1))


(defn- live-after-collection
  "[live cell count, deep size of all live cell content] once VM `k` ran
   `source` and its final state was collected with the suspended
   generator named `root` as the only extra root: everything that
   generator reaches, continuations followed, not one `:resume` alone.
   The module's own globals are garbage once it completes, so without the
   root nothing of the program would be live."
  [k source root]
  (let [ast (lower/lower-packet
              (parser/parse-source (parser/make-worker) [:u 1] source))
        final ((get final-vms k) ast (vm/ast->datoms ast))
        roots (into []
                    (keep (fn [[id {:keys [value seal]}]]
                            (when (and (= :generator (:py/type value))
                                       (= :suspended (:state value))
                                       (= root (:name value)))
                              {:type :cell-ref, :id id, :seal seal})))
                    (:heap final))
        heap (:heap (engine/collect final roots))]
    (is (= 1 (count roots)) (str k " one suspended " root))
    [(count heap) (reduce + (map (fn [[_ {:keys [value]}]] (deep-size value)) heap))]))


(defn- changing-caller-source
  "`n` items through a `yield from` level, resumed alternately by a
   function and by a fresh generator dropped after one step."
  [n]
  (lines "def g():"
         "    i = 0"
         "    while True:"
         "        yield i"
         "        i += 1"
         "def h():"
         "    yield from g()"
         "it = h()"
         "def by_function(x):"
         "    return next(x)"
         "def by_generator(x):"
         "    yield next(x)"
         "k = 0"
         (str "while k < " n ":")
         "    if k % 2 == 0:"
         "        by_function(it)"
         "    else:"
         "        next(by_generator(it))"
         "    k += 1"))


(deftest ^:slow reachable-heap-is-stable-under-delegation-test
  (testing "after a forced collection, the live heap (cells and their content,
            continuations followed) is the same after 10 and after 1000 items
            resumed through yield from by changing callers: no retention
            grows per item or per caller"
    (doseq [k [:ast-walker :semantic :stack :register]]
      (testing (str k)
        (is (= (live-after-collection k (changing-caller-source 10) "h")
               (live-after-collection k (changing-caller-source 1000) "h")))))))
