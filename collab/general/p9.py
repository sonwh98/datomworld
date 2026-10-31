p = 'src/cljc/yin/vm/ucf/handoff.cljc'
s = open(p).read()


def rep(old, new, tag):
    global s
    if old not in s:
        if new in s:
            print("already", tag)
            return
        raise Exception("missing " + tag)
    s = s.replace(old, new, 1)
    print("applied", tag)


rep('''(defn- compose-v2
  "The code space a layout of `images` builds, under `engine`'s kernel."''', '''(defn- walker-free-names
  "The names the walker rows reachable from `roots` read from outside
   every enclosing lambda: a `:variable` not bound by a `:lambda` on its
   path, the reserved definition operator excepted."
  [rows roots]
  (loop [work (mapv (fn [id] [id #{}]) roots), seen #{}, names #{}]
    (if-some [[id bound :as item] (peek work)]
      (let [work (pop work)]
        (if (contains? seen item)
          (recur work seen names)
          (let [row (get rows id)
                tag (nth row 1)]
            (case tag
              :variable
              (let [n (nth row 2)]
                (recur work (conj seen item)
                       (if (or (contains? bound n) (vm/reserved-name? n))
                         names
                         (conj names n))))
              :lambda
              (recur (conj work [(nth row 3) (into bound (nth row 2))])
                     (conj seen item) names)
              (recur (into work (map (fn [c] [c bound])) (row-children row))
                     (conj seen item) names)))))
      names)))


(defn- free-names-v2
  "Every name the carried code reads free, by profile: the `:load-free`
   names of the layout's images, or the walker rows' free variables.
   Over-approximation is admissible: a name that would never be read
   still has to be dischargeable."
  [engine layout code roots]
  (case engine
    :stack (into #{}
                 (comp (mapcat (fn [[_ image]] image))
                       (filter #(= :load-free (first %)))
                       (map #(nth % 1)))
                 layout)
    :register (into #{}
                    (comp (mapcat (fn [[_ image]] (:instructions image)))
                          (filter #(= :load-free (first %)))
                          (map #(nth % 2)))
                    layout)
    :walker (walker-free-names code roots)
    #{}))


(defn- discharge-names!
  "Rule every free name in `resolve-var`'s order -- the free environment
   and every environment carried, the store, the primitives, the module
   registry -- or refuse `:yin.k/unsatisfied`, discovery incomplete,
   naming what nothing answers.  A host-state primitive is not portable,
   and a primitive with no published profile is unsatisfied."
  [vm names bound installing]
  (let [primitives (:primitives vm)
        missing (volatile! #{})
        profiles (volatile! #{})]
    (doseq [sym (remove vm/reserved-name? names)]
      (let [profile (vm/profile-of primitives sym)]
        (cond
          (or (contains? (:free-env vm) sym) (contains? bound sym)
              (contains? (:store vm) sym) (contains? installing sym))
          nil

          (some? profile)
          (when (= :host (:yin.k/class profile))
            (non-portable! :host-state-primitive {:yin.k/name sym}))

          (contains? primitives sym) (vswap! profiles conj sym)

          (and (namespace sym)
               (some? (module/resolve-module
                        (:modules vm)
                        (symbol (str (namespace sym) "." (name sym))))))
          nil

          :else (vswap! missing conj sym))))
    (when (or (seq @missing) (seq @profiles))
      (refuse! :yin.k/unsatisfied
               {:yin.k/discovery :incomplete
                :yin.k/missing {:obligations @missing
                                :profiles @profiles}}))))


(defn- compose-v2
  "The code space a layout of `images` builds, under `engine`'s kernel."''', "free-name-fns")

rep('''    (swap! found update :rows merge rows)
    root))''', '''    (swap! found (fn [acc]
                   (-> acc
                       (update :rows merge rows)
                       (update :roots (fnil conj #{}) root))))
    root))''', "roots")

rep('''  [found encode env]
  (let [m (get env engine/store-of-key)]
    (when (some? m) (swap! found update :stores conj m))
    (cond-> {:yin.k/bindings''', '''  [found encode env]
  (let [m (get env engine/store-of-key)]
    (when (some? m) (swap! found update :stores conj m))
    (swap! found update :bound (fnil into #{})
           (keys (engine/without-store-of (or env {}))))
    (cond-> {:yin.k/bindings''', "bound")

rep('''                  (swap! found
                         (fn [acc]
                           (let [acc (update acc :segments
                                             conj (:yin.k/segment marker))]''', '''                  (swap! found
                         (fn [acc]
                           (let [acc (-> acc
                                         (update :bound (fnil into #{})
                                                 (keys (:yin.k/env marker)))
                                         (update :segments
                                                 conj (:yin.k/segment marker)))]''', "closure-bound")

rep('''               segments (if walker-code
                          (set (keys walker-code))
                          (into (:segments @found) (map first) layout))''', '''               _ (when (and v2? (not= :semantic engine))
                   (discharge-names!
                     vm
                     (free-names-v2 engine layout (or walker-code {})
                                    (into (or (:roots @found) #{})
                                          (:segments @found)))
                     (or (:bound @found) #{})
                     installing))
               segments (if walker-code
                          (set (keys walker-code))
                          (into (:segments @found) (map first) layout))''', "check")
open(p, 'w').write(s)
