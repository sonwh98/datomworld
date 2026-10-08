Completed-GMT: 2026-09-26 23:01:14 GMT
Completed-Local: 2026-09-27 06:01:14 Indochina Time

P1 | [src/cljc/dao/stream/observe.cljc:51](/Users/sto/workspace/datomworld/src/cljc/dao/stream/observe.cljc:51) | An unrecognized outcome is converted to `transport-error`, then classified as a defect or failed effect. This contradicts the [contract’s unrecognized-outcome rule](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:113). | Preserve a well-formed unrecognized outcome and classify it as refused; retain malformed-answer handling separately.

P1 | [src/cljc/yin/vm/engine.cljc:1373](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:1373) | A parked `:next` or `:put` waking with an unrecognized outcome reaches `throw-terminal-resume!`, while the [immediate path returns a shaped refusal](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:390). The blocked-must-not-change law therefore fails. | Shape unrecognized wake outcomes as refusals and allow them through resume; keep declared error outcomes terminal.

The reported JVM, Node, and Dart suites passed; I did not rerun them.

Verdict: REQUEST CHANGES
Sign-off: DENIED