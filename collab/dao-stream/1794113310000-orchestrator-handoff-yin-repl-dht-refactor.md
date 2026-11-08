Created-GMT: 2026-11-08 14:08:30 GMT
Created-Local: 2026-11-08 21:08:30 +07
Coding-Agent: interactive (ZCode / glm-5.3-flash, master-green orchestrator seat — OUTGOING)
Session-ID: not-applicable (interactive seat)

# HANDOFF — you own the yin.repl/dao.jing boundary refactor

Role: Lead Engineering Orchestrator

Implementers:
- Model: (your model) | Assigned: 2026-11-08 21:08:30 +07 | Status: active | Rationale: seat transfer; the previous seat moves off this slice

## The one-paragraph mission

Refactor the yin.repl ↔ DHT boundary so that **yin.repl depends only on
dao.jing** — it must know nothing about `dao.jing.dht`, so any dao.jing store
implementation can be swapped out. The governing spec is
[docs/design/yin.repl.dht-composition.md](../../docs/design/yin.repl.dht-composition.md)
(master `b88bad9d`, 759 lines, 14 sections) — read it FIRST and in full. It
synthesizes two architect artifacts and the owner's verbatim rulings, and it is
the spec every commit is reviewed against. Your job is its section 9 (commit
sequence): pins → separation → budget → wiring → repair scheduling → review and
landing. Estimated **8 working days** (range 6–12; supplement section "ETA").

## The state you inherit (verified at handoff; re-derive anyway)

- **Master is `186583d9`** — green except ledgered items. The manual nightly
  20261108-08 @ `001b8844` completed: node lane **fully green** (10 h 53 m,
  every shard incl. yin.vm 7 h 23 m, yang.python 5 h 15 m); dart lane 4 reds,
  **all closed on master since** (`9032649e` + `d7bd5f85`, flutter-verified
  96/0 on shiva + jupiter at `186583d9`).
- **Ledgered, intentionally NOT fixed** (astra protocol — do not "fix" them):
  JVM `yin.repl.dht-test/rounds-larger-than-the-pending-write-bound…`
  (`dht_test.cljc:2105`; astra ruled it counts manifest addresses, not ack
  timings — publication/HEAD advancement, the seam's own symptom) and the
  php-frontend-not-on-dart design gap (catalog holds `:clojure :python`;
  porting php's ANTLR runtime to dart is an owner decision). These go green
  BY the refactor, not before it.
- **In flight from the previous seat**: the php e2e JVM diagnosis r2 (muse,
  jupiter JVM lane, session in
  collab/yang-php/1794101833000-engineer-php-e2e-diagnosis-r2.muse-wonder-1.0.stdout.log)
  — measuring the e2e namespace wall time and the stale `phpt-bug40809`
  gap pin. Collect its report first; its outcome (gap-pin fix) is a small
  independent round, not part of your slice.

## The refactor workflow (charter section 9 + supplement, in order)

1. **Invariant pins (0.5–1 day).** Spec §8. The fixtures largely exist — the
   ruling cites exact lines (`index_test.cljc:385/421/470/501/553/734`,
   `require_test.cljc:244–766`, `query_test.cljc:283/314/663`,
   `store_test.cljc:833–1425`). Parameterize the non-DHT fixtures; make
   publication-result expectations explicit. Gate: existing invariants green,
   new expectations explicit.
2. **Engine/composition separation + the four adapters (1.5–2.5 days).** Spec
   §6 (coupling inventory — your checklist) and §7 (adapter dispositions).
   The supplement's tables name every touchpoint with its disposition.
   Gate: engine owns no DHT state or dependency; local synchronous behavior
   preserved.
3. **Generic byte-budget enforcement (1.5–3 days).** Spec §5. Bulk,
   incremental, restore, and reuse paths (supplement: the recorder holds
   emitted blobs; reuse-graph verification needs the same policy). Gate:
   oversize refusal, terminating branch construction, policy compatibility,
   unchanged manifest.
4. **Composition wiring (1–1.5 days).** Spec §3–4. CLI, recovery, shutdown.
   Gate: HEAD ordering, hydration admission, resource ownership on every host.
5. **Repair scheduling measurement and tuning (0.5–1.5 days).** Gate: fresh
   writes progress, queues bounded, ack and ticker-idle fixtures pass.
6. **Independent review + host matrix + landing (1–2 days).** Both swappability
   gates (spec §10) and all affected acceptance tests pass. Reviewer: astra
   (codex CLI, `-s read-only`) — it authored the charter; the implementation
   rounds below are muse-family, so astra is the independent reviewer.

## Fleet reality (routing-status.md is authoritative)

- **qwen: quota exhausted until 2026-12-04** (429; recorded). Do not dispatch
  qwen before then.
- Available: **muse** (`muse exec --trust-workspace --approval-mode never
  --model muse-wonder-1.0`, add `--session-id <your-uuid>`; implementation
  rounds 30–90 min), **codex/astra** (`codex exec -m gpt-6-astra -s read-only
  --json - < prompt-file`, review/adjudication rounds 1–2 h), **agy**
  (unchecked this session).
- Family independence: muse-authored → codex/astra reviews. This session's
  muse-authored fixes (30f7aaf7, e0fa8b0a, 9032649e, d7bd5f85) have a PENDING
  astra review — schedule it alongside step 6, or earlier if fleet frees.

## Rules that bit the previous seat — do not repeat them

1. **Focus rule (owner, sharpened 2026-11-07/08): run the failing TEST, never
   the namespace, never a suite.** Node `--test=ns/var`; JVM `-v ns/var`
   (fallback `-n ns` only when the failing var is unknown — narrow as soon as
   it is); dart `bb src/dev/cljd_agg.clj --only ns[/test-name]` (the
   NS/test-name form now works — flutter `--plain-name`). A wide run requires
   an explicit owner proposal first.
2. **Suites run on shiva/jupiter, never the laptop** (owner rule). Individual
   tests may run locally. The owner did authorize slow-test reproductions
   locally twice this session — treat as case-by-case, never default.
3. **One clone per concurrent round.** Two editing seats shared
   `~/ci/datomworld-nightly-node` this session and produced unattributed
   residue (a `dht.cljc` repair-slots 48→40 edit and a `shadow-cljs.edn`
   7168 tweak — still uncommitted on shiva; RECONCILE before your first node
   dispatch: they are not on any branch). Killed rounds: clean their clone
   state at kill time.
4. **Verification validity**: a host clone with uncommitted edits + a
   recompiled bundle makes greens meaningless (one such batch was discarded
   tonight). Checkout clean, verify `git status --porcelain` is empty and the
   HEAD sha, recompile, then run.
5. **The three environment-hygiene lessons**: fresh clones need `mise trust`;
   `node-tests.js` bundles go stale after checkout (recompile before any
   selector run); stale `cljd-out` produces phantom compile errors (wipe
   `test/cljd-out lib/cljd-out build/cljd-agg .dart_tool` when a load fails).
6. **Delegates**: never double-background; headless approval flags do not
   survive a qwen `--resume` (fresh session with inline context is the
   recovery); verify a dispatch's prompt-file path before launching (one
   round ran against a wrong filename tonight). Kill delegates by exact PID.
7. **collab/ is append-only**; artifacts file into their topic folder at
   birth; killed rounds get a Status-Event line, not silence.

## Standing owner rulings that bind you (verbatim where quoted)

- "yin.repl depends only on dao.jing. It should not know anything about
  dao.jing.dht so that any implementation of dao.jing can be swapped out."
- "dao.stream is framed as a stream of bytes" — byte-stream foundation;
  chunking/framing is transport-layer. (This superseded the earlier
  chunk-contract addendum.)
- Frame budgets derive from the transport composition; consumers never
  hardcode transport constants; `dao.space` implements semantic page cuts.
- Fix-green → merge authorization is standing for boundary-neutral fixes;
  for THIS refactor, the charter's own merge gates (spec §9) govern.
- The native-recursion ledger entry (host helpers recursing over deep
  structures: V8 trace-hash, JVM php corpus, cljd mark_tails lowering) is a
  related-debt section in the spec (§13) — do not fix in this slice unless a
  step touches the same file.

## Session trail of the previous seat (historical claims; verify anything you rely on)

orchestrator-logs/1794035976-zcode-glm-5.3-flash-master-green-recovery.md —
the master-green recovery arc: node-shard runner defects, the CESK audit
(values.cljc -hash / engine.trace culprit), the stream_eval summary fix, the
php-dart gates, the nightly 20261108-08 triage, the charter and supplement.
Key collab artifacts: the charter and supplement (collab/dao-stream/
1794062607000 and 1794109245000), the CESK audit findings
(collab/yin-vm/1794071929000), the QA brief (collab/general/1794033000000).

## First three actions

1. Read the spec (docs/design/yin.repl.dht-composition.md) end to end; file
   any objection to the charter as an astra question BEFORE touching code.
2. Collect the php e2e diagnosis report (or its partial state) and dispatch
   its follow-up if the gap pin is confirmed.
3. Dispatch the invariant-pins round (muse, spec §8) on a fresh scratch worktree;
   begin the commit sequence on a refactor branch cut from master
   (186583d9), named e.g. `yin-repl-dht-composition`.

Begin your first response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <your agent>
Session-ID: <yours>

Then report: delegated roles/models, prompts and session IDs, verified
findings, test outcomes, unresolved risks, and the slice's readiness state.
Append the final handoff entry to YOUR OWN session log (never append to the
previous seat's log) before responding.
