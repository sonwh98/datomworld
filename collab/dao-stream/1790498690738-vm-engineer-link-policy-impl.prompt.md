Created-GMT: 2026-09-27 15:45:00 GMT
Created-Local: 2026-09-27 22:45:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (link-policy implementation)

# Task: Implement yin.repl.link-policy.md (owner-adopted, Option C)

Role: VM Runtime Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master; slices 0-3
of dao.stream.remote are committed; slice 4 is IN FLIGHT in the working
tree by another agent — do NOT touch src/cljc/dao/jing/**,
src/cljc/yin/vm/linker.cljc, or their tests).

Implement docs/design/yin.repl.link-policy.md EXACTLY (status: ADOPTED;
read the whole document first — sections 1-6 are the contract,
including the view shape, consult timing, answer semantics,
misbehaving-policy fail-safe, state summary, and section 4's driving
question you must settle by reading the host drivers).

Work items:
1. :link-policy on yin.repl/create-state (:manual default; a function
   (fn [view]); :lease REFUSED in phase 1 with an ex-info naming the
   supported values; anything else refused at assembly). Kept on the
   shell beside :link-source so (reset) and (vm ...) preserve it.
2. The view: {:links [{:name ... :link-id ...}] :checks n
   :lines-retained m} — plain data, no clock, no handle, no secret.
3. Consult timing: when a require parks (:checks 0) and after each
   re-check that leaves the run pending; never on completion.
4. Answers: :keep; :abandon / {:abandon reason} runs the SAME abandon
   path as (abandon) (installs in flight refused, wait entries retired
   with the reason as the require's error, identity carried onto the
   base, retained lines dropped and reported once); the printed
   message says the session policy ended the require. Default reason
   :yin.repl/link-policy; (abandon) keeps :yin.repl/abandoned.
5. Misbehaving policy: a throw or out-of-contract return is treated as
   :keep for that consult and surfaces as one shell error line — never
   a silent abandon, never session death.
6. State summary: repl-state's :pending gains the policy name and
   :checks.
7. Settle section 4 by reading yin/repl/main.cljc and
   yin/repl/driver.cljc: if they can already trigger a re-check
   without an input line, document it; if not, add the small public
   re-check step function the design names (no clock, no callback).
8. Tests in the existing yin.repl test files covering: the default
   :manual behavior unchanged; a function policy abandoning after n
   no-progress checks (with reason); nil pass-through; the
   misbehaving-policy fail-safe; :lease refused at create-state;
   reset/vm preserving the policy; retained-line semantics on a policy
   abandon identical to (abandon).

Constraints:
- The design doc is the contract; implement exactly. Genuine ambiguity:
  minimal reading, noted in your report.
- Allowed files: src/cljc/yin/repl.cljc (or yin/repl/* as structured),
  yin/repl/main.cljc, yin/repl/driver.cljc, and the existing yin.repl
  test files. Touch nothing under dao/jing/** or yin/vm/linker.cljc
  (another agent owns them right now).
- Pure ASCII, <= 80 columns on every added/edited line; cljstyle and
  kondo clean; no commit/stage/checkout/reset/stash; no leftover
  diagnostics.
- Verify: JVM full suite green (baseline moves with slice 4's in-flight
  work — measure the true baseline at your start, run it via mise, and
  report your baseline AND your final counts), Node green, Dart green.
  Sequential, solo. If a lane failure is caused by slice 4's in-flight
  tree (not your files), report which failures those are with evidence
  and run the yin.repl-focused namespaces to prove YOUR changes green.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
