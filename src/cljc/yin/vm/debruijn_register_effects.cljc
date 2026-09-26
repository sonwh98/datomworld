(ns yin.vm.debruijn-register-effects
  "R2 (docs/design/yin.vm.debruijn.register.md S5.2): pure effect
   descriptors, sparse continuation snapshots, and wait-entry
   validators for the de Bruijn register VM.

   This namespace is pure data construction and validation only. It reads
   no host clock, stream, namespace registry, or mutable state."
  (:require [dao.stream.apply :as apply2]
            [yin.vm :as vm]
            [yin.vm.debruijn-register-code :as rcode]))


(def format-tag
  "The format identity keyword for register VM continuations."
  :yin.debruijn.register)


(def boundary-opcodes
  "The set of six instruction opcodes that form suspension or
   continuation boundaries."
  rcode/boundary-opcodes)


;; =============================================================================
;; 1. Effect descriptors (design section 5.2.1)
;; =============================================================================

(defn effect-descriptor
  "Purely construct a normalized engine effect descriptor from a register
   instruction and the current body's complete register vector, or nil when
   the instruction produces no engine effect.
   Precondition: `instruction` came from a validator-approved image."
  [instruction registers]
  (case (first instruction)
    :store-put
    (let [[_ _ key value] instruction]
      {:effect :vm/store-put, :key key, :val value})

    :stream-make
    (let [[_ _ capacity] instruction]
      {:effect :stream/make, :capacity capacity})

    :stream-put
    (let [[_ _ sr vr _live] instruction]
      {:effect :stream/put,
       :stream (nth registers sr),
       :val (nth registers vr)})

    :stream-cursor
    (let [[_ _ sr] instruction]
      {:effect :stream/cursor, :stream (nth registers sr)})

    :stream-next
    (let [[_ _ cr _live] instruction]
      {:effect :stream/next, :cursor (nth registers cr)})

    :stream-close
    (let [[_ _ sr] instruction]
      {:effect :stream/close, :stream (nth registers sr)})

    nil))


;; =============================================================================
;; 2. Continuation payload construction (design section 5.2.2)
;; =============================================================================

(defn image-row
  "The row of offset table `images` (`[[identity offset length] ...]`,
   yin.vm.linker.md section 7.3) whose range holds instruction `pc`; a pc
   one past the end of the last row falls in that row."
  [images pc]
  (or (some (fn [[_ off len :as row]]
              (when (and (<= off pc) (< pc (+ off len))) row))
            images)
      (let [[_ off len :as row] (peek images)]
        (when (and row (= pc (+ off len))) row))))


(defn continuation-payload
  "Construct a canonical sparse register continuation payload for a
   suspending or boundary instruction. Extracts only live registers in
   strictly ascending index order.
   `runtime` must contain: `:segment`, `:hash`, `:pc`, `:frames`,
   `:registers`, and `:continuation`; its offset table `:images` gives
   `:image`, the identity of the row the resume pc falls in, which is
   what `register-restore` checks (r6)."
  [runtime instruction]
  (let [op (first instruction)]
    (when-not (boundary-opcodes op)
      (throw (ex-info "Instruction is not a continuation boundary"
                      {:rule :continuation-site, :pc (:pc runtime)})))
    (let [site-pc (:pc runtime)
          live-idx (rcode/live-slot-index op)
          live (nth instruction live-idx)
          tail? (and (= :call op) (true? (nth instruction 4)))
          dest (if tail? nil (nth instruction 1))
          resume-mode (if tail? :return-result :write-result)
          regs (mapv (fn [r] [r (nth (:registers runtime) r)]) live)
          images (:images runtime)]
      (cond-> {:segment (:segment runtime),
               :site-pc site-pc,
               :pc (inc site-pc),
               :frames (:frames runtime),
               :regs regs,
               :live live,
               :continuation (:continuation runtime),
               :dest dest,
               :resume-mode resume-mode,
               :format format-tag,
               :hash (:hash runtime),
               :image (nth (image-row images (inc site-pc)) 0 nil)}
        ;; a code space grown by `attach-image` is a concatenation of
        ;; images, each admitted alone: the table travels so each can be
        ;; checked alone (`continuation-defect`)
        (< 1 (count images)) (assoc :images images)))))


;; =============================================================================
;; 3. Continuation payload defect validation (design section 5.2.2)
;; =============================================================================

(defn- nonneg-int?
  [x]
  (and (integer? x) (not (neg? x))
       #?(:cljs (js/Number.isSafeInteger x) :default true)))


(defn- ascending-distinct?
  [xs]
  (every? (fn [[a b]] (< a b)) (partition 2 1 xs)))


(defn- body-of-pc
  [{:keys [bodies]} pc]
  (some (fn [[i {:keys [start end]}]]
          (when (and (<= start pc) (<= pc end)) i))
        (map-indexed vector bodies)))


(defn- valid-regs-shape?
  [regs]
  (and (vector? regs)
       (every? (fn [pair]
                 (and (vector? pair)
                      (= 2 (count pair))
                      (nonneg-int? (nth pair 0))))
               regs)))


(defn- return-frame-defect
  "The first defect of one return `frame` of a payload whose code space
   is `segment`. A frame carries no code space of its own
   (yin.vm.linker.md section 7.3, r7): its absolute pcs are checked
   against the payload's."
  [segment frame frame-idx]
  (if-not (map? frame)
    {:rule :continuation-continuation, :frame-index frame-idx}
    (let [{:keys [site-pc return-pc frames regs live dest]} frame]
      (or (when (not= format-tag (:format frame format-tag))
            {:rule :continuation-format, :frame-index frame-idx})
          (let [insts (:instructions segment)
                len (count insts)]
            (or (when-not (and (nonneg-int? site-pc) (< site-pc len))
                  {:rule :continuation-pc, :frame-index frame-idx})
                (let [inst (nth insts site-pc)
                      op (first inst)]
                  (or (when-not (= :call op)
                        {:rule :continuation-pc, :frame-index frame-idx})
                      (when (true? (nth inst 4))
                        {:rule :continuation-pc, :frame-index frame-idx})
                      (let [body-idx (body-of-pc segment site-pc)
                            body (nth (:bodies segment) body-idx)
                            call-rd (nth inst 1)
                            call-live (nth inst 5)]
                        (or (when-not (and (= (inc site-pc) return-pc)
                                           (= body-idx
                                              (body-of-pc segment return-pc)))
                              {:rule :continuation-pc, :frame-index frame-idx})
                            (when-not (= call-rd dest)
                              {:rule :continuation-destination,
                               :frame-index frame-idx})
                            (when-not (< dest (:registers body))
                              {:rule :continuation-destination,
                               :frame-index frame-idx})
                            (when-not (= call-live live)
                              {:rule :continuation-live,
                               :expected call-live,
                               :actual live,
                               :frame-index frame-idx})
                            (when-not (valid-regs-shape? regs)
                              {:rule :continuation-registers,
                               :frame-index frame-idx})
                            (when-not (= live (mapv (fn [p] (nth p 0)) regs))
                              {:rule :continuation-registers,
                               :frame-index frame-idx})
                            (when-not (every? (fn [p]
                                                (vm/plain-data? (nth p 1)))
                                              regs)
                              {:rule :continuation-registers,
                               :frame-index frame-idx})
                            (when-not (and (vector? frames)
                                           (every? vector? frames)
                                           (vm/plain-data? frames))
                              {:rule :continuation-frames,
                               :frame-index frame-idx})))))))))))


(defn- unrelocate
  "Shift every `:pc`-kind operand of `inst` back by `offset`."
  [offset inst]
  (reduce (fn [inst [i [_ kind]]]
            (if (= :pc kind) (update inst (inc i) - offset) inst))
          inst
          (map-indexed vector (get rcode/opcode-table (nth inst 0)))))


(defn- image-slice
  "The image an offset-table row `[identity offset length]` names inside
   the concatenated `segment`, relocated back to its own pc 0."
  [{:keys [bodies instructions]} [_ off len]]
  {:bodies (into []
                 (comp (filter #(and (<= off (:start %))
                                     (< (:start %) (+ off len))))
                       (map #(-> %
                                 (update :start - off)
                                 (update :end - off))))
                 bodies),
   :instructions (mapv #(unrelocate off %)
                       (subvec instructions off (+ off len)))})


(defn- table-defect
  "The first defect of offset table `images` as a description of a code
   space of `n` instructions: every row is `[identity offset length]`,
   the rows are in offset order, each starts where the one before it
   ends (the first at 0), and together they cover all `n` instructions
   -- no gap, no overlap, no instruction outside every row."
  [n images]
  (if-not (and (sequential? images)
               (seq images)
               (every? #(and (vector? %) (= 3 (count %))) images))
    {:rule :image-table}
    (loop [rows (seq images)
           expected 0]
      (if-let [[_ off len :as row] (first rows)]
        (if (and (nonneg-int? off) (nonneg-int? len) (= expected off)
                 (<= (+ off len) n))
          (recur (next rows) (+ off len))
          {:rule :image-row, :row row})
        (when-not (= expected n)
          {:rule :image-coverage, :covered expected, :length n})))))


(defn- code-space-defect
  "The first defect of a payload's code space: the one image it holds,
   or, for a concatenation `attach-image` grew (the payload carries the
   offset table), the table's exact coverage of the instructions and
   each image alone, which must carry its row's identity -- a
   zero-length row (the empty base image of a VM built with no code)
   included."
  [segment images]
  (if (some? images)
    (or (table-defect (count (:instructions segment)) images)
        (when-let [d (rcode/register-image-defect segment)]
          (when-not (= :terminator (:rule d)) d))
        (some (fn [[ident _ len :as row]]
                (let [image (image-slice segment row)]
                  (or (when (pos? len) (rcode/register-image-defect image))
                      (when-not (= ident (rcode/register-hash image))
                        {:rule :image-row, :row row}))))
              images))
    (rcode/register-image-defect segment)))


(defn continuation-defect
  "Return nil when `payload` is a valid sparse register continuation payload,
   or the first deterministic defect map.
   Does not throw on malformed input."
  [payload]
  (if-not (map? payload)
    {:rule :continuation-shape}
    (or (when (not= format-tag (:format payload))
          {:rule :continuation-format})
        (when-let [d (code-space-defect (:segment payload)
                                        (:images payload))]
          (assoc d :rule :continuation-segment))
        (let [expected-h (rcode/register-hash (:segment payload))]
          (when (not= expected-h (:hash payload))
            {:rule :continuation-hash,
             :expected expected-h,
             :actual (:hash payload)}))
        (let [{:keys [segment site-pc pc resume-mode live regs dest frames
                      continuation]} payload
              insts (:instructions segment)
              len (count insts)]
          (or (when-not (and (nonneg-int? site-pc) (< site-pc len))
                {:rule :continuation-pc})
              (let [inst (nth insts site-pc)
                    op (first inst)]
                (or (when-not (boundary-opcodes op)
                      {:rule :continuation-pc})
                    (let [body-idx (body-of-pc segment site-pc)
                          body (nth (:bodies segment) body-idx)
                          tail? (and (= :call op) (true? (nth inst 4)))
                          expected-mode (if tail? :return-result :write-result)
                          inst-live (nth inst (rcode/live-slot-index op))]
                      (or (when-not (and (= (inc site-pc) pc)
                                         (= body-idx (body-of-pc segment pc)))
                            {:rule :continuation-pc})
                          (when-not (= expected-mode resume-mode)
                            {:rule :continuation-resume-mode})
                          (when-not (and (vector? live)
                                         (every? nonneg-int? live)
                                         (ascending-distinct? live)
                                         (every? #(< % (:registers body)) live))
                            {:rule :continuation-live})
                          (when-not (= inst-live live)
                            {:rule :continuation-live,
                             :pc site-pc,
                             :expected inst-live,
                             :actual live})
                          (when-not (valid-regs-shape? regs)
                            {:rule :continuation-registers})
                          (when-not (= live (mapv (fn [p] (nth p 0)) regs))
                            {:rule :continuation-registers})
                          (when-not (every? (fn [p]
                                              (vm/plain-data? (nth p 1)))
                                            regs)
                            {:rule :continuation-registers})
                          (if (= :write-result resume-mode)
                            (let [rd (nth inst 1)]
                              (or (when-not (= rd dest)
                                    {:rule :continuation-destination})
                                  (when-not (< dest (:registers body))
                                    {:rule :continuation-destination})
                                  (when (contains? (set live) dest)
                                    {:rule :continuation-destination})))
                            (when-not (and (nil? dest) tail?)
                              {:rule :continuation-destination}))
                          (when-not (and (vector? frames)
                                         (every? vector? frames)
                                         (vm/plain-data? frames))
                            {:rule :continuation-frames})
                          (when-not (vector? continuation)
                            {:rule :continuation-continuation})
                          (some (fn [[idx f]]
                                  (return-frame-defect segment f idx))
                                (map-indexed vector continuation)))))))))))


;; =============================================================================
;; 4. Wait-entry defect validation (design section 5.2.3)
;; =============================================================================

(def ^:private stale-keys
  [:value :status :cursor :resource-updates :stream
   :datom :type :id :request-sent :op])


(defn wait-entry-defect
  "Return nil when `entry` is a valid newly parked engine wait-set entry,
   or the first deterministic defect map.
   Validates the embedded continuation payload and shape-specific transport
   fields, and rejects stale wake keys."
  [entry]
  (or (continuation-defect entry)
      (let [shape (cond
                    (true? (:request-sent entry)) :ffi-writer
                    (contains? entry :call-id) :ffi-reader
                    (= :put (:reason entry)) :stream-writer
                    (= :next (:reason entry)) :stream-reader
                    :else nil)]
        (if-not shape
          {:rule :wait-shape}
          (let [prohibited-stale
                (case shape
                  :ffi-reader stale-keys
                  :stream-reader stale-keys
                  :stream-writer
                  [:value :status :cursor :resource-updates :stream
                   :type :id :request-sent :op]
                  :ffi-writer
                  [:value :status :cursor :resource-updates :stream
                   :type :id])
                present-stale (filterv #(contains? entry %) prohibited-stale)]
            (or (when (seq present-stale)
                  {:rule :wait-stale, :keys present-stale})
                (case shape
                  :stream-writer
                  (or (when-not (keyword? (:stream-id entry))
                        {:rule :wait-resource, :field :stream-id})
                      (when-not (contains? entry :datom)
                        {:rule :wait-resource, :field :datom})
                      (when-not (vm/plain-data? (:datom entry))
                        {:rule :wait-resource, :field :datom}))

                  :stream-reader
                  (or (when-not (keyword? (:stream-id entry))
                        {:rule :wait-resource, :field :stream-id})
                      (let [c (:cursor-ref entry)]
                        (when-not (and (map? c)
                                       (= :cursor-ref (:type c))
                                       (keyword? (:id c)))
                          {:rule :wait-resource, :field :cursor-ref})))

                  :ffi-writer
                  (or (when-not (= :put (:reason entry))
                        {:rule :wait-resource, :field :reason})
                      (when-not (= vm/call-in-stream-key (:stream-id entry))
                        {:rule :wait-resource, :field :stream-id})
                      (when-not (keyword? (:call-id entry))
                        {:rule :wait-resource, :field :call-id})
                      (when-not (keyword? (:op entry))
                        {:rule :wait-resource, :field :op})
                      (let [req (:datom entry)]
                        (or (when-not (apply2/request? req)
                              {:rule :wait-resource, :field :datom})
                            (when-not (= (:call-id entry)
                                         (apply2/request-id req))
                              {:rule :wait-resource, :field :call-id})
                            (when-not (= (:op entry) (apply2/request-op req))
                              {:rule :wait-resource, :field :op})
                            (when-not (vm/plain-data? (apply2/request-args req))
                              {:rule :wait-resource, :field :datom}))))

                  :ffi-reader
                  (or (when-not (= :next (:reason entry))
                        {:rule :wait-resource, :field :reason})
                      (when-not (= vm/call-out-stream-key (:stream-id entry))
                        {:rule :wait-resource, :field :stream-id})
                      (when-not (keyword? (:call-id entry))
                        {:rule :wait-resource, :field :call-id})
                      (let [c (:cursor-ref entry)]
                        (when-not (and (map? c)
                                       (= :cursor-ref (:type c))
                                       (= vm/call-out-cursor-key (:id c)))
                          {:rule :wait-resource, :field :cursor-ref}))))))))))
