Created-GMT: 2026-10-06 09:11:56 GMT
Created-Local: 2026-10-06 16:11:56 +07
Coding-Agent: claude
Session-ID: 8b4bda87-5e50-47a0-b0c9-391dba6d3ca7

# Task: head-h3 (round 4: a JVM lane failure and the last small items)

Role: REPL and Host Integration Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-06 14:13:43 +07 | Status: active | Rationale: same engineer, resumed for the last round before the lanes run again

The Architect SIGNED OFF your round-3 tree
(`collab/1791277458048-architect-head-h3-r3.claude-fable-5-1.stdout.log`). The reviewer's
round 3 resolved the cleanup-masking P2 and left two items
(`collab/1791277458048-reviewer-head-h3-r3.gpt-6.1-sol.findings.md`, untrusted). The full
`bb test` then failed in the JVM lane (1 failure; the Node and Dart lanes never ran
because of it).

## 1. The failing test: `yin.vm.store-write-audit-test`

`every-store-mutation-is-on-the-allowlist` fails: the audit (Rule R, the store-key
invariant; read the namespace docstring of `test/yin/vm/store_write_audit_test.clj`) flags
a new site `"src/cljc/yin/repl/dht.cljc" {["publisher" :map] 1}`: "every map literal with
a `:store` key" counts as a store mutation. The `publisher` map (about `dht.cljc:297-310`)
has `:store base`, where `base` is the DHT node's local store, NOT a program store.

Fix: RENAME that key so the audit has nothing to flag (for example `:node` or
`:dht-store`, whichever reads best; update every reader of `(:store publisher-map)` in
`dht.cljc`, `query.cljc`, `main.cljc`, tests). Do NOT add an allowlist entry and do NOT
touch the audit test. Then run `clj -M:test -n yin.vm.store-write-audit-test` (it must
pass with the allowlist unchanged) and, because the 13-namespace subset you used missed
this gate, run the WHOLE JVM lane in the foreground: `bb test:clj` (read
`docs/agents/build-n-test.md`; it can take many minutes; the report must give its final
test and assertion counts and 0 failures, 0 errors). If anything else fails, find out
whether H3 caused it (compare against `git stash`-free evidence: read the failing test,
do not use git commands) and fix it.

## 2. Reviewer P3: BOM handling differs across hosts

Dart and Node discard a leading UTF-8 BOM when decoding `heads.edn`; the JVM keeps U+FEFF
so a BOM-prefixed record refuses EDN parsing. Choose ONE policy for all hosts and test it
on all three (portable test with explicit bytes). The recommended policy: a leading UTF-8
BOM is ignored (strip a leading U+FEFF after the JVM decode), matching what Node and Dart
already do; but if you find Dart or Node do not actually discard it, say so and pick the
policy that needs the least host-specific code. Keep the strictness for everything else
(invalid bytes, overlong, surrogates).

## 3. Reviewer P2 (remaining): a regular-file/FIFO swap race on the JVM (and Dart)

The Architect RULED this acceptable and out of scope: reproducing it needs write access to
the node's own locked store directory, someone who can already replace HEAD, and the
worst outcome is a blocked startup, not a wrong head. Do NOT change the open strategy.
DO record the accepted limit in a short comment at the JVM/Dart check-then-open in
`read-bounded` (what is and is not covered, and why accepted), and say in your report that
the reviewer's concrete fix (non-blocking open then validate the opened resource, as Node
does) was deliberately not applied.

## 4. Architect comment request

The comment at about `dht.cljc:831` (the `{}` start of a link) must name the mechanism:
on ClojureDart a direct `assoc` with two or more keyword pairs is inlined to `-conj`, and
`-conj` on `nil` conses a list; the JVM and Node produce a map.

## Rules

Same files as before plus whatever the key rename touches (list them in the report). No
git, no formatter, no `clj -M:test -e`, no background processes beyond the process test's
own children, nothing under `docs/design/*`. The focused Dart runs and
`bb build:yin-repl-peer` are allowed as in round 3, in the foreground, for the changed
namespaces (the BOM test is portable: run it on Dart). Do not run the full `bb test`.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 8b4bda87-5e50-47a0-b0c9-391dba6d3ca7

Then report, per item: what changed, the test that pins it; the exact commands and
outcomes with counts (`bb test:clj` final counts, `yin.vm.store-write-audit-test`,
`yin.repl.dht-process-test`, the focused Dart run, `bb build:yin-repl-peer`, kondo on
the touched files). Do not claim edits or tests that did not occur.
