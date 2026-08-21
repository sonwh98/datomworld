;; Mobile web touch policy: normalization of authored policies to
;; direction sets, root-to-target intersection, and canonical CSS
;; touch-action serialization. These are the pure functions the web
;; terminal uses to derive policy overlays; the terminal owns the DOM.
;; Specification: docs/design/dao.gui.event.md section Mobile Web Touch
;; Policy.
(ns dao.gui.event.policy
  (:require [clojure.string :as str]))


(def unconstrained
  "The unconstrained top value produced by :auto."
  ::unconstrained)


(def authored-policies
  "The accepted authored policy values."
  #{:auto :none :manipulation :pan-x :pan-y :pan-left :pan-right :pan-up
    :pan-down :pinch-zoom})


(def set-policy-tokens
  "Tokens a policy set may contain: only directions and pinch."
  #{:pan-x :pan-y :pan-left :pan-right :pan-up :pan-down :pinch-zoom})


(def all-pan-directions #{:pan-left :pan-right :pan-up :pan-down})
(def manipulation-set #{:pan-left :pan-right :pan-up :pan-down :pinch-zoom})


(defn valid-authored-policy?
  [p]
  (cond (keyword? p) (contains? authored-policies p)
        (set? p) (and (seq p) (every? #(contains? set-policy-tokens %) p))
        :else false))


(defn normalize
  "Normalize one authored policy to a set of direction and pinch tokens,
  :none to the empty set, and :auto to the unconstrained top value.
  Returns nil for invalid policies."
  [p]
  (when (valid-authored-policy? p)
    (let [tokens (if (set? p) p #{p})]
      (reduce (fn [acc token]
                (case token
                  :auto (if (= acc unconstrained) acc unconstrained)
                  :none #{}
                  :manipulation (if (= acc unconstrained)
                                  manipulation-set
                                  (into acc manipulation-set))
                  :pan-x (if (= acc unconstrained)
                           #{:pan-left :pan-right}
                           (into acc #{:pan-left :pan-right}))
                  :pan-y (if (= acc unconstrained)
                           #{:pan-up :pan-down}
                           (into acc #{:pan-up :pan-down}))
                  (if (= acc unconstrained) #{token} (into acc #{token}))))
              unconstrained
              tokens))))


(defn intersect
  "Intersect explicit policies from root to target."
  [policies]
  (reduce (fn [acc policy]
            (let [normalized (normalize policy)]
              (if (nil? normalized)
                acc
                (if (= acc unconstrained)
                  normalized
                  (if (= normalized unconstrained)
                    acc
                    (into #{}
                          (filter (fn [token] (contains? normalized token)))
                          acc))))))
          unconstrained
          policies))


(defn serialize
  "Canonical CSS touch-action string. When horizontal, vertical, and
  pinch permissions coexist their canonical tokens appear in that order
  separated by spaces."
  [edn]
  (cond (= edn unconstrained) "auto"
        (= edn :auto) "auto"
        (= edn :manipulation) "manipulation"
        (= edn :none) "none"
        (= edn #{}) "none"
        (= edn manipulation-set) "manipulation"
        (set? edn) (let [has-left (contains? edn :pan-left)
                         has-right (contains? edn :pan-right)
                         has-up (contains? edn :pan-up)
                         has-down (contains? edn :pan-down)
                         has-pinch (contains? edn :pinch-zoom)
                         horizontal (cond (and has-left has-right) "pan-x"
                                          has-left "pan-left"
                                          has-right "pan-right"
                                          :else nil)
                         vertical (cond (and has-up has-down) "pan-y"
                                        has-up "pan-up"
                                        has-down "pan-down"
                                        :else nil)]
                     (if (and (nil? horizontal) (nil? vertical) (not has-pinch))
                       "none"
                       (str/join " "
                                 (keep identity
                                       [horizontal vertical
                                        (when has-pinch "pinch-zoom")]))))
        :else nil))
