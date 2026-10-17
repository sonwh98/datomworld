(ns yin.vm.semantic.layout
  "Phase 6c (docs/design/yin.vm.semantic.continuation.md §4):
   the de Bruijn register and stack kernels reconstruct the canonical
   frame of the semantic kernel through their layouts, and lower it
   back.

   Static half, per segment A: `segment` derives R(A) and H(A) and reads
   off the register pairing (`phys`, the restricted inverse `virt`, the
   kernel's `live` as live-out with tail boundaries `[]`), the stack
   correspondence (`h->a`, `a->h`, every `:push` removed) and the operand
   layout `stack-layout` (the values pushed and not yet consumed while
   control is inside a pc's expression). A `space` indexes segments by
   the kernels' image checksums, R and H: A is the
   only code identity.

   Dynamic half: `lift-state` reads a kernel's wait entry, parked record
   or reified payload as the canonical `act` or `tail` payload the
   semantic evaluator mints (`{:segment A :pc :env :window :k
   :deliver}`, `{:segment A :site :k :deliver}`, engine fields carried);
   `lower-state` is its inverse. The semantic kernel's return
   destinations are recovered from A, never from the physical `:dest`;
   the stack kernel's operand stack is sliced `[previous-base, base)` per
   frame and lowered with cumulative bases. `E` is the free environment
   merged under the chain's positional frames (§4.4); the inverse reads
   each frame back from `E` by the chain, never the free environment.

   Task level: `canonical-task` presents a kernel at any boundary —
   running (`lift-running` over the live fields, its `:push` stutters
   relabelled and its dead prefixes dropped and recorded as
   diagnostics) or quiescent — as a semantic machine for
   `yin.vm.ucf.rows/lift-task`; `kernel-task` restores a machine
   `rows/lower-task` produced into a fresh kernel, a `:running` machine
   into a running kernel (`lower-running`: nil register fill outside
   `L(p)`, no dead prefix, bases recomputed cumulatively);
   `lift-task` and `lower-task` compose them with the row
   carrier, the lower refusing what a kernel cannot hold before any
   stream is attached. A layout never runs code, never fills a binding
   from the receiver and never invents a state (§4.5)."
  (:require [dao.jing :as jing]
            [yin.vm.debruijn-code :as dcode]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn.register :as register]
            [yin.vm.debruijn.stack :as stack]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.module :as module]
            [yin.vm.semantic.analysis :as analysis]
            [yin.vm.semantic.code :as code]
            [yin.vm.semantic.derive :as derive]
            [yin.vm.ucf.rows :as rows]
            [yin.vm.values :as values]))


(defn- refuse!
  [status kind data]
  (throw (ex-info "Layout refused" (merge data (assoc {} :yin.k/status status :yin.k/kind kind)))))


;; =============================================================================
;; Static half: one segment
;; =============================================================================

(defn- operands
  "The register operands of an A tuple, destination first, then its uses
   in table order."
  [t]
  (into (if-some [r (code/rd t)] [r] []) (code/uses t)))


(defn- register-operands
  "The `:reg`/`:regs` operands of an R tuple in table order (the `:live`
   slot is `:data`, never paired)."
  [t]
  (into []
        (mapcat (fn [[[_ kind] x]] (case kind :reg [x] :regs x [])))
        (map vector (get rcode/opcode-table (nth t 0)) (rest t))))


(defn register-pairs
  "`[pc virtual physical]` for every register operand of every pc: A's
   tuple and R's name their registers in the same slots (phase-5 §2.4)."
  [v image]
  (into []
        (mapcat (fn [pc]
                  (map (fn [x s] [pc x s])
                       (operands (nth v pc))
                       (register-operands (nth (:instructions image) pc)))))
        (range (count v))))


(defn- phys-maps
  "Per body, `{virtual physical}` read off the pairs; the first pc that
   names a virtual wins (register-pairing checks they agree)."
  [bodies pairs]
  (reduce (fn [acc [pc x s]]
            (let [b (code/body-index bodies pc)]
              (if (contains? (nth acc b) x) acc (assoc-in acc [b x] s))))
          (vec (repeat (count bodies) {}))
          pairs))


(defn correspondence
  "§4.3 static item 1: H with every `:push` removed corresponds one to one,
   in order, with A. Answers `{:a->h [h-pc …] :h->a {h-pc a-pc}}`."
  [h]
  (reduce (fn [acc hpc]
            (if (= :push (nth (nth h hpc) 0))
              acc
              (-> acc
                  (update :a->h conj hpc)
                  (assoc-in [:h->a hpc] (count (:a->h acc))))))
          {:a->h [] :h->a {}}
          (range (count h))))


(def ^:private pushing-ops
  "The parse nodes whose children before the current one are pushed:
   `:call` (the operator and earlier operands), `:ffi-call` (earlier
   operands) and `:stream-put` (the target, while the value runs)."
  #{:call :ffi-call :stream-put})


(defn- stack-layouts
  "§4.3 static item 2 for every pc of every body: `{pc [virtual …]}`,
   bottom to top, over the parse tree's ancestors outermost first."
  [v bodies]
  (letfn [(result-rd
            [node]
            (if (= :if (:op node)) (result-rd (second (:children node)))
                (code/rd (nth v (:pc node)))))
          (walk
            [out node pending]
            (let [out (cond-> (assoc out (:pc node) pending)
                        (= :if (:op node)) (assoc (:jump node) (conj pending (result-rd node))))
                  pushes? (contains? pushing-ops (:op node))
                  uses (when pushes? (code/uses (nth v (:pc node))))]
              (reduce (fn [out [i child]]
                        (walk out child (if pushes? (into pending (take i uses)) pending)))
                      out
                      (map-indexed vector (:children node)))))]
    (reduce (fn [out body] (walk (assoc out (dec (:end body)) []) (:expr body) [])) {} bodies)))


(defn segment
  "The static facts of one admitted A vector `v`, for both kernels."
  [v]
  (let [bodies (code/parse v)
        r (derive/register-image v)
        h (:image (derive/stack-image v))
        pairs (register-pairs v r)]
    (merge {:vector v
            :address (jing/segment-key v)
            :bodies bodies
            :analysis (analysis/analyze v)
            :register r
            :register-id (rcode/register-hash r)
            :stack h
            :stack-id (dcode/image-hash h)
            :pairs pairs
            :phys (phys-maps bodies pairs)
            :layouts (stack-layouts v bodies)}
           (correspondence h))))


(defn space
  "Segments by A, and A by each kernel's image identity. `segments` is a
   seq of `segment` results or of A vectors."
  [segments]
  (reduce (fn [acc s]
            (let [s (if (map? s) s (segment s))
                  a (:address s)]
              (-> acc
                  (assoc-in [:segments a] s)
                  (update-in [:register (:register-id s)] (fnil conj #{}) a)
                  (update-in [:stack (:stack-id s)] (fnil conj #{}) a))))
          {:segments {} :register {} :stack {}}
          segments))


(defn segment-of
  "The segment A names in `space`."
  [space a]
  (or (get-in space [:segments a])
      (refuse! :yin.k/unsatisfied :segment {:yin.k/segment a})))


(defn- image-segment
  "The segment whose `kind` image is `ident`: an identity is a checksum,
   so two A deriving one image are ambiguous and refused."
  [space kind ident]
  (let [as (get-in space [kind ident])]
    (cond
      (empty? as) (refuse! :yin.k/non-portable :unknown-image {:yin.k/image ident})
      (< 1 (count as)) (refuse! :yin.k/non-portable :ambiguous-image {:yin.k/image ident})
      :else (segment-of space (first as)))))


(defn- inst
  [seg pc]
  (nth (:vector seg) pc))


(defn- body-of
  [seg pc]
  (code/body-index (:bodies seg) pc))


(defn phys
  "`phys(body(pc), x)`: the physical register R names for virtual `x` in
   the body holding `pc`."
  [seg pc x]
  (let [s (get (nth (:phys seg) (body-of seg pc)) x)]
    (when (nil? s) (refuse! :yin.k/non-portable :register-layout {:yin.k/pc pc :yin.k/register x}))
    s))


(defn live-out
  "`live-out*(p)`: the union of `L(q)` over p's actual successors (empty
   for a tail call)."
  [seg p]
  (reduce (fn [acc q] (into acc (analysis/live (:analysis seg) q)))
          #{}
          (analysis/successors (:vector seg) p)))


(defn register-live
  "§5.6 item 3: the `live` operand R must carry at boundary `p`,
   `(sort (map phys (disj live-out*(p) rd)))`, the destination removed as
   a virtual id before mapping; `[]` at a tail call."
  [seg p]
  (vec (sort (map #(phys seg p %) (disj (live-out seg p) (code/rd (inst seg p)))))))


(defn- virt*
  [seg p s virtuals named-fallback?]
  (let [m (nth (:phys seg) (body-of seg p))
        live (filterv #(= s (get m %)) (sort virtuals))
        named (when named-fallback? (filterv #(= s (get m %)) (sort (keys m))))]
    (cond
      (= 1 (count live)) (first live)
      (and named-fallback? (empty? live) (= 1 (count named))) (first named)
      (and (not named-fallback?) (empty? live)) nil
      :else (refuse! :yin.k/non-portable :register-layout {:yin.k/pc p :yin.k/register s}))))


(defn virt
  "The restricted inverse of `phys` at boundary `p`: the virtual of
   `live-out*(p)` held in physical `s`. A slot outside that image (only a
   tampered entry names one) reads as the one virtual of the body R maps
   there, so the frame validator judges the window; an ambiguous slot is
   refused. The explicit-set arity names the virtuals instead — `L(p)`
   for a run state — and reads nothing outside their image: a running
   kernel's register file holds dead slots as a matter of course, and a
   slot no named virtual holds is simply absent from the window."
  ([seg p s] (virt* seg p s (live-out seg p) true))
  ([seg p s virtuals] (virt* seg p s virtuals false)))


(defn stack-layout
  "§4.3: the virtual registers pushed and not yet consumed while control
   is inside the expression at A pc `p`, bottom to top."
  [seg p]
  (get (:layouts seg) p))


(defn chain
  "The closure pcs owning the body that holds `pc`, outermost first."
  [seg pc]
  (loop [b (body-of seg pc) out ()]
    (if-let [owner (:owner (nth (:bodies seg) b))]
      (recur (body-of seg owner) (conj out owner))
      (vec out))))


(defn- params
  [seg c]
  (nth (inst seg c) 2))


(defn env-of
  "§4.4 forward: `E` at `pc` from the positional frames `F` (outermost
   first, values already canonical), the task's free environment and the
   activation's module store."
  [seg pc free-env frames store-of]
  (let [cs (chain seg pc)]
    (when-not (= (count cs) (count frames))
      (refuse! :yin.k/non-portable :frame-depth {:yin.k/pc pc :yin.k/depth (count frames)}))
    (cond-> (reduce (fn [e [c f]] (merge e (engine/bind-params (params seg c) f)))
                    (or free-env {})
                    (map vector cs frames))
      (some? store-of) (assoc engine/store-of-key store-of))))


(defn frames-of
  "§4.4 inverse: `F[i] = (mapv #(get E %) params(cᵢ))` over the chain at
   `pc`. An outer value a shadowing inner binder hides reads as the inner
   one; the canonical frame never held it."
  [seg pc env]
  (mapv (fn [c] (mapv #(get env %) (params seg c))) (chain seg pc)))


;; =============================================================================
;; Kernel dispatch
;; =============================================================================

(defn kernel-kind
  "`:stack` or `:register`, by the kernel's link format."
  [k]
  (let [f (when (satisfies? module/IModuleKernel k) (:format (module/link-format k)))]
    (cond
      (= stack/format-tag f) :stack
      (= register/format-tag f) :register
      :else (refuse! :yin.k/unsupported :kernel {:yin.k/format f}))))


(defn- image-pc
  "`[segment rel-pc]` of absolute kernel pc `pc`."
  [kind k space pc]
  (let [[ident rel] (or (if (= :stack kind) (stack/image-pc k pc) (register/image-pc k pc))
                        (refuse! :yin.k/non-portable :unknown-image {:yin.k/pc pc}))]
    [(image-segment space kind ident) rel]))


(defn offset
  "The offset of segment `seg`'s image in kernel `k`'s code space."
  [kind k seg]
  (let [ident (if (= :stack kind) (:stack-id seg) (:register-id seg))]
    (or (if (= :stack kind) (stack/absolute-pc k [ident 0]) (register/absolute-pc k [ident 0]))
        (refuse! :yin.k/unsatisfied :image-not-attached {:yin.k/segment (:address seg)}))))


(def ^:private parking-ops
  "A1: the instructions a delivery state's resume pc may follow, besides
   a non-tail call."
  #{:stream-next :stream-put :ffi-call :park :current-continuation})


(defn- safepoint!
  "Refuse `:yin.k/not-at-safepoint` unless `pc` is a pc of one of A's
   bodies: in the run mode (tail? = :run, any pc inside a body — the
   bodies partition the vector), or else a delivery state's pc, the A
   instruction before which parks (a tail call for a tail state). A
   layout never invents a state (§4.5); the refusal's name is
   historical."
  ([seg pc] (safepoint! seg pc :run))
  ([seg pc tail?]
   (if (= :run tail?)
     (when-not (and (integer? pc) (<= 0 pc) (< pc (count (:vector seg))))
       (refuse! :yin.k/not-at-safepoint nil {:yin.k/pc pc}))
     (let [t (when (and (integer? pc) (< 0 pc) (<= pc (count (:vector seg)))) (inst seg (dec pc)))
           op (when t (nth t 0))]
       (when-not (and t
                      (= (body-of seg (dec pc)) (body-of seg (min pc (dec (count (:vector seg))))))
                      (if tail?
                        (code/tail-call? t)
                        (or (contains? parking-ops op) (and (= :call op) (not (code/tail-call? t))))))
         (refuse! :yin.k/not-at-safepoint nil {:yin.k/pc pc}))))))


(defn- h->a
  "The A pc of H pc `hpc`. In the run mode a `:push` stutters — its
   state equals its successor's but for pc (`stack.cljc`'s `:push` only
   advances pc) — so it relabels to `h->a(hpc+1)` without running
   anything; no two pushes are adjacent (`correspondence`). Elsewhere (a
   delivery state's parking instruction, a closure's entry) a `:push` has
   no A pc: relabelling there would invent a state."
  ([seg hpc] (h->a seg hpc false))
  ([seg hpc run?]
   (or (get (:h->a seg) hpc)
       (when (and run? (integer? hpc) (<= 0 hpc) (< (inc hpc) (count (:stack seg)))
                  (= :push (nth (nth (:stack seg) hpc) 0)))
         (get (:h->a seg) (inc hpc)))
       (refuse! :yin.k/not-at-safepoint nil {:yin.k/pc hpc}))))


(defn- a->h
  [seg pc]
  (nth (:a->h seg) pc))


;; =============================================================================
;; Values: positional closures and kernel continuations, both ways
;; =============================================================================
;;
;; A lift context `cx` is {:kind :kernel :space :free-env :owner}; a lower
;; context `lx` adds :address, naming a canonical payload's or closure's
;; :segment as A.

(declare lift-state lower-state canonical-value positional-value)


(defn- walk-value
  "`x` with its guest collections rebuilt around `f`, which converts
   closures and continuations."
  [f x]
  (cond
    (values/host-typed? x) (f x)
    (map? x) (into {} (map (fn [[k v]] [(walk-value f k) (walk-value f v)])) x)
    (vector? x) (mapv #(walk-value f %) x)
    (set? x) (into #{} (map #(walk-value f %)) x)
    (seq? x) (apply list (map #(walk-value f %) x))
    :else x))


(defn- canonical-closure
  "§4.4: a positional closure as the canonical `{:type :closure :params
   :entry :segment :env}`: `params` from A's `:closure` at the owner of
   the entry body, `env` by the forward rule over the closure's frames."
  [cx c]
  (let [[seg rel] (image-pc (:kind cx) (:kernel cx) (:space cx) (:body-pc c))
        entry (if (= :stack (:kind cx)) (h->a seg rel) rel)
        owner (:owner (nth (:bodies seg) (body-of seg entry)))
        _ (when-not (and owner (= entry (nth (inst seg owner) 3)))
            (refuse! :yin.k/non-portable :closure-origin {:yin.k/entry entry}))
        ps (params seg owner)]
    (when-not (= (count ps) (:arity c))
      (refuse! :yin.k/non-portable :closure-binders {:yin.k/entry entry}))
    {:type :closure :params ps :entry entry :segment (:address seg)
     :env (env-of seg owner (:free-env cx)
                  (mapv (fn [f] (mapv #(canonical-value cx %) f)) (:frames c))
                  (:store-of c))}))


(defn- canonical-value*
  [cx x]
  (cond
    (values/closure? x) (values/closure (:owner cx) (canonical-closure cx (values/payload x)))
    (values/continuation? x)
    (values/continuation (:owner cx) (assoc (lift-state cx (values/payload x)) :type :reified-continuation))
    :else x))


(defn canonical-value
  "Kernel value `x` with every positional closure and kernel continuation
   reconstructed canonically (cx: a lift context)."
  [cx x]
  (walk-value #(canonical-value* cx %) x))


(defn- address-of
  [lx seg-id]
  (let [a ((:address lx) seg-id)]
    (when (nil? a) (refuse! :yin.k/non-portable :unaddressed-segment {:yin.k/hint seg-id}))
    a))


(defn- positional-closure
  "The inverse of canonical-closure: the entry's absolute pc in the
   receiving kernel, the frames read back from `env` by the owner's chain."
  [lx c]
  (let [seg (segment-of (:space lx) (address-of lx (:segment c)))
        entry (:entry c)
        owner (:owner (nth (:bodies seg) (body-of seg entry)))
        store-of (engine/env-store-of (:env c))]
    (when-not (and owner (= entry (nth (inst seg owner) 3)) (= (vec (:params c)) (vec (params seg owner))))
      (refuse! :yin.k/undecodable :closure-binders {:yin.k/entry entry}))
    (cond-> {:type :closure :arity (count (:params c))
             :body-pc (+ (offset (:kind lx) (:kernel lx) seg) (if (= :stack (:kind lx)) (a->h seg entry) entry))
             :frames (mapv (fn [f] (mapv #(positional-value lx %) f)) (frames-of seg owner (:env c)))}
      (some? store-of) (assoc :store-of store-of))))


(defn- positional-value*
  [lx x]
  (cond
    (values/closure? x) (values/closure (:owner lx) (positional-closure lx (values/payload x)))
    (values/continuation? x)
    (values/continuation (:owner lx) (merge {:type :reified-continuation} (lower-state lx (values/payload x))))
    :else x))


(defn positional-value
  "Canonical value `x` with every closure and continuation lowered into
   the receiving kernel (lx: a lower context)."
  [lx x]
  (walk-value #(positional-value* lx %) x))


;; =============================================================================
;; Lift: a kernel payload as the canonical act or tail
;; =============================================================================

(def ^:private kernel-keys
  "The register payload keys of either kernel; every other key of an
   entry is the engine's and travels in the pending."
  #{:segment :site-pc :pc :frames :regs :live :continuation :dest :resume-mode
    :format :hash :image :images :store-of :stack})


(defn- canonical-env
  [cx seg pc frames store-of]
  (env-of seg pc (:free-env cx) (mapv (fn [f] (mapv #(canonical-value cx %) f)) frames) store-of))


(defn- register-window
  "§4.2 step 4: `{virt(site, s) V}` over the entry's `[s V]` pairs."
  [cx seg site regs]
  (into {} (map (fn [[s x]] [(virt seg site s) (canonical-value cx x)])) regs))


;; single-arity beside register-window: cljd's compiler NPEs on a
;; multi-arity defn holding destructuring fns
(defn- register-run-window
  "A run state's window: `{virt(p, s, virtuals) V}` over the register
   file read as `[s V]` pairs, the virtuals named (`L(p)`); a slot no
   named virtual holds is absent."
  [cx seg p regs virtuals]
  (into {} (remove (fn [[x _]] (nil? x)))
        (map (fn [[s x]] [(virt seg p s virtuals) (canonical-value cx x)]) regs)))


(defn- register-kframe
  "§4.2 step 6, one frame independently: its own image row and A, `rd`
   from A, never the physical `:dest`."
  [cx f]
  (let [[seg pc] (image-pc :register (:kernel cx) (:space cx) (:return-pc f))]
    (safepoint! seg pc false)
    {:type :return :segment (:address seg) :pc pc
     :env (canonical-env cx seg pc (:frames f) (:store-of f))
     :window (register-window cx seg (dec pc) (:regs f))
     :rd (code/rd (inst seg (dec pc)))}))


(defn- lift-register
  [cx entry]
  (let [[seg pc] (image-pc :register (:kernel cx) (:space cx) (:pc entry))
        tail? (= :return-result (:resume-mode entry))
        _ (safepoint! seg pc tail?)
        k (mapv #(register-kframe cx %) (:continuation entry))]
    (if tail?
      {:segment (:address seg) :site (dec pc) :k k :deliver {:deliver :return}}
      {:segment (:address seg) :pc pc
       :env (canonical-env cx seg pc (:frames entry) (:store-of entry))
       :window (register-window cx seg (dec pc) (:regs entry))
       :k k
       :deliver {:deliver :rd :rd (code/rd (inst seg (dec pc)))}})))


(defn slices
  "§4.3's K slices of operand stack `st` under return frames whose bases
   are `bases`, outermost first: `St[b(i-1), b(i))` per frame with
   `b(-1) = 0`, then the current activation's `St[b(m-1), n)`."
  [st bases]
  (let [bounds (into [0] bases)]
    (conj (mapv (fn [lo hi] (subvec st lo hi)) bounds (rest bounds))
          (subvec st (peek bounds) (count st)))))


(defn- dead-prefix!
  "Record a dead prefix of `n` values below `pc`'s region in the lift's
   diagnostics (§4.3: never in the frame). The sink is the caller's
   (`:diagnostics` of the lift opts); with none, the count is dropped."
  [cx pc n]
  (when (and (pos? n) (:diagnostics cx))
    (swap! (:diagnostics cx) conj {:yin.k/dead-prefix n :yin.k/pc pc})))


(defn- take-top
  "The top `n` of slice."
  [slice n]
  (subvec (vec slice) (- (count slice) n)))


(defn- slice-window
  "`(zipmap (stack-layout (dec pc)) slice)`. The slice may be longer: a
   tail call keeps the values below the layout without pushing a frame,
   so the lift takes the top `|layout|` and drops the dead prefix,
   recording it in the lift's diagnostics, never in the frame."
  [cx seg pc slice]
  (let [layout (stack-layout seg (dec pc))]
    (when-not (>= (count slice) (count layout))
      (refuse! :yin.k/non-portable :stack-layout {:yin.k/pc pc :yin.k/layout layout :yin.k/slice (count slice)}))
    (dead-prefix! cx pc (- (count slice) (count layout)))
    (zipmap layout (map #(canonical-value cx %) (take-top slice (count layout))))))


(defn- stack-site
  "`[segment site]`: the A pc of the parking instruction before absolute
   stack resume pc `abs`."
  [cx abs]
  (let [[seg rel] (image-pc :stack (:kernel cx) (:space cx) abs)]
    [seg (h->a seg (dec rel))]))


(defn- stack-k-frames
  "The canonical K of stack return frames over their slices (§4.2 step 6
   for H): each frame's own image row, A site and rd."
  [cx frames parts]
  (mapv (fn [f slice]
          (let [[fseg fsite] (stack-site cx (:return-pc f))
                pc (inc fsite)]
            (safepoint! fseg pc false)
            {:type :return :segment (:address fseg) :pc pc
             :env (canonical-env cx fseg pc (:frames f) (:store-of f))
             :window (slice-window cx fseg pc slice)
             :rd (code/rd (inst fseg fsite))}))
        frames parts))


(defn- lift-stack
  [cx entry]
  (let [st (vec (:stack entry))
        frames (vec (:continuation entry))
        bases (mapv :stack-base frames)
        _ (when-not (and (every? integer? bases) (apply <= 0 (conj bases (count st))))
            (refuse! :yin.k/non-portable :stack-base {:yin.k/bases bases}))
        parts (slices st bases)
        [seg site] (stack-site cx (:pc entry))
        tail? (code/tail-call? (inst seg site))
        _ (safepoint! seg (inc site) tail?)
        k (stack-k-frames cx frames parts)]
    (if tail?
      ;; frozen §2.4: the current frames, store-of and slice are dead
      {:segment (:address seg) :site site :k k :deliver {:deliver :return}}
      (let [pc (inc site)]
        {:segment (:address seg) :pc pc
         :env (canonical-env cx seg pc (:frames entry) (:store-of entry))
         :window (slice-window cx seg pc (peek parts))
         :k k
         :deliver {:deliver :rd :rd (code/rd (inst seg site))}}))))


(defn- region-window
  "The run window at A pc `p` from the current activation's region: the
   top `|layout(p)| + |uses(p)|` values zipped in emitter order and
   filtered to `L(p)` (a dead-ambient layout entry is not live and never
   travels); the excess below is a dead prefix, dropped and recorded;
   repeated positions of one register must decode to agreeing values.
   Positions are paired with their registers before the filter, so only
   live values are canonicalized: a dead-ambient value is never walked,
   as the reference lane's `L(p)` window never walks it."
  [cx seg p region]
  (let [live (analysis/live (:analysis seg) p)
        sequence (into (stack-layout seg p) (code/uses (inst seg p)))
        excess (- (count region) (count sequence))]
    (when-not (>= excess 0)
      (refuse! :yin.k/non-portable :stack-layout {:yin.k/pc p :yin.k/layout sequence :yin.k/slice (count region)}))
    (dead-prefix! cx p excess)
    (let [pairs (into []
                      (keep (fn [[x v]] (when (contains? live x) [x (canonical-value cx v)])))
                      (map vector sequence (take-top region (count sequence))))]
      (doseq [[x entries] (group-by first pairs)
              :when (< 1 (count entries))]
        (when-not (apply = (map second entries))
          (refuse! :yin.k/non-portable :stack-layout {:yin.k/pc p :yin.k/register x})))
      (into {} pairs))))


(defn lift-running
  "A running kernel's live fields as the canonical run payload (§4.2,
   §4.3): `{:segment A :pc p :env E :window W :k K}`, no delivery record,
   no pending. Register: the window is `L(p)` read off the register
   file. Stack: the pc relabels a `:push` stutter to its successor, and
   the window is the current region's per `region-window`. Any pc
   inside a body lifts; a pc outside every body refuses
   `:yin.k/not-at-safepoint`."
  [cx fields]
  (if (= :stack (:kind cx))
    (let [st (vec (:stack fields))
          frames (vec (:continuation fields))
          bases (mapv :stack-base frames)
          _ (when-not (and (every? integer? bases) (apply <= 0 (conj bases (count st))))
              (refuse! :yin.k/non-portable :stack-base {:yin.k/bases bases}))
          parts (slices st bases)
          [seg hpc] (image-pc :stack (:kernel cx) (:space cx) (:pc fields))
          pc (h->a seg hpc true)
          _ (safepoint! seg pc)]
      {:segment (:address seg) :pc pc
       :env (canonical-env cx seg pc (:frames fields) (:store-of fields))
       :window (region-window cx seg pc (peek parts))
       :k (stack-k-frames cx frames parts)})
    (let [[seg pc] (image-pc :register (:kernel cx) (:space cx) (:pc fields))
          _ (safepoint! seg pc)]
      {:segment (:address seg) :pc pc
       :env (canonical-env cx seg pc (:frames fields) (:store-of fields))
       :window (register-run-window cx seg pc (map-indexed vector (vec (:registers fields)))
                                    (analysis/live (:analysis seg) pc))
       :k (mapv #(register-kframe cx %) (:continuation fields))})))


(defn lift-state
  "A kernel's wait entry, parked record or reified payload as the
   canonical act or tail payload (§4.2, §4.3), engine fields carried."
  [cx entry]
  (let [fields (apply dissoc entry kernel-keys)]
    (merge (cond-> fields (contains? fields :datom) (update :datom #(canonical-value cx %)))
           (if (= :stack (:kind cx)) (lift-stack cx entry) (lift-register cx entry)))))


(defn lift-context
  "The lift context of kernel `k` over `space`; positional closures and
   kernel continuations are reminted under `owner`."
  [k space owner]
  (assoc {} :kind (kernel-kind k) :kernel k :space space :free-env (or (:free-env k) {}) :owner owner))


;; =============================================================================
;; Lower: a canonical act or tail into a kernel
;; =============================================================================

(defn- lower-frames
  [lx seg pc env]
  (mapv (fn [f] (mapv #(positional-value lx %) f)) (frames-of seg pc env)))


(defn- payload-segment
  [lx p]
  (segment-of (:space lx) (address-of lx (:segment p))))


(defn- lower-regs
  "`(sort-by first [[phys(v) V] …])` over window `w`."
  [lx seg site w]
  (vec (sort-by first (map (fn [[x v]] [(phys seg site x) (positional-value lx v)]) w))))


(defn- register-k
  [lx k]
  (mapv (fn [f]
          (let [seg (payload-segment lx f)
                off (offset :register (:kernel lx) seg)
                pc (:pc f)
                regs (lower-regs lx seg (dec pc) (:window f))
                store-of (engine/env-store-of (:env f))]
            (cond-> {:site-pc (+ off pc -1) :return-pc (+ off pc)
                     :frames (lower-frames lx seg pc (:env f))
                     :regs regs :live (mapv first regs)
                     :dest (phys seg (dec pc) (:rd f))}
              (some? store-of) (assoc :store-of store-of))))
        k))


(declare run-fields)


(defn- lower-register
  [lx p fill]
  (let [k (:kernel lx)
        seg (payload-segment lx p)
        off (offset :register k seg)
        common (cond-> {:segment (:segment k) :format register/format-tag :hash (:hash k)
                        :image (:register-id seg) :continuation (register-k lx (:k p))}
                 (< 1 (count (:images k))) (assoc :images (:images k)))]
    (cond
      (contains? p :site)
      (cond-> (merge common {:site-pc (+ off (:site p)) :pc (+ off (:site p) 1)
                             :frames (get fill :frames []) :regs [] :live [] :dest nil
                             :resume-mode :return-result})
        (some? (:store-of fill)) (assoc :store-of (:store-of fill)))

      (contains? p :deliver)
      (let [pc (:pc p)
            regs (lower-regs lx seg (dec pc) (:window p))
            store-of (engine/env-store-of (:env p))]
        (cond-> (merge common {:site-pc (+ off pc -1) :pc (+ off pc)
                               :frames (lower-frames lx seg pc (:env p))
                               :regs regs :live (mapv first regs)
                               :dest (phys seg (dec pc) (get-in p [:deliver :rd]))
                               :resume-mode :write-result})
          (some? store-of) (assoc :store-of store-of)))

      :else (merge common (run-fields lx p seg)))))


(defn- layout-slice
  "`(mapv W (stack-layout (dec pc)))`, every member present in W."
  [lx seg pc w]
  (mapv (fn [x]
          (when-not (contains? w x)
            (refuse! :yin.k/undecodable :window-missing {:yin.k/pc pc :yin.k/register x}))
          (positional-value lx (get w x)))
        (stack-layout seg (dec pc))))


(defn- stack-k
  "`[frames stack]`: each return frame's slice appended in order, its base
   the cumulative length through its own slice (§4.3), `b(i) = b(i-1) +
   |slice(i)|`."
  [lx k]
  (reduce (fn [[frames st] f]
            (let [seg (payload-segment lx f)
                  pc (:pc f)
                  st (into st (layout-slice lx seg pc (:window f)))
                  store-of (engine/env-store-of (:env f))]
              [(conj frames (cond-> {:return-pc (+ (offset :stack (:kernel lx) seg) (a->h seg (dec pc)) 1)
                                     :frames (lower-frames lx seg pc (:env f))
                                     :stack-base (count st)}
                              (some? store-of) (assoc :store-of store-of)))
               st]))
          [[] []]
          k))


(defn- region-slice
  "The lowered current region at run pc `p`: `layout(p) ++ uses(p)` in
   emitter order, write-back-style nil for a dead-ambient layout entry
   outside `L(p)` — never read again, so its value is the window's
   absence — and no dead prefix (§4.3: the lower produces none)."
  [lx seg p w]
  (mapv (fn [x] (if (contains? w x) (positional-value lx (get w x)) nil))
        (into (stack-layout seg p) (code/uses (inst seg p)))))


(defn- run-fields
  "The live fields of the receiving kernel for run payload `p` of `seg`:
   no `:resume-mode`, no delivery — the kernel is between instructions.
   Nothing runs, nothing is delivered."
  [lx p seg]
  (let [store-of (engine/env-store-of (:env p))]
    (cond-> (if (= :stack (:kind lx))
              (let [k (:kernel lx)
                    [frames st] (stack-k lx (:k p))
                    pc (:pc p)]
                {:pc (+ (offset :stack k seg) (a->h seg pc))
                 :frames (lower-frames lx seg pc (:env p))
                 :stack (into st (region-slice lx seg pc (:window p)))
                 :continuation frames})
              (let [k (:kernel lx)
                    pc (:pc p)
                    body (nth (get-in seg [:register :bodies]) (body-of seg pc))]
                {:pc (+ (offset :register k seg) pc)
                 :registers (reduce (fn [rs [s v]] (assoc rs s v))
                                    (vec (repeat (:registers body) nil))
                                    (lower-regs lx seg pc (:window p)))
                 :frames (lower-frames lx seg pc (:env p))
                 :continuation (register-k lx (:k p))}))
      (some? store-of) (assoc :store-of store-of))))


(defn lower-running
  "The inverse of `lift-running`: canonical run payload `p` as the
   receiving kernel's live fields. The register kernel's file is nil
   outside `L(p)`'s image (write-back's discipline); the stack kernel's
   region carries `region-slice`, its bases recomputed cumulatively by
   `stack-k`."
  [lx p]
  (run-fields lx p (payload-segment lx p)))


(defn- lower-stack
  [lx p fill]
  (let [k (:kernel lx)
        seg (payload-segment lx p)
        off (offset :stack k seg)
        [frames st] (stack-k lx (:k p))
        common {:segment (:segment k) :format stack/format-tag :hash (:hash k) :image (:stack-id seg)
                :continuation frames}]
    (cond
      (contains? p :site)
      (cond-> (merge common {:pc (+ off (a->h seg (:site p)) 1) :resume-mode :return-result
                             :stack st :frames (get fill :frames [])})
        (some? (:store-of fill)) (assoc :store-of (:store-of fill)))

      (contains? p :deliver)
      (let [pc (:pc p)
            store-of (engine/env-store-of (:env p))]
        (cond-> (merge common {:pc (+ off (a->h seg (dec pc)) 1)
                               :frames (lower-frames lx seg pc (:env p))
                               :stack (into st (layout-slice lx seg pc (:window p)))})
          (some? store-of) (assoc :store-of store-of)))

      :else (merge common (run-fields lx p seg)))))


(def ^:private canonical-keys
  #{:segment :pc :site :env :window :k :deliver :type :id})


(defn lower-state
  "Canonical act or tail payload `p` as the receiving kernel's entry
   (§4.2, §4.3), engine fields carried. `fill` gives a lowered tail's
   `:frames`/`:store-of` (default `[]`/nil), which the return transition
   replaces unread (§5.6 item 8)."
  ([lx p] (lower-state lx p {}))
  ([lx p fill]
   (let [fields (apply dissoc p canonical-keys)]
     (merge (cond-> fields (contains? fields :datom) (update :datom #(positional-value lx %)))
            (if (= :stack (:kind lx)) (lower-stack lx p fill) (lower-register lx p fill))))))


(defn lower-context
  "The lower context into kernel `k` over `space`; `address` maps a
   payload's or closure's `:segment` to A (default: it is A)."
  ([k space] (lower-context k space identity))
  ([k space address]
   (assoc {} :kind (kernel-kind k) :kernel k :space space :free-env (or (:free-env k) {})
          :owner (:owner k) :address address)))


;; =============================================================================
;; Tasks
;; =============================================================================

(defn- holds-procedure?
  "Whether kernel value `x` is or holds, through guest collections, a
   closure or continuation."
  [x]
  (cond
    (or (values/closure? x) (values/continuation? x)) true
    (values/host-typed? x) false
    (map? x) (boolean (some holds-procedure? (concat (keys x) (vals x))))
    (coll? x) (boolean (some holds-procedure? x))
    :else false))


(def ^:private live-fields
  "The kernel fields a running activation holds: register
   `{:pc :registers :frames :continuation :store-of}`, stack
   `{:pc :stack :frames :continuation :store-of}`."
  {:register [:pc :registers :frames :continuation :store-of]
   :stack [:pc :stack :frames :continuation :store-of]})


(defn canonical-task
  "Kernel `k` presented as semantic machine `template` (a fresh machine
   of the same composition) for `rows/lift-task`: code by A from
   `space`, every wait, parked record and value reconstructed through
   the layout; resources, stores and counters carried. A running kernel
   lifts as `:running`: `lift-running` over its live fields presents the
   control, window, env and K, with waits and ready lifted as the
   semantic lift does; install children are not carried by this phase's
   layouts (refused `:yin.k/unsupported :installs`); a closure or
   continuation in the free environment has no finite canonical form
   (§6.3). opts as `lift-task`'s (its `:diagnostics` sink receives the
   dead-prefix counts). Single-arity: cljd's compiler NPEs on a
   multi-arity defn holding destructuring fns."
  [k space template opts]
  (when (seq (:installs k))
    (refuse! :yin.k/unsupported :installs {:yin.k/names (vec (keys (:installs k)))}))
  (when-let [sym (some (fn [[sym x]] (when (holds-procedure? x) sym)) (sort-by (comp str key) (:free-env k)))]
    (refuse! :yin.k/non-portable :free-env-value {:yin.k/key sym}))
  (let [cx (assoc (lift-context k space (:owner template)) :diagnostics (:diagnostics opts))
        conv #(canonical-value cx %)
        running (when (engine/active-continuation? k)
                  (lift-running cx (select-keys k (get live-fields (kernel-kind k)))))
        parked (into {} (map (fn [[id rec]] [id (assoc (lift-state cx rec) :type :parked-continuation :id id)]))
                     (:parked k))
        value (:value k)
        parked-value? (and (not running) (map? value) (= :parked-continuation (:type value))
                           (contains? parked (:id value)))
        code (into {} (map (fn [[a s]] [a (assoc {} :vector (:vector s) :address a :analysis (:analysis s))]))
                   (:segments space))]
    (assoc template
           :code code
           :code-aliases (into {} (map (fn [a] [a a])) (keys code))
           :program nil
           :control (when running (select-keys running [:segment :pc]))
           :window (if running (:window running) {})
           :env (if running (:env running) (or (:free-env k) {}))
           :k (when running (:k running))
           :resources (:resources k)
           :capability-secret (:capability-secret k)
           :free-env (:free-env k)
           :store (conv (:store k))
           :module-stores (conv (or (:module-stores k) {}))
           :parked parked
           :wait-set (mapv #(lift-state cx %) (:wait-set k))
           :ready-queue (mapv (fn [e]
                                (let [state (lift-state cx (dissoc e :value :status :resource-updates :refusal))
                                      state (assoc state :value (conv (:value e)) :status (:status e)
                                                   :resource-updates (:resource-updates e))]
                                  (if (contains? e :refusal)
                                    (assoc state :refusal (conv (:refusal e)))
                                    state)))
                              (:ready-queue k))
           :value (if running nil (if parked-value? (get parked (:id value)) (conv value)))
           :id-counter (:id-counter k)
           :installs {}
           :halted? (:halted? k)
           :blocked? (:blocked? k))))


(defn lift-task
  "§7.1 for a de Bruijn kernel: `rows/lift-task` over its canonical
   presentation. `template` is a fresh semantic machine of the
   kernel's composition; opts as rows/lift-task's, plus `:diagnostics`,
   an atom receiving the lift's dead-prefix records (never the frame)."
  [k space template serve! opts]
  (rows/lift-task (canonical-task k space template opts) serve! opts))


(defn attach-space
  "Kernel `k` holding the derived image of every segment of `space`, in
   A order (§7.2: derive and attach the image for each A)."
  [k space]
  (let [kind (kernel-kind k)]
    (reduce (fn [k a]
              (let [seg (segment-of space a)]
                (if (= :stack kind)
                  (stack/attach-image k (:stack seg) vm/stack-contract)
                  (register/attach-image k (:register seg) vm/register-contract))))
            k
            (sort-by str (keys (:segments space))))))


(defn machine-space
  "The space of the A vectors semantic machine `m` holds, and
   the map from its segment ids to A."
  [m]
  [(space (map :vector (vals (:code m))))
   (fn [seg] (or (get-in m [:code seg :address]) (when (contains? (:code-aliases m) seg) seg)))])


(defn kernel-task
  "Step 12 for a de Bruijn receiver: semantic machine `m` (what
   `rows/lower-task` restored) as fresh kernel `k` of the same
   composition: every image derived and attached, the free environment
   set, every wait, parked record, ready entry and value lowered through
   the layout; a `:running` machine lowers into a running kernel
   (`lower-running` over its control, window, env and K). Delivers
   nothing, runs nothing."
  [m k]
  (let [[sp address] (machine-space m)
        k (attach-space k sp)
        lx (lower-context k sp address)
        conv #(positional-value lx %)
        parked (into {} (map (fn [[id rec]] [id (assoc (lower-state lx rec) :type :parked-continuation :id id)]))
                     (:parked m))
        value (:value m)
        parked-value? (and (map? value) (= :parked-continuation (:type value)) (contains? parked (:id value)))
        running (when-let [{:keys [segment pc]} (:control m)]
                  (lower-running lx {:segment segment :pc pc :env (:env m) :window (:window m) :k (:k m)}))]
    (assoc k
           :resources (:resources m)
           :free-env (:free-env m)
           :store (conv (:store m))
           :module-stores (conv (or (:module-stores m) {}))
           :parked parked
           :wait-set (mapv #(lower-state lx %) (:wait-set m))
           :ready-queue (mapv (fn [e]
                                (let [entry (lower-state lx (dissoc e :value :status :resource-updates :refusal))
                                      entry (assoc entry :value (conv (:value e)) :status (:status e)
                                                   :resource-updates (:resource-updates e))]
                                  (if (contains? e :refusal)
                                    (assoc entry :refusal (conv (:refusal e)))
                                    entry)))
                              (:ready-queue m))
           :value (if running nil (if parked-value? (get parked (:id value)) (conv value)))
           :id-counter (:id-counter m)
           :halted? (if running false (:halted? m))
           :blocked? (if running false (:blocked? m))
           :pc (or (:pc running) (:pc k))
           :frames (or (:frames running) (:frames k))
           :continuation (or (:continuation running) (:continuation k))
           :store-of (if running (get running :store-of) (:store-of k))
           :registers (if (= :register (:kind lx))
                        (get running :registers (:registers k))
                        (:registers k))
           :stack (if (= :stack (:kind lx))
                    (get running :stack (:stack k))
                    (:stack k)))))


(defn- lexical-names
  "The names the chain binds at `pc` of `v`."
  [v pc]
  (let [bodies (code/body-ranges v)]
    (loop [b (code/body-index bodies pc) out #{}]
      (if-let [owner (:owner (nth bodies b))]
        (recur (code/body-index bodies owner) (into out (nth (nth v owner) 2)))
        out))))


(declare state-prim)


(defn- value-prim
  "The path to the first `:prim` row carrier value `x` reaches as kernel
   payload data: through guest collections, a closure's lexical bindings
   (its positional frames) and a capture's state; or nil."
  [code x path]
  (when (and (vector? x) (= :row (first x)))
    (let [n (second x) ss (:slots n)]
      (case (:kind n)
        :prim path
        (:vec :list :set :map) (some (fn [[i y]] (value-prim code y (conj path i))) (map-indexed vector ss))
        :closure (let [[a entry _ bindings] ss
                       v (get code a)
                       owner (when v (some (fn [pc]
                                             (let [t (nth v pc)]
                                               (when (and (= :closure (nth t 0)) (= entry (nth t 3))) pc)))
                                           (range (count v))))
                       names (if owner (lexical-names v owner) #{})]
                   (some (fn [[s y]] (when (contains? names s) (value-prim code y (conj path s))))
                         (partition 2 (:slots bindings))))
        :reified (state-prim code (first ss) (conj path 0))
        nil))))


(defn- frame-prim
  "A frame's window and lexical bindings (§4.4: the free part is the
   kernel's :free-env, never payload data)."
  [code a pc e w path]
  (let [names (lexical-names (get code a) pc)]
    (or (some (fn [[r y]] (value-prim code y (conj path :window r))) (partition 2 (:slots w)))
        (some (fn [[sym y]] (when (contains? names sym) (value-prim code y (conj path :env sym))))
              (partition 2 (:slots (first (:slots e))))))))


(defn- state-prim
  [code n path]
  (let [kframes (fn [k]
                  (some (fn [[i f]]
                          (let [[a pc e w] (:slots f)]
                            (frame-prim code a pc e w (conj path :k i))))
                        (map-indexed vector (:slots k))))
        [a pc e w k] (:slots n)]
    (case (:kind n)
      (:act :run) (or (frame-prim code a pc e w path) (kframes k))
      :tail (kframes e)
      nil)))


(defn- row-procedure
  "The path to the first closure or continuation row carrier value `x`
   holds, through guest collections; or nil."
  [x path]
  (when (and (vector? x) (= :row (first x)))
    (let [n (second x)]
      (case (:kind n)
        (:closure :reified) path
        (:vec :list :set :map) (some (fn [[i y]] (row-procedure y (conj path i))) (map-indexed vector (:slots n)))
        nil))))


(defn- free-env-procedure
  "§6.3: a de Bruijn kernel resolves free names through its one
   `free-env` while a canonical closure snapshots its `env`, so a closure
   or continuation held in the free environment has no finite canonical
   form there. The path `[:free-env sym …]` to the first such value of
   decoded task `task`, or nil."
  [task]
  (some (fn [[sym x]] (row-procedure x [:free-env sym]))
        (partition 2 (:slots (nth (:slots task) 5)))))


(defn- image-collision
  "§4.1: `[ident #{A …}]` for the first image of kind `kind` two carried A
   derive, or nil. A de Bruijn kernel attaches one image once, so it
   cannot hold both segments; no enumeration order selects an A. A
   vector that derives no image is left to the carrier's own checks."
  [code kind]
  (let [ident (fn [a v]
                (try
                  (if (= :stack kind)
                    (dcode/image-hash (:image (derive/stack-image v)))
                    (rcode/register-hash (derive/register-image v)))
                  (catch #?(:cljd Object :clj Throwable :cljs :default) _ [::underivable a])))
        by-image (reduce (fn [acc [a v]] (update acc (ident a v) (fnil conj #{}) a)) {} code)]
    (some (fn [[i as]] (when (< 1 (count as)) [i as])) (sort-by (comp str key) by-image))))


(defn lower-defect
  "What a kernel of `kind` cannot hold, judged on decoded task `task`
   before anything is attached: two carried A of one image (§4.1), install
   children (not carried by this phase's layouts), a closure or
   continuation in the free environment (§6.3), a retained FFI request
   whose response pair the backend's re-park cannot preserve, and for the register
   kernel a primitive function in the payload data of the run, a wait, a
   ready entry (its state, value, refusal or value-bearing completion
   field) or a parked state (a window, a lexical binding, or reached
   through them), which
   its restore refuses as `:continuation-registers` (`vm/machine-data?`).
   nil when it can."
  [task kind]
  (let [slots (:slots task)
        code (nth slots 11)
        collision (image-collision code kind)
        free (free-env-procedure task)]
    (cond
      collision
      (assoc {} :yin.k/status :yin.k/unsupported :yin.k/kind :ambiguous-image
             :yin.k/image (first collision) :yin.k/segments (second collision))

      (seq (nth slots 12))
      (assoc {} :yin.k/status :yin.k/unsupported :yin.k/kind :installs)

      free
      (assoc {} :yin.k/status :yin.k/unsupported :yin.k/kind :free-env-value :yin.k/path free)

      (some (fn [w] (= :ffi-request (first (:slots (second (:slots w))))))
            (nth slots 13))
      (assoc {} :yin.k/status :yin.k/unsupported :yin.k/kind :retained-ffi)

      (= :register kind)
      (when-let [path (or (some (fn [[i r]] (state-prim code r [:run i])) (map-indexed vector (nth slots 17)))
                          (some (fn [[i r]]
                                  (let [[st c v _ _ refusal] (:slots r)]
                                    (or (state-prim code st [:ready i :state])
                                        (value-prim code v [:ready i :value])
                                        (when refusal (value-prim code refusal [:ready i :refusal]))
                                        (some (fn [[j d x]]
                                                (when (= :V d) (value-prim code x [:ready i :completion j])))
                                              (map vector (range) (into [:D] (get rows/completion-table (first (:slots c)))) (:slots c))))))
                                (map-indexed vector (nth slots 18)))
                          (some (fn [[i w]] (state-prim code (first (:slots w)) [:frames i]))
                                (map-indexed vector (nth slots 13)))
                          (some (fn [[p st]] (state-prim code st [:parked p]))
                                (partition 2 (:slots (nth slots 8)))))]
        (assoc {} :yin.k/status :yin.k/unsupported :yin.k/kind :continuation-registers :yin.k/path path))

      :else nil)))


(defn lower-task
  "§7.2 into a de Bruijn kernel: decode `wire`, refuse what kernel `k`
   cannot hold (`lower-defect`) before any attach, then `rows/lower-task`
   into `scratch` (a fresh semantic machine of the same
   composition: the carrier's steps 1-11 and restore) and `kernel-task`
   into `k`. Throws the first refusal as ex-data."
  ([scratch k wire attach!] (lower-task scratch k wire attach! {}))
  ([scratch k wire attach! opts]
   (let [task (rows/decode wire (merge rows/default-budget (:budget opts)))]
     (when (= :task (:kind task))
       (when-let [d (lower-defect task (kernel-kind k))]
         (throw (ex-info "Layout refused" d))))
     (kernel-task (rows/lower-task scratch wire attach! opts) k))))
