(ns yang.python.antlr.e2e-c1-test
  "Phase C1 end to end on all four VMs over the real cell and data modules,
   through the same stream topology as `yang.python.antlr.e2e-test`:
   finally and with on every exit, tuples and unpacking, slices, the new
   operators, comprehensions, keyword arguments, and the r2 gate's P3s."
  (:require
    [clojure.test :refer [deftest testing]]
    [yang.python.antlr.e2e-test :as e2e]))


(defn- prints
  [& lines]
  {:py/out (vec lines), :py/exception nil})


(def ^:private every-vm=
  "Naive and under no-op safepoint hooks, as `e2e/every-vm=`."
  e2e/every-vm=)


(defn- lines
  [& ls]
  (apply str (map #(str % "\n") ls)))


;; =============================================================================
;; 1. finally and with
;; =============================================================================

(deftest ^:slow finally-runs-on-every-exit-test
  (testing "normal completion, return, break, continue and raise all run the
            finally block"
    (every-vm=
      (prints "end r end end"
              (str "['body', 'fin-ok', 'body', 'fin-ok', 'body', 'fin-ok', "
                   "'fin-ret', 'fin-brk', 'fin-cont', 'fin-cont', 'fin-cont', "
                   "'fin-raise', 'caught']"))
      (lines "log = []"
             "def f(k):"
             "    for i in range(3):"
             "        try:"
             "            if k == 'ret':"
             "                return 'r'"
             "            if k == 'brk':"
             "                break"
             "            if k == 'cont':"
             "                continue"
             "            if k == 'raise':"
             "                raise ValueError('v')"
             "            log.append('body')"
             "        finally:"
             "            log.append('fin-' + k)"
             "    return 'end'"
             "print(f('ok'), f('ret'), f('brk'), f('cont'))"
             "try:"
             "    f('raise')"
             "except ValueError:"
             "    log.append('caught')"
             "print(log)"))))


(deftest ^:slow finally-nesting-and-override-test
  (testing "nested finally blocks run innermost first; a return in finally
            overrides; finally follows a handler that returns"
    (every-vm= (prints "1 2 m ['inner', 'outer', 'except', 'fin']")
               (lines "out = []"
                      "def g():"
                      "    try:"
                      "        try:"
                      "            return 1"
                      "        finally:"
                      "            out.append('inner')"
                      "    finally:"
                      "        out.append('outer')"
                      "def h():"
                      "    try:"
                      "        return 1"
                      "    finally:"
                      "        return 2"
                      "def m():"
                      "    try:"
                      "        raise KeyError('k')"
                      "    except KeyError:"
                      "        out.append('except')"
                      "        return 'm'"
                      "    finally:"
                      "        out.append('fin')"
                      "print(g(), h(), m(), out)"))))


(deftest raise-in-finally-and-handler-stack-test
  (testing "an exception raised in finally replaces the one in flight, and
            the handler stack is consistent afterwards"
    (every-vm= (prints "key ('b',)" "ok" "after-break")
               (lines "try:"
                      "    try:"
                      "        raise ValueError('a')"
                      "    finally:"
                      "        raise KeyError('b')"
                      "except KeyError as e:"
                      "    print('key', e.args)"
                      "except ValueError:"
                      "    print('value')"
                      "while True:"
                      "    try:"
                      "        break"
                      "    finally:"
                      "        pass"
                      "try:"
                      "    raise TypeError('t')"
                      "except TypeError:"
                      "    print('ok')"
                      "print('after-break')"))))


(deftest ^:slow with-statement-test
  (testing "__enter__/__exit__ on normal exit, a suppressing __exit__, return
            through two managers, and a propagating exception"
    (every-vm= (prints "enter a" "in a" "exit a none" "enter b" "exit b ValueError"
                       "enter c" "enter d" "exit d none" "exit c none" "d"
                       "enter e" "exit e KeyError" "propagated")
               (lines "log = []"
                      "class M:"
                      "    def __init__(self, name, suppress=False):"
                      "        self.name = name"
                      "        self.suppress = suppress"
                      "    def __enter__(self):"
                      "        log.append('enter ' + self.name)"
                      "        return self.name"
                      "    def __exit__(self, t, v, tb):"
                      "        log.append('exit ' + self.name + ' ' + ('none' if t is None else t.__name__))"
                      "        return self.suppress"
                      "with M('a') as x:"
                      "    log.append('in ' + x)"
                      "with M('b', True):"
                      "    raise ValueError('swallowed')"
                      "def f():"
                      "    with M('c'), M('d') as y:"
                      "        return y"
                      "log.append(f())"
                      "try:"
                      "    with M('e'):"
                      "        raise KeyError('k')"
                      "except KeyError:"
                      "    log.append('propagated')"
                      "for line in log:"
                      "    print(line)"))))


;; =============================================================================
;; 2. Tuples, unpacking and slices
;; =============================================================================

(deftest ^:slow tuples-and-unpacking-test
  (every-vm= (prints "6 1 2 3 1 [2, 3, 4] ['a', 'b'] c p p"
                     "a 1" "b 2"
                     "2 1"
                     "unpack")
             (lines "t = (1, 2, 3)"
                    "a, b, c = t"
                    "x, (y, z) = 1, [2, 3]"
                    "first, *rest = [1, 2, 3, 4]"
                    "*init, last = 'abc'"
                    "d = {(1, 2): 'p'}"
                    "print(a + b + c, x, y, z, first, rest, init, last, d[1, 2], d[(1.0, 2)])"
                    "for k, v in {'a': 1, 'b': 2}.items():"
                    "    print(k, v)"
                    "a, b = 1, 2"
                    "a, b = b, a"
                    "print(a, b)"
                    "try:"
                    "    p, q = [1, 2, 3]"
                    "except ValueError:"
                    "    print('unpack')")))


(deftest ^:slow slices-test
  (every-vm= (prints "() (1,) (3, 2, 1) (2, 3) [4, 5] ell hlo"
                     "(2, 3) he (3, 2)"
                     "[0, 'a', 3, 4] [] True 3 [] [4, 3]")
             (lines "t = (1, 2, 3)"
                    "print((), (1,), t[::-1], t[1:], [1, 2, 3, 4, 5][-2:], 'hello'[1:4], 'hello'[::2])"
                    "print(t[1:100], 'hello'[-100:2], t[100:0:-1])"
                    "L = [0, 1, 2, 3, 4]"
                    "L[1:3] = ['a']"
                    "print(L, L[10:], t == (1, 2, 3), len(t), L[3:1], L[:1:-1])")))


(deftest tuples-are-values-and-unhashable-lists-test
  (every-vm= (prints "True False 2" "unhashable")
             (lines "a = (1, [2])"
                    "s = {(1, 2), (1, 2.0), (2, 1)}"
                    "print((1, 2) == (1.0, 2), (1, 2) == (2, 1), len(s))"
                    "try:"
                    "    {[1]: 2}"
                    "except TypeError:"
                    "    print('unhashable')")))


;; =============================================================================
;; 3. Operators
;; =============================================================================

(deftest ^:slow arithmetic-operators-test
  (every-vm= (prints "3 -4 1 2 -2 1024 0.5 8.0 -8 512 -4"
                     "3.0 0.5 2 7 5 -6 2 16 -4 2"
                     "zero")
             (lines "print(7 // 2, -7 // 2, 7 % 3, -7 % 3, 7 % -3, 2 ** 10, 2 ** -1, 2.0 ** 3, (-2) ** 3, 2 ** 3 ** 2, -2 ** 2)"
                    "print(7.5 // 2, -7.5 % 2, 6 & 3, 6 | 3, 6 ^ 3, ~5, -6 & 3, 1 << 4, -16 >> 2, 5 >> 1)"
                    "try:"
                    "    1 // 0"
                    "except ZeroDivisionError:"
                    "    print('zero')")))


(deftest ^:slow membership-and-augmented-test
  (every-vm= (prints "True True False True False True True"
                     "4 22 [1, 2, 1, 2] [0, 0, 0] abab (1, 1)")
             (lines "print(3 in [1, 2, 3], 'ell' in 'hello', 'k' not in {'k': 1}, (1, 2) in [(1, 2)], 2 in (1, 3), 4 in range(5), 2 in {1, 2})"
                    "x = 10"
                    "x //= 3"
                    "x **= 2"
                    "x %= 5"
                    "y = 6"
                    "y &= 3"
                    "y |= 8"
                    "y ^= 1"
                    "y <<= 2"
                    "y >>= 1"
                    "L = [1]"
                    "M = L"
                    "L += [2]"
                    "L *= 2"
                    "print(x, y, M, [0] * 3, 'ab' * 2, (1,) * 2)")))


;; =============================================================================
;; 4. Comprehensions
;; =============================================================================

(deftest ^:slow comprehensions-test
  (every-vm= (prints "[4, 16] {3: 6, 4: 8} {0, 1}"
                     "[(1, 0), (2, 0), (2, 1)] 10 ['a', 'b'] True False"
                     "(1, 2) {1}")
             (lines "xs = [1, 2, 3, 4]"
                    "print([x * x for x in xs if x % 2 == 0], {x: x * 2 for x in xs if x > 2}, {x % 2 for x in [2, 3, 4]})"
                    "print([(i, j) for i in range(3) for j in range(i)], sum(x for x in xs), list(c for c in 'ab'), any(x > 3 for x in xs), all(x > 3 for x in xs))"
                    "print(tuple(x for x in [1, 2]), set(x for x in [1, 1]))")))


(deftest ^:slow comprehension-scope-test
  (testing "the loop variable is local to the comprehension; the first
            iterable is evaluated in the enclosing (here, class) scope;
            closures over the loop variable share its one cell"
    (every-vm= (prints "outer [0, 1]" "[0, 2, 4]" "[2, 2, 2]")
               (lines "x = 'outer'"
                      "ys = [x for x in range(2)]"
                      "print(x, ys)"
                      "class C:"
                      "    n = 3"
                      "    vals = [i * 2 for i in range(n)]"
                      "print(C.vals)"
                      "fs = [lambda: i for i in range(3)]"
                      "print([f() for f in fs])"))))


;; =============================================================================
;; 5. Keyword arguments
;; =============================================================================

(deftest ^:slow keyword-arguments-test
  (every-vm= (prints "[1, 2, (), 3, 4, {}]"
                     "[1, 5, (6, 7), 8, 0, {'e': 9}]"
                     "[1, 2, (3,), 4, 4, {'z': 5}]"
                     "3 7 3"
                     "TypeError g() missing required keyword-only argument 'b'"
                     "TypeError g() takes too many positional arguments"
                     "TypeError g() got an unexpected keyword argument 'c'"
                     "TypeError g() got multiple values for argument 'a'"
                     "TypeError f() missing required positional argument 'a'")
             (lines "def f(a, b=2, *args, c, d=4, **kw):"
                    "    return [a, b, args, c, d, kw]"
                    "print(f(1, c=3))"
                    "print(f(1, 5, 6, 7, c=8, e=9, d=0))"
                    "print(f(*[1, 2, 3], **{'c': 4, 'z': 5}))"
                    "def g(a, *, b):"
                    "    return a + b"
                    "class P:"
                    "    def __init__(self, x, y=0):"
                    "        self.s = x + y"
                    "print(g(1, b=2), g(b=3, a=4), P(y=2, x=1).s)"
                    "def check(thunk):"
                    "    try:"
                    "        thunk()"
                    "    except TypeError as e:"
                    "        print('TypeError', e.args[0])"
                    "check(lambda: g(1))"
                    "check(lambda: g(1, 2))"
                    "check(lambda: g(1, b=2, c=3))"
                    "check(lambda: g(1, a=2, b=3))"
                    "check(lambda: f())")))


;; =============================================================================
;; 6. The r2 gate's P3 notes
;; =============================================================================

(deftest ^:slow gate-p3-runtime-test
  (testing "global globals then globals(); builtin classes stay builtin when
            the module rebinds their names (as in CPython); the
            UnboundLocalError message; repr of quotes and control characters"
    (every-vm= (prints "5 5"
                       "caught ok"
                       "cannot access local variable 'u' where it is not associated with a value"
                       "['a\\nb', \"it's\", '\\x01']")
               (lines "def f():"
                      "    global globals"
                      "    return globals()['k']"
                      "k = 5"
                      "print(f(), globals()['k'])"
                      "TypeError = None"
                      "object = 1"
                      "class C:"
                      "    pass"
                      "try:"
                      "    1 + 'a'"
                      "except Exception:"
                      "    print('caught', 'ok' if C() is not None else 'no')"
                      "def g():"
                      "    v = u"
                      "    u = 1"
                      "try:"
                      "    g()"
                      "except NameError as e:"
                      "    print(e.args[0])"
                      "print(['a\\nb', \"it's\", '\\x01'])"))))


;; =============================================================================
;; Round 2: the C1 gate's findings. Expected output is CPython 3.9.6's for
;; the same source, except where an int leaves +-2^53 (a documented
;; OverflowError here, a big int there).
;; =============================================================================

(deftest ^:slow tuple-of-classes-test
  (testing "except with a flat tuple matches any element; a nested tuple or a
            non-class in an except clause is a TypeError when matched (every
            element is checked first); isinstance recurses into tuples"
    (every-vm= (prints "['missed A', 'missed B', 'missed C', 'missed KeyError']"
                       "['caught A', 'caught B', 'caught C', 'caught KeyError', 'missed ValueError']"
                       "True False False"
                       "bad except"
                       "bad isinstance")
               (lines "class A(Exception): pass"
                      "class B(Exception): pass"
                      "class C(B): pass"
                      "out = []"
                      "for cls in [A, B, C, KeyError]:"
                      "    try:"
                      "        try:"
                      "            raise cls('x')"
                      "        except (A, (KeyError, B)):"
                      "            out.append('caught ' + cls.__name__)"
                      "    except Exception:"
                      "        out.append('missed ' + cls.__name__)"
                      "print(out)"
                      "out = []"
                      "for cls in [A, B, C, KeyError, ValueError]:"
                      "    try:"
                      "        try:"
                      "            raise cls('x')"
                      "        except (A, KeyError, B):"
                      "            out.append('caught ' + cls.__name__)"
                      "    except Exception:"
                      "        out.append('missed ' + cls.__name__)"
                      "print(out)"
                      "print(isinstance(C(), (A, B)), isinstance(A(), (B, (C,))), isinstance(1, (A,)))"
                      "try:"
                      "    try:"
                      "        raise A()"
                      "    except None:"
                      "        pass"
                      "except TypeError:"
                      "    print('bad except')"
                      "try:"
                      "    isinstance(1, 2)"
                      "except TypeError:"
                      "    print('bad isinstance')"))))


(deftest ^:slow integer-bound-test
  (testing "an int result outside [-2^53, 2^53] is a guest OverflowError on
            every VM and host (CPython would give a big int); the boundary
            itself and everything inside agree with CPython"
    (every-vm= (prints (str "9007199254740992 overflow overflow 4503599627370496 "
                            "9007199254740992 overflow overflow")
                       "overflow overflow 0 -1 overflow 9007199254740992")
               (lines "def ov(f):"
                      "    try:"
                      "        return f()"
                      "    except OverflowError:"
                      "        return 'overflow'"
                      "print(2 ** 53, ov(lambda: 2 ** 53 + 1), ov(lambda: 3 ** 40), 1 << 52, 1 << 53, ov(lambda: 1 << 54), ov(lambda: -(2 ** 53) - 1))"
                      "print(ov(lambda: 9007199254740991 * 3), ov(lambda: -9007199254740992 - 1), 0 << 100, -1 >> 100, ov(lambda: ~(2 ** 53)), 2 ** 52 * 2)"))))


(deftest ^:slow gate-p3-round2-test
  (testing "break and continue in finally swallow the exception in flight
            (legal since 3.8); raising a non-exception is a TypeError;
            `except E as e` unbinds e; range len and membership in closed
            form; dict/set size change during iteration is a RuntimeError;
            float % and // as CPython's fmod-corrected divmod; builtins take
            no keywords; a class without __init__ takes no arguments; with
            looks its dunders up on the type"
    (every-vm= (prints "after break"
                       "3"
                       "TypeError exceptions must derive from BaseException"
                       "TypeError exceptions must derive from BaseException"
                       "TypeError exceptions must derive from BaseException"
                       "TypeError exceptions must derive from BaseException"
                       "err unbound"
                       "e2 unbound"
                       "1501199875790166 True True False 0 4"
                       "dictionary changed size during iteration"
                       "Set changed size during iteration"
                       (str "0.3999999999999999 5.0 0.7000000000000002 -4.0 0.5 "
                            "0.09999999999999998 1.0000005382899513")
                       "TypeError"
                       "TypeError"
                       "not a context manager")
               (lines "for i in range(2):"
                      "    try:"
                      "        raise ValueError('x')"
                      "    finally:"
                      "        break"
                      "print('after break')"
                      "n = 0"
                      "for i in range(3):"
                      "    try:"
                      "        raise KeyError('k')"
                      "    finally:"
                      "        n += 1"
                      "        continue"
                      "print(n)"
                      "class NotExc:"
                      "    pass"
                      "for v in [1, 'x', NotExc, NotExc()]:"
                      "    try:"
                      "        raise v"
                      "    except TypeError as e:"
                      "        print('TypeError', e.args[0])"
                      "try:"
                      "    raise KeyError('k')"
                      "except KeyError as err:"
                      "    pass"
                      "try:"
                      "    print(err)"
                      "except NameError:"
                      "    print('err unbound')"
                      "def f():"
                      "    try:"
                      "        raise KeyError('k')"
                      "    except KeyError as e2:"
                      "        pass"
                      "    return e2"
                      "try:"
                      "    f()"
                      "except UnboundLocalError:"
                      "    print('e2 unbound')"
                      "print(len(range(0, 2 ** 52, 3)), 2 ** 50 in range(0, 2 ** 52, 2), 7 in range(10, 0, -3), 5 in range(10, 0, -3), len(range(5, 1)), len(range(10, 0, -3)))"
                      "d = {'a': 1}"
                      "try:"
                      "    for k in d:"
                      "        d[k + 'x'] = 1"
                      "except RuntimeError as e:"
                      "    print(e.args[0])"
                      "s = {1}"
                      "try:"
                      "    for x in s:"
                      "        s.add(x + 1)"
                      "except RuntimeError as e:"
                      "    print(e.args[0])"
                      "print(5.9 % 1.1, 5.9 // 1.1, -5.9 % 1.1, 7.5 // -2, -7.5 % 2, 0.3 % 0.1, 1e10 % 3.3)"
                      "for thunk in [lambda: len(obj=[1]), lambda: NotExc(1, 2)]:"
                      "    try:"
                      "        thunk()"
                      "    except TypeError as e:"
                      "        print('TypeError')"
                      "class M:"
                      "    pass"
                      "m = M()"
                      "m.__enter__ = lambda: 1"
                      "m.__exit__ = lambda t, v, tb: None"
                      "try:"
                      "    with m:"
                      "        pass"
                      "except (TypeError, AttributeError):"
                      "    print('not a context manager')"))))


;; =============================================================================
;; Round 3: the C1 gate r2's findings. Expected output is CPython 3.9.6's for
;; the same source (every value here is within +-2^53).
;; =============================================================================

(deftest ^:slow gate-round3-test
  (testing "range elements exact near +-2^53 (no oversized i*step); range
            len and membership from split quotient/remainder (only the
            result is bounded); any/all over a generator short-circuit, sum
            and set over one stop at the failing element; float % without a
            quotient; range argument validation; builtin exception
            constructors reject keywords while an explicit __init__ binds
            them; * after explicit keywords binds as Python does"
    (every-vm=
      (prints (str "[-9007199254740992, -3002399751580331, 3002399751580330, "
                   "9007199254740991] 4 True False")
              "2 [-9007199254740992, 0]"
              (str "[9007199254740992, 3002399751580331, -3002399751580330, "
                   "-9007199254740991] 4")
              "True False"
              "4 [0]"
              "True [1]"
              "False False [1]"
              "sum stopped [1, 'a']"
              "set stopped [1, [2]]"
              "1.0 2.0 1.0 5.5"
              "range TypeError"
              "range TypeError"
              "range TypeError"
              "ctor TypeError"
              "code 7"
              "TypeError h() got multiple values for argument 'a'"
              "21")
      (lines "r = range(-9007199254740992, 9007199254740992, 6004799503160661)"
             "print(list(r), len(r), 9007199254740991 in r, 9007199254740990 in r)"
             "print(len(range(-9007199254740992, 9007199254740992, 9007199254740992)), list(range(-9007199254740992, 9007199254740992, 9007199254740992)))"
             "print(list(range(9007199254740992, -9007199254740992, -6004799503160661)), len(range(9007199254740992, -9007199254740992, -6004799503160661)))"
             "print(-9007199254740992 in range(-9007199254740992, 9007199254740992, 9007199254740992), 1 in range(-9007199254740992, 9007199254740992, 9007199254740992))"
             "print(len(range(True, 5)), list(range(False, True)))"
             "log = []"
             "def f(x):"
             "    log.append(x)"
             "    return 1 // x"
             "print(any(f(x) for x in [1, 0]), log)"
             "log = []"
             "print(all(0 // x for x in [1, 0]), all(f(x) > 5 for x in [1, 0]), log)"
             "log = []"
             "def g(x):"
             "    log.append(x)"
             "    return x"
             "try:"
             "    sum(g(x) for x in [1, 'a', 3])"
             "except TypeError:"
             "    print('sum stopped', log)"
             "log = []"
             "try:"
             "    set(g(x) for x in [1, [2], 3])"
             "except TypeError:"
             "    print('set stopped', log)"
             "print(1e20 % 3.0, -1e20 % 3.0, 1e300 % 7.0, 5.5 % 1e300)"
             "for bad in ['a', 1.5, None]:"
             "    try:"
             "        range(bad)"
             "    except TypeError:"
             "        print('range TypeError')"
             "try:"
             "    raise ValueError(nope=1)"
             "except TypeError:"
             "    print('ctor TypeError')"
             "class E(Exception):"
             "    def __init__(self, code=0):"
             "        self.code = code"
             "try:"
             "    raise E(code=7)"
             "except E as e:"
             "    print('code', e.code)"
             "def h(a):"
             "    print(a)"
             "try:"
             "    h(a=1, *[2])"
             "except TypeError as e:"
             "    print('TypeError', e.args[0])"
             "def k(a, b):"
             "    return a * 10 + b"
             "print(k(b=1, *[2]))"))))


(deftest ^:slow generator-consumer-rebound-test
  (testing "a consumer name rebound through globals() receives the
            generator expression itself (C2 S4: nothing is inlined)"
    (every-vm= (prints "[1, 'mine']")
               (lines "globals()['any'] = lambda it: list(it) + ['mine']"
                      "print(any(x for x in [1]))"))))


;; =============================================================================
;; Round 4: gate r3. Expected output is CPython 3.9.6's for the same source.
;; =============================================================================

(deftest ^:slow float-zero-and-infinity-test
  (testing "exact float multiples with both divisor signs, x % +-inf and
            x // +-inf, and signed zeros, identical on every VM (zero tests
            are host-independent; a zero result's sign comes from the
            divisor, never from 0.0 * inf)"
    (every-vm= (prints "-0.0 0.0 -2.0 -2.0 0.0 -0.0 2.0 2.0"
                       "-0.0 -0.0 0.0 -0.0 0.0 0.0 -0.0 -0.0"
                       "5.0 inf -inf -5.0 0.0 -0.0"
                       "0.0 -1.0 -1.0 0.0 0.0 -0.0"
                       "-0.0 -0.0 -0.0 0.0 -0.0 -inf inf")
               (lines "print(4.0 % -2.0, -4.0 % 2.0, 4.0 // -2.0, -4.0 // 2.0, 4.0 % 2.0, -4.0 % -2.0, 6.0 // 3.0, -6.0 // -3.0)"
                      "print(4 % -2.0, 4.0 % -2, 0.0 % 5.0, 0.0 % -5.0, -0.0 % 5.0, 0.0 // 5.0, 0.0 // -5.0, -0.0 // 5.0)"
                      "inf = 1e400"
                      "print(5.0 % inf, -5.0 % inf, 5.0 % -inf, -5.0 % -inf, 0.0 % inf, 0.0 % -inf)"
                      "print(5.0 // inf, -5.0 // inf, 5.0 // -inf, -5.0 // -inf, 0.0 // inf, 0.0 // -inf)"
                      "print(-0.0, +(-0.0), -(0.0), -(-0.0), 0.0 * -1, -1e400, 1e400)"))))
