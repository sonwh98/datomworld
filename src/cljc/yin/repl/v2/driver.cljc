(ns yin.repl.v2.driver
  "The one owner of Yin REPL v2 state.

   A line producer appends `{:yin.repl.v2.input/line \"…\"}` to the
   composition-owned input medium and returns.  It does not evaluate, request,
   poll, print, prompt, or touch the state held here.  `repl-step` is called
   once per externally driven tick by exactly one ticker per host: it drains the
   input medium, evaluates local input, forwards ordinary source through the v2
   RPC client, publishes completions exactly once, and returns the next state.

   It never loops on `blocked`, and it owns no promise, future, callback, atom,
   or clock."
  (:require [clojure.string :as str]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.ringbuffer :as ring]
            [yin.repl.v2-adapter :as adapter]
            [yin.repl.v2.core :as core]))


(def line-key :yin.repl.v2.input/line)


(def input-capacity
  "Declared capacity of the composition-owned input medium, in elements."
  1024)


(def input-budget
  "Maximum input elements one step reads, so a flooded medium cannot make a
   single step unbounded."
  64)


(def response-budget
  "Maximum response-medium elements one step reads."
  32)


(def event-key :yin.repl.v2.driver/event)
(def text-key :yin.repl.v2.driver/text)


(def connect-text
  (str "(connect …) needs the v2 WebSocket RPC decoder, dao.stream.v2.rpc.ws, "
       "which this slice does not carry"))


(defn create-input-medium!
  "Create the composition-owned input ring buffer.  The handoff between a line
   producer and the step owner is a stream, which is what makes it explicit and
   thread-safe."
  []
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key input-capacity})))


(defn- mint-cursor
  [handle]
  (let [result (stream/cursor handle :dao.stream/oldest)]
    (when (= :dao.stream/ok (:dao.stream/outcome result))
      (:dao.stream/cursor result))))


(defn create-state
  ([] (create-state {}))
  ([{:keys [input input-cursor repl]}]
   (let [input (or input (create-input-medium!))]
     {:repl (or repl (core/create-state))
      :input input
      :input-cursor (or input-cursor (mint-cursor input))
      :input-ledger :untried
      :adapter nil
      :queued []
      :outstanding nil
      :retrying nil
      :outbox []
      :last-tick nil
      :running? true})))


(defn submit-line!
  "Append one typed line to the input medium and return the append outcome.
   This is the whole of a line producer's work."
  [input line]
  (stream/append! input {line-key line}))


(defn take-outbox
  "Return `[entries next-state]`, clearing the publication outbox exactly once."
  [state]
  [(:outbox state) (assoc state :outbox [])])


(defn attach-remote
  [state adapter-state]
  (assoc state :adapter adapter-state :queued [] :outstanding nil :retrying nil))


(defn detach-remote
  [state]
  (assoc state :adapter nil :queued [] :outstanding nil :retrying nil))


(defn- publish
  [state event text]
  (if (str/blank? (str text))
    state
    (update state :outbox conj {event-key event text-key text})))


;; =============================================================================
;; Input
;; =============================================================================

(defn- drain-input
  "Read up to `input-budget` input elements, total over every `next` outcome.
   A gap means typed lines were evicted: the loss is published, the recovery
   cursor is taken, and none of the missing input is evaluated."
  [state]
  (loop [remaining input-budget
         state state
         lines []]
    (if (or (zero? remaining) (nil? (:input-cursor state)))
      [state lines]
      (let [result (stream/next (:input state) (:input-cursor state))
            outcome (:dao.stream/outcome result)]
        (case outcome
          :dao.stream/ok
          (recur (dec remaining)
                 (assoc state :input-cursor (:dao.stream/cursor result))
                 (conj lines (get (:dao.stream/value result) line-key)))

          :dao.stream/gap
          (recur (dec remaining)
                 (-> state
                     (assoc :input-cursor (:dao.stream/cursor result))
                     (publish :yin.repl.v2.driver/notice
                              ";; input lost: typed lines were evicted before this step"))
                 lines)

          :dao.stream/blocked
          [(assoc state :input-ledger outcome) lines]

          [(-> state
               (assoc :input-ledger outcome)
               (publish :yin.repl.v2.driver/notice
                        (str ";; input " (name outcome))))
           lines])))))


;; =============================================================================
;; Local and remote input
;; =============================================================================

(defn- evaluate-locally
  [state line]
  (let [[repl text] (core/eval-input (:repl state) line)]
    (-> state
        (assoc :repl repl)
        (assoc :running? (boolean (:running? repl)))
        (publish :yin.repl.v2.driver/result text))))


(defn- disconnect
  [state]
  (if (:adapter state)
    (-> state
        detach-remote
        (publish :yin.repl.v2.driver/notice "Disconnected from remote shell"))
    (publish state :yin.repl.v2.driver/notice "Not connected to a remote shell")))


(defn- submit-remote
  [state line]
  (let [result (adapter/submit-input (:adapter state) line)
        state (assoc state :adapter (:yin.repl.v2.adapter/state result))]
    (case (:yin.repl.v2.adapter/outcome result)
      :yin.repl.v2.adapter/requested
      (assoc state
             :outstanding (get-in result [:yin.repl.v2.adapter/rpc-result
                                          :dao.stream.v2.rpc/id])
             :retrying nil)

      ;; The request was allocated but the writer answered `full`.  The same
      ;; encoded request, with the same id, is retried on a later step.
      :yin.repl.v2.adapter/pending-request
      (assoc state :retrying line)

      (-> state
          (assoc :retrying nil)
          (publish :yin.repl.v2.driver/notice
                   (str ";; request not sent: "
                        (name (:yin.repl.v2.adapter/outcome result))))))))


(defn- handle-line
  [state line]
  (let [command (adapter/local-command line)]
    (cond
      ;; A shell that has quit evaluates nothing more.  Hosts append an
      ;; end-of-input `(quit)` that a typed `(quit)` has already answered.
      (not (:running? state)) state

      (not (string? line))
      (publish state :yin.repl.v2.driver/diagnostic
               (str ";; ignored non-string input: " (pr-str line)))

      (str/blank? line) state

      ;; Local control commands bypass the remote queue, so a stuck or slow
      ;; remote request cannot trap the operator.
      (= 'connect command)
      (publish state :yin.repl.v2.driver/notice connect-text)

      (= 'disconnect command) (disconnect state)

      (and (:adapter state) (not command))
      (if (or (:outstanding state) (:retrying state))
        (update state :queued conj line)
        (submit-remote state line))

      :else (evaluate-locally state line))))


;; =============================================================================
;; Remote completions
;; =============================================================================

(defn- completion-text
  [event]
  (case (:yin.repl.v2.adapter/event event)
    :yin.repl.v2.adapter/response
    (if (contains? event :yin.repl.v2.adapter/error)
      (str "Error: " (core/format-value (:yin.repl.v2.adapter/error event)))
      (let [value (:yin.repl.v2.adapter/value event)]
        (if (string? value) value (core/format-value value))))

    :yin.repl.v2.adapter/lost
    (str ";; remote request " (:yin.repl.v2.adapter/id event) " lost: "
         (pr-str (:yin.repl.v2.adapter/reason event)))

    (str ";; " (pr-str event))))


(defn- completion-event-kind
  [event]
  (if (= :yin.repl.v2.adapter/response (:yin.repl.v2.adapter/event event))
    :yin.repl.v2.driver/response
    :yin.repl.v2.driver/notice))


(defn- clears-outstanding?
  [state event]
  (and (:outstanding state)
       (contains? #{:yin.repl.v2.adapter/response :yin.repl.v2.adapter/lost}
                  (:yin.repl.v2.adapter/event event))
       (= (:outstanding state) (:yin.repl.v2.adapter/id event))))


(defn- publish-adapter-events
  "Move the adapter's event outbox into the driver's, exactly once."
  [state]
  (let [[events adapter] (adapter/take-events (:adapter state))]
    (reduce (fn [state event]
              (-> state
                  (cond-> (clears-outstanding? state event)
                    (assoc :outstanding nil))
                  (publish (completion-event-kind event) (completion-text event))))
            (assoc state :adapter adapter)
            events)))


(defn- poll-remote
  [state]
  (if-not (:adapter state)
    state
    (let [result (adapter/poll-responses (:adapter state) response-budget)]
      (publish-adapter-events (assoc state :adapter
                                     (:yin.repl.v2.adapter/state result))))))


(defn- retry-unsent
  [state]
  (if (and (:adapter state) (:retrying state))
    (submit-remote state (:retrying state))
    state))


(defn- release-queue
  [state]
  (if (and (:adapter state)
           (seq (:queued state))
           (not (:outstanding state))
           (not (:retrying state)))
    (let [[line & rest-lines] (:queued state)]
      (-> state
          (assoc :queued (vec rest-lines))
          (submit-remote line)))
    state))


;; =============================================================================
;; The step
;; =============================================================================

(defn repl-step
  "Advance the REPL once.  Called by exactly one ticker per host; never loops on
   `:dao.stream/blocked`.  `now` is the host's clock reading, recorded but not
   waited on: this layer has no deadline of its own."
  [state now]
  (let [state (assoc state :last-tick now)
        state (retry-unsent state)
        [state lines] (drain-input state)
        state (reduce handle-line state lines)
        state (poll-remote state)]
    (release-queue state)))
