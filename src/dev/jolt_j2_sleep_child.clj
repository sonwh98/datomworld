;; A deliberately hanging core-mode "runner" for the J2 timeout self-check
;; (src/dev/jolt_j2_timeout_check.clj): run under jolt in place of
;; src/dev/jolt_test_runner.clj, it prints one line and sleeps far past any
;; test deadline, so only the driver's watchdog can end it.
(println "SLEEP-CHILD started; sleeping 600 s")
(flush)
(Thread/sleep 600000)
(println "SLEEP-CHILD woke up: the watchdog did not kill it")
