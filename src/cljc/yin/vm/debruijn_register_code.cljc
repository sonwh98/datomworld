(ns yin.vm.debruijn-register-code
  "R1 (docs/design/yin.vm.debruijn.register.md S3): the register dimension
   (`:yin.debruijn.register/*`) and its validator. Mirrors `yin.vm.
   debruijn-code`'s role for the stack dimension, not its content: this is
   a new dimension with its own opcode table, its own image shape, and its
   own validator rules, over the SAME scalar encoder that dimension already
   defines.

   The register image is not a flat positional vector like the stack
   image (design S3's table row 'Register image: body ranges plus
   positional instructions'): it is `{:bodies [...], :instructions [...]}`.
   `:instructions` is the pc-indexed positional tuple vector, main body
   first then out-of-line lambda bodies in discovery order, exactly the
   layout `yin.vm.debruijn-linearize/lower-stack` already uses for the
   stack image. `:bodies` names each contiguous pc range's declared
   register file: body 0 is the main program (zero locals); every other
   body is a lambda's, `:locals` its declared arity (registers `0` ..
   `(dec locals)`, the L bank, section 4.2) and `:registers` its total
   register-file size (locals plus the allocator's own peak temporary
   count, the T bank). No instruction in this phase ever targets or reads
   an L register directly: `:load-bound` (including a depth-0, i.e.
   current-body, reference) always reads the runtime frame chain into a
   fresh temporary, unchanged from B3's `frame-value` convention (design
   section 4.2). L0..L(n-1) are reserved purely to keep the two banks
   disjoint, per 'locals are never coalesced with temporaries'; a future
   register kernel binds them from the call's actual arguments at body
   entry, outside this instruction stream.

   Every operand this dimension's opcode table declares -- including a
   bare register index, `:load-free`'s name, `:const`'s value, and
   `:call`'s ordered `arg-regs` vector -- is encoded through `yin.vm.
   debruijn-code/encode-scalar`: a register index or a jump/body-pc target
   is simply an integer in that encoder's `:long` class, `arg-regs` a
   `:vector` of them, and the mnemonic itself a `:keyword`. This reuses
   B1's scalar encoder for literally every byte this dimension ever
   writes, rather than reimplementing or forking any part of its framing
   -- so, unlike B1, this file defines no private hex or byte-framing
   helpers of its own at all; there is nothing left for them to do."
  (:require [dao.jing :as jing]
            [yin.vm :as vm]
            [yin.vm.debruijn-code :as debruijn-code]))


;; =============================================================================
;; Contract version -- the canonical source
;; =============================================================================
;; R0 (test/yin/vm/debruijn_register_contract_test.cljc) freezes its own
;; copy of this integer. Contract version 3 introduces the full R2
;; register effects instruction set, suspension and boundary live sets,
;; and sparse continuation formats.

(def contract-version
  "Bumped whenever this dimension's opcode table, allocation, scalar
   framing, or control-flow rules change shape (design section 3).
   3, as of design sections 4.4-4.6 and 5.2: the complete R2 register
   effects set, in-band live sets on all six boundary opcodes, and
   sparse continuation integration."
  3)


;; =============================================================================
;; The opcode table (design section 4.4)
;; =============================================================================
;; Every mnemonic's operand slots, in `yin.vm.debruijn-code/opcode-table`'s
;; own positional shape: an ordered vector of `[attribute kind]` pairs.

(def opcode-table
  "Design section 4.4's register instruction set, in positional operand-
   table form."
  {:const [[:yin.debruijn.register/rd :reg]
           [:yin.debruijn.register/value :data]]

   :load-bound [[:yin.debruijn.register/rd :reg]
                [:yin.debruijn.register/depth :uint]
                [:yin.debruijn.register/position :uint]]

   :load-free [[:yin.debruijn.register/rd :reg]
               [:yin.debruijn.register/name :sym]]

   :closure [[:yin.debruijn.register/rd :reg]
             [:yin.debruijn.register/arity :uint]
             [:yin.debruijn.register/body-pc :pc]]

   :move [[:yin.debruijn.register/rd :reg]
          [:yin.debruijn.register/rs :reg]]

   :call [[:yin.debruijn.register/rd :reg]
          [:yin.debruijn.register/fn-reg :reg]
          [:yin.debruijn.register/arg-regs :regs]
          [:yin.debruijn.register/tail? :bool]
          [:yin.debruijn.register/live :data]]

   :branch-false [[:yin.debruijn.register/cond-reg :reg]
                  [:yin.debruijn.register/target :pc]]

   :jump [[:yin.debruijn.register/target :pc]]

   :return [[:yin.debruijn.register/value-reg :reg]]

   :halt [[:yin.debruijn.register/value-reg :reg]]

   :store-get [[:yin.debruijn.register/rd :reg]
               [:yin.debruijn.register/key :data]]

   :store-put [[:yin.debruijn.register/rd :reg]
               [:yin.debruijn.register/key :data]
               [:yin.debruijn.register/value :data]]

   :gensym [[:yin.debruijn.register/rd :reg]
            [:yin.debruijn.register/prefix :str]]

   :stream-make [[:yin.debruijn.register/rd :reg]
                 [:yin.debruijn.register/capacity :uint]]

   :stream-put [[:yin.debruijn.register/rd :reg]
                [:yin.debruijn.register/stream-reg :reg]
                [:yin.debruijn.register/value-reg :reg]
                [:yin.debruijn.register/live :data]]

   :stream-cursor [[:yin.debruijn.register/rd :reg]
                   [:yin.debruijn.register/stream-reg :reg]]

   :stream-next [[:yin.debruijn.register/rd :reg]
                 [:yin.debruijn.register/cursor-reg :reg]
                 [:yin.debruijn.register/live :data]]

   :stream-close [[:yin.debruijn.register/rd :reg]
                  [:yin.debruijn.register/stream-reg :reg]]

   :ffi-call [[:yin.debruijn.register/rd :reg]
              [:yin.debruijn.register/ffi-op :kw]
              [:yin.debruijn.register/arg-regs :regs]
              [:yin.debruijn.register/live :data]]

   :current-continuation [[:yin.debruijn.register/rd :reg]
                          [:yin.debruijn.register/live :data]]

   :park [[:yin.debruijn.register/rd :reg]
          [:yin.debruijn.register/live :data]]

   :resume [[:yin.debruijn.register/parked-id :kw]
            [:yin.debruijn.register/value-reg :reg]]})


(def mnemonics
  (set (keys opcode-table)))


(def boundary-opcodes
  "Mnemonics that carry an in-band `:live` operand."
  #{:call :stream-put :stream-next :ffi-call
    :current-continuation :park})


(def live-slot-indices
  "Precomputed tuple index of the `:live` operand for each boundary opcode."
  {:call 5
   :stream-put 4
   :stream-next 3
   :ffi-call 4
   :current-continuation 2
   :park 2})


(defn live-slot-index
  "The 0-based tuple index of `:yin.debruijn.register/live` for `op`."
  [op]
  (get live-slot-indices op))


(def terminators
  "Instructions after which control never reaches pc + 1 within a body,
   mirroring `yin.vm.code/terminators` over this dimension's own mnemonics.
   `:jump`, `:branch-false`, and `:resume` never end a body; named here
   anyway so body-internal reachability checks have the full vocabulary."
  #{:jump :return :halt :resume})


;; =============================================================================
;; The descriptor
;; =============================================================================

(def descriptor
  "The `:yin.debruijn.register/*` dimension declared as data (design
   section 3): a contract version, arity, the opcode table, the encoding
   rule (which names B1's scalar domain by reference, not by copy: this
   dimension has no scalar table of its own), and the lift target."
  [[:yin.debruijn.register/dimension :dim/contract-version contract-version]
   [:yin.debruijn.register/dimension :dim/arity (count opcode-table)]
   [:yin.debruijn.register/dimension :dim/slots opcode-table]
   [:yin.debruijn.register/dimension
    :dim/encoding
    {:hash :sha256,
     :scalar-encoder :yin.debruijn-code/encode-scalar,
     :register-banks [:locals :temporaries],
     :map-set-order :canonical-encoded-bytes}]
   [:yin.debruijn.register/dimension :dim/lift-to [:yin.code/*]]])


;; =============================================================================
;; Canonical bytes and R (design section 3)
;; =============================================================================

(defn- mnemonic-of
  [t]
  (when (and (vector? t) (seq t)) (nth t 0)))


(defn- tuple-kinds
  [t]
  (mapv second (get opcode-table (mnemonic-of t))))


(defn- encode-tuple
  "One tuple's bytes: the mnemonic (a `:keyword` scalar) then every
   operand under its declared kind, every one of them routed through
   `debruijn-code/encode-scalar` -- a `:reg`/`:uint`/`:pc` operand as a
   `:long`, `:regs` as a `:vector` of them, `:bool`/`:sym`/`:data`
   directly. This reuses B1's encoder for the whole dimension; there is no
   operand kind here it does not already cover."
  [t]
  (let [kinds (tuple-kinds t)]
    (apply str
           (debruijn-code/encode-scalar (mnemonic-of t))
           (map-indexed
             (fn [i _kind]
               (debruijn-code/encode-scalar (nth t (inc i))))
             kinds))))


(defn encode-instructions
  [instructions]
  (apply str (map encode-tuple instructions)))


(defn- encode-body
  [{:keys [locals registers start end]}]
  (debruijn-code/encode-scalar [locals registers start end]))


(defn encode-register-image
  "The canonical register image's own bytes (design section 3): every
   declared body's `[locals registers start end]` in body order, then
   every pc-indexed tuple's bytes in pc order -- the two parts design
   section 3's table names ('body ranges plus positional instructions')."
  [{:keys [bodies instructions]}]
  (str (apply str (map encode-body bodies)) (encode-instructions instructions)))


(def descriptor-hash
  (jing/sha256 (debruijn-code/encode-scalar descriptor)))


(defn register-hash
  "The ONE function that computes R (design section 3): `R = sha256
   (register-descriptor-hash || canonical-register-vector)`. Received
   register bytes must be hashed before decoding, as B1's wire rule
   requires -- no receive path exists in this phase, but this function's
   own shape (bytes in, hash out, no host-value peeking) already supports
   that later use."
  [register-image]
  (jing/sha256 (str descriptor-hash (encode-register-image register-image))))


;; =============================================================================
;; body-liveness (design section 4.5)
;; =============================================================================
;; A standard backward dataflow, one pure function of the instruction
;; vector and the body ranges: per pc, use/def from the section 4.4
;; table, successors from the control-flow rule (a tail `:call` has none,
;; a non-tail `:call` falls through like any other instruction), then
;; `live-in(p) = use(p) + (live-out(p) - def(p))`, `live-out(p) = union
;; of live-in(s) over successors s`, iterated to a fixpoint. The fixpoint
;; is unique because the transfer functions are monotone over a finite
;; lattice (registers ordered by set inclusion), so it does not depend on
;; the order pcs are revisited in; pcs are still walked in descending
;; order within the body so every host repeats the identical work. Every
;; set is a Clojure sorted-set throughout -- its iteration order is
;; integer order on every host, so no host map/set iteration order can
;; reach the result. `live(call at p) = live-out(p) - {rd}`; this is
;; called uniformly for tail and non-tail calls alike, since a tail
;; call's empty successor set already makes its live-out, and so its
;; live set, the empty set.
;;
;; Used by both `yin.vm.debruijn-register-compile/lower-register`, to
;; fill the `:call` `live` operand after a body's instructions are
;; emitted, and by this namespace's own `live-exact-rule`, to recompute
;; and check it on every receiving host -- exactly one definition of
;; liveness in the format. The allocator's own free-list
;; (`yin.vm.debruijn-register-compile/allocate-temp!`/`free-temp!`) is a
;; FORWARD approximation made during allocation and is never consulted
;; here: `live` is this separate BACKWARD pass over the already-emitted
;; body.

(defn- successors-of
  [instructions pc]
  (let [t (nth instructions pc)]
    (case (nth t 0)
      :jump #{(nth t 1)}
      :branch-false #{(nth t 2) (inc pc)}
      (:return :halt :resume) #{}
      :call (if (nth t 4) #{} #{(inc pc)})
      #{(inc pc)})))


(defn- use-of
  [t]
  (case (nth t 0)
    :move #{(nth t 2)}
    :call (into #{(nth t 2)} (nth t 3))
    :branch-false #{(nth t 1)}
    (:return :halt) #{(nth t 1)}
    :stream-put #{(nth t 2) (nth t 3)}
    (:stream-cursor :stream-close) #{(nth t 2)}
    :stream-next #{(nth t 2)}
    :ffi-call (set (nth t 3))
    :resume #{(nth t 2)}
    #{}))


(defn- def-of
  [t]
  (case (nth t 0)
    (:const :load-bound :load-free :closure :move
            :store-get :store-put :gensym :stream-make
            :stream-put :stream-cursor :stream-next :stream-close
            :ffi-call :current-continuation :park)
    #{(nth t 1)}

    :call (if (nth t 4) #{} #{(nth t 1)})
    #{}))


(defn body-liveness
  "Design section 4.5's dataflow over one body of `image`
   (`{:bodies [...], :instructions [...]}`), identified by `body-index`
   -> `{boundary-pc live-vector}` for every boundary pc in that body, each
   `live-vector` the strictly ascending vector of register indices live
   after that boundary instruction returns, excluding its destination
   register if any.
   Pure: reads only `instructions` and the named body's `[start end]`."
  [{:keys [bodies instructions]} body-index]
  (let [{:keys [start end]} (nth bodies body-index)
        pcs (vec (range start (inc end)))
        total (count instructions)
        empty-sets (vec (repeat total (sorted-set)))]
    (loop [live-in empty-sets, live-out empty-sets]
      (let [[live-in' live-out' changed?]
            (reduce
              (fn [[in out changed?] pc]
                (let [t (nth instructions pc)
                      new-out (reduce (fn [s s-pc] (into s (nth in s-pc)))
                                      (sorted-set)
                                      (successors-of instructions pc))
                      new-in (into (use-of t) (reduce disj new-out (def-of t)))]
                  [(assoc in pc new-in) (assoc out pc new-out)
                   (or changed? (not= new-in (nth in pc))
                       (not= new-out (nth out pc)))]))
              [live-in live-out false]
              (rseq pcs))]
        (if changed?
          (recur live-in' live-out')
          (into {}
                (keep (fn [pc]
                        (let [t (nth instructions pc)
                              op (nth t 0)]
                          (when (boundary-opcodes op)
                            [pc (vec (disj (nth live-out' pc) (nth t 1)))]))))
                pcs))))))


;; =============================================================================
;; The validator (design section 3, section 4.2-4.3)
;; =============================================================================
;; `register-image-defect`: the sole admitter of a register image on this
;; path (mirroring B1's `image-defect`). Checks generic tuple shape first
;; (`nonempty`, `mnemonic-rule`, `arity-rule`, `operand-kind-rule`,
;; `target-bounds-rule` -- all mirroring B1's own rules of the same name,
;; over this dimension's own opcode table), then the checks new to this
;; format: `body-scope-rule` (every body range is well formed, every
;; closure's body pc names a real body of matching arity), `jump-scope-
;; rule` (every `:jump`/`:branch-false` target lies within its own
;; instruction's body, not merely within `target-bounds-rule`'s whole-
;; vector bound) and `register-bounds-rule` (every register operand is
;; within its own body's declared register count), the four live-set
;; rules of design section 4.5 (`live-shape-rule`, `live-bounds-rule`,
;; `live-tail-rule`, `live-exact-rule`), and `terminator-rule` (every
;; body ends `:return`, the main body `:halt`).

(defn- tuple-defect
  [rule pc]
  {:rule rule, :pc pc})


(defn- body-defect
  [rule body-index]
  {:rule rule, :body body-index})


(defn- nonempty
  [{:keys [instructions]}]
  (when-not (and (vector? instructions) (pos? (count instructions)))
    (tuple-defect :nonempty 0)))


(defn- mnemonic-rule
  [{:keys [instructions]}]
  (some (fn [pc]
          (when-not (contains? mnemonics (mnemonic-of (nth instructions pc)))
            (tuple-defect :mnemonic pc)))
        (range (count instructions))))


(defn- arity-rule
  [{:keys [instructions]}]
  (some (fn [pc]
          (let [t (nth instructions pc)]
            (when-not (= (inc (count (get opcode-table (mnemonic-of t))))
                         (count t))
              (tuple-defect :arity pc))))
        (range (count instructions))))


(defn- nonneg-int?
  [x]
  (and (integer? x) (not (neg? x))
       #?(:cljs (js/Number.isSafeInteger x) :default true)))


(def ^:private operand-kind-checks
  {:reg nonneg-int?,
   :uint nonneg-int?,
   :regs (fn [x] (and (vector? x) (every? nonneg-int? x))),
   :str string?,
   :kw keyword?,
   :sym symbol?,
   :data vm/plain-data?,
   :bool boolean?})


(defn- operand-kind-rule
  [{:keys [instructions]}]
  (some (fn [pc]
          (let [t (nth instructions pc), kinds (tuple-kinds t)]
            (some (fn [i]
                    (let [check (get operand-kind-checks (nth kinds (dec i)))]
                      (when (and check (not (check (nth t i))))
                        (tuple-defect :operand-kind pc))))
                  (range 1 (count t)))))
        (range (count instructions))))


(defn- target-bounds-rule
  [{:keys [instructions]}]
  (let [length (count instructions)]
    (some (fn [pc]
            (let [t (nth instructions pc), kinds (tuple-kinds t)]
              (some (fn [i]
                      (when (= :pc (nth kinds (dec i)))
                        (let [x (nth t i)]
                          (when-not (and (nonneg-int? x) (< x length))
                            (tuple-defect :target-bounds pc)))))
                    (range 1 (count t)))))
          (range (count instructions)))))


(defn- body-scope-rule
  "Bodies must exactly and contiguously partition `[0, length)` in the
   order given, body 0 starting at pc 0 (design section 4.2's own body
   layout, matching the lowerer's emission order: main body first, then
   each out-of-line lambda body in discovery order). Every `:closure`
   tuple's `body-pc` must name exactly one declared body's own `:start`,
   and that body's `:locals` must equal the `:closure`'s own declared
   arity -- two different bodies, or a body of the wrong arity, at one
   `body-pc` is a defect here, not merely a `target-bounds` pass."
  [{:keys [bodies instructions]}]
  (let [length (count instructions)
        n (count bodies)]
    (or (when (zero? n) (body-defect :no-bodies 0))
        (some (fn [i]
                (let [{:keys [locals registers start end]} (nth bodies i)]
                  (when-not (and (nonneg-int? locals) (nonneg-int? registers)
                                 (nonneg-int? start) (nonneg-int? end)
                                 (<= start end) (< end length)
                                 (<= locals registers))
                    (body-defect :body-shape i))))
              (range n))
        (some (fn [i]
                (let [prev-end (if (zero? i) -1 (:end (nth bodies (dec i))))
                      {:keys [start]} (nth bodies i)]
                  (when-not (= start (inc prev-end))
                    (body-defect :body-contiguity i))))
              (range n))
        (when-not (= (dec length) (:end (nth bodies (dec n))))
          (body-defect :body-coverage (dec n)))
        (let [start->body
              (into {} (map-indexed (fn [i b] [(:start b) i])) bodies)]
          (some (fn [pc]
                  (let [t (nth instructions pc)]
                    (when (= :closure (mnemonic-of t))
                      (let [arity (nth t 2), body-pc (nth t 3)
                            bi (get start->body body-pc)]
                        (cond
                          (nil? bi)
                          (tuple-defect :closure-target pc)
                          (not= arity (:locals (nth bodies bi)))
                          (tuple-defect :closure-arity pc)
                          :else nil)))))
                (range length))))))


(defn- owner-of
  "pc -> body index, for a body-scope-valid image."
  [{:keys [bodies]}]
  (into {}
        (mapcat (fn [[i {:keys [start end]}]]
                  (map (fn [pc] [pc i]) (range start (inc end)))))
        (map-indexed vector bodies)))


(defn- jump-scope-rule
  "Every `:jump`/`:branch-false` target must lie within the SAME body as
   the instruction itself. `target-bounds-rule` only bounds a target
   against the whole instruction vector's length, not against its own
   enclosing body -- a hand-built `:jump` from one body into another
   body's interior can be a perfectly in-range pc and still pass that
   rule. `body-liveness`'s dataflow pass only updates pcs within the one
   body it is walking, so a cross-body successor would read an empty
   `live-in` and silently corrupt liveness for that body; this rule
   rejects the image outright instead, before `body-liveness` is ever
   run over it. `:closure` targets are a different operand (checked by
   `body-scope-rule`) and are not touched here."
  [{:keys [instructions] :as image}]
  (let [owner (owner-of image)]
    (some (fn [pc]
            (let [t (nth instructions pc)]
              (case (mnemonic-of t)
                :jump (when (not= (get owner pc) (get owner (nth t 1)))
                        (tuple-defect :jump-scope pc))
                :branch-false (when (not= (get owner pc) (get owner (nth t 2)))
                                (tuple-defect :jump-scope pc))
                nil)))
          (range (count instructions)))))


(defn- register-bounds-rule
  [{:keys [bodies instructions] :as image}]
  (let [owner (owner-of image)]
    (some (fn [pc]
            (let [t (nth instructions pc), kinds (tuple-kinds t)
                  registers (:registers (nth bodies (get owner pc)))]
              (some (fn [i]
                      (case (nth kinds (dec i))
                        :reg (when-not (< (nth t i) registers)
                               (tuple-defect :register-bounds pc))
                        :regs (when (some #(not (< % registers)) (nth t i))
                                (tuple-defect :register-bounds pc))
                        nil))
                    (range 1 (count t)))))
          (range (count instructions)))))


;; =============================================================================
;; The four live-set rules (design section 4.5), after register-bounds-rule
;; =============================================================================

(defn- ascending-distinct?
  [xs]
  (every? (fn [[a b]] (< a b)) (partition 2 1 xs)))


(defn- live-shape-rule
  "`live` is a vector of nonnegative integers in strictly ascending order
   -- so it is a set, and its encoding is canonical (design section 4.5:
   'two equal live sets can never produce different bytes')."
  [{:keys [instructions]}]
  (some (fn [pc]
          (let [t (nth instructions pc)
                op (nth t 0)]
            (when (boundary-opcodes op)
              (let [live (nth t (live-slot-index op))]
                (when-not (and (vector? live)
                               (every? nonneg-int? live)
                               (ascending-distinct? live))
                  (tuple-defect :live-shape pc))))))
        (range (count instructions))))


(defn- live-bounds-rule
  "Every `live` index is below its own body's declared register count --
   `register-bounds-rule` extended to this operand."
  [{:keys [bodies instructions] :as image}]
  (let [owner (owner-of image)]
    (some (fn [pc]
            (let [t (nth instructions pc)
                  op (nth t 0)]
              (when (boundary-opcodes op)
                (let [registers (:registers (nth bodies (get owner pc)))
                      live (nth t (live-slot-index op))]
                  (when (some #(not (< % registers)) live)
                    (tuple-defect :live-bounds pc))))))
          (range (count instructions)))))


(defn- live-tail-rule
  "A tail `:call` saves nothing: its `live` is always `[]`."
  [{:keys [instructions]}]
  (some (fn [pc]
          (let [t (nth instructions pc)]
            (when (and (= :call (nth t 0)) (true? (nth t 4)) (seq (nth t 5)))
              (tuple-defect :live-tail pc))))
        (range (count instructions))))


(defn- live-exact-rule
  "`live` equals `body-liveness`'s own answer for that pc -- a received
   image's live sets are not trusted, they are recomputed on the
   receiving host before execution, the same discipline as B1's scope
   check. Reports `:expected`/`:actual` alongside the usual defect shape."
  [{:keys [bodies instructions] :as image}]
  (let [owner (owner-of image)
        live-maps (mapv #(body-liveness image %) (range (count bodies)))]
    (some (fn [pc]
            (let [t (nth instructions pc)
                  op (nth t 0)]
              (when (boundary-opcodes op)
                (let [expected (get (nth live-maps (get owner pc)) pc)
                      actual (nth t (live-slot-index op))]
                  (when (not= expected actual)
                    (assoc (tuple-defect :live-exact pc)
                           :expected expected
                           :actual actual))))))
          (range (count instructions)))))


(defn- terminator-rule
  [{:keys [bodies instructions]}]
  (some (fn [i]
          (let [{:keys [end]} (nth bodies i)
                op (mnemonic-of (nth instructions end))
                wanted (if (zero? i) :halt :return)]
            (when-not (= wanted op)
              (body-defect :terminator i))))
        (range (count bodies))))


(def ^:private structural-rules
  [nonempty mnemonic-rule arity-rule operand-kind-rule target-bounds-rule])


(def ^:private body-rules
  "`jump-scope-rule` runs right after `body-scope-rule` (the first rule
   that makes `owner-of` meaningful), and before every rule downstream of
   `body-liveness`: a cross-body `:jump`/`:branch-false` target makes
   `body-liveness` itself produce meaningless results, which the live-set
   rules must never be asked to check. `terminator-rule` runs before the
   four live-set rules, not merely after `register-bounds-rule`,
   deliberately: `body-liveness`'s successor rule assumes every body ends
   `:return`/`:halt` (its fall-through default otherwise reads one pc
   past a malformed body's own end), so a body whose terminator is wrong
   must be rejected by `terminator-rule` first rather than crash inside
   the dataflow."
  [body-scope-rule jump-scope-rule register-bounds-rule terminator-rule
   live-shape-rule live-bounds-rule live-tail-rule live-exact-rule])


(defn register-image-defect
  "The sole admitter of a `{:bodies [...], :instructions [...]}` register
   image on this path: nil when it is well formed, else the first defect
   -- a tuple defect `{:rule r :pc p}` or a body defect `{:rule r :body
   i}`. Structural rules run first (they alone make `:bodies`/pc-indexed
   access into `instructions` safe for the body rules that follow)."
  [image]
  (or (some #(% image) structural-rules)
      (some #(% image) body-rules)))
