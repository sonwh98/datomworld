# DaoLease — design rationale

Status: companion to [`dao.lease.md`](./dao.lease.md), which is the operative
contract. **Nothing here binds.** This document records why the contract has
the shape it has: the two precedents it stands on, the Jini design it rejects,
the reasoning behind each rule, and the arguments that were considered and
turned down. Where this document and the contract appear to disagree, the
contract governs.

The contract rests on Axiom 1 (everything is a stream — a grant and its
renewals are appends), Axiom 2 (interpretation creates semantics — "expired" is
a perspective taken on facts with a clock), and Axiom 3 (code and state are
datoms — a lease's terms are readable data). It is bounded by the invariants
against callbacks, implicit control flow, and hidden global state. The
invariant against collapsing interpretation into execution binds it too but
yields no lease-specific prohibition, for the reason given under *The
prohibitions, and the fifth invariant* below.

## Precedent

A lease stands on two instances this project already holds. One supplies the
shape, the other the problem.

**[`yin.vm.jit.md`](./yin.vm.jit.md) supplies the shape** — *advisory streams
with possessor-scheduled application.* There, execution facts are emitted as
trace datoms rather than callbacks; the JIT is advisory, appending patch datoms
and mutating nothing; the VM applies patches only at safe points; the
negotiation carries an explicit status, so it is itself queryable data; and a
failed guard causes a fallback to baseline whose performance is then recorded.

**Garbage collection supplies the problem** — *reclaiming what is no longer
wanted, by a possessor that cannot ask the holder, erring toward retention.*
This is lineage rather than metaphor, and the branch matters. Leases were
introduced by Gray and Cheriton in 1989 for distributed file-cache consistency;
distributed garbage collection adopted them, through Network Objects and then
RMI's DGC, from which Jini inherited them. **That branch is reference counting
with decay** — RMI's dirty and clean calls, where a count that is not refreshed
falls away — and not tracing.

The distinction is load-bearing because this repository carries the *other*
branch: `dao.jing.md` defers content reclamation because "immutable content
otherwise accumulates forever"; `dao.data.btree.md` ships `walk-addresses`, "a
plain reachability traversal over the node graph," and calls it the mark hook
for a segment collector that does not exist yet; `dao.jing.dht.md` records
unbounded caching with "no pinning, eviction, or reclamation policy." Those are
three tracing problems awaiting a collector. A lease is not one of them and
does not become one.

A lease is where the two meet. Renewals are the JIT's trace half — evidence
emitted by the running party, proposing nothing. The negotiation is its patch
half. And the granted **duration is the guard**: the predicate *a renewal was
observed within the duration* must keep holding, is checked only at the
grantor's judgment points, and its failure causes the fallback — the resource
unheld — whose performance is then recorded.

Stated once, because it is the whole shape: **a lease is a bounded deviation
from a safe default, actively maintained by evidence, reverting on evidence
failure, with the revert performed by the possessor at judgment points its
composition schedules, and then recorded.** A patch deviates from unoptimized
execution; a lease deviates from nothing-held.

### Where the collector's problem differs from the collector's evidence

A tracing collector can *compute* whether an object is still wanted.
Reachability over a closed heap is ground truth, obtained without asking the
holder anything, and a collector that traces correctly is never wrong.

A lease has no closed heap. Its holder is remote, its evidence crosses a
medium, and silence is not proof of death. **A duration is a timeout standing
in for reachability** — the substitute a collector reaches for when it cannot
trace. Everything conservative below follows from that one substitution.

Three consequences of the substitution must be stated plainly, or the kinship
will promise what a lease cannot deliver:

- A tracing collector's evidence is **complete**; a lease's is **partial**. The
  heap ends; the network does not.
- A false free is a **bug** in a collector. **A false lapse is a possible
  outcome of a correct lease**, and the design must survive it rather than
  exclude it. A collector can promise never to free a live object; a lease
  cannot, and errs toward retention without being able to guarantee it.
- **A lease traverses nothing and collects no cycles.** It has no references to
  follow, so it cannot discover that a resource is transitively unwanted, and
  two holders leasing each other are two live leases as far as any judge can
  see. Reference counting has the same blind spot for the same reason, and the
  decay is what limits the damage rather than curing it.
- A collector needs no duration because it needs no timeout. The duration
  exists precisely because reachability is unavailable.

The repository's own three reclamation problems sit on the other side of that
line: content, segments and cached blobs are all reachable-in-principle, and
`walk-addresses` is already the mark phase for one of them. They await a
collector, not a lease. This document does not generalize over both — two
instances whose evidence differs in kind do not yet justify a shared
abstraction — and the distinction between them is the useful part.

### Divergences from the JIT precedent

The mapping is not clean, and the divergences are where this document does its
work. Three are structural; two follow.

**The guard's subject is not the applier.** In the precedent one party is both:
the VM emits the trace and applies the patches, so its guard predicates over
values present in its own hand at its own safe point. Here the holder emits the
evidence and the grantor applies the judgment, and the guard's subject is
remote with its evidence crossing a medium.

**The parties are adversarial, and every fact's author is decisive.** The
precedent's parties cooperate and its datoms need no attribution; a lease turns
on who said what, since a holder that could author its own grant would grant
itself tenure. Nothing in the precedent needed that, and it is where this
design's attribution precondition comes from (see *Why attribution is a
composition duty*).

**The revert requires an act, where the patch's revert is inaction.** A patch
needs action to *apply* and reverts by doing nothing; a lease needs action to
*revert* and persists by doing nothing. The deviation is the default state.
This is the divergence that makes judging obligatory: a VM that never accepts a
patch is a correct, slower VM, while a grantor that never judges leaks its
resource without bound — and that would remain true even if the holder were
local, so it does not follow from remoteness. (The precedent does not say
applying a patch is optional; it says the VM applies accepted patches at its
safe points. What is optional there is *acceptance*.)

Two further divergences follow from the first. A patch guard is stateless
while a **lease guard must remember an observation**, because its subject is
elsewhere. And a patch is presence while **a lapse is absence**, so silence is
evidence only over a window the judge actually observed. From absence follows
one half of the cadence problem: a judge with a silent holder has nothing
arriving to prompt it, which is why this document must say where its judgment
points come from rather than inheriting them free from the precedent.

## The problem, and what Jini got right

A recurring question in this system is *how do I stop caring about something
that may never answer*. A holder is granted something — a pause, a connection's
server-side composition, a claim on shared work — and then dies, hangs, or
partitions away without saying so. The grantor must eventually take the grant
back: without detecting death, which is undetectable in general; without
consensus, which an unreachable peer cannot give; and without a stuck state,
which any switch left on becomes.

Java Jini solved this class of problem with the lease. This document keeps its
insight and rejects its design.

The insight: a grant that lapses unless renewed inverts the problem. Instead of
the grantor proving a holder dead, the holder must keep proving itself
interested, and the absence of proof is the reclaim condition. Silence — the
one thing a dead holder reliably produces — becomes the signal.

The design rejected: a lease as a live object with methods, a synchronous
remote `renew`, expiry as absolute time in the holder's milliseconds, and a
background `LeaseRenewalManager` thread. These are one mistake wearing four
faces — **meaning placed where it cannot be read.** A live object cannot travel
a stream, be stored, replayed, or read by an interpreter that does not exist
yet. A synchronous remote call is a host boundary worn as a function call. An
absolute expiration binds two clocks that share nothing — and it was always a
fiction: only the grantor can reclaim, so the moment was never anyone's but the
grantor's, and Jini merely disguised that. A background renewer manufactures
precisely the false liveness the lease exists to end.

## Why the vocabulary is shaped this way

**Why the grantor mints the id.** A lease does not exist until it is granted,
so a proposal cannot carry one; it carries its own identity instead, which a
grant or refusal echoes so a holder can tell which ask was answered. Minting at
the grantor also settles uniqueness for free, since every id a judge reads is
one it issued. Proposal ids need no such guarantee: a judge that cannot tell
two proposals apart has only failed to answer an ask it owed nobody.

**Why the grant carries subject and holder outright** rather than referring
back to a proposal: a proposal may not exist, and because a reader of the grant
alone must be able to say what was granted and to whom. Without the holder
field the attribution rules are unenforceable in the only way that matters: a
judge that cannot map lease to holder cannot reject a renewal from the wrong
author, so any co-writer that learns a lease id can keep a dead holder's tenure
alive. An unsolicited grant has no proposal whose authorship could be read
back, so nothing else supplies it.

**Why a proposal creates no state.** Three consequences follow that would
otherwise be open questions. A grantor owes no answer; silence is a sufficient
response. `:rejected` is therefore courtesy in exactly the way `:released` is —
a definitive negative the holder was never owed — and a system in which no
refusal is ever appended is fully correct. And a holder's wait on an unanswered
proposal is an ordinary unanswered request, bounded by the holder's own policy.
The statuses are a record of speech acts, not a state machine: a proposal is
something someone said, not something the system holds.

**Why a duration carries its own unit** rather than being a bare
number: a bare number puts the unit in a convention that no reader can
check. One unit is enough — a composition wanting finer granularity chooses a
finer unit. Tolerance applies to the duration and not to the cap: tolerance
absorbs flight time on evidence, while the cap is measured from the grantor's
own observation of its own grant, where nothing was in flight.

**Why a renewal carries no time.** It says *I still want this*, and the fact that it was appended, together with when it was observed, is its
entire content. A renewal asking for a new duration would be negotiating, and
renegotiation is a new proposal rather than a fat renewal. Nor is a renewal
ever acknowledged, which is why both holder rules — renew at a fraction, stop
at the bound — are the holder's own discipline rather than anything the grantor
enforces.

**A renewal obliges the grantor to honour the time already granted**, not to
grant more. What the grantor retains is the right to end a lease for reasons of
its own — shutdown, the cap, a policy change — which it does by reclaiming and
recording the cause, never by silently declining to count evidence it received.
That distinction is what makes a duration mean anything; without it the
holder's own bound would be advice about a number nobody respects.

**Why a release is evidence rather than an act.** The grantor still performs
the reclaim on its own schedule, exactly as on a lapse. What release buys is
promptness: the grantor need not wait out a duration of silence whose
reason it already knows. Where release and lapse race, whichever the grantor
acts on first terminates the lease, since both lead to the same idempotent
reclaim.

**There is no `:rolled-back`.** The precedent's word is renamed rather than
omitted, and the reason is connotation: rollback suggests undoing an
application and restoring the world as if it had not happened, while a lapse
ends a tenure whose entire past remains valid and undoes nothing. `:released`
has no patch analogue at all, because an unapplied patch costs nothing while a
holder done early saves the grantor up to one duration of waiting.

## Why attribution is a composition duty

A renewal is evidence about its author, and a grant establishes terms only
because the grantor authored it. Both depend on the judge being able to tell
who wrote a fact — and **DaoStream does not supply that.** Its Concurrency
section contemplates multiple writers and leaves one-log-per-writer to
interpretation; a descriptor carries no authorization; and the attribution a
WebSocket boundary provides is composed envelope data, not a property of the
medium.

Unlike the obligation to judge, this one *is* refusable at assembly — but not
by inspecting DaoStream, which declares surfaces, outcomes and retention and
says nothing about authorship. What can be inspected is the composition's own
supplied evidence, exactly as the WebSocket boundary inspects a destination's
declared nature, rather than a property DaoStream was asked to have.

Absent attribution, "only grantor-authored facts establish terms" is
unenforceable: any writer can append an `:accepted` naming itself and take
tenure. This is not a theoretical corner. Of the three uses that justify this
vocabulary, only the served connection has attribution by construction; the
forwarder pause and the shared-work claim run over in-process media where
nothing distinguishes writers unless a composition arranges it.

## Why judging is a pass, and in that order

Nothing fires when a lease lapses. Anyone may reach the conclusion; only the
possessor's conclusion has effect. Two judges with different tolerances may
disagree and neither is wrong; what settles it is **possession, not truth**, and
that is what reclaiming without consensus means. A judge that does not possess
the resource would have to append the effect and observe its outcome as data,
and then the record could not follow the act inside a single pass — a different
design, not described.

**Ticks supply time as data, not a wake-up.** DaoStream invokes nothing and has
no readiness extension, so a deposited tick rouses nobody; a driver still has
to call `next`, and that driver is where cadence comes from. The distinction
matters twice: it keeps the host clock out of lease code, since observation
time is the most recent tick observed rather than a clock call; and it adds the
driver's own polling lag to the bound, which is why cadence is declared between
*completed passes* rather than between timer deposits.

**Two cursors carry no ordering between them**, so the pass is specified rather
than left to the scheduler: a tick judged while an already-deposited renewal
sits unread is a false lapse manufactured by scheduling. Stamping is what makes
the two cursors comparable at all, and it can only overstate a fact's recency —
erring toward retention, the direction the contract already chooses.

**Conclude about everything before acting on anything**, so that an act cannot
change what a later conclusion is drawn from. Nothing is traced and nothing is
marked — the judge classifies leases it already knows about — but the ordering a
collector observes holds for the same reason.

**The record follows the act.** In the canonical case the reclaim *is* closing
the connection, so a record aimed at the holder would fail every time rather
than rarely: the grantor's record and the holder's signal are different facts on
different streams. Even on the grantor's own log the record is evidence that a
reclaim was performed, never a promise that one will be — which is why absence
of a `:lapsed` fact is never evidence of tenure.

## Whose clock decides

**Durations cross boundaries; moments never do.** The judge measures intervals
between its own observations, so offset skew cannot enter the comparison — it
happens entirely on one clock. **Rate skew remains**: a judge whose clock runs
fast relative to the holder's shortens the real tolerance below what the holder
expects, and that is a false lapse with zero transit delay. The renewal
fraction absorbs it, which makes the sizing relation a skew budget as well as a
load budget.

Transit delay cuts both ways, and conflating them is how tolerance gets sized to
zero. **While a renewal is in flight it protects nothing**: the interval since
the previous observation keeps growing, and a pass inside the flight window
false-lapses a holder that renewed on time. **Once observed**, the window is
re-seeded from that later observation, so the same delay extends real tenure —
safe for the holder, unsafe for the scarce resource, since a late-observed
renewal from an already-dead holder buys a fresh duration. The evidence carries
no generation or sequence, and its arrival is its entire meaning. The contract
takes that trade deliberately, preferring to extend a dead tenure over
reclaiming a live one.

Continuity is not decided by partition length alone: a partition longer than the
duration can be survived when tolerance and tick phase cover the excess, and a
shorter one can be fatal depending on when it begins relative to the last
observation. No discipline available here prevents the fatal case — a
`:dao.stream/ok` on an outbound path implies nothing about delivery — and the
holder's remedy is renegotiation when it reconnects.

Sizing is stated as relations and never numbers, because the numbers belong to
compositions.

## Why the ledger is neither a registry nor a root set

The judge's private state is a **ledger of live grants**, and it is not the
registry the invariants forbid: a registry records who is *observing* a stream,
while this records what its owner has *given out* — the grantor's own
obligations, private to it, consulted by nothing else. Where it must survive a
restart it is persisted as a stream that owner writes and reads, which is data
like everything else.

It is not a root set in the collector's sense either. Roots are where a trace
*starts*; these are the things whose liveness is in question, which makes them
the candidates.

**Seeding from the grant** is what makes a never-renewed lease judgeable: a
holder that accepts and dies before its first renewal supplies no renewal to
measure from, and without the rule such a lease could never lapse — the exact
leak this design exists to prevent, in its most likely form. The seed comes
from the grantor's own act of granting, not from reading the grant back,
because the grantor's facts and the holder's need not share a medium and in the
served-connection case do not.

**An exit rule is required** or the ledger grows without bound, which would put
the leak in the one component the design exists to keep bounded.

**The state is not rebuildable from the stream.** Renewals carry no time, so
observation times are judge-local facts appearing nowhere in the medium; and
the terms are worse than volatile, since `:accepted` is an ordinary stream
element under ordinary retention and can be evicted while the lease is live.
Restating terms on the medium is not an option: statuses are authored once, and
a second `:accepted` is a new lease rather than a restatement.

## Why absence needs a window

A false lapse has no deopt, so the discipline is conservative.

**A gap is not silence.** Eviction order is the medium's declared nature and the
stream contract standardizes none, so under a medium that evicts arbitrarily a
newer renewal may be gone while an older one remains, inflating the measured
interval and false-lapsing a live holder. A judged medium declares evict-oldest
for the same reason a deposit destination must; the gap rule is what remains
safe on a medium whose order is merely unknown.

**"No renewal was found" and "no renewal was observed" are different facts**,
and collapsing them is how a restart reclaims a live holder's grant.

**Reclaim-then-re-grant is not free of history.** A grantor must know what it
possesses, how to reclaim
it, and to whom the new grant goes. That inventory is the resource's own — an endpoint knows its open connections, a
composition knows the forwarders it wired. The new grant carries a new identity,
so the old record is left orphaned: a live-looking `:accepted` with no terminal
fact, which the grantor cannot lapse because it no longer knows the id. That is
a consequence of the recovery rather than a defect in it, and it is why the
reader rule about absent `:lapsed` facts is general.

**The limits compound.** A grantor whose restarts outpace one duration never
completes an observation window, so it leaks continuously while every component
reports healthy — and a medium that gaps more often than one duration leaks the
same way with no restart at all: on a shared deposit medium one flooding
attachment evicts everyone's renewals, every pass sees a gap, every window
restarts, and no dead holder on that medium ever lapses. Per-attachment media
are the isolation. In all three cases the cap still bounds tenure, because
tenure start is set once at the grant and no gap moves it.

## Whose obligation

A grantor that never judges leaks its resource without bound. That obligation
cannot be a callback and cannot be a component, so it lives in two places.

**Possession.** The leak lands on the grantor's own resource, so the party with
the incentive is the party with the authority. For a resource that dies with its
process — a socket, an in-memory table — a grantor that stops running holds
nothing anyone can reach, and the obligation ends with it. **This does not hold
for durable resources.** A reserved slot on disk or a claim on a persistent log
outlives its grantor, and its death then leaves the grant held with no judge
running: precisely the stuck state the design exists to end. The three things
such a lease needs — a durable judge, a rule for which incarnation may reclaim,
and fencing from the resource — are none of them lease concepts, and this design
does not invent them.

**Declaration.** The runtime that drives the judging interpreter is easy to omit
and fatal to omit: a composition with ticks and no driver satisfies every
prohibition and still leaks, because the timer fires, the adapter appends, and
nothing advances the judge. It is honest to say nothing can refuse it. The
WebSocket boundary can reject a reject-mode destination because a constructor
inspects a destination's declared nature; there is no counterpart here, because
no constructor can inspect whether a composition will later append an
`:accepted` fact. This is a defect detectable by review, with no assembly-time
or runtime signature, and no enforcement is claimed that does not exist.

## The prohibitions, and the fifth invariant

The contract's prohibitions are the general invariants spent on this
vocabulary: never a live object, since everything one can do about a lease is
append a fact or judge one; never fires into lease code, since a tick is data on
a stream like any other event; never a registry; never absolute time on the
wire; never erases, since a reclaim frees the resource and not the record;
never renewed by machinery, since renewal is evidence the holder is alive.

**No rule corresponds to the invariant against collapsing interpretation and
execution**, and the omission is deliberate. Judging and reclaiming are one
interpreter by design: the party that concludes is the party that possesses,
exactly as the precedent's VM evaluates its own guard and deopts, and as a
boundary adapter closes the socket it itself holds. The invariant governs
layers, and no layer here interprets what it also executes — the stream does
neither. What the invariant does forbid is stated positively instead: anyone may
judge, and only the possessor's judgment has effect.

**Never touches retention** is the load-bearing denial. A lease may not extend
retention, defer eviction, or gate history, and no DaoStream operation may
consult a lease. `dao.stream.md` is explicit that eviction never waits on a
reader, and a lease that could hold a stream open would make one stalled holder
every other reader's unbounded growth.

## Renewal forever

A hostile holder gains little by renewing forever, and what it gains is priced.
Renewal buys tolerance for silence and nothing else. Renewing forever holds the
lease forever, which the cap bounds within a judge lifetime — past it the
grantor lapses regardless of renewals, and an honest long-lived holder proposes
anew. Renewal costs presence, which is the honest price, and a holder able to
pay it indefinitely is indistinguishable from a legitimately long-lived one;
distinguishing them is admission policy, never lease vocabulary.

Whether a leased grant is dangerous is judged where it is granted. A paused
forwarder retains nothing extra, so that pause harms only the pauser; a lease
over a genuinely scarce resource needs its cap. The vocabulary carries the cap;
the composition decides it.

## Why delegated renewal is denied

An agent that renews on a holder's behalf without holding evidence about that
holder is Jini's `LeaseRenewalManager` in cleaner materials, manufacturing false
liveness for a wedged holder. An agent that renews *on evidence* needs its own
liveness signal from the holder — which is a lease — so the honest structure is a
chain of ordinary leases, each link its own grantor and possessor, terminating
at whoever is doing the work.

Such a chain is safe but not meaningful by default. The terminal grantor is
never unsafe: it reclaims when its immediate holder goes quiet, and that local
rule is unchanged by anything below it, which it cannot even see. What a chain
changes is what those renewals *mean*, and that is a property of each
intermediate renewer's composition, checkable only by reading it:

> For each link, the renewer's predicate is *I append a renewal only while I
> hold fresh evidence, observed on my own clock, that my immediate holder is
> still there.* The end-to-end meaning — that the terminal grantor's reclaim
> tracks the worker's death — holds only if that predicate is true at **every**
> link. Any weaker answer is not a weakening but a **cut**: from that point
> outward, renewals prove only the renewer's liveness.

Neither benefit usually claimed for delegation survives inspection. Batching
amortized a remote call in Jini; here N renewals to one grantor are N appends
the judge already reads in one pass, cross-grantor batching is impossible by
construction, and coalescing frames on a wire is carriage the transport owns.
Cadence decoupling is worse than the alternative: the terminal grantor cannot
reclaim faster than the innermost sub-lease permits, so a chain is strictly
worse than granting the loose duration directly. Each link also adds its own
duration and cadence to detection latency, and a dead manager fells every
sub-holder beneath it.

What delegation genuinely offers — one accountable counterparty, with admission
and sub-allocation a link down — needs no delegation at all: the manager is
simply **holder of record**. It holds the grantor's lease, sub-leases its own
attention beneath it, and the grantor sees one holder and is told nothing about
the rest. That shape is ordinary composition and is legal today.

Should a real need arrive, the weakest sufficient mechanism is a holder-authored
fact designating acceptable renewers, with the judge counting a renewal only
from the holder or a designated renewer. It presupposes exactly what attribution
already requires, and nothing more. Accepting any renewal that merely names a
lease is not a weaker mechanism but the absence of one — tolerable only where the
medium's own admission already restricts writers, which is designation done
implicitly and should be named as such rather than adopted as a design. Depth
beyond one delegation would need chained, attenuable, offline-verifiable
authorization, which is a different problem.

## Cancellation is what a lapse is

To cancel something ongoing is to stop wanting it continued, and a holder that
stops renewing has said exactly that. The lease form is stronger than a
cancellation flag in the case that matters most: a flag needs its setter alive
to set it, while a lease cancels when the canceller dies, hangs, or is
partitioned away. Where the thing being cancelled is itself a lease holder — an
interpreter that checks its own tenure at its own points — non-renewal is
cooperative cancellation with a deadline attached, and it needs no separate
mechanism.

What a lease cannot do is retract what already happened. It ends continuation,
not consequence: an effect already produced stays produced, a message already
sent stays sent, a computation that finished before the lapse finished. That is
also why a lease is the wrong instrument for a one-shot answer that will never
arrive, where there is no continuation to end.

## Neighbouring deferrals

`dao.stream.ws.md` defers liveness probing, and its Deferred list awaits a
durable home for a pause vocabulary. Both are lease-shaped, and this design is
the plausible home for their semantics; neither is claimed until the
transport's wire-contract gate settles what crosses the wire.

## Scope discipline

Three uses justify this vocabulary: the pause a reader holds over a forwarder,
the lifetime of a served connection, and a claim an agent leaves on shared work.
Three is enough for a small vocabulary; it is not a reason to make every
timeout, cancellation, or lock a lease.
