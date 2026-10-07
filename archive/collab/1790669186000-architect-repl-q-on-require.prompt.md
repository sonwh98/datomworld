Created-GMT: 2026-09-29 08:06:26 GMT
Created-Local: 2026-09-29 15:06:26 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0ec33-7bb8-7710-b294-bb0533691020 (captured)
# Task: Architect design — yin.repl "q on require": (require 'dao.space.query) binds q over the code index

Role: Lead System Architect

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-29 15:06:26 +07 (+0700) | Status: active | Rationale: OWNER INSTRUCTION (verbatim) "dispatch the q on require slice"; the ruled design names the behaviour but not the mechanism, so an Architect pass precedes implementation (fable reserved by owner)

Read-only; no edits; headless — your final response is the deliverable. Master 3cf3c6de.

Owner ruling to implement (docs/design/yin.repl.dao.space-index.md, "Query surface: owner ruling (2026-09-27)"),
verbatim: "dao.space.query/q is not bound in the session; the user's own (require 'dao.space.query) binds it, running
over the index this observer maintains. Ruled for yin.repl's explicit, minimal shell contract. Binding q at the first
prompt (the IDE posture) was considered and is a composition a host can still make; the shell does not."

Context:
- REPL code indexing landed fd3f0edd: src/cljc/yin/repl/index.cljc (indexer: per program one dao.space transaction of
  ast->datoms facts, :yin/address links, provenance entity in m; publish every round to the shell's dao.jing store via
  transactor/publish!; state keys :manifest-address :content-store; repl-state :index status); wiring in
  src/cljc/yin/repl.cljc (make-session, :index-store, eval rounds). Its query read-back test:
  test/yin/repl/index_test.cljc ~129-147 (query/relation over index/read-datoms, query/current and query/history).
  Gate history: collab/1790598850000-reviewer-repl-code-index-gate*.findings.md.
- How (require ...) works today: it lowers to the session link pair (yin.repl.link, link policy, content source
  yin.repl.link/composition); a require of yin code resolves content-addressed code and can park ("require pending").
  Read yin.repl.cljc around 436, 597-604, 856-1100, src/cljc/yin/repl/link.cljc, test/yin/repl/require_test.cljc,
  docs/design/yin.vm.linker.md, and the owner invariant that code is shared over the stream linker
  (docs/design/datom.world.md; the linker boundary is dao.stream).
- dao.space.query (src/cljc/dao/space/query.cljc, q ~1604) is a HOST Clojure namespace, not yin code.
- Deferred and still OUT of scope unless you argue otherwise: the $ast row relation (structural queries).

Rule, precisely enough to implement without another design round:
1. Mechanism: how does a user's (require 'dao.space.query) bind q in the yin session? Options include: the shell
   intercepting that require name and installing a host primitive; a yin-side wrapper module in the link content
   environment that calls q through an effect / dao.stream.apply FFI request answered by a host interpreter; extra
   primitives supplied at session construction but only made visible by require. Weigh: explicit/minimal shell
   contract, no layer collapse (yin.vm must stay ignorant of dao.space), no callbacks, host boundary as data, the
   "macros are stream topology"/peer-observer principles, CLJ/CLJS/CLJD portability, and consistency with how require
   resolves everything else. Say whether yin.vm must change (it should not, if avoidable).
2. Query input: what is the database q runs over — the live transactor history, the latest published manifest read
   back from dao.jing, or a snapshot per call — and what view (current vs history). Consistency with the indexer's
   gap/lost status (a lost indexer must not silently answer over incomplete data).
3. Surface: exact user-visible forms (e.g. (q '[:find ...]) with the index implied, or (q query db) with a db value),
   result shape as yin values, error shape (portable), and behaviour before any program is indexed.
4. Values across the boundary: query results containing host values (keywords, symbols, content addresses) — how
   they become yin values; limits on result size.
5. Files the implementation touches (flag any outside yin.repl/*, yin.repl.index, their tests), and acceptance tests
   (require binds q; q absent before require; query over an evaluated def returns its facts; provenance via history;
   lost indexer refuses or warns; reset rebinds; CLJ/CLJS/CLJD).
6. OWNER decisions, listed separately.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then answer 1-6 with file:line evidence.
