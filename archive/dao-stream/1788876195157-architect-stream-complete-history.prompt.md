Created-GMT: 2026-09-08 14:03:15 GMT
Created-Local: 2026-09-08 21:03:15 +0700 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: 6378e1b0-1c5d-4e10-8ea0-61551c03c828
# Task: settle in dao.stream.md how a consumer requiring complete history expresses that
Role: Lead System Architect

**Contract task. The transactor/index plan is blocked on this and must not be
implemented until it lands.** Write the design text; do not touch code.

## The defect, found by review of your plan

Your plan's full-history invariant is "read from position zero" via a freshly
minted `:dao.stream/oldest`. **Under v2 that is not what `:oldest` means.**
`v2/ringbuffer.cljc:62` returns `(:first s)` — the earliest *currently
retained* position, which advances on eviction — and `dao.stream.md:475` says
so: "the earliest retained position". A `gap` is reported only to a cursor
held from *before* the eviction. Mint `:oldest` fresh afterwards and you read
the surviving suffix and are told nothing is wrong.

So today:

- a reopened transactor can derive `next-t` from an incomplete suffix and
  silently break the causality guarantee `derive-next-t` claims to enforce;
- `publish-index!` can silently publish an incomplete index;
- and no test can catch either, because the loss is unobservable.

You already knew the underlying fact — it is why `query_test`'s gap test uses
a scripted reader rather than a ring buffer — but it did not carry into a plan
whose central invariant depends on it.

## Why this is a contract question, not a plan question

`dao.space` is not the only consumer that will ever need *complete* history
rather than *retained* history. The contract has no way to say so, and the
absence is what let the plan state an invariant v2 cannot deliver.

## The vocabulary already there — use it, do not invent

1. **Declared, never interrogated.** `dao.stream.md:424-429` already
   establishes this for deposit admission: "the contract's public surface has
   no way to ask a handle its retention policy. A constructor takes the
   deposit destination and its admission declaration as data. This is
   configuration provenance, not runtime introspection."
2. **Outcome exclusion with a stated reason.** `:441-446` already gives the
   exact case: "the outcome's precondition is impossible by the transport's
   nature (**an unbounded log never evicts, so never reports `gap`**)."
3. **Kept cursors.** The `seek` absence (`:700`) rests on "anchored minting
   and kept cursors cover repositioning."

## What the text must settle

1. **Name the distinction** plainly: retained history is not complete history,
   and `:oldest` is an anchor into the former. Say it where a reader looking
   for "how do I read everything" will hit it — the *Cursors* section is the
   likely home.
2. **Say how a consumer requiring completeness gets it.** I see two
   mechanisms already sanctioned by the contract, and I want your judgement on
   whether both stand or only one:
   - **an origin cursor**, minted before the first append and kept, so a later
     read either succeeds or reports `gap` — converting silent loss into a
     reported outcome; and
   - **a transport that declares it excludes `gap`**, i.e. never evicts,
     which is the existing declaration mechanism and needs no new surface.
   If both stand, say when each is right.
3. **Rule on the `dao.space` implication.** An agent's local stream is its
   durable log — `derive-next-t` calls it "the causality boundary" — and it is
   currently a ring buffer, a transport whose declared nature is to evict. Is
   putting a log on an evicting transport simply a wiring defect? If so, say
   what `dao.space` must wire instead, and note that this is what the
   transactor plan must then require. Do not design that transport here; say
   what it must declare.
4. **Do not add a predicate.** Anything that lets a consumer *ask* a handle
   about its retention violates both "declared, never interrogated" and the
   reason `closed?` is absent.
5. **Say what this costs.** If existing v2 transports cannot serve a
   complete-history consumer, say which and what that implies — honestly,
   including for the ring buffer.

Keep it proportionate: this is a contract clarification, not a redesign.
Emit the exact prose to add or change, with the section each belongs in, to
stdout. Start with the Completed-GMT/Local, Coding-Agent, Session-ID header.
