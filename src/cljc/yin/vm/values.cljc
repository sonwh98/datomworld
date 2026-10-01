(ns yin.vm.values
  "The in-machine closure and continuation values (D7).

   A closure and a captured continuation are host types minted only by a
   kernel, never by a guest primitive or a module export. `closure?` and
   `continuation?` are type tests, so an ordinary map is always data,
   including one carrying `:type :closure` or `:type :reified-continuation`:
   a program cannot forge either by building its map.

   Each type wraps the kernel's own payload map unchanged, plus an owner
   tag: the task the value was minted in (`owner-tag`). Lift, lower,
   completion and heap reclamation read the payload, so they keep operating
   on the maps they already understand.

   Neither type answers keyword lookup, unlike `yin.vm.effect`'s `Effect`:
   a guest `get` on a closure answers nil, never its captured environment.
   Neither is a function: `fn?` is false, so no kernel's primitive arm is
   taken. Equality is structural over kind, owner and payload, and hash
   agrees with it, so two runs of one program give equal VM states and a
   closure can be a set member or a map key.

   Both print opaquely, on every host and through `str`, `pr-str` and every
   guest print: `{:type :closure}` or `{:type :continuation}`, never the
   payload, a captured value, or the owner tag (`marker-text`).

   Cell, stream and cursor references stay sealed plain data: they name
   table entries, are already unforgeable, and must stay usable as keys."
  (:require [dao.jing :as jing]))


(def marker-text
  "The opaque printed form of each kind, shaped like the REPL's
   `{:type :host-fn}` marker."
  {:closure "{:type :closure}", :continuation "{:type :continuation}"})


(deftype Closure
  [owner payload]
  #?@(:cljd [cljd.core/IEquiv
             (-equiv [_ other]
                     (and (instance? Closure other)
                          (= owner (.-owner ^Closure other))
                          (= payload (.-payload ^Closure other))))
             cljd.core/IHash
             (-hash [_] (hash [:closure owner payload]))
             cljd.core/IPrint
             (-print [_ sink] (.write sink (:closure marker-text)))
             Object
             (toString [_] (:closure marker-text))]
      :clj [Object
            (equals [_ other]
                    (and (instance? Closure other)
                         (= owner (.-owner ^Closure other))
                         (= payload (.-payload ^Closure other))))
            (hashCode [_] (hash [:closure owner payload]))
            (toString [_] (:closure marker-text))
            clojure.lang.IHashEq
            (hasheq [_] (hash [:closure owner payload]))]
      :cljs [IEquiv
             (-equiv [_ other]
                     (and (instance? Closure other)
                          (= owner (.-owner ^Closure other))
                          (= payload (.-payload ^Closure other))))
             IHash
             (-hash [_] (hash [:closure owner payload]))
             IPrintWithWriter
             (-pr-writer [_ writer _opts]
                         (-write writer (:closure marker-text)))
             Object
             (toString [_] (:closure marker-text))]))


(deftype Continuation
  [owner payload]
  #?@(:cljd [cljd.core/IEquiv
             (-equiv [_ other]
                     (and (instance? Continuation other)
                          (= owner (.-owner ^Continuation other))
                          (= payload (.-payload ^Continuation other))))
             cljd.core/IHash
             (-hash [_] (hash [:continuation owner payload]))
             cljd.core/IPrint
             (-print [_ sink] (.write sink (:continuation marker-text)))
             Object
             (toString [_] (:continuation marker-text))]
      :clj [Object
            (equals [_ other]
                    (and (instance? Continuation other)
                         (= owner (.-owner ^Continuation other))
                         (= payload (.-payload ^Continuation other))))
            (hashCode [_] (hash [:continuation owner payload]))
            (toString [_] (:continuation marker-text))
            clojure.lang.IHashEq
            (hasheq [_] (hash [:continuation owner payload]))]
      :cljs [IEquiv
             (-equiv [_ other]
                     (and (instance? Continuation other)
                          (= owner (.-owner ^Continuation other))
                          (= payload (.-payload ^Continuation other))))
             IHash
             (-hash [_] (hash [:continuation owner payload]))
             IPrintWithWriter
             (-pr-writer [_ writer _opts]
                         (-write writer (:continuation marker-text)))
             Object
             (toString [_] (:continuation marker-text))]))


#?(:cljd nil
   :clj (defmethod print-method Closure
          [_ ^java.io.Writer w]
          (.write w ^String (:closure marker-text))))


#?(:cljd nil
   :clj (defmethod print-method Continuation
          [_ ^java.io.Writer w]
          (.write w ^String (:continuation marker-text))))


(defn owner-tag
  "The owner tag of the task whose capability secret is `secret`: derived
   once, held in VM state, and carried by every closure and continuation
   the task mints. It reveals nothing of the secret. A task without a
   secret has owner nil."
  [secret]
  (when (some? secret)
    (jing/content-hash [:yin.k/owner secret] {:algorithm :sha256})))


(defn closure
  "Mint a closure owned by `owner` over the kernel payload map `payload`.
   A trusted constructor: only kernels call it, never a guest primitive."
  [owner payload]
  (Closure. owner payload))


(defn continuation
  "Mint a captured continuation owned by `owner` over the kernel payload
   map `payload`. A trusted constructor, as `closure`."
  [owner payload]
  (Continuation. owner payload))


(defn closure?
  "True when `x` is a closure a kernel minted. A type test: no map is a
   closure, whatever keys it carries."
  [x]
  (instance? Closure x))


(defn continuation?
  "True when `x` is a continuation a kernel captured. A type test."
  [x]
  (instance? Continuation x))


(defn host-typed?
  "True when `x` is a closure or a continuation."
  [x]
  (or (closure? x) (continuation? x)))


(defn payload
  "The kernel payload map of closure or continuation `x`."
  [x]
  (if (closure? x)
    (.-payload ^Closure x)
    (.-payload ^Continuation x)))


(defn owner
  "The owner tag of closure or continuation `x`."
  [x]
  (if (closure? x)
    (.-owner ^Closure x)
    (.-owner ^Continuation x)))


(defn owned-by?
  "True when closure or continuation `x` was minted by the task whose
   owner tag is `tag`."
  [x tag]
  (let [o (owner x)]
    (or (identical? o tag) (= o tag))))


(defn kind-of
  "The coarse kind of `x` a refusal may carry in place of the value
   itself: never the value, so a refusal leaks nothing it was handed."
  [x]
  (cond (nil? x) :nil
        (boolean? x) :boolean
        (number? x) :number
        (string? x) :string
        (keyword? x) :keyword
        (symbol? x) :symbol
        (closure? x) :closure
        (continuation? x) :continuation
        (fn? x) :host-fn
        (map? x) :map
        (vector? x) :vector
        (set? x) :set
        (seq? x) :seq
        :else :host-object))
