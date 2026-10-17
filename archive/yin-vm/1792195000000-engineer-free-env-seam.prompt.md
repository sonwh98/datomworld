Created-GMT: 2026-10-12 06:50:00 GMT
Created-Local: 2026-10-12 13:50:00 Asia/Bangkok

# Task: the free-environment retention seam on `yin.vm.semantic-register` (small, JVM-verified)

Worktree `/Users/sto/workspace/datomworld-srvm-freeenv`, branch `srvm-free-env`, from master. Read `docs/design/yin.vm.semantic-register-vm.continuation.md` section 6.3 ("Coordinated retention seam"), and `docs/agents/build-n-test.md` for the commands. Vocabulary: cursor, cursor cell, cursor-ref, cell (UCF section 7.5.3).

Change, in `src/cljc/yin/vm/semantic_register.cljc` only (plus tests):
1. Add an immutable record field `free-env` to `RegisterVM`: the checked `:env` that `create-vm` received (`vm/check-bindings!` result, `{}` when absent), set once in `create-vm`, never written by any transition (engine/step code that does `assoc :env` must not touch it).
2. `gc-roots` lists it under `:values` with the others.
3. Add a public accessor if the code has an existing pattern for kernel fields (follow it; otherwise the record keyword is enough).
4. Check `restore`/`from-checkpoint`/snapshot paths in the same file: a restored or rebuilt VM must keep the same `free-env` (carry it, do not recompute it from the current `:env`). If some path rebuilds the record from a marker (the code near line 481), say in the report what it does with `free-env` and handle it consistently with the design (the free environment is the carried one, never recovered from a current E).
5. Tests in `test/yin/vm/semantic_register/vm_test.cljc` (or a new test ns beside it): (a) after create-vm with `:env {x 1}` the field equals `{x 1}`; (b) after running a program that rebinds x via a lexical binding or `yin/def`, `free-env` is unchanged while `:env` differs; (c) `gc-roots` includes it; (d) create-vm without `:env` gives `{}`; (e) a mutation check: temporarily make a transition write `free-env` and confirm a test fails (revert it).
Do NOT edit `rows.cljc`, `frame.cljc` or anything under `src/cljc/yin/vm/ucf/`; slice 6a lives there and is being reworked.

Verification, all in the foreground (never background a test lane): `clj -M:kondo --lint <changed files>` clean; `clojure -M:test -e :slow -n yin.vm.semantic-register.vm-test` (or the new ns) then the whole `yin.vm.semantic-register.*` namespaces. Do not run Node or Dart; the orchestrator does that. Do not commit; leave the diff and write `collab/1792195000000-engineer-free-env-seam.report.md` with: files changed, what restore paths do with the field, test counts, the mutation result, and anything you were unsure of.
