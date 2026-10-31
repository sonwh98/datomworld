Created-GMT: 2026-10-17 03:00:00 GMT
Created-Local: 2026-10-17 10:00:00 +07
Completed-GMT: 2026-10-16 05:10:00 GMT
Completed-Local: 2026-10-16 12:10:00 +07
Coding-Agent: claude (claude-fable-5-1)
Session-ID: pending
Role: Architect (read-only; worktree datomworld-threat at 6df441d0 = origin/master)
Brief: collab/1793520000000-architect-fetch-threat-model.prompt.md

# Threat model: policy-gated receiver-side fetch of modules a lowered continuation names (continuation §11 Q5)

## 0. One-paragraph answer

Using the linker from the lower *can* enable injection, but not through the
linker's own pipeline: the pipeline verifies address, identity, derivation,
manifest name, declared obligations and (on the DHT path) asks the holder's
admission policy before any load. The injection routes are around it:
(i) **name-only satisfaction** (today's `discharge!` and `resolve-var` match a
module by *name*, so a same-named module of other content at R silently
answers; a fetch loop that requires by name inherits this), (ii) **carried
install children** (a pack's `:install` rows are verified self-consistent and
then installed into R's registry family by `link-install` with S's admission
record copied, never R's policy asked), and (iii) **a composition that seeds
its name environment or `:trusted` derivation policy from carrier data**.
Option D (fetch inside symbol resolution) is correctly rejected. The verdict is
**C in UCF §7.6.4's existing form** (`:yin.k/modules {name manifest-address}`
in `requires`), with B's mechanism (re-discharge on lower, refuse before
attach, composition drives an explicit `require`, re-lower from the same
pack) and the install-child admission gap closed in the same slice.

## 1. Assets and trust boundaries

### 1.1 What R protects

| Asset | Where it lives | Why it matters for a fetch loop |
|---|---|---|
| Module registry | `(:modules state)`, a value (`module.cljc:27`, linker I7 `yin.vm.linker.md:201`) | Exposure domain is the **installation family for its life**; a linked name answers later `require`s with no link request and no admission question (`yin.vm.linker.dht.md:2317-2333`); untrust is not retroactive (`:2335-2338`). Poisoning is sticky. |
| Program store | isolated per task; the pack's slice replaces it (UCF §7.6.2 `:1623-1640`); pinned by `rows_test.cljc:1170-1179` | Not reachable from a pack: receiver-only store/module-store state is discarded, the carried slice never merges. |
| Module stores | `:module-stores`, instantiated per task from the pack (`rows.cljc:2210-2213`) | Sender-chosen content; a closure with `store-of m` resolves names there **before** the registry (`engine.cljc:127-139`). Not a linker question: the pack carries code by design. |
| Streams | attached only by the composition's `attach!`; declined = `:yin.k/unsatisfied {:dao.stream/identity}` (`rows.cljc:2052-2061`) | A pack names identities, it cannot mint handles. |
| Authority and custody | grant, epoch, occurrence (UCF §7.7; `check-custody!` `rows.cljc:2008`) | A fetch that runs **after** the grant holds custody while it waits. |
| Linker budgets | `default-bounds` `{:max-parts 65536 :max-depth 256 :max-bytes 16MB}` (`linker.cljc:1321`); `fetch` has no deadline (`:1958-1960`) | Amplification and starvation surface. |
| Admission media | capacity 1024, evict-oldest (`yin.vm.linker.dht.md:2158-2163`) | Unanswered questions park links forever (`:2076-2086`). |
| Privacy | DHT `get`s for manifests and closures | The names a pack makes R fetch are visible to DHT peers. |

### 1.2 Who is trusted, and for what

| Party | Trusted for | Not trusted for |
|---|---|---|
| S (sender) | Nothing by default. Under exclusive custody, the grantor's record binds `:checkpoint` to the pack address (UCF §7.7, `rows.cljc:1928-1959`). A fork pack is unbound data. | Which code R should install; which names exist; which addresses they resolve to. |
| The carrier stream | Delivery and, when attached with an identity, attribution. Row ids and the pack address are BLAKE3 and re-verified at decode (continuation §7.2 `:1744`). | Authorship: the pack is not signed by S; integrity binds only to an address R must learn out of band (the grant) or not at all (fork). |
| The DHT | Integrity of content-addressed bytes: `:address-mismatch`/`:hash-mismatch` (`linker.cljc:1392-1394`, ingress `:1202-1221`). | Availability, naming, authority. A tuple in an index "never establishes its own authority" (`yin.vm.linker.dht.md:1748-1750`). |
| A module publisher | Only if **declared** (`--dht-principal`) does its assertion enter the fold; only if **trusted** does admission allow it (`:2292-2300`). Safety checks (L-e: profiles, pins, export origin) hold regardless (`:2342-2349`). | Anything when undeclared; code semantics even when trusted. |
| R's composition | Root of trust: `:authority`, `:admission` policy, `attach!`, the drive, the lease judge. | It must not import trust from the pack (names, addresses, derivation policy). |

## 2. Attacker models

| Id | Attacker | Capabilities | Cannot |
|---|---|---|---|
| M1 | Malicious sender | Chooses every row: code vectors, free names, module stores, install children with manifests/derivations/images, pins (option C), custody slots. | Forge a pack address a grant names; forge sealed resource refs (`:forged-resource-reference`, continuation §7.1 step 4); write R's ambient store. |
| M2 | Honest sender, hostile network | Replace or mutate a pack in flight; replay an old pack; delay. | Pass decode with a mutated row (digest refusal); pass custody for an exclusive root without the grant evidence. |
| M3 | Hostile DHT peer | Serve wrong or oversized content; withhold; serve slowly; serve a malformed closure. | Pass step 2/3 (address, identity); exceed `default-bounds`. |
| M4 | Hostile module publisher (declared principal) | Assert any name it likes under its key, including names another publisher uses; republish; equivocate. | Enter the fold undeclared; win an ambiguous name (`:ambiguous-name`, `yin.vm.linker.dht.md:1789-1791`); survive equivocation (`yin.vm.linker.md:2010-2018`); be admitted when untrusted. |
| M5 | Local malicious task | `require` any name; `try-require`; loop. | Write the registry except through install; reach `:resources` or the admission response medium; issue references without the task secret (`engine.cljc:1296-1301`). |

## 3. Attack routes against options A-D

Options: **A** accept (composition prerequisite, today). **B** re-discharge on lower, refuse before attach with `:yin.k/unsatisfied {:yin.k/missing {:obligations …}}`, composition drives `require`, retries. **C** B plus the carrier pins the manifest address each registry-answered name resolved to at S; R satisfies only by equal address. **D** implicit fetch inside `resolve-var`.

Code facts the table relies on (all read):
- Lift `discharge!` answers a namespaced name by **presence** in S's registry, `(some? (module/resolve-module …))` (`rows.cljc:1615-1617`); nothing of the entry travels (continuation §2.5 `:360-366`).
- Lower `check-dependencies!` checks segments, trees, store census, `attaches?`, closure origin/binders, `:prim` presence (`rows.cljc:1898-1925`); **no registry check** (continuation §7.2 `:1827-1830`). `restore-task` keeps `recv`'s `:modules` (`rows.cljc:2095-2099`; pinned at `rows_test.cljc:1168-1169`).
- Runtime `resolve-var` resolves by name through the registry (`engine.cljc:133-139`), else `Unable to resolve symbol` (`:140`); called from the semantic evaluator with `(:modules machine)` (`semantic.cljc:152`).
- The link-pair interpreter serves **by-name requests only** (`interpreter.cljc:483-486`), resolves through R's fold, asks admission after resolve and before load (`:437-462`), loads the **retained** address of the allow (`:412-413`), and `settle` re-checks the manifest's name (`engine.cljc:1540-1548`) and runs 5b against live state (`:1214-1237`).

| # | Route | Precondition | Impact | Mitigated today? (cite) | Residual under A | under B | under C | under D |
|---|---|---|---|---|---|---|---|---|
| R1 | **Name squatting / dependency confusion at R** (M4, M1): R holds or resolves `pym.k` to content other than S's. | A same-named module in R's registry, or a declared+trusted principal asserting the name. | Continuation runs against other code; silent semantic substitution, with the pack's effects authorised under R's streams. | Partly: the fold refuses ambiguity and unauthenticated envelopes (`yin.vm.linker.md:2065-2078`); admission refuses untrusted asserters. **Not** mitigated for a name R already holds: `resolve-var` has no address check; the pin invariant covers only manifest-declared `:yin.module/requires` (`module.cljc:113-142`), never a continuation's free name. | **High**: skew undetected. | **High**: name-present passes the re-discharge. | **Closed**: equal-address check (as `declared-discharge` `:module`, `linker.cljc:1021-1027`; UCF §7.6.4 `:1699-1702`). | High, plus implicit. |
| R2 | **Carrier-driven fetch** (M1): pack names free symbols chosen to make R fetch. | A composition fetch loop. | R issues DHT loads and admission questions for attacker-chosen names; cost, leak, parked links. | No loop exists today. The linker side bounds one fetch (`default-bounds`) and asks admission per name (`:2037-2049`). `:composition` provenance is **not asked** (`interpreter.cljc:447`). | None (no fetch). | **Medium**: loop runs by name; bounded by distinct names in the pack (decode budget) and by R's policy. | **Medium→Low**: a pin lets the loop refuse before fetching when R's own resolution disagrees (one fold, no load). | **High**: every `:var` may fetch; no explicit effect. |
| R3 | **Identity substitution** (M3, M4): wrong bytes for an address, or a manifest whose derivation does not lead to its image. | Hostile content source. | Would install unverified code. | **Yes**: steps 2/3 (`linker.cljc:1392`), manifest name (`:2400-2404`), derivation `:verifying` (`:2164-2216`), `foreign-image` at install (`engine.cljc:1415-1422`), `check-install!` for carried children (`rows.cljc:1853-1858`). | Low | Low | Low | Low |
| R4 | **Carried install child bypasses R's admission** (M1). | Pack carries an `:install` row (semantic kernel accepts; backends refuse `:installs`, continuation §7.2 `:1760`). | After the child halts at R, `link-install` receives the module into R's registry **family** with **S's** `:admission` record copied (`engine.cljc:1436-1443`); later `require`s answer from the registry, never asked. **Registry poisoning via the carrier.** | **No.** `check-install!` verifies self-consistency only; the design says binding to composition-known addresses "remains the linker's fetch/admission responsibility" (continuation §11 Q3 `:2744-2745`) and no one performs it at lower. Only the sealed child secret (`:secret-source`) and L-e checks apply. | **High** (exists today, independent of Q5) | High unless fixed | **Closed if** carried installs pass the same equal-address + R-admission rule (§4.4). | n/a |
| R5 | **TOCTOU between check and fetch/install** (M5, race): registry changes between the refusal and the re-lower. | Another task links the same name meanwhile. | Re-lower sees name present (B) → skew; or install races a concurrent install (`:installs` join, `engine.cljc:1536`). | Lower is pure over a `recv` value and attaches nothing on refusal (attach-count law, continuation §7.2 `:1800-1803`). First link wins for module stores (`engine.cljc:1107-1108`). | n/a | **Medium**: name-present check is race-sensitive. | **Low**: equality against the pin is race-insensitive; the composition re-lowers from the same bytes. | High |
| R6 | **Version skew, same name different code at S and R** (honest both). | Different snapshot sets or principals at S and R. | Wrong behaviour, not detectable at run time. | No. | High | High | **Closed** (refuse `:yin.k/module-pin-mismatch` naming both addresses). | High |
| R7 | **Fetch amplification and cycles** (M1, M3). | Many names; deep closures; mutually requiring modules. | CPU, bytes, parked links. | Per-fetch `default-bounds`; `:parts-limit` (`linker.cljc:1349`); `:require-cycle` on install ancestry (`module.cljc:592-599`); transitive requires are the child's own requests. **No** bound on the number of requires a composition issues, **no** deadline (`fetch` `:1958`; link-policy `:lease` deferred, `yin.repl.link-policy.md:156-160`). | None | **Medium**: needs a per-run budget and lease (§4.3). | Medium, same | High |
| R8 | **Side effect before failure** (A's runtime symptom). | A missing name reached after an effect. | Partial execution under R's authority; under exclusive custody the occurrence is consumed and a terminal/successor edge is owed (`yin.vm.linker.dht.md:4137-4157`). | No: A fails at `engine.cljc:140` at run time. | **High** | **Closed** (refuse before attach). | Closed | Closed only if the fetch succeeds; otherwise a mid-run park with network. |
| R9 | **Information leak through fetch requests** (M3 observers). | Fetch loop over the DHT. | Peers learn which modules R resumes; admission medium learns names too (by design). | DHT `get` is inherent; `:manual` link policy lets the holder decline. | None | **Low-Medium**: composition policy decides; pins allow a local-only check first. | Low-Medium | High (unconsented) |
| R10 | **Registry poisoning** (M1 via R4; M4 via trust). | See R4; or a trusted malicious principal. | Family-wide, lifetime. | For M4: admission is the designed control; L-e safety floor. For M1: **not mitigated** (R4). | High | High | Closed with R4 fix | n/a |
| R11 | **Policy bypass of `:admit`/ShiBi** (composition error, M1-induced). | Loop satisfies a missing name through `:name-env` (provenance `:composition`, never asked, `yin.vm.linker.dht.md:2008`) or a non-DHT source. | Admission skipped for carrier-chosen names. | The seam is sound; the bypass is a composition choice. Nothing today forbids seeding `:name-env` from a pack. | n/a | **Medium**: must be forbidden in the loop contract (§4.2). | Medium, same rule; the pin never substitutes for admission. | High |
| R12 | **Downgrade** (composition error). | Loop links under `:derivation :trusted` (`linker.cljc:2504-2519`) or against a `:fallback` format. | Derivation unverified for carrier-named modules. | `:trusted` is meant for a composition that is its own publisher (`yin.vm.linker.md:1096-1102`). | n/a | Medium | Medium | High |
| R13 | **Replay / substitution of the pack itself** (M2). | Fork packs, or a receiver that does not check the grant's `:checkpoint`. | An old or attacker-built pack lowers. | Exclusive: grant names the pack address (`rows.cljc:1928-1959`). Fork: out of scope of this question; a fork is data the composition chose to run. | Same in all options; a fetch loop must bind its retry to **one** pack address (§4.3). |

## 4. Required mechanisms for a safe policy-gated fetch loop

### 4.1 Who decides

- **The lower decides nothing and fetches nothing.** It reports, before any
  attach, the complete set of unsatisfied module bindings. Pure, no stream, no
  clock (as today for segments, trees, stores, prims).
- **The receiving composition decides**, as holder context of R's registry and
  owner of the link pair, the authority, the admission policy and the drive.
  It may answer "no" (manual policy) and the pack stays refused.
- **The carrier narrows, never widens.** A pin (C) can only make R refuse
  what R would otherwise have accepted by name. It grants nothing: the fetch
  still goes by **name** through R's fold and R's admission, and the installed
  entry's address must then equal the pin.
- **The VM never learns any of this** (I6 `yin.vm.linker.md:196-199`).

### 4.2 The refusal outcome the lower must carry (exact shape)

```clojure
{:yin.k/status    :yin.k/unsatisfied
 :yin.k/discovery :complete                     ; the set below is exhaustive (one pass over all contexts)
 :yin.k/missing   {:yin.k/modules {pym.k :segment/blake3-…    ; C: the address S's registry held
                                   host.mod nil}              ; a name S answered from a host module (no address)
                   :obligations  #{pym.k/body host.mod/f}}}   ; the free names, as today
```

- Keys are plain data, sorted by the carrier's rules; no symbol-to-string
  host collapse (one entry per module name).
- `:yin.k/modules` values: the manifest address (`:address` of the registry
  entry, `module.cljc:92`) for a linked module; `nil` for a host-registered
  module (`register-host-module` entries carry no address). A `nil` pin is
  satisfied by a host module of that name whose export profiles equal those
  the lift recorded (reuse `module/host-export-profile`, `module.cljc:266`),
  else refused. A composition that cannot supply the host module cannot
  fetch it: host modules are composition prerequisites and stay so.
- The composition drives the loop **without trusting names**: it requires
  the key (a name) through its own link pair, the admission question carries
  R's fold's address and asserters, and after `linked` it compares
  `(:address entry)` with the pin. Mismatch is terminal:
  `{:yin.k/status :yin.k/unsatisfied :yin.k/kind :module-pin-mismatch
  :yin.k/name pym.k :yin.k/pinned … :yin.k/linked …}` (same words as
  `:dependency-pin-mismatch`, `engine.cljc:1388-1393`).
- Forbidden in the loop: adding `:name-env` entries from the pack; switching
  `:derivation` to `:trusted`; passing `:fallback` formats the pack named;
  requiring anything not in `:yin.k/modules`.

### 4.3 Budgets, deadlines, atomicity

- **Budget**: at most one `require` per key of `:yin.k/modules`, at most two
  `lower-task` calls per pack address per attempt (refuse, then re-lower).
  Transitive requirements are the install child's own (`yin.vm.linker.md:1788-1797`)
  and stay inside the linker's existing bounds and cycle refusal.
- **Deadline**: a `dao.lease` over the whole fetch run, subject
  `[:yin.k/lower pack-address]` (per-run, link-policy.md §7 Q2), holder the
  composition's drive, renewal on every round that made progress
  (`round`'s `:progress?`, `interpreter.cljc:521-523`), judge composed in the
  process that owns the link pair, reclaim = `abandon`/`retire` of every
  link the run owns (`interpreter/retire` by run descriptor,
  `yin.vm.linker.dht.md:2088-2129`) plus dropping the pack. `:cap` gives the
  hard ceiling. No host clock in the loop; ticks are data (`dao.lease.md:104-112`).
- **Order relative to custody**: fetch **before** proposing for the grant.
  UCF 14.2.3 step 2 already puts "requirements" before the proposal
  (`yin.vm.linker.dht.md:4115-4119`). A stuck fetch therefore holds no
  tenure, mints no occurrence and owes no successor. Only the final
  re-lower runs under the grant, and it is pure and fast.
- **Atomicity**: the lower is a pure function of `(recv, wire)`; the
  composition passes the **same wire bytes** and a `recv` whose registry is
  the value it observed after the installs. Because satisfaction is by equal
  address, a concurrent change to the registry cannot turn a refusal into a
  wrong acceptance; at worst it turns it into another refusal.
- **Idempotence**: a repeated loop over one pack address is a no-op once the
  entries are present (registry answers without a link request).

### 4.4 Carried install children (closes R4)

A pack's `:install` row names a manifest address. The lower must refuse the
child unless the receiving composition **admits that address for that name**,
by the same equal-address rule: the composition resolves the name in its own
fold, asks its admission policy (question = name, address, asserters, as
today), and passes the admitted set to the lower as data
(`opts {:yin.k/admitted {pym.k :segment/…}}`); an unlisted child is
`:yin.k/unsatisfied {:yin.k/missing {:yin.k/modules {pym.k <carried address>}}}`
before any attach. Default empty set = fail closed. The copied `:admission`
record from S (`engine.cljc:1436-1443`) is then evidence of S's decision only
and must be replaced by R's at `link-install` (or dropped).

### 4.5 What the composition must verify, as a checklist

1. Pack address equals the grant's `:checkpoint` (exclusive) or a value it
   chose itself (fork).
2. Every key of `:yin.k/modules` was required through its **own** link pair
   under a DHT or content source it configured; none through `:name-env`
   seeded from the pack.
3. Every resulting entry carries `:admission` from **R's** policy or
   provenance `:composition` chosen by R, and `(:address entry)` equals the pin.
4. The re-lower is called with the same wire and a `recv` built from the
   registry value observed in step 3.
5. The lease was live throughout; a lapse retires the run and the pack is
   not re-lowered until a fresh attempt.

## 5. What must NOT live in the VM or the lower (D confirmed rejected)

- **No fetch in `resolve-var`.** It would make every `:var` instruction an
  implicit network operation (invariants: no implicit control flow, callbacks
  are stream events; I4 no operation waits, `yin.vm.linker.md:179-182`; I6 the
  VM never invokes a loader, `:196-199`). The custody gate stops link
  requests under `:exporting` (`module.cljc:541-543`); a resolve-time fetch
  would have to re-invent that discipline inside the evaluator.
- **No admission context in the VM.** "Who is asking" is composition-owned
  holder context, never a datum (`yin.vm.linker.dht.md:2248-2255`); the VM
  has none to give.
- **No clock, no lease, no budget in the lower.** The lower is pure and
  portable; liveness is `dao.lease`'s and the drive's (`linker.cljc:1958-1960`).
- **No name environment or trust in the carrier.** A pack may pin an address
  (a fact of S's state); it may not assert who may bind a name (a pinned
  address "is never a name claim", `yin.vm.linker.dht.md:1835-1837`).
- **No registry entries in the row table.** Code travels as segments; a
  module's installed slice does not travel (continuation §2.5 `:360-366`
  stands); only the binding fact `name → address` travels.
- **No language, frontend or session key** anywhere in the new shape
  (`yin.vm.linker.dht.md:2302-2316`).

## 6. Verdict on Q5

**C**, implemented as B plus one map, in UCF's already-specified shape.

- **A is unacceptable.** It has the only runtime symptom in the gap list
  (`collab/1793430000000-architect-gap-list.report.md:125`), it fails
  **after** effects under R's authority (R8), and it hides version skew (R1,
  R6) completely.
- **B alone is insufficient.** Refusing before attach fixes R8 but keeps
  name-only satisfaction, so dependency confusion (R1), skew (R6) and the
  TOCTOU on name presence (R5) remain, and a fetch loop driven by names
  gives M1 a free hand to choose what R loads (R2) subject only to R's trust
  set.
- **C's format change is justified.** UCF §7.6.4 already requires
  `:yin.k/modules` keyed by manifest address with equal-address
  satisfaction (`:1697-1702`) and §7.6.5 already lists it in the outcome
  (`:1716-1718`); the row carrier dropped it (continuation §2.5). "Derive,
  don't persist" does not apply: the binding S's registry held is a fact of
  S's state that no query over R's rows can recover. Cost: one map entry per
  registry-answered name in the task row's `requires` slot; the lift already
  resolves the entry (`rows.cljc:1616`) and only has to keep its `:address`.
  Packs re-mint (recorded in `yin.vm.ucf-revisions.md`); no backward
  compatibility is owed.
- **Pin, do not fetch by identity.** By-address link requests are refused by
  the interpreter (`interpreter.cljc:483-486`) and admission has no asserter
  for a bare address. Keep the fetch by name through R's fold and admission;
  use the pin only as the equality the lower and the loop check. This keeps
  "A is the only code identity" intact: the pin is a manifest content address,
  a claim about S's binding, not a new code identity.
- **R4 lands in the same slice.** It is the actual injection route in today's
  code and it is the same check (equal address, R-admitted) applied to
  carried install children.

## 7. Tests and pins (portable, cljc) and documentation

### 7.1 Tests

| # | Pin | Where |
|---|---|---|
| T1 | Lift of harness row 17 (`safepoint_harness_test.cljc:397-409`, `host.mod/f` through the registry) emits `requires {:yin.k/modules {host.mod <address>}}`; a name answered by `E`, the store or a module store emits no entry. | `rows_test.cljc` |
| T2 | Lower into a receiver lacking `host.mod` throws `:yin.k/unsatisfied :yin.k/discovery :complete :yin.k/missing {:yin.k/modules {host.mod <address>} :obligations #{host.mod/f}}` with **zero** `attach!` calls (attach-count law). | `rows_test.cljc` |
| T3 | Lower into a receiver holding `host.mod` at another `:address` throws `:module-pin-mismatch` naming both addresses, zero attaches. Receiver at the equal address lowers. | `rows_test.cljc` |
| T4 | A `nil` pin (host module) is satisfied only by equal export profiles; a same-named host module with another profile refuses. | `rows_test.cljc` |
| T5 | A carried `:install` child whose manifest address is not in `opts :yin.k/admitted` refuses before attach; listed, it lowers; after `link-install` at R the entry's `:admission` is R's, not the carried one. | `rows_test.cljc` + `linker_require_test.cljc` |
| T6 | Loop test with a scripted composition: refusal → one `require` per key → admission medium holds exactly one question per key with R's fold's address → re-lower succeeds from the same bytes. Mutants killed: loop that seeds `:name-env` from the pack (no question appended → test fails); loop that passes `:derivation :trusted` (response `:trust :composition` → test fails). | new `ucf/fetch_loop_test.cljc` |
| T7 | Scripted-tick lease: a loop whose link never answers lapses `:silence` and retires every run-owned request; no grant was proposed; a second attempt is a fresh run. | same, with `dao.lease` fixtures (`lease_test.cljc` style) |
| T8 | Cross-host: the refusal map is `=` and re-encodes byte-identically on JVM, Node, Dart (Stage E row). | `stage_e_rows_test.cljc` |
| T9 | Layering: `yin.vm.engine`, `yin.vm.semantic*`, `yin.vm.ucf.rows` require neither `yin.vm.linker.interpreter` nor `yin.vm.linker.dht` nor `dao.lease` (D stays rejected by construction). | `layering_test.clj` |
| T10 | `resolve-var` unchanged: a name absent from env/store/prims/registry still fails with `Unable to resolve symbol`; no fetch, no park. | `engine` tests |

### 7.2 Documentation

- **continuation §2.5**: replace "Linked registry entries do not travel …
  composition prerequisite" with: the entry does not travel; the **binding
  fact** does, as `:yin.k/modules {name manifest-address|nil}` in
  `requires`; the receiver's registry is kept as given and must satisfy each
  binding by equal address (host modules by export profile).
- **continuation §7.1 step 6**: the registry step records the entry's
  address. **§7.2 dependencies row**: add the module check and the two
  refusal kinds; delete "The lower does not re-discharge free names"
  (`:1827-1830`) and the §7.1 sentence at `:1700-1701`. **§11 Q5**: closed
  as C; **Q3**: amend "remains the linker's responsibility" to the admitted
  set passed to the lower.
- **UCF §7.6.4/7.6.5**: no change in substance; note the row carrier now
  implements it. **§7.11**: add a blocker row "Receiver-side module
  satisfaction and carried-install admission: lower refuses before attach;
  the fetch loop is the composition's, leased, before the grant proposal;
  tests T1-T10". Cross-reference 14.2.3 step 2 for ordering.
- **yin.vm.linker.dht.md 7.5.11**: add that a lowered pack's install child
  enters the family only through R's admission.
- **known-gaps**: one row for R4 (P1 until the slice lands), one for the
  missing per-run lease (P2, reserved `:lease` policy).

THREAT-MODEL-READY
