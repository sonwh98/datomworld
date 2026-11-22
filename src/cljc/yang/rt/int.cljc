(ns yang.rt.int
  "Guest adapters for exact-integer policy, wrapping, and radix scanning."
  (:require [yang.core.prelude :as core]
            [yang.core.prelude-admission :as prelude-admission]
            [yang.core.uast :as u]
            [yang.rt.effect :as effect]))


(def module-name 'yrt.int)


(def definitions
  "The ordered source for bundled and linked emission."
  '[[yrt.int/check-limit-with
     (fn [res on-bit on-digit on-float]
       (if (= res :yin.vm.integer/bit-limit)
         (on-bit res)
         (if (= res :yin.vm.integer/digit-limit)
           (on-digit res)
           (if (= res :yin.vm.integer/float-overflow)
             (on-float res)
             res))))]
    [yrt.int/_perform
     (fn [ctx-cell tag res]
       (yrt.effect/perform ctx-cell tag res
         (fn [tag payload]
           (let [handler (get (cell/get ctx-cell) :on-unhandled-policy)]
             (if (= handler nil)
               (:yrt.int/unhandled-policy tag payload)
               (handler tag payload))))))]
    [yrt.int/check-limit
     (fn [ctx-cell res]
       (if (= res :yin.vm.integer/bit-limit)
         (yrt.int/_perform ctx-cell :int/bit-limit res)
         (if (= res :yin.vm.integer/digit-limit)
           (yrt.int/_perform ctx-cell :int/digit-limit res)
           (if (= res :yin.vm.integer/float-overflow)
             (yrt.int/_perform ctx-cell :math/overflow res)
             res))))]
    [yrt.int/wrap-unsigned
     (fn [x width-pow2 mask]
       (if (= mask nil)
         (let [qr (integer/floor-div-mod x width-pow2)]
           (if (= qr :yin.vm.integer/bit-limit) qr (data/nth qr 1)))
         (integer/bit-and x mask)))]
    [yrt.int/wrap-signed
     (fn [x half-range range]
       (let [r (yrt.int/wrap-unsigned x range nil)]
         (if (= r :yin.vm.integer/bit-limit)
           r
           (if (<= 0 (integer/compare r half-range)) (integer/sub r range) r))))]
    [yrt.int/in-range?
     (fn [x min-v max-v]
       (if (<= 0 (integer/compare x min-v))
         (<= (integer/compare x max-v) 0)
         false))]
    [yrt.int/digit-value
     (fn [cp]
       (if (if (<= 48 cp) (<= cp 57) false)
         (- cp 48)
         (if (if (<= 65 cp) (<= cp 90) false)
           (- cp 55)
           (if (if (<= 97 cp) (<= cp 122) false)
             (- cp 87)
             -1))))]
    [yrt.int/_digit?
     (fn [d radix] (if (<= 0 d) (< d radix) false))]
    [yrt.int/all-digits?
     (fn [cs i n radix]
       (if (< i n)
         (if (yrt.int/_digit? (yrt.int/digit-value (data/nth cs i)) radix)
           (yrt.int/all-digits? cs (+ i 1) n radix)
           false)
         true))]
    [yrt.int/scan-digits
     ;; Accumulate the valid prefix and propagate a limit before using its
     ;; keyword as an integer operand.
     (fn [cs i n radix acc]
       (if (= acc :yin.vm.integer/bit-limit)
         acc
         (if (< i n)
           (let [d (yrt.int/digit-value (data/nth cs i))]
             (if (yrt.int/_digit? d radix)
               (let [scaled (integer/mul acc radix)]
                 (if (= scaled :yin.vm.integer/bit-limit)
                   scaled
                   (yrt.int/scan-digits cs (+ i 1) n radix (integer/add scaled d))))
               acc))
           acc)))]
    [yrt.int/skip-while
     (fn [cs i n pred?]
       (if (< i n)
         (if (pred? (data/nth cs i))
           (yrt.int/skip-while cs (+ i 1) n pred?)
           i)
         i))]
    [yrt.int/skip-zeros
     (fn [cs i n] (yrt.int/skip-while cs i n (fn [cp] (= cp 48))))]])


(def module-exports
  '#{check-limit check-limit-with wrap-unsigned wrap-signed in-range?
     digit-value all-digits? scan-digits skip-while skip-zeros})


(def uast
  (u/mark-tails (core/definitions->uast definitions)))


(def module-uast
  (u/annotate-call-sites
    (core/module-tree (core/module-definitions "yrt.int" nil [] definitions)
                      (mapv u/sexp->uast '[(require 'yrt.effect)]))))


(def ^:private module-free-names (core/free-name-vec module-uast))


(defn module-spec
  [registry effect-address]
  (prelude-admission/module-spec
    {:module-name module-name
     :ast module-uast
     :exports module-exports
     :requires {effect/module-name effect-address}
     :skip? #(= "yrt.effect" (namespace %))
     :free-names module-free-names
     :registry registry
     :message "the yrt.int module reads an undeclared name"
     :refusal-ns "yrt.int"}))
