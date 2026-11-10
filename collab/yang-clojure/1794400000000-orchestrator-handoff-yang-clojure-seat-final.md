Created-Local: 2026-11-10 +0700
Coding-Agent: interactive (ZCode, yang.clojure orchestrator seat — outgoing)
Status: SEAT HANDOFF — read this first if you are taking over the yang.clojure track

# YANG.CLOJURE ORCHESTRATOR SEAT — HANDOFF

You are the orchestrator for **yang.clojure**: the Clojure frontend of the datom.world platform. It compiles Clojure source to yin.vm UAST. Your job is not to write the compiler — it is to run rounds: brief implementers, verify their work independently, gate it with reviewers, land it, and keep the record straight in `collab/`.

**Read in this order**: this file → `docs/agents/build-n-test.md` (the test rules — you WILL need them) → `docs/design/datom.world.md` (the axioms) → `collab/yang-clojure/` newest-first (the track's record).

---

## 1. WHERE THE TRACK STANDS (verified at handoff, 2026-11-10)

| Item | State |
|---|---|
| **cc1 — the call-site occurrence contract** | **CLOSED.** Landed on master by the QA orchestrator (`b1b9abae`). Owner ruling U1–U3 signed (`368297c3`). Astra + codex both passed the final fixes. This seat's involvement is finished — do not reopen it. |
| **R3 — exception uniformity, Clojure leg** | **NOT YOURS.** Owned by the exception-track orchestrator. See §5 — there is unlanded work and a dangling worktree you will encounter. |
| **Adoption rounds 2–4 + D1** | **IDLE — awaiting owner direction.** The original plan's next step after cc1. Not dispatched, not scoped in detail. **Do not dispatch without the owner's word.** |
| **Master** | `507ef985`, moving constantly (5+ other seats land daily). Always `git fetch` before trusting anything in this file. |

---

## 2. WHAT CC1 IS (so you can defend it, not re-derive it)

The occurrence contract attaches **provenance** to every call site: each application in compiled code carries an origin — either `[:source ...]` (the human wrote it) or `[:expansion record-address]` (a macro generated it). Rows live in the frontend-metadata side table as `[origin root-address path :yang/call-site 1]`.

**The owner's ruling of record** (`collab/yang-clojure/1795310000000-owner-ruling-occurrence-identity-unitarity.md`, SIGNED): an occurrence is **(content address, origin)**. Identity is never minted from structure or process. Distinctness is either **read from retained history** (per-occurrence origins from the expansion record) or **declared by the composition** (admission scopes/identities). Identical content with no declared distinction is one event.

Implementation consequences you should know, because they will bite you if you touch `macro.cljc`:
- Construction origins thread via **content-invisible metadata** (`::origin-of`), attached at construction, dropped at a single choke point (`recover-rebuilt-packet`) on rebuilt packets, and they **never** reach CBOR/hashing.
- Two reviewers independently found and QA fixed two holes in that carrier: rebuilt-packet metadata loss and literal-packet metadata reaching the hasher. Both fixes are `f440a2fb` and `ba2deb73`.
- **Corollary:** any future change to packet construction in `macro.cljc` must preserve those two invariants. This is the single most fragile surface in the track.

Key artifacts: `test/yang/clojure_callsite_test.cljc` (the battery, ~18 tests), `collab/yang-clojure/slice9_site_tables.edn` + `slice9_gate.md` (the versioned attribution contract), and the design `1794670000000-architect-callsite-generalization-design.gpt-6-astra.findings.md`.

---

## 3. HOW THIS SEAT WORKS (the disciplines that made it work — keep them)

1. **You do not trust a delegate's report.** Every claim is re-derived: git state over prose, test output over summary. The QA orchestrator re-derived my cc1 landing from git and caught that my `exception-r3` branch already existed. Do the same.
2. **Independent review gates before landing.** Architect review (astra) then codex gate, each given file:line findings to check, each told not to modify files. Two reviewers converged on the same cc1 defects — that convergence is what made the finding credible. **Never land on one reviewer's say-so.**
3. **One writer per surface.** `yang.clojure.cljc` and `prelude.cljc` are contested. Check `git worktree list` and `git branch -a` before creating a branch — someone may already be there (this is exactly how the R3 confusion happened).
4. **The shared checkout `/Users/sto/workspace/datomworld` is not yours.** Other seats check out their branches there constantly. **Before any commit: `git branch --show-current`.** If it is not master, create a temp worktree and commit there:
   ```sh
   git worktree add --detach /tmp/dw-x origin/master
   # copy your files in, commit, push origin HEAD:master, then worktree remove --force
   ```
   This bit me twice. Do not repeat it.
5. **Test boundary is non-negotiable** (owner ruling 2026-11-04): individual tests local; **lane sets, changed sets, slow tier, nightly → shiva/jupiter**. Laptop is 8 cores shared with the owner.
6. **Remote runs**: push the branch → scratch clone on the host → `git remote get-url origin` must be GitHub → `git fetch` → `checkout --detach` → **`git merge-base --is-ancestor <pushed-hash> HEAD`** → `mise trust` → `eval "$(mise env -s bash)"` → run. Note: **`bb` is not on the laptop PATH** — use `clojure -M:test -n <ns>` locally.
7. **Kill by exact PID only**, after confirming cwd/parent (`readlink /proc/<pid>/cwd`). `pkill -f` over SSH once killed the session doing the killing.
8. **Attribute reds before blaming your branch.** The rule that saved cc1: run the failing test on a **clean master clone**. Both cc1-era reds (`dao.host.gate-test` census drift; `yin.repl` StackOverflow on shiva) failed *worse* or identically on master. Only then does "not mine" become a defensible claim.
9. **Append-only `collab/`.** Never edit an existing note; write a new timestamped one. Prefix `Created-Local`/`Coding-Agent`. File round records in `collab/yang-clojure/`, cross-track notices in `collab/<track>/`.

---

## 4. CURRENT GOTCHAS (measured, not guessed)

- **`dt`-style serial lanes are a trap.** A single serial JVM full-suite run takes 30–60 min on shiva; `bb test:quick-sharded --workers 12` is the right shape (and even then, `yang.python` ~1450s and `yin.repl` ~1760s dominate the critical path — the historical "5 minutes" was a cold clean-host measurement).
- **Wide vs narrow routing**: `bb test:changed` on a change touching `yin/vm/macro.cljc` routes **wide** (353/353 namespaces) because the reverse-require closure of the expansion engine is the whole tree. That is correct, not a bug — and it's why cc1's gate needed the full quick lane.
- **Known pre-existing master reds** (do not chase as yours): the H3 gate-census has been re-recorded (`d454378b`) — if it goes red again, it's the census seat's; `yin.repl.shell-session-test` had a host-sensitive StackOverflow on shiva (fixed by the php prelude balance `f4df9893` — verify before assuming).
- **Node lane at `847aa885` showed 7 failures / 4688 tests, UNATTRIBUTED.** Never baselined against current master. If you run Node for an adoption round, baseline it first.
- **The email/laptop-load note** (`collab/yang-clojure/1794217976000-orchestrator-note-laptop-load-r3.md`): multiple seats ran suite-scale work locally and pushed laptop load to 6–22.8. Do not be the next one.

---

## 5. THE R3 SITUATION — READ BEFORE TOUCHING `exception-r3`

R3 (protocol failures retain and forward the complete condition) is **the exception orchestrator's round**, not this seat's. This seat stood down and filed `collab/exception-track/1795500000000-yangclojure-stood-down-from-r3.md`. **Do not dispatch R3.**

But you will find unlanded work, and it is load-bearing context:

- **`origin/exception-r3-impl` @ `69e7f666`** — `fix(yang.clojure): protocol conditions retain and forward unchanged through the public escape (R3)`, +405/−246 over `yang.clojure.cljc` and `yang/clojure_test.clj`. **Not merged to master.**
- **`/Users/sto/workspace/datomworld-r3`** (branch `exception-r3`, at `b1b9abae`) — has **uncommitted modifications** to `src/cljc/yang/clojure.cljc` and `test/yang/clojure_test.clj`. Someone's in-progress work.
- **`/Users/sto/workspace/datomworld-r3-orch`** (detached at `69e7f666`) — the exception orchestrator's verification worktree; the last delegate run there was **interrupted** (`error: interrupted`, 10936s, while running `clj -M:test -n yang.clojure-test`).
- The acceptance ledger (`collab/exception-track/acceptance-ledger.md`) still shows the `:clojure/protocol-*` rows **red** — R3 has not closed them.

**Your action**: leave all three alone. If the owner asks you to take R3, require an explicit hand-off naming the branch, whether commits exist on it, and the base — then use the repair notice `collab/yang-clojure/1794213768000-orchestrator-exception-r3-brief-repair.md` (it re-pins every site against post-cc1 master; the older brief `1795040000000` has stale line numbers).

---

## 6. THE NEXT WORK WHEN THE OWNER SAYS GO: ADOPTION ROUNDS 2–4 + D1

The original plan after cc1. Not yet scoped in detail — the short version:

- **Adoption rounds** extend the C1 call-site attribution across the remaining yang.clojure surfaces (the three-kind correspondence: ambient literals, context-API carriers, delegation exclusion). Round 1 shipped with cc1; rounds 2–4 remain.
- **The cross-host occurrence-key equality gate** is part of each frontend's own acceptance (ruled with the php/ts seat): the same source on different hosts → identical coordinates; never cross-frontend.
- **D1** is the durable store for occurrence records (deliberately out of cc1's scope).
- Before dispatching: read `collab/yang-clojure/1793980320000-orchestrator-handoff-yang-clojure-seat-to-zcode.md` and its addendum for the earlier plan, and the architect's generalization design §2.1–2.3.

---

## 7. WHO ELSE IS IN THE BUILD

- **qa orchestrator** — took over cc1's landing; owns landing verification broadly.
- **exception-track orchestrator** — owns the exception-uniformity track and R3.
- **yang.php / yang.typescript orchestrator** — owns `prelude.cljc` and the entry bridge; their B2 → JS R1+R3 → PHP R1+R2+R3 pipeline runs in that file. Coordinate before touching `prelude.cljc`.
- **master-green orchestrator** — nightly, master integrity, cross-attribution of load/reds.
- **yin.repl / dao.stream seats** — foundation; their reds are never yours to fix, only to relay with evidence.

Relay through `collab/` notes, not through assumptions. The owner distributes them.

---

## 8. FIRST FIVE ACTIONS

1. `git fetch origin && git log --oneline origin/master -5` — master has moved since this file.
2. Read `docs/agents/build-n-test.md` fully, especially the Gate Ladder, the test boundary ruling, and the remote-clone ritual.
3. `ls -lt collab/yang-clojure/ | head -20` — the newest notes are the live state; this handoff is a snapshot of a moving track.
4. Check `git worktree list` and `git branch -a` before creating anything — half the traps in this track were collisions with work already in progress.
5. Ask the owner what to do next: the track has **no dispatched work**. Adoption rounds 2–4 + D1 are the queue, but they are the owner's call, not yours.

— outgoing yang.clojure orchestrator seat
