Created-GMT: 2026-10-24 11:37:20 GMT
Created-Local: 2026-10-24 18:37:20 +07
Coding-Agent: claude
Session-ID: C34FEBEA-30A9-4DC6-97C8-75FF6B67F0AD (resumed)
# Task: dao.star Mode 1 — reconcile two review findings against your rulings
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-24 18:37:20 +07 | Status: active | Rationale: the two findings challenge your own scoping and client-close rulings; the weighing is yours

Read-only. The codex adversarial review of the implemented Phase A+B returned
NOT SIGNED OFF (`collab/1792840752497-reviewer-dao-star-m1-phase-ab.gpt-6-1-sol.findings.md`).
Two of its HIGH findings challenge rulings you made. Rule on each with
reasons and rejected alternatives; the implementation fixes for the other ten
findings are already commissioned separately. Do not edit files.

**Finding 3 (against your client-close ruling (a)).** Your ruling said "a
dead peer that http-kit hides simply stops being useful to anyone, so the
responder's own control flow stops renewing for it and the lease lapses."
The reviewer's challenge: mechanically, nothing stops the driver — the
subscription stays in its `:subs` (no `:http/closed` is ever deposited on
this host), so `tick-subscriptions!` keeps renewing the exchange lease every
tick, and the retained subscription keeps the lineage lease renewing too
(`driver.cljc:343`). Neither lease therefore lapses by `:silence`, and the
amended Sizing sentence ("the exchange lease's duration is also the
dead-peer detection bound") is unsupported. Candidates:
  (i) A buffer-fill mechanism: keep-alive chunks are real bytes; http-kit
      buffers them, but the buffer and the TCP window are finite, so at some
      point `kit/send!` must answer false or close the channel — detection
      by writing, with a latency bound by buffer capacity rather than lease
      duration. Verify this hypothesis is sound (the Phase B repro saw 40
      buffered writes answer true; nobody has driven it to exhaustion). If
      sound, the Sizing sentence changes to name the real bound and the
      keep-alive becomes the detector on such hosts.
  (ii) Accept that on such hosts an idle dead subscription is detected only
      when the lineage ends (halt, bound, or the client's reconnect closing
      the old exchange some other way) and the spec text must say so
      honestly — no detection bound at all.
  (iii) Another mechanism you supply.
Rule, and give the exact replacement sentences for `dao.star.md`'s
Keep-alive and Sizing paragraphs and `dao.stream.http.md`'s Honest Limit if
your ruling amends them again.

**Finding 4 (against your Phase B scoping ruling 3).** You ruled the
session-facts medium is a `dao.stream.ringbuffer` (evict-oldest). The
reviewer's challenge: the ratified `dao.star.md` says "The session-facts
medium is a complete-history transport (`dao.stream.md`, Complete history);
its growth is bounded only by OD-4's resolution" — an evicting ring buffer
contradicts the ratified spec and the controlling contract, and eviction
loses roots, which breaks lineage grants and replay (`dao.stream.md`
explicitly says sizing cannot substitute for complete-history retention).
The spec wins on disagreement (`dao.stream.md` is controlling; `dao.star.md`
is ratified). Confirm or reject: the session-facts medium must be a
declared complete-history transport (e.g. the repo's non-evicting memory
log), required at assembly, with compaction still deferred to OD-4. If you
confirm, name the exact constructor and declaration the composition and the
Phase B tests must use (look at `src/cljc/dao/stream/memory_log.cljc` and
how "complete history" is declared there).

Begin the final response exactly with:
Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and timezone>
