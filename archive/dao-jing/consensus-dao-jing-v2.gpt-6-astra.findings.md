Session-ID: 01a0779a-7a9d-79e0-930a-ac19af7164b4 (captured from thread.started)

I’ll read the docket and repository evidence, then rule on each contested item independently. I won’t edit files or run tests.
The host-boundary wording is stronger than Decision 2’s intake-pool argument: it explicitly rules out function-shaped host adapters. I’m also checking whether the reviews distinguish a raw remote storage API from materialization, and temporary v1 coexistence from a promise of dependency isolation.
My tentative rulings uphold the host-boundary objection and the RPC lifecycle omissions. I’m narrowing C2: the dependency table is inaccurate, but the cited migration rules don’t clearly require transitive isolation at J2. For N1, I favor coordinated removal when the replacement lands; its ownership and test destination depend on the C1 decision.
Completed-GMT: 2026-09-06 16:45:07 GMT
Completed-Local: 2026-09-06 23:45:07 Asia/Bangkok
Coding-Agent: codex
Session-ID: 01a0779a-7a9d-79e0-930a-ac19af7164b4

References below use `plan` for `collab/architect-dao-jing-v2-plan.claude-fable-5-1.findings.md`, `brief` for its `.prompt.md` counterpart, and `rpc` for `src/cljc/dao/stream/rpc.cljc`. Settled docket facts are accepted. No files were edited and no tests were run.

**C1 | POSITION: uphold finding |** Sol is right about the architectural conflict. The decisive text is not merely Axiom 1: `docs/design/datom.world.md:53–68` explicitly makes a host boundary a stream boundary, sanctions DaoStream operations as its function-shaped contact, and rejects callable host adapters as substitutes. Decision 2 removes that contact and places filesystem operations behind the content-handle functions (`plan:205–216`).

GLM’s reasoning fails because `docs/design/dao.jing.md:278–282` specifies the **content-handle interface**, not permission for arbitrary host IO beneath it. Indeed, that same document specifies the file implementation as an append-only **log stream** at lines 297–303. Explicit backend functions and an underlying stream are therefore compatible; the former does not exclude the latter. The intake establishes the publication boundary, but does not establish that every subsequent filesystem operation is outside the host-boundary rule. Reader count is irrelevant to that rule. Storage ignorance concerns content semantics (`dao.jing.md:185–200`), not a prohibition on reusable byte framing.

**Plan change:** Replace Decision 2 with a conforming v2 durable-log dependency and its conformance obligations, or obtain an explicit superior-design amendment permitting this particular backend arrangement before J1. A v2 implementation must specify durability completion; merely renaming synchronous filesystem wrappers would not meet the no-wait contract (`dao.stream.md:153–158`). This is a design prerequisite, not a mechanical five-call replacement.

**C2 | POSITION: partial, specified — uphold inaccurate dependency claims; reject mandatory J2 extraction |** Sol correctly identifies a real transitive dependency. `plan:64–66,408` deliberately requires `dao.jing`; its settled v1 require means Decision 4’s “none” entries are inaccurate as dependency claims. The table even counts a transitive dependency for `dao.jing.coordinate` (`plan:312–320`), so silently interpreting every other row as direct-only is inconsistent.

However, Sol has not established that J2 **must** be transitively v1-free. The brief permits a justified parallel migration (`brief:90–95`), and the stream migration explicitly allows temporary coexistence with deletion after the last consumer migrates (`docs/design/dao.stream.implementation-plan.md:642–659`). A v2 observer calling shared materialization code does not thereby operate on v1 readers.

GLM is closer on the remedy: an explicitly temporary dependency can be acceptable. But it is not *inherent* to fold-back—extraction would avoid it—and one sentence attached only to the end condition would leave the table and J0 misleading.

**Plan change:** Retain the single shared implementation for this migration, but distinguish direct dependencies from transitive legacy dependencies throughout Decision 4, J0, J2 and the end condition. State that pre-J5 deployment still needs v1, and make J5 remove the legacy dependency from the migrated observer/remote dependency closure. Extract a shared core before J2 only if independent v2 deployment is an actual requirement; the cited evidence does not currently impose it.

**C3 | POSITION: partial, specified — uphold missing materialization replacement; reject equating raw backend put with materialize! |** The proposed client does not preserve the complete remote materialization operation. `plan:264–289` exposes address-supplied put and raw verdicts; the required derivation and read-back verification belong to materialization (`docs/design/dao.jing.md:160–166`; settled `src/cljc/dao/jing.cljc:249–296`). No stepped equivalent is specified.

But `request-put` is not intrinsically defective for being a backend primitive. The existing backend interface also receives an address and returns a verdict; `materialize!` supplies the higher-level guarantee. Likewise, existing remote handlers validate the incoming address and invoke raw put (`src/cljc/dao/jing/remote.cljc:46–68`). Backend and materializer are distinct layers.

The consequential gap is that the old client could be composed with `materialize!`, while the new one explicitly cannot (`plan:235–242`). Calling this the completed async backend dependency at `plan:304–308,568–570` leaves responsibility for replacing that integrity-preserving composition unstated. Read-only hydration can use asynchronous get without materialization; write-side readiness is a stronger claim.

**Plan change:** Keep J3’s raw content RPC adapter, explicitly defer a caller-stepped materializer to a named DaoJing follow-on phase, and qualify the backend-readiness claim. Require the follow-on to derive the address, put, conditionally get on `:present`, reject absent/unequal read-back, and complete with the address only after verification. `store-tree-async` must depend on that operation; read hydration need not.

**C4 | POSITION: uphold finding |** Sol is right. The brief expressly says not to leave its four questions to a phase that “will decide” (`brief:52–56`), and question four includes the final naming decision (`brief:90–95`). Yet J5 says to take a decision and offers a recommendation (`plan:504–510`).

GLM correctly observes that the stream plan postpones its own naming choice (`docs/design/dao.stream.implementation-plan.md:655–659`). That precedent does not override this brief’s explicit requirement. A recommendation is not a settled decision.

**Plan change:** Decide now: fold the observer into `dao.jing` and rename `dao.jing.v2.remote` to `dao.jing.remote` once their v1 consumers have migrated. J5 executes that decision. Coordinate with a stream rename if convenient, but do not make that rename an additional prerequisite.

**C5 | POSITION: uphold finding, with a qualification about retention |** GLM identifies an unresolved lifecycle obligation. The specified wrapper allocates an ID for `pending-request`, but gives neither its step nor its reattachment contract responsibility for retiring that unsent request (`plan:269–292,469–474`). The settled behavior of `lose-outstanding` and `rebind` means the ordinary outstanding-request loss path does not settle it.

“Silently swallowed” is slightly imprecise: RPC retains the request, rather than discarding it. Retention across bindings is not inherently forbidden either. `rpc:267–276` explicitly assigns the decision to the composition. The defect is that this composition specifies neither deliberate retention nor abandonment while promising reconnection behavior. Fixing A1 alone does not settle that policy.

**Plan change:** Choose abandonment for terminal bindings. After polling, if the returned RPC state is terminal and still has an unsent request, call `rpc/abandon-unsent` with that terminal reason before draining completions. Specify the same abandonment obligation before an explicit writer-binding change, using `:dao.stream.rpc/abandoned` when no terminal reason exists. Preserve monotonic IDs and publish the loss exactly once. Add unsent-at-detach and rebind cases proving the old envelope is never sent on the new attachment.

**C6 | POSITION: uphold finding |** The enumeration is incomplete. Response-reader cursor defects become terminal loss reasons (`rpc:408–413`), while `plan:283–285` omits them. It also fails to distinguish reader `:dao.stream/end` from lifecycle `:dao.stream.apply/ended`. The settled allocator-error outcome is missing from `plan:271–274`.

These are reachable protocol results, not discretionary diagnostics. Conversely, totality need not mean exposing every internal RPC outcome: correctly constructed Jing operations always supply a keyword and vector, excluding RPC’s invalid-request branch (`rpc:246–250`).

**Plan change:** Decode any RPC completion carrying a reason into `{:id … :op … :lost reason}`, preserving the fully qualified reason unchanged. Label examples as examples, not a closed vocabulary. Include `allocator-error` in request outcomes, specify that allocation failure creates no new request ID/completion, and retain its diagnostic. Cover cursor defects, reader end, allocation failure and abandonment in J3a.

**N1 | POSITION: partial, specified — support prompt retirement and coverage transfer; reject unconditional Jing-owned deletion as written |** Accepting the docket’s consumer inventory, keeping an orphaned v1 log until unrelated v1 consumers migrate has no demonstrated production purpose. The copy at `plan:212–220` creates a needless second framing owner. Retirement should accompany the replacement.

However, the exact proposed destination depends on C1. If a superior amendment authorizes private file framing, moving the recovery coverage into `file_test.cljc` is correct. Under my C1 ruling’s default route—a v2 log transport—the framing recovery tests belong with that transport, with Jing integration coverage proving successful recovery and subsequent durable materialization. Moving all framing responsibility into Jing tests would put the evidence away from its implementation owner.

On **jurisdiction**, use a named stream-owned dependency. The brief explicitly limits scope (`brief:103–108`), and the current plan promises v1 streams remain untouched (`plan:543–548`). The brief’s specific permission to own a *v2 append-log phase* (`brief:73–74`) does not clearly authorize deleting an existing v1 transport.

**Plan change:** Add a proposed dependency named **“legacy append-log retirement”**, owned by the stream migration and coordinated atomically with J1’s replacement. It removes `dao.stream.log`, its obsolete suite and test-runner references after replacement coverage exists. Jing owns the integration evidence; the surviving framing owner owns torn-tail recovery evidence. Under an approved private-framing decision, that owner becomes Jing. Do not defer retirement to wholesale v1 deletion, and do not include the separate live-tail `dao.stream.file` transport.

**What would change my mind**

- **C1:** An accepted superior-design clause explicitly classifying content-store handles as sanctioned host interpreters, including how their outcomes satisfy the stream-emission rule, would support Decision 2. The existing “explicit functions” sentence does not supply that clause.
- **C2:** A documented requirement that J2 load or deploy without legacy namespaces would make extraction mandatory. A concrete load-time interference caused by this dependency would also move it from documentation debt to an implementation blocker.
- **C3:** A specified async composition that already owns address derivation and conditional read-back would remove the materialization gap. An explicit read-only meaning for “async backend” would justify the hydration claim, but would still require naming the separate write dependency.
- **C5:** An explicit retained-request policy, with attachment-independent operation semantics and evidence that every pending ID eventually completes or can be abandoned, would justify replay across rebind instead of mandatory abandonment.
- **N1:** Explicit orchestrator authorization extending Jing’s scope to this orphan’s retirement would move deletion ownership into J1. A named near-term production dependency on the old log would justify delaying deletion; its own remaining tests would not.
