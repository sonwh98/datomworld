Created-GMT: 2026-09-25 17:40:00 GMT
Created-Local: 2026-09-25 00:40:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (step-cljs-refused-guard fix)

# Task: Root-cause and fix the Node-side refusal-guard failure in dao.jing.remote.step

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Context: the round-3 consensus fix swapped the remote get-content client
receipt in /Users/sto/workspace/datomworld
(src/cljc/dao/jing/remote/step.cljc:305) from jing/segment-value to strict
cbor/decode, keeping the existing refusal handling:

  decoded (when bs
            (try (cbor/decode bs)
                 (catch #?(:cljd Object :clj Throwable :cljs :default) _
                   ::refused)))
  (if (or (nil? bs) (identical? ::refused decoded)) <error arm> <value arm>)

Read the full context in
collab/1790267002049-reviewer-daojing-cbor-swap-fixes-r3.glm-flash.findings.md
(P1) and the new pinning test a-hash-valid-noncanonical-payload-is-an-
integrity-failure in test/dao/jing/remote/step_test.cljc.

Observed behavior (verified by the orchestrator):
- JVM: the test passes; focused namespace 18 tests / 103 assertions / 0
  failures; full JVM 1,995 tests / 180,201 assertions / 0 failures.
- Node (shadow-cljs :node-test build): the test FAILS. Expected the
  integrity-failure error completion; actual completion is
  {:id 0, :op :jing/get-content, :found? true,
   :value :dao.jing.remote.step/refused}
  A temporary diagnostic in the test (to be removed by you) confirms the
  published :value is a cljs.core/Keyword printing as
  :dao.jing.remote.step/refused — i.e. the sentinel reached the value arm
  while the (identical? ::refused decoded) guard evaluated false on Node
  for a keyword that prints identically.

Task:
1. ROOT-CAUSE why identical? misses the sentinel on Node. Leading
   hypothesis: two distinct cljs Keyword instances sharing one fqn exist in
   the compiled build (keyword interning not holding across the compiled
   catch literal and the guard literal under shadow-cljs), making
   identical? false while = stays true. Verify empirically against the
   actual build — do not settle for theory.
2. Fix the guard so it cannot depend on interning: e.g. compare with =
   (Keyword equiv compares fqn), or restructure the refusal to a boolean or
   a unique non-decodable sentinel (a closed record/reify object). Note the
   closed CBOR profile never decodes a keyword, so a keyword sentinel stays
   value-safe once equality is correct. Keep JVM, CLJS and CLJD correct.
3. Remove the temporary #?(:cljs js/console.log "DBG" ...) diagnostic from
   step_test.cljc.
4. Scan dao/jing/remote/step.cljc for other identical?-on-keyword-sentinel
   guards with the same latent hazard; fix any you find in this file.
   Report (do not fix) the same pattern elsewhere in src/cljc/dao/.

Constraints:
- Touch only src/cljc/dao/jing/remote/step.cljc and
  test/dao/jing/remote/step_test.cljc.
- Pure ASCII, <= 80 columns on every line you add or edit.
- Do NOT commit.
- Verify: (a) JVM focused — mise exec -- clojure -M:test -n
  dao.jing.remote.step-test; (b) Node — mise exec -- clj -M:cljs -m
  shadow.cljs.devtools.cli compile slice-peer test (full suite must be 0
  failures). Report both counts.
- If root-causing shows the guard was never the problem (i.e. something
  else publishes the sentinel), follow the evidence and say so plainly.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
