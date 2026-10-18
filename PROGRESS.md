# MineAgentX fork preparation

## Authorized scope

- Base: bingdongni/MineAgent, commit `701250a950154667212d3ae1be3b3197e9ce9470`.
- Checkout identity: MineAgentX.
- Local branch is `master`, tracking `origin/master`. The user-authorized `ours` merge preserved both histories without importing the older remote codebase. The newer NeoForge tree and unfinished work were retained. Privacy cleanup rewrote the affected local commits; the sanitized privacy commit has since been confirmed on the remote. Publication remains user-owned.
- `origin` now points to `https://github.com/Xalalau/MineAgentX.git`; stale branch tracking removed. Latest user instruction: prepare incremental local commits only; the user will push. Do not push, restart authentication, or force-push. Reconciliation preserves the existing remote history as an ancestor without importing the old 1.20.1 project content.
- Keep Minecraft 1.21.1, support only NeoForge, remove Fabric while preserving relevant regression tests.
- Keep only local inference: native Ollama `/api/chat` or the documented Bonsai/MTP OpenAI-compatible endpoint (`http://127.0.0.1:8899/v1`, model `swift-bonsai2-27b-katana-mtp`). Do not inspect or modify the personal host/launcher.
- Translate project content to English; preserve behavior and upstream attribution. The user explicitly authorized removing personal paths/names and private-repository references from files and history. Do not reintroduce them in documentation or commit messages.
- Add complete English `CAPABILITIES.md`, with an English rendering of the previous assistant checkpoint as its opening and local inference only in section 6.
- Exercise tests/build, inspect packaged JAR, and commit locally. Do not claim gameplay or real Bonsai inference without testing.

## Initial observations

- Initial worktree clean. No Java or GitHub CLI on PATH.
- The public destination initially described a different older project; its history was later reconciled without importing its source tree.
- At initial inspection, regression tests were under `fabric/src/test/java`; applicable tests have since been migrated to NeoForge.
- Initial Chinese content included documentation, locale data, comments, prompts, memory/personality labels and tests. The current English-content guard passes; translated executable labels still require final integrated regression validation.

## Privacy cleanup

- Removed personal machine paths/names and all references to private repositories from current project text. Removed the unpublished comparison report.
- Rewrote all affected local history while preserving merge topology and upstream attribution. Final verification scanned every locally stored Git object, including unreachable objects; no targeted privacy matches remained. Obsolete recovery objects and cleanup tooling were removed after restoring and verifying the unfinished work.
- Preserved all 352 working-file checksums/deletions across the rewrite; unrelated implementation work remains uncommitted and intact.
- Added a personal-path publication guard and exercised it RED/GREEN. All three repository checks pass.
- Remote inspection with `git ls-remote` now confirms sanitized privacy commit `995face814496a30b3fafab67cfc0a0614c800f7` on `master`. The assistant performed no push. Previously exposed GitHub commit pages, caches, and other clones have not been verified purged. Do not merge contaminated history back in.

## Repository simplification

- Preserve the client UI design-system document. Its repository-relative link and read-before-UI-edit guidance are now in `AGENTS.md`; do not remove it as part of documentation cleanup.

- Removed contribution documentation and the entire GitHub configuration directory at the user's request, including issue/PR templates, release notes, and automatic build/release workflows. Updated the agent guide accordingly.
- Removed the inherited changelog at the user's request. Release history belongs in release notes rather than a separate tracked changelog.
- Local Gradle builds, regression tests, repository checks, license, and upstream attribution are retained. Removing GitHub automation does not remove the requirement to verify changes locally.

## Work in progress

- User requested a maintained project guide: `AGENTS.md` now maps source files, design intent, supported local protocols, validation, Git ownership, and parallel-agent coordination. Read it on resume and include it in future delegation context; update it in the same change when the project structure or supported behavior changes.

- Documentation now includes the complete 40-tool catalog in `CAPABILITIES.md`, with the preceding checkpoint rendered in English and a local-only section 6. English README replaces the duplicated language-specific READMEs. Contribution guidance and GitHub-specific templates/automation were subsequently removed at the user's request; local Gradle and repository-check commands remain available. The standalone security policy was removed at the user's request, along with its links, in local commit `ee235ac`.
- `scripts/check_project.py` was exercised RED: missing capabilities and Chinese text were detected. Its tool-catalog coverage check now passes.
- Verified Temurin JDK 21 archive checksum `ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94`; local JAVA_HOME is `.tools/jdk-21.0.12.1+1` (ignored). Gradle 8.10.2 wrapper executed successfully with that JDK.
- Delegated source edits have returned: local providers/connections, NeoForge build/test migration, English memory/personality sources, and English AgentLoop prompts/runtime tags. Build migration is already committed; provider/translation edits and new regression tests remain uncommitted. The coordinator owns final integration, verification, and incremental commits.
- User clarified that local inference means **Ollama or Bonsai**, not Bonsai exclusively. Provider and review agents were steered to support both local protocols and remove only cloud integrations.
- The initial capability/scope documentation was committed locally using the assistant's explicit identity `Hermes Agent <hermes@localhost>`, not an inferred user identity.
- Gradle `:neoforge:dependencies --configuration compileClasspath` exited 0. This resolves dependency metadata only; it is not a compile/test/build result. Its task-owned log is `build-dependencies.log` (ignored).
- Earlier HTTPS push dry-run was blocked by missing authentication. The device login process `proc_3fded90ce158` was stopped after the user took over pushing. No push occurred. Do not restart authentication.
- English/CJK guard and tool-documentation coverage both passed. Interim full Gradle tests/build (`proc_7115e3f87c1e`, log `build-verification.log`) exited 0: 23 JUnit suites, 98 tests, zero failures/errors/skips. This snapshot preceded completion of the local-provider changes; rerun before final acceptance.
- NeoForge-only build reviewed: removed Fabric sources/build/plugin repositories, migrated relevant JUnit regressions, tightened Minecraft version range, updated fork metadata and CI, and preserved attribution. Cloud-specific test suites are removed with their unsupported provider implementations; Ollama coverage is retained/replaced by dedicated local-provider tests.
- Latest verification for the documentation cleanup: all three `scripts/check_project.py` checks and `git diff --check` pass. No new Java test/build run was performed for these documentation-only changes.
- The standard `neoforge/build/test-results/test/TEST-*.xml` location currently has no reports. Treat the earlier 98-test result as historical evidence, not validation of the current unfinished source tree.
- Next: review local-provider integration and cross-layer request deadlines; run the complete NeoForge tests/build after all source edits; inspect JUnit XML and the packaged JAR; commit the remaining ready changes incrementally. Gameplay and live-model behavior remain unverified. Push is user-owned and not part of the remaining work.
