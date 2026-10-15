(ns yin.vm.ucf
  "The A of a canonical vector: UCF mints no second addressing scheme.
   Code identity delegates to dao.jing's canonical content address."
  (:require [dao.jing :as jing]))


(defn code-address
  "The dao.jing segment address of a canonical vector. UCF mints no second addressing scheme."
  [v]
  (jing/segment-key v))
