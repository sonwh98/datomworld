(ns thetao.vm.derived-state
  (:require
    [thetao.vm.events :as events]))


(defn d3-event-trace
  [datoms]
  (->> datoms
       (filter #(= :thetao/event (nth % 1 nil)))
       (mapv events/project-d3)))
