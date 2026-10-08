(ns yin.vm.linker.head.board
  "The head board's composition (docs/design/yin.vm.linker.dht.head.md
   5.1, section 6), over `dao.stream.remote-channel`.

   What stays here is the domain residue: the board's name, its
   one-entry read-only exposure table, the board path, and the loopback
   gate until it is lifted.  Endpoint, sessions, dial, liveness and stop
   are the stepped channel composition's, below dao.stream; this
   namespace hands it a portable endpoint specification `{:host h :port
   p}` and a host assembly `{:connect! :bind! :unbind!}` opaquely, and
   knows no transport.

   * `serve` serves the board under the ring's own identity with surface
     #{:reader}, and the board name mapped to that identity; `serve-step`
     drives it at the driver's `now`; `stop!` initiates an explicit stop
     that `serve-step` completes.
   * `dial` resolves the board name of a principal and attaches what it
     answers; `dial-step` drives it at `now`, the link's deadline
     included, so a board that stops answering is `:lost`; `handle` is
     the reflection once attached, a `dao.stream` reader handle the
     follower (`yin.vm.linker.head/attach`) takes like any other;
     `close!` ends the dial.

   A name is lookup data and never a :dao.stream/identity: the board
   name appears only in the name map and in the named resolve, and the
   reflection reports the ring's own identity.  Nothing here reads a
   clock or schedules itself."
  (:require [dao.stream :as stream]
            [dao.stream.remote-channel :as remote-channel]))


(def path
  "The board endpoint's path: domain naming, kept here."
  "/head")


(defn board-profile
  "The bounds profile the board composes with: `bounds` merged over
   `dao.stream.remote-channel/production-bounds`."
  [bounds]
  (merge remote-channel/production-bounds bounds))


(defn board-name
  "The board name of `principal`: lookup data, never an identity."
  [principal]
  (str "yin.head/" principal))


(defn- board-spec
  [spec]
  (assoc spec :path path))


(defn serve
  "Serve `:board`, the board ring of `:principal`'s publisher, at the
   portable endpoint `:spec` `{:host h :port p}` over the host assembly
   `:host`, with `:bounds` merged into the board profile.  Answers the
   `dao.stream.remote-channel` server as it is, plus `:identity` (the
   ring's own) and `:name` (the board name).  A spec host that is not a
   loopback literal is refused before anything is composed,
   `{:status :refused :reason :yin.head/not-loopback}`; every other
   refusal is the channel composition's."
  [{:keys [board principal spec host bounds]}]
  (if-not (remote-channel/loopback-literal? (:host spec))
    {:status :refused :reason :yin.head/not-loopback :spec spec}
    (let [identity (:dao.stream/identity (stream/descriptor board))
          n (board-name principal)]
      (assoc (remote-channel/serve
               {:spec (board-spec spec)
                :host host
                :table {identity {:handle board :surface #{:reader}}}
                :names {n identity}
                :bounds (board-profile bounds)})
             :identity identity
             :name n))))


(defn serve-step
  "Advance the board's server one tick at the driver's `now`."
  [server now]
  (remote-channel/serve-step server now))


(defn stop!
  "Initiate an explicit stop of the board's server; `serve-step`
   completes it."
  [server]
  (remote-channel/stop! server))


(defn stopped?
  "True once the board's server owes nothing: stopped, or refused."
  [server]
  (contains? #{:stopped :refused} (:status server)))


(defn dial
  "Compose the reader's end toward the board of `:principal` at the
   portable endpoint `:spec` `{:host h :port p}` over the host assembly
   `:host`; `:bounds` merges into the board profile and `:events`,
   optional, is the link's event writer.  Every dial is fresh, so a lost
   source is redialed by composing another.  Answers the
   `dao.stream.remote-channel` dial, `:status :resolving`, or its
   refusal."
  [{:keys [principal spec host bounds events]}]
  (assoc (remote-channel/dial {:spec (board-spec spec)
                               :host host
                               :name (board-name principal)
                               :bounds (board-profile bounds)
                               :events events})
         :principal principal))


(defn dial-step
  "Advance the dial one tick at the driver's `now`."
  [d now]
  (remote-channel/dial-step d now))


(defn handle
  "The dial's reader handle once attached, else nil."
  [d]
  (remote-channel/handle d))


(defn close!
  "Close the dial: its connection and its reflection.  Answers it,
   `:closed`."
  [d]
  (remote-channel/close! d))
