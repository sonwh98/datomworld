#!/bin/sh
# Read-only probe: run the handoff round-trip script on the :test classpath.
cd /Users/sto/workspace/datomworld-srvm-p6cpre || exit 1
CP=$(clojure -Spath -A:test)
exec clojure -Scp "$CP" -M -i collab/6cpre-reviewer-handoff-repro.clj -e '(println :done)'
