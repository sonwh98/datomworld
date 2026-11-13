Created-GMT: 2026-11-12 01:33:21 GMT
Created-Local: 2026-11-12 08:33:21 +0700 (+0700)
Coding-Agent: glm
Session-ID: 6e2967a3-3d6a-4604-b3f0-2a61a74bb22f (RESUME — your r3 session)

# Task: PHP R1+R2+R3 r4 — the step-7 FIX-LIST (two blocking findings)

Role: Engineer — resuming your session. sol's step-7 review of your three commits returned
FIX-LIST (NOT MERGE-READY). Read the findings IN THIS WORKTREE:
collab/exception-track/1794446270000-architect-php-r1r2r3-step7.gpt-6.1-sol.findings.md
— it is the spec; both blockers carry firsthand reproductions and required-repair text.

## The fixes (TDD: each regression red-first at this tip, then the fix turns it green)
1. **P1 — close the exported transitive mint**: php/receive-condition (prelude.cljc :2851,
   referenced :2876/:2879/:4891) is exported under the php/ prefix rule (core/prelude.cljc:71)
   and reaches php.rt/mint-retained — sol reproduced the full bypass. Make the
   condition-accepting receiver compiler-private/non-exported (the python pattern:
   non-exported reserved name, reception inside the invocation's callback), update all three
   references, audit the ENTIRE transitive condition-to-mint path (not just "retained"
   names), preserve exact-symbol resolution in bundled/linked forms.
2. **P1 regressions**: an export/projection regression that FAILS at this tip (receive-condition
   exported today) and passes after; a forged-condition regression through the available
   guest-facing module surface; extend the adoption pin — the php.rt/-prefix assertion at
   php_exception_test.clj:372 cannot catch a dangerous helper under a stripped php/ name.
3. **P2 — authenticate handle candidates before local classification**: :2848 accepts any
   non-nil :php/obj when :php/type is absent; :2870 routes it into instance-of's unauthenticated
   cell/get (:4573). {:php/obj 42} (ordinary legal foreign data) causes an operational refusal
   instead of ForeignException retention. Repair: total authenticated reference query for a
   handle candidate + safe validation of local class-lookup state; preserve legitimate
   map-error representation and unchanged local-Throwable identity; NO broad host catch.
4. **P2 regression**: a PHP-handle-looking foreign map boundary test retaining original
   reason, complete evidence, payload identity.

## unchanged rules
Kit-parity pin + all r2/r3 pins stay green; the 18 disclosed lane-blocked assertions stay as
disclosed (signature changes noted); contract test never local; foreground + timeouts; commit
on exception-php-r1r2r3 (do not push); findings to
collab/exception-track/1794447201000-engineer-php-r1r2r3-r4-fixlist.glm-5.3.findings.md.
