Created-GMT: 2026-10-16 21:10:00 GMT
Created-Local: 2026-10-17 04:10:00 +07 (+0700)
Coding-Agent: claude-cloud
Session-ID: 93cda4c1-ec00-4fd9-8fe6-1d63c63eac89 (resumed; round 4)

# Task: Revise both designs — round 4 (the codex fresh-thread review: the copy/remove asymmetry + 2 more P1s + 1 P2)

Role: Lead System Architect

The codex round-3 review: /Users/sto/workspace/datomworld/collab/1792216000000-reviewer-ts-php-r3.gpt-6.1-sol.findings.md
— read whole. Progress is real (the original unset fixture now yields 1; SCC commitment fixed; the
owning-root table fixed the alias-root mistake), but the residue:
1. P1: PHP releases holders that copies never acquired — copying $a (holder count 1->2 via $b = $a),
   then unset($b[0]) unconditionally releases a holder (2->1), so $a[0] = 2 demotes and $r prints 1 where
   PHP prints 2. The rules are asymmetric across copy/remove/demote.
2. P1: Dynamic import cannot use the static cycle shortcut.
3. P1: Late-claim rechecking lacks evidence replay.
4. P2: T10's guest-call arity fixture still exceeds the generic T1 checker contract.

INSTRUCTION — and this round's discipline: stop patching corners one fixture at a time. Produce the
EXHAUSTIVE specification of each lifecycle instead:
1. PHP references: the complete holder-count transition table — every operation (bind, copy array,
   copy ref, unset element, unset variable, rebind, write through, scope exit) x every state — with the
   fixture set enumerating each transition row. The table must reproduce PHP 8.3's zend behavior for the
   shared/committed/copied/demoted cycle in every order.
2. ES instantiation + dynamic import: the full operation matrix for static imports, dynamic import
   (import() at arbitrary points), and cycle members — the phases, what is committed when, and why the
   dynamic path composes with the static rules.
3. Claims: the late-rechecking evidence-replay rule (what evidence, replayed when, by whom).
Re-weave into the designs. Note what round 4's reviewer should probe.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
