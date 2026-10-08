Completed-GMT: 2026-10-05 22:40:24 GMT
Completed-Local: 2026-10-06 05:40:24 +0700

**Ruling: D10 restores a runnable version-1 root only after grant acceptance, using explicit composition-supplied evidence and protection declarations. Pre-grant inspection produces validated data, not a runnable machine.**

1. **Ordering and responsibility**

The apparent conflict is between inspection and restoration. The reader’s decode/version/address/grammar pipeline can run before a proposal. It neither grants custody nor authorizes execution. D13’s order is:

   1. Inspect and validate the checkpoint.
   2. Obtain a grant and authenticated, complete ledger evidence.
   3. Verify the checkpoint belongs to the granted occurrence, the binding names this holder, and tenure remains valid.
   4. Call D10’s lower with that evidence.
   5. Publish the successfully restored machine to the custody driver.

The existing [D3 evidence reader](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/holder/evidence.cljc:91) supplies binding, enrollment and replay-prefix data. Its `:yin.k/ready` answer proves historical binding evidence; **it does not itself prove that the lease remains active now**. D13 must additionally establish current tenure.

There is no new `:ungranted` gate mode and no partially populated custody map. A blocked/parked version-1 root without grant evidence returns `:yin.k/awaiting-grant`, with no `:vm`. Invalid binding returns `:yin.k/not-holder`; unavailable evidence remains unavailable, never an empty prefix or enrollment set. These checks precede resource attachment and restoration, after structural validation.

2. **Exact lower inputs**

Retain the existing API:

```clojure
(handoff/resume-task recv bytes attach! opts)
```

For a blocked/parked version-1 root, define these additional options:

```clojure
{:address checkpoint-address
 :protection {stream-identity :enrolled
              other-identity :at-least-once
              another-identity :fail-stop}
 :grant
 {:checkpoint checkpoint-address
  :dao.lease/lease L
  :dao.lease/holder H
  :evidence E
  :tenure {:now n :bound b :live true}}}
```

`E` is the unchanged successful `holder.evidence/read-evidence` result:

```clojure
{:yin.k/status :yin.k/ready
 :yin.k/binding binding
 :yin.k/enrolled #{stream-identities}
 :yin.k/prefix prefix}
```

The composition supplies `:protection`; D13 supplies `:grant` from authenticated control-plane evidence. These are trusted composition inputs, never guest values, checkpoint fields, or a caller-supplied replacement custody map.

D10 checks:

- `:checkpoint` equals the inspected address.
- Binding occurrence equals the body’s occurrence.
- Binding lease and holder equal `L` and `H`.
- Binding transaction arbitration identity equals the body’s arbitration identity.
- Epoch has the existing portable exact-integer form.
- Tenure has `:live true` and `n < b`.
- Prefix occurrence and lease match the binding; its records are dense from zero through `frontier - 1`.

D13 must establish that the checkpoint address is an admitted variant of the granted occurrence. Equality between two supplied address fields alone is not that proof. Lower performs no proposal, ledger IO, renewal, release or clock read. D13 rechecks tenure before scheduling execution and subsequent IO.

Malformed option shapes are caller defects. Well-formed but unavailable or contradictory evidence produces the corresponding data refusal without exposing a machine.

3. **What the restored machine carries**

D10 atomically returns the existing `{:status :ok :kind k :vm machine}` shape, with this root-only custody data:

```clojure
{:yin.k/custody
 {:yin.k/occurrence O
  :dao.lease/lease L
  :dao.lease/holder H
  :yin.k/epoch e
  :yin.k/arbitration arbitration
  :yin.k/next-op-seq n
  :protection classes
  :input {:next 0 :prefix prefix}
  :tenure {:bound b}}
 :yin.k/gate :running}
```

`arbitration` is the body’s identity/descriptor map. The operation counter comes **exactly from the body**, not from the grant, receiver, or maximum carried sequence. Carried operation IDs remain unchanged, including inherited IDs.

The prefix comes from evidence for this grant. Input execution begins at zero on regrant as well as first grant; the prefix frontier determines when replay ends. A successor uses its own occurrence’s prefix. Missing evidence never means frontier zero.

Amend r3’s shorthand “mode” and “whether the prefix has ended”: `:yin.k/gate` is the sole mode carrier; prefix completion is derived from `:input :next` and the prefix frontier. Do not persist duplicate flags. Lease liveness likewise remains a driver check, not an indefinitely valid saved `:live true`.

Install children receive `:yin.k/gate :running` and no custody map, counters, lease or input state. D10 restores them as part of the root’s already-authorized task; they need no independent grants.

**Halted roots are different:** a version-1 result is not a lease subject. Restore it without manufacturing custody or requiring a grant for its origin occurrence. Return a halted machine gated `:ended`, with no program execution or IO authorized. Version 0 retains its existing fork behavior.

4. **Protection declaration and mismatch rule**

`:protection` is a map keyed by portable stream identity, with exactly the three class values above. No implicit default and no classification by receiver-local binding name.

For every stream identity reachable in the restored task, children included:

- A missing declaration returns `:yin.k/unsatisfied`, naming the stream.
- Class `:enrolled` must agree with membership in `E`’s enrolled set.
- A ledger-enrolled stream cannot be declared `:at-least-once` or `:fail-stop`; an unenrolled stream cannot be declared `:enrolled`.
- Unrelated ledger enrollments need not appear in this task’s declaration.

For each retained `:put`, `:ffi-request` or `:link-request`:

- Enrolled target without a carried operation ID: refuse.
- Carried operation ID on a non-enrolled target: refuse.
- Enrolled target with an ID: preserve it; do not assign a replacement.
- Non-enrolled target without an ID: preserve its declared class.

Both protection mismatches return `:yin.k/unsatisfied`, naming the target. Lower does not convert an already-attempted write into a protected write. A retained `:fail-stop` write can restore; D11 ends the run before performing it.

These checks run recursively before attachments. They do not add wire fields or change the authority’s admission rules.

5. **D10 acceptance contract**

Run the restoration harness on JVM, Node and Dart, across the supported VM kernels:

- **Grant boundary:** valid bytes without grant evidence expose no runnable machine and make no attachment calls. Wrong occurrence, holder, lease, arbitration, expired tenure and unavailable history likewise cannot activate.
- **Counter and IDs:** restore the exact counter and all three retained-write variants, including inherited IDs and a counter at the bound. Lower may restore an exhausted counter; later assignment/export must refuse rather than wrap.
- **Protection:** pin both ID mismatch directions, declaration/enrollment disagreement, undeclared streams, and child-contained mismatches.
- **Replay initialization:** a nonempty regrant prefix starts at input zero; an empty authenticated prefix is accepted; unavailable evidence is not substituted with emptiness.
- **Fresh-receiver restoration:** use D9’s `resume-task` harness with attachment support and the version-1 address. Supply valid grant evidence for blocked/parked roots. Assert authentic references, preserved aliases and distinct cells, restored module stores, and carried pending IDs.
- **Isolation:** receiver-local guest store/module bindings cannot supply missing checkpoint bindings or change resolution. Preserve composition resources and primitive resolution, but do not merge receiver guest bindings into version-1 checkpoint state.
- **Children:** restoration does not rerun initialization; children carry the root gate only.
- **Compatibility:** version-0 restoration and byte pins remain unchanged; halted version-1 result restoration needs no grant and authorizes no execution.

D9’s blocked/parked version-1 restoration tests must now supply the explicit D10 grant inputs. That is the planned custody enforcement, not permission to remove their restoration assertions.

6. **What remains D13’s**

D13 owns proposal/grant handling, authenticated ledger acquisition, accepted-checkpoint selection, current-tenure verification, local lease-bound calculation, renewal, release after post-grant failure, and publishing the restored machine to the driver. D10 owns deterministic restoration and enforcement of the supplied contract; it must not fabricate evidence or silently restore a version-1 task as an ungated fork. D11 owns write assignment and class behavior; D12 owns replay delivery and advancement of the root input state.

No files were edited and no suites were run.