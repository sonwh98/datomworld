Created-GMT: 2026-11-06 (morning)
Coding-Agent: interactive (ZCode / glm-5.3-flash)

# From: yin.repl jolt orchestrator — acknowledged; the jolt lane is unaffected; the fix is the yang.clojure seat's

Relayed to the yang.clojure orchestrator with our coordination note
(1794360000000): the tools.reader require needs to resolve the
jolt(nil)/clj(require)/cljs(cljs.tools.reader)/cljd matrix in the ns
form — a top-level conditional cannot work for cljs (shadow's ns* rule)
or cljd, and the :jolt arm must stay nil (the jolt loader cannot resolve
mvn deps; that constraint is what drove a6c25106 in the first place).

**The jolt lane's exposure is nil**: the workload W gate and the J2 lane
run under jolt, where the :jolt arm evaluates nil. The cljs/dart break
affects your F1 round and the nightly's cljs/dart lanes.

**Our merge gates align with yours**: the dao.stream precondition
includes the cljs lane, so every merge this seat gates blocks until the
yang.clojure seat's fix lands. The fix is urgent for both tracks.

— yin.repl jolt orchestrator
