(ns yang.typescript.antlr.object-e2e-test
  "Stage 0 S6: the object, array, member-write, `delete` and `in` lowering
   arms, end to end through `e2e-test/every-vm=` (naive, safepointed and
   linked)."
  (:require [clojure.test :refer [deftest testing]]
            [dao.test-slow :as slow]
            [yang.typescript.antlr.e2e-test :as e2e]))


(defn- program-test
  [nm cases]
  (slow/guard nm
              (fn []
                (doseq [[expected source] cases]
                  (testing source (e2e/every-vm= expected source))))))


(deftest ^:slow literals-and-member-writes-test
  (program-test
    "object-literals-and-member-writes"
    [[(e2e/prints "1 2 3") "const o = {a: 1, 'b': 2, 3: 3}; console.log(o.a, o.b, o[3]);"]
     [(e2e/prints "x y") "const a = 'x', b = 'y'; const o = {a, b}; console.log(o.a, o.b);"]
     [(e2e/prints "5") "const k = 'z'; const o = {[k]: 5}; console.log(o.z);"]
     [(e2e/prints "3 6")
      "const o = {a: 1}; o.a = 3; o['b'] = 6; console.log(o.a, o.b);"]
     [(e2e/prints "5 6 6 8")
      "const o = {a: 1}; o.a += 3; const r = o.a++; console.log(o.a, ++o.a, o.a, o.a += 2);"]
     [(e2e/prints "hi")
      "const o = {}; o.f = function () { return 'hi'; }; console.log(o.f());"]
     [(e2e/prints "f")
      "const o = {f: function () {}}; console.log(o.f.name);"]
     [(e2e/prints "true false true")
      "const o = {a: 1}; console.log('a' in o, 'b' in o, delete o.a || o.a);"]
     [(e2e/prints "false true")
      "const o = {a: 1}; console.log(delete o.a && 'a' in o, 'toString' in o);"]
     [(e2e/prints "TypeError")
      "try { 'a' in 1; } catch (e) { console.log(e.name); }"]
     [(e2e/prints "true")
      "console.log(Object.getPrototypeOf({__proto__: null}) === null);"]
     [(e2e/prints "1")
      "const p = {x: 1}; const o = {__proto__: p}; console.log(o.x);"]
     [(e2e/prints "1 undefined")
      "const o = {['__proto__']: 1}; console.log(o.__proto__, Object.getPrototypeOf(o).x);"]
     [(e2e/prints "3 undefined 3")
      "const a = [1, , 3]; console.log(a.length, a[1], a[2]);"]
     [(e2e/prints "5") "const a = [,,1,2,,]; console.log(a.length);"]
     [(e2e/prints "2") "const a = [1, 2,]; console.log(a.length);"]
     [(e2e/prints "9 4")
      "const a = [1]; a[0] = 9; a[3] = 1; console.log(a[0], a.length);"]
     [(e2e/prints "TypeError")
      "const o = Object.freeze({a: 1}); try { o.a = 2; } catch (e) { console.log(e.name); }"]]))


(deftest ^:slow array-methods-test
  (program-test
    "array-methods"
    [[(e2e/prints "3 1,2,3 3 1")
      "const a = [1, 2]; console.log(a.push(3), a.join(), a.length, a.shift() ? 1 : 0);"]
     [(e2e/prints "1-2-3" "3" "1,2")
      "const a = [1, 2, 3]; console.log(a.join('-')); console.log(a.pop()); console.log(String(a));"]
     [(e2e/prints "2,3 1,2,3,4,5 -1 true false 2")
      "const a = [1, 2, 3]; console.log(String(a.slice(1)), String(a.concat([4], 5)), a.indexOf(9), a.includes(3), [NaN].indexOf(NaN) === 0 || a.includes(9), a.at(-2));"]
     [(e2e/prints "2,4,6 2,3 6 3")
      "const a = [1, 2, 3]; console.log(String(a.map(x => x * 2)), String(a.filter(x => x > 1)), a.reduce((s, x) => s + x), a.find(x => x > 2));"]
     [(e2e/prints "6 true false 1")
      "let s = 0; [1, 2, 3].forEach(x => { s += x; }); console.log(s, [1, 2].some(x => x > 1), [1, 2].every(x => x > 1), [5, 6].findIndex(x => x === 6));"]
     [(e2e/prints "3,2,1" "true false")
      "console.log(String([1, 2, 3].reverse())); console.log(Array.isArray([]), Array.isArray({}));"]
     [(e2e/prints "3 0 2")
      "console.log(Array(3).length, new Array().length, Array(1, 2).length);"]
     [(e2e/prints "RangeError") "try { Array(-1); } catch (e) { console.log(e.name); }"]
     [(e2e/prints "[object Array]")
      "console.log(Object.prototype.toString.call([]));"]
     [(e2e/prints "a,b")
      "console.log(Object.keys({a: 1, b: 2}).join());"]
     [(e2e/prints "TypeError")
      "const a = Object.freeze([1]); try { a.push(2); } catch (e) { console.log(e.name); }"]]))


(deftest ^:slow for-in-test
  (program-test
    "for-in"
    [[(e2e/prints "1,2,b,a,")
      "const o = {b: 1, 2: 1, a: 1, 1: 1}; let t = ''; for (const k in o) t += k + ','; console.log(t);"]
     [(e2e/prints "1,2,b,a,c,")
      "const p = {a: 0, c: 0}; const o = Object.create(p); o.b = 1; o[2] = 1; o.a = 1; o[1] = 1; let t = ''; for (const k in o) t += k + ','; console.log(t);"]
     [(e2e/prints "a")
      "const p = {x: 1}; const o = Object.create(p); Object.defineProperty(o, 'x', {value: 1, enumerable: false}); o.a = 1; let t = ''; for (var k in o) t += k; console.log(t);"]
     [(e2e/prints "a")
      "const o = {a: 1, b: 2}; let t = ''; for (let k in o) { t += k; delete o.b; } console.log(t);"]
     [(e2e/prints "a")
      "const o = {a: 1}; let t = ''; for (const k in o) { o.z = 1; t += k; } console.log(t);"]
     [(e2e/prints "0,1,")
      "let t = ''; for (const k in 'ab') t += k + ','; console.log(t);"]
     [(e2e/prints "none")
      "let t = 'none'; for (const k in null) t = k; for (const k in undefined) t = k; for (const k in 5) t = k; console.log(t);"]
     [(e2e/prints "a")
      "const o = {a: 1, b: 2}; let t = ''; for (const k in o) { t += k; break; } console.log(t);"]
     [(e2e/prints "b")
      "const o = {a: 1, b: 2}; let t = ''; for (const k in o) { if (k === 'a') continue; t += k; } console.log(t);"]
     [(e2e/prints "b")
      "const o = {a: 1, b: 2}; let k; for (k in o); console.log(k);"]
     [(e2e/prints "b")
      "const o = {a: 1, b: 2}; const t = {}; for (t.last in o); console.log(t.last);"]
     [(e2e/prints "a b")
      "const o = {a: 1, b: 2}; const fs = {}; for (const k in o) fs[k] = () => k; console.log(fs.a(), fs.b());"]]))
