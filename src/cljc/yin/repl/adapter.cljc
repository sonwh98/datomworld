(ns yin.repl.adapter
  "A small, host-neutral bridge between a line-oriented Yin REPL and the
   DaoStream RPC state machine.

   This is deliberately not a replacement for `yin.repl`: it does not create a
   socket, evaluate a form, print, own an atom, or invoke a callback.  A driver
   owns the state returned here.  Local shell commands are emitted as data for
   that driver to evaluate locally; every other input string becomes one
   `:op/eval` request through the explicit RPC client state."
  (:require #?(:cljd [clojure.edn :as edn]
               :clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])
            [dao.stream.apply :as apply]
            [dao.stream.rpc :as rpc]))


(def eval-operation :op/eval)


(def local-command-heads
  "Commands which retain the legacy REPL's local meaning when a remote RPC
   client is present.  The adapter emits their original input; it does not
   interpret or execute them."
  #{'connect 'disconnect 'help 'quit 'repl-state})


(def rejected-command-heads
  "Commands the REPL does not have.  `(telemetry)` is out in every form, so
   the adapter names the rejection as an event rather than doing nothing locally
   or sending the line to a remote evaluator that would report it as a missing
   var."
  #{'telemetry})


(defn state
  "Create adapter state around explicit, caller-owned RPC client state.
   `:events` is an unpublished outbox; use `take-events` to consume it once."
  [rpc-state]
  {:yin.repl.adapter/rpc rpc-state
   :yin.repl.adapter/events []})


(def init-state state)


(defn eval-request
  "Construct the apply value used for a non-local input.  Normal callers use
   `submit-input`, which delegates id allocation and append handling to RPC."
  [id input]
  (apply/request id eval-operation [input]))


(defn- read-forms
  "Read the line with a non-evaluating reader on every host.  Sniffing an input
   line for a shell command must never run it; anything the data reader rejects
   is ordinary source for the remote evaluator."
  [input]
  #?(:cljd (edn/read-string (str "[" input "]"))
     :clj (edn/read-string (str "[" input "]"))
     :cljs (reader/read-string (str "[" input "]"))))


(defn command-form
  "Exactly one well-formed shell-command form, otherwise nil.  Reader failures
   intentionally mean remote input: ordinary language source must reach the
   remote evaluator unchanged."
  [input]
  (when (string? input)
    (try
      (let [forms (read-forms input)
            form (when (= 1 (count forms)) (first forms))]
        (when (and (seq? form) (symbol? (first form))) form))
      (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil))))


(defn- command-head
  "The head symbol of one well-formed shell-command form, otherwise nil."
  [input]
  (first (command-form input)))


(defn local-command
  "Return the local command symbol for one well-formed local shell command,
   otherwise nil."
  [input]
  (let [head (command-head input)]
    (when (contains? local-command-heads head) head)))


(defn rejected-command
  "Return the command symbol for one well-formed command this slice rejects,
   otherwise nil."
  [input]
  (let [head (command-head input)]
    (when (contains? rejected-command-heads head) head)))


(defn- adapter-result
  [outcome state & {:as extra}]
  (merge {:yin.repl.adapter/outcome outcome
          :yin.repl.adapter/state state}
         extra))


(defn- append-event
  [state event]
  (update state :yin.repl.adapter/events conj event))


(defn- request-outcome
  [rpc-outcome]
  (case rpc-outcome
    :dao.stream.rpc/requested :yin.repl.adapter/requested
    :dao.stream.rpc/pending-request :yin.repl.adapter/pending-request
    :dao.stream.rpc/request-undeliverable :yin.repl.adapter/request-undeliverable
    :dao.stream.rpc/invalid-request :yin.repl.adapter/invalid-input
    :dao.stream.rpc/allocator-error :yin.repl.adapter/terminal
    :dao.stream.rpc/terminal :yin.repl.adapter/terminal
    :yin.repl.adapter/transport-result))


(defn submit-input
  "Advance one input transition.

   A local command becomes an explicit event and leaves RPC state untouched, as
   does a command this slice rejects.  Other strings are submitted as a
   `:op/eval` request.  This function makes at
   most one append attempt through `rpc/request!`; it never waits or retries."
  [state input]
  (cond
    (not (string? input))
    (let [event {:yin.repl.adapter/event :yin.repl.adapter/invalid-input
                 :yin.repl.adapter/input input}
          state (append-event state event)]
      (adapter-result :yin.repl.adapter/invalid-input state
                      :yin.repl.adapter/event event))

    (rejected-command input)
    (let [command (rejected-command input)
          event {:yin.repl.adapter/event :yin.repl.adapter/rejected-command
                 :yin.repl.adapter/command command
                 :yin.repl.adapter/input input}
          state (append-event state event)]
      (adapter-result :yin.repl.adapter/rejected-command state
                      :yin.repl.adapter/event event))

    (local-command input)
    (let [command (local-command input)
          event {:yin.repl.adapter/event :yin.repl.adapter/local-command
                 :yin.repl.adapter/command command
                 :yin.repl.adapter/input input}
          state (append-event state event)]
      (adapter-result :yin.repl.adapter/local-command state
                      :yin.repl.adapter/event event))

    :else
    (let [rpc-result (rpc/request! (:yin.repl.adapter/rpc state)
                                   eval-operation
                                   [input])
          state (assoc state :yin.repl.adapter/rpc
                       (:dao.stream.rpc/state rpc-result))]
      (adapter-result (request-outcome (:dao.stream.rpc/outcome rpc-result))
                      state
                      :yin.repl.adapter/rpc-result rpc-result))))


(defn- completion-event
  [completion]
  (let [base {:yin.repl.adapter/id (:dao.stream.rpc/id completion)
              :yin.repl.adapter/op (:dao.stream.rpc/op completion)
              :yin.repl.adapter/args (:dao.stream.rpc/args completion)}]
    (if-let [response (:dao.stream.rpc/response completion)]
      (if (contains? response apply/ok-key)
        (assoc base
               :yin.repl.adapter/event :yin.repl.adapter/response
               :yin.repl.adapter/value (apply/response-ok response))
        (assoc base
               :yin.repl.adapter/event :yin.repl.adapter/response
               :yin.repl.adapter/error (apply/response-error response)))
      (assoc base
             :yin.repl.adapter/event :yin.repl.adapter/lost
             :yin.repl.adapter/reason (:dao.stream.rpc/reason completion)))))


(defn publish-completions
  "Move RPC's completion outbox into the REPL event outbox exactly once.
   The returned RPC state has an empty completion vector, so a later driver tick
   cannot publish the same correlated response or loss again."
  [state]
  (let [[completions rpc-state] (rpc/take-completed (:yin.repl.adapter/rpc state))
        events (mapv completion-event completions)
        state (-> state
                  (assoc :yin.repl.adapter/rpc rpc-state)
                  (update :yin.repl.adapter/events into events))]
    (adapter-result (if (seq events)
                      :yin.repl.adapter/published
                      :yin.repl.adapter/idle)
                    state
                    :yin.repl.adapter/events events)))


(defn poll-responses
  "Poll up to `budget` response values, then publish any correlated completion
   values as REPL events.  `:blocked` returns immediately through `rpc/poll!`.
   No host asynchronous value, callback, or scheduler is introduced."
  ([state] (poll-responses state 1))
  ([state budget]
   (let [rpc-result (rpc/poll! (:yin.repl.adapter/rpc state) budget)
         state (assoc state :yin.repl.adapter/rpc
                      (:dao.stream.rpc/state rpc-result))
         published (publish-completions state)
         state (:yin.repl.adapter/state published)
         rpc-outcome (:dao.stream.rpc/outcome rpc-result)
         outcome (cond
                   (seq (:yin.repl.adapter/events published))
                   :yin.repl.adapter/responded

                   (= rpc-outcome :dao.stream.rpc/idle)
                   :yin.repl.adapter/idle

                   (= rpc-outcome :dao.stream.rpc/terminal)
                   :yin.repl.adapter/terminal

                   :else :yin.repl.adapter/transport-result)]
     (adapter-result outcome state
                     :yin.repl.adapter/rpc-result rpc-result
                     :yin.repl.adapter/events
                     (:yin.repl.adapter/events published)))))


(defn take-events
  "Return `[events next-state]`, clearing the adapter event outbox exactly once.
   Drivers interpret local-command events locally and render response/loss events
   in their own host loop."
  [state]
  [(:yin.repl.adapter/events state)
   (assoc state :yin.repl.adapter/events [])])
