(ns yin.vm.semantic.frame
  "Pure, ordered validation of decoded canonical continuation frames (§3)."
  (:require [yin.vm :as vm]
            [yin.vm.semantic.code :as code]
            [yin.vm.semantic.analysis :as analysis]))


(defn- problem
  [kind path]
  {:yin.k/kind kind :yin.k/path path})


(defn- closed?
  [x required optional]
  (and (map? x) (every? #(contains? x %) required)
       (every? (into (set required) optional) (keys x))))


(defn task-defect
  "X1, once per body, including a halted body with no frames."
  [task]
  (let [e (:free-env task)]
    (cond
      (not (and (map? e) (every? symbol? (keys e))))
      (problem :free-env-shape [:free-env])
      (some vm/reserved-name? (keys e))
      (problem :reserved-name [:free-env]))))


(defn- env?
  [e]
  (and (closed? e [:yin.k/bindings] [:yin.k/store-of])
       (or (not (contains? e :yin.k/store-of)) (some? (:yin.k/store-of e)))
       (map? (:yin.k/bindings e))
       (every? symbol? (keys (:yin.k/bindings e)))))


(defn- lexical-names
  [v pc]
  (let [bs (code/body-ranges v)]
    (loop [b (code/body-index bs pc) names #{}]
      (if-let [owner (:owner (nth bs b))]
        (recur (code/body-index bs owner) (into names (nth (nth v owner) 2)))
        names))))


(defn- window-defect
  [f v rd prefix path]
  (let [pc (:yin.k/pc f) w (:yin.k/window f)
        body (nth (code/parse v) (code/body-index (code/body-ranges v) pc))
        wanted (analysis/saved (analysis/analyze v) pc rd)
        ks (set (keys w))
        kind (fn [s] (keyword (str prefix s)))
        p (conj path :yin.k/window)]
    (cond
      (some #(>= % (:registers body)) ks) (problem (kind "window-scope") p)
      (contains? w rd) (problem (kind "window-self") p)
      (some #(not (contains? wanted %)) ks) (problem (kind "window-extra") p)
      (some #(not (contains? w %)) wanted) (problem (kind "window-missing") p))))


(defn defect
  "First §3 defect, or nil. Code is already well formed; task is plain
   {:free-env map :parked-key P :ffi-call-ids set}. No engine is read.
   Values have already passed the carrier's value-domain checks."
  [frame code-map context task]
  (let [act? (= :act (:yin.k/state frame))
        required (if act?
                   [:yin.k/state :yin.k/segment :yin.k/pc :yin.k/env
                    :yin.k/window :yin.k/deliver :yin.k/k]
                   [:yin.k/state :yin.k/segment :yin.k/site :yin.k/deliver :yin.k/k])
        k (:yin.k/k frame)
        returns (when (vector? k) (mapv (fn [i] [(nth k i) [:yin.k/k i]]) (range (count k))))
        all (into [[frame []]] returns)
        envs (if act? all returns)]
    (or
      ;; S1-S4, globally before any dependent read.
      (when-not (and (contains? #{:act :tail} (:yin.k/state frame))
                     (closed? frame required [:yin.k/pending]))
        (problem :frame-shape []))
      (when-not (vector? k) (problem :k-shape [:yin.k/k]))
      (some (fn [[f p]]
              (when-not (and (closed? f [:yin.k/frame-type :yin.k/segment :yin.k/pc
                                         :yin.k/env :yin.k/window :yin.k/rd] [])
                             (= :return (:yin.k/frame-type f)))
                (problem :k-shape p))) returns)
      (some (fn [[f p]]
              (when-not (and (map? (:yin.k/window f))
                             (every? code/reg? (keys (:yin.k/window f))))
                (problem :window-representation (conj p :yin.k/window)))) envs)
      (some (fn [[f p]]
              (when-not (env? (:yin.k/env f))
                (problem :env-shape (conj p :yin.k/env)))) envs)
      (when-not (map? (:yin.k/deliver frame)) (problem :deliver-shape [:yin.k/deliver]))
      ;; C1-C3 across the entire continuation.
      (some (fn [[f p]]
              (when-not (contains? code-map (:yin.k/segment f))
                (problem :segment (conj p :yin.k/segment)))) all)
      (some (fn [[f p]]
              (let [slot (if (and (empty? p) (not act?)) :yin.k/site :yin.k/pc)
                    n (get f slot) v (get code-map (:yin.k/segment f))]
                (cond
                  (not (code/reg? n)) (problem :pc-kind (conj p slot))
                  (not (and (< n (count v)) (or (= slot :yin.k/site) (pos? n))))
                  (problem :pc-bounds (conj p slot))))) all)
      (some (fn [[f p]]
              (let [pc (:yin.k/pc f) bs (code/body-ranges (get code-map (:yin.k/segment f)))]
                (when-not (= (code/body-index bs (dec pc)) (code/body-index bs pc))
                  (problem :pc-body (conj p :yin.k/pc))))) envs)
      (let [v (get code-map (:yin.k/segment frame))
            site (if act? (dec (:yin.k/pc frame)) (:yin.k/site frame))
            inst (nth v site) op (first inst) rd (code/rd inst)
            d (:yin.k/deliver frame)]
        (or
          (if act?
            (or
              (when-not (or (contains? #{:stream-next :stream-put :ffi-call :park :current-continuation} op)
                            (and (= :call op) (not (code/tail-call? inst))))
                (problem :site [:yin.k/pc]))
              (when-not (= rd (:yin.k/rd d)) (problem :destination [:yin.k/deliver :yin.k/rd]))
              (when-not (= {:yin.k/deliver :rd :yin.k/rd rd} d) (problem :deliver [:yin.k/deliver]))
              (window-defect frame v rd "" []))
            (or
              (when-not (code/tail-call? inst) (problem :site [:yin.k/site]))
              (when-not (= {:yin.k/deliver :return} d) (problem :deliver [:yin.k/deliver]))))
          (some (fn [[f p]]
                  (let [fv (get code-map (:yin.k/segment f)) fi (nth fv (dec (:yin.k/pc f)))
                        frd (:yin.k/rd f)]
                    (or
                      (when-not (and (= :call (first fi)) (not (code/tail-call? fi)))
                        (problem :return-site (conj p :yin.k/pc)))
                      (when-not (= frd (code/rd fi)) (problem :return-destination (conj p :yin.k/rd)))
                      (window-defect f fv frd "return-" p)))) returns)
          (some (fn [[f p]]
                  (when (some vm/reserved-name? (keys (get-in f [:yin.k/env :yin.k/bindings])))
                    (problem :reserved-name (conj p :yin.k/env :yin.k/bindings)))) envs)
          (some (fn [[f p]]
                  (let [names (lexical-names (get code-map (:yin.k/segment f)) (:yin.k/pc f))
                        bindings (get-in f [:yin.k/env :yin.k/bindings])]
                    (when-not (and (every? #(contains? bindings %) names)
                                   (= (apply dissoc bindings names)
                                      (apply dissoc (:free-env task) names)))
                      (problem :free-env-mismatch (conj p :yin.k/env :yin.k/bindings))))) envs)
          (when-not (and (contains? #{:wait :captured :parked} context)
                         (= (= :wait context) (contains? frame :yin.k/pending)))
            (problem :pending-presence [:yin.k/pending]))
          (when (= :wait context)
            (let [reasons (case op
                            :stream-next #{:next}
                            :stream-put #{:put}
                            :ffi-call #{:ffi :ffi-request}
                            :call #{:next :put :ffi :ffi-request :link-request :link-response :install}
                            #{})]
              (when-not (contains? reasons (get-in frame [:yin.k/pending :yin.k/reason]))
                (problem :reason-mismatch [:yin.k/pending :yin.k/reason]))))
          (when (and (= :captured context) (not (and act? (= :current-continuation op))))
            (problem :captured-site [(if act? :yin.k/pc :yin.k/site)]))
          (when (and (= :parked context)
                     (not (or (and act? (= :park op))
                              (and (contains? (:ffi-call-ids task) (:parked-key task))
                                   (or (and act? (= :ffi-call op))
                                       (and (not act?) (code/tail-call? inst)))))))
            (problem :parked-site [(if act? :yin.k/pc :yin.k/site)])))))))
