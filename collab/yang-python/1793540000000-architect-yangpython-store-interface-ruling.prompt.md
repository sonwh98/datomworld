Created-GMT: 2026-11-01 10:35:00 GMT
Created-Local: 2026-11-01 17:35:00 +07 (+0700)
Coding-Agent: kimi (read-only agent bound via --agent-file)

# Task: ruling — may yang.python's publication hold a dao.jing store handle, or must it go through dao.jing.content / dao.jing.stream?

Role: Lead System Architect. READ-ONLY. The deliverable is a ruling, not code and not a plan to code.

## The question (the owner raised it)

The owner's words: "the interface from yang.python with the dht is indirect, right? it should be via
dao.stream."

Established facts (verified by the orchestrator; re-derive if you doubt them):

1. **The guest-facing import path IS indirect.** Design §8.5.6 (catchable imports, ruling 8 / I4-a):
   a guest `import` passes a delivery thunk to `py/import`; on a `sys.modules` miss the thunk runs the
   bare VM primitive `try-require`, and the LINKER resolves. Guest code never touches a store.

2. **The host-side publication path is direct.**
   - `src/cljc/yang/python/antlr/publication.cljc:13` requires `[dao.jing :as jing]`, plus
     `yin.vm.linker.publish` and `yin.vm.linker.sign`.
   - `src/cljc/yang/python/antlr/siteset.cljc:32` likewise requires `dao.jing`.
   - Both take a raw store `handle` (a dao.jing content store) and publish Python units into it.

3. **The design names a content-service ingress.** `docs/design/yang.antlr.md:118-121`: "Content puts
   and fetched bytes pass the shared `dao.jing/accept-bytes!` ingress check **through
   `dao.jing.content`**; transport does not validate." And `:2430` names `dao.jing.stream` as the
   adapter for the remote form ("canonical Jing bytes through the existing `dao.jing.stream` adapter
   are the only remote form").

4. **Both adapters exist**: `src/cljc/dao/jing/content.cljc`, `src/cljc/dao/jing/stream.cljc`.

5. **§8.10.1 (:3534-3538)** says "Portable I/O and cross-task interaction continue through
   `dao.stream`" — stated in the calling-convention section, i.e. about guest I/O, not explicitly
   about host-side publication plumbing.

## The ruling requested (answer each)

A. **Is the direct `dao.jing` handle in the host-side publication helper PERMITTED, or a layering
   violation?** Distinguish: (i) local same-process publication during a test/REPL, versus (ii)
   publication into a remote/DHT-backed store. Does the answer differ between them?

B. **If it must route through the content service or stream adapter**, what is the correct seam —
   `dao.jing.content`, `dao.jing.stream`, or something above both — and what does the Python
   publication helper's signature become? Is the current handle-taking shape salvageable as a local
   fast path with the content service as the remote path, or must there be one path only?

C. **Does the answer change for the site-set publication** (`siteset.cljc`), which publishes
   hook-site records beside code images under the same handle?

D. **Sequencing**: if a change is required, is it (i) a prerequisite for the current link-red fixes,
   (ii) a follow-up slice, or (iii) advisory only? The in-flight round is adding the `yang.call`
   runtime module to the published set through the SAME handle — it does not change the layering
   either way.

## Constraints

- Read-only: no file writes, no test runs, no commits.
- Scope: the yang.python publication/import path and the dao.jing/dao.stream boundary. Do not
  re-litigate the calling convention (§8.10) or the site-set design (§8.5.6 ruling 14).
- Cite file:line for every claim. If the design does not settle the question, say so explicitly and
  state what the owner must decide — an honest "undecided" is a valid ruling outcome.

Begin the final response with Completed-GMT / Completed-Local / Coding-Agent / Session-ID (use
provider completion metadata; say so explicitly if unavailable). End with exactly one line:
"Ruling: PERMITTED AS IS" | "Ruling: MUST CHANGE (with the seam named)" | "Ruling: OWNER DECIDES".
