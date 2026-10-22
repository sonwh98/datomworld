(ns random-load (:require [yin.vm.linker.random :as random]))
(random/rand-bytes 32)
(println "SEED-RETURNED")
