(ns yin.vm.effect
  "The in-machine effect value (D4).

   An effect is a host type minted only by `make`, which is never a guest
   primitive or module export. `effect?` is a type test, so an ordinary
   map is always data, including a map carrying an `:effect` key: a
   `:pure` primitive that returns guest data can no longer raise an
   engine effect.

   The type wraps a plain descriptor map, `{:effect kind ...params}`, and
   answers keyword lookup over it, so handlers and park-entry builders read
   `(:effect e)`, `(:val e)` and the rest unchanged. It is not a map: guest
   `assoc` cannot extend it, and `map?` is false.

   The wrapper is an in-machine value only. What crosses a stream or a
   wire is the plain descriptor (`descriptor`) or the fields a handler
   reads out of it, never the wrapper.")


(deftype Effect
  [descriptor]
  #?@(:cljd [cljd.core/ILookup
             (-lookup [_ k] (get descriptor k))
             (-lookup [_ k not-found] (get descriptor k not-found))
             (-contains-key? [_ k] (contains? descriptor k))]
      :clj [clojure.lang.ILookup
            (valAt [_ k] (get descriptor k))
            (valAt [_ k not-found] (get descriptor k not-found))
            Object
            (toString [_] (str "#yin/effect " (pr-str descriptor)))]
      :cljs [ILookup
             (-lookup [_ k] (get descriptor k))
             (-lookup [_ k not-found] (get descriptor k not-found))]))


(defn effect?
  "True when `x` is an effect value minted by `make`. A type test: no map
   is an effect, whatever keys it carries."
  [x]
  (instance? Effect x))


(defn make
  "Mint an effect of `kind` over the plain parameter map `params`. The one
   trusted constructor; never register it as a guest primitive or export."
  ([kind] (make kind nil))
  ([kind params]
   (Effect. (assoc params :effect kind))))


(defn descriptor
  "The plain descriptor map `{:effect kind ...}` of effect `e`: the form an
   effect takes at a stream or wire boundary."
  [e]
  (.-descriptor ^Effect e))


(defn from-descriptor
  "Mint the effect a plain descriptor map `d` names. The trusted boundary
   conversion for host code that holds a portable descriptor; never
   applied to a value a guest computed."
  [d]
  (make (:effect d) (dissoc d :effect)))
