Created-GMT: 2026-10-01 17:30:00 GMT
Created-Local: 2026-10-02 00:30:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (linker transfer test)

# Task: Re-establish the Linker-Level Transfer Test (spec criteria 5a/10)

Role: QA & Verification Engineer (ZCode subagent, GLM-5.3-Flash)

Worktree: /Users/sto/workspace/datomworld-linker-transfer (branch
linker-transfer @ d93249cc; ANTLR parser and node reader pre-built).

The completion audit (collab/1790871424000-qa-yin-vm-linker-spec-completion-audit.md)
found: the linker-level remote/WebSocket transfer test was retired with
dao.jing.remote (8894d81f) and never re-established through the linker —
spec section 11 criteria 5 and 10's second clause are partial. The
restored criterion 5a (yin.vm.linker.md, the B6-criteria restoration
commit 1df123d1): "a host holding only the identity (H or R) and an
index obtains the image over a stream; the JVM-to-Dart transfer over
remote streams exercises exactly that starting state."

Task: implement the transfer test through the landed linker, per the
landed machinery:
- Publisher leg (JVM): publish a module via the linker (yin.link/publish
  or the link runtime), obtaining the manifest/identity.
- Receiver leg (Dart): a Dart-side client holding ONLY the identity (H
  or R) and the index obtains the image over the remote stream
  (dao.stream ws/ring paths, the slice-peer pattern from
  test/yin/vm/linker/dht_end_to_end_test.cljc), verifies it
  (identity-matches), loads it, executes it, and asserts B0-equal
  results to local execution.
- Cover H and R (both contract-pinned formats), and the refusal cases:
  a swapped/foreign identity, a corrupt payload.

Constraints:
- New test file(s) under test/yin/vm/linker/ only; no src/ changes
  unless the machinery genuinely lacks a hook (then STOP and report
  BLOCKED with evidence).
- Pure ASCII, <= 80 columns; cljstyle/kondo clean; no commit/stage; no
  checkout/reset/stash; JVM tooling under mise.
- Lanes: bb test:clj, bb test:cljs, bb test:cljd — solo, sequential;
  report exact counts (the tree's baseline: JVM 2,839/225,791/0;
  Node 2,656/91,265/0; CLJD 2,568 passed).
- A peer orchestrator owns yang.antlr and yang.python — do not touch
  those paths.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
