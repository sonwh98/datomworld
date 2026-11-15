(ns dao.stream.udp
  "The UDP channel of dao.stream.remote.md (3.2), rebuilt on the raw
   datagram layer (docs/design/dao.stream.datagram.md 7): the WebSocket
   deposit model over a socket that retains nothing, with the raw layer
   beneath it feeding and carrying it.

   Public facade delegating host-free value-channel logic to
   `dao.stream.udp.common`."
  (:require [dao.stream :as stream]
            [dao.stream.udp.common :as common]))


(def transport-type common/transport-type)
(def max-datagram common/max-datagram)
(def default-max-message-bytes common/default-max-message-bytes)
(def default-max-partial-messages common/default-max-partial-messages)
(def default-step-budget common/default-step-budget)

(def descriptor? common/descriptor?)
(def address-key common/address-key)
(def make-port common/make-port)
(def send-value! common/send-value!)
(def send-to! common/send-to!)
(def receive! common/receive!)
(def projection common/projection)
(def step! common/step!)
(def reading-cursor common/reading-cursor)

(defn port-step!
  ([port] (common/port-step! port))
  ([port budget] (common/port-step! port budget)))


;; =============================================================================
;; The writer handle and attacher
;; =============================================================================

(deftype UdpHandle
  [port host dest-port descriptor]

  stream/IDaoStreamDescriptor

  (descriptor
    [_]
    {:dao.stream/outcome :dao.stream/ok
     :dao.stream/descriptor descriptor
     :dao.stream/identity (:dao.stream/identity descriptor)})


  stream/IDaoStreamWriter

  (append!
    [_ v]
    (common/send-value! port host dest-port v)))


(defn make-attacher
  "Construct the host-composed unary `attach!` entry point: `:port` is
   an already-composed `make-port` value. `attach!` binds or reuses it
   -- one port serves every descriptor a composition attaches through
   it -- and answers at once with a writer handle whose `append!` sends
   toward the descriptor's address."
  [config]
  (common/make-attacher ->UdpHandle config))
