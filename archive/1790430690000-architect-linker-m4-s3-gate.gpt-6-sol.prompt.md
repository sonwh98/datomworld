Created-GMT: 2026-09-26 11:11:30 GMT
Created-Local: 2026-09-26 18:11:30 +0700
Coding-Agent: codex
Session-ID: n/a (codex thread id captured from stdout)

# Task: gate for M4 slice S3, require lowering and the install child (Claude-authored, non-Claude reviewer)

Role: Adversarial Code Reviewer and Security Auditor + Lead System Architect (combined commit gate)

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-26 18:11:30 +0700 | Status: active | Rationale: owner authorization "codex and claude has the most budget. dispatch the reviewers"; author was claude-opus-5-5, so the reviewer is a different model family or at least a different model

You are a HEADLESS read-only reviewer. Produce the COMPLETE review in your final response now. Do not wait for approval, do not ask questions, do not end with a plan or promise of a verdict. Do not edit files. Do not rerun the test suites (orchestrator ran them). Cite file:line. Findings are ranked P0 (blocks commit), P1 (fix before merge), P2/P3 (follow-up). End with an explicit verdict line: APPROVE / APPROVE-WITH-FIXES / REJECT, and the list of must-fix items.

Rules in force: Rule R (yin/def is syntax, never a name; only yin/def reserved); require is an ordinary function that lowers to the linker; no contract stamp is ever assigned to external input; code is content-addressed, names come from dao.space via authority policy; no backward-compat shims (dev-only repo); ClojureDart trap: #?(:clj ...) does NOT exclude code from cljd builds. The report you are handed was written by the implementer and is UNTRUSTED: verify every claim against the diff.
Reference: docs/design/yin.vm.linker.md (master spec), docs/design/yin.vm.universal-continuation-format.md (UCF).

## What is under review
Worktree /Users/sto/workspace/datomworld-m4-s3 (branch m4-s3, from master 9428c3d2).
Uncommitted work: 11 tracked files modified (+1677/-184) plus NEW test/yin/vm/linker_require_test.cljc (19 tests). Read with `git -C <wt> diff HEAD` and the files directly (untracked: read directly).
Scope per spec: require lowers to the linker, the install runs as a scheduler child task via attach-image (S1) and register-host-module (S2), link-module in module.cljc, engine.cljc changes, kernel changes.
Orchestrator evidence: JVM 2160 tests, Node 2073, Dart all passed, kondo/cljstyle clean. One JVM flake (dao.stream.ws.jvm-test, unrelated websocket test, passes in isolation) is tracked separately, ignore it.
Things to scrutinize:
(a) The orchestrator patched engine.cljc ~line 476 AFTER the implementer finished: `(type x)` in the encode :else branch failed to compile on ClojureDart, so it is now `#?(:cljd (str (.-runtimeType x)) :default (str (type x)))`. Check it and look for other cljd-unsafe forms in the diff.
(b) The implementer also fixed an S1 register-kernel bug (entries parked after attach-image were refused as corrupt). Confirm the fix is right and tested.
(c) Deliberately NOT done: private :resources table (r8), resource lowering (r9), sealed references (r10/r11); stream/cursor references in lift fail closed. Verify they really fail closed and that the untested :foreign-image path cannot be reached to violate an invariant. Read the report's Deviations and What is left sections and judge each deviation.
(d) Rule R and store-write audit: the diff edited attach_image_test, rule_r_test and the store_write_audit_test allowlist; check the allowlist change is minimal and justified.
(e) Any place where require becomes more than an ordinary function, or a name resolves outside the authority policy.

Implementer's report (untrusted): /Users/sto/workspace/datomworld/collab/1790416871816-vm-engineer-linker-m4-s3-require-install.claude-opus-5-5.report.md
