Created-GMT: 2026-11-10 (real header below)
Coding-Agent: muse
Session-ID: be0a2bad-8788-4d37-8368-be7378d9333c

# Task: cc1's callsite test must compile on cljd — "Can't resolve type Throwable"

Role: Engineer (cross-host portability fix in cc1's test namespace)

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-10 | Status: active
- EXECUTION CONSTRAINT: no ssh; run nothing locally beyond a single compilation check.

## The failure (nightly 20261109-0318 at b1b9abae, shiva dart lane)

  Compiling yang.clojure-callsite-test
  === Can't resolve type Throwable. ===
  (deftest validation-failure-test ... (catch Throwable ...))

The test namespace uses the JVM type java.lang.Throwable, which has no
counterpart on ClojureDart. cc1 was verified on JVM and node only; the dart lane
was never run (orchestrator's gate gap), so this reached master.

## Fix

Make the catch host-portable. Read the repository's existing conventions first:
grep for how other .cljc tests express a host-neutral catch, e.g.
(catch #?(:clj Throwable :cljs :default :cljd Object) e ...) — several
namespaces already do this (yin/vm, repl tests). Use the established idiom, not
a new one. Fix ONLY the test namespace (and only the Throwable/exception-type
occurrences); do not change cc1's production code.

Check for OTHER JVM-only types in that file too (Exception, java.* imports,
clojure.lang.*) and, if any more exist, fix them the same way.

## Verify (report the commands; orchestrator runs them remotely)

- cljd compilation of the namespace (the failing step):
  bb src/dev/cljd_agg.clj --only yang.clojure-callsite-test
  expect "Compilation succeeded".
- JVM: clojure -M:test -n yang.clojure-callsite-test
- node: the compiled bundle selector for the same namespace.
Work in a scratch worktree from origin/master (b1b9abae or later); uncommitted;
report git diff --stat.

Begin the final response exactly with Completed-GMT / Completed-Local.
