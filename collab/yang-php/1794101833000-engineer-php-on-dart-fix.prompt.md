Created-GMT: 2026-11-08 01:5x GMT
Coding-Agent: muse
Session-ID: 3f9c803f-e92a-466e-990e-d40934d54677

# Task: the php-frontend dart reds — diagnose and fix within the php/dart test surface

Role: DaoStream and Distributed Protocol Engineer

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-08 | Status: active | Rationale: dart-lane boundary-neutral reds; own scratch clone (collision rule)

## Known failures (from the branch dart run @ 4f772d61, names captured; nightly 20261106-0944 had 7 dart failures total)

- yang.typescript.antlr.source-call-test/source-calls-php-function-test
- yang.typescript.antlr.source-foreign-store-and-callback-test
- yang.php.antlr.lower-portable-test/portable-fixtures-test
- dht write-bound test = SEAM, ledgered, NOT yours.

## Facts

- Node's php red was fixed by 28bdc3d0: build/antlr/php/js added to deps.edn :cljs
  alias + cst.cljc — the ANTLR **JavaScript** runtime. Dart/cljd cannot use the JS
  runtime; whether the php frontend can work on dart at all depends on what the
  lexer/parser compile to. Diagnose what the dart reds actually assert.
- master is now 001b8844 (merge landed). Work on a FRESH scratch clone on jupiter:
  git clone from ~/ci/datomworld-nightly-dart (or GitHub origin), checkout --detach
  origin/master, verify 001b8844. THIS CLONE IS YOURS ALONE tonight.

## Your job

1. On jupiter, set up your scratch dart clone; run the three failing namespaces
   scoped: bb src/dev/cljd_agg.clj --only yang.typescript.antlr.source-call-test,yang.typescript.antlr.source-foreign-store-and-callback-test,yang.php.antlr.lower-portable-test
   (eval mise env; DATOM_SLOW_TESTS=1; --reporter expanded is in the script).
   Capture exact failure signatures.
2. Diagnose: are these (a) missing php runtime on dart (design gap — php not
   yet portable to dart), (b) test-side gap (the tests should skip/refuse
   cleanly on :cljd like other host-gated tests), or (c) genuine bugs?
3. Implement ONLY (b)/(c) fixes contained to test/yang/php/, test/yang/typescript/,
   or src/cljc/yang/php/ (if (a), STOP and report — the owner rules on porting
   ANTLR runtimes). Verify scoped with the SAME --only selectors green.
4. No commits; report diff --stat + outputs verbatim.

Begin the final response exactly with Completed-GMT / Completed-Local.
