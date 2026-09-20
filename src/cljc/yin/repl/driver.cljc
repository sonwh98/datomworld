(ns yin.repl.driver
  "The one owner of Yin REPL v2 state.

   A line producer appends `{:yin.repl.input/line \"…\"}` to the
   composition-owned input medium and returns.  It does not evaluate, request,
   poll, print, prompt, or touch the state held here.  `repl-step` is called
   once per externally driven tick by exactly one ticker per host: it drains the
   input medium, evaluates local input, forwards ordinary source through the v2
   RPC client, publishes completions exactly once, and returns the next state.

   It never loops on `blocked`, and it owns no promise, future, callback, atom,
   or clock."
  (:require [clojure.string :as str]
            [dao.data :as data]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.rpc :as rpc]
            [yin.repl.adapter :as adapter]
            [yin.repl.connect :as connect]
            [yin.repl.core :as core]
            [yin.repl.host :as host]))


(def line-key :yin.repl.input/line)


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


(def event-key :yin.repl.driver/event)
(def text-key :yin.repl.driver/text)


(def ^:private diagnostic-bounds
  "Bound on operator-facing diagnostic text built from unbounded internal
   state (raw input, event/result maps)."
  {:depth 3 :items 8 :chars 200})


(def connect-usage
  "(connect \"daostream:ws://host:port/repl\") — an absent path means /repl")


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
  ([{:keys [input input-cursor repl host]}]
   (let [input (or input (create-input-medium!))]
     {:repl (or repl (core/create-state))
      :input input
      :input-cursor (or input-cursor (mint-cursor input))
      :input-ledger :untried
      ;; The host WebSocket adapter is composition data, injected once.  Nil is
      ;; the honest answer in a build with no host socket package.
      :host (or host (host/websocket))
      :connection nil
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
                     (publish :yin.repl.driver/notice
                              ";; input lost: typed lines were evicted before this step"))
                 lines)

          :dao.stream/blocked
          [(assoc state :input-ledger outcome) lines]

          [(-> state
               (assoc :input-ledger outcome)
               (publish :yin.repl.driver/notice
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
        (publish :yin.repl.driver/result text))))


(defn- rpc-client
  [state]
  (get-in state [:adapter :yin.repl.adapter/rpc]))


(defn- remote-terminal
  [state]
  (:terminal (rpc-client state)))


(defn- remote-unsent?
  "True while the RPC client holds an allocated request it has not managed to
   append.  That request, not the driver's own record of the line, is what owns
   the request path: `rpc/request!` retries it and ignores any new operation."
  [state]
  (rpc/unsent? (rpc-client state)))


(defn pending-write?
  "True while a remote write the operator is owed remains in flight: a
   request awaiting its response, a line held for a full writer, or an
   envelope the RPC client still retains. This is one half of the tick
   owner's `moved?` — a pending write must never wait out a backoff ceiling,
   so cadence stays at the base interval until it clears."
  [state]
  (boolean (or (:outstanding state)
               (:retrying state)
               (remote-unsent? state))))


(def queueable-terminals
  "Terminal reasons after which an ordinary line still has a reattachment
   decision to wait for.  Only `/detached` reattaches.  A reachability failure
   may succeed through a *fresh* connection, but that connection cannot inherit
   this binding's queued lines, so promising to deliver them would be false.
   Every other terminal returns input to the local shell."
  #{:dao.stream.apply/detached})


(defn- remote-routed?
  "True while ordinary input belongs to the remote shell.  An operator
   `(disconnect)` ends that immediately — the shell is local again from the next
   line, without waiting for the terminal event the boundary will deposit — while
   an uninvited drop keeps routing so the input can queue for a reattachment.  A
   terminal with no reattachment behind it ends it too."
  [state]
  (and (:adapter state)
       (not (connect/operator-detached? (:connection state)))
       (let [terminal (remote-terminal state)]
         (or (nil? terminal) (contains? queueable-terminals terminal)))))


(defn- abandon-unsent
  "Give up any allocated-but-unsent request, with the reason the driver owns.

   The RPC client would otherwise retry that exact envelope on the next binding,
   in place of the line the operator types after reattaching.  Abandoning it
   publishes the loss as an ordinary completion, so the line is reported rather
   than silently replayed or dropped."
  [state reason]
  (if (rpc-client state)
    (update-in state [:adapter :yin.repl.adapter/rpc] rpc/abandon-unsent reason)
    state))


(declare publish-adapter-events)


(defn- abandon-unsent-now
  "Finish and publish the old request path before replacing an adapter wholesale.

   The ordinary disconnect/reattach paths retain their adapter until the next
   poll can publish its completion.  A fresh connection does not: it installs a
   new adapter immediately, so every already-unpublished completion must cross
   into the driver's outbox first or it is discarded with the old adapter.  An
   unsent envelope is abandoned before that unconditional publication step."
  [state reason]
  (if-not (:adapter state)
    state
    (let [state (cond-> state
                  (remote-unsent? state) (abandon-unsent reason))
          published (adapter/publish-completions (:adapter state))]
      (publish-adapter-events
        (assoc state :adapter (:yin.repl.adapter/state published))))))


(defn- disconnect
  "`disconnect` is `close!`.  The connection value, its traffic medium, and its
   cursors are kept: the terminal fact arrives as a deposited event, and that is
   what makes a later `(connect …)` a `rebind` rather than a new client.

   Input queued for the remote shell is dropped rather than carried across the
   disconnection: the operator asked to stop talking to it.  A request already
   allocated but never appended is abandoned on the same grounds, and says so."
  [state]
  (cond
    (:connection state)
    (-> state
        (assoc :connection (connect/close! (:connection state) :operator))
        (abandon-unsent :yin.repl.driver/operator-disconnect)
        (assoc :queued [] :retrying nil)
        (publish :yin.repl.driver/notice
                 (str "Disconnecting from " (:url (:connection state)))))

    (:adapter state)
    (-> state
        detach-remote
        (publish :yin.repl.driver/notice "Disconnected from remote shell"))

    :else
    (publish state :yin.repl.driver/notice "Not connected to a remote shell")))


(defn- connect-failure
  [state result]
  (publish state :yin.repl.driver/notice
           (str (get result connect/message-key)
                " [" (name (get result connect/outcome-key)) "]")))


(defn- reattach
  [state url]
  (let [result (connect/reattach (:connection state) (rpc-client state))]
    (if-not (= :yin.repl.connect/reattached (get result connect/outcome-key))
      (connect-failure state result)
      (-> state
          (assoc :connection (get result connect/connection-key))
          (assoc-in [:adapter :yin.repl.adapter/rpc] (get result connect/client-key))
          ;; The retained envelope was encoded for the attachment that died, so
          ;; it is reported lost rather than resent as the first thing the new
          ;; binding says, ahead of whatever the operator types next.  Over the
          ;; WebSocket boundary the retry meets a closed writer first and RPC
          ;; completes it there; this holds for a host whose writer keeps
          ;; answering `full` after its peer is gone.
          (abandon-unsent :yin.repl.driver/abandoned-on-reattach)
          (assoc :outstanding nil :retrying nil)
          (publish :yin.repl.driver/notice (str "Reattaching to " url))))))


(defn- open-connection
  [state url]
  (let [result (connect/open {:url url :host (:host state)})
        dropped (count (:queued state))]
    (if-not (= :yin.repl.connect/attached (get result connect/outcome-key))
      (connect-failure state result)
      (-> state
          (abandon-unsent-now :yin.repl.driver/abandoned-on-fresh-connection)
          (cond-> (pos? dropped)
            (publish :yin.repl.driver/notice
                     (str "Discarded " dropped " queued remote "
                          (if (= 1 dropped) "line" "lines")
                          " before opening a fresh connection")))
          (assoc :connection (get result connect/connection-key))
          (attach-remote (adapter/state (get result connect/client-key)))
          (assoc :outstanding nil :retrying nil)
          (publish :yin.repl.driver/notice (str "Attaching to " url))))))


(defn- connect-command
  "`(connect \"url\")`.  Reattaching a dropped connection keeps its medium,
   cursor, and id allocator; connecting a fresh URL composes a new boundary."
  [state line]
  (let [url (second (adapter/command-form line))]
    (cond
      (not (string? url))
      (publish state :yin.repl.driver/notice connect-usage)

      (and (:connection state)
           (= url (:url (:connection state)))
           (connect/reattachable? (rpc-client state)))
      (reattach state url)

      ;; The operator's own disconnection is in flight: the terminal fact has
      ;; not landed yet, so there is nothing to reattach to and nothing to
      ;; refuse.  Composing a second boundary here would abandon the first.
      ;; Once a terminal has landed the boundary has said everything it will
      ;; ever say, so this must not answer for a non-reattachable one — that
      ;; would trap the operator on a connection nothing can revive.
      (and (:connection state)
           (connect/operator-detached? (:connection state))
           (nil? (remote-terminal state)))
      (publish state :yin.repl.driver/notice
               (str "Still disconnecting from " (:url (:connection state))
                    "; (connect …) reattaches once the boundary reports it"))

      (and (remote-routed? state) (not (remote-terminal state)))
      (publish state :yin.repl.driver/notice
               (str "Already connected to "
                    (or (:url (:connection state)) "the remote shell")
                    "; (disconnect) first"))

      :else (open-connection state url))))


(defn- submit-remote
  [state line]
  (let [result (adapter/submit-input (:adapter state) line)
        state (assoc state :adapter (:yin.repl.adapter/state result))]
    (case (:yin.repl.adapter/outcome result)
      :yin.repl.adapter/requested
      (assoc state
             :outstanding (get-in result [:yin.repl.adapter/rpc-result
                                          :dao.stream.rpc/id])
             :retrying nil)

      ;; The request was allocated but the writer answered `full`.  The same
      ;; encoded request, with the same id, is retried on a later step.
      :yin.repl.adapter/pending-request
      (assoc state :retrying line)

      (-> state
          (assoc :retrying nil)
          (publish :yin.repl.driver/notice
                   (str ";; request not sent: "
                        (name (:yin.repl.adapter/outcome result))))))))


(defn- handle-line
  [state line]
  (let [command (adapter/local-command line)]
    (cond
      ;; A shell that has quit evaluates nothing more.  Hosts append an
      ;; end-of-input `(quit)` that a typed `(quit)` has already answered.
      (not (:running? state)) state

      (not (string? line))
      (publish state :yin.repl.driver/diagnostic
               (str ";; ignored non-string input: "
                    (pr-str (data/summarize line diagnostic-bounds))))

      (str/blank? line) state

      ;; Local control commands bypass the remote queue, so a stuck or slow
      ;; remote request cannot trap the operator.
      (= 'connect command) (connect-command state line)

      (= 'disconnect command) (disconnect state)

      (= 'repl-state command)
      (cond-> (evaluate-locally state line)
        (:connection state)
        (publish :yin.repl.driver/result
                 (core/format-value (connect/summary (:connection state)))))

      (and (remote-routed? state) (not command))
      (cond
        ;; A dropped or ended remote evaluates nothing.  The line was never
        ;; sent, so it waits for an explicit reattachment decision.
        (remote-terminal state)
        (-> state
            (update :queued conj line)
            (publish :yin.repl.driver/notice
                     ";; not connected: queued until (connect …) reattaches"))

        ;; `:unsent` is the RPC client's own retained envelope, and the
        ;; authority on whether the request path is free: submitting here would
        ;; resend it and discard this line.  `:retrying` is only the driver's
        ;; record of which line that envelope carries.
        (or (:outstanding state) (:retrying state) (remote-unsent? state))
        (update state :queued conj line)

        :else (submit-remote state line))

      :else (evaluate-locally state line))))


;; =============================================================================
;; Remote completions
;; =============================================================================

(defn- completion-text
  [event]
  (case (:yin.repl.adapter/event event)
    :yin.repl.adapter/response
    (if (contains? event :yin.repl.adapter/error)
      (str "Error: " (core/format-value (:yin.repl.adapter/error event)))
      (let [value (:yin.repl.adapter/value event)]
        (if (string? value) value (core/format-value value))))

    :yin.repl.adapter/lost
    (str ";; remote request " (:yin.repl.adapter/id event) " lost: "
         (pr-str (data/summarize (:yin.repl.adapter/reason event) diagnostic-bounds)))

    (str ";; " (pr-str (data/summarize event diagnostic-bounds)))))


(defn- completion-event-kind
  [event]
  (if (= :yin.repl.adapter/response (:yin.repl.adapter/event event))
    :yin.repl.driver/response
    :yin.repl.driver/notice))


(defn- clears-outstanding?
  [state event]
  (and (:outstanding state)
       (contains? #{:yin.repl.adapter/response :yin.repl.adapter/lost}
                  (:yin.repl.adapter/event event))
       (= (:outstanding state) (:yin.repl.adapter/id event))))


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
                                     (:yin.repl.adapter/state result))))))


(defn- observe-connection
  "Publish the boundary's neutral lifecycle facts.  `Connected to …` is printed
   here, when `/established` is observed, and never when `attach!` returned."
  [state]
  (if-not (:connection state)
    state
    (let [[connection events] (connect/observe (:connection state))]
      (reduce (fn [state event]
                (publish state
                         (get event connect/event-key)
                         (get event connect/text-key)))
              (assoc state :connection connection)
              events))))


(defn- retry-unsent
  "The one path that resends a retained envelope.  It is owed only while RPC
   still holds it: an abandoned request leaves nothing to retry, whatever the
   driver last recorded."
  [state]
  (if (and (remote-routed? state)
           (remote-unsent? state)
           (:retrying state)
           (not (remote-terminal state)))
    (submit-remote state (:retrying state))
    state))


(defn- release-queue
  [state]
  (if (and (remote-routed? state)
           (not (remote-terminal state))
           (seq (:queued state))
           (not (:outstanding state))
           (not (:retrying state))
           (not (remote-unsent? state)))
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
        ;; Completions and lifecycle are observed before this tick's lines are
        ;; handled, so a `(connect …)` typed in the same tick as the drop that
        ;; makes it legal sees the terminal fact rather than being told the
        ;; shell is already connected.
        state (poll-remote state)
        state (observe-connection state)
        [state lines] (drain-input state)
        state (reduce handle-line state lines)]
    (release-queue state)))
