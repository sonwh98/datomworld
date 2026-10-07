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


rep('''(defn- validate-registers-v2
  "One register map under the body's profile''', '''(defn- register-payload-v2
  "The native-shaped skeleton of one register register map, its code
   space rebuilt from its own layout; the wire values stay as they are."
  [{:keys [code]} r path]
  (let [composed (try (register/compose (mapv code (:yin.k/layout r)))
                      (catch #?(:cljd Object :clj Throwable :cljs :default) e
                        (if (= :layout-overflow (:rule (ex-data e)))
                          (undecodable! {:yin.k/path (conj path :yin.k/layout)
                                         :yin.k/kind :layout-overflow})
                          (throw e))))
        rows (:images composed)]
    (cond-> {:segment (:segment composed)
             :hash (:hash composed)
             :format reffects/format-tag
             :image (:yin.k/image r)
             :site-pc (:yin.k/site-pc r)
             :pc (:yin.k/pc r)
             :frames (:yin.k/frames r)
             :regs (:yin.k/regs r)
             :live (:yin.k/live r)
             :continuation (mapv (fn [f]
                                   {:site-pc (:yin.k/site-pc f)
                                    :return-pc (:yin.k/return-pc f)
                                    :frames (:yin.k/frames f)
                                    :regs (:yin.k/regs f)
                                    :live (:yin.k/live f)
                                    :dest (:yin.k/dest f)})
                                 (:yin.k/continuation r))
             :dest (:yin.k/dest r)
             :resume-mode (:yin.k/resume-mode r)}
      (< 1 (count rows)) (assoc :images rows))))


(defn- validate-registers-v2
  "One register map under the body's profile''', "payload-skeleton")

rep('''    (undecodable! {:yin.k/kind :unsupported-profile :yin.k/path path})))


(defn- check-frame-pc-v2''', '''    :register
    (do
      (closed! r #{:yin.k/layout :yin.k/image :yin.k/site-pc :yin.k/pc
                   :yin.k/frames :yin.k/regs :yin.k/live :yin.k/continuation
                   :yin.k/dest :yin.k/resume-mode}
               #{:yin.k/store-of} path)
      (layout-prefix! ctx (:yin.k/layout r) path)
      (when-not (and (exact-int? (:yin.k/site-pc r))
                     (exact-int? (:yin.k/pc r))
                     (vector? (:yin.k/frames r))
                     (vector? (:yin.k/regs r))
                     (every? (fn [p]
                               (and (vector? p) (= 2 (count p))
                                    (exact-int? (nth p 0))))
                             (:yin.k/regs r))
                     (vector? (:yin.k/live r))
                     (every? exact-int? (:yin.k/live r))
                     (vector? (:yin.k/continuation r))
                     (or (nil? (:yin.k/dest r)) (exact-int? (:yin.k/dest r)))
                     (contains? #{:write-result :return-result}
                                (:yin.k/resume-mode r)))
        (undecodable! {:yin.k/path path :yin.k/kind :registers}))
      (doseq [[i f] (map-indexed vector (:yin.k/continuation r))]
        (let [fp (conj path :yin.k/continuation i)]
          (closed! f #{:yin.k/site-pc :yin.k/return-pc :yin.k/frames
                       :yin.k/regs :yin.k/live :yin.k/dest}
                   #{:yin.k/store-of} fp)
          (when-not (and (exact-int? (:yin.k/site-pc f))
                         (exact-int? (:yin.k/return-pc f))
                         (exact-int? (:yin.k/dest f))
                         (vector? (:yin.k/frames f))
                         (vector? (:yin.k/regs f))
                         (vector? (:yin.k/live f))
                         (every? exact-int? (:yin.k/live f))
                         (every? (fn [p]
                                   (and (vector? p) (= 2 (count p))
                                        (exact-int? (nth p 0))))
                                 (:yin.k/regs f)))
            (undecodable! {:yin.k/path fp :yin.k/kind :return-frame}))))
      (let [payload (register-payload-v2 ctx r path)
            composed (register/compose (mapv code (:yin.k/layout r)))]
        (when-some [defect (reffects/continuation-defect payload)]
          (undecodable! {:yin.k/path path :yin.k/defect defect}))
        (when-not (contains? (get context-sites context)
                             (site-op :register payload))
          (undecodable! {:yin.k/path path :yin.k/kind :site}))
        (when-not (= (:yin.k/image r)
                     (nth (reffects/image-row (:images composed)
                                              (inc (:yin.k/site-pc r)))
                          0 nil))
          (undecodable! {:yin.k/path (conj path :yin.k/image)}))))

    (undecodable! {:yin.k/kind :unsupported-profile :yin.k/path path})))


(defn- check-frame-pc-v2''', "validate-registers-register")

rep('''    :stack
    (let [composed (stack/compose (mapv code (:yin.k/layout registers)))
          op (site-op :stack {:segment (:segment composed)
                              :pc (:yin.k/pc registers)})]''', '''    (:stack :register)
    (let [composed (compose-v2 engine (mapv code (:yin.k/layout registers)))
          op (site-op engine {:segment (:segment composed)
                              :pc (:yin.k/pc registers)
                              :site-pc (:yin.k/site-pc registers)})]''', "check-frame")

rep('''      (undecodable! {:yin.k/kind :unsupported-profile}))))


(defn- validate-layout-v2''', '''      :register
      (do (when-not (and (map? v) (= a (rcode/register-hash v)))
            (refuse! :yin.k/hash-mismatch {:yin.k/segment a}))
          (when-some [defect (when (seq (:instructions v))
                               (rcode/register-image-defect v))]
            (undecodable! {:yin.k/segment a :yin.k/defect defect})))
      (undecodable! {:yin.k/kind :unsupported-profile}))))


(defn- empty-image?
  [engine image]
  (if (= :register engine) (empty? (:instructions image)) (empty? image)))


(defn- validate-layout-v2''', "code-v2")

rep('''            :when (and (pos? i) (empty? (get code a)))]''',
    '''            :when (and (pos? i) (empty-image? engine (get code a)))]''', "empty")

rep('''    (when-some [defect (when (= :stack engine)
                         (try (stack/compose (mapv code layout)) nil
                              (catch #?(:cljd Object :clj Throwable :cljs :default) e
                                (when (= :layout-overflow (:rule (ex-data e)))
                                  {:rule :layout-overflow}))))]''',
    '''    (when-some [defect (try (compose-v2 engine (mapv code layout)) nil
                            (catch #?(:cljd Object :clj Throwable :cljs :default) e
                              (when (= :layout-overflow (:rule (ex-data e)))
                                {:rule :layout-overflow})))]''', "layout-overflow")

rep('''        (undecodable! {:yin.k/kind :unsupported-profile})))))


(defn- parked-site!''', '''        :register
        (do
          (when-not (and (= :positional (:yin.k/binding m))
                         (= reffects/format-tag (:yin.k/format m)))
            (undecodable! {:yin.k/kind :closure-profile}))
          (when-not (and (some? v) (exact-int? entry)
                         (< entry (count (:instructions v)))
                         (some (fn [inst]
                                 (and (= :closure (nth inst 0))
                                      (= (:yin.k/arity m) (nth inst 2))
                                      (= entry (nth inst 3))))
                               (:instructions v)))
            (undecodable! {:yin.k/segment a :yin.k/kind :closure-code})))
        (undecodable! {:yin.k/kind :unsupported-profile})))))


(defn- parked-site!''', "closure")

rep('''              (= :park (site-op engine
                                {:segment (:segment
                                            (stack/compose
                                              (mapv code (:yin.k/layout r))))
                                 :pc (:yin.k/pc r)}))''', '''              (= :park (site-op engine
                                {:segment (:segment
                                            (compose-v2
                                              engine
                                              (mapv code (:yin.k/layout r))))
                                 :pc (:yin.k/pc r)
                                 :site-pc (:yin.k/site-pc r)}))''', "parked-site")

rep('''    :semantic
    {:segment (get aliases (:yin.k/segment r))''', '''    :register
    (let [c (register/compose (mapv code (:yin.k/layout r)))
          frames (fn [fs] (mapv #(mapv decode %) fs))
          pairs (fn [regs] (mapv (fn [[i x]] [i (decode x)]) regs))]
      (cond-> {:segment (:segment c)
               :hash (:hash c)
               :format reffects/format-tag
               :image (:yin.k/image r)
               :site-pc (:yin.k/site-pc r)
               :pc (:yin.k/pc r)
               :frames (frames (:yin.k/frames r))
               :regs (pairs (:yin.k/regs r))
               :live (:yin.k/live r)
               :continuation
               (mapv (fn [f]
                       (cond-> {:site-pc (:yin.k/site-pc f)
                                :return-pc (:yin.k/return-pc f)
                                :frames (frames (:yin.k/frames f))
                                :regs (pairs (:yin.k/regs f))
                                :live (:yin.k/live f)
                                :dest (:yin.k/dest f)}
                         (some? (:yin.k/store-of f))
                         (assoc :store-of (:yin.k/store-of f))))
                     (:yin.k/continuation r))
               :dest (:yin.k/dest r)
               :resume-mode (:yin.k/resume-mode r)}
        (< 1 (count (:images c))) (assoc :images (:images c))
        (some? (:yin.k/store-of r))
        (assoc :store-of (:yin.k/store-of r))))
    :semantic
    {:segment (get aliases (:yin.k/segment r))''', "decode")

rep('''                     (stack/rebuild (isolated-receiver recv)
                                    (mapv (:yin.k/code body)
                                          (:yin.k/layout body)))''', '''                     ((if (= :stack engine) stack/rebuild register/rebuild)
                      (isolated-receiver recv)
                      (mapv (:yin.k/code body) (:yin.k/layout body)))''', "rebuild")

open(p, 'w').write(s)
