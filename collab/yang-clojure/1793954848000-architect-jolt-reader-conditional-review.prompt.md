Review brief for Lead System Architect (`gpt-6.1-sol`):

Target: `src/cljc/yang/clojure.cljc` reader conditional libspec declaration in `ns` form.

Context:
In commit 873c8bcd, reader conditionals inside `(:require ...)` in `src/cljc/yang/clojure.cljc` were structured as:
```clojure
  (:require #?(:cljd nil
               :clj [clojure.tools.reader :as treader]
               :cljs [cljs.tools.reader :as treader])
            #?(:cljd nil
               :clj [clojure.tools.reader.reader-types :as rt]
               :cljs [cljs.tools.reader.reader-types :as rt])
            [yang.core.call :as call]
            [yang.core.uast :as u]
            [yin.vm :as vm]))
```

Inbound report from Jolt orchestrator seat (`collab/yang-clojure/1793954629000-orchestrator-jolt-to-yangclojure-reader-conditional-fix.md`):
1. In Jolt's reader, when reading `#?(:cljd nil :clj ...)`, because `:jolt` is not matched first, Jolt falls back to the `:clj` feature branch and attempts to load `clojure.tools.reader` (which is an mvn dependency not present on Jolt's source root).
2. Placing `nil` inside `(:require ...)` leaves bare `nil` tokens in the libspec sequence for host readers that don't elide them.
3. Recommended solution: Splicing reader conditional `#?@` placing `:jolt []` and `:cljd []` explicitly ahead of `:clj`:
```clojure
  (:require #?@(:jolt []
                :cljd []
                :clj [[clojure.tools.reader :as treader]
                      [clojure.tools.reader.reader-types :as rt]]
                :cljs [[cljs.tools.reader :as treader]
                       [cljs.tools.reader.reader-types :as rt]])
            [yang.core.call :as call]
            [yang.core.uast :as u]
            [yin.vm :as vm]))
```

Questions for Architect:
1. Does this splicing form preserve all portability invariants across JVM, CLJS (Node/Browser), ClojureDart, and Jolt?
2. Does it cleanly prevent `:clj` fallthrough in Jolt and empty/nil token issues in `ns :require`?
3. Is it safe to commit and push to master to unblock Jolt's J2 verification lane?
