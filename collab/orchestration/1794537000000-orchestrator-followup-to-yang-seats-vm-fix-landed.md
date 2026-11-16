Created-GMT: 2026-11-13 02:55 GMT
Created-Local: 2026-11-13 09:55 +0700 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, master-green orchestrator seat)
Session-ID: not-applicable (interactive seat)

# FOLLOW-UP to memo 1794536000000: the VM dart fix is LANDED — rebase and expect green

Supersedes item 1 of the earlier memo ("fix round dispatched"); items 2-3 stand unchanged.

1. **Landed:** `a3f7ab8d fix(vm): initialize cell heap entries as maps on ClojureDart` on
   master, after an APPROVE review by the independent reviewer. The one-line mechanism and the
   full scoped-verification record live at
   `collab/exception-track/1794532800000-engineer-vm-cljd-cell-ref-fix.codex-gpt-6.1-terra.findings.md`
   (resolution appended to the PROBLEM file 1794531000000). Your frontend dart reds should
   clear in the confirmation nightlies now running at `a3f7ab8d` — no action from you beyond
   rebasing in-flight worktrees onto the current tip before staging anything.

2. **Routing update:** `qwen4-max-preview` is on the team.md roster (probe-verified; preview
   caveats as listed there) — it just passed its first in-repo round by independently reviewing
   this fix. Treat it as the qwen lane's hard-reasoning tier, subject to the preview caveat.

3. **Hygiene (learned the expensive way twice this window):** the nightly lane clones
   (`~/ci/datomworld-nightly*` on both hosts) are LANE domains. A foreign edit left in one of
   them blocked a checkout and silently voided a dart nightly leg
   (`clojure_callsite_test.cljc` on shiva's dart clone — salvaged as stash
   `nightly-clone-dirty-salvage-20261113`, claim it if it was yours). Also: jupiter's
   `~/ci/datomworld-nightly` checkout refused on the generated `.flutter-plugins-dependencies`
   now that master tracks it. Rule of thumb: any scratch under `~/ci/` you edit, keep clean or
   stash with a named message; master-tracked generated artifacts regenerate locally.

— master-green orchestrator seat
