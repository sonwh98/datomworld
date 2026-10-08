Created-GMT: 2026-09-21 20:26:36 GMT
Created-Local: 2026-09-22 03:26:36 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8
# Task: jing-cbor-j0 — freeze the DaoJing canonical-CBOR encoding contract as fixtures (reassigned to claude-opus-5)
Role: DaoSpace & DaoJing Storage Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-22 03:19:25 +07 | Status: active | Rationale: initial assignment (collab/1790021965607-storage-engineer-jing-cbor-j0.prompt.md)
- Status-Event: 2026-09-22 03:26:36 +07 | Model: glm-5.3 | Status: reassigned | Rationale: owner asked to spend the expiring Claude pool (resets 2026-09-22 04:00 +07) on implementation; glm was stopped after about 7 minutes with no files changed
- Model: claude-opus-5 | Assigned: 2026-09-22 03:26:36 +07 | Status: active | Rationale: owner-directed reroute; opus-5 is the team's codec/AST specialist and the pool is expiring

You are a fresh implementer with no prior context. Work ONLY in the git worktree
/Users/sto/workspace/worktree-jing-cbor (branch jing-cbor, HEAD 0dc06197), which is
your launch directory. Do NOT stage, commit, merge, or push.

## Your task specification

The full specification is in
collab/1790021965607-storage-engineer-jing-cbor-j0.prompt.md (inside this worktree).
READ IT IN FULL FIRST and do everything it says: the deliverables (the JSON fixture
corpus, the README with an AMBIGUITIES section, the independent Python generator with
an `--inspect` reader, and the `dao.jing.cbor-fixtures` namespace with green
structural self-tests), the file box (NEW files only; no production source, deps.edn
or pubspec.yaml changes), the coverage list, and the reading list. It was written for
another model: IGNORE its "Environment" section and its verification list where they
differ from the notes below.

## Notes specific to your headless session (verified in earlier rounds)

- Your `acceptEdits` session auto-approves file edits but can DENY Bash commands.
  Kondo, cljstyle, `env`/PATH exports, `$HOME` expansion, the Java-17 lane and the
  CLJD lane are denied to you: skip them, do NOT retry them, and say so in the
  report. The orchestrator runs them. Run one simple command per step; a denial means
  split the command or skip, never a workaround, never `--dangerously-skip-permissions`.
- The default PATH gives you Java 21 and the mise clojure and bb. Focused JVM run:
  `clojure -M:test -n dao.jing.cbor-fixtures-test` (use the namespace your test file
  actually defines; check how the runner discovers test namespaces, e.g. `-n`).
  The CLJS lane, if you run it: `bb test:cljs` (it needs Java 21, which you have).
- Running the generator needs `python3`; if Bash denies it, say exactly which command
  was denied and stop retrying: the orchestrator will run the generator and check
  reproducibility. Write the script so it is correct without you being able to run
  it, and be explicit in the report about what you did NOT run.
- Never report a count or a "reproduced byte for byte" result you did not observe.
- Reader conditionals: put `:cljd` FIRST (`#?(:cljd nil :clj ... :cljs ...)`); a
  bare `#?(:clj ...)` does not exclude code from the ClojureDart build.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8
Then, exactly as the specification asks: files created; case count per coverage
category; the DSL in one paragraph; the AMBIGUITIES list; what you ran with exact
counts; what you could not run and why; every deviation. Facts only; promise nothing.
