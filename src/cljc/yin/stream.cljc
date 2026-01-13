(ns yin.stream
  "Stream library for Yin VM.

  This is a client-facing library that can be required by user code:

    (require '[stream :as s])
    (let [ch (s/make 10)]
      (s/put! ch 42)
      (s/take! ch))

  Stream functions return effect descriptors that the VM interprets.
  This allows streams to manipulate VM state (store, continuations)
  while being regular functions from the client's perspective.

  Streams are stored in the VM store as:
  {:type :stream
   :buffer []        ;; values waiting to be taken
   :capacity n       ;; max buffer size
   :takers []}       ;; parked continuations waiting for values"
  (:require [yin.module :as module]))

;; ============================================================
;; Client-Facing API (returns effect descriptors)
;; ============================================================

(defn make
  "Create a new stream with given buffer capacity.
   Returns an effect descriptor that the VM will execute."
  ([] (make 1024))
  ([capacity]
   {:effect :stream/make
    :capacity capacity}))

(defn put!
  "Put a value onto a stream.
   Returns an effect descriptor that the VM will execute."
  [stream-ref val]
  {:effect :stream/put
   :stream stream-ref
   :val val})

(defn take!
  "Take a value from a stream. May block if empty.
   Returns an effect descriptor that the VM will execute."
  [stream-ref]
  {:effect :stream/take
   :stream stream-ref})

(defn close!
  "Close a stream. No more values can be put.
   Returns an effect descriptor that the VM will execute."
  [stream-ref]
  {:effect :stream/close
   :stream stream-ref})

;; ============================================================
;; Stream Data Structure Helpers
;; Pure functions used by VM to manipulate stream data
;; ============================================================

(defn make-stream-data
  "Create initial stream data structure."
  [capacity]
  {:type :stream
   :buffer []
   :capacity capacity
   :takers []
   :closed false})

(defn stream-empty?
  "Check if stream buffer is empty."
  [stream]
  (empty? (:buffer stream)))

(defn stream-full?
  "Check if stream buffer is at capacity."
  [stream]
  (>= (count (:buffer stream)) (:capacity stream)))

(defn stream-closed?
  "Check if stream is closed."
  [stream]
  (:closed stream))

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
;; VM-Level Effect Handlers
;; Called by VM when executing stream effects
;; ============================================================

(defn handle-make
  "Handle :stream/make effect. Returns [stream-ref updated-state]."
  [state effect gensym-fn]
  (let [capacity (:capacity effect)
        id (gensym-fn "stream")
        stream-data (make-stream-data capacity)
        new-store (assoc (:store state) id stream-data)
        stream-ref {:type :stream-ref :id id}]
    [stream-ref (assoc state :store new-store)]))

(defn handle-put
  "Handle :stream/put effect. Returns result map."
  [state effect]
  (let [stream-ref (:stream effect)
        val (:val effect)
        stream-id (:id stream-ref)
        store (:store state)
        stream (get store stream-id)]
    (cond
      (nil? stream)
      (throw (ex-info "Invalid stream reference" {:ref stream-ref}))

      (stream-closed? stream)
      (throw (ex-info "Cannot put to closed stream" {:ref stream-ref}))

      (stream-has-takers? stream)
      ;; Taker waiting - hand off value directly
      (let [[taker new-stream] (stream-pop-taker stream)
            new-store (assoc store stream-id new-stream)]
        {:value val
         :state (assoc state :store new-store)
         :resume-taker taker
         :resume-value val})

      :else
      ;; No takers - buffer the value
      (let [new-stream (stream-add-value stream val)
            new-store (assoc store stream-id new-stream)]
        {:value val
         :state (assoc state :store new-store)}))))

(defn handle-take
  "Handle :stream/take effect. Returns result map."
  [state effect]
  (let [stream-ref (:stream effect)
        stream-id (:id stream-ref)
        store (:store state)
        stream (get store stream-id)]
    (cond
      (nil? stream)
      (throw (ex-info "Invalid stream reference" {:ref stream-ref}))

      (not (stream-empty? stream))
      ;; Has value - take it
      (let [[val new-stream] (stream-take-value stream)
            new-store (assoc store stream-id new-stream)]
        {:value val
         :state (assoc state :store new-store)})

      (stream-closed? stream)
      ;; Closed and empty - return nil
      {:value nil
       :state state}

      :else
      ;; Empty - need to park
      {:park true
       :stream-id stream-id
       :state state})))

(defn handle-close
  "Handle :stream/close effect. Returns updated state."
  [state effect]
  (let [stream-ref (:stream effect)
        stream-id (:id stream-ref)
        store (:store state)
        stream (get store stream-id)
        new-stream (assoc stream :closed true)
        new-store (assoc store stream-id new-stream)]
    ;; TODO: Resume all takers with nil
    (assoc state :store new-store)))

;; ============================================================
;; VM-Level Stream Operations (for AST node handling)
;; These work with stream-refs directly, used by VM for
;; :stream/make, :stream/take, :stream/put AST nodes
;; ============================================================

(defn vm-stream-make
  "Create a stream in the VM store. Returns [stream-ref new-state]."
  [state capacity]
  (let [id (keyword (str "stream-" (swap! (atom 0) inc)))  ;; Will be replaced by gensym
        stream-data (make-stream-data capacity)
        new-store (assoc (:store state) id stream-data)
        stream-ref {:type :stream-ref :id id}]
    [stream-ref (assoc state :store new-store)]))

(defn vm-stream-take
  "Take from a stream by ref. Used by VM continuation handling.
   Returns result map with :park or :value/:state."
  [state stream-ref continuation]
  (let [stream-id (:id stream-ref)
        store (:store state)
        stream (get store stream-id)]
    (cond
      (nil? stream)
      (throw (ex-info "Invalid stream reference" {:ref stream-ref}))

      (not (stream-empty? stream))
      ;; Has value - take it
      (let [[val new-stream] (stream-take-value stream)
            new-store (assoc store stream-id new-stream)]
        {:value val
         :state (assoc state :store new-store :value val)})

      (stream-closed? stream)
      ;; Closed and empty - return nil
      {:value nil
       :state (assoc state :value nil)}

      :else
      ;; Empty - need to park
      {:park true
       :stream-id stream-id
       :state state})))

(defn vm-stream-put
  "Put to a stream by ref. Used by VM continuation handling.
   Returns result map."
  [state stream-ref val]
  (let [stream-id (:id stream-ref)
        store (:store state)
        stream (get store stream-id)]
    (cond
      (nil? stream)
      (throw (ex-info "Invalid stream reference" {:ref stream-ref}))

      (stream-closed? stream)
      (throw (ex-info "Cannot put to closed stream" {:ref stream-ref}))

      (stream-has-takers? stream)
      ;; Taker waiting - hand off value directly
      (let [[taker new-stream] (stream-pop-taker stream)
            new-store (assoc store stream-id new-stream)]
        {:value val
         :state (assoc state :store new-store :value val)
         :resume-taker taker
         :resume-value val})

      :else
      ;; No takers - buffer the value
      (let [new-stream (stream-add-value stream val)
            new-store (assoc store stream-id new-stream)]
        {:value val
         :state (assoc state :store new-store :value val)}))))

;; ============================================================
;; Module Registration
;; ============================================================

(defn register!
  "Register the stream module with the VM module system."
  []
  (module/register-module!
   'stream
   {'make make
    'put! put!
    'take! take!
    'close! close!}))

;; Auto-register on load
(register!)
