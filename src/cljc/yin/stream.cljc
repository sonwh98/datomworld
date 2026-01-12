(ns yin.stream
  "Stream library for Yin VM.

  Streams are implemented as a library using VM primitives:
  - :vm/gensym for unique IDs
  - :vm/store-get and :vm/store-put for state
  - :vm/park and :vm/resume for blocking

  This module provides:
  1. AST constructors for stream operations
  2. Primitive functions that can be added to the VM environment

  Streams are stored in the VM store as:
  {:type :stream
   :buffer []        ;; values waiting to be taken
   :capacity n       ;; max buffer size
   :takers []}       ;; parked continuations waiting for values")

;; ============================================================
;; AST Constructors
;; These return AST nodes that can be composed into programs
;; ============================================================

(defn make-ast
  "Return AST for creating a new stream.
   Usage in compiled code: (stream/make 10)"
  ([] (make-ast 1024))
  ([buffer-size]
   {:type :stream/make
    :buffer buffer-size}))

(defn put-ast
  "Return AST for putting a value on a stream.
   Usage in compiled code: (stream/put s val)"
  [stream-ast val-ast]
  {:type :stream/put
   :stream stream-ast
   :val val-ast})

(defn take-ast
  "Return AST for taking a value from a stream.
   Usage in compiled code: (stream/take s)"
  [stream-ast]
  {:type :stream/take
   :stream stream-ast})

;; ============================================================
;; Stream Data Structure Helpers
;; Pure functions for manipulating stream data
;; ============================================================

(defn make-stream-data
  "Create initial stream data structure."
  [capacity]
  {:type :stream
   :buffer []
   :capacity capacity
   :takers []})

(defn stream-empty?
  "Check if stream buffer is empty."
  [stream]
  (empty? (:buffer stream)))

(defn stream-full?
  "Check if stream buffer is at capacity."
  [stream]
  (>= (count (:buffer stream)) (:capacity stream)))

(defn stream-has-takers?
  "Check if there are parked continuations waiting."
  [stream]
  (seq (:takers stream)))

(defn stream-add-value
  "Add a value to the stream buffer."
  [stream val]
  (update stream :buffer conj val))

(defn stream-take-value
  "Take first value from stream buffer. Returns [value updated-stream]."
  [stream]
  (let [[val & rest-buf] (:buffer stream)]
    [val (assoc stream :buffer (vec rest-buf))]))

(defn stream-add-taker
  "Add a parked continuation to the takers list."
  [stream parked-cont]
  (update stream :takers conj parked-cont))

(defn stream-pop-taker
  "Remove and return first taker. Returns [taker updated-stream]."
  [stream]
  (let [[taker & rest-takers] (:takers stream)]
    [taker (assoc stream :takers (vec rest-takers))]))

;; ============================================================
;; VM-Level Operations
;; These work directly with VM state (for use in VM primitives)
;; ============================================================

(defn vm-stream-make
  "Create a stream in the VM store. Returns [stream-ref updated-state]."
  [state capacity]
  (let [id (keyword (str "stream-" (gensym)))
        stream-data (make-stream-data capacity)
        new-store (assoc (:store state) id stream-data)
        stream-ref {:type :stream-ref :id id}]
    [stream-ref (assoc state :store new-store)]))

(defn vm-stream-put
  "Put a value on a stream. Returns updated state.
   If takers are waiting, resumes the first one.
   Otherwise buffers the value."
  [state stream-ref val]
  (let [stream-id (:id stream-ref)
        store (:store state)
        stream (get store stream-id)]
    (if (stream-has-takers? stream)
      ;; Taker waiting - hand off value directly
      (let [[taker new-stream] (stream-pop-taker stream)
            new-store (assoc store stream-id new-stream)]
        ;; Return state ready to resume the taker
        {:resume-taker taker
         :resume-value val
         :state (assoc state :store new-store :value val)})
      ;; No takers - buffer the value
      (let [new-stream (stream-add-value stream val)
            new-store (assoc store stream-id new-stream)]
        {:state (assoc state :store new-store :value val)}))))

(defn vm-stream-take
  "Take a value from a stream. Returns result map.
   If buffer has values, returns immediately.
   Otherwise parks the current continuation."
  [state stream-ref continuation]
  (let [stream-id (:id stream-ref)
        store (:store state)
        stream (get store stream-id)]
    (if (stream-empty? stream)
      ;; Empty - need to park
      {:park true
       :stream-id stream-id
       :state state}
      ;; Has value - take it
      (let [[val new-stream] (stream-take-value stream)
            new-store (assoc store stream-id new-stream)]
        {:value val
         :state (assoc state :store new-store :value val)}))))

;; ============================================================
;; Primitive Functions for VM Environment
;; These can be added to the VM's primitive environment
;; ============================================================

(defn make-stream-primitives
  "Returns a map of stream primitive functions.
   These are stateful and need access to VM state,
   so they're wrapped in a way that the VM can call them."
  []
  {'stream/make (fn [capacity] {:op :stream/make :capacity capacity})
   'stream/put (fn [stream-ref val] {:op :stream/put :stream stream-ref :val val})
   'stream/take (fn [stream-ref] {:op :stream/take :stream stream-ref})})
