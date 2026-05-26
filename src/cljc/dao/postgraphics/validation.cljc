(ns dao.postgraphics.validation
  "Shared validation predicates and rejection helpers for the
   dao.postgraphics renderers (Flutter GPU and WebGPU). Pure data
   predicates over numeric vectors plus the canonical reject!/check-color!
   contract. Platform-specific lowering, matrix math, and the frame walker
   stay in their respective namespaces.")


(def EPSILON 1.0e-6)


(defn finite-number?
  "True when x is a number that is neither NaN nor infinite. Strict by
   design: NaN and ±∞ are rejected at validate-frame! time rather than
   propagated into lowering math."
  [x]
  (and (number? x)
       #?(:cljs (js/isFinite x)
          :cljd (.-isFinite ^num x)
          :clj (Double/isFinite (double x)))))


(defn vec2?
  [v]
  (and (vector? v) (= 2 (count v)) (every? finite-number? v)))


(defn vec3?
  [v]
  (and (vector? v) (= 3 (count v)) (every? finite-number? v)))


(defn vec4?
  [v]
  (and (vector? v) (= 4 (count v)) (every? finite-number? v)))


(defn in-unit-interval?
  [x]
  (and (finite-number? x) (<= 0.0 (double x) 1.0)))


(defn valid-color?
  [c]
  (and (vec4? c) (every? in-unit-interval? c)))


(defn valid-light-color?
  "v4 §Color Arity: light/material colors are 3-channel [r g b], exempt
   from v1's arity-4 paint-color rule."
  [c]
  (and (vec3? c) (every? in-unit-interval? c)))


(defn positive-rect?
  [r]
  (and (vec4? r) (> (double (nth r 2)) 0.0) (> (double (nth r 3)) 0.0)))


(defn reject!
  "Canonical postgraphics rejection: ex-info with the rejection reason in
   the :dao.postgraphics/reason key so terminal/rejection-reason can route
   it. Default reason is :validation-failure."
  ([message] (reject! :validation-failure message))
  ([reason message]
   (throw (ex-info (str "[" (name reason) "] " message)
                   {:dao.postgraphics/reason reason}))))


(defn check-color!
  "Validates that op contains a valid RGBA color under key k, rejecting
   with the op kind in the message when absent or malformed."
  [op k]
  (when-let [c (get op k)]
    (when-not (valid-color? c)
      (reject! (str k
                    " must be [r g b a] with each component in [0, 1] (op "
                    (:op/kind op)
                    ")")))))
