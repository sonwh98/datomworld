(ns thetao.vm.events)


(defn project-d3
  [[e a v _t _m]]
  [e a v])


(defn event-datom
  [t event]
  [:thetao/vm :thetao/event event t 0])


(defn hash-datom
  [entity-id hash t]
  [entity-id :thetao/hash hash t 1])
