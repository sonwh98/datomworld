(ns thetao.vm.algebra-vm
  (:require
    [thetao.vm.algebra :as algebra]
    [thetao.vm.common :as common]))


(defn create-vm
  ([] (create-vm {}))
  ([opts]
   (common/create-base-vm :algebra opts)))


(defn load-program
  [vm ast]
  (common/load-program vm algebra/compile-program ast))


(defn enqueue-root
  [vm]
  (common/enqueue-root vm))


(defn step
  [vm]
  (common/step vm))


(defn run
  [vm]
  (common/run vm))


(defn execution-stream
  [vm]
  (common/execution-stream vm))


(defn event-stream
  [vm]
  (common/event-stream vm))


(defn fact-stream
  [vm]
  (common/fact-stream vm))


(defn effect-stream
  [vm]
  (common/effect-stream vm))


(defn effect-response-stream
  [vm]
  (common/effect-response-stream vm))


(defn content-store
  [vm]
  (common/content-store vm))


(defn quiescent?
  [vm]
  (common/quiescent? vm))


(defn resume-continuation
  [vm cont-ref value]
  (common/resume-continuation vm cont-ref value))


(defn drain-events
  [vm]
  (common/drain-events vm))
