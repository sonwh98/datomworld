# yin.repl link policy: what a pending require does when nobody answers

Status: design, proposed (2026-09-26). Not implemented. Subordinate to
[`datom.world.md`](./datom.world.md) and to
[`yin.vm.linker.md`](./yin.vm.linker.md); it settles the M5 decision that
`yin.vm.linker.md` section 12 (bullet "Failure policy") left open. The
mechanism it points at is [`dao.lease.md`](./dao.lease.md).

## 1. The problem

M5 wires `require` at the prompt to the linker. A require whose content
source does not answer stays `:pending`. `yin.repl` parks it as the
shell's `:pending-run`: the prompt returns, lines typed meanwhile are
retained and replayed once, in order, when the link completes, and
`(abandon)` ends the wait and drops the retained lines. Nothing ends a
pending run by itself.

That is right for a person at a prompt, who can type `(abandon)`. It is
wrong for a session nobody watches: a require against a source that never
answers sits pending forever, and the lines queued behind it never run.
Retry cadence, deadline and permanent absence are the composition's
(`yin.vm.linker.md` D6 and section 6.3), so the policy belongs to
`yin.repl`, and the owner asked that it be configurable.

## 2. Rulings that bound the design

- `fetch` takes no deadline. The linker and the engine stay clock-free.
  Time reaches a policy only as data the composition supplies.
- `require` stays an ordinary function. A deadline is a session setting,
  not an argument of `require` and not a new special form.
- The M5 pending run, `(abandon)`, the retained-line replay and the
  identity carry across rollbacks are unchanged. A policy adds one more
  way to reach the abandon path; it adds no second path.
- Rule R and the sealed private resources stand: a policy is composition
  code, never a program value, and no program can reach its state.

## 3. The option

`yin.repl/create-state` takes `:link-policy`. It is a session setting kept
on the shell beside `:link-source`, so `(reset)` and `(vm ...)` rebuild the
session without losing it.

```text
+-----------+--------------------------------------------------------+
| Value     | Meaning                                                |
+===========+========================================================+
| :manual   | The default. Today's behavior: nothing ends a pending  |
|           | run but `(abandon)`. No existing test changes.         |
+-----------+--------------------------------------------------------+
| a function| An embedder's rule, (fn [view]) returning :keep,       |
|           | :abandon or {:abandon reason}. The REPL owns no clock; |
|           | time reaches the function only through what it closes  |
|           | over.                                                  |
+-----------+--------------------------------------------------------+
| :lease    | Reserved for the dao.lease deadline of section 6. In   |
|           | phase 1 it is refused, "not implemented yet".          |
+-----------+--------------------------------------------------------+
```

Any other value is refused at `create-state` with an `ex-info` naming the
supported values. The option fails closed at assembly, not at the first
pending require.

### 3.1 The view

The function receives plain data and nothing else:

```text
{:links [{:name 'foo :link-id [:t0 3]} ...]
 :checks n
 :lines-retained m}
```

`:links` are the requests still pending. `:checks` counts the re-checks of
this pending run that made no progress; a re-check that advanced a serve
cursor, delivered a response or completed an install resets the count.
`:lines-retained` is the number of typed lines waiting behind the run.
The view carries no clock reading, no handle and no secret.

### 3.2 When the policy is consulted

- when a require parks, with `:checks` 0;
- after each re-check that leaves the run pending.

It is never consulted when nothing is pending, and never on a re-check
that completes the run: a completion is not a failure.

### 3.3 What the answers do

- `:keep` leaves the run as it is.
- `:abandon`, or `{:abandon reason}`, runs the same abandon path as
  `(abandon)`: installs in flight are refused, every link wait entry is
  retired with the reason raised as the require's error, the shell returns
  to the round the require began in, and the link identity minted by the
  parked evaluation is carried onto the base. The reason defaults to
  `:yin.repl/link-policy`; `(abandon)` keeps `:yin.repl/abandoned`.
- The printed message says that the session policy ended the require, in
  the same shape as the message `(abandon)` prints, so a policy abandon is
  never mistaken for a user's.
- Retained lines are dropped once and reported once, exactly as
  `(abandon)` does. No line is evaluated twice, none is lost, and a late
  response to an abandoned link is skipped and cannot settle a later
  require (the M5 identity-carry guarantees hold unchanged).

### 3.4 A policy that misbehaves

A function that throws, or returns a value outside the contract, is
treated as `:keep` for that consult and surfaces as one shell error line.
It is never a silent abandon and never ends the session. A broken policy
must not be able to destroy the work it was meant to protect.

### 3.5 State summary

`repl-state`'s `:pending` gains the policy name (`:manual` or `:fn`) and
`:checks`, so a host can display why a run is still waiting.

## 4. Driving a re-check without a typed line

Today a re-check happens when the user types a line. An unattended host
cannot make a function policy act if nothing triggers a re-check. The host
drivers (`yin/repl/main.cljc`, `yin/repl/driver.cljc`) already own cadence,
so the design question is only whether they can already ask the shell to
re-check a pending run without an input line.

- If they can, nothing is added; this section is documentation.
- If they cannot, the shell gains one small public step function that
  performs a re-check and consults the policy, and returns the new state
  and the printed text. It reads no clock and takes no callback; the host
  decides when to call it.

Which of the two holds is settled by reading those namespaces at
implementation time. Either way the REPL itself stays free of clocks,
timers, callbacks and global atoms (the `yin.repl` namespace docstring).

## 5. Why a function comes first

A function policy covers every rule an embedder can want, from "give up
after 20 re-checks" to a host wall-clock budget the function closes over,
with no new machinery in the REPL. It needs no tick source, no judge and no
authentication, because the policy runs in the same trust domain as the
shell it configures. Phase 2 exists only for the case a function cannot
serve: a deadline that must be a first-class, inspectable fact on a stream.

## 6. Phase 2: `:lease`

This section is the design the reserved value stands for. It is optional and
is built only when unattended use makes a data-visible deadline necessary.

### 6.1 The mapping

`dao.lease.md` defines a lease as a grant that lapses unless renewed. For a
pending link:

```text
+-------------------+--------------------------------------------------+
| dao.lease         | In the REPL                                      |
+===================+==================================================+
| subject           | the pending link, [:link link-id]                |
+-------------------+--------------------------------------------------+
| grantor and judge | the shell's link-policy judge, composed inside   |
|                   | yin.repl, the boundary that possesses the link   |
+-------------------+--------------------------------------------------+
| holder            | the shell's drive, the code that steps the link  |
+-------------------+--------------------------------------------------+
| renewal           | the drive's own append when a step made progress |
+-------------------+--------------------------------------------------+
| tick              | a host adapter's readings on a stream the shell  |
|                   | wires: one for the judge, one for the holder     |
+-------------------+--------------------------------------------------+
| reclaim procedure | the abandon of section 3.3, idempotent,          |
|                   | reporting success                                |
+-------------------+--------------------------------------------------+
| :lapsed :silence  | the trigger for that abandon                     |
+-------------------+--------------------------------------------------+
```

The link is abandoned when the judge's interval since the last relevant
observation exceeds the granted duration plus tolerance (`:silence`).
`:cap` also applies if the grant carries a maximum, giving a hard ceiling
however often the link progresses.

### 6.2 What the option carries

```text
:link-policy {:lease {:duration {:seconds 30}
                      :tolerance {:seconds 2}
                      :max {:seconds 300}        ; optional cap
                      :ticks tick-source}}       ; host adapter
```

Units are the composition's interoperability decision, fixed once. The
sizing relations of `dao.lease.md` hold: the duration exceeds twice the
renewal interval, and the tolerance covers flight time and the rate skew
between the judge's and the holder's tick streams.

### 6.3 Attribution comes by construction

`dao.lease.md` makes attribution a composition duty, and warns that
in-process media distinguish no writers unless the composition arranges it.
Here it can be arranged completely: the lease media and the tick streams
are the shell's own, per-author, and never placed in a VM's private
`:resources` or handed to any program value. A program holds only sealed
references to resources the engine issued, so it cannot append a forged
grant or renewal. The resolver is therefore the shell's own knowledge of
which medium each author writes. A capability token such as ShiBi is not
needed for this case and would add nothing until leases guard something
that untrusted writers can reach.

### 6.4 What phase 2 costs

- a tick adapter per host (a timer on the JVM, on Node and on Dart), which
  is the one place a host clock is read, outside the REPL core;
- a judge, driven at a declared cadence by the same host driver that
  drives the shell;
- lease bookkeeping that survives the rollbacks M5 fixed: the lease for a
  link is reclaimed by the same abandon path, and a link identity is never
  reused on the surviving pair;
- scripted-tick tests, as `dao.lease`'s own tests are: no test reads a
  real clock;
- process-scoped leases only. The judge's ledger is not rebuildable from
  any stream and the resource is not durable, so a restart drops the
  session and its pending runs with it; `dao.lease.md`'s durable-resource
  requirements do not apply.

It would be `dao.lease`'s first consumer, so it may surface gaps in that
design; that is a reason to build it deliberately, not first.

## 7. Open decisions for the owner

1. Is a function policy enough until unattended use appears? This design
   assumes yes.
2. If a lease deadline is wanted, is the pending link the right subject,
   or should the lease cover the whole pending run (all its links and the
   retained lines)? A per-run lease is simpler and makes one abandon; a
   per-link lease lets one stuck link lapse while another progresses.
3. Whether the message for a policy abandon should name the policy value
   that fired (`:fn`, `:lease :silence`, `:lease :cap`).

## 8. Tests the implementation must pin

Phase 1, portable to JVM, Node and Dart, comparing values and structured
state, not printed text (ClojureDart prints some forms differently):

- `:manual` is the default and a pending run survives any number of
  re-checks;
- a function returning `:abandon` at a chosen `:checks` ends the require
  with the policy reason and one message; `:keep` never ends it;
- a function that throws, or returns a value outside the contract, is
  kept and reported;
- an unknown option value and `:lease` are refused at `create-state`;
- the policy survives `(reset)` and `(vm ...)`, and `:checks` resets;
- retained lines are dropped once on a policy abandon, and a retained line
  that starts a second pending require still behaves as M5 requires;
- a late response after a policy abandon cannot settle a later require.

Phase 2 adds scripted-tick tests: silence past duration plus tolerance
lapses and abandons; renewal on progress keeps the link alive; the cap
ends a link that keeps progressing; a `:lapsed` fact follows the abandon
and never precedes it; no forged renewal from a program can keep a link
alive.

## 9. File box

```text
New:      docs/design/yin.repl.link-policy.md              (this file)
Edited:   src/cljc/yin/repl.cljc     (create-state option, policy
                                      consult points, repl-state)
          src/cljc/yin/repl/main.cljc, driver.cljc
                                     (only if section 4 needs a step)
New:      test/yin/repl/link_policy_test.cljc
Phase 2:  host tick adapters, a judge composed in yin.repl, and the
          lease-fact media wired in create-state
```

The implementation brief for phase 1 is staged at
`collab/1790446000000-vm-engineer-linker-link-policy.claude-opus-5-5.prompt.md`.
